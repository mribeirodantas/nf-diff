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

import io.seqera.nf.diff.DiffResult.Kind
import spock.lang.Specification
import spock.lang.TempDir

class InputComparatorTest extends Specification {

    @TempDir
    Path tmp

    private Path write(String rel, String content) {
        def f = tmp.resolve(rel)
        Files.createDirectories(f.parent)
        Files.write(f, content.bytes)
        return f
    }

    /** A task work dir with the given staged inputs (relative link path -> target). */
    private Path workdir(String name, Map<String,Path> links) {
        def dir = Files.createDirectories(tmp.resolve('work').resolve(name))
        Files.write(dir.resolve('.command.sh'), 'cat *'.bytes)
        Files.write(dir.resolve('out.txt'), "output of ${name}".toString().bytes)   // an output, not an input
        links.each { rel, target ->
            def link = dir.resolve(rel)
            Files.createDirectories(link.parent)
            Files.createSymbolicLink(link, target)
        }
        return dir
    }

    private static TaskInfo task(Path dir) {
        new TaskInfo(process: 'ALIGN', name: 'ALIGN (s1)', workdir: dir.toString())
    }

    private DiffResult.OutputDiff compare(Path a, Path b) {
        new InputComparator().compare(task(a), task(b))
    }

    def 'an input both runs staged from the same file is unchanged without being read'() {
        given:
        def ref = write('ref/genome.fa', '>chr1\nACGT\n')
        def od = compare(workdir('a', ['genome.fa': ref]), workdir('b', ['genome.fa': ref]))

        expect: 'only staged inputs are listed, never outputs or control files'
        od.files*.path == ['genome.fa']
        od.files[0].kind == Kind.UNCHANGED
        od.files[0].note == 'both runs staged the same file'
        od.files[0].hashA == null
        !od.hasChanges()
    }

    def 'inputs from different files are compared by content and line-diffed'() {
        given: 'each run staged its own upstream output'
        def upA = write('up/a/reads.tsv', 'r1\t10\nr2\t20\n')
        def upB = write('up/b/reads.tsv', 'r1\t10\nr2\t99\n')
        def same1 = write('up/a/meta.txt', 'same')
        def same2 = write('up/b/meta.txt', 'same')

        when:
        def od = compare(workdir('a', ['reads.tsv': upA, 'meta.txt': same1]), workdir('b', ['reads.tsv': upB, 'meta.txt': same2]))

        then:
        od.hasChanges()
        od.files.find { it.path == 'meta.txt' }.kind == Kind.UNCHANGED
        def reads = od.files.find { it.path == 'reads.tsv' }
        reads.kind == Kind.CHANGED
        reads.ops.find { it.type == LineDiff.Type.DELETE }.text == 'r2\t20'
        reads.ops.find { it.type == LineDiff.Type.INSERT }.text == 'r2\t99'
    }

    def 'an input staged in only one run is added or removed'() {
        given:
        def x = write('in/x.txt', 'x')
        def y = write('in/y.txt', 'y')

        when:
        def od = compare(workdir('a', ['x.txt': x]), workdir('b', ['x.txt': x, 'extra/y.txt': y]))

        then: 'inputs staged into stageAs folders are found too'
        od.files.find { it.path == 'extra/y.txt' }.kind == Kind.ADDED
        od.files.find { it.path == 'x.txt' }.kind == Kind.UNCHANGED
    }

    def 'a staged folder has the files inside it compared'() {
        given:
        def idxA = tmp.resolve('idxA'); def idxB = tmp.resolve('idxB')
        write('idxA/index.1', 'aaa'); write('idxA/sub/index.2', 'same')
        write('idxB/index.1', 'bbb'); write('idxB/sub/index.2', 'same')

        when:
        def od = compare(workdir('a', ['index': idxA]), workdir('b', ['index': idxB]))

        then:
        od.files*.path == ['index/index.1', 'index/sub/index.2']
        od.files.find { it.path == 'index/index.1' }.kind == Kind.CHANGED
        od.files.find { it.path == 'index/sub/index.2' }.kind == Kind.UNCHANGED
    }

    def 'a link whose target is gone is skipped and noted'() {
        given:
        def gone = write('tmp/gone.txt', 'soon deleted')
        def kept = write('tmp/kept.txt', 'k')
        def a = workdir('a', ['gone.txt': gone, 'kept.txt': kept])
        def b = workdir('b', ['kept.txt': kept])
        Files.delete(gone)

        when:
        def od = compare(a, b)

        then:
        od.files*.path == ['kept.txt']
        od.note == '1 staged input(s) point at files that no longer exist and were skipped.'
    }

    def 'reports a missing work directory'() {
        when:
        def od = new InputComparator().compare(task(workdir('a', [:])), task(tmp.resolve('nope')))

        then:
        !od.availableB
        od.note == "Run B's work directory is not available locally, so inputs cannot be compared."
    }
}
