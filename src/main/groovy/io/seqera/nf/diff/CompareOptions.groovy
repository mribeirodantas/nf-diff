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

import groovy.transform.CompileStatic

/**
 * The tuning knobs for a {@link RunComparator}, grouped into one named-field
 * value object instead of a long positional constructor argument list.
 *
 * <p>The comparator previously took a dozen positional parameters — four
 * {@code boolean}s and three {@code int}/{@code long}s among them — so nothing
 * but argument <em>order</em> distinguished (say) {@code diffOutputs} from
 * {@code diffLogs} or {@code outputsMaxBytes} from {@code logsMaxLines}. Under
 * {@code @CompileStatic} a transposition of two same-typed arguments compiled
 * silently. Setting these by name removes that hazard and lets a new layer be
 * added without disturbing existing call sites.
 *
 * <p>Every field carries the same default the old constructor used, so a
 * {@code new CompareOptions()} reproduces the previous no-argument behaviour and
 * callers set only what they need — either by property assignment (used by the
 * {@code @CompileStatic} orchestrator) or the named-argument map form (used by
 * the tests).
 */
@CompileStatic
class CompareOptions {

    /** Include fields that always differ between runs (the {@code --verbose} view). */
    boolean showObvious = false

    /** Process-name include/exclude globbing; {@code null} means include everything. */
    ProcessFilter filter = null

    /** Shared project directory for both runs (its {@code .nextflow/}, config, params). */
    Path baseDir = null

    /** Per-run project directory for run A; falls back to {@link #baseDir} when null. */
    Path baseDirA = null

    /** Per-run project directory for run B; falls back to {@link #baseDir} when null. */
    Path baseDirB = null

    /** Percentage change beyond which a task metric is flagged as a regression. */
    double perfThreshold = RunComparator.DEFAULT_PERF_THRESHOLD

    /** When true, compare the output files each matched task wrote to its work dir. */
    boolean diffOutputs = false

    /** Max file size (bytes) to hash when comparing same-size outputs; 0 = no limit. */
    long outputsMaxBytes = 0L

    /** Max leading lines kept per side when line-diffing a changed text output file. */
    int outputsMaxLines = OutputComparator.DEFAULT_MAX_LINES

    /** When true, compare the standard log files each matched task wrote. */
    boolean diffLogs = false

    /** Max tail lines kept per log file when {@link #diffLogs} is set. */
    int logsMaxLines = LogComparator.DEFAULT_MAX_LINES

    /** When true, reconstruct and diff each run's process&#8594;process wiring. */
    boolean diffDag = false

    /**
     * When true, compare the two runs' published output directories
     * ({@link #publishedDirA} / {@link #publishedDirB}). Set implicitly by
     * {@link DiffCommand} when both directories are supplied.
     */
    boolean diffPublished = false

    /** Published output directory for run A; required when {@link #diffPublished} is set. */
    Path publishedDirA = null

    /** Published output directory for run B; required when {@link #diffPublished} is set. */
    Path publishedDirB = null
}
