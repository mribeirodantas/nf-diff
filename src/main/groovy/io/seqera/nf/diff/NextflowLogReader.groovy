/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.seqera.nf.diff

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher
import java.util.regex.Pattern

import groovy.transform.CompileStatic
import groovy.transform.Immutable
import groovy.util.logging.Slf4j

/**
 * Recovers a run's Nextflow version and build from the launch directory's
 * {@code .nextflow.log} and its rotated siblings ({@code .nextflow.log.1} ...
 * {@code .nextflow.log.9}). This is the fallback for runs without a
 * {@code .lineage/} store, which is otherwise the only place the version is
 * persisted.
 *
 * <p>Each log's header carries everything needed, within the first few dozen
 * lines:
 * <pre>
 * ... DEBUG nextflow.cli.CmdRun - N E X T F L O W  ~  version 25.10.0
 * ... DEBUG nextflow.Session - Session UUID: d78a3221-8ea0-4a76-b83e-ba72ab5b6328
 * ... DEBUG nextflow.Session - Run name: cranky_ride
 *   Version: 25.10.0 build 10289
 * </pre>
 * A log matches a run only when both its session UUID and run name agree, so
 * {@code -resume} runs (same session, new run name) stay distinct.
 *
 * <p>Best-effort: Nextflow keeps only the ten most recent logs, and any missing,
 * unreadable or unrecognised file is skipped, yielding {@code null}.
 */
@Slf4j
@CompileStatic
class NextflowLogReader {

    /** Base log filename Nextflow writes in the launch directory. */
    static final String LOG_NAME = '.nextflow.log'

    /** Header fields all appear early; stop scanning a log after this many lines. */
    static final int MAX_HEADER_LINES = 500

    private static final Pattern LOG_FILE = ~/^\.nextflow\.log(\.\d+)?$/
    private static final Pattern SESSION = ~/\bSession UUID: ([0-9a-fA-F-]{36})/
    private static final Pattern RUN_NAME = ~/\bRun name: (\S+)/
    private static final Pattern VERSION_BUILD = ~/^\s+Version: (\S+) build (\S+)/
    private static final Pattern BANNER = ~/N E X T F L O W\s+~\s+version (\S+)/

    /** Version information recovered from one log. */
    @Immutable
    static class VersionInfo {
        String version
        String build
    }

    private final Path launchDir

    NextflowLogReader(Path launchDir) {
        this.launchDir = launchDir
    }

    /**
     * The Nextflow version of the run identified by {@code sessionId} and
     * {@code runName}, or {@code null} when no log in the launch directory
     * matches it. A {@code null} {@code runName} matches on session id alone.
     */
    VersionInfo versionFor(UUID sessionId, String runName) {
        if( launchDir == null || sessionId == null )
            return null
        final sid = sessionId.toString()
        for( Path logFile : logFiles() ) {
            final header = readHeader(logFile)
            if( header == null || !sid.equalsIgnoreCase(header.sessionId) )
                continue
            if( runName && header.runName != runName )
                continue
            if( !header.version )
                continue
            return new VersionInfo(version: header.version, build: header.build)
        }
        return null
    }

    /** {@code .nextflow.log} first, then {@code .nextflow.log.1}, {@code .2}, ... */
    private List<Path> logFiles() {
        if( !Files.isDirectory(launchDir) )
            return Collections.<Path>emptyList()
        final List<Path> found = []
        try {
            Files.newDirectoryStream(launchDir).withCloseable { stream ->
                for( Path p : stream ) {
                    if( LOG_FILE.matcher(p.fileName.toString()).matches() && Files.isRegularFile(p) )
                        found << p
                }
            }
        }
        catch( IOException e ) {
            log.debug "nf-diff: cannot list ${launchDir} for Nextflow logs: ${e.message}"
            return Collections.<Path>emptyList()
        }
        found.sort { Path p -> rotationIndex(p) }
        return found
    }

    private static int rotationIndex(Path p) {
        final name = p.fileName.toString()
        return name == LOG_NAME ? 0 : Integer.parseInt(name.substring(LOG_NAME.length() + 1))
    }

    private static class Header {
        String sessionId
        String runName
        String version
        String build
    }

    /** Parse the header fields of one log, or {@code null} if it cannot be read. */
    private static Header readHeader(Path logFile) {
        final h = new Header()
        try {
            // InputStreamReader replaces malformed bytes instead of throwing.
            new BufferedReader(new InputStreamReader(Files.newInputStream(logFile), StandardCharsets.UTF_8)).withCloseable { BufferedReader reader ->
                String line
                int n = 0
                while( n++ < MAX_HEADER_LINES && (line = reader.readLine()) != null ) {
                    Matcher m
                    if( h.sessionId == null && (m = SESSION.matcher(line)).find() )
                        h.sessionId = m.group(1)
                    else if( h.runName == null && (m = RUN_NAME.matcher(line)).find() )
                        h.runName = m.group(1)
                    else if( h.build == null && (m = VERSION_BUILD.matcher(line)).find() ) {
                        h.version = m.group(1)
                        h.build = m.group(2)
                    }
                    else if( h.version == null && (m = BANNER.matcher(line)).find() )
                        h.version = m.group(1)
                    if( h.sessionId && h.runName && h.build )
                        break
                }
            }
        }
        catch( IOException e ) {
            log.debug "nf-diff: cannot read ${logFile}: ${e.message}"
            return null
        }
        return h
    }
}
