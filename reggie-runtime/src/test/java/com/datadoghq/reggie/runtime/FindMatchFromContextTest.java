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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategyResult;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.SubsetConstructor;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check of {@code findMatchFrom} group spans for the DFA_SWITCH, DFA_UNROLLED
 * (untagged) and ONEPASS_NFA generators, driven directly with patterns that read text around the
 * match (lookbehind before the match start, lookahead past the match end, line and string anchors).
 * The strategy is forced, independent of the analyzer's route.
 */
class FindMatchFromContextTest {

  private static final String[] SWITCH_PATTERNS = {
    "(?<=a)(x{1,3})", "(?<=a)(x)y", "(x{1,3})(?=b)", "(?<!a)(x)(y)",
  };

  // (x+)(y+): a state whose transitions lead to targets with different group actions
  private static final String[] UNROLLED_PATTERNS = {
    "(?<=a)(x{1,3})", "(?<=a)(x)y", "(?<!a)(x)(y)", "(x+)(y+)"
  };

  // ONEPASS_NFA evaluates anchors, not lookaround
  private static final String[] ONEPASS_PATTERNS = {
    "(?m)^(x)(y)", "(x)(y)\\Z", "(?m)(x)$", "\\A(x)y"
  };

  private static final String[] INPUTS = {
    "ax", "axx", "bx", "bxax", "xb", "xxb", "axy", "baxy", "bxy", "xyb", "axyb", "xy", "abx",
    "a\nbx", "x", "a\nxy", "xy\n", "x\nx"
  };

  @Test
  void dfaSwitchWithGroups() throws Exception {
    assertEquals(List.of(), diff(MatchingStrategy.DFA_SWITCH_WITH_GROUPS, SWITCH_PATTERNS));
  }

  @Test
  void dfaUnrolledWithGroups() throws Exception {
    assertEquals(List.of(), diff(MatchingStrategy.DFA_UNROLLED_WITH_GROUPS, UNROLLED_PATTERNS));
  }

  @Test
  void onePass() throws Exception {
    assertEquals(List.of(), diff(MatchingStrategy.ONEPASS_NFA, ONEPASS_PATTERNS));
  }

  private static List<String> diff(MatchingStrategy strategy, String[] patterns) throws Exception {
    List<String> mismatches = new ArrayList<>();
    for (String pattern : patterns) {
      ReggieMatcher r = compileForced(pattern, strategy);
      Pattern j = Pattern.compile(pattern);
      for (String in : INPUTS) {
        for (int from = 0; from <= in.length(); from++) {
          Matcher m = j.matcher(in);
          String expected = m.find(from) ? spans(m) : "-";
          MatchResult mr = r.findMatchFrom(in, from);
          String actual = mr == null ? "-" : spans(mr);
          if (!actual.equals(expected)) {
            mismatches.add(
                "/"
                    + pattern
                    + "/ on \""
                    + in
                    + "\" from "
                    + from
                    + ": jdk="
                    + expected
                    + " reggie="
                    + actual);
          }
        }
      }
    }
    return mismatches;
  }

  /** Generates {@code pattern} with {@code strategy} through RuntimeCompiler's code generation. */
  private static ReggieMatcher compileForced(String pattern, MatchingStrategy strategy)
      throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, Pattern.compile(pattern).matcher("").groupCount());
    DFA dfa =
        strategy == MatchingStrategy.ONEPASS_NFA
            ? null
            : new SubsetConstructor().buildDFAWithAssertions(nfa);
    Method gen =
        RuntimeCompiler.class.getDeclaredMethod(
            "generateBytecode",
            String.class,
            MatchingStrategyResult.class,
            NFA.class,
            RegexNode.class,
            boolean.class);
    gen.setAccessible(true);
    byte[] bytecode =
        (byte[])
            gen.invoke(null, pattern, new MatchingStrategyResult(strategy, dfa), nfa, ast, false);
    return MethodHandles.lookup()
        .defineHiddenClass(bytecode, true, MethodHandles.Lookup.ClassOption.NESTMATE)
        .lookupClass()
        .asSubclass(ReggieMatcher.class)
        .getDeclaredConstructor(String.class)
        .newInstance(pattern);
  }

  private static String spans(java.util.regex.MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append('[').append(m.start(g)).append(',').append(m.end(g)).append(')');
    }
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append('[').append(m.start(g)).append(',').append(m.end(g)).append(')');
    }
    return sb.toString();
  }
}
