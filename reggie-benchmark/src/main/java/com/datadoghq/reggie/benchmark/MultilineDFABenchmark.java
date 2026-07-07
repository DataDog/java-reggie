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

import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Benchmarks {@code find()} for the multiline-DFA path introduced by the START_MULTILINE fix in
 * {@code PikeVMMatcher.findDfa}. The pattern {@code (?s)(?m)^[a-z]+(.*)} routes to {@code
 * PIKEVM_CAPTURE} and is eligible for the lazy DFA {@code find()} path (no \b, no assertions, no
 * backrefs). Three representative inputs exercise:
 *
 * <ul>
 *   <li>Early match (first line) — {@code SHORT_LINES}
 *   <li>Skip-then-match (3rd line) — {@code MID_LINES}
 *   <li>Full-scan no-match (digits-only lines) — {@code NO_MATCH}
 * </ul>
 *
 * Each input is benchmarked against Reggie, JDK {@code Pattern}, and RE2J.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MultilineDFABenchmark {

  /** Pattern under test: DOTALL + MULTILINE anchor, with one capturing group. */
  private static final String PATTERN = "(?s)(?m)^[a-z]+(.*)";

  /** 3 short lines; the very first line matches. */
  static final String SHORT_LINES = "abc\ndef\nghi";

  /** 4 lines; the 3rd line ({@code abc}) is the first match after two non-matching digit lines. */
  static final String MID_LINES = "123\n456\nabc\ndef";

  /**
   * ~200-char no-match input: repeated {@code "12345\n"} blocks — every line starts with a digit,
   * so the pattern never matches.
   */
  static final String NO_MATCH = "12345\n".repeat(33); // 198 chars

  // --- compiled patterns ---

  private ReggieMatcher reggiePattern;
  private Pattern jdkPattern;
  private com.google.re2j.Pattern re2jPattern;

  @Setup
  public void setup() {
    reggiePattern = RuntimeCompiler.compile(PATTERN);
    jdkPattern = Pattern.compile(PATTERN);
    re2jPattern = com.google.re2j.Pattern.compile(PATTERN);
  }

  // ===== SHORT_LINES: match on first line =====

  @Benchmark
  public boolean reggie_shortLines() {
    return reggiePattern.find(SHORT_LINES);
  }

  @Benchmark
  public boolean jdk_shortLines() {
    return jdkPattern.matcher(SHORT_LINES).find();
  }

  @Benchmark
  public boolean re2j_shortLines() {
    return re2jPattern.matcher(SHORT_LINES).find();
  }

  // ===== MID_LINES: skip 2 non-matching lines, match on 3rd =====

  @Benchmark
  public boolean reggie_midLines() {
    return reggiePattern.find(MID_LINES);
  }

  @Benchmark
  public boolean jdk_midLines() {
    return jdkPattern.matcher(MID_LINES).find();
  }

  @Benchmark
  public boolean re2j_midLines() {
    return re2jPattern.matcher(MID_LINES).find();
  }

  // ===== NO_MATCH: full-scan over ~200 digit-only chars =====

  @Benchmark
  public boolean reggie_noMatch() {
    return reggiePattern.find(NO_MATCH);
  }

  @Benchmark
  public boolean jdk_noMatch() {
    return jdkPattern.matcher(NO_MATCH).find();
  }

  @Benchmark
  public boolean re2j_noMatch() {
    return re2jPattern.matcher(NO_MATCH).find();
  }
}
