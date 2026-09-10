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

import java.nio.file.Path
import java.nio.file.Paths

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Parses arguments for the `diff` verb and orchestrates loading, comparing and
 * rendering. Argument parsing and validation live here so the plugin entry
 * point ({@link DiffPlugin}) stays a thin dispatcher.
 */
@Slf4j
@CompileStatic
class DiffCommand {

    /** Thrown for bad/missing arguments; the caller prints {@link #usage()}. */
    static class UsageException extends RuntimeException {
        UsageException(String message) { super(message) }
    }

    String runA
    String runB
    Path outputFile = Paths.get('nf-diff-report.html')
    /** Whether {@link #outputFile} was set explicitly (vs. the format default). */
    private boolean outputExplicit = false
    Path baseDir = Paths.get('.')
    /**
     * Optional per-run project directory for run A. When null, run A is loaded
     * from (and its config/params resolved against) {@link #baseDir}. Set via
     * {@code --dir-a} to compare a run from one checkout/project against a run
     * from another.
     */
    Path baseDirA = null
    /** Optional per-run project directory for run B; falls back to {@link #baseDir}. */
    Path baseDirB = null
    /** Include fields that always differ between runs (run name, work dir, timing, resources). */
    boolean verbose = false
    /** Report format: {@code html} (default), {@code json} or {@code md}. */
    String format = 'html'
    /** When true, return a non-zero exit code if the runs are not identical. */
    boolean failOnChange = false
    /**
     * When true, print only the human-readable summary block and skip rendering
     * and writing the full report. Useful in CI logs where the full HTML/MD/JSON
     * is noise but the "N changed, …" line is the signal. The summary is
     * computed regardless, so this only suppresses the report body; exit-code
     * behaviour (including {@code --fail-on-change}) is unaffected.
     */
    boolean summaryOnly = false
    /** When true, compare runs from history rather than explicit run identifiers. */
    boolean last = false
    /**
     * How many runs back from the latest to use as run A (the older side) when
     * {@link #last} is set. {@code 1} (the bare {@code --last}) compares the two
     * most recent runs; a single {@code --last=N} compares the run N positions
     * before the latest against the latest ({@link #lastBackB} stays {@code 0}).
     */
    int lastBack = 1

    /**
     * How many runs back from the latest to use as run B (the newer side) when
     * {@link #last} is set. Defaults to {@code 0} (the latest run). Set only by
     * the explicit two-offset form {@code --last=A:B}, which lets callers name
     * an adjacent (or any) pair — e.g. {@code --last=2:1} compares the run two
     * back against the run one back — instead of always diffing against the
     * latest and silently skipping the runs in between.
     */
    int lastBackB = 0
    /** Process-name globs to include; empty means include everything. */
    List<String> onlyGlobs = []
    /** Process-name globs to exclude; applied after {@link #onlyGlobs}. */
    List<String> excludeGlobs = []
    /** Percentage change beyond which a task metric is flagged as a regression. */
    double perfThreshold = RunComparator.DEFAULT_PERF_THRESHOLD

    /** When true, compare the output files each matched task wrote to its work dir. */
    boolean diffOutputs = false

    /**
     * Maximum file size (bytes) to hash when comparing same-size outputs; 0
     * (the default) means no limit. Only used when {@link #diffOutputs} is set.
     */
    long outputsMaxBytes = 0L

    /**
     * Maximum leading lines kept per side when line-diffing a changed text
     * output file under {@link #diffOutputs}. Defaults to
     * {@link OutputComparator#DEFAULT_MAX_LINES}.
     */
    int outputsMaxLines = OutputComparator.DEFAULT_MAX_LINES

    /** When true, compare the standard log files each matched task wrote. */
    boolean diffLogs = false

    /**
     * When true, reconstruct each run's process&#8594;process wiring from its
     * task work-dir input symlinks and diff the two. Like {@link #diffOutputs}
     * and {@link #diffLogs}, this needs the work directories to still exist
     * locally.
     */
    boolean diffDag = false

    /**
     * Maximum tail lines kept per log file when {@link #diffLogs} is set.
     * Defaults to {@link LogComparator#DEFAULT_MAX_LINES}.
     */
    int logsMaxLines = LogComparator.DEFAULT_MAX_LINES

    /** Exit code returned when {@link #failOnChange} is set and runs differ. */
    static final int EXIT_CHANGED = 3

    int run(List<String> args) {
        parse(args)

        // Effective per-run project directories: each defaults to the shared
        // --dir, but --dir-a/--dir-b can point the two runs at different
        // checkouts (compare "same pipeline, two projects").
        final dirA = baseDirA ?: baseDir
        final dirB = baseDirB ?: baseDir
        final crossProject = dirA.toAbsolutePath().normalize() != dirB.toAbsolutePath().normalize()

        if( last ) {
            if( crossProject )
                throw new UsageException('--last compares two runs from a single history, so it cannot be ' +
                        'combined with differing --dir-a/--dir-b; pass explicit run identifiers instead')
            final names = new RunLoader(dirA).lastPair(lastBack, lastBackB)
            runA = names[0]
            runB = names[1]
            // Spell out the offsets so the selection is unmistakable: run A is
            // `lastBack` runs back, run B is `lastBackB` back (0 = latest).
            log.info "nf-diff: --last selected A='${runA}' (${lastBack} run(s) before latest) " +
                    "and B='${runB}' (${lastBackB == 0 ? 'latest' : "${lastBackB} run(s) before latest"})"
        }

        log.info "nf-diff: comparing runs '${runA}' and '${runB}'"
        log.debug "nf-diff: run A directory = ${dirA.toAbsolutePath()}"
        log.debug "nf-diff: run B directory = ${dirB.toAbsolutePath()}"
        log.debug "nf-diff: report output  = ${outputFile.toAbsolutePath()}"

        final snapA = new RunLoader(dirA).load(runA)
        final snapB = new RunLoader(dirB).load(runB)

        final filter = ProcessFilter.of(onlyGlobs, excludeGlobs)
        // Set each knob by name (property assignment compiles cleanly under
        // @CompileStatic) so the comparator can never be handed a transposed
        // pair of same-typed arguments.
        final opts = new CompareOptions()
        opts.showObvious = verbose
        opts.filter = filter
        opts.baseDir = baseDir
        opts.baseDirA = dirA
        opts.baseDirB = dirB
        opts.perfThreshold = perfThreshold
        opts.diffOutputs = diffOutputs
        opts.outputsMaxBytes = outputsMaxBytes
        opts.outputsMaxLines = outputsMaxLines
        opts.diffLogs = diffLogs
        opts.logsMaxLines = logsMaxLines
        opts.diffDag = diffDag
        final diff = new RunComparator(opts).compare(snapA, snapB)

        // --summary-only suppresses the report body entirely, so there is no
        // point rendering it; the summary is computed either way.
        final content = summaryOnly ? null : renderContent(diff)

        // When writing to stdout ("-"), the report is the only thing on stdout
        // so it can be piped (e.g. `--format=json --output=- | jq`). The human
        // summary then goes to stderr to keep the piped stream clean.
        final destination = toStdout() ? '<stdout>' : outputFile.toAbsolutePath().toString()
        final reportLine = summaryOnly
                ? '  Report: (suppressed by --summary-only)'
                : "  Report: ${destination} (${format})"
        final summary = """\
nf-diff: comparison complete
  Run A : ${snapA.label()}  (${snapA.tasks.size()} tasks)
  Run B : ${snapB.label()}  (${snapB.tasks.size()} tasks)
  Diff  : ${diff.tasksChanged} changed, ${diff.tasksAdded} only-in-B, ${diff.tasksRemoved} only-in-A, ${diff.tasksUnchanged} unchanged
  Mode  : ${verbose ? 'verbose (all fields, including always-changing ones)' : 'meaningful changes only (use --verbose for all fields)'}
${reportLine}"""

        if( summaryOnly ) {
            // No report body is produced; the summary is the whole output and
            // goes to stdout regardless of --output.
            System.out.println(summary)
        }
        else if( toStdout() ) {
            System.out.print(content)
            System.out.flush()
            System.err.println(summary)
        }
        else {
            final out = outputFile.toAbsolutePath()
            if( out.parent != null )
                java.nio.file.Files.createDirectories(out.parent)
            java.nio.file.Files.write(out, content.getBytes('UTF-8'))
            System.out.println(summary)
        }

        if( failOnChange && !diff.identical ) {
            log.debug "nf-diff: runs differ and --fail-on-change is set; exiting ${EXIT_CHANGED}"
            return EXIT_CHANGED
        }
        return 0
    }

    /** Render the diff into the requested output format. */
    private String renderContent(DiffResult diff) {
        switch( format ) {
            case 'json':
                return new JsonReportRenderer().render(diff)
            case 'md':
                return new MarkdownReportRenderer().render(diff)
            default:
                return new HtmlReportRenderer().render(diff)
        }
    }

    /** True when the report should be written to stdout ({@code --output=-}). */
    protected boolean toStdout() {
        return outputFile.toString() == '-'
    }

    /**
     * Parse positional run identifiers and options.
     * Positional: &lt;runA&gt; &lt;runB&gt;
     * Options: -o/--output &lt;file&gt;, -d/--dir &lt;dir&gt;, -h/--help
     */
    protected void parse(List<String> args) {
        if( !args || args.contains('-h') || args.contains('--help') )
            throw new UsageException('show help')

        final positional = new ArrayList<String>()
        // The cursor owns position tracking, inline `--key=value` splitting, and
        // value/bool/numeric extraction, so each option case is a single
        // assignment instead of the old parse + range-check + `if( inlineVal ==
        // null ) i++` boilerplate. Nextflow's `plugin` launcher forwards
        // `--key=value` reliably but rewrites bare flags to `--flag true` and
        // `--key=value` to `--key value`, so the cursor honours both forms.
        final cur = new ArgCursor(args)
        while( cur.hasNext() ) {
            final arg = cur.next()
            switch( cur.key ) {
                case '-o':
                case '--output':
                    outputFile = Paths.get(cur.requireValue())
                    outputExplicit = true
                    break
                case '-f':
                case '--format':
                    format = cur.requireValue()
                    break
                case '--fail-on-change':
                    failOnChange = cur.boolValue()
                    break
                case '-l':
                case '--last':
                    last = true
                    // Bare `--last` arrives as `--last true`; `--last=N` arrives
                    // space-separated as `--last N`. So resolve the value from
                    // the inline form OR the following token.
                    String lastVal = cur.inlineVal
                    if( lastVal == null ) {
                        final nxt = cur.peek()
                        if( nxt == 'true' || nxt == 'false' ) {
                            // Injected boolean for the bare flag; not a value.
                            last = Boolean.parseBoolean(nxt)
                            cur.consumePeeked()
                        }
                        // A single offset (`--last N`) or the explicit two-offset
                        // pair (`--last A:B`); anything else is not a --last value.
                        else if( nxt != null && nxt ==~ /\d+(:\d+)?/ ) {
                            lastVal = nxt
                            cur.consumePeeked()
                        }
                    }
                    if( lastVal != null )
                        parseLastValue(lastVal)
                    break
                case '--only':
                    onlyGlobs.addAll(splitGlobs(cur.requireValue()))
                    break
                case '--exclude':
                    excludeGlobs.addAll(splitGlobs(cur.requireValue()))
                    break
                case '--perf-threshold':
                    perfThreshold = cur.doubleValue(0.0d, 'a number (percent)')
                    break
                case '--diff-outputs':
                    diffOutputs = cur.boolValue()
                    break
                case '--outputs-max-bytes':
                    outputsMaxBytes = cur.longValue(0L, 'an integer number of bytes')
                    break
                case '--outputs-max-lines':
                    outputsMaxLines = cur.intValue(1, 'an integer')
                    break
                case '--diff-logs':
                    diffLogs = cur.boolValue()
                    break
                case '--diff-dag':
                    diffDag = cur.boolValue()
                    break
                case '--diff-all':
                    // Convenience: the three opt-in work-dir layers share the
                    // same precondition (work dirs must still exist) and are
                    // commonly wanted together. Only enables — never force
                    // false — so a later explicit --diff-<layer>=false can still
                    // switch an individual layer back off.
                    if( cur.boolValue() ) {
                        diffOutputs = true
                        diffLogs = true
                        diffDag = true
                    }
                    break
                case '--logs-max-lines':
                    logsMaxLines = cur.intValue(1, 'an integer')
                    break
                case '-d':
                case '--dir':
                    baseDir = Paths.get(cur.requireValue())
                    break
                case '--dir-a':
                    baseDirA = Paths.get(cur.requireValue())
                    break
                case '--dir-b':
                    baseDirB = Paths.get(cur.requireValue())
                    break
                case '-v':
                case '--verbose':
                case '--all':
                    verbose = cur.boolValue()
                    break
                case '-q':
                case '--quiet':
                case '--summary-only':
                    summaryOnly = cur.boolValue()
                    break
                default:
                    if( arg.startsWith('-') )
                        throw new UsageException("unknown option '${cur.key}'")
                    positional.add(arg)
            }
            cur.advance()
        }

        if( last ) {
            if( positional.size() != 0 )
                throw new UsageException("--last cannot be combined with explicit run identifiers (got ${positional.size()})")
            // runA/runB are resolved from history in run().
        }
        else {
            if( positional.size() != 2 )
                throw new UsageException("expected exactly two run identifiers, got ${positional.size()} (or use --last)")
            runA = positional[0]
            runB = positional[1]
        }

        format = format.toLowerCase()
        if( format == 'markdown' )
            format = 'md'
        if( !FORMAT_EXTENSIONS.containsKey(format) )
            throw new UsageException("unsupported format '${format}' (expected 'html', 'json' or 'md')")

        // When the user did not pick an output path, default the file extension
        // to match the chosen format rather than the .html default.
        if( !outputExplicit )
            outputFile = Paths.get("nf-diff-report.${FORMAT_EXTENSIONS[format]}")
    }

    /** Default output file extension per report format. */
    private static final Map<String,String> FORMAT_EXTENSIONS = [
            html: 'html', json: 'json', md: 'md',
    ]

    /**
     * A cursor over the raw argument list that drives {@link #parse}. Beyond
     * tracking the position it centralises the value extraction each option
     * case used to hand-roll: splitting an inline {@code --key=value}, and —
     * for the space-separated {@code --key value} form — transparently
     * consuming the following token. This removes the repeated
     * {@code if( inlineVal == null ) i++} bookkeeping (and its off-by-one
     * hazard) plus the duplicated parse/range-check blocks from every numeric
     * option, so each option case shrinks to a single assignment.
     *
     * <p>Package-visible (rather than {@code private}) only so {@code
     * ArgCursorTest} can pin its contract directly, independent of the
     * {@link #parse} wiring.
     */
    @CompileStatic
    static class ArgCursor {
        private final List<String> args
        /** Current index into {@link #args}. */
        int i = 0
        /** Option key at the current position, e.g. {@code --format} or {@code -o}. */
        String key
        /** Inline value from a {@code --key=value} token, or {@code null}. */
        String inlineVal

        ArgCursor(List<String> args) { this.args = args }

        /** True while there is an unconsumed token. */
        boolean hasNext() { return i < args.size() }

        /**
         * Load the token at the cursor, splitting a leading {@code --key=value}
         * into {@link #key}/{@link #inlineVal}. A bare value (no leading dash)
         * or a lone {@code -} keeps {@code inlineVal == null}. Returns the raw
         * token so the caller can treat it as a positional argument.
         */
        String next() {
            final arg = args[i]
            final eq = arg.indexOf('=')
            final hasInline = arg.startsWith('-') && eq > 0
            key = hasInline ? arg.substring(0, eq) : arg
            inlineVal = hasInline ? arg.substring(eq + 1) : null
            return arg
        }

        /** Advance past the current token (and any value token it consumed). */
        void advance() { i++ }

        /** The token following the cursor, or {@code null} if there is none. */
        String peek() { return i + 1 < args.size() ? args[i + 1] : null }

        /** Consume the token previously inspected via {@link #peek}. */
        void consumePeeked() { i++ }

        /**
         * The value for an option that requires one: the inline
         * {@code --key=value}, else the following token (which is consumed).
         * Throws {@link UsageException} when neither is present.
         */
        String requireValue() {
            if( inlineVal != null )
                return inlineVal
            if( i + 1 >= args.size() )
                throw new UsageException("missing value for ${key}")
            return args[++i]
        }

        /**
         * Resolve a boolean flag. A bare flag is {@code true}; the Nextflow
         * `plugin` launcher forwards bare flags as {@code --flag true}, so an
         * injected {@code true}/{@code false} in the following token (or the
         * inline {@code --flag=true} form) is honoured and consumed.
         */
        boolean boolValue() {
            if( inlineVal != null )
                return Boolean.parseBoolean(inlineVal)
            if( i + 1 < args.size() && (args[i + 1] == 'true' || args[i + 1] == 'false') )
                return Boolean.parseBoolean(args[++i])
            return true
        }

        /** Parse a required int-valued option, enforcing a {@code >= min} floor. */
        int intValue(int min, String typeDesc) {
            final raw = requireValue()
            int v
            try {
                v = Integer.parseInt(raw)
            }
            catch( NumberFormatException ignored ) {
                throw new UsageException("${key} must be ${typeDesc}, got '${raw}'")
            }
            if( v < min )
                throw new UsageException("${key} must be >= ${min}, got ${v}")
            return v
        }

        /** Parse a required long-valued option, enforcing a {@code >= min} floor. */
        long longValue(long min, String typeDesc) {
            final raw = requireValue()
            long v
            try {
                v = Long.parseLong(raw)
            }
            catch( NumberFormatException ignored ) {
                throw new UsageException("${key} must be ${typeDesc}, got '${raw}'")
            }
            if( v < min )
                throw new UsageException("${key} must be >= ${min}, got ${v}")
            return v
        }

        /** Parse a required double-valued option, enforcing a {@code >= min} floor. */
        double doubleValue(double min, String typeDesc) {
            final raw = requireValue()
            double v
            try {
                v = Double.parseDouble(raw)
            }
            catch( NumberFormatException ignored ) {
                throw new UsageException("${key} must be ${typeDesc}, got '${raw}'")
            }
            if( v < min )
                throw new UsageException("${key} must be >= ${min}, got ${v}")
            return v
        }
    }

    /**
     * Parse the value of {@code --last}. Two forms are accepted:
     * <ul>
     *   <li>a single offset {@code N} (>= 1) — compare the run N positions
     *       before the latest (A) against the latest (B), the historic
     *       behaviour; and</li>
     *   <li>an explicit pair {@code A:B} — compare the run {@code A} positions
     *       before the latest against the run {@code B} positions before the
     *       latest ({@code 0} = latest), requiring {@code A > B >= 0}. This lets
     *       callers name an adjacent pair (e.g. {@code 2:1}) instead of always
     *       diffing against the latest and silently skipping the runs between.</li>
     * </ul>
     */
    private void parseLastValue(String value) {
        if( value.contains(':') ) {
            final parts = value.split(':', -1)
            if( parts.length != 2 )
                throw new UsageException("--last pair must be A:B, got '${value}'")
            def a = 0
            def b = 0
            try {
                a = Integer.parseInt(parts[0])
                b = Integer.parseInt(parts[1])
            }
            catch( NumberFormatException ignored ) {
                throw new UsageException("--last pair offsets must be integers, got '${value}'")
            }
            if( b < 0 )
                throw new UsageException("--last pair offset B must be >= 0, got ${b}")
            if( a <= b )
                throw new UsageException("--last pair must have A > B (A is the older run), got ${a}:${b}")
            lastBack = a
            lastBackB = b
            return
        }
        try {
            lastBack = Integer.parseInt(value)
        }
        catch( NumberFormatException ignored ) {
            throw new UsageException("--last value must be an integer, got '${value}'")
        }
        if( lastBack < 1 )
            throw new UsageException("--last value must be >= 1, got ${lastBack}")
        lastBackB = 0
    }

    /** Split a comma-separated glob list into trimmed, non-empty entries. */
    private static List<String> splitGlobs(String value) {
        return value.split(',').collect { String g -> g.trim() }.findAll { String g -> g }
    }

    static String usage() {
        return '''\
Usage: nextflow plugin nf-diff:diff <runA> <runB> [options]

  Compare two Nextflow runs and render a detailed report of their
  differences: run metadata, parameters & options, resolved configuration,
  process topology, software & versions, per-task detail, failure rollup,
  performance regressions and resource-efficiency. Opt into output-file
  (--diff-outputs), log (--diff-logs) and process-wiring (--diff-dag) layers.

Arguments:
  <runA> <runB>        Run names or session UUIDs from .nextflow/history.
                       Omit both when using --last.

Options:
  -l, --last[=N|A:B]   Compare recent runs from history. Bare --last compares
                       the two most recent runs. --last=N compares the run N
                       positions before the latest (A) against the latest (B) —
                       note this SKIPS the runs in between. Use the explicit
                       --last=A:B form to name an exact pair by their offsets
                       back from the latest (0 = latest, A > B >= 0); e.g.
                       --last=2:1 compares the run two back against the run one
                       back. Cannot be combined with explicit run identifiers.
  --output=<file>      Output report path (default: nf-diff-report.<ext>,
                       where <ext> matches the chosen --format). Use "-" to
                       write the report to stdout (the summary then goes to
                       stderr), e.g. `--format=json --output=- | jq`.
  --format=<fmt>       Report format: html (default), json, or md (markdown).
                       JSON is machine-readable for CI/dashboards; Markdown
                       is handy for pull-request comments.
  --only=<globs>       Comma-separated process-name globs; only matching
                       processes/tasks are compared. `*` (spans `:` scopes)
                       and `?` are supported.
  --exclude=<globs>    Comma-separated process-name globs to drop from the
                       comparison; applied after --only.
  --perf-threshold=<n> Percentage change (default: 25) in a task metric
                       (realtime, peak_rss, peak_vmem) beyond which it is
                       flagged in the performance-regressions layer.
  --diff-outputs       Compare the output files each matched task wrote to its
                       work directory (by size, then SHA-256 for same-size
                       files). Changed text files (VCF, CSV, JSON, reports, …)
                       are additionally diffed line by line so you can see what
                       changed, not just that it changed; binary files show a
                       size/hash change only. Needs the tasks' work directories
                       to still exist locally. When enabled, an output-file
                       change counts as a difference for --fail-on-change.
  --outputs-max-bytes=<n>
                       When --diff-outputs is set, skip hashing same-size files
                       larger than <n> bytes (they are reported as content-
                       unverified). Default 0 = no limit (hash any size).
  --outputs-max-lines=<n>
                       When --diff-outputs is set, keep only the first <n> lines
                       of each changed text file before line-diffing it
                       (default: 1000).
  --diff-logs          Compare the standard log files (.command.out/.err/.log)
                       each matched task wrote to its work directory, line by
                       line. Ideal for inspecting why a task's exit code changed.
                       Needs the tasks' work directories to still exist locally.
                       This layer is informational only: it never affects
                       --fail-on-change (the exit-code change already does).
  --logs-max-lines=<n> When --diff-logs is set, keep only the last <n> lines of
                       each log file before diffing (default: 200).
  --diff-dag           Reconstruct each run's process->process wiring from its
                       task work-dir input symlinks and diff the two, so a
                       change like A->C becoming A->B->C is surfaced (the
                       process layer only counts tasks per process). Needs the
                       tasks' work directories to still exist locally. Best
                       effort and informational only: incomplete when work dirs
                       were cleaned up, so it never affects --fail-on-change.
  --diff-all           Enable all three work-dir layers at once
                       (--diff-outputs, --diff-logs, --diff-dag); they share the
                       same precondition (work dirs must still exist). Enables
                       only, so a later --diff-<layer>=false still opts one out.
  --dir=<dir>          Project directory containing .nextflow/ (default: .).
                       Used for both runs unless overridden per-run below.
  --dir-a=<dir>        Project directory for run A only (its .nextflow/ history,
  --dir-b=<dir>        cache, config and params). Use --dir-a/--dir-b to compare
                       a run from one project/checkout against a run from
                       another ("same pipeline, two directories"). Each falls
                       back to --dir when omitted. Cannot be combined with
                       --last, which needs a single history.
  -v, --verbose, --all Also diff fields that always change between runs
                       (run name, session id, launch time, work dir, wall/real
                       time, and resource usage). By default these are shown for
                       context but not flagged as changes.
  --fail-on-change     Exit with a non-zero status (3) when the runs are not
                       identical. Useful for gating CI on unexpected changes.
  -q, --quiet, --summary-only
                       Print only the summary block (the "N changed, …" line);
                       skip rendering and writing the full report. Handy for CI
                       logs where the report body is noise. Exit-code behaviour
                       (including --fail-on-change) is unaffected.
  -h, --help           Show this help

  Use the inline `--output=<file>` form (with `=`). Nextflow's `plugin`
  launcher swallows space-separated flags such as `-o compare.html`
  before they reach the plugin.

Exit codes:
  0  Success: the runs were compared (identical, or differences not gated).
  1  Runtime error (e.g. a run id not found, unreadable history/cache).
  2  Usage error (bad option, wrong number of run identifiers, or --help).
  3  Runs differ and --fail-on-change was set.

Examples:
  nextflow plugin nf-diff:diff tender_euler happy_curie
  nextflow plugin nf-diff:diff 3a8c1f2e 9f2b7d10 --output=compare.html
  nextflow plugin nf-diff:diff --last
  nextflow plugin nf-diff:diff --last=2
  nextflow plugin nf-diff:diff --last=2:1
  nextflow plugin nf-diff:diff --last --format=json --fail-on-change
  nextflow plugin nf-diff:diff --last --summary-only --fail-on-change
  nextflow plugin nf-diff:diff --last --format=md --output=diff.md
  nextflow plugin nf-diff:diff --last --only='ALIGN:*' --exclude='*:INDEX'
  nextflow plugin nf-diff:diff --last --diff-outputs --fail-on-change
  nextflow plugin nf-diff:diff --last --diff-logs
  nextflow plugin nf-diff:diff --last --diff-dag
  nextflow plugin nf-diff:diff --last --diff-all --output=compare.html
  nextflow plugin nf-diff:diff runA runB --format=json --output=diff.json
  nextflow plugin nf-diff:diff --last --format=json --output=- | jq .summary

Note:
  Invoke the plugin verb with the BARE id (nf-diff:diff), not a pinned
  version. Nextflow's `plugin` command resolves the plugin instance by
  its bare id, so `nf-diff@<version>:diff` starts the plugin but then
  fails with "Cannot find target plugin: nf-diff@<version>".

  For an unpublished (locally built) plugin, the bare id needs a source
  that advertises its version. Point Nextflow at the local test repo
  produced by `make dev-repo`, then run the verb:

    make dev-repo
    export NXF_PLUGINS_TEST_REPOSITORY="file://$PWD/build/plugin-repo/plugins.json"
    nextflow plugin nf-diff:diff <runA> <runB>

  Once nf-diff is published to the Nextflow plugin registry, the
  NXF_PLUGINS_TEST_REPOSITORY step is no longer needed.
'''
    }
}
