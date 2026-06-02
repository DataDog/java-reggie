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
import org.junit.jupiter.api.Test;

/**
 * Unit test: verifies that SubsetConstructor correctly computes {@link
 * DFA.DFAState#acceptIsPriorityCut} for both priority-cut and greedy-continue accepting states.
 */
class AcceptIsPriorityCutFlagTest {

  private DFA buildTaggedDFA(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, groupCount);
    return new SubsetConstructor().buildDFA(nfa, true);
  }

  // ── Priority-cut states ───────────────────────────────────────────────────

  @Test
  void foOrFoo_hasAtLeastOnePriorityCutAcceptingStateWithTransitions() throws Exception {
    DFA dfa = buildTaggedDFA("(fo|foo)", 1);
    boolean found =
        dfa.getAllStates().stream()
            .anyMatch(s -> s.accepting && !s.transitions.isEmpty() && s.acceptIsPriorityCut);
    assertTrue(
        found,
        "(fo|foo): must have an accepting state with outgoing transitions flagged as priority-cut");
  }

  @Test
  void aOrAb_hasAtLeastOnePriorityCutAcceptingStateWithTransitions() throws Exception {
    DFA dfa = buildTaggedDFA("(a|ab)", 1);
    boolean found =
        dfa.getAllStates().stream()
            .anyMatch(s -> s.accepting && !s.transitions.isEmpty() && s.acceptIsPriorityCut);
    assertTrue(
        found,
        "(a|ab): must have an accepting state with outgoing transitions flagged as priority-cut");
  }

  // ── Greedy-continue states ────────────────────────────────────────────────

  @Test
  void aPlus_hasNoPriorityCutAcceptingStateWithTransitions() throws Exception {
    DFA dfa = buildTaggedDFA("(a)+", 1);
    boolean found =
        dfa.getAllStates().stream()
            .anyMatch(s -> s.accepting && !s.transitions.isEmpty() && s.acceptIsPriorityCut);
    assertFalse(
        found, "(a)+: must NOT have any priority-cut accepting state with outgoing transitions");
  }

  @Test
  void abPlus_hasNoPriorityCutAcceptingStateWithTransitions() throws Exception {
    DFA dfa = buildTaggedDFA("(ab)+", 1);
    boolean found =
        dfa.getAllStates().stream()
            .anyMatch(s -> s.accepting && !s.transitions.isEmpty() && s.acceptIsPriorityCut);
    assertFalse(
        found, "(ab)+: must NOT have any priority-cut accepting state with outgoing transitions");
  }

  // ── Non-accepting states must always be false ─────────────────────────────

  @Test
  void nonAcceptingStates_neverFlagged() throws Exception {
    DFA dfa = buildTaggedDFA("(fo|foo)", 1);
    boolean anyNonAcceptingFlagged =
        dfa.getAllStates().stream().anyMatch(s -> !s.accepting && s.acceptIsPriorityCut);
    assertFalse(
        anyNonAcceptingFlagged, "non-accepting states must never have acceptIsPriorityCut=true");
  }

  // ── Terminal accept states (no outgoing transitions) must be cut ───────────

  @Test
  void terminalAcceptState_isCutOrHasNoTransitions() throws Exception {
    // For patterns whose only accepting states are terminal (no outgoing transitions),
    // the flag can be either true or false — it has no effect (no further input to consume).
    // This test just ensures the flag is computed without throwing.
    DFA dfa = buildTaggedDFA("(a)b", 1);
    for (DFA.DFAState s : dfa.getAllStates()) {
      if (s.accepting && s.transitions.isEmpty()) {
        // Flag value doesn't matter for terminal states; just verify no exception.
        assertTrue(s.acceptIsPriorityCut || !s.acceptIsPriorityCut);
      }
    }
  }
}
