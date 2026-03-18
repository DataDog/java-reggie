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
package com.datadoghq.reggie.codegen.analysis.pbt;

import static com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy.*;
import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.List;
import net.jqwik.api.*;

/**
 * Property-based tests for PatternAnalyzer routing using jqwik.
 *
 * <p>These tests complement the data-driven tests in PatternRoutingPropertyTest by generating
 * hundreds of random patterns per category and asserting universal invariants that must hold.
 */
public class PatternRoutingPropertyBasedTest {

  // ============================================
  // UNIVERSAL PROPERTIES (apply to ALL patterns)
  // ============================================

  @Property(tries = 100)
  void allPatternsHaveNonNullStrategy(@ForAll("anyValidPattern") String pattern) {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);
    assertNotNull(result.strategy, () -> "Every valid pattern must have a strategy: " + pattern);
  }

  @Property(tries = 100)
  void strategySelectionIsStable(@ForAll("anyValidPattern") String pattern) {
    // Same pattern analyzed twice should give same strategy (deterministic)
    PatternAnalyzer.MatchingStrategyResult result1 = analyze(pattern);
    PatternAnalyzer.MatchingStrategyResult result2 = analyze(pattern);

    assertEquals(
        result1.strategy,
        result2.strategy,
        () -> "Strategy selection must be deterministic for: " + pattern);
  }

  @Property(tries = 100)
  void strategySelectionSucceeds(@ForAll("anyValidPattern") String pattern) {
    // Should not throw exceptions during analysis
    assertDoesNotThrow(
        () -> analyze(pattern), () -> "Pattern analysis should not throw for: " + pattern);
  }

  // ============================================
  // CATEGORY-SPECIFIC PROPERTIES
  // ============================================

  @Property(tries = 100)
  void backrefPatternsUseBackrefStrategies(@ForAll("withBackrefs") String pattern) {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // Must use one of the backref-aware strategies
    List<PatternAnalyzer.MatchingStrategy> backrefStrategies =
        List.of(
            LINEAR_BACKREFERENCE,
            SPECIALIZED_BACKREFERENCE,
            FIXED_REPETITION_BACKREF,
            VARIABLE_CAPTURE_BACKREF,
            OPTIONAL_GROUP_BACKREF,
            OPTIMIZED_NFA_WITH_BACKREFS,
            RECURSIVE_DESCENT // fallback for complex cases
            );

    assertTrue(
        backrefStrategies.contains(result.strategy),
        () ->
            "Pattern with backrefs: '"
                + pattern
                + "' must use backref strategy, got: "
                + result.strategy);
  }

  @Property(tries = 100)
  void contextFreePatternsUseRecursiveDescent(@ForAll("contextFree") String pattern) {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    assertEquals(
        RECURSIVE_DESCENT,
        result.strategy,
        () ->
            "Context-free pattern: '"
                + pattern
                + "' must use RECURSIVE_DESCENT, got: "
                + result.strategy);
  }

  @Property(tries = 100)
  void optionalGroupBackrefsDetectedCorrectly(@ForAll("withOptionalGroupBackrefs") String pattern) {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // This is the critical property that was broken!
    // Optional group + quantified backref should route to OPTIONAL_GROUP_BACKREF
    // NOT to RECURSIVE_DESCENT
    assertEquals(
        OPTIONAL_GROUP_BACKREF,
        result.strategy,
        () ->
            "Optional group backref pattern: '"
                + pattern
                + "' should use OPTIONAL_GROUP_BACKREF, got: "
                + result.strategy);
  }

  @Property(tries = 50) // Fewer tries since these are expensive patterns
  void largeStateSpacePatternsUseDFATableOrSpecialized(@ForAll("largeStateSpace") String pattern) {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // Patterns with many states should use DFA (SWITCH or TABLE) or specialized strategy
    // Note: (a|b|c){50} = 151 states → DFA_SWITCH
    //       (a|b|c|d|e|f){100} = 601 states → DFA_TABLE or SPECIALIZED_QUANTIFIED_GROUP
    List<PatternAnalyzer.MatchingStrategy> validStrategies =
        List.of(
            DFA_SWITCH, // 50-300 states
            DFA_TABLE, // >300 states
            SPECIALIZED_QUANTIFIED_GROUP, // Might have specialized strategy
            OPTIMIZED_NFA // Rare fallback
            );

    assertTrue(
        validStrategies.contains(result.strategy),
        () ->
            "Large state space pattern: '"
                + pattern
                + "' should use DFA_SWITCH/DFA_TABLE/SPECIALIZED_QUANTIFIED_GROUP/OPTIMIZED_NFA, got: "
                + result.strategy);
  }

  // ============================================
  // ROUTING ORDER INVARIANTS
  // ============================================

  @Property(tries = 100)
  void specializedStrategiesPreferredOverGeneric(@ForAll("anyValidPattern") String pattern) {
    PatternAnalyzer.MatchingStrategyResult result = analyze(pattern);

    // If a pattern has characteristics that match a specialized strategy,
    // it should NOT use a generic strategy

    if (hasBackreferences(pattern)) {
      assertFalse(
          isGenericDFAStrategy(result.strategy),
          () ->
              "Pattern with backrefs '"
                  + pattern
                  + "' should not use generic DFA strategy: "
                  + result.strategy);
    }

    if (hasContextFreeFeatures(pattern)) {
      assertFalse(
          isGenericDFAStrategy(result.strategy),
          () ->
              "Pattern with context-free features '"
                  + pattern
                  + "' should not use generic DFA strategy: "
                  + result.strategy);
    }
  }

  // ============================================
  // PATTERN PROVIDERS (for @ForAll)
  // ============================================

  @Provide
  Arbitrary<String> anyValidPattern() {
    return Arbitraries.oneOf(
        PatternArbitraries.literal(),
        PatternArbitraries.charClass(),
        PatternArbitraries.quantified(PatternArbitraries.literal()),
        PatternArbitraries.alternation(PatternArbitraries.literal()),
        PatternArbitraries.withBackrefs(),
        PatternArbitraries.contextFree());
  }

  @Provide
  Arbitrary<String> withBackrefs() {
    return PatternArbitraries.withBackrefs();
  }

  @Provide
  Arbitrary<String> contextFree() {
    return PatternArbitraries.contextFree();
  }

  @Provide
  Arbitrary<String> withOptionalGroupBackrefs() {
    return PatternArbitraries.withOptionalGroupBackrefs();
  }

  @Provide
  Arbitrary<String> largeStateSpace() {
    return PatternArbitraries.largeStateSpace();
  }

  // ============================================
  // HELPER METHODS
  // ============================================

  /** Analyze a pattern and return the selected strategy. */
  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) {
    try {
      RegexParser parser = new RegexParser();
      RegexNode ast = parser.parse(pattern);
      ThompsonBuilder builder = new ThompsonBuilder();

      try {
        NFA nfa = builder.build(ast, 0);
        PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
        return analyzer.analyzeAndRecommend();
      } catch (UnsupportedOperationException e) {
        // Context-free features (subroutines, conditionals) can't build NFA
        PatternAnalyzer analyzer = new PatternAnalyzer(ast, null);
        return analyzer.analyzeAndRecommend();
      }
    } catch (Exception e) {
      throw new AssertionError("Failed to analyze pattern: " + pattern, e);
    }
  }

  /** Check if strategy is a generic DFA strategy (not specialized). */
  private boolean isGenericDFAStrategy(PatternAnalyzer.MatchingStrategy strategy) {
    return strategy == DFA_UNROLLED
        || strategy == DFA_UNROLLED_WITH_GROUPS
        || strategy == DFA_UNROLLED_WITH_ASSERTIONS
        || strategy == DFA_SWITCH
        || strategy == DFA_SWITCH_WITH_GROUPS
        || strategy == DFA_SWITCH_WITH_ASSERTIONS
        || strategy == DFA_TABLE
        || strategy == OPTIMIZED_NFA;
  }

  /** Check if pattern contains backreferences. */
  private boolean hasBackreferences(String pattern) {
    return pattern.matches(".*\\\\\\d+.*"); // Contains \1, \2, etc.
  }

  /** Check if pattern has context-free features. */
  private boolean hasContextFreeFeatures(String pattern) {
    return pattern.contains("(?R)") || pattern.contains("(?(") || pattern.contains("(?|");
  }
}
