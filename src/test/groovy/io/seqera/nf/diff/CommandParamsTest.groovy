package io.seqera.nf.diff

import java.nio.file.Files
import java.nio.file.Path

import spock.lang.Specification
import spock.lang.TempDir

class CommandParamsTest extends Specification {

    @TempDir
    Path tmp

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

    // -- resolve(): merge params-file with CLI, tagged by source ------------

    private Path writeFile(String name, String content) {
        def f = tmp.resolve(name)
        Files.write(f, content.getBytes('UTF-8'))
        return f
    }

    def 'resolve tags plain CLI flags as CLI-sourced'() {
        when:
        def r = CommandParams.resolve('nextflow run main.nf -profile docker --input a.csv', tmp)

        then:
        r.values['--input'] == 'a.csv'
        r.sources['--input'] == CommandParams.SRC_CLI
        r.sources['-profile'] == CommandParams.SRC_CLI
    }

    def 'resolve merges a JSON params-file, flattening nested keys'() {
        given:
        writeFile('params.json', '{"input":"samples.csv","genome":{"build":"GRCh38","gtf":"g.gtf"},"save":true}')

        when:
        def r = CommandParams.resolve('nextflow run main.nf -params-file params.json', tmp)

        then:
        r.values['--input'] == 'samples.csv'
        r.values['--genome.build'] == 'GRCh38'
        r.values['--genome.gtf'] == 'g.gtf'
        r.values['--save'] == 'true'
        and: 'all these came from the file'
        r.sources['--input'] == CommandParams.SRC_FILE
        r.sources['--genome.build'] == CommandParams.SRC_FILE
        and: 'the -params-file option itself is still a CLI option'
        r.sources['-params-file'] == CommandParams.SRC_CLI
    }

    def 'resolve merges a YAML params-file'() {
        given:
        writeFile('params.yaml', 'input: reads.csv\nmax_cpus: 8\n')

        when:
        def r = CommandParams.resolve('nextflow run main.nf -params-file params.yaml', tmp)

        then:
        r.values['--input'] == 'reads.csv'
        r.values['--max_cpus'] == '8'
        r.sources['--input'] == CommandParams.SRC_FILE
    }

    def 'CLI flags override params-file values and are tagged CLI+file'() {
        given:
        writeFile('params.json', '{"input":"from-file.csv","genome":"GRCh37"}')

        when: 'the command also sets --input on the command line'
        def r = CommandParams.resolve('nextflow run main.nf -params-file params.json --input from-cli.csv', tmp)

        then: 'the CLI value wins'
        r.values['--input'] == 'from-cli.csv'
        r.sources['--input'] == CommandParams.SRC_BOTH
        and: 'the file-only value is untouched'
        r.values['--genome'] == 'GRCh37'
        r.sources['--genome'] == CommandParams.SRC_FILE
    }

    def 'a missing params-file is skipped, leaving only CLI flags'() {
        when:
        def r = CommandParams.resolve('nextflow run main.nf -params-file nope.json --input a.csv', tmp)

        then:
        r.values['--input'] == 'a.csv'
        !r.values.containsKey('--genome')
        r.sources['--input'] == CommandParams.SRC_CLI
    }

    def 'resolve without a params-file matches plain CLI parsing'() {
        expect:
        CommandParams.resolve('nextflow run main.nf --input a.csv -r 1.0', tmp).values ==
                CommandParams.parse('nextflow run main.nf --input a.csv -r 1.0')
    }
}
