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
import com.datadoghq.reggie.UnsupportedPatternException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Test the specific interaction between CharClass and Alternation. */
class CharClassAlternationTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testAlternationLiteralOrLiteral() {
    ReggieMatcher m = Reggie.compile("(?:a|b)");
    assertTrue(m.matches("a"));
    assertTrue(m.matches("b"));
  }

  @Test
  void testAlternationCharClassOrLiteral() {
    ReggieMatcher m = Reggie.compile("(?:[^()]|a)");
    assertTrue(m.matches("a"), "Should match 'a' via either alternative");
    assertTrue(m.matches("x"), "Should match 'x' via first alternative");
    assertFalse(m.matches("("), "Should not match '('");
  }

  @Test
  void testAlternationCharClassOrLiteralInStar() {
    ReggieMatcher m = Reggie.compile("(?:[^()]|a)*");
    assertTrue(m.matches(""), "Should match empty");
    assertTrue(m.matches("a"), "Should match 'a'");
    assertTrue(m.matches("x"), "Should match 'x'");
    assertTrue(m.matches("ax"), "Should match 'ax'");
  }

  @Test
  void testAlternationCharClassOrSubroutine() {
    // D1: (?R) inside alternation arm requires intra-call backtracking
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("(?:[^()]|(?R))"));
  }

  @Test
  void testAlternationCharClassOrSubroutineInStar() {
    // D1: (?R) inside alternation arm requires intra-call backtracking
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("\\((?:[^()]|(?R))*\\)"));
  }
}
