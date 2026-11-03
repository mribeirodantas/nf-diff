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

import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.cli.CliOptions
import nextflow.cli.CmdRun
import nextflow.config.ConfigBuilder
import nextflow.scm.AssetManager

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
    Map<String,String> resolve(String command, Path baseDir, String revision = null) {
        final dir = (baseDir ?: Paths.get('.')).toAbsolutePath().normalize()
        final profile = CommandParams.parse(command).get('-profile')
        final userConfigs = configFilesFrom(command, dir, ['-c', '-config'])
        final replaceConfigs = configFilesFrom(command, dir, ['-C'])
        final projectDir = assetDir(command, dir, revision) ?: dir
        log.debug "nf-diff: resolving config for '${CommandParams.projectName(command)}' (revision '${revision}') from ${projectDir}"

        final cliOpts = new CliOptions()
        if( replaceConfigs )
            cliOpts.config = replaceConfigs.collect { Path p -> p.toString() }

        final params = cliParams(command, dir)
        final run = new CmdRun()
        run.params = params

        final builder = new ConfigBuilder()
                .setShowClosures(true)
                .setStripSecrets(true)
        final cmdConfig = cliConfigTarget(builder)
        configureCli(cmdConfig, cliOpts, run, projectDir, dir, userConfigs)
        // After configureCli: on 26.10+ the adapter's setCmdRun copies the
        // (unset) CmdRun profile onto the builder.
        if( profile )
            builder.setProfile(profile)
        applyCliParams(builder, paramsTree(params))

        final resolved = buildConfigObject(cmdConfig)
        final out = new TreeMap<String,String>()
        flatten('', resolved, out)
        return out
    }

    /** Introduced in Nextflow 26.10, which moved the CLI-facing ConfigBuilder API onto it. */
    private static final String CONFIG_CMD_ADAPTER = 'nextflow.config.ConfigCmdAdapter'

    /**
     * The object that owns the CLI-facing config API ({@code setOptions},
     * {@code setCmdRun}, {@code setBaseDir}, {@code setCurrentDir},
     * {@code setUserConfigFiles}, {@code buildConfigObject}). Up to 26.04 that
     * is {@link ConfigBuilder} itself; from 26.10 those methods were removed
     * from ConfigBuilder and live on {@code ConfigCmdAdapter}, which wraps the
     * builder. The plugin is compiled against the 25.04 floor, so the adapter
     * is located reflectively and the calls are dispatched dynamically.
     */
    private static Object cliConfigTarget(ConfigBuilder builder) {
        try {
            final clazz = Class.forName(CONFIG_CMD_ADAPTER, true, ConfigBuilder.classLoader)
            return clazz.getConstructor(ConfigBuilder).newInstance(builder)
        }
        catch( ClassNotFoundException ignored ) {
            return builder
        }
    }

    @CompileDynamic
    private static void configureCli(Object target, CliOptions cliOpts, CmdRun run, Path projectDir, Path currentDir, List<Path> userConfigs) {
        target.setOptions(cliOpts)
        target.setCmdRun(run)
        target.setBaseDir(projectDir)
        target.setCurrentDir(currentDir)
        if( userConfigs )
            target.setUserConfigFiles(userConfigs)
    }

    @CompileDynamic
    private static Object buildConfigObject(Object target) {
        return target.buildConfigObject()
    }

    /** Nextflow 26.x reads params only via setCliParams (absent in 25.04, which reads CmdRun.params). */
    private static void applyCliParams(ConfigBuilder builder, Map<String,Object> params) {
        try {
            builder.getClass().getMethod('setCliParams', Map).invoke(builder, params)
        }
        catch( NoSuchMethodException ignored ) {
            log.debug 'nf-diff: ConfigBuilder.setCliParams not available; using CmdRun.params'
        }
    }

    /** Dotted param keys become nested maps; scalars are typed like Nextflow's CLI. */
    private static Map<String,Object> paramsTree(Map<String,String> params) {
        final root = new LinkedHashMap<String,Object>()
        params.each { String k, String v ->
            final parts = k.tokenize('.')
            Map<String,Object> node = root
            parts.eachWithIndex { String part, int i ->
                if( i == parts.size() - 1 )
                    node.put(part, typed(v))
                else {
                    def next = node.get(part)
                    if( !(next instanceof Map) ) {
                        next = new LinkedHashMap<String,Object>()
                        node.put(part, next)
                    }
                    node = (Map<String,Object>) next
                }
            }
        }
        return root
    }

    private static Object typed(String v) {
        if( v == 'true' || v == 'false' )
            return Boolean.parseBoolean(v)
        if( v ==~ /-?\d+/ && v.length() < 18 )
            return Long.parseLong(v)
        if( v ==~ /-?\d+\.\d+/ )
            return Double.parseDouble(v)
        return v
    }

    /**
     * Extract all {@code -c}/{@code -config} config-file arguments from a launch
     * command, in order, resolving relative paths against {@code baseDir} and
     * keeping only those that currently exist on disk.
     */
    private static List<Path> configFilesFrom(String command, Path baseDir, List<String> flags) {
        final paths = new ArrayList<Path>()
        final tokens = CommandParams.tokenize(command)
        int i = 0
        while( i < tokens.size() ) {
            final tok = tokens[i]
            String value = null
            if( flags.contains(tok) ) {
                // Consume the following token as the config path only when it is
                // not itself an option — otherwise `-c -resume` would misread
                // `-resume` as a filename (and then silently drop it).
                final nxt = (i + 1 < tokens.size()) ? tokens[i + 1] : null
                if( nxt != null && !nxt.startsWith('-') ) {
                    value = nxt
                    i++
                }
            }
            else {
                final flag = flags.find { String f -> tok?.startsWith(f + '=') }
                if( flag )
                    value = tok.substring(flag.length() + 1)
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
     * Local asset checkout of a remote pipeline, or null for local scripts. Newer
     * Nextflow keeps one clone per commit under .repos/org/repo/clones/<commit>, so
     * the run's own revision is preferred; otherwise the legacy assets/org/repo
     * checkout is used (best-effort: it may be on a different revision).
     */
    private static Path assetDir(String command, Path baseDir, String revision = null) {
        final name = CommandParams.projectName(command)
        if( !name || name.endsWith('.nf') || Files.exists(baseDir.resolve(name)) || !name.contains('/') )
            return null
        try {
            final clone = cloneFor(assetsRoot(), name, revision)
            if( clone != null )
                return clone
            final local = new AssetManager(name).getLocalPath()
            if( local != null && local.exists() )
                return local.toPath()
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not resolve asset dir for '${name}': ${e.message}"
        }
        return null
    }

    /** The per-commit clone .repos/<name>/clones/<revision*> under an assets root, or null. */
    static Path cloneFor(Path assetsRoot, String name, String revision) {
        final rev = revision?.trim()
        if( !rev )
            return null
        final clones = assetsRoot.resolve('.repos').resolve(name).resolve('clones')
        if( !Files.isDirectory(clones) )
            return null
        return (Path) Files.list(clones).withCloseable { s ->
            s.filter { Path p -> Files.isDirectory(p) && p.fileName.toString().startsWith(rev) }.findFirst().orElse(null)
        }
    }

    private static Path assetsRoot() {
        final assets = System.getenv('NXF_ASSETS')
        if( assets )
            return Paths.get(assets)
        final home = System.getenv('NXF_HOME') ?: System.getProperty('user.home') + '/.nextflow'
        return Paths.get(home).resolve('assets')
    }

    /**
     * A note when the legacy asset checkout is on a different revision than the
     * run's {@code -r}; null when they match, no {@code -r} was given, or the
     * pipeline is not a legacy local asset (per-commit clones always match).
     */
    static String revisionNote(String command, Path baseDir) {
        final wanted = CommandParams.parse(command).get('-r')
        if( !wanted || wanted == 'true' || assetDir(command, baseDir) == null )
            return null
        try {
            final current = new AssetManager(CommandParams.projectName(command)).getCurrentRevision()
            if( current && current != wanted )
                return "pipeline config was read from the local checkout at '${current}', but the run used -r ${wanted}".toString()
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not read asset revision: ${e.message}"
        }
        return null
    }

    /** Pipeline params (--name) of the run, including -params-file contents, without the leading dashes. */
    private static Map<String,String> cliParams(String command, Path baseDir) {
        final out = new LinkedHashMap<String,String>()
        CommandParams.resolve(command, baseDir).values.each { String k, String v ->
            if( CommandParams.isPipelineParam(k) )
                out[k.substring(2)] = v
        }
        return out
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
