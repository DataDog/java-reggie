/*
 * Copyright 2026-Present Datadog, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for NUL ({@code \u0000}) literals in patterns.
 *
 * <p>History: the parser represented the epsilon sentinel as {@code new LiteralNode((char) 0)},
 * which collided with a genuine NUL literal — a raw NUL character (or a {@code \x00}/{@code \0}
 * escape) parsed to the epsilon node and was silently dropped from the pattern. The pattern
 * {@code "\0"} compiled to a matcher that matched everything, and a consumer pattern such as
 * logs-backend's {@code replaceAll("\u0000", "")} would have stripped entire strings. Epsilon is
 * now a distinct {@code EpsilonNode} type, so NUL literals are ordinary literals that match the
 * NUL character, matching {@code java.util.regex} semantics.
 */
class NulLiteralRegressionTest {

  private static final String NUL = String.valueOf((char) 0);

  @Test
  void nulOnlyPattern_matchesOnlyNul() {
    ReggieMatcher matcher = Reggie.compile(NUL);
    assertFalse(matcher.find("abc"), "pre-fix: matched everything");
    assertFalse(matcher.matches(""));
    assertTrue(matcher.find("a" + NUL + "b"));
    assertTrue(matcher.matches(NUL));
  }

  @Test
  void nulLiteralsInLargerPatterns() {
    assertFalse(Reggie.compile(NUL + "a").find("ax"));
    assertTrue(Reggie.compile(NUL + "a").find(NUL + "a"));
    assertFalse(Reggie.compile("a" + NUL).find("a"));
    assertTrue(Reggie.compile("a" + NUL).find("za" + NUL));
    assertFalse(Reggie.compile("a" + NUL + "b").find("axb"));
    assertTrue(Reggie.compile("a" + NUL + "b").find("za" + NUL + "bz"));
  }

  @Test
  void nulEscapes() {
    assertFalse(Reggie.compile("\\x00").find("abc"), "pre-fix: \\x00 matched everything");
    assertTrue(Reggie.compile("\\x00").find("x" + NUL + "y"));
    assertTrue(Reggie.compile("a\\x00b").matches("a" + NUL + "b"));
    assertFalse(Reggie.compile("a\\x00b").matches("axb"));
    // octal \0 form
    assertTrue(Reggie.compile("a\\0b").matches("a" + NUL + "b"));
    // character class containing NUL
    assertTrue(Reggie.compile("[" + NUL + "]").find(NUL + "y"));
    assertFalse(Reggie.compile("[" + NUL + "]").find("y"));
    // alternation
    assertTrue(Reggie.compile("a|\\x00").find("q" + NUL + "q"));
    assertTrue(Reggie.compile("(\\x00)(a)").matches(NUL + "a"));
    assertEquals("a", Reggie.compile("(\\x00)(a)").match(NUL + "a").group(2));
  }

  @Test
  void consumerSemantics_replaceAllAndSplit() {
    // logs-backend Utils.replaceAll("\u0000", "") must strip only the NUL characters.
    assertEquals("abc", Reggie.compile(NUL).replaceAll("a" + NUL + "b" + NUL + "c", ""));
    assertEquals("ab", Reggie.compile(NUL).replaceAll("ab", ""));
    // profiling-backend TraceProcessorIntegrationTest split("\0") on string-cell tables.
    String cells = "foo" + NUL + "bar" + NUL + "baz";
    assertArrayEquals(
        java.util.regex.Pattern.compile(NUL).split(cells, -1),
        Reggie.compile(NUL).split(cells, -1));
  }

  @Test
  void nulInInputDoesNotLeakIntoOtherLiterals() {
    // A plain literal pattern must not match across a NUL in the input.
    assertFalse(Reggie.compile("ab").find("a" + NUL + "b"));
    assertTrue(Reggie.compile("ab").find("ab"));
  }
}
