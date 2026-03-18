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
import java.util.regex.Pattern;

/**
 * Detailed profiler to identify WHERE time is spent in password validation. Breaks down the pattern
 * into components to isolate the bottleneck.
 */
public class DetailedProfiler {

  private static final String VALID = "Password123!";
  private static final int WARMUP = 100_000;
  private static final int ITERATIONS = 1_000_000;

  public static void main(String[] args) {
    AssertionPatterns patterns = Reggie.patterns(AssertionPatterns.class);

    System.out.println("=== Detailed Performance Profiling ===\n");

    // BASELINE: Full pattern
    Pattern jdkFull = Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}");
    double jdkFullOps = benchmark("JDK Full Pattern", () -> jdkFull.matcher(VALID).matches());

    double reggieFull =
        benchmark("Reggie Full Pattern", () -> patterns.passwordValidation().matches(VALID));

    System.out.println();

    // TEST 1: Just lookaheads (no .{8,})
    Pattern jdkLookaheadsOnly = Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).*");
    double jdkLookaheads =
        benchmark("JDK Lookaheads Only", () -> jdkLookaheadsOnly.matcher(VALID).matches());

    System.out.println();

    // TEST 2: Just quantifier (no lookaheads)
    Pattern jdkQuantOnly = Pattern.compile(".{8,}");
    double jdkQuant = benchmark("JDK Quantifier Only", () -> jdkQuantOnly.matcher(VALID).matches());

    System.out.println();

    // TEST 3: Single lookahead
    Pattern jdkSingleLookahead = Pattern.compile("(?=.*[A-Z]).*");
    double jdkSingle =
        benchmark("JDK Single Lookahead", () -> jdkSingleLookahead.matcher(VALID).matches());

    double reggieSingle =
        benchmark("Reggie Single Lookahead", () -> patterns.positiveLookahead().find(VALID));

    System.out.println();

    // TEST 4: Two lookaheads
    Pattern jdkTwoLookaheads = Pattern.compile("(?=.*[A-Z])(?=.*\\d).*");
    double jdkTwo =
        benchmark("JDK Two Lookaheads", () -> jdkTwoLookaheads.matcher(VALID).matches());

    System.out.println();

    // Analysis
    System.out.println("=== Time Breakdown Analysis ===");
    System.out.printf("JDK Full:          %.1f ops/ms (baseline)%n", jdkFullOps);
    System.out.printf(
        "  Lookaheads:      %.1f ops/ms (%.1f%% of time)%n",
        jdkLookaheads, (1 / jdkLookaheads) / (1 / jdkFullOps) * 100);
    System.out.printf(
        "  Quantifier:      %.1f ops/ms (%.1f%% of time)%n",
        jdkQuant, (1 / jdkQuant) / (1 / jdkFullOps) * 100);
    System.out.printf("  Single:          %.1f ops/ms%n", jdkSingle);
    System.out.printf("  Two:             %.1f ops/ms%n", jdkTwo);
    System.out.println();

    System.out.printf(
        "Reggie Full:       %.1f ops/ms (%.1fx slower)%n", reggieFull, jdkFullOps / reggieFull);
    System.out.printf(
        "  Single LA:       %.1f ops/ms (%.1fx vs JDK single)%n",
        reggieSingle, reggieSingle / jdkSingle);
    System.out.println();

    // Cost analysis
    double costPerLookahead = 1 / jdkSingle - 1 / jdkQuant;
    double expectedThreeLookaheads = 1 / jdkQuant + 3 * costPerLookahead;
    double actualThreeLookaheads = 1 / jdkFullOps;

    System.out.println("=== Lookahead Cost Analysis ===");
    System.out.printf("Cost per lookahead (JDK):     %.1f ns%n", costPerLookahead * 1_000_000);
    System.out.printf(
        "Expected for 3 lookaheads:    %.1f ns (%.1f ops/ms)%n",
        expectedThreeLookaheads * 1_000_000, 1000 / (expectedThreeLookaheads * 1_000_000));
    System.out.printf(
        "Actual for 3 lookaheads:      %.1f ns (%.1f ops/ms)%n",
        actualThreeLookaheads * 1_000_000, 1000 / (actualThreeLookaheads * 1_000_000));
    System.out.printf(
        "Lookahead scaling:            %.1fx (linear would be 1.0x)%n",
        actualThreeLookaheads / expectedThreeLookaheads);
  }

  private static double benchmark(String name, Runnable task) {
    // Warmup
    for (int i = 0; i < WARMUP; i++) {
      task.run();
    }

    // Measure
    long start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      task.run();
    }
    long elapsed = System.nanoTime() - start;

    double opsPerMs = ITERATIONS / (elapsed / 1_000_000.0);
    System.out.printf("%-30s %.0f ops/ms%n", name + ":", opsPerMs);
    return opsPerMs;
  }
}
