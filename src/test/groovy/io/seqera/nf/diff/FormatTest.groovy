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
 * Unit tests for the {@link Format} display helpers. These are pure functions
 * used by every renderer, so pinning their null-handling and formatting guards
 * against silent regressions in the report output.
 */
class FormatTest extends Specification {

    def 'duration formats millis and null/zero edge cases'() {
        expect:
        Format.duration(null) == Format.NA
        Format.duration(0L)   == '0ms'
        Format.duration(1000L) == '1s'
    }

    def 'orNa falls back for null and empty strings'() {
        expect:
        Format.orNa(null)    == Format.NA
        Format.orNa('')      == Format.NA
        Format.orNa('value') == 'value'
    }

    def 'bytes formats human-friendly sizes and null'() {
        expect:
        Format.bytes(null)          == Format.NA
        Format.bytes(1073741824L)   == '1 GB'
    }

    def 'pct renders a rounded percentage from a fraction'() {
        expect:
        Format.pct(null)  == Format.NA
        Format.pct(0.13d) == '13%'
        Format.pct(1.0d)  == '100%'
    }

    def 'pctDelta computes signed relative change and handles a zero baseline'() {
        expect:
        Format.pctDelta(100L, 150L) == 50.0d
        Format.pctDelta(100L, 50L)  == -50.0d
        Format.pctDelta(null, 50L)  == null
        Format.pctDelta(100L, null) == null
        and: 'a zero baseline is not "not computable": 0->0 is no change, 0->N is unbounded growth'
        Format.pctDelta(0L, 0L)     == 0.0d
        Format.pctDelta(0L, 50L)    == Double.POSITIVE_INFINITY
        Format.pctDelta(0L, -50L)   == Double.NEGATIVE_INFINITY
    }

    def 'signedPct prefixes a sign and one decimal'() {
        expect:
        Format.signedPct(null)   == Format.NA
        Format.signedPct(12.5d)  == '+12.5%'
        Format.signedPct(-3.0d)  == '-3.0%'
        Format.signedPct(0.0d)   == '+0.0%'
    }

    def 'signedPct renders an infinite delta as an infinity symbol'() {
        expect:
        Format.signedPct(Double.POSITIVE_INFINITY) == '+∞%'
        Format.signedPct(Double.NEGATIVE_INFINITY) == '−∞%'
    }
}
