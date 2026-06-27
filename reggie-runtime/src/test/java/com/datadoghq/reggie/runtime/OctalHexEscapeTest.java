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
import org.junit.jupiter.api.Test;

/**
 * Tests for octal and hex escape sequences. PCRE supports: - Hex escapes: \xhh where h is a hex
 * digit (e.g., \x40 = '@') - Octal escapes: \nnn where n is 0-7 (e.g., \100 = '@', \1000 = '@0')
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
  void testOctalEscapeTwoDigits() {
    // \40 = ' ' (octal 40 = decimal 32 = space)
    ReggieMatcher rm = Reggie.compile("\\40");
    assertTrue(rm.matches(" "));
    assertFalse(rm.matches("40"));
  }

  @Test
  void testOctalEscapeThreeDigits() {
    // \100 = '@' (octal 100 = decimal 64)
    ReggieMatcher rm = Reggie.compile("\\100");
    assertTrue(rm.matches("@"));
    assertFalse(rm.matches("100"));
  }

  @Test
  void testOctalEscapeWithExtraDigit() {
    // \1000 = '@' + '0' (octal 100 = '@', then literal '0')
    ReggieMatcher rm = Reggie.compile("\\1000");
    assertTrue(rm.matches("@0"));
    assertFalse(rm.matches("1000"));
  }

  @Test
  void testOctalEscapeMaxValue() {
    // \377 = 'ÿ' (octal 377 = decimal 255 = max byte value)
    ReggieMatcher rm = Reggie.compile("\\377");
    assertTrue(rm.matches("\u00FF"));
  }

  @Test
  void testOctalStartingWithZero() {
    // \071 = '9' (octal 71 = decimal 57)
    ReggieMatcher rm = Reggie.compile("\\071");
    assertTrue(rm.matches("9"));
  }

  @Test
  void testPatternWithCaptureGroupAndOctal() {
    // (abc)\100 with 1 group: full number 100 > totalGroupCount(1), so \100 is octal '@' (codepoint
    // 64).
    // PCRE never attempts shorter prefixes.
    ReggieMatcher rm = Reggie.compile("(abc)\\100");

    assertTrue(rm.matches("abc@"));
    assertFalse(rm.matches("abcabc00"));
  }

  @Test
  void testPatternWithCaptureGroupAndOctalPlusDigit() {
    // (abc)\1000 with 1 group: full number 100 > totalGroupCount(1), so \100 is octal '@'.
    // The trailing '0' is a literal character.
    ReggieMatcher rm = Reggie.compile("(abc)\\1000");

    assertTrue(rm.matches("abc@0"));
    assertFalse(rm.matches("abcabc000"));
  }

  @Test
  void backrefEightNineWithNoGroup() {
    // \8 with 0 groups: no valid backref; \8 is literal '8'.
    ReggieMatcher rm8 = Reggie.compile("\\8");
    assertTrue(rm8.matches("8"));
    assertFalse(rm8.matches("a"));

    // \9 with 0 groups: no valid backref; \9 is literal '9'.
    ReggieMatcher rm9 = Reggie.compile("\\9");
    assertTrue(rm9.matches("9"));
    assertFalse(rm9.matches("a"));

    // \89 with 1 group (totalGroupCount=1 < 89): \8 is literal '8', '9' is parsed as subsequent
    // token.
    ReggieMatcher rm89 = Reggie.compile("(a)\\89");
    assertTrue(rm89.matches("a89"));
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
  void testMixedEscapesInPattern() {
    // Test: \x48\145\154\154\x6f = "Hello"
    // \x48 = 'H', \145 = 'e' (octal), \154 = 'l' (octal), \154 = 'l' (octal), \x6f = 'o'
    ReggieMatcher rm = Reggie.compile("\\x48\\145\\154\\154\\x6f");
    assertTrue(rm.matches("Hello"));
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
