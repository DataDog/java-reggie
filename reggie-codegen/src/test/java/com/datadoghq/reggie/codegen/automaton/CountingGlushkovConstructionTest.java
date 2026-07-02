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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:ab){20}");

    assertEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
    PatternAnalyzer.CountingGlushkovInfo info =
        assertInstanceOf(PatternAnalyzer.CountingGlushkovInfo.class, result.patternInfo);
    assertEquals(2, info.base.positionCount);
    assertEquals(20, info.counterMin);
    assertEquals(20, info.counterMax);
  }

  @Test
  void twoCharBodyRangeRepetition() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:ab){5,20}");

    assertEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
    PatternAnalyzer.CountingGlushkovInfo info =
        assertInstanceOf(PatternAnalyzer.CountingGlushkovInfo.class, result.patternInfo);
    assertEquals(5, info.counterMin);
    assertEquals(20, info.counterMax);
  }

  @Test
  void singleCharClassBodyIsTriviallyEligible() throws Exception {
    // \d{11}: single-position body, positionCount==1, synchronizing check skipped
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d{11}");

    assertEquals(PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV, result.strategy);
    PatternAnalyzer.CountingGlushkovInfo info =
        assertInstanceOf(PatternAnalyzer.CountingGlushkovInfo.class, result.patternInfo);
    assertEquals(1, info.base.positionCount);
    assertEquals(11, info.counterMin);
    assertEquals(11, info.counterMax);
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
