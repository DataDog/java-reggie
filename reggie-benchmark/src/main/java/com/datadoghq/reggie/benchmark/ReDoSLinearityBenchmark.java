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
import org.openjdk.jmh.annotations.*;

/**
 * Demonstrates Reggie's O(n) safety guarantee against ReDoS patterns.
 *
 * <p>Reggie converts ambiguous patterns like {@code (a+)+b} to a minimal DFA (3 states,
 * DFA_UNROLLED_WITH_GROUPS strategy) before generating bytecode. This eliminates exponential
 * backtracking entirely.
 *
 * <p>JDK's backtracking NFA takes exponential time on non-matching inputs for the same pattern. The
 * non-matching input {@code "aaaaaa...aac"} (long 'a' string ending in 'c') triggers worst-case
 * backtracking in JDK — the benchmark measures this difference.
 *
 * <p>Uses {@link BenchmarkPatterns#BACKTRACK_A} ({@code "a*a*a*a*b"}) and {@link
 * BenchmarkPatterns#NESTED_QUANTIFIER} ({@code "(a+)+b"}).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ReDoSLinearityBenchmark {

  // Non-matching input: long run of 'a's ending in 'c' — no 'b' to satisfy either pattern.
  // JDK backtracks exponentially on this; Reggie DFA rejects in O(n).
  private static final String NON_MATCH_INPUT = "aaaaaaaaaaaaaaaaaaaac";

  // Matching inputs used to verify correctness during setup
  private static final String MATCH_INPUT = "ab";

  private ReggieMatcher reggieNestedQuantifier;
  private ReggieMatcher reggieBacktrackA;
  private Pattern jdkNestedQuantifier;
  private Pattern jdkBacktrackA;

  @Setup(Level.Trial)
  public void setup() {
    reggieNestedQuantifier = RuntimeCompiler.compile(BenchmarkPatterns.NESTED_QUANTIFIER);
    reggieBacktrackA = RuntimeCompiler.compile(BenchmarkPatterns.BACKTRACK_A);
    jdkNestedQuantifier = Pattern.compile(BenchmarkPatterns.NESTED_QUANTIFIER);
    jdkBacktrackA = Pattern.compile(BenchmarkPatterns.BACKTRACK_A);

    // Sanity checks: both engines agree on match results
    if (reggieNestedQuantifier.matches(MATCH_INPUT)
        != jdkNestedQuantifier.matcher(MATCH_INPUT).matches()) {
      throw new IllegalStateException(
          "Result mismatch for NESTED_QUANTIFIER on matching input: " + MATCH_INPUT);
    }
    if (reggieBacktrackA.matches(MATCH_INPUT) != jdkBacktrackA.matcher(MATCH_INPUT).matches()) {
      throw new IllegalStateException(
          "Result mismatch for BACKTRACK_A on matching input: " + MATCH_INPUT);
    }
  }

  /** Reggie O(n) non-match for {@code (a+)+b}: DFA traversal, no backtracking. */
  @Benchmark
  public boolean reggieNestedQuantifierNoMatch() {
    return reggieNestedQuantifier.matches(NON_MATCH_INPUT);
  }

  /**
   * JDK non-match for {@code (a+)+b}: backtracking NFA on non-matching input — exponential in the
   * worst case.
   */
  @Benchmark
  public boolean jdkNestedQuantifierNoMatch() {
    return jdkNestedQuantifier.matcher(NON_MATCH_INPUT).matches();
  }

  /** Reggie O(n) match for {@code (a+)+b}: DFA transitions. */
  @Benchmark
  public boolean reggieNestedQuantifierMatch() {
    return reggieNestedQuantifier.matches(MATCH_INPUT);
  }

  /** JDK match for {@code (a+)+b} on short matching input. */
  @Benchmark
  public boolean jdkNestedQuantifierMatch() {
    return jdkNestedQuantifier.matcher(MATCH_INPUT).matches();
  }

  /** Reggie O(n) non-match for {@code a*a*a*a*b}: DFA traversal. */
  @Benchmark
  public boolean reggieBacktrackANoMatch() {
    return reggieBacktrackA.matches(NON_MATCH_INPUT);
  }

  /** JDK non-match for {@code a*a*a*a*b}: exponential backtracking on long non-matching input. */
  @Benchmark
  public boolean jdkBacktrackANoMatch() {
    return jdkBacktrackA.matcher(NON_MATCH_INPUT).matches();
  }

  /** Reggie match for {@code a*a*a*a*b}. */
  @Benchmark
  public boolean reggieBacktrackAMatch() {
    return reggieBacktrackA.matches(MATCH_INPUT);
  }

  /** JDK match for {@code a*a*a*a*b}. */
  @Benchmark
  public boolean jdkBacktrackAMatch() {
    return jdkBacktrackA.matcher(MATCH_INPUT).matches();
  }
}
