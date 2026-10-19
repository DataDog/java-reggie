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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * The DETERMINISTIC_CHAIN_BYTECODE fallback is stored in the shared generated matcher, so {@link
 * RuntimeCompiler#createChainFallback} must return a matcher that concurrent callers can use.
 */
class ChainFallbackPoolingTest {

  @Test
  void fallbackIsPooledAndThreadSafe() throws Exception {
    ReggieMatcher fb = RuntimeCompiler.createChainFallback("(?<w>[a-z]+)=(\\d+)", Map.of("w", 1));
    assertInstanceOf(PooledMatcher.class, fb);
    assertEquals("(?<w>[a-z]+)=(\\d+)", fb.pattern());
    assertEquals("key", fb.findMatch("key=1").group("w"));

    String[] inputs = {"a=1", " bb=22", "  ccc=333", "   dddd=4444"};
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        int offset = t;
        tasks.add(
            () -> {
              int[] starts = new int[3];
              int[] ends = new int[3];
              for (int i = 0; i < 20_000; i++) {
                int k = (i + offset) % inputs.length;
                MatchResult r = fb.findMatch(inputs[k]);
                assertEquals(k, r.start(1), inputs[k]);
                assertEquals(inputs[k].length(), r.end(2), inputs[k]);
                assertTrue(fb.findMatchInto(inputs[k], starts, ends), inputs[k]);
                assertEquals(k, starts[1], inputs[k]);
                assertEquals(inputs[k].length(), ends[2], inputs[k]);
              }
              return null;
            });
      }
      for (Future<Void> f : pool.invokeAll(tasks)) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
