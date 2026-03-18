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

public class BoundedQuantifierPerfTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testMACAddressPerformance() {
    String pattern =
        "[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}";

    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    String testNoMatch = "This text has no MAC addresses";
    String testWithMatch = "Device MAC: 00:1B:44:11:3A:B7";

    // Verify correctness
    assertFalse(matcher.find(testNoMatch));
    assertTrue(matcher.find(testWithMatch));
    assertEquals(jdkPattern.matcher(testWithMatch).find(), matcher.find(testWithMatch));

    // Warmup
    for (int i = 0; i < 10000; i++) {
      matcher.find(testNoMatch);
      jdkPattern.matcher(testNoMatch).find();
    }

    int iterations = 100000;

    // Test no-match case
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      matcher.find(testNoMatch);
    }
    long reggieNoMatch = System.nanoTime() - start;

    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(testNoMatch).find();
    }
    long jdkNoMatch = System.nanoTime() - start;

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

    System.out.println("\nMAC Address Pattern Performance (" + iterations + " iterations)");
    System.out.println("Pattern: " + pattern);
    System.out.println();
    System.out.println("No-match:");
    System.out.println("  JDK:    " + (jdkNoMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieNoMatch / 1_000_000) + " ms");
    System.out.println("  Speedup: " + String.format("%.2fx", (double) jdkNoMatch / reggieNoMatch));
    System.out.println();
    System.out.println("With match:");
    System.out.println("  JDK:    " + (jdkMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieMatch / 1_000_000) + " ms");
    System.out.println("  Speedup: " + String.format("%.2fx", (double) jdkMatch / reggieMatch));
  }

  @Test
  public void testUUIDPerformance() {
    String pattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    String testNoMatch = "This text has no UUIDs in it whatsoever";
    String testWithMatch = "Request ID: 550e8400-e29b-41d4-a716-446655440000";

    // Verify correctness
    assertFalse(matcher.find(testNoMatch));
    assertTrue(matcher.find(testWithMatch));
    assertEquals(jdkPattern.matcher(testWithMatch).find(), matcher.find(testWithMatch));

    // Warmup
    for (int i = 0; i < 10000; i++) {
      matcher.find(testNoMatch);
      jdkPattern.matcher(testNoMatch).find();
    }

    int iterations = 100000;

    // Test no-match case
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      matcher.find(testNoMatch);
    }
    long reggieNoMatch = System.nanoTime() - start;

    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(testNoMatch).find();
    }
    long jdkNoMatch = System.nanoTime() - start;

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

    System.out.println("\nUUID Pattern Performance (" + iterations + " iterations)");
    System.out.println("Pattern: " + pattern);
    System.out.println();
    System.out.println("No-match:");
    System.out.println("  JDK:    " + (jdkNoMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieNoMatch / 1_000_000) + " ms");
    System.out.println("  Speedup: " + String.format("%.2fx", (double) jdkNoMatch / reggieNoMatch));
    System.out.println();
    System.out.println("With match:");
    System.out.println("  JDK:    " + (jdkMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieMatch / 1_000_000) + " ms");
    System.out.println("  Speedup: " + String.format("%.2fx", (double) jdkMatch / reggieMatch));
  }

  @Test
  public void testCreditCardPerformance() {
    String pattern = "\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}";

    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile(pattern);

    String testNoMatch = "This text has no credit card numbers";
    String testWithMatch = "Card: 1234-5678-9012-3456";

    // Verify correctness
    assertFalse(matcher.find(testNoMatch));
    assertTrue(matcher.find(testWithMatch));
    assertEquals(jdkPattern.matcher(testWithMatch).find(), matcher.find(testWithMatch));

    // Warmup
    for (int i = 0; i < 10000; i++) {
      matcher.find(testNoMatch);
      jdkPattern.matcher(testNoMatch).find();
    }

    int iterations = 100000;

    // Test no-match case
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      matcher.find(testNoMatch);
    }
    long reggieNoMatch = System.nanoTime() - start;

    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(testNoMatch).find();
    }
    long jdkNoMatch = System.nanoTime() - start;

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

    System.out.println("\nCredit Card Pattern Performance (" + iterations + " iterations)");
    System.out.println("Pattern: " + pattern);
    System.out.println();
    System.out.println("No-match:");
    System.out.println("  JDK:    " + (jdkNoMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieNoMatch / 1_000_000) + " ms");
    System.out.println("  Speedup: " + String.format("%.2fx", (double) jdkNoMatch / reggieNoMatch));
    System.out.println();
    System.out.println("With match:");
    System.out.println("  JDK:    " + (jdkMatch / 1_000_000) + " ms");
    System.out.println("  Reggie: " + (reggieMatch / 1_000_000) + " ms");
    System.out.println("  Speedup: " + String.format("%.2fx", (double) jdkMatch / reggieMatch));
  }
}
