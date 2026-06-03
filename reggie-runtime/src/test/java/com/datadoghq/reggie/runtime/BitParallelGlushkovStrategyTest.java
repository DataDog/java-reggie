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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * End-to-end validation of the BITPARALLEL_GLUSHKOV strategy: each pattern is confirmed to route to
 * the strategy, then the compiled matcher is checked against {@link java.util.regex.Pattern} across
 * a broad input battery. These patterns are leftmost-longest-safe (greedy, alternation-free), the
 * only class the analyzer routes to this engine.
 */
public class BitParallelGlushkovStrategyTest {

  // Each must route to BITPARALLEL_GLUSHKOV: a large/exploding DFA with a small (<=63-pos) NFA.
  private static final List<String> PATTERNS =
      List.of(
          ".*a.{9}", // DFA built (~513 states)
          ".*a.{25}", // determinization explosion (>10K) — alternation-free
          ".*x.{10}y",
          "a.*b.{15}",
          ".*[0-9].{12}");

  private static List<String> inputs() {
    List<String> in = new ArrayList<>();
    in.add("");
    in.add("a123456789");
    in.add("zza123456789zz");
    in.add("nomatchhere");
    in.add("xa12345678é");
    in.add("a".repeat(40));
    in.add("x".repeat(5) + "a" + "y".repeat(40));
    in.add("a" + "0123456789".repeat(4));
    in.add("....x0123456789y....");
    in.add("abABZ9 .,?".repeat(6));
    in.add("a987654321b" + "c".repeat(20));
    in.add("ééééa123456789ÿÿ");
    in.add("the quick brown fox jumps over a lazy dog 123456789");
    in.add("0".repeat(30));
    in.add("nope");
    return in;
  }

  @Test
  void glushkovRoutedPatterns_agreeWithJdk() throws Exception {
    StringBuilder fails = new StringBuilder();
    for (String pat : PATTERNS) {
      PatternAnalyzer.MatchingStrategy route = StrategyCorrectnessMetaTest.routeOf(pat);
      assertEquals(
          PatternAnalyzer.MatchingStrategy.BITPARALLEL_GLUSHKOV,
          route,
          "pattern must route to BITPARALLEL_GLUSHKOV: " + pat);

      ReggieMatcher reggie = Reggie.compile(pat);
      Pattern jdk = Pattern.compile(pat);

      for (String in : inputs()) {
        // matches (whole input)
        boolean jm = Pattern.matches(pat, in);
        boolean rm = reggie.matches(in);
        if (jm != rm) {
          fails.append(String.format("matches /%s/ %s: jdk=%b reggie=%b%n", pat, q(in), jm, rm));
        }

        // find span + start
        Matcher m = jdk.matcher(in);
        boolean jf = m.find();
        int rStart = reggie.findFrom(in, 0);
        MatchResult rMatch = reggie.findMatch(in);
        if (jf) {
          if (rStart != m.start()) {
            fails.append(
                String.format(
                    "findFrom /%s/ %s: jdk=%d reggie=%d%n", pat, q(in), m.start(), rStart));
          }
          if (rMatch == null || rMatch.start() != m.start() || rMatch.end() != m.end()) {
            fails.append(
                String.format(
                    "findMatch /%s/ %s: jdk=[%d,%d) reggie=%s%n",
                    pat,
                    q(in),
                    m.start(),
                    m.end(),
                    rMatch == null ? "null" : ("[" + rMatch.start() + "," + rMatch.end() + ")")));
          }
        } else {
          if (rStart != -1) {
            fails.append(String.format("findFrom /%s/ %s: jdk=-1 reggie=%d%n", pat, q(in), rStart));
          }
          if (rMatch != null) {
            fails.append(
                String.format(
                    "findMatch /%s/ %s: jdk=null reggie=[%d,%d)%n",
                    pat, q(in), rMatch.start(), rMatch.end()));
          }
        }
      }
    }
    assertEquals("", fails.toString(), "\n" + fails);
  }

  private static String q(String s) {
    return "\"" + s + "\"";
  }
}
