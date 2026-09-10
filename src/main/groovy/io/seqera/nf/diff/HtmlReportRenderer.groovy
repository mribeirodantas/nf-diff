/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.seqera.nf.diff

import groovy.transform.CompileStatic
import io.seqera.nf.diff.DiffResult.FieldDiff
import io.seqera.nf.diff.DiffResult.Kind
import io.seqera.nf.diff.DiffResult.ProcessDiff
import io.seqera.nf.diff.DiffResult.RegressionDiff
import io.seqera.nf.diff.DiffResult.TaskDiff

/**
 * Renders a {@link DiffResult} into a single, self-contained HTML document
 * (inline CSS, JS and SVG — no external assets or network access required).
 */
@CompileStatic
class HtmlReportRenderer {

    /** Trace fields (raw) rendered as comparison bars for changed tasks. */
    private static final List<List<String>> METRICS = [
            ['realtime',  'Realtime',   'time'],
            ['peak_rss',  'Peak RSS',   'mem'],
            ['%cpu',      'CPU',        'perc'],
            ['rchar',     'Read',       'mem'],
            ['wchar',     'Write',      'mem'],
    ]

    String render(DiffResult diff) {
        final sb = new StringBuilder(64 * 1024)
        sb << '<!DOCTYPE html>\n<html lang="en">\n<head>\n'
        sb << '<meta charset="utf-8">\n'
        sb << '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        sb << "<title>nf-diff · ${esc(diff.runA.label())} vs ${esc(diff.runB.label())}</title>\n"
        // Set the theme before first paint to avoid a flash of the wrong palette.
        sb << '<script>(function(){try{var k="nf-diff-theme",s=localStorage.getItem(k),' +
                't=s||((window.matchMedia&&matchMedia("(prefers-color-scheme: light)").matches)?"light":"dark");' +
                'document.documentElement.setAttribute("data-theme",t);}catch(e){}})();</script>\n'
        sb << '<style>\n' << css() << '\n</style>\n'
        sb << '</head>\n<body>\n'

        renderHeader(sb, diff)
        sb << '<div class="layout">\n'
        renderNav(sb, diff)
        sb << '<main class="wrap">\n'
        renderSummary(sb, diff)
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
        renderPager(sb)
        sb << '</main>\n'
        sb << '</div>\n'
        renderFooter(sb, diff)

        sb << '<script>\n' << js() << '\n</script>\n'
        sb << '</body>\n</html>\n'
        return sb.toString()
    }

    // ---------------------------------------------------------------- header

    private void renderHeader(StringBuilder sb, DiffResult diff) {
        sb << '<header class="hero">\n'
        sb << '  <button class="theme-toggle" id="theme-toggle" type="button" title="Toggle light/dark theme" aria-label="Toggle light/dark theme">'
        sb << '<span class="ti-dark">🌙</span><span class="ti-light">☀️</span></button>\n'
        sb << '  <div class="hero-inner">\n'
        sb << '    <div class="brand"><span class="logo">±</span><span>nf-diff</span></div>\n'
        // Heading and verdict badge share one row (badge to the right of the
        // title) so the verdict reads immediately without a separate band, and
        // no extra vertical space is spent on it.
        final sameText = diff.showObvious
                ? 'These runs are identical across every inspected layer'
                : 'These runs are identical (ignoring always-changing fields)'
        final verdict = diff.identical
                ? "<span class=\"verdict same\">${sameText}</span>"
                : "<span class=\"verdict diff\">These runs differ</span>"
        sb << '    <div class="hero-title"><h1>Run comparison</h1>' << verdict << '</div>\n'
        sb << '    <div class="runs">\n'
        sb << runChip(diff.runA, 'a')
        sb << '      <div class="vs">vs</div>\n'
        sb << runChip(diff.runB, 'b')
        sb << '    </div>\n'
        sb << '  </div>\n'
        sb << '</header>\n'
    }

    private String runChip(RunSnapshot run, String side) {
        final status = (run.status ?: 'UNKNOWN').toUpperCase()
        final statusClass = isSucceeded(status) ? 'ok' : (isFailed(status) ? 'err' : 'unknown')
        // Nextflow version is only known when the run has a .lineage/ store;
        // omit the row rather than show a placeholder when it wasn't recorded.
        final details = new StringBuilder()
        if( run.pipeline ) {
            // Name on the first line; when the lineage store recorded the main
            // script's absolute path, show it dimmed beneath (and as a hover
            // tooltip) so the reader can see exactly which file ran.
            final pathLine = run.pipelinePath
                    ? "<div class=\"run-fact-path\">${esc(run.pipelinePath)}</div>"
                    : ''
            final title = run.pipelinePath ? " title=\"${esc(run.pipelinePath)}\"" : ''
            details << "          <div class=\"run-fact\"><dt>Pipeline</dt><dd class=\"mono\"${title}>${esc(run.pipeline)}${pathLine}</dd></div>\n"
        }
        details << "          <div class=\"run-fact\"><dt>Started</dt><dd>${esc(Format.datetime(run.timestamp))}</dd></div>\n"
        if( run.nextflowVersion )
            details << "          <div class=\"run-fact\"><dt>Nextflow</dt><dd class=\"mono\">${esc(run.nextflowVersion)}</dd></div>\n"
        return """\
      <div class="run-chip run-${side}">
        <div class="run-name">${esc(run.runName ?: run.requestedId)}</div>
        <div class="run-meta">
          <span class="pill status-${statusClass}">${esc(statusLabel(run.status))}</span>
          <span class="mono">${esc(run.sessionId ? run.sessionId.toString().substring(0, 8) : '????????')}</span>
        </div>
        <div class="run-sub">${esc(run.tasks.size().toString())} tasks · ${esc(Format.duration(run.durationMillis))}</div>
        <dl class="run-facts">
${details}        </dl>
      </div>
"""
    }

    private void renderNav(StringBuilder sb, DiffResult diff) {
        // Whether each section carries a meaningful change, using the same
        // predicate that section's own body uses to decide "changed". Sections
        // that are purely informational (Summary, Efficiency) never alert; the
        // metadata/params/config layers respect the verbose (showObvious) view
        // so the nav alerts match what the reader actually sees flagged.
        final ob = diff.showObvious
        sb << '<nav class="sidenav" id="nav" aria-label="Report sections">\n'
        sb << '  <div class="sidenav-title">Sections</div>\n'
        navLink(sb, '#summary', 'Summary', false, true)
        if( diff.hasFailures() )
            navLink(sb, '#failures', 'Failures', diff.newFailureCount() > 0 || diff.resolvedFailureCount() > 0, false)
        navLink(sb, '#metadata', 'Metadata', diff.metadata.any { fd -> fd.isHighlighted(ob) }, false)
        navLink(sb, '#params', 'Parameters', diff.params.any { fd -> fd.isHighlighted(ob) }, false)
        navLink(sb, '#config', 'Configuration', diff.config.any { fd -> fd.isHighlighted(ob) }, false)
        navLink(sb, '#processes', 'Processes', diff.processes.any { p -> !p.unchanged }, false)
        navLink(sb, '#software', 'Software', diff.hasSoftwareChanges(), false)
        navLink(sb, '#regressions', 'Regressions', diff.regressionCount() > 0, false)
        if( diff.hasEfficiency() )
            navLink(sb, '#efficiency', 'Efficiency', false, false)
        navLink(sb, '#tasks', 'Tasks', (diff.tasksChanged + diff.tasksAdded + diff.tasksRemoved) > 0, false)
        if( diff.diffOutputs )
            navLink(sb, '#outputs', 'Outputs', diff.hasOutputChanges(), false)
        if( diff.diffLogs )
            navLink(sb, '#logs', 'Logs', diff.hasLogChanges(), false)
        if( diff.diffDag )
            navLink(sb, '#dag', 'Wiring', diff.hasDagChanges(), false)
        sb << '</nav>\n'
    }

    /**
     * A single sidebar nav link. When {@code changed} is set the entry gets a
     * trailing warning icon so the reader can see at a glance which sections
     * hold differences without opening each page.
     */
    private void navLink(StringBuilder sb, String href, String label, boolean changed, boolean active) {
        final cls = active ? ' class="active"' : ''
        sb << "  <a href=\"${href}\"${cls}><span class=\"nav-label\">${label}</span>"
        if( changed )
            sb << '<span class="nav-alert" title="Changes detected" aria-label="Changes detected">\u26A0\uFE0E</span>'
        sb << '</a>\n'
    }

    /**
     * Bottom-of-page pager for the paginated layout: one section is shown at a
     * time (see the page JS), and these controls step through the visible nav
     * entries in order. Labels are filled in by JS from the adjacent sections.
     */
    private void renderPager(StringBuilder sb) {
        sb << '<div class="pager" id="pager">\n'
        sb << '  <button type="button" class="pager-btn" id="pager-prev" disabled>&#8249; <span class="pager-lbl">Previous</span></button>\n'
        sb << '  <button type="button" class="pager-btn" id="pager-next"><span class="pager-lbl">Next</span> &#8250;</button>\n'
        sb << '</div>\n'
    }

    // --------------------------------------------------------------- summary

    private void renderSummary(StringBuilder sb, DiffResult diff) {
        sb << '<section id="summary" class="section is-active">\n'
        sb << '  <h2>Summary</h2>\n'

        // Headline: the single "should I care?" number — total task-level
        // differences — mirroring the verdict shown in the hero.
        final totalDiff = diff.tasksChanged + diff.tasksAdded + diff.tasksRemoved
        final hlKind = diff.identical ? 'same' : 'diff'
        final hlNote = diff.identical
                ? 'No task-level differences detected'
                : 'across added, removed and changed tasks'
        sb << "  <div class=\"summary-headline ${hlKind}\">\n"
        sb << "    <span class=\"hl-num\">${totalDiff}</span>\n"
        sb << '    <span class="hl-text"><span class="hl-label-lg">task-level difference(s)</span>'
        sb << "<br><span class=\"hl-label\">${esc(hlNote)}</span></span>\n"
        sb << '  </div>\n'

        // Task disposition: the four mutually-exclusive buckets (changed / only
        // in A / only in B / unchanged) as one stacked proportion bar instead of
        // four equal-weight boxes. `Recomputed` is a cross-cut of `changed`, so
        // it rides along as an annotation rather than a segment.
        sb << dispositionBar(diff)

        // Grouped card bands — only the diff-layer counts that don't fit the
        // disposition bar, split into "Failures" and "Changes by layer".
        if( diff.hasFailures() ) {
            def fb = new StringBuilder()
            fb << statCard('Failed (A)', diff.failedCountA(), 'removed')
            fb << statCard('Failed (B)', diff.failedCountB(), 'removed')
            if( diff.newFailureCount() > 0 )
                fb << statCard('New failures', diff.newFailureCount(), 'removed')
            sb << statGroup('Failures', fb.toString())
        }

        def cb = new StringBuilder()
        cb << statCard('Software changed', diff.softwareChangedCount(), 'changed')
        cb << statCard('Regressions', diff.regressionCount(), 'removed')
        if( diff.hasEfficiency() )
            cb << statCard('Over-provisioned (B)', diff.overProvisionedB(), 'removed')
        if( diff.diffOutputs )
            cb << statCard('Outputs changed', diff.outputsChangedCount(), 'changed')
        if( diff.diffLogs )
            cb << statCard('Logs changed', diff.logsChangedCount(), 'changed')
        if( diff.diffDag )
            cb << statCard('Wiring edges changed', diff.dagEdgesAdded() + diff.dagEdgesRemoved(), 'changed')
        sb << statGroup('Changes by layer', cb.toString())

        // wall-time comparison bar
        final a = diff.runA.durationMillis
        final b = diff.runB.durationMillis
        if( a != null && b != null ) {
            sb << '  <div class="card wide">\n'
            sb << '    <div class="card-title">Wall-clock duration</div>\n'
            sb << metricBars('duration', a.doubleValue(), b.doubleValue(),
                    Format.duration(a), Format.duration(b))
            final pct = Format.signedPct(Format.pctDelta(a, b))
            sb << "    <div class=\"delta\">B vs A: <strong>${esc(pct)}</strong></div>\n"
            sb << '  </div>\n'
        }
        sb << '</section>\n'
    }

    private String statCard(String label, int value, String kind) {
        return """\
    <div class="card stat ${kind}">
      <div class="stat-num">${value}</div>
      <div class="stat-label">${esc(label)}</div>
    </div>
"""
    }

    /** Wrap a run of stat cards under a labelled band. */
    private String statGroup(String title, String cardsHtml) {
        return """\
  <div class="card-group">
    <h3>${esc(title)}</h3>
    <div class="cards">
${cardsHtml}    </div>
  </div>
"""
    }

    /**
     * A single stacked proportion bar for the four mutually-exclusive task
     * buckets, replacing four equal-weight boxes. Segment widths are shares of
     * the matched+unmatched total; a legend carries the exact counts.
     */
    private String dispositionBar(DiffResult diff) {
        final changed = diff.tasksChanged
        final removed = diff.tasksRemoved
        final added = diff.tasksAdded
        final unchanged = diff.tasksUnchanged
        final total = changed + removed + added + unchanged
        final List<List> segs = [
                ['changed',   'Changed',   changed],
                ['removed',   'Only in A', removed],
                ['added',     'Only in B', added],
                ['unchanged', 'Unchanged', unchanged],
        ]
        final bar = new StringBuilder()
        segs.each { List seg ->
            final n = (seg[2] as Integer)
            if( n <= 0 )
                return
            final w = total > 0 ? (n / (total as double)) * 100.0d : 0.0d
            bar << "<div class=\"disp-seg ${seg[0]}\" style=\"width:${fmt(w)}%\" title=\"${esc(seg[1] as String)}: ${n}\"></div>"
        }
        final legend = new StringBuilder()
        segs.each { List seg ->
            legend << "<span class=\"lg\"><span class=\"sw ${seg[0]}\"></span>${esc(seg[1] as String)} <strong>${seg[2]}</strong></span>"
        }
        final recNote = diff.tasksRecomputed > 0
                ? "    <div class=\"disp-note\">Of the changed tasks, <strong>${diff.tasksRecomputed}</strong> would have been recomputed rather than resumed.</div>\n"
                : ''
        return """\
  <div class="disp">
    <div class="card-title">Task disposition · ${total} total</div>
    <div class="disp-bar">${bar}</div>
    <div class="disp-legend">${legend}</div>
${recNote}  </div>
"""
    }

    // -------------------------------------------------------------- failures

    private void renderFailures(StringBuilder sb, DiffResult diff) {
        if( !diff.hasFailures() )
            return
        sb << '<section id="failures" class="section">\n'
        sb << '  <h2>Failure rollup</h2>\n'
        sb << '  <p class="mode-note">What failed and why, rolled up by process, status and exit code, read from the run cache (no work directories needed). '
        sb << '<span class="pill removed">new</span> = a failure signature seen only in Run B; '
        sb << '<span class="pill added">resolved</span> = one that was in Run A but is gone in Run B. '
        sb << 'This is a summary of the per-task status/exit already shown in Tasks, so it is informational and does not by itself affect the identical verdict.</p>\n'

        // run-level status callouts
        if( DiffResult.runFailed(diff.runA) )
            sb << "  <p class=\"warn-note\"><strong>⚠ Run A</strong> finished in status <span class=\"mono\">${esc(statusLabel(diff.runA.status))}</span>.</p>\n"
        if( DiffResult.runFailed(diff.runB) )
            sb << "  <p class=\"warn-note\"><strong>⚠ Run B</strong> finished in status <span class=\"mono\">${esc(statusLabel(diff.runB.status))}</span>.</p>\n"

        if( diff.failureGroups.isEmpty() ) {
            sb << '  <p class="mode-note">No task-level failures were recorded in either run cache.</p>\n'
            sb << '</section>\n'
            return
        }
        sb << '  <table class="proc">\n'
        sb << '    <thead><tr><th>Process</th><th>Status</th><th>Exit</th><th>Run A</th><th>Run B</th><th></th></tr></thead>\n  <tbody>\n'
        diff.failureGroups.each { DiffResult.FailureGroup g ->
            final state = g.isNew() ? 'new' : (g.isResolved() ? 'resolved' : 'persistent')
            final kind = g.isNew() ? 'removed' : (g.isResolved() ? 'added' : 'changed')
            sb << "    <tr class=\"row-${kind}\"><th class=\"mono\">${esc(g.process)}</th>"
            sb << "<td>${esc(g.status)}</td>"
            sb << "<td class=\"mono\">${esc(g.exit)}</td>"
            sb << "<td>${g.countA}</td><td>${g.countB}</td>"
            sb << "<td><span class=\"pill ${kind}\">${state}</span></td></tr>\n"
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    // -------------------------------------------------------------- metadata

    private void renderMetadata(StringBuilder sb, DiffResult diff) {
        sb << '<section id="metadata" class="section">\n'
        sb << '  <h2>Run metadata</h2>\n'
        sb << '  <table class="kv">\n'
        sb << '    <thead><tr><th>Field</th><th>Run A</th><th>Run B</th></tr></thead>\n  <tbody>\n'
        diff.metadata.each { FieldDiff fd ->
            sb << fieldRow(fd, diff.showObvious)
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '  <p class="mode-note">The <em>Nextflow version</em>, <em>build</em> and runtime environment (<em>container engine</em>, <em>Wave</em>, <em>Fusion</em>) rows are read from each run\'s data-lineage store (<code>lineage.enabled=true</code>, Nextflow 25.04+) and appear only when a run recorded one. Per-run <em>plugin</em> versions are not shown: Nextflow does not persist them in the lineage store, the history file, or the task cache, so they cannot be compared.</p>\n'
        sb << '</section>\n'
    }

    // ----------------------------------------------------------------- params

    private void renderParams(StringBuilder sb, DiffResult diff) {
        sb << '<section id="params" class="section">\n'
        sb << '  <h2>Parameters &amp; options</h2>\n'
        if( diff.params.isEmpty() ) {
            sb << '  <p class="mode-note">No command-line parameters were recorded for these runs.</p>\n'
            sb << '</section>\n'
            return
        }
        sb << '  <p class="mode-note">Pipeline params (<code>--foo</code>) and Nextflow options (<code>-profile</code>, <code>-r</code>) from each run\'s launch command, merged with any <code>-params-file</code> contents. The <em>Source</em> column shows whether a value came from the command line, a params-file, or both (the command line wins on conflict). Params that were <em>not</em> passed at launch &mdash; e.g. defaults, or values set inside <code>nextflow.config</code> or an activated profile &mdash; are resolved config, not launch input, so they appear in the <a href="#config">Configuration</a> layer rather than here.</p>\n'
        sb << '  <table class="kv">\n'
        sb << '    <thead><tr><th>Flag</th><th>Run A</th><th>Run B</th><th>Source</th></tr></thead>\n  <tbody>\n'
        diff.params.each { FieldDiff fd ->
            sb << paramRow(fd, diff.showObvious)
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    /** A parameters-table row: the standard field row plus a provenance cell. */
    private static String paramRow(FieldDiff fd, boolean showObvious) {
        final highlighted = fd.isHighlighted(showObvious)
        final softChange = fd.changed && fd.obvious && !showObvious
        final cls = highlighted ? ' class="row-changed"' : (softChange ? ' class="row-obvious"' : '')
        final tag = softChange ? ' <span class="tag-auto" title="Always differs between runs">auto</span>' : ''
        return "        <tr${cls}><th>${esc(fd.field)}${tag}</th>" +
                "<td>${esc(Format.orNa(fd.valueA))}</td>" +
                "<td>${esc(Format.orNa(fd.valueB))}</td>" +
                "<td>${sourceCell(fd)}</td></tr>\n"
    }

    /** Render provenance as small source badges (A/B collapsed when equal). */
    private static String sourceCell(FieldDiff fd) {
        if( fd.sourceA == fd.sourceB )
            return fd.sourceA ? sourceBadge(fd.sourceA) : '<span class="src-na">—</span>'
        final a = fd.sourceA ? "A:${sourceBadge(fd.sourceA)}" : ''
        final b = fd.sourceB ? "B:${sourceBadge(fd.sourceB)}" : ''
        return "${a} ${b}".trim()
    }

    private static String sourceBadge(String source) {
        final cls = source == CommandParams.SRC_FILE ? 'src-file'
                : (source == CommandParams.SRC_BOTH ? 'src-both' : 'src-cli')
        return "<span class=\"src ${cls}\">${esc(source)}</span>"
    }

    // --------------------------------------------------------------- config

    private void renderConfig(StringBuilder sb, DiffResult diff) {
        sb << '<section id="config" class="section">\n'
        sb << '  <h2>Resolved configuration</h2>\n'
        if( diff.configNote )
            sb << "  <p class=\"mode-note\">${esc(diff.configNote)}</p>\n"
        final provWarning = diff.configProvenance?.warning()
        if( provWarning )
            sb << "  <p class=\"warn-note\"><strong>⚠ Config provenance:</strong> ${esc(provWarning)}</p>\n"
        if( diff.config.isEmpty() ) {
            sb << '</section>\n'
            return
        }
        sb << '  <table class="kv">\n'
        sb << '    <thead><tr><th>Key</th><th>Run A</th><th>Run B</th></tr></thead>\n  <tbody>\n'
        diff.config.each { FieldDiff fd ->
            sb << fieldRow(fd, diff.showObvious)
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    // ------------------------------------------------------------- processes

    private void renderProcesses(StringBuilder sb, DiffResult diff) {
        sb << '<section id="processes" class="section">\n'
        sb << '  <h2>Processes</h2>\n'
        sb << '  <table class="proc">\n'
        sb << '    <thead><tr><th>Process</th><th>Run A</th><th>Run B</th><th>Δ</th><th></th></tr></thead>\n  <tbody>\n'
        diff.processes.each { ProcessDiff pd ->
            final kind = pd.added ? 'added' : (pd.removed ? 'removed' : (pd.changed ? 'changed' : 'unchanged'))
            final delta = pd.countB - pd.countA
            final deltaStr = (delta > 0 ? "+${delta}" : delta.toString()).toString()
            sb << "    <tr class=\"row-${kind}\"><th class=\"mono\">${esc(pd.process)}</th>"
            sb << "<td>${pd.countA}</td><td>${pd.countB}</td><td>${esc(deltaStr)}</td>"
            sb << "<td><span class=\"pill ${kind}\">${kind}</span></td></tr>\n"
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    // -------------------------------------------------------------- software

    private void renderSoftware(StringBuilder sb, DiffResult diff) {
        sb << '<section id="software" class="section">\n'
        sb << '  <h2>Software &amp; versions</h2>\n'
        sb << '  <p class="mode-note">The container image and Conda package spec each process ran with, read from the run cache. A change here means the tools (and their versions) differed between the two runs.</p>\n'
        if( diff.software.isEmpty() ) {
            sb << '  <p class="mode-note">No software environment was recorded for either run.</p>\n'
            sb << '</section>\n'
            return
        }
        sb << '  <table class="proc">\n'
        sb << '    <thead><tr><th>Process</th><th>Container</th><th>Conda</th><th></th></tr></thead>\n  <tbody>\n'
        diff.software.each { DiffResult.SoftwareDiff sd ->
            final kind = sd.kind.name().toLowerCase()
            sb << "    <tr class=\"row-${kind}\"><th class=\"mono\">${esc(sd.process)}</th>"
            sb << "<td>${softwareCell(sd.containersA, sd.containersB, sd.containerChanged)}</td>"
            sb << "<td>${softwareCell(sd.condaA, sd.condaB, sd.condaChanged)}</td>"
            sb << "<td><span class=\"pill ${kind}\">${kind}</span></td></tr>\n"
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    /** Software cell: a single mono value when unchanged, or {@code A &rarr; B} when it changed. */
    private static String softwareCell(List<String> a, List<String> b, boolean changed) {
        final left = a.isEmpty() ? '—' : a.join(', ')
        if( !changed )
            return "<span class=\"mono\">${esc(left)}</span>".toString()
        final right = b.isEmpty() ? '—' : b.join(', ')
        return "<span class=\"mono\">${esc(left)}</span> &rarr; <span class=\"mono\">${esc(right)}</span>".toString()
    }

    // ------------------------------------------------------------ regressions

    private void renderRegressions(StringBuilder sb, DiffResult diff) {
        sb << '<section id="regressions" class="section">\n'
        sb << '  <h2>Performance regressions</h2>\n'
        final thr = trim(diff.perfThreshold)
        if( diff.regressions.isEmpty() ) {
            sb << "  <p class=\"mode-note\">No task metric (realtime, peak RSS, peak VMEM) changed by &ge; ${esc(thr)}% between the runs.</p>\n"
            sb << '</section>\n'
            return
        }
        sb << "  <p class=\"mode-note\">Task metrics that changed by &ge; ${esc(thr)}% (positive = Run B slower/heavier), worst first. <em>Same work</em> marks tasks whose cache hash is identical, so the cost change is environmental rather than a different computation.</p>\n"
        // Lead with a diverging-bar plot of the magnitudes; the table below keeps
        // the exact A/B values and is the large-list fallback.
        sb << regressionPlot(diff.regressions)
        sb << '  <table class="proc">\n'
        sb << '    <thead><tr><th>Task</th><th>Metric</th><th>Run A</th><th>Run B</th><th>Δ</th><th>Same work</th></tr></thead>\n  <tbody>\n'
        diff.regressions.each { RegressionDiff r ->
            final cls = r.regression ? 'row-removed' : 'row-added'
            sb << "    <tr class=\"${cls}\"><th class=\"mono\">${esc(r.taskKey)}</th>"
            sb << "<td>${esc(r.label)}</td>"
            sb << "<td>${esc(Format.orNa(r.displayA))}</td>"
            sb << "<td>${esc(Format.orNa(r.displayB))}</td>"
            sb << "<td class=\"mono\">${esc(Format.signedPct(r.pctDelta))}</td>"
            sb << "<td>${r.sameHash ? '✓' : ''}</td></tr>\n"
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    /** Trim a whole-number threshold to an integer string (25.0 -> "25"). */
    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value)
    }

    // Geometry for the regression plot (user-space SVG units).
    private static final int RP_PAD     = 16
    private static final int RP_LABEL_W = 210
    private static final int RP_GAP     = 12
    private static final int RP_HALF    = 220   // max bar length on each side
    private static final int RP_VAL_W   = 60    // room for the % label past a bar tip
    private static final int RP_ROW_H   = 28
    private static final int RP_BAR_H   = 16
    private static final int RP_TOP     = 26    // header band ("faster <- 0 -> slower")

    /**
     * Render the flagged regressions as a self-contained inline-SVG diverging
     * bar chart: one row per metric (worst first, as the comparator ordered
     * them), bars growing right for regressions (Run B slower/heavier, red) and
     * left for improvements (green), scaled to the largest absolute delta. Like
     * the DAG diagram it needs no JS or external assets.
     */
    private String regressionPlot(List<RegressionDiff> regressions) {
        final rows = regressions.findAll { RegressionDiff r -> r.pctDelta != null }
        if( rows.isEmpty() )
            return ''
        double maxAbs = 1.0d
        rows.each { RegressionDiff r -> maxAbs = Math.max(maxAbs, Math.abs(r.pctDelta)) }

        final halfSpan = RP_HALF + RP_VAL_W
        final axisX = RP_PAD + RP_LABEL_W + RP_GAP + halfSpan
        final w = axisX + halfSpan + RP_PAD
        final h = RP_TOP + RP_PAD + rows.size() * RP_ROW_H + RP_PAD
        final axisTop = RP_TOP
        final axisBot = RP_TOP + RP_PAD + rows.size() * RP_ROW_H - ((RP_ROW_H - RP_BAR_H) / 2 as int)

        final svg = new StringBuilder()
        svg << "  <div class=\"rp-plot\">\n"
        svg << "    <svg class=\"rp-svg\" viewBox=\"0 0 ${w} ${h}\" width=\"${w}\" height=\"${h}\" role=\"img\" aria-label=\"Regression magnitude chart\">\n"
        // Header band and zero axis.
        svg << "      <text class=\"rp-axhdr\" x=\"${axisX - 6}\" y=\"16\" text-anchor=\"end\">&#8592; faster / lighter</text>\n"
        svg << "      <text class=\"rp-axhdr\" x=\"${axisX + 6}\" y=\"16\" text-anchor=\"start\">slower / heavier &#8594;</text>\n"
        svg << "      <line class=\"rp-axis\" x1=\"${axisX}\" y1=\"${axisTop}\" x2=\"${axisX}\" y2=\"${axisBot}\"/>\n"

        rows.eachWithIndex { RegressionDiff r, Integer i ->
            final rowY = RP_TOP + RP_PAD + i * RP_ROW_H
            final midY = rowY + (RP_BAR_H / 2 as int) + 4
            final worse = r.pctDelta > 0
            final barLen = Math.max(1, (int)(Math.abs(r.pctDelta) / maxAbs * RP_HALF))
            final cls = worse ? 'worse' : 'better'
            // Right-aligned task · metric label in the left gutter.
            final rawLabel = "${r.taskKey} · ${r.label}".toString()
            final label = rawLabel.length() > 30 ? rawLabel.substring(0, 29) + '\u2026' : rawLabel
            svg << "      <text class=\"rp-lbl\" x=\"${RP_PAD + RP_LABEL_W}\" y=\"${midY}\" text-anchor=\"end\">${esc(label)}</text>\n"
            final title = "${r.taskKey} — ${r.label}: ${Format.orNa(r.displayA)} \u2192 ${Format.orNa(r.displayB)} (${Format.signedPct(r.pctDelta)})${r.sameHash ? ', same work' : ''}".toString()
            final barX = worse ? axisX : axisX - barLen
            final valX = worse ? barX + barLen + 6 : barX - 6
            final valAnchor = worse ? 'start' : 'end'
            svg << "      <g class=\"rp-row\"><title>${esc(title)}</title>"
            svg << "<rect class=\"rp-bar ${cls}${r.sameHash ? ' samework' : ''}\" x=\"${barX}\" y=\"${rowY}\" width=\"${barLen}\" height=\"${RP_BAR_H}\" rx=\"3\"/>"
            svg << "<text class=\"rp-val\" x=\"${valX}\" y=\"${midY}\" text-anchor=\"${valAnchor}\">${esc(Format.signedPct(r.pctDelta))}</text></g>\n"
        }
        svg << '    </svg>\n'
        svg << '    <div class="rp-legend">\n'
        svg << '      <span class="lg"><span class="sw worse"></span>Regression (Run B worse)</span>\n'
        svg << '      <span class="lg"><span class="sw better"></span>Improvement (Run B better)</span>\n'
        svg << '      <span class="lg"><span class="sw samework"></span>Same work (identical cache hash)</span>\n'
        svg << '    </div>\n'
        svg << '  </div>\n'
        return svg.toString()
    }

    // ------------------------------------------------------------ efficiency

    private void renderEfficiency(StringBuilder sb, DiffResult diff) {
        sb << '<section id="efficiency" class="section">\n'
        sb << '  <h2>Resource efficiency</h2>\n'
        sb << '  <p class="mode-note">Peak measured CPU / memory versus what each process <em>requested</em>, read from the run cache. '
        sb << '<span class="pill removed">over-provisioned</span> = used under 50% of the reservation (wasted allocation); '
        sb << '<span class="pill added">tight</span> = used 90%+ of it (risk of OOM kills or CPU throttling). '
        sb << 'Informational only — this never affects the identical verdict.</p>\n'
        if( !diff.hasEfficiency() ) {
            sb << '  <p class="mode-note">No CPU/memory usage metrics were recorded in either run cache.</p>\n'
            sb << '</section>\n'
            return
        }
        sb << '  <table class="proc">\n'
        sb << '    <thead><tr><th>Process</th><th>CPU eff. A</th><th>CPU eff. B</th><th>CPU</th>'
        sb << '<th>Mem eff. A</th><th>Mem eff. B</th><th>Mem</th></tr></thead>\n  <tbody>\n'
        diff.efficiency.each { DiffResult.ProcessEfficiency e ->
            sb << "    <tr><th class=\"mono\">${esc(e.process)}</th>"
            sb << "<td class=\"mono\">${esc(Format.pct(e.cpuEffA()))}</td>"
            sb << "<td class=\"mono\">${esc(Format.pct(e.cpuEffB()))}</td>"
            sb << "<td>${effPill(e.cpuClassB() ?: e.cpuClassA())}</td>"
            sb << "<td class=\"mono\">${esc(Format.pct(e.memEffA()))}</td>"
            sb << "<td class=\"mono\">${esc(Format.pct(e.memEffB()))}</td>"
            sb << "<td>${effPill(e.memClassB() ?: e.memClassA())}</td></tr>\n"
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    /** Efficiency class pill: over-provisioned (removed/red), tight (added/green), right-sized (unchanged). */
    private static String effPill(String cls) {
        if( cls == null )
            return '<span class="mono">—</span>'
        final kind = cls == 'over' ? 'removed' : (cls == 'tight' ? 'added' : 'unchanged')
        final label = cls == 'over' ? 'over-provisioned' : (cls == 'tight' ? 'tight' : 'right-sized')
        return "<span class=\"pill ${kind}\">${esc(label)}</span>".toString()
    }

    // ------------------------------------------------------------- outputs

    private void renderOutputs(StringBuilder sb, DiffResult diff) {
        if( !diff.diffOutputs )
            return
        sb << '<section id="outputs" class="section">\n'
        sb << '  <h2>Output files</h2>\n'
        if( diff.outputsNote )
            sb << "  <p class=\"mode-note\">${esc(diff.outputsNote)}</p>\n"

        final changed = diff.outputs.findAll { DiffResult.OutputDiff od -> od.hasChanges() }
        if( changed.isEmpty() ) {
            sb << '  <p class="mode-note">No output-file differences were detected across the compared tasks.</p>\n'
            sb << '</section>\n'
            return
        }
        changed.each { DiffResult.OutputDiff od ->
            sb << '  <div class="task changed">\n'
            sb << '    <div class="task-hdr" onclick="toggleTask(this)">\n'
            sb << '      <span class="pill changed">outputs</span>\n'
            sb << "      <span class=\"task-key mono\">${esc(od.taskKey)}</span>\n"
            sb << "      <span class=\"task-proc\">${esc(od.process ?: '')}</span>\n"
            sb << '      <span class="chev">▸</span>\n'
            sb << '    </div>\n'
            sb << '    <div class="task-body">\n'
            sb << '      <table class="kv diff-table"><thead><tr><th>File</th><th>Run A</th><th>Run B</th></tr></thead><tbody>\n'
            od.files.findAll { it.kind != Kind.UNCHANGED }.each { DiffResult.OutputFileDiff f ->
                final label = f.kind.name().toLowerCase()
                sb << "        <tr class=\"row-changed\"><th class=\"mono\">${esc(f.path)} <span class=\"pill ${label}\">${label}</span></th>"
                sb << "<td>${esc(sizeCell(f.sizeA, f.hashA))}</td>"
                sb << "<td>${esc(sizeCell(f.sizeB, f.hashB))}</td></tr>\n"
            }
            sb << '      </tbody></table>\n'
            // Line-level diff of each changed text file, below the size/hash summary.
            od.files.findAll { it.kind == Kind.CHANGED && it.hasLineDiff() }.each { DiffResult.OutputFileDiff f ->
                sb << "      <div class=\"log-file\"><span class=\"mono\">${esc(f.path)}</span> "
                sb << "<span class=\"pill changed\">+${f.linesAdded()} −${f.linesRemoved()}</span>"
                if( f.truncated )
                    sb << ' <span class="tag-auto" title="Diff capped at the line limit">truncated</span>'
                sb << "</div>\n"
                sb << logDiffPre(f.ops)
            }
            sb << '    </div>\n  </div>\n'
        }
        sb << '</section>\n'
    }

    /** Compact "size · hash" cell for an output file, or an em dash when absent. */
    private static String sizeCell(Long size, String hash) {
        if( size == null )
            return Format.NA
        return hash ? "${size} B · ${hash}".toString() : "${size} B".toString()
    }

    // --------------------------------------------------------------- logs

    private void renderLogs(StringBuilder sb, DiffResult diff) {
        if( !diff.diffLogs )
            return
        sb << '<section id="logs" class="section">\n'
        sb << '  <h2>Task logs</h2>\n'
        if( diff.logsNote )
            sb << "  <p class=\"mode-note\">${esc(diff.logsNote)}</p>\n"

        final changed = diff.logs.findAll { DiffResult.LogDiff ld -> ld.hasChanges() }
        if( changed.isEmpty() ) {
            sb << '  <p class="mode-note">No task log differences were detected across the compared tasks.</p>\n'
            sb << '</section>\n'
            return
        }
        changed.each { DiffResult.LogDiff ld ->
            final cls = ld.failure ? 'removed' : 'changed'
            sb << "  <div class=\"task ${cls}\">\n"
            sb << '    <div class="task-hdr" onclick="toggleTask(this)">\n'
            sb << "      <span class=\"pill ${cls}\">${ld.failure ? 'failure' : 'logs'}</span>\n"
            sb << "      <span class=\"task-key mono\">${esc(ld.taskKey)}</span>\n"
            sb << "      <span class=\"task-proc\">${esc(ld.process ?: '')}</span>\n"
            sb << '      <span class="chev">▸</span>\n'
            sb << '    </div>\n'
            sb << '    <div class="task-body">\n'
            if( ld.exitChanged || ld.statusChanged ) {
                sb << '      <p class="solo-note">'
                sb << "exit ${esc(ld.exitA ?: Format.NA)} &rarr; ${esc(ld.exitB ?: Format.NA)}"
                sb << " · status ${esc(ld.statusA ?: Format.NA)} &rarr; ${esc(ld.statusB ?: Format.NA)}</p>\n"
            }
            ld.changedLogs().each { DiffResult.LogFileDiff f ->
                final label = f.kind.name().toLowerCase()
                sb << "      <div class=\"log-file\"><span class=\"mono\">${esc(f.name)}</span> "
                sb << "<span class=\"pill ${label}\">${label}</span>"
                if( f.truncated )
                    sb << ' <span class="tag-auto" title="Tailed to the line cap">tailed</span>'
                sb << "</div>\n"
                sb << logDiffPre(f.ops)
            }
            sb << '    </div>\n  </div>\n'
        }
        sb << '</section>\n'
    }

    /** Render log-diff ops as a single unified-diff pane reusing the code-line styles. */
    private String logDiffPre(List<LineDiff.Op> ops) {
        final body = new StringBuilder()
        ops.each { LineDiff.Op op ->
            final cls = op.type == LineDiff.Type.INSERT ? 'ins'
                    : (op.type == LineDiff.Type.DELETE ? 'del' : 'eq')
            body << codeLine(op.text, cls)
        }
        return "      <div class=\"code-diff\"><div class=\"code-col wide\"><pre>${body}</pre></div></div>\n"
    }

    // ----------------------------------------------------------------- dag

    private void renderDag(StringBuilder sb, DiffResult diff) {
        if( !diff.diffDag )
            return
        sb << '<section id="dag" class="section">\n'
        sb << '  <h2>Process wiring (DAG)</h2>\n'
        if( diff.dagNote )
            sb << "  <p class=\"mode-note\">${esc(diff.dagNote)}</p>\n"

        final changed = diff.dag.findAll { DiffResult.DagEdgeDiff d -> d.isAdded() || d.isRemoved() }
        if( changed.isEmpty() ) {
            sb << '  <p class="mode-note">No process&rarr;process wiring differences were detected.</p>\n'
            sb << '</section>\n'
            return
        }
        // Three views of the same wiring: the union DAG with added/removed edges
        // highlighted in place (Changes), plus each run's own graph so the reader
        // can see the topology before (Run A) and after (Run B). The table below
        // carries the precise, per-edge detail (and is the large-graph fallback).
        final edgesA = runEdges(diff.dag, 'a')
        final edgesB = runEdges(diff.dag, 'b')
        sb << '  <div class="dag-tabset">\n'
        sb << '    <div class="dag-tabs" role="tablist">\n'
        sb << '      <button type="button" class="dag-tab active" data-dag="union" role="tab" aria-selected="true">Changes</button>\n'
        sb << '      <button type="button" class="dag-tab" data-dag="a" role="tab" aria-selected="false">Run A (before)</button>\n'
        sb << '      <button type="button" class="dag-tab" data-dag="b" role="tab" aria-selected="false">Run B (after)</button>\n'
        sb << '    </div>\n'
        sb << '    <div class="dag-panel is-active" data-dag="union">\n'
        sb << dagSvg(diff.dag)
        sb << '    </div>\n'
        sb << '    <div class="dag-panel" data-dag="a">\n'
        sb << dagPanel(edgesA, 'Run A')
        sb << '    </div>\n'
        sb << '    <div class="dag-panel" data-dag="b">\n'
        sb << dagPanel(edgesB, 'Run B')
        sb << '    </div>\n'
        sb << '  </div>\n'
        sb << '  <table class="proc">\n'
        sb << '    <thead><tr><th>Producer</th><th>Consumer</th><th></th></tr></thead>\n  <tbody>\n'
        changed.each { DiffResult.DagEdgeDiff d ->
            final label = d.isAdded() ? 'added' : 'removed'
            sb << "    <tr class=\"row-${label}\"><th class=\"mono\">${esc(d.from())}</th>"
            sb << "<td class=\"mono\">${esc(d.to())}</td>"
            sb << "<td><span class=\"pill ${label}\">${label}</span></td></tr>\n"
        }
        sb << '  </tbody>\n  </table>\n'
        sb << '</section>\n'
    }

    /**
     * Project the union DAG down to a single run's own wiring: keep the edges
     * that existed in that run ({@code a} → unchanged + removed; {@code b} →
     * unchanged + added) and re-tag them all as {@link Kind#UNCHANGED} so the
     * per-run view renders neutrally — it shows the topology as it was, not a
     * diff against the other run.
     */
    private static List<DiffResult.DagEdgeDiff> runEdges(List<DiffResult.DagEdgeDiff> all, String side) {
        final keep = side == 'a' ? [Kind.UNCHANGED, Kind.REMOVED] : [Kind.UNCHANGED, Kind.ADDED]
        return all.findAll { DiffResult.DagEdgeDiff d -> keep.contains(d.kind) }
                .collect { DiffResult.DagEdgeDiff d ->
                    new DiffResult.DagEdgeDiff(
                            edge: new DiffResult.DagEdge(from: d.from(), to: d.to()),
                            kind: Kind.UNCHANGED)
                }
    }

    /** A single-run DAG panel: its neutral node-link diagram, or an empty note. */
    private String dagPanel(List<DiffResult.DagEdgeDiff> edges, String label) {
        if( edges.isEmpty() )
            return "    <p class=\"mode-note\">No process&rarr;process wiring was recorded for ${esc(label)}.</p>\n"
        return dagSvg(edges, false)
    }

    // Geometry for the DAG node-link diagram (user-space SVG units).
    private static final int DAG_NODE_W = 148
    private static final int DAG_NODE_H = 32
    private static final int DAG_COL_W  = 210
    private static final int DAG_ROW_H  = 56
    private static final int DAG_MARGIN = 18

    /**
     * Render the union DAG (edges present in either run) as a self-contained,
     * inline SVG node-link diagram. Edges are colour-coded by status — neutral
     * when present in both runs, green when added in B, dashed red when removed
     * (only in A) — and nodes are outlined to match when a process appears in
     * only one run. Layout is a lightweight longest-path layering (columns =
     * topological depth), computed here so the SVG needs no JS or external libs.
     *
     * <p>When {@code diffLegend} is false the added/removed legend is omitted —
     * used by the per-run views, which render every edge neutrally.
     */
    private String dagSvg(List<DiffResult.DagEdgeDiff> edges, boolean diffLegend = true) {
        // Collect nodes in first-seen order and build the combined adjacency.
        final nodes = new LinkedHashSet<String>()
        final succ = new LinkedHashMap<String, List<String>>()
        final indeg = new HashMap<String, Integer>()
        final inA = new HashSet<String>()
        final inB = new HashSet<String>()
        edges.each { DiffResult.DagEdgeDiff d ->
            final f = d.from()
            final t = d.to()
            if( f == null || t == null )
                return
            nodes.add(f)
            nodes.add(t)
            List<String> lst = succ.get(f)
            if( lst == null ) { lst = new ArrayList<String>(); succ.put(f, lst) }
            lst.add(t)
            indeg.put(f, indeg.getOrDefault(f, 0))
            indeg.put(t, indeg.getOrDefault(t, 0) + 1)
            if( d.kind == Kind.UNCHANGED || d.kind == Kind.REMOVED ) { inA.add(f); inA.add(t) }
            if( d.kind == Kind.UNCHANGED || d.kind == Kind.ADDED )   { inB.add(f); inB.add(t) }
        }
        if( nodes.isEmpty() )
            return ''

        // Longest-path layering (Kahn): layer[v] = max(layer[pred]+1). Any node
        // left unresolved by a stray cycle simply keeps layer 0 and is still drawn.
        final layer = new HashMap<String, Integer>()
        nodes.each { String n -> layer.put(n, 0) }
        final deg = new HashMap<String, Integer>(indeg)
        final queue = new ArrayDeque<String>()
        nodes.each { String n -> if( deg.get(n) == 0 ) queue.add(n) }
        while( !queue.isEmpty() ) {
            final u = queue.poll()
            (succ.get(u) ?: new ArrayList<String>()).each { String v ->
                if( layer.get(v) < layer.get(u) + 1 )
                    layer.put(v, layer.get(u) + 1)
                deg.put(v, deg.get(v) - 1)
                if( deg.get(v) == 0 )
                    queue.add(v)
            }
        }

        // Bucket nodes by layer (first-seen order preserved) and assign coords.
        final byLayer = new LinkedHashMap<Integer, List<String>>()
        nodes.each { String n ->
            final l = layer.get(n)
            List<String> col = byLayer.get(l)
            if( col == null ) { col = new ArrayList<String>(); byLayer.put(l, col) }
            col.add(n)
        }
        final maxLayer = layer.values().max()
        final pos = new HashMap<String, int[]>()
        int maxRows = 0
        byLayer.each { Integer l, List<String> ns ->
            maxRows = Math.max(maxRows, ns.size())
            ns.eachWithIndex { String n, Integer r ->
                final x = DAG_MARGIN + l * DAG_COL_W
                final y = DAG_MARGIN + r * DAG_ROW_H
                pos.put(n, [x, y] as int[])
            }
        }
        final w = DAG_MARGIN + maxLayer * DAG_COL_W + DAG_NODE_W + DAG_MARGIN
        final h = DAG_MARGIN * 2 + Math.max(0, maxRows - 1) * DAG_ROW_H + DAG_NODE_H

        final svg = new StringBuilder()
        svg << "  <div class=\"dag-graph\">\n"
        svg << "    <svg class=\"dag-svg\" viewBox=\"0 0 ${w} ${h}\" width=\"${w}\" height=\"${h}\" role=\"img\" aria-label=\"Process wiring diagram\">\n"
        svg << '      <defs>\n'
        svg << '        <marker id="dag-arr-eq" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto" markerUnits="userSpaceOnUse"><path d="M0,0 L7,3 L0,6 Z" fill="#94a3b8"/></marker>\n'
        svg << '        <marker id="dag-arr-add" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto" markerUnits="userSpaceOnUse"><path d="M0,0 L7,3 L0,6 Z" fill="#22c55e"/></marker>\n'
        svg << '        <marker id="dag-arr-rem" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto" markerUnits="userSpaceOnUse"><path d="M0,0 L7,3 L0,6 Z" fill="#ef4444"/></marker>\n'
        svg << '      </defs>\n'

        // Edges first, so nodes paint on top.
        final int dx = (int)(DAG_COL_W * 0.4d)
        edges.each { DiffResult.DagEdgeDiff d ->
            final pf = pos.get(d.from())
            final pt = pos.get(d.to())
            if( pf == null || pt == null )
                return
            final cls = d.isAdded() ? 'add' : (d.isRemoved() ? 'rem' : 'eq')
            final x1 = pf[0] + DAG_NODE_W
            final y1 = pf[1] + (DAG_NODE_H / 2 as int)
            final x2 = pt[0]
            final y2 = pt[1] + (DAG_NODE_H / 2 as int)
            svg << "      <path class=\"dag-edge ${cls}\" d=\"M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}\" marker-end=\"url(#dag-arr-${cls})\"/>\n"
        }

        // Nodes.
        nodes.each { String n ->
            final p = pos.get(n)
            final status = (inA.contains(n) && inB.contains(n)) ? 'eq' : (inB.contains(n) ? 'add' : 'rem')
            final label = n.length() > 20 ? n.substring(0, 19) + '…' : n
            final cx = p[0] + (DAG_NODE_W / 2 as int)
            final cy = p[1] + (DAG_NODE_H / 2 as int) + 4
            svg << "      <g class=\"dag-node ${status}\">"
            svg << "<title>${esc(n)}</title>"
            svg << "<rect x=\"${p[0]}\" y=\"${p[1]}\" width=\"${DAG_NODE_W}\" height=\"${DAG_NODE_H}\" rx=\"7\"/>"
            svg << "<text x=\"${cx}\" y=\"${cy}\" text-anchor=\"middle\">${esc(label)}</text></g>\n"
        }
        svg << '    </svg>\n'
        if( diffLegend ) {
            svg << '    <div class="dag-legend">\n'
            svg << '      <span class="lg"><span class="ln eq"></span>Unchanged (both runs)</span>\n'
            svg << '      <span class="lg"><span class="ln add"></span>Added in B</span>\n'
            svg << '      <span class="lg"><span class="ln rem"></span>Removed (only in A)</span>\n'
            svg << '    </div>\n'
        }
        svg << '  </div>\n'
        return svg.toString()
    }

    // ----------------------------------------------------------------- tasks

    private void renderTasks(StringBuilder sb, DiffResult diff) {
        sb << '<section id="tasks" class="section">\n'
        sb << '  <div class="tasks-head">\n'
        sb << '    <h2>Tasks</h2>\n'
        sb << '    <div class="filters">\n'
        sb << '      <label><input type="checkbox" id="hide-unchanged" checked> Hide unchanged</label>\n'
        sb << '    </div>\n'
        sb << '  </div>\n'
        if( !diff.showObvious )
            sb << '  <p class="mode-note">Showing meaningful differences only. Fields that always change between runs — run name, session id, work dir, timing and resource usage — are shown for context but not flagged. Re-run with <code>--verbose</code> to diff them too.</p>\n'

        diff.tasks.each { TaskDiff td ->
            renderTaskCard(sb, td, diff.showObvious)
        }
        sb << '</section>\n'
    }

    private void renderTaskCard(StringBuilder sb, TaskDiff td, boolean showObvious) {
        final kind = td.kind.name().toLowerCase()
        final unchangedAttr = td.kind == Kind.UNCHANGED ? ' data-unchanged="1"' : ''
        sb << "  <div class=\"task ${kind}\"${unchangedAttr}>\n"
        sb << '    <div class="task-hdr" onclick="toggleTask(this)">\n'
        sb << "      <span class=\"pill ${kind}\">${kind}</span>\n"
        sb << "      <span class=\"task-key mono\">${esc(td.key)}</span>\n"
        sb << "      <span class=\"task-proc\">${esc(td.process())}</span>\n"
        sb << '      <span class="chev">▸</span>\n'
        sb << '    </div>\n'
        sb << '    <div class="task-body">\n'

        if( td.kind == Kind.ADDED || td.kind == Kind.REMOVED ) {
            renderSoloTask(sb, td)
        }
        else {
            renderChangedTask(sb, td, showObvious)
        }

        sb << '    </div>\n  </div>\n'
    }

    private void renderSoloTask(StringBuilder sb, TaskDiff td) {
        final t = td.a ?: td.b
        final where = td.kind == Kind.REMOVED ? 'Only present in Run A' : 'Only present in Run B'
        sb << "      <p class=\"solo-note\">${esc(where)}</p>\n"
        sb << '      <table class="kv"><tbody>\n'
        RunComparator.TASK_FIELDS.each { String f ->
            final v = t.display.get(f)
            if( v != null && !v.isEmpty() )
                sb << "        <tr><th>${esc(f)}</th><td colspan=\"2\">${esc(v)}</td></tr>\n"
        }
        sb << '      </tbody></table>\n'
    }

    private void renderChangedTask(StringBuilder sb, TaskDiff td, boolean showObvious) {
        // resource comparison bars
        sb << '      <div class="metrics">\n'
        METRICS.each { List<String> m ->
            final rawField = m[0]
            final label = m[1]
            final da = td.a.numericDouble(rawField)
            final db = td.b.numericDouble(rawField)
            if( da != null || db != null ) {
                final fa = Format.orNa(td.a.display.get(rawField))
                final fb = Format.orNa(td.b.display.get(rawField))
                sb << "        <div class=\"metric\"><div class=\"metric-label\">${esc(label)}</div>\n"
                sb << metricBars(rawField, da ?: 0.0d, db ?: 0.0d, fa, fb)
                sb << '        </div>\n'
            }
        }
        sb << '      </div>\n'

        // field-level diff table
        sb << '      <table class="kv diff-table"><thead><tr><th>Field</th><th>Run A</th><th>Run B</th></tr></thead><tbody>\n'
        td.fieldDiffs.each { FieldDiff fd ->
            if( fd.field == 'script' || fd.field == 'container' ) {
                if( fd.isHighlighted(showObvious) )
                    renderTextDiffRow(sb, fd)
                return
            }
            sb << fieldRow(fd, showObvious)
        }
        sb << '      </tbody></table>\n'
    }

    /** Render a line-level side-by-side diff for multi-line fields (script). */
    private void renderTextDiffRow(StringBuilder sb, FieldDiff fd) {
        sb << "        <tr class=\"row-changed\"><th>${esc(fd.field)}</th><td colspan=\"2\">\n"
        sb << textDiffPanes(fd.valueA ?: '', fd.valueB ?: '')
        sb << '        </td></tr>\n'
    }

    private String textDiffPanes(String a, String b) {
        final linesA = a.split('\n', -1) as List<String>
        final linesB = b.split('\n', -1) as List<String>
        final ops = LineDiff.diff(linesA, linesB)

        final left = new StringBuilder()
        final right = new StringBuilder()
        ops.each { LineDiff.Op op ->
            switch( op.type ) {
                case LineDiff.Type.EQUAL:
                    left << codeLine(op.text, 'eq')
                    right << codeLine(op.text, 'eq')
                    break
                case LineDiff.Type.DELETE:
                    left << codeLine(op.text, 'del')
                    right << codeLine('', 'gap')
                    break
                case LineDiff.Type.INSERT:
                    left << codeLine('', 'gap')
                    right << codeLine(op.text, 'ins')
                    break
            }
        }
        return """\
          <div class="code-diff">
            <div class="code-col"><div class="code-col-hdr">Run A</div><pre>${left}</pre></div>
            <div class="code-col"><div class="code-col-hdr">Run B</div><pre>${right}</pre></div>
          </div>
"""
    }

    private String codeLine(String text, String cls) {
        return "<span class=\"cl ${cls}\">${esc(text)}</span>\n"
    }

    // ------------------------------------------------------------ bars / svg

    private String metricBars(String id, double a, double b, String fa, String fb) {
        final max = Math.max(a, b)
        final wa = max > 0 ? (a / max) * 100.0d : 0.0d
        final wb = max > 0 ? (b / max) * 100.0d : 0.0d
        return """\
        <div class="bars">
          <div class="bar-row"><span class="bar-tag a">A</span><div class="bar-track"><div class="bar-fill a" style="width:${fmt(wa)}%"></div></div><span class="bar-val">${esc(fa)}</span></div>
          <div class="bar-row"><span class="bar-tag b">B</span><div class="bar-track"><div class="bar-fill b" style="width:${fmt(wb)}%"></div></div><span class="bar-val">${esc(fb)}</span></div>
        </div>
"""
    }

    private void renderFooter(StringBuilder sb, DiffResult diff) {
        sb << '<footer class="foot">\n'
        sb << "  Generated by <strong>nf-diff</strong> · ${esc(diff.generatedAt.toString())}\n"
        sb << '  <div class="foot-sub">Compares run history and cache produced by <strong>Nextflow</strong>.</div>\n'
        sb << '  <div class="foot-sub"><a href="https://github.com/mribeirodantas/nf-diff" target="_blank" rel="noopener noreferrer">github.com/mribeirodantas/nf-diff</a></div>\n'
        sb << '</footer>\n'
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Render a key/value comparison row. Rows are only highlighted as changed
     * when the change is meaningful for the current view. An "obvious" field
     * that differs is shown with a muted marker (unless verbose), so the reader
     * sees the values differ without it being flagged as a real change.
     */
    private static String fieldRow(FieldDiff fd, boolean showObvious) {
        final highlighted = fd.isHighlighted(showObvious)
        final softChange = fd.changed && fd.obvious && !showObvious
        final cls = highlighted ? ' class="row-changed"' : (softChange ? ' class="row-obvious"' : '')
        final tag = softChange ? ' <span class="tag-auto" title="Always differs between runs">auto</span>' : ''
        return "        <tr${cls}><th>${esc(fd.field)}${tag}</th><td>${esc(Format.orNa(fd.valueA))}</td><td>${esc(Format.orNa(fd.valueB))}</td></tr>\n"
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, '%.2f', v)
    }

    private static boolean isSucceeded(String status) {
        return status.startsWith('OK') || status == 'COMPLETED' || status == 'SUCCEEDED'
    }

    private static boolean isFailed(String status) {
        return status.startsWith('ERR') || status == 'FAILED'
    }

    /**
     * Map a run's raw history-file status token to the workflow-status
     * vocabulary used across Nextflow / Seqera Platform (SUCCEEDED/FAILED).
     * Delegates to {@link RunSnapshot#statusLabel(String)} so the header pill
     * and the metadata table share one definition.
     */
    private static String statusLabel(String raw) {
        return RunSnapshot.statusLabel(raw)
    }

    /** HTML-escape text for safe embedding. */
    private static String esc(String s) {
        if( s == null )
            return ''
        return s.replace('&', '&amp;')
                .replace('<', '&lt;')
                .replace('>', '&gt;')
                .replace('"', '&quot;')
                .replace("'", '&#39;')
    }

    // ------------------------------------------------------------- css / js

    private static String css() {
        return CSS + heroBackgroundCss()
    }

    // The report is a single self-contained document (no external assets, no
    // network), so the hero background image is embedded as a base64 data URI
    // read from the bundled resource rather than linked by path. Rendered over
    // a theme-aware scrim (--hero-scrim) so the header text stays legible. If
    // the resource is missing the report simply keeps its flat panel header.
    private static String heroBackgroundCss() {
        final bytes = HtmlReportRenderer.getResourceAsStream('hero-bg.jpg')?.bytes
        if( !bytes )
            return ''
        final data = bytes.encodeBase64().toString()
        return "\n.hero{background-image:linear-gradient(var(--hero-scrim),var(--hero-scrim))," +
                "url(\"data:image/jpeg;base64,${data}\");background-size:cover;background-position:center}\n"
    }

    private static String js() {
        return JS
    }

    private static final String CSS = '''
:root{
  /* nf-docs dark palette: slate-900/800/700 surfaces with the shared primary
     green (#0DC09D). A blue keeps Run A / Run B visually distinct. */
  --bg:#0f172a; --bg2:#1e293b; --panel:#1e293b; --panel2:#334155;
  --txt:#e2e8f0; --muted:#94a3b8; --line:#334155;
  --brand:#0dc09d;
  --a:#0dc09d; --a2:#0a9a7d; --b:#3b82f6; --b2:#2563eb;
  --added:#22c55e; --removed:#ef4444; --changed:#eab308; --unchanged:#64748b;
  /* theme-dependent extras */
  --chip-ink:#ffffff;
  --brand-soft:rgba(13,192,157,.18);
  --tabs-bg:rgba(30,41,59,.85);
  --gap-stripe:rgba(255,255,255,.04);
  --shadow:rgba(0,0,0,.30);
  /* card surfaces: hairline border + layered soft elevation instead of a hard
     1px box. --hair is a barely-there edge; --elev/--elev-hover give depth. */
  --radius:14px;
  --hair:rgba(255,255,255,.08);
  --elev:0 1px 2px rgba(0,0,0,.28),0 6px 20px rgba(0,0,0,.26);
  --elev-hover:0 2px 6px rgba(0,0,0,.30),0 14px 32px rgba(0,0,0,.38);
  /* scrim laid over the hero background image so header text stays legible */
  --hero-scrim:rgba(15,23,42,.78);
}
html[data-theme="light"]{
  /* nf-docs default palette: slate-50/100/200 with white cards. */
  --bg:#f8fafc; --bg2:#f1f5f9; --panel:#ffffff; --panel2:#f8fafc;
  --txt:#1e293b; --muted:#64748b; --line:#e2e8f0;
  --brand:#0dc09d;
  --a:#0dc09d; --a2:#0a9a7d; --b:#3b82f6; --b2:#2563eb;
  --added:#16a34a; --removed:#dc2626; --changed:#a16207; --unchanged:#64748b;
  --chip-ink:#ffffff;
  --brand-soft:rgba(13,192,157,.12);
  --tabs-bg:rgba(255,255,255,.85);
  --gap-stripe:rgba(15,23,42,.05);
  --shadow:rgba(15,23,42,.08);
  --radius:14px;
  --hair:rgba(15,23,42,.07);
  --elev:0 1px 2px rgba(15,23,42,.06),0 8px 24px rgba(15,23,42,.07);
  --elev-hover:0 2px 8px rgba(15,23,42,.09),0 16px 34px rgba(15,23,42,.11);
  --hero-scrim:rgba(248,250,252,.80);
}
*{box-sizing:border-box}
body{margin:0;font-family:ui-sans-serif,system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
  background:var(--bg);color:var(--txt);line-height:1.625;
  transition:background-color .2s ease,color .2s ease}
.theme-toggle{position:absolute;top:16px;right:16px;z-index:20;display:inline-grid;place-items:center;
  width:36px;height:36px;border-radius:8px;cursor:pointer;font-size:17px;line-height:1;
  background:var(--panel);border:1px solid var(--line);color:var(--txt);
  transition:background .2s ease}
.theme-toggle:hover{background:var(--panel2)}
.theme-toggle .ti-light{display:none}
html[data-theme="light"] .theme-toggle .ti-dark{display:none}
html[data-theme="light"] .theme-toggle .ti-light{display:inline}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.wrap{max-width:1100px;margin:0 auto;padding:24px}
.hero{position:relative;padding:36px 24px 28px;background:var(--panel);border-bottom:1px solid var(--line)}
.hero-inner{max-width:1100px;margin:0 auto}
.brand{display:flex;align-items:center;gap:10px;font-weight:700;letter-spacing:.3px;color:var(--muted)}
.logo{display:inline-grid;place-items:center;width:30px;height:30px;border-radius:8px;background:var(--brand);color:#fff;font-weight:900;font-size:18px}
.hero-title{display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin:14px 0 20px}
.hero h1{margin:0;font-size:30px;font-weight:700;color:var(--brand)}
.runs{display:flex;align-items:center;gap:18px;flex-wrap:wrap}
.run-chip{flex:1;min-width:260px;background:var(--panel);border:1px solid var(--hair);border-radius:var(--radius);padding:16px 18px;position:relative;overflow:hidden;box-shadow:var(--elev);transition:box-shadow .18s ease,transform .18s ease}
.run-chip:hover{box-shadow:var(--elev-hover);transform:translateY(-1px)}
.run-name{display:inline-block;font-size:16px;font-weight:700;color:var(--chip-ink);padding:4px 12px;border-radius:999px}
.run-a .run-name{background:var(--a)} .run-b .run-name{background:var(--b)}
.run-meta{display:flex;align-items:center;gap:10px;margin:6px 0}
.run-sub{color:var(--muted);font-size:13px}
.run-facts{display:grid;grid-template-columns:auto 1fr;gap:2px 12px;margin:12px 0 0;padding-top:12px;border-top:1px solid var(--line)}
.run-fact{display:contents}
.run-fact dt{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.5px}
.run-fact dd{margin:0;font-size:13px;color:var(--txt)}
.run-fact-path{color:var(--muted);font-size:11px;word-break:break-all;margin-top:2px;font-weight:400}
.vs{font-weight:700;color:var(--muted);font-size:15px}
.verdict{display:inline-block;padding:8px 14px;border-radius:8px;font-weight:600;font-size:14px}
.verdict.same{background:var(--brand-soft);color:var(--brand);border:1px solid rgba(13,192,157,.4)}
.verdict.diff{background:rgba(234,179,8,.12);color:var(--changed);border:1px solid rgba(234,179,8,.4)}
.layout{display:flex;align-items:flex-start;max-width:1340px;margin:0 auto;gap:0}
.sidenav{position:sticky;top:0;align-self:flex-start;flex:0 0 220px;display:flex;flex-direction:column;gap:2px;
  padding:22px 14px;max-height:100vh;overflow-y:auto;background:var(--tabs-bg);backdrop-filter:blur(10px);border-right:1px solid var(--line)}
.sidenav-title{color:var(--muted);font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.7px;padding:4px 12px 8px}
.sidenav a{display:flex;align-items:center;justify-content:space-between;gap:8px;
  color:var(--muted);text-decoration:none;padding:9px 14px;border-radius:8px;font-weight:600;font-size:14px;
  border-left:3px solid transparent}
.sidenav a:hover{color:var(--txt);background:var(--panel2)}
.sidenav a.active{color:var(--chip-ink);background:var(--brand);border-left-color:transparent;box-shadow:0 2px 8px var(--brand-soft)}
/* trailing icon marking a section that holds differences */
.sidenav a .nav-alert{flex:none;color:var(--changed);font-size:12px;line-height:1}
.sidenav a.active .nav-alert{color:var(--chip-ink)}
.wrap{flex:1;min-width:0;max-width:none}
/* Paginated sections: one page visible at a time. */
.section{margin:34px 0;display:none}
.section.is-active{display:block}
.pager{display:flex;justify-content:space-between;gap:12px;margin:40px 0 8px;padding-top:20px;border-top:1px solid var(--line)}
.pager-btn{display:inline-flex;align-items:center;gap:6px;cursor:pointer;font-size:14px;font-weight:600;
  color:var(--txt);background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:9px 16px;
  transition:background .15s ease}
.pager-btn:hover:not(:disabled){background:var(--panel2)}
.pager-btn:disabled{opacity:.4;cursor:default}
.pager-btn#pager-next{margin-left:auto}
@media (max-width:820px){
  .layout{flex-direction:column}
  .sidenav{position:sticky;top:0;z-index:10;flex:none;width:100%;flex-direction:row;gap:4px;max-height:none;
    overflow-x:auto;overflow-y:hidden;padding:8px 16px;border-right:none;border-bottom:1px solid var(--line)}
  .sidenav-title{display:none}
  .sidenav a{white-space:nowrap;border-left:none;border-bottom:3px solid transparent;border-radius:6px}
  .sidenav a.active{border-left:none;border-bottom-color:transparent;background:var(--brand);color:var(--chip-ink)}
}
.section h2{font-size:22px;font-weight:600;margin:0 0 16px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:14px}
.card{background:var(--panel);border:1px solid var(--hair);border-radius:var(--radius);padding:18px;box-shadow:var(--elev);transition:box-shadow .18s ease,transform .18s ease}
.card:hover{box-shadow:var(--elev-hover);transform:translateY(-1px)}
.card.wide{margin-top:16px}
.card-title{color:var(--muted);font-size:13px;text-transform:uppercase;letter-spacing:.6px;margin-bottom:12px}
.stat{text-align:center;position:relative;overflow:hidden}
.stat .stat-num{font-size:36px;font-weight:800}
.stat .stat-label{color:var(--muted);font-size:13px;margin-top:4px}
.stat.changed .stat-num{color:var(--changed)}
.stat.added .stat-num{color:var(--added)}
.stat.removed .stat-num{color:var(--removed)}
.stat.unchanged .stat-num{color:var(--muted)}
.summary-headline{display:flex;align-items:center;gap:16px;background:var(--panel);border:1px solid var(--hair);border-radius:var(--radius);padding:18px 22px;box-shadow:var(--elev);margin-bottom:18px}
.summary-headline .hl-num{font-size:46px;font-weight:800;line-height:1}
.summary-headline.same .hl-num{color:var(--brand)}
.summary-headline.diff .hl-num{color:var(--changed)}
.summary-headline .hl-label-lg{font-size:16px;font-weight:600;color:var(--txt)}
.summary-headline .hl-label{color:var(--muted);font-size:13px}
.card-group{margin-top:20px}
.card-group>h3{font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.6px;color:var(--muted);margin:0 0 12px}
.disp{background:var(--panel);border:1px solid var(--hair);border-radius:var(--radius);padding:18px;box-shadow:var(--elev)}
.disp-bar{display:flex;height:22px;border-radius:6px;overflow:hidden;background:var(--panel2)}
.disp-seg{height:100%}
.disp-seg.changed{background:var(--changed)}
.disp-seg.removed{background:var(--removed)}
.disp-seg.added{background:var(--added)}
.disp-seg.unchanged{background:var(--unchanged)}
.disp-legend{display:flex;flex-wrap:wrap;gap:18px;margin-top:12px}
.disp-legend .lg{display:flex;align-items:center;gap:7px;font-size:13px;color:var(--muted)}
.disp-legend .sw{width:11px;height:11px;border-radius:3px;display:inline-block}
.disp-legend .sw.changed{background:var(--changed)}
.disp-legend .sw.removed{background:var(--removed)}
.disp-legend .sw.added{background:var(--added)}
.disp-legend .sw.unchanged{background:var(--unchanged)}
.disp-legend .lg strong{color:var(--txt);font-weight:700}
.disp-note{color:var(--muted);font-size:13px;margin-top:12px}
.disp-note strong{color:var(--txt)}
.delta{margin-top:10px;color:var(--muted);font-size:14px}
.rp-plot{overflow-x:auto;border:1px solid var(--hair);border-radius:var(--radius);background:var(--panel);padding:16px;margin-bottom:16px;box-shadow:var(--elev)}
.rp-svg{display:block;max-width:100%;height:auto}
.rp-axis{stroke:var(--line);stroke-width:1.5}
.rp-axhdr{fill:var(--muted);font:600 11px system-ui,sans-serif}
.rp-lbl{fill:var(--txt);font:500 12px ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.rp-val{fill:var(--muted);font:600 11px system-ui,sans-serif}
.rp-bar.worse{fill:var(--removed)}
.rp-bar.better{fill:var(--added)}
.rp-bar.samework{stroke:var(--txt);stroke-width:1.5;stroke-dasharray:4 2}
.rp-legend{display:flex;flex-wrap:wrap;gap:18px;margin-top:12px}
.rp-legend .lg{display:flex;align-items:center;gap:7px;font-size:13px;color:var(--muted)}
.rp-legend .sw{width:11px;height:11px;border-radius:3px;display:inline-block}
.rp-legend .sw.worse{background:var(--removed)}
.rp-legend .sw.better{background:var(--added)}
.rp-legend .sw.samework{background:transparent;border:1.5px dashed var(--txt)}
.dag-tabset{margin-bottom:16px}
.dag-tabs{display:flex;gap:4px;margin-bottom:12px;border-bottom:1px solid var(--line)}
.dag-tab{cursor:pointer;font-size:13px;font-weight:600;color:var(--muted);background:none;border:none;
  padding:9px 16px;border-bottom:2px solid transparent;margin-bottom:-1px}
.dag-tab:hover{color:var(--txt)}
.dag-tab.active{color:var(--brand);border-bottom-color:var(--brand)}
.dag-panel{display:none}
.dag-panel.is-active{display:block}
.dag-panel .dag-graph{margin-bottom:0}
.dag-graph{overflow-x:auto;border:1px solid var(--hair);border-radius:var(--radius);background:var(--panel);padding:16px;margin-bottom:16px;box-shadow:var(--elev)}
.dag-svg{display:block;max-width:100%;height:auto}
.dag-node rect{fill:var(--panel2);stroke:var(--line);stroke-width:1.5}
.dag-node.add rect{stroke:#22c55e;stroke-width:2}
.dag-node.rem rect{stroke:#ef4444;stroke-width:2;stroke-dasharray:5 3}
.dag-node text{fill:var(--txt);font:600 11px ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.dag-edge{fill:none;stroke-width:2}
.dag-edge.eq{stroke:#94a3b8}
.dag-edge.add{stroke:#22c55e}
.dag-edge.rem{stroke:#ef4444;stroke-dasharray:6 4}
.dag-legend{display:flex;flex-wrap:wrap;gap:18px;margin-top:14px;font-size:13px;color:var(--muted)}
.dag-legend .lg{display:flex;align-items:center;gap:8px}
.dag-legend .ln{width:24px;border-top:2px solid;display:inline-block}
.dag-legend .ln.eq{border-color:#94a3b8}
.dag-legend .ln.add{border-color:#22c55e}
.dag-legend .ln.rem{border-top-style:dashed;border-color:#ef4444}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--hair);border-radius:var(--radius);overflow:hidden;box-shadow:var(--elev)}
th,td{text-align:left;padding:10px 14px;border-bottom:1px solid var(--line);vertical-align:top;font-size:14px}
thead th{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.5px;background:var(--panel2)}
tbody tr:hover{background:var(--panel2)}
tbody tr:last-child th,tbody tr:last-child td{border-bottom:none}
table.kv th{width:180px;color:var(--muted);font-weight:600}
.row-changed{background:rgba(234,179,8,.10)}
.row-changed th{color:var(--changed)}
.row-obvious th{color:var(--muted)}
.tag-auto{display:inline-block;margin-left:6px;padding:1px 6px;border-radius:6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.4px;color:var(--muted);background:var(--panel2);border:1px solid var(--line);vertical-align:middle}
.src{display:inline-block;padding:1px 7px;border-radius:6px;font-size:10px;font-weight:700;letter-spacing:.3px;border:1px solid var(--line)}
.src-cli{color:var(--b);background:rgba(59,130,246,.14)}
.src-file{color:var(--brand);background:rgba(13,192,157,.14)}
.src-both{color:var(--changed);background:rgba(234,179,8,.16)}
.src-na{color:var(--muted)}
.mode-note{color:var(--muted);font-size:13px;margin:0 0 14px;line-height:1.6}
.mode-note code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--bg2);border:1px solid var(--line);border-radius:6px;padding:1px 6px;font-size:12px}
.warn-note{color:var(--changed);font-size:13px;margin:0 0 14px;line-height:1.6;background:rgba(234,179,8,.10);border:1px solid var(--hair);border-radius:var(--radius);padding:10px 14px}
.warn-note strong{color:var(--changed)}
.row-added{background:rgba(34,197,94,.08)} .row-removed{background:rgba(239,68,68,.08)}
.pill{display:inline-block;padding:3px 10px;border-radius:999px;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.4px}
.pill.added{background:rgba(34,197,94,.15);color:var(--added)}
.pill.removed{background:rgba(239,68,68,.15);color:var(--removed)}
.pill.changed{background:rgba(234,179,8,.18);color:var(--changed)}
.pill.unchanged{background:rgba(100,116,139,.2);color:var(--muted)}
.pill.status-ok{background:rgba(34,197,94,.15);color:var(--added)}
.pill.status-err{background:rgba(239,68,68,.15);color:var(--removed)}
.pill.status-unknown{background:rgba(100,116,139,.2);color:var(--muted)}
.tasks-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}
.filters label{color:var(--muted);font-size:14px;cursor:pointer;user-select:none}
.task{border:1px solid var(--hair);border-radius:var(--radius);margin:10px 0;overflow:hidden;background:var(--panel);box-shadow:var(--elev)}
.task-hdr{display:flex;align-items:center;gap:12px;padding:12px 16px;cursor:pointer;user-select:none}
.task-hdr:hover{background:var(--panel2)}
.task-key{font-weight:600}
.task-proc{color:var(--muted);font-size:13px;margin-left:auto}
.chev{transition:transform .15s ease;color:var(--muted)}
.task.open .chev{transform:rotate(90deg)}
.task-body{display:none;padding:8px 16px 18px;border-top:1px solid var(--line)}
.task.open .task-body{display:block}
.solo-note{color:var(--muted)}
.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px;margin:6px 0 18px}
.metric-label{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.5px;margin-bottom:6px}
.bars{display:flex;flex-direction:column;gap:6px}
.bar-row{display:flex;align-items:center;gap:8px}
.bar-tag{width:18px;height:18px;border-radius:5px;display:grid;place-items:center;font-size:11px;font-weight:800;color:var(--chip-ink)}
.bar-tag.a{background:var(--a)} .bar-tag.b{background:var(--b)}
.bar-track{flex:1;height:12px;background:var(--bg2);border-radius:999px;overflow:hidden}
.bar-fill{height:100%;border-radius:999px}
.bar-fill.a{background:var(--a)}
.bar-fill.b{background:var(--b)}
.bar-val{min-width:80px;text-align:right;font-size:12px;color:var(--muted);font-family:ui-monospace,monospace}
.code-diff{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:8px}
.code-col{background:var(--bg2);border:1px solid var(--line);border-radius:8px;overflow:hidden}
.code-col-hdr{padding:6px 12px;font-size:12px;color:var(--muted);background:var(--panel2);border-bottom:1px solid var(--line)}
.code-col pre{margin:0;padding:0;overflow:auto;font-family:ui-monospace,monospace;font-size:12.5px}
.cl{display:block;padding:1px 12px;white-space:pre-wrap;word-break:break-word;border-left:3px solid transparent}
.cl.del{background:rgba(239,68,68,.14);border-left-color:var(--removed)}
.cl.ins{background:rgba(34,197,94,.14);border-left-color:var(--added)}
.cl.gap{background:repeating-linear-gradient(45deg,transparent,transparent 6px,var(--gap-stripe) 6px,var(--gap-stripe) 12px);min-height:1.4em}
.foot{text-align:center;color:var(--muted);padding:30px;border-top:1px solid var(--line);font-size:13px}
.foot-sub{margin-top:6px;font-size:12px;color:var(--muted);opacity:.85}
.foot-sub strong{color:var(--brand)}
.foot-sub a{color:var(--brand);text-decoration:none}
.foot-sub a:hover{text-decoration:underline}
'''

    private static final String JS = '''
function toggleTask(hdr){ hdr.parentElement.classList.toggle('open'); }
// theme toggle (initial theme already applied by the inline <head> script)
(function(){
  var root = document.documentElement, KEY = 'nf-diff-theme';
  var btn = document.getElementById('theme-toggle');
  if(!btn) return;
  btn.addEventListener('click', function(){
    var next = root.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
    root.setAttribute('data-theme', next);
    try{ localStorage.setItem(KEY, next); }catch(e){}
  });
})();
(function(){
  var cb = document.getElementById('hide-unchanged');
  function apply(){
    var hide = cb.checked;
    document.querySelectorAll('.task[data-unchanged="1"]').forEach(function(el){
      el.style.display = hide ? 'none' : '';
    });
  }
  if(cb){ cb.addEventListener('change', apply); apply(); }

  // Pagination: one section shown at a time, driven by the vertical nav.
  var links = Array.prototype.slice.call(document.querySelectorAll('.sidenav a'));
  var ids = links.map(function(a){ return a.getAttribute('href').slice(1); });
  var prev = document.getElementById('pager-prev');
  var next = document.getElementById('pager-next');
  function labelFor(id){
    var a = links[ids.indexOf(id)];
    return a ? a.textContent.trim() : '';
  }
  function show(id, push){
    var idx = ids.indexOf(id);
    if(idx < 0){ idx = 0; id = ids[0]; }
    ids.forEach(function(sid){
      var sec = document.getElementById(sid);
      if(sec) sec.classList.toggle('is-active', sid === id);
    });
    links.forEach(function(a,i){ a.classList.toggle('active', i === idx); });
    if(prev){
      prev.disabled = idx === 0;
      var pl = prev.querySelector('.pager-lbl');
      if(pl) pl.textContent = idx > 0 ? labelFor(ids[idx-1]) : 'Previous';
    }
    if(next){
      next.disabled = idx === ids.length - 1;
      var nl = next.querySelector('.pager-lbl');
      if(nl) nl.textContent = idx < ids.length - 1 ? labelFor(ids[idx+1]) : 'Next';
    }
    if(push && ('history' in window)){
      try{ history.replaceState(null, '', '#' + id); }catch(e){ location.hash = id; }
    }
    window.scrollTo(0, 0);
  }
  links.forEach(function(a){
    a.addEventListener('click', function(e){ e.preventDefault(); show(a.getAttribute('href').slice(1), true); });
  });
  if(prev) prev.addEventListener('click', function(){
    var i = ids.indexOf(currentId()); if(i > 0) show(ids[i-1], true);
  });
  if(next) next.addEventListener('click', function(){
    var i = ids.indexOf(currentId()); if(i < ids.length - 1) show(ids[i+1], true);
  });
  function currentId(){
    var active = document.querySelector('.sidenav a.active');
    return active ? active.getAttribute('href').slice(1) : ids[0];
  }
  window.addEventListener('hashchange', function(){ show(location.hash.slice(1), false); });
  show((location.hash && ids.indexOf(location.hash.slice(1)) >= 0) ? location.hash.slice(1) : ids[0], false);
})();
// DAG view tabs: switch between the union (Changes) diagram and each run's own.
(function(){
  document.querySelectorAll('.dag-tabset').forEach(function(set){
    var tabs = Array.prototype.slice.call(set.querySelectorAll('.dag-tab'));
    var panels = Array.prototype.slice.call(set.querySelectorAll('.dag-panel'));
    tabs.forEach(function(tab){
      tab.addEventListener('click', function(){
        var which = tab.getAttribute('data-dag');
        tabs.forEach(function(t){
          var on = t === tab;
          t.classList.toggle('active', on);
          t.setAttribute('aria-selected', on ? 'true' : 'false');
        });
        panels.forEach(function(p){ p.classList.toggle('is-active', p.getAttribute('data-dag') === which); });
      });
    });
  });
})();
'''
}
