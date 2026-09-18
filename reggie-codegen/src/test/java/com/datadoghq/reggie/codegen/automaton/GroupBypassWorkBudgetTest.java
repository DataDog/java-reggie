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
package com.datadoghq.reggie.codegen.automaton;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The group-bypass reachability BFS in SubsetConstructor.computeGroupsWithBypass runs once per
 * capturing group over the whole NFA — O(groups × states). Patterns with thousands of capture
 * groups (e.g. {@code (wa0|wb0)(wa1|wb1)...} × 6000) previously ran it completely uncharged: ~14s
 * per buildDFA call, past the total compile deadline, with no work-budget or wall-clock check ever
 * firing (2026-09-17, find-deadline-coverage-gaps). The BFS is now charged via chargeWork, so the
 * same determinization budget that bounds the subset-construction loops bounds this one too.
 */
class GroupBypassWorkBudgetTest {

  /**
   * 3000 alternation pairs = 6000 capture groups over a ~18k-state NFA: the bypass analysis alone
   * is ~850M charged units against the 200M default budget, so determinization must abort with
   * StateExplosionException (the standard "use an NFA strategy" signal) in seconds, not run the
   * full unchecked sweep.
   */
  @Test
  @Timeout(60)
  void manyGroupBypassAnalysisIsWorkBounded() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      sb.append("(wa").append(i).append("|wb").append(i).append(")");
    }
    RegexParser parser = new RegexParser();
    int groupCount = 6000;
    NFA nfa = new ThompsonBuilder().build(parser.parse(sb.toString()), groupCount);
    long t0 = System.nanoTime();
    assertThrows(StateExplosionException.class, () -> new SubsetConstructor().buildDFA(nfa, true));
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    assertTrue(
        elapsedMs < 15_000,
        "6000-group bypass analysis must abort on the work budget, took " + elapsedMs + "ms");
  }

  /**
   * Legitimate shapes are unaffected: a moderate group count over a moderate NFA charges only a few
   * hundred thousand units (dozens of groups × thousands of states × 8 units), far under the
   * budget, and determinization succeeds exactly as before.
   */
  @Test
  void moderateGroupCountsStillDeterminize() throws Exception {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      sb.append("(a").append(i).append("|b").append(i).append(")");
    }
    RegexParser parser = new RegexParser();
    NFA nfa = new ThompsonBuilder().build(parser.parse(sb.toString()), 12);
    assertNotNull(new SubsetConstructor().buildDFA(nfa, true));
  }
}
