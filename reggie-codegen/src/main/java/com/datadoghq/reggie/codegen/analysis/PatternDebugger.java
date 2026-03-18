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
package com.datadoghq.reggie.codegen.analysis;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;

/** Simple debug utility for analyzing pattern strategies. */
public class PatternDebugger {

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      System.err.println("Usage: java PatternDebugger <pattern>");
      System.exit(1);
    }

    String pattern = args[0];
    analyze(pattern);
  }

  public static void analyze(String pattern) throws Exception {
    System.out.println("Pattern: " + pattern);
    System.out.println();

    // Parse
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);

    // Build NFA
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 0);

    // Analyze
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult result = analyzer.analyzeAndRecommend();

    // Print results
    System.out.println("Strategy: " + result.strategy);
    System.out.println("NFA States: " + nfa.getStates().size());
    System.out.println("Uses BitSet: " + (nfa.getStates().size() <= 64));
    System.out.println();

    // Print strategy-specific details
    switch (result.strategy) {
      case STATELESS_LOOP:
        if (result.patternInfo instanceof PatternAnalyzer.StatelessPatternInfo) {
          PatternAnalyzer.StatelessPatternInfo info =
              (PatternAnalyzer.StatelessPatternInfo) result.patternInfo;
          System.out.println("Type: " + info.type);
          if (info.literalSuffix != null) {
            System.out.println("Literal Suffix: \"" + info.literalSuffix + "\"");
          }
          if (info.charset != null) {
            System.out.println("Charset: " + info.charset);
            System.out.println("Negated: " + info.negated);
            System.out.println("Min Reps: " + info.minReps);
            System.out.println("Max Reps: " + (info.maxReps == -1 ? "unbounded" : info.maxReps));
          }
        }
        break;

      case HYBRID_DFA_LOOKAHEAD:
        if (result.patternInfo instanceof PatternAnalyzer.HybridDFALookaheadInfo) {
          PatternAnalyzer.HybridDFALookaheadInfo info =
              (PatternAnalyzer.HybridDFALookaheadInfo) result.patternInfo;
          System.out.println("Assertion DFAs: " + info.assertionDFAs.size());
          for (java.util.Map.Entry<Integer, com.datadoghq.reggie.codegen.automaton.DFA> entry :
              info.assertionDFAs.entrySet()) {
            System.out.println(
                "  State "
                    + entry.getKey()
                    + ": "
                    + entry.getValue().getStateCount()
                    + " DFA states");
          }
        }
        break;

      case DFA_UNROLLED:
      case DFA_SWITCH:
        System.out.println("Pure DFA strategy");
        break;

      case OPTIMIZED_NFA:
        System.out.println("Fallback to NFA simulation");
        break;
    }
  }
}
