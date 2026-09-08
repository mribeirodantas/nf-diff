package io.seqera.nf.diff

import spock.lang.Specification

/**
 * Verifies the Markdown report: the documented section skeleton, that only
 * meaningful changes surface by default (verbose surfaces the rest), and that
 * table cells stay well-formed when values contain pipes or newlines.
 */
class MarkdownReportRendererTest extends Specification {

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

    private RunSnapshot snap(String name, List<TaskInfo> tasks) {
        return new RunSnapshot(
                requestedId: name, runName: name,
                sessionId: UUID.randomUUID(), status: 'OK',
                durationMillis: 1000L, tasks: tasks )
    }

    private String md(List<TaskInfo> a, List<TaskInfo> b, boolean verbose = false) {
        def diff = new RunComparator(verbose).compare(snap('runA', a), snap('runB', b))
        return new MarkdownReportRenderer().render(diff)
    }

    def 'renders the documented section skeleton'() {
        when:
        def text = md(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo bye'])] )

        then:
        text.startsWith('# nf-diff report')
        text.contains('## Summary')
        text.contains('## Runs')
        text.contains('## Run metadata')
        text.contains('## Process topology')
        text.contains('## Task detail')
    }

    def 'a changed task surfaces its highlighted field diff'() {
        when:
        def text = md(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo bye'])] )

        then:
        text.contains('### FOO — changed')
        text.contains('**script**')
        text.contains('echo hi')
        text.contains('echo bye')
    }

    def 'added and removed tasks are labelled in the task detail'() {
        when:
        def text = md(
                [task(process: 'GONE', name: 'GONE (1)', display: [status: 'COMPLETED'])],
                [task(process: 'NEW', name: 'NEW (1)', display: [status: 'COMPLETED'])] )

        then:
        text.contains('### GONE — removed')
        text.contains('### NEW — added')
    }

    def 'identical runs report an identical banner and no task changes'() {
        when:
        def text = md(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])] )

        then:
        text.contains('**The two runs are identical**')
        text.contains('_No task-level changes._')
        !text.contains('## Summary')
    }

    def 'obvious-only differences are hidden by default and shown in verbose'() {
        given:
        def a = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', realtime: '10s'])]
        def b = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', realtime: '99s'])]

        expect: 'default mode hides the always-changing field'
        md(a, b, false).contains('_No task-level changes._')

        and: 'verbose mode surfaces it'
        def text = md(a, b, true)
        text.contains('**realtime**')
        text.contains('verbose (all fields)')
    }

    def 'pipe characters in values are escaped so table rows stay well-formed'() {
        when:
        def text = md(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'], container: 'img|a')],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'], container: 'img|b')] )

        then: 'the process topology table contains no unescaped pipe from the process cell'
        text.contains('| FOO |')
    }
}
