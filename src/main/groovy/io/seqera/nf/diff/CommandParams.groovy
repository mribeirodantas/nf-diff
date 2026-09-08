package io.seqera.nf.diff

import groovy.transform.CompileStatic

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
@CompileStatic
class CommandParams {

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
