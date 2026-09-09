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
 * Guards the structure and hard invariants of the generated HTML report so
 * future styling/refactoring changes cannot silently break it:
 *  - the document stays fully self-contained (no network assets, no web fonts);
 *  - the brand palette and both themes remain defined;
 *  - the Nextflow attribution line stays in the footer;
 *  - the section/nav skeleton and per-task rendering keep working.
 *
 * These assertions deliberately avoid pinning exact colour hex values so an
 * intentional palette tweak does not require a test rewrite.
 */
class HtmlReportRendererTest extends Specification {

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

    private DiffResult diffOf(List<TaskInfo> a, List<TaskInfo> b, boolean verbose = false) {
        return new RunComparator(new CompareOptions(showObvious: verbose)).compare(snap('runA', a), snap('runB', b))
    }

    private String render(List<TaskInfo> a, List<TaskInfo> b, boolean verbose = false) {
        return new HtmlReportRenderer().render(diffOf(a, b, verbose))
    }

    // --------------------------------------------------------- regressions

    def 'renders a regressions section with a nav entry and the flagged metric'() {
        given: 'a task that ran 3x slower between runs'
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                        display: [status: 'COMPLETED', realtime: '10s'], raw: [realtime: 10_000L])],
                [task(process: 'FOO', name: 'FOO (1)', hash: 'h',
                        display: [status: 'COMPLETED', realtime: '30s'], raw: [realtime: 30_000L])] )

        expect: 'a nav link and section anchor exist'
        html.contains('href="#regressions"')
        html.contains('id="regressions"')

        and: 'the regression row surfaces the metric and signed delta'
        html.contains('Performance regressions')
        html.contains('+200.0%')
    }

    def 'regressions section shows an empty note when nothing crosses the threshold'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'x'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'y'])] )

        expect:
        html.contains('id="regressions"')
        html.contains('No task metric')
    }

    // --------------------------------------------------------- self-contained

    def 'report references no external network assets'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo bye'])] )

        expect: 'no external scripts, stylesheets, or fonts'
        // The footer carries a clickable <a href> to the project repo — that is
        // a link the user chooses to follow, not an asset the browser auto-loads.
        // Strip anchor hrefs before asserting so this guard stays focused on its
        // stated intent: nothing is fetched over the network to render the report.
        def assets = html.replaceAll(/<a\b[^>]*>/, '<a>')
        !assets.contains('<script src')
        !assets.contains('<link ')
        !assets.contains('http://')
        !assets.contains('https://')
        !assets.contains('@import')
        !assets.contains('fonts.googleapis')
        !assets.contains('fonts.gstatic')

        and: 'CSS and JS are inlined'
        html.contains('<style>')
        html.contains('</style>')
        html.contains('<script>')
    }

    def 'report does not load web fonts and keeps a system font stack'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])] )

        expect:
        html.contains('-apple-system')
        !html.contains('@font-face')
    }

    // --------------------------------------------------------------- palette

    def 'report defines the brand palette and both themes'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])] )

        expect: 'a --brand accent plus the A/B accent variables exist'
        html.contains('--brand:')
        html.contains('--a:')
        html.contains('--b:')
        html.contains('--a2:')
        html.contains('--b2:')

        and: 'both a dark (default) and a light theme are present'
        html.contains(':root{')
        html.contains('html[data-theme="light"]')

        and: 'the theme toggle control is rendered'
        html.contains('id="theme-toggle"')
    }

    // ----------------------------------------------------------- attribution

    def 'footer carries the Nextflow attribution line'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])] )

        expect:
        html.contains('Generated by')
        html.contains('foot-sub')
        html.contains('Compares run history and cache produced by')
        html.contains('<strong>Nextflow</strong>')
    }

    // ------------------------------------------------------------- structure

    def 'report renders the full section and nav skeleton'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])] )

        expect: 'valid document shell'
        html.startsWith('<!DOCTYPE html>')
        html.trim().endsWith('</html>')

        and: 'each section and its matching nav anchor exist'
        ['summary', 'metadata', 'params', 'processes', 'tasks'].every { id ->
            html.contains("id=\"${id}\"") && html.contains("href=\"#${id}\"")
        }
    }

    // ------------------------------------------------------ per-task content

    def 'changed task renders a side-by-side script diff'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo bye'])] )

        expect:
        html.contains('code-diff')
        html.contains('class="pill changed"')
    }

    def 'only-in-A and only-in-B tasks render solo notes'() {
        given:
        def html = render(
                [task(process: 'GONE', name: 'GONE (1)', display: [status: 'COMPLETED'])],
                [task(process: 'NEW', name: 'NEW (1)', display: [status: 'COMPLETED'])] )

        expect:
        html.contains('Only present in Run A')
        html.contains('Only present in Run B')
        html.contains('class="pill removed"')
        html.contains('class="pill added"')
    }

    def 'identical runs render the identical verdict'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])] )

        expect:
        html.contains('verdict same')
        html.contains('identical')
    }

    def 'default mode shows the meaningful-only mode note; verbose omits it'() {
        given: 'tasks differing only in always-changing fields'
        def a = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', realtime: '10s', workdir: '/w/a'])]
        def b = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', realtime: '99s', workdir: '/w/b'])]

        expect: 'default mode explains the meaningful-only filtering'
        render(a, b, false).contains('meaningful differences only')

        and: 'verbose mode drops the note and flags the change'
        !render(a, b, true).contains('meaningful differences only')
    }

    // --------------------------------------------------------------- params

    def 'params section renders flags parsed from the launch command'() {
        given:
        def t = task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'x'])
        def diff = new RunComparator().compare(
                snap('runA', [t], 'nextflow run main.nf -profile docker --genome GRCh38'),
                snap('runB', [t], 'nextflow run main.nf -profile test --genome GRCh38') )

        when:
        def html = new HtmlReportRenderer().render(diff)

        then: 'both flags are listed with their per-run values'
        html.contains('-profile')
        html.contains('docker')
        html.contains('test')
        html.contains('--genome')
    }

    def 'params section shows an explicit note when no command was recorded'() {
        given:
        def html = render(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED'])] )

        expect:
        html.contains('No command-line parameters were recorded')
    }

    // -------------------------------------------------------------- escaping

    def 'task values are HTML-escaped'() {
        given:
        def html = render(
                [task(process: '<script>', name: 'X (1)', display: [status: 'COMPLETED', script: 'a'])],
                [task(process: '<script>', name: 'X (1)', display: [status: 'COMPLETED', script: 'b'])] )

        expect: 'the injected markup is escaped, not emitted raw'
        html.contains('&lt;script&gt;')
        !html.contains('<script>a')
    }
}
