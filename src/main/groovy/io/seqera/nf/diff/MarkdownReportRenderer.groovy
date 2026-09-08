package io.seqera.nf.diff

import groovy.transform.CompileStatic
import io.seqera.nf.diff.DiffResult.FieldDiff
import io.seqera.nf.diff.DiffResult.ProcessDiff
import io.seqera.nf.diff.DiffResult.RegressionDiff
import io.seqera.nf.diff.DiffResult.TaskDiff

/**
 * Renders a {@link DiffResult} as GitHub-flavoured Markdown, for pasting into
 * pull-request comments, issues or chat where a full HTML document is overkill
 * but plain JSON is unreadable.
 *
 * The document mirrors the other renderers: a summary, the two run headers, and
 * the three comparison layers (run metadata / process topology / per-task
 * detail). Only meaningful changes are shown unless the diff was produced in
 * verbose mode ({@link DiffResult#showObvious}).
 */
@CompileStatic
class MarkdownReportRenderer {

    /** Render {@code diff} to a Markdown document. */
    String render(DiffResult diff) {
        final sb = new StringBuilder()
        renderHeader(sb, diff)
        renderRuns(sb, diff)
        renderFailures(sb, diff)
        renderMetadata(sb, diff)
        renderParams(sb, diff)
        renderConfig(sb, diff)
        renderProcesses(sb, diff)
        renderSoftware(sb, diff)
        renderRegressions(sb, diff)
        renderEfficiency(sb, diff)
        renderTasks(sb, diff)
        renderOutputs(sb, diff)
        renderLogs(sb, diff)
        renderDag(sb, diff)
        return sb.toString()
    }

    private void renderHeader(StringBuilder sb, DiffResult diff) {
        sb << '# nf-diff report\n\n'
        sb << "> Generated ${diff.generatedAt?.toInstant()} · "
        sb << (diff.showObvious ? 'verbose (all fields)' : 'meaningful changes only')
        sb << '\n\n'

        if( diff.identical ) {
            sb << '**The two runs are identical** at every inspected layer.\n\n'
        }
        else {
            sb << '## Summary\n\n'
            sb << '| Changed | Only in B | Only in A | Unchanged | Recomputed | Software | Regressions |\n'
            sb << '|--------:|----------:|----------:|----------:|-----------:|---------:|------------:|\n'
            sb << "| ${diff.tasksChanged} | ${diff.tasksAdded} | ${diff.tasksRemoved} | ${diff.tasksUnchanged} | ${diff.tasksRecomputed} | ${diff.software.count { it.changed }} | ${diff.regressions.count { it.regression }} |\n\n"
        }
    }

    private void renderRuns(StringBuilder sb, DiffResult diff) {
        sb << '## Runs\n\n'
        sb << '| | Run A | Run B |\n'
        sb << '|---|---|---|\n'
        final a = diff.runA
        final b = diff.runB
        row(sb, 'Run name', a.runName, b.runName)
        row(sb, 'Session id', a.sessionId?.toString(), b.sessionId?.toString())
        row(sb, 'Status', a.status, b.status)
        row(sb, 'Revision', a.revisionId, b.revisionId)
        row(sb, 'Command', a.command, b.command)
        row(sb, 'Launched', a.timestamp?.toInstant()?.toString(), b.timestamp?.toInstant()?.toString())
        row(sb, 'Wall time (ms)', a.durationMillis?.toString(), b.durationMillis?.toString())
        row(sb, 'Tasks', a.tasks.size().toString(), b.tasks.size().toString())
        row(sb, 'Cached', a.cachedCount().toString(), b.cachedCount().toString())
        sb << '\n'
    }

    private void renderFailures(StringBuilder sb, DiffResult diff) {
        if( !diff.hasFailures() )
            return
        sb << '## Failure rollup\n\n'
        sb << '> What failed and why, rolled up by process / status / exit code (read from the run cache). '
        sb << '"new" = only in Run B; "resolved" = in Run A but gone in Run B. '
        sb << 'A summary of the per-task status/exit — informational only.\n\n'
        if( DiffResult.runFailed(diff.runA) )
            sb << "> ⚠ **Run A** finished in status `${(diff.runA.status ?: 'UNKNOWN').toUpperCase()}`.\n\n"
        if( DiffResult.runFailed(diff.runB) )
            sb << "> ⚠ **Run B** finished in status `${(diff.runB.status ?: 'UNKNOWN').toUpperCase()}`.\n\n"
        if( diff.failureGroups.isEmpty() ) {
            sb << '_No task-level failures recorded in either run cache._\n\n'
            return
        }
        sb << '| Process | Status | Exit | Run A | Run B | State |\n'
        sb << '|---|---|---:|---:|---:|:---:|\n'
        diff.failureGroups.each { DiffResult.FailureGroup g ->
            final state = g.isNew() ? 'new' : (g.isResolved() ? 'resolved' : 'persistent')
            sb << "| ${cell(g.process)} | ${cell(g.status)} | ${cell(g.exit)} | ${g.countA} | ${g.countB} | ${state} |\n"
        }
        sb << '\n'
    }

    private void renderMetadata(StringBuilder sb, DiffResult diff) {
        final highlighted = diff.metadata.findAll { FieldDiff fd -> fd.isHighlighted(diff.showObvious) }
        sb << '## Run metadata\n\n'
        if( highlighted.isEmpty() ) {
            sb << '_No metadata changes._\n\n'
            return
        }
        sb << '| Field | Run A | Run B |\n'
        sb << '|---|---|---|\n'
        highlighted.each { FieldDiff fd -> row(sb, fd.field, fd.valueA, fd.valueB) }
        sb << '\n'
    }

    private void renderParams(StringBuilder sb, DiffResult diff) {
        sb << '## Parameters & options\n\n'
        if( diff.params.isEmpty() ) {
            sb << '_No command-line parameters recorded._\n\n'
            return
        }
        final highlighted = diff.params.findAll { FieldDiff fd -> fd.isHighlighted(diff.showObvious) }
        if( highlighted.isEmpty() ) {
            sb << '_No parameter changes._\n\n'
            return
        }
        sb << '| Flag | Run A | Run B | Source |\n'
        sb << '|---|---|---|---|\n'
        highlighted.each { FieldDiff fd ->
            sb << "| ${cell(fd.field)} | ${cell(fd.valueA)} | ${cell(fd.valueB)} | ${cell(sourceLabel(fd))} |\n"
        }
        sb << '\n'
    }

    /** Compact provenance label for a param row (e.g. `CLI`, `file`, `A:file B:CLI`). */
    private static String sourceLabel(FieldDiff fd) {
        if( fd.sourceA == fd.sourceB )
            return fd.sourceA ?: ''
        return "A:${fd.sourceA ?: '—'} B:${fd.sourceB ?: '—'}".toString()
    }

    private void renderConfig(StringBuilder sb, DiffResult diff) {
        sb << '## Resolved configuration\n\n'
        final highlighted = diff.config.findAll { FieldDiff fd -> fd.isHighlighted(diff.showObvious) }
        if( diff.configNote )
            sb << "> ${cell(diff.configNote)}\n\n"
        final provWarning = diff.configProvenance?.warning()
        if( provWarning )
            sb << "> ⚠️ **Config provenance:** ${cell(provWarning)}\n\n"
        if( diff.config.isEmpty() ) {
            return
        }
        if( highlighted.isEmpty() ) {
            sb << '_No configuration changes._\n\n'
            return
        }
        sb << '| Key | Run A | Run B |\n'
        sb << '|---|---|---|\n'
        highlighted.each { FieldDiff fd -> row(sb, fd.field, fd.valueA, fd.valueB) }
        sb << '\n'
    }

    private void renderProcesses(StringBuilder sb, DiffResult diff) {
        sb << '## Process topology\n\n'
        if( diff.processes.isEmpty() ) {
            sb << '_No processes to compare._\n\n'
            return
        }
        sb << '| Process | Run A | Run B | Status |\n'
        sb << '|---|---:|---:|---|\n'
        diff.processes.each { ProcessDiff pd ->
            sb << "| ${cell(pd.process)} | ${pd.countA} | ${pd.countB} | ${processStatus(pd)} |\n"
        }
        sb << '\n'
    }

    private void renderSoftware(StringBuilder sb, DiffResult diff) {
        sb << '## Software & versions\n\n'
        if( diff.software.isEmpty() ) {
            sb << '_No software environment recorded._\n\n'
            return
        }
        final changed = diff.software.findAll { DiffResult.SoftwareDiff sd -> sd.kind != DiffResult.Kind.UNCHANGED }
        if( changed.isEmpty() ) {
            sb << '_No container or conda changes — every process ran with the same software environment._\n\n'
            return
        }
        sb << '| Process | Container | Conda | Status |\n'
        sb << '|---|---|---|---|\n'
        changed.each { DiffResult.SoftwareDiff sd ->
            sb << "| ${cell(sd.process)} | ${softwareCell(sd.containersA, sd.containersB, sd.containerChanged)}"
            sb << " | ${softwareCell(sd.condaA, sd.condaB, sd.condaChanged)} | ${sd.kind.name().toLowerCase()} |\n"
        }
        sb << '\n'
    }

    /** Software table cell: single value when unchanged, `A → B` when it changed. */
    private static String softwareCell(List<String> a, List<String> b, boolean changed) {
        final left = a.isEmpty() ? '—' : a.join(', ')
        if( !changed )
            return cell(left)
        final right = b.isEmpty() ? '—' : b.join(', ')
        return cell("${left} → ${right}".toString())
    }

    private void renderRegressions(StringBuilder sb, DiffResult diff) {
        sb << '## Performance regressions\n\n'
        if( diff.regressions.isEmpty() ) {
            sb << "_No task metric changed by ≥ ${trim(diff.perfThreshold)}% between the runs._\n\n"
            return
        }
        sb << "> Metrics that changed by ≥ ${trim(diff.perfThreshold)}% (positive = Run B slower/heavier). "
        sb << '"Same work" marks tasks whose cache hash is identical, so the cost change is environmental rather than a different computation.\n\n'
        sb << '| Task | Metric | Run A | Run B | Δ | Same work |\n'
        sb << '|---|---|---|---|---:|:---:|\n'
        diff.regressions.each { RegressionDiff r ->
            final arrow = r.regression ? '🔺' : '🔻'
            sb << "| ${cell(r.taskKey)} | ${cell(r.label)} | ${cell(r.displayA)} | ${cell(r.displayB)} | ${arrow} ${Format.signedPct(r.pctDelta)} | ${r.sameHash ? '✓' : ''} |\n"
        }
        sb << '\n'
    }

    private void renderEfficiency(StringBuilder sb, DiffResult diff) {
        sb << '## Resource efficiency\n\n'
        if( diff.efficiency.isEmpty() ) {
            sb << '_No CPU/memory usage metrics recorded in the run cache._\n\n'
            return
        }
        sb << '> Peak usage vs. what each process requested. '
        sb << '"over-provisioned" = using a small fraction of the reservation (wasted allocation); '
        sb << '"tight" = using nearly all of it (risk of OOM / CPU starvation).\n\n'
        sb << '| Process | CPU eff. A | CPU eff. B | CPU class | Mem eff. A | Mem eff. B | Mem class |\n'
        sb << '|---|---:|---:|:---:|---:|---:|:---:|\n'
        diff.efficiency.each { DiffResult.ProcessEfficiency e ->
            final cpuClass = e.cpuClassB() ?: e.cpuClassA()
            final memClass = e.memClassB() ?: e.memClassA()
            sb << "| ${cell(e.process)} | ${Format.pct(e.cpuEffA())} | ${Format.pct(e.cpuEffB())} | ${cell(cpuClass ?: '—')}"
            sb << " | ${Format.pct(e.memEffA())} | ${Format.pct(e.memEffB())} | ${cell(memClass ?: '—')} |\n"
        }
        sb << '\n'
    }

    private void renderOutputs(StringBuilder sb, DiffResult diff) {
        if( !diff.diffOutputs )
            return
        sb << '## Output files\n\n'
        if( diff.outputsNote )
            sb << "> ${cell(diff.outputsNote)}\n\n"

        final changed = diff.outputs.findAll { DiffResult.OutputDiff od -> od.hasChanges() }
        if( changed.isEmpty() ) {
            sb << '_No output-file differences detected._\n\n'
            return
        }
        changed.each { DiffResult.OutputDiff od ->
            sb << "### ${cell(od.taskKey)}\n\n"
            sb << '| File | Run A | Run B | Status |\n'
            sb << '|---|---|---|---|\n'
            od.files.findAll { it.kind != DiffResult.Kind.UNCHANGED }.each { DiffResult.OutputFileDiff f ->
                sb << "| ${cell(f.path)} | ${cell(sizeCell(f.sizeA))} | ${cell(sizeCell(f.sizeB))} | ${f.kind.name().toLowerCase()} |\n"
            }
            sb << '\n'
            // Line-level diff of each changed text file, below the size summary.
            od.files.findAll { it.kind == DiffResult.Kind.CHANGED && it.hasLineDiff() }.each { DiffResult.OutputFileDiff f ->
                sb << "**${cell(f.path)}** (+${f.linesAdded()} −${f.linesRemoved()}"
                if( f.truncated )
                    sb << ', truncated'
                sb << ")\n\n"
                fencedDiff(sb, f.ops)
            }
        }
    }

    /** Byte size for a table cell, or an em dash when the file is absent on that side. */
    private static String sizeCell(Long size) {
        return size == null ? '—' : "${size} B".toString()
    }

    private void renderLogs(StringBuilder sb, DiffResult diff) {
        if( !diff.diffLogs )
            return
        sb << '## Task logs\n\n'
        if( diff.logsNote )
            sb << "> ${cell(diff.logsNote)}\n\n"

        final changed = diff.logs.findAll { DiffResult.LogDiff ld -> ld.hasChanges() }
        if( changed.isEmpty() ) {
            sb << '_No task log differences detected._\n\n'
            return
        }
        changed.each { DiffResult.LogDiff ld ->
            final flag = ld.failure ? ' ⚠️' : ''
            sb << "### ${cell(ld.taskKey)}${flag}\n\n"
            if( ld.exitChanged || ld.statusChanged ) {
                sb << "- **exit**: `${inline(ld.exitA ?: '—')}` → `${inline(ld.exitB ?: '—')}`"
                sb << " · **status**: `${inline(ld.statusA ?: '—')}` → `${inline(ld.statusB ?: '—')}`\n\n"
            }
            ld.changedLogs().each { DiffResult.LogFileDiff f ->
                sb << "**${cell(f.name)}** (${f.kind.name().toLowerCase()}"
                if( f.truncated )
                    sb << ', tailed'
                sb << ")\n\n"
                fencedDiff(sb, f.ops)
            }
        }
    }

    private void renderDag(StringBuilder sb, DiffResult diff) {
        if( !diff.diffDag )
            return
        sb << '## Process wiring (DAG)\n\n'
        if( diff.dagNote )
            sb << "> ${cell(diff.dagNote)}\n\n"

        final changed = diff.dag.findAll { DiffResult.DagEdgeDiff d -> d.isAdded() || d.isRemoved() }
        if( changed.isEmpty() ) {
            sb << '_No process→process wiring differences detected._\n\n'
            return
        }
        sb << '| Edge | Status |\n'
        sb << '|---|:---:|\n'
        changed.each { DiffResult.DagEdgeDiff d ->
            final arrow = d.isAdded() ? '➕' : '➖'
            sb << "| ${cell("${d.from()} → ${d.to()}".toString())} | ${arrow} ${d.isAdded() ? 'added' : 'removed'} |\n"
        }
        sb << '\n'
    }

    /** Emit a fenced unified-diff block (' ' context, '+' add, '-' del). */
    private static void fencedDiff(StringBuilder sb, List<LineDiff.Op> ops) {
        sb << '```diff\n'
        ops.each { LineDiff.Op op ->
            final prefix = op.type == LineDiff.Type.INSERT ? '+'
                    : (op.type == LineDiff.Type.DELETE ? '-' : ' ')
            sb << "${prefix}${op.text}\n"
        }
        sb << '```\n\n'
    }

    /** Trim a whole-number threshold to an integer string (25.0 -> "25"). */
    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value)
    }

    private void renderTasks(StringBuilder sb, DiffResult diff) {
        // Surface tasks that are added, removed, or carry highlighted field changes.
        final surfaced = diff.tasks.findAll { TaskDiff td ->
            td.kind != DiffResult.Kind.UNCHANGED &&
                    (td.kind != DiffResult.Kind.CHANGED ||
                            td.fieldDiffs.any { FieldDiff fd -> fd.isHighlighted(diff.showObvious) })
        }
        sb << '## Task detail\n\n'
        if( surfaced.isEmpty() ) {
            sb << '_No task-level changes._\n\n'
            return
        }
        surfaced.each { TaskDiff td ->
            sb << "### ${cell(td.process())} — ${td.kind.name().toLowerCase()}\n\n"
            sb << "`${td.key}`\n\n"
            final fields = td.fieldDiffs.findAll { FieldDiff fd -> fd.isHighlighted(diff.showObvious) }
            if( fields.isEmpty() )
                return
            fields.each { FieldDiff fd -> renderField(sb, fd) }
        }
    }

    /** Render one field diff, using fenced blocks for multi-line values. */
    private void renderField(StringBuilder sb, FieldDiff fd) {
        final a = fd.valueA ?: ''
        final b = fd.valueB ?: ''
        if( a.contains('\n') || b.contains('\n') ) {
            sb << "- **${fd.field}**\n\n"
            sb << "  Run A:\n\n"
            fenced(sb, a)
            sb << "  Run B:\n\n"
            fenced(sb, b)
        }
        else {
            sb << "- **${fd.field}**: `${inline(a)}` → `${inline(b)}`\n"
        }
    }

    private static String processStatus(ProcessDiff pd) {
        if( pd.added )   return 'added'
        if( pd.removed ) return 'removed'
        if( pd.changed ) return 'changed'
        return 'unchanged'
    }

    private void row(StringBuilder sb, String label, String a, String b) {
        sb << "| ${cell(label)} | ${cell(a)} | ${cell(b)} |\n"
    }

    /** Table-cell safe: collapse newlines and escape the pipe delimiter. */
    private static String cell(String value) {
        if( !value )
            return ''
        return value.replaceAll(/\s*\r?\n\s*/, ' ').replace('|', '\\|')
    }

    /** Inline-code safe: collapse newlines and neutralise backticks. */
    private static String inline(String value) {
        if( !value )
            return ''
        return value.replaceAll(/\s*\r?\n\s*/, ' ').replace('`', "'")
    }

    /** Emit an indented fenced code block for a multi-line value. */
    private static void fenced(StringBuilder sb, String value) {
        sb << '  ```\n'
        value.readLines().each { String line -> sb << "  ${line}\n" }
        sb << '  ```\n\n'
    }
}
