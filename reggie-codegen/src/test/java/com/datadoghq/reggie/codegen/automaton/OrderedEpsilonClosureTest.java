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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OrderedEpsilonClosureTest {

  private final SubsetConstructor sc = new SubsetConstructor();

  @Test
  void alternationOrdering() {
    // entry → branchA first, then → branchB: left-branch must come first
    NFA.NFAState entry = new NFA.NFAState(0);
    NFA.NFAState branchA = new NFA.NFAState(1);
    NFA.NFAState branchB = new NFA.NFAState(2);
    entry.addEpsilonTransition(branchA);
    entry.addEpsilonTransition(branchB);

    List<NFA.NFAState> result = sc.orderedEpsilonClosure(entry);

    assertEquals(List.of(entry, branchA, branchB), result);
  }

  @Test
  void firstOccurrenceWins() {
    // s0 → s1 → s2, and also s0 → s2: s2 must appear only once, at the position reached via s1
    NFA.NFAState s0 = new NFA.NFAState(0);
    NFA.NFAState s1 = new NFA.NFAState(1);
    NFA.NFAState s2 = new NFA.NFAState(2);
    s0.addEpsilonTransition(s1);
    s0.addEpsilonTransition(s2); // direct shortcut, lower priority than via s1
    s1.addEpsilonTransition(s2);

    List<NFA.NFAState> result = sc.orderedEpsilonClosure(s0);

    // s2 appears once, reached first via s1 (DFS)
    assertEquals(List.of(s0, s1, s2), result);
    assertEquals(3, result.size()); // no duplicates
  }

  @Test
  void cycleSafety() {
    // s0 → s1 → s0 (cycle): must terminate without StackOverflowError
    NFA.NFAState s0 = new NFA.NFAState(0);
    NFA.NFAState s1 = new NFA.NFAState(1);
    s0.addEpsilonTransition(s1);
    s1.addEpsilonTransition(s0);

    List<NFA.NFAState> result = sc.orderedEpsilonClosure(s0);

    assertEquals(List.of(s0, s1), result);
  }

  @Test
  void greedyQuantifierOrdering() {
    // Simulates (a)? : entry → matchBranch first (greedy), then → skipBranch
    NFA.NFAState entry = new NFA.NFAState(0);
    NFA.NFAState matchBranch = new NFA.NFAState(1);
    NFA.NFAState skipBranch = new NFA.NFAState(2);
    entry.addEpsilonTransition(matchBranch); // greedy: try match first
    entry.addEpsilonTransition(skipBranch); // skip (zero-width) comes after

    List<NFA.NFAState> result = sc.orderedEpsilonClosure(entry);

    // matchBranch must precede skipBranch to honour greedy priority
    assertTrue(result.indexOf(matchBranch) < result.indexOf(skipBranch));
    assertTrue(result.get(0) == entry);
  }
}
