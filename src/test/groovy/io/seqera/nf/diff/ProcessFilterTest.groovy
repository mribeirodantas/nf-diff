package io.seqera.nf.diff

import spock.lang.Specification

/**
 * Unit tests for the {@code --only} / {@code --exclude} process filter,
 * covering pass-through, include-only, exclude, precedence and glob semantics.
 */
class ProcessFilterTest extends Specification {

    def 'empty filter is pass-through and accepts everything'() {
        given:
        def filter = ProcessFilter.of([], [])

        expect:
        filter.passThrough
        filter.accepts('ANYTHING')
        filter.accepts('FOO:BAR')
        filter.accepts(null)
    }

    def 'only restricts to matching processes'() {
        given:
        def filter = ProcessFilter.of(['ALIGN:*'], [])

        expect:
        !filter.passThrough
        filter.accepts('ALIGN:BWA')
        !filter.accepts('QC:FASTQC')
    }

    def 'exclude removes matching processes'() {
        given:
        def filter = ProcessFilter.of([], ['QC*'])

        expect:
        !filter.accepts('QC_FASTQC')
        filter.accepts('ALIGN:BWA')
    }

    def 'exclude takes precedence over only'() {
        given:
        def filter = ProcessFilter.of(['ALIGN:*'], ['ALIGN:INDEX'])

        expect:
        filter.accepts('ALIGN:BWA')
        !filter.accepts('ALIGN:INDEX')
    }

    def 'the * glob spans scope separators'() {
        given:
        def filter = ProcessFilter.of(['*BWA*'], [])

        expect:
        filter.accepts('ALIGN:BWA:MEM')
        !filter.accepts('ALIGN:STAR')
    }

    def 'the ? glob matches exactly one character'() {
        given:
        def filter = ProcessFilter.of(['FOO?'], [])

        expect:
        filter.accepts('FOOX')
        !filter.accepts('FOO')
        !filter.accepts('FOOXY')
    }

    def 'glob special characters in the process name are matched literally'() {
        given:
        def filter = ProcessFilter.of(['FOO.BAR'], [])

        expect:
        filter.accepts('FOO.BAR')
        !filter.accepts('FOOXBAR')
    }

    def 'any matching only pattern is sufficient'() {
        given:
        def filter = ProcessFilter.of(['ALIGN:*', 'QC:*'], [])

        expect:
        filter.accepts('ALIGN:BWA')
        filter.accepts('QC:FASTQC')
        !filter.accepts('ANNOTATE:SNPEFF')
    }
}
