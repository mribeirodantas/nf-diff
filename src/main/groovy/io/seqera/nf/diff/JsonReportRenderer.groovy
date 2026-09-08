package io.seqera.nf.diff

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import io.seqera.nf.diff.DiffResult.FieldDiff
import io.seqera.nf.diff.DiffResult.ProcessDiff
import io.seqera.nf.diff.DiffResult.TaskDiff

/**
 * Renders a {@link DiffResult} as machine-readable JSON, for CI pipelines,
 * PR bots and dashboards that need the comparison programmatically rather than
 * as an HTML document.
 *
 * The shape mirrors {@link DiffResult}: run metadata, a summary, and the three
 * comparison layers (metadata / params / processes / tasks). Field-level diffs carry
 * {@code changed}, {@code obvious} and {@code highlighted} flags so consumers
 * can apply the same meaningful-vs-verbose distinction the HTML report uses.
 */
@CompileStatic
class JsonReportRenderer {

    /** Render {@code diff} to a pretty-printed JSON document. */
    String render(DiffResult diff) {
        return JsonOutput.prettyPrint(JsonOutput.toJson(toModel(diff)))
    }

    private Map<String,Object> toModel(DiffResult diff) {
        return [
                generatedAt: diff.generatedAt?.toInstant()?.toString(),
                identical  : diff.identical,
                showObvious: diff.showObvious,
                runA       : runModel(diff.runA),
                runB       : runModel(diff.runB),
                summary    : [
                        tasksChanged  : diff.tasksChanged,
                        tasksAdded    : diff.tasksAdded,
                        tasksRemoved  : diff.tasksRemoved,
                        tasksUnchanged: diff.tasksUnchanged,
                ],
                metadata   : diff.metadata.collect { fieldModel(it, diff.showObvious) },
                params     : diff.params.collect { fieldModel(it, diff.showObvious) },
                config     : diff.config.collect { fieldModel(it, diff.showObvious) },
                configNote : diff.configNote,
                processes  : diff.processes.collect { processModel(it) },
                tasks      : diff.tasks.collect { taskModel(it, diff.showObvious) },
        ] as Map<String,Object>
    }

    private Map<String,Object> runModel(RunSnapshot run) {
        return [
                runName          : run.runName,
                sessionId        : run.sessionId?.toString(),
                status           : run.status,
                revision         : run.revisionId,
                command          : run.command,
                launched         : run.timestamp?.toInstant()?.toString(),
                wallDurationMillis: run.durationMillis,
                taskCount        : run.tasks.size(),
                cachedTasks      : run.cachedCount(),
        ] as Map<String,Object>
    }

    private Map<String,Object> fieldModel(FieldDiff fd, boolean showObvious) {
        final model = [
                field      : fd.field,
                valueA     : fd.valueA,
                valueB     : fd.valueB,
                changed    : fd.changed,
                obvious    : fd.obvious,
                highlighted: fd.isHighlighted(showObvious),
        ] as Map<String,Object>
        // Provenance only applies to the parameters layer; omit it elsewhere.
        if( fd.sourceA != null )
            model.sourceA = fd.sourceA
        if( fd.sourceB != null )
            model.sourceB = fd.sourceB
        return model
    }

    private Map<String,Object> processModel(ProcessDiff pd) {
        return [
                process: pd.process,
                countA : pd.countA,
                countB : pd.countB,
                status : processStatus(pd),
        ] as Map<String,Object>
    }

    private static String processStatus(ProcessDiff pd) {
        if( pd.added )   return 'added'
        if( pd.removed ) return 'removed'
        if( pd.changed ) return 'changed'
        return 'unchanged'
    }

    private Map<String,Object> taskModel(TaskDiff td, boolean showObvious) {
        // Only emit field-level detail for the changes that are actually
        // surfaced; added/removed tasks have no counterpart to diff against.
        final fields = td.fieldDiffs
                .findAll { it.isHighlighted(showObvious) }
                .collect { fieldModel(it, showObvious) }
        return [
                key    : td.key,
                kind   : td.kind.name().toLowerCase(),
                process: td.process(),
                fields : fields,
        ] as Map<String,Object>
    }
}
