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
