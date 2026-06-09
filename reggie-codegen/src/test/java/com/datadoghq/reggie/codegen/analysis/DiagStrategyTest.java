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
import org.junit.jupiter.api.Test;

class DiagStrategyTest {
  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, 0);
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    return analyzer.analyzeAndRecommend();
  }

  @Test
  void diagAbcRepeat100() throws Exception {
    PatternAnalyzer.MatchingStrategyResult result = analyze("(?:abc){100}");
    System.out.println(
        "(?:abc){100} -> strategy="
            + result.strategy
            + " dfa_states="
            + (result.dfa != null ? result.dfa.getStateCount() : "N/A"));
  }
}
