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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for DFA_TABLE find() greedy (leftmost-longest) correctness. Patterns with an
 * early-accepting state followed by a greedy suffix must return the longest match end, not the
 * shortest.
 */
public class DFATableGreedyFindTest {

  private static PatternAnalyzer.MatchingStrategy routeOf(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = null;
    if (!PatternAnalyzer.requiresRecursiveDescent(ast)) {
      ThompsonBuilder builder = new ThompsonBuilder();
      nfa = builder.build(ast, countGroups(pattern));
    }
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    return analyzer.analyzeAndRecommend().strategy;
  }

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
            if (next == ':' || next == '=' || next == '!' || next == '>' || next == '#') {
              continue;
            }
          }
        } else {
          count++;
        }
      }
    }
    return count;
  }

  private static void assertGreedyFind(String pattern, String input) throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_TABLE,
        routeOf(pattern),
        "pattern must route to DFA_TABLE: " + pattern);

    Matcher jdk = Pattern.compile(pattern).matcher(input);
    ReggieMatcher reggie = Reggie.compile(pattern);

    boolean jdkFound = jdk.find();
    MatchResult reggieResult = reggie.findMatch(input);

    assertEquals(jdkFound, reggieResult != null, "find() presence mismatch for: " + pattern);
    if (jdkFound) {
      assertEquals(jdk.start(), reggieResult.start(), "match start mismatch for: " + pattern);
      assertEquals(jdk.end(), reggieResult.end(), "match end mismatch for: " + pattern);
    }
  }

  @Test
  public void greedySuffixSingleChar() throws Exception {
    // Base pattern confirmed DFA_TABLE; greedy suffix '9*' must extend the match end
    String input = "a0".repeat(150) + "999";
    assertGreedyFind("(?:[a-z][0-9]){150}9*", input);
  }

  @Test
  public void greedySuffixTwoCharGroup() throws Exception {
    // Greedy two-char non-capturing suffix group
    String input = "a0".repeat(150) + "909090";
    assertGreedyFind("(?:[a-z][0-9]){150}(?:90)*", input);
  }

  @Test
  public void greedySuffixEmbedded() throws Exception {
    // Match embedded in longer input — start and end must both be correct
    String input = "zzz" + "a0".repeat(150) + "999";
    assertGreedyFind("(?:[a-z][0-9]){150}9*", input);
  }

  @Test
  public void greedySuffixNoExtension() throws Exception {
    // Greedy suffix matches zero extra chars — must behave identically to a fixed pattern
    String input = "a0".repeat(150) + "zzz";
    assertGreedyFind("(?:[a-z][0-9]){150}9*", input);
  }

  @Test
  public void greedySuffixAtEndOfInput() throws Exception {
    // Greedy suffix consumes all remaining input
    String input = "a0".repeat(150) + "9".repeat(20);
    assertGreedyFind("(?:[a-z][0-9]){150}9*", input);
  }

  @Test
  public void findMatchBoundsGreedy() throws Exception {
    // Explicit bound check: end must be 303 (150*2 base + 3 extra '9's), not 300
    String pattern = "(?:[a-z][0-9]){150}9*";
    String input = "a0".repeat(150) + "999";

    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_TABLE, routeOf(pattern), "must route to DFA_TABLE");

    ReggieMatcher reggie = Reggie.compile(pattern);
    MatchResult result = reggie.findMatch(input);
    assertNotNull(result, "expected a match");
    assertEquals(0, result.start());
    assertEquals(303, result.end());
  }
}
