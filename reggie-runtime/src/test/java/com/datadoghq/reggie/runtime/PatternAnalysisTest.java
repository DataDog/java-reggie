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
package com.datadoghq.reggie.runtime;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/** Analyze patterns to understand strategy selection and NFA complexity. */
public class PatternAnalysisTest {

  @Test
  public void analyzeDigitPattern() throws Exception {
    analyzePattern("(\\d+)", "Simple digit pattern");
  }

  @Test
  public void analyzePhonePattern() throws Exception {
    analyzePattern("(\\d{3})-(\\d{3})-(\\d{4})", "Phone number pattern");
  }

  private void analyzePattern(String pattern, String description) throws Exception {
    System.out.println("\n=== Analyzing: " + description + " ===");
    System.out.println("Pattern: " + pattern);

    // Parse and build NFA
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);

    int groupCount = countGroups(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, groupCount);

    System.out.println("Group count: " + groupCount);
    System.out.println("NFA state count: " + nfa.getStates().size());
    System.out.println("Start state: " + nfa.getStartState().id);
    System.out.println(
        "Accept states: " + nfa.getAcceptStates().stream().map(s -> String.valueOf(s.id)).toList());

    // Analyze strategy selection
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();

    System.out.println("Strategy: " + result.strategy);
    if (result.dfa != null) {
      System.out.println("DFA state count: " + result.dfa.getStateCount());
    }

    // Check if hybrid would be used
    if (groupCount > 0 && result.strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA) {
      PatternAnalyzer.MatchingStrategyResult dfaResult = analyzer.analyzeAndRecommend(true);
      System.out.println("Would use hybrid mode: " + (dfaResult.dfa != null));
      if (dfaResult.dfa != null) {
        System.out.println("  DFA strategy: " + dfaResult.strategy);
        System.out.println("  DFA states: " + dfaResult.dfa.getStateCount());
      }
    }

    // Print NFA structure details
    System.out.println("\nNFA Structure:");
    for (NFA.NFAState state : nfa.getStates()) {
      System.out.print("  State " + state.id + ":");
      if (state.enterGroup != null) System.out.print(" enterGroup=" + state.enterGroup);
      if (state.exitGroup != null) System.out.print(" exitGroup=" + state.exitGroup);
      if (!state.getTransitions().isEmpty()) {
        System.out.print(" transitions=" + state.getTransitions().size());
      }
      if (!state.getEpsilonTransitions().isEmpty()) {
        System.out.print(" epsilons=" + state.getEpsilonTransitions().size());
      }
      System.out.println();
    }
  }

  private int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '(' && i + 1 < pattern.length() && pattern.charAt(i + 1) != '?') {
        count++;
      }
    }
    return count;
  }
}
