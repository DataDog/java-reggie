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
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that ONEPASS_NFA patterns containing anchors ({@code ^}, {@code $}, {@code \A}, {@code
 * \Z}, {@code \z}) produce correct {@code find()} results via the new single-pass {@code matchFrom}
 * delegation path in {@link
 * com.datadoghq.reggie.codegen.codegen.OnePassBytecodeGenerator#generateMatchFromMethod}.
 *
 * <p>The test confirms the core assumption in {@code generateMatchFromMethod}: all anchor types
 * present in ONEPASS_NFA patterns are handled by epsilon-chain processing, so no separate
 * post-match anchor validation is needed in {@code findFrom}.
 *
 * <p>Each case confirms routing to {@link PatternAnalyzer.MatchingStrategy#ONEPASS_NFA} before
 * asserting behavioral parity with {@link java.util.regex.Pattern}.
 */
public class OnePassNfaAnchorFindTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // --- Routing confirmations ---

  @Test
  void endAnchorWithCapturingGroup_routesToOnepassNfa() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        StrategyCorrectnessMetaTest.routeOf("(a)$"),
        "(a)$ must route to ONEPASS_NFA");
  }

  @Test
  void startAnchorWithCapturingGroup_routesToOnepassNfa() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        StrategyCorrectnessMetaTest.routeOf("^(a)"),
        "^(a) must route to ONEPASS_NFA");
  }

  @Test
  void stringEndAnchorWithCapturingGroup_routesToOnepassNfa() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        StrategyCorrectnessMetaTest.routeOf("(a)\\Z"),
        "(a)\\Z must route to ONEPASS_NFA");
  }

  @Test
  void absoluteEndAnchorWithCapturingGroup_routesToOnepassNfa() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        StrategyCorrectnessMetaTest.routeOf("(a)\\z"),
        "(a)\\z must route to ONEPASS_NFA");
  }

  @Test
  void startAndEndAnchorsWithCapturingGroup_routesToOnepassNfa() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        StrategyCorrectnessMetaTest.routeOf("^(a)$"),
        "^(a)$ must route to ONEPASS_NFA");
  }

  // --- Behavioral parity: find() with end-anchor ---

  @Test
  void dollarAnchorWithCapturingGroup_findMatchesParity() {
    expectFindMatch("(a)$", "xa", 1, 2);
    expectFindNone("(a)$", "xab");
    expectFindNone("(a)$", "");
  }

  @Test
  void startAnchorWithCapturingGroup_findMatchesParity() {
    expectFindMatch("^(a)", "ax", 0, 1);
    expectFindNone("^(a)", "ba");
    expectFindNone("^(a)", "");
  }

  @Test
  void startAndEndAnchors_findMatchesParity() {
    expectFindMatch("^(a)$", "a", 0, 1);
    expectFindNone("^(a)$", "ab");
    expectFindNone("^(a)$", "ba");
    expectFindNone("^(a)$", "");
  }

  @Test
  void stringEndAnchorZ_findMatchesParity() {
    expectFindMatch("(a)\\Z", "xa", 1, 2);
    // \Z also matches before a single trailing '\n'
    expectFindMatch("(a)\\Z", "xa\n", 1, 2);
    expectFindNone("(a)\\Z", "xab");
  }

  @Test
  void absoluteEndAnchorz_findMatchesParity() {
    expectFindMatch("(a)\\z", "xa", 1, 2);
    // \z does NOT match before trailing '\n' (unlike \Z)
    expectFindNone("(a)\\z", "xa\n");
    expectFindNone("(a)\\z", "xab");
  }

  @Test
  void charClassWithEndAnchor_findMatchesParity() {
    expectFindMatch("([a-z])$", "xb", 1, 2);
    expectFindNone("([a-z])$", "xb1");
    expectFindMatch("([a-z])$", "b", 0, 1);
  }

  @Test
  void charClassWithStartAnchor_findMatchesParity() {
    expectFindMatch("^([a-z])", "bx", 0, 1);
    expectFindNone("^([a-z])", "1bx");
    expectFindNone("^([a-z])", "");
  }

  // --- Helpers ---

  private static void expectFindMatch(String regex, String input, int start, int end) {
    Pattern jdk = Pattern.compile(regex);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatched = jm.find();
    if (!(jdkMatched && jm.start() == start && jm.end() == end)) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK find('"
              + input
              + "') for /"
              + regex
              + "/ expected ["
              + start
              + ","
              + end
              + ") but got "
              + (jdkMatched ? "[" + jm.start() + "," + jm.end() + ")" : "no match"));
    }
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    MatchResult mr = m.findMatch(input);
    assertEquals(
        "[" + start + "," + end + ")",
        mr == null ? "none" : "[" + mr.start() + "," + mr.end() + ")",
        () ->
            "Reggie find('"
                + input
                + "') for /"
                + regex
                + "/ should be ["
                + start
                + ","
                + end
                + ")");
  }

  private static void expectFindNone(String regex, String input) {
    Pattern jdk = Pattern.compile(regex);
    if (jdk.matcher(input).find()) {
      throw new IllegalArgumentException(
          "Test premise wrong: JDK matched /" + regex + "/ on '" + input + "'");
    }
    ReggieMatcher m = Reggie.compile(regex, WITH_FALLBACK);
    MatchResult mr = m.findMatch(input);
    assertEquals(
        null, mr, () -> "Reggie find('" + input + "') for /" + regex + "/ should not match");
  }
}
