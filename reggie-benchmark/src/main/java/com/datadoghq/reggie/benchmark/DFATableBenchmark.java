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
import org.openjdk.jmh.infra.Blackhole;

/** Benchmarks large pure regular DFAs routed through the compact DFA_TABLE backend. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class DFATableBenchmark {
  private static final String PATTERN = "(?:[a-z][0-9]){150}";

  private Pattern jdk;
  private ReggieMatcher reggie;
  private String matchingInput;
  private String searchInput;
  private String nonMatchingInput;
  private String noStartCharSearchInput;
  private int[] bounds;

  @Setup
  public void setup() {
    jdk = Pattern.compile(PATTERN);
    reggie = Reggie.compile(PATTERN);
    matchingInput = "a1".repeat(150);
    searchInput = "prefix-" + matchingInput + "-suffix";
    nonMatchingInput = matchingInput + "x";
    noStartCharSearchInput = "-".repeat(1024);
    bounds = new int[2];
  }

  @Benchmark
  public boolean jdkMatches() {
    return jdk.matcher(matchingInput).matches();
  }

  @Benchmark
  public boolean reggieMatches() {
    return reggie.matches(matchingInput);
  }

  @Benchmark
  public boolean jdkFind(Blackhole bh) {
    java.util.regex.Matcher matcher = jdk.matcher(searchInput);
    boolean found = matcher.find();
    if (found) {
      bh.consume(matcher.start());
      bh.consume(matcher.end());
    }
    return found;
  }

  @Benchmark
  public boolean reggieFindBounds(Blackhole bh) {
    boolean found = reggie.findBoundsFrom(searchInput, 0, bounds);
    if (found) {
      bh.consume(bounds[0]);
      bh.consume(bounds[1]);
    }
    return found;
  }

  @Benchmark
  public boolean jdkFindNoStartChar() {
    return jdk.matcher(noStartCharSearchInput).find();
  }

  @Benchmark
  public boolean reggieFindBoundsNoStartChar() {
    return reggie.findBoundsFrom(noStartCharSearchInput, 0, bounds);
  }

  @Benchmark
  public boolean jdkNonMatch() {
    return jdk.matcher(nonMatchingInput).matches();
  }

  @Benchmark
  public boolean reggieNonMatch() {
    return reggie.matches(nonMatchingInput);
  }
}
