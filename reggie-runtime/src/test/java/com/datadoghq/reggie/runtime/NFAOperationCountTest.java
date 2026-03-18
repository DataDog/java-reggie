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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Analyze NFA complexity to understand why group extraction is so slow. */
public class NFAOperationCountTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void analyzeDigitPatternComplexity() throws Exception {
    String pattern = "(\\d+)";
    String input = "12345";

    // Build NFA
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 1);

    System.out.println("=== Pattern: " + pattern + " ===");
    System.out.println("Input: " + input);
    System.out.println("Input length: " + input.length());
    System.out.println("NFA states: " + nfa.getStates().size());
    System.out.println();

    // Estimate operations for NFA simulation
    System.out.println("Estimated NFA operations:");

    // For each input character, we need to:
    // 1. Process current states (epsilon closure)
    // 2. Take character transitions
    // 3. Compute next epsilon closure

    int charTransitions = 0;
    int epsilonTransitions = 0;
    int statesWithGroups = 0;

    for (NFA.NFAState state : nfa.getStates()) {
      charTransitions += state.getTransitions().size();
      epsilonTransitions += state.getEpsilonTransitions().size();
      if (state.enterGroup != null || state.exitGroup != null) {
        statesWithGroups++;
      }
    }

    System.out.println("  Total character transitions: " + charTransitions);
    System.out.println("  Total epsilon transitions: " + epsilonTransitions);
    System.out.println("  States with group markers: " + statesWithGroups);
    System.out.println();

    // Simulate what happens during matching
    System.out.println("Simulation trace:");
    System.out.println("  Initial: Start at state 0");
    System.out.println("  Epsilon closure: Follow epsilon transitions (with group markers)");

    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      System.out.println("  Char " + i + " ('" + c + "'):");
      System.out.println("    - Match character transitions");
      System.out.println("    - Clear current states (SparseSet.clear)");
      System.out.println("    - Compute epsilon closure on next states");
      System.out.println("    - Update group positions if needed");
    }

    System.out.println("  Final: Check if accept state reached");
    System.out.println();

    // The problem: epsilon closure is called many times
    // For pattern (\d+):
    // - State 0: enterGroup=1, has 1 char transition to State 1
    // - State 1: exitGroup=1, has 2 epsilon transitions (loop back, exit forward)
    // - State 2: accept
    //
    // For input "12345":
    // - Initial epsilon closure (processes enterGroup)
    // - For each of 5 digits:
    //   - Match transition from State 1
    //   - Epsilon closure (2 epsilons from State 1: one to loop, one to accept)
    //   - Update exitGroup position
    // - Total: 6 epsilon closures, each processing 2 epsilon transitions

    int estimatedEpsilonClosures = input.length() + 1; // Initial + one per char
    int avgEpsilonsPerClosure = epsilonTransitions / Math.max(1, nfa.getStates().size());

    System.out.println("Estimated operation counts:");
    System.out.println("  Epsilon closure calls: ~" + estimatedEpsilonClosures);
    System.out.println("  Epsilon transitions per closure: ~" + avgEpsilonsPerClosure);
    System.out.println(
        "  Total epsilon processing: ~" + (estimatedEpsilonClosures * avgEpsilonsPerClosure));
    System.out.println(
        "  SparseSet operations: " + (estimatedEpsilonClosures * 2) + " (clear + add)");
    System.out.println("  Group marker updates: " + (estimatedEpsilonClosures * statesWithGroups));
    System.out.println();

    // Now run actual benchmark
    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);

    // Warmup
    for (int i = 0; i < 10000; i++) {
      matcher.match(input);
    }

    // Measure
    long start = System.nanoTime();
    int iterations = 1_000_000;
    for (int i = 0; i < iterations; i++) {
      MatchResult result = matcher.match(input);
    }
    long duration = System.nanoTime() - start;
    double nsPerOp = duration / (double) iterations;

    System.out.println("Actual performance: " + String.format("%.2f", nsPerOp) + " ns/op");
    System.out.println();

    // Compare with JDK
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    // Warmup
    for (int i = 0; i < 10000; i++) {
      jdkPattern.matcher(input).matches();
    }

    // Measure
    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(input).matches();
    }
    duration = System.nanoTime() - start;
    double jdkNsPerOp = duration / (double) iterations;

    System.out.println("JDK performance: " + String.format("%.2f", jdkNsPerOp) + " ns/op");
    System.out.println(
        "Performance gap: " + String.format("%.2f", nsPerOp / jdkNsPerOp) + "x slower");
  }
}
