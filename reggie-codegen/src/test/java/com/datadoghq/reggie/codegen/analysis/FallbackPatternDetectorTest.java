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
  void lookaroundAndBackrefs_fallBackForPikeVmCapture() throws Exception {
    // PikeVM crosses lookaround and backreference states as plain epsilons ((?=a)b would match
    // "b"), so PIKEVM_CAPTURE must refuse them.
    assertNotNull(detect("(?:(?=a)b)+"));
    for (String p : new String[] {"(?:(?=a)b)+", "(?>a|ab)(?=c)", "(?<=x)(?>a)", "(a)(?>\\1|b)"}) {
      assertNotNull(detect(p, PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE), p);
    }
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

  // ── B7 on the backtracker: repeated ambiguously nullable backref group → fallback ──────────

  private String detectOnBacktracker(String pattern, PatternAnalyzer.MatchingStrategy strategy)
      throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return FallbackPatternDetector.needsFallback(ast, strategy, true);
  }

  @Test
  void b7_backtracker_repeatedNullableBackrefGroup_needsFallback() throws Exception {
    // (a*|b){2}\1 on "bb": JDK rejects matches(); the backtracker would accept an empty first
    // iteration followed by "b" and \1 = "b".
    for (PatternAnalyzer.MatchingStrategy strategy :
        new PatternAnalyzer.MatchingStrategy[] {
          PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS,
          PatternAnalyzer.MatchingStrategy.PINNED_BACKREFERENCE
        }) {
      for (String pattern : new String[] {"(a*|b){2}\\1", "(a*|b)+\\1", "(?:x(a{0,3}))*\\1"}) {
        assertNotNull(
            detectOnBacktracker(pattern, strategy),
            pattern + " on the backtracker (" + strategy + ") must keep the B7 fallback");
      }
    }
  }

  @Test
  void b7_backtracker_nonRepeatedNullableBackrefGroup_noFallback() throws Exception {
    // (a{0,3})\d+\1: the group is not repeated, so the backtracker runs it.
    for (PatternAnalyzer.MatchingStrategy strategy :
        new PatternAnalyzer.MatchingStrategy[] {
          PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS,
          PatternAnalyzer.MatchingStrategy.PINNED_BACKREFERENCE
        }) {
      assertNull(
          detectOnBacktracker("(a{0,3})\\d+\\1", strategy),
          "(a{0,3})\\d+\\1 on the backtracker (" + strategy + ") must not fall back");
    }
  }

  @Test
  void b7_generatedCode_nonRepeatedNullableBackrefGroup_stillFallsBack() throws Exception {
    assertNotNull(
        detect("(a{0,3})\\d+\\1", PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS),
        "generated OPTIMIZED_NFA_WITH_BACKREFS code must keep the B7 fallback");
  }

  // ── B16-atomic: nullable-nullable capture inside an atomic group → PIKEVM_CAPTURE fallback ─

  @Test
  void b16atomic_pikeVm_nullableCaptureInAtomicRepeat_needsFallback() throws Exception {
    // (?>(a?)*)*a on "a": JDK group 1 = [1,1) from the abandoned atomic attempt; PikeVM unmatched.
    for (String pattern : new String[] {"(?>(a?)*)*a", "(?>(a?)*)b|a", "(?:(?>(a*)*)b)*a"}) {
      assertNotNull(
          detect(pattern, PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE),
          pattern + " must not run on PIKEVM_CAPTURE");
    }
  }

  @Test
  void b16atomic_pikeVm_nonAtomicNullableCapture_noFallback() throws Exception {
    // Outside an atomic group PikeVM reports JDK's final zero-width iteration.
    assertNull(
        detect("((x*){0,}|a)(c|bcd)", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE),
        "non-atomic B16 shape must stay on PIKEVM_CAPTURE");
  }
}
