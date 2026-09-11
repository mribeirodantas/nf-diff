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

/**
 * Unit tests for the static failure/exit-code helpers on {@link DiffResult}.
 * These read only cached trace fields, so they must treat the {@code NO_EXIT}
 * sentinel Nextflow writes for tasks that never produced an exit code as
 * "no failure", not as a huge non-zero code.
 */
class DiffResultTest extends Specification {

    def 'failedExit flags real non-zero codes but ignores the NO_EXIT sentinel'() {
        expect:
        DiffResult.failedExit('1')
        DiffResult.failedExit('137')
        and: 'a clean exit, blanks and non-numeric values are not failures'
        !DiffResult.failedExit('0')
        !DiffResult.failedExit('')
        !DiffResult.failedExit(null)
        !DiffResult.failedExit('-')
        and: 'the NO_EXIT sentinel (task never produced an exit code) is not a failure'
        !DiffResult.failedExit(String.valueOf(DiffResult.NO_EXIT))
    }

    def 'isTaskFailure uses status first, then the exit code'() {
        expect:
        DiffResult.isTaskFailure('FAILED', '0')
        DiffResult.isTaskFailure('ABORTED', String.valueOf(DiffResult.NO_EXIT))
        DiffResult.isTaskFailure('COMPLETED', '2')
        and:
        !DiffResult.isTaskFailure('COMPLETED', '0')
        !DiffResult.isTaskFailure('CACHED', String.valueOf(DiffResult.NO_EXIT))
    }

    def 'normalizeExit collapses the sentinel and blanks to a dash'() {
        expect:
        DiffResult.normalizeExit('0') == '0'
        DiffResult.normalizeExit('137') == '137'
        DiffResult.normalizeExit(String.valueOf(DiffResult.NO_EXIT)) == '-'
        DiffResult.normalizeExit('') == '-'
        DiffResult.normalizeExit(null) == '-'
    }
}
