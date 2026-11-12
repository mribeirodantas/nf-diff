/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.seqera.nf.diff

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.Kind
import io.seqera.nf.diff.DiffResult.OutputFileDiff

/**
 * Compares a single pair of files by path — size first, then a streamed
 * SHA-256 when the sizes match — and, for a changed text file, computes a
 * bounded line-level diff. This is the content-comparison engine shared by the
 * two file-tree layers:
 *
 * <ul>
 *   <li>{@link OutputComparator} — the per-task work-directory outputs
 *       ({@code --diff-outputs}); and</li>
 *   <li>{@link PublishedComparator} — the run's published results directory
 *       ({@code --published-a}/{@code --published-b}).</li>
 * </ul>
 *
 * <p>Both layers classify a file identically (added / removed / changed /
 * unchanged) and enrich a changed text file with the same {@link LineDiff}
 * view, so the size/hash/line-diff mechanics — and their read and line caps —
 * live here once rather than being duplicated per layer. Enumeration
 * (<em>which</em> files count as comparable) stays with each caller, because a
 * work directory (skip control files and staged input symlinks) and a published
 * tree (follow symlinks, keep everything) enumerate differently.
 *
 * <p>Gzip files (detected by their magic bytes, so {@code .gz}, {@code .bgz}
 * and BGZF files such as {@code .vcf.gz} all qualify) are compared by their
 * <em>decompressed</em> content when their compressed bytes differ. Plain
 * {@code gzip} writes a timestamp and file name into its header, so two runs
 * that produced identical data would otherwise show as changed. A changed
 * gzip file whose content is text is line-diffed on its decompressed text.
 * When {@code maxBytes} is set, a gzip file larger than it keeps the raw-byte
 * comparison, so the cap also bounds decompression work.
 *
 * <p>Everything is read-only local I/O — no task is ever re-executed.
 */
@Slf4j
@CompileStatic
class FileContentComparator {

    /** Length of the short SHA-256 prefix kept for display. */
    private static final int SHORT_HASH_LEN = 12

    /** Default number of leading lines kept per text file when computing its line diff. */
    static final int DEFAULT_MAX_LINES = 1000

    /** Hard cap on bytes read from a text file when computing its line diff (2 MiB). */
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024

    /**
     * Bytes sampled from the head of a changed file to decide whether it is text.
     * A NUL byte within this window marks the file as binary and skips line diffing.
     */
    private static final int SNIFF_BYTES = 8192

    /**
     * Maximum file size (bytes) to hash when two files share the same size.
     * {@code 0} means no limit (hash any size). Files above the cap with equal
     * sizes are left content-unverified rather than read in full.
     */
    private final long maxBytes

    /**
     * Maximum leading lines kept per side before line-diffing a changed text
     * file. Zero falls back to {@link #DEFAULT_MAX_LINES}.
     */
    private final int maxLines

    /**
     * When set ({@code --numeric-tolerance}), a text file whose lines differ
     * only in numbers within this tolerance is unchanged, and line diffs ignore
     * such differences. Null means exact comparison.
     */
    private final NumericTolerance tolerance

    FileContentComparator(long maxBytes = 0L, int maxLines = DEFAULT_MAX_LINES, double numericTolerance = 0.0d) {
        this.maxBytes = maxBytes
        this.maxLines = maxLines > 0 ? maxLines : DEFAULT_MAX_LINES
        this.tolerance = NumericTolerance.of(numericTolerance)
    }

    /**
     * Classify {@code path} across the two sides. {@code fileA}/{@code fileB}
     * are the resolved file paths (or null when absent on that side) and
     * {@code sizeA}/{@code sizeB} their sizes (null when absent). A file present
     * on only one side is ADDED / REMOVED; differing sizes are conclusively
     * CHANGED; same-size files are separated by content hash (subject to the
     * byte cap). A changed text file additionally carries a line-level diff.
     */
    OutputFileDiff compare(String path, Path fileA, Long sizeA, Path fileB, Long sizeB) {
        final fd = new OutputFileDiff(path: path, sizeA: sizeA, sizeB: sizeB)

        if( sizeA == null ) {
            fd.kind = Kind.ADDED
            return fd
        }
        if( sizeB == null ) {
            fd.kind = Kind.REMOVED
            return fd
        }
        if( isGzipPair(fileA, sizeA, fileB, sizeB) ) {
            final gz = compareGzip(fd, fileA, sizeA, fileB, sizeB)
            if( gz != null )
                return gz
            // Decompression failed (corrupt or truncated): fall back to raw bytes.
        }
        if( sizeA != sizeB ) {
            // Different sizes are conclusively different; no need to hash.
            return changed(fd, fileA, fileB, false)
        }

        // Same size: only content hashing can tell them apart.
        if( maxBytes > 0 && sizeA > maxBytes ) {
            fd.kind = Kind.UNCHANGED
            fd.verified = false
            fd.note = "same size (${sizeA} bytes); content not verified (exceeds byte cap)".toString()
            return fd
        }

        // Compare the FULL digests to decide identity; two same-size files
        // could otherwise collide on a truncated prefix and be misreported as
        // UNCHANGED. The short prefix is kept only for display (hashA/hashB).
        final fullA = sha256(fileA)
        final fullB = sha256(fileB)
        if( fullA == null || fullB == null ) {
            fd.kind = Kind.UNCHANGED
            fd.verified = false
            fd.note = 'same size; content hash could not be computed'
            return fd
        }
        fd.hashA = shortHash(fullA)
        fd.hashB = shortHash(fullB)
        if( fullA != fullB )
            return changed(fd, fileA, fileB, false)
        fd.kind = Kind.UNCHANGED
        return fd
    }

    /**
     * Finish a file whose bytes (or decompressed bytes) differ: unchanged when
     * a numeric tolerance is set and every line matches within it, otherwise
     * changed with a line diff (which ignores within-tolerance differences).
     */
    private OutputFileDiff changed(OutputFileDiff fd, Path fileA, Path fileB, boolean gzip) {
        if( tolerance != null && withinTolerance(fileA, fileB, gzip) ) {
            fd.kind = Kind.UNCHANGED
            fd.withinTolerance = true
            fd.note = "${gzip ? 'gzip: ' : ''}differs only in numbers within tolerance ${tolerance}".toString()
            return fd
        }
        fd.kind = Kind.CHANGED
        attachLineDiff(fd, fileA, fileB, gzip)
        return fd
    }

    /**
     * Whether two text files have the same number of lines and every line
     * pair matches within {@link #tolerance}. Streams both files in full
     * (decompressed for gzip); binary content, unreadable files and files over
     * {@link #maxBytes} (when set) never qualify.
     */
    private boolean withinTolerance(Path fileA, Path fileB, boolean gzip) {
        try {
            if( maxBytes > 0 && (Files.size(fileA) > maxBytes || Files.size(fileB) > maxBytes) )
                return false
            return lineReader(fileA, gzip).withCloseable { BufferedReader ra ->
                lineReader(fileB, gzip).withCloseable { BufferedReader rb ->
                    while( true ) {
                        final la = ra.readLine()
                        final lb = rb.readLine()
                        if( la == null || lb == null )
                            return la == null && lb == null
                        if( la.indexOf((int) 0) >= 0 || lb.indexOf((int) 0) >= 0 )
                            return false
                        if( !tolerance.test(la, lb) )
                            return false
                    }
                }
            } as boolean
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not compare '${fileA}' / '${fileB}' with numeric tolerance: ${e.message}"
            return false
        }
    }

    private static BufferedReader lineReader(Path file, boolean gzip) {
        final InputStream is = gzip ? gunzip(file) : file.newInputStream()
        return new BufferedReader(new InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8), 64 * 1024)
    }

    /**
     * True when both files are gzip streams worth decompressing: both start
     * with the gzip magic bytes and neither exceeds {@link #maxBytes} (when set).
     */
    private boolean isGzipPair(Path fileA, Long sizeA, Path fileB, Long sizeB) {
        if( maxBytes > 0 && (sizeA > maxBytes || sizeB > maxBytes) )
            return false
        return isGzip(fileA) && isGzip(fileB)
    }

    /** Whether {@code file} starts with the gzip magic bytes {@code 1f 8b}. */
    static boolean isGzip(Path file) {
        try {
            file.newInputStream().withCloseable { InputStream is ->
                return is.read() == 0x1f && is.read() == 0x8b
            }
        }
        catch( Exception ignored ) {
            return false
        }
    }

    /**
     * Classify a gzip pair by decompressed content. Byte-identical files short
     * circuit on their raw hash. Returns null when either side cannot be
     * decompressed, so the caller falls back to the raw-byte comparison.
     */
    private OutputFileDiff compareGzip(OutputFileDiff fd, Path fileA, Long sizeA, Path fileB, Long sizeB) {
        if( sizeA == sizeB ) {
            final fullA = sha256(fileA)
            final fullB = sha256(fileB)
            if( fullA != null && fullA == fullB ) {
                fd.hashA = shortHash(fullA)
                fd.hashB = shortHash(fullB)
                fd.kind = Kind.UNCHANGED
                return fd
            }
        }
        final same = sameDecompressed(fileA, fileB)
        if( same == null )
            return null
        fd.decompressed = true
        if( same ) {
            fd.kind = Kind.UNCHANGED
            fd.note = 'gzip: compressed bytes differ, decompressed content is identical'
            return fd
        }
        fd.note = 'gzip: decompressed content differs'
        return changed(fd, fileA, fileB, true)
    }

    /**
     * Stream both files' decompressed content side by side, stopping at the
     * first difference. Returns null when either cannot be decompressed.
     */
    private static Boolean sameDecompressed(Path fileA, Path fileB) {
        try {
            return gunzip(fileA).withCloseable { InputStream a ->
                gunzip(fileB).withCloseable { InputStream b ->
                    final bufA = new byte[64 * 1024]
                    final bufB = new byte[64 * 1024]
                    while( true ) {
                        final n = readFully(a, bufA)
                        final m = readFully(b, bufB)
                        if( n != m )
                            return false
                        if( n == 0 )
                            return true
                        if( !Arrays.equals(bufA, 0, n, bufB, 0, m) )
                            return false
                    }
                }
            } as Boolean
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not decompress '${fileA}' / '${fileB}': ${e.message}"
            return null
        }
    }

    /** Decompressing stream over a gzip file; handles multi-member (BGZF) files. */
    private static InputStream gunzip(Path file) {
        return new GZIPInputStream(new BufferedInputStream(file.newInputStream(), 64 * 1024), 64 * 1024)
    }

    /** Fill {@code buf} as far as the stream allows; returns the bytes read (0 at EOF). */
    private static int readFully(InputStream is, byte[] buf) {
        int off = 0
        int n
        while( off < buf.length && (n = is.read(buf, off, buf.length - off)) != -1 )
            off += n
        return off
    }

    /**
     * For a changed file that is text on both sides, compute a bounded
     * line-level diff and attach it to {@code fd}. Binary files (a NUL byte in
     * the head) and unreadable files are left with no ops, so the file is still
     * reported as changed but without a line-by-line view. With {@code gzip},
     * the decompressed content is read.
     */
    private void attachLineDiff(OutputFileDiff fd, Path fileA, Path fileB, boolean gzip = false) {
        final ta = readText(fileA, gzip)
        final tb = readText(fileB, gzip)
        if( ta == null || tb == null || ta.binary || tb.binary )
            return
        fd.truncatedA = ta.truncated
        fd.truncatedB = tb.truncated
        fd.ops = LineDiff.diff(ta.lines, tb.lines, tolerance)
    }

    /** Head content of a text file, or null when unreadable. */
    @CompileStatic
    private static class TextRead {
        List<String> lines
        boolean truncated
        boolean binary
        TextRead(List<String> lines, boolean truncated, boolean binary) {
            this.lines = lines
            this.truncated = truncated
            this.binary = binary
        }
    }

    /**
     * Read the head of a file for line-diffing: at most {@link #MAX_READ_BYTES}
     * of leading bytes, sniffed for a NUL byte (binary marker) and otherwise
     * decoded as UTF-8 and reduced to the first {@link #maxLines} lines.
     * {@code truncated} is set when either cap dropped content. Returns null
     * only when the file cannot be read at all.
     */
    private TextRead readText(Path file, boolean gzip = false) {
        try {
            boolean bytesDropped = false
            byte[] bytes
            if( !gzip && Files.size(file) <= MAX_READ_BYTES ) {
                bytes = Files.readAllBytes(file)
            }
            else {
                // Read at most MAX_READ_BYTES (decompressed, for gzip); one more
                // byte tells whether anything was left behind.
                final buf = new byte[(int) MAX_READ_BYTES]
                int len = 0
                (gzip ? gunzip(file) : file.newInputStream()).withCloseable { InputStream is ->
                    len = readFully(is, buf)
                    bytesDropped = len == buf.length && is.read() != -1
                }
                bytes = len == buf.length ? buf : Arrays.copyOf(buf, len)
            }

            // Binary sniff: a NUL byte in the head window means don't line-diff.
            final sniffLen = Math.min(bytes.length, SNIFF_BYTES)
            for( int k = 0; k < sniffLen; k++ ) {
                if( bytes[k] == (byte) 0 )
                    return new TextRead([], false, true)
            }

            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
            // When we stopped mid-file, drop the trailing partial line.
            if( bytesDropped ) {
                final nl = text.lastIndexOf('\n')
                text = nl >= 0 ? text.substring(0, nl) : text
            }

            List<String> lines = text.readLines()
            boolean linesDropped = false
            if( lines.size() > maxLines ) {
                lines = lines.subList(0, maxLines).collect { it }
                linesDropped = true
            }
            return new TextRead(lines, bytesDropped || linesDropped, false)
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not read '${file}' for line diff: ${e.message}"
            return null
        }
    }

    /** Streamed SHA-256 of a file, returned as the full hex digest, or null on error. */
    private static String sha256(Path file) {
        try {
            final md = MessageDigest.getInstance('SHA-256')
            final buf = new byte[64 * 1024]
            file.newInputStream().withCloseable { InputStream is ->
                int n
                while( (n = is.read(buf)) != -1 )
                    md.update(buf, 0, n)
            }
            final hex = new StringBuilder()
            md.digest().each { byte x -> hex << String.format('%02x', x) }
            return hex.toString()
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not hash '${file}': ${e.message}"
            return null
        }
    }

    /** Short hex prefix of a full digest, kept for compact display only. */
    private static String shortHash(String fullHex) {
        return fullHex.length() > SHORT_HASH_LEN ? fullHex.substring(0, SHORT_HASH_LEN) : fullHex
    }
}
