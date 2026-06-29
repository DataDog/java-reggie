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
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import org.junit.jupiter.api.Test;

class PikeVMRoutingTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @Test
  void captureAmbiguousRoutes_toPikevmCapture() throws Exception {
    // (a)?b has a nullable outer quantifier on a capturing group (B16): PIKEVM_CAPTURE gives
    // correct per-iteration spans; DFA_UNROLLED_WITH_GROUPS POSIX last-match span is wrong.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(a)?b"),
        "(a)?b must route to PIKEVM_CAPTURE");
  }

  @Test
  void captureAmbiguousRoutes_dotOptionalB() throws Exception {
    // (.)?b: nullable outer quantifier on capturing group — PIKEVM_CAPTURE.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(.)?b"),
        "(.)?b must route to PIKEVM_CAPTURE");
  }

  @Test
  void captureAmbiguousMatcher_matchesCorrectly() {
    ReggieMatcher m = Reggie.compile("(a)?b", WITH_FALLBACK);
    assertTrue(m.matches("ab"), "should match 'ab'");
    assertTrue(m.matches("b"), "should match 'b'");
    assertFalse(m.matches("a"), "should not match 'a'");
    assertTrue(m.find("xaby"), "should find in 'xaby'");
    assertTrue(m.find("xby"), "should find in 'xby'");
  }

  // ── Alternation-priority patterns now route to native DFA (declined guard retired) ──

  @Test
  void foOrFoo_routesToDfaWithGroups() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(fo|foo)"),
        "(fo|foo) must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void aOrAb_routesToDfaWithGroups() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(a|ab)"),
        "(a|ab) must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void aaOrAThenA_routesToDfaWithGroups() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(aa|a)a"),
        "(aa|a)a must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void foOrFoo_findMatchCorrect() {
    ReggieMatcher m = Reggie.compile("(fo|foo)");
    // find() must return leftmost first-alternative match (priority-cut: "fo" wins over "foo")
    assertTrue(m.find("foo"));
    assertEquals(
        "fo",
        m.findMatch("foo").group(0),
        "(fo|foo) find on 'foo' must return 'fo' (first alternative wins)");
    // match() must match the full input — 'foo' alternative covers "foo"
    assertNotNull(m.match("foo"), "(fo|foo) match on 'foo' must succeed (full-input match)");
    assertEquals(
        "foo", m.match("foo").group(0), "(fo|foo) match on 'foo' must return 'foo' (full-input)");
  }

  @Test
  void aOrAb_findMatchCorrect() {
    ReggieMatcher m = Reggie.compile("(a|ab)");
    // "a" wins over "ab" in scanning (priority-cut)
    assertTrue(m.find("ab"));
    assertEquals(
        "a",
        m.findMatch("ab").group(0),
        "(a|ab) find on 'ab' must return 'a' (first alternative wins)");
  }

  // -------------------------------------------------------------------------
  // Class E: interacting alternations wrapped in non-capturing groups
  // -------------------------------------------------------------------------

  @Test
  void ncgWrappedInteractingAlts_routesToPikeVmCapture() throws Exception {
    // ((?:a|ab))((?:c|bcd)) — same Class E shape as (a|ab)(c|bcd) but alternations
    // are wrapped in a transparent non-capturing group; capturingGroupAlternation must
    // unwrap the NCG layer to detect the interacting variable-length alternations.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("((?:a|ab))((?:c|bcd))"),
        "((?:a|ab))((?:c|bcd)) must route to PIKEVM_CAPTURE (Class E via NCG unwrap)");
  }

  @Test
  void ncgWrappedInteractingAlts_captureCorrect() {
    // ((?:a|ab))((?:c|bcd)) on "abcd": JDK leftmost-longest → group(1)="a", group(2)="bcd"
    ReggieMatcher m = Reggie.compile("((?:a|ab))((?:c|bcd))");
    MatchResult r = m.findMatch("abcd");
    assertNotNull(r, "must find a match in 'abcd'");
    assertEquals("a", r.group(1), "group(1) must be 'a'");
    assertEquals("bcd", r.group(2), "group(2) must be 'bcd'");
  }

  // ── A2: capturing group absent from some alternation branch ────────────────

  @Test
  void groupAbsentFromAlt_literalThenGroup_routesToPikevm() throws Exception {
    // Group 1 `(.)` only in alt 2; DFA_UNROLLED_WITH_GROUPS binds group 1 when alt 1 wins.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("[a][1-b]|(.)"),
        "[a][1-b]|(.) must route to PIKEVM_CAPTURE (A2: group absent from alt 1)");
  }

  @Test
  void groupAbsentFromAlt_dotThenGroup_routesToPikevm() throws Exception {
    // Group 1 `(_)` only in alt 2.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("_.|(_)"),
        "_.|(_) must route to PIKEVM_CAPTURE (A2: group absent from alt 2)");
  }

  @Test
  void groupAbsentFromAlt_groupFirstAlt_routesToPikevm() throws Exception {
    // Group 1 `(1)` only in alt 1; DFA binds group 1 when alt 2 wins.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(1)c|10"),
        "(1)c|10 must route to PIKEVM_CAPTURE (A2: group absent from alt 2)");
  }

  // ── A2 regression: patterns that MUST stay on DFA_UNROLLED_WITH_GROUPS ────

  @Test
  void singleGroupWrappingAlts_staysOnDfa() throws Exception {
    // (fo|foo): group wraps the whole alternation; both inner branches have no capturing group.
    // A2 guard must NOT fire — guard already tested by foOrFoo_routesToDfaWithGroups().
    // This test guards the simple (ab)c case as a minimal sanity check.
    // (ab)c has no alternation so it routes to SPECIALIZED_FIXED_SEQUENCE, not
    // DFA_UNROLLED_WITH_GROUPS.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE,
        StrategyCorrectnessMetaTest.routeOf("(ab)c"),
        "(ab)c must not be routed to PIKEVM_CAPTURE by the A2 guard (no alternation)");
  }

  // ── A1: capturing group body starts with nullable first element ────────────

  @Test
  void nullableFirstElem_optionalPrefix_routesToPikevm() throws Exception {
    // Group body `a?.*` starts with `a?` (min=0); TDFA fires group-start too early.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("-{1}(a?.*)"),
        "-{1}(a?.*) must route to PIKEVM_CAPTURE (A1: nullable first element a?)");
  }

  @Test
  void nullableFirstElem_zeroQuantifier_routesToPikevm() throws Exception {
    // Group body `0{0}[^_]{1,}` starts with `0{0}` (min=0,max=0); TDFA group-start fires too early.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(0{0}[^_]{1,})-"),
        "(0{0}[^_]{1,})- must route to PIKEVM_CAPTURE (A1: nullable first element 0{0})");
  }

  // ── A1 regression: patterns that MUST NOT route to PIKEVM_CAPTURE ────

  @Test
  void nonNullableGroupBody_staysOnDfa() throws Exception {
    // Group body `abc` starts with literal `a` (non-nullable); A1 guard must NOT fire.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        StrategyCorrectnessMetaTest.routeOf("(abc)"),
        "(abc) must not be routed to PIKEVM_CAPTURE by the A1 guard (non-nullable first element)");
  }

  // ── B3b: $ (end-of-line anchor) in alternation ─────────────────────────────

  // ── B3a: anchor-only capturing group body ──────────────────────────────────

  @Test
  void anchorOnlyGroupBody_routesToPikevm() throws Exception {
    // ($): capturing group whose body is a sole anchor — must route to PIKEVM_CAPTURE (B3a).
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("($)"),
        "($) must route to PIKEVM_CAPTURE (B3a: anchor-only capturing group body)");
  }

  // ── B3b: $ (end-of-line anchor) in alternation ─────────────────────────────

  @Test
  void dollarInAlternation_routesToPikevm() throws Exception {
    // $|[^c]{1}: $ anchor in alternation — must route to PIKEVM_CAPTURE (B3b).
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("$|[^c]{1}"),
        "$|[^c]{1} must route to PIKEVM_CAPTURE (B3b: $ in alternation)");
  }

  @Test
  void dollarInAlternationAlt_routesToPikevm() throws Exception {
    // $|[^0]{1}: $ anchor in alternation — must route to PIKEVM_CAPTURE (B3b).
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("$|[^0]{1}"),
        "$|[^0]{1} must route to PIKEVM_CAPTURE (B3b: $ in alternation)");
  }

  // ── B6: FIXED_REPETITION_BACKREF declined when suffix is non-empty ──────────

  @Test
  void fixedRepetitionBackrefWithSuffix_routesToOptimizedNfaWithBackrefs() throws Exception {
    // (.)\\1{2}. has a non-empty suffix (the trailing dot) — bytecode places the
    // group-end tag after the suffix is consumed, producing wrong spans.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS,
        StrategyCorrectnessMetaTest.routeOf("(.)\\1{2}."),
        "(.)\\1{2}. must route to OPTIMIZED_NFA_WITH_BACKREFS (B6: non-empty suffix)");
  }

  @Test
  void fixedRepetitionBackrefNoSuffix_staysOnFixedRepetitionBackref() throws Exception {
    // (.)\\1{2} has no suffix — FIXED_REPETITION_BACKREF is correct.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF,
        StrategyCorrectnessMetaTest.routeOf("(.)\\1{2}"),
        "(.)\\1{2} must stay on FIXED_REPETITION_BACKREF (B6: no suffix)");
  }

  @Test
  void greedyDotPlusWithSuffix_routesToPikevm() throws Exception {
    // (.+)_ has a greedy .+ group with a literal suffix — GREEDY_BACKTRACK's indexOf scan
    // overshoots on inputs ending with '\n' (B4: greedy .+ with non-group suffix).
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(.+)_"),
        "(.+)_ must route to PIKEVM_CAPTURE (B4: greedy .+ with suffix)");
  }

  @Test
  void greedyDotPlusWrappedInNonCapturing_routesToPikevm() throws Exception {
    // (?:(.+))_ — the capturing group is wrapped inside a non-capturing group at the concat level.
    // B4 detection must unwrap the non-capturing outer group to find the (.+) capture.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(?:(.+))_"),
        "(?:(.+))_ must route to PIKEVM_CAPTURE (B4: non-capturing wrapper around capture)");
  }

  @Test
  void greedyDotPlusBodyWrappedInNonCapturing_routesToPikevm() throws Exception {
    // ((?:.+))_ — the .+ quantifier is wrapped inside a non-capturing group inside the capture.
    // B4 detection must also unwrap the non-capturing inner group to find the quantifier.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("((?:.+))_"),
        "((?:.+))_ must route to PIKEVM_CAPTURE (B4: non-capturing wrapper around quantifier body)");
  }

  @Test
  void greedyDotStarWithSuffix_staysOnGreedyBacktrack() throws Exception {
    // (.*)_ has a greedy .* group (min=0): not affected by the B4 decline (min>=1 only).
    assertEquals(
        PatternAnalyzer.MatchingStrategy.GREEDY_BACKTRACK,
        StrategyCorrectnessMetaTest.routeOf("(.*)_"),
        "(.*)_ must stay on GREEDY_BACKTRACK (B4: only declines min>=1)");
  }

  // ── B5: variable-length alternation group body ──────────────────────────────

  @Test
  void variableLengthAltInGroup_routesToPikevm() throws Exception {
    // ([1]|1.)[b]_: group 1 has alternation with branch lengths 1 and 2 — variable-length (B5).
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("([1]|1.)[b]_"),
        "([1]|1.)[b]_ must route to PIKEVM_CAPTURE (B5: variable-length alt in group)");
  }

  @Test
  void variableLengthAltWrappedInNonCapturing_routesToPikevm() throws Exception {
    // ((?:[1]|1.))[b]_ — the alternation is wrapped in a non-capturing group inside the capture.
    // B5 detection must unwrap the non-capturing wrapper before checking for AlternationNode.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("((?:[1]|1.))[b]_"),
        "((?:[1]|1.))[b]_ must route to PIKEVM_CAPTURE (B5: non-capturing wrapper around alt)");
  }

  @Test
  void fixedLengthAltInGroup_staysOnDfa() throws Exception {
    // ([a]|[b])c: both alternatives have length 1 — fixed-length, should NOT trigger B5.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("([a]|[b])c"),
        "([a]|[b])c must stay on DFA_UNROLLED_WITH_GROUPS (B5: same-length alts not triggered)");
  }
}
