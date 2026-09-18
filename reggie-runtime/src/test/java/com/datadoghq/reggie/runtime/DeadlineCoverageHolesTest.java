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
import com.datadoghq.reggie.ReggieMatcher;
import com.datadoghq.reggie.ReggieOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression tests for the two pre-existing 10s-deadline coverage gaps (2026-09-17,
 * find-deadline-coverage-gaps), verified on 715fb53 before the fix:
 *
 * <ol>
 *   <li><b>Memory hole</b>: {@code (?=lk0)lk0(?=lk1)lk1...} × 1000 sequential lookaheads OOM'd a 6
 *       GB heap in ~4s during NFA codegen — NFABytecodeGenerator's emission finished and ASM's
 *       per-method maxs/frames computation exhausted memory before ClassWriter.toByteArray could
 *       even reach the 64 KB method check. A <em>time</em> deadline cannot close this hole (the
 *       pattern dies inside the envelope); the fix is the per-method emission budget in
 *       NFABytecodeGenerator (MAX_EMITTED_INSNS_PER_METHOD), which aborts during emission and
 *       surfaces as the standard MethodTooLargeException graceful-fallback path.
 *   <li><b>Time hole</b>: {@code (wa0|wb0)(wa1|wb1)...} × 6000 (12,000 capture groups) compiled
 *       successfully in 22s as BitStateMatcher — the group-bypass BFS in
 *       SubsetConstructor.computeGroupsWithBypass is O(groups × states) and never charged the
 *       determinization work budget, so no deadline was ever consulted. The fix charges it via
 *       chargeWork, so the analysis aborts onto the standard NFA-backed routes in ~1s.
 * </ol>
 */
class DeadlineCoverageHolesTest {

  @AfterEach
  void clearDeadlineProperty() {
    System.clearProperty(RuntimeCompiler.TOTAL_COMPILE_DEADLINE_PROPERTY);
  }

  /**
   * Hole 1: the 1000-lookahead cascade must complete in bounded time and memory instead of
   * exhausting the heap. The emission budget aborts the oversized method during generation, so the
   * compile surfaces as the standard too-large graceful path: with ALLOW_JDK_FALLBACK the JDK
   * fallback matcher (correct lookahead semantics), without it an UnsupportedPatternException —
   * never an OutOfMemoryError, never a multi-GB, multi-second size/frame computation.
   */

  /** Strips the R1 PrefilteringMatcher wrapper so routing assertions see the engine class. */
  private static ReggieMatcher unwrap(ReggieMatcher m) {
    while (m instanceof PrefilteringMatcher p) {
      m = p.delegate();
    }
    return m;
  }

  @Test
  @Timeout(60)
  void thousandLookaheadCascadeIsBoundedNotFatal() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      sb.append("(?=lk").append(i).append(")lk").append(i);
    }
    String pattern = sb.toString();
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    long t0 = System.nanoTime();
    ReggieMatcher m = Reggie.compile(pattern, opts);
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    assertTrue(
        elapsedMs < 30_000,
        "1000-lookahead compile must be bounded by the emission budget, took " + elapsedMs + "ms");
    assertTrue(unwrap(m) instanceof JavaRegexFallbackMatcher);
    // The fallback must be semantically correct, not just cheap to reach.
    StringBuilder input = new StringBuilder();
    for (int i = 0; i < 1000; i++) {
      input.append("lk").append(i);
    }
    assertEquals(true, m.matches(input.toString()));
    assertEquals(false, m.matches("lk1lk2lk3"));
  }

  /**
   * Hole 2: 6000 distinct capture alternations must not spend 22s past the deadline in the
   * unchecked group-bypass BFS. With a tight deadline the charged work budget aborts the analysis
   * within the envelope and the compile completes via the standard deadline path in bounded time.
   * Heap note: native matchers for 12,000-group patterns need match-time allocations far beyond the
   * Gradle test heap, so semantic assertions run only when the graceful path yields the JDK
   * fallback matcher (the native BitState route for this shape is covered by the codegen-side
   * GroupBypassWorkBudgetTest and by probe evidence at 6 GB).
   */
  @Test
  @Timeout(60)
  void sixThousandGroupAlternationsAreDeadlineBounded() {
    System.setProperty(RuntimeCompiler.TOTAL_COMPILE_DEADLINE_PROPERTY, "500");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 6000; i++) {
      sb.append("(wa").append(i).append("|wb").append(i).append(")");
    }
    String pattern = sb.toString();
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    long t0 = System.nanoTime();
    ReggieMatcher m = Reggie.compile(pattern, opts);
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    assertTrue(
        elapsedMs < 8_000,
        "6000-group alternation compile must be deadline-bounded (was 22s before the fix), took "
            + elapsedMs
            + "ms");
    String kind = unwrap(m).getClass().getSimpleName();
    assertTrue(
        kind.equals("BitStateMatcher")
            || kind.equals("PikeVMMatcher")
            || kind.equals("JavaRegexFallbackMatcher"),
        "expected a bounded NFA-backed or fallback matcher, got " + kind);
    if (kind.equals("JavaRegexFallbackMatcher")) {
      StringBuilder input = new StringBuilder();
      for (int i = 0; i < 6000; i++) {
        input.append("wa").append(i);
      }
      assertEquals(true, m.matches(input.toString()));
      assertEquals(false, m.matches("zz"));
    }
  }
}
