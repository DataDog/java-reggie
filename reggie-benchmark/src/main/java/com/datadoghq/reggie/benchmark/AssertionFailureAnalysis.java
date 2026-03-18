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

/** Analyze why assertion no-match cases are so much slower. */
public class AssertionFailureAnalysis {

  public static void main(String[] args) {
    AssertionPatterns patterns = Reggie.patterns(AssertionPatterns.class);

    System.out.println("=== Assertion Failure Path Analysis ===\n");

    // Test 1: Pattern with assertion at start
    System.out.println("Test 1: a(?=bc) - Lookahead at position 1");
    compareDetailed(
        "Match case",
        patterns.positiveLookahead(),
        Pattern.compile("a(?=bc)"),
        "abc",
        "ab",
        "abc123");
    compareDetailed(
        "No match case",
        patterns.positiveLookahead(),
        Pattern.compile("a(?=bc)"),
        "axc",
        "ax",
        "axc123");

    // Test 2: Multiple attempts
    System.out.println("\nTest 2: Searching in longer string");
    compareDetailed(
        "abc in 'xxxabc'", patterns.positiveLookahead(), Pattern.compile("a(?=bc)"), "xxxabc");
    compareDetailed(
        "axc in 'xxxaxc'", patterns.positiveLookahead(), Pattern.compile("a(?=bc)"), "xxxaxc");

    // Test 3: Lookbehind
    System.out.println("\nTest 3: (?<=ab)c - Lookbehind");
    compareDetailed("Match", patterns.positiveLookbehind(), Pattern.compile("(?<=ab)c"), "abc");
    compareDetailed("No match", patterns.positiveLookbehind(), Pattern.compile("(?<=ab)c"), "xbc");
  }

  private static void compareDetailed(
      String testName, ReggieMatcher reggieMatcher, Pattern jdkPattern, String... inputs) {
    for (String input : inputs) {
      int iterations = 500_000;
      int warmup = 50_000;

      // Warmup
      for (int i = 0; i < warmup; i++) {
        reggieMatcher.find(input);
        jdkPattern.matcher(input).find();
      }

      // Measure
      long reggieStart = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        reggieMatcher.find(input);
      }
      long reggieTime = System.nanoTime() - reggieStart;
      double reggieOpsMs = (iterations * 1_000_000.0) / reggieTime;

      long jdkStart = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        jdkPattern.matcher(input).find();
      }
      long jdkTime = System.nanoTime() - jdkStart;
      double jdkOpsMs = (iterations * 1_000_000.0) / jdkTime;

      double percentOfJdk = (reggieOpsMs / jdkOpsMs) * 100.0;

      System.out.printf(
          "  %-20s input='%-10s' Reggie: %8.0f ops/ms  JDK: %8.0f ops/ms  (%.1f%%)%n",
          testName + ":", input, reggieOpsMs, jdkOpsMs, percentOfJdk);
    }
  }
}
