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
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for non-greedy (reluctant) quantifiers: *?, +?, ??, {n,m}? */
public class NonGreedyQuantifierTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testNonGreedyStarBasic() {
    // a*?b - match minimum 'a's before 'b'
    ReggieMatcher m = Reggie.compile("a*?b");

    assertTrue(m.matches("b"), "Should match 'b' (zero a's)");
    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("aab"), "Should match 'aab'");
    assertTrue(m.matches("aaab"), "Should match 'aaab'");
    assertFalse(m.matches("a"), "Should not match 'a' (no b)");
  }

  @Test
  void testNonGreedyPlusBasic() {
    // a+?b - match minimum 'a's (at least one) before 'b'
    ReggieMatcher m = Reggie.compile("a+?b");

    assertFalse(m.matches("b"), "Should not match 'b' (+ requires at least one a)");
    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("aab"), "Should match 'aab'");
    assertTrue(m.matches("aaab"), "Should match 'aaab'");
  }

  @Test
  void testNonGreedyQuestionBasic() {
    // a??b - prefer not matching 'a'
    ReggieMatcher m = Reggie.compile("a??b");

    assertTrue(m.matches("b"), "Should match 'b'");
    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertFalse(m.matches("aab"), "Should not match 'aab' (only 0 or 1 a)");
  }

  @Test
  void testNonGreedyBounded() {
    // a{2,4}?b - match minimum (2) 'a's before 'b'
    ReggieMatcher m = Reggie.compile("a{2,4}?b");

    assertFalse(m.matches("ab"), "Should not match 'ab' (min is 2)");
    assertTrue(m.matches("aab"), "Should match 'aab'");
    assertTrue(m.matches("aaab"), "Should match 'aaab'");
    assertTrue(m.matches("aaaab"), "Should match 'aaaab'");
    assertFalse(m.matches("aaaaab"), "Should not match 'aaaaab' (max is 4)");
  }

  @Test
  void testNonGreedyWithCapturingGroup() {
    // (a*?)b - non-greedy with capturing group
    ReggieMatcher m = Reggie.compile("(a*?)b");

    // For full matches, group captures what's before 'b'
    MatchResult r1 = m.match("b");
    assertNotNull(r1, "Should match 'b'");
    assertEquals("", r1.group(1), "Group 1 should be empty");

    MatchResult r2 = m.match("ab");
    assertNotNull(r2, "Should match 'ab'");
    assertEquals("a", r2.group(1), "Group 1 should be 'a'");

    MatchResult r3 = m.match("aaab");
    assertNotNull(r3, "Should match 'aaab'");
    assertEquals("aaa", r3.group(1), "Group 1 should be 'aaa'");
  }

  @Test
  void testNonGreedyFind() {
    // x(a+?)y - find with non-greedy
    ReggieMatcher m = Reggie.compile("x(a+?)y");

    // In "xaay", the non-greedy quantifier tries to match minimum 'a's first
    // Since we need the pattern to fully match, it will match "xaay" with group="aa"
    MatchResult r = m.findMatch("xaay");
    assertNotNull(r, "Should find match in 'xaay'");
    assertEquals("xaay", r.group());
    assertEquals("aa", r.group(1), "Group 1 should be 'aa'");
  }

  @Test
  void testCompareWithJdkPattern() {
    // Verify behavior matches JDK Pattern
    String[] patterns = {"a*?b", "a+?b", "a??b", "a{1,3}?b"};
    String[] inputs = {"b", "ab", "aab", "aaab", "aaaab"};

    for (String patternStr : patterns) {
      RuntimeCompiler.clearCache(); // Clear cache for each pattern
      Pattern jdkPattern = Pattern.compile(patternStr);
      ReggieMatcher reggie = Reggie.compile(patternStr);

      for (String input : inputs) {
        boolean jdkMatches = jdkPattern.matcher(input).matches();
        boolean reggieMatches = reggie.matches(input);
        assertEquals(
            jdkMatches, reggieMatches, "Pattern '" + patternStr + "' on input '" + input + "'");
      }
    }
  }

  @Test
  void testNonGreedyWithSuffix() {
    // a+?bc - non-greedy followed by literal suffix
    ReggieMatcher m = Reggie.compile("a+?bc");

    assertTrue(m.matches("abc"), "Should match 'abc'");
    assertTrue(m.matches("aabc"), "Should match 'aabc'");
    assertTrue(m.matches("aaabc"), "Should match 'aaabc'");
    assertFalse(m.matches("bc"), "Should not match 'bc' (+ requires at least one)");
  }

  @Test
  void testNonGreedyCharClass() {
    // [ab]+?c - non-greedy char class
    ReggieMatcher m = Reggie.compile("[ab]+?c");

    assertTrue(m.matches("ac"), "Should match 'ac'");
    assertTrue(m.matches("bc"), "Should match 'bc'");
    assertTrue(m.matches("abc"), "Should match 'abc'");
    assertTrue(m.matches("ababc"), "Should match 'ababc'");
    assertFalse(m.matches("c"), "Should not match 'c'");
  }

  @Test
  void testNonGreedyDot() {
    // .+?x - non-greedy dot
    ReggieMatcher m = Reggie.compile(".+?x");

    assertTrue(m.matches("ax"), "Should match 'ax'");
    assertTrue(m.matches("abx"), "Should match 'abx'");
    assertTrue(m.matches("abcx"), "Should match 'abcx'");
    assertFalse(m.matches("x"), "Should not match 'x' (+ requires at least one)");
  }

  @Test
  void testMixedGreedyNonGreedy() {
    // a+b+? - greedy followed by non-greedy
    ReggieMatcher m = Reggie.compile("a+b+?");

    assertTrue(m.matches("ab"), "Should match 'ab'");
    assertTrue(m.matches("aab"), "Should match 'aab'");
    assertTrue(m.matches("abb"), "Should match 'abb'");
    assertTrue(m.matches("aabb"), "Should match 'aabb'");
    assertFalse(m.matches("a"), "Should not match 'a'");
    assertFalse(m.matches("b"), "Should not match 'b'");
  }
}
