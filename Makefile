.PHONY: assemble clean install test check release compile

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
