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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Acceptance tests for self-referencing backreferences (REQ-DataDog-java-reggie-39).
 *
 * <p>PCRE / JDK semantics: inside an iteration of group N the backreference \N resolves against the
 * capture accumulated so far in the CURRENT iteration, not the capture from a previous iteration.
 * This enables patterns like {@code (a\1?){4}} and {@code ^(a\1?)(a\1?)(a\2?)(a\3?)$} to match.
 */
class SelfReferencingBackrefTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── AC-1: canonical patterns ──────────────────────────────────────────────

  @Test
  void fourGroupsWithSelfRefs_matchesAaaa() {
    ReggieMatcher m = Reggie.compile("^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");
    assertNotNull(m.match("aaaa"), "^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$ must match 'aaaa'");
  }

  @Test
  void fourGroupsWithSelfRefs_matchesAaaaaa() {
    ReggieMatcher m = Reggie.compile("^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");
    assertNotNull(m.match("aaaaaa"), "^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$ must match 'aaaaaa'");
  }

  @Test
  void selfRefGroupRepeatedFourTimes_matchesAaaa() {
    ReggieMatcher m = Reggie.compile("^(a\\1?){4}$");
    assertNotNull(m.match("aaaa"), "^(a\\1?){4}$ must match 'aaaa'");
  }

  // ── AC-1 negative cases ────────────────────────────────────────────────────

  @Test
  void fourGroupsWithSelfRefs_doesNotMatchAaa() {
    ReggieMatcher m = Reggie.compile("^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$");
    assertNull(m.match("aaa"), "^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$ must not match 'aaa'");
  }

  @Test
  void selfRefGroupRepeatedFourTimes_doesNotMatchAaa() {
    ReggieMatcher m = Reggie.compile("^(a\\1?){4}$");
    assertNull(m.match("aaa"), "^(a\\1?){4}$ must not match 'aaa'");
  }

  // ── AC-2: per-iteration capture update propagates ─────────────────────────

  @Test
  void perIterationCapture_lastIterationValueInGroup() {
    // Each iteration of (a\1?) begins with an empty partial capture, so \1 matches nothing
    // and the iteration captures just 'a'. After 4 iterations group(1) holds the last
    // iteration capture per PCRE / JDK last-match semantics.
    ReggieMatcher m = Reggie.compile("^(a\\1?){4}$");
    MatchResult r = m.match("aaaa");
    assertNotNull(r);
    assertEquals("a", r.group(1), "group(1) should reflect last iteration's capture");
  }

  @Test
  void selfRefGroupPlus_capturePreservedAfterGreedyFail() {
    // Regression: unbounded greedy loop over a self-referencing group must not clobber
    // the last successful capture when the next iteration fails.
    // ^(a\1?)+$ on "a": one iteration succeeds (group(1)="a"), then the greedy loop
    // attempts a second iteration which fails; group(1) must still be "a".
    ReggieMatcher m = Reggie.compile("^(a\\1?)+$");
    MatchResult r = m.match("a");
    assertNotNull(r, "^(a\\1?)+$ must match 'a'");
    assertEquals("a", r.group(1), "group(1) must be 'a' after failed greedy iteration");
  }

  @Test
  void perIterationCapture_alternationBranchWithSelfRef() {
    // Alternation branch contains a self-referencing backref; verify per-iteration update
    // is applied when the matched branch includes the self-ref.
    ReggieMatcher m = Reggie.compile("^(a\\1?|b){4}$");
    assertNotNull(m.match("aaaa"), "(a\\1?|b){4} must match 'aaaa'");
    assertNotNull(m.match("bbbb"), "(a\\1?|b){4} must match 'bbbb'");
    assertNull(m.match("aaa"), "(a\\1?|b){4} must not match 'aaa'");
  }

  // ── AC-3 / AC-5: allocation-free contract ─────────────────────────────────

  @Test
  void allocationFreeContract_repeatedMatchingDoesNotThrow() {
    ReggieMatcher m = Reggie.compile("^(a\\1?){4}$");
    for (int i = 0; i < 1000; i++) {
      assertTrue(m.matches("aaaa"), "match must succeed on iteration " + i);
    }
  }

  // ── AC-7: regression — non-self-referencing patterns unaffected ───────────

  @Test
  void regression_simpleQuantifiedGroup() {
    ReggieMatcher m = Reggie.compile("^(a){4}$");
    assertNotNull(m.match("aaaa"));
    assertNull(m.match("aaa"));
    assertNull(m.match("aaaaa"));
  }

  @Test
  void regression_normalBackrefAfterQuantifiedGroup() {
    ReggieMatcher m = Reggie.compile("(.+)=\\1");
    assertTrue(m.find("abc=abc"));
    assertFalse(m.find("x=y"));
  }

  @Test
  void regression_fixedRepetitionBackref() {
    ReggieMatcher m = Reggie.compile("(a)\\1{3}");
    assertTrue(m.matches("aaaa"));
    assertFalse(m.matches("aaa"));
    assertFalse(m.matches("aaaaa"));
  }

  @Test
  void regression_variableCaptureBackref() {
    ReggieMatcher m = Reggie.compile("(.+)=\\1");
    assertTrue(m.find("foo=foo"));
    assertFalse(m.find("foo=bar"));
  }

  @Test
  void regression_nonCapturingQuantifiedGroup() {
    ReggieMatcher m = Reggie.compile("(?:ab){3}");
    assertTrue(m.matches("ababab"));
    assertFalse(m.matches("abab"));
  }

  @Test
  void regression_quantifiedGroupWithAlternation() {
    ReggieMatcher m = Reggie.compile("^(a|b){4}$", WITH_FALLBACK);
    assertNotNull(m.match("aaaa"));
    assertNotNull(m.match("abba"));
    assertNotNull(m.match("bbbb"));
    assertNull(m.match("aaa"));
  }
}
