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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the SPECIALIZED_BOUNDED_QUANTIFIERS fast path emitting silent wrong answers
 * when a ranged quantifier {m,n} (n>m) over a char class overshoots into a following element whose
 * first-character set overlaps the quantified class. Each case asserts both find() and matches()
 * agree with java.util.regex.
 */
class BoundedQuantifierBacktrackTest {

  private static void assertMatchesJdk(String pattern, String input) {
    boolean jdkFind = Pattern.compile(pattern).matcher(input).find();
    boolean jdkMatches = Pattern.compile(pattern).matcher(input).matches();
    var reggie = Reggie.compile(pattern);
    assertEquals(
        jdkFind, reggie.find(input), "find() mismatch for /" + pattern + "/ on \"" + input + "\"");
    assertEquals(
        jdkMatches,
        reggie.matches(input),
        "matches() mismatch for /" + pattern + "/ on \"" + input + "\"");
  }

  // ---- Confirmed-broken ranged-overlap cases (must now be correct) ----

  @Test
  void rangedOverlapAb() {
    assertMatchesJdk("[ab]{1,2}b", "ab");
  }

  @Test
  void rangedOverlapWithPrefix() {
    assertMatchesJdk("x[ab]{1,2}b", "xab");
  }

  @Test
  void rangedOverlap0cb() {
    assertMatchesJdk("[0cb]{2,3}c", "0bc");
  }

  @Test
  void rangedOverlap0ca() {
    assertMatchesJdk("[0ca]{2,3}c", "0ac");
  }

  // ---- Extra ranged-overlap variants ----

  @Test
  void rangedOverlapTrailingLiteralSeq() {
    assertMatchesJdk("[ab]{1,3}ab", "aab");
  }

  @Test
  void rangedOverlapDigitClass() {
    assertMatchesJdk("[0-9]{1,2}5", "15");
  }

  @Test
  void rangedOverlapDigitClassNoMatch() {
    assertMatchesJdk("[0-9]{1,2}5", "12");
  }

  @Test
  void rangedOverlapRangeFollowedByLiteral() {
    assertMatchesJdk("[a-c]{1,2}c", "ac");
  }

  // ---- Confirmed-OK cases (must not regress) ----

  @Test
  void exactCountNoOvershoot() {
    assertMatchesJdk("[ab]{2}b", "aab");
  }

  @Test
  void plusQuantifier() {
    assertMatchesJdk("[ab]+b", "aab");
  }

  @Test
  void starQuantifier() {
    assertMatchesJdk("[ab]*b", "b");
  }

  @Test
  void literalRanged() {
    assertMatchesJdk("a{2,3}a", "aaa");
  }

  @Test
  void rangedOverlapAbc() {
    assertMatchesJdk("[abc]{1,2}c", "abc");
  }

  @Test
  void groupRanged() {
    assertMatchesJdk("(?:ab){1,2}c", "abc");
  }

  @Test
  void disjointControl() {
    assertMatchesJdk("[xy]{1,2}z", "xyz");
  }
}
