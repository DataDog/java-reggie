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

import static com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy.LINEAR_BACKREFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.Reggie;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * LINEAR_BACKREFERENCE with every non-multiline anchor kind, against JDK, including group spans of
 * match() and matchBounded().
 */
class LinearBackrefAnchorDifferentialTest {

  private static final String[] PATTERNS = {
    "(\\w)\\1\\Z",
    "(\\w)\\1\\z",
    "\\A(\\w)\\1",
    "x(\\w)\\1\\Z",
    "\\A(a)\\1\\z",
    "(\\w)\\1$",
    "^(\\w)\\1"
  };

  @Test
  void routes() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String p : PATTERNS) {
      var s = StrategyCorrectnessMetaTest.routeOf(p);
      if (s != LINEAR_BACKREFERENCE) wrong.add(p + " -> " + s);
    }
    assertEquals(List.of(), wrong);
  }

  /** {@code \K} (not supported by JDK) moves the reported match start; groups keep their spans. */
  @Test
  void resetMatchInBackrefPattern() {
    assertEquals("2,20,1", spans(Reggie.compile("(a)\\1\\K").findMatch("aa")));
    assertEquals("4,52,3", spans(Reggie.compile("b(a)\\1\\Kc").findMatch("xbaac")));
  }

  @Test
  void agreesWithJdk() {
    List<String> inputs =
        TrailingLookaheadDifferentialTest.inputs(new char[] {'a', 'x', '\n', '\r'}, 5);
    List<String> failures = new ArrayList<>();
    for (String p : PATTERNS) {
      String f;
      try {
        f = check(p, inputs);
      } catch (RuntimeException e) {
        f = e.toString();
      }
      if (f != null) failures.add("/" + p + "/ " + f);
    }
    assertEquals(List.of(), failures);
  }

  /**
   * The linear engine never gives characters back, so a variable quantifier anywhere must keep a
   * pattern off LINEAR_BACKREFERENCE ({@code (a).*\1} on "aa" used to fail).
   */
  @Test
  void variableQuantifiersLeaveLinearRoute() throws Exception {
    List<String> inputs = TrailingLookaheadDifferentialTest.inputs(new char[] {'a', 'b', 'x'}, 5);
    List<String> failures = new ArrayList<>();
    for (String p :
        new String[] {
          "a+(a)\\1",
          "a*(a)\\1",
          "(a)a*\\1",
          "(a)b*a?\\1",
          "(a)b?a?\\1",
          "([ab])[ab]*\\1",
          "([ab]{2}).*\\1",
          "(a).*\\1b",
          "(a).+\\1"
        }) {
      if (StrategyCorrectnessMetaTest.routeOf(p) == LINEAR_BACKREFERENCE) {
        failures.add("/" + p + "/ routes to LINEAR_BACKREFERENCE");
      }
      String f = check(p, inputs);
      if (f != null) failures.add("/" + p + "/ " + f);
    }
    // A capturing group under a repeat: JDK keeps the backed-off iteration's span (Perl does not),
    // so only the match boundaries are compared.
    for (String p : new String[] {"(?:(a))+\\1", "(?:(a){1,2})+\\1", "(?:(a)b)+\\1"}) {
      if (StrategyCorrectnessMetaTest.routeOf(p) == LINEAR_BACKREFERENCE) {
        failures.add("/" + p + "/ routes to LINEAR_BACKREFERENCE");
      }
      ReggieMatcher r = Reggie.compile(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : inputs) {
        Matcher m = jdk.matcher(in);
        String exp = m.find() ? m.start() + "," + m.end() : "null";
        MatchResult got = r.findMatch(in);
        if (!exp.equals(got == null ? "null" : got.start() + "," + got.end())) {
          failures.add("/" + p + "/ findMatch on \"" + in + "\"");
        }
        if (jdk.matcher(in).matches() != r.matches(in)) {
          failures.add("/" + p + "/ matches on \"" + in + "\"");
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  private static String check(String p, List<String> inputs) {
    ReggieMatcher r = Reggie.compile(p);
    Pattern jdk = Pattern.compile(p);
    for (String in : inputs) {
      String ctx = " on \"" + in.replace("\n", "\\n").replace("\r", "\\r") + "\"";
      String f = TrailingLookaheadDifferentialTest.divergence(r, jdk, in);
      if (f != null) return f + ctx;
      Matcher m = jdk.matcher(in);
      String exp = m.find() ? spans(m) : "null";
      String got = spans(r.findMatch(in));
      if (!exp.equals(got)) return "findMatch exp " + exp + " got " + got + ctx;
      m = jdk.matcher(in);
      exp = m.matches() ? spans(m) : "null";
      got = spans(r.match(in));
      if (!exp.equals(got)) return "match exp " + exp + " got " + got + ctx;
      for (int s = 0; s <= in.length(); s++) {
        for (int e = s; e <= in.length(); e++) {
          m = jdk.matcher(in).region(s, e);
          exp = m.matches() ? spans(m) : "null";
          got = spans(r.matchBounded(in, s, e));
          if (!exp.equals(got)) {
            return "matchBounded(" + s + "," + e + ") exp " + exp + " got " + got + ctx;
          }
        }
      }
    }
    return null;
  }

  private static String spans(java.util.regex.MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) sb.append(m.start(g)).append(',').append(m.end(g));
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    if (m == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) sb.append(m.start(g)).append(',').append(m.end(g));
    return sb.toString();
  }
}
