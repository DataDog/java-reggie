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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Greedy spans of terminator-consuming runs followed by end anchors ($/\Z/\z) must match the JDK.
 *
 * <p>The generic DFA walk (DFA_UNROLLED) previously recorded the before-final-terminator acceptance
 * position and returned immediately, truncating the greedy run one character early: {@code
 * [^a]*$\Z} on "x\n" returned [0,1) where the JDK greedy run consumes the '\n' and ends at 2. A
 * single trailing anchor never hit this (those patterns route to SPECIALIZED_SUFFIX_SEQUENCE);
 * double anchors ($\Z, \Z\z) and runs nested behind other elements do. The acceptance is now
 * recorded only via the state's anchor conditions and the walk keeps consuming, so the longest
 * viable end wins.
 */
public class GreedyEndAnchorSpanTest {

  private static void assertFindSpan(String pattern, String input) {
    Matcher jdkMatcher = Pattern.compile(pattern).matcher(input);
    boolean jdkFind = jdkMatcher.find();
    boolean reggieFind = Reggie.compile(pattern).find(input);
    assertEquals(
        jdkFind, reggieFind, "find() parity for /" + pattern + "/ on [" + escape(input) + "]");

    ReggieMatcher matcher = Reggie.compile(pattern);
    int[] starts = new int[2];
    int[] ends = new int[2];
    boolean reggieSpan = matcher.findMatchInto(input, starts, ends);
    assertTrue(jdkFind == reggieSpan, "findMatchInto agrees with find()");
    if (jdkFind) {
      assertEquals(
          jdkMatcher.start(),
          starts[0],
          "span start for /" + pattern + "/ on [" + escape(input) + "]");
      assertEquals(
          jdkMatcher.end(),
          ends[0],
          "span end for /"
              + pattern
              + "/ on ["
              + escape(input)
              + "] (greedy run must"
              + " consume the final line terminator when its charset allows)");
    }
  }

  private static String escape(String s) {
    return s.replace("\n", "\\n").replace("\r", "\\r");
  }

  private static void assertRoute(String pattern, PatternAnalyzer.MatchingStrategy expected)
      throws Exception {
    assertEquals(
        expected,
        StrategyCorrectnessMetaTest.routeOf(pattern),
        "routing changed for /"
            + pattern
            + "/ — the DFA_UNROLLED greedy fix would not be"
            + " exercised");
  }

  // The original fuzz finding: greedy star consumes the final '\n', $/\Z hold at end.
  @Test
  void originalFuzzPattern() {
    assertFindSpan("[^_-ab-c]*$\\Z", "ccc0_1\n00a\n");
  }

  @Test
  void doubleAnchorDoesNotTruncateGreedyRun() throws Exception {
    assertRoute("[^a]*$\\Z", PatternAnalyzer.MatchingStrategy.DFA_UNROLLED);
    assertFindSpan("[^a]*$\\Z", "\n");
    assertFindSpan("[^a]*$\\Z", "x\n");
    assertFindSpan("[^a]*$\\Z", "xy\nz\n");
    assertFindSpan("[^a]{2,}$\\Z", "xy\n");
    assertFindSpan("[^a]*\\Z\\z", "x\n");
  }

  @Test
  void crlfTerminator() {
    // \r\n is a single final terminator: $/\Z hold at len-2; the greedy run may still
    // consume both characters and end at len.
    assertFindSpan("[^a]*$\\Z", "x\r\n");
    assertFindSpan("[^a]*$\\Z", "\r\n");
    assertFindSpan("[^a]*\\Z", "x\r\n");
  }

  @Test
  void nestedRunsBeforeEndAnchors() {
    assertFindSpan("[a-z\\s]+\\d*$\\Z", "ab\n");
    assertFindSpan("[\\s\\S]*$\\Z", "x\n");
    assertFindSpan("(?s:.)*$\\Z", "x\n");
    assertFindSpan("[a-z]*\\s*$\\Z", "ab \n");
  }

  @Test
  void nonTerminatorRunsUnaffected() {
    // Runs whose charset cannot consume the terminator: the before-terminator acceptance
    // is the greedy end — unchanged behavior, kept as regression control.
    assertFindSpan("x*$\\Z", "xx\n");
    assertFindSpan("a*$\\Z", "aa\n");
    assertFindSpan(".*$\\Z", "x\n");
    assertFindSpan("a$", "aa\n");
    assertFindSpan("a$\\Z", "a\n");
  }

  @Test
  void singleAnchorStillRoutesToSuffixSequence() throws Exception {
    // Control: single trailing anchor keeps its dedicated backward-scan route.
    assertRoute("[^a]*$", PatternAnalyzer.MatchingStrategy.SPECIALIZED_SUFFIX_SEQUENCE);
    assertRoute("[^a]*\\Z", PatternAnalyzer.MatchingStrategy.SPECIALIZED_SUFFIX_SEQUENCE);
    assertFindSpan("[^a]*$", "x\n");
    assertFindSpan("[^a]*\\Z", "x\n");
  }

  @Test
  void bareEndAnchors() {
    assertFindSpan("\\Z", "\n");
    assertFindSpan("$", "\n");
    assertFindSpan("\\Z", "x");
    assertFindSpan("$", "x\ny");
  }
}
