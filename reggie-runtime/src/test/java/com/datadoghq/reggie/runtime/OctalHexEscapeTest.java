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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for octal, hex and Unicode escape sequences, with java.util.regex semantics: {@code \xhh}
 * and {@code \x{h..h}} are hex, {@code \}{@code uhhhh} takes exactly four hex digits, {@code \0} is
 * followed by one to three octal digits (a third only when the first is at most 3), and {@code
 * \1}-{@code \9} always start a backreference.
 */
class OctalHexEscapeTest {

  @Test
  void testHexEscapeSingleDigit() {
    // \x4 = ASCII 4 (EOT control character)
    ReggieMatcher rm = Reggie.compile("\\x04");
    assertTrue(rm.matches("\u0004"));
    assertFalse(rm.matches("4"));
  }

  @Test
  void testHexEscapeTwoDigits() {
    // \x40 = '@'
    ReggieMatcher rm = Reggie.compile("\\x40");
    assertTrue(rm.matches("@"));
    assertFalse(rm.matches("x40"));
  }

  @Test
  void testHexEscapeUpperCase() {
    // \x4A = 'J' (hex 4A = decimal 74)
    ReggieMatcher rm = Reggie.compile("\\x4A");
    assertTrue(rm.matches("J"));
  }

  @Test
  void testHexEscapeLowerCase() {
    // \x4a = 'J' (hex 4a = decimal 74)
    ReggieMatcher rm = Reggie.compile("\\x4a");
    assertTrue(rm.matches("J"));
  }

  @Test
  void testOctalStartingWithZero() {
    // \071 = '9' (octal 71 = decimal 57)
    ReggieMatcher rm = Reggie.compile("\\071");
    assertTrue(rm.matches("9"));
  }

  private static final String[] NUMERIC_INPUTS = {
    "",
    " ",
    "@",
    "@0",
    "00",
    "40",
    "100",
    "1000",
    "8",
    "9",
    "a",
    "a89",
    "aa",
    "aa0",
    "ab",
    "A",
    "A1",
    " 0",
    "\u00FF",
    "\u0001",
    "\u0004",
    "\b",
    "Hello",
    "abcdefghija0",
    "abcdefghijj",
  };

  /** Numeric escapes against java.util.regex: matches() and the first find() span. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "\\40",
        "\\100",
        "\\1000",
        "\\377",
        "\\1",
        "\\8",
        "\\9",
        "(a)\\89",
        "(a)\\2",
        "(a)\\2(b)",
        "\\0101",
        "\\0400",
        "\\0377",
        "\\0477",
        "\\04",
        "\\x48\\145\\154\\154\\x6f",
        "(a)(b)(c)(d)(e)(f)(g)(h)(i)(?:\\10|(j))+",
      })
  void numericEscapeEqualsJdk(String pattern) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    for (String input : NUMERIC_INPUTS) {
      assertEquals(jdk.matcher(input).matches(), reggie.matches(input), pattern + " on " + input);
      Matcher j = jdk.matcher(input);
      String expected = j.find() ? "[" + j.start() + "," + j.end() + ")" : "-";
      MatchResult r = reggie.findMatch(input);
      assertEquals(
          expected,
          r == null ? "-" : "[" + r.start() + "," + r.end() + ")",
          pattern + " on " + input);
    }
  }

  @Test
  void testPatternWithCaptureGroupAndOctal() {
    // (abc)\100 with 1 group: JDK stops collecting digits when the running value exceeds
    // totalGroupCount. \1 is a valid backref (group 1 = "abc"); the remaining "00" are literals.
    // Pattern is effectively (abc)\100 = (abc) + backref(1) + "00".
    ReggieMatcher rm = Reggie.compileAllowingFallback("(abc)\\100");

    assertTrue(rm.matches("abcabc00"));
    assertFalse(rm.matches("abc@"));
  }

  @Test
  void testPatternWithCaptureGroupAndOctalPlusDigit() {
    // (abc)\1000 with 1 group: JDK stop-early rule — \1 is a backref (group 1), "000" are literals.
    // Pattern is effectively (abc) + backref(1) + "000".
    ReggieMatcher rm = Reggie.compileAllowingFallback("(abc)\\1000");

    assertTrue(rm.matches("abcabc000"));
    assertFalse(rm.matches("abc@0"));
  }

  @Test
  void testHexInCharacterClass() {
    // [\x40-\x5A] = [@-Z] (hex 40 = '@', hex 5A = 'Z')
    ReggieMatcher rm = Reggie.compile("[\\x40-\\x5A]");
    assertTrue(rm.matches("@"));
    assertTrue(rm.matches("A"));
    assertTrue(rm.matches("Z"));
    assertFalse(rm.matches("["));
    assertFalse(rm.matches("a"));
  }

  @Test
  void testOctalInCharacterClass() {
    // [\100-\132] = [@-Z] (octal 100 = '@', octal 132 = 'Z')
    ReggieMatcher rm = Reggie.compile("[\\100-\\132]");
    assertTrue(rm.matches("@"));
    assertTrue(rm.matches("A"));
    assertTrue(rm.matches("Z"));
    assertFalse(rm.matches("["));
    assertFalse(rm.matches("a"));
  }

  @Test
  void testUnicodeEscape() {
    ReggieMatcher rm = Reggie.compile("\\u006b\\u0085");
    assertTrue(rm.matches("k\u0085"));
    assertFalse(rm.matches("u006bu0085"));
  }

  @Test
  void testUnicodeEscapeInCharClass() {
    ReggieMatcher rm = Reggie.compile("^([^\\n\\r\\u0085\\u2028\\u2029]*)$");
    assertTrue(rm.matches("key=lorem 0258u"));
    assertFalse(rm.matches("a\u2028b"));
    ReggieMatcher range = Reggie.compile("[\\u0061-\\u007a]+");
    assertTrue(range.matches("az"));
    assertFalse(range.matches("u0061"));
  }

  @Test
  void testUnicodeEscapeRequiresFourHexDigits() {
    assertThrows(RuntimeException.class, () -> Reggie.compile("\\u12"));
    assertThrows(RuntimeException.class, () -> Reggie.compile("[\\u12g4]"));
  }

  @Test
  void testNewlineVsOctal() {
    // \n is newline (special case), not octal
    ReggieMatcher rm = Reggie.compile("\\n");
    assertTrue(rm.matches("\n"));
    assertFalse(rm.matches("n"));
  }

  @Test
  void testTabVsHex() {
    // \t is tab (special case), not hex
    ReggieMatcher rm = Reggie.compile("\\t");
    assertTrue(rm.matches("\t"));
    assertFalse(rm.matches("t"));
  }
}
