.PHONY: assemble clean install test check release compile dev-repo

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
	printf '[\n  {\n    "id": "nf-diff",\n    "name": "nf-diff",\n    "description": "Compare two Nextflow runs and render a detailed HTML report",\n    "provider": "seqera",\n    "releases": [\n      {\n        "version": "$(VERSION)",\n        "date": "1970-01-01T00:00:00.000Z",\n        "requires": ">=25.04.0",\n        "url": "file://%s",\n        "sha512sum": "%s"\n      }\n    ]\n  }\n]\n' "$$ZIP" "$$SHA" > build/plugin-repo/plugins.json; \
	echo "Wrote build/plugin-repo/plugins.json for nf-diff@$(VERSION)"; \
	echo 'Now: export NXF_PLUGINS_TEST_REPOSITORY="file://$(CURDIR)/build/plugin-repo/plugins.json"'
