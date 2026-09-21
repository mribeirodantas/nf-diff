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

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceObserverFactoryV2

/**
 * Creates the {@link DiffObserver} — but only when {@code diff.archive.enabled}
 * is set. When archiving is off (the default) this returns no observers, so the
 * plugin adds zero run-time overhead: it stays a pure CLI tool unless the user
 * opts in.
 */
@Slf4j
@CompileStatic
class DiffObserverFactory implements TraceObserverFactoryV2 {

    @Override
    Collection<TraceObserverV2> create(Session session) {
        final config = ArchiveConfig.from(session)
        if( !config.enabled ) {
            log.debug 'nf-diff: run archiving disabled (set diff.archive.enabled = true to enable)'
            return Collections.<TraceObserverV2> emptyList()
        }
        log.debug "nf-diff: run archiving enabled (${config.complete ? 'complete' : 'lightweight'}) -> ${config.dir}"
        return Collections.<TraceObserverV2> singletonList(new DiffObserver(config, session))
    }
}
