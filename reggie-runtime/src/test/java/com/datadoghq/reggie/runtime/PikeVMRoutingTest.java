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
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(ab)c"),
        "(ab)c must stay on DFA_UNROLLED_WITH_GROUPS (no alternation, non-nullable body)");
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

  // ── A1 regression: patterns that MUST stay on DFA_UNROLLED_WITH_GROUPS ────

  @Test
  void nonNullableGroupBody_staysOnDfa() throws Exception {
    // Group body `abc` starts with literal `a` (non-nullable); A1 guard must NOT fire.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(abc)"),
        "(abc) must stay on DFA_UNROLLED_WITH_GROUPS (non-nullable first element)");
  }
}
