# nf-diff

[![CI](https://github.com/mribeirodantas/nf-diff/actions/workflows/ci.yml/badge.svg)](https://github.com/mribeirodantas/nf-diff/actions/workflows/ci.yml)

> Compare two Nextflow runs and render a detailed, self-contained HTML report of what changed.

`nf-diff` is a [Nextflow plugin](https://www.nextflow.io/docs/latest/plugins.html) that adds a `diff` CLI verb. Point it at two runs from your local run history — or just say `--last` to grab the two most recent — and it produces a report that walks through their differences across six layers: **run metadata**, **parameters & options** (the resolved flags each run was launched with, merging `-params-file` contents with the command line), **resolved configuration** (the effective `nextflow.config` after profiles, with a caveat when the working tree has drifted from the git revision a run was launched at), **process topology**, **software & versions** (the container image and Conda spec each process ran with), and **per-task detail** (resources, scripts, containers, exit codes). On top of those, it derives a **performance-regressions** view — the tasks whose runtime or memory moved beyond a threshold between the two runs — a **resource-efficiency** view — how much of each process's requested CPU/memory it actually used at peak, flagging over- and under-provisioning — and a **recompute count** telling you how many matched tasks were re-executed rather than resumed.

Opt into a sixth layer with `--diff-outputs`: for every task matched in both runs, it compares the **output files** each one wrote to its work directory — by size first, then a content SHA-256 for same-size files — so you can see not just whether the runs were *launched* differently, but whether they actually *produced* different results. When a changed file is text (VCF, CSV, JSON, reports, …), it goes one step further and diffs it **line by line**, so you see *what* changed, not just *that* it changed; binary files fall back to the size/hash verdict.

And opt into a seventh with `--diff-logs`: for every matched task, it diffs the **standard log files** (`.command.out`, `.command.err`, `.command.log`) line by line and surfaces the exit-code/status change alongside them — so when a task goes from exit 0 to exit 1, you can read *what it printed* right before it failed. This layer is informational only; it never affects the "identical" verdict or `--fail-on-change`.

An eighth layer, `--diff-dag`, reconstructs each run's **process wiring** and shows which producer→consumer edges were added or removed — so you catch a pipeline rewired from `A → C` to `A → B → C`, which the process-topology counts alone cannot see. When the run's project directory has a Nextflow **data-lineage store** (`.lineage/`, produced with `lineage.enabled = true` on Nextflow 25.04+), the wiring is read straight from the authoritative provenance Nextflow recorded — so it needs no work directories and is not best-effort. Otherwise it falls back to inferring edges from the input symlinks each task staged into its work directory, which needs the work directories to still exist locally and is a best-effort reconstruction (a note reports which source was used and how many work dirs were missing). Like the logs layer, it is informational only.

The default report is a single, standalone HTML document — no external assets, no network access — that you can open in a browser or email to a colleague. For scripting and CI, `--format=json` emits the same comparison as machine-readable JSON, `--format=md` produces Markdown you can drop straight into a pull-request comment, and `--fail-on-change` turns a difference into a non-zero exit code.

It's the tool you reach for when you ask *"my pipeline behaved differently this time — what actually changed?"*

---

## Why

Nextflow already records everything about a run in `.nextflow/history` and the per-session LevelDB cache under `.nextflow/cache/`. That data is rich, but it's not built for eyeballing two runs side by side. `nf-diff` reads that same data — reusing Nextflow's own internal cache and history APIs — and turns it into a readable comparison:

- **Were the runs launched differently?** A structured, flag-by-flag diff of each run's launch command — Nextflow options (`-profile`, `-r`) and pipeline params (`--genome`, `--input`) side by side — instead of eyeballing two opaque command strings. Params passed via `-params-file` (JSON/YAML) are parsed and merged in too, tagged by source (`CLI`, `file`, or `CLI+file`) so you can see where each value came from.
- **Did the configuration change?** A diff of the *resolved* `nextflow.config` — flattened to dotted keys like `process.cpus`, `executor.name`, `docker.enabled` — with each run's `-profile`/`-c` options applied. This is what catches the classic case where two identical commands still behave differently because `-profile docker` and `-profile test` resolve to different process resources, executors, or container settings.
- **Did the topology change?** Which processes gained or lost tasks between the two runs.
- **Did the wiring change?** With `--diff-dag`, the **process→process edges** each run actually ran are reconstructed and diffed, so a rewired pipeline (`A → C` becoming `A → B → C`) shows up as added/removed edges — something the per-process task counts cannot reveal. When a Nextflow **data-lineage store** (`.lineage/`, `lineage.enabled = true`) recorded the run, edges are read from that authoritative provenance — the input LID references each task recorded — so no work directories are needed. Without a lineage store, edges are inferred from the input symlinks each task staged into its work directory (a link resolving into another task's work dir is a producer→consumer edge); that fallback needs the work directories to still exist locally and is best-effort (a note reports which source was used and how many task work dirs were missing). Either way this layer is informational only — it never affects the "identical" verdict or `--fail-on-change`.
- **Did the tools change?** A per-process **software & versions** diff of the container image(s) and Conda package spec(s) each process ran with — read straight from the run cache, so no work directories are needed. This is the layer that catches `biocontainers/fastqc:0.11.9` → `biocontainers/fastqc:0.12.1` (or a bumped `bioconda::salmon=` pin) directly, rather than leaving it buried in per-task detail. A software change counts toward the "identical" verdict and `--fail-on-change`.
- **Did a task change?** Per-task diffs of status, exit code, container, script, requested resources, and measured usage.
- **What failed, and why?** A top-level **failure rollup** that gathers every failed task — detected from the cached `status`/`exit` fields (an explicit `FAILED`/`ABORTED` status or a non-zero exit code), so no work directories are needed — and rolls them up by `(process, status, exit)` signature, counted per run and sorted by biggest blast radius first. A signature seen only in Run B is flagged **new** (a regression), one in Run A but gone in Run B is **resolved**, and one in both is **persistent**, so you can tell at a glance whether a re-run introduced, fixed, or carried over a failure — e.g. a `CALL` process that started exiting `137` (OOM-killed) only in Run B. A run-level error state is surfaced too, even when no individual task failure was recorded. This is a summary of the per-task status/exit already shown in the task layer, so it is informational only and never separately affects the "identical" verdict or `--fail-on-change`.
- **Did anything get slower or heavier?** A dedicated performance-regressions layer flags matched tasks whose runtime (`realtime`) or peak memory (`peak_rss`, `peak_vmem`) changed by at least `--perf-threshold` percent (default `25`), sorted worst-regression first. Tasks that share the same cache hash are marked "same work", so a `+200%` realtime on identical work stands out from a slowdown that also changed what ran. Improvements (Run B faster/leaner) are shown too, but only true regressions are counted.
- **Was anything over- or under-provisioned?** A **resource-efficiency** layer that, for each process in each run, compares what it *requested* (`cpus`, `memory`) against what it actually *peaked* at (`%cpu`, `peak_rss`) — both read straight from the run cache, so no work directories are needed and it is always computed. Each process is classified per run: **over** (used under 50% of the reservation — e.g. "requested 32 GB, peaked at 4 GB" — so the allocation, and often the cost, was wasted), **tight** (used 90%+ of it, a risk of OOM kills or CPU throttling), or **ok** in between. Because provisioning is a tuning signal rather than a correctness change, this layer is informational only — it never affects the "identical" verdict or `--fail-on-change`.
- **Was work reused?** Cached-task counts and total task realtime, plus a **recompute count** — matched tasks (present in both runs) whose cache hash differs, i.e. work that was re-executed rather than resumed — so you can see whether a re-run actually recomputed anything, and why.
- **Did the results change?** With `--diff-outputs`, the files each matched task wrote to its work directory are compared — by size first, then a streamed SHA-256 for same-size files — and classified as added / removed / changed / unchanged. For a *changed* file that is text on both sides, it also produces a line-level diff (reusing the same `LineDiff` engine as `--diff-logs`), bounded to the first `--outputs-max-lines` lines (default `1000`) and a hard byte cap; binary files (a NUL byte in the head) show only the size/hash change. Tasks that resumed from cache share a work directory, so they short-circuit to "identical"; the interesting cases are recomputed tasks whose outputs actually differ. This layer needs the work directories to still exist locally, and (unlike the always-changing performance metrics) an output change *does* count toward the "identical" verdict and `--fail-on-change`.
- **Why did a task fail?** With `--diff-logs`, the standard log files (`.command.out`, `.command.err`, `.command.log`) each matched task wrote are compared line by line, with the task's exit code and status surfaced alongside — so a task that flipped from exit 0 to exit 1 shows both the change *and* the stderr that explains it. Reads are bounded (tailed to `--logs-max-lines`, default `200`, and a hard byte cap) so a huge log never blows up memory, and cache-resumed tasks sharing a work directory short-circuit as identical. Because stdout/stderr legitimately varies between runs (timestamps, paths, ordering), this layer is *informational only* — it never affects the "identical" verdict or `--fail-on-change` (the exit-code change already does).

By default the report highlights **meaningful** changes and treats fields that *always* differ between two distinct runs (run name, session id, launch time, work directory, wall-clock time, and measured resource usage) as context rather than "changes". The same rule applies to the parameters layer: launch options that routinely differ without changing what was executed (`-name`, `-resume`, `-ansi-log`, `-with-tower`, `-with-weblog`, `-bg`) are shown for context but not flagged as changes. Use `--verbose` when you want everything flagged.

---

## Requirements

- **Nextflow** `>= 25.04.0`
- **Java** 17+ (as required by your Nextflow version)
- Two runs present in the local `.nextflow/history` of the project you're inspecting

---

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

---

## Usage

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
| `--diff-logs`          | Compare the standard log files (`.command.out`/`.command.err`/`.command.log`) each matched task wrote to its work directory, line by line. Ideal for inspecting why a task's exit code changed. Requires the tasks' work directories to still exist locally. Informational only: never affects `--fail-on-change`. |
| `--logs-max-lines=<n>` | With `--diff-logs`, keep only the last `<n>` lines of each log file before diffing. Default `200`. |
| `--diff-dag`           | Reconstruct each run's process→process wiring and diff the two edge sets, surfacing added/removed edges (a rewired pipeline). Reads the authoritative `.lineage/` data-lineage store when present (`lineage.enabled = true`, no work dirs needed); otherwise infers edges from the input symlinks staged into task work directories, which must still exist locally (best-effort when some were cleaned up). Informational only: never affects `--fail-on-change`. |
| `--fail-on-change`     | Exit with code `3` if the runs are not identical (useful in CI)                                  |
| `--dir=<dir>`          | Project directory containing `.nextflow/` (default: `.`). Used for both runs unless overridden per-run below. |
| `--dir-a=<dir>` / `--dir-b=<dir>` | Per-run project directory for run A / run B (its `.nextflow/` history, cache, config and params). Use these to compare a run from one project or checkout against a run from another ("same pipeline, two directories"). Each falls back to `--dir` when omitted. Cannot be combined with `--last`, which needs a single history. |
| `-v`, `--verbose`, `--all` | Also diff fields that always change between runs (run name, session id, launch time, work dir, wall/real time, resource usage) |
| `-h`, `--help`         | Show help                                                                                        |

> **Use the inline `--output=<file>` form (with `=`).** Nextflow's `plugin` launcher swallows space-separated flags such as `-o compare.html` before they reach the plugin.

### Examples

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

# Inspect why a task's exit code changed by diffing its stdout/stderr
nextflow plugin nf-diff:diff --last --diff-logs

# See whether the process wiring changed (edges added/removed between runs)
nextflow plugin nf-diff:diff --last --diff-dag

# Inspect a project in another directory, with every field flagged
nextflow plugin nf-diff:diff runA runB --dir=/path/to/project --verbose

# Compare the same pipeline across two checkouts/projects (run per directory)
nextflow plugin nf-diff:diff runA runB --dir-a=/path/to/checkout-v1 --dir-b=/path/to/checkout-v2

# CI-friendly: emit JSON and fail the step if anything changed
nextflow plugin nf-diff:diff --last --format=json --fail-on-change

# Stream JSON to stdout and pipe it straight into jq
nextflow plugin nf-diff:diff --last --format=json --output=- | jq .summary
```

> When `--output=-` is used, only the report is written to stdout; the human-readable summary is redirected to stderr so the piped stream stays clean.

Finding run names or session ids is as easy as:

```bash
nextflow log
```

### Sample output

The CLI prints a short summary and writes the full report to disk:

```
nf-diff: comparison complete
  Run A : tender_euler (3a8c1f2e)  (42 tasks)
  Run B : happy_curie (9f2b7d10)  (42 tasks)
  Diff  : 3 changed, 0 only-in-B, 0 only-in-A, 39 unchanged
  Mode  : meaningful changes only (use --verbose for all fields)
  Report: /path/to/nf-diff-report.html (html)
```

The HTML report is fully self-contained (inline CSS/JS/SVG) with a light/dark theme toggle, so you can open it directly in a browser or email it to a colleague. With `--format=json` the same six-layer comparison — plus the derived performance-regressions layer, resource-efficiency layer, and recompute count — is written as structured JSON instead, convenient for diffing in scripts or asserting against in a pipeline.

### Exit codes

| Code | Meaning                                                             |
|------|---------------------------------------------------------------------|
| `0`  | Success (runs may still differ, unless `--fail-on-change` is set)   |
| `1`  | Runtime error (e.g. run not found, ambiguous id, cache read failed) |
| `2`  | Usage error (bad arguments, unknown option)                         |
| `3`  | Runs differ **and** `--fail-on-change` was set                      |

---

## How it works

`nf-diff` never re-executes anything. It reconstructs each run entirely from local state:

1. **Resolve the run** — `RunLoader` looks up the identifier (name or session-id prefix) in `.nextflow/history` via Nextflow's `HistoryFile`, capturing run-level metadata: run name, session id, status, revision, command, launch time, and duration.
2. **Hydrate tasks** — it opens the run's LevelDB cache (`.nextflow/cache/<sessionId>`) read-only through Nextflow's `CacheDB`, reading every `TraceRecord` into a `TaskInfo` (both human-formatted and raw values). Cache opens hold an exclusive lock, so reads retry with backoff when the lock is briefly contended (e.g. by `nextflow log` or an IDE indexing the cache).
3. **Compare across six layers** — `RunComparator` produces:
   - **Metadata** — run-level field diffs. The raw launch command is kept here as context, since the structured Parameters layer is now the authoritative view of what changed on the command line.
   - **Parameters** — each run's launch command is parsed by `CommandParams` into Nextflow options (single-dash, e.g. `-profile`) and pipeline params (double-dash, e.g. `--genome`), then diffed flag by flag. When the command referenced a `-params-file`, that JSON/YAML is read (relative paths resolved against `--dir`), flattened into dotted keys (`--genome.build`), and merged *underneath* the command-line flags — Nextflow's precedence, so an explicit `--flag` overrides the file. Each value is tagged with its source (`CLI`, `file`, `CLI+file`). A flag present in only one run shows a missing value on the other side; noisy options (`-name`, `-resume`, …) are flagged "obvious" and excluded from change detection unless `--verbose` is set.
   - **Configuration** — `ConfigLoader` rebuilds each run's effective config with Nextflow's own `ConfigBuilder` (the same machinery behind `nextflow config`), applying the run's recorded `-profile`/`-c` options, then flattens it to dotted keys and diffs the two. The `params` scope is excluded here — it belongs to the Parameters layer. Because Nextflow does not persist the fully merged config in its history/cache, this is resolved from the project's config files *as they exist now*; it is authoritative for `-profile`/`-c`-driven differences rather than a byte-for-byte snapshot at launch time, and the report says so.
   - **Processes** — task counts per process, classified as added / removed / changed / unchanged.
   - **Software & versions** — for each process, the distinct container image(s) and Conda package spec(s) its tasks ran with are collected from the cached trace records (`container`, `conda`) and diffed. A process present in only one run is added / removed; a process in both whose container or Conda set differs is changed. Because it reads only the cache, it needs no work directories and is always computed. A changed software environment *does* count toward "identical" and `--fail-on-change` — which also means a Conda-only change (not part of the per-task field set) now breaks identity.
   - **Tasks** — matched across runs by task name (falling back to process + tag), with per-field diffs. "Obvious" always-changing fields are shown for context but excluded from change detection unless `--verbose` is set. Matched tasks whose cache hash differs are tallied as the **recompute count** — work that was re-executed rather than resumed.
   - **Performance regressions** — derived from the matched tasks' numeric trace metrics (`realtime`, `peak_rss`, `peak_vmem`). For each metric that moved by at least `--perf-threshold` percent, a signed delta is recorded and the list is sorted worst-regression first; each entry notes whether both tasks shared a cache hash ("same work"). This layer is *always* computed from fields that change between runs, so it is informational only — it never affects the "identical" verdict or `--fail-on-change`.
   - **Outputs** (only with `--diff-outputs`) — `OutputComparator` walks each matched task's work directory, skipping staged inputs (symlinks) and Nextflow control files (`.command.*`, `.exitcode`), and compares the remaining real files by path. Files are matched by size first; same-size files are then compared by a streamed SHA-256 (capped by `--outputs-max-bytes`, which leaves oversized same-size files content-unverified). For a *changed* file that is text on both sides (no NUL byte in its head), it then computes a line-level diff via the shared `LineDiff` engine, bounded to the first `--outputs-max-lines` lines and a hard byte cap. Tasks that resolved to the same physical work directory (a cache resume) short-circuit as identical. Unlike performance regressions, an output-file change *does* count toward "identical" and `--fail-on-change`.
   - **Logs** (only with `--diff-logs`) — `LogComparator` reads the standard log files (`.command.out`, `.command.err`, `.command.log`) from each matched task's work directory and produces a line-level diff of each via `LineDiff`, alongside the task's exit code and status. Reads are bounded (only the tail up to `--logs-max-lines` and a hard byte cap are loaded) so large logs never exhaust memory, and tasks sharing a physical work directory short-circuit as identical. Like performance regressions, this layer is informational only — task stdout/stderr legitimately varies between runs, so it never affects "identical" or `--fail-on-change`.
   - **DAG / wiring** (only with `--diff-dag`) — `DagComparator` reconstructs each run's process→process graph and diffs the two edge sets, preferring the authoritative Nextflow data-lineage store and falling back to work-dir symlinks. When the run's project directory has a `.lineage/` store (from `lineage.enabled = true`), `LineageStore` reads it directly off disk: it indexes the run's `TaskRun` records (matched by session id) by their LID hash, then reconstructs a producer→consumer edge for every `input` LID reference (`lid://<producerTaskHash>/…`) a consumer task recorded. This is exactly the provenance Nextflow persisted, so it needs no work directories and is not best-effort. Without a lineage store, it falls back to `symlinkGraphOf`: it walks each task's staged input symlinks and, for any that resolve into another task's work directory (walking the parent chain so a link into a nested output subdir still attributes to its producer), records an edge; links resolving outside every work dir are external inputs and yield no edge — reporting how many task work dirs were missing so a partial reconstruction isn't read as authoritative. Each run's graph records its `source` (`LINEAGE`/`SYMLINK`/`NONE`) and the layer's note says which was used. Because the fallback is best-effort and the layer is a summary regardless, it is informational only — it never affects "identical" or `--fail-on-change`.
4. **Render** — `HtmlReportRenderer` emits the standalone HTML document, `JsonReportRenderer` the structured JSON (`--format=json`), or `MarkdownReportRenderer` the Markdown report (`--format=md`).

`--only` / `--exclude` narrow the comparison via a `ProcessFilter` before rendering, so process- and task-level output is restricted to the processes you care about.

---

## Development

The project builds with Gradle via the `io.nextflow.nextflow-plugin` build plugin. Common tasks are wrapped in the `Makefile`:

```bash
make compile    # compile Groovy sources
make assemble   # build the plugin distribution
make test       # run the Spock test suite
make check      # full verification (compile + tests + checks)
make dev-repo   # build a local plugin repo for bare-id resolution
make release    # publish a release
make clean      # clean build outputs
```

### Layout

```
src/main/groovy/io/seqera/nf/diff/
  DiffPlugin.groovy         # plugin entry point; registers the `diff` verb
  DiffCommand.groovy        # argument parsing + orchestration
  RunLoader.groovy          # reads history + cache into a RunSnapshot
  RunSnapshot.groovy        # run-level metadata + tasks
  TaskInfo.groovy           # per-task record (formatted + raw values)
  RunComparator.groovy      # multi-layer comparison logic
  CommandParams.groovy      # resolves launch params (CLI + -params-file), tagged by source
  ConfigLoader.groovy       # resolves the effective nextflow.config (profiles + -c)
  GitProvenance.groovy      # inspects working-tree git HEAD + dirty state for the config caveat
  OutputComparator.groovy   # --diff-outputs: compares task work-dir output files
  LogComparator.groovy      # --diff-logs: diffs task .command.out/.err/.log files
  DagComparator.groovy      # --diff-dag: reconstructs process wiring (lineage store, else work-dir symlinks)
  LineageStore.groovy       # --diff-dag: reads the .lineage/ store for authoritative process wiring
  ProcessFilter.groovy      # --only / --exclude process-name globbing
  DiffResult.groovy         # structured comparison outcome
  LineDiff.groovy           # line-level diff (e.g. task scripts)
  Format.groovy             # human-readable formatting helpers
  HtmlReportRenderer.groovy # self-contained HTML report
  JsonReportRenderer.groovy # machine-readable JSON report
  MarkdownReportRenderer.groovy # Markdown report for PR comments
```

Tests live under `src/test/groovy/...` and use [Spock](https://spockframework.org/).

### Continuous integration

Every push and pull request to `main` runs the full verification suite
(`make check`) on GitHub Actions across JDK 17 and 21
(`.github/workflows/ci.yml`). Test reports are uploaded as build artifacts so a
red build is debuggable without re-running locally.

### Releasing

Releases are published to the [Nextflow plugin registry](https://registry.nextflow.io/)
by pushing a version tag — `.github/workflows/release.yml` does the rest:

```bash
# 1. Bump `version` in build.gradle and update the CHANGELOG, then commit.
# 2. Tag the release (the tag must match build.gradle's version, prefixed 'v').
git tag v0.2.0
git push origin v0.2.0
```

On a `v*` tag the release workflow verifies the tag matches `build.gradle`'s
`version` (so a tag can never publish a mismatched artifact), runs `make check`,
then `make release`. Publishing authenticates against the registry with an API
token read from the `NPR_API_KEY` environment variable — provided in CI by a
repository secret of the same name.

> **One-time setup:** generate an access token in the Nextflow plugin registry
> (**Access tokens** page) and add it under the repository's
> **Settings → Secrets and variables → Actions** as `NPR_API_KEY`. To publish
> from a local machine instead, `export NPR_API_KEY=<token>` before `make release`.

---

## License

Licensed under the [Apache License, Version 2.0](LICENSE).

## Author

Marcel Ribeiro-Dantas — Seqera
