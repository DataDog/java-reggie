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

import static com.datadoghq.reggie.benchmark.BenchmarkPatterns.*;

import com.datadoghq.reggie.Reggie;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Consolidated JMH benchmark for matches() operations across all pattern types. Compares Reggie's
 * generated matcher with JDK's Pattern for exact string matching.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MatchOperationBenchmark {

  private ExamplePatterns patterns;
  private Pattern jdkPhone;
  private Pattern jdkEmail;
  private Pattern jdkDigits;
  private Pattern jdkLiteral;

  // RE2J patterns (linear-time, fewer features than JDK)
  private com.google.re2j.Pattern re2jPhone;
  private com.google.re2j.Pattern re2jEmail;
  private com.google.re2j.Pattern re2jDigits;
  private com.google.re2j.Pattern re2jLiteral;

  // Phone test strings
  private static final String PHONE_VALID = "555-123-4567";
  private static final String PHONE_INVALID = "not-a-phone";

  // Email test strings
  private static final String EMAIL_VALID = "user@example.com";
  private static final String EMAIL_INVALID = "not-an-email";

  // Digits test strings
  private static final String DIGITS_VALID = "123456";
  private static final String DIGITS_INVALID = "no-digits-here";

  // Literal test strings
  private static final String LITERAL_MATCH = "hello";
  private static final String LITERAL_NO_MATCH = "goodbye";

  @Setup
  public void setup() {
    patterns = Reggie.patterns(ExamplePatterns.class);
    jdkPhone = Pattern.compile(PHONE);
    jdkEmail = Pattern.compile(EMAIL);
    jdkDigits = Pattern.compile(DIGITS);
    jdkLiteral = Pattern.compile(LITERAL_HELLO);

    // RE2J patterns (same regex syntax, different engine)
    re2jPhone = com.google.re2j.Pattern.compile(PHONE);
    re2jEmail = com.google.re2j.Pattern.compile(EMAIL);
    re2jDigits = com.google.re2j.Pattern.compile(DIGITS);
    re2jLiteral = com.google.re2j.Pattern.compile(LITERAL_HELLO);
  }

  // ===== Phone Pattern Benchmarks =====

  @Benchmark
  public boolean reggiePhoneMatch() {
    return patterns.phone().matches(PHONE_VALID);
  }

  @Benchmark
  public boolean jdkPhoneMatch() {
    return jdkPhone.matcher(PHONE_VALID).matches();
  }

  @Benchmark
  public boolean reggiePhoneNoMatch() {
    return patterns.phone().matches(PHONE_INVALID);
  }

  @Benchmark
  public boolean jdkPhoneNoMatch() {
    return jdkPhone.matcher(PHONE_INVALID).matches();
  }

  @Benchmark
  public boolean re2jPhoneMatch() {
    return re2jPhone.matcher(PHONE_VALID).matches();
  }

  @Benchmark
  public boolean re2jPhoneNoMatch() {
    return re2jPhone.matcher(PHONE_INVALID).matches();
  }

  // ===== Email Pattern Benchmarks =====

  @Benchmark
  public boolean reggieEmailMatch() {
    return patterns.email().matches(EMAIL_VALID);
  }

  @Benchmark
  public boolean jdkEmailMatch() {
    return jdkEmail.matcher(EMAIL_VALID).matches();
  }

  @Benchmark
  public boolean reggieEmailNoMatch() {
    return patterns.email().matches(EMAIL_INVALID);
  }

  @Benchmark
  public boolean jdkEmailNoMatch() {
    return jdkEmail.matcher(EMAIL_INVALID).matches();
  }

  @Benchmark
  public boolean re2jEmailMatch() {
    return re2jEmail.matcher(EMAIL_VALID).matches();
  }

  @Benchmark
  public boolean re2jEmailNoMatch() {
    return re2jEmail.matcher(EMAIL_INVALID).matches();
  }

  // ===== Digits Pattern Benchmarks =====

  @Benchmark
  public boolean reggieDigitsMatch() {
    return patterns.digits().matches(DIGITS_VALID);
  }

  @Benchmark
  public boolean jdkDigitsMatch() {
    return jdkDigits.matcher(DIGITS_VALID).matches();
  }

  @Benchmark
  public boolean reggieDigitsNoMatch() {
    return patterns.digits().matches(DIGITS_INVALID);
  }

  @Benchmark
  public boolean jdkDigitsNoMatch() {
    return jdkDigits.matcher(DIGITS_INVALID).matches();
  }

  @Benchmark
  public boolean re2jDigitsMatch() {
    return re2jDigits.matcher(DIGITS_VALID).matches();
  }

  @Benchmark
  public boolean re2jDigitsNoMatch() {
    return re2jDigits.matcher(DIGITS_INVALID).matches();
  }

  // ===== Literal Pattern Benchmarks =====

  @Benchmark
  public boolean reggieLiteralMatch() {
    return patterns.hello().matches(LITERAL_MATCH);
  }

  @Benchmark
  public boolean jdkLiteralMatch() {
    return jdkLiteral.matcher(LITERAL_MATCH).matches();
  }

  @Benchmark
  public boolean reggieLiteralNoMatch() {
    return patterns.hello().matches(LITERAL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkLiteralNoMatch() {
    return jdkLiteral.matcher(LITERAL_NO_MATCH).matches();
  }

  @Benchmark
  public boolean re2jLiteralMatch() {
    return re2jLiteral.matcher(LITERAL_MATCH).matches();
  }

  @Benchmark
  public boolean re2jLiteralNoMatch() {
    return re2jLiteral.matcher(LITERAL_NO_MATCH).matches();
  }
}
