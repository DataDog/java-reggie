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
 * Extended strategy selection tests covering additional strategies and edge cases in
 * PatternAnalyzer. Each test exercises the detection-method chain up to the selected strategy,
 * increasing branch coverage in PatternAnalyzer's analyze-and-recommend path.
 */
class StrategySelectionExtendedTest {

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    try {
      NFA nfa = builder.build(ast, countGroups(pattern));
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
      return analyzer.analyzeAndRecommend();
    } catch (UnsupportedOperationException e) {
      PatternAnalyzer analyzer = new PatternAnalyzer(ast, null);
      return analyzer.analyzeAndRecommend();
    }
  }

  private int countGroups(String pattern) {
    int count = 0;
    boolean inEscape = false;
    for (int i = 0; i < pattern.length(); i++) {
      char ch = pattern.charAt(i);
      if (inEscape) {
        inEscape = false;
        continue;
      }
      if (ch == '\\') {
        inEscape = true;
      } else if (ch == '(' && i + 1 < pattern.length()) {
        if (i + 2 < pattern.length()
            && pattern.charAt(i + 1) == '?'
            && pattern.charAt(i + 2) == ':') {
          continue;
        }
        count++;
      }
    }
    return count;
  }

  // ── STATELESS_LOOP ───────────────────────────────────────────────────────

  @Test
  void testStatelessLoopDigitPlus() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d+");
    assertEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);
  }

  @Test
  void testStatelessLoopAlphaStar() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("[a-z]*");
    assertEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);
  }

  @Test
  void testStatelessLoopWordCharPlus() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\w+");
    assertEquals(PatternAnalyzer.MatchingStrategy.STATELESS_LOOP, result.strategy);
  }

  // ── SPECIALIZED_LITERAL_ALTERNATION ─────────────────────────────────────

  @Test
  void testLiteralAlternationThreeKeywordsToDfa() throws Exception {
    // < 5 alternatives → falls through to DFA (threshold enforced by detectLiteralAlternation)
    PatternAnalyzer.MatchingStrategyResult result = analyze("foo|bar|baz");
    assertEquals(PatternAnalyzer.MatchingStrategy.DFA_UNROLLED, result.strategy);
  }

  @Test
  void testLiteralAlternationTwoKeywordsToDfa() throws Exception {
    // < 5 alternatives → falls through to DFA
    PatternAnalyzer.MatchingStrategyResult result = analyze("GET|POST");
    assertEquals(PatternAnalyzer.MatchingStrategy.DFA_UNROLLED, result.strategy);
  }

  @Test
  void testLiteralAlternationFiveKeywords() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("one|two|three|four|five");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_LITERAL_ALTERNATION, result.strategy);
  }

  // ── SPECIALIZED_GREEDY_CHARCLASS ─────────────────────────────────────────

  @Test
  void testGreedyCharClassSingleGroup() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\d+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_GREEDY_CHARCLASS, result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  @Test
  void testGreedyCharClassWordStar() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\w*)");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_GREEDY_CHARCLASS, result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  @Test
  void testGreedyCharClassAlphaPlus() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("([a-z]+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_GREEDY_CHARCLASS, result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  @Test
  void testGreedyCharClassNegatedCharset() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("([^a-z]+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_GREEDY_CHARCLASS, result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  // ── GREEDY_BACKTRACK ─────────────────────────────────────────────────────

  @Test
  void testGreedyBacktrackDotStarLiteral() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(.*)end");
    assertEquals(PatternAnalyzer.MatchingStrategy.GREEDY_BACKTRACK, result.strategy);
  }

  @Test
  void testGreedyBacktrackDotStarDigit() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(.*)([0-9]+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.GREEDY_BACKTRACK, result.strategy);
  }

  @Test
  void testGreedyBacktrackPlusGroup() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(.+)(\\d+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.GREEDY_BACKTRACK, result.strategy);
  }

  // ── SPECIALIZED_MULTI_GROUP_GREEDY ───────────────────────────────────────

  @Test
  void testMultiGroupGreedyEmail() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("([a-z]+)@([a-z]+)\\.([a-z]+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTI_GROUP_GREEDY, result.strategy);
  }

  @Test
  void testMultiGroupGreedyTwoGroups() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\w+) (\\w+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTI_GROUP_GREEDY, result.strategy);
  }

  @Test
  void testMultiGroupGreedyNegatedCharset() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("([^@]+)@([^.]+)\\.([^.]+)");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_MULTI_GROUP_GREEDY, result.strategy);
  }

  // ── SPECIALIZED_FIXED_SEQUENCE ───────────────────────────────────────────

  @Test
  void testFixedSequencePhone() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d{3}-\\d{3}-\\d{4}");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE, result.strategy);
  }

  @Test
  void testFixedSequenceDate() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d{4}-\\d{2}-\\d{2}");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE, result.strategy);
  }

  @Test
  void testFixedSequenceLiteral() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("abc");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE, result.strategy);
  }

  // ── SPECIALIZED_BOUNDED_QUANTIFIERS ─────────────────────────────────────

  @Test
  void testBoundedQuantifiersIPv4() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result =
        analyze("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_BOUNDED_QUANTIFIERS, result.strategy);
  }

  @Test
  void testBoundedQuantifiersHexColor() throws Exception {
    // Range quantifier {4,6} prevents fixed-sequence detection
    PatternAnalyzer.MatchingStrategyResult result = analyze("#[0-9a-fA-F]{4,6}");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_BOUNDED_QUANTIFIERS, result.strategy);
  }

  @Test
  void testBoundedQuantifiersRange() throws Exception {
    // Concat with range quantifier (min≥1 required); avoids both STATELESS_LOOP and FIXED_SEQUENCE.
    // The trailing literal must be DISJOINT from the quantified class: an overlapping shape such as
    // [a-z]{1,3}b requires giving back the overshoot (e.g. "ab" must match) which the
    // non-backtracking fast path cannot do, so the analyzer declines it.
    PatternAnalyzer.MatchingStrategyResult result = analyze("[a-z]{1,3}0");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_BOUNDED_QUANTIFIERS, result.strategy);
  }

  // ── LINEAR_BACKREFERENCE ─────────────────────────────────────────────────

  @Test
  void testLinearBackreferenceLiteralGroup() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(abc)\\1");
    assertEquals(PatternAnalyzer.MatchingStrategy.LINEAR_BACKREFERENCE, result.strategy);
  }

  @Test
  void testLinearBackreferenceSingleChar() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a)\\1");
    assertEquals(PatternAnalyzer.MatchingStrategy.LINEAR_BACKREFERENCE, result.strategy);
  }

  // ── SPECIALIZED_BACKREFERENCE ────────────────────────────────────────────

  @Test
  void testSpecializedBackrefRepeatedWord() throws Exception {
    // Word boundaries cause this to be handled as VARIABLE_CAPTURE_BACKREF, not SPECIALIZED
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\b(\\w+)\\s+\\1\\b");
    assertEquals(PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF, result.strategy);
  }

  // ── VARIABLE_CAPTURE_BACKREF ─────────────────────────────────────────────

  @Test
  void testVariableCaptureBackrefWhitespace() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\w+)\\s+\\1");
    assertEquals(PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF, result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  @Test
  void testVariableCaptureBackrefAnchored() throws Exception {
    // Anchors set hasStartAnchor/hasEndAnchor in VariableCaptureBackrefInfo.toString()
    PatternAnalyzer.MatchingStrategyResult result = analyze("^(\\w+)\\s+\\1$");
    assertEquals(PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF, result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  @Test
  void testVariableCaptureBackrefLiteralSeparator() throws Exception {
    // Literal separator covers separatorLiteral != null branch in toString()
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\w+),(\\w+),\\1");
    assertNotNull(result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  // ── FIXED_REPETITION_BACKREF ─────────────────────────────────────────────

  @Test
  void testFixedRepetitionBackrefTriple() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\w)\\1{2}");
    assertEquals(PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF, result.strategy);
  }

  @Test
  void testFixedRepetitionBackrefEight() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(\\d)\\1{8}");
    assertEquals(PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF, result.strategy);
  }

  // ── RECURSIVE_DESCENT ───────────────────────────────────────────────────

  @Test
  void testRecursiveDescentNonGreedy() throws Exception {
    // \d+? has no backrefs/lookaround/possessives — routes to PIKEVM_CAPTURE for lazy NFA, then
    // substituted to BITSTATE_CAPTURE (see PatternAnalyzer.isBitStateEligible): same
    // leftmost-greedy, native group-extraction semantics.
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d+?");
    assertEquals(PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, result.strategy);
  }

  @Test
  void testRecursiveDescentNonGreedyStar() throws Exception {
    // .*?end has no backrefs/lookaround/possessives — routes to PIKEVM_CAPTURE for lazy NFA, then
    // substituted to BITSTATE_CAPTURE (see PatternAnalyzer.isBitStateEligible): same
    // leftmost-greedy, native group-extraction semantics.
    PatternAnalyzer.MatchingStrategyResult result = analyze(".*?end");
    assertEquals(PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, result.strategy);
  }

  // ── DFA_UNROLLED ────────────────────────────────────────────────────────

  @Test
  void testDfaUnrolledConcatUnbounded() throws Exception {
    // Concat with unbounded quantifier + literal - bypasses FIXED_SEQUENCE, small DFA
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d+[a-z]");
    assertEquals(PatternAnalyzer.MatchingStrategy.DFA_UNROLLED, result.strategy);
  }

  @Test
  void testDfaUnrolledSimpleAlternation() throws Exception {
    // Pattern not caught by literal alternation (has char classes)
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\d|[a-z]");
    // Either DFA_UNROLLED or DFA_SWITCH depending on state count
    assertTrue(
        result.strategy == PatternAnalyzer.MatchingStrategy.DFA_UNROLLED
            || result.strategy == PatternAnalyzer.MatchingStrategy.DFA_SWITCH,
        "Expected DFA strategy, got: " + result.strategy);
  }

  // ── DFA_SWITCH ──────────────────────────────────────────────────────────

  @Test
  void testDfaSwitchEmailPattern() throws Exception {
    // Complex email pattern with many DFA states
    PatternAnalyzer.MatchingStrategyResult result =
        analyze("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    // Large charset patterns generate many DFA states -> DFA_SWITCH or DFA_UNROLLED
    assertTrue(
        result.strategy == PatternAnalyzer.MatchingStrategy.DFA_SWITCH
            || result.strategy == PatternAnalyzer.MatchingStrategy.DFA_UNROLLED,
        "Expected DFA strategy, got: " + result.strategy);
  }

  // ── ONEPASS_NFA ─────────────────────────────────────────────────────────

  @Test
  void testOnPassOrDfaWithGroups() throws Exception {
    // Pattern with groups but no ambiguity - may select ONEPASS_NFA or DFA_WITH_GROUPS
    PatternAnalyzer.MatchingStrategyResult result = analyze("(abc)(def)");
    assertNotNull(result.strategy);
    // The strategy should be something efficient (not generic NFA)
    assertNotEquals(PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA, result.strategy);
  }

  // ── DFA paths with groups ────────────────────────────────────────────────

  @Test
  void testDfaWithGroupsSmall() throws Exception {
    // Pattern with groups and small DFA
    PatternAnalyzer.MatchingStrategyResult result = analyze("(a)(b)(c)");
    assertNotNull(result.strategy);
  }

  @Test
  void testDfaWithGroupsMedium() throws Exception {
    // Pattern with groups
    PatternAnalyzer.MatchingStrategyResult result = analyze("([a-z]+)-([0-9]+)");
    assertNotNull(result.strategy);
  }

  // ── SPECIALIZED_BACKREFERENCE (detectAttributeMatchPattern) ──────────────

  @Test
  void testAttributeMatchPatternDoubleQuote() throws Exception {
    // Complex separator (['"', \s*, '=', \s*, '"']) makes extractSeparatorInfoFromNodes return
    // null,
    // bypassing detectVariableCaptureBackref and reaching detectAttributeMatchPattern instead.
    PatternAnalyzer.MatchingStrategyResult result = analyze("\"([^\"]+)\"\\s*=\\s*\"\\1\"");
    assertEquals(PatternAnalyzer.MatchingStrategy.SPECIALIZED_BACKREFERENCE, result.strategy);
  }

  @Test
  void testAttributeMatchPatternSingleQuote() throws Exception {
    // Single-quote variant does not match the 9-child detectAttributeMatchPattern structure
    // and falls through to OPTIMIZED_NFA_WITH_BACKREFS
    PatternAnalyzer.MatchingStrategyResult result = analyze("'([^']+)'\\s*=\\s*'\\1'");
    assertEquals(PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS, result.strategy);
  }

  // ── detectFastPathEligibility ────────────────────────────────────────────

  @Test
  void testFastPathEligibilityEmailLookahead() throws Exception {
    // Positive lookahead + .* + literal suffix exercises detectFastPathEligibility
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?=\\w+@).*@\\w+\\.\\w+");
    assertNotNull(result.strategy);
  }

  // ── isSingleQuantifierPattern (anchored patterns → DFA) ─────────────────

  @Test
  void testAnchoredBothEnds() throws Exception {
    // Anchors cause isSingleQuantifierPattern to be evaluated but DFA_UNROLLED is selected
    PatternAnalyzer.MatchingStrategyResult result = analyze("^[a-z]+$");
    assertEquals(PatternAnalyzer.MatchingStrategy.DFA_UNROLLED, result.strategy);
  }

  @Test
  void testAnchoredLeading() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("^\\d+");
    assertEquals(PatternAnalyzer.MatchingStrategy.DFA_UNROLLED, result.strategy);
  }

  @Test
  void testAnchoredTrailing() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("\\w+$");
    assertEquals(PatternAnalyzer.MatchingStrategy.DFA_UNROLLED, result.strategy);
  }

  // ── detectHTMLTagPattern ─────────────────────────────────────────────────

  @Test
  void testHtmlTagPatternExact() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("<(\\w+)>.*</\\1>");
    assertNotNull(result.strategy);
  }

  @Test
  void testHtmlTagPatternStarQuantifier() throws Exception {
    // \w* instead of \w+ — fails detectHTMLTagPattern's quantifier check, falls through
    PatternAnalyzer.MatchingStrategyResult result = analyze("<(\\w*)>.*</\\1>");
    assertNotNull(result.strategy);
  }

  // ── NESTED_QUANTIFIED_GROUPS ─────────────────────────────────────────────

  @Test
  void testNestedQuantifiedGroupsDouble() throws Exception {
    // Outer and inner quantifiers trigger NestedQuantifiedGroupsInfo
    PatternAnalyzer.MatchingStrategyResult result = analyze("((a+)+)");
    assertNotNull(result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }

  @Test
  void testNestedQuantifiedGroupsWordChar() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("((\\w+)+)");
    assertNotNull(result.strategy);
    if (result.patternInfo != null) assertNotNull(result.patternInfo.toString());
  }
}
