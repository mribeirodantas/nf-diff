# nf-diff

> Compare two Nextflow runs and render a detailed, self-contained HTML report of what changed.

`nf-diff` is a [Nextflow plugin](https://www.nextflow.io/docs/latest/plugins.html) that adds a `diff` CLI verb. Point it at two runs from your local run history and it produces a single, standalone HTML report — no external assets, no network access — that walks through their differences across three layers: **run metadata**, **process topology**, and **per-task detail** (resources, scripts, containers, exit codes).

It's the tool you reach for when you ask *"my pipeline behaved differently this time — what actually changed?"*

---

## Why

Nextflow already records everything about a run in `.nextflow/history` and the per-session LevelDB cache under `.nextflow/cache/`. That data is rich, but it's not built for eyeballing two runs side by side. `nf-diff` reads that same data — reusing Nextflow's own internal cache and history APIs — and turns it into a readable comparison:

- **Did the topology change?** Which processes gained or lost tasks between the two runs.
- **Did a task change?** Per-task diffs of status, exit code, container, script, requested resources, and measured usage.
- **Was work reused?** Cached-task counts and total task realtime, so you can see whether a re-run actually recomputed anything.

By default the report highlights **meaningful** changes and treats fields that *always* differ between two distinct runs (run name, session id, launch time, work directory, wall-clock time, and measured resource usage) as context rather than "changes". Use `--verbose` when you want everything flagged.

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
| `<runA> <runB>` | Run names or session UUID prefixes from `.nextflow/history`         |

### Options

| Option                 | Description                                                                                      |
|------------------------|--------------------------------------------------------------------------------------------------|
| `--output=<file>`      | Output HTML report path (default: `nf-diff-report.html`)                                         |
| `--dir=<dir>`          | Project directory containing `.nextflow/` (default: `.`)                                         |
| `-v`, `--verbose`, `--all` | Also diff fields that always change between runs (run name, session id, launch time, work dir, wall/real time, resource usage) |
| `-h`, `--help`         | Show help                                                                                        |

> **Use the inline `--output=<file>` form (with `=`).** Nextflow's `plugin` launcher swallows space-separated flags such as `-o compare.html` before they reach the plugin.

### Examples

```bash
# Compare two runs by name; report goes to nf-diff-report.html
nextflow plugin nf-diff:diff tender_euler happy_curie

# Compare by session-id prefix and choose the output file
nextflow plugin nf-diff:diff 3a8c1f2e 9f2b7d10 --output=compare.html

# Inspect a project in another directory, with every field flagged
nextflow plugin nf-diff:diff runA runB --dir=/path/to/project --verbose
```

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
  Report: /path/to/nf-diff-report.html
```

The HTML report is fully self-contained (inline CSS/JS/SVG) with a light/dark theme toggle, so you can open it directly in a browser or email it to a colleague.

---

## How it works

`nf-diff` never re-executes anything. It reconstructs each run entirely from local state:

1. **Resolve the run** — `RunLoader` looks up the identifier (name or session-id prefix) in `.nextflow/history` via Nextflow's `HistoryFile`, capturing run-level metadata: run name, session id, status, revision, command, launch time, and duration.
2. **Hydrate tasks** — it opens the run's LevelDB cache (`.nextflow/cache/<sessionId>`) read-only through Nextflow's `CacheDB`, reading every `TraceRecord` into a `TaskInfo` (both human-formatted and raw values). Cache opens hold an exclusive lock, so reads retry with backoff when the lock is briefly contended (e.g. by `nextflow log` or an IDE indexing the cache).
3. **Compare across three layers** — `RunComparator` produces:
   - **Metadata** — run-level field diffs.
   - **Processes** — task counts per process, classified as added / removed / changed / unchanged.
   - **Tasks** — matched across runs by task name (falling back to process + tag), with per-field diffs. "Obvious" always-changing fields are shown for context but excluded from change detection unless `--verbose` is set.
4. **Render** — `HtmlReportRenderer` emits the standalone HTML document.

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
  RunComparator.groovy      # three-layer comparison logic
  DiffResult.groovy         # structured comparison outcome
  LineDiff.groovy           # line-level diff (e.g. task scripts)
  Format.groovy             # human-readable formatting helpers
  HtmlReportRenderer.groovy # self-contained HTML report
```

Tests live under `src/test/groovy/...` and use [Spock](https://spockframework.org/).

---

## License

See the repository for license details.

## Author

Marcel Ribeiro-Dantas — Seqera
