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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IPv4PerfTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void investigateIPv4Performance() {
    String pattern = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}";

    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    String testNoMatch = "This is some text without any IP addresses in it at all";
    String testWithMatch = "Server at 192.168.1.1 responded";
    String longNoMatch =
        "The quick brown fox jumps over the lazy dog. This sentence contains no IP addresses whatsoever and is meant to simulate typical prose text.";

    // Verify correctness first
    assertFalse(matcher.find(testNoMatch));
    assertTrue(matcher.find(testWithMatch));
    assertFalse(matcher.find(longNoMatch));

    // Warmup
    for (int i = 0; i < 50000; i++) {
      matcher.find(testNoMatch);
      jdkPattern.matcher(testNoMatch).find();
      matcher.find(longNoMatch);
      jdkPattern.matcher(longNoMatch).find();
    }

    int iterations = 200000;

    // Test short no-match case
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      matcher.find(testNoMatch);
    }
    long reggieShortNoMatch = System.nanoTime() - start;

    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(testNoMatch).find();
    }
    long jdkShortNoMatch = System.nanoTime() - start;

    // Test long no-match case
    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      matcher.find(longNoMatch);
    }
    long reggieLongNoMatch = System.nanoTime() - start;

    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(longNoMatch).find();
    }
    long jdkLongNoMatch = System.nanoTime() - start;

    // Test with-match case
    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      matcher.find(testWithMatch);
    }
    long reggieMatch = System.nanoTime() - start;

    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(testWithMatch).find();
    }
    long jdkMatch = System.nanoTime() - start;

    System.out.println("\nIPv4 Pattern Performance (" + iterations + " iterations)");
    System.out.println("Pattern: " + pattern);
    System.out.println();
    System.out.println("Short no-match (58 chars):");
    System.out.println("  JDK:    " + (jdkShortNoMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieShortNoMatch / 1_000_000) + " ms");
    System.out.println(
        "  Ratio:  " + String.format("%.2fx", (double) reggieShortNoMatch / jdkShortNoMatch));
    System.out.println();
    System.out.println("Long no-match (142 chars):");
    System.out.println("  JDK:    " + (jdkLongNoMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieLongNoMatch / 1_000_000) + " ms");
    System.out.println(
        "  Ratio:  " + String.format("%.2fx", (double) reggieLongNoMatch / jdkLongNoMatch));
    System.out.println();
    System.out.println("With match (31 chars):");
    System.out.println("  JDK:    " + (jdkMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieMatch / 1_000_000) + " ms");
    System.out.println("  Ratio:  " + String.format("%.2fx", (double) reggieMatch / jdkMatch));
  }
}
