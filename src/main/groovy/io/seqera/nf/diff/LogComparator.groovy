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

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.Kind
import io.seqera.nf.diff.DiffResult.LogDiff
import io.seqera.nf.diff.DiffResult.LogFileDiff

/**
 * Compares the standard log files a task produced across two runs.
 *
 * When a task fails, the most useful question is not <em>that</em> it failed
 * but <em>what it printed</em> before it did. Nextflow captures a task's stdout
 * in {@code .command.out}, its stderr in {@code .command.err}, and a combined
 * stream in {@code .command.log}, all in the task work directory. This
 * comparator reads those files from each run's matched task and produces a
 * line-level diff of each, so a task that went from exit 0 to exit 1 can be
 * inspected side by side.
 *
 * Reads are bounded: only the tail of each log (up to {@code maxLines}, and a
 * hard byte cap) is loaded, so an enormous log never blows up memory. All I/O
 * is read-only and local — no task is ever re-executed. When a work directory
 * is missing the logs simply cannot be compared and a note records why.
 */
@Slf4j
@CompileStatic
class LogComparator {

    /** The task log files compared, in display order. */
    static final List<String> LOG_FILES = ['.command.out', '.command.err', '.command.log']

    /** Default number of (tail) lines kept per log file when none is given. */
    static final int DEFAULT_MAX_LINES = 200

    /** Hard cap on bytes read from the tail of any single log file (2 MiB). */
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024

    /** Maximum tail lines kept per log file before diffing. */
    private final int maxLines

    LogComparator(int maxLines = DEFAULT_MAX_LINES) {
        this.maxLines = maxLines > 0 ? maxLines : DEFAULT_MAX_LINES
    }

    /** Compare the log files of two matched tasks. */
    LogDiff compare(TaskInfo a, TaskInfo b) {
        final ld = new LogDiff(
                taskKey  : (a?.matchKey()) ?: (b?.matchKey()),
                process  : (a?.process) ?: (b?.process),
                workdirA : a?.workdir,
                workdirB : b?.workdir,
                exitA    : a?.exit,
                exitB    : b?.exit,
                statusA  : a?.status,
                statusB  : b?.status )

        final dirA = toDir(a?.workdir)
        final dirB = toDir(b?.workdir)
        ld.availableA = dirA != null
        ld.availableB = dirB != null

        if( dirA == null || dirB == null ) {
            ld.note = unavailableNote(ld)
            return ld
        }

        // A cached task in Run B points at the same physical work dir that
        // originally produced it, so its logs are identical by construction.
        if( dirA.toAbsolutePath().normalize() == dirB.toAbsolutePath().normalize() ) {
            ld.sameWorkdir = true
            return ld
        }

        LOG_FILES.each { String name ->
            final fd = compareFile(name, dirA.resolve(name), dirB.resolve(name))
            if( fd != null )
                ld.logs << fd
        }
        return ld
    }

    /** Resolve a work dir string to a readable directory, or null. */
    private static Path toDir(String workdir) {
        if( !workdir )
            return null
        try {
            final p = Paths.get(workdir)
            return Files.isDirectory(p) ? p : null
        }
        catch( Exception e ) {
            log.debug "nf-diff: work dir '${workdir}' is not a usable local path: ${e.message}"
            return null
        }
    }

    private static String unavailableNote(LogDiff ld) {
        if( !ld.availableA && !ld.availableB )
            return 'Neither work directory is available locally, so logs cannot be compared.'
        final missing = !ld.availableA ? 'A' : 'B'
        return "Run ${missing}'s work directory is not available locally, so logs cannot be compared."
    }

    /**
     * Compare a single log file across the two work dirs. Returns null when the
     * file is absent on both sides (nothing to show), otherwise a classified
     * {@link LogFileDiff}.
     */
    private LogFileDiff compareFile(String name, Path fileA, Path fileB) {
        final existsA = isReadableFile(fileA)
        final existsB = isReadableFile(fileB)
        if( !existsA && !existsB )
            return null

        final fd = new LogFileDiff(name: name)
        fd.bytesA = existsA ? sizeOf(fileA) : null
        fd.bytesB = existsB ? sizeOf(fileB) : null

        final tailA = existsA ? readTail(fileA) : new Tail([], false)
        final tailB = existsB ? readTail(fileB) : new Tail([], false)
        fd.truncatedA = tailA.truncated
        fd.truncatedB = tailB.truncated
        fd.ops = LineDiff.diff(tailA.lines, tailB.lines)

        if( !existsA )
            fd.kind = Kind.ADDED
        else if( !existsB )
            fd.kind = Kind.REMOVED
        else
            fd.kind = fd.ops.any { it.type != LineDiff.Type.EQUAL } ? Kind.CHANGED : Kind.UNCHANGED
        return fd
    }

    private static boolean isReadableFile(Path p) {
        try {
            return Files.isRegularFile(p) && Files.isReadable(p)
        }
        catch( Exception e ) {
            return false
        }
    }

    private static Long sizeOf(Path p) {
        try {
            return Files.size(p)
        }
        catch( Exception e ) {
            return null
        }
    }

    /** Tail content of a log file: its last lines and whether it was truncated. */
    @CompileStatic
    private static class Tail {
        List<String> lines
        boolean truncated
        Tail(List<String> lines, boolean truncated) {
            this.lines = lines
            this.truncated = truncated
        }
    }

    /**
     * Read the tail of a log file: at most {@link #MAX_READ_BYTES} of trailing
     * bytes, decoded as UTF-8 and reduced to the last {@link #maxLines} lines.
     * {@code truncated} is set when either cap dropped content.
     */
    private Tail readTail(Path file) {
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
                Files.newByteChannel(file, StandardOpenOption.READ).withCloseable { SeekableByteChannel ch ->
                    ch.position(size - MAX_READ_BYTES)
                    final buf = ByteBuffer.wrap(bytes)
                    while( buf.hasRemaining() && ch.read(buf) != -1 ) { }
                }
            }

            String text = new String(bytes, StandardCharsets.UTF_8)
            // When we started mid-file, drop the leading partial line.
            if( bytesDropped ) {
                final nl = text.indexOf('\n')
                text = nl >= 0 ? text.substring(nl + 1) : text
            }

            List<String> lines = text.readLines()
            boolean linesDropped = false
            if( lines.size() > maxLines ) {
                lines = lines.subList(lines.size() - maxLines, lines.size()).collect { it }
                linesDropped = true
            }
            return new Tail(lines, bytesDropped || linesDropped)
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not read log '${file}': ${e.message}"
            return new Tail([], false)
        }
    }
}
