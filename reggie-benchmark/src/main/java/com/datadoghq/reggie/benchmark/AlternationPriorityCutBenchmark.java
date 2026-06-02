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
package com.datadoghq.reggie.benchmark;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Throughput benchmark for alternation-boundary patterns that previously routed to OPTIMIZED_NFA
 * (JDK fallback) and now route to DFA_UNROLLED_WITH_GROUPS via the acceptIsPriorityCut flag.
 *
 * <p>Patterns: {@code (fo|foo)}, {@code (a|ab)}, {@code (aa|a)a}.
 *
 * <p>Inputs exercise the priority-cut path where the shorter alternative would match but the engine
 * must correctly prefer it and not continue scanning for a longer match.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class AlternationPriorityCutBenchmark {

  // Compiled matchers — one per pattern, created once per trial
  private ReggieMatcher matcherFoFoo;
  private ReggieMatcher matcherAab;
  private ReggieMatcher matcherAaaA;

  @Setup(Level.Trial)
  public void setup() {
    Reggie.clearCache();
    matcherFoFoo = Reggie.compile("(fo|foo)");
    matcherAab = Reggie.compile("(a|ab)");
    matcherAaaA = Reggie.compile("(aa|a)a");
  }

  // ── (fo|foo) ─────────────────────────────────────────────────────────────

  /** Priority-cut hit: "fo" wins over "foo" — cut at the shorter alternative. */
  @Benchmark
  public Object foFoo_foo() {
    return matcherFoFoo.findMatch("foo");
  }

  /** Priority-cut in embedded context: "fo" found inside surrounding chars. */
  @Benchmark
  public Object foFoo_xfooy() {
    return matcherFoFoo.findMatch("xfooy");
  }

  // ── (a|ab) ───────────────────────────────────────────────────────────────

  /** Priority-cut hit: "a" wins over "ab" — cut at the shorter alternative. */
  @Benchmark
  public Object aAb_ab() {
    return matcherAab.findMatch("ab");
  }

  /** Priority-cut in embedded context: "a" found inside surrounding chars. */
  @Benchmark
  public Object aAb_xaby() {
    return matcherAab.findMatch("xaby");
  }

  // ── (aa|a)a ──────────────────────────────────────────────────────────────

  /**
   * Priority-cut hit: group captures "aa" so that the trailing literal "a" can match; exercises the
   * boundary where the greedy-continue path and priority-cut interact.
   */
  @Benchmark
  public Object aaAa_aa() {
    return matcherAaaA.findMatch("aa");
  }

  /** Priority-cut in embedded context: "aaa" found inside surrounding chars. */
  @Benchmark
  public Object aaAa_xaay() {
    return matcherAaaA.findMatch("xaay");
  }
}
