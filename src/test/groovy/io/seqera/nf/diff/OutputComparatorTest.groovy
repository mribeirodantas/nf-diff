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

class OutputComparatorTest extends Specification {

    @TempDir
    Path tmp

    /** Create a task work dir with the given files (name -> content), plus the usual control files. */
    private Path workdir(String name, Map<String,String> files) {
        def dir = Files.createDirectories(tmp.resolve(name))
        // Nextflow control files that must be ignored.
        Files.write(dir.resolve('.command.sh'), 'echo hi'.bytes)
        Files.write(dir.resolve('.command.out'), 'stdout differs across runs'.bytes)
        Files.write(dir.resolve('.exitcode'), '0'.bytes)
        files.each { fname, content -> Files.write(dir.resolve(fname), content.bytes) }
        return dir
    }

    private TaskInfo task(String workdir) {
        return new TaskInfo(process: 'FOO', name: 'FOO (1)', workdir: workdir)
    }

    def 'flags a same-size content change via hashing'() {
        given:
        def a = task(workdir('a', [out: 'AAAA']).toString())
        def b = task(workdir('b', [out: 'BBBB']).toString()) // same size, different bytes

        when:
        def od = new OutputComparator().compare(a, b)

        then:
        od.availableA && od.availableB
        od.hasChanges()
        def f = od.files.find { it.path == 'out' }
        f.kind == DiffResult.Kind.CHANGED
        f.verified
        f.hashA != f.hashB
    }

    def 'treats identical outputs as unchanged'() {
        given:
        def a = task(workdir('a', [out: 'same']).toString())
        def b = task(workdir('b', [out: 'same']).toString())

        when:
        def od = new OutputComparator().compare(a, b)

        then:
        !od.hasChanges()
        od.files.find { it.path == 'out' }.kind == DiffResult.Kind.UNCHANGED
    }

    def 'classifies added and removed files'() {
        given:
        def a = task(workdir('a', [only_a: 'x', shared: 'z']).toString())
        def b = task(workdir('b', [only_b: 'y', shared: 'z']).toString())

        when:
        def od = new OutputComparator().compare(a, b)

        then:
        od.removedFiles()*.path == ['only_a']
        od.addedFiles()*.path == ['only_b']
        od.hasChanges()
    }

    def 'a size change is flagged without needing a hash'() {
        given:
        def a = task(workdir('a', [out: 'short']).toString())
        def b = task(workdir('b', [out: 'a much longer output']).toString())

        when:
        def od = new OutputComparator().compare(a, b)

        then:
        def f = od.files.find { it.path == 'out' }
        f.kind == DiffResult.Kind.CHANGED
        f.hashA == null && f.hashB == null // sizes differed, so no hashing
    }

    def 'ignores control files and staged input symlinks'() {
        given: 'both runs produce identical real outputs but different control files and inputs'
        def da = workdir('a', [out: 'result'])
        def db = workdir('b', [out: 'result'])
        // a staged input, present as a symlink pointing outside the work dir
        def external = Files.write(tmp.resolve('external-input.txt'), 'input'.bytes)
        Files.createSymbolicLink(da.resolve('input.txt'), external)
        Files.createSymbolicLink(db.resolve('input.txt'), external)

        when:
        def od = new OutputComparator().compare(task(da.toString()), task(db.toString()))

        then: 'only the real output file is considered, and it is unchanged'
        od.files*.path == ['out']
        !od.hasChanges()
    }

    def 'keeps a nested output that shares a control-file name'() {
        given: 'a genuine output at results/.command.sh — only the work-dir-root control files should be skipped'
        def a = workdir('a', ['out': 'x'])
        def b = workdir('b', ['out': 'x'])
        Files.createDirectories(a.resolve('results'))
        Files.createDirectories(b.resolve('results'))
        Files.write(a.resolve('results/.command.sh'), 'nested-A'.bytes)
        Files.write(b.resolve('results/.command.sh'), 'nested-B'.bytes)

        when:
        def od = new OutputComparator().compare(task(a.toString()), task(b.toString()))

        then: 'the root control file is ignored, but the nested one is compared'
        def paths = od.files*.path
        !paths.contains('.command.sh')
        paths.contains('results/.command.sh')
        od.files.find { it.path == 'results/.command.sh' }.kind == DiffResult.Kind.CHANGED
        od.hasChanges()
    }

    def 'short-circuits when both tasks share the same work directory'() {
        given: 'a cached task in B points at the same work dir A produced'
        def dir = workdir('shared', [out: 'x']).toString()

        when:
        def od = new OutputComparator().compare(task(dir), task(dir))

        then:
        od.sameWorkdir
        od.files.isEmpty()
        !od.hasChanges()
    }

    def 'reports a missing work directory as unavailable'() {
        given:
        def a = task(workdir('a', [out: 'x']).toString())
        def b = task(tmp.resolve('does-not-exist').toString())

        when:
        def od = new OutputComparator().compare(a, b)

        then:
        od.availableA
        !od.availableB
        !od.hasChanges()
        od.note?.contains("Run B")
    }

    def 'computes a line-level diff for a changed text file'() {
        given:
        def a = task(workdir('a', [out: "line1\nline2\nline3\n"]).toString())
        def b = task(workdir('b', [out: "line1\nline2-changed\nline3\nline4\n"]).toString())

        when:
        def od = new OutputComparator().compare(a, b)

        then:
        def f = od.files.find { it.path == 'out' }
        f.kind == DiffResult.Kind.CHANGED
        f.hasLineDiff()
        // line2 removed + line2-changed and line4 inserted
        f.linesRemoved() == 1
        f.linesAdded() == 2
        !f.truncated
        def texts = f.ops.collect { it.text }
        texts.contains('line2')
        texts.contains('line2-changed')
        texts.contains('line4')
    }

    def 'line-diffs a same-size text change (still confirmed by hashing)'() {
        given:
        def a = task(workdir('a', [out: 'AAAA']).toString())
        def b = task(workdir('b', [out: 'BBBB']).toString()) // same size, different bytes

        when:
        def od = new OutputComparator().compare(a, b)

        then:
        def f = od.files.find { it.path == 'out' }
        f.kind == DiffResult.Kind.CHANGED
        f.verified
        f.hasLineDiff()
        f.ops.any { it.type == LineDiff.Type.DELETE && it.text == 'AAAA' }
        f.ops.any { it.type == LineDiff.Type.INSERT && it.text == 'BBBB' }
    }

    def 'does not line-diff a changed binary file'() {
        given: 'two different files that each contain a NUL byte in the head'
        def da = Files.createDirectories(tmp.resolve('a'))
        def db = Files.createDirectories(tmp.resolve('b'))
        Files.write(da.resolve('out.bin'), [0x01, 0x00, 0x02, 0x41] as byte[])
        Files.write(db.resolve('out.bin'), [0x01, 0x00, 0x02, 0x42] as byte[]) // same size, differs

        when:
        def od = new OutputComparator().compare(task(da.toString()), task(db.toString()))

        then: 'still flagged changed, but no line-level diff is produced'
        def f = od.files.find { it.path == 'out.bin' }
        f.kind == DiffResult.Kind.CHANGED
        !f.hasLineDiff()
    }

    def 'caps the line diff to the configured max lines and flags truncation'() {
        given: 'files far longer than the 2-line cap'
        def a = task(workdir('a', [out: (1..50).collect { "a-line-${it}" }.join('\n') + '\n']).toString())
        def b = task(workdir('b', [out: (1..50).collect { "b-line-${it}" }.join('\n') + '\n']).toString())

        when:
        def od = new OutputComparator(0L, 2).compare(a, b)

        then:
        def f = od.files.find { it.path == 'out' }
        f.kind == DiffResult.Kind.CHANGED
        f.hasLineDiff()
        f.truncated
        // only the first 2 lines per side were considered
        f.ops.every { it.text ==~ /[ab]-line-[12]/ }
    }

    def 'leaves same-size files above the byte cap content-unverified'() {
        given: 'two same-size but different files, with a cap below their size'
        def a = task(workdir('a', [out: 'AAAA']).toString())
        def b = task(workdir('b', [out: 'BBBB']).toString())

        when: 'the cap (2 bytes) is smaller than the files (4 bytes)'
        def od = new OutputComparator(2L).compare(a, b)

        then: 'the difference is not detected, but the file is marked unverified'
        def f = od.files.find { it.path == 'out' }
        f.kind == DiffResult.Kind.UNCHANGED
        !f.verified
        f.note?.contains('not verified')
        !od.hasChanges()
    }
}
