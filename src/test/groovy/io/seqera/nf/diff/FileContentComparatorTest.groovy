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

/**
 * Direct coverage of {@link FileContentComparator}, the shared size / hash /
 * line-diff engine behind both {@link OutputComparator} and
 * {@link PublishedComparator}. The two callers test their own enumeration; this
 * pins the classification engine itself, in isolation, across every branch:
 * present-on-one-side, size mismatch, same-size hashing, the byte cap, the
 * binary sniff, and the line-diff caps.
 */
class FileContentComparatorTest extends Specification {

    @TempDir
    Path tmp

    /** Write {@code content} to a fresh file and return it. */
    private Path file(String name, byte[] content) {
        final p = tmp.resolve(name)
        Files.write(p, content)
        return p
    }

    private Path file(String name, String content) {
        return file(name, content.bytes)
    }

    /** Compare two on-disk files through the engine, sizing them from disk. */
    private DiffResult.OutputFileDiff compare(FileContentComparator engine, String path, Path a, Path b) {
        return engine.compare(path, a, a == null ? null : Files.size(a), b, b == null ? null : Files.size(b))
    }

    def 'classifies a file present on only one side'() {
        given:
        def only = file('x', 'data')

        when: 'A is absent (sizeA null)'
        def added = new FileContentComparator().compare('x', null, null, only, Files.size(only))
        and: 'B is absent (sizeB null)'
        def removed = new FileContentComparator().compare('x', only, Files.size(only), null, null)

        then:
        added.kind == Kind.ADDED
        removed.kind == Kind.REMOVED
    }

    def 'flags differing sizes as changed without hashing'() {
        given:
        def a = file('a', 'short')
        def b = file('b', 'a much longer output')

        when:
        def fd = compare(new FileContentComparator(), 'out', a, b)

        then:
        fd.kind == Kind.CHANGED
        fd.hashA == null && fd.hashB == null   // sizes differed: no hash computed
    }

    def 'treats same-size identical content as unchanged and verified'() {
        given:
        def a = file('a', 'same-bytes')
        def b = file('b', 'same-bytes')

        when:
        def fd = compare(new FileContentComparator(), 'out', a, b)

        then:
        fd.kind == Kind.UNCHANGED
        fd.verified
        fd.hashA == fd.hashB
    }

    def 'detects a same-size, same-length, different-content change'() {
        given: 'identical size (4 bytes) but different content — only hashing separates them'
        def a = file('a', 'AAAA')
        def b = file('b', 'BBBB')

        when:
        def fd = compare(new FileContentComparator(), 'out', a, b)

        then: 'the decision is content-based, not size-based'
        fd.kind == Kind.CHANGED
        fd.verified
        fd.hashA != fd.hashB
    }

    def 'stores the hash as a short 12-char display prefix, not the full digest'() {
        given: 'the equality decision must use the full digest, but hashA/hashB are display-only prefixes'
        def a = file('a', 'AAAA')
        def b = file('b', 'BBBB')

        when:
        def fd = compare(new FileContentComparator(), 'out', a, b)

        then: 'a full SHA-256 hex is 64 chars; the stored display value is trimmed to 12'
        fd.hashA.length() == 12
        fd.hashB.length() == 12
        fd.hashA ==~ /[0-9a-f]{12}/
        fd.hashB ==~ /[0-9a-f]{12}/
    }

    def 'leaves same-size files above the byte cap content-unverified'() {
        given: 'two same-size (4-byte) different files with a 2-byte cap'
        def a = file('a', 'AAAA')
        def b = file('b', 'BBBB')

        when:
        def fd = compare(new FileContentComparator(2L), 'out', a, b)

        then: 'the change is not detected, but the file is explicitly marked unverified'
        fd.kind == Kind.UNCHANGED
        !fd.verified
        fd.note?.contains('byte cap')
    }

    def 'still hashes same-size files at or below the byte cap'() {
        given: 'files (4 bytes) exactly at the cap (4 bytes) are hashed'
        def a = file('a', 'AAAA')
        def b = file('b', 'BBBB')

        when:
        def fd = compare(new FileContentComparator(4L), 'out', a, b)

        then:
        fd.kind == Kind.CHANGED
        fd.verified
    }

    def 'flags a changed binary file without a line diff'() {
        given: 'two same-size files that each contain a NUL byte in the head window'
        def a = file('a.bin', [0x01, 0x00, 0x02, 0x41] as byte[])
        def b = file('b.bin', [0x01, 0x00, 0x02, 0x42] as byte[])

        when:
        def fd = compare(new FileContentComparator(), 'out.bin', a, b)

        then: 'reported changed by hash, but no line-level diff for binary content'
        fd.kind == Kind.CHANGED
        !fd.hasLineDiff()
    }

    def 'computes a bounded line diff for a changed text file'() {
        given:
        def a = file('a', "line1\nline2\nline3\n")
        def b = file('b', "line1\nline2-changed\nline3\nline4\n")

        when:
        def fd = compare(new FileContentComparator(), 'out', a, b)

        then:
        fd.kind == Kind.CHANGED
        fd.hasLineDiff()
        !fd.truncated
        def texts = fd.ops.collect { it.text }
        texts.contains('line2')
        texts.contains('line2-changed')
        texts.contains('line4')
    }

    def 'caps the line diff to maxLines and flags truncation'() {
        given: 'files far longer than the 2-line cap'
        def a = file('a', (1..50).collect { "a-line-${it}" }.join('\n') + '\n')
        def b = file('b', (1..50).collect { "b-line-${it}" }.join('\n') + '\n')

        when: 'maxLines = 2'
        def fd = compare(new FileContentComparator(0L, 2), 'out', a, b)

        then:
        fd.kind == Kind.CHANGED
        fd.hasLineDiff()
        fd.truncated
        fd.ops.every { it.text ==~ /[ab]-line-[12]/ }
    }
}
