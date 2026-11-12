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

import groovy.transform.CompileStatic

/**
 * Detects processes that were renamed between two runs (e.g. {@code EXAMPLEFOO}
 * &#8594; {@code EXAMPLE_FOO}) rather than genuinely removed and added.
 *
 * <p>A name alone cannot tell a rename from a removal plus an unrelated
 * addition, so only processes present in exactly one run are considered, and
 * each removed/added pair is scored on what the process actually did:
 * <ul>
 *   <li><b>script</b> &#8212; the rendered task scripts, paired task by task
 *       (by the task-name suffix, e.g. {@code (sample1)}) and compared line by
 *       line after the process names themselves are masked out. This is the
 *       primary evidence: a rename does not change what a process runs;</li>
 *   <li><b>tags</b> &#8212; the set of task tags;</li>
 *   <li><b>task count</b>;</li>
 *   <li><b>software</b> &#8212; container image(s) and Conda spec(s);</li>
 *   <li><b>name</b> &#8212; similarity of the simple (last {@code :} segment)
 *       names, ignoring case and punctuation.</li>
 * </ul>
 * A pair is a rename when its scripts are identical and at least one other
 * signal agrees, or when its scripts are near-identical (&#8805; 80%), its
 * names are similar, and another signal agrees. Requiring a similar name for
 * the near-identical case keeps processes that share boilerplate (e.g. nf-core
 * {@code versions.yml} blocks) such as {@code SAMTOOLS_SORT} and
 * {@code SAMTOOLS_INDEX} apart. Without recorded scripts, a rename needs a
 * similar name, the same task count and matching tags. Candidates are paired
 * best-score first, each process used at most once.
 */
@CompileStatic
class ProcessRenameDetector {

    /** Script similarity treated as "identical" (tolerates float rounding only). */
    static final double IDENTICAL_SCRIPT = 0.999d
    /** Minimum script similarity for a rename that also edited the script. */
    static final double SIMILAR_SCRIPT = 0.8d
    /** Minimum name similarity for the similar-script and no-script cases. */
    static final double SIMILAR_NAME = 0.8d
    /** Cap on task pairs whose scripts are compared, per candidate pair. */
    static final int MAX_SCRIPT_PAIRS = 200

    /** A detected rename and the evidence behind it. */
    @CompileStatic
    static class Rename {
        /** Process name in run A. */
        String from
        /** Process name in run B. */
        String to
        /** Human-readable reasons, strongest first. */
        List<String> evidence = []
        /** Ranking score; higher is a stronger match. */
        double score
    }

    /** Detect renames from run A's process names to run B's. */
    List<Rename> detect(RunSnapshot a, RunSnapshot b) {
        final byProcA = tasksByProcess(a)
        final byProcB = tasksByProcess(b)
        final removed = byProcA.keySet().findAll { String p -> !byProcB.containsKey(p) }
        final added = byProcB.keySet().findAll { String p -> !byProcA.containsKey(p) }
        if( removed.isEmpty() || added.isEmpty() )
            return []

        final candidates = new ArrayList<Rename>()
        removed.each { String from ->
            added.each { String to ->
                final r = evaluate(from, byProcA.get(from), to, byProcB.get(to))
                if( r != null )
                    candidates << r
            }
        }
        // Best score first; ties broken by name for a stable result.
        candidates.sort { Rename x, Rename y ->
            final byScore = y.score <=> x.score
            byScore != 0 ? byScore : ((x.from + '\u0000' + x.to) <=> (y.from + '\u0000' + y.to))
        }
        final usedFrom = new HashSet<String>()
        final usedTo = new HashSet<String>()
        final out = new ArrayList<Rename>()
        candidates.each { Rename r ->
            if( usedFrom.contains(r.from) || usedTo.contains(r.to) )
                return
            usedFrom << r.from
            usedTo << r.to
            out << r
        }
        out.sort { Rename r -> r.to }
        return out
    }

    /** Score one removed/added pair, or {@code null} if it is not a rename. */
    private static Rename evaluate(String from, List<TaskInfo> tasksA, String to, List<TaskInfo> tasksB) {
        final evidence = new ArrayList<String>()

        final script = scriptSimilarity(from, tasksA, to, tasksB)
        final name = nameSimilarity(from, to)
        final tags = tagSimilarity(tasksA, tasksB)
        final countSame = tasksA.size() == tasksB.size()
        final software = softwareSame(tasksA, tasksB)

        // Corroborating signals, independent of the script.
        int corroborating = 0
        if( tags != null && tags >= 0.5d ) {
            corroborating++
            evidence << (tags == 1.0d ? "same tags (${tagSet(tasksA).size()})".toString() : "${pct(tags)} of tags shared".toString())
        }
        if( countSame ) {
            corroborating++
            evidence << "same task count (${tasksA.size()})".toString()
        }
        if( software == Boolean.TRUE ) {
            corroborating++
            evidence << 'same container/Conda environment'
        }
        final nameSimilar = name >= SIMILAR_NAME
        if( nameSimilar ) {
            corroborating++
            evidence << (name == 1.0d ? 'names differ only in case, punctuation or scope' : 'similar names')
        }

        boolean rename
        if( script != null ) {
            if( script.score >= IDENTICAL_SCRIPT ) {
                rename = corroborating >= 1
                evidence.add(0, "identical script in ${script.pairs} matched task(s)".toString())
            }
            else if( script.score >= SIMILAR_SCRIPT ) {
                // Name plus one more signal, so shared boilerplate alone can't pair unrelated processes.
                rename = nameSimilar && corroborating >= 2
                evidence.add(0, "scripts ${pct(script.score)} similar across ${script.pairs} matched task(s)".toString())
            }
            else
                rename = false
        }
        else {
            // No scripts to compare: demand name, count and tags all agree.
            rename = nameSimilar && countSame && (tags == null || tags >= 0.9d) && software != Boolean.FALSE
        }
        if( !rename )
            return null

        final score = 2.0d * (script?.score ?: 0.0d) + name + (tags ?: 0.0d) + (countSame ? 1.0d : 0.0d) + (software == Boolean.TRUE ? 1.0d : 0.0d)
        return new Rename(from: from, to: to, evidence: evidence, score: score)
    }

    // ------------------------------------------------------------- script

    @CompileStatic
    private static class ScriptScore {
        double score
        int pairs
    }

    /**
     * Mean line similarity of the scripts of tasks paired across the two
     * processes, or {@code null} when no pair has a script on both sides.
     * Tasks are paired by their name with the process prefix stripped (e.g.
     * {@code " (sample1)"}); if none pair that way, distinct scripts are
     * compared as sets instead.
     */
    private static ScriptScore scriptSimilarity(String from, List<TaskInfo> tasksA, String to, List<TaskInfo> tasksB) {
        final suffixA = bySuffix(from, tasksA)
        final suffixB = bySuffix(to, tasksB)
        final List<List<String>> pairs = []
        for( String key : suffixA.keySet() ) {
            final tb = suffixB.get(key)
            if( tb == null )
                continue
            final sa = normalizeScript(suffixA.get(key).script, from)
            final sb = normalizeScript(tb.script, to)
            if( sa == null || sb == null )
                continue
            pairs << [sa, sb]
            if( pairs.size() >= MAX_SCRIPT_PAIRS )
                break
        }
        if( !pairs.isEmpty() ) {
            double total = 0.0d
            pairs.each { List<String> p -> total += lineSimilarity(p[0], p[1]) }
            return new ScriptScore(score: total / pairs.size(), pairs: pairs.size())
        }

        // No pairable tasks: compare the distinct scripts as sets.
        final setA = tasksA.collect { TaskInfo t -> normalizeScript(t.script, from) }.findAll { it != null } as Set<String>
        final setB = tasksB.collect { TaskInfo t -> normalizeScript(t.script, to) }.findAll { it != null } as Set<String>
        if( setA.isEmpty() || setB.isEmpty() )
            return null
        final inter = setA.findAll { String s -> setB.contains(s) }.size()
        final union = (setA + setB).size()
        return new ScriptScore(score: inter / (double) union, pairs: Math.min(setA.size(), setB.size()))
    }

    /** Final attempt of each task keyed by its name with the process prefix removed. */
    private static Map<String,TaskInfo> bySuffix(String process, List<TaskInfo> tasks) {
        final map = new LinkedHashMap<String,TaskInfo>()
        tasks.each { TaskInfo t ->
            final key = suffixKey(process, t)
            if( key == null )
                return
            final prev = map.get(key)
            if( prev == null || (t.numeric('attempt') ?: 0L) >= (prev.numeric('attempt') ?: 0L) )
                map.put(key, t)
        }
        return map
    }

    private static String suffixKey(String process, TaskInfo t) {
        if( t.name && t.name.startsWith(process) )
            return t.name.substring(process.length())
        return t.tag ? " (${t.tag})".toString() : null
    }

    /**
     * Trimmed, non-blank script lines with both the qualified and simple
     * process name masked, so a script that echoes {@code task.process}
     * (e.g. nf-core {@code versions.yml}) still compares equal across a rename.
     */
    private static String normalizeScript(String script, String process) {
        if( script == null || script.trim().isEmpty() || script.trim() == '-' )
            return null
        String s = script.replace(process, '\u0000PROC\u0000')
        final simple = simpleName(process)
        if( simple != process )
            s = s.replace(simple, '\u0000PROC\u0000')
        return s.readLines().collect { String l -> l.trim() }.findAll { String l -> !l.isEmpty() }.join('\n')
    }

    /** Dice coefficient over the two scripts' line multisets. */
    static double lineSimilarity(String a, String b) {
        if( a == b )
            return 1.0d
        final la = a.split('\n')
        final lb = b.split('\n')
        final counts = new HashMap<String,Integer>()
        la.each { String l -> counts.put(l, (counts.get(l) ?: 0) + 1) }
        int common = 0
        lb.each { String l ->
            final n = counts.get(l) ?: 0
            if( n > 0 ) {
                common++
                counts.put(l, n - 1)
            }
        }
        return (2.0d * common) / (la.length + lb.length)
    }

    // ------------------------------------------------------------- other signals

    /** Simple-name similarity ignoring case, punctuation and workflow scope. */
    static double nameSimilarity(String a, String b) {
        final na = simpleName(a).toLowerCase().replaceAll(/[^a-z0-9]/, '')
        final nb = simpleName(b).toLowerCase().replaceAll(/[^a-z0-9]/, '')
        if( na == nb )
            return 1.0d
        final max = Math.max(na.length(), nb.length())
        return max == 0 ? 0.0d : 1.0d - levenshtein(na, nb) / (double) max
    }

    private static String simpleName(String process) {
        final i = process.lastIndexOf(':')
        return i >= 0 ? process.substring(i + 1) : process
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1]
        int[] cur = new int[b.length() + 1]
        for( int j = 0; j <= b.length(); j++ )
            prev[j] = j
        for( int i = 1; i <= a.length(); i++ ) {
            cur[0] = i
            for( int j = 1; j <= b.length(); j++ ) {
                final cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            final tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length()]
    }

    /** Jaccard similarity of the tag sets, or {@code null} when neither side has tags. */
    private static Double tagSimilarity(List<TaskInfo> a, List<TaskInfo> b) {
        final ta = tagSet(a)
        final tb = tagSet(b)
        if( ta.isEmpty() && tb.isEmpty() )
            return null
        final inter = ta.findAll { String t -> tb.contains(t) }.size()
        return inter / (double) (ta + tb).size()
    }

    private static Set<String> tagSet(List<TaskInfo> tasks) {
        return tasks.collect { TaskInfo t -> t.tag }.findAll { String t -> t } as Set<String>
    }

    /** Whether both sides used the same software, or {@code null} when neither recorded any. */
    private static Boolean softwareSame(List<TaskInfo> a, List<TaskInfo> b) {
        final sa = softwareSet(a)
        final sb = softwareSet(b)
        if( sa.isEmpty() && sb.isEmpty() )
            return null
        return sa == sb
    }

    private static Set<String> softwareSet(List<TaskInfo> tasks) {
        final out = new TreeSet<String>()
        tasks.each { TaskInfo t ->
            final c = t.container?.trim()
            if( c && c != '-' )
                out << ('container:' + c)
            final conda = t.display?.get('conda')?.trim()
            if( conda && conda != '-' )
                out << ('conda:' + conda)
        }
        return out
    }

    private static Map<String,List<TaskInfo>> tasksByProcess(RunSnapshot snap) {
        final map = new TreeMap<String,List<TaskInfo>>()
        snap.tasks.each { TaskInfo t ->
            map.computeIfAbsent(t.process ?: '(unknown)', { String k -> new ArrayList<TaskInfo>() } as java.util.function.Function<String,List<TaskInfo>>).add(t)
        }
        return map
    }

    private static String pct(double v) {
        return "${Math.round(v * 100)}%".toString()
    }
}
