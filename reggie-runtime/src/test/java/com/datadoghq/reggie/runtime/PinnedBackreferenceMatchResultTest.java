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
 * PINNED_BACKREFERENCE patterns: backreferences with a structurally-disjoint (provably unambiguous)
 * group boundary.
 */
public class PinnedBackreferenceMatchResultTest {

  @BeforeEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ---- routing assertions ----

  private static PatternAnalyzer.MatchingStrategy routeOf(String pattern) throws Exception {
    return StrategyCorrectnessMetaTest.routeOf(pattern);
  }

  @Test
  public void testRepeatedWordWithBoundaryAnchorsDoesNotRouteToPinnedBackreference()
      throws Exception {
    // \b(\w+)\s+\1\b has \b anchors outside the group/backref span, which the generated
    // matcher has no code path to evaluate - PINNED_BACKREFERENCE requires the group and
    // backreference to span the whole pattern, so this falls through to another strategy.
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.PINNED_BACKREFERENCE,
        routeOf("\\b(\\w+)\\s+\\1\\b"),
        "Pattern \\b(\\w+)\\s+\\1\\b has anchors outside the group/backref span and must not "
            + "route to PINNED_BACKREFERENCE");
  }

  @Test
  public void testLiteralSeparatorRoutesToPinnedBackreference() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PINNED_BACKREFERENCE,
        routeOf("(\\w+):\\1"),
        "Pattern (\\w+):\\1 should route to PINNED_BACKREFERENCE");
  }

  @Test
  public void testCharsetSeparatorRoutesToPinnedBackreference() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PINNED_BACKREFERENCE,
        routeOf("([a-z]+)\\d+\\1"),
        "Pattern ([a-z]+)\\d+\\1 should route to PINNED_BACKREFERENCE");
  }

  @Test
  public void testEmptySeparatorRoutesToPinnedBackreference() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PINNED_BACKREFERENCE,
        routeOf("(\\w+)\\b\\1"),
        "Pattern (\\w+)\\b\\1 should route to PINNED_BACKREFERENCE");
  }

  @Test
  public void testStrategyIsNative() throws Exception {
    assertEquals(
        StrategyJdkClassifier.StrategyJdkClass.NATIVE,
        StrategyJdkClassifier.classifyJdkDependency(
            PatternAnalyzer.MatchingStrategy.PINNED_BACKREFERENCE),
        "PINNED_BACKREFERENCE should be classified as NATIVE");
  }

  // ---- (\w+):\1 : match() ----

  @Test
  public void testMatch_success() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    MatchResult result = matcher.match("hello:hello");

    assertNotNull(result, "match(\"hello:hello\") should succeed");
    assertEquals("hello:hello", result.group(0), "group(0) should be full match");
    assertEquals("hello", result.group(1), "group(1) should capture 'hello'");
    assertEquals(0, result.start(), "match should start at 0");
    assertEquals(11, result.end(), "match should end at 11");
  }

  @Test
  public void testMatch_mismatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    assertNull(matcher.match("hello:world"), "match(\"hello:world\") should return null");
  }

  @Test
  public void testMatch_minimumGroupLengthOne() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    MatchResult result = matcher.match("a:a");

    assertNotNull(result, "match(\"a:a\") should succeed (group length 1)");
    assertEquals("a", result.group(1), "group(1) should be 'a'");
    assertEquals(0, result.start());
    assertEquals(3, result.end());
  }

  // ---- findMatch for (\w+):\1 ----

  @Test
  public void testFindMatch_embedded() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    MatchResult result = matcher.findMatch("xx hello:hello yy");

    assertNotNull(result, "findMatch should succeed");
    assertEquals(3, result.start(), "match should start at 3");
    assertEquals(14, result.end(), "match should end at 14");
    assertEquals("hello:hello", result.group(0));
    assertEquals("hello", result.group(1));
  }

  @Test
  public void testFindMatch_notFound() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    assertNull(matcher.findMatch("hello:world"), "findMatch should return null on mismatch");
  }

  @Test
  public void testFindMatch_atEndOfString() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    MatchResult result = matcher.findMatch("xx hello:hello");

    assertNotNull(result, "findMatch with backref site at end of string should succeed");
    assertEquals(3, result.start());
    assertEquals(14, result.end());
    assertEquals("hello:hello", result.group(0));
  }

  // ---- findMatchFrom for (\w+):\1 ----

  @Test
  public void testFindMatchFrom_skipsEarlierMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    MatchResult result = matcher.findMatchFrom("a:a x b:b", 1);

    assertNotNull(result, "findMatchFrom should skip the earlier match and find the later one");
    assertEquals(6, result.start(), "match should start at 6");
    assertEquals(9, result.end(), "match should end at 9");
    assertEquals("b:b", result.group(0));
  }

  @Test
  public void testFindMatchFrom_notFound() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    assertNull(
        matcher.findMatchFrom("a:a", 1), "findMatchFrom past the only match should return null");
  }

  // ---- matchesBounded / matchBounded for (\w+):\1 ----

  @Test
  public void testMatchesBounded_success() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    assertTrue(
        matcher.matchesBounded("xx hello:hello yy", 3, 14),
        "matchesBounded on the embedded span should succeed");
  }

  @Test
  public void testMatchesBounded_failure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    assertFalse(
        matcher.matchesBounded("xx hello:hello yy", 0, 14),
        "matchesBounded including the leading 'xx ' prefix should fail");
  }

  @Test
  public void testMatchBounded_success() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    MatchResult result = matcher.matchBounded("xx hello:hello yy", 3, 14);

    assertNotNull(result, "matchBounded on the embedded span should succeed");
    assertEquals("hello:hello", result.group(0));
  }

  @Test
  public void testMatchBounded_failure() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+):\\1");
    assertNull(
        matcher.matchBounded("xx hello:hello yy", 0, 14),
        "matchBounded with a mismatched prefix in the bound should fail");
  }

  // ---- (\w+)\s+\1 : whitespace separator ----
  // Unlike \b(\w+)\s+\1\b (covered by
  // testRepeatedWordWithBoundaryAnchorsDoesNotRouteToPinnedBackreference
  // above), this pattern has no anchors outside the group/backref span, so it routes to
  // PINNED_BACKREFERENCE - the \b anchors are zero-width, so removing them doesn't change the
  // expected match boundaries below.

  @Test
  public void testRepeatedWord_match() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)\\s+\\1");
    MatchResult result = matcher.match("hello hello");

    assertNotNull(result, "match(\"hello hello\") should succeed");
    assertEquals("hello hello", result.group(0));
    assertEquals("hello", result.group(1));
  }

  @Test
  public void testRepeatedWord_findMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)\\s+\\1");
    MatchResult result = matcher.findMatch("say hello hello now");

    assertNotNull(result, "findMatch should locate the repeated word");
    assertEquals(4, result.start());
    assertEquals(15, result.end());
    assertEquals("hello hello", result.group(0));
  }

  @Test
  public void testRepeatedWord_notFound() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)\\s+\\1");
    assertNull(matcher.findMatch("hello world foo"), "no repeated word should return null");
  }

  // ---- (\w+)\b\1 : zero-width (empty) separator ----
  // A word-boundary can never occur between two word characters, so this pattern is
  // structurally unsatisfiable for any input - match() must always return null.

  @Test
  public void testEmptySeparator_alwaysNull() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)\\b\\1");
    assertNull(matcher.match("abcabc"), "match(\"abcabc\") should return null (unsatisfiable)");
    assertNull(matcher.match("aa"), "match(\"aa\") should return null (unsatisfiable)");
  }

  @Test
  public void testEmptySeparator_mismatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)\\b\\1");
    assertNull(matcher.match("abcxyz"), "match(\"abcxyz\") should return null");
  }

  // ---- ([a-z]+)\d+\1 : charset separator ----

  @Test
  public void testCharsetSeparator_match() {
    ReggieMatcher matcher = RuntimeCompiler.compile("([a-z]+)\\d+\\1");
    MatchResult result = matcher.match("ab12ab");

    assertNotNull(result, "match(\"ab12ab\") should succeed");
    assertEquals("ab12ab", result.group(0));
    assertEquals("ab", result.group(1));
  }

  @Test
  public void testCharsetSeparator_findMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("([a-z]+)\\d+\\1");
    MatchResult result = matcher.findMatch("xx ab12ab yy");

    assertNotNull(result, "findMatch should locate the embedded match");
    assertEquals(3, result.start());
    assertEquals(9, result.end());
    assertEquals("ab12ab", result.group(0));
  }

  @Test
  public void testCharsetSeparator_mismatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("([a-z]+)\\d+\\1");
    assertNull(matcher.match("ab12cd"), "match(\"ab12cd\") should return null");
  }

  // ---- (\w+)(?:--){2}\1 : multi-char literal separator with explicit repetition count ----
  // (?:--){2} matches exactly 4 dashes; separator length bounds must be tracked in characters,
  // not in repetitions of the "--" atom, or a valid 4-dash separator is wrongly rejected.

  @Test
  public void testMultiCharSeparatorWithRepetitionCount_match() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)(?:--){2}\\1");
    MatchResult result = matcher.match("ab----ab");

    assertNotNull(result, "match(\"ab----ab\") should succeed (4 dashes = (?:--){2})");
    assertEquals("ab----ab", result.group(0));
    assertEquals("ab", result.group(1));
  }

  @Test
  public void testMultiCharSeparatorWithRepetitionCount_mismatchWrongDashCount() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\w+)(?:--){2}\\1");
    assertNull(matcher.match("ab--ab"), "2 dashes should not satisfy (?:--){2} (needs 4)");
    assertNull(matcher.match("ab------ab"), "6 dashes should not satisfy (?:--){2} (needs 4)");
  }
}
