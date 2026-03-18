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
 * Test subroutine patterns: (?R), (?1), (?&name) Phase 4: Verify recursive descent implementation
 * works correctly
 */
class SubroutinePatternTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testSimpleNumberedSubroutine() {
    // Pattern: (a+)(?1)
    // Group 1 captures a+, then (?1) calls the PATTERN a+ again (not a backreference)
    // This means: match a+ (captured), then match a+ again
    // Any combination where we have at least 2 a's total works
    ReggieMatcher m = Reggie.compile("(a+)(?1)");

    assertTrue(m.matches("aa"), "Should match 'aa' (a + a)");
    assertTrue(m.matches("aaa"), "Should match 'aaa' (a + aa OR aa + a)");
    assertTrue(m.matches("aaaa"), "Should match 'aaaa' (aa + aa OR a + aaa OR aaa + a)");
    assertTrue(m.matches("aaaaa"), "Should match 'aaaaa'");

    assertFalse(m.matches("a"), "Should not match 'a' (need 1+ for capture AND 1+ for subroutine)");
    assertFalse(m.matches("b"), "Should not match 'b'");
  }

  @Test
  void testRecursivePatternSimple() {
    // Pattern: a(?R)?b
    // Should match: "ab", "aabb", "aaabbb" (balanced a's and b's)
    ReggieMatcher m = Reggie.compile("a(?R)?b");

    assertTrue(m.matches("ab"), "Should match 'ab' (base case)");
    assertTrue(m.matches("aabb"), "Should match 'aabb' (one recursion)");
    assertTrue(m.matches("aaabbb"), "Should match 'aaabbb' (two recursions)");
    assertTrue(m.matches("aaaabbbb"), "Should match 'aaaabbbb' (three recursions)");

    assertFalse(m.matches("aab"), "Should not match 'aab' (unbalanced)");
    assertFalse(m.matches("abb"), "Should not match 'abb' (unbalanced)");
    assertFalse(m.matches("a"), "Should not match 'a'");
  }

  @Test
  void testRecursivePatternComplex() {
    // Pattern: \((?:[^()]|(?R))*\)
    // Matches balanced parentheses
    ReggieMatcher m = Reggie.compile("\\((?:[^()]|(?R))*\\)");

    assertTrue(m.matches("()"), "Should match '()' (empty)");
    assertTrue(m.matches("(a)"), "Should match '(a)'");
    assertTrue(m.matches("(ab)"), "Should match '(ab)'");
    assertTrue(m.matches("((a))"), "Should match '((a))' (nested)");
    assertTrue(m.matches("(a(b)c)"), "Should match '(a(b)c)' (nested)");

    assertFalse(m.matches("("), "Should not match '(' (unbalanced)");
    assertFalse(m.matches(")"), "Should not match ')' (unbalanced)");
    assertFalse(m.matches(")("), "Should not match ')(' (wrong order)");
  }

  @Test
  void testSubroutineWithCapturingGroups() {
    // Pattern: (a)(b)(?1)(?2)
    // Should match: "abab" (capture 'a', capture 'b', match 'a' again, match 'b' again)
    ReggieMatcher m = Reggie.compile("(a)(b)(?1)(?2)");

    assertTrue(m.matches("abab"), "Should match 'abab'");
    assertFalse(m.matches("ab"), "Should not match 'ab' (incomplete)");
    assertFalse(m.matches("aabb"), "Should not match 'aabb' (wrong order)");
  }

  @Test
  void testSubroutineInAlternation() {
    // Pattern: (a|b)(?1)
    // Should match: "aa", "bb", "ab", "ba"
    ReggieMatcher m = Reggie.compile("(a|b)(?1)");

    assertTrue(m.matches("aa"), "Should match 'aa'");
    assertTrue(m.matches("bb"), "Should match 'bb'");
    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("ba"), "Should match 'ba'");

    assertFalse(m.matches("a"), "Should not match 'a' (need 2 chars)");
    assertFalse(m.matches("abc"), "Should not match 'abc' (extra char)");
  }

  @Test
  void testRecursionDepthLimit() {
    // Pattern: a(?R)?b
    // Test that deep recursion throws StackOverflowError
    ReggieMatcher m = Reggie.compile("a(?R)?b");

    // Create a deeply nested pattern (more than MAX_RECURSION_DEPTH)
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 110; i++) {
      sb.append('a');
    }
    for (int i = 0; i < 110; i++) {
      sb.append('b');
    }
    String deeplyNested = sb.toString();

    // Should throw StackOverflowError due to recursion limit (max 100)
    assertThrows(
        StackOverflowError.class,
        () -> {
          m.matches(deeplyNested);
        },
        "Should throw StackOverflowError for deep recursion");
  }

  @Test
  void testFindWithSubroutine() {
    // Pattern: (a+)(?1)
    ReggieMatcher m = Reggie.compile("(a+)(?1)");

    assertTrue(m.find("xxaaayy"), "Should find 'aa' in 'xxaaayy'");
    assertTrue(m.find("bbaaaabbcc"), "Should find 'aaaa' in 'bbaaaabbcc'");

    assertFalse(m.find("xyz"), "Should not find in 'xyz'");
  }

  @Test
  void testMatchWithGroupExtraction() {
    // Pattern: (a+)(?1)
    // With greedy backtracking on input "aaaa":
    // 1. Try (a+) = "aaaa", (?1) at pos 4 → fails
    // 2. Try (a+) = "aaa", (?1) matches "a" → succeeds
    // So group 1 = "aaa" (verified with Perl PCRE)
    ReggieMatcher m = Reggie.compile("(a+)(?1)");

    MatchResult result = m.match("aaaa");
    assertNotNull(result, "Should match 'aaaa'");
    assertEquals("aaaa", result.group(0), "Full match should be 'aaaa'");
    assertEquals("aaa", result.group(1), "Group 1 should be 'aaa'");
  }

  @Test
  void testSubroutineDoesNotModifyOriginalGroup() {
    // Pattern: (a+)x(?1)
    // Group 1 captures a+, then we have 'x', then (?1) calls group 1 again
    // The second call to group 1 should NOT overwrite the first capture
    ReggieMatcher m = Reggie.compile("(a+)x(?1)");

    MatchResult result = m.match("aaaxaaa");
    assertNotNull(result, "Should match 'aaaxaaa'");
    assertEquals("aaa", result.group(1), "Group 1 should be 'aaa' (first capture)");
  }
}
