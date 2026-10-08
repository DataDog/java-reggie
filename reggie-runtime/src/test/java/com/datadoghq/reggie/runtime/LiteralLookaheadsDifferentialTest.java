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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for literal-extractable multi-lookahead patterns. The groupless ones route
 * to DFA_UNROLLED_WITH_ASSERTIONS via the sub-DFA gate; the grouped ones stay on
 * SPECIALIZED_LITERAL_LOOKAHEADS (gate admission is groupless-patterns-only), as does a DFA too
 * large for the assertion ladder. The {@code |c} entries put the lookaheads under a top-level
 * alternation, where the literal-lookahead info used to re-analyze its own pattern without bound.
 */
public class LiteralLookaheadsDifferentialTest {

  private static final String[] PATTERNS = {
    "(?=.*a)(?=.*b).{3,}",
    "(?s)(?=.*a)(?=.*b).{3,}",
    "(?=.*a)(?=.*b)x",
    "(?=.*foo)(?=.*bar).*",
    "(?=.*1)(?=.*A).{4,}",
    "(?=\\w+@)(?=.*x).+"
  };

  private static final String[] LITERAL_TIER = {
    "(?=.*a)(?=.*b)(.{3,})",
    "(?=.*foo)(?=.*bar)(.*)",
    "(?=.*(a))(?=.*b)x",
    "(?=.*1)(?=.*A)(.{4,})",
    "(?=.*a)(?=.*b)(.{3,})|c",
    "(?=.*a)(?=.*b)[a-c]*[ab][a-c]{12}|c"
  };

  private static final List<String> INPUTS =
      List.of(
          "",
          "ab",
          "ba",
          "abc",
          "xab",
          "abx",
          "abxyz",
          "a\nbc",
          "ab\nc",
          "foobar",
          "barfoo",
          "foo\nbar",
          "xfoobar",
          "A1bc",
          "a1Bc",
          "A\n1bc",
          "1aaA",
          "#@x",
          "a@x",
          "a@\nx",
          "x@a",
          "aaaa",
          "bbbb");

  @Test
  void routesToLiteralLookaheads() throws Exception {
    for (String p : LITERAL_TIER) {
      assertEquals(
          PatternAnalyzer.MatchingStrategy.SPECIALIZED_LITERAL_LOOKAHEADS,
          StrategyCorrectnessMetaTest.routeOf(p),
          p);
    }
  }

  @Test
  void agreesWithJdk() {
    assertEquals(List.of(), DotLineTerminatorTest.mismatches(PATTERNS, INPUTS));
    assertEquals(List.of(), DotLineTerminatorTest.mismatches(LITERAL_TIER, INPUTS));
  }
}
