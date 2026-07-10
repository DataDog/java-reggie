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
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code SPECIALIZED_OPTIONAL_GROUP} strategy: {@code (anchor-start)?
 * (optional-group) (suffix-literal-char) (anchor-end)?} shapes such as {@code (a)?b}, {@code
 * ^(a)?b$}, {@code (?<x>a)?b}.
 *
 * <p>Targets the three bug shapes documented in {@code
 * doc/2026-07-09-optional-group-backref-backtracking-bug.md} (missing group/suffix backtrack,
 * dropped prefix, unenforced end anchor) so they can't silently reappear in this from-scratch
 * generator.
 */
class SpecializedOptionalGroupTest {

  // ── Routing ────────────────────────────────────────────────────────────────

  @Test
  void routesToSpecializedOptionalGroup() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP,
        StrategyCorrectnessMetaTest.routeOf("(a)?b"));
    assertEquals(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP,
        StrategyCorrectnessMetaTest.routeOf("(?<x>a)?b"));
  }

  // ── Bug-1 shape: group content overlaps suffix ──────────────────────────────

  @Test
  void groupContentOverlapsSuffix_matches() {
    ReggieMatcher m = Reggie.compile("(a)?a");
    assertTrue(m.matches("a"));
    assertTrue(m.matches("aa"));
    assertFalse(m.matches("aaa"));
  }

  @Test
  void groupContentOverlapsSuffix_groupSpans() {
    ReggieMatcher m = Reggie.compile("(a)?a");

    MatchResult r1 = m.match("a");
    assertNotNull(r1);
    assertEquals(-1, r1.start(1));
    assertEquals(-1, r1.end(1));

    MatchResult r2 = m.match("aa");
    assertNotNull(r2);
    assertEquals(0, r2.start(1));
    assertEquals(1, r2.end(1));
  }

  @Test
  void groupContentOverlapsSuffix_boundaryCase() {
    // (a)?b on "aab": the greedy group-present attempt at pos 0 sees 'a' then 'a' (not the 'b'
    // suffix) and must retry with the group absent - the retry Bug 1 was missing.
    ReggieMatcher m = Reggie.compile("(a)?b");
    Pattern jp = Pattern.compile("(a)?b");
    assertEquals(jp.matcher("aab").find(), m.find("aab"));

    MatchResult r = m.findMatch("aab");
    java.util.regex.Matcher jm = jp.matcher("aab");
    assertTrue(jm.find());
    assertNotNull(r);
    assertEquals(jm.start(), r.start());
    assertEquals(jm.end(), r.end());
    assertEquals(jm.start(1) == -1 ? -1 : jm.start(1), r.start(1));
    assertEquals(jm.end(1) == -1 ? -1 : jm.end(1), r.end(1));
  }

  // ── No-prefix enforcement ────────────────────────────────────────────────────

  @Test
  void prefixPattern_doesNotRouteToSpecializedOptionalGroup() throws Exception {
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP,
        StrategyCorrectnessMetaTest.routeOf("x(a)?b"));

    // Still correct via whatever strategy it does route to.
    ReggieMatcher m = Reggie.compile("x(a)?b");
    Pattern jp = Pattern.compile("x(a)?b");
    for (String in : new String[] {"xab", "xb", "ab", "", "xaby"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }

  // ── Anchors, differential against java.util.regex ──────────────────────────

  @Test
  void anchoredBothEnds_matchesJdk() {
    ReggieMatcher m = Reggie.compile("^(a)?b$");
    Pattern jp = Pattern.compile("^(a)?b$");
    for (String in : new String[] {"ab", "xab", "abx", "", "b"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }

  @Test
  void startAnchorOnly_matchesJdk() {
    ReggieMatcher m = Reggie.compile("^(a)?b");
    Pattern jp = Pattern.compile("^(a)?b");
    for (String in : new String[] {"ab", "xab", "abx", "", "b", "aby"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }

  @Test
  void endAnchorOnly_matchesJdk() {
    ReggieMatcher m = Reggie.compile("(a)?b$");
    Pattern jp = Pattern.compile("(a)?b$");
    for (String in : new String[] {"ab", "xab", "abx", "", "b", "yab"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }

  @Test
  void noAnchors_findFromEveryPosition() {
    ReggieMatcher m = Reggie.compile("(a)?b");
    Pattern jp = Pattern.compile("(a)?b");
    String input = "xxabyyabzz";
    for (int start = 0; start <= input.length(); start++) {
      java.util.regex.Matcher jm = jp.matcher(input);
      boolean jdkFound = jm.find(start);
      assertEquals(jdkFound, m.findFrom(input, start) >= 0, "findFrom diverged at " + start);
      if (jdkFound) {
        assertEquals(jm.start(), m.findFrom(input, start));
      }
    }
  }

  // ── Named group ──────────────────────────────────────────────────────────────

  @Test
  void namedGroup_presentAndAbsentBranches() {
    ReggieMatcher m = Reggie.compile("(?<x>a)?b");

    MatchResult present = m.findMatch("ab");
    assertNotNull(present);
    assertEquals("a", present.group("x"));

    MatchResult absent = m.findMatch("b");
    assertNotNull(absent);
    assertNull(absent.group("x"));
  }

  // ── Multiple matches ─────────────────────────────────────────────────────────

  @Test
  void findAll_multipleOccurrences() {
    ReggieMatcher m = Reggie.compile("(a)?b");
    Pattern jp = Pattern.compile("(a)?b");
    String input = "ab cb ab b abx";
    List<MatchResult> reggieMatches = m.findAll(input);
    java.util.regex.Matcher jm = jp.matcher(input);
    int i = 0;
    while (jm.find()) {
      assertTrue(i < reggieMatches.size(), "reggie should find as many matches as JDK");
      assertEquals(jm.start(), reggieMatches.get(i).start());
      assertEquals(jm.end(), reggieMatches.get(i).end());
      assertEquals(jm.group(), reggieMatches.get(i).group(0));
      i++;
    }
    assertEquals(i, reggieMatches.size(), "reggie should not find more matches than JDK");
  }

  // ── Out-of-scope shapes fall through correctly ──────────────────────────────

  @Test
  void literalStringGroupContent_fallsThroughCorrectly() throws Exception {
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP,
        StrategyCorrectnessMetaTest.routeOf("(ab)?c"));
    ReggieMatcher m = Reggie.compile("(ab)?c");
    Pattern jp = Pattern.compile("(ab)?c");
    for (String in : new String[] {"abc", "c", "ac", "", "xabcx"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }

  @Test
  void charClassGroupContent_fallsThroughCorrectly() throws Exception {
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP,
        StrategyCorrectnessMetaTest.routeOf("([a-z])?b"));
    ReggieMatcher m = Reggie.compile("([a-z])?b");
    Pattern jp = Pattern.compile("([a-z])?b");
    for (String in : new String[] {"ab", "b", "1b", "", "xzby"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }

  @Test
  void twoOptionalGroups_fallsThroughCorrectly() throws Exception {
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.SPECIALIZED_OPTIONAL_GROUP,
        StrategyCorrectnessMetaTest.routeOf("(a)?(b)?c"));
    ReggieMatcher m = Reggie.compile("(a)?(b)?c");
    Pattern jp = Pattern.compile("(a)?(b)?c");
    for (String in : new String[] {"abc", "ac", "bc", "c", "", "xabcx"}) {
      assertEquals(jp.matcher(in).matches(), m.matches(in), "matches diverged on '" + in + "'");
      assertEquals(jp.matcher(in).find(), m.find(in), "find diverged on '" + in + "'");
    }
  }
}
