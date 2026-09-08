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
        renderMetadata(sb, diff)
        renderParams(sb, diff)
        renderConfig(sb, diff)
        renderProcesses(sb, diff)
        renderRegressions(sb, diff)
        renderTasks(sb, diff)
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
        sb << '  <a href="#metadata">Metadata</a>\n'
        sb << '  <a href="#params">Parameters</a>\n'
        sb << '  <a href="#config">Configuration</a>\n'
        sb << '  <a href="#processes">Processes</a>\n'
        sb << '  <a href="#regressions">Regressions</a>\n'
        sb << '  <a href="#tasks">Tasks</a>\n'
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
        sb << statCard('Regressions', diff.regressions.count { it.regression } as int, 'removed')
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
  --bg:#0b0f1a; --bg2:#131a2b; --panel:#171f33; --panel2:#1d2740;
  --txt:#e6ecff; --muted:#8b97b5; --line:#273250;
  /* Nextflow-inspired green as the brand accent; a complementary blue keeps
     Run A / Run B visually distinct without leaning on the cyan/purple defaults. */
  --brand:#0dc09d;
  --a:#0dc09d; --a2:#0a9d80; --b:#4f8cff; --b2:#2f6fe0;
  --added:#34d399; --removed:#f87171; --changed:#fbbf24; --unchanged:#64748b;
  /* theme-dependent extras */
  --chip-ink:#04120e;
  --tabs-bg:rgba(11,15,26,.85);
  --gap-stripe:rgba(255,255,255,.03);
  --body-glow:#132b28;
  --hero-glow-a:rgba(13,192,157,.24);
  --hero-glow-b:rgba(79,140,255,.20);
  --shadow:rgba(0,0,0,.35);
}
html[data-theme="light"]{
  --bg:#f5f7fb; --bg2:#e8edf6; --panel:#ffffff; --panel2:#eef2fa;
  --txt:#1a2236; --muted:#5a6785; --line:#d8e0ee;
  --brand:#0a9d80;
  --a:#0a9d80; --a2:#087a64; --b:#2563eb; --b2:#1d4ed8;
  --added:#059669; --removed:#dc2626; --changed:#b45309; --unchanged:#64748b;
  --chip-ink:#ffffff;
  --tabs-bg:rgba(245,247,251,.85);
  --gap-stripe:rgba(0,0,0,.05);
  --body-glow:#d5efe8;
  --hero-glow-a:rgba(13,192,157,.16);
  --hero-glow-b:rgba(37,99,235,.12);
  --shadow:rgba(30,50,90,.12);
}
*{box-sizing:border-box}
body{margin:0;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
  background:radial-gradient(1200px 800px at 20% -10%,var(--body-glow) 0,transparent 60%),linear-gradient(160deg,var(--bg),var(--bg2));
  background-attachment:fixed;
  color:var(--txt);line-height:1.5;transition:background-color .2s ease,color .2s ease}
.theme-toggle{position:absolute;top:18px;right:18px;z-index:20;display:inline-grid;place-items:center;
  width:40px;height:40px;border-radius:11px;cursor:pointer;font-size:18px;line-height:1;
  background:var(--panel);border:1px solid var(--line);color:var(--txt);
  box-shadow:0 4px 14px var(--shadow);transition:transform .12s ease,background .2s ease}
.theme-toggle:hover{transform:translateY(-1px);background:var(--panel2)}
.theme-toggle .ti-light{display:none}
html[data-theme="light"] .theme-toggle .ti-dark{display:none}
html[data-theme="light"] .theme-toggle .ti-light{display:inline}
.mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace}
.wrap{max-width:1100px;margin:0 auto;padding:24px}
.hero{position:relative;padding:48px 24px 28px;background:radial-gradient(900px 500px at 80% -20%,var(--hero-glow-b),transparent 55%),radial-gradient(700px 500px at 0% -10%,var(--hero-glow-a),transparent 55%);border-bottom:1px solid var(--line)}
.hero-inner{max-width:1100px;margin:0 auto}
.brand{display:flex;align-items:center;gap:10px;font-weight:700;letter-spacing:.5px;color:var(--muted)}
.logo{display:inline-grid;place-items:center;width:30px;height:30px;border-radius:9px;background:linear-gradient(135deg,var(--a),var(--b));color:var(--chip-ink);font-weight:900;font-size:18px}
.hero h1{margin:14px 0 20px;font-size:34px;font-weight:800}
.runs{display:flex;align-items:center;gap:18px;flex-wrap:wrap}
.run-chip{flex:1;min-width:260px;background:linear-gradient(180deg,var(--panel),var(--panel2));border:1px solid var(--line);border-radius:16px;padding:16px 18px;position:relative;overflow:hidden}
.run-chip::before{content:"";position:absolute;inset:0 auto 0 0;width:4px}
.run-a::before{background:var(--a)} .run-b::before{background:var(--b)}
.run-name{font-size:19px;font-weight:700}
.run-meta{display:flex;align-items:center;gap:10px;margin:6px 0}
.run-sub{color:var(--muted);font-size:13px}
.vs{font-weight:800;color:var(--muted);font-size:15px}
.verdict-wrap{margin-top:18px}
.verdict{display:inline-block;padding:8px 14px;border-radius:999px;font-weight:600;font-size:14px}
.verdict.same{background:rgba(52,211,153,.15);color:var(--added);border:1px solid rgba(52,211,153,.4)}
.verdict.diff{background:rgba(251,191,36,.15);color:var(--changed);border:1px solid rgba(251,191,36,.4)}
.tabs{position:sticky;top:0;z-index:10;display:flex;gap:6px;padding:10px 24px;background:var(--tabs-bg);backdrop-filter:blur(10px);border-bottom:1px solid var(--line)}
.tabs a{color:var(--muted);text-decoration:none;padding:8px 14px;border-radius:10px;font-weight:600;font-size:14px}
.tabs a:hover{color:var(--txt);background:var(--panel)}
.tabs a.active{color:var(--txt);background:var(--panel2)}
.section{margin:34px 0}
.section h2{font-size:22px;margin:0 0 16px}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:14px}
.card{background:linear-gradient(180deg,var(--panel),var(--panel2));border:1px solid var(--line);border-radius:16px;padding:18px}
.card.wide{margin-top:16px}
.card-title{color:var(--muted);font-size:13px;text-transform:uppercase;letter-spacing:.6px;margin-bottom:12px}
.stat{text-align:center;position:relative;overflow:hidden}
.stat .stat-num{font-size:38px;font-weight:800}
.stat .stat-label{color:var(--muted);font-size:13px;margin-top:4px}
.stat.changed{box-shadow:inset 0 -3px 0 var(--changed)} .stat.changed .stat-num{color:var(--changed)}
.stat.added{box-shadow:inset 0 -3px 0 var(--added)} .stat.added .stat-num{color:var(--added)}
.stat.removed{box-shadow:inset 0 -3px 0 var(--removed)} .stat.removed .stat-num{color:var(--removed)}
.stat.unchanged{box-shadow:inset 0 -3px 0 var(--unchanged)} .stat.unchanged .stat-num{color:var(--muted)}
.delta{margin-top:10px;color:var(--muted);font-size:14px}
table{width:100%;border-collapse:collapse;background:var(--panel);border:1px solid var(--line);border-radius:14px;overflow:hidden}
th,td{text-align:left;padding:10px 14px;border-bottom:1px solid var(--line);vertical-align:top;font-size:14px}
thead th{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.5px;background:var(--panel2)}
tbody tr:last-child th,tbody tr:last-child td{border-bottom:none}
table.kv th{width:180px;color:var(--muted);font-weight:600}
.row-changed{background:rgba(251,191,36,.10)}
.row-changed th{color:var(--changed)}
.row-obvious th{color:var(--muted)}
.tag-auto{display:inline-block;margin-left:6px;padding:1px 6px;border-radius:6px;font-size:10px;font-weight:700;text-transform:uppercase;letter-spacing:.4px;color:var(--muted);background:var(--panel2);border:1px solid var(--line);vertical-align:middle}
.src{display:inline-block;padding:1px 7px;border-radius:6px;font-size:10px;font-weight:700;letter-spacing:.3px;border:1px solid var(--line)}
.src-cli{color:var(--b);background:rgba(79,140,255,.14)}
.src-file{color:var(--brand);background:rgba(13,192,157,.14)}
.src-both{color:var(--changed);background:rgba(251,191,36,.16)}
.src-na{color:var(--muted)}
.mode-note{color:var(--muted);font-size:13px;margin:0 0 14px;line-height:1.5}
.mode-note code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--panel2);border:1px solid var(--line);border-radius:6px;padding:1px 6px;font-size:12px}
.row-added{background:rgba(52,211,153,.08)} .row-removed{background:rgba(248,113,113,.08)}
.pill{display:inline-block;padding:3px 10px;border-radius:999px;font-size:11px;font-weight:700;text-transform:uppercase;letter-spacing:.4px}
.pill.added{background:rgba(52,211,153,.18);color:var(--added)}
.pill.removed{background:rgba(248,113,113,.18);color:var(--removed)}
.pill.changed{background:rgba(251,191,36,.18);color:var(--changed)}
.pill.unchanged{background:rgba(100,116,139,.2);color:var(--muted)}
.pill.status-ok{background:rgba(52,211,153,.18);color:var(--added)}
.pill.status-err{background:rgba(248,113,113,.18);color:var(--removed)}
.pill.status-unknown{background:rgba(100,116,139,.2);color:var(--muted)}
.tasks-head{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:16px}
.filters label{color:var(--muted);font-size:14px;cursor:pointer;user-select:none}
.task{border:1px solid var(--line);border-radius:14px;margin:10px 0;overflow:hidden;background:var(--panel)}
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
.bar-fill.a{background:linear-gradient(90deg,var(--a),var(--a2))}
.bar-fill.b{background:linear-gradient(90deg,var(--b),var(--b2))}
.bar-val{min-width:80px;text-align:right;font-size:12px;color:var(--muted);font-family:ui-monospace,monospace}
.code-diff{display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:8px}
.code-col{background:var(--bg2);border:1px solid var(--line);border-radius:10px;overflow:hidden}
.code-col-hdr{padding:6px 12px;font-size:12px;color:var(--muted);background:var(--panel2);border-bottom:1px solid var(--line)}
.code-col pre{margin:0;padding:0;overflow:auto;font-family:ui-monospace,monospace;font-size:12.5px}
.cl{display:block;padding:1px 12px;white-space:pre-wrap;word-break:break-word;border-left:3px solid transparent}
.cl.del{background:rgba(248,113,113,.14);border-left-color:var(--removed)}
.cl.ins{background:rgba(52,211,153,.14);border-left-color:var(--added)}
.cl.gap{background:repeating-linear-gradient(45deg,transparent,transparent 6px,var(--gap-stripe) 6px,var(--gap-stripe) 12px);min-height:1.4em}
.foot{text-align:center;color:var(--muted);padding:30px;border-top:1px solid var(--line);font-size:13px}
.foot-sub{margin-top:6px;font-size:12px;color:var(--muted);opacity:.85}
.foot-sub strong{color:var(--brand)}
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
