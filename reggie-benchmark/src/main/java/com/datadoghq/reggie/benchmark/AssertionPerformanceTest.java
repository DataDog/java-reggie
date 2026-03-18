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

/** Simple performance test for assertion patterns. */
public class AssertionPerformanceTest {

  public static void main(String[] args) {
    AssertionPatterns patterns = Reggie.patterns(AssertionPatterns.class);

    System.out.println("=== Assertion Performance Tests ===\n");

    // Positive Lookahead: a(?=bc)
    testPerformance(
        "Positive Lookahead",
        patterns.positiveLookahead(),
        Pattern.compile("a(?=bc)"),
        "abc",
        1_000_000);

    // Negative Lookahead: a(?!bc)
    testPerformance(
        "Negative Lookahead",
        patterns.negativeLookahead(),
        Pattern.compile("a(?!bc)"),
        "axc",
        1_000_000);

    // Positive Lookbehind: (?<=ab)c
    testPerformance(
        "Positive Lookbehind",
        patterns.positiveLookbehind(),
        Pattern.compile("(?<=ab)c"),
        "abc",
        1_000_000);

    // Negative Lookbehind: (?<!ab)c
    testPerformance(
        "Negative Lookbehind",
        patterns.negativeLookbehind(),
        Pattern.compile("(?<!ab)c"),
        "xbc",
        1_000_000);
  }

  private static void testPerformance(
      String name, ReggieMatcher reggieMatcher, Pattern jdkPattern, String input, int iterations) {
    // Warmup
    for (int i = 0; i < 10_000; i++) {
      reggieMatcher.find(input);
      jdkPattern.matcher(input).find();
    }

    // Measure Reggie
    long reggieStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      reggieMatcher.find(input);
    }
    long reggieTime = System.nanoTime() - reggieStart;
    double reggieOpsPerMs = (iterations * 1_000_000.0) / reggieTime;

    // Measure JDK
    long jdkStart = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(input).find();
    }
    long jdkTime = System.nanoTime() - jdkStart;
    double jdkOpsPerMs = (iterations * 1_000_000.0) / jdkTime;

    double ratio = jdkOpsPerMs / reggieOpsPerMs;

    System.out.printf(
        "%-25s Reggie: %8.0f ops/ms   JDK: %8.0f ops/ms   Ratio: %.2fx %s%n",
        name + ":",
        reggieOpsPerMs,
        jdkOpsPerMs,
        ratio,
        ratio < 1.0 ? "(Reggie faster)" : "(JDK faster)");
  }
}
