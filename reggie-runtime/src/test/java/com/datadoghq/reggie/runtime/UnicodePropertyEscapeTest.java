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

/** Tests for Unicode property escapes: \p{L}, \p{N}, \P{L}, etc. */
public class UnicodePropertyEscapeTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testLetterCategory() {
    ReggieMatcher m = Reggie.compile("\\p{L}+");
    assertNotNull(m.match("hello"), "ASCII letters should match \\p{L}");
    assertNull(m.match("123"), "digits should not match \\p{L}");
    assertNotNull(m.match("héllo"), "Unicode letters should match \\p{L}");
  }

  @Test
  void testNumberCategory() {
    ReggieMatcher m = Reggie.compile("\\p{N}+");
    assertNotNull(m.match("123"), "digits should match \\p{N}");
    assertNull(m.match("abc"), "letters should not match \\p{N}");
  }

  @Test
  void testNegatedLetterCategory() {
    ReggieMatcher m = Reggie.compile("\\P{L}+");
    assertNotNull(m.match("123"), "digits should match \\P{L}");
    assertNull(m.match("abc"), "letters should not match \\P{L}");
  }

  @Test
  void testFindMatchWithLetterCategory() {
    ReggieMatcher m = Reggie.compile("\\p{L}+");
    MatchResult r = m.findMatch("123abc456");
    assertNotNull(r, "should find letters in mixed string");
    assertEquals("abc", r.group(0), "should extract letter span");
  }

  @Test
  void testLetterCategoryInsideCharClass() {
    ReggieMatcher m = Reggie.compile("[\\p{L}0-9]+");
    assertNotNull(m.match("hello123"), "letters and digits should match");
    assertNull(m.match("!@#"), "punctuation should not match");
  }

  @Test
  void testUppercaseLetterCategory() {
    ReggieMatcher m = Reggie.compile("\\p{Lu}+");
    assertNotNull(m.match("ABC"), "uppercase letters should match \\p{Lu}");
    assertNull(m.match("abc"), "lowercase letters should not match \\p{Lu}");
  }

  @Test
  void testLowercaseLetterCategory() {
    ReggieMatcher m = Reggie.compile("\\p{Ll}+");
    assertNotNull(m.match("abc"), "lowercase letters should match \\p{Ll}");
    assertNull(m.match("ABC"), "uppercase letters should not match \\p{Ll}");
  }

  @Test
  void testDecimalDigitCategory() {
    ReggieMatcher m = Reggie.compile("\\p{Nd}+");
    assertNotNull(m.match("123"), "decimal digits should match \\p{Nd}");
    assertNull(m.match("abc"), "letters should not match \\p{Nd}");
  }
}
