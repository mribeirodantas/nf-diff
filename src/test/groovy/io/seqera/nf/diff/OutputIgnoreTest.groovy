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

class OutputIgnoreTest extends Specification {

    def 'pattern #pattern on #path -> #ignored'() {
        expect:
        OutputIgnore.of([pattern]).ignores(path) == ignored

        where:
        pattern                     | path                                   | ignored
        // no '/': matches a file or folder name anywhere
        '*.html'                    | 'multiqc_report.html'                  | true
        '*.html'                    | 'multiqc/multiqc_report.html'          | true
        '*.html'                    | 'report.htmlx'                         | false
        'versions.yml'              | 'versions.yml'                         | true
        'versions.yml'              | 'sub/dir/versions.yml'                 | true
        'versions.yml'              | 'my_versions.yml'                      | false
        'pipeline_info'             | 'pipeline_info/execution_trace.txt'    | true
        'pipeline_info'             | 'other/pipeline_info/x.txt'            | true
        // with '/': matches the relative path
        'multiqc/*.html'            | 'multiqc/report.html'                  | true
        'multiqc/*.html'            | 'multiqc/sub/report.html'              | false
        'multiqc/*.html'            | 'report.html'                          | false
        'multiqc/**'                | 'multiqc/sub/data/x.txt'               | true
        '**/*.log'                  | 'deep/down/run.log'                    | true
        '**/*.log'                  | 'run.log'                              | true
        '/top.txt'                  | 'top.txt'                              | true
        // trailing '/': everything under that folder
        'pipeline_info/'            | 'pipeline_info/execution_report.html'  | true
        'pipeline_info/'            | 'pipeline_info_backup/x.txt'          | false
    }

    def 'several patterns, blanks and duplicates from the command line'() {
        given:
        def ignore = OutputIgnore.of([' *.html ', '', 'versions.yml'])

        expect:
        !ignore.isEmpty()
        ignore.patterns == ['*.html', 'versions.yml']
        ignore.describe() == '`*.html`, `versions.yml`'
        ignore.ignores('a/report.html')
        ignore.ignores('versions.yml')
        !ignore.ignores('results.tsv')
    }

    def 'no patterns ignore nothing'() {
        expect:
        OutputIgnore.of(null).isEmpty()
        OutputIgnore.of([]).is(OutputIgnore.NONE)
        !OutputIgnore.of(['  ']).ignores('anything')
    }
}
