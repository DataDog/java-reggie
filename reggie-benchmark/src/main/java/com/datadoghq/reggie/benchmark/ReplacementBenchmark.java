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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark replacement operations to measure Phase 2-3 optimizations: - Phase 2: Loop unrolling
 * threshold for bounded quantifiers - Phase 3: indexOf optimization for single-character patterns -
 * Phase 1: Zero-allocation no-match optimization
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class ReplacementBenchmark {

  // Test inputs
  private String phoneNumbersText;
  private String singleCharText;
  private String noMatchText;
  private String largeQuantifierText;

  // Reggie matchers
  private ReggieMatcher phoneReggie;
  private ReggieMatcher singleCharReggie;
  private ReggieMatcher digitReggie;
  private ReggieMatcher largeQuantifierReggie;

  // JDK patterns
  private Pattern phoneJdk;
  private Pattern singleCharJdk;
  private Pattern digitJdk;
  private Pattern largeQuantifierJdk;

  @Setup
  public void setup() {
    // Phone numbers: Tests Phase 2 loop unrolling for \d{3}
    phoneNumbersText = "Call me at 555-123-4567 or 555-987-6543 for more info about 555-555-5555.";
    phoneReggie = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
    phoneJdk = Pattern.compile("\\d{3}-\\d{3}-\\d{4}");

    // Single character: Tests Phase 3 indexOf optimization
    singleCharText =
        "The quick brown fox jumps over the lazy dog. "
            + "The quick brown fox jumps over the lazy dog. "
            + "The quick brown fox jumps over the lazy dog.";
    singleCharReggie = Reggie.compile("o");
    singleCharJdk = Pattern.compile("o");

    // Digit pattern for mixed content
    digitReggie = Reggie.compile("\\d+");
    digitJdk = Pattern.compile("\\d+");

    // No match text: Tests Phase 1 zero-allocation optimization
    noMatchText = "This text contains no phone numbers at all just words and spaces.";

    // Large quantifier: Tests Phase 2 runtime loop generation
    largeQuantifierText = "a".repeat(50) + " " + "a".repeat(75) + " " + "a".repeat(100);
    largeQuantifierReggie = Reggie.compile("a{30,}");
    largeQuantifierJdk = Pattern.compile("a{30,}");
  }

  // === Phase 2: Loop Unrolling Tests ===

  @Benchmark
  public void phoneReplaceAll_Reggie(Blackhole bh) {
    bh.consume(phoneReggie.replaceAll(phoneNumbersText, "XXX-XXX-XXXX"));
  }

  @Benchmark
  public void phoneReplaceAll_JDK(Blackhole bh) {
    bh.consume(phoneJdk.matcher(phoneNumbersText).replaceAll("XXX-XXX-XXXX"));
  }

  @Benchmark
  public void phoneReplaceFirst_Reggie(Blackhole bh) {
    bh.consume(phoneReggie.replaceFirst(phoneNumbersText, "XXX-XXX-XXXX"));
  }

  @Benchmark
  public void phoneReplaceFirst_JDK(Blackhole bh) {
    bh.consume(phoneJdk.matcher(phoneNumbersText).replaceFirst("XXX-XXX-XXXX"));
  }

  // === Phase 3: indexOf Optimization Tests ===

  @Benchmark
  public void singleCharReplaceAll_Reggie(Blackhole bh) {
    bh.consume(singleCharReggie.replaceAll(singleCharText, "0"));
  }

  @Benchmark
  public void singleCharReplaceAll_JDK(Blackhole bh) {
    bh.consume(singleCharJdk.matcher(singleCharText).replaceAll("0"));
  }

  @Benchmark
  public void singleCharReplaceFirst_Reggie(Blackhole bh) {
    bh.consume(singleCharReggie.replaceFirst(singleCharText, "0"));
  }

  @Benchmark
  public void singleCharReplaceFirst_JDK(Blackhole bh) {
    bh.consume(singleCharJdk.matcher(singleCharText).replaceFirst("0"));
  }

  // === Phase 1: Zero-Allocation No-Match Tests ===

  @Benchmark
  public void noMatchReplaceAll_Reggie(Blackhole bh) {
    bh.consume(phoneReggie.replaceAll(noMatchText, "XXX-XXX-XXXX"));
  }

  @Benchmark
  public void noMatchReplaceAll_JDK(Blackhole bh) {
    bh.consume(phoneJdk.matcher(noMatchText).replaceAll("XXX-XXX-XXXX"));
  }

  @Benchmark
  public void noMatchReplaceFirst_Reggie(Blackhole bh) {
    bh.consume(phoneReggie.replaceFirst(noMatchText, "XXX-XXX-XXXX"));
  }

  @Benchmark
  public void noMatchReplaceFirst_JDK(Blackhole bh) {
    bh.consume(phoneJdk.matcher(noMatchText).replaceFirst("XXX-XXX-XXXX"));
  }

  // === Phase 2: Large Quantifier Tests ===

  @Benchmark
  public void largeQuantifierReplaceAll_Reggie(Blackhole bh) {
    bh.consume(largeQuantifierReggie.replaceAll(largeQuantifierText, "X"));
  }

  @Benchmark
  public void largeQuantifierReplaceAll_JDK(Blackhole bh) {
    bh.consume(largeQuantifierJdk.matcher(largeQuantifierText).replaceAll("X"));
  }

  // === Mixed Pattern Tests ===

  @Benchmark
  public void digitReplaceAll_Reggie(Blackhole bh) {
    bh.consume(digitReggie.replaceAll(phoneNumbersText, "N"));
  }

  @Benchmark
  public void digitReplaceAll_JDK(Blackhole bh) {
    bh.consume(digitJdk.matcher(phoneNumbersText).replaceAll("N"));
  }
}
