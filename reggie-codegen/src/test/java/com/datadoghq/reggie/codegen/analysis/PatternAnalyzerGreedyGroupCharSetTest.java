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
package com.datadoghq.reggie.codegen.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@code PatternAnalyzer} correctly identifies capturing groups whose ConcatNode child
 * ends with an optional greedy quantifier as requiring backtracking when the following element
 * overlaps the optional's charset.
 */
class PatternAnalyzerGreedyGroupCharSetTest {

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    int groupCount = countCapturingGroups(pattern);
    NFA nfa = builder.build(ast, groupCount);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    return analyzer.analyzeAndRecommend();
  }

  /** Count capturing groups by scanning for unescaped {@code (} not followed by {@code ?}. */
  private int countCapturingGroups(String pattern) {
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
      } else if (c == '(' && i + 1 < pattern.length() && pattern.charAt(i + 1) != '?') {
        count++;
      }
    }
    return count;
  }

  @Test
  void testConcatGroupWithTrailingOptionalOverlappingNext() throws Exception {
    // (\.\d\d[1-9]?)\d+ — group has ConcatNode child ending in optional [1-9]?,
    // and \d+ overlaps [1-9]. Must route to RECURSIVE_DESCENT.
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\.\\d\\d[1-9]?)\\d+");
    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Group with trailing optional greedy quantifier overlapping next element must use RECURSIVE_DESCENT");
  }

  @Test
  void testConcatGroupWithTrailingOptionalNonOverlapping() throws Exception {
    // (\.\d\d[a-z]?)\d+ — [a-z] and \d are disjoint; no backtracking needed.
    // Should NOT route to RECURSIVE_DESCENT.
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\.\\d\\d[a-z]?)\\d+");
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Group with trailing optional greedy quantifier NOT overlapping next element must not use RECURSIVE_DESCENT");
  }
}
