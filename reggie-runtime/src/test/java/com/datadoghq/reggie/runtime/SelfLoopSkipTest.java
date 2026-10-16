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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for DFA_UNROLLED self-loops that matchesAtStart and findLongestMatchEnd
 * run as one scan to the first char leaving the loop: a single char ({@code indexOf}), the five
 * line terminators ({@link LineTerminators#indexOfTerminator}) or none (the loop runs to the input
 * end). Inputs place each exit char before, inside and after the loop's run, and mix Latin-1 with
 * UTF-16 strings and surrogate pairs.
 */
public class SelfLoopSkipTest {

  /** Pattern and the strategy it routes to, so each case keeps covering the intended engine. */
  private static final String[][] CASES = {
    {"(?m)^.+$", "DFA_UNROLLED"},
    {"a.+", "DFA_UNROLLED"},
    {"(?s)a.*", "DFA_UNROLLED"},
    {".*x", "DFA_UNROLLED"},
    {"(?s).*x", "DFA_UNROLLED"},
    {"[^\\u4e2d]+\\u4e2d", "DFA_UNROLLED"},
    {"[^\"]*\"", "DFA_UNROLLED"},
    {"\"[^\"]+\"", "DFA_UNROLLED"},
    {"a.*b", "DFA_UNROLLED"},
    {"(?m)a[^x]*$", "DFA_UNROLLED"}
  };

  private static final String[] EXITS = {
    "\n", "\r", "\u0085", "\u2028", "\u2029", "\r\n", "\"", "x", "b", "\u4e2d"
  };

  private static final String[] RUNS = {
    "", "a", "ab c", "\u00e9a\u00ff", "\u4e2d\u6587", "\ud83d\ude00a"
  };

  private static List<String> inputs() {
    List<String> inputs = new ArrayList<>(DotLineTerminatorTest.inputs());
    for (String e : EXITS) {
      for (String run : RUNS) {
        inputs.add(run + e);
        inputs.add(e + run);
        inputs.add("a" + run + e + run + "b");
        inputs.add("\"" + run + e + run + "\"x");
        inputs.add(run + e + e + run);
      }
    }
    return inputs;
  }

  private static String[] patterns() {
    String[] patterns = new String[CASES.length];
    for (int i = 0; i < CASES.length; i++) {
      patterns[i] = CASES[i][0];
    }
    return patterns;
  }

  @Test
  void agreesWithJdk() {
    assertEquals(List.of(), DotLineTerminatorTest.mismatches(patterns(), inputs()));
  }

  @Test
  void findBoundsAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    int[] bounds = new int[2];
    for (String p : patterns()) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : inputs()) {
        for (int start = 0; start <= in.length(); start++) {
          Matcher m = j.matcher(in);
          String jdk = m.find(start) ? m.start() + "-" + m.end() : "-";
          String reggie = r.findBoundsFrom(in, start, bounds) ? bounds[0] + "-" + bounds[1] : "-";
          if (!jdk.equals(reggie)) {
            mismatches.add(
                "/"
                    + p
                    + "/ findBoundsFrom("
                    + start
                    + ") on \""
                    + DotLineTerminatorTest.escape(in)
                    + "\": jdk="
                    + jdk
                    + " reggie="
                    + reggie);
            break;
          }
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void routesAsIntended() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String[] c : CASES) {
      String route = StrategyCorrectnessMetaTest.routeOf(c[0]).name();
      if (!route.equals(c[1])) {
        wrong.add(c[0] + " -> " + route + " (expected " + c[1] + ")");
      }
    }
    assertEquals(List.of(), wrong);
  }
}
