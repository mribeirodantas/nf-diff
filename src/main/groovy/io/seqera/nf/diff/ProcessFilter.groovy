package io.seqera.nf.diff

import java.util.regex.Pattern

import groovy.transform.CompileStatic

/**
 * Decides whether a process (and therefore its tasks) should be included in a
 * comparison, based on {@code --only} / {@code --exclude} glob patterns.
 *
 * <p>Globs are matched against the full process name using {@code *} (any run
 * of characters, including {@code :} scope separators) and {@code ?} (a single
 * character). A process is included when it matches at least one {@code only}
 * pattern (or when {@code only} is empty) and no {@code exclude} pattern.
 */
@CompileStatic
class ProcessFilter {

    private final List<Pattern> only
    private final List<Pattern> exclude

    private ProcessFilter(List<Pattern> only, List<Pattern> exclude) {
        this.only = only
        this.exclude = exclude
    }

    /** Build a filter from raw glob strings; empty lists mean "no constraint". */
    static ProcessFilter of(List<String> onlyGlobs, List<String> excludeGlobs) {
        return new ProcessFilter(
                (onlyGlobs ?: []).collect { String g -> compile(g) },
                (excludeGlobs ?: []).collect { String g -> compile(g) } )
    }

    /** True when no include/exclude constraints are configured. */
    boolean isPassThrough() {
        return only.isEmpty() && exclude.isEmpty()
    }

    /** Whether {@code process} survives the include/exclude rules. */
    boolean accepts(String process) {
        final name = process ?: ''
        if( !only.isEmpty() && !only.any { Pattern p -> p.matcher(name).matches() } )
            return false
        if( exclude.any { Pattern p -> p.matcher(name).matches() } )
            return false
        return true
    }

    /** Compile a glob (only {@code *} and {@code ?} are special) into a regex. */
    private static Pattern compile(String glob) {
        final sb = new StringBuilder()
        glob.each { String ch ->
            switch( ch ) {
                case '*': sb << '.*'; break
                case '?': sb << '.'; break
                default:  sb << Pattern.quote(ch)
            }
        }
        return Pattern.compile(sb.toString())
    }
}
