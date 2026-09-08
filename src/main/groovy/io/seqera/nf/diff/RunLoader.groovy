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
        final history = openHistory()
        final matches = history.findByIdOrName(idOrName)
        if( !matches )
            throw new IllegalArgumentException("Run '${idOrName}' not found in ${historyPath()}. Recent runs: ${recentNamesHint(history)}")
        if( matches.size() > 1 ) {
            final labels = matches.collect { HistoryFile.Record r -> "${r.runName} (${shortId(r.sessionId)})" }.join(', ')
            throw new IllegalArgumentException("Run '${idOrName}' is ambiguous — matched ${matches.size()} entries: ${labels}. Use a longer session id.")
        }

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

    /**
     * Resolve the run pair for {@code --last[=N]}: compare the run {@code back}
     * positions before the most recent (A) against the most recent run (B).
     * {@code back == 1} (the bare {@code --last}) therefore compares the two
     * most recent runs, matching the original behaviour. The returned list is
     * {@code [olderId, latestId]}.
     *
     * @throws IllegalArgumentException if {@code back < 1} or history has fewer
     *         than {@code back + 1} runs.
     */
    List<String> lastPair(int back) {
        if( back < 1 )
            throw new IllegalArgumentException("--last must be a positive number of runs back, got ${back}")
        final all = openHistory().findAll()
        final needed = back + 1
        if( all.size() < needed )
            throw new IllegalArgumentException("--last=${back} needs at least ${needed} runs in history, but only ${all.size()} found in ${historyPath()}")
        final latest = all.get(all.size() - 1)
        final older = all.get(all.size() - 1 - back)
        return [runId(older), runId(latest)]
    }

    /** Path to the {@code .nextflow/history} file. */
    private Path historyPath() {
        return nextflowDir.resolve('history')
    }

    /** Open the history file, validating that it exists first. */
    private HistoryFile openHistory() {
        final path = historyPath()
        if( !path.toFile().exists() )
            throw new IllegalArgumentException("No Nextflow history found at ${path} — run from a project directory or pass --dir")
        return new HistoryFile(path.toFile())
    }

    /** A short, human-friendly list of the most recent run names for error hints. */
    private static String recentNamesHint(HistoryFile history, int max = 10) {
        final all = history.findAll()
        if( !all )
            return '(none)'
        final names = all.collect { HistoryFile.Record r -> runId(r) }
        final tail = names.size() > max ? names.subList(names.size() - max, names.size()) : names
        return tail.join(', ')
    }

    /** Prefer the run name, falling back to the session id, for identifying a record. */
    private static String runId(HistoryFile.Record r) {
        return r.runName ?: r.sessionId?.toString()
    }

    /** First 8 characters of a session id, for compact display. Package-private for unit testing. */
    static String shortId(UUID id) {
        return id ? id.toString().substring(0, 8) : '????????'
    }

    /** Max attempts to open a run's LevelDB cache when a lock is contended. */
    private static final int LOCK_MAX_ATTEMPTS = 5
    /** Base backoff (ms) between lock-acquisition retries; grows linearly. */
    private static final long LOCK_BACKOFF_MS = 300L

    private List<TaskInfo> loadTasks(UUID sessionId, String runName) {
        final tasks = new ArrayList<TaskInfo>()
        CacheDB db = null
        try {
            db = openForReadWithRetry(sessionId, runName)
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

    /**
     * Open a run's cache for reading, retrying on transient lock-acquisition
     * failures. The local LevelDB cache holds an exclusive lock while open, so
     * a concurrent reader (e.g. {@code nextflow log}, or an IDE/language-server
     * indexing {@code .nextflow/cache}) can briefly block the open. These
     * failures are transient, so we back off and retry a few times before
     * giving up.
     */
    private CacheDB openForReadWithRetry(UUID sessionId, String runName) {
        Exception last = null
        for( int attempt = 1; attempt <= LOCK_MAX_ATTEMPTS; attempt++ ) {
            try {
                return new CacheDB(new DefaultCacheStore(sessionId, runName, nextflowDir)).openForRead()
            }
            catch( Exception e ) {
                if( !isLockError(e) || attempt == LOCK_MAX_ATTEMPTS )
                    throw e
                last = e
                final waitMs = LOCK_BACKOFF_MS * attempt
                log.warn "nf-diff: cache for '${runName}' is locked (attempt ${attempt}/${LOCK_MAX_ATTEMPTS}); retrying in ${waitMs}ms"
                // Use Thread.sleep, not Groovy's sleep(): the latter swallows
                // InterruptedException and clears the flag, so a Ctrl-C during
                // a contended cache open would be ignored. Restore the flag and
                // abort the retry loop instead.
                try {
                    Thread.sleep(waitMs)
                }
                catch( InterruptedException ie ) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        // unreachable, but keeps the compiler happy about the return type
        throw last
    }

    /**
     * Message fragments (compared case-insensitively) that mark a <em>transient</em>
     * cache-lock failure worth retrying. The LevelDB store Nextflow opens holds
     * an exclusive OS lock, so a concurrent reader surfaces one of these. Kept as
     * a small allow-list rather than a single literal so a phrasing change in
     * LevelDB/Nextflow does not silently disable the retry path — but every entry
     * still requires lock-specific wording, so we never mistake an unrelated
     * failure for a transient one and retry it pointlessly. All entries must be
     * lower case.
     */
    private static final List<String> LOCK_SIGNATURES = [
            'unable to acquire lock',            // LevelDB JNI: another process holds the db
            'resource temporarily unavailable',  // EAGAIN surfaced by the native flock
            'another process is using this database',
            'lock currently held',
    ]

    /**
     * True when {@code e} (or any exception in its cause chain) looks like a
     * transient cache-lock contention failure. Package-private for direct unit
     * testing.
     */
    static boolean isLockError(Throwable e) {
        Throwable t = e
        while( t != null ) {
            final msg = t.message?.toLowerCase(Locale.ROOT)
            if( msg && LOCK_SIGNATURES.any { String sig -> msg.contains(sig) } )
                return true
            t = t.cause
        }
        return false
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
                // No defensive copy: CacheDB.eachRecord deserializes a fresh
                // TraceRecord (hence a fresh store map) per iteration, and the
                // record is discarded here, so nothing else can mutate or reuse
                // this map. Copying it just doubled per-task memory on large
                // runs for no isolation benefit.
                raw       : store )

        return info
    }

    /** Coerce a raw trace-store value to a String. Package-private for unit testing. */
    static String asString(Object value) {
        return value != null ? value.toString() : null
    }

    /**
     * Coerce a raw trace-store value to a Long, returning null for a null or
     * non-numeric value. Package-private for unit testing.
     */
    static Long asLong(Object value) {
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
