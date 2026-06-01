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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for {@link PikeVMMatcher}. Each test compares group spans against {@code
 * java.util.regex} as the oracle.
 */
class PikeVMMatcherTest {

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  /** Build a PikeVMMatcher for the given pattern. */
  private static PikeVMMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new PikeVMMatcher(nfa, pattern);
  }

  /** JDK oracle: whole-input match, returns null if no match. */
  private static java.util.regex.Matcher jdkMatch(String pattern, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
    return m.matches() ? m : null;
  }

  /** JDK oracle: find anywhere in input, returns null if no match. */
  private static java.util.regex.Matcher jdkFind(String pattern, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
    return m.find() ? m : null;
  }

  // -------------------------------------------------------------------------
  // Repro tests — spans must match JDK exactly
  // -------------------------------------------------------------------------

  /** {@code (.)?b} on {@code "b"}: group 1 is optional and unmatched → start/end must be -1. */
  @Test
  void optionalGroupNotMatched() throws Exception {
    String pat = "(.)?b";
    String input = "b";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.match(input);
    assertNotNull(r, "should match");

    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
    assertEquals(-1, r.start(1), "unmatched group start must be -1");
    assertEquals(-1, r.end(1), "unmatched group end must be -1");
  }

  /**
   * {@code (0)?\Z} on {@code ""}: group 1 optional, input is empty, \Z matches end. Group 1 must be
   * [-1,-1].
   */
  @Test
  void optionalGroupWithStringEndAnchor() throws Exception {
    String pat = "(0)?\\Z";
    String input = "";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.match(input);
    assertNotNull(r, "should match");

    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
    assertEquals(-1, r.start(1));
    assertEquals(-1, r.end(1));
  }

  /**
   * {@code (fo|foo)} on {@code "foo"}: first alternative "fo" wins (Perl leftmost-greedy). JDK
   * agrees: group 1 = [0,2].
   */
  @Test
  void firstAltWins_foOrFoo() throws Exception {
    String pat = "(fo|foo)";
    String input = "foo";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
  }

  /** {@code (a|ab)} on {@code "ab"}: first alternative "a" wins. JDK: group 1 = [0,1]. */
  @Test
  void firstAltWins_aOrAb() throws Exception {
    String pat = "(a|ab)";
    String input = "ab";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
  }

  /**
   * {@code (a|ab)(c|)} on {@code "abc"}: Perl semantics; JDK gives group 1 = [0,1], group 2 =
   * [1,2].
   */
  @Test
  void twoGroupAlternation() throws Exception {
    String pat = "(a|ab)(c|)";
    String input = "abc";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
    assertEquals(oracle.start(2), r.start(2), "group 2 start");
    assertEquals(oracle.end(2), r.end(2), "group 2 end");
  }

  /** {@code a{1}()|.} on {@code "a"}: tests that group 1 span matches JDK. */
  @Test
  void boundedQuantifierGroupOrDot() throws Exception {
    String pat = "a{1}()|.";
    String input = "a";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
  }

  // -------------------------------------------------------------------------
  // Basic sanity
  // -------------------------------------------------------------------------

  @Test
  void simpleMatchesTrue() throws Exception {
    assertTrue(build("abc").matches("abc"));
  }

  @Test
  void simpleMatchesFalse() throws Exception {
    assertFalse(build("abc").matches("abx"));
  }

  @Test
  void simpleFindTrue() throws Exception {
    assertTrue(build("bc").find("abcd"));
  }

  @Test
  void simpleFindFrom() throws Exception {
    assertEquals(2, build("c").findFrom("abcd", 0));
  }

  @Test
  void wholeMatchGroup() throws Exception {
    MatchResult r = build("(a)(b)").match("ab");
    assertNotNull(r);
    assertEquals("ab", r.group(0));
    assertEquals("a", r.group(1));
    assertEquals("b", r.group(2));
  }

  // -------------------------------------------------------------------------
  // Utilities
  // -------------------------------------------------------------------------

  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '(' && i + 1 < pattern.length()) {
        if (pattern.charAt(i + 1) == '?') {
          if (i + 2 < pattern.length()) {
            char next = pattern.charAt(i + 2);
            if (next == ':'
                || next == '='
                || next == '!'
                || next == '>'
                || next == '#'
                || next == '|'
                || next == '('
                || next == '-'
                || next == 'i'
                || next == 'm'
                || next == 's'
                || next == 'x'
                || next == 'u'
                || next == 'U'
                || next == 'd') {
              if (next == '<' && i + 3 < pattern.length()) {
                char afterLt = pattern.charAt(i + 3);
                if (afterLt == '=' || afterLt == '!') {
                  continue;
                }
              } else {
                continue;
              }
            }
            if (next == '<' && i + 3 < pattern.length()) {
              char afterLt = pattern.charAt(i + 3);
              if (afterLt == '=' || afterLt == '!') {
                continue;
              }
            }
          }
        }
        count++;
      }
    }
    return count;
  }
}
