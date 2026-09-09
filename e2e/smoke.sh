#!/usr/bin/env bash
#
# End-to-end smoke test for the nf-diff plugin.
#
# The Spock suite covers every component in isolation, but nothing there
# actually resolves the plugin by its bare id and drives the real
# `nextflow plugin nf-diff:diff` launcher against a genuine run history +
# LevelDB cache. That launcher path is exactly where the internal Nextflow
# APIs we reuse (HistoryFile, CacheDB/DefaultCacheStore, ConfigBuilder) can
# drift between Nextflow lines — and the CI matrix pins NXF_VER precisely to
# exercise both 25.04 and 26.04. This script closes that gap: it runs a trivial
# pipeline twice to produce two real runs, then invokes the plugin verb and
# asserts exit codes and report content, against whatever Nextflow the caller
# installed.
#
# Prerequisites (see `make smoke`):
#   * a `nextflow` binary on PATH
#   * NXF_PLUGINS_TEST_REPOSITORY pointing at a local dev repo that advertises
#     nf-diff (produced by `make dev-repo`)
#
set -euo pipefail

command -v nextflow >/dev/null 2>&1 || {
    echo "smoke: nextflow not found on PATH" >&2
    exit 127
}

if [[ -z "${NXF_PLUGINS_TEST_REPOSITORY:-}" ]]; then
    echo "smoke: NXF_PLUGINS_TEST_REPOSITORY is not set — run via 'make smoke'" >&2
    exit 2
fi

echo "smoke: nextflow $(nextflow -v 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9][0-9.]*' | head -1)"
echo "smoke: plugin repo ${NXF_PLUGINS_TEST_REPOSITORY}"

WORKDIR="$(mktemp -d)"
cleanup() { rm -rf "$WORKDIR"; }
trap cleanup EXIT

fail() { echo "smoke: FAIL — $1" >&2; exit 1; }

# A trivial pipeline that stays valid under the v2 strict parser (26.04+):
# no implicit `it`, no `def` functions, explicit channel factory.
cat > "$WORKDIR/main.nf" <<'NF'
process SAY {
    input:
    val message

    output:
    path 'out.txt'

    script:
    """
    echo "${message}" > out.txt
    """
}

workflow {
    SAY(channel.of(params.message))
}
NF

pushd "$WORKDIR" >/dev/null

# Two runs that differ only in a pipeline param, so the comparison has a
# meaningful change to detect.
nextflow run main.nf --message hello
nextflow run main.nf --message world

# 1) JSON report of the two most recent runs — expect a machine-readable
#    document carrying the schemaVersion + summary contract.
nextflow plugin nf-diff:diff --last --format=json --output=report.json
[[ -s report.json ]] || fail "report.json was not written (or is empty)"
grep -q '"schemaVersion"' report.json || fail "report.json is missing schemaVersion"
grep -q '"summary"'       report.json || fail "report.json is missing summary"
echo "smoke: JSON report OK"

# 2) End-to-end correctness: the two runs differ only in the --message param,
#    so the comparison must classify them as not identical. This exercises the
#    whole path — RunLoader over the real HistoryFile + CacheDB, the params
#    layer, and the JSON renderer — and would break if any of those internal
#    APIs drifted on a new Nextflow line.
grep -q '"identical": *false' report.json || fail "expected differing runs to be reported as identical:false"
echo "smoke: change detection OK (identical:false)"

# 3) Default HTML report — a standalone document written to disk.
nextflow plugin nf-diff:diff --last --output=report.html
[[ -s report.html ]] || fail "report.html was not written (or is empty)"
grep -qi '<html' report.html || fail "report.html is not an HTML document"
echo "smoke: HTML report OK"

# NOTE: we intentionally do not assert the process exit code of the
# `nextflow plugin` launcher. On Nextflow 26.04.x the launcher does not
# propagate the plugin's exec() return value, so `--fail-on-change` (documented
# to exit 3) still exits 0 when invoked via `nextflow plugin`. Change detection
# is asserted above via the JSON `identical` field instead.

popd >/dev/null
echo "smoke: PASS"
