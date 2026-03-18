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
 * Debug test for complex pattern: \((?:[^()]|(?R))*\) Breaking down the pattern step by step to
 * find the issue.
 */
class DebugComplexPatternTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testJustEscapedParens() {
    ReggieMatcher m = Reggie.compile("\\(\\)");
    assertTrue(m.matches("()"), "Should match '()'");
  }

  @Test
  void testEscapedParensWithLiteral() {
    ReggieMatcher m = Reggie.compile("\\(a\\)");
    assertTrue(m.matches("(a)"), "Should match '(a)'");
  }

  @Test
  void testNonCapturingGroupWithLiteral() {
    ReggieMatcher m = Reggie.compile("\\((?:a)\\)");
    assertTrue(m.matches("(a)"), "Should match '(a)'");
  }

  @Test
  void testNonCapturingGroupWithCharClass() {
    ReggieMatcher m = Reggie.compile("\\((?:[^()])\\)");
    assertTrue(m.matches("(a)"), "Should match '(a)'");
    assertTrue(m.matches("(b)"), "Should match '(b)'");
    assertFalse(m.matches("()"), "Should not match '()' (requires exactly one char)");
  }

  @Test
  void testOptionalNonCapturingGroup() {
    ReggieMatcher m = Reggie.compile("\\((?:[^()])?\\)");
    assertTrue(m.matches("()"), "Should match '()' (zero chars)");
    assertTrue(m.matches("(a)"), "Should match '(a)' (one char)");
    assertFalse(m.matches("(ab)"), "Should not match '(ab)' (two chars)");
  }

  @Test
  void testStarQuantifier() {
    ReggieMatcher m = Reggie.compile("\\((?:[^()])*\\)");
    assertTrue(m.matches("()"), "Should match '()' (zero chars)");
    assertTrue(m.matches("(a)"), "Should match '(a)' (one char)");
    assertTrue(m.matches("(ab)"), "Should match '(ab)' (two chars)");
    assertTrue(m.matches("(abc)"), "Should match '(abc)' (three chars)");
    assertFalse(m.matches("(a()"), "Should not match '(a()' (unbalanced)");
  }

  @Test
  void testAlternationWithCharClass() {
    ReggieMatcher m = Reggie.compile("\\((?:[^()]|x)*\\)");
    assertTrue(m.matches("()"), "Should match '()' (zero chars)");
    assertTrue(m.matches("(a)"), "Should match '(a)' (first alternative)");
    assertTrue(m.matches("(x)"), "Should match '(x)' (second alternative)");
    assertTrue(m.matches("(ax)"), "Should match '(ax)' (both alternatives)");
  }

  @Test
  void testFullComplexPattern() {
    ReggieMatcher m = Reggie.compile("\\((?:[^()]|(?R))*\\)");
    assertTrue(m.matches("()"), "Should match '()' (empty)");
    assertTrue(m.matches("(a)"), "Should match '(a)' (single char)");
    assertTrue(m.matches("(ab)"), "Should match '(ab)' (two chars)");
    assertTrue(m.matches("((a))"), "Should match '((a))' (nested)");
  }
}
