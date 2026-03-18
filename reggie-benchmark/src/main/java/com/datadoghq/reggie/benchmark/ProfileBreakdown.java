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
import java.util.BitSet;
import java.util.regex.Pattern;

/** Break down password validation into components to identify bottlenecks. */
public class ProfileBreakdown {

  private static final String VALID = "Password123!";
  private static final String INVALID = "password";
  private static final int WARMUP = 100_000;
  private static final int ITERATIONS = 1_000_000;

  public static void main(String[] args) {
    AssertionPatterns patterns = Reggie.patterns(AssertionPatterns.class);
    Pattern jdkPassword = Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}");

    System.out.println("=== Profiling Password Validation ===\n");

    // Warmup
    for (int i = 0; i < WARMUP; i++) {
      patterns.passwordValidation().matches(VALID);
      jdkPassword.matcher(VALID).matches();
    }

    // 1. JDK baseline
    long start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      jdkPassword.matcher(VALID).matches();
    }
    long jdkTime = System.nanoTime() - start;
    double jdkOpsPerMs = ITERATIONS / (jdkTime / 1_000_000.0);
    System.out.printf("JDK Password Validation:    %.0f ops/ms (baseline)%n", jdkOpsPerMs);

    // 2. Reggie full password validation
    start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      patterns.passwordValidation().matches(VALID);
    }
    long reggieTime = System.nanoTime() - start;
    double reggieOpsPerMs = ITERATIONS / (reggieTime / 1_000_000.0);
    System.out.printf(
        "Reggie Password Validation: %.0f ops/ms (%.1fx slower)%n%n",
        reggieOpsPerMs, jdkOpsPerMs / reggieOpsPerMs);

    // 3. Simple lookahead for comparison
    start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      patterns.positiveLookahead().find("abc123");
    }
    long simpleLookaheadTime = System.nanoTime() - start;
    double simpleOpsPerMs = ITERATIONS / (simpleLookaheadTime / 1_000_000.0);
    System.out.printf(
        "Simple Lookahead:           %.0f ops/ms (%.1fx of JDK password)%n",
        simpleOpsPerMs, simpleOpsPerMs / jdkOpsPerMs);

    // 4. BitSet allocation overhead test
    start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      BitSet bs1 = new BitSet();
      BitSet bs2 = new BitSet();
      bs1.set(5);
      bs2.set(10);
      boolean result = bs1.get(5);
    }
    long bitsetTime = System.nanoTime() - start;
    double bitsetOpsPerMs = ITERATIONS / (bitsetTime / 1_000_000.0);
    System.out.printf("BitSet Allocation Test:     %.0f ops/ms%n", bitsetOpsPerMs);

    // 5. String scanning overhead test
    start = System.nanoTime();
    for (int i = 0; i < ITERATIONS; i++) {
      String s = VALID;
      boolean found = false;
      for (int j = 0; j < s.length(); j++) {
        char c = s.charAt(j);
        if (c >= 'A' && c <= 'Z') {
          found = true;
          break;
        }
      }
    }
    long scanTime = System.nanoTime() - start;
    double scanOpsPerMs = ITERATIONS / (scanTime / 1_000_000.0);
    System.out.printf("Simple String Scan:         %.0f ops/ms%n%n", scanOpsPerMs);

    // Analysis
    System.out.println("=== Analysis ===");
    System.out.printf("Reggie overhead vs JDK:     %.1fx%n", jdkOpsPerMs / reggieOpsPerMs);
    System.out.printf("Time breakdown (estimated):%n");

    // Estimate: 3 scans * scanTime
    double estimatedScanTime = (scanTime / ITERATIONS) * 3;
    double actualTime = reggieTime / ITERATIONS;
    double scanPercent = (estimatedScanTime / actualTime) * 100;
    double overheadTime = actualTime - estimatedScanTime;
    double overheadPercent = (overheadTime / actualTime) * 100;

    System.out.printf(
        "  String scanning (3x):     %.0f%% (%.1f ns)%n", scanPercent, estimatedScanTime);
    System.out.printf(
        "  Other overhead:           %.0f%% (%.1f ns)%n", overheadPercent, overheadTime);

    if (bitsetOpsPerMs < reggieOpsPerMs) {
      System.out.println("\n⚠️  WARNING: BitSet allocation is slower than password validation!");
      System.out.println("   This suggests BitSets are a major bottleneck.");
    }
  }
}
