package io.seqera.nf.diff

import spock.lang.Specification

/**
 * Argument-parsing behaviour for the {@code diff} verb, focused on the
 * --format / --fail-on-change options and the format-aware output default.
 */
class DiffCommandTest extends Specification {

    private DiffCommand parse(List<String> args) {
        def cmd = new DiffCommand()
        cmd.parse(args)
        return cmd
    }

    def 'defaults to html format and the html output file'() {
        when:
        def cmd = parse(['runA', 'runB'])

        then:
        cmd.format == 'html'
        cmd.outputFile.fileName.toString() == 'nf-diff-report.html'
        !cmd.failOnChange
    }

    def 'json format switches the default output extension'() {
        when:
        def cmd = parse(['runA', 'runB', '--format=json'])

        then:
        cmd.format == 'json'
        cmd.outputFile.fileName.toString() == 'nf-diff-report.json'
    }

    def 'explicit output path is preserved regardless of format'() {
        when:
        def cmd = parse(['runA', 'runB', '--format=json', '--output=custom.out'])

        then:
        cmd.format == 'json'
        cmd.outputFile.fileName.toString() == 'custom.out'
    }

    def 'format value is case-insensitive'() {
        expect:
        parse(['a', 'b', '--format=JSON']).format == 'json'
        parse(['a', 'b', '--format=Html']).format == 'html'
    }

    def 'fail-on-change flag is parsed'() {
        expect:
        parse(['runA', 'runB', '--fail-on-change']).failOnChange
    }

    def 'an unsupported format is rejected'() {
        when:
        parse(['runA', 'runB', '--format=yaml'])

        then:
        thrown(DiffCommand.UsageException)
    }

    def 'the space-separated --format form is also accepted'() {
        expect:
        parse(['runA', 'runB', '--format', 'json']).format == 'json'
    }

    def '--last is parsed and needs no positional run identifiers'() {
        when:
        def cmd = parse(['--last'])

        then:
        cmd.last
        cmd.runA == null
        cmd.runB == null
    }

    def '--last combines with other options'() {
        when:
        def cmd = parse(['--last', '--format=json', '--fail-on-change'])

        then:
        cmd.last
        cmd.format == 'json'
        cmd.failOnChange
        cmd.outputFile.fileName.toString() == 'nf-diff-report.json'
    }

    def '--last cannot be combined with explicit run identifiers'() {
        when:
        parse(['runA', 'runB', '--last'])

        then:
        thrown(DiffCommand.UsageException)
    }

    def 'two run identifiers are still required without --last'() {
        when:
        parse(['runA'])

        then:
        thrown(DiffCommand.UsageException)
    }

    def '--output=- selects stdout output'() {
        when:
        def cmd = parse(['runA', 'runB', '--output=-'])

        then:
        cmd.toStdout()
        cmd.outputFile.toString() == '-'
    }

    def 'stdout output is preserved even with json format'() {
        when:
        def cmd = parse(['runA', 'runB', '--format=json', '--output=-'])

        then:
        cmd.format == 'json'
        cmd.toStdout()
        cmd.outputFile.toString() == '-'
    }

    def 'a normal output path is not treated as stdout'() {
        expect:
        !parse(['runA', 'runB']).toStdout()
        !parse(['runA', 'runB', '--output=report.html']).toStdout()
    }
}
