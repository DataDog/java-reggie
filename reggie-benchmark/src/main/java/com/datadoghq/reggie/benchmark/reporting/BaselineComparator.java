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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares current benchmark results against a saved baseline to detect regressions. A regression
 * is defined as a performance drop greater than the threshold (default 10%).
 */
public class BaselineComparator {

  private static final double REGRESSION_THRESHOLD = 0.10; // 10%
  private static final double IMPROVEMENT_THRESHOLD = 0.10; // 10%

  /**
   * Compare current results against baseline results.
   *
   * @param current the current benchmark results
   * @param baseline the baseline benchmark results
   * @return comparison result with regressions, improvements, and stable benchmarks
   */
  public static ComparisonResult compare(JMHResults current, JMHResults baseline) {
    if (current == null || baseline == null) {
      return new ComparisonResult(List.of());
    }

    // Build map of baseline scores for quick lookup
    Map<String, JMHResults.BenchmarkResult> baselineMap =
        baseline.getResults().stream()
            .collect(
                Collectors.toMap(
                    JMHResults.BenchmarkResult::benchmark,
                    r -> r,
                    (a, b) -> a // Keep first if duplicates
                    ));

    List<BenchmarkComparison> comparisons = new ArrayList<>();

    for (JMHResults.BenchmarkResult currentResult : current.getResults()) {
      String name = currentResult.benchmark();
      JMHResults.BenchmarkResult baselineResult = baselineMap.get(name);

      if (baselineResult != null) {
        double currentScore = currentResult.score();
        double baselineScore = baselineResult.score();
        double change = (currentScore - baselineScore) / baselineScore;

        Status status;
        if (change < -REGRESSION_THRESHOLD) {
          status = Status.REGRESSION;
        } else if (change > IMPROVEMENT_THRESHOLD) {
          status = Status.IMPROVEMENT;
        } else {
          status = Status.STABLE;
        }

        comparisons.add(new BenchmarkComparison(name, currentScore, baselineScore, change, status));
      }
    }

    return new ComparisonResult(comparisons);
  }

  /** Status of a benchmark compared to baseline. */
  public enum Status {
    /** Performance degraded by more than threshold */
    REGRESSION,
    /** Performance improved by more than threshold */
    IMPROVEMENT,
    /** Performance within acceptable range (±threshold) */
    STABLE
  }

  /** Comparison of a single benchmark against baseline. */
  public static class BenchmarkComparison {
    private final String name;
    private final double currentScore;
    private final double baselineScore;
    private final double percentageChange;
    private final Status status;

    public BenchmarkComparison(
        String name,
        double currentScore,
        double baselineScore,
        double percentageChange,
        Status status) {
      this.name = name;
      this.currentScore = currentScore;
      this.baselineScore = baselineScore;
      this.percentageChange = percentageChange;
      this.status = status;
    }

    public String getName() {
      return name;
    }

    public double getCurrentScore() {
      return currentScore;
    }

    public double getBaselineScore() {
      return baselineScore;
    }

    public double getPercentageChange() {
      return percentageChange;
    }

    public Status getStatus() {
      return status;
    }
  }

  /** Result of comparing all benchmarks against baseline. */
  public static class ComparisonResult {
    private final List<BenchmarkComparison> comparisons;

    public ComparisonResult(List<BenchmarkComparison> comparisons) {
      this.comparisons = comparisons;
    }

    public List<BenchmarkComparison> getComparisons() {
      return comparisons;
    }

    public List<BenchmarkComparison> getRegressions() {
      return comparisons.stream()
          .filter(c -> c.getStatus() == Status.REGRESSION)
          .collect(Collectors.toList());
    }

    public List<BenchmarkComparison> getImprovements() {
      return comparisons.stream()
          .filter(c -> c.getStatus() == Status.IMPROVEMENT)
          .collect(Collectors.toList());
    }

    public List<BenchmarkComparison> getStable() {
      return comparisons.stream()
          .filter(c -> c.getStatus() == Status.STABLE)
          .collect(Collectors.toList());
    }

    public int getTotalCount() {
      return comparisons.size();
    }

    public boolean hasRegressions() {
      return comparisons.stream().anyMatch(c -> c.getStatus() == Status.REGRESSION);
    }
  }
}
