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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Manual profiling test to identify NFA bottlenecks. Run this with a profiler (e.g.,
 * async-profiler, JFR) to see hotspots.
 */
public class NFAProfilingTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void profileDigitPatternMatch() {
    // Pattern: (\d+) - simple but slow (7.9x slower than JDK)
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");
    String input = "12345";

    System.out.println("Starting profiling run for match()...");
    System.out.println(
        "Run with: java -agentpath:.../libasyncProfiler.so=start,event=cpu,file=profile.html");

    // Warmup
    for (int i = 0; i < 10000; i++) {
      matcher.match(input);
    }

    // Measured run
    long start = System.nanoTime();
    int iterations = 1_000_000;
    for (int i = 0; i < iterations; i++) {
      MatchResult result = matcher.match(input);
      if (result == null || result.group(1) == null) {
        throw new RuntimeException("Match failed");
      }
    }
    long duration = System.nanoTime() - start;

    double opsPerMs = (iterations * 1_000_000.0) / duration;
    System.out.printf(
        "Performance: %.2f ops/ms (%.2f ns/op)%n", opsPerMs, duration / (double) iterations);
  }

  @Test
  public void profileDigitPatternFindMatch() {
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");
    String input = "The value is 98765 here";

    System.out.println("Starting profiling run for findMatch()...");

    // Warmup
    for (int i = 0; i < 10000; i++) {
      matcher.findMatch(input);
    }

    // Measured run
    long start = System.nanoTime();
    int iterations = 500_000;
    for (int i = 0; i < iterations; i++) {
      MatchResult result = matcher.findMatch(input);
      if (result == null || result.group(1) == null) {
        throw new RuntimeException("Match failed");
      }
    }
    long duration = System.nanoTime() - start;

    double opsPerMs = (iterations * 1_000_000.0) / duration;
    System.out.printf(
        "Performance: %.2f ops/ms (%.2f ns/op)%n", opsPerMs, duration / (double) iterations);
  }

  @Test
  public void compareWithJDK() {
    // Compare Reggie vs JDK for the same pattern
    ReggieMatcher reggie = RuntimeCompiler.compile("(\\d+)");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(\\d+)");
    String input = "12345";

    // Warmup both
    for (int i = 0; i < 10000; i++) {
      reggie.match(input);
      jdk.matcher(input).matches();
    }

    // Measure Reggie
    long startReggie = System.nanoTime();
    int iterations = 1_000_000;
    for (int i = 0; i < iterations; i++) {
      reggie.match(input);
    }
    long durationReggie = System.nanoTime() - startReggie;

    // Measure JDK
    long startJDK = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      java.util.regex.Matcher m = jdk.matcher(input);
      m.matches();
      m.group(1);
    }
    long durationJDK = System.nanoTime() - startJDK;

    double reggieOpsPerMs = (iterations * 1_000_000.0) / durationReggie;
    double jdkOpsPerMs = (iterations * 1_000_000.0) / durationJDK;
    double gap = jdkOpsPerMs / reggieOpsPerMs;

    System.out.printf("Reggie: %.2f ops/ms%n", reggieOpsPerMs);
    System.out.printf("JDK:    %.2f ops/ms%n", jdkOpsPerMs);
    System.out.printf("Gap:    %.2fx slower%n", gap);
  }

  @Test
  public void profileInstrumentation() {
    // Use reflection to call the generated matcher and add instrumentation
    ReggieMatcher matcher = RuntimeCompiler.compile("(\\d+)");
    String input = "12345";

    System.out.println("Matcher class: " + matcher.getClass().getName());
    System.out.println(
        "Matcher type: " + (matcher instanceof HybridMatcher ? "HybridMatcher" : "Direct"));

    if (matcher instanceof HybridMatcher) {
      System.out.println("Using hybrid mode (DFA + NFA)");
      // Can't easily instrument without bytecode manipulation
    } else {
      System.out.println("Using direct strategy");
    }

    // Run and measure
    long start = System.nanoTime();
    for (int i = 0; i < 100_000; i++) {
      matcher.match(input);
    }
    long duration = System.nanoTime() - start;
    System.out.printf("Average time per operation: %.2f ns%n", duration / 100_000.0);
  }
}
