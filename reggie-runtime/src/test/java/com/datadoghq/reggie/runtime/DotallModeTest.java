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

/** Tests for dotall mode (?s) where . matches newlines */
class DotallModeTest {

  @Test
  void testDotMatchesNewlineInDotallMode() {
    // (?s). should match newline
    ReggieMatcher rm = Reggie.compile("(?s).");
    assertTrue(rm.matches("\n"));
    assertTrue(rm.matches("a"));
  }

  @Test
  void testDotDoesNotMatchNewlineInNormalMode() {
    // . should NOT match newline in normal mode
    ReggieMatcher rm = Reggie.compile(".");
    assertFalse(rm.matches("\n"));
    assertTrue(rm.matches("a"));
  }

  @Test
  void testDotallWithGreedyQuantifier() {
    // (?s).* should match across newlines
    ReggieMatcher rm = Reggie.compile("(?s)a.*z");
    assertTrue(rm.matches("a\nz"));
    assertTrue(rm.matches("abc\ndef\nxyz"));
  }

  @Test
  void testNormalModeDoesNotMatchAcrossNewlines() {
    // .* should NOT match across newlines in normal mode
    ReggieMatcher rm = Reggie.compile("a.*z");
    assertFalse(rm.matches("a\nz"));
    assertTrue(rm.matches("abcz"));
  }

  @Test
  void testDotallWithAlternation() {
    // Pattern from PCRE test: (?s)(.*X|^B)
    ReggieMatcher rm = Reggie.compile("(?s)(.*X|^B)");

    // Should match "abcde\n1234X" with group 1 = "abcde\n1234X"
    MatchResult result = rm.findMatch("abcde\n1234Xyz");
    assertNotNull(result);
    assertEquals("abcde\n1234X", result.group(0));
    assertEquals("abcde\n1234X", result.group(1));
  }

  @Test
  void testDotallWithAlternationStartAnchor() {
    // Pattern: (?s)(.*X|^B) with input starting with B
    ReggieMatcher rm = Reggie.compile("(?s)(.*X|^B)");

    MatchResult result = rm.findMatch("BarFoo");
    assertNotNull(result);
    assertEquals("B", result.group(0));
    assertEquals("B", result.group(1));
  }

  @Test
  void testDotallInGroup() {
    // Test: ((?s)b.)c with dotall in nested group
    ReggieMatcher rm = Reggie.compile("((?s)b.)c");

    String input = "a\nb\nc\n";
    MatchResult result = rm.findMatch(input);
    assertNotNull(result);
    assertEquals("b\nc", result.group(0));
    assertEquals("b\n", result.group(1));
  }
}
