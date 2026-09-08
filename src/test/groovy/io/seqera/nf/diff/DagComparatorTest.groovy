package io.seqera.nf.diff

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

class DagComparatorTest extends Specification {

    @TempDir
    Path tmp

    /** Create a task work dir under {@code tmp} and return it. */
    private Path workdir(String name) {
        return Files.createDirectories(tmp.resolve(name))
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
        def graph = new DagComparator().graphOf(run)

        then:
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
        def graph = new DagComparator().graphOf(run)

        then:
        graph.anyWorkdir
        graph.edges.isEmpty()
    }

    def 'counts a missing work directory'() {
        given:
        def dirA = workdir('A')
        def run = new RunSnapshot(tasks: [task('A', dirA), task('B', tmp.resolve('gone'))])

        when:
        def graph = new DagComparator().graphOf(run)

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
        def graph = new DagComparator().graphOf(run)

        then:
        graph.edges.collect { "${it.from}->${it.to}" } == ['A->B']
    }
}
