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
 * Unit coverage of {@link RunSnapshot}'s computed accessors — the per-run
 * aggregates the metadata layer renders directly (task/cache/process counts,
 * total realtime, the status label vocabulary) and the {@code tasksByKey} index
 * the task-matching layer joins on.
 */
class RunSnapshotTest extends Specification {

    private static TaskInfo task(String process, String name, Map extra = [:]) {
        return new TaskInfo([process: process, name: name] + extra)
    }

    private static RunSnapshot snapshot(List<TaskInfo> tasks) {
        final s = new RunSnapshot()
        s.tasks = tasks
        return s
    }

    def 'taskCountByProcess groups by process name, sorted, with an unknown bucket'() {
        given:
        def s = snapshot([
                task('ALIGN', 'ALIGN (1)'),
                task('ALIGN', 'ALIGN (2)'),
                task('QC', 'QC (1)'),
                task(null, null) ])

        expect: 'sorted (TreeMap) counts, nameless tasks bucketed under (unknown)'
        s.taskCountByProcess() == ['(unknown)': 1, 'ALIGN': 2, 'QC': 1]
    }

    def 'processNames returns the distinct sorted process set'() {
        given:
        def s = snapshot([ task('QC', 'QC (1)'), task('ALIGN', 'ALIGN (1)'), task('ALIGN', 'ALIGN (2)') ])

        expect:
        s.processNames() == ['ALIGN', 'QC'] as Set
        s.processNames() instanceof SortedSet
    }

    def 'tasksByKey indexes each task by its matchKey'() {
        given:
        def a = task('ALIGN', 'ALIGN (1)')
        def q = task('QC', 'QC (1)')
        def s = snapshot([a, q])

        expect:
        s.tasksByKey().keySet() == ['ALIGN (1)', 'QC (1)'] as Set
        s.tasksByKey()['ALIGN (1)'].is(a)
    }

    def 'totalRealtimeMillis sums every task realtime'() {
        given:
        def s = snapshot([
                task('A', 'A (1)', [realtimeMillis: 1000L]),
                task('B', 'B (1)', [realtimeMillis: 2500L]),
                task('C', 'C (1)') ])  // defaults to 0

        expect:
        s.totalRealtimeMillis() == 3500L
    }

    def 'cachedCount counts only cached tasks'() {
        given:
        def s = snapshot([
                task('A', 'A (1)', [cached: true]),
                task('B', 'B (1)', [cached: false]),
                task('C', 'C (1)', [cached: true]) ])

        expect:
        s.cachedCount() == 2
    }

    def 'label combines run name and the short session id'() {
        given:
        def s = new RunSnapshot(runName: 'happy_curie',
                sessionId: UUID.fromString('12345678-90ab-cdef-1234-567890abcdef'))

        expect:
        s.label() == 'happy_curie (12345678)'
    }

    def 'label uses a placeholder id and the requested id when values are missing'() {
        given:
        def s = new RunSnapshot(requestedId: 'abc', runName: null, sessionId: null)

        expect:
        s.label() == 'abc (????????)'
    }

    @Unroll
    def 'statusLabel maps #raw to #expected'() {
        expect:
        RunSnapshot.statusLabel(raw) == expected

        where:
        raw         || expected
        'OK'        || 'SUCCEEDED'
        'ok'        || 'SUCCEEDED'
        'COMPLETED' || 'SUCCEEDED'
        'SUCCEEDED' || 'SUCCEEDED'
        'ERR'       || 'FAILED'
        'ERROR'     || 'FAILED'
        'FAILED'    || 'FAILED'
        'RUNNING'   || 'RUNNING'   // unknown vocabulary passes through, upper-cased
        null        || 'UNKNOWN'
    }

    def 'the instance statusLabel delegates to its own status'() {
        expect:
        new RunSnapshot(status: 'OK').statusLabel() == 'SUCCEEDED'
        new RunSnapshot(status: 'ERR').statusLabel() == 'FAILED'
    }
}
