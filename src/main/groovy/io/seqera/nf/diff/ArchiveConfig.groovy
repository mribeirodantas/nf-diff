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

import java.nio.file.Path

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import nextflow.Session

/**
 * The resolved {@code diff.archive.*} configuration that controls whether — and
 * how — {@link DiffObserver} archives a run when it completes.
 *
 * <p>The config scope is {@code diff} (not the hyphenated plugin id, which does
 * not parse as an unquoted Groovy config block):
 * <pre>
 *   diff {
 *       archive {
 *           enabled = true            // off by default
 *           mode    = 'lightweight'   // or 'complete' to also copy work-dir files
 *           dir     = "$HOME/.nextflow/nf-diff/archive"   // optional override
 *       }
 *   }
 * </pre>
 */
@CompileStatic
class ArchiveConfig {

    /** When false the observer is never registered and nothing is archived. */
    boolean enabled

    /** True for {@code mode = 'complete'} (also copy each task's work-dir files). */
    boolean complete

    /** Resolved archive directory (config value, else env, else default). */
    Path dir

    /** Resolve the archive settings from the session's merged configuration. */
    static ArchiveConfig from(Session session) {
        final cfg = new ArchiveConfig()
        cfg.enabled = asBool(navigate(session, 'diff.archive.enabled'))
        final mode = navigate(session, 'diff.archive.mode') as String
        cfg.complete = mode != null && mode.trim().equalsIgnoreCase('complete')
        cfg.dir = ArchiveStore.resolveDir(navigate(session, 'diff.archive.dir') as String)
        return cfg
    }

    /**
     * Read a dotted config key, tolerating any lookup failure (an absent scope,
     * or an API shift across Nextflow versions) by returning null. Kept dynamic
     * because {@code session.config} is a {@code ConfigMap} whose {@code navigate}
     * method is not visible to {@code @CompileStatic} against the plain Map type.
     */
    @CompileDynamic
    private static Object navigate(Session session, String key) {
        try {
            return session?.config?.navigate(key)
        }
        catch( Throwable t ) {
            return null
        }
    }

    /** Coerce a config value to a boolean, accepting real Booleans and strings. */
    static boolean asBool(Object v) {
        if( v instanceof Boolean )
            return ((Boolean) v).booleanValue()
        if( v instanceof String )
            return Boolean.parseBoolean(((String) v).trim())
        return false
    }
}
