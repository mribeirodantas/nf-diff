package io.seqera.nf.diff

import spock.lang.Specification

/**
 * Direct contract tests for {@link DiffCommand.ArgCursor}, the cursor that
 * drives argument parsing. {@link DiffCommandTest} covers the end-to-end
 * {@code parse()} behaviour; this suite pins the cursor primitives in
 * isolation — in particular the token-consumption semantics that the old
 * hand-rolled {@code if( inlineVal == null ) i++} bookkeeping used to encode,
 * and the shared numeric parse/range-check helpers.
 *
 * <p>Real usage per option is: {@code next()} to load the token, then one
 * value helper (which may consume the following token), then {@code advance()}
 * to step past it — so the tests mirror that sequence.
 */
class ArgCursorTest extends Specification {

    private DiffCommand.ArgCursor cursor(List<String> args) {
        return new DiffCommand.ArgCursor(args)
    }

    // --- next(): token loading and inline --key=value splitting ---

    def 'next splits an inline --key=value into key and inlineVal'() {
        given:
        def cur = cursor(['--output=report.html'])

        when:
        def raw = cur.next()

        then:
        raw == '--output=report.html'
        cur.key == '--output'
        cur.inlineVal == 'report.html'
    }

    def 'next on a bare option leaves inlineVal null'() {
        given:
        def cur = cursor(['--verbose'])

        when:
        cur.next()

        then:
        cur.key == '--verbose'
        cur.inlineVal == null
    }

    def 'next on a positional (no leading dash) is not treated as an option'() {
        given:
        def cur = cursor(['run_A'])

        when:
        def raw = cur.next()

        then:
        raw == 'run_A'
        cur.key == 'run_A'
        cur.inlineVal == null
    }

    def 'a lone dash is not split (keeps inlineVal null)'() {
        given:
        def cur = cursor(['-'])

        when:
        cur.next()

        then:
        cur.key == '-'
        cur.inlineVal == null
    }

    // --- requireValue(): inline vs space-separated, and consumption ---

    def 'requireValue returns the inline value without consuming a token'() {
        given:
        def cur = cursor(['--dir=/tmp/proj'])

        when:
        cur.next()
        def val = cur.requireValue()

        then:
        val == '/tmp/proj'
        cur.i == 0 // nothing consumed; advance() alone will step past this token
    }

    def 'requireValue consumes the following token for the space-separated form'() {
        given:
        def cur = cursor(['--dir', '/tmp/proj'])

        when:
        cur.next()
        def val = cur.requireValue()

        then:
        val == '/tmp/proj'
        cur.i == 1 // the value token was consumed
    }

    def 'requireValue throws when a spaced option has no following value'() {
        given:
        def cur = cursor(['--output'])

        when:
        cur.next()
        cur.requireValue()

        then:
        thrown(DiffCommand.UsageException)
    }

    // --- boolValue(): bare true, inline, and injected true/false ---

    def 'boolValue on a bare flag is true and consumes nothing'() {
        given:
        def cur = cursor(['--fail-on-change'])

        when:
        cur.next()
        def val = cur.boolValue()

        then:
        val
        cur.i == 0
    }

    def 'boolValue honours the inline --flag=false form'() {
        given:
        def cur = cursor(['--diff-all=false'])

        when:
        cur.next()
        def val = cur.boolValue()

        then:
        !val
        cur.i == 0
    }

    def 'boolValue consumes a launcher-injected true/false token'() {
        given:
        def cur = cursor(['--diff-outputs', 'false'])

        when:
        cur.next()
        def val = cur.boolValue()

        then:
        !val
        cur.i == 1 // the injected boolean was consumed
    }

    def 'boolValue does not consume a following non-boolean token'() {
        given:
        def cur = cursor(['--verbose', 'run_B'])

        when:
        cur.next()
        def val = cur.boolValue()

        then:
        val
        cur.i == 0 // 'run_B' is left for the next iteration
    }

    // --- numeric helpers: parse + range-check + consumption ---

    def 'intValue parses an inline value and enforces the floor'() {
        given:
        def cur = cursor(['--outputs-max-lines=50'])

        when:
        cur.next()
        def val = cur.intValue(1, 'an integer')

        then:
        val == 50
    }

    def 'intValue consumes the space-separated value'() {
        given:
        def cur = cursor(['--outputs-max-lines', '80'])

        when:
        cur.next()
        def val = cur.intValue(1, 'an integer')

        then:
        val == 80
        cur.i == 1
    }

    def 'intValue rejects a non-integer'() {
        given:
        def cur = cursor(['--outputs-max-lines=lots'])

        when:
        cur.next()
        cur.intValue(1, 'an integer')

        then:
        thrown(DiffCommand.UsageException)
    }

    def 'intValue rejects a value below the floor'() {
        given:
        def cur = cursor(['--outputs-max-lines=0'])

        when:
        cur.next()
        cur.intValue(1, 'an integer')

        then:
        thrown(DiffCommand.UsageException)
    }

    def 'longValue parses and enforces the floor'() {
        expect:
        cursorValue(['--outputs-max-bytes=1024']) { it.longValue(0L, 'an integer number of bytes') } == 1024L

        when:
        def cur = cursor(['--outputs-max-bytes=-1'])
        cur.next()
        cur.longValue(0L, 'an integer number of bytes')

        then:
        thrown(DiffCommand.UsageException)
    }

    def 'longValue rejects a non-integer'() {
        given:
        def cur = cursor(['--outputs-max-bytes=big'])

        when:
        cur.next()
        cur.longValue(0L, 'an integer number of bytes')

        then:
        thrown(DiffCommand.UsageException)
    }

    def 'doubleValue parses and enforces the floor'() {
        expect:
        cursorValue(['--perf-threshold=12.5']) { it.doubleValue(0.0d, 'a number (percent)') } == 12.5d

        when:
        def cur = cursor(['--perf-threshold=-1'])
        cur.next()
        cur.doubleValue(0.0d, 'a number (percent)')

        then:
        thrown(DiffCommand.UsageException)
    }

    def 'doubleValue rejects a non-number'() {
        given:
        def cur = cursor(['--perf-threshold=lots'])

        when:
        cur.next()
        cur.doubleValue(0.0d, 'a number (percent)')

        then:
        thrown(DiffCommand.UsageException)
    }

    // --- peek()/consumePeeked() and iteration ---

    def 'peek returns the following token without consuming it'() {
        given:
        def cur = cursor(['--last', '2'])

        when:
        cur.next()

        then:
        cur.peek() == '2'
        cur.i == 0

        when:
        cur.consumePeeked()

        then:
        cur.i == 1
    }

    def 'peek returns null at the end of the argument list'() {
        given:
        def cur = cursor(['--last'])

        when:
        cur.next()

        then:
        cur.peek() == null
    }

    def 'hasNext and advance walk the full token list once'() {
        given:
        def cur = cursor(['a', 'b', 'c'])
        def seen = []

        when:
        while( cur.hasNext() ) {
            seen << cur.next()
            cur.advance()
        }

        then:
        seen == ['a', 'b', 'c']
        !cur.hasNext()
    }

    /** Load the first token and apply a value helper, returning its result. */
    private static <T> T cursorValue(List<String> args, Closure<T> extract) {
        def cur = new DiffCommand.ArgCursor(args)
        cur.next()
        return extract(cur)
    }
}
