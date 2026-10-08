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

import static com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy.GREEDY_SUFFIX;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * GREEDY_SUFFIX against JDK on every shape {@code P (G{m,}) [\b] T [END]} of the plan's grid, over
 * every input up to length 4 of an alphabet with all five line terminators' representatives: every
 * API, every {@code from}, every bounded region (on a String and a StringBuilder), and all group
 * spans.
 */
public class GreedySuffixExhaustiveTest {

  private static final String[] M = {"*", "+"};
  private static final String[] P = {"", "x", "[a1]", "x\\n", "\\n", "\\s", "[\\r\\n]x"};
  private static final String[] WB = {"", "\\b"};
  private static final String[] T = {
    "ab", "\\d", "\\n", "a\\r", "(\\d+)", "(\\d*)", "(\\d{2,3})", "(\\d{0,1})", "([a1]{1,2})"
  };
  private static final String[] END = {"", "$", "\\Z", "\\z"};
  private static final String[] G = {"", "(?s)"};

  private static final char[] ALPHABET = {'a', 'x', '1', ' ', '\n', '\r', '\u0085', '\u2028'};

  static List<String> shapes() {
    List<String> patterns = new ArrayList<>();
    for (String g : G)
      for (String p : P)
        for (String m : M)
          for (String wb : WB)
            for (String t : T)
              for (String end : END) patterns.add(g + p + "(." + m + ")" + wb + t + end);
    return patterns;
  }

  @Test
  void routes() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String p : shapes()) {
      MatchingStrategy s = StrategyCorrectnessMetaTest.routeOf(p);
      if (s != GREEDY_SUFFIX) wrong.add(p + " -> " + s);
    }
    assertEquals(List.of(), wrong);
  }

  @Test
  void agreesWithJdk() {
    List<String> inputs = TrailingLookaheadDifferentialTest.inputs(ALPHABET, 4);
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    shapes().parallelStream().forEach(p -> check(p, inputs, failures));
    Collections.sort(failures);
    assertEquals(List.of(), failures);
  }

  /** A subset of the grid on longer inputs over a smaller alphabet. */
  @Test
  void agreesWithJdkOnLongerInputs() {
    List<String> inputs =
        TrailingLookaheadDifferentialTest.inputs(new char[] {'a', '1', ' ', '\n'}, 6);
    List<String> patterns = new ArrayList<>();
    for (String g : G)
      for (String p : new String[] {"", "x"})
        for (String m : M)
          for (String wb : WB)
            for (String t : new String[] {"ab", "(\\d+)", "(\\d*)"})
              for (String end : END) patterns.add(g + p + "(." + m + ")" + wb + t + end);
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    patterns.parallelStream().forEach(p -> check(p, inputs, failures));
    Collections.sort(failures);
    assertEquals(List.of(), failures);
  }

  /**
   * G runs longer than the generator's 64-char loop scan, with each line terminator placed around
   * the switch to {@code String.indexOf}.
   */
  @Test
  void agreesWithJdkPastTheLoopScan() {
    String[] patterns = {"(.*)a1", "(.*)(\\d+)", "x(.*)(\\d+)$", "(.+)\\b(\\d+)"};
    String[] terminators = {"", "\n", "\r", "\u0085", "\u2028", "\u2029"};
    List<String> inputs = new ArrayList<>();
    for (String t : terminators) {
      for (int pos : new int[] {0, 62, 63, 64, 65, 66, 100}) {
        inputs.add("x" + "a".repeat(pos) + t + "a".repeat(70) + "a1");
      }
    }
    List<String> failures = Collections.synchronizedList(new ArrayList<>());
    Arrays.stream(patterns).parallel().forEach(p -> check(p, inputs, failures));
    Collections.sort(failures);
    assertEquals(List.of(), failures);
  }

  /** Long random inputs, long minimum runs, and the rich APIs built on findMatchFrom. */
  @Test
  void agreesWithJdkOnLongRandomInputs() {
    String[] patterns = {
      "(.*)(\\d{7,})", "(.*)(\\d{7,})$", "(.*)bar", "(.+)\\b(\\d+)", "x(.*)(\\d+)", "(?s)(.*)ab$"
    };
    char[] alphabet = {'a', 'b', 'r', 'x', '1', '2', ' ', '\n'};
    Random random = new Random(42);
    List<String> failures = new ArrayList<>();
    for (String p : patterns) {
      ReggieMatcher r = EngineRouting.unwrap(Reggie.compile(p));
      Pattern jdk = Pattern.compile(p);
      for (int i = 0; i < 300; i++) {
        StringBuilder sb = new StringBuilder();
        int len = random.nextInt(200);
        for (int j = 0; j < len; j++) {
          sb.append(alphabet[random.nextInt(alphabet.length)]);
          if (random.nextInt(8) == 0) sb.append("bar1234567");
        }
        String in = sb.toString();
        String f = divergence(r, jdk, in);
        if (f == null && !jdk.matcher(in).replaceAll("<$1>").equals(r.replaceAll(in, "<$1>"))) {
          f = "replaceAll($1)";
        }
        if (f == null && !Arrays.equals(jdk.split(in), r.split(in))) f = "split";
        if (f == null && !findAll(jdk, in).equals(findAll(r, in))) f = "findAll";
        if (f != null) {
          failures.add("/" + p + "/ on \"" + escape(in) + "\": " + f);
          break;
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  private static void check(String p, List<String> inputs, List<String> failures) {
    ReggieMatcher r = EngineRouting.unwrap(Reggie.compile(p));
    Pattern jdk = Pattern.compile(p);
    for (String in : inputs) {
      String f = divergence(r, jdk, in);
      if (f != null) {
        failures.add("/" + p + "/ on \"" + escape(in) + "\": " + f);
        return;
      }
    }
  }

  /** First API whose result diverges from JDK, or null. */
  private static String divergence(ReggieMatcher r, Pattern jdk, String in) {
    String f = TrailingLookaheadDifferentialTest.divergence(r, jdk, in);
    if (f != null) return f;
    Matcher m = jdk.matcher(in);
    String exp = m.matches() ? spans(m) : "null";
    MatchResult mr = r.match(in);
    String got = mr == null ? "null" : spans(mr);
    if (!exp.equals(got)) return "match exp " + exp + " got " + got;
    for (int s = 0; s <= in.length(); s++) {
      m = jdk.matcher(in);
      exp = m.find(s) ? spans(m) : "null";
      mr = r.findMatchFrom(in, s);
      got = mr == null ? "null" : spans(mr);
      if (!exp.equals(got)) return "findMatchFrom(" + s + ") exp " + exp + " got " + got;
      for (int e = s; e <= in.length(); e++) {
        m = jdk.matcher(in).region(s, e);
        exp = m.matches() ? spans(m) : "null";
        mr = r.matchBounded(in, s, e);
        got = mr == null ? "null" : spans(mr);
        if (!exp.equals(got)) return "matchBounded(" + s + "," + e + ") exp " + exp + " got " + got;
        mr = r.matchBounded(new StringBuilder(in), s, e);
        got = mr == null ? "null" : spans(mr);
        if (!exp.equals(got))
          return "matchBounded(sb," + s + "," + e + ") exp " + exp + " got " + got;
      }
    }
    return null;
  }

  private static String spans(java.util.regex.MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append("..").append(m.end(g)).append(' ');
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append("..").append(m.end(g)).append(' ');
    return sb.toString();
  }

  private static List<String> findAll(Pattern jdk, String in) {
    List<String> out = new ArrayList<>();
    Matcher m = jdk.matcher(in);
    while (m.find()) out.add(spans(m));
    return out;
  }

  private static List<String> findAll(ReggieMatcher r, String in) {
    List<String> out = new ArrayList<>();
    for (MatchResult mr : r.findAll(in)) out.add(spans(mr));
    return out;
  }

  private static String escape(String in) {
    return in.replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u0085", "\\u0085")
        .replace("\u2028", "\\u2028")
        .replace("\u2029", "\\u2029");
  }
}
