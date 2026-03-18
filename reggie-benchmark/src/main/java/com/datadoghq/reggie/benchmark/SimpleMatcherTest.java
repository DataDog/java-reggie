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

/** Simple tests for generated matchers. Run with: ./gradlew :reggie-benchmark:run */
public class SimpleMatcherTest {

  public static void main(String[] args) {
    System.out.println("Testing generated matchers...\n");

    testPhonePattern();
    testHelloMatcher();
    testPerformanceComparison();

    System.out.println("\nAll tests passed!");
  }

  private static void testPhonePattern() {
    System.out.println("=== Phone Matcher ===");
    var matcher = Reggie.patterns(ExamplePatterns.class).phone();

    // Test valid phone numbers
    assertTrue(matcher.matches("123-456-7890"), "Valid phone number");
    assertTrue(matcher.matches("000-000-0000"), "All zeros");

    // Test invalid phone numbers
    assertFalse(matcher.matches("123-456-789"), "Too short");
    assertFalse(matcher.matches("123-456-78901"), "Too long");
    assertFalse(matcher.matches("abc-def-ghij"), "Letters");
    assertFalse(matcher.matches("123-456"), "Missing parts");
    assertFalse(matcher.matches(""), "Empty string");
    assertFalse(matcher.matches(null), "Null string");

    // Test find
    assertTrue(matcher.find("Call 123-456-7890 now"), "Find in text");
    int pos = matcher.findFrom("Call 123-456-7890 or 999-888-7777", 0);
    assertTrue(pos >= 0, "Find first occurrence");

    System.out.println("Phone Matcher: PASSED\n");
  }

  private static void testHelloMatcher() {
    System.out.println("=== Hello Matcher ===");
    var matcher = Reggie.patterns(ExamplePatterns.class).hello();

    // Test matches
    assertTrue(matcher.matches("hello"), "Exact match");
    assertFalse(matcher.matches("Hello"), "Case sensitive");
    assertFalse(matcher.matches("hello world"), "Longer string");
    assertFalse(matcher.matches("say hello"), "Substring");

    // Test find
    assertTrue(matcher.find("hello world"), "Find at start");
    assertTrue(matcher.find("say hello"), "Find at end");
    assertFalse(matcher.find("HELLO"), "Find case sensitive");

    assertEquals(0, matcher.findFrom("hello world", 0), "Find position");
    assertEquals(4, matcher.findFrom("say hello", 0), "Find position 2");
    assertEquals(-1, matcher.findFrom("hi there", 0), "Not found");

    System.out.println("Hello Matcher: PASSED\n");
  }

  private static void testPerformanceComparison() {
    System.out.println("=== Performance Comparison ===");

    String validPhone = "123-456-7890";
    String invalidPhone = "not-a-phone";
    int iterations = 1_000_000;

    // Test with generated matcher
    var reggieMatch = Reggie.patterns(ExamplePatterns.class).phone();
    long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      reggieMatch.matches(validPhone);
      reggieMatch.matches(invalidPhone);
    }
    long reggieTime = System.nanoTime() - start;

    // Test with java.util.regex.Pattern
    java.util.regex.Pattern jdkPattern = java.util.regex.Pattern.compile("\\d{3}-\\d{3}-\\d{4}");
    start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      jdkPattern.matcher(validPhone).matches();
      jdkPattern.matcher(invalidPhone).matches();
    }
    long jdkTime = System.nanoTime() - start;

    System.out.printf("Reggie matcher: %.2f ms%n", reggieTime / 1_000_000.0);
    System.out.printf("JDK Pattern:    %.2f ms%n", jdkTime / 1_000_000.0);
    System.out.printf("Speedup:        %.2fx%n", (double) jdkTime / reggieTime);
    System.out.println();
  }

  private static void assertTrue(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError("Assertion failed: " + message);
    }
  }

  private static void assertFalse(boolean condition, String message) {
    if (condition) {
      throw new AssertionError("Assertion failed (expected false): " + message);
    }
  }

  private static void assertEquals(int expected, int actual, String message) {
    if (expected != actual) {
      throw new AssertionError(
          "Assertion failed: " + message + " (expected: " + expected + ", actual: " + actual + ")");
    }
  }
}
