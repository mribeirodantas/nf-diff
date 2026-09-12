---
title: Usage
description: Installation, arguments, every option, examples, sample output, and exit codes for nf-diff.
---

## Requirements

- **Nextflow** `>= 25.04.0`
- **Java** 17+ (as required by your Nextflow version)
- Two runs present in the local `.nextflow/history` of the project you're inspecting

## Installation

Once published to the [Nextflow plugin registry](https://www.nextflow.io/docs/latest/plugins.html), the plugin resolves automatically:

```bash
nextflow plugin nf-diff:diff <runA> <runB>
```

### Local / unpublished builds

Until it's published, build a local test repository so Nextflow can resolve the plugin by its **bare id**:

```bash
make dev-repo
export NXF_PLUGINS_TEST_REPOSITORY="file://$PWD/build/plugin-repo/plugins.json"
nextflow plugin nf-diff:diff <runA> <runB>
```

> **Invoke the bare id (`nf-diff:diff`), not a pinned version.** Nextflow's `plugin` command resolves the plugin instance by its bare id. `nf-diff@<version>:diff` starts the plugin but then fails with `Cannot find target plugin: nf-diff@<version>`. The `dev-repo` step exists so the bare id has a source that advertises the plugin's version.

## Command

```
nextflow plugin nf-diff:diff <runA> <runB> [options]
```

### Arguments

| Argument        | Description                                                         |
|-----------------|---------------------------------------------------------------------|
| `<runA> <runB>` | Run names or session UUID prefixes from `.nextflow/history`. Omit both when using `--last`. |

### Options

| Option                 | Description                                                                                      |
|------------------------|--------------------------------------------------------------------------------------------------|
| `-l`, `--last[=N\|A:B]` | Compare recent runs from history. Bare `--last` compares the two most recent runs. `--last=N` compares the run *N* positions before the latest (A) against the latest (B) — note this **skips** the runs in between. Use the explicit `--last=A:B` form to name an exact pair by their offsets back from the latest (`0` = latest, `A > B >= 0`); e.g. `--last=2:1` compares the run two back against the run one back. Cannot be combined with explicit run identifiers. |
| `--format=<fmt>`       | Report format: `html` (default), `json`, or `md` (`markdown`)                                    |
| `--output=<file>`      | Output report path (default: `nf-diff-report.<ext>`, where `<ext>` matches the chosen format). Use `-` to write to stdout. |
| `--only=<globs>`       | Comma-separated process-name globs; only matching processes/tasks are compared (`*` and `?` supported, `*` spans `:` scopes) |
| `--exclude=<globs>`    | Comma-separated process-name globs to drop from the comparison; applied after `--only`           |
| `--perf-threshold=<pct>` | Percentage change beyond which a task metric (runtime, peak memory) is flagged in the performance-regressions layer (default: `25`). Must be `>= 0`; `0` flags any measurable change. |
| `--diff-outputs`       | Compare the output files each matched task wrote to its work directory (by size, then SHA-256 for same-size files). Changed text files are additionally diffed line by line; binary files show a size/hash change only. Requires the tasks' work directories to still exist locally. When enabled, an output-file change counts toward `--fail-on-change`. |
| `--outputs-max-bytes=<n>` | With `--diff-outputs`, skip hashing same-size files larger than `<n>` bytes (they are reported as content-unverified). Default `0` = no limit. |
| `--outputs-max-lines=<n>` | With `--diff-outputs`, keep only the first `<n>` lines of each changed text file before line-diffing it. Default `1000`. |
| `--published-a=<dir>` / `--published-b=<dir>` | Compare the two runs' published output directories (their `outdir` / `publishDir` trees) instead of — or in addition to — the per-task work-dir outputs. Give both: `--published-a` is run A's directory, `--published-b` run B's. Files are matched by their path relative to each root, then compared by size, then SHA-256, then a line-level diff for changed text files, exactly like `--diff-outputs` (and honouring `--outputs-max-bytes` / `--outputs-max-lines`). Symlinks are followed, so it works whether `publishDir` copied or symlinked. Unlike `--diff-outputs`, this reads the *durable* published results, so it still works after the work directories are gone. When set, a published-file change counts toward `--fail-on-change`. |
| `--diff-logs`          | Compare the standard log files (`.command.out`/`.command.err`/`.command.log`) each matched task wrote to its work directory, line by line. Ideal for inspecting why a task's exit code changed. Requires the tasks' work directories to still exist locally. Informational only: never affects `--fail-on-change`. |
| `--logs-max-lines=<n>` | With `--diff-logs`, keep only the last `<n>` lines of each log file before diffing. Default `200`. |
| `--diff-dag`           | Reconstruct each run's process→process wiring and diff the two edge sets, surfacing added/removed edges (a rewired pipeline). Reads the authoritative `.lineage/` data-lineage store when present (`lineage.enabled = true`, no work dirs needed); otherwise infers edges from the input symlinks staged into task work directories, which must still exist locally (best-effort when some were cleaned up). Informational only: never affects `--fail-on-change`. |
| `--diff-all`           | Enable all three work-dir layers at once (`--diff-outputs`, `--diff-logs`, `--diff-dag`); they share the same precondition (the tasks' work directories must still exist locally). Enables only — a later explicit `--diff-<layer>=false` can still switch an individual layer back off. |
| `--fail-on-change`     | Exit with code `3` if the runs are not identical (useful in CI)                                  |
| `-q`, `--quiet`, `--summary-only` | Print only the summary block (the `N changed, …` line); skip rendering and writing the full report. Handy for CI logs where the report body is noise. Exit-code behaviour (including `--fail-on-change`) is unaffected. |
| `--dir=<dir>`          | Project directory containing `.nextflow/` (default: `.`). Used for both runs unless overridden per-run below. |
| `--dir-a=<dir>` / `--dir-b=<dir>` | Per-run project directory for run A / run B (its `.nextflow/` history, cache, config and params). Use these to compare a run from one project or checkout against a run from another ("same pipeline, two directories"). Each falls back to `--dir` when omitted. Cannot be combined with `--last`, which needs a single history. |
| `-v`, `--verbose`, `--all` | Also diff fields that always change between runs (run name, session id, launch time, work dir, wall/real time, resource usage) |
| `-h`, `--help`         | Show help                                                                                        |

> **Use the inline `--output=<file>` form (with `=`).** Nextflow's `plugin` launcher swallows space-separated flags such as `-o compare.html` before they reach the plugin.

## Examples

```bash
# Compare two runs by name; report goes to nf-diff-report.html
nextflow plugin nf-diff:diff tender_euler happy_curie

# Compare the two most recent runs — no need to look up names
nextflow plugin nf-diff:diff --last

# Compare the run two-before-latest against the latest
nextflow plugin nf-diff:diff --last=2

# Compare an adjacent pair without diffing against the latest: the run two
# back (A) against the run one back (B).
nextflow plugin nf-diff:diff --last=2:1

# Compare by session-id prefix and choose the output file
nextflow plugin nf-diff:diff 3a8c1f2e 9f2b7d10 --output=compare.html

# Emit a Markdown report for a pull-request comment
nextflow plugin nf-diff:diff --last --format=md --output=diff.md

# Focus on the alignment processes, ignoring QC noise
nextflow plugin nf-diff:diff --last --only='ALIGN:*' --exclude='*:INDEX'

# Tighten the regression threshold so any task >10% slower/heavier is flagged
nextflow plugin nf-diff:diff --last --perf-threshold=10

# Also compare the files each task produced, and fail CI if any result changed
nextflow plugin nf-diff:diff --last --diff-outputs --fail-on-change

# Compare two runs' durable published result trees (works after the work dirs are gone)
nextflow plugin nf-diff:diff --last --published-a=runA/results --published-b=runB/results

# Inspect why a task's exit code changed by diffing its stdout/stderr
nextflow plugin nf-diff:diff --last --diff-logs

# See whether the process wiring changed (edges added/removed between runs)
nextflow plugin nf-diff:diff --last --diff-dag

# Enable all three work-dir layers at once (outputs + logs + dag)
nextflow plugin nf-diff:diff --last --diff-all --output=compare.html

# Inspect a project in another directory, with every field flagged
nextflow plugin nf-diff:diff runA runB --dir=/path/to/project --verbose

# Compare the same pipeline across two checkouts/projects (run per directory)
nextflow plugin nf-diff:diff runA runB --dir-a=/path/to/checkout-v1 --dir-b=/path/to/checkout-v2

# CI-friendly: emit JSON and fail the step if anything changed
nextflow plugin nf-diff:diff --last --format=json --fail-on-change

# Leanest CI gate: no report body, just the summary line, fail if anything changed
nextflow plugin nf-diff:diff --last --summary-only --fail-on-change

# Stream JSON to stdout and pipe it straight into jq
nextflow plugin nf-diff:diff --last --format=json --output=- | jq .summary
```

> When `--output=-` is used, only the report is written to stdout; the human-readable summary is redirected to stderr so the piped stream stays clean.

Finding run names or session ids is as easy as:

```bash
nextflow log
```

## Sample output

The CLI prints a short summary and writes the full report to disk:

```
nf-diff: comparison complete
  Run A : tender_euler (3a8c1f2e)  (42 tasks)
  Run B : happy_curie (9f2b7d10)  (42 tasks)
  Diff  : 3 changed, 0 only-in-B, 0 only-in-A, 39 unchanged
  Mode  : meaningful changes only (use --verbose for all fields)
  Report: /path/to/nf-diff-report.html (html)
```

The HTML report is fully self-contained (inline CSS/JS/SVG) with a light/dark theme toggle, so you can open it directly in a browser or email it to a colleague. Its layers are paginated behind a vertical sidebar (one page at a time, with Previous/Next controls), run statuses are shown in Nextflow / Seqera vocabulary (`SUCCEEDED` / `FAILED` rather than the terse `OK` / `ERR` history tokens), the performance-regressions page leads with a diverging-bar chart, and the process-wiring page adds per-run "before/after" views alongside the changed-edges diagram. Cards share a soft, uniform surface (hairline border, rounded corners, gentle elevation) and carry status/run identity through color cues rather than accent bars — the two runs are told apart by a colored name badge (green for Run A, blue for Run B). With `--format=json` the same comparison — the six core layers plus the derived performance-regressions layer, resource-efficiency layer, and recompute count — is written as structured JSON instead, convenient for diffing in scripts or asserting against in a pipeline.

See a rendered example in the [Gallery](../gallery/).

## Exit codes

| Code | Meaning                                                             |
|------|---------------------------------------------------------------------|
| `0`  | Success (runs may still differ, unless `--fail-on-change` is set)   |
| `1`  | Runtime error (e.g. run not found, ambiguous id, cache read failed) |
| `2`  | Usage error (bad arguments, unknown option)                         |
| `3`  | Runs differ **and** `--fail-on-change` was set                      |

## GitHub Action

This repository ships a composite action (`action.yml`) that wraps the `nf-diff:diff` verb for CI.

### How it works in CI

`nf-diff` does **not** execute your pipeline; it inspects the `.nextflow/history` file and `.nextflow/cache/` database generated by Nextflow runs.

To check for changes when opening a Pull Request, there are two main patterns:
1. **[Pattern A (Recommended): Cache-backed baseline](#pattern-a-cache-backed-baseline-recommended)** — Run the baseline pipeline once on `main` and cache its `.nextflow/` and output directories. On PRs, restore that cache, run the PR code once, and compare. Saves time, compute, and keeps Git repo size clean.
2. **[Pattern B: Dual run in the same job](#pattern-b-dual-run-in-the-same-job)** — Run the pipeline twice (e.g. baseline checkout vs. PR checkout, or two parameter sets) in the same PR runner and diff them.

---

### Pattern A: Cache-Backed Baseline (Recommended)

This pattern lets you run the test pipeline only **once** on each PR while still getting a full comparison against the latest `main` branch baseline.

#### How it works:
1. When code is pushed to `main`, an `update-baseline.yml` workflow runs your test dataset and saves `.nextflow` and `baseline-results` to the GitHub Actions cache under `key: nf-baseline-${{ github.sha }}`.
2. When a PR is opened, `pr-diff.yml` restores that `.nextflow` history using `restore-keys: nf-baseline-`.
3. Nextflow runs the PR code in the same workspace. This adds the PR run to `.nextflow/history` as the latest run.
4. `nf-diff` with `last: 'true'` compares the previous run (the restored baseline) against the latest run (the PR run), plus any differences between `baseline-results` and `pr-results`.

#### 1. Save baseline on `push` to `main` (`.github/workflows/update-baseline.yml`)

```yaml
name: Update Baseline Run

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  build-baseline:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Nextflow
        uses: nf-core/setup-nextflow@v2

      - name: Run baseline pipeline
        run: |
          nextflow run main.nf -profile test --outdir baseline-results

      - name: Cache baseline run and outputs
        uses: actions/cache/save@v4
        with:
          path: |
            .nextflow
            baseline-results
          key: nf-baseline-${{ github.sha }}
```

#### 2. Compare PR against cached baseline (`.github/workflows/pr-diff.yml`)

```yaml
name: PR nf-diff Check

on:
  pull_request:
    branches: [main]

permissions:
  contents: read
  pull-requests: write   # Required to post/update the PR comment

jobs:
  diff:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Nextflow
        uses: nf-core/setup-nextflow@v2

      # 1. Fetch latest baseline from main
      - name: Restore baseline from main
        uses: actions/cache/restore@v4
        id: restore-baseline
        with:
          path: |
            .nextflow
            baseline-results
          key: nf-baseline-${{ github.sha }}
          restore-keys: |
            nf-baseline-

      # 2. Run pipeline with PR code
      - name: Run pipeline on PR
        run: |
          nextflow run main.nf -profile test --outdir pr-results

      # 3. Compare PR run against baseline (only if baseline cache was restored)
      - name: Run nf-diff against baseline
        if: steps.restore-baseline.outputs.cache-matched-key != ''
        uses: mribeirodantas/nf-diff@main
        with:
          setup-java: 'false'       # Nextflow & Java already set up above
          setup-nextflow: 'false'
          last: 'true'              # Compares baseline (run 1 back) vs PR (latest run)
          published-a: baseline-results
          published-b: pr-results
          format: md
          comment-pr: 'true'        # Posts or updates a sticky comment on the PR
          upload-artifact: 'true'   # Uploads report as an artifact
```

---

### Pattern B: Dual Run in the Same Job

If you prefer not to manage a cache and your test runs are fast, you can check out both branches (or run the pipeline twice with different options) in a single job:

```yaml
name: PR nf-diff Check (Dual Run)

on:
  pull_request:
    branches: [main]

permissions:
  contents: read
  pull-requests: write

jobs:
  diff:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout PR branch
        uses: actions/checkout@v4
        with:
          path: pr-branch

      - name: Checkout baseline (main)
        uses: actions/checkout@v4
        with:
          ref: main
          path: baseline-branch

      - name: Set up Nextflow
        uses: nf-core/setup-nextflow@v2

      # Run baseline
      - name: Run baseline on main
        run: |
          cd baseline-branch
          nextflow run main.nf -profile test --outdir results_baseline

      # Run PR
      - name: Run PR version
        run: |
          cd pr-branch
          nextflow run main.nf -profile test --outdir results_pr

      # Compare cross-directory runs
      - name: Run nf-diff
        uses: mribeirodantas/nf-diff@main
        with:
          setup-java: 'false'
          setup-nextflow: 'false'
          dir-a: baseline-branch
          dir-b: pr-branch
          published-a: baseline-branch/results_baseline
          published-b: pr-branch/results_pr
          format: md
          comment-pr: 'true'
          fail-on-change: 'false'   # Set to 'true' if any change should fail CI
```

---

### Inputs

| Input | Default | Description |
|-------|---------|-------------|
| `run-a` / `run-b` | — | Run names or session-id prefixes. Leave empty when using `last`. |
| `last` | — | `true` compares the two most recent runs; any other value is passed as `--last=<value>` (e.g. `2`, `2:1`). |
| `format` | `md` | Report format: `md`, `html`, or `json`. |
| `output` | `nf-diff-report.<ext>` | Report output path. |
| `fail-on-change` | `false` | Fail the action (exit 3) if the runs are not identical. |
| `only` / `exclude` | — | Process-name globs to include / drop. |
| `perf-threshold` | — | Percent change beyond which a task metric is flagged (plugin default: 25). |
| `diff-outputs` / `diff-logs` / `diff-dag` / `diff-all` | `false` | Enable the work-dir layers (require work dirs to still exist). |
| `verbose` | `false` | Also diff always-changing fields. |
| `dir` | `.` | Project directory containing `.nextflow/`. |
| `dir-a` / `dir-b` | — | Per-run project directory for run A / run B (falls back to `dir`). |
| `published-a` / `published-b` | — | Run A / Run B published output directory (`outdir` / `publishDir`). |
| `extra-args` | — | Raw arguments appended to the diff command verbatim. |
| `nextflow-version` | — | Nextflow version to install (also exported as `NXF_VER`). |
| `java-version` | `17` | Temurin JDK to set up. |
| `setup-java` / `setup-nextflow` | `true` | Toggle the JDK / Nextflow install steps. |
| `comment-pr` | `false` | Post the report as a PR comment (best with `format: md`). |
| `github-token` | workflow token | Token used to post the PR comment. |
| `upload-artifact` | `true` | Upload the report as a workflow artifact. |
| `artifact-name` | `nf-diff-report` | Name for the uploaded artifact. |

### Outputs

| Output | Description |
|--------|-------------|
| `report-path` | Path to the generated primary report (relative to `dir`). |
| `html-report-path` | Path to the generated standalone HTML report dashboard (relative to `dir`). |
| `artifact-url` | Direct download URL for the uploaded report artifact. |
| `artifact-id` | ID of the uploaded report artifact. |
| `exit-code` | Exit code from the verb (`0` ok, `3` differ + fail-on-change). |
| `identical` | `true`/`false` — only populated when `format: json`. |
