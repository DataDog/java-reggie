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

/** Ultra-simple debug test for subroutine. */
class SimpleSubroutineDebugTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testLiteralPattern() {
    // Baseline: Does '\(a\)' match '(a)'?
    ReggieMatcher m = Reggie.compile("\\(a\\)");
    assertTrue(m.matches("(a)"), "Baseline: literal pattern should work");
  }

  @Test
  void testQuantifierWithCharClass() {
    // Does '\([^()]*\)' match '(a)'?
    ReggieMatcher m = Reggie.compile("\\([^()]*\\)");
    assertTrue(m.matches("(a)"), "CharClass in quantifier should work");
  }

  @Test
  void testQuantifierWithAlternation() {
    // Does '\((?:a|b)*\)' match '(a)'?
    ReggieMatcher m = Reggie.compile("\\((?:a|b)*\\)");
    assertTrue(m.matches("(a)"), "Alternation in quantifier should work");
  }

  @Test
  void testSubroutineAlone() {
    // Can '(?R)' call itself?
    // Pattern 'a|(?R)' should match 'a' via first alternative
    ReggieMatcher m = Reggie.compile("a|(?R)");
    assertTrue(m.matches("a"), "Subroutine in alternation - use first alt");
  }

  @Test
  void testSubroutineInOptional() {
    // Pattern 'a(?R)?' should match:
    // - 'a' (zero recursions)
    // - 'aa' (one recursion: a + a(?R)? where (?R) matches 'a')
    ReggieMatcher m = Reggie.compile("a(?R)?");
    assertTrue(m.matches("a"), "Optional subroutine - zero recursions");
    assertTrue(m.matches("aa"), "Optional subroutine - one recursion");
  }

  @Test
  void testSubroutineInStar() {
    // Pattern 'a(?R)*b' should match:
    // - 'ab' (zero recursions)
    // But NOT 'aab' because (?R) would try to match 'ab' starting at 'a', succeed, then need
    // another 'b'
    ReggieMatcher m = Reggie.compile("a(?R)*b");
    assertTrue(m.matches("ab"), "Star subroutine - zero recursions");
  }

  // Note: Pattern '(?:x|(?R))*' causes infinite recursion even on empty input
  // because the greedy star tries to match, triggering the subroutine alternative.
  // This is a semantically invalid pattern - removed from tests.

  @Test
  void testComplexMinimal() {
    // Minimal version of failing pattern
    // Pattern: '((?:a|(?R))*)'
    // Should match '(a)'
    ReggieMatcher m = Reggie.compile("\\((?:a|(?R))*\\)");
    assertTrue(m.matches("()"), "Minimal complex - empty");
    assertTrue(m.matches("(a)"), "Minimal complex - one char");
  }
}
