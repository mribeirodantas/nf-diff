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

import org.pf4j.PluginWrapper
import spock.lang.Specification

/**
 * Tests for the {@link DiffPlugin} exit-code contract. The plugin maps failures
 * to distinct codes — {@code 2} for usage errors (unknown verb, bad/missing
 * arguments) and {@code 1} for runtime failures — matching the documented
 * exit-code table. These tests pin that mapping without needing a run cache.
 *
 * They exercise {@link DiffPlugin#dispatch} rather than {@link DiffPlugin#exec}:
 * exec() is the launcher entry point and now calls {@code System.exit(code)} for
 * any non-zero result (the `nextflow plugin` launcher discards exec()'s return
 * value, so forcing the code is the only way the documented 1/2/3 reach the
 * shell). Calling exec() here would therefore kill the test JVM; dispatch() holds
 * the same mapping logic minus that terminal side effect.
 */
class DiffPluginTest extends Specification {

    private DiffPlugin plugin() {
        return new DiffPlugin(Mock(PluginWrapper))
    }

    def 'unknown command returns the usage exit code (2)'() {
        expect:
        plugin().dispatch('nope', []) == 2
    }

    def 'missing run identifiers is a usage error (2)'() {
        expect:
        // 'diff' with no positional runs trips DiffCommand's UsageException,
        // which dispatch() maps to 2 — never touching the run cache.
        plugin().dispatch('diff', []) == 2
    }

    def 'help request is a usage exit (2)'() {
        expect:
        plugin().dispatch('diff', ['-h']) == 2
    }

    def 'getCommands advertises only the diff verb'() {
        expect:
        plugin().getCommands() == ['diff']
    }
}
