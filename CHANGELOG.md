# Changelog

All notable changes to `nf-diff` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Software & versions diffing** — a new always-on layer that compares, per
  process, the distinct container image(s) and Conda package spec(s) that
  process's tasks ran with in each run. Both values are read straight from the
  run cache's trace records, so the layer needs no work directories and is
  always computed. It answers "did a tool version change?" directly — e.g.
  `biocontainers/fastqc:0.11.9` → `biocontainers/fastqc:0.12.1` — instead of
  leaving it buried in the per-task container field. A process present in only
  one run is added/removed; a process in both whose container or Conda set
  differs is flagged changed. A changed software environment counts toward the
  "identical" verdict and `--fail-on-change`, which also makes a Conda-only
  change (previously invisible to the task layer) break identity. Surfaced in
  all three report formats (HTML section + summary card, Markdown section +
  summary column, and a `software` array with a `softwareChanged` summary count
  in JSON).
- **Output-file diffing** — a new opt-in `--diff-outputs` layer that compares
  the files each task matched in both runs wrote to its work directory,
  classifying them as added / removed / changed / unchanged. Files are compared
  by size first, then by a streamed SHA-256 for same-size files. Staged inputs
  (symlinks) and Nextflow control files (`.command.*`, `.exitcode`) are skipped;
  cache-resumed tasks that share a work directory short-circuit as identical.
  Unlike the performance-regressions layer, an output-file change counts toward
  the "identical" verdict and `--fail-on-change`, so this is the layer that
  answers "did my pipeline actually produce different results?".
- **`--outputs-max-bytes=<n>`** — caps the size of same-size files that are
  hashed under `--diff-outputs`; larger files are reported content-unverified.
  Default `0` means no limit.
- Output diffs are surfaced in all three report formats (HTML, JSON, Markdown),
  with an `outputsChanged` count in the JSON/HTML summary.
- **Failure / log diffing** — a new opt-in `--diff-logs` layer that compares the
  standard log files (`.command.out`, `.command.err`, `.command.log`) each
  matched task wrote to its work directory, line by line. It surfaces the
  exit-code and status change alongside the log contents, so a task that went
  from exit 0 to exit 1 can be inspected side by side — answering not *that* a
  task failed but *what it printed* before it did. Reads are bounded (tailed to
  a line cap and a hard byte cap) so an enormous log never blows up memory, and
  tasks sharing a work directory (cache-resumed) short-circuit as identical.
  Because task stdout/stderr legitimately varies between runs (timestamps,
  paths, ordering), this layer is **informational only**: it never affects the
  "identical" verdict or `--fail-on-change` — the exit-code change already
  captured by the per-task diff does that.
- **`--logs-max-lines=<n>`** — keeps only the last `<n>` lines of each log file
  before diffing under `--diff-logs` (default `200`).
- Log diffs are surfaced in all three report formats (HTML, JSON, Markdown),
  with a `logsChanged` count in the JSON/HTML summary.

### Fixed

- **`--last` and bare boolean flags dropped by the plugin launcher** — Nextflow's
  `plugin` launcher rewrites forwarded arguments before they reach the verb: a
  bare `--flag` arrives as `--flag true`, and `--opt=value` arrives
  space-separated as `--opt value`. The `diff` parser assumed the inline `=`
  form survived, so `nextflow plugin nf-diff:diff --last` reached it as
  `['--last', 'true']` and the injected `true` leaked into the positional list,
  tripping the "--last cannot be combined with explicit run identifiers" guard.
  The same latent bug affected `--last=N` and every bare boolean flag
  (`--fail-on-change`, `--verbose`, `--diff-outputs`, `--diff-logs`). The parser
  now tolerates the launcher-normalized forms, consuming an injected/inline
  `true`/`false` (or an integer for `--last N`) instead of treating it as a
  positional run identifier.

## [0.1.0] - 2026-09-08

First release. `nf-diff` is a Nextflow plugin that adds a `diff` CLI verb to
compare two runs from the local `.nextflow/history` and cache, and render a
readable report of what changed. It never re-executes anything — each run is
reconstructed entirely from local state via Nextflow's own history and cache
APIs.

### Added

- **`diff` command** — `nextflow plugin nf-diff:diff <runA> <runB>`, resolving
  runs by name or session-id prefix from `.nextflow/history`.
- **Five comparison layers:**
  - **Run metadata** — run-level field diffs (status, revision, command, …).
  - **Parameters & options** — the resolved launch flags, splitting Nextflow
    options (`-profile`, `-r`) from pipeline params (`--genome`, `--input`)
    and diffing them flag by flag. `-params-file` (JSON/YAML) contents are
    parsed, flattened to dotted keys, and merged under the command line with
    Nextflow's precedence; each value is tagged by source (`CLI`, `file`,
    `CLI+file`).
  - **Resolved configuration** — the effective `nextflow.config` rebuilt with
    Nextflow's `ConfigBuilder` (applying each run's `-profile`/`-c`), flattened
    to dotted keys (`process.cpus`, `docker.enabled`) and diffed.
  - **Process topology** — task counts per process, classified as
    added / removed / changed / unchanged.
  - **Per-task detail** — tasks matched across runs (by name, falling back to
    process + tag) with per-field diffs of status, exit code, container,
    script, requested resources, and measured usage.
- **Performance-regressions layer** — derived from matched tasks' numeric trace
  metrics (`realtime`, `peak_rss`); flags each metric that moved by at least
  `--perf-threshold` percent (default `25`), sorted worst-regression first, and
  notes whether both tasks shared a cache hash ("same work"). Informational
  only — never affects the "identical" verdict or `--fail-on-change`.
- **Recompute count** — matched tasks whose cache hash differs, i.e. work
  re-executed rather than resumed.
- **Meaningful-change filtering** — fields that always differ between two runs
  (run name, session id, timestamps, work dir, wall/real time, resource usage,
  and noisy options like `-name`, `-resume`, `-with-tower`) are shown for
  context but excluded from change detection unless `--verbose` is set.
- **Output formats** — self-contained HTML report (inline CSS/JS/SVG, light/dark
  theme, no network assets) by default; `--format=json` for machine-readable
  output and `--format=md` for a Markdown report suited to PR comments.
- **CLI options:**
  - `-l`, `--last[=N]` — compare recent runs from history (bare `--last`
    compares the two most recent; `--last=N` compares the run N-before-latest
    against the latest).
  - `--only=<globs>` / `--exclude=<globs>` — restrict the comparison by
    process-name globs (`*` spans `:` scopes).
  - `--perf-threshold=<pct>` — threshold for the performance-regressions layer.
  - `--output=<file>` — report path; `-` streams the report to stdout (the
    human summary is redirected to stderr to keep the stream clean).
  - `--dir=<dir>` — project directory containing `.nextflow/`.
  - `--fail-on-change` — exit with code `3` when the runs are not identical
    (for CI).
  - `-v`, `--verbose`, `--all` — flag always-changing fields too.
- **Exit codes** — `0` success, `1` runtime error, `2` usage error, `3` runs
  differ with `--fail-on-change`.

### Requirements

- Nextflow `>= 25.04.0`
- Java 17+

[0.1.0]: https://github.com/mribeirodantas/nf-diff/releases/tag/v0.1.0
