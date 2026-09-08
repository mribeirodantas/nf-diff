package io.seqera.nf.diff

import java.nio.file.Path

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.FieldDiff
import io.seqera.nf.diff.DiffResult.Kind
import io.seqera.nf.diff.DiffResult.ProcessDiff
import io.seqera.nf.diff.DiffResult.TaskDiff

/**
 * Computes a {@link DiffResult} between two {@link RunSnapshot} instances across
 * these layers: run metadata, parameters, resolved configuration, process
 * topology, and per-task detail.
 */
@Slf4j
@CompileStatic
class RunComparator {

    /**
     * Per-task trace fields compared for the "tiniest details" view, in the
     * order they should appear in the report. All are pulled from the task's
     * formatted display map.
     */
    static final List<String> TASK_FIELDS = [
            'hash', 'status', 'exit', 'container', 'script',
            'cpus', 'memory', 'time', 'disk',
            'realtime', '%cpu', 'peak_rss', 'peak_vmem',
            'rchar', 'wchar', 'attempt', 'queue', 'workdir', 'tag'
    ]

    /**
     * Numeric task metrics compared for performance regressions, as
     * {@code [rawField, label]}. All are "larger is worse" (longer runtime,
     * more memory), so a positive delta from A to B is a regression.
     */
    static final List<List<String>> PERF_METRICS = [
            ['realtime',  'Realtime'],
            ['peak_rss',  'Peak RSS'],
            ['peak_vmem', 'Peak VMEM'],
    ]

    /** Default percentage change beyond which a metric is flagged as a regression. */
    static final double DEFAULT_PERF_THRESHOLD = 25.0d

    /**
     * Task fields that vary between essentially any two runs even when the work
     * is identical (unique work dir, timing, and measured resource usage). These
     * are flagged {@code obvious} and excluded from change detection unless the
     * verbose view is enabled.
     */
    static final Set<String> OBVIOUS_TASK_FIELDS = [
            'realtime', '%cpu', 'peak_rss', 'peak_vmem', 'rchar', 'wchar', 'workdir'
    ] as Set

    /**
     * Run-metadata labels shown for context rather than treated as meaningful
     * changes. Besides the fields that always differ between two distinct runs
     * (run name, session id, timing), this includes the raw {@code Command}
     * string: the structured Parameters layer is now the authoritative view of
     * what changed on the command line, so the opaque command string is context.
     */
    static final Set<String> OBVIOUS_METADATA = [
            'Run name', 'Session ID', 'Launched', 'Wall duration', 'Total task realtime', 'Command'
    ] as Set

    /**
     * Nextflow options that commonly differ between two runs without reflecting
     * a meaningful change in what was executed (run naming, resume, monitoring).
     * Flagged {@code obvious} so they are context rather than changes unless the
     * verbose view is enabled.
     */
    static final Set<String> OBVIOUS_PARAM_KEYS = [
            '-name', '-resume', '-ansi-log', '-with-tower', '-with-weblog', '-bg'
    ] as Set

    /** When true, always-changing fields are treated as meaningful changes. */
    private final boolean showObvious

    /** Restricts which processes/tasks are compared (defaults to all). */
    private final ProcessFilter filter

    /**
     * Project directory used to resolve each run's {@code -params-file} and
     * {@code nextflow.config}. When null, the parameters layer sees only literal
     * command-line flags and the configuration layer is skipped.
     */
    private final Path baseDir

    /** Percentage change beyond which a task metric is flagged as a regression. */
    private final double perfThreshold

    /** When true, compare the output files each matched task wrote to its work dir. */
    private final boolean diffOutputs

    /**
     * Maximum file size (bytes) to hash when comparing same-size outputs; 0
     * means no limit. Only meaningful when {@link #diffOutputs} is set.
     */
    private final long outputsMaxBytes

    /** When true, compare the standard log files each matched task wrote. */
    private final boolean diffLogs

    /**
     * Maximum tail lines kept per log file when {@link #diffLogs} is set.
     * Zero falls back to {@link LogComparator#DEFAULT_MAX_LINES}.
     */
    private final int logsMaxLines

    RunComparator(boolean showObvious = false, ProcessFilter filter = null, Path baseDir = null,
                  double perfThreshold = DEFAULT_PERF_THRESHOLD,
                  boolean diffOutputs = false, long outputsMaxBytes = 0L,
                  boolean diffLogs = false, int logsMaxLines = LogComparator.DEFAULT_MAX_LINES) {
        this.showObvious = showObvious
        this.filter = filter ?: ProcessFilter.of([], [])
        this.baseDir = baseDir
        this.perfThreshold = perfThreshold
        this.diffOutputs = diffOutputs
        this.outputsMaxBytes = outputsMaxBytes
        this.diffLogs = diffLogs
        this.logsMaxLines = logsMaxLines
    }

    DiffResult compare(RunSnapshot a, RunSnapshot b) {
        final result = new DiffResult(runA: a, runB: b, showObvious: showObvious, perfThreshold: perfThreshold)
        result.metadata = compareMetadata(a, b)
        result.params = compareParams(a, b)
        computeConfig(result, a, b)
        result.processes = compareProcesses(a, b).findAll { ProcessDiff pd -> filter.accepts(pd.process) }
        result.tasks = compareTasks(a, b).findAll { TaskDiff td -> filter.accepts(td.process()) }

        result.tasks.each { TaskDiff td ->
            switch( td.kind ) {
                case Kind.ADDED:     result.tasksAdded++;     break
                case Kind.REMOVED:   result.tasksRemoved++;   break
                case Kind.CHANGED:   result.tasksChanged++;   break
                case Kind.UNCHANGED: result.tasksUnchanged++; break
            }
        }

        result.tasksRecomputed = countRecomputed(result.tasks)
        result.regressions = computeRegressions(result.tasks)
        computeOutputs(result)
        computeLogs(result)
        return result
    }

    /**
     * Populate the outputs layer: for each task matched in both runs, compare
     * the files it wrote to its work directory. Skipped unless output diffing
     * was requested. Only matched tasks are inspected — an added/removed task
     * has no counterpart to diff outputs against.
     */
    private void computeOutputs(DiffResult result) {
        if( !diffOutputs )
            return
        result.diffOutputs = true

        final comparator = new OutputComparator(outputsMaxBytes)
        result.tasks.each { TaskDiff td ->
            if( td.a != null && td.b != null )
                result.outputs << comparator.compare(td.a, td.b)
        }

        final unavailable = result.outputs.count { DiffResult.OutputDiff od -> !od.availableA || !od.availableB }
        final capped = outputsMaxBytes > 0 ? " Same-size files larger than ${outputsMaxBytes} bytes are left content-unverified." : ''
        if( result.outputs.isEmpty() )
            result.outputsNote = 'No tasks were matched in both runs, so there were no outputs to compare.'
        else if( unavailable == result.outputs.size() )
            result.outputsNote = ('No work directories were available locally, so outputs could not be compared. ' +
                    'Output diffing needs the tasks\' work directories to still exist on this machine.').toString()
        else
            result.outputsNote = ("Output files compared by size, then SHA-256 for same-size files, from each task's " +
                    "work directory as it exists now.${capped}" +
                    (unavailable > 0 ? " ${unavailable} task(s) had a missing work directory and were skipped." : '')).toString()
    }

    /**
     * Populate the logs layer: for each task matched in both runs, compare the
     * standard log files ({@code .command.out/.err/.log}) it wrote to its work
     * directory. Skipped unless log diffing was requested. Only matched tasks
     * are inspected — an added/removed task has no counterpart to diff against.
     * This layer is informational and never affects {@link DiffResult#isIdentical()}.
     */
    private void computeLogs(DiffResult result) {
        if( !diffLogs )
            return
        result.diffLogs = true

        final comparator = new LogComparator(logsMaxLines)
        result.tasks.each { TaskDiff td ->
            if( td.a != null && td.b != null )
                result.logs << comparator.compare(td.a, td.b)
        }

        final unavailable = result.logs.count { DiffResult.LogDiff ld -> !ld.availableA || !ld.availableB }
        if( result.logs.isEmpty() )
            result.logsNote = 'No tasks were matched in both runs, so there were no task logs to compare.'
        else if( unavailable == result.logs.size() )
            result.logsNote = ('No work directories were available locally, so task logs could not be compared. ' +
                    'Log diffing needs the tasks\' work directories to still exist on this machine.').toString()
        else
            result.logsNote = ("Task stdout/stderr (.command.out/.err/.log) compared line by line, tailed to the last " +
                    "${logsMaxLines} lines per file. Logs are informational only — they never affect the " +
                    "\"identical\" verdict or --fail-on-change." +
                    (unavailable > 0 ? " ${unavailable} task(s) had a missing work directory and were skipped." : '')).toString()
    }

    /**
     * Count matched tasks whose cache hash differs between the two runs. A
     * differing hash means Nextflow would recompute the task rather than resume
     * it, so this is the "why did work get redone?" signal.
     */
    private static int countRecomputed(List<TaskDiff> tasks) {
        int n = 0
        tasks.each { TaskDiff td ->
            if( td.a != null && td.b != null && td.a.hash != null && td.b.hash != null && td.a.hash != td.b.hash )
                n++
        }
        return n
    }

    /**
     * Flag numeric metrics (runtime, memory) that changed by at least
     * {@link #perfThreshold} percent between matched tasks. The result is sorted
     * worst-regression first (largest positive delta), with improvements after.
     */
    private List<DiffResult.RegressionDiff> computeRegressions(List<TaskDiff> tasks) {
        final out = new ArrayList<DiffResult.RegressionDiff>()
        tasks.each { TaskDiff td ->
            if( td.a == null || td.b == null )
                return
            PERF_METRICS.each { List<String> m ->
                final field = m[0]
                final label = m[1]
                final va = td.a.numeric(field)
                final vb = td.b.numeric(field)
                final pct = Format.pctDelta(va, vb)
                if( pct == null || Math.abs(pct) < perfThreshold )
                    return
                out << new DiffResult.RegressionDiff(
                        taskKey  : td.key,
                        process  : td.process(),
                        metric   : field,
                        label    : label,
                        valueA   : va,
                        valueB   : vb,
                        displayA : td.a.display.get(field),
                        displayB : td.b.display.get(field),
                        pctDelta : pct,
                        sameHash : td.a.hash != null && td.a.hash == td.b.hash )
            }
        }
        out.sort { DiffResult.RegressionDiff r -> -r.pctDelta }
        return out
    }

    private List<FieldDiff> compareMetadata(RunSnapshot a, RunSnapshot b) {
        final diffs = new ArrayList<FieldDiff>()
        diffs << field('Run name', a.runName, b.runName)
        diffs << field('Session ID', a.sessionId?.toString(), b.sessionId?.toString())
        diffs << field('Status', a.status, b.status)
        diffs << field('Revision', a.revisionId, b.revisionId)
        diffs << field('Command', a.command, b.command)
        diffs << field('Launched', a.timestamp?.toString(), b.timestamp?.toString())
        diffs << field('Wall duration', Format.duration(a.durationMillis), Format.duration(b.durationMillis))
        diffs << field('Total task realtime', Format.duration(a.totalRealtimeMillis()), Format.duration(b.totalRealtimeMillis()))
        diffs << field('Task count', String.valueOf(a.tasks.size()), String.valueOf(b.tasks.size()))
        diffs << field('Cached tasks', String.valueOf(a.cachedCount()), String.valueOf(b.cachedCount()))
        diffs << field('Distinct processes', String.valueOf(a.processNames().size()), String.valueOf(b.processNames().size()))
        diffs.each { FieldDiff fd -> fd.obvious = OBVIOUS_METADATA.contains(fd.field) }
        return diffs
    }

    /**
     * Diff the launch-command flags of the two runs. Nextflow options (single
     * dash) are listed first, then pipeline params (double dash); each group is
     * sorted by name for stable output. A flag present in only one run shows a
     * missing value on the other side.
     */
    private List<FieldDiff> compareParams(RunSnapshot a, RunSnapshot b) {
        final ra = CommandParams.resolve(a.command, baseDir)
        final rb = CommandParams.resolve(b.command, baseDir)
        final pa = ra.values
        final pb = rb.values
        final keys = new TreeSet<String>()
        keys.addAll(pa.keySet())
        keys.addAll(pb.keySet())

        final ordered = new ArrayList<String>()
        ordered.addAll(keys.findAll { String k -> !CommandParams.isPipelineParam(k) })
        ordered.addAll(keys.findAll { String k -> CommandParams.isPipelineParam(k) })

        return ordered.collect { String k ->
            final fd = field(k, pa.get(k), pb.get(k), OBVIOUS_PARAM_KEYS.contains(k))
            fd.sourceA = ra.sources.get(k)
            fd.sourceB = rb.sources.get(k)
            return fd
        }
    }

    /**
     * Populate the configuration layer: resolve each run's effective Nextflow
     * config (see {@link ConfigLoader}) and diff the flattened key/value maps.
     * Config resolution reads the current on-disk config files, so failures
     * (missing project dir, unparseable config, unknown profile) are recorded
     * as a note rather than allowed to break the whole comparison.
     */
    private void computeConfig(DiffResult result, RunSnapshot a, RunSnapshot b) {
        if( baseDir == null ) {
            result.configNote = 'Configuration diff skipped: no project directory available.'
            return
        }
        Map<String,String> ca
        Map<String,String> cb
        try {
            final loader = new ConfigLoader()
            ca = loader.resolve(a.command, baseDir)
            cb = loader.resolve(b.command, baseDir)
        }
        catch( Exception e ) {
            log.warn "nf-diff: could not resolve configuration: ${e.message}"
            result.configNote = "Configuration could not be resolved from ${baseDir}: ${e.message}"
            return
        }

        final keys = new TreeSet<String>()
        keys.addAll(ca.keySet())
        keys.addAll(cb.keySet())
        result.config = keys.collect { String k -> field(k, ca.get(k), cb.get(k)) }

        result.configNote = keys.isEmpty()
                ? "No Nextflow configuration was resolved from ${baseDir} for either run."
                : ('Resolved from the current on-disk config files under ' +
                   "${baseDir}, applying each run's -profile/-c options — not a " +
                   'snapshot of the config at launch time.')
    }

    private List<ProcessDiff> compareProcesses(RunSnapshot a, RunSnapshot b) {
        final countsA = a.taskCountByProcess()
        final countsB = b.taskCountByProcess()
        final names = new TreeSet<String>()
        names.addAll(countsA.keySet())
        names.addAll(countsB.keySet())

        return names.collect { String p ->
            new ProcessDiff(process: p, countA: (countsA[p] ?: 0), countB: (countsB[p] ?: 0))
        }
    }

    private List<TaskDiff> compareTasks(RunSnapshot a, RunSnapshot b) {
        final mapA = a.tasksByKey()
        final mapB = b.tasksByKey()

        // preserve A order first, then B-only tasks
        final keys = new LinkedHashSet<String>()
        keys.addAll(mapA.keySet())
        keys.addAll(mapB.keySet())

        return keys.collect { String key ->
            final ta = mapA.get(key)
            final tb = mapB.get(key)
            if( ta != null && tb == null )
                return new TaskDiff(key: key, kind: Kind.REMOVED, a: ta)
            if( ta == null && tb != null )
                return new TaskDiff(key: key, kind: Kind.ADDED, b: tb)

            final fieldDiffs = compareTaskFields(ta, tb)
            final changed = fieldDiffs.any { it.isHighlighted(showObvious) }
            return new TaskDiff(
                    key: key,
                    kind: changed ? Kind.CHANGED : Kind.UNCHANGED,
                    a: ta, b: tb,
                    fieldDiffs: fieldDiffs )
        }
    }

    private List<FieldDiff> compareTaskFields(TaskInfo a, TaskInfo b) {
        return TASK_FIELDS.collect { String f ->
            field(f, a.display.get(f), b.display.get(f), OBVIOUS_TASK_FIELDS.contains(f))
        }
    }

    private static FieldDiff field(String name, String va, String vb, boolean obvious = false) {
        return new FieldDiff(field: name, valueA: va, valueB: vb, obvious: obvious)
    }
}
