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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for DFA_UNROLLED accepting states whose end-class condition ({@code $},
 * {@code \Z}, {@code \z}, multiline {@code $}) is checked where the state is left without a
 * transition instead of on every entry (matchesAtStart, linStep, findLongestMatchEnd), and whose
 * match end matchesAtStart then reports to findMatchFrom and findBoundsFrom. Inputs place each
 * java.util.regex line terminator before, after and between the consumed runs; the long inputs make
 * the find loop switch to {@link DFALinearFind}, which runs the states through linStep.
 */
public class DeferredEndAcceptanceTest {

  /** Pattern and the strategy it routes to, so each case keeps covering the intended engine. */
  private static final String[][] CASES = {
    {"(?m)^.+$", "DFA_UNROLLED"},
    {"(?m)\\w+$", "DFA_UNROLLED"},
    {"(?m)a+$", "DFA_UNROLLED"},
    {"(?m)\\d$", "DFA_UNROLLED"},
    {"x.*$", "DFA_UNROLLED"},
    {"a\\w+$", "DFA_UNROLLED"},
    {"a\\w+\\Z", "DFA_UNROLLED"},
    {"a\\w+\\z", "DFA_UNROLLED"},
    {"(?:ab|b)+$", "DFA_UNROLLED"},
    {"(?m)[^x]+$", "DFA_UNROLLED"},
    {"(?m)\\s+$", "DFA_UNROLLED"}
  };

  private static final String[] TERMINATORS = {"\n", "\r", "\u0085", "\u2028", "\u2029", "\r\n"};

  private static List<String> inputs() {
    List<String> inputs = new ArrayList<>(DotLineTerminatorTest.inputs());
    for (String t : TERMINATORS) {
      inputs.add("ab" + t + t);
      inputs.add("ab " + t);
      inputs.add("a1 b2" + t + "x");
      inputs.add(t + "ab");
    }
    return inputs;
  }

  private static List<String> longInputs() {
    StringBuilder word = new StringBuilder();
    for (int i = 0; i < 100; i++) word.append(i % 3 == 0 ? 'a' : 'b');
    List<String> inputs = new ArrayList<>();
    for (String t : TERMINATORS) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 20; i++) sb.append(word).append(' ');
      inputs.add(sb.toString() + word + t);
      inputs.add(sb.toString() + word + t + " x");
      inputs.add(sb.toString() + "x" + t + word);
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
  void agreesWithJdkOnLinearFind() {
    List<String> mismatches = new ArrayList<>();
    for (String p : patterns()) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : longInputs()) {
        java.util.regex.Matcher m = j.matcher(in);
        String jdk = m.find() ? m.start() + "-" + m.end() : "-";
        MatchResult fr = r.findMatch(in);
        String reggie = fr == null ? "-" : fr.start() + "-" + fr.end();
        int jdkStart = jdk.equals("-") ? -1 : m.start();
        if (!jdk.equals(reggie) || r.findFrom(in, 0) != jdkStart) {
          mismatches.add(
              "/"
                  + p
                  + "/ on \""
                  + DotLineTerminatorTest.escape(in)
                  + "\": jdk="
                  + jdk
                  + " reggie="
                  + reggie
                  + "("
                  + r.findFrom(in, 0)
                  + ")");
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void findBoundsAgreesWithJdk() {
    List<String> inputs = inputs();
    inputs.addAll(longInputs());
    List<String> mismatches = new ArrayList<>();
    int[] bounds = new int[2];
    for (String p : patterns()) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : inputs) {
        for (int start = 0; start <= in.length(); start++) {
          java.util.regex.Matcher m = j.matcher(in);
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
