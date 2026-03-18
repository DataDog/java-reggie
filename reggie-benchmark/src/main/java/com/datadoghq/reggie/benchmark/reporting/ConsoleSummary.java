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
package com.datadoghq.reggie.benchmark.reporting;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Prints a concise, colored summary of benchmark results to the console. Supports N-way comparison
 * (Reggie vs JDK vs RE2J vs any future engines). Uses ANSI color codes for visual clarity.
 */
public class ConsoleSummary {

  private static final String ANSI_RESET = "\u001B[0m";
  private static final String ANSI_GREEN = "\u001B[32m";
  private static final String ANSI_RED = "\u001B[31m";
  private static final String ANSI_YELLOW = "\u001B[33m";
  private static final String ANSI_BOLD = "\u001B[1m";
  private static final String ANSI_CYAN = "\u001B[36m";

  // Known implementations for detection
  private static final List<String> KNOWN_IMPLS = List.of("reggie", "jdk", "re2j");

  private String detectImplementation(String benchmarkName) {
    String lower = benchmarkName.toLowerCase();
    for (String impl : KNOWN_IMPLS) {
      if (lower.contains(impl)) return impl;
    }
    return "other";
  }

  /**
   * Print summary of benchmark results and optionally compare with baseline.
   *
   * @param results the current benchmark results
   * @param baseline the baseline results (may be null)
   */
  public void print(JMHResults results, JMHResults baseline) {
    printHeader();
    printOverview(results);

    if (results.size() > 0) {
      printPerformanceComparison(results);

      if (baseline != null) {
        BaselineComparator.ComparisonResult comparison =
            BaselineComparator.compare(results, baseline);
        printBaselineComparison(comparison);
      }

      printCategorySummary(results);
    }

    printFooter();
  }

  private void printHeader() {
    System.out.println();
    System.out.println("===============================================");
    System.out.println(ANSI_BOLD + ANSI_CYAN + "    Reggie Benchmark Results" + ANSI_RESET);
    System.out.println("===============================================");
    System.out.println();
  }

  private void printOverview(JMHResults results) {
    System.out.println("Total Benchmarks: " + ANSI_BOLD + results.size() + ANSI_RESET);
    if (results.size() > 0) {
      System.out.printf("Average Throughput: %.2f ops/ms%n", results.getAverageThroughput());
      System.out.println("Best Performer: " + ANSI_BOLD + results.getBestBenchmark() + ANSI_RESET);
    }
    System.out.println();
  }

  private void printPerformanceComparison(JMHResults results) {
    List<JMHResults.BenchmarkResult> allResults = results.getResults();

    // Group by implementation
    Map<String, List<JMHResults.BenchmarkResult>> byImpl =
        allResults.stream()
            .collect(Collectors.groupingBy(r -> detectImplementation(r.benchmark())));

    List<JMHResults.BenchmarkResult> reggieResults = byImpl.getOrDefault("reggie", List.of());

    if (reggieResults.isEmpty()) {
      return;
    }

    double reggieAvg =
        reggieResults.stream().mapToDouble(JMHResults.BenchmarkResult::score).average().orElse(0);

    System.out.println("Performance Comparison:");
    System.out.printf("  Reggie Average: %.2f ops/ms%n", reggieAvg);

    // Show stats for each non-reggie implementation
    Map<String, Double> reggieScores = new HashMap<>();
    for (JMHResults.BenchmarkResult r : reggieResults) {
      String key = normalizeKey(r.benchmark());
      reggieScores.put(key, r.score());
    }

    for (String impl : byImpl.keySet()) {
      if (impl.equals("reggie") || impl.equals("other")) continue;

      List<JMHResults.BenchmarkResult> implResults = byImpl.get(impl);
      double implAvg =
          implResults.stream().mapToDouble(JMHResults.BenchmarkResult::score).average().orElse(0);
      double speedup = reggieAvg / implAvg;

      System.out.printf("  %s Average: %.2f ops/ms%n", capitalize(impl), implAvg);
      String color = speedup >= 1.0 ? ANSI_GREEN : ANSI_RED;
      System.out.printf(
          "  Speedup vs %s: %s%.2fx%s%n",
          impl.toUpperCase(), color + ANSI_BOLD, speedup, ANSI_RESET);

      // Count wins
      int reggieWins = 0;
      int implWins = 0;

      for (JMHResults.BenchmarkResult r : implResults) {
        String key = normalizeKey(r.benchmark());
        Double reggieScore = reggieScores.get(key);
        if (reggieScore != null) {
          if (reggieScore > r.score()) {
            reggieWins++;
          } else {
            implWins++;
          }
        }
      }

      int total = reggieWins + implWins;
      if (total > 0) {
        System.out.printf(
            "  %sReggie > %s: %d/%d (%.1f%%)%s%n",
            ANSI_GREEN,
            impl.toUpperCase(),
            reggieWins,
            total,
            100.0 * reggieWins / total,
            ANSI_RESET);
      }
    }
    System.out.println();
  }

  private String capitalize(String s) {
    return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
  }

  private String normalizeKey(String benchmarkName) {
    // Remove implementation prefix to match benchmarks
    String simple = benchmarkName.replaceFirst(".*\\.(reggie|jdk|re2j)", "");
    return simple.toLowerCase();
  }

  private void printBaselineComparison(BaselineComparator.ComparisonResult comparison) {
    int regressions = comparison.getRegressions().size();
    int improvements = comparison.getImprovements().size();
    int stable = comparison.getStable().size();

    System.out.println("Baseline Comparison:");
    System.out.printf("  %s✓ %d stable (within 10%%)%s%n", ANSI_GREEN, stable, ANSI_RESET);

    if (improvements > 0) {
      System.out.printf(
          "  %s✓ %d improved (>10%% faster)%s%n", ANSI_GREEN + ANSI_BOLD, improvements, ANSI_RESET);
    }

    if (regressions > 0) {
      System.out.printf(
          "  %s⚠ %d regressions detected:%s%n", ANSI_RED + ANSI_BOLD, regressions, ANSI_RESET);

      for (BaselineComparator.BenchmarkComparison comp : comparison.getRegressions()) {
        System.out.printf(
            "    %s- %s: %.1f%% slower (was %.0f ops/ms, now %.0f ops/ms)%s%n",
            ANSI_RED,
            comp.getName(),
            -comp.getPercentageChange() * 100,
            comp.getBaselineScore(),
            comp.getCurrentScore(),
            ANSI_RESET);
      }
    }
    System.out.println();
  }

  private void printCategorySummary(JMHResults results) {
    Map<PatternCategory, List<JMHResults.BenchmarkResult>> byCategory =
        results.getResults().stream()
            .collect(Collectors.groupingBy(r -> PatternCategory.fromBenchmarkName(r.benchmark())));

    if (byCategory.size() > 1) {
      System.out.println("Categories:");

      for (Map.Entry<PatternCategory, List<JMHResults.BenchmarkResult>> entry :
          byCategory.entrySet()) {
        PatternCategory category = entry.getKey();
        List<JMHResults.BenchmarkResult> categoryResults = entry.getValue();

        double avgScore =
            categoryResults.stream()
                .mapToDouble(JMHResults.BenchmarkResult::score)
                .average()
                .orElse(0.0);

        System.out.printf(
            "  %s: %d benchmarks (avg %.0f ops/ms)%n",
            category.getDisplayName(), categoryResults.size(), avgScore);
      }
      System.out.println();
    }
  }

  private void printFooter() {
    System.out.println("===============================================");
    System.out.println(
        "Full report: " + ANSI_BOLD + "build/reports/benchmark-report.html" + ANSI_RESET);
    System.out.println("===============================================");
    System.out.println();
  }
}
