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
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Variable-width leading lookaheads ({@code (?=.{1,64}@)...}, {@code (?=\w+@)...}) as sub-DFA gates
 * on the DFA-with-assertions ladder (AssertionCheck gate form; SubsetConstructor {@code
 * buildGateDfa}), plus the bounded-path assertion fixes that shipped with it:
 *
 * <ul>
 *   <li>The unrolled bounded emitter previously dropped charSets-form lookaheads silently (no
 *       branch for the non-literal form) — a fixed-width charSets lookahead like {@code (?=\d+)x}
 *       (existence-collapsed to {@code [\d]}) had no effect in {@code matchesBounded} at all.
 *   <li>The switch generator's {@code matchesBounded} never evaluated any assertion; it now fires
 *       start-state gate-form checks before the region walk (fixed-width forms keep their
 *       pre-existing absent behavior there — the gate form is what the new admission produces).
 * </ul>
 *
 * <p>Every behavior assertion is parity-checked against {@code java.util.regex} on both sides.
 */
class LookaheadGateDfaBytecodeTest {

  private static final String EMAIL = "(?=.{1,64}@)[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
  private static final String NO_BOYER_MOORE = "(?=\\w+@).*@\\w+\\.\\w+";

  private static PatternAnalyzer.MatchingStrategy routeOf(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = null;
    if (!PatternAnalyzer.requiresRecursiveDescent(ast)) {
      ThompsonBuilder builder = new ThompsonBuilder();
      nfa = builder.build(ast, 0);
    }
    PatternAnalyzer analyzer = new PatternAnalyzer(ast, nfa);
    return analyzer.analyzeAndRecommend().strategy;
  }

  @Test
  void routingUnrolled() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS,
        routeOf(EMAIL),
        "the ComplexEmail benchmark pattern routes to the unrolled DFA ladder via the gate");
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS,
        routeOf(NO_BOYER_MOORE),
        "the LookaheadNoBoyerMoore benchmark pattern routes to the unrolled DFA ladder");
  }

  @Test
  void routingSwitch() throws Exception {
    // 26-state main DFA lands in the DFA_SWITCH band; the gate body .{1,5}\d cannot be
    // existence-collapsed (variable position of the digit) and needs the gate form.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.DFA_SWITCH_WITH_ASSERTIONS,
        routeOf("(?=.{1,5}\\d)[a-z]{25}"),
        "a variable-width gate over a DFA_SWITCH-sized body routes to the switch ladder");
  }

  @Test
  void emailParityWithGateBoundaries() {
    assertJdkParity(
        EMAIL,
        "user@example.com",
        "a@b.co",
        "a@b.c",
        "x@y.zz",
        "a".repeat(64) + "@e.com",
        "a".repeat(65) + "@e.com",
        "@e.com",
        "user@examplecom",
        "userexample.com",
        "",
        "@",
        "a@b.co extra");
  }

  @Test
  void noBoyerMooreParityIncludingGateFailBodyPass() {
    // "x!@a.co": gate (?=\w+@) FAILS at 0 ('!' before the '@') while the body .*@\w+\.\w+
    // PASSES — the isolation case that proves the gate is evaluated on every path (unanchored
    // find candidates and all bounded regions), not just the matches() anchor.
    assertJdkParity(
        NO_BOYER_MOORE,
        "user@example.com",
        "a@b.co",
        "a@b.c",
        "!@x.co",
        "user@examplecom",
        "",
        "@",
        "a b@c.de",
        "user@exa mple.com",
        "x!@a.co",
        "ab@x.co");
  }

  @Test
  void charSetsFormGateBoundedParity() {
    // (?=\d+)x: the assertion existence-collapses to the charSets form [\d]. The bounded
    // emitter previously had no charSets-lookahead branch at all — the assertion vanished from
    // matchesBounded (region "x" of "1x" wrongly accepted: the body matches and the lookahead
    // was never checked).
    assertJdkParity("(?=\\d+)x", "1x", "x", "2x", "12x", "");
  }

  @Test
  void switchBoundedGateParity() {
    assertJdkParity(
        "(?=.{1,5}\\d)[a-z]{25}",
        "ab".repeat(12) + "1",
        "z".repeat(25),
        "1" + "z".repeat(24),
        "12" + "z".repeat(24),
        "a".repeat(25),
        "");
  }

  @Test
  void negativeGateParity() {
    assertJdkParity("(?!\\d)x", "x", "1x", "ax", "");
    assertJdkParity("(?![a-z])\\dx", "1x", "a1x", "_1x", "");
  }

  @Test
  void acceptingGateStartParity() {
    // (?=x*) gate DFA accepts at its start (empty body): the gate passes immediately.
    assertJdkParity("(?=x*)y", "y", "xy", "ay", "");
  }

  @Test
  void gateFormDeclinesBodiesWithGroups() throws Exception {
    // Capturing groups inside the lookahead need boundary tracking the gate does not do: the
    // gate admission must reject them (back to the pre-gate routing). A fixed-width group body
    // ((?=(\\d))x) is the old machinery's territory and still routes to the DFA ladder.
    assertEquals(
        PatternAnalyzer.MatchingStrategy.HYBRID_DFA_LOOKAHEAD,
        routeOf("(?=(\\d+))x"),
        "a group inside the variable-width lookahead declines the gate");
  }

  /**
   * Checks Reggie against {@code java.util.regex} for matches/find/findFrom/match (with group
   * spans) and matchesBounded over several regions — all parity, both sides computed here.
   */
  private static void assertJdkParity(String pattern, String... inputs) {
    ReggieMatcher reggie = RuntimeCompiler.compile(pattern);
    Pattern jdk = Pattern.compile(pattern);
    for (String input : inputs) {
      String ctx = pattern + " <" + input + "> ";
      assertEquals(jdk.matcher(input).matches(), reggie.matches(input), ctx + "matches");
      // fresh Matcher each time: find() after a matches() on the same Matcher is always false
      assertEquals(jdk.matcher(input).find(), reggie.find(input), ctx + "find");
      Matcher fm = jdk.matcher(input);
      assertEquals(fm.find(0) ? fm.start() : -1, reggie.findFrom(input, 0), ctx + "findFrom");

      MatchResult rr = reggie.match(input);
      Matcher mm = jdk.matcher(input);
      if (!mm.matches()) {
        assertNull(rr, ctx + "match-null");
      } else {
        assertEquals(mm.start(), rr.start(), ctx + "match-start");
        assertEquals(mm.end(), rr.end(), ctx + "match-end");
      }

      int len = input.length();
      int[][] regions = {
        {0, len},
        {0, Math.min(4, len)},
        {Math.min(1, len), len},
        {0, Math.max(0, len - 1)}
      };
      for (int[] r : regions) {
        if (r[0] > r[1]) continue;
        Matcher bm = jdk.matcher(input);
        bm.region(r[0], r[1]);
        String rctx = ctx + "region[" + r[0] + "," + r[1] + "] ";
        assertEquals(
            bm.matches(), reggie.matchesBounded(input, r[0], r[1]), rctx + "matchesBounded");
      }
    }
  }
}
