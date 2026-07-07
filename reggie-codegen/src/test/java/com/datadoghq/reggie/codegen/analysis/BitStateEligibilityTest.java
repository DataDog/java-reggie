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
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * P1 tests for the {@code BITSTATE_CAPTURE} anchored-quantifier exclusion added in task T2.4 (see
 * {@link PatternAnalyzer#isBitStateEligible} / {@code hasAnchoredNullableQuantifierBody}, design
 * doc {@code doc/2026-07-03-bitstate-capture-engine-design.md} §7.5).
 *
 * <p>These tests only prove the three anchored-quantifier shapes from §7.5 are excluded from {@code
 * BITSTATE_CAPTURE} routing (falling back to whatever {@code PIKEVM_CAPTURE} would otherwise have
 * been) -- they do not assert anything about BitState's actual matching behavior on these patterns.
 */
class BitStateEligibilityTest {

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new PatternAnalyzer(ast, nfa).analyzeAndRecommend();
  }

  @Test
  void repeatedAnchoredOptionalGroupIsExcluded() throws Exception {
    // (^a?){3}: the quantifier body starts with ^ and is nullable (a? matches empty), and the
    // quantifier itself repeats more than once (max == 3). This is the exact §7.5 shape that a
    // plain (stateId, pos) visited-set DFS cannot reproduce JDK's anchor semantics for.
    PatternAnalyzer.MatchingStrategyResult r = analyze("(^a?){3}");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, r.strategy);
  }

  @Test
  void multilineAnchoredOptionalGroupPlusIsExcluded() throws Exception {
    // (?m)(^x?)+: multiline start anchor inside a nullable body under an unbounded quantifier.
    PatternAnalyzer.MatchingStrategyResult r = analyze("(?m)(^x?)+");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, r.strategy);
  }

  @Test
  void stringStartAnchorFixedRepetitionIsExcluded() throws Exception {
    // \A{3}a: \A itself is zero-width nullable and repeats 3 times before the literal "a".
    PatternAnalyzer.MatchingStrategyResult r = analyze("\\A{3}a");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, r.strategy);
  }

  // hasBackreferences and hasLookaround are checked first in isBitStateEligible, but no pattern
  // containing a backreference or a lookaround ever reaches routeBitState with strategy ==
  // PIKEVM_CAPTURE: both features are intercepted earlier by their own dedicated strategies
  // (e.g. LINEAR_BACKREFERENCE, DFA_UNROLLED_WITH_ASSERTIONS). The guards are therefore invoked
  // directly via reflection to prove the decline logic itself, matching the direct-invocation
  // convention already used for PatternAnalyzer#detectPinnedBackreference in
  // PinnedBackreferenceDetectionTest.
  private boolean isBitStateEligible(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    Method method = PatternAnalyzer.class.getDeclaredMethod("isBitStateEligible", RegexNode.class);
    method.setAccessible(true);
    return (Boolean) method.invoke(analyzer, ast);
  }

  @Test
  void backreferencePatternIsIneligible() throws Exception {
    assertFalse(isBitStateEligible("(a)\\1"));
  }

  @Test
  void lookaroundPatternIsIneligible() throws Exception {
    assertFalse(isBitStateEligible("(?=a)b"));
  }

  @Test
  void nullableQuantifierBodyWithCapturingGroupInConcatIsExcluded() throws Exception {
    // Body "a?(b)?" is a ConcatNode (not itself a GroupNode), so
    // hasNullableQuantifierBodyWithNestedCapture's hasCapturingGroup call must iterate the
    // ConcatNode's children to find the nested capturing group (b): a?, which is not capturing,
    // followed by (b)?, which is. Both the whole body (a?(b)?) is zero-width nullable and the
    // outer "+" can repeat more than once, matching the declined shape from
    // hasNullableQuantifierBodyWithNestedCapture.
    PatternAnalyzer.MatchingStrategyResult r = analyze("(?:a?(b)?)+");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, r.strategy);
  }

  @Test
  void nullableQuantifierBodyWithCapturingGroupInAlternationIsExcluded() throws Exception {
    // Body "a?|(b)" is an AlternationNode; hasCapturingGroup must iterate its alternatives to
    // find the nested capturing group (b). The body is zero-width nullable because the first
    // alternative (a?) can match empty, and the outer "+" can repeat more than once.
    PatternAnalyzer.MatchingStrategyResult r = analyze("(?:a?|(b))+");
    assertNotEquals(PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, r.strategy);
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
          continue;
        }
        count++;
      } else if (c == '(') {
        count++;
      }
    }
    return count;
  }
}
