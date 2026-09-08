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
        renderMetadata(sb, diff)
        renderParams(sb, diff)
        renderConfig(sb, diff)
        renderProcesses(sb, diff)
        renderRegressions(sb, diff)
        renderTasks(sb, diff)
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
            sb << '| Changed | Only in B | Only in A | Unchanged | Recomputed | Regressions |\n'
            sb << '|--------:|----------:|----------:|----------:|-----------:|------------:|\n'
            sb << "| ${diff.tasksChanged} | ${diff.tasksAdded} | ${diff.tasksRemoved} | ${diff.tasksUnchanged} | ${diff.tasksRecomputed} | ${diff.regressions.count { it.regression }} |\n\n"
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
