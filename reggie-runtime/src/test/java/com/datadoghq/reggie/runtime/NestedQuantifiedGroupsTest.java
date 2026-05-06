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
 * Patterns with nested and combined quantified groups, exercising NestedQuantifiedGroups and
 * ConcatQuantifiedGroups analysis paths in PatternAnalyzer.
 */
class NestedQuantifiedGroupsTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Nested quantified groups (non-capturing) ───────────────────────────────

  @Test
  void nestedStarStar() {
    ReggieMatcher m = Reggie.compile("(?:(?:a)*)*");
    assertTrue(m.matches(""));
    assertTrue(m.matches("a"));
    assertTrue(m.matches("aaa"));
  }

  @Test
  void nestedPlusStar() {
    ReggieMatcher m = Reggie.compile("(?:(?:a+))*");
    assertTrue(m.matches(""));
    assertTrue(m.matches("a"));
    assertTrue(m.matches("aaa"));
  }

  @Test
  void nestedGroupWithLiteral() {
    ReggieMatcher m = Reggie.compile("(?:a(?:b)+)+");
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("abb"));
    assertTrue(m.matches("abab"));
    assertFalse(m.matches("a"));
    assertFalse(m.matches("b"));
  }

  @Test
  void threeNestedGroups() {
    ReggieMatcher m = Reggie.compile("(?:(?:(?:a)+)+)+");
    assertTrue(m.matches("a"));
    assertTrue(m.matches("aaa"));
    assertFalse(m.matches(""));
  }

  // ── Quantified groups in concatenation ────────────────────────────────────

  @Test
  void twoQuantifiedGroupsConcatenated() {
    ReggieMatcher m = Reggie.compile("(?:a+)(?:b+)");
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("aaabbb"));
    assertFalse(m.matches("a"));
    assertFalse(m.matches("b"));
  }

  @Test
  void threeQuantifiedGroupsConcatenated() {
    ReggieMatcher m = Reggie.compile("(?:a+)(?:b+)(?:c+)");
    assertTrue(m.matches("abc"));
    assertTrue(m.matches("aaabbcc"));
    assertFalse(m.matches("ab"));
  }

  @Test
  void quantifiedGroupWithBoundedQuantifier() {
    ReggieMatcher m = Reggie.compile("(?:[a-z]{2,4})+");
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("abcd"));
    assertTrue(m.matches("abcdef")); // 2+4
    assertFalse(m.matches("a"));
  }

  @Test
  void quantifiedGroupWithOptional() {
    ReggieMatcher m = Reggie.compile("(?:a+)?(?:b+)?");
    assertTrue(m.matches(""));
    assertTrue(m.matches("a"));
    assertTrue(m.matches("b"));
    assertTrue(m.matches("ab"));
    assertFalse(m.matches("c"));
  }

  // ── Alternation inside quantified groups ──────────────────────────────────

  @Test
  void quantifiedAlternation() {
    ReggieMatcher m = Reggie.compile("(?:a|b)+");
    assertTrue(m.matches("a"));
    assertTrue(m.matches("b"));
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("baba"));
    assertFalse(m.matches("c"));
    assertFalse(m.matches(""));
  }

  @Test
  void quantifiedAlternationWithCapture() {
    ReggieMatcher m = Reggie.compile("(?:a|b){2,4}");
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("abba"));
    assertFalse(m.matches("a"));
    assertFalse(m.matches("ababab")); // 6 chars, exceeds {2,4}
  }

  @Test
  void nestedAlternationWithQuantifier() {
    ReggieMatcher m = Reggie.compile("(?:(?:x|y){2}z)+");
    assertTrue(m.matches("xxz"));
    assertTrue(m.matches("xyz"));
    assertTrue(m.matches("xyzxxz"));
    assertFalse(m.matches("xz"));
    assertFalse(m.matches("xyz "));
  }

  // ── Mixed quantifiers ─────────────────────────────────────────────────────

  @Test
  void plusInsideStar() {
    ReggieMatcher m = Reggie.compile("(?:a+b)*");
    assertTrue(m.matches(""));
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("aaab"));
    assertTrue(m.matches("abab"));
    assertFalse(m.matches("b"));
  }

  @Test
  void starInsidePlus() {
    ReggieMatcher m = Reggie.compile("(?:a*b)+");
    assertTrue(m.matches("b"));
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("aab"));
    assertTrue(m.matches("bab"));
    assertFalse(m.matches("a"));
    assertFalse(m.matches(""));
  }

  @Test
  void quantifiedGroupFollowedByLiteral() {
    ReggieMatcher m = Reggie.compile("(?:ab)+c");
    assertTrue(m.matches("abc"));
    assertTrue(m.matches("ababc"));
    assertFalse(m.matches("ab"));
    assertFalse(m.matches("c"));
  }

  // ── CharClass in quantified groups ────────────────────────────────────────

  @Test
  void charClassGroupQuantified() {
    ReggieMatcher m = Reggie.compile("(?:[0-9]{2}[-/])+[0-9]{2}");
    assertTrue(m.matches("12-34"));
    assertTrue(m.matches("12/34"));
    assertTrue(m.matches("12-34-56"));
    assertFalse(m.matches("1-34"));
  }

  @Test
  void wordCharGroupQuantified() {
    ReggieMatcher m = Reggie.compile("(?:\\w+\\.)+\\w+");
    assertTrue(m.matches("a.b"));
    assertTrue(m.matches("foo.bar.baz"));
    assertFalse(m.matches("foo"));
    assertFalse(m.matches(".b"));
  }

  // ── Quantified groups with find() ─────────────────────────────────────────

  @Test
  void nestedGroupFind() {
    ReggieMatcher m = Reggie.compile("(?:(?:\\d{3})-)+\\d{4}");
    assertTrue(m.find("call 123-456-7890"));
    assertFalse(m.find("call 123-456"));
  }

  @Test
  void complexNestedFind() {
    ReggieMatcher m = Reggie.compile("(?:[A-Z][a-z]+\\s)+[A-Z][a-z]+");
    assertTrue(m.find("Hello World"));
    assertTrue(m.find("John Doe Smith"));
    assertFalse(m.find("hello world"));
  }
}
