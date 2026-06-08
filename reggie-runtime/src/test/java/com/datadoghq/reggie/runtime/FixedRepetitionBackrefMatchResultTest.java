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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the rich MatchResult API (match/findMatch/findMatchFrom/matchesBounded/matchBounded) on
 * FIXED_REPETITION_BACKREF patterns after promotion to NATIVE strategy.
 */
public class FixedRepetitionBackrefMatchResultTest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ---- routing assertions ----

  private static PatternAnalyzer.MatchingStrategy routeOf(String pattern) throws Exception {
    return StrategyCorrectnessMetaTest.routeOf(pattern);
  }

  @Test
  public void testExactRepetitionRoutesToFixedRepetitionBackref() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF,
        routeOf("(a)\\1{2}"),
        "Pattern (a)\\1{2} should route to FIXED_REPETITION_BACKREF");
  }

  @Test
  public void testBoundedRepetitionRoutesToFixedRepetitionBackref() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF,
        routeOf("(\\w)\\1{1,3}"),
        "Pattern (\\w)\\1{1,3} should route to FIXED_REPETITION_BACKREF");
  }

  @Test
  public void testStrategyIsNative() throws Exception {
    assertEquals(
        StrategyJdkClassifier.StrategyJdkClass.NATIVE,
        StrategyJdkClassifier.classifyJdkDependency(
            PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF),
        "FIXED_REPETITION_BACKREF should be classified as NATIVE");
  }

  // ---- (a)\1{2}: matches exactly "aaa" ----

  @Test
  public void testMatchExact_success() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    MatchResult result = matcher.match("aaa");

    assertNotNull(result, "match(\"aaa\") should succeed");
    assertEquals("aaa", result.group(0), "group(0) should be full match");
    assertEquals("a", result.group(1), "group(1) should capture 'a'");
    assertEquals(0, result.start(), "match should start at 0");
    assertEquals(3, result.end(), "match should end at 3");
  }

  @Test
  public void testMatchExact_tooShort() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertNull(matcher.match("aa"), "match(\"aa\") should return null (too short)");
  }

  @Test
  public void testMatchExact_tooLong() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertNull(
        matcher.match("aaaa"), "match(\"aaaa\") should return null (too long for whole-input)");
  }

  @Test
  public void testMatchExact_wrongChar() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertNull(matcher.match("bbb"), "match(\"bbb\") should return null");
  }

  // ---- findMatch for (a)\1{2} ----

  @Test
  public void testFindMatchExact_embedded() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    MatchResult result = matcher.findMatch("xaaaz");

    assertNotNull(result, "findMatch(\"xaaaz\") should succeed");
    assertEquals(1, result.start(), "match should start at 1");
    assertEquals(4, result.end(), "match should end at 4");
    assertEquals("aaa", result.group(0), "group(0) should be 'aaa'");
    assertEquals("a", result.group(1), "group(1) should be 'a'");
  }

  @Test
  public void testFindMatchExact_notFound() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertNull(matcher.findMatch("xyz"), "findMatch(\"xyz\") should return null");
  }

  @Test
  public void testFindMatchExact_atStart() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    MatchResult result = matcher.findMatch("aaaxxx");

    assertNotNull(result, "findMatch(\"aaaxxx\") should succeed");
    assertEquals(0, result.start(), "match should start at 0");
    assertEquals(3, result.end(), "match should end at 3");
  }

  @Test
  public void testFindMatchExact_atEnd() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    MatchResult result = matcher.findMatch("xxxaaa");

    assertNotNull(result, "findMatch(\"xxxaaa\") should succeed");
    assertEquals(3, result.start(), "match should start at 3");
    assertEquals(6, result.end(), "match should end at 6");
  }

  // ---- findMatchFrom for (a)\1{2} ----

  @Test
  public void testFindMatchFromExact_skipsEarlierMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    MatchResult result = matcher.findMatchFrom("aaaxaaa", 1);

    assertNotNull(result, "findMatchFrom(\"aaaxaaa\", 1) should succeed");
    assertEquals(4, result.start(), "match should start at 4 (skipping earlier match)");
    assertEquals(7, result.end(), "match should end at 7");
  }

  @Test
  public void testFindMatchFromExact_notFound() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertNull(
        matcher.findMatchFrom("aaa", 1), "findMatchFrom past the only match should return null");
  }

  // ---- matchesBounded for (a)\1{2} ----

  @Test
  public void testMatchesBoundedExact_success() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertTrue(
        matcher.matchesBounded("xaaay", 1, 4), "matchesBounded [1,4) on 'xaaay' should succeed");
  }

  @Test
  public void testMatchesBoundedExact_failure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertFalse(
        matcher.matchesBounded("xaaay", 0, 4), "matchesBounded [0,4) on 'xaaay' should fail");
  }

  // ---- matchBounded for (a)\1{2} ----

  @Test
  public void testMatchBoundedExact_success() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    MatchResult result = matcher.matchBounded("xaaay", 1, 4);

    assertNotNull(result, "matchBounded [1,4) on 'xaaay' should succeed");
    assertEquals("aaa", result.group(0), "group(0) should be 'aaa'");
  }

  @Test
  public void testMatchBoundedExact_failure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(a)\\1{2}");
    assertNull(
        matcher.matchBounded("xaaay", 0, 4), "matchBounded [0,4) should fail (starts with 'x')");
  }

  // ---- (\w)\1{1,3}: bounded repetition ----

  @Test
  public void testMatchBounded_minReps() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w)\\1{1,3}");
    MatchResult result = matcher.match("bb");

    assertNotNull(result, "match(\"bb\") should succeed (1 rep after capture)");
    assertEquals("bb", result.group(0), "group(0) should be 'bb'");
    assertEquals("b", result.group(1), "group(1) should be 'b'");
  }

  @Test
  public void testMatchBounded_maxReps() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w)\\1{1,3}");
    MatchResult result = matcher.match("bbbb");

    assertNotNull(result, "match(\"bbbb\") should succeed (3 reps after capture)");
    assertEquals("bbbb", result.group(0), "group(0) should be 'bbbb'");
    assertEquals("b", result.group(1), "group(1) should be 'b'");
  }

  @Test
  public void testMatchBounded_tooManyReps() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w)\\1{1,3}");
    assertNull(matcher.match("bbbbb"), "match(\"bbbbb\") should return null (exceeds maxReps)");
  }

  @Test
  public void testMatchBounded_tooFewReps() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w)\\1{1,3}");
    assertNull(matcher.match("b"), "match(\"b\") should return null (0 reps, need at least 1)");
  }

  @Test
  public void testFindMatchBounded_success() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w)\\1{1,3}");
    MatchResult result = matcher.findMatch("xbbby");

    assertNotNull(result, "findMatch(\"xbbby\") should succeed");
    assertEquals(1, result.start(), "match should start at 1");
    assertEquals(4, result.end(), "match should end at 4");
    assertEquals("bbb", result.group(0), "group(0) should be 'bbb'");
    assertEquals("b", result.group(1), "group(1) should be 'b'");
  }

  @Test
  public void testFindMatchBounded_greedyMaxReps() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w)\\1{1,3}");
    MatchResult result = matcher.findMatch("xbbbby");

    assertNotNull(result, "findMatch(\"xbbbby\") should succeed");
    assertEquals(1, result.start(), "match should start at 1");
    // Greedy: consumes up to maxReps=3 repetitions, so matched chars = 1 (capture) + 3 (reps) = 4
    assertEquals(5, result.end(), "match should end at 5 (greedy, 1 capture + 3 reps)");
  }
}
