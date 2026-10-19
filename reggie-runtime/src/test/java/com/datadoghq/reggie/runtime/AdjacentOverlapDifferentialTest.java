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
 * Shapes where a later element can start on a character an earlier element consumes: the
 * VARIABLE_CAPTURE_BACKREF separator must give characters back to the backreference, and
 * overlapping concatenated quantified groups must not use the greedy SPECIALIZED_QUANTIFIED_GROUP
 * generator. Every input up to length 4 over {a, b, c, m, 5} is checked against JDK, including
 * group spans.
 */
public class AdjacentOverlapDifferentialTest {

  /** {pattern, expected route}. */
  private static final String[][] CASES = {
    {"([a-z]+)[^a-cx-z]+\\1", "VARIABLE_CAPTURE_BACKREF"},
    {"(.+)\\d+\\1", "VARIABLE_CAPTURE_BACKREF"},
    {"([ab]+)[bc]+\\1", "VARIABLE_CAPTURE_BACKREF"},
    {"([ab]*)[bc]\\1", "VARIABLE_CAPTURE_BACKREF"},
    {"([ab]*)[bc]{2}\\1", "VARIABLE_CAPTURE_BACKREF"},
    {"([ab]*)[bc]{0,2}\\1", "VARIABLE_CAPTURE_BACKREF"},
    {"([^a-cx-z]+)+([0-9]+)+", "BITSTATE_CAPTURE"},
    {"(a+)+(a+)+", "BITSTATE_CAPTURE"},
    {"([ab]+)+([bc])+", "BITSTATE_CAPTURE"},
    {"(a+)+(b+)*(a+)+", "BITSTATE_CAPTURE"},
    {"(a+)+(b+)+(a+)+", "BITSTATE_CAPTURE"},
    {"([^a-cx-z]+)+([a-c]+)+", "BITSTATE_CAPTURE"}
  };

  @Test
  void agreesWithJdkExhaustively() throws Exception {
    char[] alphabet = {'a', 'b', 'c', 'm', '5'};
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    for (int i = 0; i < inputs.size() && inputs.get(i).length() < 4; i++) {
      for (char ch : alphabet) inputs.add(inputs.get(i) + ch);
    }
    List<String> mismatches = new ArrayList<>();
    for (String[] c : CASES) {
      String pattern = c[0];
      String route = StrategyCorrectnessMetaTest.routeOf(pattern).name();
      if (!route.equals(c[1])) {
        mismatches.add(pattern + " -> " + route + " (expected " + c[1] + ")");
        continue;
      }
      ReggieMatcher r = Reggie.compile(pattern);
      Pattern j = Pattern.compile(pattern);
      for (String in : inputs) {
        Matcher jm = j.matcher(in);
        boolean jFound = jm.find();
        MatchResult rm = r.findMatch(in);
        if (r.matches(in) != j.matcher(in).matches()
            || r.find(in) != jFound
            || (rm != null) != jFound
            || (jFound && !spans(jm).equals(spans(rm)))) {
          mismatches.add(pattern + " on \"" + in + "\"");
          break;
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static List<Integer> spans(java.util.regex.MatchResult m) {
    List<Integer> s = new ArrayList<>();
    for (int g = 0; g <= m.groupCount(); g++) {
      s.add(m.start(g));
      s.add(m.end(g));
    }
    return s;
  }

  private static List<Integer> spans(MatchResult m) {
    List<Integer> s = new ArrayList<>();
    for (int g = 0; g <= m.groupCount(); g++) {
      s.add(m.start(g));
      s.add(m.end(g));
    }
    return s;
  }
}
