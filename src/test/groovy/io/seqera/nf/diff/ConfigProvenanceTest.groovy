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
 * Unit coverage for {@link DiffResult.ConfigProvenance} — the warning logic is
 * pure, so it is tested directly without any git interaction.
 */
class ConfigProvenanceTest extends Specification {

    def 'a clean, non-drifted provenance produces no warning'() {
        given:
        def prov = new DiffResult.ConfigProvenance(
                gitAvailable: true,
                currentRevision: 'abc123',
                workingTreeDirty: false,
                revisionA: 'abc123', revisionB: 'abc123',
                driftedA: false, driftedB: false )

        expect:
        !prov.hasWarning()
        prov.warning() == null
    }

    def 'drift on one side warns and names that run'() {
        given:
        def prov = new DiffResult.ConfigProvenance(
                gitAvailable: true,
                currentRevision: 'cccccccccc0000',
                revisionA: 'aaaaaaaaaa0000', revisionB: 'cccccccccc0000',
                driftedA: true, driftedB: false )

        expect:
        prov.hasWarning()
        prov.warning().contains('run A was')
        prov.warning().contains('NOT visible here')
        prov.warning().contains('cccccccccc')  // abbreviated current revision
    }

    def 'drift on both sides is called out together'() {
        given:
        def prov = new DiffResult.ConfigProvenance(
                gitAvailable: true, currentRevision: 'deadbeefcafe',
                revisionA: '1111111111', revisionB: '2222222222',
                driftedA: true, driftedB: true )

        expect:
        prov.warning().contains('both runs were')
    }

    def 'a dirty working tree warns even without drift'() {
        given:
        def prov = new DiffResult.ConfigProvenance(
                gitAvailable: true, currentRevision: 'abc123',
                workingTreeDirty: true,
                revisionA: 'abc123', revisionB: 'abc123',
                driftedA: false, driftedB: false )

        expect:
        prov.hasWarning()
        prov.warning().contains('uncommitted changes')
    }

    def 'drift and dirtiness are both surfaced in one warning'() {
        given:
        def prov = new DiffResult.ConfigProvenance(
                gitAvailable: true, currentRevision: 'ccccccc',
                workingTreeDirty: true,
                revisionA: 'aaaaaaa', revisionB: 'ccccccc',
                driftedA: true, driftedB: false )

        when:
        def w = prov.warning()

        then:
        w.contains('different git revision')
        w.contains('uncommitted changes')
    }
}
