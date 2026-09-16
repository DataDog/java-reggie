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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure-literal specialization of SPECIALIZED_FIXED_SEQUENCE: when every element is a groupless
 * literal with no word-boundary anchors (e.g. {@code (?:abc){100}}, {@code a{5}}), the pattern is
 * one fixed string and findFrom is emitted as {@code String.indexOf(literal, start)} (HotSpot SIMD
 * intrinsic) and matches as {@code String.equals(literal)}. Guard checks pin the non-literal shapes
 * (\d{3}-\d{3}-\d{4}, \bfoo\b, capture groups) to the unrolled per-char emission.
 */
class PureLiteralFixedSequenceBytecodeTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void pureLiteralShapesRouteFixedSequence() throws Exception {
    for (String regex :
        new String[] {"(?:abc){100}", "(?:xy){3}", "(?:ab)(?:cd)", "(?:abc)(?:def)"}) {
      assertEquals(
          PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE,
          StrategyCorrectnessMetaTest.routeOf(regex),
          () -> "/" + regex + "/ should route to SPECIALIZED_FIXED_SEQUENCE");
    }
  }

  @Test
  void findAndSpansMatchJdk() {
    assertFindParity("(?:abc){100}");
    assertFindParity("a{5}");
    assertFindParity("(?:xy){3}");
    assertFindParity("(?:ab)(?:cd)");
  }

  private static void assertFindParity(String regex) {
    String lit100 = "abc".repeat(100);
    String[] inputs = {
      "x" + lit100 + "y",
      lit100 + "y",
      "x".repeat(301),
      "ab".repeat(100) + "x",
      "",
      "abcabc" + lit100,
      "abcabcabx" + lit100,
      "xxaaaaax",
      "aaaaa",
    };
    for (String input : inputs) {
      assertParity(regex, input);
    }
  }

  private static void assertParity(String regex, String input) {
    ReggieMatcher reggie = (ReggieMatcher) Reggie.compile(regex);
    Pattern jdk = Pattern.compile(regex);

    // find()
    assertEquals(jdk.matcher(input).find(), reggie.find(input), "find " + regex + " | " + input);

    for (int start : new int[] {0, 1, 2, 50, 99, 150, 9999}) {
      int clamped = Math.min(Math.max(start, 0), input.length());
      Matcher jm = jdk.matcher(input);
      boolean expected = jm.find(clamped);
      int idx = reggie.findFrom(input, start);
      assertEquals(expected, idx >= 0, "findFrom@" + start + " " + regex + " | " + input);
      if (expected) {
        assertEquals(jm.start(), idx, "findFrom@" + start + " start " + regex + " | " + input);
      }

      com.datadoghq.reggie.runtime.MatchResult mr = reggie.findMatchFrom(input, start);
      Matcher jm2 = jdk.matcher(input);
      boolean expected2 = jm2.find(clamped);
      assertEquals(expected2, mr != null, "findMatchFrom@" + start + " " + regex + " | " + input);
      if (expected2) {
        assertEquals(jm2.start(), mr.start(), "span start");
        assertEquals(jm2.end(), mr.end(), "span end");
      }
    }

    // matches()
    assertEquals(jdk.matcher(input).matches(), reggie.matches(input), "matches " + input);

    // findBoundsFrom + findAll
    int[] bounds = new int[2];
    boolean rb = reggie.findBoundsFrom(input, 0, bounds);
    Matcher jm3 = jdk.matcher(input);
    boolean jb = jm3.find();
    assertEquals(jb, rb, "findBoundsFrom " + regex + " | " + input);
    if (jb) {
      assertEquals(jm3.start(), bounds[0], "bounds start");
      assertEquals(jm3.end(), bounds[1], "bounds end");
    }
    int reggieCount = 0;
    for (com.datadoghq.reggie.runtime.MatchResult ignored : reggie.findAll(input)) {
      reggieCount++;
    }
    int jdkCount = 0;
    for (Matcher m = jdk.matcher(input); m.find(); ) {
      jdkCount++;
    }
    assertEquals(jdkCount, reggieCount, "findAll count " + regex + " | " + input);
  }

  @Test
  void matchesExactLiteralOnly() {
    ReggieMatcher reggie = (ReggieMatcher) Reggie.compile("(?:abc){2}");
    assertTrue(reggie.matches("abcabc"));
    assertFalse(reggie.matches("abcabcd"));
    assertFalse(reggie.matches("xabcabc"));
    assertFalse(reggie.matches(""));
    assertFalse(reggie.matches("ABCABC"));
    assertFalse(reggie.matches(null));
  }

  @Test
  void pureLiteralNegativeStartClampsToZero() {
    // String.indexOf(str, from) treats a negative from as 0, matching the old unrolled
    // emission's explicit clamp — both give the find(input) result.
    ReggieMatcher pure = (ReggieMatcher) Reggie.compile("(?:ab){2}");
    assertEquals(1, pure.findFrom("zababx", -5));
    assertEquals(1, pure.findFrom("zababx", -1));
    assertEquals(1, pure.findFrom("zababx", 0));
    assertEquals(1, pure.findFrom("zababx", 1));
    assertEquals(-1, pure.findFrom("zababx", 3));
    assertEquals(-1, pure.findFrom("zababx", 5));
  }

  @Test
  void nonPureShapesKeepUnrolledEmission() {
    // charset repetitions: no literal, must stay on the per-char unrolled path
    assertParity("\\d{3}-\\d{3}-\\d{4}", "call 123-456-7890 now");
    assertParity("\\d{3}-\\d{3}-\\d{4}", "12-34-5678");
    // word-boundary anchored literals: boundary checks must survive
    assertParity("\\bfoo\\b", "foo bar foo!");
    assertParity("\\bfoo\\b", "xfoox");
    // capturing group in the sequence: group spans must be recorded (not pure-literal)
    assertParity("(ab){2}", "abab");
    assertParity("(ab){2}", "zababx");
    ReggieMatcher grouped = (ReggieMatcher) Reggie.compile("(ab){2}");
    assertNull(grouped.findMatch("nope"));
    com.datadoghq.reggie.runtime.MatchResult gr = grouped.findMatch("zababx");
    assertEquals(1, gr.groupCount());
    assertEquals("ab", gr.group(1));
    assertEquals(3, gr.start(1));
    assertEquals(5, gr.end(1));
  }
}
