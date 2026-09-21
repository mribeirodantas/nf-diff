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

import nextflow.Session
import nextflow.config.ConfigMap
import spock.lang.Specification

/**
 * Guards the "zero overhead by default" contract: the trace observer must only
 * be registered when {@code diff.archive.enabled = true}. A regular pipeline run
 * (no archive config) must get no observers at all, so the plugin stays a pure
 * CLI tool unless the user opts in.
 */
class DiffObserverFactoryTest extends Specification {

    private Session sessionWith(Map<String,Object> settings) {
        // Return a real nested ConfigMap (not an Expando): Session.getConfig() is
        // typed ConfigMap and Spock casts the stubbed return to that type, so a
        // non-Map would throw GroovyCastException and be swallowed as "disabled".
        final nested = [:]
        settings.each { key, value ->
            final parts = (key as String).tokenize('.')
            def cursor = nested
            parts[0..<-1].each { part ->
                cursor = (cursor[part] ?: (cursor[part] = [:]))
            }
            cursor[parts[-1]] = value
        }
        return Stub(Session) { getConfig() >> new ConfigMap(nested) }
    }

    def 'registers no observer when archiving is disabled (the default)'() {
        expect:
        new DiffObserverFactory().create(sessionWith([:])).isEmpty()
    }

    def 'registers a single DiffObserver when archiving is enabled'() {
        when:
        def observers = new DiffObserverFactory().create(sessionWith(['diff.archive.enabled': true]))

        then:
        observers.size() == 1
        observers.first() instanceof DiffObserver
    }
}
