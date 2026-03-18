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
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.regex.Pattern;

/**
 * Comprehensive performance comparison between Reggie and JDK. Shows performance as percentage of
 * JDK for each pattern type.
 */
public class ComprehensivePerformanceReport {

  private static class BenchmarkResult {
    String patternName;
    String pattern;
    String input;
    double reggieOpsMs;
    double jdkOpsMs;
    double percentOfJdk;

    BenchmarkResult(String name, String pattern, String input) {
      this.patternName = name;
      this.pattern = pattern;
      this.input = input;
    }
  }

  public static void main(String[] args) {
    System.out.println("=== Comprehensive Performance Report ===\n");
    System.out.println("Comparing Reggie vs JDK Pattern performance");
    System.out.println("Higher percentage = closer to JDK performance\n");

    AssertionPatterns assertionPatterns = Reggie.patterns(AssertionPatterns.class);
    ExamplePatterns examplePatterns = Reggie.patterns(ExamplePatterns.class);

    BenchmarkResult[] results =
        new BenchmarkResult[] {
          // Assertion patterns
          benchmark(
              "Positive Lookahead (match)",
              "a(?=bc)",
              "abc",
              assertionPatterns.positiveLookahead(),
              Pattern.compile("a(?=bc)")),
          benchmark(
              "Positive Lookahead (no match)",
              "a(?=bc)",
              "axc",
              assertionPatterns.positiveLookahead(),
              Pattern.compile("a(?=bc)")),
          benchmark(
              "Negative Lookahead (match)",
              "a(?!bc)",
              "axc",
              assertionPatterns.negativeLookahead(),
              Pattern.compile("a(?!bc)")),
          benchmark(
              "Negative Lookahead (no match)",
              "a(?!bc)",
              "abc",
              assertionPatterns.negativeLookahead(),
              Pattern.compile("a(?!bc)")),
          benchmark(
              "Positive Lookbehind (match)",
              "(?<=ab)c",
              "abc",
              assertionPatterns.positiveLookbehind(),
              Pattern.compile("(?<=ab)c")),
          benchmark(
              "Positive Lookbehind (no match)",
              "(?<=ab)c",
              "xbc",
              assertionPatterns.positiveLookbehind(),
              Pattern.compile("(?<=ab)c")),
          benchmark(
              "Negative Lookbehind (match)",
              "(?<!ab)c",
              "xbc",
              assertionPatterns.negativeLookbehind(),
              Pattern.compile("(?<!ab)c")),
          benchmark(
              "Negative Lookbehind (no match)",
              "(?<!ab)c",
              "abc",
              assertionPatterns.negativeLookbehind(),
              Pattern.compile("(?<!ab)c")),

          // Simple patterns
          benchmark(
              "Phone Number",
              "\\d{3}-\\d{3}-\\d{4}",
              "123-456-7890",
              examplePatterns.phone(),
              Pattern.compile("\\d{3}-\\d{3}-\\d{4}")),
          benchmark(
              "Hello", "hello", "hello world", examplePatterns.hello(), Pattern.compile("hello")),
          benchmark(
              "Digits", "\\d+", "abc123def", examplePatterns.digits(), Pattern.compile("\\d+")),
        };

    // Print results table
    System.out.println(
        "┌─────────────────────────────────────────┬──────────────┬──────────────┬──────────────┐");
    System.out.println(
        "│ Pattern                                 │ Reggie ops/ms│ JDK ops/ms   │ % of JDK     │");
    System.out.println(
        "├─────────────────────────────────────────┼──────────────┼──────────────┼──────────────┤");

    for (BenchmarkResult result : results) {
      System.out.printf(
          "│ %-39s │ %12.0f │ %12.0f │ %11.1f%% │%n",
          truncate(result.patternName, 39),
          result.reggieOpsMs,
          result.jdkOpsMs,
          result.percentOfJdk);
    }

    System.out.println(
        "└─────────────────────────────────────────┴──────────────┴──────────────┴──────────────┘");

    // Summary by category
    System.out.println("\n=== Summary by Category ===\n");

    double[] lookaheadPerf = {
      results[0].percentOfJdk, results[1].percentOfJdk,
      results[2].percentOfJdk, results[3].percentOfJdk
    };
    double[] lookbehindPerf = {
      results[4].percentOfJdk, results[5].percentOfJdk,
      results[6].percentOfJdk, results[7].percentOfJdk
    };
    double[] simplePerf = {
      results[8].percentOfJdk, results[9].percentOfJdk, results[10].percentOfJdk
    };

    System.out.printf("Lookahead assertions:   %.1f%% of JDK (avg)%n", avg(lookaheadPerf));
    System.out.printf("Lookbehind assertions:  %.1f%% of JDK (avg)%n", avg(lookbehindPerf));
    System.out.printf("Simple patterns:        %.1f%% of JDK (avg)%n", avg(simplePerf));
    System.out.printf("%nOverall average:        %.1f%% of JDK%n", avgAll(results));
  }

  private static BenchmarkResult benchmark(
      String name, String pattern, String input, ReggieMatcher reggieMatcher, Pattern jdkPattern) {
    int iterations = 1_000_000;
    int warmup = 100_000;

    BenchmarkResult result = new BenchmarkResult(name, pattern, input);

    // Warmup
    for (int i = 0; i < warmup; i++) {
      reggieMatcher.find(input);
      jdkPattern.matcher(input).find();
    }

    // Benchmark Reggie
    long reggieStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      reggieMatcher.find(input);
    }
    long reggieTime = System.nanoTime() - reggieStart;
    result.reggieOpsMs = (iterations * 1_000_000.0) / reggieTime;

    // Benchmark JDK
    long jdkStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(input).find();
    }
    long jdkTime = System.nanoTime() - jdkStart;
    result.jdkOpsMs = (iterations * 1_000_000.0) / jdkTime;

    result.percentOfJdk = (result.reggieOpsMs / result.jdkOpsMs) * 100.0;

    return result;
  }

  private static String truncate(String s, int maxLen) {
    return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
  }

  private static double avg(double[] values) {
    double sum = 0;
    for (double v : values) sum += v;
    return sum / values.length;
  }

  private static double avgAll(BenchmarkResult[] results) {
    double sum = 0;
    for (BenchmarkResult r : results) sum += r.percentOfJdk;
    return sum / results.length;
  }
}
