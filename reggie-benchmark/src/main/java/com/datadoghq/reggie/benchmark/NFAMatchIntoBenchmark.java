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
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
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

/** Benchmark for generated NFA capture extraction via matchInto(). */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class NFAMatchIntoBenchmark {
  private static final String NFA_PATTERN = "(?=.*[0-9])([a-z]+)([0-9]+)";
  private static final String[] INPUTS = {"abc123", "xyz98765", "letters", "a1"};

  private Pattern jdkPattern;
  private ReggieMatcher reggieMatcher;
  private int[] starts;
  private int[] ends;

  @Setup
  public void setup() throws ReflectiveOperationException {
    jdkPattern = Pattern.compile(NFA_PATTERN);
    reggieMatcher = Reggie.compile(NFA_PATTERN);
    starts = new int[3];
    ends = new int[3];

    Class<?> declaringClass =
        reggieMatcher
            .getClass()
            .getMethod("matchInto", String.class, int[].class, int[].class)
            .getDeclaringClass();
    if (declaringClass == ReggieMatcher.class) {
      throw new IllegalStateException(
          "benchmark pattern does not use generated matchInto override");
    }
  }

  @Benchmark
  public int jdkParseBoundsOnly() {
    int total = 0;
    for (String input : INPUTS) {
      Matcher matcher = jdkPattern.matcher(input);
      if (matcher.matches()) {
        for (int group = 0; group <= 2; group++) {
          total += matcher.start(group) + matcher.end(group);
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchResultBoundsOnly() {
    int total = 0;
    for (String input : INPUTS) {
      MatchResult match = reggieMatcher.match(input);
      if (match != null) {
        for (int group = 0; group <= 2; group++) {
          total += match.start(group) + match.end(group);
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchIntoBoundsOnly() {
    int total = 0;
    for (String input : INPUTS) {
      if (reggieMatcher.matchInto(input, starts, ends)) {
        for (int group = 0; group <= 2; group++) {
          total += starts[group] + ends[group];
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchResultParseAndExtract() {
    int total = 0;
    for (String input : INPUTS) {
      MatchResult match = reggieMatcher.match(input);
      if (match != null) {
        for (int group = 1; group <= 2; group++) {
          total += match.group(group).length();
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchIntoParseAndExtract() {
    int total = 0;
    for (String input : INPUTS) {
      if (reggieMatcher.matchInto(input, starts, ends)) {
        for (int group = 1; group <= 2; group++) {
          total += input.substring(starts[group], ends[group]).length();
        }
      }
    }
    return total;
  }
}
