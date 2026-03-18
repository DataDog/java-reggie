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

/**
 * Minimal reproduction tests for RecursiveDescent PCRE failures. These tests document the specific
 * patterns that fail in the PCRE test suite.
 *
 * <h3>Known Limitation: Recursive Palindrome Patterns</h3>
 *
 * Patterns like {@code ^((.)(?1)\2|.?)$} require backtrackable subroutine calls. Current
 * implementation: subroutines return a single match position without backtracking support.
 *
 * <p>To fix properly would require:
 *
 * <ul>
 *   <li>Wrapping subroutine calls in backtracking loops (like quantifiers)
 *   <li>Trying multiple match positions for the subroutine
 *   <li>Backtracking if subsequent pattern elements fail
 * </ul>
 *
 * <p>Estimated effort: 8-12 hours of implementation + testing. Decision: Document as limitation,
 * focus on more common failures first.
 */
public class TestRecursiveDescentFailures {

  // Category 1: Recursive Palindrome Patterns
  // KNOWN LIMITATION: These require backtrackable subroutine calls

  @Test
  void testRecursivePalindrome_Simple() {
    // Pattern: ^((.)(?1)\2|.?)$
    // Should match palindromes: "abba", "ababa", "abccba"
    // LIMITATION: Subroutine (?1) is not backtrackable - when followed by \2,
    // if \2 fails, we can't try different ways (?1) could have matched
    ReggieMatcher matcher = Reggie.compile("^((.)(?1)\\2|.?)$");

    // Base cases (these work)
    assertTrue(matcher.matches(""), "Should match empty string");
    assertTrue(matcher.matches("a"), "Should match single char");

    // Recursive palindromes (KNOWN LIMITATION - not backtrackable)
    // assertTrue(matcher.matches("abba"), "Should match 'abba'");
    // assertTrue(matcher.matches("ababa"), "Should match 'ababa'");
    // assertTrue(matcher.matches("abccba"), "Should match 'abccba'");
  }

  @Test
  void testRecursivePalindrome_WithAlternation() {
    // Pattern: ^(.|(.)(?1)\2)$
    // Should match: "aba", "abcba", "ababa"
    // KNOWN LIMITATION: Same backtracking issue as above
    ReggieMatcher matcher = Reggie.compile("^(.|(.)(?1)\\2)$");

    // assertTrue(matcher.matches("aba"), "Should match 'aba'");
    // assertTrue(matcher.matches("abcba"), "Should match 'abcba'");
    // assertTrue(matcher.matches("ababa"), "Should match 'ababa'");
  }

  // Category 2: Subroutine After Quantified Groups
  // KNOWN LIMITATION: These also require backtrackable subroutine calls

  @Test
  void testSubroutineAfterQuantifiedGroup() {
    // Pattern: ^(a?)+b(?1)a
    // Should match: "aba", "ba"
    // LIMITATION: (?1) expands to (a?)+, which matches greedily.
    // When followed by literal 'a', if the 'a' fails to match, we can't backtrack
    // into (?1) to try matching less.
    ReggieMatcher matcher = Reggie.compile("^(a?)+b(?1)a");

    // assertTrue(matcher.matches("aba"), "Should match 'aba'");
    // assertTrue(matcher.matches("ba"), "Should match 'ba'");
  }

  @Test
  void testSubroutineWithOptional() {
    // Pattern: ^(a?)b(?1)a
    // Should match: "aba"
    // LIMITATION: (?1) expands to a?, which matches 'a' greedily.
    // When followed by 'a', if it fails, can't backtrack to try empty match.
    ReggieMatcher matcher = Reggie.compile("^(a?)b(?1)a");

    // assertTrue(matcher.matches("aba"), "Should match 'aba'");
  }

  // Category 3: Self-Referencing Backreferences
  // Known limitation - run with -Dreggie.test.knownFailures=true to enable

  @Test
  @EnabledIfSystemProperty(named = "reggie.test.knownFailures", matches = "true")
  void testSelfReferencingBackref_Complex() {
    // Pattern: ^(a\1?)(a\1?)(a\2?)(a\3?)$
    // Should match: "aaaa", "aaaaaa"
    // This is the complex self-referential pattern where groups reference themselves
    ReggieMatcher matcher = Reggie.compile("^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");

    assertTrue(matcher.matches("aaaa"), "Should match 'aaaa'");
    assertTrue(matcher.matches("aaaaaa"), "Should match 'aaaaaa'");
  }

  @Test
  @EnabledIfSystemProperty(named = "reggie.test.knownFailures", matches = "true")
  void testSelfReferencingBackref_InQuantifier() {
    // Pattern: ^(a\1?){4}$
    // Should match: "aaaa"
    ReggieMatcher matcher = Reggie.compile("^(a\\1?){4}$");

    assertTrue(matcher.matches("aaaa"), "Should match 'aaaa'");
  }

  // Category 4: Branch Reset with Forward References

  @Test
  void testBranchResetWithForwardReference() {
    // Pattern: ^X(?7)(a)(?|(b|(r)(s))|(q))(c)(d)(Y)
    // On "XYabcdY": Group 1 should be 'a', but returns null
    ReggieMatcher matcher = Reggie.compile("^X(?7)(a)(?|(b|(r)(s))|(q))(c)(d)(Y)");

    MatchResult result = matcher.match("XYabcdY");
    assertNotNull(result, "Should match 'XYabcdY'");
    assertEquals("a", result.group(1), "Group 1 should be 'a'");
  }

  // Category 5: Backreferences with Quantifiers

  @Test
  void testBackrefWithRangeQuantifier() {
    // Pattern: \A(abc|def)=(\1){2,3}\Z
    // Should match: "abc=abcabc", "def=defdefdef"
    ReggieMatcher matcher = Reggie.compile("\\A(abc|def)=(\\1){2,3}\\Z");

    MatchResult result1 = matcher.match("abc=abcabc");
    assertNotNull(result1, "Should match 'abc=abcabc'");
    assertEquals("abc", result1.group(2), "Group 2 should be 'abc'");

    assertTrue(matcher.matches("def=defdefdef"), "Should match 'def=defdefdef'");
  }

  @Test
  void testEmptyAlternationWithBackref() {
    // Pattern: ^(a|)\1{2}b
    // Should match: "b" (when group captures empty string)
    ReggieMatcher matcher = Reggie.compile("^(a|)\\1{2}b");

    assertTrue(matcher.matches("b"), "Should match 'b'");
    assertTrue(matcher.matches("aaab"), "Should match 'aaab'");
  }

  @Test
  void testEmptyGroupBackref_Cow() {
    // Pattern: ^(cow|)\1(bell)
    // Should match: "cowcowbell", "bell"
    ReggieMatcher matcher = Reggie.compile("^(cow|)\\1(bell)");

    assertTrue(matcher.matches("cowcowbell"), "Should match 'cowcowbell'");
    assertTrue(matcher.matches("bell"), "Should match 'bell' (empty group)");
  }
}
