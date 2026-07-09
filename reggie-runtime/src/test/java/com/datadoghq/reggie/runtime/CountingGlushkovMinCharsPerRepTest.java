/*
 * Copyright 2026-Present Datadog, Inc.
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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.codegen.automaton.GlushkovAutomaton;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Direct unit tests for {@link CountingGlushkovRuntime#minCharsPerRep} — the admissible-lower-bound
 * BFS used to prune impossible restarts in {@code findFrom}/{@code findBoundsFrom}. Exercised
 * end-to-end elsewhere ({@code CountingGlushkovRuntimeTest}), but never directly at the boundary
 * cases (nullable body, multi-step BFS) that its own branches guard.
 */
class CountingGlushkovMinCharsPerRepTest {

  private static GlushkovAutomaton glushkov(String bodyPattern) throws Exception {
    NFA nfa = new ThompsonBuilder().build(new RegexParser().parse(bodyPattern), 0);
    GlushkovAutomaton g = GlushkovAutomaton.from(nfa);
    assertNotNull(g, "body must be Glushkov-eligible: " + bodyPattern);
    return g;
  }

  @Test
  void nullableBody_returnsZero() throws Exception {
    // "a?" accepts the empty string — no positive lower bound holds regardless of follow/accept.
    GlushkovAutomaton g = glushkov("a?");
    assertEquals(0, CountingGlushkovRuntime.minCharsPerRep(true, g.initial, g.accept, g.follow));
  }

  @Test
  void singleCharBody_returnsOne() throws Exception {
    // "a": initial and accept are the same single position — the (initial & accept) != 0 fast
    // path, not the BFS loop.
    GlushkovAutomaton g = glushkov("a");
    assertEquals(1, CountingGlushkovRuntime.minCharsPerRep(false, g.initial, g.accept, g.follow));
  }

  @Test
  void multiCharBody_walksBfsToAccept() throws Exception {
    // "abc": initial (position for 'a') and accept (position for 'c') are disjoint, forcing the
    // BFS loop to actually walk the follow relation — exercises the loop body and the
    // frontier-hits-accept return path (as opposed to either fast path above).
    GlushkovAutomaton g = glushkov("abc");
    assertEquals(3, CountingGlushkovRuntime.minCharsPerRep(false, g.initial, g.accept, g.follow));
  }

  @Test
  void disconnectedFollowGraph_fallsBackToOne() throws Exception {
    // Synthetic follow relation with no edges at all: the BFS frontier collapses to empty on the
    // very first expand() call, hitting the "frontier == 0L -> return 1" dead-end path — not
    // constructible from a real regex body (every real Glushkov position is on some path to
    // accept), so built directly from raw bitmasks.
    long initial = 0b001L; // position 0
    long accept = 0b100L; // position 2 (unreachable from position 0)
    long[] follow = new long[] {0L, 0L, 0L}; // no position follows any other
    assertEquals(1, CountingGlushkovRuntime.minCharsPerRep(false, initial, accept, follow));
  }
}
