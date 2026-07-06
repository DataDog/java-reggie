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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for patterns involving backreferences to capture groups: repeated-word detection, HTML tag
 * matching, and attribute patterns. Exercises detectRepeatedWordPattern, detectHTMLTagPattern, and
 * detectVariableCaptureBackref analysis paths in PatternAnalyzer.
 */
class RepeatedWordPatternTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Repeated word (\\w+)\\s+\\1 ───────────────────────────────────────────

  @Test
  void repeatedWordMatches() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1");
    assertTrue(m.find("hello hello"));
    assertTrue(m.find("the the cat"));
    assertFalse(m.find("hello world"));
  }

  @Test
  void repeatedWordExactMatch() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1");
    assertTrue(m.find("word word"));
    assertFalse(m.find("word"));
  }

  @Test
  void repeatedWordCaseSensitive() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1");
    assertFalse(m.find("Hello hello")); // different case
    assertTrue(m.find("Hello Hello"));
  }

  @Test
  void repeatedWordMultipleSpaces() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1");
    assertTrue(m.find("test  test")); // two spaces
    assertTrue(m.find("go\tthere\tthere"));
  }

  @Test
  void tripleRepeat() {
    ReggieMatcher m = Reggie.compile("(\\w+)\\s+\\1\\s+\\1");
    assertTrue(m.find("go go go"));
    assertFalse(m.find("go go stop")); // fallback to java.util.regex fixes false-positive
  }

  // ── HTML tag matching <(\w+)>...</\1> ─────────────────────────────────────

  @Test
  void htmlTagBasic() {
    ReggieMatcher m = Reggie.compile("<(\\w+)>[^<]*</\\1>");
    assertTrue(m.find("<b>bold</b>"));
    assertTrue(m.find("<p>text</p>"));
    assertFalse(m.find("<b>bold</p>")); // mismatched tags
  }

  @Test
  void htmlTagWithAttributes() {
    ReggieMatcher m = Reggie.compile("<(\\w+)[^>]*>[^<]*</\\1>");
    assertFalse(m.find("<div>text</span>")); // mismatched tags always fail
  }

  @Test
  void htmlTagFind() {
    ReggieMatcher m = Reggie.compile("<([a-z]+)>[^<]*</\\1>");
    assertTrue(m.find("prefix <em>word</em> suffix"));
    assertFalse(m.find("no tags here"));
  }

  @Test
  void htmlTagSelfClose() {
    ReggieMatcher m = Reggie.compile("<(\\w+)></\\1>");
    assertTrue(m.find("<br></br>"));
    assertFalse(m.find("<br/>"));
  }

  @Test
  void htmlTagMatchesContinuesPastEarlierSameNamedClose() {
    // Regression: matches() previously gave up as soon as it found a candidate closing tag with
    // the right name, even when that candidate left trailing content unconsumed - instead of
    // continuing to search for a later occurrence of the same tag name that reaches end-of-input.
    // "<a>x</a>y</a>": the first "</a>" is a real closing tag for "a", but leaves "y</a>"
    // unconsumed; JDK correctly keeps searching and matches on the second "</a>".
    ReggieMatcher m = Reggie.compile("<(\\w+)>.*</\\1>");
    assertTrue(m.matches("<a>x</a>y</a>"));
    assertTrue(m.matches("<a>p</a>q</a>r</a>")); // two false starts before the real close
    MatchResult result = m.match("<a>x</a>y</a>");
    assertNotNull(result);
    assertEquals(0, result.start());
    assertEquals("<a>x</a>y</a>".length(), result.end());
  }

  // ── Fixed-repetition backreferences ───────────────────────────────────────

  @Test
  void fixedRepetitionBackref() {
    // (a{2})\1 — group captures "aa", backref also "aa"
    ReggieMatcher m = Reggie.compile("(a{2})\\1");
    assertTrue(m.matches("aaaa"));
    assertFalse(m.matches("aaa"));
    assertFalse(m.matches("aa"));
  }

  @Test
  void fixedLiteralBackref() {
    ReggieMatcher m = Reggie.compile("([abc])\\1");
    assertTrue(m.find("aa"));
    assertTrue(m.find("bb"));
    assertTrue(m.find("cc"));
    assertFalse(m.find("ab"));
  }

  @Test
  void backrefToDigitCapture() {
    ReggieMatcher m = Reggie.compile("([0-9])\\1");
    assertTrue(m.find("00"));
    assertTrue(m.find("99"));
    assertFalse(m.find("01"));
  }

  // ── Multiple capturing groups with backreferences ─────────────────────────

  @Test
  void twoGroups() {
    ReggieMatcher m = Reggie.compile("([a-z]+)\\s([0-9]+)\\s\\1\\s\\2");
    assertTrue(m.find("abc 123 abc 123"));
    assertFalse(m.find("abc 123 abc 124"));
  }

  @Test
  void backrefGroupTwo() {
    ReggieMatcher m = Reggie.compile("([a-z]+)([0-9]+)\\2\\1");
    assertTrue(m.find("ab123123ba"));
    // Wait: group1=ab, group2=123, then \2=123, \1=ab → "ab123123ab"
    assertTrue(m.find("xy99xy"));
    // group1=x, group2=9, then 99x? No: group1=xy, group2=9, \2=9, \1=xy → "xy99xy"
  }

  // ── Optional capturing group with backref ─────────────────────────────────

  @Test
  void optionalGroupBackref() {
    ReggieMatcher m = Reggie.compile("(a?)b\\1", WITH_FALLBACK);
    assertTrue(m.find("ab a")); // group1=a, then b, then \1=a → "aba"
    assertTrue(m.find("b")); // group1= (empty), b, \1= (empty) → "b"
  }

  // ── Backref in alternation ─────────────────────────────────────────────────

  @Test
  void backrefInAlternation() {
    ReggieMatcher m = Reggie.compile("(a|b)\\1");
    assertTrue(m.find("aa"));
    assertTrue(m.find("bb"));
    assertFalse(m.find("ab"));
    assertFalse(m.find("ba"));
  }

  // ── Quote-like patterns (attribute values) ────────────────────────────────

  @Test
  void quoteAttributePattern() {
    // Pattern: (["'])([^\1]*)\1
    // Inside a character class, \1 is NOT a backreference — it is the SOH control character
    // (code point 1). So [^\1] means "any character except SOH", not "any character except
    // the opening quote". The closing \1 IS a backreference that enforces matching quotes.
    // The test assertions are correct: "hello" and 'world' match because SOH is absent,
    // and the closing \1 enforces the same quote character.
    ReggieMatcher m = Reggie.compile("([\"'])([^\\1]*)\\1");
    assertTrue(m.find("\"hello\""));
    assertTrue(m.find("'world'"));
    assertFalse(m.find("\"hello'"));
  }

  @Test
  void htmlAttributeDoubleQuote() {
    ReggieMatcher m = Reggie.compile("\\w+=\"[^\"]*\"");
    assertTrue(m.find("class=\"container\""));
    assertFalse(m.find("class=container"));
  }
}
