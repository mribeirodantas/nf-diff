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
                script: args.script as String,
                container: args.container as String )
        t.display = (args.display as Map<String,String>) ?: [:]
        t.raw = (args.raw as Map<String,Object>) ?: [:]
        return t
    }

    private RunSnapshot snap(String name, List<TaskInfo> tasks, String command = null) {
        return new RunSnapshot(
                requestedId: name, runName: name,
                sessionId: UUID.randomUUID(), status: 'OK',
                command: command,
                tasks: tasks )
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
        def diff = new RunComparator(true).compare(a, b)

        then: 'the task is now flagged as changed'
        diff.tasksChanged == 1
        diff.tasksUnchanged == 0
        !diff.identical
        diff.showObvious
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

        then: 'the hash change marks the task recomputed and changed'
        diff.tasksRecomputed == 1
        diff.tasksChanged == 1
        !diff.identical
        diff.tasks.find { it.key == 'FOO (1)' }.fieldDiffs.find { it.field == 'hash' }.changed
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

    def 'a custom perf threshold widens or narrows what is flagged'() {
        given:
        def a = snap('runA', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED', realtime: '10s'], raw: [realtime: 10_000L])])
        def b = snap('runB', [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                display: [status: 'COMPLETED', realtime: '11s'], raw: [realtime: 11_000L])])

        expect: 'the default 25% threshold ignores a +10% change'
        new RunComparator().compare(a, b).regressions.isEmpty()

        and: 'a 5% threshold flags it'
        def diff = new RunComparator(false, null, null, 5.0d).compare(a, b)
        diff.regressions*.metric == ['realtime']
        diff.regressions[0].pctDelta == 10.0d
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
        def diff = new RunComparator(false, ProcessFilter.of(['ALIGN:*'], [])).compare(a, b)

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
        def diff = new RunComparator(false, ProcessFilter.of([], ['QC:*'])).compare(a, b)

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
        def diff = new RunComparator(false, null, projectDir).compare(a, b)

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
        def diff = new RunComparator(false, null, null, RunComparator.DEFAULT_PERF_THRESHOLD,
                false, 0L, false, LogComparator.DEFAULT_MAX_LINES,
                OutputComparator.DEFAULT_MAX_LINES, dirA, dirB).compare(a, b)

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
        def diff = new RunComparator(false, null, projectDir).compare(a, b)

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
        def diff = new RunComparator(false, null, null, RunComparator.DEFAULT_PERF_THRESHOLD, true, 0L).compare(a, b)

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
        def diff = new RunComparator(false, null, null, RunComparator.DEFAULT_PERF_THRESHOLD,
                false, 0L, true, LogComparator.DEFAULT_MAX_LINES).compare(a, b)

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

    @spock.lang.Requires({ GitProvenanceTest.isGitAvailable() })
    def 'config provenance warns when a run was launched at a different git revision'() {
        given: 'a committed config and one run whose recorded revision matches HEAD'
        def head = initGitWithConfig('process.cpus = 1\n')
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')
        a.revisionId = 'deadbeefdeadbeefdeadbeefdeadbeefdeadbeef' // different commit
        b.revisionId = head

        when:
        def diff = new RunComparator(false, null, projectDir).compare(a, b)

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
        def diff = new RunComparator(false, null, projectDir).compare(a, b)

        then:
        diff.configProvenance.gitAvailable
        !diff.configProvenance.hasWarning()
        diff.configProvenance.warning() == null
    }

    def 'config provenance reports git unavailable outside a repository'() {
        given: 'a project dir with config but no git repo'
        writeConfig('process.cpus = 1\n')
        def tasks = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])]
        def a = snap('runA', tasks.collect { it }, 'nextflow run main.nf')
        def b = snap('runB', tasks.collect { it }, 'nextflow run main.nf')

        when:
        def diff = new RunComparator(false, null, projectDir).compare(a, b)

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
}
