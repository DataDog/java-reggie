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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for MatchResult API completeness: every pattern accepted by Reggie.compile()
 * must support the full public API without throwing UnsupportedOperationException or returning null
 * from methods that should return a MatchResult when matches() is true.
 */
class MatchResultApiCompletenessTest {

  @BeforeEach
  void clearCache() {
    Reggie.clearCache();
  }

  // ── VARIABLE_CAPTURE_BACKREF ──────────────────────────────────────────────

  @Test
  void variableCaptureBackref_match_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("(.+)=\\1");
    assertTrue(m.matches("abc=abc"), "matches() must return true first");
    MatchResult result = assertDoesNotThrow(() -> m.match("abc=abc"));
    assertNotNull(result, "match() must not return null when pattern matches");
    assertEquals("abc=abc", result.group());
  }

  @Test
  void variableCaptureBackref_findMatch_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("(.+)=\\1");
    MatchResult result = assertDoesNotThrow(() -> m.findMatch("xx abc=abc yy"));
    assertNotNull(result, "findMatch() must find the embedded match");
    assertEquals("abc=abc", result.group());
  }

  @Test
  void variableCaptureBackref_matchesBounded_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("(.+)=\\1");
    String input = "XXabc=abcXX";
    boolean r = assertDoesNotThrow(() -> m.matchesBounded(input, 2, 9));
    assertTrue(r, "matchesBounded must match the 'abc=abc' region");
  }

  @Test
  void variableCaptureBackref_matchBounded_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("(.+)=\\1");
    String input = "XXabc=abcXX";
    MatchResult r = assertDoesNotThrow(() -> m.matchBounded(input, 2, 9));
    assertNotNull(r);
    assertEquals("abc=abc", r.group());
  }

  @Test
  void variableCaptureBackref_findMatchFrom_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("(.+)=\\1");
    MatchResult r = assertDoesNotThrow(() -> m.findMatchFrom("aa abc=abc bb", 3));
    assertNotNull(r);
    assertEquals("abc=abc", r.group());
  }

  // ── SPECIALIZED_BACKREFERENCE ─────────────────────────────────────────────

  @Test
  void specializedBackref_match_returnsNonNull() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("<(\\w+)>.*</\\1>");
    assertTrue(m.matches("<b>hello</b>"), "matches() must return true first");
    MatchResult result = assertDoesNotThrow(() -> m.match("<b>hello</b>"));
    assertNotNull(result, "match() must not return null when pattern matches");
    assertEquals("<b>hello</b>", result.group());
  }

  @Test
  void specializedBackref_matchesBounded_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("<(\\w+)>.*</\\1>");
    String input = "XX<b>hi</b>XX";
    boolean r = assertDoesNotThrow(() -> m.matchesBounded(input, 2, 11));
    assertTrue(r);
  }

  @Test
  void specializedBackref_matchBounded_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("<(\\w+)>.*</\\1>");
    String input = "XX<b>hi</b>XX";
    MatchResult r = assertDoesNotThrow(() -> m.matchBounded(input, 2, 11));
    assertNotNull(r);
    assertEquals("<b>hi</b>", r.group());
  }

  @Test
  void specializedBackref_findMatch_returnsCorrectSpan() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("<(\\w+)>.*</\\1>");
    MatchResult r = assertDoesNotThrow(() -> m.findMatch("text <b>bold</b> more"));
    assertNotNull(r);
    assertEquals("<b>bold</b>", r.group());
  }

  // ── NESTED_QUANTIFIED_GROUPS ──────────────────────────────────────────────

  @Test
  void nestedQuantifiedGroups_match_doesNotThrow() {
    // ((a+)+) routes to NESTED_QUANTIFIED_GROUPS
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("((a+)+)");
    assertTrue(m.matches("aaa"));
    MatchResult r = assertDoesNotThrow(() -> m.match("aaa"));
    assertNotNull(r);
    assertEquals("aaa", r.group());
  }

  @Test
  void nestedQuantifiedGroups_findMatch_doesNotThrow() {
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("((a+)+)");
    MatchResult r = assertDoesNotThrow(() -> m.findMatch("xaaax"));
    assertNotNull(r);
    assertEquals("aaa", r.group());
  }
}
