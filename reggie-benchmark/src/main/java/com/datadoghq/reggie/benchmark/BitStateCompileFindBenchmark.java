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

import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * Warm-cache per-operation cost of runtime {@code BitStateMatcher} construction through the hybrid
 * NFA half (see {@code RuntimeCompiler.HybridEntry#newHybridNfaHalf}).
 *
 * <p>The measured operation is exactly one {@code RuntimeCompiler.compile(PATTERN)} — a warm
 * runtime-cache lookup that still builds a fresh hybrid matcher and a fresh BitState NFA half on
 * every call — followed by one {@code find(INPUT)}. Trial setup clears the cache once and compiles
 * once so parse/NFA/strategy-selection cold compilation is excluded, but the compiled matcher is
 * never retained as benchmark state and caches are never cleared per operation.
 *
 * <p>Route guard: the exact production GO pattern is deterministic-chain at this HEAD, so this
 * fixture pins a GO-shaped pattern that was verified to route to {@code HYBRID_DFA} with a
 * BitState-backed NFA half. Setup fails unless reflective unwrapping proves that routing —
 * preventing silent rerouting from turning this benchmark into a measurement of some other engine's
 * construction cost.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BitStateCompileFindBenchmark {

  /**
   * GO-shaped fixture: anchored lazy first capture, literal dot separator, slash-free second
   * capture. {@code ^(.+?)\.([^/]+)} is {@code DETERMINISTIC_CHAIN_BYTECODE} at this HEAD; this
   * variant routes to {@code HYBRID_DFA} with a {@code BitStateMatcher} NFA half.
   */
  private static final String PATTERN = "^((?:x|y)+?)\\.([^/]+)";

  /** Production-shaped matching input: short x/y run, literal dot, slash-free tail. */
  private static final String INPUT = "xyxyyxxyxyx.service_registry.node07.eu-west1.checksum_a91f";

  private static final String HYBRID_MATCHER_CLASS = "com.datadoghq.reggie.runtime.HybridMatcher";
  private static final String BITSTATE_MATCHER_CLASS =
      "com.datadoghq.reggie.runtime.BitStateMatcher";

  @Setup(Level.Trial)
  public void setup() throws Exception {
    // Exactly one cache clear for the whole trial: measurement covers the warm cache path only.
    RuntimeCompiler.clearCache();

    // Exactly one compile: warms the runtime cache key and proves the routed engine. The returned
    // matcher is a local probe and is intentionally NOT retained as benchmark state.
    ReggieMatcher probe = RuntimeCompiler.compile(PATTERN);
    if (!probe.find(INPUT)) {
      throw new IllegalStateException(
          "Route guard failed: fixture input does not match PATTERN — benchmark would measure a"
              + " miss path instead of the production hit path. Input: '"
              + INPUT
              + "'");
    }
    verifyBitStateBackedHybridRoute(probe);
  }

  /** Exactly one warm-cache runtime compile plus one find; the result is returned as a boolean. */
  @Benchmark
  public boolean warmCacheCompileAndFind() {
    return RuntimeCompiler.compile(PATTERN).find(INPUT);
  }

  /**
   * Unwraps {@code PrefilteringMatcher}/{@code NameEnrichingMatcher} delegates and asserts the
   * underlying engine is a {@code HybridMatcher} whose NFA half is a {@code BitStateMatcher}. Both
   * classes are runtime-internal, so the unwrapping and assertions are reflective.
   */
  private static void verifyBitStateBackedHybridRoute(ReggieMatcher matcher) throws Exception {
    ReggieMatcher current = matcher;
    while (true) {
      String className = current.getClass().getName();
      if (className.equals("com.datadoghq.reggie.runtime.PrefilteringMatcher")
          || className.equals("com.datadoghq.reggie.runtime.NameEnrichingMatcher")) {
        current = delegateOf(current);
        continue;
      }
      break;
    }
    if (!current.getClass().getName().equals(HYBRID_MATCHER_CLASS)) {
      throw new IllegalStateException(
          "Route guard failed: expected a "
              + HYBRID_MATCHER_CLASS
              + " after unwrapping, got "
              + current.getClass().getName()
              + " — the fixture no longer routes through the hybrid path and this benchmark would"
              + " measure a different engine's construction cost.");
    }
    ReggieMatcher nfaHalf = fieldOf(current, "nfaMatcher");
    if (!nfaHalf.getClass().getName().equals(BITSTATE_MATCHER_CLASS)) {
      throw new IllegalStateException(
          "Route guard failed: hybrid NFA half is "
              + nfaHalf.getClass().getName()
              + ", expected "
              + BITSTATE_MATCHER_CLASS
              + " — the fixture no longer constructs a BitState NFA half and this benchmark would"
              + " not measure BitState matcher setup cost.");
    }
  }

  private static ReggieMatcher delegateOf(ReggieMatcher wrapper) throws Exception {
    return fieldOf(wrapper, "delegate");
  }

  private static ReggieMatcher fieldOf(ReggieMatcher owner, String name) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return (ReggieMatcher) field.get(owner);
  }
}
