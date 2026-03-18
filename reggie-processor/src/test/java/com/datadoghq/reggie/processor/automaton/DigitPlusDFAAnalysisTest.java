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

/** Analyzes DFA structure for pattern \d+ to understand why bytecode has 2 loops. */
class DigitPlusDFAAnalysisTest {

  private final RegexParser parser = new RegexParser();
  private final ThompsonBuilder thompsonBuilder = new ThompsonBuilder();
  private final SubsetConstructor subsetConstructor = new SubsetConstructor();

  @Test
  void analyzeDFAStructureForDigitPlus() throws Exception {
    // Pattern: "\d+"
    RegexNode ast = parser.parse("\\d+");
    NFA nfa = thompsonBuilder.build(ast, 0);
    DFA dfa = subsetConstructor.buildDFA(nfa);

    System.out.println("=== DFA Analysis for \\d+ ===");
    System.out.println("Total states: " + dfa.getStateCount());
    System.out.println("Accept states: " + dfa.getAcceptStates().size());
    System.out.println("Start state accepting: " + dfa.getStartState().accepting);
    System.out.println();

    // Print detailed state information
    for (DFA.DFAState state : dfa.getAllStates()) {
      System.out.println("State " + state.id + ":");
      System.out.println("  Accepting: " + state.accepting);
      System.out.println("  Transitions: " + state.transitions.size());

      for (var entry : state.transitions.entrySet()) {
        CharSet charset = entry.getKey();
        DFA.DFATransition transition = entry.getValue();
        System.out.println("    " + charSetToString(charset) + " -> State " + transition.target.id);

        // Check for self-loops
        if (transition.target.id == state.id) {
          System.out.println("      ^^^ SELF-LOOP!");
        }
      }
      System.out.println();
    }

    // Analyze the structure
    assertNotNull(dfa);
    System.out.println("Expected structure for \\d+:");
    System.out.println("  State 0: Non-accepting start, transitions on [0-9] to State 1");
    System.out.println("  State 1: Accepting, self-loop on [0-9]");
    System.out.println();

    System.out.println("Actual state count: " + dfa.getStateCount());
    if (dfa.getStateCount() > 2) {
      System.out.println(
          "WARNING: More than 2 states! This explains the nested loops in bytecode.");
    }
  }

  private String charSetToString(CharSet charset) {
    if (charset.isSingleChar()) {
      char c = charset.getSingleChar();
      return "'" + (c >= 32 && c <= 126 ? c : "\\u" + String.format("%04x", (int) c)) + "'";
    } else if (charset.isSimpleRange()) {
      CharSet.Range range = charset.getSimpleRange();
      return "[" + range.start + "-" + range.end + "]";
    } else {
      StringBuilder sb = new StringBuilder("[");
      for (CharSet.Range range : charset.getRanges()) {
        if (sb.length() > 1) sb.append(",");
        sb.append(range.start).append("-").append(range.end);
      }
      sb.append("]");
      return sb.toString();
    }
  }

  @Test
  void compareToSimplePatterns() throws Exception {
    String[] patterns = {"\\d", "\\d+", "\\d*", "a+", "[0-9]+"};

    System.out.println("=== Comparing DFA state counts ===");
    for (String pattern : patterns) {
      RegexNode ast = parser.parse(pattern);
      NFA nfa = thompsonBuilder.build(ast, 0);
      DFA dfa = subsetConstructor.buildDFA(nfa);

      System.out.println(
          String.format(
              "%-10s : %d states, start accepting: %s",
              pattern, dfa.getStateCount(), dfa.getStartState().accepting));
    }
  }
}
