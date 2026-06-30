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
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests that StructuralHash produces distinct values for patterns that are structurally different
 * but would otherwise look the same if key fields were omitted from the hash.
 */
class StructuralHashTest {

  private long hashFor(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, 0);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();
    return StructuralHash.compute(result, nfa, false);
  }

  // ── AssertionCheck content ────────────────────────────────────────────────────

  @Test
  void differentLookaheadLiterals_produceDifferentHashes() throws Exception {
    // (?=ab)x and (?=cd)x have the same DFA topology shape but different assertion literals.
    // If the literal is not hashed, both patterns would produce the same structural hash,
    // causing the cache to return the wrong compiled class.
    long h1 = hashFor("(?=ab)x");
    long h2 = hashFor("(?=cd)x");
    assertNotEquals(h1, h2, "(?=ab)x and (?=cd)x must have distinct structural hashes");
  }

  @Test
  void positiveLookaheadVsNegativeLookahead_produceDifferentHashes() throws Exception {
    // (?=ab)x vs (?!ab)x — same literal, different assertion type.
    long h1 = hashFor("(?=ab)x");
    long h2 = hashFor("(?!ab)x");
    assertNotEquals(
        h1, h2, "positive and negative lookaheads must have distinct structural hashes");
  }

  @Test
  void sameLookaheadLiteral_producesSameHash() throws Exception {
    // Two textually identical patterns must still produce the same hash.
    long h1 = hashFor("(?=ab)x");
    long h2 = hashFor("(?=ab)x");
    assertEquals(h1, h2, "identical patterns must produce the same structural hash");
  }

  // ── GroupAction content ───────────────────────────────────────────────────────

  @Test
  void groupAtDifferentPositions_produceDifferentHashes() throws Exception {
    // (a)b and a(b) have groups at different DFA states.
    // If groupActions are hashed only by size, these two patterns might collide.
    long h1 = hashFor("(a)b");
    long h2 = hashFor("a(b)");
    assertNotEquals(h1, h2, "(a)b and a(b) must have distinct structural hashes");
  }

  @Test
  void differentGroupNumbers_produceDifferentHashes() throws Exception {
    // (a)(b) with groupId=1 captured in first position vs second position.
    // More concretely: two groups vs one group at the same syntactic position.
    long h1 = hashFor("(a)b");
    long h2 = hashFor("(a)(b)");
    assertNotEquals(h1, h2, "different group counts must produce distinct structural hashes");
  }

  // ── TagOperation content ──────────────────────────────────────────────────────

  @Test
  void taggedDFAPatterns_differentGroups_produceDifferentHashes() throws Exception {
    // (a)b and a(b) via tagged DFA should also differ.
    RegexParser parser = new RegexParser();

    RegexNode ast1 = parser.parse("(a)b");
    NFA nfa1 = new ThompsonBuilder().build(ast1, 0);
    PatternAnalyzer.MatchingStrategyResult r1 =
        new PatternAnalyzer(ast1, nfa1).analyzeAndRecommend();
    long h1 = StructuralHash.compute(r1, nfa1, false);

    RegexNode ast2 = parser.parse("a(b)");
    NFA nfa2 = new ThompsonBuilder().build(ast2, 0);
    PatternAnalyzer.MatchingStrategyResult r2 =
        new PatternAnalyzer(ast2, nfa2).analyzeAndRecommend();
    long h2 = StructuralHash.compute(r2, nfa2, false);

    assertNotEquals(h1, h2, "groups at different positions must have distinct structural hashes");
  }

  // ── NFAState.assertionWidth (lookbehind) ──────────────────────────────────────

  @Test
  void lookbehindDifferentWidths_produceDifferentHashes() throws Exception {
    // (?<=a)x uses width=1 lookbehind; (?<=ab)x uses width=2.
    // If assertionWidth is not included in contentHashCode(), these can collide.
    long h1 = hashFor("(?<=a)x");
    long h2 = hashFor("(?<=ab)x");
    assertNotEquals(
        h1, h2, "lookbehind assertions of different widths must have distinct structural hashes");
  }

  @Test
  void lookbehindDifferentLiterals_produceDifferentHashes() throws Exception {
    // (?<=ab)x and (?<=cd)x — same width but different content.
    long h1 = hashFor("(?<=ab)x");
    long h2 = hashFor("(?<=cd)x");
    assertNotEquals(
        h1,
        h2,
        "lookbehind assertions with different literals must have distinct structural hashes");
  }

  // ── acceptIsPriorityCut distinctness (HARD RULE: fail-on-revert) ────────────────

  /**
   * Two patterns with different acceptIsPriorityCut polarity must produce different structural
   * hashes. This test fails when the hash line for acceptIsPriorityCut is removed from
   * StructuralHash.computeDFATopologyHash — making it the fail-on-revert guard for this invariant.
   *
   * <p>{@code (fo|foo)} has a priority-cut accepting state (the 'fo'-branch wins over 'foo');
   * {@code (a)+} has only greedy-continue accepting states (loop-back wins over stop). Both have
   * accepting states with outgoing transitions, so the topology shapes are similar but the flag
   * values differ.
   */
  @Test
  void priorityCutVsGreedyContinue_produceDifferentHashes() throws Exception {
    // (fo|foo): has priority-cut accepting state (cut=true on the state after "fo")
    long h1 = hashFor("(fo|foo)");
    // (a)+: has only greedy-continue accepting state (cut=false on the self-loop state)
    long h2 = hashFor("(a)+");
    assertNotEquals(
        h1,
        h2,
        "(fo|foo) and (a)+ differ in acceptIsPriorityCut; their structural hashes must differ — "
            + "this test fails when the acceptIsPriorityCut hash line is removed");
  }

  // ── Atomic group distinctness ─────────────────────────────────────────────────

  @Test
  void atomicGroupVsPlainQuantifier_produceDifferentHashes() throws Exception {
    // a* and (?>a*) have the same DFA topology but differ in hasAtomicGroups.
    // If hasAtomicGroups is not included in the hash, both patterns would produce the same
    // structural hash, causing the cache to return the wrong compiled class.
    long h1 = hashFor("a*");
    long h2 = hashFor("(?>a*)");
    assertNotEquals(h1, h2, "a* and (?>a*) must have distinct structural hashes");
  }

  // ── Strategy / topology distinctness ─────────────────────────────────────────

  @Test
  void captureAmbiguousOptional_producesDistinctHashFromMandatoryGroup() throws Exception {
    // (a)?b and (a)b both route to DFA_UNROLLED_WITH_GROUPS after C4 (the optional bypass
    // makes (a)?b capture-ambiguous, but the C2 priority-ordered TDFA is span-correct).
    // Their tagged-DFA topologies are structurally distinct; the hash must reflect that.
    long h1 = hashFor("(a)?b");
    long h2 = hashFor("(a)b");
    assertNotEquals(
        h1, h2, "(a)?b and (a)b have distinct DFA topologies and must produce distinct hashes");
  }

  // ── TagOperation membership / order (Phase C2 guard) ──

  /** Builds a 2-state DFA (start→accept on 'a') with the given tag operations on the transition. */
  private static DFA buildTwoStateDFA(List<DFA.TagOperation> tagOps) {
    DFA.DFAState start = new DFA.DFAState(0, Collections.emptySet(), false);
    DFA.DFAState accept = new DFA.DFAState(1, Collections.emptySet(), true);
    start.addTransition(CharSet.of('a'), accept, tagOps);
    return new DFA(start, Set.of(accept), List.of(start, accept));
  }

  @Test
  void tagOpMembership_changesHash() throws Exception {
    RegexNode sharedAst = new RegexParser().parse("(a)b");
    NFA sharedNfa = new ThompsonBuilder().build(sharedAst, 1);

    DFA dfa1 =
        buildTwoStateDFA(List.of(new DFA.TagOperation(2, 1, DFA.TagOperation.ActionType.START, 0)));
    DFA dfa2 =
        buildTwoStateDFA(
            List.of(
                new DFA.TagOperation(2, 1, DFA.TagOperation.ActionType.START, 0),
                new DFA.TagOperation(3, 1, DFA.TagOperation.ActionType.END, 0)));

    PatternAnalyzer.MatchingStrategyResult r1 =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, dfa1);
    PatternAnalyzer.MatchingStrategyResult r2 =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, dfa2);

    long h1 = StructuralHash.compute(r1, sharedNfa, false);
    long h2 = StructuralHash.compute(r2, sharedNfa, false);
    assertNotEquals(h1, h2, "adding a tagOp to the list must change the structural hash");
  }

  @Test
  void tagOpOrder_changesHash() throws Exception {
    RegexNode sharedAst = new RegexParser().parse("(a)b");
    NFA sharedNfa = new ThompsonBuilder().build(sharedAst, 1);

    DFA dfa1 =
        buildTwoStateDFA(
            List.of(
                new DFA.TagOperation(2, 1, DFA.TagOperation.ActionType.START, 0),
                new DFA.TagOperation(3, 1, DFA.TagOperation.ActionType.END, 0)));
    DFA dfa2 =
        buildTwoStateDFA(
            List.of(
                new DFA.TagOperation(3, 1, DFA.TagOperation.ActionType.END, 0),
                new DFA.TagOperation(2, 1, DFA.TagOperation.ActionType.START, 0)));

    PatternAnalyzer.MatchingStrategyResult r1 =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, dfa1);
    PatternAnalyzer.MatchingStrategyResult r2 =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, dfa2);

    long h1 = StructuralHash.compute(r1, sharedNfa, false);
    long h2 = StructuralHash.compute(r2, sharedNfa, false);
    assertNotEquals(h1, h2, "reversing tagOp order must change the structural hash");
  }

  @Test
  void tagOpPriorityOnly_sameHash() throws Exception {
    RegexNode sharedAst = new RegexParser().parse("(a)b");
    NFA sharedNfa = new ThompsonBuilder().build(sharedAst, 1);

    DFA dfa1 =
        buildTwoStateDFA(List.of(new DFA.TagOperation(2, 1, DFA.TagOperation.ActionType.START, 0)));
    DFA dfa2 =
        buildTwoStateDFA(
            List.of(new DFA.TagOperation(2, 1, DFA.TagOperation.ActionType.START, 99)));

    PatternAnalyzer.MatchingStrategyResult r1 =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, dfa1);
    PatternAnalyzer.MatchingStrategyResult r2 =
        new PatternAnalyzer.MatchingStrategyResult(
            PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, dfa2);

    long h1 = StructuralHash.compute(r1, sharedNfa, false);
    long h2 = StructuralHash.compute(r2, sharedNfa, false);
    assertEquals(h1, h2, "priority-only difference must not change the structural hash");
  }
}
