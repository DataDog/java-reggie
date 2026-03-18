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
 * Strategy for StringView creation based on pattern characteristics.
 *
 * <p>The optimal strategy depends on: - Expected scan depth (% of input string that will be
 * examined) - Early bailout potential (can reject quickly on first few characters) - SWAR
 * optimization opportunities (bulk character class matching) - Input string length (amortization of
 * setup costs)
 */
public enum StringViewStrategy {
  /**
   * Always use charAt() delegation. Best for: Anchored patterns with early bailout (^abc,
   * ^[A-Z]{5}$) - Zero setup cost - ~1-2ns overhead per character access - No SWAR benefits
   */
  ALWAYS_CHAR_AT,

  /**
   * Always copy the string's byte array. Best for: Patterns with SWAR opportunities or high scan
   * depth - O(n) setup cost (~0.5-1ns per byte) - ~0.1ns per character access - Enables SWAR bulk
   * operations (8x-16x speedup)
   */
  ALWAYS_COPY,

  /**
   * Choose strategy based on input string length at runtime. Best for: Patterns with moderate scan
   * depth, no strong SWAR benefits - Short strings (< threshold): Use charAt - Long strings (>=
   * threshold): Copy array - Threshold determined by pattern characteristics
   */
  LENGTH_BASED_HYBRID;

  /**
   * Recommended length threshold for LENGTH_BASED_HYBRID strategy. Shorter strings use charAt,
   * longer strings use copy.
   */
  private int lengthThreshold = 50; // default

  public void setLengthThreshold(int threshold) {
    this.lengthThreshold = threshold;
  }

  public int getLengthThreshold() {
    return lengthThreshold;
  }
}
