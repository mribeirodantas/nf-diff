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

class RunComparatorTest extends Specification {

    @TempDir
    Path projectDir

    private TaskInfo task(Map args) {
        def t = new TaskInfo(
                process: args.process as String,
                name: args.name as String,
                hash: args.hash as String,
                status: args.status as String,
                exit: args.exit as String,
                tag: args.tag as String,
                script: args.script as String,
                container: args.container as String )
        t.display = (args.display as Map<String,String>) ?: [:]
        t.raw = (args.raw as Map<String,Object>) ?: [:]
        return t
    }

    private RunSnapshot snap(String name, List<TaskInfo> tasks, String command = null, String status = 'OK') {
        return new RunSnapshot(
                requestedId: name, runName: name,
                sessionId: UUID.randomUUID(), status: status,
                command: command,
                tasks: tasks )
    }

    def 'retried attempts are collapsed to the final attempt, recorded as a warning and kept in failures'() {
        given:
        def a = snap('runA', [
                task(process: 'FOO', name: 'FOO (1)', hash: 'aa', status: 'FAILED', exit: '1', raw: [attempt: 1]),
                task(process: 'FOO', name: 'FOO (1)', hash: 'bb', status: 'COMPLETED', exit: '0', raw: [attempt: 2]) ])
        def b = snap('runB', [
                task(process: 'FOO', name: 'FOO (1)', hash: 'bb', status: 'COMPLETED', exit: '0', raw: [attempt: 2]) ])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.tasks.size() == 1
        diff.tasks[0].a.hash == 'bb'
        diff.warnings.any { it.contains('multiple attempts') && it.contains('FOO (1)') }
        diff.failuresA.size() == 1
        diff.failuresA[0].attempt == 1L
    }

    def 'warns when a process has several untagged tasks'() {
        given:
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)'), task(process: 'FOO', name: 'FOO (2)')])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)'), task(process: 'FOO', name: 'FOO (2)')])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.warnings.any { it.contains('FOO') && it.contains("'tag'") }
    }

    def 'classifies added, removed, changed and unchanged tasks'() {
        given:
        def a = snap('runA', [
                task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi']),
                task(process: 'BAR', name: 'BAR (1)', display: [status: 'COMPLETED', script: 'run x']),
                task(process: 'GONE', name: 'GONE (1)', display: [status: 'COMPLETED']),
        ])
        def b = snap('runB', [
                task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi']), // unchanged
                task(process: 'BAR', name: 'BAR (1)', display: [status: 'COMPLETED', script: 'run y']),   // changed
                task(process: 'NEW', name: 'NEW (1)', display: [status: 'COMPLETED']),                    // added
        ])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.tasksUnchanged == 1
        diff.tasksChanged == 1
        diff.tasksAdded == 1
        diff.tasksRemoved == 1
        !diff.identical

        and: 'the changed task records a script field diff'
        def changed = diff.tasks.find { it.kind == DiffResult.Kind.CHANGED }
        changed.key == 'BAR (1)'
        changed.fieldDiffs.find { it.field == 'script' }.changed
    }

    def 'flags a container version change in the software layer'() {
        given: 'the same process running a different container tag between runs'
        def a = snap('runA', [task(process: 'FASTQC', name: 'FASTQC (1)',
                display: [status: 'COMPLETED'], container: 'biocontainers/fastqc:0.11.9--0')])
        def b = snap('runB', [task(process: 'FASTQC', name: 'FASTQC (1)',
                display: [status: 'COMPLETED'], container: 'biocontainers/fastqc:0.12.1--0')])

        when:
        def diff = new RunComparator().compare(a, b)

        then: 'the software layer reports the process as changed'
        def sw = diff.software.find { it.process == 'FASTQC' }
        sw.kind == DiffResult.Kind.CHANGED
        sw.containerChanged
        !sw.condaChanged
        sw.containersA == ['biocontainers/fastqc:0.11.9--0']
        sw.containersB == ['biocontainers/fastqc:0.12.1--0']

        and: 'the change breaks the identical verdict'
        diff.hasSoftwareChanges()
        !diff.identical
    }

    def 'flags a conda spec change even when containers match'() {
        given:
        def a = snap('runA', [task(process: 'SALMON', name: 'SALMON (1)',
                display: [status: 'COMPLETED', conda: 'bioconda::salmon=1.9.0'])])
        def b = snap('runB', [task(process: 'SALMON', name: 'SALMON (1)',
                display: [status: 'COMPLETED', conda: 'bioconda::salmon=1.10.1'])])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        def sw = diff.software.find { it.process == 'SALMON' }
        sw.kind == DiffResult.Kind.CHANGED
        sw.condaChanged
        !sw.containerChanged
        !diff.identical
    }

    def 'treats an identical software environment as unchanged'() {
        given:
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'COMPLETED'], container: 'ubuntu:22.04')])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'COMPLETED'], container: 'ubuntu:22.04')])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        def sw = diff.software.find { it.process == 'FOO' }
        sw.kind == DiffResult.Kind.UNCHANGED
        !diff.hasSoftwareChanges()
        diff.identical
    }

    def 'identical runs report no differences'() {
        given:
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])]
        def a = snap('runA', tasks.collect { it })
        def b = snap('runB', tasks.collect { it })

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.tasksChanged == 0
        diff.tasksAdded == 0
        diff.tasksRemoved == 0
        diff.tasksUnchanged == 1
    }

    def 'obvious-only differences are not flagged by default'() {
        given: 'two tasks that differ only in always-changing fields'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'COMPLETED', script: 'echo hi',
                          realtime: '10s', workdir: '/work/aa'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'COMPLETED', script: 'echo hi',
                          realtime: '12s', workdir: '/work/bb'])])

        when: 'the default (meaningful-only) comparison'
        def diff = new RunComparator().compare(a, b)

        then: 'the task is unchanged and the runs are identical'
        diff.tasksChanged == 0
        diff.tasksUnchanged == 1
        diff.identical
        !diff.showObvious
    }

    def 'obvious differences are flagged in verbose mode'() {
        given: 'two tasks that differ only in always-changing fields'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'COMPLETED', script: 'echo hi',
                          realtime: '10s', workdir: '/work/aa'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'COMPLETED', script: 'echo hi',
                          realtime: '12s', workdir: '/work/bb'])])

        when: 'the verbose comparison'
        def diff = new RunComparator(new CompareOptions(showObvious: true)).compare(a, b)

        then: 'the task is now flagged as changed'
        diff.tasksChanged == 1
        diff.tasksUnchanged == 0
        !diff.identical
        diff.showObvious
    }

    def 'run-level counts respect --only so filtered-out processes do not flip the verdict (#13)'() {
        given:
        def foo = { task(process: 'FOO', name: 'FOO (1)', hash: 'h', display: [status: 'COMPLETED']) }
        def bar = task(process: 'BAR', name: 'BAR (1)', hash: 'g', display: [status: 'COMPLETED'])
        def a = snap('runA', [foo(), bar])
        def b = snap('runB', [foo()])

        when:
        def diff = new RunComparator(new CompareOptions(filter: ProcessFilter.of(['FOO'], []))).compare(a, b)

        then:
        diff.identical
        diff.metadata.find { it.field == 'Task count (filtered)' }.valueA == '1'
    }

    def 'run-level counts respect --exclude (#13)'() {
        given:
        def foo = { task(process: 'FOO', name: 'FOO (1)', hash: 'h', display: [status: 'COMPLETED']) }
        def bar = task(process: 'BAR', name: 'BAR (1)', hash: 'g', display: [status: 'COMPLETED'])
        def a = snap('runA', [foo(), bar])
        def b = snap('runB', [foo()])

        when:
        def diff = new RunComparator(new CompareOptions(filter: ProcessFilter.of([], ['BAR']))).compare(a, b)

        then:
        diff.identical
        diff.metadata.find { it.field == 'Distinct processes (filtered)' }.valueA == '1'
    }

    def 'a differing cached-task count is context, not a change (#20)'() {
        given: 'run B is a -resume of run A: same task, now served from cache'
        def ta = task(process: 'FOO', name: 'FOO (1)', hash: 'h', display: [status: 'COMPLETED'])
        def tb = task(process: 'FOO', name: 'FOO (1)', hash: 'h', display: [status: 'COMPLETED'])
        tb.cached = true
        def a = snap('runA', [ta])
        def b = snap('runB', [tb])

        when:
        def quiet = new RunComparator().compare(a, b)
        def verbose = new RunComparator(new CompareOptions(showObvious: true)).compare(a, b)

        then: 'the row is shown but marked obvious, so the runs are identical by default'
        def row = quiet.metadata.find { it.field == 'Cached tasks' }
        row.changed
        row.obvious
        quiet.identical

        and: '--verbose still flags it'
        verbose.metadata.find { it.field == 'Cached tasks' }.isHighlighted(true)
        !verbose.identical
    }

    def 'meaningful differences are still flagged in default mode'() {
        given: 'tasks differing in a meaningful field (exit) and an obvious one'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'COMPLETED', exit: '0', realtime: '10s'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)',
                display: [status: 'FAILED', exit: '1', realtime: '99s'])])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.tasksChanged == 1
        !diff.identical
    }

    def 'diffs launch-command params and options, options first then params'() {
        given:
        def task = task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'x'])
        def a = snap('runA', [task], 'nextflow run main.nf -profile docker --genome GRCh38 --input a.csv')
        def b = snap('runB', [task], 'nextflow run main.nf -profile singularity --genome GRCh38 --input b.csv')

        when:
        def diff = new RunComparator().compare(a, b)
        def byField = diff.params.collectEntries { [(it.field): it] }

        then: 'single-dash options sort before double-dash params'
        diff.params*.field == ['-profile', '--genome', '--input']

        and: 'changed flags are highlighted, unchanged ones are not'
        byField['-profile'].valueA == 'docker' && byField['-profile'].valueB == 'singularity'
        byField['-profile'].isHighlighted(false)
        byField['--input'].isHighlighted(false)
        !byField['--genome'].isHighlighted(false)

        and: 'a param change breaks identical'
        !diff.identical
    }

    def 'a param present in only one run shows a missing value on the other side'() {
        given:
        def task = task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'x'])
        def a = snap('runA', [task], 'nextflow run main.nf --input a.csv')
        def b = snap('runB', [task], 'nextflow run main.nf --input a.csv -resume')

        when:
        def diff = new RunComparator().compare(a, b)
        def resume = diff.params.find { it.field == '-resume' }

        then:
        resume.valueA == null
        resume.valueB == 'true'

        and: '-resume is an obvious option, so it does not break identical by default'
        !resume.isHighlighted(false)
        diff.identical
    }

    def 'flags recomputed tasks when the cache hash differs'() {
        given: 'the same task name with a different cache hash between runs'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', hash: 'aa/1111',
                display: [hash: 'aa/1111', status: 'COMPLETED', script: 'echo hi'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', hash: 'bb/2222',
                display: [hash: 'bb/2222', status: 'COMPLETED', script: 'echo hi'])])

        when:
        def diff = new RunComparator().compare(a, b)

        then: 'the hash change marks the task recomputed and is recorded on the hash field'
        diff.tasksRecomputed == 1
        diff.tasks.find { it.key == 'FOO (1)' }.fieldDiffs.find { it.field == 'hash' }.changed

        and: 'but the hash is an always-changing field (session UUID), so it is not highlighted by default'
        !diff.tasks.find { it.key == 'FOO (1)' }.fieldDiffs.find { it.field == 'hash' }.isHighlighted(false)
    }

    def 'identical hashes are not counted as recomputed'() {
        given:
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', hash: 'aa/1111',
                display: [hash: 'aa/1111', status: 'COMPLETED', script: 'echo hi'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', hash: 'aa/1111',
                display: [hash: 'aa/1111', status: 'COMPLETED', script: 'echo hi'])])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.tasksRecomputed == 0
        diff.identical
    }

    def 'a hash-only difference is recomputed but does not break identical by default'() {
        given: 'two matched tasks identical in every field except the cache hash'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', hash: 'aa/1111',
                display: [hash: 'aa/1111', status: 'COMPLETED', script: 'echo hi'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', hash: 'bb/2222',
                display: [hash: 'bb/2222', status: 'COMPLETED', script: 'echo hi'])])

        when: 'the default (meaningful-only) comparison'
        def diff = new RunComparator().compare(a, b)

        then: 'the hash change is surfaced as a recompute, but the runs stay identical'
        diff.tasksRecomputed == 1
        diff.tasksChanged == 0
        diff.tasksUnchanged == 1
        diff.identical

        when: 'the verbose comparison'
        def verbose = new RunComparator(new CompareOptions(showObvious: true)).compare(a, b)

        then: 'the hash change is now flagged as a difference'
        verbose.showObvious
        verbose.tasksRecomputed == 1
        verbose.tasksChanged == 1
        !verbose.identical
    }

    def 'detects performance regressions beyond the threshold, worst first'() {
        given: 'two matched tasks whose runtime and memory move by varying amounts'
        def a = snap('runA', [
                task(process: 'SLOW', name: 'SLOW (1)', hash: 'h1',
                        display: [status: 'COMPLETED', realtime: '10s', peak_rss: '1 GB'],
                        raw: [realtime: 10_000L, peak_rss: 1_000_000_000L]),
                task(process: 'STEADY', name: 'STEADY (1)', hash: 'h2',
                        display: [status: 'COMPLETED', realtime: '10s'],
                        raw: [realtime: 10_000L]),
        ])
        def b = snap('runB', [
                task(process: 'SLOW', name: 'SLOW (1)', hash: 'h1',
                        display: [status: 'COMPLETED', realtime: '30s', peak_rss: '1.1 GB'],
                        raw: [realtime: 30_000L, peak_rss: 1_100_000_000L]),
                task(process: 'STEADY', name: 'STEADY (1)', hash: 'h2',
                        display: [status: 'COMPLETED', realtime: '11s'],
                        raw: [realtime: 11_000L]),
        ])

        when:
        def diff = new RunComparator().compare(a, b)

        then: 'only the +200% realtime and +10% (below 25% threshold) are considered'
        // SLOW realtime +200% and peak_rss +10% -> only realtime clears 25%;
        // STEADY realtime +10% is below threshold and dropped.
        diff.regressions*.metric == ['realtime']
        diff.regressions[0].taskKey == 'SLOW (1)'
        diff.regressions[0].pctDelta == 200.0d
        diff.regressions[0].regression
        diff.regressions[0].sameHash
        diff.perfThreshold == RunComparator.DEFAULT_PERF_THRESHOLD

        and: 'a runtime regression alone does not break identical (obvious fields)'
        diff.identical
    }

    def 'a performance regression breaks identical in verbose mode'() {
        given: 'two matched tasks (same cache hash) whose runtime regresses well past the threshold'
        def a = snap('runA', [task(process: 'SLOW', name: 'SLOW (1)', hash: 'h1',
                display: [status: 'COMPLETED', realtime: '10s'], raw: [realtime: 10_000L])])
        def b = snap('runB', [task(process: 'SLOW', name: 'SLOW (1)', hash: 'h1',
                display: [status: 'COMPLETED', realtime: '30s'], raw: [realtime: 30_000L])])

        when: 'the verbose comparison'
        def diff = new RunComparator(new CompareOptions(showObvious: true)).compare(a, b)

        then: 'the regression is flagged and now counts as a difference'
        diff.showObvious
        diff.regressionCount() == 1
        !diff.identical
    }

    def 'a custom perf threshold widens or narrows what is flagged'() {
        given:
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED', realtime: '10s'], raw: [realtime: 10_000L])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED', realtime: '11s'], raw: [realtime: 11_000L])])

        expect: 'the default 25% threshold ignores a +10% change'
        new RunComparator().compare(a, b).regressions.isEmpty()

        and: 'a 5% threshold flags it'
        def diff = new RunComparator(new CompareOptions(perfThreshold: 5.0d)).compare(a, b)
        diff.regressions*.metric == ['realtime']
        diff.regressions[0].pctDelta == 10.0d
    }

    def 'a zero perf threshold flags any measurable change but never an unchanged metric (#19)'() {
        given: 'realtime is unchanged and peak RSS moves by a single byte'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED'], raw: [realtime: 10_000L, peak_rss: 1_000_000L])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED'], raw: [realtime: 10_000L, peak_rss: 1_000_001L])])

        when:
        def diff = new RunComparator(new CompareOptions(perfThreshold: 0.0d)).compare(a, b)

        then: 'only the metric that actually moved is flagged'
        diff.regressions*.metric == ['peak_rss']
        diff.regressions.every { it.pctDelta != 0.0d }
    }

    def 'sub-second realtime changes are below timing resolution and never flagged'() {
        given: 'run A timed at whole-second resolution (0 ms), run B at ms resolution'
        def a = snap('runA', [
                task(process: 'SHORT', name: 'SHORT (1)', hash: 'h1',
                        display: [status: 'COMPLETED', realtime: '0ms'], raw: [realtime: 0L]),
                task(process: 'LONGER', name: 'LONGER (1)', hash: 'h2',
                        display: [status: 'COMPLETED', realtime: '0ms'], raw: [realtime: 0L]),
        ])
        def b = snap('runB', [
                task(process: 'SHORT', name: 'SHORT (1)', hash: 'h1',
                        display: [status: 'COMPLETED', realtime: '626ms'], raw: [realtime: 626L]),
                task(process: 'LONGER', name: 'LONGER (1)', hash: 'h2',
                        display: [status: 'COMPLETED', realtime: '1.5s'], raw: [realtime: 1_500L]),
        ])

        expect: 'a 0 -> 626 ms move is dropped, even with a zero percentage threshold'
        def diff = new RunComparator(new CompareOptions(perfThreshold: 0.0d)).compare(a, b)
        diff.regressions*.taskKey == ['LONGER (1)']

        and: 'a change of at least one second is still flagged'
        diff.regressions[0].pctDelta == Double.POSITIVE_INFINITY
    }

    def '--diff-inputs reports a changed staged input and counts it as a difference'() {
        given: 'the same task in both runs, staging different versions of an input'
        def mk = { String run, String content ->
            def src = java.nio.file.Files.write(projectDir.resolve("${run}-reads.tsv".toString()), content.bytes)
            def dir = java.nio.file.Files.createDirectories(projectDir.resolve("work-${run}".toString()))
            java.nio.file.Files.createSymbolicLink(dir.resolve('reads.tsv'), src)
            def t = task(process: 'COUNT', name: 'COUNT (1)', hash: 'h', display: [status: 'COMPLETED'])
            t.workdir = dir.toString()
            snap(run, [t])
        }
        def a = mk('A', 'r1\t10\n')
        def b = mk('B', 'r1\t11\n')

        when:
        def off = new RunComparator().compare(a, b)
        def on = new RunComparator(new CompareOptions(diffInputs: true)).compare(a, b)

        then: 'without the layer nothing differs'
        off.identical
        !off.diffInputs

        and: 'with it, the input change is reported in every format and breaks identical'
        !on.identical
        on.inputsChangedCount() == 1
        on.inputs[0].files[0].path == 'reads.tsv'
        new MarkdownReportRenderer().render(on).contains('## Input files')
        new HtmlReportRenderer().render(on).contains('<section id="inputs"')
        (new groovy.json.JsonSlurper().parseText(new JsonReportRenderer().render(on)) as Map).summary.inputsChanged == 1
    }

    def 'improvements are captured but not counted as regressions'() {
        given: 'run B is twice as fast'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED', realtime: '20s'], raw: [realtime: 20_000L])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED', realtime: '10s'], raw: [realtime: 10_000L])])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.regressions.size() == 1
        diff.regressions[0].pctDelta == -50.0d
        !diff.regressions[0].regression
    }

    def 'process diff counts tasks per process'() {
        given:
        def a = snap('runA', [
                task(process: 'FOO', name: 'FOO (1)'),
                task(process: 'FOO', name: 'FOO (2)'),
        ])
        def b = snap('runB', [
                task(process: 'FOO', name: 'FOO (1)'),
        ])

        when:
        def diff = new RunComparator().compare(a, b)
        def foo = diff.processes.find { it.process == 'FOO' }

        then:
        foo.countA == 2
        foo.countB == 1
        foo.changed
    }

    def '--only restricts the comparison to matching processes'() {
        given:
        def a = snap('runA', [
                task(process: 'ALIGN:BWA', name: 'ALIGN:BWA (1)', display: [status: 'COMPLETED', script: 'x']),
                task(process: 'QC:FASTQC', name: 'QC:FASTQC (1)', display: [status: 'COMPLETED', script: 'y']),
        ])
        def b = snap('runB', [
                task(process: 'ALIGN:BWA', name: 'ALIGN:BWA (1)', display: [status: 'COMPLETED', script: 'x2']),
                task(process: 'QC:FASTQC', name: 'QC:FASTQC (1)', display: [status: 'COMPLETED', script: 'y2']),
        ])

        when:
        def diff = new RunComparator(new CompareOptions(filter: ProcessFilter.of(['ALIGN:*'], []))).compare(a, b)

        then: 'only the aligned process is present in both layers'
        diff.processes*.process == ['ALIGN:BWA']
        diff.tasks.every { it.process() == 'ALIGN:BWA' }
        diff.tasksChanged == 1
    }

    def '--exclude drops matching processes from the comparison'() {
        given:
        def a = snap('runA', [
                task(process: 'ALIGN:BWA', name: 'ALIGN:BWA (1)', display: [status: 'COMPLETED', script: 'x']),
                task(process: 'QC:FASTQC', name: 'QC:FASTQC (1)', display: [status: 'COMPLETED', script: 'y']),
        ])
        def b = snap('runB', [
                task(process: 'ALIGN:BWA', name: 'ALIGN:BWA (1)', display: [status: 'COMPLETED', script: 'x']),
                task(process: 'QC:FASTQC', name: 'QC:FASTQC (1)', display: [status: 'COMPLETED', script: 'y2']),
        ])

        when: 'the only changing process is excluded'
        def diff = new RunComparator(new CompareOptions(filter: ProcessFilter.of([], ['QC:*']))).compare(a, b)

        then: 'the remaining process is unchanged, so the filtered runs look identical'
        diff.processes*.process == ['ALIGN:BWA']
        diff.tasksChanged == 0
        diff.identical
    }

    def 'renderer produces a self-contained HTML document'() {
        given:
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo bye'])])
        def diff = new RunComparator().compare(a, b)

        when:
        def html = new HtmlReportRenderer().render(diff)

        then:
        html.startsWith('<!DOCTYPE html>')
        html.contains('nf-diff')
        html.contains('Run comparison')
        html.contains('<style>')
        html.contains('code-diff')      // script diff rendered
        !html.contains('<script src')   // no external scripts
    }

    // -- configuration layer ------------------------------------------------

    private void writeConfig(String content) {
        Files.write(projectDir.resolve('nextflow.config'), content.getBytes('UTF-8'))
    }

    def 'configuration layer diffs the resolved config between two profiles'() {
        given:
        writeConfig('''
            process.cpus = 1
            docker.enabled = false
            profiles {
                docker { docker.enabled = true; process.cpus = 8 }
            }
        '''.stripIndent())
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf -profile docker')

        when:
        def diff = new RunComparator(new CompareOptions(baseDir: projectDir)).compare(a, b)

        then: 'process.cpus and docker.enabled show up as config changes'
        def cpus = diff.config.find { it.field == 'process.cpus' }
        cpus.valueA == '1' && cpus.valueB == '8' && cpus.changed
        def docker = diff.config.find { it.field == 'docker.enabled' }
        docker.valueA == 'false' && docker.valueB == 'true' && docker.changed
        and: 'the note explains this is resolved from current on-disk config'
        diff.configNote?.contains('on-disk config')
    }

    def 'configuration layer is skipped when no project directory is given'() {
        given:
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf -profile docker')

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.config == null || diff.config.isEmpty()
        diff.configNote?.contains('skipped')
    }

    def 'cross-project mode resolves config and params from each run\'s own directory'() {
        given: 'two separate project dirs with different nextflow.config values'
        def dirA = Files.createDirectories(projectDir.resolve('projA'))
        def dirB = Files.createDirectories(projectDir.resolve('projB'))
        Files.write(dirA.resolve('nextflow.config'), 'process.cpus = 2\n'.getBytes('UTF-8'))
        Files.write(dirB.resolve('nextflow.config'), 'process.cpus = 16\n'.getBytes('UTF-8'))
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')

        when: 'run A is resolved from dirA and run B from dirB'
        def diff = new RunComparator(new CompareOptions(baseDirA: dirA, baseDirB: dirB)).compare(a, b)

        then: 'the per-directory config values are diffed against each other'
        def cpus = diff.config.find { it.field == 'process.cpus' }
        cpus.valueA == '2'
        cpus.valueB == '16'
        cpus.changed

        and: 'the note names both project directories'
        diff.configNote.contains('run A')
        diff.configNote.contains('run B')

        and: 'provenance is flagged cross-project with both directories recorded'
        diff.configProvenance.crossProject
        diff.configProvenance.dirA == dirA.toString()
        diff.configProvenance.dirB == dirB.toString()
    }

    def 'parameter field diffs carry the CLI-vs-file source of each value'() {
        given:
        Files.write(projectDir.resolve('p.json'),
                '{"input":"from-file.csv"}'.getBytes('UTF-8'))
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf -params-file p.json')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf --input from-cli.csv')

        when:
        def diff = new RunComparator(new CompareOptions(baseDir: projectDir)).compare(a, b)

        then:
        def input = diff.params.find { it.field == '--input' }
        input.valueA == 'from-file.csv'
        input.valueB == 'from-cli.csv'
        input.sourceA == CommandParams.SRC_FILE
        input.sourceB == CommandParams.SRC_CLI
    }

    // -- outputs layer ------------------------------------------------------

    private TaskInfo taskWithWork(String workdir) {
        return new TaskInfo(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED', script: 'echo hi'], workdir: workdir)
    }

    def 'outputs layer is not computed unless --diff-outputs is enabled'() {
        given:
        def dirA = Files.createDirectories(projectDir.resolve('wa'))
        def dirB = Files.createDirectories(projectDir.resolve('wb'))
        Files.write(dirA.resolve('out.txt'), 'AAAA'.bytes)
        Files.write(dirB.resolve('out.txt'), 'BBBB'.bytes)
        def a = snap('runA', [taskWithWork(dirA.toString())])
        def b = snap('runB', [taskWithWork(dirB.toString())])

        when: 'default comparison (no output diffing)'
        def diff = new RunComparator().compare(a, b)

        then:
        !diff.diffOutputs
        diff.outputs.isEmpty()
        diff.identical // tasks match on every inspected field
    }

    def 'enabling --diff-outputs surfaces a content change and breaks identical'() {
        given: 'matched tasks whose only difference is their output file content'
        def dirA = Files.createDirectories(projectDir.resolve('wa'))
        def dirB = Files.createDirectories(projectDir.resolve('wb'))
        Files.write(dirA.resolve('out.txt'), 'AAAA'.bytes)
        Files.write(dirB.resolve('out.txt'), 'BBBB'.bytes) // same size, different content
        def a = snap('runA', [taskWithWork(dirA.toString())])
        def b = snap('runB', [taskWithWork(dirB.toString())])

        when:
        def diff = new RunComparator(new CompareOptions(diffOutputs: true)).compare(a, b)

        then:
        diff.diffOutputs
        diff.outputs.size() == 1
        diff.outputs[0].hasChanges()
        diff.hasOutputChanges()
        !diff.identical
    }

    // -- logs layer ---------------------------------------------------------

    def 'logs layer is not computed unless --diff-logs is enabled'() {
        given:
        def dirA = Files.createDirectories(projectDir.resolve('la'))
        def dirB = Files.createDirectories(projectDir.resolve('lb'))
        Files.write(dirA.resolve('.command.err'), 'ok'.bytes)
        Files.write(dirB.resolve('.command.err'), 'boom'.bytes)
        def a = snap('runA', [taskWithWork(dirA.toString())])
        def b = snap('runB', [taskWithWork(dirB.toString())])

        when: 'default comparison (no log diffing)'
        def diff = new RunComparator().compare(a, b)

        then:
        !diff.diffLogs
        diff.logs.isEmpty()
    }

    def 'enabling --diff-logs surfaces a log change without breaking identical'() {
        given: 'matched, otherwise-identical tasks whose stderr differs'
        def dirA = Files.createDirectories(projectDir.resolve('la'))
        def dirB = Files.createDirectories(projectDir.resolve('lb'))
        Files.write(dirA.resolve('.command.err'), 'all good\n'.bytes)
        Files.write(dirB.resolve('.command.err'), 'warning: retry\n'.bytes)
        def a = snap('runA', [taskWithWork(dirA.toString())])
        def b = snap('runB', [taskWithWork(dirB.toString())])

        when:
        def diff = new RunComparator(new CompareOptions(diffLogs: true)).compare(a, b)

        then:
        diff.diffLogs
        diff.logs.size() == 1
        diff.logs[0].hasChanges()
        diff.hasLogChanges()
        diff.identical // logs are informational only
    }

    // -- config provenance --------------------------------------------------

    private void gitInProject(String... args) {
        final cmd = (['git'] + (args as List)) as List<String>
        final proc = new ProcessBuilder(cmd).directory(projectDir.toFile()).start()
        proc.inputStream.text
        proc.errorStream.text
        assert proc.waitFor() == 0
    }

    private String initGitWithConfig(String content) {
        gitInProject('init', '-q')
        gitInProject('config', 'user.email', 'test@example.com')
        gitInProject('config', 'user.name', 'Test')
        writeConfig(content)
        gitInProject('add', '.')
        gitInProject('commit', '-q', '-m', 'initial')
        final proc = new ProcessBuilder(['git', 'rev-parse', 'HEAD'] as List<String>)
                .directory(projectDir.toFile()).start()
        final head = proc.inputStream.text.trim()
        proc.errorStream.text
        assert proc.waitFor() == 0
        return head
    }

    /** Commit a config change on top of the current HEAD and return the new HEAD. */
    private String commitConfig(String content) {
        writeConfig(content)
        gitInProject('commit', '-q', '-am', 'change config')
        final proc = new ProcessBuilder(['git', 'rev-parse', 'HEAD'] as List<String>)
                .directory(projectDir.toFile()).start()
        final head = proc.inputStream.text.trim()
        proc.errorStream.text
        assert proc.waitFor() == 0
        return head
    }

    @spock.lang.Requires({ GitProvenanceTest.isGitAvailable() })
    def 'config provenance warns when a run was launched at a different git revision'() {
        given: 'run A launched at an older commit of this repo, run B at HEAD'
        def older = initGitWithConfig('process.cpus = 1\n')
        def head = commitConfig('process.cpus = 2\n')
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')
        a.revisionId = older
        b.revisionId = head

        when:
        def diff = new RunComparator(new CompareOptions(baseDir: projectDir)).compare(a, b)

        then:
        diff.configProvenance != null
        diff.configProvenance.gitAvailable
        diff.configProvenance.driftedA
        !diff.configProvenance.driftedB
        diff.configProvenance.warning().contains('run A was')

        and: 'the caveat is rendered in every format'
        new MarkdownReportRenderer().render(diff).contains('Config provenance')
        new HtmlReportRenderer().render(diff).contains('warn-note')
        new JsonReportRenderer().render(diff).contains('driftedA')
    }

    @spock.lang.Requires({ GitProvenanceTest.isGitAvailable() })
    def 'config provenance is clean when both runs match HEAD on an unmodified tree'() {
        given:
        def head = initGitWithConfig('process.cpus = 1\n')
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')
        a.revisionId = head
        b.revisionId = head

        when:
        def diff = new RunComparator(new CompareOptions(baseDir: projectDir)).compare(a, b)

        then:
        diff.configProvenance.gitAvailable
        !diff.configProvenance.hasWarning()
        diff.configProvenance.warning() == null
    }

    @spock.lang.Requires({ GitProvenanceTest.isGitAvailable() })
    def 'a revision that is not a commit of the launch repo is not reported as drift (#9): #desc'() {
        given: 'a clean git repo and two runs recording a non-commit revision, as Nextflow does'
        initGitWithConfig('process.cpus = 1\n')
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')
        a.revisionId = revA
        b.revisionId = revB

        when:
        def diff = new RunComparator(new CompareOptions(baseDir: projectDir)).compare(a, b)

        then: 'git is known, but nothing is flagged and no warning is produced'
        diff.configProvenance.gitAvailable
        !diff.configProvenance.driftedA
        !diff.configProvenance.driftedB
        diff.configProvenance.warning() == null

        where:
        desc                                    | revA                                       | revB
        'local scriptId (32-hex script hash)'   | '47e64accfc5d8f61b1a3d9b3e0f1c2a4'         | '47e64accfc5d8f61b1a3d9b3e0f1c2a4'
        'different scriptIds per run'           | '47e64accfc5d8f61b1a3d9b3e0f1c2a4'         | '0f1e2d3c4b5a69788796a5b4c3d2e1f0'
        'remote pipeline assets commit'         | '8a7f5c0d3b2e1f4a6c9d8e7b5a4c3d2e1f0a9b8c' | '8a7f5c0d3b2e1f4a6c9d8e7b5a4c3d2e1f0a9b8c'
        'branch name recorded by an archived -r'| 'main'                                     | 'main'
    }

    @spock.lang.Requires({ GitProvenanceTest.isGitAvailable() })
    def 'both runs at the same older commit is reported once, not as differing revisions (#9)'() {
        given:
        def older = initGitWithConfig('process.cpus = 1\n')
        def head = commitConfig('process.cpus = 2\n')
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')
        a.revisionId = older
        b.revisionId = older.substring(0, 10) // abbreviated id of the same commit

        when:
        def diff = new RunComparator(new CompareOptions(baseDir: projectDir)).compare(a, b)
        def w = diff.configProvenance.warning()

        then:
        diff.configProvenance.driftedA
        diff.configProvenance.driftedB
        w.contains("both runs were launched at the same, different git revision (${older.substring(0, 10)})")
        w.contains(head.substring(0, 10))
        !w.contains('A: ')
    }

    def 'revisionDrifted only flags a confirmed repo commit that differs from HEAD (#9)'() {
        given:
        def head = 'cccccccccccccccccccccccccccccccccccccccc'
        def known = ['aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'] as Set
        def isCommit = { String r -> known.any { it.startsWith(r) } } as java.util.function.Predicate<String>
        def never = { String r -> assert false, 'should not need a git lookup'; false } as java.util.function.Predicate<String>

        expect: 'a real, different commit of the repo is drift'
        RunComparator.revisionDrifted('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', head, isCommit)
        RunComparator.revisionDrifted('aaaaaaaaaa', head, isCommit)

        and: 'a value that is not a commit of the repo (scriptId, assets commit) is not'
        !RunComparator.revisionDrifted('47e64accfc5d8f61b1a3d9b3e0f1c2a4', head, isCommit)
        !RunComparator.revisionDrifted('bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', head, isCommit)

        and: 'matching HEAD (full or abbreviated) or missing values short-circuit without a git call'
        !RunComparator.revisionDrifted(head, head, never)
        !RunComparator.revisionDrifted('cccccccccc', head, never)
        !RunComparator.revisionDrifted(null, head, never)
        !RunComparator.revisionDrifted('aaaaaaaaaa', null, never)
        !RunComparator.revisionDrifted('aaaaaaaaaa', head, null)
    }

    def 'config provenance reports git unavailable outside a repository'() {
        given: 'a project dir with config but no git repo'
        writeConfig('process.cpus = 1\n')
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')

        when:
        def diff = new RunComparator(new CompareOptions(baseDir: projectDir)).compare(a, b)

        then: 'provenance exists but reports unknown git state and no warning'
        diff.configProvenance != null
        !diff.configProvenance.gitAvailable
        !diff.configProvenance.hasWarning()
    }

    def 'computes per-process resource efficiency and classifies over/tight'() {
        given: 'a process that requested 8 cores / 32 GB but peaked at ~1 core / 4 GB'
        def a = snap('runA', [task(process: 'ALIGN', name: 'ALIGN (1)',
                display: [status: 'COMPLETED'],
                raw: [cpus: 8, memory: 32L * 1024 * 1024 * 1024, '%cpu': 105.0, peak_rss: 4L * 1024 * 1024 * 1024])])
        // run B still requests 32 GB but now peaks at ~30 GB — tight against the request
        def b = snap('runB', [task(process: 'ALIGN', name: 'ALIGN (1)',
                display: [status: 'COMPLETED'],
                raw: [cpus: 8, memory: 32L * 1024 * 1024 * 1024, '%cpu': 780.0, peak_rss: 30L * 1024 * 1024 * 1024])])

        when:
        def diff = new RunComparator().compare(a, b)

        then: 'the efficiency layer surfaces the process with computable ratios'
        diff.hasEfficiency()
        def e = diff.efficiency.find { it.process == 'ALIGN' }
        e != null

        and: 'run A is over-provisioned on both CPU and memory'
        e.cpuClassA() == 'over'   // 105% of 800% requested ~ 13%
        e.memClassA() == 'over'   // 4 GB of 32 GB = 12.5%
        Math.round(e.memEffA() * 100) == 13

        and: 'run B runs tight on both'
        e.cpuClassB() == 'tight'  // 780% of 800% requested ~ 98%
        e.memClassB() == 'tight'  // 30 GB of 32 GB ~ 94%

        and: 'the summary counts reflect the shift and the layer is informational only'
        diff.overProvisionedA() == 1
        diff.tightB() == 1
    }

    def 'efficiency layer stays quiet when the cache has no usage metrics'() {
        given:
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.efficiency.isEmpty()
        !diff.hasEfficiency()
    }

    def 'renderer escapes HTML in task values'() {
        given:
        def a = snap('runA', [task(process: '<b>', name: 'X (1)', display: [status: 'COMPLETED', script: 'a'])])
        def b = snap('runB', [task(process: '<b>', name: 'X (1)', display: [status: 'COMPLETED', script: 'b'])])
        def diff = new RunComparator().compare(a, b)

        when:
        def html = new HtmlReportRenderer().render(diff)

        then:
        html.contains('&lt;b&gt;')
        !html.contains('<b>')
    }

    // ------------------------------------------------------- failure rollup

    def 'rolls up failed tasks by process/status/exit and classifies new vs resolved'() {
        given: 'run A has one ALIGN failure; run B keeps it and adds a new CALL failure'
        def a = snap('runA', [
                task(process: 'ALIGN', name: 'ALIGN (1)', status: 'FAILED', exit: '1'),
                task(process: 'QC',    name: 'QC (1)',    status: 'COMPLETED', exit: '0'),
        ])
        def b = snap('runB', [
                task(process: 'ALIGN', name: 'ALIGN (1)', status: 'FAILED', exit: '1'),
                task(process: 'QC',    name: 'QC (1)',    status: 'COMPLETED', exit: '0'),
                task(process: 'CALL',  name: 'CALL (1)',  status: 'FAILED', exit: '137'),
        ])

        when:
        def diff = new RunComparator().compare(a, b)

        then: 'both failed tasks per run are collected'
        diff.hasFailures()
        diff.failedCountA() == 1
        diff.failedCountB() == 2

        and: 'the persistent ALIGN group and the new CALL group are distinguished'
        def align = diff.failureGroups.find { it.process == 'ALIGN' }
        align.countA == 1 && align.countB == 1
        align.isPersistent()
        def call = diff.failureGroups.find { it.process == 'CALL' }
        call.exit == '137'
        call.isNew()
        diff.newFailureCount() == 1
        diff.resolvedFailureCount() == 0
    }

    def 'flags a failure that was resolved in run B'() {
        given:
        def a = snap('runA', [task(process: 'ALIGN', name: 'ALIGN (1)', status: 'FAILED', exit: '1')])
        def b = snap('runB', [task(process: 'ALIGN', name: 'ALIGN (1)', status: 'COMPLETED', exit: '0')])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        def align = diff.failureGroups.find { it.process == 'ALIGN' }
        align.isResolved()
        diff.resolvedFailureCount() == 1
        diff.newFailureCount() == 0
    }

    def 'reports a run-level failure even with no failed tasks'() {
        given: 'run B finished in an error state but every recorded task completed'
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', status: 'COMPLETED', exit: '0')])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', status: 'COMPLETED', exit: '0')],
                null, 'ERR')

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.hasFailures()
        diff.failureGroups.isEmpty()
        DiffResult.runFailed(diff.runB)
        !DiffResult.runFailed(diff.runA)
    }

    def 'the failure rollup does not by itself break the identical verdict'() {
        given: 'both runs fail identically (same process/status/exit)'
        def a = snap('runA', [task(process: 'ALIGN', name: 'ALIGN (1)', status: 'FAILED', exit: '1',
                display: [status: 'FAILED', exit: '1'])])
        def b = snap('runB', [task(process: 'ALIGN', name: 'ALIGN (1)', status: 'FAILED', exit: '1',
                display: [status: 'FAILED', exit: '1'])])

        when:
        def diff = new RunComparator().compare(a, b)

        then: 'failures are surfaced but the runs remain identical'
        diff.hasFailures()
        diff.failureGroups.find { it.process == 'ALIGN' }.isPersistent()
        diff.identical
    }

    def 'renders the failure rollup section in all three formats'() {
        given:
        def a = snap('runA', [task(process: 'ALIGN', name: 'ALIGN (1)', status: 'COMPLETED', exit: '0',
                display: [status: 'COMPLETED', exit: '0'])])
        def b = snap('runB', [task(process: 'ALIGN', name: 'ALIGN (1)', status: 'FAILED', exit: '137',
                display: [status: 'FAILED', exit: '137'])])
        def diff = new RunComparator().compare(a, b)

        expect: 'HTML has the failures section'
        def html = new HtmlReportRenderer().render(diff)
        html.contains('id="failures"')
        html.contains('137')

        and: 'Markdown has the rollup heading and the new-failure row'
        def md = new MarkdownReportRenderer().render(diff)
        md.contains('## Failure rollup')
        md.contains('new')

        and: 'JSON carries the failures block and summary counts'
        def json = new groovy.json.JsonSlurper().parseText(new JsonReportRenderer().render(diff)) as Map
        json.summary.newFailures == 1
        (json.failures as Map).groups.find { (it as Map).process == 'ALIGN' }.state == 'new'
    }

    def 'a renamed process is reported once as renamed and its tasks are matched across the rename'() {
        given: 'EXAMPLEFOO in run A becomes EXAMPLE_FOO in run B, same scripts and tags'
        def a = snap('runA', [
                task(process: 'EXAMPLEFOO', name: 'EXAMPLEFOO (s1)', tag: 's1', hash: 'h1', script: 'foo s1.txt',
                        display: [status: 'COMPLETED', script: 'foo s1.txt', tag: 's1']),
                task(process: 'EXAMPLEFOO', name: 'EXAMPLEFOO (s2)', tag: 's2', hash: 'h2', script: 'foo s2.txt',
                        display: [status: 'COMPLETED', script: 'foo s2.txt', tag: 's2']) ])
        def b = snap('runB', [
                task(process: 'EXAMPLE_FOO', name: 'EXAMPLE_FOO (s1)', tag: 's1', hash: 'h1', script: 'foo s1.txt',
                        display: [status: 'COMPLETED', script: 'foo s1.txt', tag: 's1']),
                task(process: 'EXAMPLE_FOO', name: 'EXAMPLE_FOO (s2)', tag: 's2', hash: 'h2', script: 'foo s2.txt',
                        display: [status: 'FAILED', script: 'foo s2.txt', tag: 's2']) ])

        when:
        def diff = new RunComparator().compare(a, b)

        then: 'one process row, named as in run B, remembering run A\'s name'
        diff.processes.size() == 1
        with(diff.processes[0]) {
            process == 'EXAMPLE_FOO'
            renamedFrom == 'EXAMPLEFOO'
            countA == 2 && countB == 2
            renamed && !unchanged
            renameEvidence[0] == 'identical script in 2 matched task(s)'
        }
        diff.processesRenamed() == 1

        and: 'tasks pair up instead of showing as removed + added'
        diff.tasksAdded == 0
        diff.tasksRemoved == 0
        diff.tasks*.key as Set == ['EXAMPLE_FOO (s1)', 'EXAMPLE_FOO (s2)'] as Set
        diff.tasks.find { it.key == 'EXAMPLE_FOO (s2)' }.kind == DiffResult.Kind.CHANGED

        and: 'a rename is a change, and the input snapshot is untouched'
        !diff.identical
        a.tasks*.process == ['EXAMPLEFOO', 'EXAMPLEFOO']
        a.tasks*.name == ['EXAMPLEFOO (s1)', 'EXAMPLEFOO (s2)']

        and: 'every report says so'
        new MarkdownReportRenderer().render(diff).contains('| EXAMPLE_FOO (was EXAMPLEFOO) | 2 | 2 | renamed |')
        def html = new HtmlReportRenderer().render(diff)
        html.contains('was EXAMPLEFOO</span>')
        html.contains('<span class="pill renamed">renamed</span>')
        def json = new groovy.json.JsonSlurper().parseText(new JsonReportRenderer().render(diff)) as Map
        json.summary.processesRenamed == 1
        (json.processes as List<Map>)[0].status == 'renamed'
        (json.processes as List<Map>)[0].renamedFrom == 'EXAMPLEFOO'
    }

    def 'a removed process and an unrelated added process stay removed and added'() {
        given:
        def a = snap('runA', [task(process: 'SAMTOOLS_SORT', name: 'SAMTOOLS_SORT (s1)', tag: 's1', script: 'samtools sort s1.bam')])
        def b = snap('runB', [task(process: 'SAMTOOLS_INDEX', name: 'SAMTOOLS_INDEX (s1)', tag: 's1', script: 'samtools index s1.bam')])

        when:
        def diff = new RunComparator().compare(a, b)

        then:
        diff.processes*.process == ['SAMTOOLS_INDEX', 'SAMTOOLS_SORT']
        diff.processes.every { !it.renamed }
        diff.processes.find { it.process == 'SAMTOOLS_SORT' }.removed
        diff.processes.find { it.process == 'SAMTOOLS_INDEX' }.added
        diff.tasksAdded == 1
        diff.tasksRemoved == 1
    }

    def 'the process filter applies to the renamed process by its run B name'() {
        given:
        def a = snap('runA', [task(process: 'EXAMPLEFOO', name: 'EXAMPLEFOO (s1)', tag: 's1', script: 'foo s1')])
        def b = snap('runB', [task(process: 'EXAMPLE_FOO', name: 'EXAMPLE_FOO (s1)', tag: 's1', script: 'foo s1')])

        expect:
        new RunComparator(new CompareOptions(filter: ProcessFilter.of(['EXAMPLE_*'], []))).compare(a, b).processes*.renamedFrom == ['EXAMPLEFOO']
    }

    def 'with --detect-renames=false a renamed process stays removed and added'() {
        given:
        def a = snap('runA', [task(process: 'EXAMPLEFOO', name: 'EXAMPLEFOO (s1)', tag: 's1', script: 'foo s1')])
        def b = snap('runB', [task(process: 'EXAMPLE_FOO', name: 'EXAMPLE_FOO (s1)', tag: 's1', script: 'foo s1')])

        when:
        def diff = new RunComparator(new CompareOptions(detectRenames: false)).compare(a, b)

        then:
        diff.processesRenamed() == 0
        diff.processes.find { it.process == 'EXAMPLEFOO' }.removed
        diff.processes.find { it.process == 'EXAMPLE_FOO' }.added
        diff.tasksAdded == 1
        diff.tasksRemoved == 1
    }

    def 'DAG edges of a renamed process are compared under its run B name'() {
        given: 'run A wires EXAMPLEFOO -> CONSUME, run B the same work as EXAMPLE_FOO -> CONSUME'
        def wd = { String run, String proc ->
            java.nio.file.Files.createDirectories(projectDir.resolve(run).resolve(proc))
        }
        def stage = { Path consumer, Path producer ->
            def out = java.nio.file.Files.write(producer.resolve('out.txt'), 'data'.bytes)
            java.nio.file.Files.createSymbolicLink(consumer.resolve('out.txt'), out)
        }
        def aFoo = wd('A', 'foo'), aUse = wd('A', 'use')
        def bFoo = wd('B', 'foo'), bUse = wd('B', 'use')
        stage(aUse, aFoo)
        stage(bUse, bFoo)
        def mk = { String proc, Path dir, String script ->
            def t = task(process: proc, name: "${proc} (s1)".toString(), tag: 's1', script: script)
            t.workdir = dir.toString()
            t
        }
        def a = snap('runA', [mk('EXAMPLEFOO', aFoo, 'foo s1'), mk('CONSUME', aUse, 'use out.txt')])
        def b = snap('runB', [mk('EXAMPLE_FOO', bFoo, 'foo s1'), mk('CONSUME', bUse, 'use out.txt')])

        when:
        def diff = new RunComparator(new CompareOptions(diffDag: true, baseDir: projectDir)).compare(a, b)

        then: 'one unchanged edge, not a removed EXAMPLEFOO edge plus an added EXAMPLE_FOO edge'
        diff.processesRenamed() == 1
        diff.dag.collect { "${it.from()}->${it.to()}:${it.kind}".toString() } == ['EXAMPLE_FOO->CONSUME:UNCHANGED']
    }

    def '--outputs-ignore keeps an always-different file from breaking the identical verdict'() {
        given: 'one task whose only output difference is a timestamped report'
        def mk = { String run, String html ->
            def dir = java.nio.file.Files.createDirectories(projectDir.resolve(run))
            java.nio.file.Files.write(dir.resolve('result.tsv'), 'same'.bytes)
            java.nio.file.Files.write(dir.resolve('report.html'), html.bytes)
            def t = task(process: 'FOO', name: 'FOO (1)', hash: 'h', display: [status: 'COMPLETED'])
            t.workdir = dir.toString()
            snap(run, [t])
        }
        def a = mk('A', '<p>generated 12:00</p>')
        def b = mk('B', '<p>generated 13:00</p>')

        when:
        def plain = new RunComparator(new CompareOptions(diffOutputs: true)).compare(a, b)
        def ignoring = new RunComparator(new CompareOptions(diffOutputs: true, outputsIgnore: ['*.html'])).compare(a, b)

        then: 'without the pattern the report counts as a change'
        !plain.identical

        and: 'with it, the runs are identical and the note says what was skipped'
        ignoring.identical
        ignoring.outputs[0].ignoredFiles == ['report.html']
        ignoring.outputsNote.contains('1 file(s) matching --outputs-ignore (`*.html`) were skipped')
    }

    def '--numeric-tolerance lets floating-point noise in outputs through and says so'() {
        given:
        def mk = { String run, String value ->
            def dir = java.nio.file.Files.createDirectories(projectDir.resolve("tol-${run}".toString()))
            java.nio.file.Files.write(dir.resolve('stats.tsv'), "mean\t${value}\n".toString().bytes)
            def t = task(process: 'STATS', name: 'STATS (1)', hash: 'h', display: [status: 'COMPLETED'])
            t.workdir = dir.toString()
            snap(run, [t])
        }
        def a = mk('A', '0.30000000000000004')
        def b = mk('B', '0.3')

        when:
        def exact = new RunComparator(new CompareOptions(diffOutputs: true)).compare(a, b)
        def tolerant = new RunComparator(new CompareOptions(diffOutputs: true, numericTolerance: 1e-9d)).compare(a, b)

        then:
        !exact.identical
        tolerant.identical
        tolerant.outputs[0].files[0].withinTolerance
        tolerant.outputsNote.contains('compared with --numeric-tolerance=1.0E-9; 1 file(s) differed only within it')
    }
}
