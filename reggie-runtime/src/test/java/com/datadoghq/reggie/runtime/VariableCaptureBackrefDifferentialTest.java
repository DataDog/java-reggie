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
import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Anchored, quantified-backreference and captured-separator shapes on the backreference strategies.
 * Every input up to length 5 over {1, 2, x, a, \n, space} is checked against JDK, including group
 * spans.
 */
public class VariableCaptureBackrefDifferentialTest {

  private static final String[] PATTERNS = {
    "^(\\d+)x\\1",
    "(\\d+)x\\1$",
    "^(\\d+)x\\1$",
    "\\A(\\d+)x\\1",
    "(\\d+)x\\1\\Z",
    "(\\d+)x\\1\\z",
    "(?m)^(\\d+)x\\1",
    "(?m)(\\d+)x\\1$",
    "(\\d+)x\\1+",
    "(\\d+)x\\1{2}",
    "(\\d+)x\\1*",
    "(\\d+)x\\1?",
    "(\\d+)(x)\\1",
    "(\\d+)(x)+\\1",
    "(\\d+)(\\d)+\\1",
    "(\\d)+x\\1",
    "(.*)(\\d{2})+\\1",
    "(.+)(\\d+)\\1",
    "(.*)(\\d)\\1",
    "(.*)\\d+\\1",
    "(?s)(.*)\\d+\\1",
    "([\\s\\S]+)[0-9]+\\1",
    "(.+)\\s+\\1",
    "(\\w+)\\s+\\1",
    "(\\w+)[^\\w]+\\1",
    "(\\w+) \\1",
    "\\b(\\w+)\\s+\\1\\b",
    "a{1,3}(\\d+)x\\1",
    "\\d*(\\d+)x\\1",
    "a*a(\\d+)x\\1",
    "(a)(\\d+)x\\2",
    "(?:a|2)(\\d+)x\\1",
    "a?(\\d+)x\\1",
    "x^(\\d+)x\\1",
    "(?m)\\n^(\\d+)x\\1"
  };

  @Test
  void agreesWithJdkExhaustively() throws Exception {
    char[] alphabet = {'1', '2', 'x', 'a', '\n', ' '};
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    for (int i = 0; i < inputs.size() && inputs.get(i).length() < 5; i++) {
      for (char ch : alphabet) inputs.add(inputs.get(i) + ch);
    }
    List<String> mismatches = new ArrayList<>();
    for (String pattern : PATTERNS) {
      String route = StrategyCorrectnessMetaTest.routeOf(pattern).name();
      ReggieMatcher r;
      try {
        r = Reggie.compile(pattern);
      } catch (UnsupportedPatternException e) {
        continue; // refused: no wrong answers to check
      }
      Pattern j = Pattern.compile(pattern);
      int bad = 0;
      String first = null;
      for (String in : inputs) {
        Matcher jm = j.matcher(in);
        boolean jFound = jm.find();
        MatchResult rm = r.findMatch(in);
        if (r.matches(in) != j.matcher(in).matches()
            || r.find(in) != jFound
            || (rm != null) != jFound
            || (jFound && !spans(jm).equals(spans(rm)))) {
          if (first == null) first = in.replace("\n", "\\n");
          bad++;
        }
      }
      if (bad > 0) {
        mismatches.add(pattern + " [" + route + "] " + bad + " bad, first \"" + first + "\"");
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
