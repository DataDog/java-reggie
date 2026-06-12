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
 * Patterns that combine multiple lookahead and/or lookbehind assertions, exercising the
 * multi-assertion analysis path in PatternAnalyzer and NFABytecodeGenerator.
 */
class MultiAssertionTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Two positive lookaheads ────────────────────────────────────────────────

  @Test
  void twoLookaheadsPasswordStrength() {
    // At least one digit AND at least one lowercase letter
    ReggieMatcher m = Reggie.compile("(?=.*[0-9])(?=.*[a-z]).+");
    assertTrue(m.matches("abc1"));
    assertTrue(m.matches("1a"));
    assertFalse(m.matches("abc")); // no digit
    assertFalse(m.matches("123")); // no lowercase
  }

  @Test
  void twoLookaheadsBothDigit() {
    ReggieMatcher m = Reggie.compile("(?=.*[0-9])(?=.*[A-Z]).+");
    assertTrue(m.matches("A1"));
    assertFalse(m.matches("a1")); // no uppercase
    assertFalse(m.matches("AB")); // no digit
  }

  @Test
  void threeLookaheads() {
    // Must contain digit, lowercase, uppercase
    ReggieMatcher m = Reggie.compile("(?=.*\\d)(?=.*[a-z])(?=.*[A-Z]).+");
    assertTrue(m.matches("aA1"));
    assertTrue(m.matches("Password1"));
    assertFalse(m.matches("password1")); // no uppercase
    assertFalse(m.matches("Password")); // no digit
  }

  // ── Positive and negative lookaheads mixed ────────────────────────────────

  @Test
  void posLookaheadNegLookahead() {
    // Contains a digit but not uppercase
    ReggieMatcher m = Reggie.compile("(?=.*\\d)(?!.*[A-Z]).+");
    assertTrue(m.matches("abc1"));
    assertFalse(m.matches("Abc1")); // has uppercase
    assertFalse(m.matches("abc")); // no digit
  }

  @Test
  void negLookaheadAtStart() {
    ReggieMatcher m = Reggie.compile("(?!foo)\\w+");
    assertTrue(m.find("bar"));
    assertTrue(m.find("foo")); // find() is unanchored: matches "oo" at pos 1
    assertTrue(m.find("foobar")); // "oobar" still matches
  }

  @Test
  void negLookaheadWordBoundary() {
    ReggieMatcher m = Reggie.compile("(?!\\d)\\w+");
    assertTrue(m.find("abc"));
    assertTrue(m.find("_var"));
    // digits lead: negation prevents
    assertFalse(m.find("123"));
  }

  // ── Lookahead inside groups ────────────────────────────────────────────────

  @Test
  void lookaheadInsideGroup() {
    ReggieMatcher m = Reggie.compile("(?:(?=\\d)\\w)+", WITH_FALLBACK);
    assertTrue(m.find("123")); // falls back to java.util.regex — correct behavior
    assertFalse(m.find("abc"));
  }

  @Test
  void lookaheadWithCapturingGroup() {
    ReggieMatcher m = Reggie.compile("(?=(\\w+))\\w+");
    assertTrue(m.matches("hello"));
    assertFalse(m.matches(""));
  }

  // ── Lookbehind + multiple lookaheads ──────────────────────────────────────

  @Test
  void lookbehindPlusLookahead() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)(?=[^\\]]{1,10}\\])\\w+");
    assertTrue(m.find("[hello]"));
    assertFalse(m.find("[this is too long string here]"));
    assertFalse(m.find("hello"));
  }

  @Test
  void twoLookbehindsOneLookahead() {
    // Exercises two-lookbehind + lookahead code path
    ReggieMatcher m = Reggie.compile("(?<=\\d)(?<=.)x(?=\\w)");
    assertFalse(m.find("ax")); // no digit precedes x
  }

  // ── Lookahead at end of pattern ────────────────────────────────────────────

  @Test
  void lookaheadAtEnd() {
    ReggieMatcher m = Reggie.compile("\\w+(?=\\.)");
    assertTrue(m.find("end."));
    assertFalse(m.find("end "));
  }

  @Test
  void negativeLookaheadAtEnd() {
    ReggieMatcher m = Reggie.compile("\\w+(?!\\.)");
    assertTrue(m.find("word "));
    assertTrue(m.find("word"));
  }

  // ── Assertion-only patterns ────────────────────────────────────────────────

  @Test
  void assertionOnlyLookaheadThenLiteral() {
    ReggieMatcher m = Reggie.compile("(?=abc)abc");
    assertTrue(m.matches("abc"));
    assertFalse(m.matches("ab"));
    assertFalse(m.matches("xabc"));
  }

  @Test
  void twoLookaheadsSamePosition() {
    ReggieMatcher m = Reggie.compile("(?=a)(?=.)a");
    assertFalse(m.matches("b"));
  }

  // ── Quantified assertions ──────────────────────────────────────────────────

  @Test
  void lookaheadInsideQuantifiedGroup() {
    ReggieMatcher m = Reggie.compile("(?:(?=\\d)\\d)+", WITH_FALLBACK);
    assertFalse(m.matches("12a")); // trailing non-digit always fails
  }

  @Test
  void lookbehindInsideQuantifiedGroup() {
    ReggieMatcher m = Reggie.compile("[a-z]+(?<=e)");
    assertTrue(m.find("handle"));
    assertTrue(m.find("love"));
    assertFalse(m.find("abc"));
  }

  // ── Assertions with char classes ──────────────────────────────────────────

  @Test
  void lookaheadHexCharClass() {
    // Exercises lookahead-with-charclass analysis path
    ReggieMatcher m = Reggie.compile("(?=[0-9a-fA-F]{4})\\w+");
    assertFalse(m.find("xy")); // too short for lookahead
  }

  @Test
  void lookbehindAndLookaheadHexSandwich() {
    // Exercises lookbehind+content+lookahead code path
    ReggieMatcher m = Reggie.compile("(?<=0x)[0-9a-fA-F]+(?=[hH])");
    assertFalse(m.find("0xDEAD"));
    assertFalse(m.find("DEADh"));
  }
}
