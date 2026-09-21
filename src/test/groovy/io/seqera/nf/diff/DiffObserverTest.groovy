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

import java.nio.file.Path

import nextflow.Session
import nextflow.config.ConfigMap
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Coverage of {@link DiffObserver} driven through its public
 * {@code TraceObserverV2} surface and verified by reading the run back out of
 * the {@link ArchiveStore} it writes on completion. This exercises the live
 * trace collection, the null-trace tolerance, the run-metadata fallback to the
 * session when {@code WorkflowMetadata} is absent, and the stable
 * process-then-name task ordering — without depending on a real Nextflow
 * execution.
 */
class DiffObserverTest extends Specification {

    @TempDir
    Path tmp

    private static TraceRecord trace(Map<String,Object> fields) {
        final rec = new TraceRecord()
        rec.putAll(fields)
        return rec
    }

    private TaskEvent taskEvent(TraceRecord rec) {
        return Stub(TaskEvent) { getTrace() >> rec }
    }

    private Session sessionStub(UUID sid, String runName) {
        return Stub(Session) {
            getConfig() >> new ConfigMap([:])
            getRunName() >> runName
            getUniqueId() >> sid
            getWorkflowMetadata() >> null   // force the session-fallback path
        }
    }

    private ArchiveConfig config(Path dir) {
        final cfg = new ArchiveConfig()
        cfg.enabled = true
        cfg.complete = false
        cfg.dir = dir
        return cfg
    }

    def 'enableMetrics is on so the archived run carries performance data'() {
        expect:
        new DiffObserver(config(tmp), sessionStub(UUID.randomUUID(), 'r')).enableMetrics()
    }

    def 'collects task traces and archives the run on completion, sorted by process then name'() {
        given:
        def sid = UUID.fromString('12345678-90ab-cdef-1234-567890abcdef')
        def observer = new DiffObserver(config(tmp), sessionStub(sid, 'obs_run'))

        when: 'tasks complete out of order, plus a null-trace event that must be ignored'
        observer.onTaskComplete(taskEvent(trace([process: 'QC',    name: 'QC (1)',    hash: 'h-qc', exit: '0'])))
        observer.onTaskComplete(taskEvent(trace([process: 'ALIGN', name: 'ALIGN (2)', hash: 'h-a2', exit: '0'])))
        observer.onTaskCached(taskEvent(trace([process: 'ALIGN',   name: 'ALIGN (1)', hash: 'h-a1', exit: '0'])))
        observer.onTaskComplete(Stub(TaskEvent) { getTrace() >> null })
        observer.onFlowComplete()

        then: 'the run was archived under its session id and is loadable'
        def loaded = new ArchiveStore(tmp).load('obs_run')
        loaded.runName == 'obs_run'
        loaded.sessionId == sid

        and: 'the null-trace event was dropped; the three real tasks are ordered by process then name'
        loaded.tasks*.matchKey() == ['ALIGN (1)', 'ALIGN (2)', 'QC (1)']

        and: 'metadata falls back sensibly with no WorkflowMetadata: a timestamp is always set'
        loaded.timestamp != null
        loaded.status == 'ERR'   // meta.success unavailable -> not marked successful
    }

    def 'a failure while archiving never propagates out of onFlowComplete'() {
        given: 'a config pointing the archive at a path that cannot be created (a file, not a dir)'
        def notADir = tmp.resolve('blocker')
        notADir.toFile().text = 'x'   // a regular file where a directory is needed
        def cfg = new ArchiveConfig()
        cfg.enabled = true
        cfg.dir = notADir.resolve('archive')  // createDirectories under a file will fail
        def observer = new DiffObserver(cfg, sessionStub(UUID.randomUUID(), 'boom'))
        observer.onTaskComplete(taskEvent(trace([process: 'A', name: 'A (1)', hash: 'h', exit: '0'])))

        when: 'the archive write fails'
        observer.onFlowComplete()

        then: 'the observer swallows it (best-effort) rather than failing the pipeline'
        noExceptionThrown()
    }
}
