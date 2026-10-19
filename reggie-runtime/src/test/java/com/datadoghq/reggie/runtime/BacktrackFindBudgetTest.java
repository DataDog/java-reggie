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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The match starts of one BackrefBacktrackMatcher find call share a budget of steps and
 * backreference characters compared that grows with the searched length, on top of the per-start
 * step budget.
 */
class BacktrackFindBudgetTest {

  private static ReggieMatcher backtracker(String regex) {
    ReggieMatcher m = EngineRouting.unwrap(RuntimeCompiler.compile(regex));
    assertEquals(BackrefBacktrackMatcher.class, m.getClass(), "engine of /" + regex + "/");
    return m;
  }

  @Test
  void costlyFailingFindStaysWithinBudget() {
    ReggieMatcher m = backtracker("(a+)+\\1b");
    String input = "a".repeat(300);
    assertEquals(-1, m.findFrom(input, 0));
    assertNull(m.findMatchFrom(input, 0));
  }

  @Test
  void ambiguousFindThrowsInBoundedTime() {
    // Every start stays under the per-start budget, but the compares of \1 cost O(n) per step.
    // Wide wall-clock budget: CI runners under parallel load measure 3-4x local latency; a real
    // budget failure (exception never thrown) fails the assertion regardless of the window.
    ReggieMatcher m = backtracker("(a+)\\1+b");
    String input = "a".repeat(2000);
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          MatchBudgetExceededException e =
              assertThrows(MatchBudgetExceededException.class, () -> m.findFrom(input, 0));
          assertTrue(e.getMessage().contains("stepsPerChar"), e.getMessage());
          assertThrows(MatchBudgetExceededException.class, () -> m.findMatchFrom(input, 0));
        });
  }
}
