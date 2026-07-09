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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the three OPTIONAL_GROUP_BACKREF bugs documented in
 * doc/2026-07-09-optional-group-backref-backtracking-bug.md.
 *
 * <p>Bug 1: no backtracking between the optional group and the suffix — a greedy group-present
 * attempt that fails downstream never retried the group-absent branch, and could leak a stale
 * capture into the result.
 *
 * <p>Bug 2: prefix/middle content was parsed by the detector but never matched by the generator —
 * fixed by rejecting such patterns from this strategy (they fall back to BITSTATE_CAPTURE, which
 * handles them correctly) rather than silently mismatching.
 *
 * <p>Bug 3: {@code hasEndAnchor} was computed but never enforced by find()/findMatch()/findFrom().
 */
class OptionalGroupBackrefBugFixTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  private static PatternAnalyzer.MatchingStrategy routedStrategy(String pattern) throws Exception {
    return StrategyCorrectnessMetaTest.routeOf(pattern);
  }

  // --- Bug 1: backtracking between group and suffix -----------------------------------------

  @Test
  void backtracksFromGroupPresentToAbsent_whenSuffixFailsAfterGreedyAttempt() throws Exception {
    // (a)?\1{0,3}a on "a": greedy group-present consumes 'a' at pos 0, leaving nothing for the
    // mandatory suffix 'a' that follows the backref, so the whole present-branch fails; must
    // retry group-absent, where the backref is satisfied vacuously (0 reps, min=0) and the
    // mandatory suffix then matches the original 'a'. Confirmed against JDK: matches, group 1
    // unmatched.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF, routedStrategy("(a)?\\1{0,3}a"));

    ReggieMatcher m = Reggie.compile("(a)?\\1{0,3}a");
    Pattern jdk = Pattern.compile("(a)?\\1{0,3}a");
    Matcher jm = jdk.matcher("a");

    assertEquals(jm.matches(), m.matches("a"), "matches() must agree with JDK on \"a\"");
    assertTrue(m.matches("a"));

    MatchResult r = m.match("a");
    assertTrue(jm.matches());
    assertNull(jm.group(1), "JDK: group 1 must be unmatched (backtracked to group-absent)");
    assertNull(r.group(1), "Reggie: group 1 must be unmatched, not a stale [0,1) capture");
  }

  @Test
  void findMatch_backtracksFromGroupPresentToAbsent() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF, routedStrategy("(a)?\\1{0,3}a"));

    ReggieMatcher m = Reggie.compile("(a)?\\1{0,3}a");
    MatchResult r = m.findMatch("xax");
    assertTrue(r != null, "findMatch should find 'a' at index 1 via the group-absent branch");
    assertEquals(1, r.start());
    assertEquals(2, r.end());
    assertNull(r.group(1));
  }

  @Test
  void backtracksFromGroupPresentToAbsent_forMultiCharLiteralGroup() throws Exception {
    // (cow|)\1{2}c: empty-alt form with multi-char literal content ("cow"), exercising the
    // generateGroupBacktrackTree branch for entry.literalString != null (as opposed to the
    // single-char branch exercised by the other Bug 1 tests above). Present branch matches
    // "cow", leaving no room for the mandatory 2 backref repetitions, so it backtracks to the
    // empty-alt's always-captures-empty absent branch, where the single-char suffix 'c' then
    // matches at position 0.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF, routedStrategy("(cow|)\\1{2}c"));

    ReggieMatcher m = Reggie.compile("(cow|)\\1{2}c");
    Pattern jdk = Pattern.compile("(cow|)\\1{2}c");

    for (String in : new String[] {"cowc", "xcowcy"}) {
      Matcher jm = jdk.matcher(in);
      boolean jdkFound = jm.find();
      MatchResult r = m.findMatch(in);
      assertEquals(jdkFound, r != null, "findMatch on \"" + in + "\"");
      if (jdkFound) {
        assertEquals(jm.start(), r.start(), "start on \"" + in + "\"");
        assertEquals(jm.end(), r.end(), "end on \"" + in + "\"");
        assertEquals(jm.start(1), r.start(1), "group1 start on \"" + in + "\"");
        assertEquals(jm.end(1), r.end(1), "group1 end on \"" + in + "\"");
      }
    }
  }

  // --- Bug 2: prefix/middle now rejected (falls back to a correct strategy) ------------------

  @Test
  void prefixPattern_isNotRoutedToOptionalGroupBackref_butStillMatchesCorrectly() throws Exception {
    String pattern = "x(a)?\\1b";
    assertFalse(
        routedStrategy(pattern) == PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF,
        "patterns with a prefix before the optional group must not use OPTIONAL_GROUP_BACKREF "
            + "(the generator has no prefix support)");

    // No strategy currently handles this shape strictly, so it needs the JDK-delegation
    // fallback — that is strictly better than OPTIONAL_GROUP_BACKREF's old silent mismatch.
    ReggieMatcher m = Reggie.compile(pattern, WITH_FALLBACK);
    Pattern jdk = Pattern.compile(pattern);

    assertEquals(jdk.matcher("aab").matches(), m.matches("aab"), "\"aab\" (missing prefix)");
    assertEquals(jdk.matcher("xaab").matches(), m.matches("xaab"), "\"xaab\" (prefix present)");
    assertFalse(m.matches("aab"), "must NOT match without the 'x' prefix");
    assertTrue(m.matches("xaab"), "must match with the 'x' prefix");
  }

  @Test
  void middlePattern_isNotRoutedToOptionalGroupBackref_butStillMatchesCorrectly() throws Exception {
    String pattern = "(a)?x\\1b";
    assertFalse(
        routedStrategy(pattern) == PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF,
        "patterns with content between the group and the backref must not use "
            + "OPTIONAL_GROUP_BACKREF (the generator has no middle-content support)");

    ReggieMatcher m = Reggie.compile(pattern, WITH_FALLBACK);
    Pattern jdk = Pattern.compile(pattern);

    assertEquals(jdk.matcher("xb").matches(), m.matches("xb"));
    assertEquals(jdk.matcher("axab").matches(), m.matches("axab"));
    assertFalse(m.matches("xb"), "group 1 unmatched makes the mandatory \\1 fail");
    assertTrue(m.matches("axab"));
  }

  // --- Bug 3: end anchor enforcement in find()/findMatch()/findFrom() ------------------------

  @Test
  void findMatch_enforcesEndAnchor() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF, routedStrategy("^(a)?\\1$"));

    ReggieMatcher m = Reggie.compile("^(a)?\\1$");
    Pattern jdk = Pattern.compile("^(a)?\\1$");

    assertEquals(jdk.matcher("aax").find(), m.find("aax"), "\"aax\" has trailing content after $");
    assertFalse(m.find("aax"), "must NOT match — trailing 'x' violates the end anchor");
    assertTrue(m.find("aa"), "sanity: still matches without trailing content");
  }

  @Test
  void findFrom_enforcesEndAnchor() {
    ReggieMatcher m = Reggie.compile("^(a)?\\1$");
    assertEquals(-1, m.findFrom("aax", 0), "must NOT find a match — trailing 'x' violates $");
    assertEquals(0, m.findFrom("aa", 0));
  }
}
