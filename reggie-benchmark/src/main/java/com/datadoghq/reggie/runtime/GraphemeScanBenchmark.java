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
package com.datadoghq.reggie.runtime;

import com.datadoghq.reggie.Reggie;
import java.text.BreakIterator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** Complete grapheme traversal; each operation consumes every match's UTF-16 bounds. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
@State(Scope.Thread)
public class GraphemeScanBenchmark {

  @Param({"ASCII", "DECOMPOSED", "CJK", "EMOJI", "INDIC", "CRLF", "MARKS", "RI"})
  public String corpus;

  /** Minimum UTF-16 length; corpus units remain whole. */
  @Param({"16", "1024", "65536"})
  public int size;

  private String input;
  private ReggieMatcher nativeMatcher;
  private ReggieMatcher fallbackMatcher;
  private Pattern jdkPattern;
  private Matcher jdkMatcher;
  private BreakIterator breakIterator;
  private final int[] bounds = new int[2];

  @Setup(Level.Trial)
  public void setup() {
    if (size <= 0) {
      throw new IllegalArgumentException("size must be positive");
    }
    String unit =
        switch (corpus) {
          case "ASCII" -> "Text 0123456789. ";
          case "DECOMPOSED" -> "e\u0301a\u0308 ";
          case "CJK" -> "\u65E5\u672C\u8A9E\u4E2D\u6587";
          case "EMOJI" -> "\uD83D\uDC69\u200D\uD83D\uDCBB\uD83D\uDC4D\uD83C\uDFFD ";
          case "INDIC" -> "\u0915\u094D\u0915 ";
          case "CRLF" -> "line\r\n";
          case "MARKS" -> "a" + "\u0301".repeat(size - 1);
          case "RI" -> "\uD83C\uDDEF";
          default -> throw new IllegalArgumentException("Unknown corpus: " + corpus);
        };
    input = unit.repeat((size + unit.length() - 1) / unit.length());
    nativeMatcher = Reggie.compile("\\X");
    if (nativeMatcher.isJdkFallback()) {
      throw new IllegalStateException("Native benchmark requires a supported executing JDK");
    }
    // The benchmark shares the runtime package to construct the fallback without changing
    // admission.
    fallbackMatcher = new JavaRegexFallbackMatcher("\\X");
    jdkPattern = Pattern.compile("\\X");
    jdkMatcher = jdkPattern.matcher(input);
    breakIterator = BreakIterator.getCharacterInstance(Locale.ROOT);
    verifyBounds();
  }

  private void verifyBounds() {
    Matcher expected = jdkPattern.matcher(input);
    breakIterator.setText(input);
    int iteratorStart = breakIterator.first();
    int iteratorEnd = breakIterator.next();
    int from = 0;
    long checksum = 0;
    ReggieMatcher[] matchers = {nativeMatcher, fallbackMatcher};
    while (expected.find()) {
      for (ReggieMatcher matcher : matchers) {
        if (!matcher.findBoundsFrom(input, from, bounds)
            || bounds[0] != expected.start()
            || bounds[1] != expected.end()) {
          throw new IllegalStateException("Boundary mismatch for " + corpus + " at " + from);
        }
        MatchResult result = matcher.findMatchFrom(input, from);
        if (result == null
            || result.start() != expected.start()
            || result.end() != expected.end()) {
          throw new IllegalStateException("Rich-result mismatch for " + corpus + " at " + from);
        }
      }
      if (iteratorStart != expected.start() || iteratorEnd != expected.end()) {
        throw new IllegalStateException("BreakIterator mismatch for " + corpus + " at " + from);
      }
      checksum = checksum(checksum, expected.start(), expected.end());
      from = expected.end();
      iteratorStart = iteratorEnd;
      iteratorEnd = breakIterator.next();
    }
    if (from != input.length()
        || iteratorEnd != BreakIterator.DONE
        || nativeMatcher.findBoundsFrom(input, from, bounds)
        || fallbackMatcher.findBoundsFrom(input, from, bounds)
        || checksum != nativeBounds()
        || checksum != explicitFallback()
        || checksum != jdkReusedMatcher()
        || checksum != jdkNewMatcher()
        || checksum != breakIterator()) {
      throw new IllegalStateException("Traversal mismatch for " + corpus);
    }
  }

  @Benchmark
  public long nativeBounds() {
    long checksum = 0;
    int from = 0;
    while (nativeMatcher.findBoundsFrom(input, from, bounds)) {
      checksum = checksum(checksum, bounds[0], bounds[1]);
      from = bounds[1];
    }
    return checksum;
  }

  @Benchmark
  public long explicitFallback() {
    long checksum = 0;
    int from = 0;
    while (fallbackMatcher.findBoundsFrom(input, from, bounds)) {
      checksum = checksum(checksum, bounds[0], bounds[1]);
      from = bounds[1];
    }
    return checksum;
  }

  @Benchmark
  public long jdkReusedMatcher() {
    return drainJdk(jdkMatcher.reset(input));
  }

  @Benchmark
  public long jdkNewMatcher() {
    return drainJdk(jdkPattern.matcher(input));
  }

  private static long drainJdk(Matcher matcher) {
    long checksum = 0;
    while (matcher.find()) {
      checksum = checksum(checksum, matcher.start(), matcher.end());
    }
    return checksum;
  }

  @Benchmark
  public long breakIterator() {
    breakIterator.setText(input);
    long checksum = 0;
    int start = breakIterator.first();
    for (int end = breakIterator.next(); end != BreakIterator.DONE; end = breakIterator.next()) {
      checksum = checksum(checksum, start, end);
      start = end;
    }
    return checksum;
  }

  @Benchmark
  public long nativeRich(Blackhole blackhole) {
    long checksum = 0;
    int from = 0;
    MatchResult result;
    while ((result = nativeMatcher.findMatchFrom(input, from)) != null) {
      checksum = checksum(checksum, result.start(), result.end());
      from = result.end();
      blackhole.consume(result);
    }
    return checksum;
  }

  @Benchmark
  public long jdkRich(Blackhole blackhole) {
    jdkMatcher.reset(input);
    long checksum = 0;
    while (jdkMatcher.find()) {
      checksum = checksum(checksum, jdkMatcher.start(), jdkMatcher.end());
      blackhole.consume(jdkMatcher.toMatchResult());
    }
    return checksum;
  }

  private static long checksum(long previous, int start, int end) {
    return (31 * previous + start) * 31 + end;
  }
}
