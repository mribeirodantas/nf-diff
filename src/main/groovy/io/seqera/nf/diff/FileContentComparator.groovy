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

    FileContentComparator(long maxBytes = 0L, int maxLines = DEFAULT_MAX_LINES) {
        this.maxBytes = maxBytes
        this.maxLines = maxLines > 0 ? maxLines : DEFAULT_MAX_LINES
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
        if( sizeA != sizeB ) {
            // Different sizes are conclusively different; no need to hash.
            fd.kind = Kind.CHANGED
            attachLineDiff(fd, fileA, fileB)
            return fd
        }

        // Same size: only content hashing can tell them apart.
        if( maxBytes > 0 && sizeA > maxBytes ) {
            fd.kind = Kind.UNCHANGED
            fd.verified = false
            fd.note = "same size (${sizeA} bytes); content not verified (exceeds byte cap)".toString()
            return fd
        }

        fd.hashA = sha256(fileA)
        fd.hashB = sha256(fileB)
        if( fd.hashA == null || fd.hashB == null ) {
            fd.kind = Kind.UNCHANGED
            fd.verified = false
            fd.note = 'same size; content hash could not be computed'
            return fd
        }
        fd.kind = (fd.hashA == fd.hashB) ? Kind.UNCHANGED : Kind.CHANGED
        if( fd.kind == Kind.CHANGED )
            attachLineDiff(fd, fileA, fileB)
        return fd
    }

    /**
     * For a changed file that is text on both sides, compute a bounded
     * line-level diff and attach it to {@code fd}. Binary files (a NUL byte in
     * the head) and unreadable files are left with no ops, so the file is still
     * reported as changed but without a line-by-line view.
     */
    private void attachLineDiff(OutputFileDiff fd, Path fileA, Path fileB) {
        final ta = readText(fileA)
        final tb = readText(fileB)
        if( ta == null || tb == null || ta.binary || tb.binary )
            return
        fd.truncatedA = ta.truncated
        fd.truncatedB = tb.truncated
        fd.ops = LineDiff.diff(ta.lines, tb.lines)
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
    private TextRead readText(Path file) {
        try {
            final size = Files.size(file)
            boolean bytesDropped = false
            byte[] bytes
            if( size <= MAX_READ_BYTES ) {
                bytes = Files.readAllBytes(file)
            }
            else {
                bytesDropped = true
                bytes = new byte[(int) MAX_READ_BYTES]
                file.newInputStream().withCloseable { InputStream is ->
                    int off = 0
                    int n
                    while( off < bytes.length && (n = is.read(bytes, off, bytes.length - off)) != -1 )
                        off += n
                }
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

    /** Streamed SHA-256 of a file, returned as a short hex prefix, or null on error. */
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
            return hex.toString().substring(0, SHORT_HASH_LEN)
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not hash '${file}': ${e.message}"
            return null
        }
    }
}
