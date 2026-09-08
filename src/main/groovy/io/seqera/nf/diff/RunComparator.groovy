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
     *
     * <p>{@link #baseDirA} / {@link #baseDirB} are the effective per-run
     * directories: they default to this shared {@code baseDir}, but may point at
     * two different checkouts when comparing runs across projects
     * ({@code --dir-a} / {@code --dir-b}).
     */
    private final Path baseDir

    /** Effective project directory for run A (falls back to {@link #baseDir}). */
    private final Path baseDirA

    /** Effective project directory for run B (falls back to {@link #baseDir}). */
    private final Path baseDirB

    /** True when run A and run B resolve config/params from different directories. */
    private final boolean crossProject

    /** Percentage change beyond which a task metric is flagged as a regression. */
    private final double perfThreshold

    /** When true, compare the output files each matched task wrote to its work dir. */
    private final boolean diffOutputs

    /**
     * Maximum file size (bytes) to hash when comparing same-size outputs; 0
     * means no limit. Only meaningful when {@link #diffOutputs} is set.
     */
    private final long outputsMaxBytes

    /**
     * Maximum leading lines kept per side when line-diffing a changed text
     * output file. Only meaningful when {@link #diffOutputs} is set.
     */
    private final int outputsMaxLines

    /** When true, compare the standard log files each matched task wrote. */
    private final boolean diffLogs

    /**
     * Maximum tail lines kept per log file when {@link #diffLogs} is set.
     * Zero falls back to {@link LogComparator#DEFAULT_MAX_LINES}.
     */
    private final int logsMaxLines

    /** When true, reconstruct and diff each run's process&#8594;process wiring. */
    private final boolean diffDag

    RunComparator(boolean showObvious = false, ProcessFilter filter = null, Path baseDir = null,
                  double perfThreshold = DEFAULT_PERF_THRESHOLD,
                  boolean diffOutputs = false, long outputsMaxBytes = 0L,
                  boolean diffLogs = false, int logsMaxLines = LogComparator.DEFAULT_MAX_LINES,
                  int outputsMaxLines = OutputComparator.DEFAULT_MAX_LINES,
                  Path baseDirA = null, Path baseDirB = null, boolean diffDag = false) {
        this.showObvious = showObvious
        this.filter = filter ?: ProcessFilter.of([], [])
        this.baseDir = baseDir
        this.baseDirA = baseDirA ?: baseDir
        this.baseDirB = baseDirB ?: baseDir
        this.crossProject = norm(this.baseDirA) != norm(this.baseDirB)
        this.perfThreshold = perfThreshold
        this.diffOutputs = diffOutputs
        this.outputsMaxBytes = outputsMaxBytes
        this.diffLogs = diffLogs
        this.logsMaxLines = logsMaxLines
        this.outputsMaxLines = outputsMaxLines
        this.diffDag = diffDag
    }

    /** Absolute, normalised form of a directory for equality comparison; null-safe. */
    private static Path norm(Path p) {
        return p?.toAbsolutePath()?.normalize()
    }

    DiffResult compare(RunSnapshot a, RunSnapshot b) {
        final result = new DiffResult(runA: a, runB: b, showObvious: showObvious, perfThreshold: perfThreshold)
        result.metadata = compareMetadata(a, b)
        result.params = compareParams(a, b)
        computeConfig(result, a, b)
        result.processes = compareProcesses(a, b).findAll { ProcessDiff pd -> filter.accepts(pd.process) }
        result.software = compareSoftware(a, b).findAll { DiffResult.SoftwareDiff sd -> filter.accepts(sd.process) }
        result.efficiency = computeEfficiency(a, b).findAll { DiffResult.ProcessEfficiency pe -> filter.accepts(pe.process) }
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
        computeFailures(result, a, b)
        computeOutputs(result)
        computeLogs(result)
        computeDag(result, a, b)
        return result
    }

    /**
     * Populate the wiring layer: reconstruct each run's process&#8594;process
     * edges from its task work-dir input symlinks (via {@link DagComparator})
     * and diff the two edge sets. Skipped unless {@code --diff-dag} was
     * requested. Edges are kept when the {@link #filter} accepts either
     * endpoint. This layer is informational only — the wiring is a best-effort
     * reconstruction bounded by which work directories still exist locally, so
     * it never affects {@link DiffResult#isIdentical()} or {@code --fail-on-change}.
     */
    private void computeDag(DiffResult result, RunSnapshot a, RunSnapshot b) {
        if( !diffDag )
            return
        result.diffDag = true

        final comparator = new DagComparator()
        final graphA = comparator.graphOf(a, baseDirA)
        final graphB = comparator.graphOf(b, baseDirB)

        final all = new TreeSet<DiffResult.DagEdge>({ DiffResult.DagEdge x, DiffResult.DagEdge y ->
            final byTo = (x.to ?: '') <=> (y.to ?: '')
            byTo != 0 ? byTo : ((x.from ?: '') <=> (y.from ?: ''))
        } as Comparator<DiffResult.DagEdge>)
        all.addAll(graphA.edges)
        all.addAll(graphB.edges)

        final diffs = new ArrayList<DiffResult.DagEdgeDiff>()
        all.each { DiffResult.DagEdge e ->
            if( !filter.accepts(e.from) && !filter.accepts(e.to) )
                return
            final inA = graphA.edges.contains(e)
            final inB = graphB.edges.contains(e)
            final kind = inA && inB ? Kind.UNCHANGED : (inB ? Kind.ADDED : Kind.REMOVED)
            diffs << new DiffResult.DagEdgeDiff(edge: e, kind: kind)
        }
        // Changed edges first (added, then removed), unchanged last.
        diffs.sort { DiffResult.DagEdgeDiff d -> dagRank(d.kind) }
        result.dag = diffs

        result.dagNote = dagNote(graphA, graphB)
    }

    /** Sort rank so added edges lead, then removed, then unchanged. */
    private static int dagRank(Kind kind) {
        switch( kind ) {
            case Kind.ADDED:   return 0
            case Kind.REMOVED: return 1
            default:           return 2
        }
    }

    /**
     * Build the human-readable note for the wiring layer, tailored to how each
     * run's graph was obtained: authoritative when read from the lineage store,
     * best-effort when inferred from work-dir symlinks.
     */
    private static String dagNote(DagComparator.RunGraph graphA, DagComparator.RunGraph graphB) {
        final bothLineage = graphA.source == DagComparator.Source.LINEAGE &&
                graphB.source == DagComparator.Source.LINEAGE
        if( bothLineage )
            return 'Process wiring read from the Nextflow data-lineage store (each task\'s recorded input ' +
                    'provenance), so it is authoritative and needs no work directories. Informational only — ' +
                    'it never affects the "identical" verdict or --fail-on-change.'

        final anyLineage = graphA.source == DagComparator.Source.LINEAGE ||
                graphB.source == DagComparator.Source.LINEAGE
        final anyWorkdir = graphA.anyWorkdir || graphB.anyWorkdir
        if( !anyLineage && !anyWorkdir )
            return 'No lineage store and no local work directories were available, so process wiring could not ' +
                    'be reconstructed. DAG diffing reads the .lineage/ store when present, otherwise it needs the ' +
                    'tasks\' work directories to still exist on this machine.'

        final missing = graphA.missingWorkdirs + graphB.missingWorkdirs
        String base
        if( anyLineage )
            // One side is authoritative (lineage), the other inferred from symlinks.
            base = 'One run\'s wiring was read from the data-lineage store (authoritative); the other was ' +
                    'inferred from work-dir input symlinks (best-effort). Informational only — it never affects ' +
                    'the "identical" verdict or --fail-on-change.'
        else
            base = 'Process wiring inferred from each task\'s staged input symlinks (a link into another task\'s ' +
                    'work directory is a producer→consumer edge). This is a best-effort reconstruction — enable ' +
                    'lineage (lineage.enabled) for an authoritative graph. Informational only — it never affects ' +
                    'the "identical" verdict or --fail-on-change.'
        if( missing > 0 )
            return (base + " ${missing} task(s) had a missing work directory; recovered wiring may be incomplete.").toString()
        return base
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

        final comparator = new OutputComparator(outputsMaxBytes, outputsMaxLines)
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
                    "work directory as it exists now. Changed text files are additionally diffed line by line " +
                    "(first ${outputsMaxLines} lines per file; binary files show a size/hash change only).${capped}" +
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
        final ra = CommandParams.resolve(a.command, baseDirA)
        final rb = CommandParams.resolve(b.command, baseDirB)
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
        if( baseDirA == null && baseDirB == null ) {
            result.configNote = 'Configuration diff skipped: no project directory available.'
            return
        }
        Map<String,String> ca
        Map<String,String> cb
        try {
            final loader = new ConfigLoader()
            ca = loader.resolve(a.command, baseDirA)
            cb = loader.resolve(b.command, baseDirB)
        }
        catch( Exception e ) {
            log.warn "nf-diff: could not resolve configuration: ${e.message}"
            result.configNote = crossProject
                    ? "Configuration could not be resolved from ${baseDirA} (run A) / ${baseDirB} (run B): ${e.message}".toString()
                    : "Configuration could not be resolved from ${baseDirA}: ${e.message}".toString()
            return
        }

        final keys = new TreeSet<String>()
        keys.addAll(ca.keySet())
        keys.addAll(cb.keySet())
        result.config = keys.collect { String k -> field(k, ca.get(k), cb.get(k)) }

        if( keys.isEmpty() )
            result.configNote = crossProject
                    ? "No Nextflow configuration was resolved from ${baseDirA} (run A) or ${baseDirB} (run B).".toString()
                    : "No Nextflow configuration was resolved from ${baseDirA} for either run.".toString()
        else
            result.configNote = crossProject
                    ? ('Resolved from the current on-disk config files under ' +
                       "${baseDirA} (run A) and ${baseDirB} (run B), applying each run's " +
                       '-profile/-c options — not a snapshot of the config at launch time.').toString()
                    : ('Resolved from the current on-disk config files under ' +
                       "${baseDirA}, applying each run's -profile/-c options — not a " +
                       'snapshot of the config at launch time.').toString()

        result.configProvenance = computeConfigProvenance(a, b)
    }

    /**
     * Determine whether the working tree the config was resolved from has
     * drifted from the git revision each run was launched at. When the checkout
     * moved (or has uncommitted changes) since a run, the resolved config no
     * longer reflects what that run actually used — a caveat surfaced by the
     * renderers. Git state is inspected best-effort: when {@code baseDir} is not
     * a git work tree, the provenance simply reports "unknown" and warns about
     * nothing.
     */
    private DiffResult.ConfigProvenance computeConfigProvenance(RunSnapshot a, RunSnapshot b) {
        final prov = new DiffResult.ConfigProvenance(
                crossProject: crossProject,
                revisionA: a.revisionId,
                revisionB: b.revisionId )

        final gp = new GitProvenance()
        final stateA = gp.inspect(baseDirA)
        // In same-project mode both runs share one tree, so reuse stateA rather
        // than inspecting the same directory twice.
        final stateB = crossProject ? gp.inspect(baseDirB) : stateA

        prov.gitAvailable = crossProject ? (stateA.isRepo() || stateB.isRepo()) : stateA.isRepo()

        // Run A side (also the shared tree in same-project mode).
        if( stateA.isRepo() ) {
            prov.currentRevision = stateA.headCommit
            prov.workingTreeDirty = stateA.dirty
            prov.driftedA = revisionDrifted(a.revisionId, stateA.headCommit)
        }

        // Run B side.
        if( crossProject ) {
            prov.dirA = baseDirA?.toString()
            prov.dirB = baseDirB?.toString()
            if( stateB.isRepo() ) {
                prov.currentRevisionB = stateB.headCommit
                prov.dirtyB = stateB.dirty
                prov.driftedB = revisionDrifted(b.revisionId, stateB.headCommit)
            }
        }
        else if( stateA.isRepo() ) {
            prov.driftedB = revisionDrifted(b.revisionId, stateA.headCommit)
        }
        return prov
    }

    /**
     * True when a run's recorded git revision is known and differs from the
     * current working-tree HEAD. Commit ids may be abbreviated (Nextflow can
     * record a short id), so a shared prefix in either direction counts as a
     * match. An unknown revision (non-git run) is never treated as drift.
     */
    private static boolean revisionDrifted(String runRevision, String currentHead) {
        if( !runRevision || !currentHead )
            return false
        final r = runRevision.trim()
        final c = currentHead.trim()
        return !(c.startsWith(r) || r.startsWith(c))
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

    /**
     * Diff the software environment per process: the distinct container
     * image(s) and conda package spec(s) each process's tasks ran with. A
     * process present in only one run is ADDED/REMOVED; a process present in
     * both whose container or conda set differs is CHANGED. Values come from
     * the cached trace records, so this needs no work directories.
     */
    private List<DiffResult.SoftwareDiff> compareSoftware(RunSnapshot a, RunSnapshot b) {
        final sa = softwareByProcess(a)
        final sb = softwareByProcess(b)
        final names = new TreeSet<String>()
        names.addAll(sa.keySet())
        names.addAll(sb.keySet())

        return names.collect { String p ->
            final ea = sa.get(p)
            final eb = sb.get(p)
            final containersA = ea != null ? new ArrayList<String>(ea.get('containers')) : new ArrayList<String>()
            final containersB = eb != null ? new ArrayList<String>(eb.get('containers')) : new ArrayList<String>()
            final condaA = ea != null ? new ArrayList<String>(ea.get('conda')) : new ArrayList<String>()
            final condaB = eb != null ? new ArrayList<String>(eb.get('conda')) : new ArrayList<String>()

            DiffResult.Kind kind
            if( ea != null && eb == null )
                kind = Kind.REMOVED
            else if( ea == null && eb != null )
                kind = Kind.ADDED
            else
                kind = (containersA != containersB || condaA != condaB) ? Kind.CHANGED : Kind.UNCHANGED

            return new DiffResult.SoftwareDiff(
                    process     : p,
                    containersA : containersA, containersB : containersB,
                    condaA      : condaA, condaB : condaB,
                    kind        : kind )
        }
    }

    /**
     * Collect the distinct container image(s) and conda spec(s) each process
     * used across its tasks, keyed by process name. Values are read from the
     * task's {@code container} field and its formatted {@code conda} value;
     * empty or {@code -} placeholders are treated as absent.
     */
    private static Map<String,Map<String,Set<String>>> softwareByProcess(RunSnapshot snap) {
        final map = new TreeMap<String,Map<String,Set<String>>>()
        snap.tasks.each { TaskInfo t ->
            final p = t.process ?: '(unknown)'
            Map<String,Set<String>> entry = map.get(p)
            if( entry == null ) {
                entry = ['containers': new TreeSet<String>(), 'conda': new TreeSet<String>()] as Map<String,Set<String>>
                map.put(p, entry)
            }
            final container = cleanSoftware(t.container)
            if( container != null )
                entry.get('containers').add(container)
            final conda = cleanSoftware(t.display?.get('conda'))
            if( conda != null )
                entry.get('conda').add(conda)
        }
        return map
    }

    /** Normalise a software value: trim, treating empty or {@code -} placeholders as absent. */
    private static String cleanSoftware(String value) {
        if( value == null )
            return null
        final v = value.trim()
        return (v.isEmpty() || v == '-') ? null : v
    }

    /**
     * Compute per-process resource-provisioning efficiency for both runs: the
     * requested {@code cpus}/{@code memory} versus the measured peak
     * {@code %cpu}/{@code peak_rss}, aggregated per process. All values come
     * from the cached trace records, so no work directories are needed. Peaks
     * are taken as the max across the process's tasks (a retried task that used
     * more is the honest worst case); requested values are likewise the max
     * (a task retried with a bumped request represents the allocation that
     * mattered). Processes with neither a CPU nor a memory figure on either
     * side are dropped so the layer stays quiet when the trace lacks metrics.
     */
    private List<DiffResult.ProcessEfficiency> computeEfficiency(RunSnapshot a, RunSnapshot b) {
        final map = new TreeMap<String,DiffResult.ProcessEfficiency>()
        accumulateEfficiency(map, a, true)
        accumulateEfficiency(map, b, false)
        return new ArrayList<DiffResult.ProcessEfficiency>(map.values()).findAll { DiffResult.ProcessEfficiency e ->
            e.cpuEffA() != null || e.cpuEffB() != null || e.memEffA() != null || e.memEffB() != null
        }
    }

    private static void accumulateEfficiency(Map<String,DiffResult.ProcessEfficiency> map, RunSnapshot snap, boolean sideA) {
        snap.tasks.each { TaskInfo t ->
            final p = t.process ?: '(unknown)'
            DiffResult.ProcessEfficiency e = map.get(p)
            if( e == null ) {
                e = new DiffResult.ProcessEfficiency(process: p)
                map.put(p, e)
            }
            final Integer cpus = t.numeric('cpus')?.intValue()
            final Long mem = t.numeric('memory')
            final Double pcpu = t.numericDouble('%cpu')
            final Long rss = t.numeric('peak_rss')
            if( sideA ) {
                e.cpusReqA = maxInt(e.cpusReqA, cpus)
                e.memReqBytesA = maxLong(e.memReqBytesA, mem)
                e.peakCpuPctA = maxDouble(e.peakCpuPctA, pcpu)
                e.peakRssBytesA = maxLong(e.peakRssBytesA, rss)
            }
            else {
                e.cpusReqB = maxInt(e.cpusReqB, cpus)
                e.memReqBytesB = maxLong(e.memReqBytesB, mem)
                e.peakCpuPctB = maxDouble(e.peakCpuPctB, pcpu)
                e.peakRssBytesB = maxLong(e.peakRssBytesB, rss)
            }
        }
    }

    private static Integer maxInt(Integer cur, Integer val) {
        if( val == null ) return cur
        if( cur == null ) return val
        return Math.max(cur, val)
    }

    private static Long maxLong(Long cur, Long val) {
        if( val == null ) return cur
        if( cur == null ) return val
        return Math.max(cur, val)
    }

    private static Double maxDouble(Double cur, Double val) {
        if( val == null ) return cur
        if( cur == null ) return val
        return Math.max(cur, val)
    }

    /**
     * Populate the failure rollup: the failed tasks in each run and their
     * roll-up by (process, status, exit) signature — the top-level "what failed
     * and why" summary. Failures are detected from cached status/exit fields, so
     * no work directories are needed. Respects the active process filter so the
     * rollup is consistent with the rest of the report. Informational only — it
     * never affects {@link DiffResult#isIdentical()}.
     */
    private void computeFailures(DiffResult result, RunSnapshot a, RunSnapshot b) {
        result.failuresA = collectFailures(a)
        result.failuresB = collectFailures(b)
        result.failureGroups = groupFailures(result.failuresA, result.failuresB)
    }

    /** Failed tasks in a run, in task order, filtered by the active process filter. */
    private List<DiffResult.TaskFailure> collectFailures(RunSnapshot snap) {
        final out = new ArrayList<DiffResult.TaskFailure>()
        snap.tasks.each { TaskInfo t ->
            final p = t.process ?: '(unknown)'
            if( !filter.accepts(p) )
                return
            if( DiffResult.isTaskFailure(t.status, t.exit) ) {
                out << new DiffResult.TaskFailure(
                        taskKey: t.matchKey(),
                        process: p,
                        tag    : t.tag,
                        status : (t.status ?: 'UNKNOWN'),
                        exit   : DiffResult.normalizeExit(t.exit) )
            }
        }
        return out
    }

    /**
     * Roll failures up by their (process, status, exit) signature, counting how
     * many tasks matched it in each run. Sorted with the biggest total blast
     * radius first, then by process for stable output.
     */
    private static List<DiffResult.FailureGroup> groupFailures(List<DiffResult.TaskFailure> a,
                                                               List<DiffResult.TaskFailure> b) {
        final map = new LinkedHashMap<String,DiffResult.FailureGroup>()
        accumulateFailures(map, a, true)
        accumulateFailures(map, b, false)
        final groups = new ArrayList<DiffResult.FailureGroup>(map.values())
        groups.sort { DiffResult.FailureGroup x, DiffResult.FailureGroup y ->
            final byTotal = (y.countA + y.countB) <=> (x.countA + x.countB)
            if( byTotal != 0 )
                return byTotal
            final byProc = (x.process ?: '') <=> (y.process ?: '')
            if( byProc != 0 )
                return byProc
            return (x.exit ?: '') <=> (y.exit ?: '')
        }
        return groups
    }

    private static void accumulateFailures(Map<String,DiffResult.FailureGroup> map,
                                           List<DiffResult.TaskFailure> failures, boolean sideA) {
        failures.each { DiffResult.TaskFailure f ->
            final key = "${f.process}\u0000${f.status}\u0000${f.exit}".toString()
            DiffResult.FailureGroup g = map.get(key)
            if( g == null ) {
                g = new DiffResult.FailureGroup(process: f.process, status: f.status, exit: f.exit)
                map.put(key, g)
            }
            if( sideA ) g.countA++
            else        g.countB++
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
