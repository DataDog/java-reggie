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
 * Configuration for backtracking behavior in specialized bytecode generators.
 *
 * <p>Reggie uses specialized bytecode generation for patterns that require limited backtracking
 * (e.g., variable-capture backreferences). This class provides configurable limits to prevent
 * pathological cases.
 *
 * <p>Configuration via system properties:
 *
 * <ul>
 *   <li>{@code reggie.backtrack.limit} - Maximum backtrack iterations (default: 10000)
 *   <li>{@code reggie.backtrack.throwOnLimit} - If true, throw exception when limit exceeded; if
 *       false (default), return no-match silently
 * </ul>
 *
 * <p>Example usage:
 *
 * <pre>
 * // Set via system property before first pattern compilation
 * System.setProperty("reggie.backtrack.limit", "5000");
 *
 * // Or via JVM argument
 * java -Dreggie.backtrack.limit=5000 -Dreggie.backtrack.throwOnLimit=true ...
 * </pre>
 */
public final class BacktrackConfig {

  /**
   * Maximum number of backtrack iterations allowed. Default: 10000 iterations.
   *
   * <p>This limit prevents pathological patterns from causing excessive CPU usage. For most
   * real-world patterns, backtracking completes in far fewer iterations.
   */
  public static final int MAX_ITERATIONS = Integer.getInteger("reggie.backtrack.limit", 10000);

  /**
   * Whether to throw an exception when the backtrack limit is exceeded. Default: false (return
   * no-match silently).
   *
   * <p>When false: Patterns exceeding the limit return no-match, which is safe but may hide
   * pathological patterns.
   *
   * <p>When true: Throws {@link BacktrackLimitExceededException}, making it explicit that the
   * pattern exceeded acceptable complexity.
   */
  public static final boolean THROW_ON_LIMIT = Boolean.getBoolean("reggie.backtrack.throwOnLimit");

  private BacktrackConfig() {
    // Utility class - no instantiation
  }

  /**
   * Check if the backtrack limit has been exceeded and handle accordingly.
   *
   * @param iterations current iteration count
   * @return true if limit exceeded and THROW_ON_LIMIT is false (caller should return no-match)
   * @throws BacktrackLimitExceededException if limit exceeded and THROW_ON_LIMIT is true
   */
  public static boolean checkLimit(int iterations) {
    if (iterations >= MAX_ITERATIONS) {
      if (THROW_ON_LIMIT) {
        throw new BacktrackLimitExceededException(iterations, MAX_ITERATIONS);
      }
      return true; // Limit exceeded, return no-match
    }
    return false; // Within limit, continue
  }

  /** Exception thrown when backtrack limit is exceeded and throwOnLimit is enabled. */
  public static class BacktrackLimitExceededException extends RuntimeException {
    private final int iterations;
    private final int limit;

    public BacktrackLimitExceededException(int iterations, int limit) {
      super("Backtrack limit exceeded: " + iterations + " iterations (limit: " + limit + ")");
      this.iterations = iterations;
      this.limit = limit;
    }

    public int getIterations() {
      return iterations;
    }

    public int getLimit() {
      return limit;
    }
  }
}
