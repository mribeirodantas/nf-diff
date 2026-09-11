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

class PublishedComparatorTest extends Specification {

    @TempDir
    Path tmp

    /** Create a published-output tree with the given files (relative path -> content). */
    private Path published(String name, Map<String,String> files) {
        def root = Files.createDirectories(tmp.resolve(name))
        files.each { rel, content ->
            def f = root.resolve(rel)
            Files.createDirectories(f.parent)
            Files.write(f, content.bytes)
        }
        return root
    }

    def 'flags a same-size content change via hashing'() {
        given:
        def a = published('a', ['out.txt': 'AAAA'])
        def b = published('b', ['out.txt': 'BBBB']) // same size, different bytes

        when:
        def pd = new PublishedComparator().compare(a, b)

        then:
        pd.availableA && pd.availableB
        pd.hasChanges()
        def f = pd.files.find { it.path == 'out.txt' }
        f.kind == DiffResult.Kind.CHANGED
        f.verified
        f.hashA != f.hashB
    }

    def 'treats identical trees as unchanged'() {
        given:
        def a = published('a', ['dir/out.txt': 'same', 'top.txt': 'x'])
        def b = published('b', ['dir/out.txt': 'same', 'top.txt': 'x'])

        when:
        def pd = new PublishedComparator().compare(a, b)

        then:
        !pd.hasChanges()
        pd.changedCount() == 0
    }

    def 'classifies added and removed files by relative path'() {
        given:
        def a = published('a', ['only_a.txt': 'x', 'shared.txt': 'z'])
        def b = published('b', ['nested/only_b.txt': 'y', 'shared.txt': 'z'])

        when:
        def pd = new PublishedComparator().compare(a, b)

        then:
        pd.removedFiles()*.path == ['only_a.txt']
        pd.addedFiles()*.path == ['nested/only_b.txt']
        pd.hasChanges()
    }

    def 'follows symlinked published files (publishDir default mode)'() {
        given: 'B publishes a symlink pointing at a real file elsewhere'
        def a = published('a', ['out.txt': 'result'])
        def b = Files.createDirectories(tmp.resolve('b'))
        def real = Files.write(tmp.resolve('work-output.txt'), 'result'.bytes)
        Files.createSymbolicLink(b.resolve('out.txt'), real)

        when:
        def pd = new PublishedComparator().compare(a, b)

        then: 'the symlink target is compared, so identical content is unchanged'
        def f = pd.files.find { it.path == 'out.txt' }
        f.kind == DiffResult.Kind.UNCHANGED
        !pd.hasChanges()
    }

    def 'computes a line-level diff for a changed text file'() {
        given:
        def a = published('a', ['report.csv': "a,b\n1,2\n3,4\n"])
        def b = published('b', ['report.csv': "a,b\n1,2\n5,6\n"])

        when:
        def pd = new PublishedComparator().compare(a, b)

        then:
        def f = pd.files.find { it.path == 'report.csv' }
        f.kind == DiffResult.Kind.CHANGED
        f.hasLineDiff()
        f.ops.any { it.type == LineDiff.Type.DELETE && it.text == '3,4' }
        f.ops.any { it.type == LineDiff.Type.INSERT && it.text == '5,6' }
    }

    def 'reports a missing published directory as unavailable'() {
        given:
        def a = published('a', ['out.txt': 'x'])
        def b = tmp.resolve('does-not-exist')

        when:
        def pd = new PublishedComparator().compare(a, b)

        then:
        pd.availableA
        !pd.availableB
        !pd.hasChanges()
        pd.note?.contains('Run B')
    }

    def 'short-circuits when both sides point at the same directory'() {
        given:
        def dir = published('shared', ['out.txt': 'x'])

        when:
        def pd = new PublishedComparator().compare(dir, dir)

        then:
        pd.sameDir
        pd.files.isEmpty()
        !pd.hasChanges()
    }
}
