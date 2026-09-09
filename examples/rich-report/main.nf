// Rich-report demo pipeline for nf-diff.
//
// A deliberately small but multi-stage, per-sample "genomics-shaped" pipeline
// (no real tools — plain shell stands in for the aligners/QC). It is designed
// to be run twice under two different profiles so that nf-diff's HTML report
// lights up across every layer: software & versions, configuration, process
// topology, DAG wiring, performance regressions, resource efficiency, failure
// rollup, output diffs and log diffs.
//
// Containers are attached per process in nextflow.config (so the profiles fully
// control the software layer). Strict-syntax clean (Nextflow 26.04+): explicit
// channel factories, explicit closure parameters, `def` locals.

process INDEX_REF {
    tag "${genome}"
    cpus 1
    memory '1 GB'

    input:
    val genome

    output:
    path 'reference.idx'

    script:
    """
    echo "index for ${genome} built with samtools ${params.samtools_tag}" > reference.idx
    """
}

process ALIGN {
    tag "${sample}"
    cpus params.align_cpus
    memory params.align_mem

    input:
    tuple val(sample), path(index)

    output:
    tuple val(sample), path("${sample}.bam")

    script:
    """
    # Stand-in for alignment; sleep models a runtime difference between runs.
    sleep ${params.align_sleep}
    echo "sample=${sample}" > ${sample}.bam
    echo "aligner=${params.aligner}:${params.aligner_tag}" >> ${sample}.bam
    echo "reads_mapped=${params.markdup ? 9421 : 9310}" >> ${sample}.bam
    cat ${index} >> ${sample}.bam
    """
}

process MARKDUP {
    tag "${sample}"
    cpus 1
    memory '2 GB'

    input:
    tuple val(sample), path(bam)

    output:
    tuple val(sample), path("${sample}.dedup.bam")

    script:
    """
    grep -v '^duplicates' ${bam} > ${sample}.dedup.bam
    echo "duplicates_removed=312" >> ${sample}.dedup.bam
    """
}

process QC {
    tag "${sample}"
    cpus 1
    memory '1 GB'
    errorStrategy 'ignore'

    input:
    tuple val(sample), path(bam)

    output:
    path "${sample}.qc.txt"

    script:
    def fail = sample == params.fail_sample
    """
    echo "sample=${sample}" > ${sample}.qc.txt
    echo "fastqc=${params.fastqc_tag}" >> ${sample}.qc.txt
    echo "gc_content=${params.markdup ? 41 : 43}" >> ${sample}.qc.txt
    grep '^aligner' ${bam} >> ${sample}.qc.txt

    if [ "${fail}" = "true" ]; then
        echo "ERROR: adapter contamination above threshold for ${sample}" >&2
        echo "QC gate failed" >&2
        exit 1
    fi
    """
}

process MULTIQC {
    tag "aggregate"
    cpus 1
    memory '1 GB'

    input:
    path 'qc/*'

    output:
    path 'multiqc_report.txt'

    script:
    """
    echo "MultiQC ${params.multiqc_tag}" > multiqc_report.txt
    echo "samples_reported=\$(ls qc | wc -l)" >> multiqc_report.txt
    cat qc/* >> multiqc_report.txt
    """
}

workflow {
    def ref = INDEX_REF(channel.of(params.genome))
    def samples = channel.of('sampleA', 'sampleB', 'sampleC')
    def aligned = ALIGN(samples.combine(ref))
    def qc_input = params.markdup ? MARKDUP(aligned) : aligned
    def qc = QC(qc_input)
    MULTIQC(qc.collect())
}
