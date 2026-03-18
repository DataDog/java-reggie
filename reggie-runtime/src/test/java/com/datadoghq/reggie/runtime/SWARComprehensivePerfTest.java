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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive performance tests for SWAR optimizations across various pattern types. Tests
 * single-range, multi-range, negated patterns with and without group capture.
 */
public class SWARComprehensivePerfTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  private static final int ITERATIONS = 100_000;

  // Test texts
  private static final String SPARSE_DIGITS =
      "This text has no numbers in it whatsoever. Only words and spaces.";
  private static final String DENSE_DIGITS =
      "12345 67890 24680 13579 98765 43210 11111 22222 33333 44444";
  private static final String SPARSE_ALPHA =
      "123456789 0987654321 246810 135790 999888777 666555444";
  private static final String DENSE_ALPHA =
      "Hello World The Quick Brown Fox Jumps Over The Lazy Dog Again";
  private static final String MIXED_TEXT =
      "User123 logged in at 14:30:45 from IP 192.168.1.100 with session abc-def-ghi";

  // ============================================================
  // Test 1: Single-range digit patterns \d+
  // ============================================================

  @Test
  public void testDigitPattern() {
    System.out.println("\n=== Digit Pattern \\d+ (Single Range [0-9]) ===");
    runBenchmark("\\d+", SPARSE_DIGITS, "sparse (no match)");
    runBenchmark("\\d+", DENSE_DIGITS, "dense (many matches)");
    runBenchmark("\\d+", MIXED_TEXT, "mixed text");
  }

  @Test
  public void testDigitPatternWithGroup() {
    System.out.println("\n=== Digit Pattern (\\d+) With Group Capture ===");
    runBenchmarkWithGroup("(\\d+)", SPARSE_DIGITS, "sparse (no match)");
    runBenchmarkWithGroup("(\\d+)", DENSE_DIGITS, "dense (many matches)");
    runBenchmarkWithGroup("(\\d+)", MIXED_TEXT, "mixed text");
  }

  // ============================================================
  // Test 2: Lowercase alpha patterns [a-z]+
  // ============================================================

  @Test
  public void testLowerAlphaPattern() {
    System.out.println("\n=== Lowercase Alpha [a-z]+ (Single Range) ===");
    runBenchmark("[a-z]+", SPARSE_ALPHA, "sparse (no letters)");
    runBenchmark("[a-z]+", "hello world from the system", "dense (all lowercase)");
    runBenchmark("[a-z]+", DENSE_ALPHA, "mixed case");
  }

  @Test
  public void testLowerAlphaPatternWithGroup() {
    System.out.println("\n=== Lowercase Alpha ([a-z]+) With Group Capture ===");
    runBenchmarkWithGroup("([a-z]+)", SPARSE_ALPHA, "sparse (no letters)");
    runBenchmarkWithGroup("([a-z]+)", "hello world from the system", "dense (all lowercase)");
  }

  // ============================================================
  // Test 3: Mixed alpha patterns [a-zA-Z]+
  // ============================================================

  @Test
  public void testMixedAlphaPattern() {
    System.out.println("\n=== Mixed Alpha [a-zA-Z]+ (Multi-Range) ===");
    runBenchmark("[a-zA-Z]+", SPARSE_ALPHA, "sparse (no letters)");
    runBenchmark("[a-zA-Z]+", DENSE_ALPHA, "dense (many letters)");
    runBenchmark("[a-zA-Z]+", MIXED_TEXT, "mixed text");
  }

  @Test
  public void testMixedAlphaPatternWithGroup() {
    System.out.println("\n=== Mixed Alpha ([a-zA-Z]+) With Group Capture ===");
    runBenchmarkWithGroup("([a-zA-Z]+)", SPARSE_ALPHA, "sparse (no letters)");
    runBenchmarkWithGroup("([a-zA-Z]+)", DENSE_ALPHA, "dense (many letters)");
    runBenchmarkWithGroup("([a-zA-Z]+)", MIXED_TEXT, "mixed text");
  }

  // ============================================================
  // Test 4: Alphanumeric patterns [a-zA-Z0-9]+
  // ============================================================

  @Test
  public void testAlphanumPattern() {
    System.out.println("\n=== Alphanumeric [a-zA-Z0-9]+ (Multi-Range) ===");
    runBenchmark("[a-zA-Z0-9]+", "... --- ... !!! @@@ ###", "sparse (no alphanum)");
    runBenchmark("[a-zA-Z0-9]+", "User123Admin456Test789", "dense (all alphanum)");
    runBenchmark("[a-zA-Z0-9]+", MIXED_TEXT, "mixed text");
  }

  @Test
  public void testAlphanumPatternWithGroup() {
    System.out.println("\n=== Alphanumeric ([a-zA-Z0-9]+) With Group Capture ===");
    runBenchmarkWithGroup("([a-zA-Z0-9]+)", "... --- ... !!! @@@ ###", "sparse (no alphanum)");
    runBenchmarkWithGroup("([a-zA-Z0-9]+)", "User123Admin456Test789", "dense (all alphanum)");
  }

  // ============================================================
  // Test 5: Negated digit patterns [^0-9]+
  // ============================================================

  @Test
  public void testNonDigitPattern() {
    System.out.println("\n=== Non-Digit [^0-9]+ (Negated Range) ===");
    runBenchmark("[^0-9]+", DENSE_DIGITS, "sparse (mostly digits)");
    runBenchmark("[^0-9]+", SPARSE_DIGITS, "dense (no digits)");
    runBenchmark("[^0-9]+", MIXED_TEXT, "mixed text");
  }

  @Test
  public void testNonDigitPatternWithGroup() {
    System.out.println("\n=== Non-Digit ([^0-9]+) With Group Capture ===");
    runBenchmarkWithGroup("([^0-9]+)", DENSE_DIGITS, "sparse (mostly digits)");
    runBenchmarkWithGroup("([^0-9]+)", SPARSE_DIGITS, "dense (no digits)");
  }

  // ============================================================
  // Test 6: Bounded quantifier patterns
  // ============================================================

  @Test
  public void testBoundedDigitPattern() {
    System.out.println("\n=== Bounded Digit \\d{2,4} ===");
    runBenchmark("\\d{2,4}", SPARSE_DIGITS, "sparse (no match)");
    runBenchmark("\\d{2,4}", "12 345 6789 01 2345", "with matches");
    runBenchmark("\\d{2,4}", MIXED_TEXT, "mixed text");
  }

  @Test
  public void testBoundedDigitPatternWithGroup() {
    System.out.println("\n=== Bounded Digit (\\d{2,4}) With Group Capture ===");
    runBenchmarkWithGroup("(\\d{2,4})", SPARSE_DIGITS, "sparse (no match)");
    runBenchmarkWithGroup("(\\d{2,4})", "12 345 6789 01 2345", "with matches");
  }

  @Test
  public void testBoundedAlphaPattern() {
    System.out.println("\n=== Bounded Alpha [a-z]{3,8} ===");
    runBenchmark("[a-z]{3,8}", SPARSE_ALPHA, "sparse (no letters)");
    runBenchmark("[a-z]{3,8}", "the quick brown fox jumps", "dense (many words)");
  }

  // ============================================================
  // Test 7: Real-world patterns
  // ============================================================

  @Test
  public void testIPv4Pattern() {
    System.out.println("\n=== IPv4 Pattern \\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3} ===");
    String noIP = "This text has no IP addresses at all just words";
    String withIP = "Server at 192.168.1.1 connected to 10.0.0.254 successfully";

    runBenchmark("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", noIP, "no match");
    runBenchmark("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}", withIP, "with matches");
  }

  @Test
  public void testUsernamePattern() {
    System.out.println("\n=== Username Pattern [a-zA-Z][a-zA-Z0-9_]{2,15} ===");
    String noUser = "123 456 789 !!! @@@ ###";
    String withUser = "Hello user123 and admin_test and SuperUser99";

    runBenchmark("[a-zA-Z][a-zA-Z0-9_]{2,15}", noUser, "no match");
    runBenchmark("[a-zA-Z][a-zA-Z0-9_]{2,15}", withUser, "with matches");
  }

  // ============================================================
  // Benchmark helpers
  // ============================================================

  private void runBenchmark(String pattern, String text, String scenario) {
    Pattern jdkPattern = Pattern.compile(pattern);
    ReggieMatcher reggiePattern;

    try {
      reggiePattern = RuntimeCompiler.compile(pattern);
    } catch (Exception e) {
      System.out.println("  " + scenario + ": SKIP (pattern not supported)");
      return;
    }

    // Warmup
    for (int i = 0; i < 1000; i++) {
      jdkPattern.matcher(text).find();
      reggiePattern.find(text);
    }

    // Benchmark JDK
    long jdkStart = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      jdkPattern.matcher(text).find();
    }
    long jdkTime = (System.nanoTime() - jdkStart) / 1_000_000;

    // Benchmark Reggie
    long reggieStart = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      reggiePattern.find(text);
    }
    long reggieTime = (System.nanoTime() - reggieStart) / 1_000_000;

    double speedup = (double) jdkTime / reggieTime;
    System.out.printf(
        "  %-25s JDK: %4dms  Reggie: %4dms  Speedup: %.2fx%n",
        scenario + ":", jdkTime, reggieTime, speedup);
  }

  private void runBenchmarkWithGroup(String pattern, String text, String scenario) {
    Pattern jdkPattern = Pattern.compile(pattern);
    ReggieMatcher reggiePattern;

    try {
      reggiePattern = RuntimeCompiler.compile(pattern);
    } catch (Exception e) {
      System.out.println("  " + scenario + ": SKIP (pattern not supported)");
      return;
    }

    // Warmup
    for (int i = 0; i < 1000; i++) {
      Matcher m = jdkPattern.matcher(text);
      while (m.find()) {
        m.group(1);
      }
      MatchResult r = reggiePattern.match(text);
      if (r != null && r.groupCount() > 0) {
        r.group(1);
      }
    }

    // Benchmark JDK
    long jdkStart = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      Matcher m = jdkPattern.matcher(text);
      while (m.find()) {
        m.group(1);
      }
    }
    long jdkTime = (System.nanoTime() - jdkStart) / 1_000_000;

    // Benchmark Reggie
    long reggieStart = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      MatchResult r = reggiePattern.match(text);
      if (r != null && r.groupCount() > 0) {
        r.group(1);
      }
    }
    long reggieTime = (System.nanoTime() - reggieStart) / 1_000_000;

    double speedup = (double) jdkTime / reggieTime;
    System.out.printf(
        "  %-25s JDK: %4dms  Reggie: %4dms  Speedup: %.2fx%n",
        scenario + ":", jdkTime, reggieTime, speedup);
  }
}
