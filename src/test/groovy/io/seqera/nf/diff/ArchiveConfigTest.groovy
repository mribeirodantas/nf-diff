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

import java.nio.file.Paths

import nextflow.Session
import nextflow.config.ConfigMap
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Coverage of the opt-in gate: {@link ArchiveConfig#from} reading
 * {@code diff.archive.*} out of the session config, and the {@code asBool}
 * coercion behind {@code enabled}. This is the switch that keeps the plugin a
 * pure CLI tool for everyone who has not turned archiving on, so its parsing is
 * pinned explicitly.
 */
class ArchiveConfigTest extends Specification {

    /**
     * A stub {@link Session} whose {@code config.navigate(dottedKey)} returns
     * {@code settings[dottedKey]} — mirroring how Nextflow's {@code ConfigMap}
     * resolves a dotted path.
     */
    private Session sessionWith(Map<String,Object> settings) {
        // Build a real nested ConfigMap from the flat dotted keys so that
        // Nextflow's Map.navigate(dottedKey) extension resolves them exactly as
        // it does against a live session config. Returning a genuine Map (not an
        // Expando) matters: Session.getConfig() is typed ConfigMap, and Spock
        // casts the stubbed return to that type — a non-Map would throw
        // GroovyCastException, which ArchiveConfig.navigate would swallow as null.
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

    def 'is disabled by default when nothing is configured'() {
        when:
        def cfg = ArchiveConfig.from(sessionWith([:]))

        then:
        !cfg.enabled
        !cfg.complete
        cfg.dir != null   // resolved to the default location
    }

    @Unroll
    def 'enabled parses #value as #expected'() {
        expect:
        ArchiveConfig.from(sessionWith(['diff.archive.enabled': value])).enabled == expected

        where:
        value   || expected
        true    || true
        false   || false
        'true'  || true
        'TRUE'  || true
        'false' || false
        'yes'   || false   // only real booleans / 'true' count
        null    || false
    }

    @Unroll
    def 'mode #value maps to complete=#expected'() {
        expect:
        ArchiveConfig.from(sessionWith([
                'diff.archive.enabled': true,
                'diff.archive.mode'   : value ])).complete == expected

        where:
        value         || expected
        'complete'    || true
        'COMPLETE'    || true
        ' complete '  || true
        'lightweight' || false
        null          || false
    }

    def 'dir is taken from config when set'() {
        expect:
        ArchiveConfig.from(sessionWith([
                'diff.archive.enabled': true,
                'diff.archive.dir'    : '/shared/nf-diff-archive' ])).dir == Paths.get('/shared/nf-diff-archive')
    }

    @Unroll
    def 'asBool coerces #value to #expected'() {
        expect:
        ArchiveConfig.asBool(value) == expected

        where:
        value       || expected
        true        || true
        false       || false
        Boolean.TRUE|| true
        'true'      || true
        ' true '    || true
        'false'     || false
        'nope'      || false
        null        || false
        42          || false
    }
}
