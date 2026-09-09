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

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.DagEdge

/**
 * Reconstructs a run's process&#8594;process wiring so two runs can be compared
 * for topology changes that the task-count-per-process view cannot see (e.g. a
 * pipeline rewired from {@code A → C} to {@code A → B → C}).
 *
 * <p>Two sources are used, in order of trust:
 * <ol>
 *   <li><b>Data lineage</b> — when the run's project directory has a Nextflow
 *       lineage store ({@code .lineage/}, from {@code lineage.enabled}) that
 *       recorded this session, edges are read from the persisted
 *       {@code TaskRun} input references via {@link LineageStore}. This is
 *       <em>authoritative</em>: it is exactly the provenance Nextflow recorded,
 *       needs no work directories, and is unaffected by cleanup.</li>
 *   <li><b>Work-dir symlinks</b> — otherwise the wiring is inferred from the
 *       input symlinks Nextflow stages into each task's work directory: a link
 *       that resolves <em>into another task's work directory</em> means that
 *       task consumed the other's output — a producer&#8594;consumer edge; links
 *       resolving elsewhere are external inputs and yield no edge. This needs the
 *       work directories to still exist locally and is a <em>best-effort</em>
 *       reconstruction — if some were cleaned up, the recovered wiring is
 *       incomplete.</li>
 * </ol>
 * Either way this layer is informational only — the caller never lets it break
 * the "identical" verdict or {@code --fail-on-change}.
 */
@Slf4j
@CompileStatic
class DagComparator {

    /** Everything is read-only local I/O; no task is ever re-executed. */
    DagComparator() {}

    /** Where a run's reconstructed wiring came from. */
    static enum Source {
        /** Read from the authoritative {@code .lineage/} provenance store. */
        LINEAGE,
        /** Inferred (best-effort) from work-dir input symlinks. */
        SYMLINK,
        /** Nothing was available (no lineage store, no readable work dirs). */
        NONE
    }

    /**
     * The reconstructed edge set for a run, plus how it was obtained and (for
     * the symlink source) whether any work directory was actually readable, so
     * the caller can tell "no edges" from "nothing to read" and word its note
     * as authoritative vs best-effort.
     */
    @CompileStatic
    static class RunGraph {
        Set<DagEdge> edges = new LinkedHashSet<>()
        /** How the edges were obtained. */
        Source source = Source.NONE
        /** True when at least one task work directory existed and was scanned. */
        boolean anyWorkdir = false
        /** Number of tasks whose work directory was missing/unreadable. */
        int missingWorkdirs = 0
    }

    /**
     * Reconstruct the process&#8594;process edges for a single run, preferring
     * the authoritative lineage store under {@code projectDir} and falling back
     * to work-dir symlink inference when no lineage store recorded this run.
     */
    RunGraph graphOf(RunSnapshot run, Path projectDir) {
        final store = LineageStore.locate(projectDir)
        if( store != null ) {
            final edges = store.edgesForSession(run.sessionId)
            if( edges != null ) {
                log.debug "nf-diff: reconstructed ${edges.size()} DAG edge(s) for '${run.runName}' from the lineage store"
                return new RunGraph(edges: edges, source: Source.LINEAGE, anyWorkdir: true, missingWorkdirs: 0)
            }
            log.debug "nf-diff: lineage store present but held no wiring for '${run.runName}'; falling back to work-dir symlinks"
        }
        return symlinkGraphOf(run)
    }

    /**
     * Reconstruct the process&#8594;process edges for a single run by scanning
     * each task's work directory for input symlinks that resolve into another
     * task's work directory. Retained as the fallback used when no lineage store
     * recorded the run, and exposed for direct use in tests.
     */
    RunGraph symlinkGraphOf(RunSnapshot run) {
        final graph = new RunGraph(source: Source.SYMLINK)

        // Map every task's normalised absolute work dir -> its process name, so
        // a resolved symlink target can be attributed back to its producer.
        final workdirToProcess = new LinkedHashMap<Path,String>()
        run.tasks.each { TaskInfo t ->
            final dir = toDir(t.workdir)
            if( dir != null && t.process )
                workdirToProcess.put(dir, t.process)
        }

        run.tasks.each { TaskInfo t ->
            final dir = toDir(t.workdir)
            if( dir == null ) {
                graph.missingWorkdirs++
                return
            }
            graph.anyWorkdir = true
            final consumer = t.process
            if( !consumer )
                return
            symlinkTargets(dir).each { Path target ->
                final producer = attribute(target, workdirToProcess)
                // A link into another task's work dir (or, for a scatter/feedback
                // process, its own) is a producer→consumer edge. Links that
                // resolve outside every work dir are external inputs — no edge.
                if( producer != null )
                    graph.edges << new DagEdge(from: producer, to: consumer)
            }
        }
        return graph
    }

    /**
     * Resolve which task work directory (if any) a symlink target lives under,
     * returning that task's process name. Walks the target's parent chain so a
     * link into a nested output subdirectory still attributes to the work dir.
     */
    private static String attribute(Path target, Map<Path,String> workdirToProcess) {
        Path p = target
        while( p != null ) {
            final proc = workdirToProcess.get(p)
            if( proc != null )
                return proc
            p = p.getParent()
        }
        return null
    }

    /**
     * Collect the resolved, normalised targets of every symbolic link under a
     * task's work directory. These are the task's staged inputs; each is a
     * candidate producer&#8594;consumer edge.
     */
    private static List<Path> symlinkTargets(Path dir) {
        final targets = new ArrayList<Path>()
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if( Files.isSymbolicLink(file) ) {
                        final resolved = resolveLink(file)
                        if( resolved != null )
                            targets.add(resolved)
                    }
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
            log.warn "nf-diff: failed to scan work dir '${dir}' for input links: ${e.message}"
        }
        return targets
    }

    /**
     * Resolve a symlink to an absolute, normalised path. Prefer the raw link
     * target (works even when the pointee no longer exists), resolving it
     * against the link's parent when relative.
     */
    private static Path resolveLink(Path link) {
        try {
            Path raw = Files.readSymbolicLink(link)
            if( !raw.isAbsolute() )
                raw = link.getParent()?.resolve(raw) ?: raw
            return raw.toAbsolutePath().normalize()
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not resolve symlink '${link}': ${e.message}"
            return null
        }
    }

    /** Resolve a work dir string to a readable directory, or null. */
    private static Path toDir(String workdir) {
        if( !workdir )
            return null
        try {
            final p = Paths.get(workdir)
            return Files.isDirectory(p) ? p.toAbsolutePath().normalize() : null
        }
        catch( Exception e ) {
            log.debug "nf-diff: work dir '${workdir}' is not a usable local path: ${e.message}"
            return null
        }
    }
}
