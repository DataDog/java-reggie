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

/**
 * Categories for grouping and filtering benchmark results. Used to organize benchmarks by pattern
 * type in reports.
 */
public enum PatternCategory {
  PHONE("Phone Numbers", "\\d{3}-\\d{3}-\\d{4}"),
  EMAIL("Email Addresses", "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
  IPV4("IPv4 Addresses", "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"),
  UUID("UUIDs", "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
  DIGITS("Digit Sequences", "\\d+"),
  LITERAL("Literal Patterns", "Simple string literals"),
  ASSERTION("Assertions", "Lookahead, lookbehind, word boundaries"),
  BACKREFERENCE("Backreferences", "Back-references to capturing groups"),
  STATE_EXPLOSION("State Explosion", "Catastrophic backtracking patterns"),
  CORPUS("Large Corpus", "Benchmarks on large text corpora"),
  GROUPS("Capturing Groups", "Group extraction operations");

  private final String displayName;
  private final String description;

  PatternCategory(String displayName, String description) {
    this.displayName = displayName;
    this.description = description;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getDescription() {
    return description;
  }

  /**
   * Determine the category from a benchmark method name. Uses pattern matching on the benchmark
   * name to infer category.
   *
   * @param benchmarkName the full benchmark name (e.g.,
   *     "com.datadoghq.reggie.benchmark.MatchOperationBenchmark.reggiePhoneMatch")
   * @return the inferred category
   */
  public static PatternCategory fromBenchmarkName(String benchmarkName) {
    String lower = benchmarkName.toLowerCase();

    // Check for specific patterns
    if (lower.contains("phone")) return PHONE;
    if (lower.contains("email")) return EMAIL;
    if (lower.contains("ipv4") || lower.contains("ip4")) return IPV4;
    if (lower.contains("uuid")) return UUID;
    if (lower.contains("digit")) return DIGITS;
    if (lower.contains("literal") || lower.contains("hello")) return LITERAL;
    if (lower.contains("groups") || lower.contains("groupextraction")) return GROUPS;

    // Check for assertion patterns
    if (lower.contains("lookahead")
        || lower.contains("lookbehind")
        || lower.contains("boundary")
        || lower.contains("assertion")) {
      return ASSERTION;
    }

    // Check for backreference patterns
    if (lower.contains("backreference")) {
      return BACKREFERENCE;
    }

    // Check for state explosion patterns
    if (lower.contains("state") || lower.contains("explosion")) {
      return STATE_EXPLOSION;
    }

    // Check for corpus benchmarks
    if (lower.contains("corpus")) {
      return CORPUS;
    }

    // Default to LITERAL if no match found
    return LITERAL;
  }
}
