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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@code findMatch()}/{@code findMatchFrom()}/{@code findBoundsFrom()} correctness for
 * {@code ONEPASS_NFA} after replacing the O(n) trial-length rescan (via {@code matchesInRange})
 * with a direct reuse of {@code matchFrom}'s single-pass end position. See {@code
 * doc/2026-07-07-onepass-findmatch-perf-plan.md} for the root cause and correctness argument.
 */
public class OnePassFindMatchPerfCorrectnessTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void multiGroupPattern_routesToOnepassNfa() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.ONEPASS_NFA,
        StrategyCorrectnessMetaTest.routeOf("([a-z])(bar)"),
        "([a-z])(bar) must route to ONEPASS_NFA");
  }

  @Test
  void multiGroupPattern_findMatchGroupSpans() {
    ReggieMatcher m = Reggie.compile("([a-z])(bar)");
    MatchResult mr = m.findMatch("xxxzbarxxx");
    assertTrue(mr != null, "expected a match");
    assertEquals(3, mr.start());
    assertEquals(7, mr.end());
    assertEquals("z", mr.group(1));
    assertEquals("bar", mr.group(2));
  }

  @Test
  void singleGroupPattern_findMatchAtStartMiddleEnd() {
    ReggieMatcher m = Reggie.compile("(abc)");

    MatchResult atStart = m.findMatch("abcxxxxxxxx");
    assertEquals(0, atStart.start());
    assertEquals(3, atStart.end());

    MatchResult inMiddle = m.findMatch("xxxxabcxxxx");
    assertEquals(4, inMiddle.start());
    assertEquals(7, inMiddle.end());

    MatchResult atEnd = m.findMatch("xxxxxxxxabc");
    assertEquals(8, atEnd.start());
    assertEquals(11, atEnd.end());

    assertEquals(null, m.findMatch("xxxxxxxxxxx"));
  }

  @Test
  void findMatchFrom_resumesAfterPriorMatch() {
    ReggieMatcher m = Reggie.compile("(abc)");
    MatchResult first = m.findMatchFrom("abcxxxabcxxx", 0);
    assertEquals(0, first.start());
    MatchResult second = m.findMatchFrom("abcxxxabcxxx", first.end());
    assertEquals(6, second.start());
    assertEquals(9, second.end());
  }

  @Test
  void findBoundsFrom_matchesFindMatch() {
    ReggieMatcher m = Reggie.compile("([a-z])(bar)");
    int[] bounds = new int[2];

    assertTrue(m.findBoundsFrom("xxxzbarxxx", 0, bounds));
    assertEquals(3, bounds[0]);
    assertEquals(7, bounds[1]);

    assertFalse(m.findBoundsFrom("xxxxxxxxxx", 0, bounds));
  }

  @Test
  void longHaystackWithMatchNearEnd_findMatchIsExactAndFast() {
    ReggieMatcher m = Reggie.compile("(abc)");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20_000; i++) {
      sb.append('x');
    }
    sb.append("abc");
    String input = sb.toString();

    MatchResult mr = m.findMatch(input);
    assertEquals(20_000, mr.start());
    assertEquals(20_003, mr.end());

    int[] bounds = new int[2];
    assertTrue(m.findBoundsFrom(input, 0, bounds));
    assertEquals(20_000, bounds[0]);
    assertEquals(20_003, bounds[1]);
  }
}
