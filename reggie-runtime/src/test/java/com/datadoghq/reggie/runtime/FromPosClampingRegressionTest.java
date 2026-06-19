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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for fromPos clamping in {@code findFrom} / {@code findMatchFrom}.
 *
 * <p>Covers {@code PikeVMMatcher} (routed via {@code PIKEVM_CAPTURE}) and {@code
 * BackrefBacktrackMatcher} (routed via {@code OPTIMIZED_NFA_WITH_BACKREFS}) to verify both clamp
 * negative starts to 0 and return -1/null for starts past end, matching the JDK contract.
 */
class FromPosClampingRegressionTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // -------------------------------------------------------------------------
  // T1 — findFrom with negative start clamps to 0 (PikeVMMatcher path)
  // -------------------------------------------------------------------------

  @Test
  void findFrom_negativeStart_clampsToZero() {
    ReggieMatcher m = Reggie.compile("a", WITH_FALLBACK);
    assertEquals(0, m.findFrom("abc", -1), "negative start -1 must clamp to 0");
    assertEquals(0, m.findFrom("abc", -5), "negative start -5 must clamp to 0");
  }

  // -------------------------------------------------------------------------
  // T2 — findFrom with start past end returns -1 (PikeVMMatcher path)
  // -------------------------------------------------------------------------

  @Test
  void findFrom_startPastEnd_returnsMinusOne() {
    ReggieMatcher m = Reggie.compile("a", WITH_FALLBACK);
    assertEquals(-1, m.findFrom("abc", 10), "start past end must return -1");
    assertEquals(-1, m.findFrom("", 1), "start past empty string must return -1");
  }

  // -------------------------------------------------------------------------
  // T3 — findMatchFrom with negative start returns match at 0 (PikeVMMatcher path)
  // -------------------------------------------------------------------------

  @Test
  void findMatchFrom_negativeStart_returnsMatchAtZero() {
    ReggieMatcher m = Reggie.compile("a", WITH_FALLBACK);
    MatchResult r = m.findMatchFrom("abc", -3);
    assertNotNull(r, "negative start clamped to 0 should find match");
    assertEquals(0, r.start());
    assertEquals(1, r.end());
  }

  // -------------------------------------------------------------------------
  // T4 — findMatchFrom with start past end returns null (PikeVMMatcher path)
  // -------------------------------------------------------------------------

  @Test
  void findMatchFrom_startPastEnd_returnsNull() {
    ReggieMatcher m = Reggie.compile("a", WITH_FALLBACK);
    assertNull(m.findMatchFrom("abc", 100), "start past end must return null");
  }

  // -------------------------------------------------------------------------
  // T5 — Boundary: start == input.length() with zero-length pattern
  // -------------------------------------------------------------------------

  @Test
  void findFrom_startEqualsLength_zeroLengthPattern() {
    ReggieMatcher m = Reggie.compile("a*", WITH_FALLBACK);
    assertEquals(3, m.findFrom("abc", 3), "start == length must find zero-length match at end");
  }

  // -------------------------------------------------------------------------
  // T6 — Boundary: start == 0 on empty input with zero-length pattern
  // -------------------------------------------------------------------------

  @Test
  void findFrom_startZero_emptyInput_zeroLengthPattern() {
    ReggieMatcher m = Reggie.compile("a*", WITH_FALLBACK);
    assertEquals(0, m.findFrom("", 0), "start == 0 on empty input must return 0");
  }

  // -------------------------------------------------------------------------
  // T8 — No regression on normal positive-start findFrom
  // -------------------------------------------------------------------------

  @Test
  void findFrom_normalPositiveStart_noRegression() {
    ReggieMatcher m = Reggie.compile("foo", WITH_FALLBACK);
    assertEquals(6, m.findFrom("barbarfoobar", 0), "should find 'foo' at 6 from start 0");
    assertEquals(6, m.findFrom("barbarfoobar", 6), "should find 'foo' at 6 from start 6");
    assertEquals(-1, m.findFrom("barbarfoobar", 7), "should return -1 when no match after start 7");
  }

  // -------------------------------------------------------------------------
  // T9 — BackrefBacktrackMatcher negative start (backref pattern)
  // -------------------------------------------------------------------------

  @Test
  void backrefMatcher_findFrom_negativeStart_clampsToZero() {
    // (a)\1 forces OPTIMIZED_NFA_WITH_BACKREFS / BackrefBacktrackMatcher
    ReggieMatcher m = Reggie.compile("(a)\\1", WITH_FALLBACK);
    assertEquals(0, m.findFrom("aa", -2), "backref: negative start must clamp to 0");
  }

  @Test
  void backrefMatcher_findMatchFrom_negativeStart_returnsMatchAtZero() {
    ReggieMatcher m = Reggie.compile("(a)\\1", WITH_FALLBACK);
    MatchResult r = m.findMatchFrom("aa", -1);
    assertNotNull(r, "backref: negative start clamped to 0 should find match");
    assertEquals(0, r.start());
  }

  // -------------------------------------------------------------------------
  // T10 — JavaRegexFallbackMatcher negative start (lazy quantifier → fallback)
  // -------------------------------------------------------------------------

  @Test
  void fallbackMatcher_findFrom_negativeStart_clampsToZero() {
    // a*?b has a lazy quantifier → RECURSIVE_DESCENT + needsFallback → JavaRegexFallbackMatcher
    ReggieMatcher m = Reggie.compile("a*?b", WITH_FALLBACK);
    assertEquals(0, m.findFrom("ab", -1), "fallback: negative start must clamp to 0");
  }

  @Test
  void fallbackMatcher_findFrom_startPastEnd_returnsMinusOne() {
    ReggieMatcher m = Reggie.compile("a*?b", WITH_FALLBACK);
    assertEquals(-1, m.findFrom("ab", 10), "fallback: start past end must return -1");
  }

  @Test
  void fallbackMatcher_findMatchFrom_negativeStart_returnsMatchAtZero() {
    ReggieMatcher m = Reggie.compile("a*?b", WITH_FALLBACK);
    MatchResult r = m.findMatchFrom("ab", -1);
    assertNotNull(r, "fallback: negative start clamped to 0 should find match");
    assertEquals(0, r.start());
  }
}
