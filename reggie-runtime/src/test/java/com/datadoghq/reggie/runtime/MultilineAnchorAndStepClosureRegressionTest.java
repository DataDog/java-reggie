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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for two defects fixed in this PR:
 *
 * <ul>
 *   <li>Defect A: O(stateCount) allocation in {@code rejectStepClosure}/{@code findStepClosure}
 *       (correctness verified via observable behavior; allocation removal is transparent).
 *   <li>Defect B: multiline {@code ^} patterns must not be routed to {@code
 *       SPECIALIZED_MULTI_GROUP_GREEDY}; they must match at every line start, not only at pos==0.
 * </ul>
 *
 * <p>Group A covers Defect B routing + correctness. Group B covers Defect A step-closure
 * correctness. Group C covers the sibling zero-length-accept pruning fix for multiline {@code ^}.
 */
public class MultilineAnchorAndStepClosureRegressionTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void assertRoute(String pattern, PatternAnalyzer.MatchingStrategy expected)
      throws Exception {
    PatternAnalyzer.MatchingStrategy actual = StrategyCorrectnessMetaTest.routeOf(pattern);
    assertEquals(
        expected, actual, "routing changed for /" + pattern + "/ — fix would not be exercised");
  }

  private static void assertNotRoute(String pattern, PatternAnalyzer.MatchingStrategy forbidden)
      throws Exception {
    PatternAnalyzer.MatchingStrategy actual = StrategyCorrectnessMetaTest.routeOf(pattern);
    assertNotEquals(forbidden, actual, "pattern /" + pattern + "/ must NOT route to " + forbidden);
  }

  // ---------------------------------------------------------------------------
  // Group A — Defect B: multiline ^ must not route to SPECIALIZED_MULTI_GROUP_GREEDY
  // ---------------------------------------------------------------------------

  /**
   * A1: multiline ^ with two capture groups must produce all line-start matches, not only pos==0.
   *
   * <p>Pattern: {@code (?m)^(\d+)-(\w+)}, Input: {@code "123-abc\n456-def\n"}
   */
  @Test
  void a1_multilineCaretMultiGroup_allLineMatches() throws Exception {
    String pattern = "(?m)^(\\d+)-(\\w+)";
    String input = "123-abc\n456-def\n";

    assertNotRoute(pattern, PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTI_GROUP_GREEDY);

    ReggieMatcher m = Reggie.compile(pattern);
    List<MatchResult> all = m.findAll(input);

    assertEquals(2, all.size(), "expected exactly 2 line matches");

    MatchResult first = all.get(0);
    assertEquals(0, first.start(), "first match start");
    assertEquals("123", first.group(1), "first match group(1)");
    assertEquals("abc", first.group(2), "first match group(2)");

    MatchResult second = all.get(1);
    assertEquals(8, second.start(), "second match start");
    assertEquals("456", second.group(1), "second match group(1)");
    assertEquals("def", second.group(2), "second match group(2)");
  }

  /**
   * A2: multiline ^ with uppercase letter groups and literal separator. Both lines must be matched.
   *
   * <p>Pattern: {@code (?m)^([A-Z]+):([0-9]+)}, Input: {@code "FOO:1\nBAR:2"}
   */
  @Test
  void a2_multilineCaretLiteralSeparator_bothLines() throws Exception {
    String pattern = "(?m)^([A-Z]+):([0-9]+)";
    String input = "FOO:1\nBAR:2";

    assertNotRoute(pattern, PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTI_GROUP_GREEDY);

    ReggieMatcher m = Reggie.compile(pattern);
    List<MatchResult> all = m.findAll(input);

    assertEquals(2, all.size(), "expected exactly 2 matches");

    assertEquals("FOO", all.get(0).group(1), "first match group(1)");
    assertEquals("1", all.get(0).group(2), "first match group(2)");

    assertEquals("BAR", all.get(1).group(1), "second match group(1)");
    assertEquals("2", all.get(1).group(2), "second match group(2)");
  }

  /**
   * A3: non-multiline ^ must still anchor only to input start — regression guard.
   *
   * <p>Pattern: {@code ^(\d+)-(\w+)}, Input: {@code "123-abc\n456-def\n"}
   */
  @Test
  void a3_nonMultilineCaretAnchorInputStartOnly() {
    String pattern = "^(\\d+)-(\\w+)";
    String input = "123-abc\n456-def\n";

    ReggieMatcher m = Reggie.compile(pattern);
    List<MatchResult> all = m.findAll(input);

    assertEquals(1, all.size(), "non-multiline ^ must produce exactly one match");
    assertEquals(0, all.get(0).start(), "match must be at input start");
    assertEquals("123", all.get(0).group(1), "group(1)");
    assertEquals("abc", all.get(0).group(2), "group(2)");
  }

  /**
   * A4: \A must always anchor to input start regardless of newlines.
   *
   * <p>Pattern: {@code \A(\d+)-(\w+)}, Input: {@code "123-abc\n456-def\n"}
   */
  @Test
  void a4_absoluteStartAnchorInputStartOnly() {
    String pattern = "\\A(\\d+)-(\\w+)";
    String input = "123-abc\n456-def\n";

    ReggieMatcher m = Reggie.compile(pattern);
    List<MatchResult> all = m.findAll(input);

    assertEquals(1, all.size(), "\\A must produce exactly one match");
    assertEquals(0, all.get(0).start(), "match must be at input start");
    assertEquals("123", all.get(0).group(1), "group(1)");
    assertEquals("abc", all.get(0).group(2), "group(2)");
  }

  // ---------------------------------------------------------------------------
  // Group B — Defect A: step-closure correctness (allocation fix is transparent)
  // ---------------------------------------------------------------------------

  /**
   * B1: anchored pattern with no match exercises {@code rejectStepClosure}; must return false/null.
   *
   * <p>Pattern: {@code ^foo(\d+)bar}, Input: {@code "xxxfooxxx"}
   */
  @Test
  void b1_anchoredPatternNoMatch_rejectStepClosure() {
    String pattern = "^foo(\\d+)bar";
    String input = "xxxfooxxx";

    ReggieMatcher m = Reggie.compile(pattern);
    assertFalse(m.find(input), "find() must return false — no match");
    assertNull(m.findMatch(input), "findMatch() must return null — no match");
  }

  /**
   * B2: anchor-free pattern exercises {@code findStepClosure}; must find embedded match.
   *
   * <p>Pattern: {@code (\d{3})-(\d{4})}, Input: {@code "call 555-1234 now"}
   */
  @Test
  void b2_anchorFreePattern_findStepClosure() {
    String pattern = "(\\d{3})-(\\d{4})";
    String input = "call 555-1234 now";

    ReggieMatcher m = Reggie.compile(pattern);
    assertTrue(m.find(input), "find() must return true");

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "findMatch() must not be null");
    assertEquals(5, r.start(), "match start");
    assertEquals("555", r.group(1), "group(1)");
    assertEquals("1234", r.group(2), "group(2)");
  }

  /**
   * B3: pattern with start + end anchors exercises {@code rejectStepClosure} with non-trivial
   * reinject closure.
   *
   * <p>Pattern: {@code ^(\w+)$}, Input: {@code "hello"}
   */
  @Test
  void b3_startEndAnchorPattern_matches() {
    String pattern = "^(\\w+)$";
    String input = "hello";

    ReggieMatcher m = Reggie.compile(pattern);
    assertTrue(m.matches(input), "matches() must return true");

    MatchResult r = m.match(input);
    assertNotNull(r, "match() must not be null");
    assertEquals("hello", r.group(1), "group(1)");
  }

  /**
   * B4: alternation exercises {@code findStepClosure} with overlapping closure state ids; find must
   * succeed.
   *
   * <p>Pattern: {@code (\d+)|\w+}, Input: {@code "abc123"}
   */
  @Test
  void b4_alternationOverlappingClosures_findSucceeds() {
    String pattern = "(\\d+)|\\w+";
    String input = "abc123";

    ReggieMatcher m = Reggie.compile(pattern);
    assertTrue(m.find(input), "find() must return true");
  }

  // ---------------------------------------------------------------------------
  // Group C — zero-length-accept pruning for multiline ^
  // ---------------------------------------------------------------------------

  /**
   * C1: multiline {@code ^} (zero-length match) must match at every line boundary, not just
   * fromPos.
   *
   * <p>Pattern: {@code (?m)^}, Input: {@code "a\nb"}
   */
  @Test
  void c1_multilineCaretZeroLength_allLineBoundaries() {
    String pattern = "(?m)^";
    String input = "a\nb";

    ReggieMatcher m = Reggie.compile(pattern);
    List<MatchResult> all = m.findAll(input);

    // Expect at least the two line starts: pos=0 and pos=2 (after '\n')
    assertTrue(all.size() >= 2, "must find at least 2 zero-length matches; got " + all.size());

    assertEquals(0, all.get(0).start(), "first zero-length match at input start");
    assertEquals(0, all.get(0).end(), "first match is zero-length");

    assertEquals(2, all.get(1).start(), "second zero-length match after newline");
    assertEquals(2, all.get(1).end(), "second match is zero-length");
  }

  /**
   * C2: non-zero-length multiline {@code ^} match at both line boundaries; no spurious pruning.
   *
   * <p>Pattern: {@code (?m)^(abc)}, Input: {@code "abc\nabc"}
   */
  @Test
  void c2_multilineCaretNonZeroLength_noPruning() {
    String pattern = "(?m)^(abc)";
    String input = "abc\nabc";

    ReggieMatcher m = Reggie.compile(pattern);
    List<MatchResult> all = m.findAll(input);

    assertEquals(2, all.size(), "expected 2 matches — one per line");

    assertEquals(0, all.get(0).start(), "first match start");
    assertEquals("abc", all.get(0).group(1), "first match group(1)");

    assertEquals(4, all.get(1).start(), "second match start");
    assertEquals("abc", all.get(1).group(1), "second match group(1)");
  }
}
