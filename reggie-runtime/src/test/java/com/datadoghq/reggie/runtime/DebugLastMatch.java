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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.*;
import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.*;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DebugLastMatch {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void debugLastMatch() throws Exception {
    String pattern = "(a)+";
    System.out.println("Pattern: " + pattern);

    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    System.out.println("AST: " + ast);

    int groupCount = 1;
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, groupCount);
    System.out.println("NFA group count: " + nfa.getGroupCount());

    // Check strategy
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    PatternAnalyzer.MatchingStrategyResult strategyResult = analyzer.analyzeAndRecommend();
    System.out.println("Strategy: " + strategyResult.strategy);
    System.out.println("useTaggedDFA: " + strategyResult.useTaggedDFA);
    System.out.println("usePosixLastMatch: " + strategyResult.usePosixLastMatch);

    // Run match
    ReggieMatcher m = Reggie.compile(pattern);
    MatchResult result = m.match("aaa");
    if (result != null) {
      System.out.println("Match found!");
      System.out.println(
          "  Group 0: '" + result.group(0) + "' [" + result.start(0) + ", " + result.end(0) + "]");
      System.out.println(
          "  Group 1: '" + result.group(1) + "' [" + result.start(1) + ", " + result.end(1) + "]");
    } else {
      System.out.println("No match!");
    }
  }
}
