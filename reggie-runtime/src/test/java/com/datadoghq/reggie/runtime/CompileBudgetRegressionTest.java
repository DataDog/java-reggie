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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for bounded compile work on bounded-quantifier patterns.
 *
 * <p>History: before the flattenClosure hoist, the group-exit memoization and the NFA state cap,
 * the canonical semver pattern (multiple nested {0,256} bounded quantifiers) never finished
 * compiling — DFA analysis rebuilt the flattened closure structure per transition, and 20 nested
 * {0,8} groups exhausted the heap with ~8^20 unrolled NFA states. These tests pin the fixes: the
 * semver pattern must compile and match correctly within the test timeout, and exponential-NFA
 * bombs must be rejected gracefully with {@link UnsupportedPatternException}.
 */
class CompileBudgetRegressionTest {

  /**
   * Canonical semver pattern as used by logs-backend (SemVerParser). Nested {0,256} quantifiers
   * expand to ~0.5M NFA states — under the 1M cap, and deterministic enough for cheap analysis.
   */
  private static final String SEMVER =
      "^(?<major>0|[1-9]\\d{0,256})\\.(?<minor>0|[1-9]\\d{0,256})\\.(?<patch>0|[1-9]\\d{0,256})"
          + "(?:-(?<prerelease>(?:0|[1-9]\\d{0,256}|\\d{0,256}[a-zA-Z-][0-9a-zA-Z-]{0,256})"
          + "(?:\\.(?:0|[1-9]\\d{0,256}|\\d{0,256}[a-zA-Z-][0-9a-zA-Z-]{0,256})){0,256}))?"
          + "(?:\\+(?<buildmetadata>[0-9a-zA-Z-]{0,40}(?:\\.[0-9a-zA-Z-]{0,40}){0,256}))?$";

  @Test
  void semverPattern_compilesAndMatchesWithinBudget() {
    ReggieMatcher matcher =
        assertTimeoutPreemptively(
            Duration.ofSeconds(60), // pre-fix: never completed (exponential determinization)
            () -> Reggie.compile(SEMVER));
    assertTrue(matcher.matches("1.2.3"));
    assertTrue(matcher.matches("1.2.3-rc.1+build.5"));
    assertFalse(matcher.matches("01.2.3"));
    assertFalse(matcher.matches("1.2"));
    assertEquals("1", matcher.match("1.2.3").group("major"));
    assertEquals("2", matcher.match("1.2.3").group("minor"));
    assertEquals("3", matcher.match("1.2.3").group("patch"));
  }

  @Test
  void semverPattern_fallbackCompileStillAvailable() {
    // compileAllowingFallback must also stay bounded for the semver shape.
    ReggieMatcher matcher =
        assertTimeoutPreemptively(
            Duration.ofSeconds(60), () -> Reggie.compileAllowingFallback(SEMVER));
    assertTrue(matcher.matches("10.20.30"));
  }

  @Test
  void nestedBoundedQuantifiers_rejectedGracefully() {
    // 20 nested {0,8} groups would request ~8^20 NFA states; the state cap must convert that
    // into a graceful UnsupportedPatternException instead of an OutOfMemoryError.
    StringBuilder bomb = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      bomb.insert(0, "(?:").append("){0,8}");
    }
    bomb.append("a");
    String pattern = bomb.toString();
    assertTimeoutPreemptively(
        Duration.ofSeconds(30),
        () -> {
          UnsupportedPatternException ex =
              assertThrows(UnsupportedPatternException.class, () -> Reggie.compile(pattern));
          assertTrue(
              ex.getMessage().contains("NFA states"),
              "expected NFA state-cap rejection, got: " + ex.getMessage());
        },
        "nested bounded-quantifier bomb must be rejected quickly, not hang or OOM");
  }

  @Test
  void alternationUnrolledQuantifiers_compileBounded() {
    // (a|b){0,256} repeated: alternation inside unrolled quantifiers creates closure-heavy DFA
    // states. Pre-fix this took minutes/hung; post-fix it must complete (or reject) within the
    // timeout — and when it compiles, it must match correctly.
    StringBuilder pattern = new StringBuilder();
    for (int i = 0; i < 12; i++) {
      pattern.append("(a|b){0,256}");
    }
    ReggieMatcher matcher =
        assertTimeoutPreemptively(
            Duration.ofSeconds(60), () -> Reggie.compileAllowingFallback(pattern.toString()));
    assertTrue(matcher.matches("abab"));
    assertTrue(matcher.matches("ab".repeat(24)));
    assertFalse(matcher.matches("abab" + "c"));
  }
}
