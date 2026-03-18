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
package com.datadoghq.reggie.codegen;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.SubsetConstructor;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

public class TaggedDFAStructureTest {

  @Test
  public void testSimpleGroupHasTags() throws Exception {
    String pattern = "(fo|foo)";

    // Parse and build NFA
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 1);

    // Build DFA WITH tag computation
    SubsetConstructor constructor = new SubsetConstructor();
    DFA dfa = constructor.buildDFA(nfa, true); // computeTags = true

    System.out.println("\n=== Tagged DFA Structure for " + pattern + " ===");
    System.out.println("DFA has " + dfa.getStateCount() + " states");
    System.out.println("NFA has " + nfa.getGroupCount() + " groups\n");

    boolean foundTags = false;
    for (DFA.DFAState state : dfa.getAllStates()) {
      System.out.println("State " + state.id + " (accepting=" + state.accepting + "):");
      for (var entry : state.transitions.entrySet()) {
        DFA.DFATransition trans = entry.getValue();
        System.out.println("  " + entry.getKey() + " -> State " + trans.target.id);
        if (!trans.tagOps.isEmpty()) {
          foundTags = true;
          for (DFA.TagOperation tagOp : trans.tagOps) {
            System.out.println(
                "    TAG: "
                    + tagOp.type
                    + " for group "
                    + tagOp.groupId
                    + " (tagId="
                    + tagOp.tagId
                    + ", priority="
                    + tagOp.priority
                    + ")");
          }
        } else {
          System.out.println("    [NO TAGS]");
        }
      }
      System.out.println();
    }

    assertTrue(
        foundTags, "Expected to find tag operations on DFA transitions for pattern with groups");
  }
}
