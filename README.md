# nf-diff

[![CI](https://github.com/mribeirodantas/nf-diff/actions/workflows/ci.yml/badge.svg)](https://github.com/mribeirodantas/nf-diff/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/mribeirodantas/nf-diff?sort=semver&label=release)](https://github.com/mribeirodantas/nf-diff/releases/latest)
[![License](https://img.shields.io/github/license/mribeirodantas/nf-diff)](LICENSE)

> **My pipeline behaved differently this time — what actually changed?**

`nf-diff` is a [Nextflow plugin](https://www.nextflow.io/docs/latest/plugins.html) that adds a `diff` CLI verb. Point it at two runs from your local run history — or just say `--last` — and it reconstructs both entirely from Nextflow's own history and cache (no re-execution) and renders a self-contained HTML report of everything that changed between them.

```bash
nextflow plugin nf-diff:diff --last
```

## What it compares

| Layer | Answers |
|-------|---------|
| **Metadata** | Were the runs launched differently? |
| **Parameters** | Which flags & `-params-file` values differ (tagged by source)? |
| **Configuration** | Did the resolved `nextflow.config` change (profiles applied)? |
| **Processes** | Which processes gained or lost tasks? |
| **Software & versions** | Did a container image or Conda pin change? |
| **Tasks** | Per-task status, exit code, script, resources, usage. |
| **Failures** | What failed, and is it new / resolved / persistent? |
| **Performance** | Did any task get slower or heavier (`--perf-threshold`)? |
| **Efficiency** | Was any process over- or under-provisioned? |
| **Outputs** *(`--diff-outputs`)* | Did the files each task produced actually change (line-level)? |
| **Published outputs** *(`--published-a` / `--published-b`)* | Did the durable published result trees (`outdir` / `publishDir`) change, even after the work dirs are gone? |
| **Logs** *(`--diff-logs`)* | Why did a task's exit code change (stdout/stderr diff)? |
| **DAG / wiring** *(`--diff-dag`)* | Was the pipeline rewired (edges added/removed)? |

By default the report highlights **meaningful** changes and treats always-differing fields (run name, session id, launch time, cache hash, wall time, resource usage) as context. Use `--verbose` to flag everything. See **[docs/layers.md](docs/layers.md)** for what each layer detects.

## Output

A single, standalone HTML document — inline CSS/JS/SVG, no network access, light/dark theme — that you can open in a browser or email to a colleague. It's paginated behind a vertical sidebar, and the performance page leads with a diverging-bar chart of the biggest movers. For CI and scripting, `--format=json` and `--format=md` emit the same comparison, and `--fail-on-change` turns any difference into a non-zero exit code.

## Requirements

- **Nextflow** `>= 25.04.0`, **Java** 17+
- Two runs present in the local `.nextflow/history` of the project you're inspecting

## Quick start

```bash
# Compare the two most recent runs
nextflow plugin nf-diff:diff --last

# Compare two runs by name (or session-id prefix)
nextflow plugin nf-diff:diff tender_euler happy_curie

# Emit Markdown for a PR comment
nextflow plugin nf-diff:diff --last --format=md --output=diff.md

# CI gate: fail if anything meaningful changed
nextflow plugin nf-diff:diff --last --fail-on-change
```

> **Local / unpublished builds:** run `make dev-repo` and
> `export NXF_PLUGINS_TEST_REPOSITORY="file://$PWD/build/plugin-repo/plugins.json"`
> first, then invoke the **bare id** `nf-diff:diff` (not a pinned version). See
> [docs/usage.md](docs/usage.md#installation) for details.

## Documentation

Full reference lives in **[`docs/`](docs/README.md)**:

- **[Usage](docs/usage.md)** — installation, every option, examples, sample output, exit codes.
- **[Comparison layers](docs/layers.md)** — what each layer detects and the meaningful-vs-everything verdict.
- **[How it works](docs/how-it-works.md)** — how a run is reconstructed and compared internally.
- **[Development](docs/development.md)** — building, layout, CI, and releasing.
