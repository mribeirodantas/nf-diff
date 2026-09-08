package io.seqera.nf.diff

import spock.lang.Specification
import spock.lang.Unroll

/**
 * Unit coverage of {@link RunLoader}'s pure static helpers. The full loader
 * needs a real {@code .nextflow/history} file and a LevelDB cache directory to
 * exercise end to end, but its riskiest small pieces — the transient-lock
 * detection that drives the retry loop, the trace-store value coercions, and
 * the session-id shortening — are pure and testable in isolation. Pinning them
 * guards the retry path against silent breakage if a LevelDB/Nextflow message
 * is reworded, and locks in the null-handling the snapshot mapping relies on.
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
}
