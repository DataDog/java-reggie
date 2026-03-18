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
package com.datadoghq.reggie.codegen.analysis;

/**
 * Characteristics of a regex pattern that inform StringView strategy selection.
 *
 * <p>These metrics are computed during pattern analysis to determine the optimal way to access the
 * input string during matching.
 */
public class PatternCharacteristics {
  private final boolean hasEarlyBailout;
  private final double expectedScanRatio;
  private final int comparisonIntensity;
  private final boolean hasSwarOpportunities;

  public PatternCharacteristics(
      boolean hasEarlyBailout,
      double expectedScanRatio,
      int comparisonIntensity,
      boolean hasSwarOpportunities) {
    this.hasEarlyBailout = hasEarlyBailout;
    this.expectedScanRatio = expectedScanRatio;
    this.comparisonIntensity = comparisonIntensity;
    this.hasSwarOpportunities = hasSwarOpportunities;
  }

  /**
   * Whether the pattern can reject input early based on first few characters. Examples: ^abc
   * (literal prefix), ^[A-Z] (restrictive start anchor)
   */
  public boolean hasEarlyBailout() {
    return hasEarlyBailout;
  }

  /**
   * Expected percentage of input string that will be scanned (0.0 - 1.0). - 0.1: Likely rejects on
   * first few characters - 0.5: Scans about half the string - 1.0: Must scan entire string
   * (unanchored patterns)
   */
  public double getExpectedScanRatio() {
    return expectedScanRatio;
  }

  /**
   * Number of character comparisons per input character (1-10). - 1: Simple literal matching - 5:
   * Character class with multiple ranges - 8+: SWAR-friendly bulk operations
   */
  public int getComparisonIntensity() {
    return comparisonIntensity;
  }

  /**
   * Whether the pattern has opportunities for SWAR (SIMD Within A Register) optimizations.
   * Examples: [0-9a-fA-F]+, \d+, [a-zA-Z0-9]+
   */
  public boolean hasSwarOpportunities() {
    return hasSwarOpportunities;
  }

  /** Recommend the optimal StringView strategy based on pattern characteristics. */
  public StringViewStrategy recommendStrategy() {
    // SWAR opportunities → always worth copying for massive speedup
    if (hasSwarOpportunities) {
      StringViewStrategy strategy = StringViewStrategy.ALWAYS_COPY;
      return strategy;
    }

    // Early bailout + low scan ratio → charAt preferred (minimal setup cost)
    if (hasEarlyBailout && expectedScanRatio < 0.2) {
      return StringViewStrategy.ALWAYS_CHAR_AT;
    }

    // High scan ratio or intensity → copy cost amortizes quickly
    if (expectedScanRatio > 0.5 || comparisonIntensity > 5) {
      return StringViewStrategy.ALWAYS_COPY;
    }

    // Medium complexity → use length-based hybrid strategy
    StringViewStrategy strategy = StringViewStrategy.LENGTH_BASED_HYBRID;

    // Calculate break-even threshold:
    // copy_cost = length * 0.75ns
    // charAt_overhead = (length * scanRatio) * 1.5ns
    // Break-even when: length * 0.75 = length * scanRatio * 1.5
    // Simplifies to: threshold = 0.5 / scanRatio

    int threshold = (int) (50.0 / Math.max(expectedScanRatio, 0.1));
    threshold = Math.max(20, Math.min(100, threshold)); // clamp to reasonable range

    strategy.setLengthThreshold(threshold);
    return strategy;
  }

  @Override
  public String toString() {
    return String.format(
        "PatternCharacteristics{earlyBailout=%s, scanRatio=%.2f, intensity=%d, swar=%s}",
        hasEarlyBailout, expectedScanRatio, comparisonIntensity, hasSwarOpportunities);
  }
}
