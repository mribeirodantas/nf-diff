package io.seqera.nf.diff

import groovy.transform.CompileStatic

/**
 * The structured outcome of comparing two runs. Consumed by the HTML renderer.
 */
@CompileStatic
class DiffResult {

    /** A single named value compared across the two runs. */
    @CompileStatic
    static class FieldDiff {
        String field
        String valueA
        String valueB
        /**
         * True for fields that are expected to differ between any two runs
         * (run name, session id, timestamps, work dir, wall/real time, resource
         * usage). These are not treated as meaningful changes unless the user
         * opts into the verbose view.
         */
        boolean obvious = false

        /**
         * For the parameters layer: where each side's value came from — one of
         * {@link CommandParams#SRC_CLI}, {@link CommandParams#SRC_FILE} or
         * {@link CommandParams#SRC_BOTH}. Null for layers where provenance does
         * not apply (metadata, config, tasks).
         */
        String sourceA
        String sourceB

        boolean isChanged() {
            return (valueA ?: '') != (valueB ?: '')
        }

        /**
         * Whether this diff should be surfaced as a change: a raw value change
         * that is either non-obvious, or obvious while the verbose view is on.
         */
        boolean isHighlighted(boolean showObvious) {
            return changed && (showObvious || !obvious)
        }
    }

    /** Per-process comparison, based on task counts. */
    @CompileStatic
    static class ProcessDiff {
        String process
        int countA
        int countB

        boolean isAdded()    { countA == 0 && countB > 0 }
        boolean isRemoved()  { countA > 0 && countB == 0 }
        boolean isChanged()  { countA != countB && countA > 0 && countB > 0 }
        boolean isUnchanged(){ countA == countB }
    }

    /**
     * A single directed process&#8594;process edge in a run's reconstructed
     * wiring: {@code from} produced an output that {@code to} consumed as a
     * staged input. Reconstructed from work-dir input symlinks, so it needs the
     * work directories to exist locally.
     */
    @groovy.transform.EqualsAndHashCode
    @CompileStatic
    static class DagEdge {
        String from
        String to

        @Override
        String toString() { "${from} → ${to}" }
    }

    /**
     * A process&#8594;process edge compared across the two runs. {@link Kind#ADDED}
     * means the wiring appeared only in Run B (a new dependency), {@link Kind#REMOVED}
     * only in Run A (a dropped dependency), {@link Kind#UNCHANGED} in both.
     */
    @CompileStatic
    static class DagEdgeDiff {
        DagEdge edge
        Kind kind

        String from() { edge?.from }
        String to()   { edge?.to }
        boolean isAdded()   { kind == Kind.ADDED }
        boolean isRemoved() { kind == Kind.REMOVED }
    }

    /**
     * Per-process comparison of the software environment — the container
     * image(s) and conda package spec(s) that process's tasks ran with in each
     * run. This is the "did a tool version change?" layer: both values are read
     * straight from the run cache's trace records, so it needs no work
     * directories. A process present in only one run is {@link Kind#ADDED} /
     * {@link Kind#REMOVED}; a process present in both whose container or conda
     * set differs is {@link Kind#CHANGED}.
     */
    @CompileStatic
    static class SoftwareDiff {
        String process
        /** Distinct container images used by this process in Run A / Run B, sorted. */
        List<String> containersA = []
        List<String> containersB = []
        /** Distinct conda package specs used by this process in Run A / Run B, sorted. */
        List<String> condaA = []
        List<String> condaB = []
        Kind kind

        boolean isContainerChanged() { containersA != containersB }
        boolean isCondaChanged()     { condaA != condaB }
        /** True when the software environment meaningfully differs (process in both runs). */
        boolean isChanged()          { kind == Kind.CHANGED }
    }

    /**
     * Ratio (measured peak / requested) at or above which a resource is
     * considered to have run "tight" against its request — a risk of OOM kills
     * (memory) or CPU throttling. Used to classify {@link ProcessEfficiency}.
     */
    static final double TIGHT_THRESHOLD = 0.9d

    /**
     * Ratio (measured peak / requested) below which a resource is considered
     * over-provisioned — much more was requested than the task ever used, so
     * the allocation (and, on many schedulers, the cost) was wasted.
     */
    static final double OVER_THRESHOLD = 0.5d

    /** Classification of a single resource's efficiency ratio. */
    static String classifyEfficiency(Double ratio) {
        if( ratio == null )
            return null
        if( ratio >= TIGHT_THRESHOLD )
            return 'tight'
        if( ratio < OVER_THRESHOLD )
            return 'over'
        return 'ok'
    }

    /**
     * Per-process resource-provisioning efficiency for both runs: how much of
     * the requested CPU / memory the process's tasks actually used at peak.
     *
     * <p>Computed entirely from the run cache trace — requested {@code cpus} /
     * {@code memory} versus measured {@code %cpu} / {@code peak_rss} — so no
     * work directories are required and it is always populated. The efficiency
     * ratio is measured-peak / requested: a low ratio means the process was
     * over-provisioned (wasted allocation, e.g. "requested 32 GB, peaked at
     * 4 GB"); a ratio near or above 1 means it ran tight against its request.
     *
     * <p>This layer is informational only — provisioning is a tuning signal,
     * not a correctness change, so it never affects {@link #isIdentical()} or
     * {@code --fail-on-change}.
     */
    @CompileStatic
    static class ProcessEfficiency {
        String process
        /** Requested cores ({@code cpus}), representative across the process's tasks. */
        Integer cpusReqA
        Integer cpusReqB
        /** Peak measured CPU percent ({@code %cpu}; 100 == one core), max across tasks. */
        Double peakCpuPctA
        Double peakCpuPctB
        /** Requested memory in bytes ({@code memory}), representative across tasks. */
        Long memReqBytesA
        Long memReqBytesB
        /** Peak measured resident memory in bytes ({@code peak_rss}), max across tasks. */
        Long peakRssBytesA
        Long peakRssBytesB

        /** CPU efficiency fraction: peak %cpu / (requested cores * 100), or null when unknown. */
        Double cpuEffA() { cpuEff(cpusReqA, peakCpuPctA) }
        Double cpuEffB() { cpuEff(cpusReqB, peakCpuPctB) }
        /** Memory efficiency fraction: peak RSS / requested memory, or null when unknown. */
        Double memEffA() { memEff(memReqBytesA, peakRssBytesA) }
        Double memEffB() { memEff(memReqBytesB, peakRssBytesB) }

        String cpuClassA() { classifyEfficiency(cpuEffA()) }
        String cpuClassB() { classifyEfficiency(cpuEffB()) }
        String memClassA() { classifyEfficiency(memEffA()) }
        String memClassB() { classifyEfficiency(memEffB()) }

        private static Double cpuEff(Integer cpusReq, Double peakCpuPct) {
            if( cpusReq == null || cpusReq <= 0 || peakCpuPct == null )
                return null
            return peakCpuPct / (cpusReq * 100.0d)
        }

        private static Double memEff(Long memReq, Long peakRss) {
            if( memReq == null || memReq <= 0L || peakRss == null )
                return null
            return peakRss / (memReq as double)
        }
    }

    /** Sentinel Nextflow writes to a task's {@code exit} field when it never produced one. */
    static final int NO_EXIT = Integer.MAX_VALUE

    /**
     * True when a task's {@code status}/{@code exit} pair represents a failure:
     * an explicit {@code FAILED}/{@code ABORTED} status, or a non-zero exit code
     * (ignoring the {@link #NO_EXIT} sentinel and non-numeric values). Reads only
     * cached trace fields, so it works without work directories.
     */
    static boolean isTaskFailure(String status, String exit) {
        final s = status?.trim()?.toUpperCase()
        if( s == 'FAILED' || s == 'ABORTED' )
            return true
        return failedExit(exit)
    }

    /** True when {@code exit} parses to a non-zero code other than the {@link #NO_EXIT} sentinel. */
    static boolean failedExit(String exit) {
        if( !exit )
            return false
        try {
            final code = Integer.parseInt(exit.trim())
            return code != 0 && code != NO_EXIT
        }
        catch( NumberFormatException ignored ) {
            return false
        }
    }

    /** Normalise an exit code for display/grouping: the {@link #NO_EXIT} sentinel and blanks become {@code -}. */
    static String normalizeExit(String exit) {
        if( !exit )
            return '-'
        final t = exit.trim()
        if( t == '-' || t == String.valueOf(NO_EXIT) )
            return '-'
        return t
    }

    /**
     * A single failed task in one run — the raw material of the failure rollup.
     * Captured from the run cache trace (status + exit code), so no work
     * directories are required.
     */
    @CompileStatic
    static class TaskFailure {
        String taskKey
        String process
        String tag
        /** Task status, e.g. {@code FAILED}, {@code ABORTED}. */
        String status
        /** Exit code as recorded, already normalised via {@link DiffResult#normalizeExit}. */
        String exit
    }

    /**
     * A rolled-up group of failures sharing the same (process, status, exit)
     * signature, with how many tasks matched it in each run. This is the
     * top-level "what failed and why" answer: a compact table of failure
     * signatures rather than a scroll through per-task detail. A group present
     * in only one run is newly-appearing ({@link #isNew}) or resolved
     * ({@link #isResolved}); a group in both whose count changed shifted.
     */
    @CompileStatic
    static class FailureGroup {
        String process
        String status
        /** Normalised exit code shared by the group ({@code -} when none). */
        String exit
        int countA
        int countB

        /** True when this failure signature appeared only in Run B (a new failure). */
        boolean isNew()      { countA == 0 && countB > 0 }
        /** True when this failure signature was in Run A but is gone in Run B (fixed). */
        boolean isResolved() { countA > 0 && countB == 0 }
        /** True when present in both runs. */
        boolean isPersistent() { countA > 0 && countB > 0 }
    }

    /**
     * A single task metric whose value changed enough between the two runs to
     * be worth surfacing (e.g. realtime, peak_rss). Larger values are "worse",
     * so a positive {@link #pctDelta} means Run B regressed relative to Run A.
     */
    @CompileStatic
    static class RegressionDiff {
        String taskKey
        String process
        /** Raw trace field, e.g. {@code realtime}, {@code peak_rss}. */
        String metric
        /** Human label for the metric, e.g. {@code Realtime}. */
        String label
        Long valueA
        Long valueB
        String displayA
        String displayB
        /** Signed percentage change of B relative to A; positive means slower/heavier. */
        Double pctDelta
        /** True when the same cache hash produced both tasks (i.e. same work, different cost). */
        boolean sameHash

        /** True when B is worse than A (larger value). */
        boolean isRegression() { pctDelta != null && pctDelta > 0 }
    }

    /**
     * Git-provenance context for the resolved-configuration layer. Because
     * {@link ConfigLoader} rebuilds each run's effective config from the working
     * tree <em>as it exists now</em>, a config that changed with the code between
     * the two runs' git revisions is invisible: both sides resolve against the
     * current checkout. This captures the current HEAD and working-tree state so
     * the report can warn when that masking is possible. It is informational —
     * it never affects {@link #isIdentical()} or {@code --fail-on-change}.
     */
    @CompileStatic
    static class ConfigProvenance {
        /** Whether git state could be determined at all (a git work tree was found). */
        boolean gitAvailable
        /**
         * True when the two runs' configs were resolved from <em>different</em>
         * project directories ({@code --dir-a} / {@code --dir-b}). In that case
         * each side has its own working tree, so the per-side fields
         * ({@link #currentRevisionB}, {@link #dirtyB}, {@link #dirA}/{@link #dirB})
         * are populated and {@link #warning()} describes each tree separately.
         */
        boolean crossProject
        /** Project directory each run's config was resolved from (cross-project only). */
        String dirA
        String dirB
        /**
         * Commit id of the working tree HEAD run A's config was resolved against;
         * null when unknown. In same-project mode this is also run B's tree.
         */
        String currentRevision
        /** Commit id of run B's working tree HEAD (cross-project only); null otherwise. */
        String currentRevisionB
        /**
         * True when run A's working tree has uncommitted (tracked) changes. In
         * same-project mode this is the single shared tree's state.
         */
        boolean workingTreeDirty
        /** True when run B's working tree is dirty (cross-project only). */
        boolean dirtyB
        /** Each run's recorded git revision at launch; may be null (non-git run). */
        String revisionA
        String revisionB
        /** True when run A / B was launched at a git revision other than the current checkout. */
        boolean driftedA
        boolean driftedB

        /** True when drift or a dirty tree undermines the config layer's trustworthiness. */
        boolean hasWarning() {
            return driftedA || driftedB || workingTreeDirty || dirtyB
        }

        /**
         * Human-readable caveat about config provenance, or null when the layer
         * is trustworthy (or git state is unknown, in which case there is nothing
         * to warn about beyond the standard "resolved from current files" note).
         */
        String warning() {
            if( !hasWarning() )
                return null
            return crossProject ? crossProjectWarning() : sameProjectWarning()
        }

        /** Warning text when both runs share one working tree (the common case). */
        private String sameProjectWarning() {
            final parts = new ArrayList<String>()
            if( driftedA || driftedB ) {
                final which = (driftedA && driftedB) ? 'both runs were'
                        : (driftedA ? 'run A was' : 'run B was')
                parts << ("The resolved configuration was rebuilt from the working tree at " +
                        "${shortSha(currentRevision)}, but ${which} launched at a different git revision " +
                        "(A: ${shortSha(revisionA)}, B: ${shortSha(revisionB)}). Any config difference driven " +
                        "by code changes between those revisions is NOT visible here — both sides were resolved " +
                        "against the current checkout.").toString()
            }
            if( workingTreeDirty )
                parts << ('The working tree has uncommitted changes, so the resolved configuration reflects ' +
                        'local edits that may not match either run.')
            return parts.join(' ')
        }

        /**
         * Warning text when each run's config came from a different project
         * directory. Each tree is described on its own, since there is no single
         * "current checkout" shared by the two runs.
         */
        private String crossProjectWarning() {
            final parts = new ArrayList<String>()
            parts << ("Run A and Run B configurations were resolved from different project directories " +
                    "(A: ${dirA}, B: ${dirB}).").toString()
            if( driftedA )
                parts << ("Run A's config was rebuilt from its working tree at ${shortSha(currentRevision)}, " +
                        "but the run was launched at ${shortSha(revisionA)}; config differences driven by code " +
                        "changes between those revisions are not visible.").toString()
            if( driftedB )
                parts << ("Run B's config was rebuilt from its working tree at ${shortSha(currentRevisionB)}, " +
                        "but the run was launched at ${shortSha(revisionB)}; config differences driven by code " +
                        "changes between those revisions are not visible.").toString()
            if( workingTreeDirty )
                parts << ("Run A's working tree (${dirA}) has uncommitted changes, so its resolved " +
                        "configuration reflects local edits that may not match the run.").toString()
            if( dirtyB )
                parts << ("Run B's working tree (${dirB}) has uncommitted changes, so its resolved " +
                        "configuration reflects local edits that may not match the run.").toString()
            return parts.join(' ')
        }

        /** Abbreviate a commit id to 10 chars for display; {@code (unknown)} when null. */
        private static String shortSha(String sha) {
            if( !sha )
                return '(unknown)'
            final s = sha.trim()
            return s.length() > 10 ? s.substring(0, 10) : s
        }
    }

    /** How a task relates between the two runs. */
    static enum Kind { ADDED, REMOVED, CHANGED, UNCHANGED }

    /**
     * A single output file compared between the two matched tasks' work
     * directories, keyed by its path relative to the work dir. {@code kind}
     * classifies it exactly like a task: {@link Kind#ADDED} means present only
     * in Run B, {@link Kind#REMOVED} only in Run A, {@link Kind#CHANGED} when
     * size or content differs, {@link Kind#UNCHANGED} when identical.
     */
    @CompileStatic
    static class OutputFileDiff {
        /** Path relative to the task work directory. */
        String path
        Long sizeA
        Long sizeB
        /** Short content hash (SHA-256 prefix), or null when not computed. */
        String hashA
        String hashB
        Kind kind
        /**
         * True when the classification was fully verified (by size, or by
         * content hash when sizes matched). False when two equal-sized files
         * exceeded the hashing cap and were left content-unverified.
         */
        boolean verified = true
        /** Optional human note (e.g. why a file was left unverified). */
        String note
        /**
         * Line-level diff ops for a {@link Kind#CHANGED} file that was
         * recognised as text on both sides. Empty for binary files, for
         * added/removed files (no counterpart to diff), or when the layer left
         * the file unverified. When populated, it answers <em>what</em> changed
         * rather than merely <em>that</em> it changed. Bounded by the read/line
         * caps so an enormous file never blows up memory.
         */
        List<LineDiff.Op> ops = []
        /** True when the line-level diff was capped (byte or line limit hit) on that side. */
        boolean truncatedA
        boolean truncatedB

        /** True when a line-level diff was computed for this file. */
        boolean hasLineDiff() { !ops.isEmpty() }
        /** True when the line-level diff was capped on either side. */
        boolean isTruncated() { truncatedA || truncatedB }
        int linesAdded()   { ops.count { it.type == LineDiff.Type.INSERT } as int }
        int linesRemoved() { ops.count { it.type == LineDiff.Type.DELETE } as int }
    }

    /**
     * Comparison of the output files produced by a task in each run. Populated
     * only when output diffing is enabled ({@link #diffOutputs}). When the two
     * tasks share a physical work directory (e.g. Run B resumed the task from
     * cache), {@link #sameWorkdir} is set and no files are enumerated — the
     * outputs are identical by construction.
     */
    @CompileStatic
    static class OutputDiff {
        String taskKey
        String process
        String workdirA
        String workdirB
        /** Whether each run's work directory existed and was readable. */
        boolean availableA
        boolean availableB
        /** True when both tasks resolved to the same physical work directory. */
        boolean sameWorkdir
        /** Human note when outputs could not be compared (missing work dir, etc.). */
        String note
        List<OutputFileDiff> files = []

        List<OutputFileDiff> changedFiles() { files.findAll { it.kind == Kind.CHANGED } }
        List<OutputFileDiff> addedFiles()   { files.findAll { it.kind == Kind.ADDED } }
        List<OutputFileDiff> removedFiles()  { files.findAll { it.kind == Kind.REMOVED } }

        /** True when any output file was added, removed, or changed. */
        boolean hasChanges() {
            return files.any { it.kind == Kind.ADDED || it.kind == Kind.REMOVED || it.kind == Kind.CHANGED }
        }
    }

    /**
     * A single task log file ({@code .command.out}, {@code .command.err} or
     * {@code .command.log}) compared between the two matched tasks' work
     * directories. {@code kind} classifies it like everything else:
     * {@link Kind#ADDED} present only in Run B, {@link Kind#REMOVED} only in
     * Run A, {@link Kind#CHANGED} when the contents differ, {@link Kind#UNCHANGED}
     * when identical. The line-level {@link #ops} drive the side-by-side and
     * unified renderings.
     */
    @CompileStatic
    static class LogFileDiff {
        /** Log file name, e.g. {@code .command.err}. */
        String name
        Kind kind
        /** File size in bytes on each side, or null when absent. */
        Long bytesA
        Long bytesB
        /** True when the content was tailed to the line cap before diffing. */
        boolean truncatedA
        boolean truncatedB
        /** Line-level diff ops (over the tailed content). */
        List<LineDiff.Op> ops = []

        int linesAdded()   { ops.count { it.type == LineDiff.Type.INSERT } as int }
        int linesRemoved() { ops.count { it.type == LineDiff.Type.DELETE } as int }
        boolean isTruncated() { truncatedA || truncatedB }
    }

    /**
     * Comparison of the standard log files a task produced in each run. Populated
     * only when log diffing is enabled ({@link #diffLogs}). Because task stdout
     * and stderr legitimately vary between runs (timestamps, absolute paths,
     * ordering), this layer is <em>informational only</em> — it never affects
     * {@link #isIdentical()} or {@code --fail-on-change}. The meaningful signal,
     * an exit-code or status change, is already captured by the per-task field
     * diff; this layer explains <em>why</em> a task behaved differently.
     */
    @CompileStatic
    static class LogDiff {
        String taskKey
        String process
        String workdirA
        String workdirB
        /** Whether each run's work directory existed and was readable. */
        boolean availableA
        boolean availableB
        /** True when both tasks resolved to the same physical work directory. */
        boolean sameWorkdir
        /** Human note when logs could not be compared (missing work dir, etc.). */
        String note
        /** Exit code / status captured from each task, to surface failures. */
        String exitA
        String exitB
        String statusA
        String statusB
        List<LogFileDiff> logs = []

        boolean isExitChanged()   { (exitA ?: '') != (exitB ?: '') }
        boolean isStatusChanged() { (statusA ?: '') != (statusB ?: '') }
        /** True when either side failed (non-zero, numeric exit code). */
        boolean isFailure()       { failed(exitA) || failed(exitB) }

        List<LogFileDiff> changedLogs() { logs.findAll { it.kind != Kind.UNCHANGED } }

        /** True when any log file differs, or the exit/status changed. */
        boolean hasChanges() {
            return exitChanged || statusChanged || logs.any { it.kind != Kind.UNCHANGED }
        }

        private static boolean failed(String exit) {
            if( !exit )
                return false
            try {
                return Integer.parseInt(exit.trim()) != 0
            }
            catch( NumberFormatException ignored ) {
                return false
            }
        }
    }

    /** Comparison of a single task matched (or not) across runs. */
    @CompileStatic
    static class TaskDiff {
        String key
        Kind kind
        TaskInfo a
        TaskInfo b
        List<FieldDiff> fieldDiffs = []

        String process() {
            return (a?.process) ?: (b?.process) ?: '(unknown)'
        }
    }

    RunSnapshot runA
    RunSnapshot runB

    List<FieldDiff> metadata = []
    /** Launch-command parameters and Nextflow options, keyed by flag. */
    List<FieldDiff> params = []
    /**
     * Resolved Nextflow configuration, flattened to dotted keys
     * (e.g. {@code process.cpus}, {@code docker.enabled}). Empty when config
     * could not be resolved; see {@link #configNote} for why.
     */
    List<FieldDiff> config = []
    /**
     * Human-readable note about the configuration layer: how it was resolved
     * (or why it is empty). Surfaced by the renderers as context.
     */
    String configNote
    /**
     * Git-provenance caveat for the configuration layer: whether the working
     * tree config was resolved from matches the git revision each run was
     * launched at. Null when not computed (e.g. no project directory). See
     * {@link ConfigProvenance}.
     */
    ConfigProvenance configProvenance
    List<ProcessDiff> processes = []
    List<TaskDiff> tasks = []

    /**
     * Per-process software environment (container image + conda spec) diff.
     * Read from the run cache, so this is always populated (no work directories
     * required). A {@link Kind#CHANGED} entry — a process whose
     * container or conda set differs between the runs — counts toward
     * {@link #isIdentical()} and {@code --fail-on-change}: a changed tool
     * version means the runs are not reproducibly identical.
     */
    List<SoftwareDiff> software = []

    /** True when any process's container or conda environment changed between runs. */
    boolean hasSoftwareChanges() {
        return software.any { it.kind == Kind.CHANGED }
    }

    /**
     * Per-process resource-provisioning efficiency for both runs (requested vs
     * measured-peak CPU and memory). Read from the run cache, so it is always
     * populated. Informational only — never affects {@link #isIdentical()}.
     */
    List<ProcessEfficiency> efficiency = []

    /** Processes over-provisioned on CPU or memory in run A / run B. */
    int overProvisionedA() { efficiency.findAll { ProcessEfficiency e -> e.cpuClassA() == 'over' || e.memClassA() == 'over' }.size() }
    int overProvisionedB() { efficiency.findAll { ProcessEfficiency e -> e.cpuClassB() == 'over' || e.memClassB() == 'over' }.size() }

    /** Processes running tight against their CPU or memory request in run A / run B. */
    int tightA() { efficiency.findAll { ProcessEfficiency e -> e.cpuClassA() == 'tight' || e.memClassA() == 'tight' }.size() }
    int tightB() { efficiency.findAll { ProcessEfficiency e -> e.cpuClassB() == 'tight' || e.memClassB() == 'tight' }.size() }

    /** True when any process has a computable efficiency figure worth showing. */
    boolean hasEfficiency() {
        return efficiency.any { ProcessEfficiency e ->
            e.cpuEffA() != null || e.cpuEffB() != null || e.memEffA() != null || e.memEffB() != null
        }
    }

    /** Failed tasks in run A / run B (status FAILED/ABORTED or a non-zero exit). */
    List<TaskFailure> failuresA = []
    List<TaskFailure> failuresB = []

    /**
     * Failures rolled up by (process, status, exit) signature — the top-level
     * "what failed and why" summary. Derived from the per-task status/exit
     * already captured in the run cache, so this layer is informational only:
     * the meaningful identity signal (a task whose status or exit changed) is
     * already carried by the task field diffs, so the rollup never separately
     * affects {@link #isIdentical()} or {@code --fail-on-change}.
     */
    List<FailureGroup> failureGroups = []

    int failedCountA() { failuresA.size() }
    int failedCountB() { failuresB.size() }

    /** Failure signatures that appear only in Run B (regressed). */
    int newFailureCount()      { failureGroups.count { FailureGroup g -> g.isNew() } as int }
    /** Failure signatures present in Run A but gone in Run B (fixed). */
    int resolvedFailureCount() { failureGroups.count { FailureGroup g -> g.isResolved() } as int }

    /** True when the run itself finished in an error state (history status). */
    static boolean runFailed(RunSnapshot run) {
        final s = run?.status?.trim()?.toUpperCase()
        return s != null && (s.startsWith('ERR') || s == 'FAILED' || s == 'ABORTED' || s == 'KILLED')
    }

    /** True when there is anything for the failure rollup to report. */
    boolean hasFailures() {
        return !failuresA.isEmpty() || !failuresB.isEmpty() || runFailed(runA) || runFailed(runB)
    }

    /**
     * Task metrics (realtime, memory) that changed beyond the configured
     * threshold, sorted worst-regression first. Derived from always-changing
     * numeric fields, so this layer never affects {@link #isIdentical()}.
     */
    List<RegressionDiff> regressions = []

    /** The percentage threshold used to flag {@link #regressions}. */
    double perfThreshold

    /**
     * Whether the output-diff layer was computed. When false, {@link #outputs}
     * is empty and output content never affects {@link #isIdentical()}.
     */
    boolean diffOutputs = false

    /**
     * Per-task comparison of the files each matched task wrote to its work
     * directory. Populated only when {@link #diffOutputs} is set. When enabled,
     * an output-file change breaks {@link #isIdentical()}.
     */
    List<OutputDiff> outputs = []

    /**
     * Human-readable note about the outputs layer: how it was computed, or why
     * it is empty/limited (e.g. missing work directories). Surfaced by renderers.
     */
    String outputsNote

    /** True when any compared task produced a changed/added/removed output file. */
    boolean hasOutputChanges() {
        return diffOutputs && outputs.any { it.hasChanges() }
    }

    /**
     * Whether the log-diff layer was computed. When false, {@link #logs} is
     * empty. This layer is always informational and never affects
     * {@link #isIdentical()}.
     */
    boolean diffLogs = false

    /**
     * Per-task comparison of the standard log files ({@code .command.out},
     * {@code .command.err}, {@code .command.log}) each matched task wrote.
     * Populated only when {@link #diffLogs} is set.
     */
    List<LogDiff> logs = []

    /**
     * Human-readable note about the logs layer: how it was computed, or why it
     * is empty/limited (e.g. missing work directories). Surfaced by renderers.
     */
    String logsNote

    /** True when any compared task has a differing log file or exit/status. */
    boolean hasLogChanges() {
        return diffLogs && logs.any { it.hasChanges() }
    }

    /**
     * Whether the process&#8594;process wiring layer was computed. When false,
     * {@link #dag} is empty.
     */
    boolean diffDag = false

    /**
     * The reconstructed process&#8594;process edges compared across the two
     * runs, sorted with changed edges (added, then removed) before unchanged.
     * Populated only when {@link #diffDag} is set.
     *
     * <p>The wiring is reconstructed heuristically from work-dir input symlinks
     * and is only as complete as the work directories still present locally, so
     * this layer is <em>informational only</em> — it never affects
     * {@link #isIdentical()} or {@code --fail-on-change}.
     */
    List<DagEdgeDiff> dag = []

    /**
     * Human-readable note about the wiring layer: how it was reconstructed, or
     * why it is empty/limited (e.g. missing work directories). Surfaced by
     * renderers.
     */
    String dagNote

    /** Edges present only in Run B (newly-added dependencies). */
    int dagEdgesAdded()   { dag.count { DagEdgeDiff d -> d.isAdded() } as int }
    /** Edges present only in Run A (dropped dependencies). */
    int dagEdgesRemoved() { dag.count { DagEdgeDiff d -> d.isRemoved() } as int }

    /** True when the reconstructed wiring differs between the two runs. */
    boolean hasDagChanges() {
        return diffDag && dag.any { DagEdgeDiff d -> d.kind == Kind.ADDED || d.kind == Kind.REMOVED }
    }

    int tasksAdded
    int tasksRemoved
    int tasksChanged
    int tasksUnchanged

    /**
     * Matched tasks (present in both runs) whose cache hash differs — i.e. the
     * task would have been recomputed rather than resumed. This is the direct
     * answer to "why did my pipeline redo work?".
     */
    int tasksRecomputed

    /**
     * When true, fields that always differ between runs (see {@link FieldDiff#obvious})
     * are treated as meaningful changes. When false (the default), they are shown
     * for context but never flagged, counted, or allowed to break "identical".
     */
    boolean showObvious = false

    Date generatedAt = new Date()

    /**
     * True when the two runs match at every inspected layer. By default this
     * ignores always-changing fields; with {@link #showObvious} it is exact.
     */
    boolean isIdentical() {
        return tasksAdded == 0 && tasksRemoved == 0 && tasksChanged == 0 &&
                metadata.every { !it.isHighlighted(showObvious) } &&
                params.every { !it.isHighlighted(showObvious) } &&
                config.every { !it.isHighlighted(showObvious) } &&
                processes.every { it.unchanged } &&
                !hasSoftwareChanges() &&
                !hasOutputChanges()
    }
}
