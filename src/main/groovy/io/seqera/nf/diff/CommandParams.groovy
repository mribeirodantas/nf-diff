package io.seqera.nf.diff

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.yaml.snakeyaml.Yaml

/**
 * Parses a Nextflow launch command string (as recorded in {@code .nextflow/history})
 * into an ordered map of {@code flag -> value}, covering both pipeline
 * parameters ({@code --input}) and Nextflow CLI options ({@code -profile},
 * {@code -r}, ...).
 *
 * <p>This is a best-effort, dependency-free view of <em>what was passed on the
 * command line</em>. It does not resolve parameters that come from config files
 * or {@code -params-file}, because Nextflow does not persist those in the
 * history/cache that nf-diff reads.
 */
@Slf4j
@CompileStatic
class CommandParams {

    /** Source of a resolved parameter value. */
    static final String SRC_CLI = 'CLI'
    /** From a {@code -params-file} (JSON/YAML). */
    static final String SRC_FILE = 'file'
    /** Defined in both a params-file and on the command line; the CLI value wins. */
    static final String SRC_BOTH = 'CLI+file'

    /**
     * A resolved view of a run's parameters: the merged flag/value map plus,
     * for each flag, where its value came from. This closes the blind spot of
     * {@link #parse} — which only sees literal command-line flags — by folding
     * in the contents of any {@code -params-file} the command referenced.
     */
    @CompileStatic
    static class Resolved {
        /** Ordered {@code flag -> value}: params-file entries first, then CLI. */
        Map<String,String> values = new LinkedHashMap<String,String>()
        /** {@code flag -> } one of {@link #SRC_CLI}, {@link #SRC_FILE}, {@link #SRC_BOTH}. */
        Map<String,String> sources = new LinkedHashMap<String,String>()
    }

    /**
     * Resolve the effective parameters of a launch command, merging any
     * {@code -params-file} contents with the literal command-line flags.
     *
     * <p>Nextflow's precedence applies: a value given on the command line
     * overrides the same key in the params-file, so CLI flags are layered on
     * top and win. Each entry is tagged with its {@link Resolved#sources source}
     * so the report can show whether a value came from the command line, a
     * params-file, or both.
     *
     * <p>The params-file is read from disk <em>now</em>, resolved relative to
     * {@code baseDir} when the recorded path is relative. Nextflow does not
     * persist params-file contents in its run history, so this reflects the
     * file's current state — if it has changed or moved since the run, the
     * values shown are best-effort. An unreadable or unparseable file is
     * skipped rather than treated as an error.
     *
     * @param command the recorded launch command
     * @param baseDir project directory used to resolve a relative params-file
     *                path (may be {@code null}, in which case only absolute
     *                paths are read)
     */
    static Resolved resolve(String command, Path baseDir = null) {
        final result = new Resolved()
        final cli = parse(command)

        // 1) params-file first, so CLI flags below can override its values.
        final pfPath = cli.get('-params-file')
        if( pfPath != null && pfPath != 'true' ) {
            loadParamsFile(pfPath, baseDir).each { String k, String v ->
                result.values[k] = v
                result.sources[k] = SRC_FILE
            }
        }

        // 2) literal command-line flags (options + params); these win on conflict.
        cli.each { String k, String v ->
            result.sources[k] = result.values.containsKey(k) ? SRC_BOTH : SRC_CLI
            result.values[k] = v
        }
        return result
    }

    /**
     * Read a {@code -params-file} and flatten it into {@code --flag -> value}
     * entries (nested maps become dotted keys, e.g. {@code --genome.build}).
     * Returns an empty map when the file is missing, unreadable or unparseable.
     */
    private static Map<String,String> loadParamsFile(String path, Path baseDir) {
        final out = new LinkedHashMap<String,String>()
        final file = resolveParamsPath(path, baseDir)
        if( file == null || !Files.isReadable(file) ) {
            log.debug "nf-diff: params-file '${path}' not readable (resolved: ${file}); skipping"
            return out
        }
        try {
            final text = new String(Files.readAllBytes(file), 'UTF-8')
            flatten('', parseStructured(text, file.toString()), out)
        }
        catch( Exception e ) {
            log.warn "nf-diff: could not parse params-file '${file}': ${e.message}"
        }
        return out
    }

    /** Resolve a params-file path against {@code baseDir} when it is relative. */
    private static Path resolveParamsPath(String path, Path baseDir) {
        final p = Paths.get(path)
        if( p.isAbsolute() || baseDir == null )
            return p
        return baseDir.resolve(p)
    }

    /** Parse params-file text as YAML or JSON, guided by extension then content. */
    private static Object parseStructured(String text, String name) {
        final lower = name.toLowerCase()
        if( lower.endsWith('.yml') || lower.endsWith('.yaml') )
            return new Yaml().load(text)
        if( lower.endsWith('.json') )
            return new JsonSlurper().parseText(text)
        // unknown extension: sniff the content (JSON is a strict subset of YAML,
        // but JsonSlurper gives cleaner types for the common JSON case).
        final trimmed = text.trim()
        if( trimmed.startsWith('{') || trimmed.startsWith('[') )
            return new JsonSlurper().parseText(text)
        return new Yaml().load(text)
    }

    /**
     * Recursively flatten a parsed params structure into {@code --dotted.key}
     * entries. Maps recurse; scalars and lists are leaves rendered as strings.
     */
    private static void flatten(String prefix, Object node, Map<String,String> out) {
        if( node instanceof Map ) {
            ((Map) node).each { Object k, Object v ->
                final key = prefix ? "${prefix}.${k}".toString() : k.toString()
                flatten(key, v, out)
            }
        }
        else if( prefix ) {
            out["--${prefix}".toString()] = (node == null) ? 'null' : node.toString()
        }
    }

    /**
     * Parse the flags out of a launch command. Keys retain their leading dashes
     * so pipeline params ({@code --input}) stay distinguishable from Nextflow
     * options ({@code -profile}). The value is the token following the flag; a
     * flag with no value (or immediately followed by another flag) is treated
     * as a boolean {@code 'true'}. Insertion order follows the command line.
     */
    static Map<String,String> parse(String command) {
        final out = new LinkedHashMap<String,String>()
        if( !command )
            return out
        final tokens = tokenize(command)
        int i = 0
        while( i < tokens.size() ) {
            final tok = tokens[i]
            if( isFlag(tok) ) {
                final eq = tok.indexOf('=')
                if( eq > 0 ) {
                    out[tok.substring(0, eq)] = tok.substring(eq + 1)
                }
                else {
                    final next = (i + 1 < tokens.size()) ? tokens[i + 1] : null
                    if( next != null && !isFlag(next) ) {
                        out[tok] = next
                        i++
                    }
                    else {
                        out[tok] = 'true'
                    }
                }
            }
            i++
        }
        return out
    }

    /** True for a pipeline parameter (double-dash), false for a Nextflow option. */
    static boolean isPipelineParam(String key) {
        return key != null && key.startsWith('--')
    }

    /** A token is a flag when it starts with '-' and is not a bare '-' or '--'. */
    private static boolean isFlag(String tok) {
        return tok.length() > 1 && tok.charAt(0) == ('-' as char) && tok != '--'
    }

    /**
     * Split a command line into tokens, honouring single and double quotes so a
     * quoted value containing spaces stays intact. Backslash escaping is not
     * handled — recorded launch commands rarely need it.
     */
    private static List<String> tokenize(String command) {
        final tokens = new ArrayList<String>()
        final sb = new StringBuilder()
        char quote = 0
        boolean inToken = false
        for( int i = 0; i < command.length(); i++ ) {
            final char c = command.charAt(i)
            if( quote != 0 ) {
                if( c == quote )
                    quote = 0
                else
                    sb.append(c)
                inToken = true
            }
            else if( c == ('"' as char) || c == ("'" as char) ) {
                quote = c
                inToken = true
            }
            else if( Character.isWhitespace(c) ) {
                if( inToken ) {
                    tokens.add(sb.toString())
                    sb.setLength(0)
                    inToken = false
                }
            }
            else {
                sb.append(c)
                inToken = true
            }
        }
        if( inToken )
            tokens.add(sb.toString())
        return tokens
    }
}
