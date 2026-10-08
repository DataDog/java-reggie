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
 * JDK-differential check for non-dotall {@code .}, {@code $} and multiline {@code ^}/{@code $} on
 * input containing each java.util.regex line terminator ({@code \n}, {@code \r}, U+0085, U+2028,
 * U+2029) and {@code \r\n}. Compares matches()/match(), find()/findMatch() and the full find-from
 * iteration.
 */
public class DotLineTerminatorTest {

  /** Pattern and the strategy it routes to, so each case keeps covering the intended engine. */
  private static final String[][] CASES = {
    {"a.b", "DFA_UNROLLED"},
    {".*", "STATELESS_LOOP"},
    {".+", "STATELESS_LOOP"},
    {"(.*)x", "GREEDY_SUFFIX"},
    {"x.*$", "DFA_UNROLLED"},
    {"(.+)(\\d+)", "GREEDY_SUFFIX"},
    {".{2,3}", "STATELESS_LOOP"},
    {"a.*b", "DFA_UNROLLED"},
    {"(?s)a.b", "DFA_UNROLLED"},
    {"^(.*)$", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"a.+?b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(.)(.)", "ONEPASS_NFA"},
    {".*$", "SPECIALIZED_SUFFIX_SEQUENCE"},
    {"(a.)+", "DFA_UNROLLED_WITH_GROUPS"},
    {"(?<=a.)b", "DFA_UNROLLED_WITH_ASSERTIONS"},
    {"x(.*)", "SPECIALIZED_MULTI_GROUP_GREEDY"},
    {"(?=.*a)(?=.*b).{3,}", "DFA_UNROLLED_WITH_ASSERTIONS"},
    {"(?=.*\\d).+", "DFA_UNROLLED_WITH_ASSERTIONS"},
    {"(.+)\\r\\n(\\w+)", "BITSTATE_CAPTURE"},
    {"(.+)\\n(\\d+)", "BITSTATE_CAPTURE"},
    {"(?m)^.+$", "DFA_UNROLLED"},
    {"(?m)^(.*)$", "BITSTATE_CAPTURE"},
    {"(?m)^\\w+", "DFA_UNROLLED"},
    {"(?m)\\d$", "DFA_UNROLLED"},
    {"(?m)^a*", "DFA_UNROLLED"},
    {"(?m)^(a*)", "BITSTATE_CAPTURE"},
    {"(?m)^\\n", "DFA_UNROLLED"},
    {"(?m)^(\\s)", "ONEPASS_NFA"},
    {"(?m)^[ab]+", "DFA_UNROLLED"},
    {"(?m)^(b+)x?", "DFA_UNROLLED_WITH_GROUPS"},
    {"(?m)^$", "DFA_UNROLLED"},
    {"(?m)(^a|b)", "DFA_UNROLLED_WITH_GROUPS"},
    {"(?m)a.+?$", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(?m)(\\r)$", "ONEPASS_NFA"},
    {"(?m)(b|\\r)$", "DFA_UNROLLED_WITH_GROUPS"},
    {"(?m)\\r$", "DFA_UNROLLED"},
    {"(?m)a[\\r]+?$", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(?m)a\\s+?$", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(?m)^abcdefghijklmnopqrstuvwxyz|b", "OPTIMIZED_NFA"},
    {"(?m)(?:^a|b)\\d{0,25}", "OPTIMIZED_NFA"},
    {"a.+?$", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(\\w)\\1$", "LINEAR_BACKREFERENCE"},
    {"(a|b)\\1$", "OPTIMIZED_NFA_WITH_BACKREFS"}
  };

  private static final String[] TERMINATORS = {"\n", "\r", "\u0085", "\u2028", "\u2029", "\r\n"};

  private static final String[] TEMPLATES = {
    "aTb", "Tab", "abT", "fooTbar", "x1T22", "axbTaxb", "TT", "aT1", "abTTx", "aaTbbT"
  };

  static List<String> inputs() {
    List<String> inputs = new ArrayList<>(List.of("", "ab", "axb", "x12", "abc"));
    for (String t : TERMINATORS) {
      for (String template : TEMPLATES) {
        inputs.add(template.replace("T", t));
      }
    }
    return inputs;
  }

  @Test
  void agreesWithJdk() {
    String[] patterns = new String[CASES.length];
    for (int i = 0; i < CASES.length; i++) {
      patterns[i] = CASES[i][0];
    }
    assertEquals(List.of(), mismatches(patterns, inputs()));
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

  static List<String> mismatches(String[] patterns, List<String> inputs) {
    List<String> mismatches = new ArrayList<>();
    for (String p : patterns) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : inputs) {
        String diff = diff(r, j, in);
        if (diff != null) {
          mismatches.add("/" + p + "/ on \"" + escape(in) + "\": " + diff);
        }
      }
    }
    return mismatches;
  }

  static String diff(ReggieMatcher r, Pattern j, String in) {
    Matcher m = j.matcher(in);
    String jdkMatch = m.matches() ? spans(m) : "-";
    MatchResult mr = r.match(in);
    String reggieMatch = mr == null ? "-" : spans(mr);
    if (!jdkMatch.equals(reggieMatch) || r.matches(in) != !jdkMatch.equals("-")) {
      return "match jdk=" + jdkMatch + " reggie=" + reggieMatch + "(" + r.matches(in) + ")";
    }
    m = j.matcher(in);
    String jdkFind = m.find() ? spans(m) : "-";
    MatchResult fr = r.findMatch(in);
    String reggieFind = fr == null ? "-" : spans(fr);
    if (!jdkFind.equals(reggieFind) || r.find(in) != !jdkFind.equals("-")) {
      return "find jdk=" + jdkFind + " reggie=" + reggieFind + "(" + r.find(in) + ")";
    }
    for (int start = 0; start <= in.length(); start++) {
      m = j.matcher(in);
      String jdkFrom = m.find(start) ? spans(m) : "-";
      MatchResult fm = r.findMatchFrom(in, start);
      String reggieFrom = fm == null ? "-" : spans(fm);
      int reggieFromStart = r.findFrom(in, start);
      int jdkFromStart = jdkFrom.equals("-") ? -1 : m.start();
      if (!jdkFrom.equals(reggieFrom) || reggieFromStart != jdkFromStart) {
        return "findFrom("
            + start
            + ") jdk="
            + jdkFrom
            + " reggie="
            + reggieFrom
            + "("
            + reggieFromStart
            + ")";
      }
    }
    return null;
  }

  static String escape(String s) {
    return s.replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u0085", "\\u0085")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029");
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
