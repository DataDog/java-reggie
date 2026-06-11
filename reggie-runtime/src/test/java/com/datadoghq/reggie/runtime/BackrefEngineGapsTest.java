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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Failing regression tests for backref engine gap fallbacks (A6, B5–B9, B12–B14).
 *
 * <p>Each test asserts that Reggie produces a native (non-fallback) matcher AND that the result
 * agrees with java.util.regex. Currently all of these patterns route to {@link
 * JavaRegexFallbackMatcher} because of a guard in {@link RuntimeCompiler} (A6) or {@link
 * com.datadoghq.reggie.codegen.analysis.FallbackPatternDetector} (B5–B9, B12–B14). The tests are
 * marked {@code @Disabled} because they are expected to fail until each guard is lifted.
 *
 * <p>Feasibility matrix (as of 2026-06-11):
 *
 * <pre>
 * | Case | Pattern example          | Classification  | Reason                                        |
 * |------|--------------------------|-----------------|-----------------------------------------------|
 * | A6   | (a)\1|b                  | NEEDS-RND       | NFA bypass path = capture-ambiguity; per-state|
 * |      |                          |                 | group arrays needed (issue #38 Cat B)         |
 * | B5   | (a+?)\1                  | NEEDS-RND       | Lazy quantifier requires shortest-match       |
 * |      |                          |                 | semantics. Pattern routes native via          |
 * |      |                          |                 | VARIABLE_CAPTURE_BACKREF (guard gap: B5 only  |
 * |      |                          |                 | gates RECURSIVE_DESCENT and                   |
 * |      |                          |                 | OPTIMIZED_NFA_WITH_BACKREFS) but produces     |
 * |      |                          |                 | greedy spans — fix requires lazy-aware        |
 * |      |                          |                 | backtracking in VARIABLE_CAPTURE_BACKREF      |
 * | B6   | (a)|(b)\1                | NEEDS-RND       | Cross-alt backref needs per-state group arrays|
 * |      |                          |                 | to distinguish which branch captured group N  |
 * | B7   | (a?)\1                   | FIXABLE-NOW     | Parallel NFA shared group array issue; a      |
 * |      |                          |                 | pre-check "if groupLen==0 accept always"      |
 * |      |                          |                 | in backrefCheck would fix the most common     |
 * |      |                          |                 | case within OPTIMIZED_NFA_WITH_BACKREFS       |
 * | B8   | (a?)\1{2}                | KEEP-PERMANENT  | Guard is unreachable: detectFixedRepetition   |
 * |      |                          |                 | Backref requires isSimpleGroupContent (only   |
 * |      |                          |                 | Literal/CharClass), which are never nullable. |
 * |      |                          |                 | Nullable groups route to RECURSIVE_DESCENT;   |
 * |      |                          |                 | the B8 guard in FallbackPatternDetector is    |
 * |      |                          |                 | dead code. The test documents this finding.   |
 * | B9   | ((a?)\2)                 | NEEDS-RND       | RECURSIVE_DESCENT propagates zero-length      |
 * |      |                          |                 | capture into nested group boundary; fixing    |
 * |      |                          |                 | requires per-frame capture state in the       |
 * |      |                          |                 | descent parser                                |
 * | B12  | (?:x)(a)\1               | FIXABLE-NOW     | Generator only handles Literal/CharClass      |
 * |      |                          |                 | prefix nodes; extending emitPrefixMatch to    |
 * |      |                          |                 | non-capturing groups is bounded work          |
 * | B13  | (a)+\1                   | NEEDS-RND       | Last-iteration capture tracking for outer-    |
 * |      |                          |                 | quantified group requires iteration state;    |
 * |      |                          |                 | not representable in the current fixed frame  |
 * | B14  | (a?)+\1                  | NEEDS-RND       | Nullable group + outer quantifier: combines  |
 * |      |                          |                 | zero-length capture and last-iteration        |
 * |      |                          |                 | tracking — both unsolved sub-problems         |
 * </pre>
 *
 * <p>Summary: 2 FIXABLE-NOW (B7, B12), 6 NEEDS-RND (A6, B5, B6, B9, B13, B14), 1 KEEP-PERMANENT (B8
 * — dead-code guard).
 *
 * <p>Spike finding: B5 {@code (a+?)\1} routes native today via VARIABLE_CAPTURE_BACKREF (the lazy
 * guard only covers RECURSIVE_DESCENT and OPTIMIZED_NFA_WITH_BACKREFS). The
 * VARIABLE_CAPTURE_BACKREF engine applies greedy semantics, producing wrong spans for lazy inputs.
 * The B5 test below therefore asserts JDK agreement (which fails) rather than fallback detection.
 */
class BackrefEngineGapsTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── A6: captureAmbiguous — NFA has a bypass path around capturing group ────────────────────────

  /**
   * A6: {@code (a)\1|b} — the NFA can reach accept via the {@code b} branch without ever entering
   * group 1. RuntimeCompiler detects this via {@code hasNfaCaptureAmbiguity} and returns a {@link
   * JavaRegexFallbackMatcher} before {@code FallbackPatternDetector} is called. Fixing this
   * requires per-state group arrays so the engine can correctly isolate group bindings per NFA
   * thread (issue #38 Cat B). Classification: NEEDS-RND.
   */
  @Test
  @Disabled("A6: captureAmbiguous fallback — needs per-state group arrays (NEEDS-RND)")
  void a6_captureAmbiguous_nfaBypassPath() {
    ReggieMatcher m = Reggie.compile("(a)\\1|b");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "A6: (a)\\1|b must stay native — remove captureAmbiguous fallback in RuntimeCompiler");

    // JDK agreement on "aab": "(a)\1" matches at position 0, group 1 = "a"
    Matcher jdk = Pattern.compile("(a)\\1|b").matcher("aab");
    boolean jdkFound = jdk.find();
    MatchResult r = m.findMatch("aab");
    assertEquals(jdkFound, r != null, "A6: find result must agree with JDK on 'aab'");
    if (jdkFound && r != null) {
      assertEquals(jdk.start(), r.start(), "A6: match start must agree with JDK");
    }
  }

  // ── B5: lazy quantifier + backref ─────────────────────────────────────────────────────────────

  /**
   * B5: {@code (a+?)\1} — lazy quantifier inside the capturing group. The {@code hasLazyQuantifier}
   * guard in FallbackPatternDetector only gates {@code RECURSIVE_DESCENT} and {@code
   * OPTIMIZED_NFA_WITH_BACKREFS}. This pattern currently routes native via {@code
   * VARIABLE_CAPTURE_BACKREF}, which applies greedy semantics and produces wrong spans: on {@code
   * "aaaa"} reggie returns {@code end=4, group(1)="aa"} while JDK returns {@code end=2,
   * group(1)="a"}. The fix requires either extending the hasLazyQuantifier guard to cover
   * VARIABLE_CAPTURE_BACKREF (forcing fallback) or implementing lazy-aware backtracking in that
   * engine. Classification: NEEDS-RND.
   */
  @Test
  @Disabled("B5: lazy backref in VARIABLE_CAPTURE_BACKREF produces greedy spans — NEEDS-RND")
  void b5_lazyQuantifierWithBackref() {
    // Note: this pattern routes NATIVE (not fallback) today — assertFalse would pass.
    // The failure is in JDK agreement: reggie picks greedy match, JDK picks lazy match.
    ReggieMatcher m = Reggie.compile("(a+?)\\1");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B5: (a+?)\\1 already routes native — this assertion would pass");

    // JDK agreement: lazy semantics require shortest match; reggie returns greedy spans
    MatchResult r = m.findMatch("aaaa");
    assertNotNull(r, "B5: (a+?)\\1 must return a match result for 'aaaa'");
    assertEquals(
        2, r.end(), "B5: lazy match must end at 2 (group 'a' + backref 'a'); reggie returns 4");
    assertEquals("a", r.group(1), "B5: group 1 must be 'a' (lazy shortest); reggie returns 'aa'");
  }

  // ── B6: cross-alternative backref ──────────────────────────────────────────────────────────────

  /**
   * B6: {@code (a)|(b)\1} — group 1 is defined in alt-branch-0, but {@code \1} is used in
   * alt-branch-1. Both {@code OPTIMIZED_NFA_WITH_BACKREFS} (shared group array) and {@code
   * RECURSIVE_DESCENT} (no branch retry) fail here. Requires per-state group arrays to distinguish
   * which thread captured group 1. Classification: NEEDS-RND.
   */
  @Test
  @Disabled("B6: cross-alternative backref fallback — needs per-state group arrays (NEEDS-RND)")
  void b6_crossAlternativeBackref() {
    ReggieMatcher m = Reggie.compile("(a)|(b)\\1");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B6: (a)|(b)\\1 must stay native — remove hasCrossAlternativeBackref fallback");

    // JDK agreement: "a" matches via branch-0, group 1 = "a"
    MatchResult r = m.findMatch("a");
    assertNotNull(r, "B6: must match 'a' via branch-0");
    assertEquals("a", r.group(1), "B6: group 1 must be 'a'");

    // "b" does not match: branch-0 fails, branch-1 uses \1 which was never captured
    assertNull(m.findMatch("b"), "B6: must not match 'b' — backref \\1 not captured");
  }

  // ── B7: nullable backref group in OPTIMIZED_NFA_WITH_BACKREFS ─────────────────────────────────

  /**
   * B7: {@code (a?)\1} — group 1 can capture the empty string. In parallel NFA simulation the
   * shared group array may be overwritten by the greedy (non-empty) path before the empty-capture
   * path's backref check runs. Adding a zero-length early-accept in the backref check ({@code if
   * groupLen == 0 accept always}) is a bounded, allocation-free fix within {@code
   * OPTIMIZED_NFA_WITH_BACKREFS}. Classification: FIXABLE-NOW.
   */
  @Test
  void b7_nullableBackrefGroupInOptimizedNfa() {
    ReggieMatcher m = Reggie.compile("(a?)\\1");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B7: (a?)\\1 must stay native — remove hasNullableBackrefGroup fallback for "
            + "OPTIMIZED_NFA_WITH_BACKREFS");

    // JDK agreement on "aa": group 1 = "a", backref matches "a"
    MatchResult r = m.findMatch("aa");
    assertNotNull(r, "B7: (a?)\\1 must match 'aa'");
    assertEquals("a", r.group(1), "B7: group 1 must be 'a'");

    // JDK agreement on "": group 1 = "", backref matches ""
    assertTrue(
        m.matches(""), "B7: (a?)\\1 must match empty string (both capture and backref empty)");
  }

  // ── B8: nullable backref group in FIXED_REPETITION_BACKREF ────────────────────────────────────

  /**
   * B8: {@code (a?)\1{2}} — spike finding: the {@code hasNullableBackrefGroup} guard for {@code
   * FIXED_REPETITION_BACKREF} is dead code. {@code detectFixedRepetitionBackref} requires {@code
   * isSimpleGroupContent} (only {@code LiteralNode} or {@code CharClassNode}), neither of which is
   * nullable. Nullable group bodies (e.g. {@code a?}) fail {@code isSimpleGroupContent} and route
   * to {@code RECURSIVE_DESCENT} instead. Therefore no pattern can simultaneously satisfy:
   *
   * <ul>
   *   <li>strategy == FIXED_REPETITION_BACKREF
   *   <li>hasNullableBackrefGroup == true
   * </ul>
   *
   * <p>The guard can be removed from FallbackPatternDetector without any behavioral change. This
   * test verifies that {@code (a?)\1{2}} routes to RECURSIVE_DESCENT (not FIXED_REPETITION_BACKREF)
   * and produces correct results natively. Classification: KEEP-PERMANENT (dead guard).
   */
  @Test
  void b8_nullableBackrefGroupInFixedRepetition_guardIsDeadCode() {
    // (a?)\1{2} routes to RECURSIVE_DESCENT (not FIXED_REPETITION_BACKREF) because
    // the nullable group body 'a?' fails isSimpleGroupContent. No fallback needed.
    ReggieMatcher m = Reggie.compile("(a?)\\1{2}");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B8: (a?)\\1{2} must be native — nullable group routes to RECURSIVE_DESCENT, "
            + "not FIXED_REPETITION_BACKREF, so the B8 guard never fires");

    // Verify correct behavior (RECURSIVE_DESCENT handles this correctly)
    MatchResult r = m.findMatch("aaa");
    assertNotNull(r, "B8: (a?)\\1{2} must match 'aaa'");
    assertEquals("a", r.group(1), "B8: group 1 must be 'a'");
    assertTrue(m.matches(""), "B8: (a?)\\1{2} must match empty string");
  }

  // ── B9: nullable backref inside capturing group in RECURSIVE_DESCENT ──────────────────────────

  /**
   * B9: {@code ((a?)\2)} — a nullable group 2 is referenced by {@code \2} inside capturing group 1.
   * The recursive descent parser propagates the zero-length capture from group 2 into the outer
   * group 1 boundary incorrectly. Fixing this requires per-frame capture state in the descent
   * parser. Classification: NEEDS-RND.
   */
  @Test
  @Disabled("B9: nullable backref inside capturing group in RECURSIVE_DESCENT — NEEDS-RND")
  void b9_nullableBackrefInsideCapturingGroup() {
    ReggieMatcher m = Reggie.compile("((a?)\\2)");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B9: ((a?)\\2) must stay native — remove hasNullableBackrefInsideCapturingGroup fallback");

    // JDK agreement on "aa": group 2 = "a", backref \2 = "a", outer group 1 = "aa"
    MatchResult r = m.findMatch("aa");
    assertNotNull(r, "B9: ((a?)\\2) must match 'aa'");
    assertEquals("aa", r.group(1), "B9: outer group 1 must be 'aa'");
    assertEquals("a", r.group(2), "B9: inner group 2 must be 'a'");

    // JDK agreement on "": all groups capture empty
    assertTrue(m.matches(""), "B9: ((a?)\\2) must match empty string");
  }

  // ── B12: non-anchor prefix before backref group in VARIABLE_CAPTURE_BACKREF ──────────────────

  /**
   * B12: {@code (?:x)(a)\1} — a non-capturing group prefix appears before the capturing group. The
   * {@code VARIABLE_CAPTURE_BACKREF} generator's {@code emitPrefixMatch} handles {@code
   * LiteralNode} and {@code CharClassNode} prefix nodes but not non-capturing {@code GroupNode}
   * prefixes. Extending {@code emitPrefixMatch} to handle non-capturing groups by inlining their
   * content is a bounded, allocation-free fix. Classification: FIXABLE-NOW.
   */
  @Test
  @Disabled("B12: non-capturing group prefix in VARIABLE_CAPTURE_BACKREF — FIXABLE-NOW")
  void b12_nonAnchorPrefixBeforeBackrefGroup() {
    ReggieMatcher m = Reggie.compile("(?:x)(a)\\1");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B12: (?:x)(a)\\1 must stay native — remove hasNonAnchorPrefixBeforeBackrefGroup "
            + "fallback");

    // JDK agreement: "xaa" matches, group 1 = "a"
    MatchResult r = m.findMatch("xaa");
    assertNotNull(r, "B12: (?:x)(a)\\1 must match 'xaa'");
    assertEquals("a", r.group(1), "B12: group 1 must be 'a'");

    // No match when backref fails
    assertNull(m.findMatch("xab"), "B12: (?:x)(a)\\1 must not match 'xab'");
  }

  // ── B13: outer quantifier on backref group in VARIABLE_CAPTURE_BACKREF ────────────────────────

  /**
   * B13: {@code (a)+\1} — the capturing group has an outer {@code +} quantifier, so the backref
   * must use the last-iteration capture. The {@code VARIABLE_CAPTURE_BACKREF} engine does not track
   * which iteration produced the final capture. Fixing this requires iteration-state tracking in
   * the backref engine. Classification: NEEDS-RND.
   */
  @Test
  @Disabled("B13: outer quantifier on backref group in VARIABLE_CAPTURE_BACKREF — NEEDS-RND")
  void b13_outerQuantifierOnBackrefGroup() {
    ReggieMatcher m = Reggie.compile("(a)+\\1");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B13: (a)+\\1 must stay native — remove hasOuterQuantifierOnBackrefGroup fallback");

    // JDK agreement: "aa" matches — group 1 last capture = "a", backref = "a"
    MatchResult r = m.findMatch("aa");
    assertNotNull(r, "B13: (a)+\\1 must match 'aa'");
    assertEquals("a", r.group(1), "B13: group 1 last capture must be 'a'");

    // No match when backref fails
    assertNull(m.findMatch("ab"), "B13: (a)+\\1 must not match 'ab'");
  }

  // ── B14: outer quantifier on unsupported backref group in OPTIONAL_GROUP_BACKREF ──────────────

  /**
   * B14: {@code (a?)+\1} — nullable group with outer quantifier. This combines zero-length capture
   * (nullable body) and last-iteration tracking (outer quantifier), both of which are unsolved
   * sub-problems for {@code OPTIONAL_GROUP_BACKREF}. Classification: NEEDS-RND.
   */
  @Test
  @Disabled("B14: nullable group + outer quantifier in OPTIONAL_GROUP_BACKREF — NEEDS-RND")
  void b14_outerQuantifierOnUnsupportedBackrefGroup() {
    ReggieMatcher m = Reggie.compile("(a?)+\\1");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "B14: (a?)+\\1 must stay native — remove hasOuterQuantifierOnUnsupportedBackrefGroup "
            + "fallback");

    // JDK agreement: "aa" matches — outer + iterates at least once, last capture = "a"
    MatchResult r = m.findMatch("aa");
    assertNotNull(r, "B14: (a?)+\\1 must match 'aa'");
    assertEquals("a", r.group(1), "B14: group 1 last capture must be 'a'");
  }
}
