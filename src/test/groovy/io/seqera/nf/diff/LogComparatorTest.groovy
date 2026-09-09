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

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

class LogComparatorTest extends Specification {

    @TempDir
    Path tmp

    /** Create a task work dir with the given log files (name -> content). */
    private Path workdir(String name, Map<String,String> logs) {
        def dir = Files.createDirectories(tmp.resolve(name))
        Files.write(dir.resolve('.command.sh'), 'echo hi'.bytes)
        logs.each { fname, content -> Files.write(dir.resolve(fname), content.bytes) }
        return dir
    }

    private TaskInfo task(String workdir, String exit = '0', String status = 'COMPLETED') {
        return new TaskInfo(process: 'FOO', name: 'FOO (1)', workdir: workdir, exit: exit, status: status)
    }

    def 'flags a line-level change in stderr'() {
        given:
        def a = task(workdir('a', ['.command.err': 'line 1\nall good\n']).toString())
        def b = task(workdir('b', ['.command.err': 'line 1\nboom: failed\n']).toString())

        when:
        def ld = new LogComparator().compare(a, b)

        then:
        ld.availableA && ld.availableB
        ld.hasChanges()
        def f = ld.logs.find { it.name == '.command.err' }
        f.kind == DiffResult.Kind.CHANGED
        f.linesAdded() >= 1
        f.linesRemoved() >= 1
    }

    def 'treats identical logs as unchanged'() {
        given:
        def a = task(workdir('a', ['.command.out': 'same\n']).toString())
        def b = task(workdir('b', ['.command.out': 'same\n']).toString())

        when:
        def ld = new LogComparator().compare(a, b)

        then:
        !ld.hasChanges()
        ld.logs.find { it.name == '.command.out' }.kind == DiffResult.Kind.UNCHANGED
    }

    def 'classifies a log present only on one side as added or removed'() {
        given:
        def a = task(workdir('a', ['.command.out': 'hi']).toString())
        def b = task(workdir('b', ['.command.out': 'hi', '.command.err': 'oops']).toString())

        when:
        def ld = new LogComparator().compare(a, b)

        then:
        ld.hasChanges()
        ld.logs.find { it.name == '.command.err' }.kind == DiffResult.Kind.ADDED
    }

    def 'surfaces an exit-code change as a failure'() {
        given:
        def a = task(workdir('a', ['.command.err': 'ok']).toString(), '0', 'COMPLETED')
        def b = task(workdir('b', ['.command.err': 'segfault']).toString(), '1', 'FAILED')

        when:
        def ld = new LogComparator().compare(a, b)

        then:
        ld.exitChanged
        ld.statusChanged
        ld.failure
        ld.hasChanges()
    }

    def 'short-circuits when both tasks share the same work directory'() {
        given: 'a cached task in B points at the same work dir A produced'
        def dir = workdir('shared', ['.command.err': 'boom']).toString()

        when:
        def ld = new LogComparator().compare(task(dir), task(dir))

        then:
        ld.sameWorkdir
        ld.logs.isEmpty()
        !ld.hasChanges()
    }

    def 'reports a missing work directory as unavailable'() {
        given:
        def a = task(workdir('a', ['.command.err': 'x']).toString())
        def b = task(tmp.resolve('does-not-exist').toString())

        when:
        def ld = new LogComparator().compare(a, b)

        then:
        ld.availableA
        !ld.availableB
        !ld.hasChanges()
        ld.note?.contains('Run B')
    }

    def 'tails a log to the line cap and marks it truncated'() {
        given: 'logs longer than the cap, differing on the last line'
        def linesA = (1..10).collect { "line ${it}" }.join('\n')
        def linesB = (1..9).collect { "line ${it}" }.join('\n') + '\nline 10 changed'
        def a = task(workdir('a', ['.command.out': linesA]).toString())
        def b = task(workdir('b', ['.command.out': linesB]).toString())

        when: 'only the last 3 lines are kept'
        def ld = new LogComparator(3).compare(a, b)

        then:
        def f = ld.logs.find { it.name == '.command.out' }
        f.truncated
        f.kind == DiffResult.Kind.CHANGED
    }
}
