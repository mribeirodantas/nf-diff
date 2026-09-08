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
    Path baseDir = Paths.get('.')

    int run(List<String> args) {
        parse(args)

        log.info "nf-diff: comparing runs '${runA}' and '${runB}'"
        log.info "nf-diff: base directory = ${baseDir.toAbsolutePath()}"
        log.info "nf-diff: report output  = ${outputFile.toAbsolutePath()}"

        // Loading, comparison and rendering are wired in subsequent steps.
        System.out.println("nf-diff: comparing '${runA}' vs '${runB}' — report will be written to ${outputFile}")
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
            switch( arg ) {
                case '-o':
                case '--output':
                    if( i + 1 >= args.size() )
                        throw new UsageException("missing value for ${arg}")
                    outputFile = Paths.get(args[++i])
                    break
                case '-d':
                case '--dir':
                    if( i + 1 >= args.size() )
                        throw new UsageException("missing value for ${arg}")
                    baseDir = Paths.get(args[++i])
                    break
                default:
                    if( arg.startsWith('-') )
                        throw new UsageException("unknown option '${arg}'")
                    positional.add(arg)
            }
            i++
        }

        if( positional.size() != 2 )
            throw new UsageException("expected exactly two run identifiers, got ${positional.size()}")

        runA = positional[0]
        runB = positional[1]
    }

    static String usage() {
        return '''\
Usage: nextflow plugin nf-diff:diff <runA> <runB> [options]

  Compare two Nextflow runs and render a detailed HTML report of their
  differences (metadata, processes, and per-task resources/scripts).

Arguments:
  <runA> <runB>        Run names or session UUIDs from .nextflow/history

Options:
  -o, --output <file>  Output HTML report path (default: nf-diff-report.html)
  -d, --dir <dir>      Project directory containing .nextflow/ (default: .)
  -h, --help           Show this help

Examples:
  nextflow plugin nf-diff:diff tender_euler happy_curie
  nextflow plugin nf-diff:diff 3a8c1f2e 9f2b7d10 -o compare.html
'''
    }
}
