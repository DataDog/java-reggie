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

/** Verifies that ThompsonBuilder produces correct NFA structure for atomic groups. */
class ThompsonBuilderTest {

  private final RegexParser parser = new RegexParser();
  private final ThompsonBuilder builder = new ThompsonBuilder();

  @Test
  void atomicGroupProducesEntryAndExitStatesWithMatchingId() throws Exception {
    RegexNode ast = parser.parse("(?>ab)");
    NFA nfa = builder.build(ast, 0);

    List<NFA.NFAState> states = nfa.getStates();

    NFA.NFAState entryState =
        states.stream()
            .filter(s -> s.atomicEntry >= 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No atomicEntry state found"));

    NFA.NFAState exitState =
        states.stream()
            .filter(s -> s.atomicExit >= 0)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No atomicExit state found"));

    assertEquals(
        entryState.atomicEntry, exitState.atomicExit, "atomicEntry id must match atomicExit id");
  }

  @Test
  void twoAtomicGroupsGetDistinctIds() throws Exception {
    RegexNode ast = parser.parse("(?>a)(?>b)");
    NFA nfa = builder.build(ast, 0);

    List<NFA.NFAState> states = nfa.getStates();

    long entryIds =
        states.stream().filter(s -> s.atomicEntry >= 0).map(s -> s.atomicEntry).distinct().count();

    assertEquals(2, entryIds, "Two atomic groups must produce two distinct atomicEntry ids");
  }

  @Test
  void nonAtomicGroupProducesNoAtomicMarkers() throws Exception {
    RegexNode ast = parser.parse("(ab)");
    NFA nfa = builder.build(ast, 1);

    List<NFA.NFAState> states = nfa.getStates();

    boolean hasAtomicEntry = states.stream().anyMatch(s -> s.atomicEntry >= 0);
    boolean hasAtomicExit = states.stream().anyMatch(s -> s.atomicExit >= 0);

    assertFalse(hasAtomicEntry, "Capturing group must not produce atomicEntry states");
    assertFalse(hasAtomicExit, "Capturing group must not produce atomicExit states");
  }

  @Test
  void atomicGroupCountReflectsNumberOfAtomicGroups() throws Exception {
    RegexNode ast = parser.parse("(?>a)(?>b)(?>c)");
    NFA nfa = builder.build(ast, 0);

    assertEquals(
        3,
        nfa.getAtomicGroupCount(),
        "NFA.getAtomicGroupCount() must equal the number of atomic groups in the pattern");
  }
}
