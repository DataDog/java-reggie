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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Group spans of a quantified group nested in another quantified group, against JDK. */
class NestedQuantifiedGroupSpanTest {

  private static final String[][] CASES = {
    {"((a+)+)", "aaa"},
    {"((ab)+)+", "ababab"},
    {"((a+)+)b", "aaab"},
    {"(?:(a+)b)+", "aabab"},
    {"(a+)+", "aaa"},
    {"(a*)+", "aaa"},
    {"(a+)*", "aaa"},
    {"((a)+)+", "aaa"},
    {"(?:(a)+)+", "aaa"},
    {"((a)*)*", "aaa"},
    {"(?:(ab)+)+", "ababab"},
    {"(a+){2,}", "aaa"},
    {"(a{1,2})+", "aaa"},
    {"((?:a|b)+)+", "abab"},
    {"(?:(a+)|b)+", "aaba"},
    {"x((a+)+)y", "xaaay"},
    {"(?:a(b+))+", "abbabb"},
    {"(a+){3}", "aaa"},
    {"(a{1,3}){2}", "aaa"},
    {"(a{1,3}){2,}", "aaa"},
    {"([ab]+){2,}", "abab"},
    {"(a{2}){2,}", "aaaa"},
    {"(a+|b){2,}", "aa"},
  };

  @Test
  void routedMatcherAgreesWithJdk() {
    List<String> failures = new ArrayList<>();
    for (String[] c : CASES) {
      check(c[0], c[1], Reggie.compile(c[0]), "routed", failures);
    }
    assertEquals(List.of(), failures);
  }

  /** Nested nullable quantifiers on inputs they cannot fully match. */
  @Test
  void nestedNullableLoopsTerminate() {
    List<String> failures = new ArrayList<>();
    for (String p : new String[] {"((a)*)*", "(a*)*", "((a)*)+", "(?:(a)*)*"}) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : new String[] {"", "b", "ab", "aab", "test123test", "no match here"}) {
        String exp = String.valueOf(jdk.matcher(in).matches());
        String got =
            assertTimeoutPreemptively(
                Duration.ofSeconds(5), () -> String.valueOf(r.matches(in)), p + " on " + in);
        if (!exp.equals(got)) failures.add("/" + p + "/ matches \"" + in + "\" got " + got);
      }
    }
    assertEquals(List.of(), failures);
  }

  /**
   * A capturing group with nullable content under a nullable quantifier: JDK reports a final
   * zero-width iteration ((0*-?)* on "0": group 1 = [1,1)). PikeVM reproduces it; BitState does
   * not, so these must not route to BITSTATE_CAPTURE.
   */
  @Test
  void nullableGroupContentAgreesWithJdk() throws Exception {
    List<String> inputs = TrailingLookaheadDifferentialTest.inputs(new char[] {'0', '-', 'x'}, 4);
    List<String> failures = new ArrayList<>();
    for (String p :
        new String[] {
          "(0*-?){0,}",
          "(0*-?)*",
          "(0*)*",
          "(-?)*",
          "(0*-?)?",
          "x(0*-?)*",
          "(0*-?)*x",
          "((0*-?)*)*",
          "((0*)*)+",
          "((0)*)*",
          "(?:(0*)+)*",
          "((0{1,2})*)?",
          "((0-)*)?",
          "(0*-?)*|x",
          "(?:(0*)*|x)-",
          "(0*-?)*(.+)-",
          "x?(0*-?)*",
          "(0*)*(-|0-)",
          "((0*)*-?)x?",
          "(?:(-)|(0*)*)x",
          "(0|)*|x",
          "(|-)*(.+)-",
          "x?(0|)*",
          "(?:(0|)*|x)-",
          "(?:(0|)*|x)-?"
        }) {
      PatternAnalyzer.MatchingStrategy route = StrategyCorrectnessMetaTest.routeOf(p);
      if (route == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE) {
        failures.add("/" + p + "/ routes to BITSTATE_CAPTURE");
      }
      ReggieMatcher routed;
      try {
        routed = Reggie.compile(p);
      } catch (RuntimeException e) {
        failures.add("/" + p + "/ " + route + " " + e.getMessage());
        continue;
      }
      NFA nfa =
          new ThompsonBuilder()
              .build(new RegexParser().parse(p), Pattern.compile(p).matcher("").groupCount());
      ReggieMatcher pike = new PikeVMMatcher(nfa, p);
      for (String in : inputs) {
        if (Pattern.compile(p).matcher(in).matches() != routed.matches(in)) {
          failures.add("routed matches /" + p + "/ on \"" + in + "\"");
        }
        check(p, in, routed, "routed(" + route + ")", failures);
        check(p, in, pike, "pikevm", failures);
      }
    }
    assertEquals(List.of(), failures);
  }

  /** A single-char run inside an unbounded repeat keeps SPECIALIZED_QUANTIFIED_GROUP. */
  @Test
  void singleRunRepeatAgreesWithJdk() throws Exception {
    List<String> inputs = TrailingLookaheadDifferentialTest.inputs(new char[] {'a', 'b', '1'}, 5);
    List<String> failures = new ArrayList<>();
    for (String p :
        new String[] {"(\\d+)+", "([a-z]+)*", "(a+)+", "(a+)*", "^(a+)+", "\\A(a+)*", "([^b]+)+"}) {
      PatternAnalyzer.MatchingStrategy route = StrategyCorrectnessMetaTest.routeOf(p);
      if (route != PatternAnalyzer.MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP) {
        failures.add("/" + p + "/ routes to " + route);
      }
      ReggieMatcher r = Reggie.compile(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : inputs) {
        if (jdk.matcher(in).matches() != r.matches(in)) failures.add("matches /" + p + "/ " + in);
        if (jdk.matcher(in).find() != r.find(in)) failures.add("find /" + p + "/ " + in);
        check(p, in, r, "routed", failures);
      }
    }
    assertEquals(List.of(), failures);
  }

  /**
   * JDK GroupCurly: a greedy (X){min,max} with a deterministic body, re-entered by an outer repeat,
   * reports the last iteration of the earliest invocation that ran more than min times
   * ((?:(a){1,2})+ on "aaa": group 1 = [1,2)).
   */
  @Test
  void groupCurlyPinAgreesWithJdk() throws Exception {
    List<String> inputs = TrailingLookaheadDifferentialTest.inputs(new char[] {'a', 'b'}, 6);
    List<String> failures = new ArrayList<>();
    for (String p :
        new String[] {
          "(?:(a){1,2})+",
          "(?:(a){2,3})+",
          "(?:(ab){1,2})+",
          "((ab)+){2,}",
          "(?:(a)+){2}",
          "(?:(a){1,2}){3}",
          "(?:(a){1,2})+b",
          "(?:(a){1,2}b?)+",
          "(?:(a){1,2}|b)+",
          "(?:([ab]){1,2})+",
          "(?:(\\w){1,2})+",
          "(?:(a){0,2})+",
          "(?:(a)*)+",
          "(?:(a)*b)+",
          "(?:(a){1,2}?)+",
          "(?:(a){1,2}+)+",
          "(?:(?>(a){1,2}))+",
          "(?:(a|b){1,2})+",
          "(?:(a+){1,2})+",
          "(?:(a)?)+",
          "(a){1,2}",
          "(?:(?:(a){1,2})b)*",
          "(?:(a){1,2})++b",
          "(?:(a){1,2})*+b",
          "(?:(a){1,2}){2,3}+b",
          "(?:(?:(a){1,2})++b?)+",
          "(?>(?:(a){1,2})+)b"
        }) {
      PatternAnalyzer.MatchingStrategy route = StrategyCorrectnessMetaTest.routeOf(p);
      ReggieMatcher routed = Reggie.compile(p);
      NFA nfa =
          new ThompsonBuilder()
              .build(new RegexParser().parse(p), Pattern.compile(p).matcher("").groupCount());
      ReggieMatcher pike = new PikeVMMatcher(nfa, p);
      for (String in : inputs) {
        check(p, in, routed, "routed(" + route + ")", failures);
        check(p, in, pike, "pikevm", failures);
      }
    }
    assertEquals(List.of(), failures);
  }

  /**
   * GroupCurly pins on BackrefBacktrackMatcher: lowered counted loops, backreferences (which read
   * the unpinned span) and lookaround (a pin set inside a lookahead body applies when the body
   * completes, so a later backreference reads the pinned span).
   */
  @Test
  void groupCurlyPinOnBacktrackerAgreesWithJdk() throws Exception {
    List<String> inputs = TrailingLookaheadDifferentialTest.inputs(new char[] {'a', 'b', 'x'}, 5);
    List<String> failures = new ArrayList<>();
    for (String p :
        new String[] {
          "(?:(a){1,6000})+",
          "(?:(a){1,6000}b)+",
          "(?:(a){0,6000}b)+",
          "(?:(ab){1,3000})+",
          "(?:(?:(a){1,6000}b)+x)+",
          "(?:(a){1,6000})+\\1",
          "(?:(a){1,2})+b\\1",
          "(?:(a){1,2})+(?=b)",
          "(?<=b)(?:(a){1,2})+",
          "(?:(a){1,2})+(?=b)\\1",
          "(?=(?:(a){1,2})+)a",
          "(?=(?:([ab]){1,2})+x)\\1",
          "(?=(?:([ab]){1,2})+x)\\1.*(?=(?:([ab]){1,2})+x)\\2"
        }) {
      ReggieMatcher routed = Reggie.compile(p);
      for (String in : inputs) {
        check(p, in, routed, "routed", failures);
      }
    }
    assertEquals(List.of(), failures);
  }

  @Test
  void pikeVmAgreesWithJdk() throws Exception {
    List<String> failures = new ArrayList<>();
    for (String[] c : CASES) {
      NFA nfa =
          new ThompsonBuilder()
              .build(new RegexParser().parse(c[0]), Pattern.compile(c[0]).matcher("").groupCount());
      check(c[0], c[1], new PikeVMMatcher(nfa, c[0]), "pikevm", failures);
    }
    assertEquals(List.of(), failures);
  }

  private static void check(
      String p, String in, ReggieMatcher r, String engine, List<String> failures) {
    Matcher m = Pattern.compile(p).matcher(in);
    String exp = m.find() ? spans(m) : "null";
    String got = spans(r.findMatch(in));
    if (!exp.equals(got))
      failures.add(engine + " findMatch /" + p + "/ exp " + exp + " got " + got);
    m = Pattern.compile(p).matcher(in);
    exp = m.matches() ? spans(m) : "null";
    got = spans(r.match(in));
    if (!exp.equals(got)) failures.add(engine + " match /" + p + "/ exp " + exp + " got " + got);
  }

  private static String spans(Matcher m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append("..").append(m.end(g)).append(' ');
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    if (m == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append("..").append(m.end(g)).append(' ');
    return sb.toString();
  }
}
