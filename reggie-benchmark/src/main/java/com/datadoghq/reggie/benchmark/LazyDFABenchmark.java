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
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Hit/miss/frozen benchmarks for the Lazy DFA cache (R1+R2). Per R7 methodology: explicit _hit /
 * _miss / _frozen variants. Baseline: compare against NFAFallbackBenchmark for the same patterns.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LazyDFABenchmark {

  // ~685 NFA states, no groups/anchors — DFA state explosion via interleaved a+/b+ alternation
  // causes StateExplosionException → OPTIMIZED_NFA → LAZY_DFA.
  // Note: deterministic patterns like (?:[a-z][0-9]){200} now route to DFA_TABLE instead.
  private static final String PATTERN = "(?:a+b+|b+a+){75}";
  // Positive match: 75 repetitions of "ab" — each "ab" satisfies one (a+b+) group
  private static final String MATCH_INPUT = "ab".repeat(75);

  private ReggieMatcher lazyMatcher;
  // JDK baseline — same pattern, same inputs, java.util.regex NFA
  private Pattern jdkPattern;
  private String[] missInputs;
  private int missIndex;

  @Setup(Level.Trial)
  public void setup() {
    RuntimeCompiler.clearCache();
    lazyMatcher = RuntimeCompiler.compile(PATTERN);
    jdkPattern = Pattern.compile(PATTERN);
    // Warm up the DFA cache
    for (int i = 0; i < 50; i++) lazyMatcher.matches(MATCH_INPUT);
    // Build diverse miss inputs
    Random rng = new Random(12345);
    missInputs = new String[1000];
    String chars = "abcdefghijklmnopqrstuvwxyz0123456789!@#$";
    for (int i = 0; i < missInputs.length; i++) {
      int len = 300 + rng.nextInt(200);
      StringBuilder sb = new StringBuilder(len);
      for (int j = 0; j < len; j++) sb.append(chars.charAt(rng.nextInt(chars.length())));
      missInputs[i] = sb.toString();
    }
  }

  /** Warm path: all DFA transitions cached → single int[128] read per char. */
  @Benchmark
  public boolean hitPath() {
    return lazyMatcher.matches(MATCH_INPUT);
  }

  /** Cold path: fresh diverse inputs → NFA step + interning on every transition. */
  @Benchmark
  public boolean missPath() {
    return lazyMatcher.matches(missInputs[(missIndex++ & 0x7FFF_FFFF) % missInputs.length]);
  }

  /** JDK baseline — same pattern, fixed matching input, java.util.regex NFA. */
  @Benchmark
  public boolean jdkHitBaseline() {
    return jdkPattern.matcher(MATCH_INPUT).matches();
  }

  /** JDK baseline — same diverse miss inputs as missPath. */
  @Benchmark
  public boolean jdkMissBaseline() {
    return jdkPattern
        .matcher(missInputs[(missIndex++ & 0x7FFF_FFFF) % missInputs.length])
        .matches();
  }

  /** Frozen path: cache at cap, all transitions use NFA fallback. */
  @State(Scope.Thread)
  public static class FrozenState {
    ReggieMatcher matcher;
    String[] frozenInputs;
    int idx;

    @Setup(Level.Trial)
    public void setup() {
      RuntimeCompiler.clearCache();
      matcher = RuntimeCompiler.compile(PATTERN);
      Random rng = new Random(99999);
      String alpha = "abcdefghijklmnopqrstuvwxyz0123456789";
      // Fill the cache to trigger freeze
      for (int i = 0; i < 10_000; i++) {
        StringBuilder sb = new StringBuilder(400);
        for (int j = 0; j < 400; j++) sb.append(alpha.charAt(rng.nextInt(alpha.length())));
        matcher.matches(sb.toString());
      }
      // Fixed always-matching input: measures full 400-char NFA traversal after freeze,
      // not early rejection on random non-matching strings.
      frozenInputs = new String[500];
      Arrays.fill(frozenInputs, MATCH_INPUT);
    }
  }

  @Benchmark
  public boolean frozenPath(FrozenState s) {
    return s.matcher.matches(s.frozenInputs[s.idx++ % s.frozenInputs.length]);
  }
}
