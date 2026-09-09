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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.config.ConfigBuilder

/**
 * Resolves the effective Nextflow configuration for a run and flattens it to a
 * sorted {@code dotted.key -> value} map, so two runs' configurations can be
 * diffed field by field (process resources, executor, container engine, profile
 * contents, ...).
 *
 * <h2>What "resolved" means here</h2>
 * The launch command records which profiles ({@code -profile}) and extra config
 * files ({@code -c}/{@code -config}) a run used, but Nextflow does <em>not</em>
 * persist the fully merged config in its local run history or cache. This loader
 * therefore rebuilds the config the same way {@code nextflow config} does — with
 * {@link ConfigBuilder} — against the project's config files <em>as they exist
 * now</em>, applying each run's recorded profiles and {@code -c} files.
 *
 * <p>The practical consequence: this layer is authoritative for differences
 * driven by {@code -profile}/{@code -c} choices (the classic "{@code -profile
 * docker} vs {@code -profile test}" case), and reflects the current on-disk
 * {@code nextflow.config}. It is not a historical snapshot of the config bytes
 * at launch time — if the project's config files changed since the run, the
 * resolved values follow the current files. This is called out in the report.
 */
@Slf4j
@CompileStatic
class ConfigLoader {

    /** The {@code params} scope is covered by the dedicated Parameters layer. */
    private static final String PARAMS_PREFIX = 'params.'

    /**
     * Resolve and flatten the configuration a run would use, given its recorded
     * launch command, against {@code baseDir}. Returns a sorted map of dotted
     * config keys to string values. The {@code params} scope is excluded — the
     * Parameters layer owns param provenance (CLI vs file) — so this layer stays
     * focused on infrastructure/config settings.
     *
     * @return an ordered (key-sorted) map; empty when nothing could be resolved
     * @throws Exception if the config files cannot be parsed (callers should
     *         catch and record this as a note rather than fail the whole diff)
     */
    Map<String,String> resolve(String command, Path baseDir) {
        final dir = (baseDir ?: Paths.get('.')).toAbsolutePath().normalize()
        final profile = CommandParams.parse(command).get('-profile')
        final userConfigs = configFilesFrom(command, dir)

        final builder = new ConfigBuilder()
                .setShowClosures(true)
                .setStripSecrets(true)
                .setBaseDir(dir)
                .setCurrentDir(dir)
        if( profile )
            builder.setProfile(profile)
        if( userConfigs )
            builder.setUserConfigFiles(userConfigs)

        final resolved = builder.buildConfigObject()
        final out = new TreeMap<String,String>()
        flatten('', resolved, out)
        return out
    }

    /**
     * Extract all {@code -c}/{@code -config} config-file arguments from a launch
     * command, in order, resolving relative paths against {@code baseDir} and
     * keeping only those that currently exist on disk.
     */
    private static List<Path> configFilesFrom(String command, Path baseDir) {
        final paths = new ArrayList<Path>()
        final tokens = CommandParams.tokenize(command)
        int i = 0
        while( i < tokens.size() ) {
            final tok = tokens[i]
            String value = null
            if( tok == '-c' || tok == '-config' ) {
                value = (i + 1 < tokens.size()) ? tokens[i + 1] : null
                i++
            }
            else if( tok?.startsWith('-c=') ) {
                value = tok.substring('-c='.length())
            }
            else if( tok?.startsWith('-config=') ) {
                value = tok.substring('-config='.length())
            }
            if( value ) {
                final p = Paths.get(value)
                final resolved = p.isAbsolute() ? p : baseDir.resolve(p)
                if( Files.isReadable(resolved) )
                    paths.add(resolved)
                else
                    log.debug "nf-diff: config file '${value}' (resolved: ${resolved}) not readable; skipping"
            }
            i++
        }
        return paths
    }

    /**
     * Recursively flatten a resolved config into {@code dotted.key -> string}
     * entries. Nested maps recurse; scalars and lists are leaves. The
     * {@code params} scope is skipped.
     */
    private static void flatten(String prefix, Object node, Map<String,String> out) {
        if( node instanceof Map ) {
            ((Map) node).each { Object k, Object v ->
                final key = prefix ? "${prefix}.${k}".toString() : k.toString()
                if( key == 'params' || key.startsWith(PARAMS_PREFIX) )
                    return
                flatten(key, v, out)
            }
        }
        else if( prefix && !prefix.startsWith(PARAMS_PREFIX) ) {
            out[prefix] = (node == null) ? 'null' : node.toString()
        }
    }
}
