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

import java.util.concurrent.CopyOnWriteArrayList

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.script.WorkflowMetadata
import nextflow.trace.TraceObserverV2
import nextflow.trace.TraceRecord
import nextflow.trace.event.TaskEvent

/**
 * Archives the current run to the central {@link ArchiveStore} when it finishes,
 * so it can be compared later even after its project folder is deleted.
 *
 * <p>Rather than re-reading {@code .nextflow/history} + cache at completion
 * (whose flush timing relative to {@code onFlowComplete} is not guaranteed), the
 * observer collects each task's {@link TraceRecord} live as it completes and
 * builds the {@link RunSnapshot} from those plus the {@link WorkflowMetadata} at
 * the end — reusing {@link RunLoader#toTaskInfo} so the archived tasks are
 * identical to what the cache-reading path would have produced.
 *
 * <p>This observer is only created (see {@link DiffObserverFactory}) when
 * {@code diff.archive.enabled = true}, so it is entirely inert otherwise.
 */
@Slf4j
@CompileStatic
class DiffObserver implements TraceObserverV2 {

    private final ArchiveConfig config
    private final Session session

    /**
     * Task traces collected during the run. {@code onProcessComplete}/
     * {@code onProcessCached} are invoked from task-monitor threads
     * concurrently, so this must be a thread-safe collection.
     */
    private final CopyOnWriteArrayList<TraceRecord> records = new CopyOnWriteArrayList<>()

    DiffObserver(ArchiveConfig config, Session session) {
        this.config = config
        this.session = session
    }

    /**
     * Collect trace metrics (peak RSS/VMEM, %CPU, realtime) while archiving is
     * on, so the archived run carries the same performance data the cache-backed
     * path would, and the performance/resource comparison layers work against it.
     */
    @Override
    boolean enableMetrics() {
        return true
    }

    @Override
    void onTaskComplete(TaskEvent event) {
        final trace = event?.trace
        if( trace != null )
            records.add(trace)
    }

    @Override
    void onTaskCached(TaskEvent event) {
        final trace = event?.trace
        if( trace != null )
            records.add(trace)
    }

    @Override
    void onFlowComplete() {
        try {
            final snap = buildSnapshot(session?.workflowMetadata)
            final stored = new ArchiveStore(config.dir).save(snap, config.complete)
            log.info "nf-diff: archived run '${stored.runName}' " +
                    "(${stored.tasks.size()} task(s), ${config.complete ? 'complete' : 'lightweight'}) to ${config.dir}"
        }
        catch( Throwable e ) {
            // Nextflow swallows observer exceptions silently: it will neither
            // fail the run nor print this. Log loudly so a failed archive is
            // visible in .nextflow.log, but never let it break the pipeline.
            log.error("nf-diff: failed to archive run — ${e.message ?: e.class.simpleName}", e)
        }
    }

    /** Build the run snapshot from collected traces + run metadata. */
    private RunSnapshot buildSnapshot(WorkflowMetadata metadata) {
        final snap = new RunSnapshot()
        populateMetadata(snap, metadata)
        snap.tasks = records.collect { TraceRecord t -> RunLoader.toTaskInfo(t) }
        // Match RunLoader's stable ordering: by process, then task name.
        snap.tasks.sort { TaskInfo a, TaskInfo b ->
            final byProc = (a.process ?: '') <=> (b.process ?: '')
            byProc != 0 ? byProc : ((a.name ?: '') <=> (b.name ?: ''))
        }
        return snap
    }

    /**
     * Copy run-level metadata off {@link WorkflowMetadata}. Kept dynamic on
     * purpose: several of these properties ({@code start}, {@code nextflow},
     * {@code containerEngine}) have shifted type or availability across Nextflow
     * versions, and {@code @CompileStatic} would break the build against the
     * floor version. Each read is individually guarded so a single missing
     * property never aborts the whole snapshot.
     */
    @CompileDynamic
    private void populateMetadata(RunSnapshot snap, WorkflowMetadata meta) {
        snap.runName = safe { meta.runName } ?: safe { session.runName }
        snap.sessionId = safe { meta.sessionId } ?: safe { session.uniqueId }
        snap.status = safe { meta.success } ? 'OK' : 'ERR'
        snap.command = safe { meta.commandLine }
        snap.revisionId = safe { meta.revision } ?: safe { meta.commitId }
        snap.timestamp = toDate(safe { meta.start }) ?: new Date()
        final dur = safe { meta.duration }
        snap.durationMillis = dur != null ? dur.toMillis() : null
        snap.pipeline = safe { meta.projectName }
        snap.pipelinePath = safe { meta.scriptFile?.toString() }
        snap.nextflowVersion = safe { meta.nextflow?.version?.toString() }
        snap.nextflowBuild = safe { meta.nextflow?.build?.toString() }
        snap.containerEngine = safe { meta.containerEngine }
        snap.waveEnabled = ArchiveConfig.asBool(navigate('wave.enabled'))
        snap.fusionEnabled = ArchiveConfig.asBool(navigate('fusion.enabled'))
    }

    /** Evaluate a metadata read, returning null on any failure. */
    @CompileDynamic
    private static Object safe(Closure c) {
        try {
            return c.call()
        }
        catch( Throwable t ) {
            return null
        }
    }

    /** Read a dotted config key off the session, tolerating any failure. */
    @CompileDynamic
    private Object navigate(String key) {
        try {
            return session?.config?.navigate(key)
        }
        catch( Throwable t ) {
            return null
        }
    }

    /**
     * Coerce a run start value to a {@link Date}. Nextflow has used both
     * {@code Date} and {@code OffsetDateTime} for {@code WorkflowMetadata.start}
     * across versions, so accept either (and anything else exposing
     * {@code toInstant()}), returning null when it cannot be interpreted.
     */
    @CompileDynamic
    private static Date toDate(Object o) {
        if( o == null )
            return null
        if( o instanceof Date )
            return (Date) o
        try {
            return Date.from(o.toInstant())
        }
        catch( Throwable ignored ) {
        }
        try {
            return new Date(o.toEpochMilli() as long)
        }
        catch( Throwable ignored ) {
        }
        return null
    }
}
