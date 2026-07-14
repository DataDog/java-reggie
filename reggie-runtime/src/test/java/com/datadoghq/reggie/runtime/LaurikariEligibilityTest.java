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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Impl-plan Task 1.3 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): one rejecting case per
 * {@link LaurikariEligibility} exclusion reason, plus positive cases confirming ordinary capturing
 * patterns pass.
 */
class LaurikariEligibilityTest {

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder().build(ast, groupCount);
  }

  private static boolean eligible(String pattern, int groupCount) throws Exception {
    return LaurikariEligibility.isEligible(nfa(pattern, groupCount), false);
  }

  // --- rejecting cases -----------------------------------------------------------------------

  @Test
  void lookahead_rejected() throws Exception {
    assertFalse(eligible("a(?=b)", 0));
  }

  @Test
  void negativeLookahead_rejected() throws Exception {
    assertFalse(eligible("a(?!b)", 0));
  }

  @Test
  void backreference_rejected() throws Exception {
    assertFalse(eligible("(a)\\1", 1));
  }

  @Test
  void atomicGroup_rejected() throws Exception {
    assertFalse(eligible("(?>a+)b", 0));
  }

  @Test
  void wordBoundary_rejected() throws Exception {
    assertFalse(eligible("\\bfoo", 0));
  }

  @Test
  void endAnchor_rejected() throws Exception {
    assertFalse(eligible("foo$", 0));
  }

  @Test
  void anchorInsideLoop_rejected() throws Exception {
    // Start anchor reachable after consuming a character -> not "leading-only".
    assertFalse(eligible("(^a|b)+", 0));
  }

  @Test
  void usePosixLastMatch_rejected() throws Exception {
    NFA n = nfa("(a|a)+", 1);
    assertFalse(LaurikariEligibility.isEligible(n, true));
  }

  @Test
  void allOptionalLoopBodyFollowedByMoreContent_rejected() throws Exception {
    // Bug class: a quantified capturing group whose body can match zero-width, looped via an
    // epsilon-only cycle, followed by more pattern content -- LaurikariCaptureNfaStep.addClosure's
    // visited-on-first-arrival DFS can't propagate the fresher register vector from an extra
    // zero-width loop iteration to that downstream content (see LaurikariEligibility javadoc).
    assertFalse(eligible("([b1-ba]?.*0??)+1", 1));
  }

  // --- positive cases --------------------------------------------------------------------------

  @Test
  void ordinaryTwoGroupConcatenation_eligible() throws Exception {
    assertTrue(eligible("(a+)(b+)", 2));
  }

  @Test
  void loopBodyGroup_eligible() throws Exception {
    assertTrue(eligible("(ab)+", 1));
  }

  @Test
  void nestedGroups_eligible() throws Exception {
    assertTrue(eligible("((a)|())*", 2));
  }

  @Test
  void leadingStartAnchor_eligible() throws Exception {
    assertTrue(eligible("^(a+)(b+)", 2));
  }

  @Test
  void multilineStartAnchor_eligible() throws Exception {
    assertTrue(eligible("(?m)^(a+)", 1));
  }

  @Test
  void usePosixLastMatchFalse_ordinaryPattern_eligible() throws Exception {
    NFA n = nfa("(a+)(b+)", 2);
    assertTrue(LaurikariEligibility.isEligible(n, false));
  }
}
