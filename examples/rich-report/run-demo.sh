#!/usr/bin/env bash
#
# Regenerate the nf-diff rich-report demo.
#
# Runs the demo pipeline twice from this directory — a `baseline` run and a
# `changed` run engineered to differ across every nf-diff report layer — then
# renders the standalone HTML report (and a JSON sibling) comparing the two.
#
# The two runs differ in:
#   * parameters & options   (profile, aligner, tool tags, resources)
#   * resolved configuration (ALIGN cpus/memory, per-process containers)
#   * software & versions    (ALIGN + QC images swapped; MARKDUP added)
#   * process topology + DAG (MARKDUP inserted: ALIGN→QC becomes ALIGN→MARKDUP→QC)
#   * performance            (ALIGN sleeps longer in the changed run)
#   * failure rollup         (sampleB's QC gate exits 1 in the changed run)
#   * outputs (--diff-outputs) and logs (--diff-logs)
#
# Prereqs: a `nextflow` binary on PATH, Docker running, and a local plugin repo
# built from the repo root (`make dev-repo`). The image tags referenced in
# nextflow.config are ordinary biocontainers/wave images — pulled on first use.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../.." && pwd)"

# Resolve the plugin by its bare id from the local dev repo.
: "${NXF_PLUGINS_TEST_REPOSITORY:=file://$REPO_ROOT/build/plugin-repo/plugins.json}"
export NXF_PLUGINS_TEST_REPOSITORY
# Hermetic plugin home so the just-built plugin is always used.
export NXF_HOME="$REPO_ROOT/build/nxf-home-demo"

cd "$HERE"

echo "demo: cleaning previous run state"
rm -rf .nextflow* work .lineage report.html report.json

# Nextflow caches extracted plugins by version under NXF_HOME, so a rebuilt
# plugin with an unchanged version is ignored. Drop the extracted copy so this
# run always picks up the freshly assembled `make dev-repo` build.
rm -rf "$NXF_HOME/plugins/nf-diff-"*

echo "demo: run A (baseline)"
nextflow run main.nf -profile baseline

echo "demo: run B (changed)"
# The changed run intentionally fails one QC task (errorStrategy 'ignore' keeps
# the run going), so its exit status is non-zero; don't let that abort the demo.
nextflow run main.nf -profile changed || true

echo "demo: rendering HTML report (all opt-in layers)"
nextflow plugin nf-diff:diff --last --diff-outputs --diff-logs --diff-dag --output=report.html

echo "demo: rendering JSON report"
nextflow plugin nf-diff:diff --last --diff-outputs --diff-logs --diff-dag --format=json --output=report.json

echo "demo: done — open $HERE/report.html"
