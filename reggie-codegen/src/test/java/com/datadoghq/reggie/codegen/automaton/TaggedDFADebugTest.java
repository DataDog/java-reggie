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

import com.datadoghq.reggie.codegen.ast.GroupNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.io.PrintWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Debug test to understand Tagged DFA construction for zero-length group matches. */
public class TaggedDFADebugTest {

  @Test
  public void debugZeroLengthGroupMatch() throws Exception {
    String pattern = "a(b*)";

    PrintWriter out = new PrintWriter("/tmp/tagged-dfa-debug.txt");

    // Parse pattern
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    out.println("Pattern: " + pattern);
    out.println("AST: " + ast);
    out.println();

    // Build NFA
    ThompsonBuilder builder = new ThompsonBuilder();
    // Count groups in AST
    int groupCount = countGroups(ast);
    NFA nfa = builder.build(ast, groupCount);
    out.println("NFA States: " + nfa.getStates().size());
    out.println("Group Count: " + nfa.getGroupCount());
    out.println();

    // Print NFA structure
    out.println("NFA Structure:");
    printNFAStructure(nfa, out);
    out.println();

    // Build Tagged DFA
    SubsetConstructor constructor = new SubsetConstructor();
    DFA dfa = constructor.buildDFA(nfa, true);
    out.println("DFA States: " + dfa.getAllStates().size());
    out.println();

    // Print DFA structure with tag operations
    out.println("DFA Structure (with Tags):");
    printDFAStructure(dfa, out);

    // Check what happens on transition 0->1
    out.println();
    out.println("=== ANALYSIS OF TRANSITION DFA 0 -> DFA 1 ===");
    DFA.DFAState state0 = dfa.getAllStates().stream().filter(s -> s.id == 0).findFirst().get();
    DFA.DFAState state1 = dfa.getAllStates().stream().filter(s -> s.id == 1).findFirst().get();

    out.println(
        "Source NFA states: "
            + state0.nfaStates.stream()
                .map(s -> String.valueOf(s.id))
                .reduce((a, b) -> a + ", " + b)
                .orElse(""));
    out.println(
        "Target NFA states: "
            + state1.nfaStates.stream()
                .map(s -> String.valueOf(s.id))
                .reduce((a, b) -> a + ", " + b)
                .orElse(""));

    // Check which NFA states in target have group markers
    out.println();
    out.println("Group markers in target NFA states:");
    for (NFA.NFAState nfaState : state1.nfaStates) {
      if (nfaState.enterGroup != null) {
        out.println("  State " + nfaState.id + ": ENTER GROUP " + nfaState.enterGroup);
      }
      if (nfaState.exitGroup != null) {
        out.println("  State " + nfaState.id + ": EXIT GROUP " + nfaState.exitGroup);
      }
    }

    out.close();
    System.out.println("Debug output written to /tmp/tagged-dfa-debug.txt");
  }

  private void printNFAStructure(NFA nfa, PrintWriter out) {
    out.println("Start State: " + nfa.getStartState().id);
    out.println(
        "Accept States: "
            + nfa.getAcceptStates().stream()
                .map(s -> String.valueOf(s.id))
                .reduce((a, b) -> a + ", " + b)
                .orElse("none"));
    out.println();

    for (NFA.NFAState state : nfa.getStates()) {
      out.println("State " + state.id + ":");

      if (state.enterGroup != null) {
        out.println("  ENTER GROUP " + state.enterGroup);
      }
      if (state.exitGroup != null) {
        out.println("  EXIT GROUP " + state.exitGroup);
      }

      // Character transitions
      for (NFA.Transition trans : state.getTransitions()) {
        out.println("  --[" + trans.chars + "]--> State " + trans.target.id);
      }

      // Epsilon transitions
      for (NFA.NFAState target : state.getEpsilonTransitions()) {
        out.println("  --[ε]--> State " + target.id);
      }

      out.println();
    }
  }

  private void printDFAStructure(DFA dfa, PrintWriter out) {
    out.println("Start State: " + dfa.getStartState().id);
    out.println();

    for (DFA.DFAState state : dfa.getAllStates()) {
      out.println("DFA State " + state.id + ":");
      out.print("  NFA States: {");
      String nfaStates =
          state.nfaStates.stream()
              .map(s -> String.valueOf(s.id))
              .sorted()
              .reduce((a, b) -> a + ", " + b)
              .orElse("");
      out.println(nfaStates + "}");
      out.println("  Accepting: " + state.accepting);

      if (!state.groupActions.isEmpty()) {
        out.println("  Group Actions:");
        for (DFA.GroupAction action : state.groupActions) {
          out.println(
              "    - Group "
                  + action.groupId
                  + " "
                  + action.type
                  + " (priority="
                  + action.priority
                  + ")");
        }
      }

      // Print transitions with tag operations
      for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
        CharSet chars = entry.getKey();
        DFA.DFATransition trans = entry.getValue();
        out.println("  --[" + chars + "]--> DFA State " + trans.target.id);
        if (trans.tagOps != null && !trans.tagOps.isEmpty()) {
          out.println("    Tag Operations:");
          for (DFA.TagOperation tagOp : trans.tagOps) {
            out.println(
                "      - Tag "
                    + tagOp.tagId
                    + " ("
                    + tagOp.type
                    + " group "
                    + tagOp.groupId
                    + ", priority="
                    + tagOp.priority
                    + ")");
          }
        }
      }

      out.println();
    }
  }

  private int countGroups(RegexNode node) {
    if (node instanceof GroupNode) {
      GroupNode gn = (GroupNode) node;
      return 1 + countGroups(gn.child);
    }
    // For other node types, recursively count
    // This is a simplified version - full implementation would need to handle all node types
    return 0;
  }
}
