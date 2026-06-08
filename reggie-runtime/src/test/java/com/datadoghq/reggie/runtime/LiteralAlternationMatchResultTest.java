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
import com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier;
import com.datadoghq.reggie.codegen.analysis.StrategyJdkClassifier.StrategyJdkClass;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that SPECIALIZED_LITERAL_ALTERNATION is NATIVE and generates correct rich MatchResult
 * API methods (match, findMatch, findMatchFrom, matchBounded).
 */
class LiteralAlternationMatchResultTest {

  private static final String PATTERN = "foo|bar|baz|qux|quux";

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void strategyIsSpecializedLiteralAlternation() {
    PatternAnalyzer.MatchingStrategyResult result = analyze(PATTERN);
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_LITERAL_ALTERNATION, result.strategy);
  }

  @Test
  void strategyIsNative() {
    PatternAnalyzer.MatchingStrategyResult result = analyze(PATTERN);
    assertEquals(
        StrategyJdkClass.NATIVE,
        StrategyJdkClassifier.classifyJdkDependency(result.strategy),
        "SPECIALIZED_LITERAL_ALTERNATION must be classified NATIVE after promotion");
  }

  // ── match() ───────────────────────────────────────────────────────────────

  @Test
  void matchReturnsResultForExactKeyword() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    MatchResult r = m.match("foo");
    assertNotNull(r, "match(\"foo\") must return non-null");
    assertEquals(0, r.start());
    assertEquals(3, r.end());
    assertEquals("foo", r.group(0));
  }

  @Test
  void matchReturnsNullForNonKeyword() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    assertNull(m.match("nomatch"));
  }

  @Test
  void matchReturnsNullForNull() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    assertNull(m.match((String) null));
  }

  @Test
  void matchReturnsResultForAllKeywords() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    assertNotNull(m.match("bar"));
    assertNotNull(m.match("baz"));
    assertNotNull(m.match("qux"));
    assertNotNull(m.match("quux"));
  }

  // ── findMatch() ───────────────────────────────────────────────────────────

  @Test
  void findMatchLocatesKeywordInString() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    MatchResult r = m.findMatch("xbary");
    assertNotNull(r, "findMatch(\"xbary\") must return non-null");
    assertEquals(1, r.start());
    assertEquals(4, r.end());
    assertEquals("bar", r.group(0));
  }

  @Test
  void findMatchReturnsNullForNoMatch() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    assertNull(m.findMatch("nomatch"));
  }

  @Test
  void findMatchLocatesFirstKeyword() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    MatchResult r = m.findMatch("foo bar");
    assertNotNull(r);
    assertEquals(0, r.start());
    assertEquals(3, r.end());
  }

  // ── findMatchFrom() ───────────────────────────────────────────────────────

  @Test
  void findMatchFromFindsAtStartPos() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    MatchResult r = m.findMatchFrom("xbary", 1);
    assertNotNull(r, "findMatchFrom(\"xbary\", 1) must return non-null");
    assertEquals(1, r.start());
    assertEquals(4, r.end());
    assertEquals("bar", r.group(0));
  }

  @Test
  void findMatchFromReturnsNullWhenMatchPassed() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    // from position 2, "ary" / "ry" / "y" don't match any keyword
    assertNull(m.findMatchFrom("xbary", 2));
  }

  @Test
  void findMatchFromStartZeroSameAsFindMatch() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    MatchResult r = m.findMatchFrom("hello foo world", 0);
    assertNotNull(r);
    assertEquals(6, r.start());
    assertEquals(9, r.end());
  }

  // ── matchBounded() ────────────────────────────────────────────────────────

  @Test
  void matchBoundedMatchesKeywordInWindow() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    MatchResult r = m.matchBounded("prefoo", 3, 6);
    assertNotNull(r, "matchBounded(\"prefoo\", 3, 6) must return non-null");
    // matchBounded returns relative indices (0-based within the window)
    assertEquals(0, r.start());
    assertEquals(3, r.end());
    assertEquals("foo", r.group(0));
  }

  @Test
  void matchBoundedReturnsNullForNonMatch() {
    ReggieMatcher m = Reggie.compile(PATTERN);
    assertNull(m.matchBounded("foooo", 0, 4)); // "fooo" not a keyword
  }

  // ── helper ────────────────────────────────────────────────────────────────

  private static PatternAnalyzer.MatchingStrategyResult analyze(String pattern) {
    try {
      RegexParser parser = new RegexParser();
      RegexNode ast = parser.parse(pattern);
      NFA nfa = null;
      if (!PatternAnalyzer.requiresRecursiveDescent(ast)) {
        nfa = new ThompsonBuilder().build(ast, countGroups(pattern));
      }
      return new PatternAnalyzer(ast, nfa).analyzeAndRecommend();
    } catch (Exception e) {
      throw new RuntimeException("analyze failed for " + pattern, e);
    }
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '(' && (i + 1 >= pattern.length() || pattern.charAt(i + 1) != '?')) {
        count++;
      }
    }
    return count;
  }
}
