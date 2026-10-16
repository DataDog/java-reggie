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
 * JDK-differential check for the end DFA_UNROLLED's inline findBoundsFrom scan reports when the
 * scan stops in an accepting state reached from another accepting state (chains of accepting
 * states, the {@code .*} loop after {@code a.} in {@code (?s)a.*}).
 */
public class FindBoundsLongestEndTest {

  /** Pattern and the strategy it routes to, so each case keeps covering the intended engine. */
  private static final String[][] CASES = {
    {"(?s)a.*", "DFA_UNROLLED"},
    {"(?s)xa..*", "DFA_UNROLLED"}
  };

  private static final String[] INPUTS = {
    "a", "ab", "abc", "aa", "aaa", "aaaa", "aaaaa", "aaab", "xa", "xaa", "xaab", "xaaab", "xaaaa",
    "-aaa-", "-xaa-"
  };

  @Test
  void findBoundsAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    int[] bounds = new int[2];
    for (String[] c : CASES) {
      ReggieMatcher r = Reggie.compile(c[0]);
      Pattern j = Pattern.compile(c[0]);
      for (String in : INPUTS) {
        for (int start = 0; start <= in.length(); start++) {
          Matcher m = j.matcher(in);
          String jdk = m.find(start) ? m.start() + "-" + m.end() : "-";
          String reggie = r.findBoundsFrom(in, start, bounds) ? bounds[0] + "-" + bounds[1] : "-";
          if (!jdk.equals(reggie)) {
            mismatches.add(
                "/"
                    + c[0]
                    + "/ findBoundsFrom("
                    + start
                    + ") on \""
                    + in
                    + "\": jdk="
                    + jdk
                    + " reggie="
                    + reggie);
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
