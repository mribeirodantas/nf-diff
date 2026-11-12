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

/**
 * A full snapshot of a Nextflow run: the run-level metadata taken from the
 * history file plus every task recovered from the run's cache DB.
 */
@CompileStatic
class RunSnapshot {

    /** The identifier the user supplied on the command line (name or UUID prefix). */
    String requestedId

    // -- run-level metadata (from HistoryFile.Record)
    String runName
    UUID sessionId
    String status
    String revisionId
    String command
    Date timestamp
    Long durationMillis

    // -- pipeline (project) name. Sourced authoritatively from the lineage
    //    WorkflowRun's projectName; falls back to the positional argument of the
    //    recorded launch command when no .lineage/ store is present. Null when
    //    neither source yields a value.
    String pipeline

    // -- absolute path to the run's main script (lineage scriptFile, e.g.
    //    /path/to/main.nf). Null when no .lineage/ store recorded it.
    String pipelinePath

    // -- Nextflow version & runtime environment (from the lineage WorkflowRun
    //    record, when a .lineage/ store is present; otherwise all null).
    String nextflowVersion
    String nextflowBuild
    String containerEngine
    Boolean waveEnabled
    Boolean fusionEnabled

    // -- tasks (from CacheDB)
    List<TaskInfo> tasks = []

    /**
     * A copy of this snapshot whose tasks of each process in {@code renames}
     * (old name &#8594; new name) carry the new process name, with the task
     * name's process prefix rewritten too (e.g. {@code EXAMPLEFOO (s1)} &#8594;
     * {@code EXAMPLE_FOO (s1)}) so they match the other run's tasks. Other
     * tasks are shared, not copied; this snapshot is left untouched.
     */
    RunSnapshot withRenamedProcesses(Map<String,String> renames) {
        final copy = new RunSnapshot(
                requestedId: requestedId, runName: runName, sessionId: sessionId,
                status: status, revisionId: revisionId, command: command,
                timestamp: timestamp, durationMillis: durationMillis,
                pipeline: pipeline, pipelinePath: pipelinePath,
                nextflowVersion: nextflowVersion, nextflowBuild: nextflowBuild,
                containerEngine: containerEngine, waveEnabled: waveEnabled, fusionEnabled: fusionEnabled )
        copy.tasks = tasks.collect { TaskInfo t ->
            final to = t.process != null ? renames.get(t.process) : null
            return to != null ? t.renamedTo(to) : t
        }
        return copy
    }

    /** Count of tasks grouped by process name, sorted by process name. */
    Map<String,Integer> taskCountByProcess() {
        final counts = new TreeMap<String,Integer>()
        tasks.each { t ->
            final p = t.process ?: '(unknown)'
            counts[p] = (counts[p] ?: 0) + 1
        }
        return counts
    }

    /** Distinct process names present in this run. */
    Set<String> processNames() {
        return tasks.collect { it.process ?: '(unknown)' } as TreeSet
    }

    /**
     * Tasks indexed by their {@link TaskInfo#matchKey()}. Records sharing a key
     * (retried attempts) are collapsed to the final attempt (highest
     * {@code attempt}; later record wins ties). Earlier attempts remain in {@link #tasks}.
     */
    Map<String,TaskInfo> tasksByKey() {
        final map = new LinkedHashMap<String,TaskInfo>()
        tasks.each { TaskInfo t ->
            final key = t.matchKey()
            final prev = map.get(key)
            if( prev == null || (t.numeric('attempt') ?: 0L) >= (prev.numeric('attempt') ?: 0L) )
                map.put(key, t)
        }
        return map
    }

    /** Number of task records per match key. */
    Map<String,Integer> attemptCounts() {
        final counts = new LinkedHashMap<String,Integer>()
        tasks.each { TaskInfo t -> counts.merge(t.matchKey(), 1, { Integer x, Integer y -> x + y } as java.util.function.BiFunction<Integer,Integer,Integer>) }
        return counts
    }

    /** Processes with more than one untagged task, matched by submission index only. */
    Set<String> positionallyMatchedProcesses() {
        final counts = new LinkedHashMap<String,Set<String>>()
        tasksByKey().values().each { TaskInfo t ->
            if( !t.tag )
                counts.computeIfAbsent(t.process ?: '(unknown)', { String k -> new LinkedHashSet<String>() } as java.util.function.Function<String,Set<String>>).add(t.matchKey())
        }
        return counts.findAll { k, v -> v.size() > 1 }.keySet() as LinkedHashSet<String>
    }

    /** Sum of task realtimes in milliseconds. */
    long totalRealtimeMillis() {
        long total = 0L
        tasks.each { TaskInfo t -> total += t.realtimeMillis }
        return total
    }

    /** Number of tasks served from cache. */
    int cachedCount() {
        int n = 0
        tasks.each { TaskInfo t -> if( t.cached ) n++ }
        return n
    }

    /** A short label combining run name and short session id. */
    String label() {
        final shortId = sessionId ? sessionId.toString().substring(0, 8) : '????????'
        return "${runName ?: requestedId} (${shortId})".toString()
    }

    /** This run's status in Nextflow / Seqera Platform vocabulary. */
    String statusLabel() {
        return statusLabel(status)
    }

    /**
     * Map a raw run-level status token to the workflow-status vocabulary used
     * across Nextflow / Seqera Platform. Nextflow records the terse
     * {@code OK}/{@code ERR} tokens in {@code .nextflow/history}; surface them
     * as {@code SUCCEEDED}/{@code FAILED} so the reader is not left guessing
     * what "OK" means. Values already in long form pass through unchanged.
     */
    static String statusLabel(String raw) {
        final s = (raw ?: 'UNKNOWN').toUpperCase()
        if( s.startsWith('OK') || s == 'COMPLETED' || s == 'SUCCEEDED' )
            return 'SUCCEEDED'
        if( s.startsWith('ERR') || s == 'FAILED' )
            return 'FAILED'
        return s
    }
}
