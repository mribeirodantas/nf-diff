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
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.DagEdge

/**
 * Reads Nextflow's data-lineage store (the {@code .lineage/} directory produced
 * when {@code lineage.enabled = true}, Nextflow 25.04+) and reconstructs a
 * run's process&#8594;process wiring from the <em>authoritative</em> provenance
 * Nextflow records, rather than inferring it from work-dir input symlinks.
 *
 * <p>Nextflow writes one JSON record per lineage entity under the store, keyed
 * by its lineage id (LID). Each record is a {@code lineage/v1beta1} envelope
 * whose discriminator is {@code kind} and whose payload is nested under
 * {@code spec} (older, pre-{@code v1beta1} stores put the same fields flat at
 * the top level under {@code type} — both shapes are accepted). The record
 * kinds relevant here are:
 * <ul>
 *   <li>{@code WorkflowRun} — one per run, carrying the {@code sessionId};</li>
 *   <li>{@code TaskRun} — one per task, carrying its process {@code name},
 *       {@code sessionId}, and an {@code input} list whose values reference the
 *       upstream outputs it consumed as {@code lid://<taskHash>/...} URIs.</li>
 * </ul>
 * A {@code TaskRun} input that references another {@code TaskRun}'s output
 * (its LID hash) is a producer&#8594;consumer edge — the same edge the
 * {@link DagComparator} symlink heuristic tries to recover, but read straight
 * from what Nextflow persisted, so it needs no work directories and is not
 * best-effort.
 *
 * <p>Records are read directly off disk (each is a {@code .data.json} file under
 * a per-LID directory) rather than through the {@code nf-lineage} module's
 * classes, so this layer imposes no compile-time dependency on that plugin and
 * degrades gracefully if the store is absent or in an unexpected shape — the
 * caller then falls back to the symlink reconstruction.
 */
@Slf4j
@CompileStatic
class LineageStore {

    /** Default store directory name, relative to a run's project directory. */
    static final String DEFAULT_DIR = '.lineage'

    /** The per-LID record filename Nextflow writes inside each entry directory. */
    private static final String RECORD_FILE = '.data.json'

    /** Cap on files scanned so a huge/foreign store can never hang the diff. */
    private static final int MAX_RECORDS = 200_000

    private final Path storeRoot

    private LineageStore(Path storeRoot) {
        this.storeRoot = storeRoot
    }

    /**
     * Locate the lineage store for a project directory, or {@code null} when
     * none exists. Only the default {@code <projectDir>/.lineage} location is
     * auto-detected; a custom {@code lineage.store.location} is not resolved
     * here (the caller falls back to symlink reconstruction in that case).
     */
    static LineageStore locate(Path projectDir) {
        if( projectDir == null )
            return null
        try {
            final root = projectDir.resolve(DEFAULT_DIR)
            return Files.isDirectory(root) ? new LineageStore(root.toAbsolutePath().normalize()) : null
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not access lineage store under '${projectDir}': ${e.message}"
            return null
        }
    }

    /**
     * The subset of a run's Nextflow environment that {@code nf-diff} can
     * recover from the lineage {@code WorkflowRun} record's {@code metadata}.
     * Every field is nullable: it is {@code null} either because the store has
     * no {@code WorkflowRun} for the session, or because that Nextflow build did
     * not populate the field. Note there is deliberately no {@code plugins}
     * field — Nextflow does not persist per-run plugin versions anywhere in the
     * lineage store (nor in the history file or the task cache), so they cannot
     * be compared.
     */
    @CompileStatic
    static class RunEnv {
        String pipeline
        String pipelinePath
        String nextflowVersion
        String nextflowBuild
        String containerEngine
        Boolean waveEnabled
        Boolean fusionEnabled
    }

    /**
     * Read the Nextflow version and runtime environment for the run identified
     * by {@code sessionId} from its {@code WorkflowRun} lineage record's
     * {@code metadata} block. Returns {@code null} when the store holds no
     * {@code WorkflowRun} for that session (so the caller leaves the fields
     * unset and the report simply omits the corresponding rows).
     */
    RunEnv environmentForSession(UUID sessionId) {
        if( sessionId == null )
            return null

        final session = sessionId.toString()
        final slurper = new JsonSlurper()
        int scanned = 0
        try {
            for( Path file : recordFiles() ) {
                if( ++scanned > MAX_RECORDS ) {
                    log.warn "nf-diff: lineage store under '${storeRoot}' exceeded ${MAX_RECORDS} records; stopping scan"
                    break
                }
                final record = parse(slurper, file)
                if( record == null || kindOf(record) != 'WorkflowRun' )
                    continue
                final spec = specOf(record)
                if( asText(spec.get('sessionId')) != session )
                    continue
                return toRunEnv(spec.get('metadata'))
            }
        }
        catch( Exception e ) {
            log.warn "nf-diff: failed to read lineage WorkflowRun for session ${session} under '${storeRoot}': ${e.message}"
            return null
        }
        log.debug "nf-diff: lineage store under '${storeRoot}' has no WorkflowRun record for session ${session}"
        return null
    }

    /** Build a {@link RunEnv} from a {@code WorkflowRun.metadata} map, tolerating missing sub-objects. */
    private static RunEnv toRunEnv(Object metadata) {
        final meta = metadata instanceof Map ? (Map) metadata : null
        if( meta == null )
            return null
        final nf = meta.get('nextflow') instanceof Map ? (Map) meta.get('nextflow') : null
        final wave = meta.get('wave') instanceof Map ? (Map) meta.get('wave') : null
        final fusion = meta.get('fusion') instanceof Map ? (Map) meta.get('fusion') : null
        // Prefer the git repository (e.g. nf-core/rnaseq) as the pipeline name;
        // fall back to projectName, which for a local run is the script name
        // (e.g. main.nf) — the same label Nextflow itself reports.
        final pipeline = asText(meta.get('repository')) ?: asText(meta.get('projectName'))
        return new RunEnv(
                pipeline        : pipeline,
                pipelinePath    : asText(meta.get('scriptFile')),
                nextflowVersion : nf != null ? asText(nf.get('version')) : null,
                nextflowBuild   : nf != null ? asText(nf.get('build')) : null,
                containerEngine : asText(meta.get('containerEngine')),
                waveEnabled     : wave != null ? asBool(wave.get('enabled')) : null,
                fusionEnabled   : fusion != null ? asBool(fusion.get('enabled')) : null )
    }

    /** Coerce a lineage JSON value to a Boolean, or null when absent/unrecognised. */
    private static Boolean asBool(Object value) {
        if( value instanceof Boolean )
            return (Boolean) value
        if( value instanceof CharSequence )
            return Boolean.valueOf(value.toString())
        return null
    }

    /**
     * Reconstruct the process&#8594;process edges for the run identified by
     * {@code sessionId}. Returns {@code null} when the store holds no records
     * for that session (so the caller can fall back), or an edge set (possibly
     * empty — a linear single-task run legitimately has no edges) when it does.
     */
    Set<DagEdge> edgesForSession(UUID sessionId) {
        if( sessionId == null )
            return null

        final session = sessionId.toString()

        // Pass 1: index every TaskRun for this session by its LID hash -> process.
        final taskHashToProcess = new LinkedHashMap<String,String>()
        // Retain each TaskRun's parsed record so pass 2 can read its inputs
        // without re-parsing the file.
        final taskRecords = new ArrayList<Map<String,Object>>()

        final slurper = new JsonSlurper()
        int scanned = 0
        try {
            final iterator = recordFiles()
            for( Path file : iterator ) {
                if( ++scanned > MAX_RECORDS ) {
                    log.warn "nf-diff: lineage store under '${storeRoot}' exceeded ${MAX_RECORDS} records; stopping scan"
                    break
                }
                final record = parse(slurper, file)
                if( record == null )
                    continue
                if( kindOf(record) != 'TaskRun' )
                    continue
                final spec = specOf(record)
                if( asText(spec.get('sessionId')) != session )
                    continue
                final hash = lidHash(file)
                final process = processName(asText(spec.get('name')))
                if( hash != null && process != null ) {
                    taskHashToProcess.put(hash, process)
                    taskRecords.add(record)
                }
            }
        }
        catch( Exception e ) {
            log.warn "nf-diff: failed to read lineage store under '${storeRoot}': ${e.message}"
            return null
        }

        if( taskRecords.isEmpty() ) {
            log.debug "nf-diff: lineage store under '${storeRoot}' has no TaskRun records for session ${session}"
            return null
        }

        // Pass 2: for each consumer TaskRun, resolve its input LIDs back to the
        // producing task's process and record a producer -> consumer edge.
        final edges = new LinkedHashSet<DagEdge>()
        taskRecords.each { Map<String,Object> record ->
            final spec = specOf(record)
            final consumer = processName(asText(spec.get('name')))
            if( consumer == null )
                return
            inputLidHashes(spec.get('input')).each { String producerHash ->
                final producer = taskHashToProcess.get(producerHash)
                if( producer != null )
                    edges << new DagEdge(from: producer, to: consumer)
            }
        }
        return edges
    }

    /**
     * Extract every {@code lid://<hash>[/...]} reference reachable from a
     * {@code TaskRun.input} value (a list of inputs, each with a nested
     * {@code value} that may be a string, a list, or a map), returning the
     * leading LID hash of each. Only the first path segment is kept, since that
     * is the producing task's (or workflow run's) LID.
     */
    private static Set<String> inputLidHashes(Object input) {
        final hashes = new LinkedHashSet<String>()
        collectLidHashes(input, hashes)
        return hashes
    }

    private static void collectLidHashes(Object node, Set<String> acc) {
        if( node == null )
            return
        if( node instanceof CharSequence ) {
            final h = lidHashOf(node.toString())
            if( h != null )
                acc << h
            return
        }
        if( node instanceof Map ) {
            ((Map) node).values().each { Object v -> collectLidHashes(v, acc) }
            return
        }
        if( node instanceof Iterable ) {
            ((Iterable) node).each { Object v -> collectLidHashes(v, acc) }
        }
    }

    /** {@code lid://abc123/foo/bar} -> {@code abc123}; non-LID strings -> null. */
    private static String lidHashOf(String value) {
        if( value == null || !value.startsWith('lid://') )
            return null
        final rest = value.substring('lid://'.length())
        if( !rest )
            return null
        final slash = rest.indexOf('/')
        return slash >= 0 ? rest.substring(0, slash) : rest
    }

    /**
     * The LID hash of a record file is the name of the entry directory that
     * contains its {@code .data.json}. For a {@code TaskRun} this is the single
     * hash segment (a nested output subpath would belong to a FileOutput, not a
     * TaskRun, so this is safe here).
     */
    private String lidHash(Path recordFile) {
        final parent = recordFile.getParent()
        return parent != null ? parent.getFileName()?.toString() : null
    }

    /**
     * Derive the process name from a task's lineage {@code name}, dropping the
     * trailing task-index/tag (e.g. {@code "ALIGN:BWA (sample1)"} -> {@code
     * "ALIGN:BWA"}) so it matches the process-level edges the rest of nf-diff
     * compares.
     */
    private static String processName(String taskName) {
        if( !taskName )
            return null
        final trimmed = taskName.replaceFirst(/\s*\(.*\)\s*$/, '').trim()
        return trimmed ?: taskName.trim()
    }

    /** All {@code .data.json} record files under the store root. */
    private List<Path> recordFiles() {
        final files = new ArrayList<Path>()
        Files.walkFileTree(storeRoot, new SimpleFileVisitor<Path>() {
            @Override
            FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if( file.getFileName()?.toString() == RECORD_FILE )
                    files.add(file)
                return FileVisitResult.CONTINUE
            }

            @Override
            FileVisitResult visitFileFailed(Path file, IOException exc) {
                log.debug "nf-diff: could not read lineage record '${file}': ${exc.message}"
                return FileVisitResult.CONTINUE
            }
        })
        return files
    }

    @SuppressWarnings('unchecked')
    private static Map<String,Object> parse(JsonSlurper slurper, Path file) {
        try {
            final parsed = slurper.parse(file.toFile())
            return parsed instanceof Map ? (Map<String,Object>) parsed : null
        }
        catch( Exception e ) {
            log.debug "nf-diff: skipping unparseable lineage record '${file}': ${e.message}"
            return null
        }
    }

    private static String asText(Object value) {
        return value != null ? value.toString() : null
    }

    /**
     * The record's discriminator. Nextflow's {@code lineage/v1beta1} envelope
     * names it {@code kind} and nests the payload under {@code spec}; the
     * pre-{@code v1beta1} flat records used {@code type} at the top level. We
     * accept either so a store from either era reconstructs.
     */
    private static String kindOf(Map<String,Object> record) {
        final kind = record.get('kind')
        return kind != null ? kind.toString() : asText(record.get('type'))
    }

    /**
     * The record's payload map: the {@code spec} sub-object for a
     * {@code v1beta1} envelope, or the record itself for a legacy flat record
     * (where {@code sessionId}/{@code name}/{@code input} live at the top level).
     */
    @SuppressWarnings('unchecked')
    private static Map<String,Object> specOf(Map<String,Object> record) {
        final spec = record.get('spec')
        return spec instanceof Map ? (Map<String,Object>) spec : record
    }
}
