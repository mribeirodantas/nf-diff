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

import spock.lang.Specification

class NumericToleranceTest extends Specification {

    def 'with tolerance 1e-6: "#a" vs "#b" -> #equal'() {
        expect:
        NumericTolerance.of(1e-6d).test(a, b) == equal

        where:
        a                              | b                              | equal
        'gene\t0.30000000000000004'    | 'gene\t0.3'                    | true
        'x=1.0000001'                  | 'x=1.0000002'                  | true    // relative: within 1e-6 * 1.0000002
        'x=1000000.0'                  | 'x=1000000.5'                  | true    // relative for large values
        'x=1000000.0'                  | 'x=1000002.0'                  | false
        'p=0.0000001'                  | 'p=0.0000009'                  | true    // absolute below 1
        'p=0.001'                      | 'p=0.002'                      | false
        'mean 1.5e-3 sd 2e-4'          | 'mean 1.5000001e-3 sd 2.0e-4'  | true    // scientific notation
        'a\t1\t2'                      | 'a\t1\t2\t3'                   | false   // different number count
        'chr1\t100'                    | 'chr2\t100'                    | false   // number inside an identifier
        'sample_01\t5'                 | 'sample_02\t5'                 | false
        'gene\t5.0'                    | 'GENE\t5.0'                    | false   // text must match exactly
        'v=-0.5'                       | 'v=-0.5000000001'              | true    // signs
        'v=-0.5'                       | 'v=0.5'                        | false
        'nan'                          | 'nan'                          | true
    }

    def 'no tolerance means exact comparison'() {
        expect:
        NumericTolerance.of(0d) == null
        NumericTolerance.of(-1d) == null
    }

    def 'close() is relative above 1 and absolute below'() {
        given:
        def t = NumericTolerance.of(0.01d)

        expect:
        t.close(100d, 100.9d)
        !t.close(100d, 101.5d)
        t.close(0.001d, 0.009d)
        !t.close(0.0d, 0.02d)
        !t.close(Double.NaN, Double.NaN)
    }
}
