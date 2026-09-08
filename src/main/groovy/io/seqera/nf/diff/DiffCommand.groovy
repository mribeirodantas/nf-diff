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
    /** Include fields that always differ between runs (run name, work dir, timing, resources). */
    boolean verbose = false
    /** Report format: {@code html} (default) or {@code json}. */
    String format = 'html'
    /** When true, return a non-zero exit code if the runs are not identical. */
    boolean failOnChange = false
    /** When true, compare the two most recent runs from history (no positional args). */
    boolean last = false

    /** Exit code returned when {@link #failOnChange} is set and runs differ. */
    static final int EXIT_CHANGED = 3

    int run(List<String> args) {
        parse(args)

        final loader = new RunLoader(baseDir)

        if( last ) {
            final names = loader.lastRunNames(2)
            runA = names[0]
            runB = names[1]
            log.info "nf-diff: --last selected '${runA}' (A) and '${runB}' (B)"
        }

        log.info "nf-diff: comparing runs '${runA}' and '${runB}'"
        log.debug "nf-diff: base directory = ${baseDir.toAbsolutePath()}"
        log.debug "nf-diff: report output  = ${outputFile.toAbsolutePath()}"

        final snapA = loader.load(runA)
        final snapB = loader.load(runB)

        final diff = new RunComparator(verbose).compare(snapA, snapB)

        final content = (format == 'json')
                ? new JsonReportRenderer().render(diff)
                : new HtmlReportRenderer().render(diff)
        final out = outputFile.toAbsolutePath()
        if( out.parent != null )
            java.nio.file.Files.createDirectories(out.parent)
        java.nio.file.Files.write(out, content.getBytes('UTF-8'))

        System.out.println("""\
nf-diff: comparison complete
  Run A : ${snapA.label()}  (${snapA.tasks.size()} tasks)
  Run B : ${snapB.label()}  (${snapB.tasks.size()} tasks)
  Diff  : ${diff.tasksChanged} changed, ${diff.tasksAdded} only-in-B, ${diff.tasksRemoved} only-in-A, ${diff.tasksUnchanged} unchanged
  Mode  : ${verbose ? 'verbose (all fields, including always-changing ones)' : 'meaningful changes only (use --verbose for all fields)'}
  Report: ${out} (${format})""")

        if( failOnChange && !diff.identical ) {
            log.debug "nf-diff: runs differ and --fail-on-change is set; exiting ${EXIT_CHANGED}"
            return EXIT_CHANGED
        }
        return 0
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
                    failOnChange = true
                    break
                case '-l':
                case '--last':
                    last = true
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
                case '-v':
                case '--verbose':
                case '--all':
                    verbose = true
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
        if( format != 'html' && format != 'json' )
            throw new UsageException("unsupported format '${format}' (expected 'html' or 'json')")

        // When the format is JSON and the user did not pick an output path,
        // default to a .json file rather than the .html default.
        if( format == 'json' && !outputExplicit )
            outputFile = Paths.get('nf-diff-report.json')
    }

    static String usage() {
        return '''\
Usage: nextflow plugin nf-diff:diff <runA> <runB> [options]

  Compare two Nextflow runs and render a detailed HTML report of their
  differences (metadata, processes, and per-task resources/scripts).

Arguments:
  <runA> <runB>        Run names or session UUIDs from .nextflow/history.
                       Omit both when using --last.

Options:
  -l, --last           Compare the two most recent runs in history (A = the
                       older of the two, B = the most recent). Cannot be
                       combined with explicit run identifiers.
  --output=<file>      Output report path (default: nf-diff-report.<ext>,
                       where <ext> matches the chosen --format)
  --format=<fmt>       Report format: html (default) or json. JSON is
                       machine-readable for CI, PR bots and dashboards.
  --dir=<dir>          Project directory containing .nextflow/ (default: .)
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
  nextflow plugin nf-diff:diff --last --format=json --fail-on-change
  nextflow plugin nf-diff:diff runA runB --format=json --output=diff.json

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
