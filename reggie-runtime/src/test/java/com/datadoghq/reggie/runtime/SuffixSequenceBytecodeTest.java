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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPECIALIZED_SUFFIX_SEQUENCE: [optional literals] + unbounded class run + $/\Z/\z (e.g. {@code
 * \.?0+$}, {@code \s+$}) compiles to a greedy backward scan from the anchor end(s) instead of the
 * forward candidate loop. Covers the TrailingZeros benchmark shape: the forward DFA scan walked the
 * trailing zero run once per first-char candidate; the backward scan walks it once.
 */
class SuffixSequenceBytecodeTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void suffixRunShapesRouteSuffixSequence() throws Exception {
    for (String regex :
        new String[] {
          "\\.?0+$",
          "0+$",
          "\\s+$",
          "0*$",
          "\\.?\\s*$",
          "x?[0-9]+$",
          "\\.?0{2,}$",
          "[^x]+$",
          "x?y?[0-9]+$"
        }) {
      assertEquals(
          PatternAnalyzer.MatchingStrategy.SPECIALIZED_SUFFIX_SEQUENCE,
          StrategyCorrectnessMetaTest.routeOf(regex),
          () -> "/" + regex + "/ should route to SPECIALIZED_SUFFIX_SEQUENCE");
    }
  }

  @Test
  void nonSuffixShapesStayOnTheirRoutes() throws Exception {
    // No unbounded trailing run / bounded tail / mandatory prefix / multiline / other anchors:
    // these keep their existing routes (the admission must not steal DFA winners).
    for (String regex :
        new String[] {
          "xyz#$", // end-anchored literal concat: DFA_UNROLLED wins today (AtEndConcat)
          "$", // bare end anchor
          "[0-9]{2}$", // bounded tail rep
          "00+$", // mandatory literal before the run (needs run backtracking)
          "^0+$", // leading start anchor
          "(?m)0+$", // multiline $
          "0+$x", // anchor not last
          "(0)+$", // capturing group
          "\\.?0+?$" // lazy run
        }) {
      assertNotEquals(
          PatternAnalyzer.MatchingStrategy.SPECIALIZED_SUFFIX_SEQUENCE,
          StrategyCorrectnessMetaTest.routeOf(regex),
          () -> "/" + regex + "/ must not route to SPECIALIZED_SUFFIX_SEQUENCE");
    }
  }

  @Test
  void findAndSpansMatchJdk() {
    String z97 = "0".repeat(97);
    assertParity("\\.?0+$", "12345.6789012345678901234567890" + z97); // benchmark input
    assertParity("\\.?0+$", "abc.000");
    assertParity("\\.?0+$", "a000");
    assertParity("\\.?0+$", "000");
    assertParity("\\.?0+$", ".000");
    assertParity("\\.?0+$", "no zeros!");
    assertParity("\\.?0+$", "");
    assertParity("\\.?0+$", "0");
    assertParity("\\.?0+$", ".");
    assertParity("\\.?0+$", "x.0.000");
    assertParity("\\.?0+$", "0.000");
    // Final line terminators: $ and \Z also match before the final terminator.
    assertParity("\\.?0+$", "000\n");
    assertParity("\\.?0+$", ".000\n");
    assertParity("\\.?0+$", "000\r\n");
    assertParity("\\.?0+$", "000\r");
    assertParity("\\.?0+$", "0\u0085");
    assertParity("\\.?0+$", "0\u2028");
    assertParity("\\.?0+$", "00\r\n0");
    assertParity("0+$", "a000b0000");
    assertParity("0+$", "");
    assertParity("0\\z", "ab000");
    assertParity("0\\z", "ab000\n"); // \z: absolute end only - no match
    assertParity("0\\Z", "ab000\n"); // \Z: before final terminator allowed
    assertParity("\\s+$", "x  \t ");
    assertParity("\\s+$", "no trailing ws");
    assertParity("\\s*$", "abc");
    assertParity("\\s*$", "");
    assertParity("0*$", "abc");
    assertParity("0*$", "000");
    assertParity("\\.?\\s*$", "abc  ");
    assertParity("x?[0-9]+$", "ab12");
    assertParity("x?[0-9]+$", "ab12x");
    assertParity("x?y?[0-9]+$", "y12");
    assertParity("\\.?0{2,}$", "x.000");
    assertParity("\\.?0{2,}$", "x0");
    assertParity("\\.?0{2,}$", "x00");
    assertParity("[^x]+$", "abxc");
    assertParity("[^x]+$", "abc");
    // Declined shapes must keep parity too (they route elsewhere).
    assertParity("[0-9]{2}$", "12345");
    assertParity("xyz#$", "abcxyz#");
    assertParity("(?m)0+$", "a\n000");
  }

  private static void assertParity(String regex, String input) {
    ReggieMatcher reggie = (ReggieMatcher) Reggie.compile(regex);
    Pattern jdk = Pattern.compile(regex);

    // find()
    assertEquals(jdk.matcher(input).find(), reggie.find(input), "find " + regex + " | " + input);

    // findFrom + findMatchFrom spans across offsets (start past end -> -1 by contract)
    for (int start : new int[] {0, 1, 2, 3, 5, 8, 97, 200}) {
      int idx = reggie.findFrom(input, start);
      if (start > input.length()) {
        assertEquals(-1, idx, "findFrom@" + start + " past end " + regex + " | " + input);
        assertNull(reggie.findMatchFrom(input, start), "findMatchFrom@" + start + " past end");
        continue;
      }
      Matcher jm = jdk.matcher(input);
      boolean expected = jm.find(start);
      assertEquals(expected, idx >= 0, "findFrom@" + start + " " + regex + " | " + input);
      if (expected) {
        assertEquals(jm.start(), idx, "findFrom@" + start + " start " + regex + " | " + input);
      }
      com.datadoghq.reggie.runtime.MatchResult mr = reggie.findMatchFrom(input, start);
      Matcher jm2 = jdk.matcher(input);
      boolean expected2 = jm2.find(start);
      assertEquals(expected2, mr != null, "findMatchFrom@" + start + " " + regex + " | " + input);
      if (expected2) {
        assertEquals(jm2.start(), mr.start(), "span start " + regex + " | " + input);
        assertEquals(jm2.end(), mr.end(), "span end " + regex + " | " + input);
      }
    }

    // matches(): full region only - "000\n" does NOT fully match \.?0+$
    assertEquals(jdk.matcher(input).matches(), reggie.matches(input), "matches " + input);

    // match(): full match result or null
    com.datadoghq.reggie.runtime.MatchResult mm = reggie.match(input);
    assertEquals(jdk.matcher(input).matches(), mm != null, "match " + regex + " | " + input);
    if (mm != null) {
      assertEquals(0, mm.start(), "match start");
      assertEquals(input.length(), mm.end(), "match end");
    }

    // findBoundsFrom
    int[] bounds = new int[2];
    boolean rb = reggie.findBoundsFrom(input, 0, bounds);
    Matcher jm3 = jdk.matcher(input);
    boolean jb = jm3.find();
    assertEquals(jb, rb, "findBoundsFrom " + regex + " | " + input);
    if (jb) {
      assertEquals(jm3.start(), bounds[0], "bounds start");
      assertEquals(jm3.end(), bounds[1], "bounds end");
    }

    // findAll count
    int reggieCount = 0;
    for (com.datadoghq.reggie.runtime.MatchResult ignored : reggie.findAll(input)) {
      reggieCount++;
    }
    int jdkCount = 0;
    for (Matcher m = jdk.matcher(input); m.find(); ) {
      jdkCount++;
    }
    assertEquals(jdkCount, reggieCount, "findAll count " + regex + " | " + input);

    // matchesBounded: region sub-matches behave like matches on the subregion
    if (input.length() >= 2) {
      int st = 0;
      int en = input.length();
      boolean subMatch = reggie.matchesBounded(input.subSequence(st, en), st, en);
      assertEquals(jdk.matcher(input.substring(st, en)).matches(), subMatch, "matchesBounded");
    }
  }

  @Test
  void negativeStartClampsToZero() {
    ReggieMatcher reggie = (ReggieMatcher) Reggie.compile("\\.?0+$");
    assertEquals(3, reggie.findFrom("abc.000", -5));
    assertEquals(3, reggie.findFrom("abc.000", 0));
    assertEquals(4, reggie.findFrom("abc.000", 4)); // interior run position is a valid start
    assertEquals(5, reggie.findFrom("abc.000", 5)); // still inside the zero run [4, 6]
    assertEquals(6, reggie.findFrom("abc.000", 6)); // last valid start: "0" + $
    assertEquals(-1, reggie.findFrom("abc.000", 7)); // past the run: 0+ needs one char
    assertEquals(-1, reggie.findFrom("abc.000", 999));
  }

  @Test
  void crlfCountsAsOneTerminator() {
    ReggieMatcher reggie = (ReggieMatcher) Reggie.compile("\\.?0+$");
    // $ matches before the \r of a final \r\n, so the run stops before "\r\n" (2 chars).
    com.datadoghq.reggie.runtime.MatchResult m = reggie.findMatch("x000\r\n");
    assertEquals(1, m.start());
    assertEquals(4, m.end()); // excludes the whole "\r\n"
    assertTrue(reggie.matches("000")); // full match without terminator
    assertFalse(reggie.matches("000\n")); // $ before terminator is NOT a full-region match
    assertFalse(reggie.matches("x.000"));
  }
}
