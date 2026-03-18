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
 * Tests for strategy selection in Phase 2. Verifies that patterns with subroutines, conditionals,
 * or branch reset correctly select RECURSIVE_DESCENT strategy.
 */
class StrategySelectionTest {

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();

    // Note: ThompsonBuilder will throw UnsupportedOperationException for these patterns,
    // but PatternAnalyzer should detect them BEFORE trying to build NFA
    try {
      NFA nfa = builder.build(ast, 0);
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
      return analyzer.analyzeAndRecommend();
    } catch (UnsupportedOperationException e) {
      // Expected for context-free features
      // PatternAnalyzer should have caught these earlier, but if we get here,
      // it means we need to check the AST directly
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, null);
      return analyzer.analyzeAndRecommend();
    }
  }

  // ==================== Subroutine Tests ====================

  @Test
  void testRecursiveSubroutine() throws Exception {
    // (?R) - recursive call to entire pattern
    PatternAnalyzer.MatchingStrategyResult result = analyze("a(?R)b");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with (?R) should select RECURSIVE_DESCENT strategy");
  }

  @Test
  void testNumberedSubroutine() throws Exception {
    // (?1) - call to group 1
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a+)(?1)");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with (?1) should select RECURSIVE_DESCENT strategy");
  }

  @Test
  void testNamedSubroutine() throws Exception {
    // (?&foo) - call to named group
    PatternAnalyzer.MatchingStrategyResult result = analyze("a(?&foo)b");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with (?&name) should select RECURSIVE_DESCENT strategy");
  }

  // ==================== Conditional Tests ====================

  @Test
  void testConditionalWithElse() throws Exception {
    // (?(1)yes|no) - conditional with both branches
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a)?(?(1)b|c)");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with (?(1)yes|no) should select RECURSIVE_DESCENT strategy");
  }

  @Test
  void testConditionalWithoutElse() throws Exception {
    // (?(1)yes) - conditional with only then branch
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a)?(?(1)b)");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with (?(1)yes) should select RECURSIVE_DESCENT strategy");
  }

  // ==================== Branch Reset Tests ====================

  @Test
  void testBranchReset() throws Exception {
    // (?|abc|xyz) - branch reset
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?|abc|xyz)");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with (?|...) should select RECURSIVE_DESCENT strategy");
  }

  @Test
  void testBranchResetWithGroups() throws Exception {
    // (?|(a)|(b)) - branch reset with groups
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?|(a)|(b))");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with branch reset should select RECURSIVE_DESCENT strategy");
  }

  // ==================== Mixed Features Tests ====================

  @Test
  void testSubroutineAndConditional() throws Exception {
    // Pattern with both subroutine and conditional
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a)(?1)(?(1)b|c)");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Pattern with subroutine AND conditional should select RECURSIVE_DESCENT strategy");
  }

  // ==================== Regular Patterns Tests ====================

  @Test
  void testRegularPatternWithoutContextFreeFeatures() throws Exception {
    // Simple pattern without context-free features - should NOT select RECURSIVE_DESCENT
    PatternAnalyzer.MatchingStrategyResult result = analyze("a+b*");

    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Simple pattern without context-free features should NOT select RECURSIVE_DESCENT");
  }

  @Test
  void testBackreferenceWithoutSubroutine() throws Exception {
    // Backreference without subroutine - should NOT select RECURSIVE_DESCENT
    // (Backreferences use OPTIMIZED_NFA_WITH_BACKREFS, not RECURSIVE_DESCENT)
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a+)\\1");

    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Backreference without context-free features should NOT select RECURSIVE_DESCENT");

    // Should select NFA strategy for backreferences
    assertTrue(
        result.strategy.name().contains("NFA") || result.strategy.name().contains("BACKREF"),
        "Backreference should use NFA-based strategy, got: " + result.strategy);
  }
}
