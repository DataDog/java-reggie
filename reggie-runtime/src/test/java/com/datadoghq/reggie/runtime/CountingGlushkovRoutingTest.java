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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * End-to-end routing and correctness tests for the {@code COUNTING_GLUSHKOV} strategy.
 *
 * <p>Routing is verified via {@link StrategyCorrectnessMetaTest#routeOf}, which mirrors the {@link
 * RuntimeCompiler} analysis path. Behavioral correctness is verified differentially against {@link
 * java.util.regex.Pattern}.
 *
 * <p>Patterns that route to {@code COUNTING_GLUSHKOV} must have a body with more than one position
 * and a quantifier bound {@code > 10}. Pure single-char-class repeats (e.g. {@code \d{50}}) route
 * to {@code STATELESS_LOOP}; pure literal repeats (e.g. {@code (?:abc){20}}) route to {@code
 * SPECIALIZED_FIXED_SEQUENCE}.
 */
public class CountingGlushkovRoutingTest {

  // -------------------------------------------------------------------------
  // Routing assertions
  // -------------------------------------------------------------------------

  @Test
  void alphanumPairRepeat20_routesToCountingGlushkov() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV,
        StrategyCorrectnessMetaTest.routeOf("(?:[a-z][0-9]){20}"),
        "(?:[a-z][0-9]){20} must route to COUNTING_GLUSHKOV");
  }

  @Test
  void hexPairColon11_routesToCountingGlushkov() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV,
        StrategyCorrectnessMetaTest.routeOf("(?:[a-f0-9]{2}:){11}"),
        "(?:[a-f0-9]{2}:){11} must route to COUNTING_GLUSHKOV");
  }

  @Test
  void alphanumBodyRepeat150_routesToCountingGlushkov() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.COUNTING_GLUSHKOV,
        StrategyCorrectnessMetaTest.routeOf("(?:[a-z][0-9]){150}"),
        "(?:[a-z][0-9]){150} must route to COUNTING_GLUSHKOV");
  }

  // -------------------------------------------------------------------------
  // (?:[a-z][0-9]){20} — matches / rejects
  // -------------------------------------------------------------------------

  @Test
  void alphanumPairRepeat20_matches_20repetitions() {
    ReggieMatcher m = RuntimeCompiler.compile("(?:[a-z][0-9]){20}");
    assertTrue(m.matches("a0".repeat(20)), "(?:[a-z][0-9]){20} must match 20 repetitions");
  }

  @Test
  void alphanumPairRepeat20_rejects_19repetitions() {
    ReggieMatcher m = RuntimeCompiler.compile("(?:[a-z][0-9]){20}");
    assertFalse(m.matches("a0".repeat(19)), "(?:[a-z][0-9]){20} must not match 19 repetitions");
  }

  @Test
  void alphanumPairRepeat20_rejects_21repetitions() {
    ReggieMatcher m = RuntimeCompiler.compile("(?:[a-z][0-9]){20}");
    assertFalse(m.matches("a0".repeat(21)), "(?:[a-z][0-9]){20} must not match 21 repetitions");
  }

  @Test
  void alphanumPairRepeat20_find_returnsStartOne() {
    ReggieMatcher m = RuntimeCompiler.compile("(?:[a-z][0-9]){20}");
    String input = "X" + "a0".repeat(20) + "Y";
    int start = m.findFrom(input, 0);
    assertEquals(1, start, "(?:[a-z][0-9]){20} find should start at index 1 in \"X<pattern>Y\"");
  }

  // -------------------------------------------------------------------------
  // T1.9 — differential correctness against JDK java.util.regex.Pattern
  // -------------------------------------------------------------------------

  /**
   * Verify matches() agrees with JDK for the given pattern across the exact input, one character
   * short, and one character too many.
   */
  private static void assertDifferential(String regex, String exactInput) {
    Pattern jdk = Pattern.compile(regex);
    ReggieMatcher reggie = RuntimeCompiler.compile(regex);

    // exact match
    assertTrue(reggie.matches(exactInput), "reggie must match exact input for /" + regex + "/");
    assertTrue(jdk.matcher(exactInput).matches(), "JDK must match exact input for /" + regex + "/");

    // one character short
    String shortInput = exactInput.substring(0, exactInput.length() - 1);
    assertEquals(
        jdk.matcher(shortInput).matches(),
        reggie.matches(shortInput),
        "reggie and JDK must agree on one-short input for /" + regex + "/");

    // one character too many (append the first body character)
    String longInput = exactInput + exactInput.charAt(0);
    assertEquals(
        jdk.matcher(longInput).matches(),
        reggie.matches(longInput),
        "reggie and JDK must agree on one-too-many input for /" + regex + "/");
  }

  @Test
  void differential_alphanumPairRepeat20() {
    assertDifferential("(?:[a-z][0-9]){20}", "a0".repeat(20));
  }

  @Test
  void differential_alphanumPairRepeat150() {
    assertDifferential("(?:[a-z][0-9]){150}", "a0".repeat(150));
  }

  @Test
  void differential_hexPairColon11() {
    // (?:[a-f0-9]{2}:){11} — 11 hex-octet-colon units
    assertDifferential("(?:[a-f0-9]{2}:){11}", "ab:".repeat(11));
  }

  @Test
  void differential_find_alphanumPairRepeat20() {
    Pattern jdk = Pattern.compile("(?:[a-z][0-9]){20}");
    ReggieMatcher reggie = RuntimeCompiler.compile("(?:[a-z][0-9]){20}");
    String haystack = "X" + "a0".repeat(20) + "Y";

    java.util.regex.Matcher jdkM = jdk.matcher(haystack);
    assertTrue(jdkM.find(), "JDK must find (?:[a-z][0-9]){20} in haystack");
    int jdkStart = jdkM.start();

    int reggieStart = reggie.findFrom(haystack, 0);
    assertNotEquals(-1, reggieStart, "reggie must find (?:[a-z][0-9]){20} in haystack");
    assertEquals(jdkStart, reggieStart, "reggie and JDK find-start must agree");
  }
}
