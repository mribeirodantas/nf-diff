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

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit coverage of {@link TaskInfo}'s two decision helpers: {@code matchKey}
 * (how the "same" task is paired across two runs) and the {@code numeric} /
 * {@code numericDouble} coercions that feed regression detection and the
 * resource charts. Both drive comparison correctness, so their fallbacks are
 * pinned here.
 */
class TaskInfoTest extends Specification {

    def 'matchKey prefers the fully-qualified task name'() {
        expect:
        new TaskInfo(name: 'FOO (1)', process: 'FOO', tag: 'sampleA', hash: 'abc').matchKey() == 'FOO (1)'
    }

    def 'matchKey falls back to process + tag when the name is absent'() {
        expect:
        new TaskInfo(process: 'FOO', tag: 'sampleA', hash: 'abc').matchKey() == 'FOO (sampleA)'
    }

    def 'matchKey falls back to the process name when name and tag are absent'() {
        expect:
        new TaskInfo(process: 'FOO', hash: 'abc').matchKey() == 'FOO'
    }

    def 'matchKey falls back to the hash when nothing else identifies the task'() {
        expect:
        new TaskInfo(hash: 'abc').matchKey() == 'abc'
    }

    @Unroll
    def 'numeric coerces raw #value to #expected'() {
        expect:
        new TaskInfo(raw: ['m': value]).numeric('m') == expected

        where:
        value     || expected
        4000L     || 4000L
        42        || 42L
        3.9d      || 3L          // Number path truncates toward zero
        '1000'    || 1000L
        '-7'      || -7L
        'abc'     || null
        '1.5'     || null        // not an integer string
        null      || null
    }

    def 'numeric returns null for an absent field'() {
        expect:
        new TaskInfo(raw: [:]).numeric('missing') == null
        new TaskInfo().numeric('missing') == null
    }

    @Unroll
    def 'numericDouble coerces raw #value to #expected'() {
        expect:
        new TaskInfo(raw: ['m': value]).numericDouble('m') == expected

        where:
        value    || expected
        4000L    || 4000.0d
        1.5d     || 1.5d
        '2.5'    || 2.5d
        '10'     || 10.0d
        'nope'   || null
        null     || null
    }

    def 'numericDouble returns null for an absent field'() {
        expect:
        new TaskInfo(raw: [:]).numericDouble('missing') == null
    }
}
