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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Tests for recursive patterns using (?R), (?1), etc. */
public class TestRecursivePatterns {

  @Test
  void testSimpleSubroutineCall() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(a|)*(?1)b");

    System.out.println("[DEBUG] Pattern: (a|)*(?1)b");
    assertTrue(m.matches("b"), "Should match 'b'");
    assertTrue(m.matches("ab"), "Should match 'ab'");
  }

  /**
   * Recursive palindrome patterns are a known limitation. Run with -Dreggie.test.knownFailures=true
   * to enable.
   */
  @Test
  @EnabledIfSystemProperty(named = "reggie.test.knownFailures", matches = "true")
  void testRecursiveCallToEntirePattern() {
    RuntimeCompiler.clearCache();
    // Palindrome matcher: ^((.)(?R)\2|.?)$
    ReggieMatcher m = Reggie.compile("^((.)(?R)\\2|.?)$");

    System.out.println("[DEBUG] Pattern: ^((.)(?R)\\2|.?)$");

    assertTrue(m.matches("a"), "Should match 'a'");
    assertTrue(m.matches("aba"), "Should match 'aba'");
    assertTrue(m.matches("abba"), "Should match 'abba'");
    assertTrue(m.matches("abcba"), "Should match 'abcba'");
    assertFalse(m.matches("abc"), "Should NOT match 'abc'");
  }

  @Test
  void testSubroutineWithCapture() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("(?1)(a(b)|(c))");

    System.out.println("[DEBUG] Pattern: (?1)(a(b)|(c))");

    MatchResult r1 = m.match("abc");
    assertNotNull(r1, "Should match 'abc'");
    assertEquals("c", r1.group(1));

    MatchResult r2 = m.match("cab");
    assertNotNull(r2, "Should match 'cab'");
    assertEquals("ab", r2.group(1));
  }

  @Test
  void testRecursiveWithQuantifier() {
    RuntimeCompiler.clearCache();
    ReggieMatcher m = Reggie.compile("^(a)(?1)+ab");

    System.out.println("[DEBUG] Pattern: ^(a)(?1)+ab");
    assertTrue(m.matches("aaaab"), "Should match 'aaaab'");
  }
}
