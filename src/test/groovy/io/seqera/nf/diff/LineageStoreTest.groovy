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

import groovy.json.JsonOutput
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Direct unit coverage of {@link LineageStore}. {@code DagComparatorTest}
 * exercises the lineage path through {@link DagComparator#graphOf}, but this
 * suite pins the store's own contract — in particular the null-vs-empty return
 * distinction ({@code null} = "no records for this session, caller should fall
 * back" vs an empty set = "authoritative run that legitimately has no edges"),
 * the nested {@code input} value shapes, multi-producer inputs, LID-hash
 * parsing, and resilience to malformed records.
 */
class LineageStoreTest extends Specification {

    @TempDir
    Path tmp

    /** Create the default {@code .lineage} store dir under {@code tmp}. */
    private Path newStore() {
        return Files.createDirectories(tmp.resolve(LineageStore.DEFAULT_DIR))
    }

    /** Write a lineage record as {@code <store>/<hash>/.data.json}. */
    private void record(Path store, String hash, Map rec) {
        def dir = Files.createDirectories(store.resolve(hash))
        Files.write(dir.resolve('.data.json'), JsonOutput.toJson(rec).bytes)
    }

    /** Convenience builder for a TaskRun record. */
    private Map taskRun(String session, String name, Object input = []) {
        return [type: 'TaskRun', sessionId: session, name: name, input: input]
    }

    private Set<String> edgeStrings(Set<DiffResult.DagEdge> edges) {
        return edges.collect { "${it.from}->${it.to}" } as Set
    }

    // --- locate() -----------------------------------------------------------

    def 'locate returns null when the project dir has no .lineage store'() {
        expect:
        LineageStore.locate(tmp) == null
    }

    def 'locate returns null for a null project dir'() {
        expect:
        LineageStore.locate(null) == null
    }

    def 'locate returns a store when .lineage exists'() {
        given:
        newStore()

        expect:
        LineageStore.locate(tmp) != null
    }

    def 'locate returns null when .lineage is a file, not a directory'() {
        given:
        Files.write(tmp.resolve(LineageStore.DEFAULT_DIR), 'not a dir'.bytes)

        expect:
        LineageStore.locate(tmp) == null
    }

    // --- edgesForSession(): null vs empty contract --------------------------

    def 'edgesForSession returns null for a null session id'() {
        given:
        newStore()

        expect:
        LineageStore.locate(tmp).edgesForSession(null) == null
    }

    def 'returns null (fall-back signal) when no TaskRun matches the session'() {
        given: 'the store only holds a task from a different session'
        def store = newStore()
        record(store, 'other', taskRun(UUID.randomUUID().toString(), 'X'))
        def sid = UUID.randomUUID()

        expect: 'null tells the caller to fall back to symlink reconstruction'
        LineageStore.locate(tmp).edgesForSession(sid) == null
    }

    def 'returns an empty set (not null) for a linear run with no edges'() {
        given: 'a single-task run for this session, consuming no task outputs'
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hashA', taskRun(sid.toString(), 'A'))

        when:
        def edges = LineageStore.locate(tmp).edgesForSession(sid)

        then: 'authoritative: records exist for the session, they just have no edges'
        edges != null
        edges.isEmpty()
    }

    // --- edge reconstruction ------------------------------------------------

    def 'reconstructs a single producer -> consumer edge'() {
        given:
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hashA', taskRun(sid.toString(), 'A'))
        record(store, 'hashB', taskRun(sid.toString(), 'B',
                [[type: 'path', name: 'in', value: ['lid://hashA/out.txt']]]))

        expect:
        edgeStrings(LineageStore.locate(tmp).edgesForSession(sid)) == ['A->B'] as Set
    }

    def 'resolves a bare lid://hash reference with no path segment'() {
        given:
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hashA', taskRun(sid.toString(), 'A'))
        record(store, 'hashB', taskRun(sid.toString(), 'B',
                [[type: 'path', name: 'in', value: 'lid://hashA']]))

        expect:
        edgeStrings(LineageStore.locate(tmp).edgesForSession(sid)) == ['A->B'] as Set
    }

    def 'records an edge from every producer feeding one consumer'() {
        given:
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hashA', taskRun(sid.toString(), 'A'))
        record(store, 'hashB', taskRun(sid.toString(), 'B'))
        record(store, 'hashC', taskRun(sid.toString(), 'C',
                [[value: 'lid://hashA/a.txt'], [value: 'lid://hashB/b.txt']]))

        expect:
        edgeStrings(LineageStore.locate(tmp).edgesForSession(sid)) == ['A->C', 'B->C'] as Set
    }

    def 'digs LID references out of nested map/list input values'() {
        given: 'the LID is buried inside a nested map within a list'
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hashA', taskRun(sid.toString(), 'A'))
        record(store, 'hashB', taskRun(sid.toString(), 'B',
                [[type: 'map', value: [nested: [inner: ['lid://hashA/deep.txt']]]]]))

        expect:
        edgeStrings(LineageStore.locate(tmp).edgesForSession(sid)) == ['A->B'] as Set
    }

    def 'ignores inputs that are not LID references'() {
        given: 'B consumes an external file path, not a task output'
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hashA', taskRun(sid.toString(), 'A'))
        record(store, 'hashB', taskRun(sid.toString(), 'B',
                [[type: 'path', name: 'reads', value: '/data/reads.fastq']]))

        when:
        def edges = LineageStore.locate(tmp).edgesForSession(sid)

        then:
        edges != null
        edges.isEmpty()
    }

    def 'ignores an input LID whose producer is not in this session'() {
        given: 'B references a hash for which no TaskRun exists in the session'
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hashB', taskRun(sid.toString(), 'B',
                [[value: 'lid://unknownHash/out.txt']]))

        expect: 'the dangling reference yields no edge'
        LineageStore.locate(tmp).edgesForSession(sid).isEmpty()
    }

    def 'strips the task tag from the lineage name down to the process name'() {
        given:
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'hA', taskRun(sid.toString(), 'ALIGN:BWA (sample1)'))
        record(store, 'hB', taskRun(sid.toString(), 'QC:SAMTOOLS (sample1)',
                [[value: 'lid://hA/aln.bam']]))

        expect:
        edgeStrings(LineageStore.locate(tmp).edgesForSession(sid)) == ['ALIGN:BWA->QC:SAMTOOLS'] as Set
    }

    // --- record filtering & resilience --------------------------------------

    def 'ignores non-TaskRun records such as WorkflowRun'() {
        given: 'only a WorkflowRun for the session, no TaskRun'
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'wf', [type: 'WorkflowRun', sessionId: sid.toString(), name: 'run'])

        expect: 'no TaskRun records -> null fall-back signal'
        LineageStore.locate(tmp).edgesForSession(sid) == null
    }

    def 'skips an unparseable record and still reads the valid ones'() {
        given: 'a corrupt .data.json alongside a valid producer/consumer pair'
        def store = newStore()
        def sid = UUID.randomUUID()
        def bad = Files.createDirectories(store.resolve('corrupt'))
        Files.write(bad.resolve('.data.json'), '{ not valid json'.bytes)
        record(store, 'hashA', taskRun(sid.toString(), 'A'))
        record(store, 'hashB', taskRun(sid.toString(), 'B',
                [[value: 'lid://hashA/out.txt']]))

        expect:
        edgeStrings(LineageStore.locate(tmp).edgesForSession(sid)) == ['A->B'] as Set
    }

    def 'skips a TaskRun record with no usable name'() {
        given: 'one nameless TaskRun plus a normal edge'
        def store = newStore()
        def sid = UUID.randomUUID()
        record(store, 'noname', [type: 'TaskRun', sessionId: sid.toString(), input: []])
        record(store, 'hashA', taskRun(sid.toString(), 'A'))
        record(store, 'hashB', taskRun(sid.toString(), 'B',
                [[value: 'lid://hashA/out.txt']]))

        expect: 'the nameless record is dropped; the real edge survives'
        edgeStrings(LineageStore.locate(tmp).edgesForSession(sid)) == ['A->B'] as Set
    }
}
