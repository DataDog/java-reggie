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

class PatternAnalyzerLazyDFATest {

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 0);
    return new PatternAnalyzer(ast, nfa).analyzeAndRecommend();
  }

  @Test
  void testRouteToLazyDFAWhenNFALarge() throws Exception {
    // (?:[a-z][0-9]){200} has ~800 NFA states, no groups/anchors → LAZY_DFA
    PatternAnalyzer.MatchingStrategyResult r = analyze("(?:[a-z][0-9]){200}");
    assertEquals(PatternAnalyzer.MatchingStrategy.LAZY_DFA, r.strategy);
  }

  @Test
  void testDoNotRouteWhenNFASmall() throws Exception {
    // (a?){50} has ~100 NFA states — below threshold → stays OPTIMIZED_NFA
    PatternAnalyzer.MatchingStrategyResult r = analyze("(a?){50}");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.LAZY_DFA, r.strategy);
  }

  @Test
  void testDoNotRouteWithLookahead() throws Exception {
    // Lookahead → OPTIMIZED_NFA_WITH_LOOKAROUND, not LAZY_DFA
    PatternAnalyzer.MatchingStrategyResult r = analyze("(?=[a-z])(?:[a-z][0-9]){200}");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.LAZY_DFA, r.strategy);
  }

  @Test
  void testDoNotRouteWithAnchor() throws Exception {
    // Anchored pattern must not route to LAZY_DFA
    PatternAnalyzer.MatchingStrategyResult r = analyze("^(?:[a-z][0-9]){200}");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.LAZY_DFA, r.strategy);
  }

  @Test
  void testDoNotRouteWithBackref() throws Exception {
    // Pattern with backreference → OPTIMIZED_NFA_WITH_BACKREFS, not LAZY_DFA
    PatternAnalyzer.MatchingStrategyResult r = analyze("((?:[a-z][0-9]){100})\\1");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.LAZY_DFA, r.strategy);
  }
}
