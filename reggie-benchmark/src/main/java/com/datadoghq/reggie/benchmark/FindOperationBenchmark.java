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
 * Consolidated JMH benchmark for find() operations across all pattern types. Compares Reggie's
 * generated matcher with JDK's Pattern and RE2J for substring searching.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class FindOperationBenchmark {

  private ExamplePatterns patterns;
  private Pattern jdkPhone;
  private Pattern jdkEmail;
  private Pattern jdkDigits;
  private Pattern jdkLiteral;

  // RE2J patterns
  private com.google.re2j.Pattern re2jPhone;
  private com.google.re2j.Pattern re2jEmail;
  private com.google.re2j.Pattern re2jDigits;
  private com.google.re2j.Pattern re2jLiteral;

  // Phone test strings
  private static final String PHONE_TEXT = "Please call me at 555-123-4567 for more info";
  private static final String PHONE_NO_MATCH = "No phone number here at all";

  // Email test strings
  private static final String EMAIL_TEXT = "Contact us at support@example.com for help";
  private static final String EMAIL_NO_MATCH = "No email address in this text";

  // Digits test strings
  private static final String DIGITS_TEXT = "The year 2024 has 365 days";
  private static final String DIGITS_NO_MATCH = "Hello World with no numbers";

  // Literal test strings
  private static final String LITERAL_TEXT = "Say hello to the world";
  private static final String LITERAL_NO_MATCH = "Goodbye to everyone";

  @Setup
  public void setup() {
    patterns = Reggie.patterns(ExamplePatterns.class);
    jdkPhone = Pattern.compile(PHONE);
    jdkEmail = Pattern.compile(EMAIL);
    jdkDigits = Pattern.compile(DIGITS);
    jdkLiteral = Pattern.compile(LITERAL_HELLO);

    // RE2J patterns
    re2jPhone = com.google.re2j.Pattern.compile(PHONE);
    re2jEmail = com.google.re2j.Pattern.compile(EMAIL);
    re2jDigits = com.google.re2j.Pattern.compile(DIGITS);
    re2jLiteral = com.google.re2j.Pattern.compile(LITERAL_HELLO);
  }

  // ===== Phone Pattern Benchmarks =====

  @Benchmark
  public boolean reggiePhoneFind() {
    return patterns.phone().find(PHONE_TEXT);
  }

  @Benchmark
  public boolean jdkPhoneFind() {
    return jdkPhone.matcher(PHONE_TEXT).find();
  }

  @Benchmark
  public boolean reggiePhoneFindNone() {
    return patterns.phone().find(PHONE_NO_MATCH);
  }

  @Benchmark
  public boolean jdkPhoneFindNone() {
    return jdkPhone.matcher(PHONE_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jPhoneFind() {
    return re2jPhone.matcher(PHONE_TEXT).find();
  }

  @Benchmark
  public boolean re2jPhoneFindNone() {
    return re2jPhone.matcher(PHONE_NO_MATCH).find();
  }

  // ===== Email Pattern Benchmarks =====

  @Benchmark
  public boolean reggieEmailFind() {
    return patterns.email().find(EMAIL_TEXT);
  }

  @Benchmark
  public boolean jdkEmailFind() {
    return jdkEmail.matcher(EMAIL_TEXT).find();
  }

  @Benchmark
  public boolean reggieEmailFindNone() {
    return patterns.email().find(EMAIL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkEmailFindNone() {
    return jdkEmail.matcher(EMAIL_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jEmailFind() {
    return re2jEmail.matcher(EMAIL_TEXT).find();
  }

  @Benchmark
  public boolean re2jEmailFindNone() {
    return re2jEmail.matcher(EMAIL_NO_MATCH).find();
  }

  // ===== Digits Pattern Benchmarks =====

  @Benchmark
  public boolean reggieDigitsFind() {
    return patterns.digits().find(DIGITS_TEXT);
  }

  @Benchmark
  public boolean jdkDigitsFind() {
    return jdkDigits.matcher(DIGITS_TEXT).find();
  }

  @Benchmark
  public boolean reggieDigitsFindNone() {
    return patterns.digits().find(DIGITS_NO_MATCH);
  }

  @Benchmark
  public boolean jdkDigitsFindNone() {
    return jdkDigits.matcher(DIGITS_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jDigitsFind() {
    return re2jDigits.matcher(DIGITS_TEXT).find();
  }

  @Benchmark
  public boolean re2jDigitsFindNone() {
    return re2jDigits.matcher(DIGITS_NO_MATCH).find();
  }

  // ===== Literal Pattern Benchmarks =====

  @Benchmark
  public boolean reggieLiteralFind() {
    return patterns.hello().find(LITERAL_TEXT);
  }

  @Benchmark
  public boolean jdkLiteralFind() {
    return jdkLiteral.matcher(LITERAL_TEXT).find();
  }

  @Benchmark
  public boolean reggieLiteralFindNone() {
    return patterns.hello().find(LITERAL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkLiteralFindNone() {
    return jdkLiteral.matcher(LITERAL_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jLiteralFind() {
    return re2jLiteral.matcher(LITERAL_TEXT).find();
  }

  @Benchmark
  public boolean re2jLiteralFindNone() {
    return re2jLiteral.matcher(LITERAL_NO_MATCH).find();
  }
}
