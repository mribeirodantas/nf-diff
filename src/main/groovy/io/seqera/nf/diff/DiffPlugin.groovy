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
        final code = dispatch(cmd, args)
        // The `nextflow plugin <id>:<cmd>` launcher (CmdPlugin) runs on
        // Nextflow's Launcher path, whose command `run()` is `void`: it invokes
        // this exec() but discards the returned int, so the process exits 0 no
        // matter what we return (verified on 26.04.1). That silently breaks the
        // documented CLI exit-code contract — 1 (runtime error), 2 (usage
        // error), 3 (--fail-on-change) — which is the entire point of
        // --fail-on-change in CI. So force the code ourselves for any non-zero
        // result. We still `return code` below so the value is correct for a
        // direct caller, or a future Nextflow line that does propagate it.
        if( code != 0 ) {
            // Flush first: System.exit() skips the trait's session teardown, and
            // we don't want a half-buffered report/summary lost on the way out.
            System.out.flush()
            System.err.flush()
            System.exit(code)
        }
        return code
    }

    /**
     * Dispatch the verb and map outcomes to the documented exit codes. Kept
     * package-visible (not {@code private}) so the exit-code mapping can be unit
     * tested without {@link #exec}'s {@code System.exit()} tearing down the test
     * JVM.
     */
    int dispatch(String cmd, List<String> args) {
        if( cmd != 'diff' ) {
            // An unknown verb is a usage error, not a runtime failure, so it
            // returns 2 (matching DiffCommand.UsageException handling below).
            System.err.println "nf-diff: unknown command '${cmd}' (only 'diff' is supported)"
            return 2
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
