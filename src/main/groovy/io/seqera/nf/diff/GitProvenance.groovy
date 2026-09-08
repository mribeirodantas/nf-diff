package io.seqera.nf.diff

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

/**
 * Inspects the git state of a working directory so the configuration layer can
 * warn when the tree it resolved config from has drifted from the git revision
 * a run was actually launched at.
 *
 * <p>{@link ConfigLoader} rebuilds each run's effective configuration from the
 * config files <em>as they exist now</em>. If the checkout moved since a run
 * (launched at commit A, re-run at commit B, then compared against commit C),
 * both runs' configs are resolved against whatever is checked out now — silently
 * masking config differences that were actually driven by code changes between
 * those revisions. This class supplies the current HEAD and working-tree
 * cleanliness so {@link RunComparator} can flag that caveat.
 *
 * <p>It never throws: git being absent, the directory not being a repository, or
 * a command timing out all degrade to a non-repository {@link State}, so config
 * provenance simply reports "unknown" rather than breaking the diff.
 */
@Slf4j
@CompileStatic
class GitProvenance {

    /** The git state of a directory at inspection time. */
    @CompileStatic
    static class State {
        /** Full commit id of {@code HEAD}, or null when not a git work tree. */
        String headCommit
        /** True when tracked files have uncommitted changes. */
        boolean dirty
        /** True when the directory is inside a git work tree at all. */
        boolean isRepo() { headCommit != null }
    }

    /** Timeout (ms) for each git subprocess before we give up on it. */
    private static final long TIMEOUT_MS = 5000L

    /**
     * Inspect the git state of {@code dir}. Never throws — any failure (git not
     * installed, not a repository, timeout, non-zero exit) yields a non-repo
     * {@link State} so config provenance degrades gracefully to "unknown".
     */
    State inspect(Path dir) {
        final state = new State()
        if( dir == null || !Files.isDirectory(dir) )
            return state
        final head = run(dir, ['git', 'rev-parse', 'HEAD'])
        if( head == null )
            return state
        state.headCommit = head.trim()
        // Only tracked-file changes matter for config trustworthiness; untracked
        // files (build output, reports) do not affect the resolved config.
        final status = run(dir, ['git', 'status', '--porcelain', '--untracked-files=no'])
        state.dirty = status != null && !status.trim().isEmpty()
        return state
    }

    /**
     * Run a git command in {@code dir}, returning its stdout on a clean (zero)
     * exit or null on any error, non-zero exit, or timeout.
     */
    private static String run(Path dir, List<String> cmd) {
        Process proc = null
        try {
            final pb = new ProcessBuilder(cmd)
            pb.directory(dir.toFile())
            proc = pb.start()
            final out = proc.inputStream.getText('UTF-8')
            proc.errorStream?.getText('UTF-8')
            if( !proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS) ) {
                proc.destroyForcibly()
                log.debug "nf-diff: git command ${cmd} timed out in ${dir}"
                return null
            }
            return proc.exitValue() == 0 ? out : null
        }
        catch( Exception e ) {
            log.debug "nf-diff: git command ${cmd} failed in ${dir}: ${e.message}"
            proc?.destroyForcibly()
            return null
        }
    }
}
