# Changelog

All notable changes to `nf-diff` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Cross-project comparison (`--dir-a` / `--dir-b`)** — the two runs no longer
  have to live in the same project. Previously a single `--dir` resolved both
  runs' `.nextflow/` history, cache, config and params, so you could not compare
  "the same pipeline in two checkouts" (or on two machines). `--dir-a=<dir>` and
  `--dir-b=<dir>` now set each run's project directory independently; each falls
  back to `--dir` when omitted, so existing invocations are unchanged. Run A is
  loaded from and resolved against `dir-a`, run B against `dir-b`: the parameters
  layer reads each run's own `-params-file`, and the configuration layer rebuilds
  each run's effective `nextflow.config` from its own working tree, so a
  `-profile docker` in project A is diffed against project B's config. The
  git-provenance caveat became per-tree: `ConfigProvenance` now carries a
  `crossProject` flag plus each side's directory, current HEAD and dirty state,
  and its warning describes the two working trees separately (`currentRevisionB`,
  `dirtyB`, `dirA`, `dirB` are surfaced in the JSON report). `--last` still needs
  a single history, so it is rejected when combined with differing
  `--dir-a`/`--dir-b`.

### Fixed

- **`GitProvenance` subprocess timeout was ineffective** — the git subprocess's
  stdout/stderr were read inline with `getText()` *before* the timed `waitFor`,
  which blocks until the process exits, so a hung `git` could never be timed out.
  Both streams are now drained on background threads started before `waitFor`, so
  the 5s timeout actually fires and a chatty command cannot deadlock on a full
  pipe buffer.

## [0.2.0] - 2026-09-08

### Added

- **Line-level output diffing** — under `--diff-outputs`, a file classified as
  *changed* that is text on both sides is now additionally diffed line by line
  (reusing the same `LineDiff` engine as `--diff-logs`), so the report answers
  *what* changed rather than merely *that* it changed — a VCF, CSV, JSON, or
  report file shows its added/removed lines inline. `OutputComparator` sniffs
  the head of each changed file for a NUL byte; binary files fall back to the
  existing size/hash verdict and produce no line diff. Reads are bounded: the
  first `--outputs-max-lines` lines (new option, default `1000`) and a hard byte
  cap, so a huge file never blows up memory, with a `truncated` marker when a
  cap dropped content. Surfaced in all three report formats (HTML unified-diff
  pane with an added/removed line count, Markdown fenced `diff` block, and
  `diff`/`linesAdded`/`linesRemoved`/`truncated` fields on each output file in
  JSON). This enriches the existing layer only — an output change still counts
  toward the "identical" verdict and `--fail-on-change` exactly as before.
- **Config-provenance caveat** — the configuration layer now inspects the git
  state of the working tree it resolves config from and warns when that tree has
  drifted from the revision a run was actually launched at. Because
  `ConfigLoader` rebuilds each run's effective config from the files *as they
  exist now*, a run launched at commit A and re-run at commit B have their
  configs both resolved against whatever is checked out now — silently masking
  config differences driven by code changes between those revisions. The
  metadata layer already records each run's `revisionId`; this compares it to
  the current `HEAD` (via `git rev-parse`, prefix-matching abbreviated ids) and
  also flags an uncommitted (dirty) working tree. When either run drifted, or
  the tree is dirty, a prominent caveat is surfaced in all three report formats
  (HTML warning banner, Markdown blockquote, and a `configProvenance` object
  with `driftedA`/`driftedB`/`workingTreeDirty`/`warning` fields in JSON). Git
  state is inspected best-effort — a non-git project, missing `git`, or a
  command timeout degrades to "unknown" without breaking the diff. The caveat is
  informational only: it never affects the "identical" verdict or
  `--fail-on-change`.
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
