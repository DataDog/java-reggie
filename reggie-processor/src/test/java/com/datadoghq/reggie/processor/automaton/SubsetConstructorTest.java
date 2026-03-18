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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.*;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

class SubsetConstructorTest {

  private final RegexParser parser = new RegexParser();
  private final ThompsonBuilder thompsonBuilder = new ThompsonBuilder();
  private final SubsetConstructor subsetConstructor = new SubsetConstructor();

  @Test
  void testSimpleLiteral() throws Exception {
    // Pattern: "abc"
    RegexNode ast = parser.parse("abc");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertNotNull(dfa.getStartState());
    assertFalse(dfa.getAcceptStates().isEmpty());
    assertTrue(dfa.getStateCount() > 0);
    assertTrue(dfa.getStateCount() < 10, "Simple literal should have few states");
  }

  @Test
  void testDigitPattern() throws Exception {
    // Pattern: "\d"
    RegexNode ast = parser.parse("\\d");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertEquals(2, dfa.getStateCount(), "\\d should produce 2 states (start + accept)");
    assertFalse(dfa.getStartState().accepting);
    assertEquals(1, dfa.getAcceptStates().size());
  }

  @Test
  void testPhonePattern() throws Exception {
    // Pattern: "\d{3}-\d{3}-\d{4}"
    RegexNode ast = parser.parse("\\d{3}-\\d{3}-\\d{4}");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertTrue(dfa.getStateCount() > 10, "Phone pattern should have multiple states");
    assertTrue(dfa.getStateCount() < 50, "Phone pattern should be unrollable");
    assertEquals(1, dfa.getAcceptStates().size());
  }

  @Test
  void testAlternation() throws Exception {
    // Pattern: "a|b|c"
    RegexNode ast = parser.parse("a|b|c");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertTrue(dfa.getStateCount() >= 2, "Alternation should have at least 2 states");
    assertTrue(dfa.getStateCount() < 10, "Simple alternation should be small");
  }

  @Test
  void testCharacterClass() throws Exception {
    // Pattern: "[a-z]+"
    RegexNode ast = parser.parse("[a-z]+");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertTrue(dfa.getStateCount() >= 2);
    assertFalse(dfa.getStartState().accepting, "Start state should not be accepting for +");
  }

  @Test
  void testOptionalQuantifier() throws Exception {
    // Pattern: "a*"
    RegexNode ast = parser.parse("a*");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertTrue(dfa.getStartState().accepting, "Start state should be accepting for a*");
  }

  @Test
  void testEmailPattern() throws Exception {
    // Pattern: "[a-z]+@[a-z]+"
    RegexNode ast = parser.parse("[a-z]+@[a-z]+");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertTrue(dfa.getStateCount() > 3);
    assertTrue(dfa.getStateCount() < 100);
  }

  @Test
  void testDFAStructure() throws Exception {
    // Pattern: "ab"
    RegexNode ast = parser.parse("ab");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    // Verify basic DFA properties
    assertNotNull(dfa.getStartState());
    assertFalse(dfa.getStartState().accepting);

    // All states should have valid IDs
    for (DFA.DFAState state : dfa.getAllStates()) {
      assertTrue(state.id >= 0);
      assertNotNull(state.transitions);
      assertNotNull(state.nfaStates);
    }

    // Accept states should actually be in the all states list
    for (DFA.DFAState accept : dfa.getAcceptStates()) {
      assertTrue(dfa.getAllStates().contains(accept));
      assertTrue(accept.accepting);
    }
  }

  @Test
  void testDisjointPartitioning() throws Exception {
    // Pattern: "[a-z][e-m]" - tests character set partitioning
    RegexNode ast = parser.parse("[a-z][e-m]");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    // Should have states for different character ranges
    assertTrue(dfa.getStateCount() >= 2);
  }

  @Test
  void testComplexPattern() throws Exception {
    // Pattern: "(a|b)*c"
    RegexNode ast = parser.parse("(a|b)*c");
    NFA nfa = thompsonBuilder.build(ast, 1);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    assertNotNull(dfa);
    assertTrue(dfa.getStateCount() >= 2);
    assertFalse(dfa.getStartState().accepting, "Should not accept empty string");
  }
}
