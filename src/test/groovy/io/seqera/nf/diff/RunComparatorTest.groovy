package io.seqera.nf.diff

import spock.lang.Specification

class RunComparatorTest extends Specification {

    private TaskInfo task(Map args) {
        def t = new TaskInfo(
                process: args.process as String,
                name: args.name as String,
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
