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
package com.datadoghq.reggie.codegen.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

class FallbackPatternDetectorTest {

  private static final PatternAnalyzer.MatchingStrategy STRATEGY =
      PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS;

  private String detect(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return FallbackPatternDetector.needsFallback(ast, STRATEGY);
  }

  // ── Bug-3 removed: lookbehind + unbounded quantifier must NOT trigger fallback ────────────────

  @Test
  void lookbehindPlusQuantifierNoFallback() throws Exception {
    assertNull(detect("(?<=\\d)[a-z]+"));
  }

  @Test
  void lookbehindStarQuantifierNoFallback() throws Exception {
    assertNull(detect("(?<=\\d)[a-z]*"));
  }

  @Test
  void lookbehindOpenEndedRangeQuantifierNoFallback() throws Exception {
    assertNull(detect("(?<=\\d)[a-z]{2,}"));
  }

  // ── Bug-2 regression: lookahead inside quantified group must still trigger fallback ──────────

  @Test
  void lookaheadInQuantifierTriggersFallback() throws Exception {
    assertNotNull(detect("(?:(?=a)b)+"));
  }

  // ── Bug-4 regression: alternation inside lookbehind must still trigger fallback ────────────

  @Test
  void alternationInLookbehindTriggersFallback() throws Exception {
    assertNotNull(detect("(?<=a|b)c"));
  }

  // ── Bug-5 regression: combined lookbehind + lookahead must still trigger fallback ──────────

  @Test
  void lookbehindAndLookaheadCombinedTriggersFallback() throws Exception {
    assertNotNull(detect("(?<=\\d)[a-z]+(?=\\s)"));
  }
}
