package io.seqera.nf.diff

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.cache.CacheDB
import nextflow.cache.DefaultCacheStore
import nextflow.trace.TraceRecord
import nextflow.util.HistoryFile

/**
 * Loads a {@link RunSnapshot} for a given run identifier by reading the local
 * {@code .nextflow/history} file and the run's LevelDB cache directory
 * ({@code .nextflow/cache/<sessionId>}), reusing Nextflow's own internal APIs.
 */
@Slf4j
@CompileStatic
class RunLoader {

    /** Project directory that contains the {@code .nextflow/} folder. */
    private final Path nextflowDir

    RunLoader(Path baseDir) {
        this.nextflowDir = baseDir.resolve('.nextflow')
    }

    /**
     * Resolve {@code idOrName} against the history file and hydrate a full
     * snapshot including every cached task.
     *
     * @throws IllegalArgumentException if the run cannot be found.
     */
    RunSnapshot load(String idOrName) {
        final historyPath = nextflowDir.resolve('history')
        if( !historyPath.toFile().exists() )
            throw new IllegalArgumentException("No Nextflow history found at ${historyPath} — run from a project directory or pass --dir")

        final history = new HistoryFile(historyPath.toFile())
        final matches = history.findByIdOrName(idOrName)
        if( !matches )
            throw new IllegalArgumentException("Run '${idOrName}' not found in ${historyPath}")
        if( matches.size() > 1 )
            throw new IllegalArgumentException("Run '${idOrName}' is ambiguous — matched ${matches.size()} entries; use a longer session id")

        final record = matches.first()

        final snapshot = new RunSnapshot(
                requestedId : idOrName,
                runName     : record.runName,
                sessionId   : record.sessionId,
                status      : record.status,
                revisionId  : record.revisionId,
                command     : record.command,
                timestamp   : record.timestamp,
                durationMillis: record.duration?.toMillis() )

        snapshot.tasks = loadTasks(record.sessionId, record.runName)
        log.debug "nf-diff: loaded ${snapshot.tasks.size()} task(s) for run '${record.runName}' (${record.sessionId})"
        return snapshot
    }

    private List<TaskInfo> loadTasks(UUID sessionId, String runName) {
        final tasks = new ArrayList<TaskInfo>()
        CacheDB db = null
        try {
            db = new CacheDB(new DefaultCacheStore(sessionId, runName, nextflowDir)).openForRead()
            db.eachRecord { TraceRecord trace ->
                tasks.add(toTaskInfo(trace))
            }
        }
        catch( Exception e ) {
            throw new IllegalArgumentException("Unable to read cache for run '${runName}' (${sessionId}): ${e.message}", e)
        }
        finally {
            db?.close()
        }
        // stable ordering: by process then task name
        tasks.sort { TaskInfo a, TaskInfo b ->
            final byProc = (a.process ?: '') <=> (b.process ?: '')
            byProc != 0 ? byProc : ((a.name ?: '') <=> (b.name ?: ''))
        }
        return tasks
    }

    private static TaskInfo toTaskInfo(TraceRecord trace) {
        final store = trace.getStore()

        final display = new LinkedHashMap<String,String>()
        TraceRecord.FIELDS.keySet().each { String field ->
            final formatted = trace.getFmtStr(field)
            if( formatted != null )
                display[field] = formatted
        }

        final submit = asLong(store.get('submit'))
        final complete = asLong(store.get('complete'))
        final realtime = asLong(store.get('realtime'))

        final info = new TaskInfo(
                hash      : asString(store.get('hash')),
                process   : asString(store.get('process')),
                name      : asString(store.get('name')),
                tag       : asString(store.get('tag')),
                status    : asString(store.get('status')),
                exit      : asString(store.get('exit')),
                container : asString(store.get('container')),
                script    : asString(store.get('script')),
                workdir   : asString(store.get('workdir')),
                cached    : trace.cached,
                realtimeMillis : realtime ?: 0L,
                durationMillis : (submit != null && complete != null) ? (complete - submit) : (realtime ?: 0L),
                display   : display,
                raw       : new LinkedHashMap<String,Object>(store) )

        return info
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null
    }

    private static Long asLong(Object value) {
        if( value == null )
            return null
        if( value instanceof Number )
            return ((Number) value).longValue()
        try {
            return Long.parseLong(value.toString())
        }
        catch( NumberFormatException ignored ) {
            return null
        }
    }
}
