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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Impl-plan Task 1.1/1.2 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): validates {@link
 * LaurikariTagNumbering} + {@link LaurikariCaptureNfaStep} against {@link PikeVMMatcher} — the same
 * oracle-comparison style {@code LaurikariPhase05Test} already uses for its hand-derived 2-3-tag
 * checkpoint, generalized here to the full {@code 2 * (groupCount + 1)} tag count derived
 * automatically from an arbitrary pattern's real {@code groupCount}, instead of 2-3 hand-picked
 * tags on 3 fixed patterns.
 *
 * <p>Scope, per the impl plan: anchored whole-input match only (mirrors {@code
 * PikeVMMatcher.match()}/{@code Matcher.matches()} semantics) — no self-anchoring {@code find()}
 * localization (that composition is {@code LaurikariDfaMatcher}'s job, Task 1.4, explicitly out of
 * scope here). Patterns are restricted to concatenation/alternation/quantified capturing groups
 * with no lookaround, backreferences, or atomic groups (Task 1.3's eligibility gate is also out of
 * scope here — this test simply never constructs an ineligible pattern).
 */
class LaurikariCaptureStepTest {

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder().build(ast, groupCount);
  }

  private static final class Built {
    final LaurikariCaptureNfaStep step;
    final LaurikariDFACache cache;
    final int acceptId;

    Built(LaurikariCaptureNfaStep step, LaurikariDFACache cache, int acceptId) {
      this.step = step;
      this.cache = cache;
      this.acceptId = acceptId;
    }
  }

  private static Built build(String pattern, int groupCount) throws Exception {
    NFA nfa = nfa(pattern, groupCount);
    LaurikariCaptureNfaStep step = new LaurikariCaptureNfaStep(nfa, groupCount);
    LaurikariStepResult initial = step.initialClosure();
    int acceptId = nfa.getAcceptStates().iterator().next().id;
    LaurikariDFACache cache =
        new LaurikariDFACache(initial.states, initial.regs, new int[] {acceptId});
    return new Built(step, cache, acceptId);
  }

  /**
   * Drives {@code input} through {@code built.cache} (the production {@link LaurikariDFACache}
   * lazily-materializing/interning transitions produced by {@code built.step}) one character at a
   * time, and returns the absolute tag positions once every character is consumed, or {@code null}
   * if the accept state isn't live at the end (no whole-input match).
   */
  private static int[] runToEnd(Built built, String input) {
    int dfaState = 0;
    for (int i = 0; i < input.length(); i++) {
      int next = built.cache.lookupOrCompute(dfaState, input.charAt(i), built.step);
      if (next == LaurikariDFACache.DEAD) return null;
      if (next == LaurikariDFACache.FALLBACK) {
        fail("unexpected cache overflow for a small test pattern");
      }
      dfaState = next;
    }
    if (!built.cache.accepting[dfaState]) return null;
    int[] acceptRegs = built.cache.acceptRegs(dfaState);
    return built.step.absolutePositions(acceptRegs, input.length());
  }

  private static PikeVMMatcher pikeVm(String pattern, int groupCount) throws Exception {
    NFA nfa = nfa(pattern, groupCount);
    return new PikeVMMatcher(nfa, pattern);
  }

  /** Asserts every group's open/close tag matches {@code oracleResult}'s reported span exactly. */
  private static void assertCapturesMatchOracle(
      int[] absolute, MatchResult oracleResult, int groupCount, String input) {
    int[] expected = new int[2 * (groupCount + 1)];
    for (int g = 0; g <= groupCount; g++) {
      expected[2 * g] = oracleResult.start(g);
      expected[2 * g + 1] = oracleResult.end(g);
    }
    assertArrayEquals(expected, absolute, "capture tags diverged from PikeVMMatcher for " + input);
  }

  // --- (a) simple concatenation: (a)(b)
  // -----------------------------------------------------------

  @Test
  void concatenation_twoGroups_matchesPikeVM() throws Exception {
    String pattern = "(a)(b)";
    int groupCount = 2;
    Built built = build(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"ab"}) {
      MatchResult oracleResult = oracle.match(input);
      assertNotNull(oracleResult, "PikeVMMatcher must match " + input);
      int[] absolute = runToEnd(built, input);
      assertNotNull(absolute, "Laurikari driver must also match " + input);
      assertCapturesMatchOracle(absolute, oracleResult, groupCount, input);
    }
  }

  // --- (b) alternation with distinct capture groups per branch: (foo|bar)(baz) -------------------

  @Test
  void alternation_distinctBranches_matchesPikeVM() throws Exception {
    String pattern = "(foo|bar)(baz)";
    int groupCount = 2;
    Built built = build(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"foobaz", "barbaz"}) {
      MatchResult oracleResult = oracle.match(input);
      assertNotNull(oracleResult, "PikeVMMatcher must match " + input);
      int[] absolute = runToEnd(built, input);
      assertNotNull(absolute, "Laurikari driver must also match " + input);
      assertCapturesMatchOracle(absolute, oracleResult, groupCount, input);
    }
  }

  // --- (c) quantified group + optional group: (a+)(b)? -------------------------------------------

  @Test
  void quantifiedAndOptionalGroups_matchesPikeVM() throws Exception {
    String pattern = "(a+)(b)?";
    int groupCount = 2;
    Built built = build(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"a", "aaa", "ab", "aaab"}) {
      MatchResult oracleResult = oracle.match(input);
      assertNotNull(oracleResult, "PikeVMMatcher must match " + input);
      int[] absolute = runToEnd(built, input);
      assertNotNull(absolute, "Laurikari driver must also match " + input);
      assertCapturesMatchOracle(absolute, oracleResult, groupCount, input);
    }
  }

  // --- (d) nested/loop group re-set every iteration: (ab)+ (single group, no independent second
  // --- group, but exercises the loop-back re-set path with the general tag-numbering mechanism
  // --- instead of Phase 0.5's hand-picked tagSpecs)
  // -----------------------------------------------

  @Test
  void loopBodyGroup_reSetOnEveryIteration_matchesPikeVM() throws Exception {
    String pattern = "(ab)+";
    int groupCount = 1;
    Built built = build(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"ab", "abab", "ababab"}) {
      MatchResult oracleResult = oracle.match(input);
      assertNotNull(oracleResult, "PikeVMMatcher must match " + input);
      int[] absolute = runToEnd(built, input);
      assertNotNull(absolute, "Laurikari driver must also match " + input);
      assertCapturesMatchOracle(absolute, oracleResult, groupCount, input);
    }
  }
}
