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
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Spike regression tests for B1 (lookahead in quantifier, issue #28) and B11 (lookahead in
 * alternation, OPTIMIZED_NFA_WITH_LOOKAROUND guard).
 *
 * <p>These tests are <em>intentionally failing</em>: the patterns currently fall back to JDK via
 * FallbackPatternDetector. They document the expected end-state once each guard is removed, and
 * serve as a red test that must be made green before the corresponding spike branch can merge.
 *
 * <h2>B1 — lookaheadInQuantifier (all strategies, issue #28)</h2>
 *
 * <p>Root cause: the NFA engine represents the set of active threads as a flat state-id set (BitSet
 * / long bitmask) with no per-thread metadata. When a quantifier loops back — i.e. the NFA follows
 * the ε-transition from the quantifier's accept state back to its entry state — the epsilon closure
 * is recomputed from scratch at the current input position. Any assertion state (lookahead node)
 * reached during that closure is evaluated against the current position. The engine has lost all
 * information about which threads entered this iteration with which prior assertion context. In the
 * Thompson/Pike model, two threads that reach the same NFA state at the same input position are
 * indistinguishable and merged immediately. A lookahead assertion inside a quantifier requires
 * knowing, per thread, whether the assertion was already satisfied at the iteration's entry point —
 * information the flat state set discards.
 *
 * <p>Concretely: {@code (?:(?=\d)\d)+} on input {@code "1"}. Iteration 1 at position 0: lookahead
 * {@code (?=\d)} succeeds, {@code \d} consumes '1', position advances to 1. Quantifier loops; the
 * epsilon closure re-enters the lookahead state at position 1. The lookahead now fails (no digit at
 * position 1). This is the correct result for this specific case. However, for patterns where the
 * loop-back ε-closure can reach the lookahead state via a path that bypasses re-evaluation (shared
 * intermediate NFA states), the merged thread set carries a stale "assertion passed" flag from a
 * prior iteration into the next, producing a false match.
 *
 * <p>B1 classification: <strong>NEEDS-RND</strong>. Fixing this correctly requires per-thread
 * assertion state — tagging each active thread with the assertion results it computed at its
 * current position. That is exactly the per-thread metadata the deferred safe-backtracking R&amp;D
 * track is designed to introduce. The flat state set must be replaced or augmented with a structure
 * preserving per-thread context across ε-closure steps. This is a non-trivial, allocation-bearing
 * change out of scope for bounded allocation-free fixes.
 *
 * <h2>B11 — hasLookaheadInAlternation (OPTIMIZED_NFA_WITH_LOOKAROUND only)</h2>
 *
 * <p>Root cause: same flat-state-set merger, manifesting across alternation branches rather than
 * quantifier iterations. The NFA for {@code ((?!.*[A-Z])a|b)} has an epsilon fork at the
 * alternation node. Branch 1 enters the negative lookahead assertion state; branch 2 skips it. In
 * the Thompson/Pike epsilon closure these two paths compete for the same successor states. When the
 * worklist processes the assertion state it evaluates the lookahead and, if it passes, adds the
 * successor states to the global active set. But that same assertion state can be reached by a
 * thread that came via branch 2 (which never "owned" the assertion), contaminating the active set
 * with states that should only be reachable through branch 1. Conversely, if branch 2's thread
 * merges with branch 1's thread at a shared successor state before the assertion is processed, the
 * assertion check is skipped entirely, letting branch-2 input past the branch-1 guard.
 *
 * <p>B11 classification: <strong>NEEDS-RND</strong>. The guard is scoped to
 * OPTIMIZED_NFA_WITH_LOOKAROUND only because DFA-based strategies (DFA_UNROLLED_WITH_ASSERTIONS,
 * DFA_SWITCH_WITH_ASSERTIONS) bake the assertion check into the DFA transition function at compile
 * time; alternation structure is eliminated and assertion contamination cannot occur. Fixing the
 * NFA path requires the same per-thread assertion metadata as B1. No allocation-free bounded patch
 * exists. Patterns where DFA construction succeeds (e.g. simple literal lookaheads like {@code
 * (?=a)b|c}) are handled correctly by DFA strategies and never reach this guard.
 */
public class LookaroundEngineNativeTest {

  // ---------------------------------------------------------------------------
  // B1: lookahead inside a quantifier
  // FallbackPatternDetector.needsFallback fires for ALL strategies when
  // v.lookaheadInQuantifier is true. These patterns must become native and
  // produce results that agree with JDK once B1 is resolved.
  // ---------------------------------------------------------------------------

  static Stream<Arguments> b1PatternsAndInputs() {
    return Stream.of(
        // Simple positive lookahead gates a quantified match
        Arguments.of("(?:(?=\\d)\\d)+", ""),
        Arguments.of("(?:(?=\\d)\\d)+", "1"),
        Arguments.of("(?:(?=\\d)\\d)+", "123"),
        Arguments.of("(?:(?=\\d)\\d)+", "abc"),
        Arguments.of("(?:(?=\\d)\\d)+", "1b2"),
        // Lookahead inside a capturing group that is quantified
        Arguments.of("((?=\\w)\\w)+", ""),
        Arguments.of("((?=\\w)\\w)+", "a"),
        Arguments.of("((?=\\w)\\w)+", "abc"),
        Arguments.of("((?=\\w)\\w)+", "!"),
        // Lookahead at the start of a + repetition with a trailing fixed element
        Arguments.of("(?:(?=a)a)+b", "aab"),
        Arguments.of("(?:(?=a)a)+b", "ab"),
        Arguments.of("(?:(?=a)a)+b", "b"));
  }

  /**
   * B1: patterns with a lookahead inside a quantifier.
   *
   * <p>Currently fails on the {@code assertFalse}: FallbackPatternDetector routes every
   * lookahead-in-quantifier pattern to JavaRegexFallbackMatcher regardless of strategy. Once B1 is
   * resolved (NEEDS-RND), the guard is removed and these tests must pass: native engine, JDK
   * agreement.
   */
  @Disabled(
      "NEEDS-RND: flat state set has no per-thread assertion metadata; safe-backtracking R&D required")
  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b1PatternsAndInputs")
  void b1LookaheadInQuantifier_shouldBeNativeAndAgreeWithJdk(String pat, String in)
      throws Exception {
    ReggieMatcher reggie = Reggie.compile(pat);

    // FAILS NOW: guard routes to JavaRegexFallbackMatcher
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "B1: expected native matcher for lookahead-in-quantifier pattern: " + pat);

    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
  }

  // ---------------------------------------------------------------------------
  // B11: lookahead inside an alternation branch (OPTIMIZED_NFA_WITH_LOOKAROUND only)
  // FallbackPatternDetector.needsFallback fires only when strategy ==
  // OPTIMIZED_NFA_WITH_LOOKAROUND AND hasLookaheadInAlternation. Patterns must use
  // a complex assertion to force OPTIMIZED_NFA_WITH_LOOKAROUND (simple literal
  // lookaheads like (?=a) succeed in DFA construction and never reach this guard).
  // ---------------------------------------------------------------------------

  static Stream<Arguments> b11PatternsAndInputs() {
    return Stream.of(
        // Negative lookahead (complex → OPTIMIZED_NFA_WITH_LOOKAROUND) in branch 1
        Arguments.of("((?!.*[A-Z])a|b)", ""),
        Arguments.of("((?!.*[A-Z])a|b)", "a"),
        Arguments.of("((?!.*[A-Z])a|b)", "b"),
        Arguments.of("((?!.*[A-Z])a|b)", "A"),
        Arguments.of("((?!.*[A-Z])a|b)", "Ab"),
        // Complex lookahead in second branch
        Arguments.of("(a|(?!.*[A-Z])b)", ""),
        Arguments.of("(a|(?!.*[A-Z])b)", "a"),
        Arguments.of("(a|(?!.*[A-Z])b)", "b"),
        Arguments.of("(a|(?!.*[A-Z])b)", "B"),
        // Variable-width positive lookahead forces NFA path
        Arguments.of("((?=\\d+)x|y)", ""),
        Arguments.of("((?=\\d+)x|y)", "x"),
        Arguments.of("((?=\\d+)x|y)", "y"),
        Arguments.of("((?=\\d+)x|y)", "1x"),
        Arguments.of("((?=\\d+)x|y)", "12x"));
  }

  /**
   * B11: patterns with a complex lookahead inside an alternation branch.
   *
   * <p>Currently fails on the {@code assertFalse}: FallbackPatternDetector routes
   * OPTIMIZED_NFA_WITH_LOOKAROUND patterns that contain hasLookaheadInAlternation to
   * JavaRegexFallbackMatcher. Once B11 is resolved (NEEDS-RND), the guard is removed and these
   * tests must pass: native engine, JDK agreement.
   */
  @Disabled("NEEDS-RND: same flat state set limitation as B1; safe-backtracking R&D required")
  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b11PatternsAndInputs")
  void b11LookaheadInAlternation_shouldBeNativeAndAgreeWithJdk(String pat, String in)
      throws Exception {
    ReggieMatcher reggie = Reggie.compile(pat);

    // FAILS NOW: guard routes to JavaRegexFallbackMatcher
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "B11: expected native matcher for lookahead-in-alternation pattern: " + pat);

    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
  }
}
