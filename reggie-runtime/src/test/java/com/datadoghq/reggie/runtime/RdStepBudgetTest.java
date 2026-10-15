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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The recursive-descent generator's choice-point code (compiled through {@link
 * RuntimeCompiler#compileRecursiveDescentForTest}) has no per-loop iteration cap, and bounds each
 * match attempt with the {@link BacktrackConfig#MAX_RD_STEPS} call budget.
 */
class RdStepBudgetTest {

  private static ReggieMatcher forcedRd(String pattern) throws Exception {
    return RuntimeCompiler.compileRecursiveDescentForTest(pattern);
  }

  @Test
  void quantifierPastFormerIterationCapMatches() throws Exception {
    String n = "a".repeat(11_000);
    String ab = "ab".repeat(11_000);
    String[][] cases = {
      {"(a+)b(?1)(?>q)?", n + "b" + n},
      {"(a+)b(?1)(?:(x)){0}(?2)?", n + "b" + n},
      {"((?:ab)+)ab(?:(x)){0}(?2)?", ab},
    };
    for (String[] c : cases) {
      ReggieMatcher m = forcedRd(c[0]);
      assertTrue(m.matches(c[1]), c[0]);
      MatchResult r = m.findMatch(c[1]);
      assertNotNull(r, c[0]);
      assertEquals(0, r.start(), c[0]);
      assertEquals(c[1].length(), r.end(), c[0]);
    }
  }

  @Test
  void polynomialBlowupThrowsBudgetException() throws Exception {
    // Three sequential give-back groups: about n^4 calls (176 s at n = 1000 without a budget).
    ReggieMatcher m = forcedRd("(a+)(a+)(a+)bc(?1)?(?>q)?");
    String in = "a".repeat(1000) + "b!aaabc";
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> assertThrows(MatchBudgetExceededException.class, () -> m.findMatch(in)));
  }

  @Test
  void budgetDoesNotAccumulateAcrossCalls() throws Exception {
    // matches() and match() call the root parsers directly; each call must start a fresh budget.
    // Each matches() or match() call makes at least 1000 parse calls, so the loop's total exceeds
    // one attempt's budget (MAX_RD_STEPS + 16 per character).
    String in = "a".repeat(1000) + "b";
    int calls = BacktrackConfig.MAX_RD_STEPS / 2000 + 100;
    for (String p : new String[] {"(a+)(?(1)b|c)(?>q)?", "(a+)?(?(1)b|c)(?>q)?|x"}) {
      ReggieMatcher m = forcedRd(p);
      for (int i = 0; i < calls; i++) {
        assertTrue(m.matches(in), p);
        assertNotNull(m.match(in), p);
      }
    }
  }
}
