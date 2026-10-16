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
 * JDK-differential check for DFA_UNROLLED find loops that continue after a failed run where the run
 * died instead of at the next position. Covers the start-state-and-loop-state shape the skip
 * applies to, under end conditions, and near shapes it must not apply to: a start class outside the
 * loop class ({@code [ab]c*$}) and more than one loop state.
 */
public class DeadSpanSkipTest {

  /** Pattern and the strategy it routes to, so each case keeps covering the intended engine. */
  private static final String[][] CASES = {
    {"(?m)\\w+$", "DFA_UNROLLED"},
    {"(?m)[a-z]+$", "DFA_UNROLLED"},
    {"(?m)a+$", "DFA_UNROLLED"},
    {"(?m)[ab][abc]*$", "DFA_UNROLLED"},
    {"(?m)[ab]c*$", "DFA_UNROLLED"},
    {"(?m)[ab][bc]*$", "DFA_UNROLLED"},
    {"(?m)\\w+-$", "DFA_UNROLLED"}
  };

  private static List<String> inputs() {
    List<String> inputs = new ArrayList<>(DotLineTerminatorTest.inputs());
    for (String s :
        new String[] {
          "ab", "ab ", "abc", "abc def", "abc def ", "a b c", "ab\n", "ab\r\n", "ab\rcd", "ab\ncd ",
          "aa a", "a\u0085b", "ab\u2028", "ab-", "ab- ", "ab-c", "acb", "bc", "abcc", "abd",
          "abd c", "key0=lorem ipsum 42 zz", "key0=lorem\nipsum 42\r\nzz", "a-b-"
        }) {
      inputs.add(s);
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
