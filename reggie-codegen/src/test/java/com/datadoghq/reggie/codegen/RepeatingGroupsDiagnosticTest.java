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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

public class RepeatingGroupsDiagnosticTest {

  @Test
  public void diagnoseRepeatingGroupsStrategy() throws Exception {
    String pattern = "(a+|b)+";

    // Parse pattern
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);

    // Build NFA
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 1); // 1 group

    // Analyze strategy
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();

    System.out.println("\n=== REPEATING GROUPS DIAGNOSTIC ===");
    System.out.println("Pattern: " + pattern);
    System.out.println("Strategy: " + result.strategy);
    System.out.println(
        "DFA: " + (result.dfa != null ? result.dfa.getStateCount() + " states" : "null"));
    System.out.println("useTaggedDFA: " + result.useTaggedDFA);
    System.out.println("Group count: " + nfa.getGroupCount());

    if (result.dfa != null) {
      System.out.println("\nDFA States:");
      for (var state : result.dfa.getAllStates()) {
        System.out.println(
            "  State "
                + state.id
                + ": accepting="
                + state.accepting
                + ", transitions="
                + state.transitions.size());
        for (var entry : state.transitions.entrySet()) {
          var trans = entry.getValue();
          System.out.println(
              "    "
                  + entry.getKey()
                  + " -> State "
                  + trans.target.id
                  + " ["
                  + trans.tagOps.size()
                  + " tag ops]");
          for (var tagOp : trans.tagOps) {
            System.out.println("      " + tagOp);
          }
        }
      }
    }

    System.out.println("\nNFA States:");
    for (var state : nfa.getStates()) {
      System.out.println(
          "  State "
              + state.id
              + ": enterGroup="
              + state.enterGroup
              + ", exitGroup="
              + state.exitGroup
              + ", transitions="
              + state.getTransitions().size()
              + ", epsilon="
              + state.getEpsilonTransitions().size());
    }
  }
}
