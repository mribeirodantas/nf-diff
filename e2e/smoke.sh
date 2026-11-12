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
#   * NXF_HOME pointing at a fresh, disposable Nextflow home
#   * NF_DIFF_ZIP / NF_DIFF_VERSION naming the plugin zip just built from this
#     checkout and its version
#
# The plugin under test must be the local build, never the same version
# published on the official registry. A dev repo alone does not guarantee that:
# when the version matches a release, Nextflow installs the published copy.
# So the zip is unpacked into $NXF_HOME/plugins/nf-diff-<version>/ up front
# (Nextflow then uses it as already installed), and at the end the installed
# folder is compared file by file with the zip.
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

for var in NXF_HOME NF_DIFF_ZIP NF_DIFF_VERSION; do
    if [[ -z "${!var:-}" ]]; then
        echo "smoke: $var is not set — run via 'make smoke'" >&2
        exit 2
    fi
done
[[ -s "$NF_DIFF_ZIP" ]] || { echo "smoke: plugin zip not found: $NF_DIFF_ZIP" >&2; exit 2; }

# Unpack a zip with whatever the host has (`unzip` is missing from some JDK
# images; `jar` ships with the JDK the build needs anyway).
unpack() { # <zip> <dest>
    local zip dest
    zip="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
    dest="$2"
    mkdir -p "$dest"
    if command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$zip" -d "$dest"
    elif command -v jar >/dev/null 2>&1; then
        (cd "$dest" && jar xf "$zip")
    else
        python3 -c 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$zip" "$dest"
    fi
}

PLUGIN_DIR="$NXF_HOME/plugins/nf-diff-$NF_DIFF_VERSION"
rm -rf "$PLUGIN_DIR"
unpack "$NF_DIFF_ZIP" "$PLUGIN_DIR"

echo "smoke: nextflow $(nextflow -v 2>/dev/null | grep -oE '[0-9]+\.[0-9]+\.[0-9][0-9.]*' | head -1)"
echo "smoke: plugin repo ${NXF_PLUGINS_TEST_REPOSITORY}"
echo "smoke: plugin nf-diff@${NF_DIFF_VERSION} pre-installed from ${NF_DIFF_ZIP}"

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

# 4) CI exit-code contract: the two runs differ, so `--fail-on-change` must make
#    the process exit 3 — the headline behaviour CI relies on. The `nextflow
#    plugin` launcher discards a verb's exec() return value (it stays 0 no matter
#    what we return, seen on 26.04.1), so the plugin forces the code via
#    System.exit(); this asserts that reaches the shell. Guarded with `set +e`
#    because the non-zero exit is the expected result, not a script failure.
set +e
nextflow plugin nf-diff:diff --last --fail-on-change --format=json --output=report-fail.json
code=$?
set -e
[[ $code -eq 3 ]] || fail "expected --fail-on-change to exit 3 for differing runs, got $code"
echo "smoke: --fail-on-change exit code OK (3)"

popd >/dev/null

# 5) Provenance: everything above must have run the plugin built from this
#    checkout. The installed folder has to match the local zip exactly; a
#    published release of the same version (or any re-download) would differ.
mkdir -p "$WORKDIR/expected"
unpack "$NF_DIFF_ZIP" "$WORKDIR/expected"
diff -r "$WORKDIR/expected" "$PLUGIN_DIR" >"$WORKDIR/provenance.diff" 2>&1 || {
    head -20 "$WORKDIR/provenance.diff" >&2
    fail "installed plugin in $PLUGIN_DIR does not match the local build $NF_DIFF_ZIP"
}
others="$(find "$NXF_HOME/plugins" -maxdepth 1 -name 'nf-diff-*' ! -path "$PLUGIN_DIR" -print)"
[[ -z "$others" ]] || fail "unexpected nf-diff installs next to the local build: $others"
echo "smoke: plugin provenance OK (installed files match $(basename "$NF_DIFF_ZIP"))"

echo "smoke: PASS"
