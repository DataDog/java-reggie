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

import com.datadoghq.reggie.benchmark.engines.RustRegexEngine;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * matches/find/findMatch throughput for anchor, lookahead and backreference shapes across the
 * reggie, JDK, re2j and Rust engines, on a 20-line {@code key=value} input. {@code SINGLE} joins
 * the lines with spaces; {@code MULTI} alternates {@code \n} and {@code \r\n} terminators. Setup
 * fails the trial when the reggie lane disagrees with the JDK; a lane that cannot compile the
 * pattern (re2j and Rust: lookaround/backreferences; Rust also when its library is not built) fails
 * its trial.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EngineShapeBenchmark {
  @Param({
    "a.b",
    "x.*$",
    "^(.*)$",
    "a.+?$",
    "(?m)^.+$",
    "(?m)\\d$",
    "(?m)^a*",
    "(?m)^(\\w+)=(.*)$",
    "(?=.*a)(?=.*b).{3,}",
    "\\d+(?=px)",
    "\\w+(?=\\s42)",
    "(?m)(^a|b)",
    "(?m)^abcdefghijklmnopqrstuvwxyz|b",
    "(\\w)\\1$",
    "(?s)(?m)^[a-z]+(.*)",
    "(?m)\\w+$"
  })
  public String pattern;

  @Param({"SINGLE", "MULTI"})
  public String inputKind;

  @Param({"reggie", "jdk", "re2j", "rust"})
  public String engine;

  private String input;
  private ReggieMatcher reggie;
  private Pattern jdk;
  private com.google.re2j.Pattern re2j;
  private RustRegexEngine rustFind;
  private RustRegexEngine rustFull;

  @Setup(Level.Trial)
  public void setup() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20; i++) {
      sb.append("key").append(i).append("=lorem ipsum dolor sit amet 42");
      if ("MULTI".equals(inputKind)) sb.append(i % 2 == 0 ? "\n" : "\r\n");
      else sb.append(' ');
    }
    sb.append("zz");
    input = sb.toString();
    Pattern ref = Pattern.compile(pattern);
    Matcher rm = ref.matcher(input);
    boolean refFind = rm.find();
    boolean refMatches = ref.matcher(input).matches();
    boolean m;
    boolean f;
    switch (engine) {
      case "reggie":
        reggie = RuntimeCompiler.compile(pattern);
        m = reggie.matches(input);
        f = reggie.find(input);
        break;
      case "jdk":
        jdk = ref;
        m = refMatches;
        f = refFind;
        break;
      case "re2j":
        re2j = com.google.re2j.Pattern.compile(pattern);
        m = re2j.matcher(input).matches();
        f = re2j.matcher(input).find();
        break;
      case "rust":
        rustFind = RustRegexEngine.compile(pattern);
        rustFull = RustRegexEngine.compileFullMatch(pattern);
        m = rustFull.isMatch(input);
        f = rustFind.find(input);
        break;
      default:
        throw new IllegalArgumentException(engine);
    }
    if ("reggie".equals(engine) && (m != refMatches || f != refFind)) {
      throw new IllegalStateException(
          "reggie disagrees with JDK on " + pattern + " / " + inputKind);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    if (rustFind != null) rustFind.close();
    if (rustFull != null) rustFull.close();
  }

  @Benchmark
  public boolean matches() {
    switch (engine) {
      case "reggie":
        return reggie.matches(input);
      case "jdk":
        return jdk.matcher(input).matches();
      case "re2j":
        return re2j.matcher(input).matches();
      default:
        return rustFull.isMatch(input);
    }
  }

  @Benchmark
  public boolean find() {
    switch (engine) {
      case "reggie":
        return reggie.find(input);
      case "jdk":
        return jdk.matcher(input).find();
      case "re2j":
        return re2j.matcher(input).find();
      default:
        return rustFind.find(input);
    }
  }

  @Benchmark
  public int findMatch() {
    switch (engine) {
      case "reggie":
        MatchResult r = reggie.findMatch(input);
        return r == null ? -1 : r.end();
      case "jdk":
        Matcher jm = jdk.matcher(input);
        return jm.find() ? jm.end() : -1;
      case "re2j":
        com.google.re2j.Matcher em = re2j.matcher(input);
        return em.find() ? em.end() : -1;
      default:
        // The Rust lane computes the span natively (rxFind); the wrapper exposes only the boolean.
        return rustFind.find(input) ? 1 : -1;
    }
  }
}
