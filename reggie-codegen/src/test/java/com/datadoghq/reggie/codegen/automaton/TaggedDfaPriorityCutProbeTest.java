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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Empirically demonstrates that a blanket first-accept rule is insufficient and that per-state
 * priority-cut is required.
 *
 * <p>Two classes of accepting states exist:
 *
 * <ul>
 *   <li><b>Priority-cut:</b> the highest-priority live thread accepts — e.g. {@code (fo|foo)} after
 *       reading "fo". The first alternative wins; stop immediately.
 *   <li><b>Greedy-continue:</b> the highest-priority live thread is the loop-back — e.g. {@code
 *       (a)+} after reading "a". Greedy means consume more; the accept thread loses to the
 *       loop-back thread in priority.
 * </ul>
 *
 * <p>A blanket first-accept rule (always stop at the first accepting state) is correct for
 * priority-cut states but wrong for greedy-continue states: it would give {@code (a)+} on "aaa"
 * span [0,1) instead of the JDK-correct [0,3).
 *
 * <p>The per-state-cut rule (stop iff {@code acceptRank <= continueRank}) is correct for both. The
 * {@code acceptRank} is the lowest rank among accepting NFA states in the DFA state's ordered
 * closure; the {@code continueRank} is the lowest rank among NFA states with consuming transitions.
 * Lower rank = higher priority (Perl/PikeVM semantics).
 */
class TaggedDfaPriorityCutProbeTest {

  /** Returns int[4] = [matchStart, matchEnd, group1Start, group1End] via java.util.regex. */
  private int[] jdkFind(String pattern, String input) {
    Matcher m = Pattern.compile(pattern).matcher(input);
    if (!m.find()) return null;
    return new int[] {
      m.start(), m.end(), m.start(1) < 0 ? -1 : m.start(1), m.end(1) < 0 ? -1 : m.end(1)
    };
  }

  /**
   * Blanket first-accept simulator: always stops at the first accepting state encountered during a
   * scan (identical to TaggedDfaFindSpanProbeTest.simulateTaggedDfaFind). This is the rule C5.2
   * incorrectly generalised as sufficient for all patterns.
   */
  private int[] simulateBlanketFirstAccept(DFA dfa, NFA nfa, String input) {
    int tagLen = 2 * (nfa.getGroupCount() + 1);
    for (int start = 0; start <= input.length(); start++) {
      int[] tags = new int[tagLen];
      Arrays.fill(tags, -1);
      DFA.DFAState state = dfa.getStartState();
      for (DFA.GroupAction a : state.groupActions) {
        int idx = (a.type == DFA.GroupAction.ActionType.ENTER) ? 2 * a.groupId : 2 * a.groupId + 1;
        tags[idx] = start;
      }
      boolean failed = false;
      int end = start;
      for (int i = start; i < input.length(); i++) {
        char c = input.charAt(i);
        DFA.DFATransition trans = null;
        for (Map.Entry<CharSet, DFA.DFATransition> e : state.transitions.entrySet()) {
          if (e.getKey().contains(c)) {
            trans = e.getValue();
            break;
          }
        }
        if (trans == null) {
          failed = true;
          break;
        }
        for (DFA.TagOperation op : trans.tagOps) {
          tags[op.tagId] = (op.type == DFA.TagOperation.ActionType.START) ? i : i + 1;
        }
        state = trans.target;
        end = i + 1;
        if (state.accepting) {
          return new int[] {start, end, tags[2], tags[3]};
        }
      }
      if (!failed && state.accepting) {
        return new int[] {start, end, tags[2], tags[3]};
      }
    }
    return null;
  }

  /**
   * Computes, for every accepting DFA state, whether it is a priority-cut state. A state is a
   * priority-cut state iff the lowest-rank (highest-priority) NFA state that accepts has rank ≤ the
   * lowest-rank NFA state that has consuming outgoing transitions. When true, the highest- priority
   * live thread is the one that accepts — the executor must stop immediately (cut).
   */
  private Map<DFA.DFAState, Boolean> computePerStateCutMap(
      DFA dfa, SubsetConstructor sc, Set<NFA.NFAState> nfaAcceptStates) {
    Map<DFA.DFAState, Boolean> map = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (!state.accepting) {
        map.put(state, false);
        continue;
      }
      List<NFA.NFAState> ordered = sc.getOrdering(state.nfaStates);
      if (ordered == null || ordered.isEmpty()) {
        map.put(state, false);
        continue;
      }
      Map<NFA.NFAState, Integer> rankMap = SubsetConstructor.buildRankMap(ordered);

      int acceptRank = Integer.MAX_VALUE;
      for (NFA.NFAState s : state.nfaStates) {
        if (nfaAcceptStates.contains(s)) {
          acceptRank = Math.min(acceptRank, rankMap.getOrDefault(s, Integer.MAX_VALUE));
        }
      }
      if (acceptRank == Integer.MAX_VALUE) {
        map.put(state, false);
        continue;
      }

      int continueRank = Integer.MAX_VALUE;
      for (NFA.NFAState s : state.nfaStates) {
        if (!s.getTransitions().isEmpty()) {
          continueRank = Math.min(continueRank, rankMap.getOrDefault(s, Integer.MAX_VALUE));
        }
      }

      map.put(state, acceptRank <= continueRank);
    }
    return map;
  }

  /**
   * Per-state-cut simulator: at a priority-cut accepting state, commits immediately; at a
   * greedy-continue accepting state, saves the result as a candidate and keeps consuming. Returns
   * the last accepted position (longest match for non-cut states, first accept for cut states).
   */
  private int[] simulatePerStateCut(
      DFA dfa, NFA nfa, String input, Map<DFA.DFAState, Boolean> cutMap) {
    int tagLen = 2 * (nfa.getGroupCount() + 1);
    for (int start = 0; start <= input.length(); start++) {
      int[] tags = new int[tagLen];
      Arrays.fill(tags, -1);
      DFA.DFAState state = dfa.getStartState();
      for (DFA.GroupAction a : state.groupActions) {
        int idx = (a.type == DFA.GroupAction.ActionType.ENTER) ? 2 * a.groupId : 2 * a.groupId + 1;
        tags[idx] = start;
      }
      boolean failed = false;
      int end = start;
      int[] savedTags = null;
      int savedEnd = -1;
      for (int i = start; i < input.length(); i++) {
        char c = input.charAt(i);
        DFA.DFATransition trans = null;
        for (Map.Entry<CharSet, DFA.DFATransition> e : state.transitions.entrySet()) {
          if (e.getKey().contains(c)) {
            trans = e.getValue();
            break;
          }
        }
        if (trans == null) {
          failed = true;
          break;
        }
        for (DFA.TagOperation op : trans.tagOps) {
          tags[op.tagId] = (op.type == DFA.TagOperation.ActionType.START) ? i : i + 1;
        }
        state = trans.target;
        end = i + 1;
        if (state.accepting) {
          if (Boolean.TRUE.equals(cutMap.get(state))) {
            return new int[] {start, end, tags[2], tags[3]};
          }
          savedTags = tags.clone();
          savedEnd = end;
        }
      }
      if (!failed && state.accepting) {
        return new int[] {start, end, tags[2], tags[3]};
      }
      if (savedTags != null) {
        return new int[] {start, savedEnd, savedTags[2], savedTags[3]};
      }
    }
    return null;
  }

  // ── Cut case: (fo|foo) ────────────────────────────────────────────────────

  @Test
  void foOrFoo_blanketFirstAccept_isCorrect() throws Exception {
    int[] jdk = jdkFind("(fo|foo)", "foo");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(fo|foo)");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = sc.buildDFA(nfa, true);

    int[] blanket = simulateBlanketFirstAccept(dfa, nfa, "foo");
    assertNotNull(blanket);
    assertArrayEquals(jdk, blanket, "(fo|foo): blanket first-accept must match JDK for cut cases");
  }

  @Test
  void foOrFoo_perStateCut_matchesJdk() throws Exception {
    int[] jdk = jdkFind("(fo|foo)", "foo");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(fo|foo)");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = sc.buildDFA(nfa, true);
    Map<DFA.DFAState, Boolean> cutMap = computePerStateCutMap(dfa, sc, nfa.getAcceptStates());

    int[] result = simulatePerStateCut(dfa, nfa, "foo", cutMap);
    assertNotNull(result);
    assertArrayEquals(jdk, result, "(fo|foo): per-state-cut must match JDK");
  }

  // ── Greedy-continue case: (a)+ ────────────────────────────────────────────

  /**
   * Demonstrates the core refutation of C5.2 Outcome A: blanket first-accept gives the wrong span
   * for a greedy-continue pattern. The accepting state after the first 'a' has an outgoing 'a'
   * transition (the loop-back), and the loop-back thread has higher priority than the accept
   * thread, so the executor must continue — not stop at first accept.
   */
  @Test
  void aPlus_blanketFirstAccept_divergesFromJdk() throws Exception {
    int[] jdk = jdkFind("(a)+", "aaa");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(a)+");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = sc.buildDFA(nfa, true);

    int[] blanket = simulateBlanketFirstAccept(dfa, nfa, "aaa");
    // Blanket first-accept stops after the first 'a', giving [0,1,...] instead of [0,3,...].
    assertFalse(
        Arrays.equals(jdk, blanket),
        "(a)+ blanket-first-accept must diverge from JDK — this is the C5.2 counterexample");
  }

  @Test
  void aPlus_perStateCut_matchesJdk() throws Exception {
    int[] jdk = jdkFind("(a)+", "aaa");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(a)+");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = sc.buildDFA(nfa, true);
    Map<DFA.DFAState, Boolean> cutMap = computePerStateCutMap(dfa, sc, nfa.getAcceptStates());

    int[] result = simulatePerStateCut(dfa, nfa, "aaa", cutMap);
    assertNotNull(result);
    assertArrayEquals(jdk, result, "(a)+ per-state-cut must match JDK (greedy-continue = no cut)");
  }

  // ── Greedy-continue case: (ab)+ ───────────────────────────────────────────

  @Test
  void abPlus_blanketFirstAccept_divergesFromJdk() throws Exception {
    int[] jdk = jdkFind("(ab)+", "abab");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(ab)+");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = sc.buildDFA(nfa, true);

    int[] blanket = simulateBlanketFirstAccept(dfa, nfa, "abab");
    assertFalse(Arrays.equals(jdk, blanket), "(ab)+ blanket-first-accept must diverge from JDK");
  }

  // ── Cross-check cut flag polarity ────────────────────────────────────────

  @Test
  void foOrFoo_acceptingState_isCut() throws Exception {
    RegexNode ast = new RegexParser().parse("(fo|foo)");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = sc.buildDFA(nfa, true);
    Map<DFA.DFAState, Boolean> cutMap = computePerStateCutMap(dfa, sc, nfa.getAcceptStates());

    boolean anyAcceptingCut =
        dfa.getAllStates().stream()
            .anyMatch(
                s -> s.accepting && !s.transitions.isEmpty() && Boolean.TRUE.equals(cutMap.get(s)));
    assertTrue(
        anyAcceptingCut,
        "(fo|foo) must have at least one priority-cut accepting state with outgoing transitions");
  }

  @Test
  void aPlus_acceptingState_isNotCut() throws Exception {
    RegexNode ast = new RegexParser().parse("(a)+");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = sc.buildDFA(nfa, true);
    Map<DFA.DFAState, Boolean> cutMap = computePerStateCutMap(dfa, sc, nfa.getAcceptStates());

    boolean anyAcceptingCut =
        dfa.getAllStates().stream()
            .anyMatch(
                s -> s.accepting && !s.transitions.isEmpty() && Boolean.TRUE.equals(cutMap.get(s)));
    assertFalse(
        anyAcceptingCut,
        "(a)+ must have NO priority-cut accepting state with outgoing transitions");
  }
}
