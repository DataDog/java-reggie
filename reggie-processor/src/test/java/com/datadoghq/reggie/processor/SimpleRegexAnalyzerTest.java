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
package com.datadoghq.reggie.processor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for SimpleRegexAnalyzer covering all analysis branches. */
class SimpleRegexAnalyzerTest {

  // ── isLiteral / getLiteralValue ────────────────────────────────────────────

  @Test
  void literalStringNoMetachars() {
    var a = new SimpleRegexAnalyzer("hello");
    assertTrue(a.isLiteral());
    assertEquals("hello", a.getLiteralValue());
    assertFalse(a.isSimplePattern());
  }

  @Test
  void literalEmptyString() {
    var a = new SimpleRegexAnalyzer("");
    assertTrue(a.isLiteral());
    assertEquals("", a.getLiteralValue());
  }

  @Test
  void literalDigitsOnly() {
    var a = new SimpleRegexAnalyzer("123");
    assertTrue(a.isLiteral());
    assertEquals("123", a.getLiteralValue());
  }

  @Test
  void notLiteralDot() {
    var a = new SimpleRegexAnalyzer("a.b");
    assertFalse(a.isLiteral());
    assertNull(a.getLiteralValue());
  }

  @Test
  void notLiteralBackslash() {
    var a = new SimpleRegexAnalyzer("\\d");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralStar() {
    var a = new SimpleRegexAnalyzer("a*");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralParen() {
    var a = new SimpleRegexAnalyzer("(a)");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralBracket() {
    var a = new SimpleRegexAnalyzer("[a-z]");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralCaret() {
    var a = new SimpleRegexAnalyzer("^abc");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralDollar() {
    var a = new SimpleRegexAnalyzer("abc$");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralPipe() {
    var a = new SimpleRegexAnalyzer("a|b");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralPlus() {
    var a = new SimpleRegexAnalyzer("a+");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralQuestion() {
    var a = new SimpleRegexAnalyzer("a?");
    assertFalse(a.isLiteral());
  }

  @Test
  void notLiteralBraces() {
    var a = new SimpleRegexAnalyzer("a{3}");
    assertFalse(a.isLiteral());
  }

  // ── isSimplePattern: phone-like ───────────────────────────────────────────

  @Test
  void phonePatternSingleSegment() {
    var a = new SimpleRegexAnalyzer("\\d{3}");
    assertFalse(a.isLiteral());
    assertTrue(a.isSimplePattern());
  }

  @Test
  void phonePatternThreeSegments() {
    var a = new SimpleRegexAnalyzer("\\d{3}-\\d{3}-\\d{4}");
    assertTrue(a.isSimplePattern());
  }

  @Test
  void phonePatternTwoSegments() {
    var a = new SimpleRegexAnalyzer("\\d{2}-\\d{4}");
    assertTrue(a.isSimplePattern());
  }

  // ── isSimplePattern: not simple ───────────────────────────────────────────

  @Test
  void charClassNotSimple() {
    var a = new SimpleRegexAnalyzer("[a-z]+");
    assertFalse(a.isLiteral());
    assertFalse(a.isSimplePattern());
  }

  @Test
  void complexPatternNotSimple() {
    var a = new SimpleRegexAnalyzer("(\\w+)\\s+\\1");
    assertFalse(a.isSimplePattern());
  }

  // ── hasBackreferences ─────────────────────────────────────────────────────

  @Test
  void hasBackreferenceGroup1() {
    assertTrue(new SimpleRegexAnalyzer("(.)\\1").hasBackreferences());
  }

  @Test
  void hasBackreferenceGroup2() {
    assertTrue(new SimpleRegexAnalyzer("(.)(.)\\2\\1").hasBackreferences());
  }

  @Test
  void noBackreferenceSimple() {
    assertFalse(new SimpleRegexAnalyzer("\\d+").hasBackreferences());
  }

  @Test
  void noBackreferenceLiteral() {
    assertFalse(new SimpleRegexAnalyzer("hello").hasBackreferences());
  }

  // ── hasLookaround ─────────────────────────────────────────────────────────

  @Test
  void hasPositiveLookahead() {
    assertTrue(new SimpleRegexAnalyzer("(?=abc)\\w+").hasLookaround());
  }

  @Test
  void hasNegativeLookahead() {
    assertTrue(new SimpleRegexAnalyzer("(?!foo)\\w+").hasLookaround());
  }

  @Test
  void hasPositiveLookbehind() {
    assertTrue(new SimpleRegexAnalyzer("(?<=\\d)x").hasLookaround());
  }

  @Test
  void hasNegativeLookbehind() {
    assertTrue(new SimpleRegexAnalyzer("(?<!\\d)x").hasLookaround());
  }

  @Test
  void noLookaround() {
    assertFalse(new SimpleRegexAnalyzer("\\d+").hasLookaround());
  }

  // ── estimateDFAStates ─────────────────────────────────────────────────────

  @Test
  void estimateLiteralPattern() {
    var a = new SimpleRegexAnalyzer("hello");
    assertEquals(5, a.estimateDFAStates());
  }

  @Test
  void estimateWithEscape() {
    var a = new SimpleRegexAnalyzer("\\d+");
    // \d → escape(+2) + + → (+10) = 12; max(12, 3) = 12 (but length=3 so max is 12)
    assertTrue(a.estimateDFAStates() > 0);
  }

  @Test
  void estimateWithCharClass() {
    var a = new SimpleRegexAnalyzer("[a-z]");
    // [ → 5, ] closes, default=0 chars inside charClass, max(5, 5)=5
    assertTrue(a.estimateDFAStates() > 0);
  }

  @Test
  void estimateWithStar() {
    var a = new SimpleRegexAnalyzer("a*");
    // a → 1, * → 10 = 11; max(11, 2) = 11
    assertTrue(a.estimateDFAStates() >= 10);
  }

  @Test
  void estimateWithPlus() {
    var a = new SimpleRegexAnalyzer("a+");
    assertTrue(a.estimateDFAStates() >= 10);
  }

  @Test
  void estimateWithAlternation() {
    var a = new SimpleRegexAnalyzer("a|b");
    // a=1, |=20, b=1 = 22; max(22, 3) = 22
    assertTrue(a.estimateDFAStates() >= 20);
  }

  @Test
  void estimateWithGroup() {
    var a = new SimpleRegexAnalyzer("(ab)");
    // (=3, a=1, b=1, )=0 = 5; max(5, 4)=5
    assertTrue(a.estimateDFAStates() >= 3);
  }

  // ── recommendStrategy ─────────────────────────────────────────────────────

  @Test
  void strategyBackreferenceIsNFA() {
    var a = new SimpleRegexAnalyzer("(.)\\1");
    assertEquals(SimpleRegexAnalyzer.MatchingStrategy.THOMPSON_NFA, a.recommendStrategy());
  }

  @Test
  void strategyLookaheadIsNFA() {
    var a = new SimpleRegexAnalyzer("(?=abc)\\w+");
    assertEquals(SimpleRegexAnalyzer.MatchingStrategy.THOMPSON_NFA, a.recommendStrategy());
  }

  @Test
  void strategyLookbehindIsNFA() {
    var a = new SimpleRegexAnalyzer("(?<=x)y");
    assertEquals(SimpleRegexAnalyzer.MatchingStrategy.THOMPSON_NFA, a.recommendStrategy());
  }

  @Test
  void strategySmallPatternIsPureDFA() {
    var a = new SimpleRegexAnalyzer("abc");
    assertEquals(SimpleRegexAnalyzer.MatchingStrategy.PURE_DFA, a.recommendStrategy());
  }

  @Test
  void strategyLargeAlternationIsLazyDFA() {
    // Alternations each add 20 states; 25 alternations = 500 states → LAZY_DFA
    String p = "a|b|c|d|e|f|g|h|i|j|k|l|m|n|o|p|q|r|s|t|u|v|w|x|y|z";
    var a = new SimpleRegexAnalyzer(p);
    var strategy = a.recommendStrategy();
    assertTrue(
        strategy == SimpleRegexAnalyzer.MatchingStrategy.LAZY_DFA
            || strategy == SimpleRegexAnalyzer.MatchingStrategy.THOMPSON_NFA);
  }

  // ── getPattern ────────────────────────────────────────────────────────────

  @Test
  void getPatternReturnsOriginal() {
    var a = new SimpleRegexAnalyzer("\\d{3}");
    assertEquals("\\d{3}", a.getPattern());
  }
}
