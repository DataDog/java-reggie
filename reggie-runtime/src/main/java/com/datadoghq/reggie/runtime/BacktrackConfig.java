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

/**
 * Configuration of the step budget of the recursive-descent matchers that may backtrack.
 *
 * <p>Configuration via system properties:
 *
 * <ul>
 *   <li>{@code reggie.rd.maxSteps} - Base per-attempt call budget of the recursive-descent matchers
 *       that may backtrack (default: 100000000); see {@link #MAX_RD_STEPS}
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>
 * // Set via system property before the class is loaded
 * System.setProperty("reggie.rd.maxSteps", "1000000");
 *
 * // Or via JVM argument
 * java -Dreggie.rd.maxSteps=1000000 ...
 * </pre>
 */
public final class BacktrackConfig {

  /**
   * Base per-attempt budget of parse-method calls for a recursive-descent matcher generated without
   * the one-way guarantee. Each match attempt (each start position of a search) may make {@code
   * MAX_RD_STEPS} calls plus a fixed number per character of the searched region; exceeding that
   * throws the exception returned by {@link #rdStepBudgetExceeded()}. Default: 100000000.
   */
  public static final int MAX_RD_STEPS = Integer.getInteger("reggie.rd.maxSteps", 100_000_000);

  private BacktrackConfig() {
    // Utility class - no instantiation
  }

  /**
   * Returns the exception a recursive-descent matcher throws when an attempt exceeds its call
   * budget (see {@link #MAX_RD_STEPS}). Generated code throws the result.
   */
  public static MatchBudgetExceededException rdStepBudgetExceeded() {
    return new MatchBudgetExceededException(
        "Recursive-descent match step budget exceeded (tune via -Dreggie.rd.maxSteps) — input is"
            + " too ambiguous for bounded exploration");
  }
}
