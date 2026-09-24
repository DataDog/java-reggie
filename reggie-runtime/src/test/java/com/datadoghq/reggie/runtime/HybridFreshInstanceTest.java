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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Review regression: compile() must return a fresh matcher instance for hybrid patterns. The hybrid
 * nfa-half (PikeVM/BitState, or a generated NFA matcher) carries mutable per-call buffers; sharing
 * one instance across compile() calls is a data race for any caller that matches from two threads —
 * the same reason PikeVM/BitState patterns never stay in the L1 pattern cache.
 */
class HybridFreshInstanceTest {

  private static final String HYBRID_PATTERN = ".*-shadow(-.*)?-sep";

  @Test
  void hybridCompileReturnsFreshInstances() {
    ReggieMatcher first = Reggie.compile(HYBRID_PATTERN);
    ReggieMatcher second = Reggie.compile(HYBRID_PATTERN);
    assertNotSame(first, second, "hybrid instances must not be shared across compile() calls");
    // both instances behave identically
    String input = "kind-foo-shadow-x-sep-bar";
    assertEquals(String.valueOf(first.findMatch(input)), String.valueOf(second.findMatch(input)));
  }

  @Test
  void concurrentMatchingOnPerCallInstancesIsStable() throws Exception {
    String input = "kind-foo-shadow" + "-x".repeat(200) + "-sep-bar";
    String expected = String.valueOf(Reggie.compile(HYBRID_PATTERN).findMatch(input));
    AtomicInteger mismatches = new AtomicInteger();
    Thread[] threads = new Thread[4];
    for (int t = 0; t < threads.length; t++) {
      threads[t] =
          new Thread(
              () -> {
                for (int i = 0; i < 5000; i++) {
                  // each iteration uses its own instance, as a compile()-per-call consumer would
                  ReggieMatcher m = Reggie.compile(HYBRID_PATTERN);
                  if (!expected.equals(String.valueOf(m.findMatch(input)))) {
                    mismatches.incrementAndGet();
                    return;
                  }
                }
              });
      threads[t].start();
    }
    for (Thread t : threads) {
      t.join(60_000);
    }
    assertEquals(0, mismatches.get());
    assertTrue(RuntimeCompiler.describeRouting(HYBRID_PATTERN).routing.contains("HYBRID"));
  }
}
