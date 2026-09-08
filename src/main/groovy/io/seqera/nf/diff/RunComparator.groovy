package io.seqera.nf.diff

import groovy.transform.CompileStatic
import io.seqera.nf.diff.DiffResult.FieldDiff
import io.seqera.nf.diff.DiffResult.Kind
import io.seqera.nf.diff.DiffResult.ProcessDiff
import io.seqera.nf.diff.DiffResult.TaskDiff

/**
 * Computes a {@link DiffResult} between two {@link RunSnapshot} instances across
 * three layers: run metadata, process topology, and per-task detail.
 */
@CompileStatic
class RunComparator {

    /**
     * Per-task trace fields compared for the "tiniest details" view, in the
     * order they should appear in the report. All are pulled from the task's
     * formatted display map.
     */
    static final List<String> TASK_FIELDS = [
            'status', 'exit', 'container', 'script',
            'cpus', 'memory', 'time', 'disk',
            'realtime', '%cpu', 'peak_rss', 'peak_vmem',
            'rchar', 'wchar', 'attempt', 'queue', 'workdir', 'tag'
    ]

    /**
     * Task fields that vary between essentially any two runs even when the work
     * is identical (unique work dir, timing, and measured resource usage). These
     * are flagged {@code obvious} and excluded from change detection unless the
     * verbose view is enabled.
     */
    static final Set<String> OBVIOUS_TASK_FIELDS = [
            'realtime', '%cpu', 'peak_rss', 'peak_vmem', 'rchar', 'wchar', 'workdir'
    ] as Set

    /** Run-metadata labels that always differ between two distinct runs. */
    static final Set<String> OBVIOUS_METADATA = [
            'Run name', 'Session ID', 'Launched', 'Wall duration', 'Total task realtime'
    ] as Set

    /** When true, always-changing fields are treated as meaningful changes. */
    private final boolean showObvious

    RunComparator(boolean showObvious = false) {
        this.showObvious = showObvious
    }

    DiffResult compare(RunSnapshot a, RunSnapshot b) {
        final result = new DiffResult(runA: a, runB: b, showObvious: showObvious)
        result.metadata = compareMetadata(a, b)
        result.processes = compareProcesses(a, b)
        result.tasks = compareTasks(a, b)

        result.tasks.each { TaskDiff td ->
            switch( td.kind ) {
                case Kind.ADDED:     result.tasksAdded++;     break
                case Kind.REMOVED:   result.tasksRemoved++;   break
                case Kind.CHANGED:   result.tasksChanged++;   break
                case Kind.UNCHANGED: result.tasksUnchanged++; break
            }
        }
        return result
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
