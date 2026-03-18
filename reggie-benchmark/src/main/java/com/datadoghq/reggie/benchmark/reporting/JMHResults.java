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

import com.google.gson.annotations.SerializedName;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Data model for JMH benchmark results (JSON format). JMH outputs an array of benchmark results at
 * the root level.
 */
public class JMHResults {

  private final List<BenchmarkEntry> benchmarks;

  public JMHResults(List<BenchmarkEntry> benchmarks) {
    this.benchmarks = benchmarks;
  }

  public int size() {
    return benchmarks != null ? benchmarks.size() : 0;
  }

  public List<BenchmarkResult> getResults() {
    if (benchmarks == null) return List.of();

    return benchmarks.stream()
        .map(
            entry ->
                new BenchmarkResult(
                    simplifyBenchmarkName(entry.benchmark),
                    entry.primaryMetric.score,
                    entry.primaryMetric.scoreError,
                    entry.primaryMetric.scoreUnit))
        .collect(Collectors.toList());
  }

  public double getAverageThroughput() {
    if (benchmarks == null || benchmarks.isEmpty()) return 0.0;

    return benchmarks.stream().mapToDouble(e -> e.primaryMetric.score).average().orElse(0.0);
  }

  public String getBestBenchmark() {
    if (benchmarks == null || benchmarks.isEmpty()) return "N/A";

    return benchmarks.stream()
        .max((a, b) -> Double.compare(a.primaryMetric.score, b.primaryMetric.score))
        .map(e -> simplifyBenchmarkName(e.benchmark))
        .orElse("N/A");
  }

  private String simplifyBenchmarkName(String fullName) {
    // Simplify "com.datadoghq.reggie.benchmark.AssertionBenchmark.jdkNegativeLookaheadMatch"
    // to "AssertionBenchmark.jdkNegativeLookaheadMatch"
    if (fullName == null) return "Unknown";

    int lastDot = fullName.lastIndexOf('.');
    if (lastDot > 0) {
      int secondLastDot = fullName.lastIndexOf('.', lastDot - 1);
      if (secondLastDot > 0) {
        return fullName.substring(secondLastDot + 1);
      }
    }
    return fullName;
  }

  // JMH JSON structure
  public static class BenchmarkEntry {
    public String benchmark;
    public String mode;
    public int threads;
    public int forks;
    public int warmupIterations;
    public int measurementIterations;

    @SerializedName("primaryMetric")
    public PrimaryMetric primaryMetric;
  }

  public static class PrimaryMetric {
    public double score;
    public double scoreError;
    public String scoreUnit;
    // scorePercentiles is an object/map in JMH output, not used in reporting
  }

  // Simplified result for reporting
  public record BenchmarkResult(String benchmark, double score, double error, String unit) {}

  /**
   * Find a benchmark result by its name.
   *
   * @param name the simplified benchmark name to search for
   * @return the matching benchmark result, or null if not found
   */
  public BenchmarkResult findBenchmark(String name) {
    return getResults().stream().filter(r -> r.benchmark().equals(name)).findFirst().orElse(null);
  }

  /**
   * Find all benchmark results in a specific category.
   *
   * @param category the pattern category to filter by
   * @return list of benchmark results in the specified category
   */
  public List<BenchmarkResult> findByCategory(PatternCategory category) {
    return getResults().stream()
        .filter(r -> PatternCategory.fromBenchmarkName(r.benchmark()) == category)
        .collect(Collectors.toList());
  }
}
