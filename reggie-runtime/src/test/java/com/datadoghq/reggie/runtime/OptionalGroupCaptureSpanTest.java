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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Backtrack-capture hygiene for optional capturing groups: when a backtracking construct unwinds
 * past a capture the JDK leaves the group unmatched, and Reggie must not leak the failed attempt's
 * group-start/end into the reported spans. Two independent fixes are pinned here:
 *
 * <ol>
 *   <li><b>DETERMINISTIC_CHAIN_BYTECODE</b> (DeterministicChainBytecodeGenerator): the OPT skip
 *       path and the ALT_CHAIN downstream-fail path restore the capture slots snapshotted before
 *       the with-path attempt. Before the fix, {@code (?:(\d+):)?(\d+)} on "123" reported g1="123"
 *       (the failed with-path's stale slots) while the JDK reports null.
 *   <li><b>B17 routing</b> (PatternAnalyzer.hasNfaBypassCharsetOverlap): a bypass-able group whose
 *       body charset overlaps the bypass path, or whose enter marker sits behind a consuming
 *       element (a non-start DFA state), routes to PikeVM instead of the tagged TDFA — the TDFA
 *       merges the with-path thread's tags onto transitions a bypass thread also rides and cannot
 *       pick the winner at determinization time. Before the fix, {@code x(?:(a):)?b} on "xb"
 *       reported g1=[0,-1) and {@code (?:(a))?(?:(b))?b} on "ab" bound g2 from a losing thread.
 * </ol>
 *
 * <p>All expectations are computed from {@code java.util.regex} at runtime (JDK-differential) — do
 * not hand-derive them.
 */
class OptionalGroupCaptureSpanTest {

  @ParameterizedTest
  @CsvSource({
    // pattern, input
    "(?:(\\d+):)?(\\d+),123", // the original logs-backend consumer shape (chain fix)
    "(?:(\\d+):)?(\\d+),12:3", // with-path taken: g1 binds normally
    "(?:(a):)?(a),a",
    "(?:(a)x)?(a),a",
    "(?:(\\d)x)?(\\d),5",
    "(?:(a)b)?(a),a",
    "(?:(a))?(?:(a))?b,ab", // sequential optional captures (B17 charset overlap)
    "(?:(a))?(?:(b))?b,ab", // ... even with disjoint body charsets
    "(?:(a))?(?:(a))?c,ac",
    "(?:(a):)?(?:(a):)?b,a:b",
    "(?:(a))?(?:(a))?ab,aab",
    "(?:(a))?x(?:(a))?b,axb",
    "x(?:(a):)?b,xb", // consuming head: branch in a non-start DFA state (B17 part 2)
    "x(?:(a+)x)?b,xb",
    "y(?:(a+)x)?b,yb",
    "x(?:(a+)x)?b,xaxb",
    "x(?:(a*)x)?b,xb",
  })
  void optionalGroupSpansMatchJdk(String pattern, String input) {
    Pattern jp = Pattern.compile(pattern);
    java.util.regex.Matcher jm = jp.matcher(input);
    ReggieMatcher rm = Reggie.compile(pattern);

    boolean jf = jm.find();
    MatchResult rr = rm.findMatch(input);
    assertEquals(jf, rr != null, "find() presence for " + pattern + " on " + input);
    if (!jf) {
      return;
    }
    assertNotNull(rr);
    assertEquals(jm.groupCount(), rr.groupCount(), "groupCount");
    for (int g = 1; g <= jm.groupCount(); g++) {
      assertEquals(
          jm.group(g),
          rr.group(g),
          "group "
              + g
              + " for "
              + pattern
              + " on "
              + input
              + " (jdk=["
              + jm.start(g)
              + ","
              + jm.end(g)
              + "))");
    }
  }

  /** Bounded-quantifier optional body — plain @Test because the pattern contains a comma. */
  @org.junit.jupiter.api.Test
  void boundedQuantifierOptionalBody() {
    optionalGroupSpansMatchJdk("x(?:(a{1,3})x)?b", "xb");
    optionalGroupSpansMatchJdk("x(?:(a{1,3})x)?b", "xaaxb");
  }

  /** The original consumer pattern: group 1 must stay null when the optional prefix is skipped. */
  @ParameterizedTest
  @CsvSource({"123", "456789", "0-1"})
  void consumerOptionalPrefixSkippedLeavesGroupNull(String input) {
    String pattern = "^(?:(\\d+):)?([0-9A-Za-z.~^_]+)(?:-([0-9A-Za-z.~^_-]*))?$";
    java.util.regex.Matcher jm = Pattern.compile(pattern).matcher(input); // JDK-differential
    org.junit.jupiter.api.Assertions.assertTrue(jm.find(), input);
    MatchResult rr = Reggie.compile(pattern).findMatch(input);
    assertNotNull(rr);
    assertNull(rr.group(1), "group 1 must not bind when the optional prefix is skipped");
    for (int g = 2; g <= jm.groupCount(); g++) {
      assertEquals(jm.group(g), rr.group(g), "group " + g);
    }
  }

  /**
   * Alternation bypasses without an optional quantifier (b|(b), (b)|b) stay on the tagged TDFA and
   * are handled correctly by the C2.4/C2.4B thread suppression — B17 must not divert them (pinned
   * by DfaUnrolledGroupAndFindRegressionTest as well; this test pins the SPANS).
   */
  @ParameterizedTest
  @CsvSource({"b|(b),b", "(b)|b,b", ".|([^c]),a", "x(a)y|(z),xay"})
  void alternationBypassSpansMatchJdk(String pattern, String input) {
    optionalGroupSpansMatchJdk(pattern, input);
  }
}
