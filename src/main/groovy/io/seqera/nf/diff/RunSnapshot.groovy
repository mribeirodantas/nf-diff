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

    // -- Nextflow version & runtime environment (from the lineage WorkflowRun
    //    record, when a .lineage/ store is present; otherwise all null).
    String nextflowVersion
    String nextflowBuild
    String containerEngine
    Boolean waveEnabled
    Boolean fusionEnabled

    // -- tasks (from CacheDB)
    List<TaskInfo> tasks = []

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

    /** Tasks indexed by their {@link TaskInfo#matchKey()}. */
    Map<String,TaskInfo> tasksByKey() {
        final map = new LinkedHashMap<String,TaskInfo>()
        tasks.each { t -> map[t.matchKey()] = t }
        return map
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
}
