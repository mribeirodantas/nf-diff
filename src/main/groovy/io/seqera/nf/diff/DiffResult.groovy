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
    List<ProcessDiff> processes = []
    List<TaskDiff> tasks = []

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
                !hasOutputChanges()
    }
}
