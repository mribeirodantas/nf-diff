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

class NextflowLogReaderTest extends Specification {

    @TempDir
    Path dir

    static final UUID SID_A = UUID.fromString('d78a3221-8ea0-4a76-b83e-ba72ab5b6328')
    static final UUID SID_B = UUID.fromString('e011602f-9e8b-4a96-b0b1-4419668a4631')

    /** A log header shaped like the one Nextflow 25.10 writes. */
    private static String header(UUID sessionId, String runName, String version, String build) {
        """\
Nov-25 18:18:35.475 [main] DEBUG nextflow.cli.Launcher - \$> nextflow run main.nf
Nov-25 18:18:35.510 [main] DEBUG nextflow.cli.CmdRun - N E X T F L O W  ~  version ${version}
Nov-25 18:18:36.595 [main] DEBUG nextflow.cli.CmdRun - Launching `main.nf` [${runName}] DSL2 - revision: 5a3e30729e
Nov-25 18:18:36.616 [main] DEBUG nextflow.Session - Session UUID: ${sessionId}
Nov-25 18:18:36.617 [main] DEBUG nextflow.Session - Run name: ${runName}
Nov-25 18:18:36.640 [main] DEBUG nextflow.Session - Executor pool size: 8
Nov-25 18:18:36.650 [main] DEBUG nextflow.cli.CmdRun -
  Version: ${version} build ${build}
  Created: 27-10-2025 18:36 UTC
  System: Mac OS X 15.6
""".stripIndent()
    }

    private void writeLog(String name, String content) {
        Files.writeString(dir.resolve(name), content)
    }

    def 'reads version and build from the log matching session and run name'() {
        given:
        writeLog('.nextflow.log', header(SID_B, 'gloomy_sammet', '25.10.0', '10289'))
        writeLog('.nextflow.log.1', header(SID_A, 'cranky_ride', '24.10.4', '5934'))

        when:
        def info = new NextflowLogReader(dir).versionFor(SID_A, 'cranky_ride')

        then:
        info.version == '24.10.4'
        info.build == '5934'
    }

    def 'a resumed run is told apart from the original by its run name'() {
        given: 'same session, different run names and versions'
        writeLog('.nextflow.log', header(SID_A, 'resumed_run', '25.10.0', '10289'))
        writeLog('.nextflow.log.1', header(SID_A, 'first_run', '25.04.6', '5954'))

        expect:
        new NextflowLogReader(dir).versionFor(SID_A, 'first_run').version == '25.04.6'
        new NextflowLogReader(dir).versionFor(SID_A, 'resumed_run').version == '25.10.0'
    }

    def 'a null run name matches on session id alone, newest log first'() {
        given:
        writeLog('.nextflow.log', header(SID_A, 'resumed_run', '25.10.0', '10289'))
        writeLog('.nextflow.log.2', header(SID_A, 'first_run', '25.04.6', '5954'))

        expect:
        new NextflowLogReader(dir).versionFor(SID_A, null).version == '25.10.0'
    }

    def 'falls back to the banner version when the info block is missing'() {
        given:
        writeLog('.nextflow.log', header(SID_A, 'cranky_ride', '25.10.0', '10289')
                .readLines().findAll { !it.contains('Version:') }.join('\n'))

        when:
        def info = new NextflowLogReader(dir).versionFor(SID_A, 'cranky_ride')

        then:
        info.version == '25.10.0'
        info.build == null
    }

    def 'returns null when no log matches: #desc'() {
        given:
        writeLog('.nextflow.log', header(SID_B, 'gloomy_sammet', '25.10.0', '10289'))

        expect:
        new NextflowLogReader(launch ? dir : null).versionFor(sid, name) == null

        where:
        desc                 | launch | sid   | name
        'other session'      | true   | SID_A | 'gloomy_sammet'
        'other run name'     | true   | SID_B | 'cranky_ride'
        'null session id'    | true   | null  | 'gloomy_sammet'
        'null launch dir'    | false  | SID_B | 'gloomy_sammet'
    }

    def 'ignores files that are not Nextflow logs'() {
        given:
        writeLog('.nextflow.log.bak', header(SID_A, 'cranky_ride', '25.10.0', '10289'))
        writeLog('nextflow.log', header(SID_A, 'cranky_ride', '25.10.0', '10289'))

        expect:
        new NextflowLogReader(dir).versionFor(SID_A, 'cranky_ride') == null
    }

    def 'returns null for a missing launch directory'() {
        expect:
        new NextflowLogReader(dir.resolve('nope')).versionFor(SID_A, 'cranky_ride') == null
    }

    def 'tolerates non-UTF-8 bytes in a log'() {
        given:
        def bytes = new ByteArrayOutputStream()
        bytes.write([0xC3, 0x28, 0x0A] as byte[])
        bytes.write(header(SID_A, 'cranky_ride', '25.10.0', '10289').bytes)
        Files.write(dir.resolve('.nextflow.log'), bytes.toByteArray())

        expect:
        new NextflowLogReader(dir).versionFor(SID_A, 'cranky_ride').build == '10289'
    }
}
