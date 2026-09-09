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

    /** Human-friendly byte size using Nextflow's MemoryUnit, e.g. "4 GB". */
    static String bytes(Long value) {
        if( value == null )
            return NA
        return new nextflow.util.MemoryUnit(value).toString()
    }

    /** Format an efficiency fraction (0..1+) as a rounded percentage, e.g. "13%". */
    static String pct(Double fraction) {
        if( fraction == null )
            return NA
        return "${Math.round(fraction * 100.0d)}%".toString()
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
        return "${sign}${String.format(Locale.ROOT, '%.1f', pct)}%".toString()
    }
}
