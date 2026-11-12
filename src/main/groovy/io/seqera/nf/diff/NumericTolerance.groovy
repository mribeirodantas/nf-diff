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

import java.util.function.BiPredicate
import java.util.regex.Matcher
import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * Line equality that tolerates small numeric differences
 * ({@code --numeric-tolerance}), for text outputs where floating-point noise
 * ({@code 0.30000000000000004} vs {@code 0.3}, thread-order effects) would
 * otherwise show as changed lines.
 *
 * <p>Two lines are equal when they are identical apart from their numbers, and
 * every pair of numbers is within tolerance: {@code |a - b| <= tol * max(1,
 * |a|, |b|)}, i.e. a relative tolerance for values above 1 and an absolute one
 * below. Both lines must contain the same numbers in the same places.
 *
 * <p>A number glued to a letter or {@code _} on its left (as in {@code chr1}
 * or {@code sample_01}) is part of an identifier and must match exactly, so
 * IDs are never "close enough".
 */
@CompileStatic
class NumericTolerance implements BiPredicate<String,String> {

    /** A decimal or scientific number not preceded by an identifier character or another digit/dot. */
    private static final Pattern NUMBER = ~/(?<![A-Za-z_0-9.])[-+]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?/

    /** The tolerance; always {@code > 0}. */
    final double tolerance

    private NumericTolerance(double tolerance) {
        this.tolerance = tolerance
    }

    /** A tolerance, or null when {@code tolerance <= 0} (exact comparison). */
    static NumericTolerance of(double tolerance) {
        return tolerance > 0 ? new NumericTolerance(tolerance) : null
    }

    /** Whether two numbers are within tolerance of each other. */
    boolean close(double a, double b) {
        if( a == b )
            return true
        if( Double.isNaN(a) || Double.isNaN(b) || Double.isInfinite(a) || Double.isInfinite(b) )
            return false
        return Math.abs(a - b) <= tolerance * Math.max(1.0d, Math.max(Math.abs(a), Math.abs(b)))
    }

    /** Whether two lines are equal apart from numbers within tolerance. */
    @Override
    boolean test(String a, String b) {
        if( a == b )
            return true
        if( a == null || b == null )
            return false
        final Matcher ma = NUMBER.matcher(a)
        final Matcher mb = NUMBER.matcher(b)
        int posA = 0
        int posB = 0
        while( true ) {
            final foundA = ma.find()
            final foundB = mb.find()
            if( foundA != foundB )
                return false
            if( !foundA )
                return a.substring(posA) == b.substring(posB)
            // The text between numbers must match exactly.
            if( a.substring(posA, ma.start()) != b.substring(posB, mb.start()) )
                return false
            if( ma.group() != mb.group() && !close(Double.parseDouble(ma.group()), Double.parseDouble(mb.group())) )
                return false
            posA = ma.end()
            posB = mb.end()
        }
    }

    @Override
    String toString() {
        return String.valueOf(tolerance)
    }
}
