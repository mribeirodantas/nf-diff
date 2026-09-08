package io.seqera.nf.diff

import groovy.transform.CompileStatic

/**
 * A minimal line-oriented diff based on the classic longest-common-subsequence
 * algorithm. Produces an ordered list of {@link Op}s (EQUAL / DELETE / INSERT)
 * suitable for rendering a side-by-side view. Kept dependency-free so the
 * renderer stays self-contained.
 */
@CompileStatic
class LineDiff {

    static enum Type { EQUAL, DELETE, INSERT }

    @CompileStatic
    static class Op {
        Type type
        String text
        Op(Type type, String text) {
            this.type = type
            this.text = text
        }
    }

    /** Compute the diff turning {@code a} into {@code b}. */
    static List<Op> diff(List<String> a, List<String> b) {
        final n = a.size()
        final m = b.size()

        // LCS length table
        final dp = new int[n + 1][m + 1]
        for( int i = n - 1; i >= 0; i-- ) {
            for( int j = m - 1; j >= 0; j-- ) {
                if( a.get(i) == b.get(j) )
                    dp[i][j] = dp[i + 1][j + 1] + 1
                else
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1])
            }
        }

        // backtrack into an ordered op list
        final ops = new ArrayList<Op>()
        int i = 0
        int j = 0
        while( i < n && j < m ) {
            if( a.get(i) == b.get(j) ) {
                ops.add(new Op(Type.EQUAL, a.get(i)))
                i++; j++
            }
            else if( dp[i + 1][j] >= dp[i][j + 1] ) {
                ops.add(new Op(Type.DELETE, a.get(i)))
                i++
            }
            else {
                ops.add(new Op(Type.INSERT, b.get(j)))
                j++
            }
        }
        while( i < n ) {
            ops.add(new Op(Type.DELETE, a.get(i)))
            i++
        }
        while( j < m ) {
            ops.add(new Op(Type.INSERT, b.get(j)))
            j++
        }
        return ops
    }
}
