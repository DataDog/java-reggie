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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises lookbehind-heavy patterns to improve PatternAnalyzer and NFABytecodeGenerator coverage.
 */
class LookbehindVariantsTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Positive lookbehind ────────────────────────────────────────────────────

  @Test
  void positiveLookbehindSingleChar() {
    ReggieMatcher m = Reggie.compile("(?<=a)b");
    assertTrue(m.find("ab"));
    assertFalse(m.find("xb"));
    assertFalse(m.find("a"));
  }

  @Test
  void positiveLookbehindDigit() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)x");
    assertTrue(m.find("3x"));
    assertFalse(m.find("ax"));
  }

  @Test
  void positiveLookbehindFixed() {
    ReggieMatcher m = Reggie.compile("(?<=\\d{2})x");
    assertTrue(m.find("12x"));
    assertFalse(m.find("1x"));
    assertTrue(m.find("123x")); // "23x" portion does match inside
    assertTrue(m.find("a12x"));
  }

  @Test
  void positiveLookbehindWord() {
    ReggieMatcher m = Reggie.compile("(?<=\\w)\\s");
    assertTrue(m.find("a "));
    assertFalse(m.find("  "));
  }

  // ── Negative lookbehind ────────────────────────────────────────────────────

  @Test
  void negativeLookbehindSingleChar() {
    ReggieMatcher m = Reggie.compile("(?<!a)b");
    assertTrue(m.find("xb"));
    assertFalse(m.find("ab"));
  }

  @Test
  void negativeLookbehindDigit() {
    ReggieMatcher m = Reggie.compile("(?<!\\d)x");
    assertTrue(m.find("ax"));
    assertFalse(m.find("3x"));
  }

  @Test
  void negativeLookbehindAtStart() {
    ReggieMatcher m = Reggie.compile("(?<!\\d)x");
    assertTrue(m.find("x")); // nothing before x satisfies the negative
  }

  // ── Lookbehind combined with other constructs ──────────────────────────────

  @Test
  void lookbehindFollowedByQuantifier() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]+");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("3"));
    assertFalse(m.find("abc"));
  }

  @Test
  void lookbehindFollowedByStarQuantifier() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]*");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("abc"));
  }

  @Test
  void lookbehindFollowedByOpenEndedRangeQuantifier() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]{2,}");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("3a"));
    assertFalse(m.find("abc"));
  }

  @Test
  void charSetLookbehindFollowedByPlusQuantifier() {
    ReggieMatcher m = Reggie.compile("(?<=[0-9])[a-z]+");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("abc"));
  }

  @Test
  void charSetLookbehindFollowedByStarQuantifier() {
    ReggieMatcher m = Reggie.compile("(?<=[0-9])[a-z]*");
    assertTrue(m.find("3abc"));
    assertFalse(m.find("abc"));
  }

  @Test
  void lookbehindWithAlternation() {
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertTrue(m.find("ac"));
    assertTrue(m.find("bc"));
    assertFalse(m.find("xc"));
  }

  @Test
  void negativeLookbehindWithAlternation() {
    ReggieMatcher m = Reggie.compile("(?<!a|b)c");
    assertFalse(m.find("ac"));
    assertFalse(m.find("bc"));
    assertTrue(m.find("xc"));
  }

  @Test
  void lookbehindWithTwoCharAlternation() {
    ReggieMatcher m = Reggie.compile("(?<=ab|cd)x");
    assertTrue(m.find("abx"));
    assertTrue(m.find("cdx"));
    assertFalse(m.find("efx"));
  }

  @Test
  void lookbehindInsideAlternation() {
    // Lookbehind followed by alternation (exercises PatternAnalyzer code path)
    ReggieMatcher m = Reggie.compile("(?<=a)(?:x|y)");
    assertTrue(m.find("ax"));
    assertTrue(m.find("ay"));
    assertFalse(m.find("bx"));
    assertFalse(m.find("az"));
  }

  @Test
  void lookbehindWithCapturingGroup() {
    ReggieMatcher m = Reggie.compile("(?<=\\()([^)]+)(?=\\))");
    assertFalse(m.find("hello"));
    assertFalse(m.find("()"));
  }

  // ── Lookbehind + lookahead combined ───────────────────────────────────────

  @Test
  void lookbehindAndLookaheadSandwich() {
    // combined lookbehind+lookahead falls back to java.util.regex
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertTrue(m.find("[value]"));
    assertFalse(m.find("value"));
    assertFalse(m.find("[value"));
  }

  @Test
  void negativeLookbehindAndNegativeLookahead() {
    ReggieMatcher m = Reggie.compile("(?<!\\d)\\w+(?!\\d)");
    assertTrue(m.find("hello"));
    assertTrue(m.find("abc"));
  }

  @Test
  void positiveLookbehindNegativeLookahead() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)[a-z]{1,4}(?!\\d)");
    assertTrue(m.find("3abc"));
  }

  // ── Lookbehind on matches() (full-string) ──────────────────────────────────

  @Test
  void matchesWithLookbehind() {
    ReggieMatcher m = Reggie.compile("(?<=\\d)abc");
    assertFalse(m.matches("3abc")); // matches() anchors to full string, 3 is not part of match
    assertTrue(m.find("3abc"));
  }

  @Test
  void matchesNegativeLookbehindAtStart() {
    ReggieMatcher m = Reggie.compile("(?<!x)abc");
    assertTrue(m.matches("abc")); // nothing before start → negative lookbehind succeeds
    assertFalse(m.find("xabc"));
  }

  // ── Multiple lookbehinds in sequence ──────────────────────────────────────

  @Test
  void twoLookbehindsInSequence() {
    ReggieMatcher m = Reggie.compile("(?<=a)(?<=.)b");
    assertTrue(m.find("ab"));
    assertFalse(m.find("xb"));
  }

  @Test
  void lookbehindWithCharClass() {
    ReggieMatcher m = Reggie.compile("(?<=[0-9a-f])x");
    assertTrue(m.find("ax"));
    assertTrue(m.find("3x"));
    assertFalse(m.find("gx"));
  }

  // ── Regression: issue #30 — only first alternative in lookbehind was checked ──

  @Test
  void lookbehindAlternationAllBranchesChecked() {
    // Exact reproduction from issue #30: (?<=a|b)c must match for both alternatives
    ReggieMatcher m = Reggie.compile("(?<=a|b)c");
    assertFalse(m instanceof JavaRegexFallbackMatcher, "must be handled natively");
    assertTrue(m.find("ac"), "first alternative");
    assertTrue(m.find("bc"), "second alternative — was incorrectly false before fix");
    assertFalse(m.find("xc"), "no matching predecessor");
  }
}
