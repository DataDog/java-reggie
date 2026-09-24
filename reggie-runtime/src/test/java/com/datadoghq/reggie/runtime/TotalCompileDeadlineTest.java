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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Total-compile deadline ({@code -Dreggie.compile.totalDeadlineMs}, default 10s, 0 disables): the
 * per-determinization deadline (-Dreggie.dfa.deadlineMs) bounds each analysis pass, but a pattern
 * can need several failing passes — the nested {@code ((a|b){0,256}){12}} unrolling spent 15s
 * trying DFA routes before landing on PikeVM. The total deadline bounds the WHOLE compile, and is
 * clamped into every determinization (SubsetConstructor.TOTAL_COMPILE_DEADLINE_NANOS) so a tight
 * knob actually binds.
 *
 * <p>Pass-through nuance: when the deadline expires but the analysis already selected an NFA-backed
 * strategy (PikeVM/BitState), the compile FINISHES anyway — the remaining NFA build is cheap, and
 * those patterns are exactly the shapes java.util.regex cannot match in bounded time (catastrophic
 * backtracking), so a JDK fallback would move the denial of service from compile time to match
 * time.
 */
class TotalCompileDeadlineTest {

  private static final String BOMB = "((a|b){0,256}){12}";

  @AfterEach
  void restoreProperty() {
    System.clearProperty(RuntimeCompiler.TOTAL_COMPILE_DEADLINE_PROPERTY);
  }

  /**
   * A tight 2s deadline bounds the bomb's compile to ~2s and it still completes on PikeVM (NFA
   * pass-through) with correct linear-time matching — the JDK itself cannot match this pattern in
   * bounded time, so the JDK fallback is the worse outcome.
   */
  @Test
  @Timeout(30)
  void tightDeadlineBombsToPikevmWithCorrectMatching() {
    System.setProperty(RuntimeCompiler.TOTAL_COMPILE_DEADLINE_PROPERTY, "2000");
    long t0 = System.nanoTime();
    ReggieMatcher m = Reggie.compile(BOMB);
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    assertTrue(elapsedMs < 5_000, "compile must be bounded to ~2s, took " + elapsedMs + "ms");
    assertEquals(
        "PikeVMMatcher", m.getClass().getSimpleName(), "NFA-backed strategies pass through");
    // linear-time correctness on the shapes the JDK chokes on
    assertEquals(false, m.matches("abab".repeat(30) + "c"));
    assertEquals(true, m.matches("abab".repeat(30)));
    assertTrue(m.findMatch("xx" + "ab".repeat(16) + "yy") != null);
  }

  /**
   * Legitimate patterns are unaffected at the default: the semver shape (the original 300-char
   * {0,256} consumer pattern) compiles in well under a second.
   */
  @Test
  void legitimatePatternsUnaffected() {
    long t0 = System.nanoTime();
    ReggieMatcher m =
        Reggie.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$");
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    assertTrue(elapsedMs < 2_000, "semver must compile fast, took " + elapsedMs + "ms");
    assertEquals(true, m.matches("1.2.3.4"));
    assertEquals(false, m.matches("01.2.3.4"));
  }

  /**
   * With an ultra-tight deadline (1ms) the bomb still yields a CORRECT matcher: the clamped
   * determinization aborts instantly, analysis routes to PikeVM, and the pass-through finishes the
   * cheap NFA build — natively, in milliseconds. Either outcome (native PikeVM or a JDK fallback
   * with ALLOW_JDK_FALLBACK) must match identically; the invariant under test is correctness, never
   * a silently wrong matcher.
   */
  @Test
  @Timeout(30)
  void ultraTightDeadlineStaysCorrect() {
    System.setProperty(RuntimeCompiler.TOTAL_COMPILE_DEADLINE_PROPERTY, "1");
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    ReggieMatcher m = Reggie.compile(BOMB, opts);
    String kind = m.getClass().getSimpleName();
    assertTrue(
        kind.equals("PikeVMMatcher") || kind.equals("JavaRegexFallbackMatcher"),
        "unexpected matcher kind " + kind);
    assertEquals(false, m.matches("abab".repeat(30) + "c"));
    assertEquals(true, m.matches("abab".repeat(30)));
  }
}
