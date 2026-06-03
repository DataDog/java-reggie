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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.codegen.automaton.GlushkovAutomaton;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/**
 * Differential test for the bit-parallel Glushkov leftmost-longest simulation, using {@link
 * java.util.regex.Pattern} as the oracle. Only LONGEST-SAFE patterns (greedy / non-overlapping
 * alternation) are exercised here — these are exactly the patterns the engine is allowed to take;
 * priority-conflicting patterns are deferred to other strategies and are not this engine's concern.
 */
public class BitParallelGlushkovRuntimeTest {

  /**
   * Thin adapter so the test reads naturally while the runtime keeps DFATableRuntime-style params.
   */
  private record G(GlushkovAutomaton a) {
    boolean matches(CharSequence in) {
      return BitParallelGlushkovRuntime.matches(
          in,
          a.initial,
          a.accept,
          a.nullable,
          a.follow,
          a.asciiClasses,
          a.rangeStarts,
          a.rangeEnds,
          a.rangeClasses,
          a.entry);
    }

    int findFrom(CharSequence in, int from) {
      return BitParallelGlushkovRuntime.findFrom(
          in,
          from,
          a.initial,
          a.accept,
          a.nullable,
          a.follow,
          a.followReverse,
          a.asciiClasses,
          a.rangeStarts,
          a.rangeEnds,
          a.rangeClasses,
          a.entry);
    }

    int[] findBounds(CharSequence in, int from) {
      int[] bounds = new int[2];
      boolean found =
          BitParallelGlushkovRuntime.findBoundsFrom(
              in,
              from,
              bounds,
              a.initial,
              a.accept,
              a.nullable,
              a.follow,
              a.followReverse,
              a.asciiClasses,
              a.rangeStarts,
              a.rangeEnds,
              a.rangeClasses,
              a.entry);
      return found ? bounds : null;
    }
  }

  private static G g(String pattern) {
    try {
      NFA nfa = new ThompsonBuilder().build(new RegexParser().parse(pattern), 0);
      GlushkovAutomaton a = GlushkovAutomaton.from(nfa);
      assertNotNull(a, "pattern must be Glushkov-eligible: " + pattern);
      return new G(a);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // Longest-safe patterns: greedy quantifiers and non-prefix-overlapping alternations only.
  private static final List<String> PATTERNS =
      List.of(
          "ab",
          "a|b",
          "ab|cd",
          "ab|a", // longest == first here ("ab" wins both ways)
          "a*",
          "a+",
          "a?b",
          "[a-c]d",
          "[ab]c",
          "ab+c",
          "colou?r",
          "(?:ab)+",
          "a.*b",
          "[ab]{3}c[cd]{4}",
          "(?:a|b)*a(?:a|b){4}",
          "\\d+",
          "[a-z]{2,5}");

  private static final List<String> INPUTS =
      List.of(
          "ab",
          "a",
          "b",
          "cd",
          "abab",
          "aaa",
          "bbb",
          "color",
          "colour",
          "xabcdy",
          "aXbc4d",
          "  ab  ",
          "",
          "héllo",
          "a0é",
          "abbbc",
          "aaaabaaaa",
          "z",
          "123",
          "abc",
          "ababababaa",
          "aabbaa");

  @Test
  void leftmostLongest_agreesWithJdk_acrossPatternsAndInputs() {
    StringBuilder fails = new StringBuilder();
    for (String pat : PATTERNS) {
      G g = g(pat);
      java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
      for (String in : INPUTS) {
        // matches (full-input)
        boolean jdkMatches = jdk.matcher(in).matches();
        boolean rMatches = g.matches(in);
        if (rMatches != jdkMatches) {
          fails.append(
              String.format(
                  "matches /%s/ on %-12s jdk=%b reggie=%b%n", pat, q(in), jdkMatches, rMatches));
        }

        // find span
        Matcher m = jdk.matcher(in);
        boolean jdkFind = m.find();
        int[] rb = g.findBounds(in, 0);
        int rStart = g.findFrom(in, 0);
        if (jdkFind) {
          int js = m.start();
          int je = m.end();
          if (rb == null || rb[0] != js || rb[1] != je) {
            fails.append(
                String.format(
                    "findBounds /%s/ on %-12s jdk=[%d,%d) reggie=%s%n",
                    pat, q(in), js, je, rb == null ? "null" : ("[" + rb[0] + "," + rb[1] + ")")));
          }
          if (rStart != js) {
            fails.append(
                String.format("findFrom /%s/ on %-12s jdk=%d reggie=%d%n", pat, q(in), js, rStart));
          }
        } else {
          if (rb != null) {
            fails.append(
                String.format(
                    "findBounds /%s/ on %-12s jdk=no-match reggie=[%d,%d)%n",
                    pat, q(in), rb[0], rb[1]));
          }
          if (rStart != -1) {
            fails.append(
                String.format("findFrom /%s/ on %-12s jdk=-1 reggie=%d%n", pat, q(in), rStart));
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
