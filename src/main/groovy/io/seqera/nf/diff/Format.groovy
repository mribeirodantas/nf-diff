package io.seqera.nf.diff

import groovy.transform.CompileStatic
import nextflow.util.Duration

/**
 * Small formatting helpers shared by the comparator and the renderer.
 */
@CompileStatic
class Format {

    static final String NA = '—'

    /** Format a millisecond duration using Nextflow's human-friendly Duration. */
    static String duration(Long millis) {
        if( millis == null )
            return NA
        if( millis == 0L )
            return '0ms'
        return new Duration(millis).toString()
    }

    /** Null/blank-safe string for display. */
    static String orNa(String value) {
        return (value != null && !value.isEmpty()) ? value : NA
    }

    /** Signed percentage delta of b relative to a, or null when not computable. */
    static Double pctDelta(Long a, Long b) {
        if( a == null || b == null || a == 0L )
            return null
        return ((b - a) / (double) a) * 100.0d
    }

    /** Format a signed percentage with one decimal, e.g. "+12.5%". */
    static String signedPct(Double pct) {
        if( pct == null )
            return NA
        final sign = pct >= 0 ? '+' : ''
        return "${sign}${String.format('%.1f', pct)}%".toString()
    }
}
