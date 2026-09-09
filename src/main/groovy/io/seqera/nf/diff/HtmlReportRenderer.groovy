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
        sb << '</main>\n'
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
        sb << '    <h1>Run comparison</h1>\n'
        sb << '    <div class="runs">\n'
        sb << runChip(diff.runA, 'a')
        sb << '      <div class="vs">vs</div>\n'
        sb << runChip(diff.runB, 'b')
        sb << '    </div>\n'
        final sameText = diff.showObvious
                ? 'These runs are identical across every inspected layer'
                : 'These runs are identical (ignoring always-changing fields)'
        final verdict = diff.identical
                ? "<span class=\"verdict same\">${sameText}</span>"
                : "<span class=\"verdict diff\">${diff.tasksChanged + diff.tasksAdded + diff.tasksRemoved} task-level difference(s) detected</span>"
        sb << "    <div class=\"verdict-wrap\">${verdict}</div>\n"
        sb << '  </div>\n'
        sb << '</header>\n'
    }

    private String runChip(RunSnapshot run, String side) {
        final status = (run.status ?: 'UNKNOWN').toUpperCase()
        final statusClass = status.startsWith('OK') || status == 'COMPLETED' ? 'ok' : (status.startsWith('ERR') ? 'err' : 'unknown')
        return """\
      <div class="run-chip run-${side}">
        <div class="run-name">${esc(run.runName ?: run.requestedId)}</div>
        <div class="run-meta">
          <span class="pill status-${statusClass}">${esc(status)}</span>
          <span class="mono">${esc(run.sessionId ? run.sessionId.toString().substring(0, 8) : '????????')}</span>
        </div>
        <div class="run-sub">${esc(run.tasks.size().toString())} tasks · ${esc(Format.duration(run.durationMillis))}</div>
      </div>
"""
    }

    private void renderNav(StringBuilder sb, DiffResult diff) {
        sb << '<nav class="tabs" id="nav">\n'
        sb << '  <a href="#summary" class="active">Summary</a>\n'
        if( diff.hasFailures() )
            sb << '  <a href="#failures">Failures</a>\n'
        sb << '  <a href="#metadata">Metadata</a>\n'
        sb << '  <a href="#params">Parameters</a>\n'
        sb << '  <a href="#config">Configuration</a>\n'
        sb << '  <a href="#processes">Processes</a>\n'
        sb << '  <a href="#software">Software</a>\n'
        sb << '  <a href="#regressions">Regressions</a>\n'
        if( diff.hasEfficiency() )
            sb << '  <a href="#efficiency">Efficiency</a>\n'
        sb << '  <a href="#tasks">Tasks</a>\n'
        if( diff.diffOutputs )
            sb << '  <a href="#outputs">Outputs</a>\n'
        if( diff.diffLogs )
            sb << '  <a href="#logs">Logs</a>\n'
        if( diff.diffDag )
            sb << '  <a href="#dag">Wiring</a>\n'
        sb << '</nav>\n'
    }

    // --------------------------------------------------------------- summary

    private void renderSummary(StringBuilder sb, DiffResult diff) {
        sb << '<section id="summary" class="section">\n'
        sb << '  <h2>Summary</h2>\n'
        sb << '  <div class="cards">\n'
        sb << statCard('Tasks changed', diff.tasksChanged, 'changed')
        sb << statCard('Only in A', diff.tasksRemoved, 'removed')
        sb << statCard('Only in B', diff.tasksAdded, 'added')
        sb << statCard('Unchanged', diff.tasksUnchanged, 'unchanged')
        sb << statCard('Recomputed', diff.tasksRecomputed, 'changed')
        if( diff.hasFailures() ) {
            sb << statCard('Failed (A)', diff.failedCountA(), 'removed')
            sb << statCard('Failed (B)', diff.failedCountB(), 'removed')
            if( diff.newFailureCount() > 0 )
                sb << statCard('New failures', diff.newFailureCount(), 'removed')
        }
        sb << statCard('Software changed', diff.softwareChangedCount(), 'changed')
        sb << statCard('Regressions', diff.regressionCount(), 'removed')
        if( diff.hasEfficiency() )
            sb << statCard('Over-provisioned (B)', diff.overProvisionedB(), 'removed')
        if( diff.diffOutputs )
            sb << statCard('Outputs changed', diff.outputsChangedCount(), 'changed')
        if( diff.diffLogs )
            sb << statCard('Logs changed', diff.logsChangedCount(), 'changed')
        if( diff.diffDag )
            sb << statCard('Wiring edges changed', diff.dagEdgesAdded() + diff.dagEdgesRemoved(), 'changed')
        sb << '  </div>\n'

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
            sb << "  <p class=\"warn-note\"><strong>⚠ Run A</strong> finished in status <span class=\"mono\">${esc((diff.runA.status ?: 'UNKNOWN').toUpperCase())}</span>.</p>\n"
        if( DiffResult.runFailed(diff.runB) )
            sb << "  <p class=\"warn-note\"><strong>⚠ Run B</strong> finished in status <span class=\"mono\">${esc((diff.runB.status ?: 'UNKNOWN').toUpperCase())}</span>.</p>\n"

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
        sb << '  <p class="mode-note">Pipeline params (<code>--foo</code>) and Nextflow options (<code>-profile</code>, <code>-r</code>) from each run\'s launch command, merged with any <code>-params-file</code> contents. The <em>Source</em> column shows whether a value came from the command line, a params-file, or both (the command line wins on conflict).</p>\n'
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

    // ------------------------------------------------------------ efficiency

    private void renderEfficiency(StringBuilder sb, DiffResult diff) {
        sb << '<section id="efficiency" class="section">\n'
        sb << '  <h2>Resource efficiency</h2>\n'
        sb << '  <p class="mode-note">Peak measured CPU / memory versus what each process <em>requested</em>, read from the run cache. '
        sb << '<span class="pill removed">over</span> = used under 50% of the reservation (wasted allocation); '
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

    /** Efficiency class pill: over-provisioned (removed/red), tight (added/green), ok (unchanged). */
    private static String effPill(String cls) {
        if( cls == null )
            return '<span class="mono">—</span>'
        final kind = cls == 'over' ? 'removed' : (cls == 'tight' ? 'added' : 'unchanged')
        return "<span class=\"pill ${kind}\">${esc(cls)}</span>".toString()
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
        return String.format('%.2f', v)
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
        return CSS
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
.hero h1{margin:14px 0 20px;font-size:30px;font-weight:700;color:var(--brand)}
.runs{display:flex;align-items:center;gap:18px;flex-wrap:wrap}
.run-chip{flex:1;min-width:260px;background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:16px 18px;position:relative;overflow:hidden;box-shadow:0 1px 2px var(--shadow)}
.run-chip::before{content:"";position:absolute;inset:0 auto 0 0;width:4px}
.run-a::before{background:var(--a)} .run-b::before{background:var(--b)}
.run-name{font-size:18px;font-weight:700}
.run-meta{display:flex;align-items:center;gap:10px;margin:6px 0}
.run-sub{color:var(--muted);font-size:13px}
.vs{font-weight:700;color:var(--muted);font-size:15px}
.verdict-wrap{margin-top:18px}
.verdict{display:inline-block;padding:8px 14px;border-radius:8px;font-weight:600;font-size:14px}
.verdict.same{background:var(--brand-soft);color:var(--brand);border:1px solid rgba(13,192,157,.4)}
.verdict.diff{background:rgba(234,179,8,.12);color:var(--changed);border:1px solid rgba(234,179,8,.4)}
.tabs{position:sticky;top:0;z-index:10;display:flex;gap:4px;padding:8px 24px;background:var(--tabs-bg);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
.tabs a{color:var(--muted);text-decoration:none;padding:8px 14px;border-radius:6px;font-weight:600;font-size:14px}
.tabs a:hover{color:var(--txt);background:var(--panel2)}
.tabs a.active{color:var(--brand);background:var(--brand-soft)}
.section{margin:34px 0}
.section h2{font-size:22px;font-weight:600;margin:0 0 16px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:14px}
.card{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:18px;box-shadow:0 1px 2px var(--shadow)}
.card.wide{margin-top:16px}
.card-title{color:var(--muted);font-size:13px;text-transform:uppercase;letter-spacing:.6px;margin-bottom:12px}
.stat{text-align:center;position:relative;overflow:hidden}
.stat .stat-num{font-size:36px;font-weight:800}
.stat .stat-label{color:var(--muted);font-size:13px;margin-top:4px}
.stat.changed{box-shadow:inset 0 -3px 0 var(--changed)} .stat.changed .stat-num{color:var(--changed)}
.stat.added{box-shadow:inset 0 -3px 0 var(--added)} .stat.added .stat-num{color:var(--added)}
.stat.removed{box-shadow:inset 0 -3px 0 var(--removed)} .stat.removed .stat-num{color:var(--removed)}
.stat.unchanged{box-shadow:inset 0 -3px 0 var(--unchanged)} .stat.unchanged .stat-num{color:var(--muted)}
.delta{margin-top:10px;color:var(--muted);font-size:14px}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:10px;overflow:hidden}
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
.warn-note{color:var(--changed);font-size:13px;margin:0 0 14px;line-height:1.6;background:rgba(234,179,8,.10);border-left:4px solid var(--changed);border-radius:6px;padding:10px 14px}
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
.task{border:1px solid var(--line);border-radius:10px;margin:10px 0;overflow:hidden;background:var(--panel)}
.task.changed{border-left:4px solid var(--changed)}
.task.added{border-left:4px solid var(--added)}
.task.removed{border-left:4px solid var(--removed)}
.task.unchanged{border-left:4px solid var(--unchanged)}
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

  // scroll-spy for the nav
  var links = Array.prototype.slice.call(document.querySelectorAll('.tabs a'));
  var sections = links.map(function(a){ return document.querySelector(a.getAttribute('href')); });
  window.addEventListener('scroll', function(){
    var pos = window.scrollY + 120, idx = 0;
    for(var i=0;i<sections.length;i++){ if(sections[i] && sections[i].offsetTop <= pos) idx = i; }
    links.forEach(function(a,i){ a.classList.toggle('active', i===idx); });
  }, {passive:true});
})();
'''
}
