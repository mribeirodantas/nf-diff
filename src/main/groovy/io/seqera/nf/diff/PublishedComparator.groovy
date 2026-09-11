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

import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.PublishedDiff

/**
 * Compares two runs' <em>published</em> output directories — the
 * {@code publishDir} / {@code outdir} trees a run copied its results into.
 *
 * <p>Where {@link OutputComparator} reads each task's work directory (the first
 * thing {@code nextflow clean} deletes, and unavailable when the work dir lived
 * on remote object storage), the published tree is the durable result of a run
 * and typically survives long after the work dirs are gone. Pointing nf-diff at
 * two runs' published directories therefore extends output diffing to the
 * majority of real runs.
 *
 * <p>Each directory is walked recursively and every file is keyed by its path
 * relative to its published root, then handed to the shared
 * {@link FileContentComparator} for the same size / hash / line-diff
 * classification the work-dir outputs layer uses. Symlinks are followed:
 * {@code publishDir}'s default mode symlinks published files back into the work
 * directory, so following links compares the real content (and still works for
 * {@code copy} mode, where the files are real). Walk cycles and unreadable
 * entries are skipped defensively.
 *
 * <p>Everything is read-only local I/O — no task is ever re-executed.
 */
@Slf4j
@CompileStatic
class PublishedComparator {

    /** Shared per-file size/hash/line-diff engine, configured with the caps below. */
    private final FileContentComparator engine

    PublishedComparator(long maxBytes = 0L, int maxLines = FileContentComparator.DEFAULT_MAX_LINES) {
        this.engine = new FileContentComparator(maxBytes, maxLines)
    }

    /** Compare the two published output directories. */
    PublishedDiff compare(Path dirA, Path dirB) {
        final pd = new PublishedDiff(
                dirA: dirA?.toString(),
                dirB: dirB?.toString() )

        final rootA = toDir(dirA)
        final rootB = toDir(dirB)
        pd.availableA = rootA != null
        pd.availableB = rootB != null

        if( rootA == null || rootB == null ) {
            pd.note = unavailableNote(pd)
            return pd
        }

        // Both sides point at the same tree: there is nothing to compare (a run
        // would have overwritten the other's results in place).
        if( rootA.toAbsolutePath().normalize() == rootB.toAbsolutePath().normalize() ) {
            pd.sameDir = true
            return pd
        }

        final filesA = enumerate(rootA)
        final filesB = enumerate(rootB)
        final paths = new TreeSet<String>()
        paths.addAll(filesA.keySet())
        paths.addAll(filesB.keySet())

        paths.each { String rel ->
            pd.files << engine.compare(rel, rootA.resolve(rel), filesA.get(rel), rootB.resolve(rel), filesB.get(rel))
        }
        return pd
    }

    /** Resolve a directory path to a readable directory, or null. */
    private static Path toDir(Path dir) {
        if( dir == null )
            return null
        try {
            return Files.isDirectory(dir) ? dir : null
        }
        catch( Exception e ) {
            log.debug "nf-diff: published dir '${dir}' is not a usable local path: ${e.message}"
            return null
        }
    }

    private static String unavailableNote(PublishedDiff pd) {
        if( !pd.availableA && !pd.availableB )
            return 'Neither published directory exists locally, so published outputs cannot be compared.'
        final missing = !pd.availableA ? 'A' : 'B'
        return "Run ${missing}'s published directory does not exist locally, so published outputs cannot be compared."
    }

    /**
     * Enumerate the regular files under {@code root}, mapped by their path
     * relative to {@code root} to their size in bytes. Symlinks are followed
     * (see the class doc), directories are descended, and unreadable entries or
     * walk cycles are skipped rather than aborting the whole enumeration.
     */
    private static Map<String,Long> enumerate(Path root) {
        final out = new LinkedHashMap<String,Long>()
        try {
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                        @Override
                        FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            // With FOLLOW_LINKS a symlink to a file is reported as a
                            // regular file; keep those, drop anything that is not a
                            // regular file (e.g. a dangling link, a device node).
                            if( !attrs.isRegularFile() )
                                return FileVisitResult.CONTINUE
                            out.put(root.relativize(file).toString(), attrs.size())
                            return FileVisitResult.CONTINUE
                        }

                        @Override
                        FileVisitResult visitFileFailed(Path file, IOException exc) {
                            // FileSystemLoopException (a symlink cycle) or an
                            // unreadable entry — skip it, don't abort the walk.
                            log.debug "nf-diff: could not read published entry '${file}': ${exc.message}"
                            return FileVisitResult.CONTINUE
                        }
                    })
        }
        catch( Exception e ) {
            log.warn "nf-diff: failed to enumerate published outputs under '${root}': ${e.message}"
        }
        return out
    }
}
