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
package com.datadoghq.reggie.codegen.automaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Tests that {@code detectCountingGlushkov()} routes patterns correctly via analyzeAndRecommend().
 */
public class CountingGlushkovConstructionTest {

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, 0);
    return new PatternAnalyzer(ast, nfa).analyzeAndRecommend();
  }

  @Test
  void twoCharBodyExactRepetition() throws Exception {
    // (?:ab){20}: 2 positions * 20 = 41 DFA states < 300, so COUNTING_GLUSHKOV is skipped.
    // Falls through to SPECIALIZED_FIXED_SEQUENCE (literal repeat).
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:ab){20}");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE, result.strategy);
  }

  @Test
  void twoCharBodyRangeRepetition() throws Exception {
    // (?:ab){5,20}: 2 positions * 20 = 41 DFA states < 300 → DFA_SWITCH.
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:ab){5,20}");
    assertEquals(PatternAnalyzer.MatchingStrategy.DFA_SWITCH, result.strategy);
  }

  @Test
  void singleCharClassBodyIsTriviallyEligible() throws Exception {
    // \d{11}: 1 position * 11 = 12 DFA states < 300, so COUNTING_GLUSHKOV is skipped.
    // Falls through to STATELESS_LOOP (single char class).
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d{11}");
    assertEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);
  }

  @Test
  void maxTenOrBelowIsNotCountingGlushkov() throws Exception {
    // \d{10}: q.max == 10, threshold is max > 10, so this must NOT be COUNTING_GLUSHKOV
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d{10}");

    assertNotEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
  }

  @Test
  void capturingGroupInBodyIsNotEligible() throws Exception {
    // (a){20}: capturing group in body — hasCapturingGroups(body) fires
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a){20}");

    assertNotEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
  }

  @Test
  void nestedUnboundedQuantifierInBodyIsNotEligible() throws Exception {
    // (?:a*){20}: a* has max==Integer.MAX_VALUE > 10, hasLargeBoundQuantifier(body) fires
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:a*){20}");

    assertNotEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
  }

  @Test
  void nestedLargeBoundQuantifierInBodyIsNotEligible() throws Exception {
    // (?:(?:a){50}){20}: inner {50} has max==50 > 10, hasLargeBoundQuantifier(body) fires
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:(?:a){50}){20}");

    assertNotEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
  }

  @Test
  void anchorInBodyIsNotEligible() throws Exception {
    // (?:^a){20}: hasAnchors(body) fires
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:^a){20}");

    assertNotEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
  }
}
