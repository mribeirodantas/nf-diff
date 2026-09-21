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
import java.nio.file.Paths

import spock.lang.Specification
import spock.lang.TempDir

/**
 * Coverage of {@link ArchiveStore}: the lightweight JSON round-trip (run-level
 * metadata and every task field, including the raw trace values the numeric
 * comparisons rely on), complete-mode work-dir file copy (outputs and
 * {@code .command.*} logs copied, staged-input symlinks skipped, workdir
 * repointed at the archive), id matching, and directory resolution.
 */
class ArchiveStoreTest extends Specification {

    @TempDir
    Path tmp

    /** A snapshot with a single task carrying display + raw trace fields. */
    private static RunSnapshot sampleSnapshot(UUID sid = UUID.randomUUID(), String runName = 'happy_curie') {
        final task = new TaskInfo(
                hash    : 'ab/cdef01',
                process : 'ALIGN',
                name    : 'ALIGN (1)',
                tag     : 'sampleA',
                status  : 'COMPLETED',
                exit    : '0',
                container: 'quay.io/biocontainers/bwa:0.7.17',
                script  : 'bwa mem ...',
                cached  : false,
                durationMillis: 4200L,
                realtimeMillis: 4000L )
        task.display['realtime'] = '4s'
        task.display['peak_rss'] = '2 GB'
        task.raw['realtime'] = 4000L
        task.raw['peak_rss'] = 2147483648L
        task.raw['exit'] = '0'

        final snap = new RunSnapshot(
                runName        : runName,
                sessionId      : sid,
                status         : 'OK',
                revisionId     : 'abc123',
                command        : 'nextflow run main.nf --x 1',
                timestamp      : new Date(1_700_000_000_000L),
                durationMillis : 1500L,
                pipeline       : 'main.nf',
                nextflowVersion: '25.04.0' )
        snap.tasks = [task]
        return snap
    }

    def 'round-trips a lightweight snapshot through JSON'() {
        given:
        def dir = tmp.resolve('archive')
        def store = new ArchiveStore(dir)
        def sid = UUID.fromString('12345678-90ab-cdef-1234-567890abcdef')
        def snap = sampleSnapshot(sid, 'happy_curie')

        when:
        store.save(snap, false)

        then: 'the run is written as <sessionId>.json and no task files are copied'
        Files.exists(dir.resolve("${sid}.json"))
        !Files.exists(dir.resolve(sid.toString()))

        when:
        def loaded = store.load('happy_curie')

        then: 'run-level metadata survives'
        loaded.runName == 'happy_curie'
        loaded.sessionId == sid
        loaded.status == 'OK'
        loaded.revisionId == 'abc123'
        loaded.command == 'nextflow run main.nf --x 1'
        loaded.timestamp == new Date(1_700_000_000_000L)
        loaded.durationMillis == 1500L
        loaded.pipeline == 'main.nf'
        loaded.nextflowVersion == '25.04.0'
        loaded.requestedId == 'happy_curie'

        and: 'the task and its display + raw values survive, so numeric comparisons still work'
        loaded.tasks.size() == 1
        def t = loaded.tasks[0]
        t.matchKey() == 'ALIGN (1)'
        t.process == 'ALIGN'
        t.exit == '0'
        t.durationMillis == 4200L
        t.realtimeMillis == 4000L
        t.display['peak_rss'] == '2 GB'
        t.numeric('realtime') == 4000L
        t.numeric('peak_rss') == 2147483648L
    }

    def 'matches an archived run by run name, full session id and prefix'() {
        given:
        def store = new ArchiveStore(tmp.resolve('archive'))
        def sid = UUID.fromString('abcdef01-0000-0000-0000-000000000000')
        store.save(sampleSnapshot(sid, 'tender_euler'), false)

        expect:
        store.has('tender_euler')
        store.has(sid.toString())
        store.has('abcdef01')
        !store.has('nope')

        and:
        store.load('abcdef01').sessionId == sid
    }

    def 'load throws when the run is not archived'() {
        given:
        def store = new ArchiveStore(tmp.resolve('empty'))

        when:
        store.load('ghost')

        then:
        thrown(IllegalArgumentException)
    }

    def 'complete mode copies output and log files, skips input symlinks, and repoints workdir'() {
        given: 'a task work dir with an output, a control log, and a staged-input symlink'
        def work = Files.createDirectories(tmp.resolve('work/ab/cdef01'))
        Files.write(work.resolve('output.txt'), 'result\n'.bytes)
        Files.write(work.resolve('.command.out'), 'stdout\n'.bytes)
        def external = Files.write(tmp.resolve('external-input.txt'), 'input\n'.bytes)
        Files.createSymbolicLink(work.resolve('input.txt'), external)

        and:
        def dir = tmp.resolve('archive')
        def store = new ArchiveStore(dir)
        def sid = UUID.fromString('11111111-2222-3333-4444-555555555555')
        def snap = sampleSnapshot(sid, 'run_complete')
        snap.tasks[0].workdir = work.toAbsolutePath().toString()

        when:
        store.save(snap, true)
        def loaded = store.load('run_complete')
        def archivedWorkdir = Paths.get(loaded.tasks[0].workdir)

        then: 'workdir now points inside the archive, not the original work tree'
        archivedWorkdir.startsWith(dir.toAbsolutePath())
        Files.isDirectory(archivedWorkdir)

        and: 'regular output + control log were copied'
        Files.exists(archivedWorkdir.resolve('output.txt'))
        Files.exists(archivedWorkdir.resolve('.command.out'))
        new String(Files.readAllBytes(archivedWorkdir.resolve('output.txt'))) == 'result\n'

        and: 'the staged-input symlink was NOT copied'
        !Files.exists(archivedWorkdir.resolve('input.txt'))
    }

    def 'resolveDir prefers an explicit value, else the default location'() {
        expect: 'an explicit configured dir wins'
        ArchiveStore.resolveDir('/tmp/custom-archive') == Paths.get('/tmp/custom-archive')

        and: 'with nothing configured (and no env override) it falls back to the default'
        ArchiveStore.resolveDir(null) == ArchiveStore.defaultDir() || System.getenv(ArchiveStore.DIR_ENV) != null
    }

    def 'a corrupt or non-map entry in the archive is skipped, not fatal'() {
        given: 'a valid run plus junk files sitting in the same archive dir'
        def dir = tmp.resolve('archive')
        def store = new ArchiveStore(dir)
        store.save(sampleSnapshot(UUID.randomUUID(), 'good_run'), false)
        Files.write(dir.resolve('garbage.json'), 'not json at all {{{'.bytes)
        Files.write(dir.resolve('notmap.json'), '[1, 2, 3]'.bytes)

        expect: 'the good run is still readable and the junk is silently ignored'
        store.has('good_run')
        store.load('good_run').runName == 'good_run'
        !store.has('nope')
    }

    def 'complete mode degrades to lightweight for a task whose work dir is gone'() {
        given: 'a task whose work dir does not exist locally'
        def dir = tmp.resolve('archive')
        def store = new ArchiveStore(dir)
        def sid = UUID.fromString('99999999-0000-0000-0000-000000000000')
        def snap = sampleSnapshot(sid, 'degraded')
        def missing = tmp.resolve('gone/nowhere').toAbsolutePath().toString()
        snap.tasks[0].workdir = missing

        when: 'archiving in complete mode does not throw'
        store.save(snap, true)
        def loaded = store.load('degraded')

        then: 'the run is still archived and the workdir is left pointing at the original (no copy dir made)'
        loaded.tasks[0].workdir == missing
        !Files.exists(dir.resolve(sid.toString()).resolve('tasks'))
    }

    def 'complete mode preserves nested sub-directory layout'() {
        given:
        def work = Files.createDirectories(tmp.resolve('work/ab/cdef01'))
        Files.createDirectories(work.resolve('sub/dir'))
        Files.write(work.resolve('sub/dir/out.bam'), 'binary\n'.bytes)

        and:
        def dir = tmp.resolve('archive')
        def store = new ArchiveStore(dir)
        def sid = UUID.fromString('22222222-0000-0000-0000-000000000000')
        def snap = sampleSnapshot(sid, 'nested')
        snap.tasks[0].workdir = work.toAbsolutePath().toString()

        when:
        store.save(snap, true)
        def archivedWorkdir = Paths.get(store.load('nested').tasks[0].workdir)

        then: 'the nested file is copied under the same relative path'
        Files.exists(archivedWorkdir.resolve('sub/dir/out.bam'))
    }

    def 'round-trips a run that has no session id, keyed by its run name'() {
        given:
        def dir = tmp.resolve('archive')
        def store = new ArchiveStore(dir)
        def snap = new RunSnapshot(runName: 'nameless run/42', sessionId: null, status: 'OK')
        snap.tasks = [new TaskInfo(process: 'X', name: 'X (1)', exit: '0')]

        when:
        store.save(snap, false)

        then: 'the run-name is sanitised into a safe file name'
        Files.exists(dir.resolve('nameless_run_42.json'))

        when:
        def loaded = store.load('nameless run/42')

        then:
        loaded.runName == 'nameless run/42'
        loaded.sessionId == null
        loaded.tasks.size() == 1
    }

    def 'load is ambiguous when a session-id prefix matches multiple archived runs'() {
        given:
        def store = new ArchiveStore(tmp.resolve('archive'))
        store.save(sampleSnapshot(UUID.fromString('abcd1111-0000-0000-0000-000000000000'), 'run_a'), false)
        store.save(sampleSnapshot(UUID.fromString('abcd2222-0000-0000-0000-000000000000'), 'run_b'), false)

        expect: 'the shared prefix matches both'
        store.has('abcd')

        when:
        store.load('abcd')

        then:
        thrown(IllegalArgumentException)
    }

    def 'coerces non-primitive raw trace values to strings without breaking numeric parsing'() {
        given: 'a raw field that is neither String, Number nor Boolean'
        def dir = tmp.resolve('archive')
        def store = new ArchiveStore(dir)
        def snap = sampleSnapshot(UUID.randomUUID(), 'coerce')
        snap.tasks[0].raw['weird'] = Paths.get('/some/opaque/path')

        when:
        store.save(snap, false)
        def t = store.load('coerce').tasks[0]

        then: 'the opaque value survives as a string'
        t.raw['weird'] instanceof String
        t.raw['weird'] == Paths.get('/some/opaque/path').toString()

        and: 'genuine numeric fields still parse after the round-trip'
        t.numeric('realtime') == 4000L
    }
}
