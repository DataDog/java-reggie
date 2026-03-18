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
 * Minimal smoke test benchmark covering all major bytecode generation strategies. Each benchmark
 * method tests ONE representative pattern for each strategy type.
 *
 * <p>Used for fast CI validation - not for performance measurement.
 *
 * <p>Strategy coverage: - SPECIALIZED_FIXED_SEQUENCE: fixedSequence - STATELESS_LOOP: statelessLoop
 * - DFA_UNROLLED_WITH_GROUPS: dfaUnrolled - DFA_SWITCH_WITH_GROUPS: dfaSwitch -
 * DFA_UNROLLED_WITH_ASSERTIONS: assertions - LINEAR_BACKREFERENCE: backreference
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class SmokeBenchmark {

  // Test data
  private String phone = "123-456-7890";
  private String digits = "1234567890";
  private String smallAlt = "abc";
  private String mediumAlt = "jkl";
  private String lookahead = "abc";
  private String backref = "aaaaaaaaa";

  // Reggie compiled patterns
  private ReggieMatcher fixedSequenceMatcher;
  private ReggieMatcher statelessLoopMatcher;
  private ReggieMatcher dfaUnrolledMatcher;
  private ReggieMatcher dfaSwitchMatcher;
  private ReggieMatcher assertionsMatcher;
  private ReggieMatcher backreferenceMatcher;

  // JDK Pattern for comparison
  private Pattern jdkFixedSequence;
  private Pattern jdkStatelessLoop;
  private Pattern jdkDfaUnrolled;
  private Pattern jdkDfaSwitch;
  private Pattern jdkAssertions;
  private Pattern jdkBackreference;

  @Setup
  public void setup() {
    // SPECIALIZED_FIXED_SEQUENCE
    fixedSequenceMatcher = Reggie.compile("\\d{3}-\\d{3}-\\d{4}");
    jdkFixedSequence = Pattern.compile("\\d{3}-\\d{3}-\\d{4}");

    // STATELESS_LOOP
    statelessLoopMatcher = Reggie.compile("\\d+");
    jdkStatelessLoop = Pattern.compile("\\d+");

    // DFA_UNROLLED_WITH_GROUPS
    dfaUnrolledMatcher = Reggie.compile("(a|b|c)");
    jdkDfaUnrolled = Pattern.compile("(a|b|c)");

    // DFA_SWITCH_WITH_GROUPS
    dfaSwitchMatcher = Reggie.compile("(abc|def|ghi|jkl|mno|pqr|stu|vwx|yz0|123|456|789)+");
    jdkDfaSwitch = Pattern.compile("(abc|def|ghi|jkl|mno|pqr|stu|vwx|yz0|123|456|789)+");

    // DFA_UNROLLED_WITH_ASSERTIONS
    assertionsMatcher = Reggie.compile("a(?=bc)");
    jdkAssertions = Pattern.compile("a(?=bc)");

    // LINEAR_BACKREFERENCE
    backreferenceMatcher = Reggie.compile("(a)\\1{8,}");
    jdkBackreference = Pattern.compile("(a)\\1{8,}");
  }

  // Reggie benchmarks
  @Benchmark
  public boolean reggieFixedSequence() {
    return fixedSequenceMatcher.matches(phone);
  }

  @Benchmark
  public boolean reggieStatelessLoop() {
    return statelessLoopMatcher.matches(digits);
  }

  @Benchmark
  public boolean reggieDfaUnrolled() {
    return dfaUnrolledMatcher.matches(smallAlt);
  }

  @Benchmark
  public boolean reggieDfaSwitch() {
    return dfaSwitchMatcher.matches(mediumAlt);
  }

  @Benchmark
  public boolean reggieAssertions() {
    return assertionsMatcher.matches(lookahead);
  }

  @Benchmark
  public boolean reggieBackreference() {
    return backreferenceMatcher.matches(backref);
  }

  // JDK Pattern benchmarks for comparison
  @Benchmark
  public boolean jdkFixedSequence() {
    return jdkFixedSequence.matcher(phone).matches();
  }

  @Benchmark
  public boolean jdkStatelessLoop() {
    return jdkStatelessLoop.matcher(digits).matches();
  }

  @Benchmark
  public boolean jdkDfaUnrolled() {
    return jdkDfaUnrolled.matcher(smallAlt).matches();
  }

  @Benchmark
  public boolean jdkDfaSwitch() {
    return jdkDfaSwitch.matcher(mediumAlt).matches();
  }

  @Benchmark
  public boolean jdkAssertions() {
    return jdkAssertions.matcher(lookahead).matches();
  }

  @Benchmark
  public boolean jdkBackreference() {
    return jdkBackreference.matcher(backref).matches();
  }
}
