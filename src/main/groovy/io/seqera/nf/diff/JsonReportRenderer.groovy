package io.seqera.nf.diff

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import io.seqera.nf.diff.DiffResult.FieldDiff
import io.seqera.nf.diff.DiffResult.ProcessDiff
import io.seqera.nf.diff.DiffResult.RegressionDiff
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
                        tasksChanged   : diff.tasksChanged,
                        tasksAdded     : diff.tasksAdded,
                        tasksRemoved   : diff.tasksRemoved,
                        tasksUnchanged : diff.tasksUnchanged,
                        tasksRecomputed: diff.tasksRecomputed,
                        regressions    : diff.regressions.count { it.regression },
                        softwareChanged : diff.software.count { it.changed },
                        outputsChanged : diff.outputs.count { it.hasChanges() },
                        logsChanged    : diff.logs.count { it.hasChanges() },
                        overProvisionedA: diff.overProvisionedA(),
                        overProvisionedB: diff.overProvisionedB(),
                        tightA          : diff.tightA(),
                        tightB          : diff.tightB(),
                        failedA         : diff.failedCountA(),
                        failedB         : diff.failedCountB(),
                        newFailures     : diff.newFailureCount(),
                        resolvedFailures: diff.resolvedFailureCount(),
                ],
                metadata   : diff.metadata.collect { fieldModel(it, diff.showObvious) },
                params     : diff.params.collect { fieldModel(it, diff.showObvious) },
                config     : diff.config.collect { fieldModel(it, diff.showObvious) },
                configNote : diff.configNote,
                configProvenance: configProvenanceModel(diff.configProvenance),
                processes  : diff.processes.collect { processModel(it) },
                software   : diff.software.collect { softwareModel(it) },
                efficiency : diff.efficiency.collect { efficiencyModel(it) },
                failures   : [
                        runFailedA   : DiffResult.runFailed(diff.runA),
                        runFailedB   : DiffResult.runFailed(diff.runB),
                        groups       : diff.failureGroups.collect { failureGroupModel(it) },
                        tasksA       : diff.failuresA.collect { failureTaskModel(it) },
                        tasksB       : diff.failuresB.collect { failureTaskModel(it) },
                ],
                tasks      : diff.tasks.collect { taskModel(it, diff.showObvious) },
                perfThreshold: diff.perfThreshold,
                regressions: diff.regressions.collect { regressionModel(it) },
                diffOutputs: diff.diffOutputs,
                outputsNote: diff.outputsNote,
                outputs    : diff.outputs.collect { outputModel(it) },
                diffLogs   : diff.diffLogs,
                logsNote   : diff.logsNote,
                logs       : diff.logs.collect { logModel(it) },
        ] as Map<String,Object>
    }

    private Map<String,Object> logModel(DiffResult.LogDiff ld) {
        return [
                task         : ld.taskKey,
                process      : ld.process,
                workdirA     : ld.workdirA,
                workdirB     : ld.workdirB,
                availableA   : ld.availableA,
                availableB   : ld.availableB,
                sameWorkdir  : ld.sameWorkdir,
                hasChanges   : ld.hasChanges(),
                failure      : ld.failure,
                exitChanged  : ld.exitChanged,
                statusChanged: ld.statusChanged,
                exitA        : ld.exitA,
                exitB        : ld.exitB,
                statusA      : ld.statusA,
                statusB      : ld.statusB,
                note         : ld.note,
                logs         : ld.logs.collect { logFileModel(it) },
        ] as Map<String,Object>
    }

    private Map<String,Object> logFileModel(DiffResult.LogFileDiff f) {
        return [
                name        : f.name,
                kind        : f.kind?.name()?.toLowerCase(),
                bytesA      : f.bytesA,
                bytesB      : f.bytesB,
                linesAdded  : f.linesAdded(),
                linesRemoved: f.linesRemoved(),
                truncated   : f.truncated,
                diff        : unifiedDiff(f.ops),
        ] as Map<String,Object>
    }

    /** Render line-diff ops as a unified-style array (' ' context, '+' add, '-' del). */
    private static List<String> unifiedDiff(List<LineDiff.Op> ops) {
        return ops.collect { LineDiff.Op op ->
            final prefix = op.type == LineDiff.Type.INSERT ? '+'
                    : (op.type == LineDiff.Type.DELETE ? '-' : ' ')
            return "${prefix}${op.text}".toString()
        }
    }

    private Map<String,Object> outputModel(DiffResult.OutputDiff od) {
        return [
                task       : od.taskKey,
                process    : od.process,
                workdirA   : od.workdirA,
                workdirB   : od.workdirB,
                availableA : od.availableA,
                availableB : od.availableB,
                sameWorkdir: od.sameWorkdir,
                hasChanges : od.hasChanges(),
                note       : od.note,
                files      : od.files.collect { outputFileModel(it) },
        ] as Map<String,Object>
    }

    private Map<String,Object> outputFileModel(DiffResult.OutputFileDiff f) {
        final model = [
                path    : f.path,
                kind    : f.kind?.name()?.toLowerCase(),
                sizeA   : f.sizeA,
                sizeB   : f.sizeB,
                verified: f.verified,
        ] as Map<String,Object>
        if( f.hashA != null ) model.hashA = f.hashA
        if( f.hashB != null ) model.hashB = f.hashB
        if( f.note != null )  model.note = f.note
        if( f.hasLineDiff() ) {
            model.linesAdded   = f.linesAdded()
            model.linesRemoved = f.linesRemoved()
            model.truncated    = f.truncated
            model.diff         = unifiedDiff(f.ops)
        }
        return model
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

    /**
     * Model the config-provenance caveat. Returns null when it was not computed
     * (no project directory), so consumers can distinguish "not checked" from
     * "checked, clean". The {@code warning} is null when config provenance is
     * trustworthy.
     */
    private Map<String,Object> configProvenanceModel(DiffResult.ConfigProvenance prov) {
        if( prov == null )
            return null
        return [
                gitAvailable    : prov.gitAvailable,
                crossProject    : prov.crossProject,
                dirA            : prov.dirA,
                dirB            : prov.dirB,
                currentRevision : prov.currentRevision,
                currentRevisionB: prov.currentRevisionB,
                workingTreeDirty: prov.workingTreeDirty,
                dirtyB          : prov.dirtyB,
                revisionA       : prov.revisionA,
                revisionB       : prov.revisionB,
                driftedA        : prov.driftedA,
                driftedB        : prov.driftedB,
                warning         : prov.warning(),
        ] as Map<String,Object>
    }

    private Map<String,Object> processModel(ProcessDiff pd) {
        return [
                process: pd.process,
                countA : pd.countA,
                countB : pd.countB,
                status : processStatus(pd),
        ] as Map<String,Object>
    }

    private Map<String,Object> softwareModel(DiffResult.SoftwareDiff sd) {
        return [
                process         : sd.process,
                status          : sd.kind?.name()?.toLowerCase(),
                containerChanged: sd.containerChanged,
                condaChanged    : sd.condaChanged,
                containersA     : sd.containersA,
                containersB     : sd.containersB,
                condaA          : sd.condaA,
                condaB          : sd.condaB,
        ] as Map<String,Object>
    }

    private Map<String,Object> efficiencyModel(DiffResult.ProcessEfficiency e) {
        return [
                process        : e.process,
                cpusRequestedA : e.cpusReqA,
                cpusRequestedB : e.cpusReqB,
                peakCpuPctA    : e.peakCpuPctA,
                peakCpuPctB    : e.peakCpuPctB,
                cpuEfficiencyA : e.cpuEffA(),
                cpuEfficiencyB : e.cpuEffB(),
                cpuClassA      : e.cpuClassA(),
                cpuClassB      : e.cpuClassB(),
                memRequestedBytesA: e.memReqBytesA,
                memRequestedBytesB: e.memReqBytesB,
                peakRssBytesA  : e.peakRssBytesA,
                peakRssBytesB  : e.peakRssBytesB,
                memEfficiencyA : e.memEffA(),
                memEfficiencyB : e.memEffB(),
                memClassA      : e.memClassA(),
                memClassB      : e.memClassB(),
        ] as Map<String,Object>
    }

    private Map<String,Object> failureGroupModel(DiffResult.FailureGroup g) {
        return [
                process : g.process,
                status  : g.status,
                exit    : g.exit,
                countA  : g.countA,
                countB  : g.countB,
                state   : g.isNew() ? 'new' : (g.isResolved() ? 'resolved' : 'persistent'),
        ] as Map<String,Object>
    }

    private Map<String,Object> failureTaskModel(DiffResult.TaskFailure f) {
        return [
                task    : f.taskKey,
                process : f.process,
                tag     : f.tag,
                status  : f.status,
                exit    : f.exit,
        ] as Map<String,Object>
    }

    private static String processStatus(ProcessDiff pd) {
        if( pd.added )   return 'added'
        if( pd.removed ) return 'removed'
        if( pd.changed ) return 'changed'
        return 'unchanged'
    }

    private Map<String,Object> regressionModel(RegressionDiff r) {
        return [
                task      : r.taskKey,
                process   : r.process,
                metric    : r.metric,
                label     : r.label,
                valueA    : r.valueA,
                valueB    : r.valueB,
                displayA  : r.displayA,
                displayB  : r.displayB,
                pctDelta  : r.pctDelta,
                regression: r.regression,
                sameHash  : r.sameHash,
        ] as Map<String,Object>
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
