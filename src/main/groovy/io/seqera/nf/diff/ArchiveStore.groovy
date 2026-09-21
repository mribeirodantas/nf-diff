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
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * A local, directory-backed archive of {@link RunSnapshot}s so a run can be
 * compared later even after its project folder (and its {@code .nextflow/}
 * history + cache) has been deleted.
 *
 * <h2>Layout</h2>
 * Each run is written as a single JSON document {@code <dir>/<sessionId>.json}
 * (falling back to a sanitised run name when a session id is absent). This is
 * the <em>lightweight</em> archive: it carries the run-level metadata and every
 * task's trace fields, which is everything the default comparison
 * (metadata, parameters, config, process topology, per-task detail, performance)
 * needs — none of which depends on the original work directories still existing.
 *
 * <p>In <em>complete</em> mode the store additionally copies each task's regular
 * work-directory files (its outputs and the {@code .command.out/.err/.log}
 * logs) into {@code <dir>/<sessionId>/tasks/<hash>/} and rewrites the stored
 * task's {@code workdir} to point there, so {@link OutputComparator} and
 * {@link LogComparator} work verbatim against the archived run. Staged input
 * symlinks are intentionally not copied — they would dangle once the originals
 * are gone — so work-dir symlink DAG reconstruction ({@code --diff-dag}) is not
 * preserved by archiving; it is best-effort/informational anyway.
 *
 * <p>The store is deliberately outside any single project directory (default
 * {@code $NXF_HOME/nf-diff/archive}) so archived runs survive the deletion of
 * the runs that produced them.
 */
@Slf4j
@CompileStatic
class ArchiveStore {

    /** On-disk schema version, bumped only on an incompatible format change. */
    static final int SCHEMA = 1

    /** Environment variable that overrides the archive directory for readers/writers. */
    static final String DIR_ENV = 'NXF_DIFF_ARCHIVE_DIR'

    private final Path dir

    ArchiveStore(Path dir) {
        this.dir = dir
    }

    /** The archive directory this store reads from and writes to. */
    Path getDir() { return dir }

    /**
     * The default archive location: {@code $NXF_HOME/nf-diff/archive}, falling
     * back to {@code ~/.nextflow/nf-diff/archive} when {@code NXF_HOME} is unset.
     */
    static Path defaultDir() {
        final home = System.getenv('NXF_HOME')
        final base = home ? Paths.get(home) : Paths.get(System.getProperty('user.home'), '.nextflow')
        return base.resolve('nf-diff').resolve('archive')
    }

    /**
     * Resolve the effective archive directory. Precedence: an explicitly
     * configured value, then the {@link #DIR_ENV} environment variable, then
     * {@link #defaultDir}. Shared by the writer ({@link ArchiveConfig}) and the
     * reader ({@link RunLoader}) so a custom location stays consistent across both.
     */
    static Path resolveDir(String configured) {
        if( configured )
            return Paths.get(configured)
        final env = System.getenv(DIR_ENV)
        if( env )
            return Paths.get(env)
        return defaultDir()
    }

    /**
     * Archive {@code snap}. In {@code complete} mode the task work-dir files are
     * copied first and each task's {@code workdir} is rewritten to its archived
     * location, so the JSON that is then written references the durable copies.
     *
     * @return the (possibly work-dir-rewritten) snapshot that was archived.
     */
    RunSnapshot save(RunSnapshot snap, boolean complete) {
        if( !snap.sessionId && !snap.runName )
            throw new IllegalStateException('cannot archive a run without a session id or run name')
        Files.createDirectories(dir)
        final key = storageKey(snap)
        if( complete )
            archiveTaskFiles(snap, dir.resolve(key))
        final json = JsonOutput.prettyPrint(JsonOutput.toJson(toMap(snap, complete)))
        final file = dir.resolve("${key}.json")
        Files.write(file, json.getBytes('UTF-8'))
        log.debug "nf-diff: archived run '${snap.runName}' (${key}, ${complete ? 'complete' : 'lightweight'}) to ${file}"
        return snap
    }

    /** True when at least one archived run matches {@code idOrName}. */
    boolean has(String idOrName) {
        return !findMatches(idOrName).isEmpty()
    }

    /**
     * Load an archived run by run name, full session id, or session-id prefix.
     *
     * @throws IllegalArgumentException when no entry matches, or the id is
     *         ambiguous across multiple archived runs.
     */
    RunSnapshot load(String idOrName) {
        final matches = findMatches(idOrName)
        if( !matches )
            throw new IllegalArgumentException("Run '${idOrName}' not found in archive ${dir}")
        if( matches.size() > 1 ) {
            final labels = matches.collect { RunSnapshot s -> s.label() }.join(', ')
            throw new IllegalArgumentException("Run '${idOrName}' is ambiguous in archive — matched ${matches.size()} entries: ${labels}. Use a longer session id.")
        }
        final snap = matches.first()
        snap.requestedId = idOrName
        return snap
    }

    /** Every archived run, most-recently-archived first is not guaranteed. */
    List<RunSnapshot> list() {
        return readAll { Map m -> true }
    }

    // --- matching ---------------------------------------------------------

    private List<RunSnapshot> findMatches(String idOrName) {
        return readAll { Map m -> matchesId(m, idOrName) }
    }

    private static boolean matchesId(Map m, String id) {
        final name = m.get('runName') as String
        if( id == name )
            return true
        final sid = m.get('sessionId') as String
        if( sid == null )
            return false
        return sid == id || sid.startsWith(id)
    }

    /** Read every {@code *.json} entry, keeping those the predicate accepts. */
    private List<RunSnapshot> readAll(@groovy.transform.stc.ClosureParams(value = groovy.transform.stc.SimpleType, options = ['java.util.Map']) Closure<Boolean> keep) {
        if( !Files.isDirectory(dir) )
            return []
        final out = new ArrayList<RunSnapshot>()
        Files.newDirectoryStream(dir, '*.json').withCloseable { stream ->
            stream.each { Path f ->
                try {
                    final parsed = new JsonSlurper().parse(f.toFile())
                    if( !(parsed instanceof Map) )
                        return
                    final m = (Map) parsed
                    if( keep.call(m) )
                        out.add(fromMap(m))
                }
                catch( Exception e ) {
                    log.debug "nf-diff: could not read archive entry '${f}': ${e.message}"
                }
            }
        }
        return out
    }

    // --- serialization ----------------------------------------------------

    private static String storageKey(RunSnapshot s) {
        return s.sessionId ? s.sessionId.toString() : sanitize(s.runName)
    }

    /** Replace anything but a safe filename character so ids map to valid paths. */
    private static String sanitize(String raw) {
        return (raw ?: 'run').replaceAll(/[^A-Za-z0-9._-]/, '_')
    }

    private static Map toMap(RunSnapshot s, boolean complete) {
        return [
                schema         : SCHEMA,
                mode           : complete ? 'complete' : 'lightweight',
                archivedAt     : System.currentTimeMillis(),
                runName        : s.runName,
                sessionId      : s.sessionId?.toString(),
                status         : s.status,
                revisionId     : s.revisionId,
                command        : s.command,
                timestamp      : s.timestamp?.time,
                durationMillis : s.durationMillis,
                pipeline       : s.pipeline,
                pipelinePath   : s.pipelinePath,
                nextflowVersion: s.nextflowVersion,
                nextflowBuild  : s.nextflowBuild,
                containerEngine: s.containerEngine,
                waveEnabled    : s.waveEnabled,
                fusionEnabled  : s.fusionEnabled,
                tasks          : s.tasks.collect { TaskInfo t -> taskToMap(t) },
        ] as Map
    }

    private static Map taskToMap(TaskInfo t) {
        return [
                hash          : t.hash,
                process       : t.process,
                name          : t.name,
                tag           : t.tag,
                status        : t.status,
                exit          : t.exit,
                container     : t.container,
                script        : t.script,
                workdir       : t.workdir,
                cached        : t.cached,
                durationMillis: t.durationMillis,
                realtimeMillis: t.realtimeMillis,
                display       : t.display,
                raw           : jsonSafeMap(t.raw),
        ] as Map
    }

    /**
     * Coerce a raw trace map to JSON-safe values. Numbers, booleans, strings and
     * nulls pass through unchanged (so {@link TaskInfo#numeric} still works after
     * a round-trip); anything else is stringified rather than risk
     * {@code JsonOutput} serialising an opaque object graph.
     */
    private static Map jsonSafeMap(Map<String,Object> raw) {
        final out = new LinkedHashMap<String,Object>()
        raw?.each { Object k, Object v ->
            out.put(k?.toString(), jsonSafe(v))
        }
        return out
    }

    private static Object jsonSafe(Object v) {
        if( v == null || v instanceof String || v instanceof Boolean || v instanceof Number )
            return v
        return v.toString()
    }

    /** Rebuild a {@link RunSnapshot} from its archived JSON map. */
    static RunSnapshot fromMap(Map m) {
        final sidStr = m.get('sessionId') as String
        final tsMillis = m.get('timestamp') as Number
        final dur = m.get('durationMillis') as Number
        final snap = new RunSnapshot(
                runName        : m.get('runName') as String,
                sessionId      : sidStr ? UUID.fromString(sidStr) : null,
                status         : m.get('status') as String,
                revisionId     : m.get('revisionId') as String,
                command        : m.get('command') as String,
                timestamp      : tsMillis != null ? new Date(tsMillis.longValue()) : null,
                durationMillis : dur?.longValue(),
                pipeline       : m.get('pipeline') as String,
                pipelinePath   : m.get('pipelinePath') as String,
                nextflowVersion: m.get('nextflowVersion') as String,
                nextflowBuild  : m.get('nextflowBuild') as String,
                containerEngine: m.get('containerEngine') as String,
                waveEnabled    : m.get('waveEnabled') as Boolean,
                fusionEnabled  : m.get('fusionEnabled') as Boolean )
        final rawTasks = (m.get('tasks') ?: []) as List
        snap.tasks = rawTasks.collect { Object o -> taskFromMap((Map) o) }
        return snap
    }

    private static TaskInfo taskFromMap(Map m) {
        final dur = m.get('durationMillis') as Number
        final rt = m.get('realtimeMillis') as Number
        final t = new TaskInfo(
                hash          : m.get('hash') as String,
                process       : m.get('process') as String,
                name          : m.get('name') as String,
                tag           : m.get('tag') as String,
                status        : m.get('status') as String,
                exit          : m.get('exit') as String,
                container     : m.get('container') as String,
                script        : m.get('script') as String,
                workdir       : m.get('workdir') as String,
                cached        : (m.get('cached') as Boolean) ?: false,
                durationMillis: dur != null ? dur.longValue() : 0L,
                realtimeMillis: rt != null ? rt.longValue() : 0L )
        final disp = (m.get('display') ?: [:]) as Map
        disp.each { Object k, Object v -> t.display.put(k?.toString(), v?.toString()) }
        final raw = (m.get('raw') ?: [:]) as Map
        raw.each { Object k, Object v -> t.raw.put(k?.toString(), v) }
        return t
    }

    // --- complete-mode file copy -----------------------------------------

    /**
     * Copy each task's regular work-dir files into {@code runRoot/tasks/<key>/}
     * and repoint the task's {@code workdir} at that copy. Tasks whose work dir
     * is missing locally (already cleaned up, or remote) are left untouched, so
     * complete mode degrades gracefully to lightweight for those tasks.
     */
    private void archiveTaskFiles(RunSnapshot snap, Path runRoot) {
        snap.tasks.each { TaskInfo t ->
            if( !t.workdir )
                return
            Path wd
            try {
                wd = Paths.get(t.workdir)
            }
            catch( Exception e ) {
                log.debug "nf-diff: task '${t.matchKey()}' work dir '${t.workdir}' is not a usable path: ${e.message}"
                return
            }
            if( !Files.isDirectory(wd) ) {
                log.debug "nf-diff: task '${t.matchKey()}' work dir '${t.workdir}' is not available locally; skipping file copy"
                return
            }
            final dest = runRoot.resolve('tasks').resolve(sanitize(t.hash ?: t.matchKey()))
            copyRegularFiles(wd, dest)
            t.workdir = dest.toAbsolutePath().toString()
        }
    }

    /**
     * Copy every regular, non-symlink file under {@code src} into {@code dest},
     * preserving the relative layout. Symlinks (staged inputs) and directories
     * are skipped; the outputs and {@code .command.*} logs are exactly the
     * regular files, which is what the output/log comparators read.
     */
    private static void copyRegularFiles(Path src, Path dest) {
        try {
            Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if( attrs.isSymbolicLink() || Files.isSymbolicLink(file) || !attrs.isRegularFile() )
                        return FileVisitResult.CONTINUE
                    final target = dest.resolve(src.relativize(file).toString())
                    try {
                        if( target.parent != null )
                            Files.createDirectories(target.parent)
                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    catch( Exception e ) {
                        log.debug "nf-diff: could not archive '${file}': ${e.message}"
                    }
                    return FileVisitResult.CONTINUE
                }

                @Override
                FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug "nf-diff: could not read '${file}' while archiving: ${exc.message}"
                    return FileVisitResult.CONTINUE
                }
            })
        }
        catch( Exception e ) {
            log.warn "nf-diff: failed to archive work dir '${src}': ${e.message}"
        }
    }
}
