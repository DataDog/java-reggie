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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FallbackPatternDetectorTest {

  private static final PatternAnalyzer.MatchingStrategy STRATEGY =
      PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS;

  private String detect(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return FallbackPatternDetector.needsFallback(ast, STRATEGY);
  }

  private String detect(String pattern, PatternAnalyzer.MatchingStrategy strategy)
      throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return FallbackPatternDetector.needsFallback(ast, strategy);
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

  // ── Bug-4 removed: alternation inside lookbehind must NOT trigger fallback ─────────────────

  @Test
  void alternationInLookbehindNoFallback() throws Exception {
    assertNull(detect("(?<=a|b)c"));
  }

  // ── Bug-5 fixed: combined lookbehind + lookahead no longer triggers blanket fallback ────────

  @Test
  void lookbehindAndLookaheadCombinedNoFallback() throws Exception {
    assertNull(detect("(?<=\\d)[a-z]+(?=\\s)"));
  }

  // ── Anchor inside quantifier (non-capturing) must trigger fallback ────────────────────────

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\A{0,3}a",
        "(?:c*^{0,2})",
        "(?:)(?:c*^{0,2}a)",
        "${3}0?[^a]*",
        "0{0}\\z{0,2}.{3}",
      })
  void anchorInQuantifier_needsFallback(String pat) throws Exception {
    assertNotNull(detect(pat), "expected fallback for: " + pat);
  }

  // ── B1 / B4 are skipped for PIKEVM_CAPTURE ────────────────────────────────────────────────

  @Test
  void b1_lookaheadInQuantifier_noFallbackForPikeVmCapture() throws Exception {
    // DFA strategy triggers B1; PIKEVM_CAPTURE correctly handles it and must not fall back.
    assertNotNull(detect("(?:(?=a)b)+"));
    assertNull(
        detect("(?:(?=a)b)+", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE),
        "PIKEVM_CAPTURE must skip B1");
  }

  @Test
  void b4_endAnchorBeforeNonNewlineConsumer_noFallbackForPikeVmCapture() throws Exception {
    // DFA strategy triggers B4; PIKEVM_CAPTURE must skip it.
    assertNotNull(detect("\\Z[^c]"));
    assertNull(
        detect("\\Z[^c]", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE),
        "PIKEVM_CAPTURE must skip B4");
  }

  // ── B12-overlap: greedy prefix charset overlaps backref group → fallback ──────────────────

  @Test
  void b12overlap_greedyPrefixOverlapsBackrefGroup_needsFallback() throws Exception {
    // a* prefix and (a+) group share 'a': greedy non-backtracking split is wrong.
    String reason = detect("a*(a+)\\1", PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF);
    assertNotNull(reason, "expected fallback for overlapping greedy prefix");
    assertTrue(reason.contains("overlaps"), "reason should mention charset overlap: " + reason);
  }

  @Test
  void b12overlap_disjointPrefixNoFallback() throws Exception {
    // b* prefix and (a+) group are disjoint: no overlap fallback.
    assertNull(
        detect("b*(a+)\\1", PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF),
        "disjoint prefix must not trigger B12-overlap");
  }

  // ── B12-nullable: nullable child in unbounded prefix quantifier → fallback ─────────────────

  @Test
  void b12nullable_nullableChildInUnboundedPrefix_needsFallback() throws Exception {
    // (?:a*)+ has nullable child a*: prefix loop spins forever on empty iterations.
    String reason =
        detect("(?:a*)+(a+)\\1", PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF);
    assertNotNull(reason, "expected fallback for nullable child in unbounded prefix");
    assertTrue(
        reason.contains("nullable child"), "reason should mention nullable child: " + reason);
  }

  @Test
  void b12nullable_nonNullableChildNoFallback() throws Exception {
    // (?:a)+ has a non-nullable child: no infinite-loop risk.
    assertNull(
        detect("(?:a)+(b+)\\1", PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF),
        "non-nullable child must not trigger B12-nullable");
  }
}
