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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the epsilon-transition insertion order produced by ThompsonBuilder as a contract. The
 * ordered epsilon list is load-bearing: it encodes Perl thread priorities used by
 * SubsetConstructor.orderedEpsilonClosure for left-first alternation and greedy-match-first
 * semantics.
 */
class ThompsonBuilderPriorityTest {

  private final RegexParser parser = new RegexParser();
  private final ThompsonBuilder builder = new ThompsonBuilder();

  @Test
  void alternationLeftBranchHasHigherPriorityThanRightBranch() throws Exception {
    // why: if the epsilon order were reversed, "a|b" against "a" would prefer the b-branch thread,
    // producing wrong leftmost-first capture semantics in the tagged-DFA engine.
    RegexNode ast = parser.parse("a|b");
    NFA nfa = builder.build(ast, 0);

    NFA.NFAState start = nfa.getStartState();
    List<NFA.NFAState> epsilons = start.getEpsilonTransitions();

    // The alternation entry has exactly two epsilon targets (one per branch).
    assertEquals(2, epsilons.size(), "alternation entry must have exactly two epsilon transitions");

    // First epsilon leads to the a-branch: it must reach a character transition for 'a'.
    NFA.NFAState firstTarget = epsilons.get(0);
    assertTrue(
        leadsToCharTransition(firstTarget, 'a'), "first epsilon must lead to the left (a) branch");

    // Second epsilon leads to the b-branch.
    NFA.NFAState secondTarget = epsilons.get(1);
    assertTrue(
        leadsToCharTransition(secondTarget, 'b'),
        "second epsilon must lead to the right (b) branch");
  }

  @Test
  void greedyOptionalMatchFirstBeforeSkip() throws Exception {
    // why: if the skip epsilon were inserted before the try-match epsilon, "(a)?" would prefer the
    // zero-length path even when 'a' is present, breaking greedy semantics.
    RegexNode ast = parser.parse("(a)?");
    NFA nfa = builder.build(ast, 1);

    NFA.NFAState start = nfa.getStartState();
    List<NFA.NFAState> epsilons = start.getEpsilonTransitions();

    // After build(), start has: [0] = try-match (group entry), [1] = skip (accept state).
    assertEquals(2, epsilons.size(), "greedy ? entry must have exactly two epsilon transitions");

    // First epsilon is the try-match path: it reaches the group-entry state (enterGroup == 1).
    NFA.NFAState tryMatch = epsilons.get(0);
    assertTrue(
        isGroupEntry(tryMatch, 1) || leadsToGroupEntry(tryMatch, 1),
        "first epsilon must lead toward the group entry (try-match path)");

    // Second epsilon is the skip path: it reaches the accept state.
    NFA.NFAState skip = epsilons.get(1);
    assertTrue(
        nfa.getAcceptStates().contains(skip), "second epsilon must be the skip-to-accept path");
  }

  @Test
  void greedyStarMatchFirstBeforeSkip() throws Exception {
    // why: if the skip epsilon were inserted before try-match, "(a)*" would always prefer the
    // zero-repetition path, making the star behave like it never matches.
    RegexNode ast = parser.parse("(a)*");
    NFA nfa = builder.build(ast, 1);

    NFA.NFAState start = nfa.getStartState();
    List<NFA.NFAState> epsilons = start.getEpsilonTransitions();

    // After build(), start has: [0] = try-match (child entry), [1] = skip (accept state).
    assertEquals(2, epsilons.size(), "greedy * entry must have exactly two epsilon transitions");

    // First epsilon is the try-match path: ultimately reaches a character transition for 'a'.
    NFA.NFAState tryMatch = epsilons.get(0);
    assertTrue(
        leadsToCharTransition(tryMatch, 'a'),
        "first epsilon must lead toward the match path (try-match first)");

    // Second epsilon is the skip path: reaches the accept state directly.
    NFA.NFAState skip = epsilons.get(1);
    assertTrue(
        nfa.getAcceptStates().contains(skip), "second epsilon must be the skip-to-accept path");
  }

  // --- helpers ---

  /** Returns true if {@code state} has a character transition that includes {@code ch}. */
  private boolean hasCharTransition(NFA.NFAState state, char ch) {
    return state.getTransitions().stream().anyMatch(t -> t.chars.contains(ch));
  }

  /**
   * BFS: returns true if following epsilon transitions from {@code state} reaches a state with a
   * character transition for {@code ch}, without crossing any character transition first.
   */
  private boolean leadsToCharTransition(NFA.NFAState state, char ch) {
    java.util.Set<NFA.NFAState> visited = new java.util.HashSet<>();
    java.util.Queue<NFA.NFAState> queue = new java.util.ArrayDeque<>();
    queue.add(state);
    visited.add(state);
    while (!queue.isEmpty()) {
      NFA.NFAState s = queue.poll();
      if (hasCharTransition(s, ch)) return true;
      for (NFA.NFAState next : s.getEpsilonTransitions()) {
        if (visited.add(next)) queue.add(next);
      }
    }
    return false;
  }

  /** Returns true if {@code state} is the entry marker for group {@code n}. */
  private boolean isGroupEntry(NFA.NFAState state, int n) {
    return state.enterGroup != null && state.enterGroup == n;
  }

  /** BFS: returns true if an epsilon-reachable state is the entry marker for group {@code n}. */
  private boolean leadsToGroupEntry(NFA.NFAState state, int n) {
    java.util.Set<NFA.NFAState> visited = new java.util.HashSet<>();
    java.util.Queue<NFA.NFAState> queue = new java.util.ArrayDeque<>();
    queue.add(state);
    visited.add(state);
    while (!queue.isEmpty()) {
      NFA.NFAState s = queue.poll();
      if (isGroupEntry(s, n)) return true;
      for (NFA.NFAState next : s.getEpsilonTransitions()) {
        if (visited.add(next)) queue.add(next);
      }
    }
    return false;
  }
}
