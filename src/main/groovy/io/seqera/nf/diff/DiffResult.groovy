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

    /** How a task relates between the two runs. */
    static enum Kind { ADDED, REMOVED, CHANGED, UNCHANGED }

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
    List<ProcessDiff> processes = []
    List<TaskDiff> tasks = []

    int tasksAdded
    int tasksRemoved
    int tasksChanged
    int tasksUnchanged

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
                processes.every { it.unchanged }
    }
}
