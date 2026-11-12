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
import java.util.zip.GZIPOutputStream

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

    // ------------------------------------------------------------------ gzip

    /** gzip {@code content}; {@code mtime} is written into the header like `gzip` does. */
    private static byte[] gz(String content, int mtime = 0) {
        def bos = new ByteArrayOutputStream()
        new GZIPOutputStream(bos).withCloseable { it.write(content.getBytes('UTF-8')) }
        byte[] out = bos.toByteArray()
        // Header bytes 4..7 are MTIME (little endian); not covered by any CRC.
        (0..3).each { int i -> out[4 + i] = (byte) ((mtime >>> (8 * i)) & 0xff) }
        return out
    }

    private static byte[] concat(byte[]... parts) {
        def bos = new ByteArrayOutputStream()
        parts.each { bos.write(it) }
        return bos.toByteArray()
    }

    def 'gzip files with identical content but different headers are unchanged'() {
        given: 'same data, compressed at different times'
        def a = file('a.txt.gz', gz('chr1\t100\nchr2\t200\n', 1_700_000_000))
        def b = file('b.txt.gz', gz('chr1\t100\nchr2\t200\n', 1_800_000_000))
        assert Files.readAllBytes(a) != Files.readAllBytes(b)

        when:
        def fd = compare(new FileContentComparator(), 'out.txt.gz', a, b)

        then:
        fd.kind == Kind.UNCHANGED
        fd.verified
        fd.decompressed
        fd.note == 'gzip: compressed bytes differ, decompressed content is identical'
    }

    def 'gzip files with different content are changed and line-diffed on their text'() {
        given:
        def a = file('a.gz', gz('keep\nold line\nkeep too\n'))
        def b = file('b.gz', gz('keep\nnew line\nkeep too\n'))

        when:
        def fd = compare(new FileContentComparator(), 'out.gz', a, b)

        then:
        fd.kind == Kind.CHANGED
        fd.decompressed
        fd.linesRemoved() == 1
        fd.linesAdded() == 1
        fd.ops.find { it.type == LineDiff.Type.DELETE }.text == 'old line'
        fd.ops.find { it.type == LineDiff.Type.INSERT }.text == 'new line'
    }

    def 'byte-identical gzip files short-circuit on the raw hash'() {
        given:
        def a = file('a.gz', gz('same\n'))
        def b = file('b.gz', gz('same\n'))

        when:
        def fd = compare(new FileContentComparator(), 'x.gz', a, b)

        then:
        fd.kind == Kind.UNCHANGED
        !fd.decompressed
        fd.hashA == fd.hashB
    }

    def 'a multi-member (BGZF-style) file matches a single-member file with the same content'() {
        given:
        def a = file('a.gz', concat(gz('first half\n'), gz('second half\n')))
        def b = file('b.gz', gz('first half\nsecond half\n'))

        expect:
        compare(new FileContentComparator(), 'x.vcf.gz', a, b).kind == Kind.UNCHANGED
    }

    def 'a changed gzip of binary data is reported without a line diff'() {
        given:
        def a = file('a.gz', gz('BAM\u0001\u0000\u0000data-a'))
        def b = file('b.gz', gz('BAM\u0001\u0000\u0000data-b'))

        when:
        def fd = compare(new FileContentComparator(), 'x.bam', a, b)

        then:
        fd.kind == Kind.CHANGED
        fd.decompressed
        fd.ops.isEmpty()
    }

    def 'gzip files above the byte cap keep the raw-byte comparison'() {
        given: 'same content, different headers, but larger than the cap'
        def a = file('a.gz', gz('x' * 200, 1))
        def b = file('b.gz', gz('x' * 200, 2))

        when:
        def fd = compare(new FileContentComparator(5L), 'x.gz', a, b)

        then: 'same size above the cap: content not verified, and nothing decompressed'
        fd.kind == Kind.UNCHANGED
        !fd.verified
        !fd.decompressed
    }

    def 'a corrupt gzip falls back to the raw-byte comparison'() {
        given: 'gzip magic bytes followed by garbage'
        def a = file('a.gz', [0x1f, 0x8b, 1, 2, 3, 4] as byte[])
        def b = file('b.gz', gz('real\n'))

        when:
        def fd = compare(new FileContentComparator(), 'x.gz', a, b)

        then:
        fd.kind == Kind.CHANGED
        !fd.decompressed
    }

    def 'only one side being gzip compares raw bytes'() {
        given:
        def a = file('a', 'plain text\n')
        def b = file('b', gz('plain text\n'))

        when:
        def fd = compare(new FileContentComparator(), 'x', a, b)

        then:
        fd.kind == Kind.CHANGED
        !fd.decompressed
    }

    // ------------------------------------------------------- numeric tolerance

    def 'a text file that differs only in floating-point noise is unchanged under tolerance'() {
        given:
        def a = file('a.tsv', 'gene\tscore\nBRCA1\t0.30000000000000004\nTP53\t1.5e-3\n')
        def b = file('b.tsv', 'gene\tscore\nBRCA1\t0.3\nTP53\t1.5000001e-3\n')

        when:
        def exact = compare(new FileContentComparator(), 'x.tsv', a, b)
        def tolerant = compare(new FileContentComparator(0L, 1000, 1e-6d), 'x.tsv', a, b)

        then:
        exact.kind == Kind.CHANGED
        tolerant.kind == Kind.UNCHANGED
        tolerant.withinTolerance
        tolerant.note == 'differs only in numbers within tolerance 1.0E-6'
    }

    def 'a real change is still changed, and the line diff skips within-tolerance lines'() {
        given:
        def a = file('a.tsv', 'BRCA1\t0.30000000000000004\nTP53\t10\n')
        def b = file('b.tsv', 'BRCA1\t0.3\nTP53\t12\n')

        when:
        def fd = compare(new FileContentComparator(0L, 1000, 1e-6d), 'x.tsv', a, b)

        then: 'only the TP53 line shows as changed'
        fd.kind == Kind.CHANGED
        !fd.withinTolerance
        fd.linesRemoved() == 1
        fd.linesAdded() == 1
        fd.ops.find { it.type == LineDiff.Type.DELETE }.text == 'TP53\t10'
        fd.ops.find { it.type == LineDiff.Type.EQUAL }.text == 'BRCA1\t0.30000000000000004'
    }

    def 'tolerance applies to gzipped text too'() {
        given:
        def a = file('a.tsv.gz', gz('x\t0.1\ny\t2.00000001\n'))
        def b = file('b.tsv.gz', gz('x\t0.1\ny\t2.00000002\n'))

        when:
        def fd = compare(new FileContentComparator(0L, 1000, 1e-6d), 'x.tsv.gz', a, b)

        then:
        fd.kind == Kind.UNCHANGED
        fd.decompressed
        fd.withinTolerance
        fd.note == 'gzip: differs only in numbers within tolerance 1.0E-6'
    }

    def 'an extra line is a change even when every shared line is within tolerance'() {
        given:
        def a = file('a.txt', 'v 1.0\n')
        def b = file('b.txt', 'v 1.0000000001\nv 2\n')

        expect:
        compare(new FileContentComparator(0L, 1000, 1e-6d), 'x', a, b).kind == Kind.CHANGED
    }
}
