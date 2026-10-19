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

import com.datadoghq.reggie.runtime.MatchBudgetExceededException;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * How find() time grows with input length on ReDoS-shaped patterns, for Reggie, RE2J and
 * java.util.regex.
 *
 * <p>The input is {@code "a".repeat(n) + "!b"}. It contains every literal the patterns require, so
 * prefilters cannot reject it, and the '!' makes every attempt that consumes the run of 'a's fail.
 *
 * <ul>
 *   <li>{@link Regular}: patterns without backreferences or lookaround. Reggie and RE2J run them on
 *       automata, so time should grow linearly with n.
 *   <li>{@link Extended}: backreferences, lookahead, possessive quantifiers, atomic groups and
 *       recursion. RE2J cannot parse them. Reggie runs most of them on BackrefBacktrackMatcher,
 *       whose work is polynomial and capped per attempt by {@code reggie.countedloop.maxSteps} and
 *       per find call by {@code reggie.countedloop.stepsPerChar}; rows that exceed a cap measure
 *       the time to throw MatchBudgetExceededException, and setup prints which ones do.
 *   <li>{@link Jdk}: the java.util.regex rows. Its backtracking is exponential on several of these
 *       patterns, so n stops at 24; compare with the n=24 rows of the other engines.
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ReDoSLinearityBenchmark {

  private static final Map<String, String> PATTERNS =
      Map.ofEntries(
          Map.entry("nested", BenchmarkPatterns.NESTED_QUANTIFIER),
          Map.entry("star4", BenchmarkPatterns.BACKTRACK_A),
          Map.entry("altOverlap", "(a|aa)+b"),
          Map.entry("nestedBackref", "(a+)+\\1b"),
          Map.entry("repeatBackref", "^(a+)\\1*b"),
          Map.entry("singleBackref", "(a)+\\1b"),
          Map.entry("lookaheadNested", "(?=a)(a+)+b"),
          Map.entry("altLookahead", "(a|aa)+(?=b)"),
          Map.entry("possessiveNested", "(a++)+b"),
          Map.entry("recursion", "(a+)(a+)(a+)bc(?1)?(?>q)?"));

  private static String input(int n) {
    return "a".repeat(n) + "!b";
  }

  @State(Scope.Thread)
  public static class Regular {
    @Param({"nested", "star4", "altOverlap"})
    String pattern;

    @Param({"24", "1000", "10000", "100000"})
    int n;

    ReggieMatcher reggie;
    com.google.re2j.Pattern re2j;
    String input;

    @Setup(Level.Trial)
    public void setup() {
      String regex = PATTERNS.get(pattern);
      reggie = RuntimeCompiler.compile(regex);
      re2j = com.google.re2j.Pattern.compile(regex);
      input = input(n);
      if (reggie.find(input) != re2j.matcher(input).find()) {
        throw new IllegalStateException("Reggie and RE2J disagree on /" + regex + "/, n=" + n);
      }
    }
  }

  @State(Scope.Thread)
  public static class Extended {
    @Param({
      "nestedBackref",
      "repeatBackref",
      "singleBackref",
      "lookaheadNested",
      "altLookahead",
      "possessiveNested",
      "recursion"
    })
    String pattern;

    @Param({"24", "1000", "10000", "100000"})
    int n;

    ReggieMatcher reggie;
    String input;

    @Setup(Level.Trial)
    public void setup() {
      String regex = PATTERNS.get(pattern);
      reggie = RuntimeCompiler.compile(regex);
      input = input(n);
      try {
        reggie.find(input);
      } catch (MatchBudgetExceededException e) {
        System.out.println("\n[ReDoS] /" + regex + "/ n=" + n + ": step budget exceeded");
      }
    }
  }

  @State(Scope.Thread)
  public static class Jdk {
    @Param({
      "nested",
      "star4",
      "altOverlap",
      "nestedBackref",
      "repeatBackref",
      "singleBackref",
      "lookaheadNested",
      "altLookahead",
      "possessiveNested"
    })
    String pattern;

    @Param({"12", "16", "20", "24"})
    int n;

    Pattern jdk;
    String input;

    @Setup(Level.Trial)
    public void setup() {
      jdk = Pattern.compile(PATTERNS.get(pattern));
      input = input(n);
    }
  }

  @Benchmark
  public boolean reggieRegular(Regular s) {
    return s.reggie.find(s.input);
  }

  @Benchmark
  public boolean re2jRegular(Regular s) {
    return s.re2j.matcher(s.input).find();
  }

  @Benchmark
  public boolean reggieExtended(Extended s) {
    try {
      return s.reggie.find(s.input);
    } catch (MatchBudgetExceededException e) {
      return false;
    }
  }

  @Benchmark
  public boolean jdk(Jdk s) {
    return s.jdk.matcher(s.input).find();
  }
}
