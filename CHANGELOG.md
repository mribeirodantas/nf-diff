# Changelog

All notable changes to `nf-diff` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Direct unit tests for the `ArgCursor` parsing primitive.** A new
  `ArgCursorTest` pins the cursor's contract in isolation from `parse()`: inline
  `--key=value` splitting, the token-consumption semantics that the old manual
  `if( inlineVal == null ) i++` bookkeeping encoded (`requireValue`/`boolValue`
  consume the space-separated value; a bare or non-boolean-followed flag does
  not), the shared numeric parse/floor helpers (`intValue`/`longValue`/
  `doubleValue`), and `peek`/`consumePeeked` iteration. `ArgCursor` was widened
  from `private` to package-visible for this.
- **`--diff-all` convenience flag** enables the three opt-in work-dir layers
  (`--diff-outputs`, `--diff-logs`, `--diff-dag`) at once. They share the same
  precondition — the tasks' work directories must still exist — and are commonly
  wanted together. The flag only enables, never forces off, so a later explicit
  `--diff-<layer>=false` still opts an individual layer back out.
- **Direct unit tests for `RunLoader`'s pure helpers.** `RunLoader` is the
  riskiest component (it reuses Nextflow's internal `HistoryFile`/`CacheDB`) yet
  had no test. A new `RunLoaderTest` pins the pieces that are pure and
  standalone — transient-lock detection (`isLockError`), the trace-store value
  coercions (`asLong`/`asString`), and session-id shortening (`shortId`) — which
  required only making those static helpers package-visible.
- **Fixture-backed test for `RunLoader.lastPair`.** `lastPair` reads only
  `.nextflow/history` (no LevelDB cache), so `RunLoaderTest` now writes a
  hand-built history fixture and exercises the real selection logic end to end:
  the default-B single-offset form, the explicit `A:B` pair form (and its
  equivalence to `A:0`), and the out-of-range guards (negative B, `A <= B`, and
  too few runs in history).

### Changed

- **Argument parsing is centralised behind an `ArgCursor`.** `DiffCommand.parse`
  previously hand-rolled, for every option, the inline `--key=value` split, the
  space-separated `--key value` fallback with its manual `if( inlineVal == null )
  i++` index bookkeeping, and — for each numeric flag — a duplicated
  parse/`NumberFormatException`/range-check block. A private `ArgCursor` now owns
  position tracking and exposes `requireValue`/`boolValue`/`intValue`/`longValue`/
  `doubleValue` helpers, so each option case collapses to a single assignment and
  the off-by-one hazard in the repeated `i++` dance is gone. Behaviour is
  unchanged (all forms — inline, space-separated, and launcher-injected
  `--flag true`/`--last N`/`--last A:B` — parse exactly as before); only the
  numeric-validation messages are now generated from a shared template.
- **`--last` gained an explicit `A:B` pair form and clearer docs.** A single
  `--last=N` still compares the run N positions before the latest against the
  latest — but that silently *skips* the runs in between, which was easy to
  misread as "the N most recent runs". You can now name an exact pair by their
  offsets back from the latest (`0` = latest, requiring `A > B >= 0`): e.g.
  `--last=2:1` compares the run two back against the run one back. The bare
  `--last` and single-integer forms are unchanged (`--last=N` ≡ `--last=N:0`).
  The `-h` text now spells out the skip behavior, and the info line logged at
  selection names each side's offset explicitly.
- **Per-task `raw` trace map is no longer deep-copied.** `RunLoader.toTaskInfo`
  built each `TaskInfo` with `raw = new LinkedHashMap<>(store)`, duplicating the
  entire trace store on top of the already-copied `display` map — roughly
  doubling per-task memory on large runs. `CacheDB.eachRecord` deserializes a
  fresh `TraceRecord` (and store map) per iteration and the record is discarded
  immediately, so nothing can mutate or reuse it; the defensive copy bought no
  isolation. `TaskInfo` now references the store map directly.
- **Cache-lock detection is no longer coupled to a single literal message.**
  `RunLoader.isLockError` — which decides whether a failed cache open is a
  transient lock contention worth retrying — previously matched exactly one
  string (`Unable to acquire lock`). A phrasing change in LevelDB or Nextflow
  would silently disable the retry loop. It now matches a small, case-insensitive
  allow-list of known lock signatures (still lock-specific, so unrelated failures
  are never retried pointlessly) while continuing to walk the cause chain.
- **The opt-in work-dir layers now compare tasks in parallel.** `--diff-outputs`
  and `--diff-logs` previously walked matched task pairs one at a time, so a
  pipeline with many tasks and large outputs paid for single-threaded
  SHA-256/log I/O. Because each matched pair is independent, read-only work-dir
  I/O, `RunComparator` now fans the comparisons out across a bounded pool (sized
  to the smaller of the work size and the available processors) while still
  returning results in `result.tasks` order, so the report is byte-for-byte
  unchanged. `--diff-dag` likewise reconstructs both runs' graphs concurrently.
  Failures propagate unchanged (the underlying exception is unwrapped from the
  executor), and pool threads are daemon so a stuck read never keeps the JVM
  alive.
- **Command-line tokenisation now has a single source of truth.** `ConfigLoader`
  previously carried its own copy of the quote-aware command tokenizer that
  "mirrored" `CommandParams`'; the two could silently drift. `CommandParams.tokenize`
  is now shared and `ConfigLoader` delegates to it, so `-c`/`-config` extraction
  and flag parsing always split commands identically.

## [0.3.0] - 2026-09-08

### Added

- **Lineage-backed DAG reconstruction (`--diff-dag`)** — when a run's project
  directory has a Nextflow data-lineage store (`.lineage/`, produced with
  `lineage.enabled = true` on Nextflow 25.04+), the process wiring is now read
  from the **authoritative** provenance Nextflow persisted instead of being
  inferred from work-dir symlinks. A new `LineageStore` reads each `.data.json`
  record directly off disk (no compile-time dependency on the `nf-lineage`
  module), indexes the run's `TaskRun` records by their session id, and
  reconstructs producer→consumer edges from each task's recorded `input` LID
  references (`lid://<producerTaskHash>/…`). Because it reads what Nextflow
  recorded, this needs **no work directories** and is unaffected by cleanup.
  `DagComparator.graphOf` now takes the run's project directory and prefers the
  lineage store, falling back to the existing best-effort symlink
  reconstruction (`symlinkGraphOf`) when no lineage store recorded the run. Each
  run's `RunGraph` carries a `source` (`LINEAGE` / `SYMLINK` / `NONE`), and the
  wiring layer's note now states whether the graph is authoritative (lineage) or
  best-effort (symlinks), including the mixed case. The layer remains
  informational only — it never affects the "identical" verdict or
  `--fail-on-change`. Only the default `<projectDir>/.lineage` store location is
  auto-detected; a custom `lineage.store.location` still falls back to symlinks.

### Changed

- **Unknown plugin command now exits with the usage code (2), not 1.** An
  unrecognized verb (anything other than `diff`) is a usage error, in the same
  class as bad arguments, so it now returns `2` — matching the documented
  exit-code table — instead of `1` (which is reserved for runtime errors). The
  message also notes that only `diff` is supported.
- **Help summary lists all current diff layers.** The one-line description shown
  by `-h`/`--help` still read "metadata, processes, and per-task
  resources/scripts" from the 0.1.0 days; it now enumerates the always-on layers
  (parameters, configuration, software & versions, failure rollup, performance
  regressions, resource-efficiency) and the three opt-in flags.
- **`--diff-dag` no longer requires work directories when lineage is enabled.**
  Previously the wiring layer always needed the tasks' work directories to still
  exist locally; with a lineage store present it is reconstructed from persisted
  provenance instead.

- **Continuous integration & tag-based releases** — a GitHub Actions CI
  workflow (`.github/workflows/ci.yml`) now runs the full verification suite
  (`make check`) on every push and pull request to `main`, across JDK 17 and 21,
  uploading test reports as build artifacts. A companion release workflow
  (`.github/workflows/release.yml`) publishes to the
  [Nextflow plugin registry](https://registry.nextflow.io/) when a `v*` version
  tag is pushed: it verifies the tag matches `build.gradle`'s `version` (so a tag
  can never publish a mismatched artifact), runs `make check`, then `make
  release`, authenticating with an `NPR_API_KEY` repository secret. See the
  README's "Continuous integration" and "Releasing" sections for setup and the
  tagging flow.
- **DAG (process wiring) diff (`--diff-dag`)** — a new opt-in layer that
  reconstructs each run's process;process wiring and diffs the two edge
  sets, so nf-diff surfaces topology changes the task-count-per-process view
  cannot see — e.g. a pipeline rewired from `A → C` to `A → B → C`. Nextflow
  does not persist DAG edges in its history or cache, so there is no
  authoritative edge list to read; what it *does* leave on disk is every task's
  staged inputs, materialised as symbolic links inside the task's work
  directory. `DagComparator` walks each task's work dir, resolves every input
  symlink, and attributes any target that resolves into another task's work dir
  (walking the parent chain so a link into a nested output subdir still
  attributes to the producer) as a producer;consumer edge; links that
  resolve outside every work dir are external inputs and yield no edge. Because
  it walks work directories, this layer needs them to still exist locally (like
  `--diff-outputs` / `--diff-logs`) and is a best-effort reconstruction: if some
  work dirs were cleaned up, the recovered wiring is incomplete, and a note
  reports how many task work dirs were missing so a partial diff is not read as
  authoritative. Surfaced in all three report formats (HTML "Process wiring
  (DAG)" section + nav link, Markdown section, and a `dag` block with
  `dagEdgesAdded`/`dagEdgesRemoved` in JSON). Because the reconstruction is
  best-effort, this layer is informational only and never affects the
  "identical" verdict or `--fail-on-change`.
- **Failure rollup (top-level "what failed and why")** — a new always-on layer
  that answers, at a glance, which tasks failed and why, instead of leaving that
  scattered across per-task detail. Failed tasks are detected from the cached
  `status`/`exit` fields (an explicit `FAILED`/`ABORTED` status, or a non-zero
  exit code — the `NO_EXIT` sentinel and blanks are ignored), so the layer reads
  straight from the run cache and needs no work directories. Failures are rolled
  up by their `(process, status, exit)` signature and counted per run, sorted by
  biggest blast radius first; a signature seen only in Run B is flagged **new**
  (a regression), one present in Run A but gone in Run B is **resolved**, and one
  in both is **persistent**. The layer also surfaces a run-level error state
  (history `status` starting `ERR` or equal to `FAILED`/`ABORTED`/`KILLED`) even
  when no individual task failure was recorded. Surfaced in all three report
  formats (HTML "Failure rollup" section + nav link + summary cards, Markdown
  section, and a `failures` block with `failedA`/`failedB`/`newFailures`/
  `resolvedFailures` summary counts in JSON). Because the meaningful identity
  signal — a task whose status or exit changed — is already carried by the task
  field diffs, this rollup is informational only and never separately affects
  `isIdentical()` / `--fail-on-change`.
- **Resource-efficiency layer (requested vs. measured-peak provisioning)** — a
  new always-on layer that, per process, compares what each run *requested*
  (`cpus`, `memory`) against what it actually *peaked* at (`%cpu`, `peak_rss`).
  Both requested and peak values are read straight from the run cache trace and
  taken as the max across a process's tasks (a retried task that used more, or
  was bumped a higher request, is the honest worst case), so the layer needs no
  work directories and is always computed. The efficiency ratio is
  measured-peak / requested; each process is classified per run as **over**
  (below 50% — wasted allocation, e.g. "requested 32 GB, peaked at 4 GB"),
  **tight** (90%+ — risk of OOM kills or CPU throttling), or **ok** in between.
  Surfaced in all three report formats (HTML section + an "Over-provisioned (B)"
  summary card, Markdown table, and an `efficiency` array plus
  `overProvisionedA`/`overProvisionedB` and `tightA`/`tightB` summary counts in
  JSON). Because provisioning is a tuning signal rather than a correctness
  change, this layer is informational only — it never affects `isIdentical()` /
  `--fail-on-change`.
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

- **Diff errors with no message printed a blank line.** `DiffPlugin.exec()`
  reported a caught throwable via `e.message` only, so a message-less exception
  (notably `NullPointerException`) produced a bare `nf-diff:` line with nothing
  after it, while the stack trace went only to the debug-gated log. It now falls
  back to the exception's simple class name, so both the stderr line and the log
  always name the failure.
- **Cache-lock retry backoff was not interruptible.** The backoff between
  attempts to open a contended run cache used Groovy's `sleep()`, which swallows
  `InterruptedException` and clears the interrupt flag, so a `Ctrl-C` during a
  contended open was ignored and the loop kept retrying. It now uses
  `Thread.sleep()`, restoring the interrupt flag and aborting the retry on
  interruption.
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
