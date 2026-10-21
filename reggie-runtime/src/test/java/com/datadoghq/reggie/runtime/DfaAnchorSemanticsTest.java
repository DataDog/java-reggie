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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for {@code \Z}, non-multiline {@code ^} and non-multiline {@code $} on
 * DFA-backed matchers: {@code \Z} matches only at the end or before a final line terminator,
 * non-multiline {@code ^} only at input start, and non-multiline {@code $} only at the end or
 * before a final line terminator, wherever they appear in the pattern. Also checks that bounded
 * matching evaluates lookarounds with JDK's default opaque region bounds, without allocating.
 */
public class DfaAnchorSemanticsTest {

  private static final String[] PATTERNS = {
    // \Z
    "a\\Z",
    "a\\Z\\n",
    "a\\Z\\n?",
    "(a)\\Z",
    "(?m)a\\Z",
    "(?m)((\\w){2,})[^a]{1,3}(1.?)\\Z",
    // non-multiline ^ in the middle
    "a\\n^b",
    "\\n^b",
    "(a)\\n^b",
    "\\n^(?:\\S*)\\S?([^a]{1,3})([ab]+)",
    // non-multiline $ in the middle
    "a$\\nb",
    "a$\\n",
    "(a)$\\nb",
    "^(\\n)((.)?)[a1\\-]{0,2}((\\w){1,3})$\\n",
    // the same anchors on standalone DFA_SWITCH matchers (more DFA states than DFA_UNROLLED takes)
    "x{1,25}\\Z",
    "x{1,25}\\Z\\n",
    "x{1,25}\\n^b",
    "x{1,25}$\\nb",
    "x{1,25}$\\n",
    "^x{1,25}b",
    "\\Ax{1,25}b",
    "(?m)x{1,25}$\\n^b",
    // DFA_SWITCH_WITH_GROUPS
    "(x{1,25})\\n^b",
    "(x{1,25})$\\nb",
    "(x{1,18})\\n^b",
    "(x{1,25})\\Z",
    "(x{1,25})$",
    "(?m)(x{1,25})$",
    // DFA_SWITCH with fewer states
    "x{1,18}\\n^b",
    "x{1,18}$\\nb",
    "x{1,18}$\\n",
    "x{1,18}\\Z\\n",
    // multiline controls
    "(?m)a$\\nb",
    "(?m)a\\n^b",
    "(?m)(a)$\\n^b",
    // leading and trailing anchors, for the region-bounded check
    "x*^b",
    "x?^b",
    "\\n?^b",
    "a*\\Ab",
    "^b",
    "(?m)x*^b",
    "(?m)\\n?^b",
    "x?^",
    "(?m)x?^",
    "^xa",
    "\\Axa",
    "xa$",
    "^x{1,25}a$",
    "(?m)^x{1,25}$",
    // DFA_SWITCH_WITH_ASSERTIONS: fixed-width lookaround, and a gate on a non-start state
    "(?<=a)x{1,25}",
    "(?<!a)x{1,25}",
    "(?=a)[ab]{1,25}",
    "(?!b)[ab]{1,25}",
    "c(?=a{1,3}b)[ab]{1,25}",
    "(?=ab)[ab]{1,25}",
    "(?!ab)[ab]{1,25}",
    // DFA_SWITCH_WITH_ASSERTIONS: lookbehind on a non-start state (entry guard, then acceptance)
    // past an accepting state, so find must not extend the match through a failing assertion;
    // the {1,120} variants split the state switch into helper methods
    "x(?:[cd](?<=c)[ab]{1,18})?",
    "x(?:[ab]{1,18}c(?<=bc))?",
    "x(?:a{18}[ab]c(?<=ac))?",
    "x(?:[cd](?<=c)[ab]{1,25})?",
    "x(?:[cd](?<=c)[ab]{1,120})?",
    "x(?:[ab]{1,25}c(?<=bc))?",
    "x(?:[ab]{1,120}c(?<=bc))?"
  };

  /**
   * DFA_SWITCH_WITH_ASSERTIONS patterns for the opaque-bounds and allocation checks; the {1,120}
   * variants have more than 100 DFA states, so their state switch is split into helper methods, and
   * the {0,25} variants accept in their asserted start state (empty region).
   */
  private static final String[] ASSERTION_PATTERNS = {
    "(?<=a)x{1,25}",
    "(?<!a)x{1,25}",
    "(?=ab)[ab]{1,25}",
    "(?!ab)[ab]{1,25}",
    "c(?=a{1,3}b)[ab]{1,25}",
    "(?<=a)x{1,120}",
    "(?<!a)x{1,120}",
    "(?=ab)[ab]{1,120}",
    "(?!ab)[ab]{1,120}",
    "c(?=a{1,3}b)[ab]{1,120}",
    "(?<=a)x{0,25}",
    "(?<!a)x{0,25}",
    "(?=a{1,3}b)[ab]{0,25}"
  };

  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "a\n",
          "a\n\n",
          "a\nb",
          "a\nb\n",
          "xa\nb",
          "\nb",
          "\nba",
          "x\nba",
          "11-1",
          "11-1\n",
          "11-1\n\n",
          "\nb\n",
          "\nb\n1ba-",
          "\nb1\n",
          "a\r\nb",
          "a\r\n",
          "x\n",
          "x\nb",
          "xx\n\n",
          "xx\nb\n",
          "yxx\nb",
          "xxb",
          "yxxb",
          "x\r\n",
          "b",
          "ax",
          "ab",
          "cab",
          "cbb",
          "ca",
          "xcab",
          "xdab",
          "xabc",
          "xaac",
          "x" + "a".repeat(18) + "bc",
          "x" + "a".repeat(19) + "c",
          "x",
          "xx");

  /**
   * Patterns where an anchor- or lookaround-guarded path shares a charset with an unguarded path,
   * DFA_SWITCH_WITH_GROUPS acceptance and capture spans, optional captures nested in
   * deterministic-chain bodies, and leftmost-first optional tails after a greedy loop.
   */
  private static final String[] REVIEW_PATTERNS = {
    // an END/STRING_END-guarded consumer next to an unguarded consumer of the same char
    "(?!a)(?:[^a]|\\Z)ba{1,105}",
    "(?ms)(?!a)(?:[^a]|\\Z)bb{0,22}[a\\n]{1,105}",
    "(?!a)(?:b|\\Z)b[a\\n]{1,105}",
    "(?<!a)(?:[^a]|\\Z)ba{1,105}",
    "(?=b)(?:[^a]|\\Z)ba{1,105}",
    "(?!a)(?:[^a]|\\Z)ba{1,20}",
    "(?!a)(?:[^a]|$)ba{1,105}",
    "(?!a)(?:[^a]|\\Z)b[a\\n]{1,25}",
    // the same, sharing a line terminator, which the END/STRING_END guard cannot exclude
    "(?!a)(?:[^a]|\\Z)\\n\\n?",
    "(?!a)(?:[^a]|\\Z)\\nb{0,105}",
    // DFA_SWITCH_WITH_GROUPS acceptance anchors
    "(x{1,25})\\A",
    "(x{1,25})^",
    "(x{1,120})\\A",
    // capture spans: a group entry beside a thread past the group (see captureRouting)
    "[ab]{1,25}(\\w)",
    "[ab]{1,25}(\\w)[ab]{1,25}",
    "[ab]{1,3}(\\w)[ab]{1,3}",
    "[ab]+(\\w)[ab]+",
    // near misses that keep the DFA capture route
    "\\w+(\\d)",
    "x{1,3}(x)",
    "(\\w)[ab]{1,25}",
    "x{1,25}(\\d)",
    "(x{1,25})\\.(y{1,3})",
    "(abc){1,2}zz",
    "$(\\n[a\\n]{0,25})",
    "\\Z(\\n)[a\\n]{0,25}",
    // optional capture nested in an optional/alternation chain body (local slot measurement)
    "x(?:(c?)d?|e)f?",
    "x(?:e|(c?)d?)f?",
    "x(?:a(c?)d?|b)",
    "(?s)x{1,25}(?:[ab])?[a\\n]{1,30}(?:([^a]?).?)?",
    // leftmost-first optional tail after a greedy loop
    "x{1,3}(?:x\\s?)?",
    "x{1,25}(?:x\\s?)?",
    "x+(?:x\\s)?",
    // near misses that keep the DFA route (see loopOptionalTailRouting)
    "x{1,25}(?:y\\s?)?",
    "x{1,25}x?",
    "x+(?:y\\s)?"
  };

  private static final List<String> REVIEW_INPUTS =
      List.of(
          "",
          "a",
          "b",
          "ba",
          "bb",
          "abab",
          "bab",
          "bba",
          "b\nba",
          "ab\n",
          "bba\n",
          "x",
          "xx",
          "x\n\n",
          "abab",
          "aab",
          "xbxx\n",
          "xxx\n",
          "xa\n",
          "xab\nb",
          "xxa\nba",
          "\nb",
          "\n",
          "\n\n",
          "x\nbb",
          "a1",
          "ab12",
          "xxxx",
          "a\r\n",
          "a\r\na",
          "xacdy",
          "xay",
          "xby",
          "xcdf",
          "xef",
          "xf",
          "xabx",
          "xacd",
          "abcabczz",
          "xabczz");

  /**
   * A greedy loop followed by a nullable tail that can consume what the loop consumes (bug class of
   * {@link #loopOptionalTailRouting}), and a greedy group that must give chars back to a following
   * loop (see {@link #captureRouting}).
   */
  private static final String[] GIVE_BACK_PATTERNS = {
    "(?:x{1,25})(?:x\\s?)?",
    "x{1,25}c?(?:x\\s?)?",
    "[ab]{1,25}(?:(?:b\\n)?)*",
    "x{1,25}[^a]?(?:[ax]{2,30})*",
    "(?s)x{1,25}b{0,22}(?:(?:[ax]{2,30})?)*",
    "(?ms)x{1,25}(?:(?:[^\\n]b)*)*",
    "(a[ab]{1,25})b{1,2}",
    "(?s)(b[ab]{1,25})[ab]{1,25}",
    "(\\wx{1,25})x{1,120}",
    "x{1,25}(?:xb)?b?",
    // near misses
    "(\\d+)-(\\d+)",
    "([A-Z][a-z]{1,20}) ([A-Z][a-z]{1,20})",
    "(a|b)c",
    "^(\\w+)\\s(\\w+)$",
    "(abc){1,2}"
  };

  private static final List<String> GIVE_BACK_INPUTS =
      List.of(
          "",
          "xx\n",
          "xxc\n",
          "aab\n",
          "aab\nb\n",
          "xxxa",
          "xxxab",
          "x".repeat(27) + "b",
          "abab",
          "abbb",
          "babab",
          "xxxx",
          "axxa",
          "xxb",
          "xxbb",
          "12-34",
          "Ab Cd",
          "ac",
          "ab cd",
          "abcabc");

  @Test
  void giveBackPatternsAgreeWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : GIVE_BACK_PATTERNS) {
      try {
        mismatches.addAll(DotLineTerminatorTest.mismatches(new String[] {p}, GIVE_BACK_INPUTS));
      } catch (RuntimeException | LinkageError e) {
        mismatches.add("/" + p + "/ threw " + e.getClass().getSimpleName());
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void agreesWithJdk() {
    assertEquals(List.of(), DotLineTerminatorTest.mismatches(PATTERNS, INPUTS));
    assertEquals(List.of(), DotLineTerminatorTest.mismatches(ASSERTION_PATTERNS, INPUTS));
  }

  @Test
  void reviewPatternsAgreeWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : REVIEW_PATTERNS) {
      try {
        mismatches.addAll(DotLineTerminatorTest.mismatches(new String[] {p}, REVIEW_INPUTS));
      } catch (RuntimeException | LinkageError e) {
        mismatches.add("/" + p + "/ threw " + e.getClass().getSimpleName());
      }
    }
    assertEquals(List.of(), mismatches);
  }

  /**
   * A DFA state holding both a fresh group entry and a thread past that group, or a thread past a
   * group's exit and a thread still consuming inside it, leaves the DFA capture routes; other group
   * entries and exits, including re-entries of a quantified group, keep them.
   */
  @Test
  void captureRouting() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String p :
        new String[] {
          "[ab]{1,25}(\\w)",
          "[ab]{1,25}(\\w)[ab]{1,25}",
          "[ab]{1,3}(\\w)[ab]{1,3}",
          "[ab]+(\\w)[ab]+",
          "(a[ab]{1,25})b{1,2}",
          "(?s)(b[ab]{1,25})[ab]{1,25}",
          "(\\wx{1,25})x{1,120}"
        }) {
      if (StrategyCorrectnessMetaTest.routeOf(p).name().endsWith("_WITH_GROUPS")) {
        wrong.add(p + " -> " + StrategyCorrectnessMetaTest.routeOf(p));
      }
    }
    for (String p :
        new String[] {
          "\\w+(\\d)",
          "x{1,3}(x)",
          "(\\w)[ab]{1,25}",
          "x{1,25}(\\d)",
          "(x{1,25})\\.(y{1,3})",
          "(abc){1,2}zz"
        }) {
      if (!StrategyCorrectnessMetaTest.routeOf(p).name().endsWith("_WITH_GROUPS")) {
        wrong.add(p + " -> " + StrategyCorrectnessMetaTest.routeOf(p));
      }
    }
    assertEquals(List.of(), wrong);
  }

  /**
   * A greedy loop followed by an optional sequence that starts with a char the loop can consume
   * leaves the longest-match DFA; an optional single element, or a sequence starting outside the
   * loop's chars, keeps the DFA route.
   */
  @Test
  void loopOptionalTailRouting() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String p :
        new String[] {
          "x{1,3}(?:x\\s?)?",
          "x{1,25}(?:x\\s?)?",
          "x+(?:x\\s)?",
          "(?:x{1,25})(?:x\\s?)?",
          "x{1,25}c?(?:x\\s?)?",
          "[ab]{1,25}(?:(?:b\\n)?)*",
          "x{1,25}[^a]?(?:[ax]{2,30})*",
          "(?s)x{1,25}b{0,22}(?:(?:[ax]{2,30})?)*",
          "(?ms)x{1,25}(?:(?:[^\\n]b)*)*",
          "x{1,25}(?:xb)?b?"
        }) {
      if (StrategyCorrectnessMetaTest.routeOf(p).name().startsWith("DFA_")) {
        wrong.add(p + " -> " + StrategyCorrectnessMetaTest.routeOf(p));
      }
    }
    for (String p : new String[] {"x{1,25}(?:y\\s?)?", "x{1,25}x?", "x+(?:y\\s)?"}) {
      if (!StrategyCorrectnessMetaTest.routeOf(p).name().startsWith("DFA_")) {
        wrong.add(p + " -> " + StrategyCorrectnessMetaTest.routeOf(p));
      }
    }
    assertEquals(List.of(), wrong);
  }

  /**
   * {@code $}/{@code \Z} before a consumer: DFA_SWITCH(_WITH_GROUPS) with an optional consumer, and
   * the PIKEVM_CAPTURE reroutes of OPTIMIZED_NFA candidates (plain and anchor-diluted with groups),
   * run natively; an end-anchor accept with a line-terminator transition in an alternation leaves
   * the longest-match DFA.
   */
  private static final String[] END_ANCHOR_CONSUMER_PATTERNS = {
    "(?:[ab]{1,120}a|$(?:x{1,25})?)\\s",
    "(a{1,20})\\Z(?:x{1,25})?\\r",
    "(?m)(b)$b{0,22}",
    "(?:a|b){3,110}[^\\n]$b{0,22}",
    "(?m)(?:^\\r\\Zb{0,22}|$a{1,20}\\n)(?:a|b){3,40}",
    "[a\\n]{1,105}$[a\\n]{1,105}",
    "[^\\n]?(aa{1,20}|$[^a]?)[ab]{1,120}a",
    "(?:$b?|\\r)",
    "(?:x{1,25})?(?:$(?:b)?|\\A\\r)"
  };

  private static final List<String> END_ANCHOR_CONSUMER_INPUTS =
      List.of(
          "",
          "\n",
          "\r",
          "\r\n",
          "x\r\n",
          "a\n",
          "b\n",
          "aaa\n",
          "aaab\n",
          "aaa\r",
          "aaa\r\nb",
          "abab\n",
          "aaa\nbbb",
          "aaab\nab");

  @Test
  void endAnchorBeforeConsumerIsNativeAndAgreesWithJdk() {
    List<String> wrong = new ArrayList<>();
    for (String p : END_ANCHOR_CONSUMER_PATTERNS) {
      if (Reggie.compile(p, ReggieOptions.builder().allowJdkFallback().build()).isJdkFallback()) {
        wrong.add(p + " -> JDK fallback");
      }
    }
    wrong.addAll(
        DotLineTerminatorTest.mismatches(END_ANCHOR_CONSUMER_PATTERNS, END_ANCHOR_CONSUMER_INPUTS));
    assertEquals(List.of(), wrong);
  }

  /**
   * Alternations whose end-anchor accept sits before a transition on {@code \S} or {@code [^\s:]}
   * (both contain U+0085, U+2028 and U+2029): the captureless analysis keeps their DFA half, and
   * every route agrees with JDK on those line terminators.
   */
  private static final String[][] DFA_HALF_ROUTES = {
    {"@(\\S+)\\.(com|net|org)$", "BACKREF_BACKTRACK with DFA half"},
    {
      "^\\s*(?<suppressed>Suppressed:)?(Caused by:)?\\s*((?<kind>[^\\s:]+)( ?:\\s*(?<message1>.*))?"
          + "|(?<message2>.+))$",
      "HYBRID_DFA"
    },
    {"a*(b|(?:a[^a])[a\\n]*(.1?)+b)$", "HYBRID_DFA"}
  };

  private static final List<String> LINE_TERMINATOR_INPUTS =
      List.of(
          "",
          "a@b.com",
          "a@b.com\n",
          "a@b.com\r\n",
          "a@b.com\u0085",
          "x@y.net\u2028",
          "x@y.org\u2029",
          "x@y\u2028.org",
          "Caused by: x\u2028",
          "kind: msg\u0085",
          "Suppressed: k: m\r\n",
          "k\u2029: m",
          "ab\u2028",
          "aab\n",
          "aab\u0085b",
          "aac1b\r\n",
          "b\u2029");

  @Test
  void captureLessAnalysisKeepsDfaHalf() {
    List<String> wrong = new ArrayList<>();
    String[] patterns = new String[DFA_HALF_ROUTES.length];
    for (int i = 0; i < DFA_HALF_ROUTES.length; i++) {
      patterns[i] = DFA_HALF_ROUTES[i][0];
      String routing = RuntimeCompiler.describeRouting(patterns[i]).routing;
      if (!routing.startsWith(DFA_HALF_ROUTES[i][1])) {
        wrong.add(patterns[i] + " -> " + routing);
      }
    }
    wrong.addAll(DotLineTerminatorTest.mismatches(patterns, LINE_TERMINATOR_INPUTS));
    assertEquals(List.of(), wrong);
  }

  /**
   * Patterns whose generated method exceeds the JIT size limit (the first is the one from the
   * review, the rest are from {@code OverJitLimitRerouteBenchmark}).
   */
  private static final String[] OVER_JIT_LIMIT_PATTERNS = {
    "[^a]{3,30}b+(?:\\n{2}\\n{1,25}\\n$)\\d{1,25}",
    "[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$",
    "[0-9a-f]{4,40}[0-9]{4,40}\\Z",
    "[\\w.-]{1,40}@[\\w.-]{1,40}\\.[a-z]{2,6}$",
    "[A-Za-z ]{1,60}[A-Za-z]{1,40}:\\d{1,5}$",
    "\\w{1,30}[\\w.]{1,40}\\.(?:com|org|net)$"
  };

  private static final List<String> OVER_JIT_LIMIT_INPUTS =
      List.of(
          "",
          "xyzb\n\n\n\n1",
          "xyzbb\n\n\n\n12",
          "xyzb\n\n\n\n12\n",
          "aaab\n\n\n\n1",
          "ab cd session7f3a9b2c1d0e12345",
          "session7f3a9b2c1d0e12345\n",
          "ab12",
          "x john.doe@mail.example.com",
          "john.doe@mail.example.com\n",
          "a@b.c",
          "deadbeef0123456789",
          "Server Host Name:8080",
          "Server Host Name:8080\n",
          "x www.example.com",
          "www.example.org\n",
          "a.b.net");

  private static final String SPLIT_ROUTE = "LAURIKARI find / JDK matches (over JIT limit)";

  private static final String BITSTATE_ROUTE =
      "BITSTATE_CAPTURE (generated method over the JIT size limit)";

  private static final String JDK_ROUTE =
      "JAVA_FALLBACK: generated method exceeds HotSpot's JIT method-size limit";

  /**
   * With JDK fallback, a generated method over the JIT size limit is replaced by the Laurikari/JDK
   * split, by BitState for a case-insensitive pattern, and by the JDK for a strategy that the
   * native re-route does not cover (a one-pass NFA here).
   */
  @Test
  void overJitLimitRoutesToNativeWhenEligible() {
    String[][] expected = {
      {OVER_JIT_LIMIT_PATTERNS[0], SPLIT_ROUTE},
      {OVER_JIT_LIMIT_PATTERNS[1], SPLIT_ROUTE},
      {OVER_JIT_LIMIT_PATTERNS[2], SPLIT_ROUTE},
      {OVER_JIT_LIMIT_PATTERNS[3], SPLIT_ROUTE},
      {OVER_JIT_LIMIT_PATTERNS[4], SPLIT_ROUTE},
      {OVER_JIT_LIMIT_PATTERNS[5], SPLIT_ROUTE},
      {"(?i)[a-z]{2,30}[a-z0-9]{2,30}\\d{2,30}$", BITSTATE_ROUTE},
      {"(a)".repeat(300), JDK_ROUTE}
    };
    List<String> wrong = new ArrayList<>();
    for (String[] e : expected) {
      String p = e[0];
      RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(p);
      if (!info.routing.startsWith(e[1])
          || info.matcher.isJdkFallback() != e[1].equals(JDK_ROUTE)) {
        wrong.add(p + " -> " + info.routing);
      }
      Pattern j = Pattern.compile(p);
      for (String in : OVER_JIT_LIMIT_INPUTS) {
        String diff = DotLineTerminatorTest.diff(info.matcher, j, in);
        if (diff != null) {
          wrong.add(p + " on \"" + escape(in) + "\": " + diff);
        }
      }
    }
    assertEquals(List.of(), wrong);
  }

  /**
   * A strict compile caches the oversized generated class; a later compile with JDK fallback hits
   * that structure-cache entry and takes the same Laurikari/JDK split route as an uncached compile.
   */
  @Test
  void overJitLimitStructureCacheHitRoutesToSplit() {
    String p = OVER_JIT_LIMIT_PATTERNS[0];
    RuntimeCompiler.clearCache();
    assertTrue(!RuntimeCompiler.compile(p, ReggieOptions.DEFAULT).isJdkFallback());
    RuntimeCompiler.ROUTING_NOTE.remove();
    ReggieMatcher m =
        RuntimeCompiler.compile(p, ReggieOptions.builder().allowJdkFallback().build());
    String note = RuntimeCompiler.ROUTING_NOTE.get();
    assertTrue(
        note != null && note.startsWith(SPLIT_ROUTE) && !m.isJdkFallback(), String.valueOf(note));
    Pattern j = Pattern.compile(p);
    for (String in : OVER_JIT_LIMIT_INPUTS) {
      assertEquals(null, DotLineTerminatorTest.diff(m, j, in), escape(in));
    }
  }

  /**
   * The split route logs no java.util.regex fallback warning; an over-limit pattern that falls back
   * entirely logs one per compile.
   */
  @Test
  void overJitLimitFallbackWarningOnlyForFullFallback() {
    List<String> warnings = new ArrayList<>();
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            if (record.getLevel() == Level.WARNING) {
              warnings.add(record.getMessage());
            }
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    Logger log = Logger.getLogger(JavaRegexFallbackMatcher.class.getName());
    log.addHandler(handler);
    try {
      RuntimeCompiler.clearCache();
      ReggieOptions jdk = ReggieOptions.builder().allowJdkFallback().build();
      RuntimeCompiler.ROUTING_NOTE.remove();
      ReggieMatcher split = RuntimeCompiler.compile(OVER_JIT_LIMIT_PATTERNS[0], jdk);
      String note = RuntimeCompiler.ROUTING_NOTE.get();
      assertTrue(
          note != null && note.startsWith(SPLIT_ROUTE) && !split.isJdkFallback(),
          String.valueOf(note));
      assertEquals(List.of(), warnings);
      assertTrue(RuntimeCompiler.compile("(a)".repeat(300), jdk).isJdkFallback());
      assertEquals(1, warnings.size(), String.valueOf(warnings));
      assertTrue(warnings.get(0).contains("JIT method-size limit"), warnings.get(0));
    } finally {
      log.removeHandler(handler);
    }
  }

  /** {@code matchesBounded}/{@code matchBounded} over the whole input agree with JDK matches(). */
  @Test
  void boundedAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : INPUTS) {
        Matcher m = j.matcher(in);
        boolean expected = m.matches();
        if (r.matchesBounded(in, 0, in.length()) != expected) {
          mismatches.add("/" + p + "/ matchesBounded on " + escape(in));
        }
        MatchResult got = r.matchBounded(in, 0, in.length());
        if ((got != null) != expected || (expected && got.end() != m.end())) {
          mismatches.add("/" + p + "/ matchBounded on " + escape(in));
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  /**
   * {@code matchBounded} over every region of inputs with a {@code \r\n} agrees with JDK {@code
   * region(start, end).matches()}: {@code $}/{@code \Z} do not match between {@code \r} and {@code
   * \n}, even when the {@code \r} lies before the region.
   */
  @Test
  void matchBoundedSeesCarriageReturnBeforeRegion() {
    String[] patterns = {"\\Z[a\\n]{1,30}", "$[a\\n]{1,30}", "(?ms)^$.[a\\n]{1,30}"};
    String[] inputs = {"a\r\n", "a\n\n", "\r\n", "\r\na", "a\r\n\n"};
    List<String> mismatches = new ArrayList<>();
    for (String p : patterns) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : inputs) {
        for (int start = 0; start <= in.length(); start++) {
          for (int end = start; end <= in.length(); end++) {
            Matcher m = j.matcher(in).region(start, end);
            boolean expected = m.matches();
            MatchResult got = r.matchBounded(in, start, end);
            if ((got != null) != expected
                || (expected && (got.start() != m.start() || got.end() != m.end()))
                || r.matchesBounded(in, start, end) != expected) {
              mismatches.add(
                  "/" + p + "/ on " + escape(in) + " region [" + start + ", " + end + ")");
            }
          }
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  /**
   * {@code matchesBounded}/{@code matchBounded} over a region that excludes the first and last char
   * agree with JDK {@code region(1, len - 1).matches()}: with JDK's default anchoring bounds,
   * anchors see the region bounds as the input bounds.
   */
  @Test
  void regionBoundedAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in0 : INPUTS) {
        String in = "y" + in0 + "y";
        int end = in.length() - 1;
        Matcher m = j.matcher(in).region(1, end);
        boolean expected = m.matches();
        if (r.matchesBounded(in, 1, end) != expected) {
          mismatches.add("/" + p + "/ matchesBounded on " + escape(in) + " region [1, len - 1)");
        }
        MatchResult got = r.matchBounded(in, 1, end);
        if ((got != null) != expected || (expected && got.end() != m.end())) {
          mismatches.add("/" + p + "/ matchBounded on " + escape(in) + " region [1, len - 1)");
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  /**
   * {@code matchesBounded}/{@code matchBounded} on a region padded with chars a lookaround could
   * read agree with JDK's default opaque bounds. Each pattern has at least one case where
   * transparent bounds give a different result, so the check depends on the bounds being opaque.
   */
  @Test
  void regionLookaroundsUseOpaqueBounds() {
    String[] pads = {"", "a", "b", "c", "x"};
    List<String> mismatches = new ArrayList<>();
    for (String p : ASSERTION_PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      int transparentDiffers = 0;
      for (String in0 : INPUTS) {
        for (String left : pads) {
          for (String right : pads) {
            String in = left + in0 + right;
            int start = left.length();
            int end = start + in0.length();
            Matcher m = j.matcher(in).region(start, end);
            boolean expected = m.matches();
            if (j.matcher(in).region(start, end).useTransparentBounds(true).matches() != expected) {
              transparentDiffers++;
            }
            String where = "/" + p + "/ on " + escape(in) + " region [" + start + ", " + end + ")";
            if (r.matchesBounded(in, start, end) != expected) {
              mismatches.add("matchesBounded " + where);
            }
            MatchResult got = r.matchBounded(in, start, end);
            if ((got != null) != expected || (expected && got.end() != m.end())) {
              mismatches.add("matchBounded " + where);
            }
          }
        }
      }
      assertTrue(transparentDiffers > 0, "no transparent-bounds-sensitive case for /" + p + "/");
    }
    assertEquals(List.of(), mismatches);
  }

  /** {@code matchesBounded} on assertion patterns allocates nothing per call. */
  @Test
  void assertionMatchesBoundedDoesNotAllocate() {
    ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    assumeTrue(bean.isThreadAllocatedMemorySupported());
    bean.setThreadAllocatedMemoryEnabled(true);
    String in = "zcaabbbz";
    for (String p : ASSERTION_PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      int hits = 0;
      for (int i = 0; i < 20_000; i++) {
        hits += r.matchesBounded(in, 1 + (i & 1), in.length() - 1) ? 1 : 0;
      }
      long tid = Thread.currentThread().getId();
      long before = bean.getThreadAllocatedBytes(tid);
      for (int i = 0; i < 20_000; i++) {
        hits += r.matchesBounded(in, 1 + (i & 1), in.length() - 1) ? 1 : 0;
      }
      long allocated = bean.getThreadAllocatedBytes(tid) - before;
      assertTrue(
          allocated < 1024, "/" + p + "/ allocated " + allocated + " bytes (hits " + hits + ")");
    }
  }

  private static String escape(String s) {
    return "\"" + s.replace("\r", "\\r").replace("\n", "\\n") + "\"";
  }
}
