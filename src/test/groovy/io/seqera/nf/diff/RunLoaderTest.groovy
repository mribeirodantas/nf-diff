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

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

/**
 * Unit coverage of {@link RunLoader}'s pure static helpers plus a
 * fixture-backed slice of {@link RunLoader#lastPair}. The full {@code load}
 * path additionally needs a LevelDB cache directory, but {@code lastPair} only
 * reads {@code .nextflow/history}, so it can be exercised end to end against a
 * hand-written history fixture. The pure helpers — transient-lock detection
 * that drives the retry loop, the trace-store value coercions, and session-id
 * shortening — are tested in isolation. Pinning them guards the retry path
 * against silent breakage if a LevelDB/Nextflow message is reworded, and locks
 * in the null-handling the snapshot mapping relies on.
 */
class RunLoaderTest extends Specification {

    // ---- isLockError -------------------------------------------------------

    @Unroll
    def 'isLockError detects transient cache-lock failure: #desc'() {
        expect:
        RunLoader.isLockError(throwable) == expected

        where:
        desc                             | throwable                                                        || expected
        'literal LevelDB message'        | new IOException('Unable to acquire lock on \'/x/.nextflow/cache\'') || true
        'case-insensitive match'         | new IOException('UNABLE TO ACQUIRE LOCK')                        || true
        'resource temporarily unavail.'  | new IOException('lock failed: Resource temporarily unavailable')  || true
        'another process message'        | new RuntimeException('Another process is using this database')    || true
        'lock currently held'            | new RuntimeException('lock currently held by another process')    || true
        'unrelated failure'              | new IOException('No such file or directory')                      || false
        'null message'                   | new NullPointerException()                                        || false
    }

    def 'isLockError walks the cause chain'() {
        given: 'a wrapper whose root cause is a lock error'
        def root = new IOException('Unable to acquire lock on the cache')
        def mid = new RuntimeException('open failed', root)
        def top = new IllegalStateException('could not read run', mid)

        expect:
        RunLoader.isLockError(top)

        and: 'a chain with no lock error anywhere returns false'
        !RunLoader.isLockError(new RuntimeException('a', new IOException('b')))
    }

    def 'isLockError is null-safe'() {
        expect:
        !RunLoader.isLockError(null)
    }

    // ---- asLong ------------------------------------------------------------

    @Unroll
    def 'asLong coerces #value to #expected'() {
        expect:
        RunLoader.asLong(value) == expected

        where:
        value        || expected
        null         || null
        42L          || 42L
        42           || 42L
        (3.9d)       || 3L          // Number path truncates toward zero
        '1000'       || 1000L
        '-7'         || -7L
        'abc'        || null
        '1.5'        || null        // not an integer string
        ' 5 '        || null        // parseLong does not trim
        ''           || null
    }

    // ---- asString ----------------------------------------------------------

    @Unroll
    def 'asString renders #value as #expected'() {
        expect:
        RunLoader.asString(value) == expected

        where:
        value   || expected
        null    || null
        'hi'    || 'hi'
        42      || '42'
        true    || 'true'
    }

    // ---- shortId -----------------------------------------------------------

    def 'shortId returns the first 8 chars of a session id'() {
        given:
        def id = UUID.fromString('12345678-90ab-cdef-1234-567890abcdef')

        expect:
        RunLoader.shortId(id) == '12345678'
    }

    def 'shortId returns a placeholder for a null id'() {
        expect:
        RunLoader.shortId(null) == '????????'
    }

    // ---- lastPair (fixture-backed) ----------------------------------------

    @TempDir
    Path tmp

    /**
     * Write a {@code .nextflow/history} fixture with one row per run name, in
     * chronological order (latest last, matching Nextflow's append-only file),
     * and return a {@link RunLoader} rooted at the fixture's base dir.
     */
    private RunLoader loaderWithHistory(List<String> runNames) {
        final dir = Files.createDirectories(tmp.resolve('.nextflow'))
        final rev = 'afff16a9b45c8e8a4f5a3743780ac13a541762f8'
        final lines = runNames.withIndex().collect { String name, int i ->
            // timestamp, duration, runName, status, revisionId, sessionId, command
            [ "2026-09-08 12:0${i}:00", '1.5s', name, 'OK', rev,
              UUID.randomUUID().toString(), 'nextflow run hello' ].join('\t')
        }
        Files.write(dir.resolve('history'), (lines.join('\n') + '\n').bytes)
        return new RunLoader(tmp)
    }

    def 'lastPair with the default B offset compares run A-back against the latest'() {
        given:
        def loader = loaderWithHistory(['r0', 'r1', 'r2', 'r3', 'r4'])

        expect: 'bare --last (backA=1) picks the two most recent runs'
        loader.lastPair(1) == ['r3', 'r4']

        and: '--last=2 skips the run in between and diffs against the latest'
        loader.lastPair(2) == ['r2', 'r4']
    }

    def 'lastPair with an explicit B offset names an adjacent pair'() {
        given:
        def loader = loaderWithHistory(['r0', 'r1', 'r2', 'r3', 'r4'])

        expect: '--last=2:1 compares the run two back (A) against the run one back (B)'
        loader.lastPair(2, 1) == ['r2', 'r3']

        and: 'B=0 is equivalent to the single-offset form'
        loader.lastPair(2, 0) == loader.lastPair(2)
    }

    def 'lastPair rejects a negative B offset'() {
        given:
        def loader = loaderWithHistory(['r0', 'r1'])

        when:
        loader.lastPair(1, -1)

        then:
        thrown(IllegalArgumentException)
    }

    def 'lastPair rejects A <= B'() {
        given:
        def loader = loaderWithHistory(['r0', 'r1', 'r2'])

        when:
        loader.lastPair(1, 2)

        then:
        thrown(IllegalArgumentException)
    }

    def 'lastPair fails when history has fewer than A+1 runs'() {
        given:
        def loader = loaderWithHistory(['r0', 'r1'])

        when: 'run A is 2 back but only 2 runs exist (needs 3)'
        loader.lastPair(2)

        then:
        thrown(IllegalArgumentException)
    }
}
