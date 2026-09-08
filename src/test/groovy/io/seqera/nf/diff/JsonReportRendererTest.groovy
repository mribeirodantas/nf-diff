package io.seqera.nf.diff

import groovy.json.JsonSlurper
import spock.lang.Specification

/**
 * Verifies the machine-readable JSON report: valid JSON, the documented shape,
 * and that the meaningful-vs-verbose distinction is preserved in the emitted
 * field-level flags.
 */
class JsonReportRendererTest extends Specification {

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
                durationMillis: 1000L, tasks: tasks )
    }

    private Object json(List<TaskInfo> a, List<TaskInfo> b, boolean verbose = false) {
        def diff = new RunComparator(verbose).compare(snap('runA', a), snap('runB', b))
        def text = new JsonReportRenderer().render(diff)
        return new JsonSlurper().parseText(text)
    }

    def 'renders valid JSON with the documented top-level shape'() {
        when:
        def obj = json(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo bye'])] )

        then:
        obj.keySet().containsAll(['generatedAt', 'identical', 'showObvious',
                                  'runA', 'runB', 'summary', 'metadata', 'params', 'processes', 'tasks'])
        obj.runA.runName == 'runA'
        obj.runB.runName == 'runB'
        obj.summary.tasksChanged == 1
        !obj.identical
    }

    def 'changed task carries only highlighted field diffs'() {
        when:
        def obj = json(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo bye'])] )
        def foo = obj.tasks.find { it.key == 'FOO (1)' }

        then:
        foo.kind == 'changed'
        foo.process == 'FOO'
        foo.fields.find { it.field == 'script' }
        foo.fields.every { it.changed }
    }

    def 'added and removed tasks are labelled and carry no field diffs'() {
        when:
        def obj = json(
                [task(process: 'GONE', name: 'GONE (1)', display: [status: 'COMPLETED'])],
                [task(process: 'NEW', name: 'NEW (1)', display: [status: 'COMPLETED'])] )

        then:
        obj.tasks.find { it.key == 'GONE (1)' }.kind == 'removed'
        obj.tasks.find { it.key == 'NEW (1)' }.kind == 'added'
        obj.tasks.every { it.fields == [] || it.kind == 'changed' }
    }

    def 'identical runs report identical=true and no changes'() {
        when:
        def obj = json(
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])],
                [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'echo hi'])] )

        then:
        obj.identical
        obj.summary.tasksChanged == 0
    }

    def 'params layer carries per-flag diffs parsed from the launch command'() {
        given:
        def t = task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', script: 'x'])
        def diff = new RunComparator().compare(
                snap('runA', [t], 'nextflow run main.nf -profile docker --genome GRCh38'),
                snap('runB', [t], 'nextflow run main.nf -profile test --genome GRCh38') )

        when:
        def obj = new JsonSlurper().parseText(new JsonReportRenderer().render(diff))
        def profile = obj.params.find { it.field == '-profile' }

        then:
        profile.valueA == 'docker'
        profile.valueB == 'test'
        profile.highlighted
        obj.params.find { it.field == '--genome' }.highlighted == false
    }

    def 'obvious-only differences are not surfaced by default but are in verbose'() {
        given:
        def a = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', realtime: '10s', workdir: '/w/a'])]
        def b = [task(process: 'FOO', name: 'FOO (1)', display: [status: 'COMPLETED', realtime: '99s', workdir: '/w/b'])]

        expect: 'default mode treats the runs as identical'
        json(a, b, false).identical

        and: 'verbose mode surfaces the change and the highlighted fields'
        def obj = json(a, b, true)
        !obj.identical
        obj.showObvious
        obj.tasks.find { it.key == 'FOO (1)' }.fields*.field.containsAll(['realtime', 'workdir'])
    }
}
