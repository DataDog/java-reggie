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

import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Verifies NFA construction for atomic groups. AST nodes are constructed directly because the
 * parser does not yet support the {@code (?>...)} syntax.
 */
class ThompsonBuilderTest {

  private final RegexParser parser = new RegexParser();
  private final ThompsonBuilder builder = new ThompsonBuilder();

  /** Builds an atomic GroupNode wrapping {@code child}. */
  private static GroupNode atomic(RegexNode child) {
    return new GroupNode(child, 0, false, null, true);
  }

  /** Builds a literal LiteralNode for character {@code ch}. */
  private static LiteralNode lit(char ch) {
    return new LiteralNode(ch);
  }

  @Test
  void atomicGroupProducesEntryAndExitMarkers() throws Exception {
    // equivalent to (?>ab)
    RegexNode ast = atomic(new ConcatNode(java.util.List.of(lit('a'), lit('b'))));
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);

    boolean foundEntry = false;
    boolean foundExit = false;
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.atomicEntry == 0) foundEntry = true;
      if (state.atomicExit == 0) foundExit = true;
    }
    assertTrue(foundEntry, "atomic group must produce a state with atomicEntry=0");
    assertTrue(foundExit, "atomic group must produce a state with atomicExit=0");
  }

  @Test
  void atomicGroupCountIsOne() throws Exception {
    RegexNode ast = atomic(new ConcatNode(java.util.List.of(lit('a'), lit('b'))));
    NFA nfa = builder.build(ast, 0);

    assertEquals(1, nfa.getAtomicGroupCount(), "single atomic group must yield atomicGroupCount=1");
  }

  @Test
  void nestedAtomicGroupsGetDistinctIds() throws Exception {
    // outer (?>…) wraps inner (?>a) followed by 'b'
    RegexNode inner = atomic(lit('a'));
    RegexNode ast = atomic(new ConcatNode(java.util.List.of(inner, lit('b'))));
    NFA nfa = builder.build(ast, 0);

    boolean foundId0 = false;
    boolean foundId1 = false;
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.atomicEntry == 0 || state.atomicExit == 0) foundId0 = true;
      if (state.atomicEntry == 1 || state.atomicExit == 1) foundId1 = true;
    }
    assertTrue(foundId0, "first atomic group must use id 0");
    assertTrue(foundId1, "second atomic group must use id 1");
    assertEquals(2, nfa.getAtomicGroupCount(), "two atomic groups must yield atomicGroupCount=2");
  }

  @Test
  void nonAtomicGroupProducesNoAtomicMarkers() throws Exception {
    RegexNode ast = parser.parse("(ab)");
    NFA nfa = builder.build(ast, 1);

    for (NFA.NFAState state : nfa.getStates()) {
      assertEquals(-1, state.atomicEntry, "capturing group must not set atomicEntry");
      assertEquals(-1, state.atomicExit, "capturing group must not set atomicExit");
    }
    assertEquals(0, nfa.getAtomicGroupCount());
  }

  @Test
  void patternWithNoAtomicGroupsHasAtomicGroupCountZero() throws Exception {
    RegexNode ast = parser.parse("a+b*c?");
    NFA nfa = builder.build(ast, 0);

    assertEquals(0, nfa.getAtomicGroupCount());
  }

  @Test
  void atomicGroupEntryAndExitShareSameId() throws Exception {
    // equivalent to (?>x+)
    RegexNode xPlus = new QuantifierNode(lit('x'), 1, -1, true, false);
    RegexNode ast = atomic(xPlus);
    NFA nfa = builder.build(ast, 0);

    int entryId = -1;
    int exitId = -1;
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.atomicEntry >= 0) entryId = state.atomicEntry;
      if (state.atomicExit >= 0) exitId = state.atomicExit;
    }
    assertTrue(entryId >= 0, "must have an atomicEntry state");
    assertTrue(exitId >= 0, "must have an atomicExit state");
    assertEquals(entryId, exitId, "atomicEntry and atomicExit must share the same group id");
  }
}
