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

import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Focused benchmark for profiling the (\d+) pattern bottleneck. Run with: ./gradlew
 * :reggie-benchmark:jmh --args="NFAProfiling -prof async:output=flamegraph" Or: ./gradlew
 * :reggie-benchmark:jmh --args="NFAProfiling -prof stack"
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class NFAProfilingBenchmark {

  private static final String DIGIT_PATTERN = "(\\d+)";
  private static final String INPUT_MATCH = "12345";
  private static final String INPUT_FIND = "The value is 98765 here";

  private ReggieMatcher digitMatcher;

  @Setup
  public void setup() {
    digitMatcher = RuntimeCompiler.compile(DIGIT_PATTERN);
  }

  @Benchmark
  public MatchResult digitMatch(Blackhole bh) {
    MatchResult result = digitMatcher.match(INPUT_MATCH);
    bh.consume(result.group(1)); // Force group extraction
    return result;
  }

  @Benchmark
  public MatchResult digitFindMatch(Blackhole bh) {
    MatchResult result = digitMatcher.findMatch(INPUT_FIND);
    bh.consume(result.group(1)); // Force group extraction
    return result;
  }

  /** Baseline: just match without group extraction */
  @Benchmark
  public boolean digitMatchOnly() {
    return digitMatcher.matches(INPUT_MATCH);
  }

  /** Baseline: just find without group extraction */
  @Benchmark
  public int digitFindOnly() {
    return digitMatcher.findFrom(INPUT_FIND, 0);
  }
}
