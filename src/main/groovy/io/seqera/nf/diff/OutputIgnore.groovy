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

import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.Paths

import groovy.transform.CompileStatic

/**
 * File patterns ({@code --outputs-ignore}) whose matches are left out of the
 * work-dir outputs and published-results comparisons, for files that are
 * expected to differ between any two runs (reports with timestamps,
 * {@code versions.yml}, logs).
 *
 * <p>Patterns follow {@code .gitignore}-style rules, matched against a file's
 * path relative to its work directory or published root:
 * <ul>
 *   <li>a pattern without {@code /} matches any path segment, so
 *       {@code *.html} ignores HTML files in every folder and
 *       {@code pipeline_info} ignores everything under such a folder;</li>
 *   <li>a pattern with {@code /} matches the whole relative path, where
 *       {@code *} stays within one folder and {@code **} spans folders; a
 *       leading {@code **}{@code /} also matches at the root, and a trailing
 *       {@code /} means everything under that folder.</li>
 * </ul>
 */
@CompileStatic
class OutputIgnore {

    /** Matches nothing; the default when no patterns are given. */
    static final OutputIgnore NONE = new OutputIgnore([])

    /** The patterns as given, for display. */
    final List<String> patterns

    private final List<PathMatcher> segmentMatchers = []
    private final List<PathMatcher> pathMatchers = []

    private OutputIgnore(List<String> patterns) {
        this.patterns = patterns.asImmutable()
        final fs = FileSystems.getDefault()
        patterns.each { String raw ->
            String p = raw.trim()
            if( !p )
                return
            if( p.endsWith('/') )
                p = p + '**'
            if( !p.contains('/') ) {
                segmentMatchers << fs.getPathMatcher('glob:' + p)
                return
            }
            if( p.startsWith('/') )
                p = p.substring(1)
            pathMatchers << fs.getPathMatcher('glob:' + p)
            // `**/x` should also match `x` at the root, as in .gitignore.
            if( p.startsWith('**/') )
                pathMatchers << fs.getPathMatcher('glob:' + p.substring(3))
        }
    }

    /** Patterns from the command line; null or empty means ignore nothing. */
    static OutputIgnore of(List<String> patterns) {
        final clean = (patterns ?: []).collect { String p -> p.trim() }.findAll { String p -> p } as List<String>
        return clean ? new OutputIgnore(clean) : NONE
    }

    boolean isEmpty() {
        return patterns.isEmpty()
    }

    /** Whether {@code relPath} (relative, {@code /}-separated) is ignored. */
    boolean ignores(String relPath) {
        if( isEmpty() || !relPath )
            return false
        final path = Paths.get(relPath)
        for( PathMatcher m : pathMatchers ) {
            if( m.matches(path) )
                return true
        }
        if( segmentMatchers ) {
            for( Path segment : path ) {
                for( PathMatcher m : segmentMatchers ) {
                    if( m.matches(segment) )
                        return true
                }
            }
        }
        return false
    }

    /** Patterns for display in notes, e.g. {@code `*.html`, `versions.yml`}. */
    String describe() {
        return patterns.collect { String p -> "`${p}`".toString() }.join(', ')
    }
}
