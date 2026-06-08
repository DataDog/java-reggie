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
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for the rich MatchResult API of the NESTED_QUANTIFIED_GROUPS strategy. */
class NestedQuantifiedGroupsMatchResultTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  private static void assertStrategy(String pattern) throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.NESTED_QUANTIFIED_GROUPS,
        StrategyCorrectnessMetaTest.routeOf(pattern),
        "pattern '" + pattern + "' must route to NESTED_QUANTIFIED_GROUPS");
  }

  // ── match() ───────────────────────────────────────────────────────────────

  @Test
  void matchSimpleNestedReturnsGroupSpans() throws Exception {
    assertStrategy("((a)+)*");
    MatchResult r = Reggie.compile("((a)+)*").match("aaa");
    assertNotNull(r, "match must succeed on 'aaa'");
    assertEquals("aaa", r.group(0), "group 0 = whole match");
    // POSIX last-iteration: group 2 is innermost (a), group 1 is outermost
    assertEquals("a", r.group(2), "group 2 = last iteration of inner +");
    assertNotNull(r.group(1), "group 1 must have participated");
  }

  @Test
  void matchReturnsNullOnMismatch() throws Exception {
    assertStrategy("((a)+)*");
    MatchResult r = Reggie.compile("((a)+)+").match("bbb");
    assertNull(r, "match must return null when pattern does not match");
  }

  @Test
  void matchAlternationPattern() throws Exception {
    assertStrategy("((a|bc)+)*");
    MatchResult r = Reggie.compile("((a|bc)+)*").match("abcbc");
    assertNotNull(r, "match must succeed on 'abcbc'");
    assertEquals("abcbc", r.group(0));
    assertNotNull(r.group(1), "group 1 (outer) must have participated");
    assertNotNull(r.group(2), "group 2 (inner) must have participated");
  }

  // ── matchesBounded() ──────────────────────────────────────────────────────

  @Test
  void matchesBoundedExtractsSubsequence() throws Exception {
    assertStrategy("((a)+)*");
    ReggieMatcher m = Reggie.compile("((a)+)*");
    // "xaaay" — bounded [1,4) → "aaa"
    assertTrue(m.matchesBounded("xaaay", 1, 4), "bounded match on embedded 'aaa'");
    assertFalse(m.matchesBounded("xaaay", 0, 5), "full string does not match ((a)+)*");
  }

  // ── matchBounded() ────────────────────────────────────────────────────────

  @Test
  void matchBoundedReturnsResult() throws Exception {
    assertStrategy("((a)+)*");
    ReggieMatcher m = Reggie.compile("((a)+)*");
    MatchResult r = m.matchBounded("xaaay", 1, 4);
    assertNotNull(r, "matchBounded must return non-null when it matches");
    assertEquals("aaa", r.group(0));
  }

  @Test
  void matchBoundedReturnsNullOnMismatch() throws Exception {
    assertStrategy("((a)+)+");
    ReggieMatcher m = Reggie.compile("((a)+)+");
    assertNull(m.matchBounded("xbbby", 1, 4), "matchBounded must return null when no match");
  }

  // ── findMatch() ───────────────────────────────────────────────────────────

  @Test
  void findMatchLocatesEmbeddedMatch() throws Exception {
    // Use + outer so that the zero-length match path is avoided
    assertStrategy("((a)+)+");
    MatchResult r = Reggie.compile("((a)+)+").findMatch("xaaay");
    assertNotNull(r, "findMatch must find 'aaa' inside 'xaaay'");
    assertEquals("aaa", r.group(0));
  }

  @Test
  void findMatchReturnsNullWhenNotFound() throws Exception {
    assertStrategy("((a)+)+");
    MatchResult r = Reggie.compile("((a)+)+").findMatch("bbb");
    assertNull(r, "findMatch must return null when nothing matches");
  }

  // ── findMatchFrom() ───────────────────────────────────────────────────────

  @Test
  void findMatchFromStartsAtOffset() throws Exception {
    assertStrategy("((a)+)+");
    ReggieMatcher m = Reggie.compile("((a)+)+");
    MatchResult r = m.findMatchFrom("xaaay", 1);
    assertNotNull(r);
    assertEquals("aaa", r.group(0));
  }

  @Test
  void findMatchFromReturnsNullWhenNoMatchAfterOffset() throws Exception {
    assertStrategy("((a)+)+");
    ReggieMatcher m = Reggie.compile("((a)+)+");
    // search from position 4 — only "y" remains, which doesn't match
    assertNull(m.findMatchFrom("xaaay", 4));
  }
}
