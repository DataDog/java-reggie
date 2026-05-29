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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchIntoAPITest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void matchIntoCopiesWholeMatchAndCaptureGroups() {
    ReggieMatcher matcher = Reggie.compile("(\\d{2})-([a-z]+)");
    int[] starts = new int[3];
    int[] ends = new int[3];

    assertTrue(matcher.matchInto("12-abc", starts, ends));

    assertArrayEquals(new int[] {0, 0, 3}, starts);
    assertArrayEquals(new int[] {6, 2, 6}, ends);
  }

  @Test
  void matchIntoLeavesArraysUnchangedWhenThereIsNoMatch() {
    ReggieMatcher matcher = Reggie.compile("(a)b");
    int[] starts = new int[] {7, 8};
    int[] ends = new int[] {9, 10};

    assertFalse(matcher.matchInto("ac", starts, ends));

    assertArrayEquals(new int[] {7, 8}, starts);
    assertArrayEquals(new int[] {9, 10}, ends);
  }

  @Test
  void findMatchIntoCopiesFoundMatchAndCaptureGroups() {
    ReggieMatcher matcher = Reggie.compile("(\\d+)-([a-z]+)");
    int[] starts = new int[3];
    int[] ends = new int[3];

    assertTrue(matcher.findMatchInto("xx123-abc yy", 2, starts, ends));

    assertArrayEquals(new int[] {2, 2, 6}, starts);
    assertArrayEquals(new int[] {9, 5, 9}, ends);
  }

  @Test
  void dfaSwitchMatcherOverridesMatchInto() throws Exception {
    ReggieMatcher matcher = Reggie.compile("([a-z]|[0-9]|[A-Z]|_){10}x");
    int[] starts = new int[2];
    int[] ends = new int[2];

    assertNotEquals(
        ReggieMatcher.class,
        matcher
            .getClass()
            .getMethod("matchInto", String.class, int[].class, int[].class)
            .getDeclaringClass());
    assertTrue(matcher.matchInto("abcdefghi1x", starts, ends));

    MatchResult match = matcher.match("abcdefghi1x");
    assertArrayEquals(new int[] {match.start(0), match.start(1)}, starts);
    assertArrayEquals(new int[] {match.end(0), match.end(1)}, ends);
  }

  @Test
  void nfaMatcherOverridesMatchInto() throws Exception {
    ReggieMatcher matcher = Reggie.compile("(?=.*[0-9])([a-z]+)([0-9]+)");
    int[] starts = new int[3];
    int[] ends = new int[3];

    assertNotEquals(
        ReggieMatcher.class,
        matcher
            .getClass()
            .getMethod("matchInto", String.class, int[].class, int[].class)
            .getDeclaringClass());
    assertTrue(matcher.matchInto("abc123", starts, ends));

    MatchResult match = matcher.match("abc123");
    assertArrayEquals(new int[] {match.start(0), match.start(1), match.start(2)}, starts);
    assertArrayEquals(new int[] {match.end(0), match.end(1), match.end(2)}, ends);
  }

  @Test
  void recursiveDescentMatcherOverridesMatchInto() throws Exception {
    ReggieMatcher matcher = Reggie.compile("(a(?R)?b)");
    int[] starts = new int[2];
    int[] ends = new int[2];

    assertNotEquals(
        ReggieMatcher.class,
        matcher
            .getClass()
            .getMethod("matchInto", String.class, int[].class, int[].class)
            .getDeclaringClass());
    assertTrue(matcher.matchInto("aabb", starts, ends));

    MatchResult match = matcher.match("aabb");
    assertArrayEquals(new int[] {match.start(0), match.start(1)}, starts);
    assertArrayEquals(new int[] {match.end(0), match.end(1)}, ends);
  }

  @Test
  void tooSmallArraysThrowOnSuccessfulMatch() {
    ReggieMatcher matcher = Reggie.compile("(a)(b)");
    int[] starts = new int[2];
    int[] ends = new int[3];

    assertThrows(IndexOutOfBoundsException.class, () -> matcher.matchInto("ab", starts, ends));
  }

  @Test
  void javaRegexFallbackMatcherPopulatesCallerArrays() {
    ReggieMatcher matcher = new JavaRegexFallbackMatcher("(a)?b", "test");
    int[] starts = new int[2];
    int[] ends = new int[2];

    assertTrue(matcher.matchInto("b", starts, ends));

    assertArrayEquals(new int[] {0, -1}, starts);
    assertArrayEquals(new int[] {1, -1}, ends);
  }
}
