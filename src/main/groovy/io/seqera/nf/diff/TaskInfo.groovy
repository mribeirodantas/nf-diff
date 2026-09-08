package io.seqera.nf.diff

import groovy.transform.CompileStatic

/**
 * Immutable-ish snapshot of a single task extracted from a run's cache DB.
 *
 * Holds both a display map (human-formatted field values, as produced by
 * {@code TraceRecord.getFmtStr}) and a raw map (original values) so the
 * comparator can diff formatted strings for presentation while still doing
 * numeric comparisons (durations, memory, cpu) for the resource charts.
 */
@CompileStatic
class TaskInfo {

    String hash
    String process
    String name
    String tag
    String status
    String exit
    String container
    String script
    String workdir
    boolean cached

    /** Wall-clock duration in milliseconds (complete - submit). */
    long durationMillis
    /** Task realtime (execution) in milliseconds. */
    long realtimeMillis

    /** Human-formatted values for every trace field. */
    Map<String,String> display = [:]
    /** Raw trace values, used for numeric comparisons. */
    Map<String,Object> raw = [:]

    /**
     * Stable identity used to match the "same" task across two runs. Prefer
     * the fully-qualified task name (e.g. {@code FOO (1)}); fall back to
     * process + tag when a name is unavailable.
     */
    String matchKey() {
        if( name )
            return name
        return tag ? "${process} (${tag})".toString() : (process ?: hash)
    }

    /** Coerce a raw numeric field to Long, or null when absent/non-numeric. */
    Long numeric(String field) {
        final v = raw?.get(field)
        if( v == null )
            return null
        if( v instanceof Number )
            return ((Number) v).longValue()
        try {
            return Long.parseLong(v.toString())
        }
        catch( NumberFormatException ignored ) {
            return null
        }
    }

    /** Coerce a raw numeric field to Double, or null when absent/non-numeric. */
    Double numericDouble(String field) {
        final v = raw?.get(field)
        if( v == null )
            return null
        if( v instanceof Number )
            return ((Number) v).doubleValue()
        try {
            return Double.parseDouble(v.toString())
        }
        catch( NumberFormatException ignored ) {
            return null
        }
    }
}
