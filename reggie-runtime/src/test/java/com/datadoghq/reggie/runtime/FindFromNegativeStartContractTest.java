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

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cross-strategy findFrom(String, int) start-offset contract: a negative start clamps to 0 (like
 * String.indexOf), and a start past the end returns -1. FixedSequenceBytecodeGenerator and the
 * suffix scan always did this; the DFA/bitstate/NFA/onepass/greedy/backref generators and
 * HybridMatcher returned -1 (or crashed with StringIndexOutOfBounds for VARIABLE_CAPTURE_BACKREF)
 * before the clamp was unified - see the per-generator prologue fixes.
 */
class FindFromNegativeStartContractTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  private static final String[][] CASES = {
    // pattern, input - covers DFA_UNROLLED, BITSTATE_CAPTURE, VARIABLE_CAPTURE_BACKREF,
    // HYBRID (HybridMatcher), PIKEVM-ish, DFA_SWITCH, backrefs, fixed sequences.
    {"a.*b", "xxaxb"},
    {"a|b|c", "xxbxx"},
    {"x[a-z]+", "xyzabc"},
    {"\\d+\\s+\\w+", "x 12 ab"},
    {"(?i)abc", "xxABC"},
    {"(?:\\w+\\s+)*(x|y)", "a x"},
    {"select|from|where", "x select y"},
    {"(?:select\\s+|from\\s+)+", "x from "},
    {"[0-9]+\\.[0-9]+", "x1.2"},
    {"(?:(?:x|y)a)+z", "xxaxaz"},
    {"(?:ab|a)*c", "aabc"},
    {"x=[^&]*", "y?x=1"},
    {"(a|ab)c", "abc"},
    {"(ab){2}", "zababx"},
    {"(foo|bar)+baz", "xfoobarbaz"},
    {"(a|b|cd)*ef", "abef"},
    {"(?:(a)|(b))+", "ab"},
    {"back(\\w+)\\s+\\1", "back aa aa "},
    {"(?=(\\w+@)).*x", "a@x"},
    {"(?=.*foo)(?=.*bar).*baz", "x foobar baz"},
    {"(a?b?)+c", "xxabc"},
    {"(a|bb)*(x|y|z)", "qabx"},
    {"(a|b|c|d|e|f|g|h|i|j)*(x|y|z)", "abcdx"},
    {"\\d{11}", "12345678901"},
    {"(.{0,3})x(.{0,3})", "abxcd"},
    {"a(b*)", "abbb"},
    {"\\d{1,3}\\.\\d{1,3}", "x12.3"},
    {"\\.?0+$", "abc.000"},
    {"(?:abc){2}", "zabcabc"},
    {"abcabc", "zabcabc"},
    {"(a+)(b+)\\1\\2", "aabbaabb"},
  };

  @Test
  void negativeStartClampsToZeroAcrossStrategies() {
    for (String[] c : CASES) {
      ReggieMatcher m = (ReggieMatcher) Reggie.compile(c[0]);
      int expected = m.findFrom(c[1], 0);
      for (int neg : new int[] {-1, -5, -100}) {
        assertEquals(
            expected,
            m.findFrom(c[1], neg),
            "findFrom(" + neg + ") should clamp to 0 for /" + c[0] + "/ on |" + c[1] + "|");
      }
      // findMatchFrom follows the same contract
      assertEquals(
          m.findMatchFrom(c[1], 0) != null,
          m.findMatchFrom(c[1], -7) != null,
          "findMatchFrom(-7) should clamp to 0 for /" + c[0] + "/");
    }
  }

  @Test
  void startPastEndReturnsNoMatch() {
    for (String[] c : CASES) {
      ReggieMatcher m = (ReggieMatcher) Reggie.compile(c[0]);
      int len = c[1].length();
      assertEquals(-1, m.findFrom(c[1], len + 1), "past-end for /" + c[0] + "/");
      assertEquals(-1, m.findFrom(c[1], len + 1000), "far past-end for /" + c[0] + "/");
    }
  }

  @Test
  void negativeStartFindsTheZeroStartMatchResult() {
    // A pattern with a non-zero leftmost match: clamped findFrom must return the same span.
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("(ab){2}");
    com.datadoghq.reggie.runtime.MatchResult clamped = m.findMatchFrom("zababx", -3);
    assertNotNull(clamped, "clamped findMatchFrom should find the match");
    assertEquals(1, clamped.start());
    assertEquals(5, clamped.end());
  }
}
