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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Correctness tests for PCRE subroutine patterns. Verifies that patterns match/reject the expected
 * inputs.
 */
public class SubroutineCorrectnessTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testNumberedSubroutine_aa() {
    // Pattern: (a+)(?1) - match 'a+' then call group 1 again
    // Should match: "aa" (a+ matches 'a', then (?1) matches 'a' again)
    ReggieMatcher m = RuntimeCompiler.compile("(a+)(?1)");
    assertTrue(m.matches("aa"), "Pattern (a+)(?1) should match 'aa'");
  }

  @Test
  public void testNumberedSubroutine_aaaa() {
    // Pattern: (a+)(?1)
    // Should match: "aaaa" (a+ matches 'aa', then (?1) matches 'aa' again)
    ReggieMatcher m = RuntimeCompiler.compile("(a+)(?1)");

    // Test various even-length inputs to find the pattern
    System.err.println("DEBUG testNumberedSubroutine_aaaa:");
    for (int i = 2; i <= 8; i += 2) {
      String input = "a".repeat(i);
      boolean result = m.matches(input);
      System.err.printf("  (a+)(?1) with %d 'a's: %b%n", i, result);
    }

    boolean result = m.matches("aaaa");
    if (!result) {
      System.err.println("  ERROR: Pattern (a+)(?1) should match 'aaaa' but returned false");
      System.err.println("  Expected: group matches 'aa', subroutine matches 'aa'");
    }
    assertTrue(result, "Pattern (a+)(?1) should match 'aaaa'");
  }

  @Test
  public void testNumberedSubroutine_ab_noMatch() {
    // Pattern: (a+)(?1)
    // Should NOT match: "ab" (a+ matches 'a', but (?1) expects 'a+' not 'b')
    ReggieMatcher m = RuntimeCompiler.compile("(a+)(?1)");
    assertFalse(m.matches("ab"), "Pattern (a+)(?1) should NOT match 'ab'");
  }

  @Test
  public void testRecursivePattern_ab() {
    // Pattern: a(?R)?b - match 'a', optionally recurse, then match 'b'
    // Should match: "ab" (a, no recursion, b)
    ReggieMatcher m = RuntimeCompiler.compile("a(?R)?b");
    boolean result = m.matches("ab");
    if (!result) {
      System.err.println("DEBUG: Pattern a(?R)?b FAILED to match 'ab', got: " + result);
    }
    assertTrue(result, "Pattern a(?R)?b should match 'ab'");
  }

  @Test
  public void testRecursivePattern_aabb() {
    // Pattern: a(?R)?b
    // Should match: "aabb" (a, recurse to match 'ab', b)
    ReggieMatcher m = RuntimeCompiler.compile("a(?R)?b");
    assertTrue(m.matches("aabb"), "Pattern a(?R)?b should match 'aabb'");
  }

  @Test
  public void testRecursivePattern_aaabbb() {
    // Pattern: a(?R)?b
    // Should match: "aaabbb" (a, recurse to match 'aabb', b)
    ReggieMatcher m = RuntimeCompiler.compile("a(?R)?b");
    assertTrue(m.matches("aaabbb"), "Pattern a(?R)?b should match 'aaabbb'");
  }

  @Test
  public void testRecursivePattern_aab_noMatch() {
    // Pattern: a(?R)?b
    // Should NOT match: "aab" (unbalanced - two 'a' but only one 'b')
    ReggieMatcher m = RuntimeCompiler.compile("a(?R)?b");
    assertFalse(m.matches("aab"), "Pattern a(?R)?b should NOT match 'aab'");
  }

  @Test
  public void testNestedRecursion_ab() {
    // Pattern: (a(?1)?b) - group that recursively calls itself
    // Should match: "ab"
    ReggieMatcher m = RuntimeCompiler.compile("(a(?1)?b)");
    assertTrue(m.matches("ab"), "Pattern (a(?1)?b) should match 'ab'");
  }

  @Test
  public void testNestedRecursion_aabb() {
    // Pattern: (a(?1)?b)
    // Should match: "aabb"
    ReggieMatcher m = RuntimeCompiler.compile("(a(?1)?b)");
    assertTrue(m.matches("aabb"), "Pattern (a(?1)?b) should match 'aabb'");
  }

  @Test
  public void testNestedRecursion_aaabbb() {
    // Pattern: (a(?1)?b)
    // Should match: "aaabbb"
    ReggieMatcher m = RuntimeCompiler.compile("(a(?1)?b)");
    assertTrue(m.matches("aaabbb"), "Pattern (a(?1)?b) should match 'aaabbb'");
  }
}
