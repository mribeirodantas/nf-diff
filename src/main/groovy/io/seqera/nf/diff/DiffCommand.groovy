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
    /** When true, compare runs from history rather than explicit run identifiers. */
    boolean last = false
    /**
     * How many runs back from the latest to use as run A when {@link #last} is
     * set: {@code 1} (the bare {@code --last}) compares the two most recent
     * runs; {@code N} compares the N-th-most-recent-before-latest against the
     * latest.
     */
    int lastBack = 1
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
            final names = new RunLoader(dirA).lastPair(lastBack)
            runA = names[0]
            runB = names[1]
            log.info "nf-diff: --last=${lastBack} selected '${runA}' (A) and '${runB}' (B)"
        }

        log.info "nf-diff: comparing runs '${runA}' and '${runB}'"
        log.debug "nf-diff: run A directory = ${dirA.toAbsolutePath()}"
        log.debug "nf-diff: run B directory = ${dirB.toAbsolutePath()}"
        log.debug "nf-diff: report output  = ${outputFile.toAbsolutePath()}"

        final snapA = new RunLoader(dirA).load(runA)
        final snapB = new RunLoader(dirB).load(runB)

        final filter = ProcessFilter.of(onlyGlobs, excludeGlobs)
        final diff = new RunComparator(verbose, filter, baseDir, perfThreshold,
                        diffOutputs, outputsMaxBytes, diffLogs, logsMaxLines, outputsMaxLines,
                        dirA, dirB, diffDag)
                .compare(snapA, snapB)

        final content = renderContent(diff)

        // When writing to stdout ("-"), the report is the only thing on stdout
        // so it can be piped (e.g. `--format=json --output=- | jq`). The human
        // summary then goes to stderr to keep the piped stream clean.
        final destination = toStdout() ? '<stdout>' : outputFile.toAbsolutePath().toString()
        final summary = """\
nf-diff: comparison complete
  Run A : ${snapA.label()}  (${snapA.tasks.size()} tasks)
  Run B : ${snapB.label()}  (${snapB.tasks.size()} tasks)
  Diff  : ${diff.tasksChanged} changed, ${diff.tasksAdded} only-in-B, ${diff.tasksRemoved} only-in-A, ${diff.tasksUnchanged} unchanged
  Mode  : ${verbose ? 'verbose (all fields, including always-changing ones)' : 'meaningful changes only (use --verbose for all fields)'}
  Report: ${destination} (${format})"""

        if( toStdout() ) {
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
        int i = 0
        while( i < args.size() ) {
            final arg = args[i]
            // Support the inline `--opt=value` form as well as `--opt value`.
            // Nextflow's `plugin` launcher forwards `--output=<file>` reliably,
            // whereas a space-separated short flag like `-o <file>` is swallowed
            // by the launcher before it reaches the plugin.
            final eq = arg.indexOf('=')
            final key = (arg.startsWith('-') && eq > 0) ? arg.substring(0, eq) : arg
            final inlineVal = (arg.startsWith('-') && eq > 0) ? arg.substring(eq + 1) : null
            switch( key ) {
                case '-o':
                case '--output':
                    if( inlineVal != null ) {
                        outputFile = Paths.get(inlineVal)
                    }
                    else {
                        if( i + 1 >= args.size() )
                            throw new UsageException("missing value for ${key}")
                        outputFile = Paths.get(args[++i])
                    }
                    outputExplicit = true
                    break
                case '-f':
                case '--format':
                    final fmt = inlineVal
                    if( fmt != null ) {
                        format = fmt
                    }
                    else {
                        if( i + 1 >= args.size() )
                            throw new UsageException("missing value for ${key}")
                        format = args[++i]
                    }
                    break
                case '--fail-on-change':
                    failOnChange = boolFlag(inlineVal, args, i)
                    if( inlineVal == null && nextIsBool(args, i) ) i++
                    break
                case '-l':
                case '--last':
                    last = true
                    // Nextflow's `plugin` launcher rewrites forwarded args:
                    // bare `--last` arrives as `--last true`, and `--last=N`
                    // arrives space-separated as `--last N`. So resolve the
                    // value from the inline form OR the following token.
                    String lastVal = inlineVal
                    if( lastVal == null && i + 1 < args.size() ) {
                        final nxt = args[i + 1]
                        if( nxt == 'true' || nxt == 'false' ) {
                            // Injected boolean for the bare flag; not a value.
                            last = Boolean.parseBoolean(nxt)
                            i++
                        }
                        else if( nxt ==~ /\d+/ ) {
                            lastVal = nxt
                            i++
                        }
                    }
                    if( lastVal != null ) {
                        try {
                            lastBack = Integer.parseInt(lastVal)
                        }
                        catch( NumberFormatException ignored ) {
                            throw new UsageException("--last value must be an integer, got '${lastVal}'")
                        }
                        if( lastBack < 1 )
                            throw new UsageException("--last value must be >= 1, got ${lastBack}")
                    }
                    break
                case '--only':
                    onlyGlobs.addAll(splitGlobs(requireValue(key, inlineVal, args, i)))
                    if( inlineVal == null ) i++
                    break
                case '--exclude':
                    excludeGlobs.addAll(splitGlobs(requireValue(key, inlineVal, args, i)))
                    if( inlineVal == null ) i++
                    break
                case '--perf-threshold':
                    final pt = requireValue(key, inlineVal, args, i)
                    if( inlineVal == null ) i++
                    try {
                        perfThreshold = Double.parseDouble(pt)
                    }
                    catch( NumberFormatException ignored ) {
                        throw new UsageException("--perf-threshold must be a number (percent), got '${pt}'")
                    }
                    if( perfThreshold < 0 )
                        throw new UsageException("--perf-threshold must be >= 0, got ${perfThreshold}")
                    break
                case '--diff-outputs':
                    diffOutputs = boolFlag(inlineVal, args, i)
                    if( inlineVal == null && nextIsBool(args, i) ) i++
                    break
                case '--outputs-max-bytes':
                    final mb = requireValue(key, inlineVal, args, i)
                    if( inlineVal == null ) i++
                    try {
                        outputsMaxBytes = Long.parseLong(mb)
                    }
                    catch( NumberFormatException ignored ) {
                        throw new UsageException("--outputs-max-bytes must be an integer number of bytes, got '${mb}'")
                    }
                    if( outputsMaxBytes < 0 )
                        throw new UsageException("--outputs-max-bytes must be >= 0, got ${outputsMaxBytes}")
                    break
                case '--outputs-max-lines':
                    final oml = requireValue(key, inlineVal, args, i)
                    if( inlineVal == null ) i++
                    try {
                        outputsMaxLines = Integer.parseInt(oml)
                    }
                    catch( NumberFormatException ignored ) {
                        throw new UsageException("--outputs-max-lines must be an integer, got '${oml}'")
                    }
                    if( outputsMaxLines < 1 )
                        throw new UsageException("--outputs-max-lines must be >= 1, got ${outputsMaxLines}")
                    break
                case '--diff-logs':
                    diffLogs = boolFlag(inlineVal, args, i)
                    if( inlineVal == null && nextIsBool(args, i) ) i++
                    break
                case '--diff-dag':
                    diffDag = boolFlag(inlineVal, args, i)
                    if( inlineVal == null && nextIsBool(args, i) ) i++
                    break
                case '--diff-all':
                    // Convenience: the three opt-in work-dir layers share the
                    // same precondition (work dirs must still exist) and are
                    // commonly wanted together. Only enables — never force
                    // false — so a later explicit --diff-<layer>=false can still
                    // switch an individual layer back off.
                    if( boolFlag(inlineVal, args, i) ) {
                        diffOutputs = true
                        diffLogs = true
                        diffDag = true
                    }
                    if( inlineVal == null && nextIsBool(args, i) ) i++
                    break
                case '--logs-max-lines':
                    final ml = requireValue(key, inlineVal, args, i)
                    if( inlineVal == null ) i++
                    try {
                        logsMaxLines = Integer.parseInt(ml)
                    }
                    catch( NumberFormatException ignored ) {
                        throw new UsageException("--logs-max-lines must be an integer, got '${ml}'")
                    }
                    if( logsMaxLines < 1 )
                        throw new UsageException("--logs-max-lines must be >= 1, got ${logsMaxLines}")
                    break
                case '-d':
                case '--dir':
                    if( inlineVal != null ) {
                        baseDir = Paths.get(inlineVal)
                    }
                    else {
                        if( i + 1 >= args.size() )
                            throw new UsageException("missing value for ${key}")
                        baseDir = Paths.get(args[++i])
                    }
                    break
                case '--dir-a':
                    baseDirA = Paths.get(requireValue(key, inlineVal, args, i))
                    if( inlineVal == null ) i++
                    break
                case '--dir-b':
                    baseDirB = Paths.get(requireValue(key, inlineVal, args, i))
                    if( inlineVal == null ) i++
                    break
                case '-v':
                case '--verbose':
                case '--all':
                    verbose = boolFlag(inlineVal, args, i)
                    if( inlineVal == null && nextIsBool(args, i) ) i++
                    break
                default:
                    if( arg.startsWith('-') )
                        throw new UsageException("unknown option '${key}'")
                    positional.add(arg)
            }
            i++
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
     * Resolve a boolean flag's value. A bare flag is {@code true}; Nextflow's
     * `plugin` launcher forwards bare flags as `--flag true`, so an injected
     * {@code true}/{@code false} in the following token (or the inline
     * `--flag=true` form) is honoured.
     */
    private static boolean boolFlag(String inlineVal, List<String> args, int i) {
        if( inlineVal != null )
            return Boolean.parseBoolean(inlineVal)
        if( nextIsBool(args, i) )
            return Boolean.parseBoolean(args[i + 1])
        return true
    }

    /** True when the token after index {@code i} is a literal {@code true}/{@code false}. */
    private static boolean nextIsBool(List<String> args, int i) {
        return i + 1 < args.size() && (args[i + 1] == 'true' || args[i + 1] == 'false')
    }

    /** Resolve an option value from its inline (`--opt=val`) or next-arg form. */
    private static String requireValue(String key, String inlineVal, List<String> args, int i) {
        if( inlineVal != null )
            return inlineVal
        if( i + 1 >= args.size() )
            throw new UsageException("missing value for ${key}")
        return args[i + 1]
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
  -l, --last[=N]       Compare recent runs from history. Bare --last compares
                       the two most recent runs; --last=N compares the run N
                       positions before the latest (A) against the latest (B).
                       Cannot be combined with explicit run identifiers.
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
  -h, --help           Show this help

  Use the inline `--output=<file>` form (with `=`). Nextflow's `plugin`
  launcher swallows space-separated flags such as `-o compare.html`
  before they reach the plugin.

Examples:
  nextflow plugin nf-diff:diff tender_euler happy_curie
  nextflow plugin nf-diff:diff 3a8c1f2e 9f2b7d10 --output=compare.html
  nextflow plugin nf-diff:diff --last
  nextflow plugin nf-diff:diff --last=2
  nextflow plugin nf-diff:diff --last --format=json --fail-on-change
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
