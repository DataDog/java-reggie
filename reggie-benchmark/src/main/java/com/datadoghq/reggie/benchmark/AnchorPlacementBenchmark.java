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

/**
 * Benchmarks for anchor-placement patterns whose semantics were corrected by the anchor-aware DFA
 * construction change. Each pattern is exercised through both {@link java.util.regex.Pattern}'s
 * {@code matcher().find()} and Reggie's {@link ReggieMatcher#findMatch} on a non-trivial input so
 * the cost of the per-state anchor checks is visible.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class AnchorPlacementBenchmark {

  /** A 128-char body with trailing zero run — exercises {@code \\.?0+$} matching at end. */
  private static final String TRAILING_ZEROS = "12345.6789012345678901234567890" + "0".repeat(97);

  /** A 128-char body whose first char is a digit — exercises {@code ^[0-9]} hot path. */
  private static final String LEADS_WITH_DIGIT = "1" + "abcdefghij".repeat(12) + "xyz";

  /** A 128-char body that ends with a non-alphanumeric — the user's original pattern. */
  private static final String ENDS_WITH_SYMBOL = "abcdefghij".repeat(12) + "xyz#";

  /** Plain identifier — exercises the "no match anywhere" path for the original user pattern. */
  private static final String PLAIN_IDENT = "abcdefghij".repeat(12) + "xyz0";

  /** Long input used by the bare-{@code $} benchmark — find must scan to the end. */
  private static final String LONG_INPUT = "abcdefghij".repeat(50);

  // Patterns — kept exactly as the bug report and the consuming codebase use them.
  private ReggieMatcher reggieTrailingZeros;
  private Pattern jdkTrailingZeros;
  private ReggieMatcher reggieUserPattern;
  private Pattern jdkUserPattern;
  private ReggieMatcher reggieStartDigit;
  private Pattern jdkStartDigit;
  private ReggieMatcher reggieBareDollar;
  private Pattern jdkBareDollar;
  private ReggieMatcher reggieAtEndConcat;
  private Pattern jdkAtEndConcat;
  private ReggieMatcher reggieAlternationMixed;
  private Pattern jdkAlternationMixed;

  @Setup
  public void setup() {
    reggieTrailingZeros = Reggie.compile("\\.?0+$");
    jdkTrailingZeros = Pattern.compile("\\.?0+$");

    reggieUserPattern = Reggie.compile("$[^a-zA-Z0-9]|^[0-9]");
    jdkUserPattern = Pattern.compile("$[^a-zA-Z0-9]|^[0-9]");

    reggieStartDigit = Reggie.compile("^[0-9]");
    jdkStartDigit = Pattern.compile("^[0-9]");

    reggieBareDollar = Reggie.compile("$");
    jdkBareDollar = Pattern.compile("$");

    // End-anchor at end of concat: already worked pre-fix, ensure we did not regress.
    reggieAtEndConcat = Reggie.compile("xyz#$");
    jdkAtEndConcat = Pattern.compile("xyz#$");

    // Mixed alternation with start and end anchors in different branches.
    reggieAlternationMixed = Reggie.compile("^a|z$");
    jdkAlternationMixed = Pattern.compile("^a|z$");
  }

  // ---- \.?0+$  --------------------------------------------------------------------------

  @Benchmark
  public boolean reggieTrailingZeros_match() {
    return reggieTrailingZeros.findMatch(TRAILING_ZEROS) != null;
  }

  @Benchmark
  public boolean jdkTrailingZeros_match() {
    return jdkTrailingZeros.matcher(TRAILING_ZEROS).find();
  }

  @Benchmark
  public boolean reggieTrailingZeros_noMatch() {
    return reggieTrailingZeros.findMatch(PLAIN_IDENT) != null;
  }

  @Benchmark
  public boolean jdkTrailingZeros_noMatch() {
    return jdkTrailingZeros.matcher(PLAIN_IDENT).find();
  }

  // ---- $[^a-zA-Z0-9]|^[0-9] — the original bug report ----------------------------------

  @Benchmark
  public boolean reggieUserPattern_leadingDigit() {
    return reggieUserPattern.findMatch(LEADS_WITH_DIGIT) != null;
  }

  @Benchmark
  public boolean jdkUserPattern_leadingDigit() {
    return jdkUserPattern.matcher(LEADS_WITH_DIGIT).find();
  }

  @Benchmark
  public boolean reggieUserPattern_noMatch() {
    return reggieUserPattern.findMatch(ENDS_WITH_SYMBOL) != null;
  }

  @Benchmark
  public boolean jdkUserPattern_noMatch() {
    return jdkUserPattern.matcher(ENDS_WITH_SYMBOL).find();
  }

  // ---- ^[0-9] alone (find on a long string) ----------------------------------------------

  @Benchmark
  public boolean reggieStartDigit_match() {
    return reggieStartDigit.findMatch(LEADS_WITH_DIGIT) != null;
  }

  @Benchmark
  public boolean jdkStartDigit_match() {
    return jdkStartDigit.matcher(LEADS_WITH_DIGIT).find();
  }

  @Benchmark
  public boolean reggieStartDigit_noMatch() {
    return reggieStartDigit.findMatch(LONG_INPUT) != null;
  }

  @Benchmark
  public boolean jdkStartDigit_noMatch() {
    return jdkStartDigit.matcher(LONG_INPUT).find();
  }

  // ---- Bare $ — must scan to end-of-input ------------------------------------------------

  @Benchmark
  public boolean reggieBareDollar_match() {
    return reggieBareDollar.findMatch(LONG_INPUT) != null;
  }

  @Benchmark
  public boolean jdkBareDollar_match() {
    return jdkBareDollar.matcher(LONG_INPUT).find();
  }

  // ---- x$ — end-anchor at end of concat (pre-fix worked, regression check) --------------

  @Benchmark
  public boolean reggieAtEndConcat_match() {
    return reggieAtEndConcat.findMatch(ENDS_WITH_SYMBOL) != null;
  }

  @Benchmark
  public boolean jdkAtEndConcat_match() {
    return jdkAtEndConcat.matcher(ENDS_WITH_SYMBOL).find();
  }

  // ---- ^a|z$ — mixed alternation, both branches viable -----------------------------------

  @Benchmark
  public boolean reggieAlternationMixed_startBranch() {
    return reggieAlternationMixed.findMatch("a" + LONG_INPUT) != null;
  }

  @Benchmark
  public boolean jdkAlternationMixed_startBranch() {
    return jdkAlternationMixed.matcher("a" + LONG_INPUT).find();
  }

  @Benchmark
  public boolean reggieAlternationMixed_endBranch() {
    return reggieAlternationMixed.findMatch(LONG_INPUT + "z") != null;
  }

  @Benchmark
  public boolean jdkAlternationMixed_endBranch() {
    return jdkAlternationMixed.matcher(LONG_INPUT + "z").find();
  }
}
