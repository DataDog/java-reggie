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

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Performance test for structural caching. This test measures the compilation speedup from level 2
 * cache hits.
 */
public class StructuralCachePerformanceTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void measureCachePerformance() {
    System.out.println("\n=== Structural Cache Performance Test ===\n");

    // Test 1: First compilation (cache miss - full pipeline)
    RuntimeCompiler.clearCache();
    long start = System.nanoTime();
    Reggie.compile("\\d{3}");
    long cacheMissTime = System.nanoTime() - start;
    System.out.printf("1. Cache MISS (full compilation): %.2f ms\n", cacheMissTime / 1_000_000.0);
    System.out.printf(
        "   Pattern cache: %d, Structural cache: %d\n",
        RuntimeCompiler.cacheSize(), RuntimeCompiler.structuralCacheSize());

    // Test 2: Same pattern (level 1 cache hit - instant)
    start = System.nanoTime();
    Reggie.compile("\\d{3}");
    long level1HitTime = System.nanoTime() - start;
    System.out.printf(
        "\n2. Level 1 HIT (same pattern): %.2f ms (%.1fx faster)\n",
        level1HitTime / 1_000_000.0, (double) cacheMissTime / level1HitTime);
    System.out.printf(
        "   Pattern cache: %d, Structural cache: %d\n",
        RuntimeCompiler.cacheSize(), RuntimeCompiler.structuralCacheSize());

    // Test 3: Similar pattern (level 2 cache hit - skips bytecode generation)
    start = System.nanoTime();
    Reggie.compile("\\d{4}");
    long level2HitTime = System.nanoTime() - start;
    System.out.printf(
        "\n3. Level 2 HIT (similar pattern): %.2f ms (%.1fx faster than miss)\n",
        level2HitTime / 1_000_000.0, (double) cacheMissTime / level2HitTime);
    System.out.printf(
        "   Pattern cache: %d, Structural cache: %d\n",
        RuntimeCompiler.cacheSize(), RuntimeCompiler.structuralCacheSize());

    // Test 4: Another similar pattern (level 2 hit again)
    start = System.nanoTime();
    Reggie.compile("\\d{5}");
    long level2HitTime2 = System.nanoTime() - start;
    System.out.printf(
        "\n4. Level 2 HIT (another similar): %.2f ms (%.1fx faster than miss)\n",
        level2HitTime2 / 1_000_000.0, (double) cacheMissTime / level2HitTime2);
    System.out.printf(
        "   Pattern cache: %d, Structural cache: %d\n",
        RuntimeCompiler.cacheSize(), RuntimeCompiler.structuralCacheSize());

    // Test 5: Different strategy (cache miss)
    start = System.nanoTime();
    Reggie.compile("[a-z]+");
    long differentStrategyTime = System.nanoTime() - start;
    System.out.printf(
        "\n5. Cache MISS (different strategy): %.2f ms\n", differentStrategyTime / 1_000_000.0);
    System.out.printf(
        "   Pattern cache: %d, Structural cache: %d\n",
        RuntimeCompiler.cacheSize(), RuntimeCompiler.structuralCacheSize());

    // Measure average speedup for many similar patterns
    System.out.println("\n=== Bulk Test: 50 Similar Patterns ===");
    RuntimeCompiler.clearCache();

    start = System.nanoTime();
    for (int i = 0; i < 50; i++) {
      Reggie.compile("\\d{" + (3 + i) + "}");
    }
    long withCacheTime = System.nanoTime() - start;

    System.out.printf(
        "With structural cache: %.2f ms (avg %.2f ms per pattern)\n",
        withCacheTime / 1_000_000.0, withCacheTime / 1_000_000.0 / 50);
    System.out.printf(
        "Pattern cache: %d, Structural cache: %d\n",
        RuntimeCompiler.cacheSize(), RuntimeCompiler.structuralCacheSize());

    // Estimate time without cache (first compilation time × 50)
    double estimatedWithoutCache = (cacheMissTime / 1_000_000.0) * 50;
    System.out.printf(
        "\nEstimated without cache: %.2f ms (avg %.2f ms per pattern)\n",
        estimatedWithoutCache, estimatedWithoutCache / 50);
    System.out.printf(
        "Speedup: %.1fx faster\n", estimatedWithoutCache / (withCacheTime / 1_000_000.0));

    System.out.println("\n=== Summary ===");
    System.out.printf(
        "- Level 1 cache (same pattern): %.0fx speedup\n", (double) cacheMissTime / level1HitTime);
    System.out.printf(
        "- Level 2 cache (similar pattern): %.1fx speedup\n",
        (double) cacheMissTime / level2HitTime);
    System.out.printf(
        "- Bulk compilation (50 patterns): %.1fx speedup\n",
        estimatedWithoutCache / (withCacheTime / 1_000_000.0));

    System.out.println("\n=== Test Complete ===");
  }
}
