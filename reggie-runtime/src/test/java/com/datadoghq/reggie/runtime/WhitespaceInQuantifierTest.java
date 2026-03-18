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
 * Tests for whitespace handling in quantifiers {n,m}. PCRE allows whitespace inside quantifiers
 * even outside extended mode.
 */
class WhitespaceInQuantifierTest {

  @Test
  void testWhitespaceAfterOpenBrace() {
    // { 3} should work like {3}
    ReggieMatcher rm = Reggie.compile("a{ 3}");
    assertTrue(rm.matches("aaa"));
    assertFalse(rm.matches("aa"));
    assertFalse(rm.matches("aaaa"));
  }

  @Test
  void testWhitespaceBeforeCloseBrace() {
    // {3 } should work like {3}
    ReggieMatcher rm = Reggie.compile("a{3 }");
    assertTrue(rm.matches("aaa"));
    assertFalse(rm.matches("aa"));
    assertFalse(rm.matches("aaaa"));
  }

  @Test
  void testWhitespaceAroundNumber() {
    // { 3 } should work like {3}
    ReggieMatcher rm = Reggie.compile("a{ 3 }");
    assertTrue(rm.matches("aaa"));
    assertFalse(rm.matches("aa"));
    assertFalse(rm.matches("aaaa"));
  }

  @Test
  void testWhitespaceWithComma() {
    // { 3, } should work like {3,}
    ReggieMatcher rm = Reggie.compile("a{ 3, }");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
  }

  @Test
  void testWhitespaceAroundComma() {
    // { 3 , 5 } should work like {3,5}
    ReggieMatcher rm = Reggie.compile("a{ 3 , 5 }");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
    assertFalse(rm.matches("aaaaaa"));
  }

  @Test
  void testWhitespaceBeforeComma() {
    // {3 ,5} should work like {3,5}
    ReggieMatcher rm = Reggie.compile("a{3 ,5}");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
    assertFalse(rm.matches("aaaaaa"));
  }

  @Test
  void testWhitespaceAfterComma() {
    // {3, 5} should work like {3,5}
    ReggieMatcher rm = Reggie.compile("a{3, 5}");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
    assertFalse(rm.matches("aaaaaa"));
  }

  @Test
  void testComplexPatternWithWhitespace() {
    // PCRE test pattern: A{ 3, }
    ReggieMatcher rm = Reggie.compile("A{ 3, }");
    assertFalse(rm.matches("AA"));
    assertTrue(rm.matches("AAA"));
    assertTrue(rm.matches("AAAA"));
    assertTrue(rm.matches("AAAAA"));
  }

  @Test
  void testCharClassWithWhitespace() {
    // [a-z]{ 2 , 4 }
    ReggieMatcher rm = Reggie.compile("[a-z]{ 2 , 4 }");
    assertFalse(rm.matches("a"));
    assertTrue(rm.matches("ab"));
    assertTrue(rm.matches("abc"));
    assertTrue(rm.matches("abcd"));
    assertFalse(rm.matches("abcde"));
  }

  @Test
  void testDigitsWithWhitespace() {
    // \d{ 1, }
    ReggieMatcher rm = Reggie.compile("\\d{ 1, }");
    assertTrue(rm.matches("1"));
    assertTrue(rm.matches("123"));
    assertTrue(rm.matches("123456789"));
    assertFalse(rm.matches(""));
    assertFalse(rm.matches("abc"));
  }

  @Test
  void testMultipleWhitespaces() {
    // {  3  ,  5  } with multiple spaces
    ReggieMatcher rm = Reggie.compile("a{  3  ,  5  }");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
    assertFalse(rm.matches("aaaaaa"));
  }

  @Test
  void testTabsInQuantifier() {
    // {\t3\t,\t5\t} with tabs
    ReggieMatcher rm = Reggie.compile("a{\t3\t,\t5\t}");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
    assertFalse(rm.matches("aaaaaa"));
  }

  @Test
  void testNewlinesInQuantifier() {
    // With newlines (edge case, but should work)
    ReggieMatcher rm = Reggie.compile("a{\n3\n,\n5\n}");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
    assertFalse(rm.matches("aaaaaa"));
  }

  @Test
  void testNoWhitespaceStillWorks() {
    // Verify that patterns without whitespace still work
    ReggieMatcher rm = Reggie.compile("a{3,5}");
    assertFalse(rm.matches("aa"));
    assertTrue(rm.matches("aaa"));
    assertTrue(rm.matches("aaaa"));
    assertTrue(rm.matches("aaaaa"));
    assertFalse(rm.matches("aaaaaa"));
  }
}
