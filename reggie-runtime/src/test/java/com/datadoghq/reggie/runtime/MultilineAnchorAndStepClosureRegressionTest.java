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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for multiline-anchor behaviour and step-closure correctness.
 *
 * <p>The key assertion is that {@code (?m)^} on {@code "a\nb"} produces <em>exactly</em> 2 matches
 * (positions 0 and 2). An {@code assertEquals} rather than {@code assertTrue(... >= 2)} is
 * intentional: over-matching (3+ results) is also a regression.
 */
class MultilineAnchorAndStepClosureRegressionTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // -------------------------------------------------------------------------
  // T7 — Multiline ^ exact match count
  // -------------------------------------------------------------------------

  @Test
  void multilineCaret_twoMatches_exactCount() {
    ReggieMatcher m = Reggie.compile("(?m)^", WITH_FALLBACK);
    List<MatchResult> all = m.findAll("a\nb");
    assertEquals(
        2,
        all.size(),
        "(?m)^ on \"a\\nb\" must produce exactly 2 zero-length matches (positions 0 and 2)");
    assertEquals(0, all.get(0).start(), "first match must be at position 0");
    assertEquals(2, all.get(1).start(), "second match must be at position 2");
  }
}
