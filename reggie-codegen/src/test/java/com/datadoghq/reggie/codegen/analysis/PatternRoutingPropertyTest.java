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

import static com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy.*;
import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression tests for PatternAnalyzer strategy selection.
 *
 * <p>These tests complement the property-based tests in PatternRoutingPropertyBasedTest with
 * specific regression cases and representative examples.
 *
 * <p>Focus: Critical bugs we fixed + one example per major strategy category. Property-based tests
 * provide comprehensive coverage with 800+ generated patterns.
 */
public class PatternRoutingPropertyTest {

  /** Test data model for pattern routing test cases. */
  record PatternRoutingTestCase(
      String pattern,
      PatternAnalyzer.MatchingStrategy expectedStrategy,
      String description,
      boolean checkPatternInfo) {
    PatternRoutingTestCase(
        String pattern, PatternAnalyzer.MatchingStrategy expectedStrategy, String description) {
      this(pattern, expectedStrategy, description, false);
    }
  }

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();

    try {
      NFA nfa = builder.build(ast, 0);
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
      return analyzer.analyzeAndRecommend();
    } catch (UnsupportedOperationException e) {
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, null);
      return analyzer.analyzeAndRecommend();
    }
  }

  private String formatError(
      PatternRoutingTestCase testCase, PatternAnalyzer.MatchingStrategyResult result) {
    return String.format(
        "Pattern routing mismatch:%n"
            + "  Pattern: %s%n"
            + "  Description: %s%n"
            + "  Expected: %s%n"
            + "  Actual: %s%n"
            + "  DFA states: %s",
        testCase.pattern,
        testCase.description,
        testCase.expectedStrategy,
        result.strategy,
        result.dfa != null ? result.dfa.getStateCount() : "null");
  }

  // ============================================
  // CRITICAL REGRESSIONS (Bugs We Fixed)
  // ============================================

  @Nested
  @DisplayName("Critical Regressions")
  class CriticalRegressions {

    @ParameterizedTest
    @MethodSource(
        "com.datadoghq.reggie.codegen.analysis.PatternRoutingPropertyTest#provideCriticalRegressions")
    @DisplayName("Patterns that had routing bugs")
    void criticalRegressions(PatternRoutingTestCase testCase) throws Exception {
      PatternAnalyzer.MatchingStrategyResult result = analyze(testCase.pattern);
      assertEquals(testCase.expectedStrategy, result.strategy, () -> formatError(testCase, result));

      if (testCase.checkPatternInfo) {
        assertInstanceOf(
            OptionalGroupBackrefInfo.class,
            result.patternInfo,
            "OPTIONAL_GROUP_BACKREF should return OptionalGroupBackrefInfo");
      }
    }
  }

  static Stream<PatternRoutingTestCase> provideCriticalRegressions() {
    return Stream.of(
        // BUG FIX: This pattern routed to RECURSIVE_DESCENT before fix
        // Fixed by adding OPTIONAL_GROUP_BACKREF detection before hasQuantifiedBackrefs check
        new PatternRoutingTestCase(
            "^(a|)\\1+b",
            OPTIONAL_GROUP_BACKREF,
            "REGRESSION: optional group with quantified backref (was RECURSIVE_DESCENT)",
            true),

        // BUG FIX: This pattern routed to RECURSIVE_DESCENT before fix
        // Fixed by adding FIXED_REPETITION_BACKREF detection before hasQuantifiedBackrefs check
        new PatternRoutingTestCase(
            "(abc)\\1{3}",
            FIXED_REPETITION_BACKREF,
            "REGRESSION: multi-char group with quantified backref (was RECURSIVE_DESCENT)"));
  }

  // ============================================
  // REPRESENTATIVE EXAMPLES (One per category)
  // ============================================

  @Nested
  @DisplayName("Backref Strategies")
  class BackrefStrategies {

    @ParameterizedTest
    @MethodSource(
        "com.datadoghq.reggie.codegen.analysis.PatternRoutingPropertyTest#provideBackrefExamples")
    @DisplayName("Example patterns for each backref strategy")
    void backrefExamples(PatternRoutingTestCase testCase) throws Exception {
      PatternAnalyzer.MatchingStrategyResult result = analyze(testCase.pattern);
      assertEquals(testCase.expectedStrategy, result.strategy, () -> formatError(testCase, result));
    }
  }

  static Stream<PatternRoutingTestCase> provideBackrefExamples() {
    return Stream.of(
        // Optional group backrefs
        new PatternRoutingTestCase(
            "^(cow|)\\1b", OPTIONAL_GROUP_BACKREF, "multi-char optional group with backref"),

        // Variable capture backrefs
        new PatternRoutingTestCase(
            "(.*)\\d+\\1", VARIABLE_CAPTURE_BACKREF, "greedy group with backref"),

        // Specialized backrefs
        new PatternRoutingTestCase(
            "<(\\w+)>.*</\\1>", SPECIALIZED_BACKREFERENCE, "HTML tag pattern with backref"),

        // Linear backrefs
        new PatternRoutingTestCase("(a)\\1", LINEAR_BACKREFERENCE, "simple linear backref"));
  }

  @Nested
  @DisplayName("Specialized Strategies")
  class SpecializedStrategies {

    @ParameterizedTest
    @MethodSource(
        "com.datadoghq.reggie.codegen.analysis.PatternRoutingPropertyTest#provideSpecializedExamples")
    @DisplayName("Example patterns for specialized strategies")
    void specializedExamples(PatternRoutingTestCase testCase) throws Exception {
      PatternAnalyzer.MatchingStrategyResult result = analyze(testCase.pattern);
      assertEquals(testCase.expectedStrategy, result.strategy, () -> formatError(testCase, result));
    }
  }

  static Stream<PatternRoutingTestCase> provideSpecializedExamples() {
    return Stream.of(
        // Greedy charclass (requires capturing group)
        new PatternRoutingTestCase(
            "(\\d+)", SPECIALIZED_GREEDY_CHARCLASS, "capturing group with digit+"),

        // Literal alternation (requires ≥5 alternatives)
        new PatternRoutingTestCase(
            "foo|bar|baz|qux|quux", SPECIALIZED_LITERAL_ALTERNATION, "five literal keywords"),

        // Stateless loop
        new PatternRoutingTestCase(
            "(?=\\w+@).*@example\\.com",
            STATELESS_LOOP,
            "lookahead with greedy dot and literal suffix"),

        // Greedy backtrack
        new PatternRoutingTestCase("(.*)bar", GREEDY_BACKTRACK, "greedy .* with literal suffix"));
  }

  @Nested
  @DisplayName("Generic DFA Strategies")
  class GenericDFAStrategies {

    @ParameterizedTest
    @MethodSource(
        "com.datadoghq.reggie.codegen.analysis.PatternRoutingPropertyTest#provideDFAExamples")
    @DisplayName("Example patterns for DFA strategies")
    void dfaExamples(PatternRoutingTestCase testCase) throws Exception {
      PatternAnalyzer.MatchingStrategyResult result = analyze(testCase.pattern);
      assertEquals(testCase.expectedStrategy, result.strategy, () -> formatError(testCase, result));
    }
  }

  static Stream<PatternRoutingTestCase> provideDFAExamples() {
    return Stream.of(
        // DFA_UNROLLED (<20 states)
        new PatternRoutingTestCase(
            "(abc)", DFA_UNROLLED, "capturing group with literal (groups not tracked in DFA)"),

        // DFA_SWITCH (20-300 states)
        new PatternRoutingTestCase("(a|b|c){50}", DFA_SWITCH, "medium alternation (151 states)"),

        // DFA_TABLE (>300 states)
        new PatternRoutingTestCase(
            "(a|b|c|d|e|f){100}", DFA_TABLE, "very high repetition alternation (601 states)"));
  }

  @Nested
  @DisplayName("Context-Free Features")
  class ContextFreeFeatures {

    @ParameterizedTest
    @MethodSource(
        "com.datadoghq.reggie.codegen.analysis.PatternRoutingPropertyTest#provideContextFreeExamples")
    @DisplayName("Example patterns requiring recursive descent")
    void contextFreeExamples(PatternRoutingTestCase testCase) throws Exception {
      PatternAnalyzer.MatchingStrategyResult result = analyze(testCase.pattern);
      assertEquals(testCase.expectedStrategy, result.strategy, () -> formatError(testCase, result));
    }
  }

  static Stream<PatternRoutingTestCase> provideContextFreeExamples() {
    return Stream.of(
        new PatternRoutingTestCase("(?R)", RECURSIVE_DESCENT, "recursive subroutine"),
        new PatternRoutingTestCase("(?(1)yes|no)", RECURSIVE_DESCENT, "conditional pattern"));
  }
}
