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
                durationMillis: 1000L, tasks: tasks )
    }

    private String md(List<TaskInfo> a, List<TaskInfo> b, boolean verbose = false) {
        def diff = new RunComparator(new CompareOptions(showObvious: verbose)).compare(snap('runA', a), snap('runB', b))
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
        text.contains('## Parameters & options')
        text.contains('## Process topology')
        text.contains('## Performance regressions')
        text.contains('## Task detail')
    }

    def 'performance regressions render a table sorted worst first'() {
        when: 'run B slows one task by +200% (same work) and another by +50%'
        def text = md(
                [task(process: 'FAST', name: 'FAST (1)', hash: 'a',
                        display: [status: 'COMPLETED', realtime: '10s'], raw: [realtime: 10_000L]),
                 task(process: 'SLOW', name: 'SLOW (1)', hash: 'b',
                        display: [status: 'COMPLETED', realtime: '10s'], raw: [realtime: 10_000L])],
                [task(process: 'FAST', name: 'FAST (1)', hash: 'a',
                        display: [status: 'COMPLETED', realtime: '15s'], raw: [realtime: 15_000L]),
                 task(process: 'SLOW', name: 'SLOW (1)', hash: 'b',
                        display: [status: 'COMPLETED', realtime: '30s'], raw: [realtime: 30_000L])] )
        def table = text.substring(text.indexOf('## Performance regressions'))

        then: 'both clear the 25% default and the +200% row comes first'
        table.contains('| Task | Metric | Run A | Run B | Δ | Same work |')
        table.indexOf('SLOW (1)') < table.indexOf('FAST (1)')
        table.contains('+200.0%')
        table.contains('+50.0%')
    }

    def 'no regressions renders an explicit empty note'() {
        when:
        def text = md(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'x'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'y'])] )

        then:
        text.contains('_No task metric changed by ≥ 25% between the runs._')
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

    def 'changed launch-command flags surface in the parameters table'() {
        given:
        def t = task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'x'])
        def diff = new RunComparator().compare(
                snap('runA', [t], 'nextflow run main.nf -profile docker --genome GRCh38'),
                snap('runB', [t], 'nextflow run main.nf -profile test --genome GRCh38') )

        when:
        def text = new MarkdownReportRenderer().render(diff)

        then: 'the changed flag appears, the unchanged one is not listed as a row'
        text.contains('| -profile | docker | test |')
        !text.contains('| --genome |')
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
