package io.seqera.nf.diff

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import io.seqera.nf.diff.DiffResult.Kind
import io.seqera.nf.diff.DiffResult.OutputDiff
import io.seqera.nf.diff.DiffResult.OutputFileDiff

/**
 * Compares the output files a task wrote to its work directory across two runs.
 *
 * Nextflow stages a task's declared inputs into its work directory as symbolic
 * links (pointing at upstream work dirs or the original input data) and writes
 * a fixed set of control files ({@code .command.*}, {@code .exitcode}). Real,
 * non-symlink files that are not control files are therefore the task's own
 * outputs (plus any scratch it left behind). This comparator enumerates those,
 * keyed by their path relative to the work dir, and classifies each as
 * added / removed / changed / unchanged by comparing file size first and then,
 * only when sizes match, a streamed SHA-256 of the contents.
 *
 * Everything is read-only local I/O — no task is ever re-executed. When a work
 * directory is missing (cleaned up, remote, or never materialised) the task's
 * outputs simply cannot be compared and a note records why.
 */
@Slf4j
@CompileStatic
class OutputComparator {

    /**
     * Control files Nextflow writes into every task work directory. These are
     * bookkeeping, not task outputs, so they are excluded from the comparison.
     */
    static final Set<String> CONTROL_FILES = [
            '.command.begin', '.command.env', '.command.err', '.command.log',
            '.command.out', '.command.run', '.command.sh', '.command.stub',
            '.command.trace', '.exitcode',
    ] as Set

    /** Length of the short SHA-256 prefix kept for display. */
    private static final int SHORT_HASH_LEN = 12

    /**
     * Maximum file size (bytes) to hash when two files share the same size.
     * {@code 0} means no limit (hash any size). Files above the cap with equal
     * sizes are left content-unverified rather than read in full.
     */
    private final long maxBytes

    OutputComparator(long maxBytes = 0L) {
        this.maxBytes = maxBytes
    }

    /** Compare the outputs of two matched tasks. */
    OutputDiff compare(TaskInfo a, TaskInfo b) {
        final od = new OutputDiff(
                taskKey  : (a?.matchKey()) ?: (b?.matchKey()),
                process  : (a?.process) ?: (b?.process),
                workdirA : a?.workdir,
                workdirB : b?.workdir )

        final dirA = toDir(a?.workdir)
        final dirB = toDir(b?.workdir)
        od.availableA = dirA != null
        od.availableB = dirB != null

        if( dirA == null || dirB == null ) {
            od.note = unavailableNote(od)
            return od
        }

        // A cached task in Run B points at the same physical work dir that
        // originally produced it, so the outputs are identical by construction.
        if( dirA.toAbsolutePath().normalize() == dirB.toAbsolutePath().normalize() ) {
            od.sameWorkdir = true
            return od
        }

        final filesA = enumerate(dirA)
        final filesB = enumerate(dirB)
        final paths = new TreeSet<String>()
        paths.addAll(filesA.keySet())
        paths.addAll(filesB.keySet())

        paths.each { String rel ->
            od.files << compareFile(rel, dirA, dirB, filesA.get(rel), filesB.get(rel))
        }
        return od
    }

    /** Resolve a work dir string to a readable directory, or null. */
    private static Path toDir(String workdir) {
        if( !workdir )
            return null
        try {
            final p = Paths.get(workdir)
            return Files.isDirectory(p) ? p : null
        }
        catch( Exception e ) {
            log.debug "nf-diff: work dir '${workdir}' is not a usable local path: ${e.message}"
            return null
        }
    }

    private static String unavailableNote(OutputDiff od) {
        if( !od.availableA && !od.availableB )
            return 'Neither work directory is available locally, so outputs cannot be compared.'
        final missing = !od.availableA ? 'A' : 'B'
        return "Run ${missing}'s work directory is not available locally, so outputs cannot be compared."
    }

    /**
     * Enumerate the task's output files under {@code dir}, mapped by their path
     * relative to {@code dir} to their size in bytes. Symlinks (staged inputs)
     * and Nextflow control files are skipped.
     */
    private static Map<String,Long> enumerate(Path dir) {
        final out = new LinkedHashMap<String,Long>()
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    // Skip staged inputs (symlinks) and directory entries.
                    if( attrs.isSymbolicLink() || Files.isSymbolicLink(file) || !attrs.isRegularFile() )
                        return FileVisitResult.CONTINUE
                    final rel = dir.relativize(file).toString()
                    // Control files live at the work-dir root; match on the leaf name.
                    if( CONTROL_FILES.contains(file.fileName.toString()) )
                        return FileVisitResult.CONTINUE
                    out.put(rel, attrs.size())
                    return FileVisitResult.CONTINUE
                }

                @Override
                FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug "nf-diff: could not read '${file}': ${exc.message}"
                    return FileVisitResult.CONTINUE
                }
            })
        }
        catch( Exception e ) {
            log.warn "nf-diff: failed to enumerate outputs under '${dir}': ${e.message}"
        }
        return out
    }

    private OutputFileDiff compareFile(String rel, Path dirA, Path dirB, Long sizeA, Long sizeB) {
        final fd = new OutputFileDiff(path: rel, sizeA: sizeA, sizeB: sizeB)

        if( sizeA == null ) {
            fd.kind = Kind.ADDED
            return fd
        }
        if( sizeB == null ) {
            fd.kind = Kind.REMOVED
            return fd
        }
        if( sizeA != sizeB ) {
            // Different sizes are conclusively different; no need to hash.
            fd.kind = Kind.CHANGED
            return fd
        }

        // Same size: only content hashing can tell them apart.
        if( maxBytes > 0 && sizeA > maxBytes ) {
            fd.kind = Kind.UNCHANGED
            fd.verified = false
            fd.note = "same size (${sizeA} bytes); content not verified (exceeds --outputs-max-bytes)".toString()
            return fd
        }

        fd.hashA = sha256(dirA.resolve(rel))
        fd.hashB = sha256(dirB.resolve(rel))
        if( fd.hashA == null || fd.hashB == null ) {
            fd.kind = Kind.UNCHANGED
            fd.verified = false
            fd.note = 'same size; content hash could not be computed'
            return fd
        }
        fd.kind = (fd.hashA == fd.hashB) ? Kind.UNCHANGED : Kind.CHANGED
        return fd
    }

    /** Streamed SHA-256 of a file, returned as a short hex prefix, or null on error. */
    private static String sha256(Path file) {
        try {
            final md = MessageDigest.getInstance('SHA-256')
            final buf = new byte[64 * 1024]
            file.newInputStream().withCloseable { InputStream is ->
                int n
                while( (n = is.read(buf)) != -1 )
                    md.update(buf, 0, n)
            }
            final hex = new StringBuilder()
            md.digest().each { byte x -> hex << String.format('%02x', x) }
            return hex.toString().substring(0, SHORT_HASH_LEN)
        }
        catch( Exception e ) {
            log.debug "nf-diff: could not hash '${file}': ${e.message}"
            return null
        }
    }
}
