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
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import org.junit.jupiter.api.Test;

class PikeVMRoutingTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  @Test
  void captureAmbiguousRoutes_toDfaWithGroups() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(a)?b"),
        "(a)?b must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void captureAmbiguousRoutes_dotOptionalB() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(.)?b"),
        "(.)?b must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void captureAmbiguousMatcher_matchesCorrectly() {
    ReggieMatcher m = Reggie.compile("(a)?b", WITH_FALLBACK);
    assertTrue(m.matches("ab"), "should match 'ab'");
    assertTrue(m.matches("b"), "should match 'b'");
    assertFalse(m.matches("a"), "should not match 'a'");
    assertTrue(m.find("xaby"), "should find in 'xaby'");
    assertTrue(m.find("xby"), "should find in 'xby'");
  }

  // ── Alternation-priority patterns now route to native DFA (declined guard retired) ──

  @Test
  void foOrFoo_routesToDfaWithGroups() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(fo|foo)"),
        "(fo|foo) must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void aOrAb_routesToDfaWithGroups() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(a|ab)"),
        "(a|ab) must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void aaOrAThenA_routesToDfaWithGroups() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
        StrategyCorrectnessMetaTest.routeOf("(aa|a)a"),
        "(aa|a)a must route to DFA_UNROLLED_WITH_GROUPS");
  }

  @Test
  void foOrFoo_findMatchCorrect() {
    ReggieMatcher m = Reggie.compile("(fo|foo)");
    // find() must return leftmost first-alternative match (priority-cut: "fo" wins over "foo")
    assertTrue(m.find("foo"));
    assertEquals(
        "fo",
        m.findMatch("foo").group(0),
        "(fo|foo) find on 'foo' must return 'fo' (first alternative wins)");
    // match() must match the full input — 'foo' alternative covers "foo"
    assertNotNull(m.match("foo"), "(fo|foo) match on 'foo' must succeed (full-input match)");
    assertEquals(
        "foo", m.match("foo").group(0), "(fo|foo) match on 'foo' must return 'foo' (full-input)");
  }

  @Test
  void aOrAb_findMatchCorrect() {
    ReggieMatcher m = Reggie.compile("(a|ab)");
    // "a" wins over "ab" in scanning (priority-cut)
    assertTrue(m.find("ab"));
    assertEquals(
        "a",
        m.findMatch("ab").group(0),
        "(a|ab) find on 'ab' must return 'a' (first alternative wins)");
  }
}
