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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Verifies that C2's priority-correct tagged-DFA selection did not inflate state counts past the
 * SubsetConstructor ceiling (10 000 states, SubsetConstructor.java:193).
 *
 * <p>All patterns are from the C2 repro set plus the Track-1 repros in
 * SubsetConstructorPriorityTest.
 */
class TaggedDfaStateCountTest {

  private DFA buildDFA(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, groupCount);
    return new SubsetConstructor().buildDFA(nfa, true);
  }

  @Test
  void dotOptionalB() throws Exception {
    // measured: 4 states
    int count = buildDFA("(.)?b", 1).getStateCount();
    assertTrue(count <= 10, "(.)?b state count: " + count);
  }

  @Test
  void aOptionalB() throws Exception {
    // measured: 3 states
    int count = buildDFA("(a)?b", 1).getStateCount();
    assertTrue(count <= 10, "(a)?b state count: " + count);
  }

  @Test
  void foOrFoo() throws Exception {
    // measured: 4 states
    int count = buildDFA("(fo|foo)", 1).getStateCount();
    assertTrue(count <= 10, "(fo|foo) state count: " + count);
  }

  @Test
  void aOrAb() throws Exception {
    // measured: 2 states
    int count = buildDFA("(a|ab)", 1).getStateCount();
    assertTrue(count <= 10, "(a|ab) state count: " + count);
  }

  @Test
  void aOrAbThenCOrEmpty() throws Exception {
    // measured: 4 states
    int count = buildDFA("(a|ab)(c|)", 2).getStateCount();
    assertTrue(count <= 10, "(a|ab)(c|) state count: " + count);
  }

  @Test
  void aaOrAThenA() throws Exception {
    // measured: 4 states
    int count = buildDFA("(aa|a)a", 1).getStateCount();
    assertTrue(count <= 10, "(aa|a)a state count: " + count);
  }

  // Track-1 repros from SubsetConstructorPriorityTest

  @Test
  void track1_dotOptionalB() throws Exception {
    // measured: 4 states
    int count = buildDFA("(.)?b", 1).getStateCount();
    assertTrue(count <= 10, "Track-1 (.)?b state count: " + count);
  }

  @Test
  void track1_aaOrAThenA() throws Exception {
    // measured: 4 states
    int count = buildDFA("(aa|a)a", 1).getStateCount();
    assertTrue(count <= 10, "Track-1 (aa|a)a state count: " + count);
  }

  @Test
  void track1_aB() throws Exception {
    // measured: 3 states
    int count = buildDFA("(a)b", 1).getStateCount();
    assertTrue(count <= 10, "Track-1 (a)b state count: " + count);
  }
}
