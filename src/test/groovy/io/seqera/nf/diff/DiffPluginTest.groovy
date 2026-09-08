package io.seqera.nf.diff

import org.pf4j.PluginWrapper
import spock.lang.Specification

/**
 * Tests for {@link DiffPlugin#exec} exit-code contract. The plugin maps failures
 * to distinct codes — {@code 2} for usage errors (unknown verb, bad/missing
 * arguments) and {@code 1} for runtime failures — matching the documented
 * exit-code table. These tests pin that mapping without needing a run cache.
 */
class DiffPluginTest extends Specification {

    private DiffPlugin plugin() {
        return new DiffPlugin(Mock(PluginWrapper))
    }

    def 'unknown command returns the usage exit code (2)'() {
        expect:
        plugin().exec('nope', []) == 2
    }

    def 'missing run identifiers is a usage error (2)'() {
        expect:
        // 'diff' with no positional runs trips DiffCommand's UsageException,
        // which exec() maps to 2 — never touching the run cache.
        plugin().exec('diff', []) == 2
    }

    def 'help request is a usage exit (2)'() {
        expect:
        plugin().exec('diff', ['-h']) == 2
    }

    def 'getCommands advertises only the diff verb'() {
        expect:
        plugin().getCommands() == ['diff']
    }
}
