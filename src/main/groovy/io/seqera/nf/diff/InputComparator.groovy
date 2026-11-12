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
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.Kind
import io.seqera.nf.diff.DiffResult.OutputDiff
import io.seqera.nf.diff.DiffResult.OutputFileDiff

/**
 * Compares the input files staged into two matched tasks' work directories
 * ({@code --diff-inputs}), so that when a task's outputs differ you can see
 * whether its inputs differed too.
 *
 * <p>Nextflow stages a task's inputs as symbolic links in its work directory
 * (at the root, or in folders created by {@code stageAs}). Each link is
 * followed to the real file it points at:
 * <ul>
 *   <li>when both runs' links resolve to the <em>same</em> real file (a shared
 *       reference, or the same upstream output after {@code -resume}), the input
 *       is unchanged without reading it;</li>
 *   <li>otherwise the two files go through the same size / SHA-256 / line-diff
 *       engine as the outputs layer ({@link FileContentComparator}), gzip
 *       handling included;</li>
 *   <li>a link to a folder (an index, a reference bundle) has the files inside
 *       it compared, keyed {@code link/sub/path}, up to {@link #MAX_DIR_FILES}
 *       files per folder;</li>
 *   <li>a link whose target no longer exists is left out and counted in the
 *       task's note.</li>
 * </ul>
 * Inputs staged by copy ({@code stageInMode 'copy'}) are indistinguishable from
 * outputs and are not covered. Results reuse {@link OutputDiff}, so the
 * renderers draw this layer exactly like the outputs layer.
 */
@Slf4j
@CompileStatic
class InputComparator {

    /** Cap on files compared inside one staged folder, so a huge index can't stall the diff. */
    static final int MAX_DIR_FILES = 1000

    private final FileContentComparator engine

    InputComparator(long maxBytes = 0L, int maxLines = FileContentComparator.DEFAULT_MAX_LINES, double numericTolerance = 0.0d) {
        this.engine = new FileContentComparator(maxBytes, maxLines, numericTolerance)
    }

    /** One staged input resolved to its real file. */
    @CompileStatic
    private static class Staged {
        Path real
        long size
    }

    /** Staged inputs of one task, keyed by path relative to the work dir. */
    @CompileStatic
    private static class StagedSet {
        Map<String,Staged> files = new TreeMap<String,Staged>()
        int broken
        int truncatedDirs
    }

    /** Compare the staged inputs of two matched tasks. */
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
            od.note = !od.availableA && !od.availableB
                    ? 'Neither work directory is available locally, so inputs cannot be compared.'
                    : "Run ${!od.availableA ? 'A' : 'B'}'s work directory is not available locally, so inputs cannot be compared.".toString()
            return od
        }
        if( dirA.toAbsolutePath().normalize() == dirB.toAbsolutePath().normalize() ) {
            od.sameWorkdir = true
            return od
        }

        final setA = staged(dirA)
        final setB = staged(dirB)
        final paths = new TreeSet<String>()
        paths.addAll(setA.files.keySet())
        paths.addAll(setB.files.keySet())
        paths.each { String rel ->
            final sa = setA.files.get(rel)
            final sb = setB.files.get(rel)
            if( sa != null && sb != null && sa.real == sb.real ) {
                // Same real file on both sides: identical without reading it.
                od.files << new OutputFileDiff(path: rel, sizeA: sa.size, sizeB: sb.size, kind: Kind.UNCHANGED,
                        note: 'both runs staged the same file')
                return
            }
            od.files << engine.compare(rel, sa?.real, sa?.size, sb?.real, sb?.size)
        }

        final notes = []
        final broken = setA.broken + setB.broken
        if( broken > 0 )
            notes << "${broken} staged input(s) point at files that no longer exist and were skipped.".toString()
        final capped = setA.truncatedDirs + setB.truncatedDirs
        if( capped > 0 )
            notes << "${capped} staged folder(s) had more than ${MAX_DIR_FILES} files; only the first ${MAX_DIR_FILES} were compared.".toString()
        if( notes )
            od.note = notes.join(' ')
        return od
    }

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

    /**
     * Walk {@code dir} without following links and resolve every symbolic link
     * found (the staged inputs) to its real target.
     */
    private static StagedSet staged(Path dir) {
        final out = new StagedSet()
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if( attrs.isSymbolicLink() )
                        resolve(dir.relativize(file).toString(), file, out)
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
            log.warn "nf-diff: failed to enumerate staged inputs under '${dir}': ${e.message}"
        }
        return out
    }

    private static void resolve(String rel, Path link, StagedSet out) {
        final Path real
        try {
            real = link.toRealPath()
        }
        catch( Exception ignored ) {
            out.broken++
            return
        }
        if( Files.isRegularFile(real) ) {
            out.files.put(rel, new Staged(real: real, size: Files.size(real)))
            return
        }
        if( Files.isDirectory(real) )
            stagedFolder(rel, real, out)
    }

    /** Add the files inside a staged folder as {@code rel/sub/path}, up to the cap. */
    private static void stagedFolder(String rel, Path root, StagedSet out) {
        int count = 0
        boolean capped = false
        try {
            Files.walkFileTree(root, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<Path>() {
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if( !attrs.isRegularFile() )
                        return FileVisitResult.CONTINUE
                    if( count >= MAX_DIR_FILES ) {
                        capped = true
                        return FileVisitResult.TERMINATE
                    }
                    count++
                    out.files.put(rel + '/' + root.relativize(file).toString(), new Staged(real: file.toRealPath(), size: attrs.size()))
                    return FileVisitResult.CONTINUE
                }

                @Override
                FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE
                }
            })
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not walk staged folder '${root}': ${e.message}"
        }
        if( capped )
            out.truncatedDirs++
    }
}
