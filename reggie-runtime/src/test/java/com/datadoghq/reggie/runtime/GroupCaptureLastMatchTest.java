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
 * Tests for POSIX last-match group capture semantics in quantified contexts.
 *
 * <p>This test verifies that capturing groups inside quantifiers capture the LAST iteration's
 * content, not the first iteration or the entire span.
 *
 * <p>POSIX semantics: (a?)+ on "ab" should capture "" (empty from last iteration), not "a" (from
 * first iteration).
 */
public class GroupCaptureLastMatchTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testSimpleQuantifiedGroup() {
    // Pattern: (a?)+b on "ab"
    // First iteration: matches 'a' at pos 0-1
    // Second iteration: matches '' (empty) at pos 1-1, then 'b' matches
    // Expected: group 1 should capture from LAST iteration = ""
    ReggieMatcher m = Reggie.compile("(a?)+b");
    MatchResult result = m.match("ab");

    assertNotNull(result, "Pattern should match");
    assertEquals("ab", result.group(0), "Full match should be 'ab'");
    assertEquals("", result.group(1), "Group 1 should capture '' (empty from last iteration)");
  }

  @Test
  void testQuantifiedAlternation() {
    // Pattern: (a|b)+ on "ab"
    // First iteration: matches 'a' at pos 0-1
    // Second iteration: matches 'b' at pos 1-2
    // Expected: group 1 should capture from LAST iteration = "b"
    ReggieMatcher m = Reggie.compile("(a|b)+");
    MatchResult result = m.match("ab");

    assertNotNull(result, "Pattern should match");
    assertEquals("ab", result.group(0), "Full match should be 'ab'");
    assertEquals("b", result.group(1), "Group 1 should capture 'b' (from last iteration)");
  }

  @Test
  void testEmptyGroupInQuantifier() {
    // Pattern: a(b*) on "a"
    // Group (b*) matches zero 'b's at position 1
    // Expected: group 1 should be "" (empty string, not null)
    ReggieMatcher m = Reggie.compile("a(b*)");
    MatchResult result = m.match("a");

    assertNotNull(result, "Pattern should match");
    assertEquals("a", result.group(0), "Full match should be 'a'");
    assertEquals("", result.group(1), "Group 1 should be empty string, not null");
  }

  @Test
  void testQuantifiedGroupMultipleMatches() {
    // Pattern: (a)+ on "aaa"
    // Iteration 1: matches 'a' at 0-1
    // Iteration 2: matches 'a' at 1-2
    // Iteration 3: matches 'a' at 2-3
    // Expected: group 1 captures LAST iteration = "a" at pos 2-3
    ReggieMatcher m = Reggie.compile("(a)+");
    MatchResult result = m.match("aaa");

    assertNotNull(result, "Pattern should match");
    assertEquals("aaa", result.group(0), "Full match should be 'aaa'");
    assertEquals("a", result.group(1), "Group 1 should capture 'a' (from last iteration)");
    assertEquals(2, result.start(1), "Group 1 should start at position 2 (last iteration)");
    assertEquals(3, result.end(1), "Group 1 should end at position 3 (last iteration)");
  }

  @Test
  void testAlternationInQuantifier() {
    // Pattern: (a+|b)+ on "aaabaa"
    // Iterations: "aaa", "b", "aa"
    // Expected: group 1 captures LAST iteration = "aa"
    ReggieMatcher m = Reggie.compile("(a+|b)+");
    MatchResult result = m.match("aaabaa");

    assertNotNull(result, "Pattern should match");
    assertEquals("aaabaa", result.group(0), "Full match");
    assertEquals("aa", result.group(1), "Group 1 should capture last iteration 'aa'");
  }

  @Test
  void testOptionalQuantifiedGroup() {
    // Pattern: (a*)+ on "aa"
    // First iteration: matches "aa"
    // Second iteration: matches "" (empty)
    // Expected: group 1 = "" (last iteration)
    ReggieMatcher m = Reggie.compile("(a*)+");
    MatchResult result = m.match("aa");

    assertNotNull(result, "Pattern should match");
    assertEquals("aa", result.group(0), "Full match should be 'aa'");
    assertEquals("", result.group(1), "Group 1 should capture empty (from last iteration)");
  }

  @Test
  void testQuantifiedGroupWithNoMatch() {
    // Pattern: (a+)? on "b"
    // Quantifier '?' allows 0 or 1 matches
    // Group doesn't participate in match
    // Expected: group 1 = null (didn't match)
    ReggieMatcher m = Reggie.compile("(a+)?b");
    MatchResult result = m.match("b");

    assertNotNull(result, "Pattern should match");
    assertEquals("b", result.group(0), "Full match should be 'b'");
    assertNull(result.group(1), "Group 1 should be null (didn't participate)");
  }

  @Test
  void testMultipleQuantifiedGroups() {
    // Pattern: (a+)+(b+)+ on "aaabbb"
    // Group 1 iterations: "aaa" (only one iteration for +)
    // Group 2 iterations: "bbb" (only one iteration for +)
    // But both are quantified with +, so last iteration rules apply
    ReggieMatcher m = Reggie.compile("(a+)+(b+)+");
    MatchResult result = m.match("aaabbb");

    assertNotNull(result, "Pattern should match");
    assertEquals("aaabbb", result.group(0), "Full match");
    // With + quantifier on groups, there's only 1 iteration, so it captures that
    assertEquals("aaa", result.group(1), "Group 1 last (and only) iteration");
    assertEquals("bbb", result.group(2), "Group 2 last (and only) iteration");
  }
}
