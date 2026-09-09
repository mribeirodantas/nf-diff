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

class DagComparatorTest extends Specification {

    @TempDir
    Path tmp

    /** Create a task work dir under {@code tmp} and return it. */
    private Path workdir(String name) {
        return Files.createDirectories(tmp.resolve(name))
    }

    /** Write a lineage record as {@code <store>/<hash>/.data.json}. */
    private void lineageRecord(Path store, String hash, Map record) {
        def dir = Files.createDirectories(store.resolve(hash))
        Files.write(dir.resolve('.data.json'), JsonOutput.toJson(record).bytes)
    }

    private TaskInfo task(String process, Path dir) {
        return new TaskInfo(process: process, name: "${process} (1)", workdir: dir?.toString())
    }

    /** Stage {@code producerDir}'s output into {@code consumerDir} as an input symlink. */
    private void stageInput(Path consumerDir, Path producerDir, String outName = 'out.txt') {
        def out = producerDir.resolve(outName)
        if( !Files.exists(out) )
            Files.write(out, 'data'.bytes)
        Files.createSymbolicLink(consumerDir.resolve(outName), out)
    }

    def 'reconstructs a producer -> consumer edge from a staged input symlink'() {
        given: 'A produced an output that B consumed'
        def dirA = workdir('A')
        def dirB = workdir('B')
        stageInput(dirB, dirA)
        def run = new RunSnapshot(tasks: [task('A', dirA), task('B', dirB)])

        when:
        def graph = new DagComparator().symlinkGraphOf(run)

        then:
        graph.source == DagComparator.Source.SYMLINK
        graph.anyWorkdir
        graph.missingWorkdirs == 0
        graph.edges.collect { "${it.from}->${it.to}" } == ['A->B']
    }

    def 'ignores external inputs that resolve outside every work dir'() {
        given: 'B consumes an external input, not another task output'
        def dirA = workdir('A')
        def dirB = workdir('B')
        def external = Files.write(tmp.resolve('reads.fastq'), 'x'.bytes)
        Files.createSymbolicLink(dirB.resolve('reads.fastq'), external)
        def run = new RunSnapshot(tasks: [task('A', dirA), task('B', dirB)])

        when:
        def graph = new DagComparator().symlinkGraphOf(run)

        then:
        graph.anyWorkdir
        graph.edges.isEmpty()
    }

    def 'counts a missing work directory'() {
        given:
        def dirA = workdir('A')
        def run = new RunSnapshot(tasks: [task('A', dirA), task('B', tmp.resolve('gone'))])

        when:
        def graph = new DagComparator().symlinkGraphOf(run)

        then:
        graph.anyWorkdir
        graph.missingWorkdirs == 1
    }

    def 'attributes a link into a nested output subdirectory to the producer'() {
        given: 'A writes into a subdir that B links to'
        def dirA = workdir('A')
        def sub = Files.createDirectories(dirA.resolve('results'))
        def out = Files.write(sub.resolve('out.txt'), 'data'.bytes)
        def dirB = workdir('B')
        Files.createSymbolicLink(dirB.resolve('out.txt'), out)
        def run = new RunSnapshot(tasks: [task('A', dirA), task('B', dirB)])

        when:
        def graph = new DagComparator().symlinkGraphOf(run)

        then:
        graph.edges.collect { "${it.from}->${it.to}" } == ['A->B']
    }

    // --- lineage-backed reconstruction (authoritative, no work dirs) ---

    def 'reads an authoritative producer -> consumer edge from the lineage store'() {
        given: 'a .lineage store recording A producing an output B consumed'
        def sid = UUID.randomUUID()
        def store = Files.createDirectories(tmp.resolve('.lineage'))
        lineageRecord(store, 'wf000', [type: 'WorkflowRun', sessionId: sid.toString(), name: 'run'])
        lineageRecord(store, 'hashA', [type: 'TaskRun', sessionId: sid.toString(), name: 'A', input: []])
        lineageRecord(store, 'hashB', [type: 'TaskRun', sessionId: sid.toString(), name: 'B',
                input: [[type: 'path', name: 'in', value: ['lid://hashA/out.txt']]]])
        // No work directories set on the tasks — lineage must not need them.
        def run = new RunSnapshot(sessionId: sid, tasks: [task('A', null), task('B', null)])

        when:
        def graph = new DagComparator().graphOf(run, tmp)

        then:
        graph.source == DagComparator.Source.LINEAGE
        graph.edges.collect { "${it.from}->${it.to}" } == ['A->B']
    }

    def 'strips the task tag from a lineage name down to the process name'() {
        given:
        def sid = UUID.randomUUID()
        def store = Files.createDirectories(tmp.resolve('.lineage'))
        lineageRecord(store, 'hA', [type: 'TaskRun', sessionId: sid.toString(), name: 'ALIGN:BWA (sample1)', input: []])
        lineageRecord(store, 'hB', [type: 'TaskRun', sessionId: sid.toString(), name: 'QC:SAMTOOLS (sample1)',
                input: [[type: 'path', name: 'bam', value: 'lid://hA/aln.bam']]])
        def run = new RunSnapshot(sessionId: sid, tasks: [])

        when:
        def graph = new DagComparator().graphOf(run, tmp)

        then:
        graph.source == DagComparator.Source.LINEAGE
        graph.edges.collect { "${it.from}->${it.to}" } == ['ALIGN:BWA->QC:SAMTOOLS']
    }

    def 'ignores lineage records from other sessions'() {
        given: 'the store holds a task from a different run'
        def sid = UUID.randomUUID()
        def store = Files.createDirectories(tmp.resolve('.lineage'))
        lineageRecord(store, 'other', [type: 'TaskRun', sessionId: UUID.randomUUID().toString(), name: 'X', input: []])
        def dirA = workdir('A')
        def dirB = workdir('B')
        stageInput(dirB, dirA)
        def run = new RunSnapshot(sessionId: sid, tasks: [task('A', dirA), task('B', dirB)])

        when: 'no lineage record matches this session, so it falls back to symlinks'
        def graph = new DagComparator().graphOf(run, tmp)

        then:
        graph.source == DagComparator.Source.SYMLINK
        graph.edges.collect { "${it.from}->${it.to}" } == ['A->B']
    }

    def 'falls back to symlink reconstruction when no lineage store exists'() {
        given:
        def dirA = workdir('A')
        def dirB = workdir('B')
        stageInput(dirB, dirA)
        def run = new RunSnapshot(sessionId: UUID.randomUUID(), tasks: [task('A', dirA), task('B', dirB)])

        when: 'projectDir has no .lineage directory'
        def graph = new DagComparator().graphOf(run, tmp)

        then:
        graph.source == DagComparator.Source.SYMLINK
        graph.edges.collect { "${it.from}->${it.to}" } == ['A->B']
    }
}
