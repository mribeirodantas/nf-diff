package io.seqera.nf.diff

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.cli.PluginAbstractExec
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

/**
 * nf-diff — a Nextflow plugin that adds a `diff` CLI verb to compare two
 * Nextflow runs and render a detailed, self-contained HTML report.
 *
 * Usage:
 * <pre>
 *   nextflow plugin nf-diff:diff &lt;runA&gt; &lt;runB&gt; [options]
 * </pre>
 *
 * where {@code runA}/{@code runB} are run names or session UUIDs found in the
 * local {@code .nextflow/history} file.
 *
 * @author Marcel Ribeiro-Dantas &lt;marcel@seqera.io&gt;
 */
@Slf4j
@CompileStatic
class DiffPlugin extends BasePlugin implements PluginAbstractExec {

    DiffPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }

    @Override
    List<String> getCommands() {
        return ['diff']
    }

    @Override
    int exec(String cmd, List<String> args) {
        if( cmd != 'diff' ) {
            System.err.println "nf-diff: unknown command '${cmd}'"
            return 1
        }
        try {
            return new DiffCommand().run(args)
        }
        catch( DiffCommand.UsageException e ) {
            System.err.println "nf-diff: ${e.message}\n"
            System.err.println DiffCommand.usage()
            return 2
        }
        catch( Throwable e ) {
            // Some throwables (e.g. NullPointerException) carry no message, so
            // fall back to the exception's simple class name — otherwise the
            // user sees a bare "nf-diff:" with nothing after it, and the stack
            // trace only goes to the debug-gated log.
            final detail = e.message ?: e.class.simpleName
            log.error("nf-diff: diff failed — ${detail}", e)
            System.err.println "nf-diff: ${detail}"
            return 1
        }
    }
}
