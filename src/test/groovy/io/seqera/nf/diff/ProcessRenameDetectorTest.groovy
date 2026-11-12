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

class ProcessRenameDetectorTest extends Specification {

    private static TaskInfo task(String process, String tag, String script, String container = 'quay.io/biocontainers/tool:1.0') {
        new TaskInfo(process: process, name: "${process} (${tag})".toString(), tag: tag, script: script, container: container)
    }

    private static List<TaskInfo> tasks(String process, Map<String,String> scriptByTag, String container = 'quay.io/biocontainers/tool:1.0') {
        scriptByTag.collect { tag, script -> task(process, tag, script, container) }
    }

    private static RunSnapshot snap(List<TaskInfo> tasks) {
        new RunSnapshot(runName: 'r', sessionId: UUID.randomUUID(), tasks: tasks)
    }

    /** nf-core style script: one command line plus a versions.yml block naming the process. */
    private static String nfcore(String process, String command) {
        """\
${command}
cat <<-END_VERSIONS > versions.yml
"${process}":
    samtools: \$(samtools --version | head -1 | sed 's/samtools //')
END_VERSIONS
""".stripIndent()
    }

    def 'a punctuation-only rename with identical scripts is detected'() {
        given:
        def a = snap(tasks('EXAMPLEFOO', [s1: 'foo s1.txt', s2: 'foo s2.txt']))
        def b = snap(tasks('EXAMPLE_FOO', [s1: 'foo s1.txt', s2: 'foo s2.txt']))

        when:
        def renames = new ProcessRenameDetector().detect(a, b)

        then:
        renames.size() == 1
        renames[0].from == 'EXAMPLEFOO'
        renames[0].to == 'EXAMPLE_FOO'
        renames[0].evidence[0] == 'identical script in 2 matched task(s)'
        renames[0].evidence.contains('same tags (2)')
        renames[0].evidence.contains('same task count (2)')
        renames[0].evidence.contains('same container/Conda environment')
    }

    def 'a rename to an unrelated name is detected when the work is identical'() {
        given:
        def a = snap(tasks('WF:ALIGN', [s1: 'bwa mem ref.fa s1.fq']))
        def b = snap(tasks('WF:MAP_READS', [s1: 'bwa mem ref.fa s1.fq']))

        expect:
        new ProcessRenameDetector().detect(a, b)*.to == ['WF:MAP_READS']
    }

    def 'the process name inside the script is masked, so versions.yml blocks still match'() {
        given:
        def a = snap(tasks('NFCORE:SAMTOOLSSORT', [s1: nfcore('NFCORE:SAMTOOLSSORT', 'samtools sort s1.bam')]))
        def b = snap(tasks('NFCORE:SAMTOOLS_SORT', [s1: nfcore('NFCORE:SAMTOOLS_SORT', 'samtools sort s1.bam')]))

        when:
        def renames = new ProcessRenameDetector().detect(a, b)

        then:
        renames*.from == ['NFCORE:SAMTOOLSSORT']
        renames[0].evidence[0].startsWith('identical script')
    }

    def 'a rename that also tweaked the script is detected when the names are similar'() {
        given: 'one of five lines changed'
        def before = 'set -e\nfoo --in s1.txt\nfoo --stats\nfoo --report\necho done'
        def after  = 'set -e\nfoo --in s1.txt --fast\nfoo --stats\nfoo --report\necho done'
        def a = snap(tasks('EXAMPLEFOO', [s1: before]))
        def b = snap(tasks('EXAMPLE_FOO', [s1: after]))

        when:
        def renames = new ProcessRenameDetector().detect(a, b)

        then:
        renames*.to == ['EXAMPLE_FOO']
        renames[0].evidence[0] == 'scripts 80% similar across 1 matched task(s)'
    }

    def 'processes sharing only boilerplate are not paired: SAMTOOLS_SORT removed, SAMTOOLS_INDEX added'() {
        given: 'same container, tags and task count; only the command line differs'
        def a = snap(tasks('SAMTOOLS_SORT', [s1: nfcore('SAMTOOLS_SORT', 'samtools sort s1.bam')]))
        def b = snap(tasks('SAMTOOLS_INDEX', [s1: nfcore('SAMTOOLS_INDEX', 'samtools index s1.bam')]))

        expect:
        new ProcessRenameDetector().detect(a, b).isEmpty()
    }

    def 'different scripts are never a rename, even with a near-identical name'() {
        given:
        def a = snap(tasks('EXAMPLEFOO', [s1: 'foo s1.txt']))
        def b = snap(tasks('EXAMPLE_FOO', [s1: 'bar s1.txt']))

        expect:
        new ProcessRenameDetector().detect(a, b).isEmpty()
    }

    def 'identical scripts alone are not enough without a corroborating signal'() {
        given: 'different tags, counts, containers and names'
        def a = snap([task('ALPHA', 'x1', 'echo hi', 'img:1')])
        def b = snap([task('ZULU', 'y1', 'echo hi', 'img:2'), task('ZULU', 'y2', 'echo hi', 'img:2')])

        expect:
        new ProcessRenameDetector().detect(a, b).isEmpty()
    }

    def 'each process is paired at most once, best match first'() {
        given: 'FOOONE could pair with either added process; FOO_ONE matches it more closely'
        def a = snap(tasks('FOOONE', [s1: 'foo s1', s2: 'foo s2']))
        def b = snap(tasks('FOO_ONE', [s1: 'foo s1', s2: 'foo s2']) +
                tasks('OTHER', [s1: 'foo s1', s2: 'foo s2'], 'img:other'))

        expect:
        new ProcessRenameDetector().detect(a, b).collect { "${it.from}->${it.to}".toString() } == ['FOOONE->FOO_ONE']
    }

    def 'without recorded scripts a rename needs a similar name, same count and same tags'() {
        given:
        def a = snap(tasks('EXAMPLEFOO', [s1: null, s2: null]))
        def b = snap(tasks(name, [s1: null, s2: null]))

        expect:
        new ProcessRenameDetector().detect(a, b).size() == expected

        where:
        name          | expected
        'EXAMPLE_FOO' | 1
        'UNRELATED'   | 0
    }

    def 'processes present in both runs are never considered'() {
        given:
        def a = snap(tasks('FOO', [s1: 'x']) + tasks('BAR', [s1: 'y']))
        def b = snap(tasks('FOO', [s1: 'x']))

        expect:
        new ProcessRenameDetector().detect(a, b).isEmpty()
    }

    def 'nameSimilarity ignores case, punctuation and scope'() {
        expect:
        ProcessRenameDetector.nameSimilarity(a, b) == expected

        where:
        a                | b                    | expected
        'EXAMPLEFOO'     | 'EXAMPLE_FOO'        | 1.0d
        'WF:example-foo' | 'OTHER:EXAMPLE_FOO'  | 1.0d
        'ABCD'           | 'WXYZ'               | 0.0d
    }
}
