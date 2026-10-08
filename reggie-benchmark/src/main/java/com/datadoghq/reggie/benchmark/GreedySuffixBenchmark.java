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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Greedy capturing {@code .*}/{@code .+} group followed by a suffix that the greedy run must give
 * characters back to. Each pattern is measured on a short input, a long input, and many short lines
 * (where {@code .} stops at every {@code \n}).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GreedySuffixBenchmark {

  @Param({
    "(.*)bar",
    "(.*)(\\d+)",
    "(.+)(\\d+)",
    "(.*)(\\d+)$",
    "(.*)\\b(\\d+)",
    "x(.*)(\\d+)",
    "(.*)\\d",
    "(.*)ab$"
  })
  public String pattern;

  @Param({"SHORT", "LONG", "LINES"})
  public String inputKind;

  private static final String SHORT_INPUT = "xuser=alice bar id=4821";
  private static final String LONG_INPUT;
  private static final String LINES_INPUT = "xlorem ipsum bar dolor id=4821\n".repeat(36);

  static {
    StringBuilder sb = new StringBuilder("x");
    for (int i = 0; i < 50; i++) {
      sb.append("lorem ipsum bar dolor ");
    }
    sb.append("id=4821");
    LONG_INPUT = sb.toString();
  }

  private String input;
  private ReggieMatcher reggie;
  private Pattern jdk;

  @Setup(Level.Trial)
  public void setup() {
    input =
        switch (inputKind) {
          case "SHORT" -> SHORT_INPUT;
          case "LONG" -> LONG_INPUT;
          default -> LINES_INPUT;
        };
    reggie = RuntimeCompiler.compile(pattern);
    jdk = Pattern.compile(pattern);
  }

  @Benchmark
  public boolean reggieMatches() {
    return reggie.matches(input);
  }

  @Benchmark
  public boolean jdkMatches() {
    return jdk.matcher(input).matches();
  }

  @Benchmark
  public boolean reggieFind() {
    return reggie.find(input);
  }

  @Benchmark
  public boolean jdkFind() {
    return jdk.matcher(input).find();
  }

  @Benchmark
  public int reggieFindMatch() {
    MatchResult r = reggie.findMatch(input);
    return r == null ? -1 : r.end(1);
  }

  @Benchmark
  public int jdkFindMatch() {
    Matcher m = jdk.matcher(input);
    return m.find() ? m.end(1) : -1;
  }

  @Benchmark
  public int reggieFindAll() {
    int count = 0;
    int from = 0;
    MatchResult r;
    while (from <= input.length() && (r = reggie.findMatchFrom(input, from)) != null) {
      count++;
      from = r.end() == r.start() ? r.end() + 1 : r.end();
    }
    return count;
  }

  @Benchmark
  public int jdkFindAll() {
    int count = 0;
    Matcher m = jdk.matcher(input);
    while (m.find()) {
      count++;
    }
    return count;
  }
}
