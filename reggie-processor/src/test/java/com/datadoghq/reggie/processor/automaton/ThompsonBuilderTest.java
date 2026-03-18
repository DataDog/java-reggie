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
package com.datadoghq.reggie.processor.automaton;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.*;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

class ThompsonBuilderTest {

  private final RegexParser parser = new RegexParser();
  private final ThompsonBuilder builder = new ThompsonBuilder();

  @Test
  void testLiteralNFA() throws Exception {
    RegexNode ast = parser.parse("a");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    assertNotNull(nfa.getStartState());
    assertEquals(1, nfa.getAcceptStates().size());
    assertEquals(0, nfa.getGroupCount());

    // Should have at least start, transition, accept states
    assertTrue(nfa.getStates().size() >= 3);
  }

  @Test
  void testConcatenationNFA() throws Exception {
    RegexNode ast = parser.parse("abc");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    assertTrue(nfa.getStates().size() > 3);
  }

  @Test
  void testAlternationNFA() throws Exception {
    RegexNode ast = parser.parse("a|b|c");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    // Alternation creates entry state with epsilon to each branch
    assertTrue(nfa.getStates().size() > 3);
  }

  @Test
  void testQuantifierStarNFA() throws Exception {
    RegexNode ast = parser.parse("a*");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    // Star creates loop structure
    assertTrue(nfa.getStates().size() >= 3);
  }

  @Test
  void testQuantifierPlusNFA() throws Exception {
    RegexNode ast = parser.parse("a+");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    assertTrue(nfa.getStates().size() >= 3);
  }

  @Test
  void testQuantifierQuestionNFA() throws Exception {
    RegexNode ast = parser.parse("a?");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    assertTrue(nfa.getStates().size() >= 3);
  }

  @Test
  void testCapturingGroup() throws Exception {
    RegexNode ast = parser.parse("(abc)");
    NFA nfa = builder.build(ast, 1);

    assertNotNull(nfa);
    assertEquals(1, nfa.getGroupCount());

    // Find states with group markers
    boolean foundEnter = false;
    boolean foundExit = false;
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.enterGroup != null && state.enterGroup == 1) {
        foundEnter = true;
      }
      if (state.exitGroup != null && state.exitGroup == 1) {
        foundExit = true;
      }
    }
    assertTrue(foundEnter, "Should have state marking group entry");
    assertTrue(foundExit, "Should have state marking group exit");
  }

  @Test
  void testBackreference() throws Exception {
    RegexNode ast = parser.parse("(a)\\1");
    NFA nfa = builder.build(ast, 1);

    assertNotNull(nfa);
    assertEquals(1, nfa.getGroupCount());

    // Find backreference state
    boolean foundBackref = false;
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.backrefCheck != null && state.backrefCheck == 1) {
        foundBackref = true;
        break;
      }
    }
    assertTrue(foundBackref, "Should have state with backreference check");
  }

  @Test
  void testAnchor() throws Exception {
    RegexNode ast = parser.parse("^abc$");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);

    // Find anchor states
    boolean foundStart = false;
    boolean foundEnd = false;
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.anchor == NFA.AnchorType.START) {
        foundStart = true;
      }
      if (state.anchor == NFA.AnchorType.END) {
        foundEnd = true;
      }
    }
    assertTrue(foundStart, "Should have START anchor");
    assertTrue(foundEnd, "Should have END anchor");
  }

  @Test
  void testComplexPattern() throws Exception {
    // Phone pattern
    RegexNode ast = parser.parse("\\d{3}-\\d{3}-\\d{4}");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    assertTrue(nfa.getStates().size() > 10);
    assertEquals(1, nfa.getAcceptStates().size());
  }

  @Test
  void testCharacterClass() throws Exception {
    RegexNode ast = parser.parse("[a-z]+");
    NFA nfa = builder.build(ast, 0);

    assertNotNull(nfa);
    // Should have states for the character class transition
    assertTrue(nfa.getStates().size() >= 3);
  }

  @Test
  void testNFAStructure() throws Exception {
    RegexNode ast = parser.parse("ab");
    NFA nfa = builder.build(ast, 0);

    // Verify basic NFA structure
    assertNotNull(nfa.getStartState());
    assertFalse(nfa.getAcceptStates().isEmpty());

    // Start state should not be an accept state
    assertFalse(nfa.getAcceptStates().contains(nfa.getStartState()));

    // All states should have valid IDs
    for (NFA.NFAState state : nfa.getStates()) {
      assertTrue(state.id >= 0);
    }
  }
}
