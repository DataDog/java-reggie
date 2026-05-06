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
 * Tests for patterns with multi-range character classes, exercising MultiRangeOptimization
 * (isAlpha, isAlphaNum, and general multi-range paths) and SWARPatternAnalyzer.
 */
class MultiRangeCharClassTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── [a-zA-Z] — alpha path ──────────────────────────────────────────────────

  @Test
  void alphaMatchesLetters() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]+");
    assertTrue(m.matches("hello"));
    assertTrue(m.matches("WORLD"));
    assertTrue(m.matches("CamelCase"));
  }

  @Test
  void alphaRejectsNonLetters() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]+");
    assertFalse(m.matches("hello1"));
    assertFalse(m.matches("123"));
    assertFalse(m.matches(""));
  }

  @Test
  void alphaFind() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]+");
    assertTrue(m.find("123abc456"));
    assertFalse(m.find("123456"));
  }

  @Test
  void alphaFindFrom() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]+");
    assertEquals(3, m.findFrom("123abc", 0));
    assertEquals(-1, m.findFrom("123abc", 6));
  }

  @Test
  void alphaBounded() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]+");
    assertTrue(m.matchesBounded("123abc456", 3, 6));
    assertFalse(m.matchesBounded("123abc456", 0, 3));
  }

  // ── [a-zA-Z0-9] — alphaNum variant-2 path ────────────────────────────────

  @Test
  void alphaNumV2Matches() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z0-9]+");
    assertTrue(m.matches("abc123"));
    assertTrue(m.matches("ABC"));
    assertTrue(m.matches("999"));
  }

  @Test
  void alphaNumV2Rejects() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z0-9]+");
    assertFalse(m.matches("abc_123"));
    assertFalse(m.matches(""));
  }

  @Test
  void alphaNumV2Find() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z0-9]+");
    assertTrue(m.find("!hello2!"));
    assertFalse(m.find("!@#$%"));
  }

  // ── [0-9a-zA-Z] — alphaNum variant-1 path ────────────────────────────────

  @Test
  void alphaNumV1Matches() {
    ReggieMatcher m = Reggie.compile("[0-9a-zA-Z]+");
    assertTrue(m.matches("123abc"));
    assertTrue(m.matches("XYZ"));
    assertTrue(m.matches("007bond"));
  }

  @Test
  void alphaNumV1Rejects() {
    ReggieMatcher m = Reggie.compile("[0-9a-zA-Z]+");
    assertFalse(m.matches("hello world")); // space
    assertFalse(m.matches(""));
  }

  // ── General multi-range (not alpha / alphaNum) ────────────────────────────

  @Test
  void twoRangesLowerAndDigit() {
    ReggieMatcher m = Reggie.compile("[a-z0-9]+");
    assertTrue(m.matches("abc123"));
    assertTrue(m.matches("xyz"));
    assertTrue(m.matches("999"));
    assertFalse(m.matches("ABC"));
    assertFalse(m.matches(""));
  }

  @Test
  void threeRangesWithUnderscore() {
    ReggieMatcher m = Reggie.compile("[a-z0-9_]+");
    assertTrue(m.matches("hello_123"));
    assertTrue(m.matches("_"));
    assertFalse(m.matches("UPPER"));
  }

  @Test
  void hexCharClass() {
    ReggieMatcher m = Reggie.compile("[0-9a-fA-F]+");
    assertTrue(m.matches("DEADBEEF"));
    assertTrue(m.matches("0123456789abcdef"));
    assertFalse(m.matches("xyz"));
  }

  @Test
  void hexFind() {
    ReggieMatcher m = Reggie.compile("[0-9a-fA-F]+");
    assertTrue(m.find("color: #FF0000"));
    assertFalse(m.find("xyz"));
  }

  // ── Bounded quantifiers with multi-range ─────────────────────────────────

  @Test
  void alphaExactQuantifier() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]{3}");
    assertTrue(m.matches("abc"));
    assertFalse(m.matches("ab"));
    assertFalse(m.matches("abcd"));
  }

  @Test
  void alphaNumRangeQuantifier() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z0-9]{2,4}");
    assertTrue(m.matches("ab"));
    assertTrue(m.matches("abc"));
    assertTrue(m.matches("abcd"));
    assertFalse(m.matches("a"));
    assertFalse(m.matches("abcde"));
  }

  // ── Multi-range with anchors ───────────────────────────────────────────────

  @Test
  void alphaMatchesBounded() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]+");
    assertTrue(m.matchesBounded("prefix hello suffix", 7, 12));
    assertFalse(m.matchesBounded("prefix 123 suffix", 7, 10));
  }

  @Test
  void alphaFindBoundsFrom() {
    ReggieMatcher m = Reggie.compile("[a-zA-Z]+");
    int[] bounds = new int[2];
    assertTrue(m.findBoundsFrom("123abc456def", 0, bounds));
    assertEquals(3, bounds[0]);
    assertEquals(6, bounds[1]);
  }
}
