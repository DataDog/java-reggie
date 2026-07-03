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

  @Test
  void largePureDFAUsesTableStrategy() throws Exception {
    // The leading literal 'x' prevents COUNTING_GLUSHKOV interception (extractSingleQuantifier
    // returns null for a concat node with two children), so this routes to DFA_TABLE (302 states).
    PatternAnalyzer.MatchingStrategyResult result = analyze("x(?:[a-z][0-9]){150}");

    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_TABLE,
        result.strategy,
        "Large pure regular DFAs should use the compact table backend");
  }

  @Test
  void negatedCharClassDelimitedCapturesDoNotRequireRecursiveDescent() throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse("([^ ]+) ([^\" ]*) (HTTP/\\d\\.\\d)");
    NFA nfa = new ThompsonBuilder().build(ast, 2);

    PatternAnalyzer.MatchingStrategyResult result =
        new PatternAnalyzer(ast, nfa).analyzeAndRecommend();

    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        result.strategy,
        "Negated classes that exclude their following delimiter do not need backtracking");
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

  // ==================== Lazy Quantifier Routing Tests ====================

  @Test
  void lazyQuantifierRoutesToPikeVm() throws Exception {
    // Positive: plain lazy quantifiers must route to PIKEVM_CAPTURE
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("a*?b").strategy,
        "a*?b should route to PIKEVM_CAPTURE");
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("a+?b").strategy,
        "a+?b should route to PIKEVM_CAPTURE");
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("a??b").strategy,
        "a??b should route to PIKEVM_CAPTURE");
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("a{2,4}?b").strategy,
        "a{2,4}?b should route to PIKEVM_CAPTURE");
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("(a*?)b").strategy,
        "(a*?)b should route to PIKEVM_CAPTURE");

    // Negative: backreference blocks the lazy route
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("(a+?)\\1").strategy,
        "(a+?)\\1 has a backref — must not reach the lazy PIKEVM_CAPTURE block");

    // Negative: lookaround blocks the lazy route
    assertNotEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("a+?(?=b)").strategy,
        "a+?(?=b) has lookahead — must not reach the lazy PIKEVM_CAPTURE block");

    // Possessive quantifiers route to PIKEVM_CAPTURE via the hasAtomicGroups path (NOT the lazy
    // block). The !hasPossessiveQuantifiers guard in the lazy block ensures they never arrive via
    // the lazy path; hasAtomicGroups fires first because AtomicGroupDetector treats possessives as
    // atomic. These assertions verify possessives still reach PIKEVM_CAPTURE (correct engine) while
    // the guard prevents the wrong lazy-NFA construction from being triggered.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("a*+b").strategy,
        "a*+b is possessive — must route to PIKEVM_CAPTURE via hasAtomicGroups, not lazy block");
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        analyze("a++b").strategy,
        "a++b is possessive — must route to PIKEVM_CAPTURE via hasAtomicGroups, not lazy block");

    // Negative: subroutine + lazy quantifier must route to RECURSIVE_DESCENT (not PIKEVM_CAPTURE)
    assertEquals(
        PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT,
        analyze("(a+?)(?1)").strategy,
        "(a+?)(?1) has a subroutine — must route to RECURSIVE_DESCENT, not the lazy PIKEVM_CAPTURE block");
  }

  @Test
  void diagnoseRepros() throws Exception {
    for (String pat :
        new String[] {"(_{0}|.){4}[^_]+?c", "()c|\\10?", "(.{0}0{0}[0]{0}|a){2}|^[0-b]{1}"}) {
      var result = analyze(pat);
      System.out.println(
          "PAT=[" + pat + "] strategy=" + result.strategy + " trace=" + result.guardTrace);
    }
  }
}
