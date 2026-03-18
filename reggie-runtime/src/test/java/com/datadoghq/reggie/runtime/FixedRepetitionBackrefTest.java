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
 * Tests for FIXED_REPETITION_BACKREF strategy. Patterns like (a)\1{n} where the backreference has
 * fixed repetition bounds.
 */
class FixedRepetitionBackrefTest {

  @Test
  void testSingleCharRepetition_exactMatch() {
    // (a)\1{3} - capture 'a', then exactly 3 more 'a's = "aaaa"
    ReggieMatcher m = Reggie.compile("(a)\\1{3}");

    assertTrue(m.matches("aaaa"), "(a)\\1{3} should match 'aaaa'");
    assertFalse(m.matches("aaa"), "Should not match 'aaa' (only 2 backrefs)");
    assertFalse(m.matches("aaaaa"), "Should not match 'aaaaa' (4 backrefs, need exactly 3)");
  }

  @Test
  void testSingleCharRepetition_minOnly() {
    // (a)\1{2,} - capture 'a', then 2 or more 'a's
    ReggieMatcher m = Reggie.compile("(a)\\1{2,}");

    assertTrue(m.matches("aaa"), "(a)\\1{2,} should match 'aaa' (2 backrefs)");
    assertTrue(m.matches("aaaa"), "Should match 'aaaa' (3 backrefs)");
    assertTrue(m.matches("aaaaaa"), "Should match 'aaaaaa' (5 backrefs)");
    assertFalse(m.matches("aa"), "Should not match 'aa' (only 1 backref)");
    assertFalse(m.matches("a"), "Should not match 'a' (no backrefs)");
  }

  @Test
  void testSingleCharRepetition_bounded() {
    // (a)\1{2,4} - capture 'a', then 2 to 4 'a's
    ReggieMatcher m = Reggie.compile("(a)\\1{2,4}");

    assertTrue(m.matches("aaa"), "(a)\\1{2,4} should match 'aaa' (2 backrefs)");
    assertTrue(m.matches("aaaa"), "Should match 'aaaa' (3 backrefs)");
    assertTrue(m.matches("aaaaa"), "Should match 'aaaaa' (4 backrefs)");
    assertFalse(m.matches("aa"), "Should not match 'aa' (1 backref, min is 2)");
    assertFalse(m.matches("aaaaaa"), "Should not match 'aaaaaa' (5 backrefs, max is 4)");
  }

  @Test
  void testDigitCharClass() {
    // (\d)\1{2} - capture digit, then exactly 2 more of the same digit
    ReggieMatcher m = Reggie.compile("(\\d)\\1{2}");

    assertTrue(m.matches("111"), "(\\d)\\1{2} should match '111'");
    assertTrue(m.matches("999"), "Should match '999'");
    assertFalse(m.matches("123"), "Should not match '123' (different digits)");
    assertFalse(m.matches("11"), "Should not match '11' (only 1 backref)");
  }

  @Test
  void testFind() {
    // Find pattern in longer string
    ReggieMatcher m = Reggie.compile("(a)\\1{2}");

    assertTrue(m.find("xxxaaayyy"), "Should find 'aaa' in 'xxxaaayyy'");
    assertTrue(m.find("aaa"), "Should find 'aaa' in 'aaa'");
    assertFalse(m.find("aa"), "Should not find in 'aa'");
    assertFalse(m.find("xyz"), "Should not find in 'xyz'");
  }

  @Test
  void testFindFrom() {
    ReggieMatcher m = Reggie.compile("(a)\\1{2}");

    assertEquals(3, m.findFrom("xxxaaayyy", 0), "Should find at position 3");
    assertEquals(3, m.findFrom("xxxaaayyy", 3), "Should find at position 3 when starting there");
    assertEquals(-1, m.findFrom("xxxaaayyy", 4), "Should not find starting after match");
  }
}
