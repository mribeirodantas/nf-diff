# Development

[← docs index](README.md)

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

## Layout

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

## Continuous integration

Every push and pull request to `main` runs the full verification suite
(`make check`) on GitHub Actions across JDK 17 and 21
(`.github/workflows/ci.yml`). Test reports are uploaded as build artifacts so a
red build is debuggable without re-running locally.

## Releasing

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
