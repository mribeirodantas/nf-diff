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

/**
 * Exercises {@link ConfigLoader} against a real on-disk {@code nextflow.config},
 * verifying that profiles and {@code -c} files are applied, that the result is
 * flattened to dotted keys, and that the {@code params} scope is excluded (it is
 * owned by the Parameters layer).
 */
class ConfigLoaderTest extends Specification {

    @TempDir
    Path projectDir

    private final ConfigLoader loader = new ConfigLoader()

    private void writeConfig(String content) {
        Files.write(projectDir.resolve('nextflow.config'), content.getBytes('UTF-8'))
    }

    def 'resolves the base config, flattened to dotted keys'() {
        given:
        writeConfig('''
            process { cpus = 2; memory = '1 GB' }
            docker.enabled = false
        '''.stripIndent())

        when:
        def cfg = loader.resolve('nextflow run main.nf', projectDir)

        then:
        cfg['process.cpus'] == '2'
        cfg['process.memory'] == '1 GB'
        cfg['docker.enabled'] == 'false'
    }

    def 'applies the selected profile'() {
        given:
        writeConfig('''
            process.cpus = 1
            docker.enabled = false
            profiles {
                docker { docker.enabled = true; process.cpus = 8 }
                test   { process.memory = '4 GB' }
            }
        '''.stripIndent())

        when:
        def base   = loader.resolve('nextflow run main.nf', projectDir)
        def docker = loader.resolve('nextflow run main.nf -profile docker', projectDir)

        then: 'the docker profile overrides the base values'
        base['docker.enabled'] == 'false'
        base['process.cpus'] == '1'
        docker['docker.enabled'] == 'true'
        docker['process.cpus'] == '8'
    }

    def 'excludes the params scope (owned by the Parameters layer)'() {
        given:
        writeConfig('''
            params.input = 'data.csv'
            process.cpus = 1
        '''.stripIndent())

        when:
        def cfg = loader.resolve('nextflow run main.nf', projectDir)

        then:
        cfg['process.cpus'] == '1'
        !cfg.keySet().any { it == 'params' || it.startsWith('params.') }
    }

    def 'merges an extra -c config file over the base config'() {
        given:
        writeConfig('process.cpus = 1\n')
        Files.write(projectDir.resolve('extra.config'), "process.cpus = 16\n".getBytes('UTF-8'))

        when:
        def base = loader.resolve('nextflow run main.nf', projectDir)
        def withC = loader.resolve('nextflow run main.nf -c extra.config', projectDir)

        then:
        base['process.cpus'] == '1'
        withC['process.cpus'] == '16'
    }

    def 'resolves no user config keys when there is no config file'() {
        when: 'the project has no nextflow.config'
        def cfg = loader.resolve('nextflow run main.nf', projectDir)

        then: 'no user-defined config scopes are present'
        !cfg.containsKey('process.cpus')
        !cfg.keySet().any { it.startsWith('docker.') }
        !cfg.keySet().any { it.startsWith('process.') }
    }
}
