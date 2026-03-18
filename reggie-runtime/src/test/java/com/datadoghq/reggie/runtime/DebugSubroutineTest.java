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

/** Focused debug on the subroutine interaction. */
class DebugSubroutineTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testSimpleSubroutineInAlternation() {
    // Pattern: a|(?R)
    ReggieMatcher m = Reggie.compile("a|(?R)");
    assertTrue(m.matches("a"), "Should match 'a' (first alternative)");
    // (?R) recursively calls the whole pattern, so it should also match 'a'
    // (infinite recursion, but with base case 'a')
  }

  @Test
  void testSubroutineWithQuantifier() {
    // Pattern: (?:a|(?R))* is invalid - causes infinite recursion on empty input
    // because the greedy star keeps trying to match, and the subroutine
    // alternative doesn't consume input, creating an infinite loop.
    // Use a pattern that requires progress: a(?R)?
    ReggieMatcher m = Reggie.compile("a(?R)?");
    assertTrue(m.matches("a"), "Should match 'a' (zero recursions)");
    assertTrue(m.matches("aa"), "Should match 'aa' (one recursion)");
    assertTrue(m.matches("aaa"), "Should match 'aaa' (two recursions)");
  }

  @Test
  void testSubroutineWithLiteralPrefix() {
    // Pattern: xa(?R)?y can only match xay, xaxayy, xaxaxayyy, etc.
    // because (?R) calls the entire pattern which starts with 'x'
    ReggieMatcher m = Reggie.compile("xa(?R)?y");
    assertTrue(m.matches("xay"), "Should match 'xay' (zero recursions)");
    assertTrue(m.matches("xaxayy"), "Should match 'xaxayy' (one recursion)");
  }

  @Test
  void testComplexPatternEmpty() {
    // Pattern: \((?:[^()]|(?R))*\)
    ReggieMatcher m = Reggie.compile("\\((?:[^()]|(?R))*\\)");
    assertTrue(m.matches("()"), "Should match '()' (zero times) - BASE CASE");
  }

  @Test
  void testComplexPatternSingleChar() {
    // Pattern: \((?:[^()]|(?R))*\)
    ReggieMatcher m = Reggie.compile("\\((?:[^()]|(?R))*\\)");
    assertTrue(m.matches("(a)"), "Should match '(a)' (first alt once)");
  }

  @Test
  void testComplexPatternNested() {
    // Pattern: \((?:[^()]|(?R))*\)
    ReggieMatcher m = Reggie.compile("\\((?:[^()]|(?R))*\\)");
    assertTrue(m.matches("((a))"), "Should match '((a))' (second alt - recursion)");
  }
}
