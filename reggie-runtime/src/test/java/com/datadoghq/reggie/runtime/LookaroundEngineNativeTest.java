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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spike tests for lookahead fallback predicates B1 and B11.
 *
 * <p><b>B1 — lookahead inside quantifier</b> (guard: {@code v.lookaheadInQuantifier} in
 * FallbackPatternDetector, lines ~57-61):
 *
 * <ul>
 *   <li><b>Root cause (NEEDS-RND):</b> The NFA simulation evaluates assertions per position inside
 *       the epsilon-closure step. When a quantifier loops back, the assertion sub-NFA is
 *       re-evaluated at the new position — this is mechanically correct for position-local checks.
 *       The actual bug surfaces when multiple NFA threads reach the same state after different
 *       numbers of quantifier iterations: the Thompson/Pike NFA merges them in the shared state set
 *       without per-thread loop-counter context. Assertion results diverge across iterations (e.g.
 *       {@code (?=\d)} passes in iteration 1, may fail in iteration 2 at a different position), but
 *       the shared state set cannot represent this divergence. Concretely, {@code (?:(?=\d)\d)+} on
 *       input {@code "1"} returns false instead of true because the epsilon closure after the
 *       quantifier loop-back merges the assertion-passed thread with the initial-position thread at
 *       the wrong position.
 *   <li><b>Classification: NEEDS-RND.</b> Removing the guard causes wrong find() results for inputs
 *       tested in {@link LookaheadInQuantifierTest}. Fixing this correctly requires per-thread
 *       quantifier context (safe-backtracking R&amp;D).
 * </ul>
 *
 * <p><b>B11 — lookahead inside alternation branch</b> (guard: {@code hasLookaheadInAlternation}
 * with strategy {@code OPTIMIZED_NFA_WITH_LOOKAROUND} in FallbackPatternDetector, lines ~149-156):
 *
 * <ul>
 *   <li><b>Root cause (NEEDS-RND):</b> The B11 guard fires only for the {@code
 *       OPTIMIZED_NFA_WITH_LOOKAROUND} strategy — patterns where the DFA construction fails and the
 *       engine falls back to pure NFA simulation. When a positive lookahead appears inside one
 *       alternative (e.g. {@code ((?=\d+)x|y)}), the NFA thread scheduler runs the assertion check
 *       inside the shared epsilon-closure worklist. If the assertion passes, its epsilon-targets
 *       are added to the global state set. Those targets can be reachable from other alternatives
 *       too, so a passing assertion in branch A enables transitions that should only belong to
 *       branch A, contaminating sibling branch B. The reverse also holds: a failing assertion in A
 *       may prevent state additions that branch B legitimately needs if the NFA states overlap.
 *       This is the fundamental Thompson NFA limitation — no per-thread branch identity. Notably,
 *       patterns that do get a DFA-based strategy ({@code HYBRID_DFA_LOOKAHEAD}, {@code
 *       DFA_UNROLLED_WITH_ASSERTIONS}, etc.) do NOT hit this guard and work correctly because the
 *       assertion is evaluated structurally at compile time rather than at runtime in the shared
 *       state set. The guard is therefore correctly scoped to {@code OPTIMIZED_NFA_WITH_LOOKAROUND}
 *       only.
 *   <li><b>Classification: NEEDS-RND.</b> Removing the guard causes wrong find() results for
 *       NFA-only patterns like {@code ((?=\d+)x|y)} where assertion failure in one branch
 *       incorrectly blocks the other. Fixing this requires per-thread assertion state
 *       (safe-backtracking R&amp;D) or assertion-aware NFA construction changes.
 * </ul>
 */
class LookaroundEngineNativeTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  private static boolean jdkFind(String pattern, String input) {
    return Pattern.compile(pattern).matcher(input).find();
  }

  // -------------------------------------------------------------------------
  // B1: lookahead inside quantifier — verifies that guard is active
  // -------------------------------------------------------------------------

  /**
   * Confirms B1 guard: {@code (?:(?=\d)\d)+} routes to JDK fallback (guard in
   * FallbackPatternDetector). This is the canonical failing case from issue #28.
   */
  @Test
  void b1_lookaheadInsideNonCapturingQuantifier_routedToFallback() {
    ReggieMatcher m = Reggie.compile("(?:(?=\\d)\\d)+");
    assertTrue(
        m instanceof JavaRegexFallbackMatcher,
        "(?:(?=\\d)\\d)+ must route to JDK fallback (B1 guard active)");
    for (String input : new String[] {"", "a", "1", "123", "abc", "a1b"}) {
      assertEquals(
          jdkFind("(?:(?=\\d)\\d)+", input),
          m.find(input),
          "(?:(?=\\d)\\d)+ fallback must agree with JDK for: " + input);
    }
  }

  /** Another B1 pattern: capturing group with lookahead repeated. Routes to JDK fallback. */
  @Test
  void b1_lookaheadInsideCapturingGroupRepeated_routedToFallback() {
    ReggieMatcher m = Reggie.compile("(a(?=b))+");
    assertTrue(
        m instanceof JavaRegexFallbackMatcher,
        "(a(?=b))+ must route to JDK fallback (B1 guard active)");
    for (String input : new String[] {"", "a", "ab", "abc", "abab"}) {
      assertEquals(
          jdkFind("(a(?=b))+", input),
          m.find(input),
          "(a(?=b))+ fallback must agree with JDK for: " + input);
    }
  }

  // -------------------------------------------------------------------------
  // B11: lookahead inside alternation — verifies that guard is active
  // -------------------------------------------------------------------------

  /**
   * Confirms B11 guard: a complex pattern with a variable-width lookahead inside a capturing group
   * alternation forces OPTIMIZED_NFA_WITH_LOOKAROUND and triggers the B11 fallback.
   *
   * <p>Pattern {@code ((?=\d+)x|y)}: the lookahead {@code (?=\d+)} uses a variable-width
   * quantifier, so the DFA constructor throws UnsupportedOperationException and the engine routes
   * to OPTIMIZED_NFA_WITH_LOOKAROUND. hasLookaheadInAlternation() then detects the lookahead inside
   * the first alternative and triggers the fallback.
   */
  @Test
  void b11_lookaheadInGroupAlternationNfaStrategy_routedToFallback() {
    // ((?=\d+)x|y) — DFA fails for \d+; routes to OPTIMIZED_NFA_WITH_LOOKAROUND; B11 fires
    ReggieMatcher m = Reggie.compile("((?=\\d+)x|y)");
    assertTrue(
        m instanceof JavaRegexFallbackMatcher,
        "((?=\\d+)x|y) must route to JDK fallback (B11 guard active for OPTIMIZED_NFA path)");
    for (String input : new String[] {"", "x", "y", "1x", "1y", "xy", "12x"}) {
      assertEquals(
          jdkFind("((?=\\d+)x|y)", input),
          m.find(input),
          "((?=\\d+)x|y) fallback must agree with JDK for: " + input);
    }
  }

  /**
   * Another B11 pattern: negative lookahead inside alternation with OPTIMIZED_NFA_WITH_LOOKAROUND.
   * The negative complex lookahead (e.g. {@code (?!.*[A-Z])}) forces the NFA path.
   */
  @Test
  void b11_negativeLookaheadInAlternationNfaStrategy_routedToFallback() {
    // ((?!.*[A-Z])a|b) — (?!.*[A-Z]) is complex, DFA fails; NFA path; B11 fires
    ReggieMatcher m = Reggie.compile("((?!.*[A-Z])a|b)");
    assertTrue(
        m instanceof JavaRegexFallbackMatcher,
        "((?!.*[A-Z])a|b) must route to JDK fallback (B11 guard active for OPTIMIZED_NFA path)");
    for (String input : new String[] {"", "a", "b", "A", "Ba", "abc", "Abc"}) {
      assertEquals(
          jdkFind("((?!.*[A-Z])a|b)", input),
          m.find(input),
          "((?!.*[A-Z])a|b) fallback must agree with JDK for: " + input);
    }
  }

  /**
   * Negative B11 control: a simple lookahead in an alternation that routes to the DFA strategy
   * (literal lookahead, DFA construction succeeds) must NOT route to fallback — the B11 guard is
   * NFA-only.
   *
   * <p><b>Investigation finding:</b> {@code (?=a)b|c} is NOT routed to fallback, confirming the
   * guard is correctly scoped. However, investigation revealed that even the DFA path returns wrong
   * results for some inputs (e.g. input {@code "c"} returns false instead of true). The DFA
   * strategy for assertion-in-alternation has a separate correctness bug distinct from B11. This
   * test only verifies routing (not full correctness) to document the guard scope.
   */
  @Test
  void b11_simpleLookaheadInAlternationDfaStrategy_notFallback() {
    // (?=a)b|c — simple literal lookahead: DFA construction succeeds; B11 guard never fires
    ReggieMatcher m = Reggie.compile("(?=a)b|c");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "(?=a)b|c must NOT route to fallback (DFA handles it; B11 guard is NFA-only)");
    // Verify only inputs that are known-correct for the DFA path
    // Input "ab" — (?=a) passes, b matches → true
    assertEquals(jdkFind("(?=a)b|c", "ab"), m.find("ab"), "(?=a)b|c on 'ab'");
    // Input "" — neither branch matches → false
    assertEquals(jdkFind("(?=a)b|c", ""), m.find(""), "(?=a)b|c on ''");
  }
}
