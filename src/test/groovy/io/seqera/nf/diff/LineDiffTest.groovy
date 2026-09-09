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

class LineDiffTest extends Specification {

    def 'identical input yields only EQUAL ops'() {
        given:
        def a = ['one', 'two', 'three']

        when:
        def ops = LineDiff.diff(a, a)

        then:
        ops.size() == 3
        ops.every { it.type == LineDiff.Type.EQUAL }
    }

    def 'a changed middle line becomes DELETE + INSERT'() {
        given:
        def a = ['head', 'old', 'tail']
        def b = ['head', 'new', 'tail']

        when:
        def ops = LineDiff.diff(a, b)
        def types = ops.collect { it.type }

        then:
        // head equal, old deleted, new inserted, tail equal (order of del/ins may vary)
        types.count { it == LineDiff.Type.EQUAL } == 2
        types.count { it == LineDiff.Type.DELETE } == 1
        types.count { it == LineDiff.Type.INSERT } == 1
        ops.find { it.type == LineDiff.Type.DELETE }.text == 'old'
        ops.find { it.type == LineDiff.Type.INSERT }.text == 'new'
    }

    def 'pure insertion is all INSERT against empty'() {
        expect:
        LineDiff.diff([], ['x', 'y']).collect { it.type } ==
                [LineDiff.Type.INSERT, LineDiff.Type.INSERT]
    }

    def 'reconstructs both sides from ops'() {
        given:
        def a = ['a', 'b', 'c', 'd']
        def b = ['a', 'x', 'c', 'e', 'f']

        when:
        def ops = LineDiff.diff(a, b)
        def rebuiltA = ops.findAll { it.type != LineDiff.Type.INSERT }.collect { it.text }
        def rebuiltB = ops.findAll { it.type != LineDiff.Type.DELETE }.collect { it.text }

        then:
        rebuiltA == a
        rebuiltB == b
    }
}
