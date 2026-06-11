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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Engine-level regression tests: a start-anchor (^, \A) guarding only one alternation branch must
 * be evaluated against the true search-region start, not against each per-attempt trial start, when
 * running find()/findMatch(). Each case compares the PikeVM result against java.util.regex.
 */
class PikeVMAnchorFindTest {

  /** Build a PikeVMMatcher for the given pattern (bypasses strategy routing). */
  private static PikeVMMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new PikeVMMatcher(nfa, pattern);
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean inClass = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '\\') {
        i++;
        continue;
      }
      if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '(' && !inClass) {
        boolean capturing = !(i + 1 < pattern.length() && pattern.charAt(i + 1) == '?');
        if (capturing) {
          count++;
        }
      }
    }
    return count;
  }

  /** Assert PikeVM find() agrees with JDK on match presence and matched text. */
  private static void assertFindMatchesJdk(String pattern, String input) throws Exception {
    PikeVMMatcher m = build(pattern);
    MatchResult r = m.findMatch(input);
    Matcher oracle = Pattern.compile(pattern).matcher(input);
    if (oracle.find()) {
      assertEquals(
          oracle.start(),
          r == null ? -1 : r.start(),
          "match start for /" + pattern + "/ on \"" + input + "\"");
      assertEquals(
          oracle.group(),
          r == null ? null : input.substring(r.start(), r.end()),
          "matched text for /" + pattern + "/ on \"" + input + "\"");
    } else {
      assertNull(r, "expected no match for /" + pattern + "/ on \"" + input + "\"");
    }
  }

  @Test
  void stringStartAnchoredBranchDoesNotMatchAtNonZeroStart() throws Exception {
    // \A only matches at index 0; on "xa" there is no second-branch match, so JDK finds nothing.
    assertFindMatchesJdk("\\Aa|b", "xa");
  }

  @Test
  void caretAnchoredBranchDoesNotMatchAtNonZeroStart() throws Exception {
    // ^ (non-multiline) only matches at index 0; on "xa" JDK finds nothing.
    assertFindMatchesJdk("^a|b", "xa");
  }

  @Test
  void anchoredFirstBranchPreferredAtStart() throws Exception {
    // At index 0 the anchored first branch is leftmost-first; matched text must be "a".
    assertFindMatchesJdk("\\Aa|b", "ab");
    assertFindMatchesJdk("^a|b", "ab");
  }

  @Test
  void secondBranchMatchesWhenAnchoredBranchFails() throws Exception {
    // "ba": \Aa fails at 0 (char 'b'), so JDK finds "b" at [0,1]; PikeVM must agree.
    assertFindMatchesJdk("\\Aa|b", "ba");
    assertFindMatchesJdk("^a|b", "ba");
  }

  @Test
  void anchoredBranchWithQuantifier() throws Exception {
    // Regression for the Task 2 fuzz class: anchor + quantified branch in alternation.
    assertFindMatchesJdk("\\Aa{2,4}|b", "xaa");
    assertFindMatchesJdk("\\Aa{2,4}|b", "aaab");
  }

  @Test
  void matchesRespectsAnchorAtRegionStart() throws Exception {
    // matches() is whole-region: \Aa|b on "a" must match (anchor satisfied at region start 0).
    PikeVMMatcher m = build("\\Aa|b");
    assertEquals(true, m.matches("a"), "\\Aa|b should match \"a\" under matches()");
    assertEquals(true, m.matches("b"), "\\Aa|b should match \"b\" under matches()");
  }

  @Test
  void boundedMatchRespectsAnchorAtRegionStart() throws Exception {
    // matchesBounded over region [2,3] of "xxa": the substring "a" starts the region, \Aa matches.
    PikeVMMatcher m = build("\\Aa|b");
    assertEquals(true, m.matchesBounded("xxa", 2, 3), "region \"a\" should match \\Aa|b");
  }
}
