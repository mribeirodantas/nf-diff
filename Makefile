.PHONY: assemble clean install test check release compile dev-repo smoke

VERSION := $(shell awk -F"'" '/^version[[:space:]]*=/{print $$2}' build.gradle)

compile:
	./gradlew compileGroovy

assemble:
	./gradlew assemble

clean:
	./gradlew clean

install:
	./gradlew installPlugin

test:
	./gradlew test

check:
	./gradlew check

release:
	./gradlew releasePlugin

# Generate a local test repository (plugins.json + zip) so the plugin verb
# can be resolved by its BARE id before it is published to the registry:
#   make dev-repo
#   export NXF_PLUGINS_TEST_REPOSITORY="file://$(CURDIR)/build/plugin-repo/plugins.json"
#   nextflow plugin nf-diff:diff <runA> <runB>
dev-repo: assemble
	@mkdir -p build/plugin-repo
	@ZIP="$(CURDIR)/build/distributions/nf-diff-$(VERSION).zip"; \
	SHA=$$(shasum -a 512 "$$ZIP" | awk '{print $$1}'); \
	printf '[\n  {\n    "id": "nf-diff",\n    "name": "nf-diff",\n    "description": "Compare two Nextflow runs and render a detailed HTML report",\n    "provider": "nextflow",\n    "releases": [\n      {\n        "version": "$(VERSION)",\n        "date": "1970-01-01T00:00:00.000Z",\n        "requires": ">=25.04.0",\n        "url": "file://%s",\n        "sha512sum": "%s"\n      }\n    ]\n  }\n]\n' "$$ZIP" "$$SHA" > build/plugin-repo/plugins.json; \
	echo "Wrote build/plugin-repo/plugins.json for nf-diff@$(VERSION)"; \
	echo 'Now: export NXF_PLUGINS_TEST_REPOSITORY="file://$(CURDIR)/build/plugin-repo/plugins.json"'

# End-to-end smoke test: build a local plugin repo, then resolve the plugin by
# its bare id and drive the real `nextflow plugin nf-diff:diff` launcher against
# two genuine runs, asserting exit codes and report content. Exercises the
# HistoryFile/CacheDB/ConfigBuilder path the unit tests cannot. Requires a
# `nextflow` binary on PATH (CI pins its version via NXF_VER).
#
# NXF_HOME is pinned to a fresh, build-local directory so the test is hermetic:
# Nextflow always extracts the just-built plugin from the dev repo (never a
# stale same-version copy already unpacked under ~/.nextflow/plugins), and a
# developer's personal ~/.nextflow/config (e.g. a `plugins { id 'nf-diff@x' }`
# pin) can neither influence nor break the run. This also makes local behaviour
# match CI, where no such home exists.
smoke: dev-repo
	@rm -rf "$(CURDIR)/build/nxf-home-smoke"
	NXF_PLUGINS_TEST_REPOSITORY="file://$(CURDIR)/build/plugin-repo/plugins.json" \
	NXF_HOME="$(CURDIR)/build/nxf-home-smoke" \
	./e2e/smoke.sh
