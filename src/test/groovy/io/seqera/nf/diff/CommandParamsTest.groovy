package io.seqera.nf.diff

import spock.lang.Specification

class CommandParamsTest extends Specification {

    def 'parses pipeline params and nextflow options with values'() {
        when:
        def p = CommandParams.parse('nextflow run main.nf -profile docker -r 1.2.0 --input samples.csv --genome GRCh38')

        then:
        p['-profile'] == 'docker'
        p['-r'] == '1.2.0'
        p['--input'] == 'samples.csv'
        p['--genome'] == 'GRCh38'
        and: 'positional tokens (nextflow, run, main.nf) are ignored'
        !p.containsKey('main.nf')
        !p.containsKey('run')
    }

    def 'supports --key=value form'() {
        expect:
        CommandParams.parse('nextflow run main.nf --max_cpus=16 --outdir=results')['--max_cpus'] == '16'
        CommandParams.parse('nextflow run main.nf --max_cpus=16 --outdir=results')['--outdir'] == 'results'
    }

    def 'treats a valueless flag as a boolean true'() {
        when:
        def p = CommandParams.parse('nextflow run main.nf -resume --save_reference --input x')

        then:
        p['-resume'] == 'true'
        p['--save_reference'] == 'true'
        p['--input'] == 'x'
    }

    def 'keeps quoted values containing spaces intact'() {
        when:
        def p = CommandParams.parse('nextflow run main.nf --title "My great run" --note \'a b c\'')

        then:
        p['--title'] == 'My great run'
        p['--note'] == 'a b c'
    }

    def 'distinguishes pipeline params from nextflow options'() {
        expect:
        CommandParams.isPipelineParam('--input')
        !CommandParams.isPipelineParam('-profile')
        !CommandParams.isPipelineParam(null)
    }

    def 'empty or null command yields no flags'() {
        expect:
        CommandParams.parse(null).isEmpty()
        CommandParams.parse('').isEmpty()
        CommandParams.parse('nextflow run main.nf').isEmpty()
    }
}
