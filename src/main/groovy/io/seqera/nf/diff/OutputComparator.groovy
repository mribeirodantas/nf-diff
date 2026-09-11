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

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.OutputDiff

/**
 * Compares the output files a task wrote to its work directory across two runs.
 *
 * Nextflow stages a task's declared inputs into its work directory as symbolic
 * links (pointing at upstream work dirs or the original input data) and writes
 * a fixed set of control files ({@code .command.*}, {@code .exitcode}). Real,
 * non-symlink files that are not control files are therefore the task's own
 * outputs (plus any scratch it left behind). This comparator enumerates those,
 * keyed by their path relative to the work dir, and hands each pair to the
 * shared {@link FileContentComparator} for size / hash / line-diff classification.
 *
 * Everything is read-only local I/O — no task is ever re-executed. When a work
 * directory is missing (cleaned up, remote, or never materialised) the task's
 * outputs simply cannot be compared and a note records why.
 */
@Slf4j
@CompileStatic
class OutputComparator {

    /**
     * Control files Nextflow writes into every task work directory. These are
     * bookkeeping, not task outputs, so they are excluded from the comparison.
     */
    static final Set<String> CONTROL_FILES = [
            '.command.begin', '.command.env', '.command.err', '.command.log',
            '.command.out', '.command.run', '.command.sh', '.command.stub',
            '.command.trace', '.exitcode',
    ] as Set

    /** Default number of leading lines kept per text file when computing its line diff. */
    static final int DEFAULT_MAX_LINES = FileContentComparator.DEFAULT_MAX_LINES

    /** Shared per-file size/hash/line-diff engine, configured with the caps below. */
    private final FileContentComparator engine

    OutputComparator(long maxBytes = 0L, int maxLines = DEFAULT_MAX_LINES) {
        this.engine = new FileContentComparator(maxBytes, maxLines)
    }

    /** Compare the outputs of two matched tasks. */
    OutputDiff compare(TaskInfo a, TaskInfo b) {
        final od = new OutputDiff(
                taskKey  : (a?.matchKey()) ?: (b?.matchKey()),
                process  : (a?.process) ?: (b?.process),
                workdirA : a?.workdir,
                workdirB : b?.workdir )

        final dirA = toDir(a?.workdir)
        final dirB = toDir(b?.workdir)
        od.availableA = dirA != null
        od.availableB = dirB != null

        if( dirA == null || dirB == null ) {
            od.note = unavailableNote(od)
            return od
        }

        // A cached task in Run B points at the same physical work dir that
        // originally produced it, so the outputs are identical by construction.
        if( dirA.toAbsolutePath().normalize() == dirB.toAbsolutePath().normalize() ) {
            od.sameWorkdir = true
            return od
        }

        final filesA = enumerate(dirA)
        final filesB = enumerate(dirB)
        final paths = new TreeSet<String>()
        paths.addAll(filesA.keySet())
        paths.addAll(filesB.keySet())

        paths.each { String rel ->
            od.files << engine.compare(rel, dirA.resolve(rel), filesA.get(rel), dirB.resolve(rel), filesB.get(rel))
        }
        return od
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

    private static String unavailableNote(OutputDiff od) {
        if( !od.availableA && !od.availableB )
            return 'Neither work directory is available locally, so outputs cannot be compared.'
        final missing = !od.availableA ? 'A' : 'B'
        return "Run ${missing}'s work directory is not available locally, so outputs cannot be compared."
    }

    /**
     * Enumerate the task's output files under {@code dir}, mapped by their path
     * relative to {@code dir} to their size in bytes. Symlinks (staged inputs)
     * and Nextflow control files are skipped.
     */
    private static Map<String,Long> enumerate(Path dir) {
        final out = new LinkedHashMap<String,Long>()
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Skip staged inputs (symlinks) and directory entries.
                    if( attrs.isSymbolicLink() || Files.isSymbolicLink(file) || !attrs.isRegularFile() )
                        return FileVisitResult.CONTINUE
                    final rel = dir.relativize(file).toString()
                    // Control files live at the work-dir root; match on the leaf name.
                    if( CONTROL_FILES.contains(file.fileName.toString()) )
                        return FileVisitResult.CONTINUE
                    out.put(rel, attrs.size())
                    return FileVisitResult.CONTINUE
                }

                @Override
                FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug "nf-diff: could not read '${file}': ${exc.message}"
                    return FileVisitResult.CONTINUE
                }
            })
        }
        catch( Exception e ) {
            log.warn "nf-diff: failed to enumerate outputs under '${dir}': ${e.message}"
        }
        return out
    }
}
