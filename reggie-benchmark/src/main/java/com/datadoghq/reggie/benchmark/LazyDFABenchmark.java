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

import com.datadoghq.reggie.runtime.LazyDFACache;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
  // Separate counters so missPath() and jdkMissBaseline() walk the same input stream
  // independently, making each benchmark pairwise comparable over the same inputs.
  private int reggieIndex;
  private int jdkIndex;
  // Hard-miss inputs: all-[ab] strings that fail late in the pattern,
  // forcing real NFA/DFA traversal rather than immediate first-char rejection.
  private String[] hardMissInputs;
  private int hardMissIndex;

  @Setup(Level.Trial)
  public void setup() {
    RuntimeCompiler.clearCache();
    lazyMatcher = RuntimeCompiler.compile(PATTERN);
    jdkPattern = Pattern.compile(PATTERN);
    // Warm up the DFA cache
    for (int i = 0; i < 50; i++) lazyMatcher.matches(MATCH_INPUT);
    // Build diverse miss inputs (random chars — tests early-exit behavior)
    Random rng = new Random(12345);
    missInputs = new String[1000];
    String chars = "abcdefghijklmnopqrstuvwxyz0123456789!@#$";
    for (int i = 0; i < missInputs.length; i++) {
      int len = 300 + rng.nextInt(200);
      StringBuilder sb = new StringBuilder(len);
      for (int j = 0; j < len; j++) sb.append(chars.charAt(rng.nextInt(chars.length())));
      missInputs[i] = sb.toString();
    }
    // Build hard-miss inputs: all [ab] chars, fail after 60-74 complete groups.
    // Forces real NFA/DFA traversal before rejection — no early-exit on first char.
    hardMissInputs = new String[1000];
    for (int i = 0; i < hardMissInputs.length; i++) {
      // 60-74 complete (a+b+) groups, then 1-5 trailing 'a's without a closing 'b'.
      int completeGroups = 60 + (i % 15);
      int trailingAs = 1 + (i % 5);
      hardMissInputs[i] = "ab".repeat(completeGroups) + "a".repeat(trailingAs);
    }
  }

  /** Warm path: all DFA transitions cached → single int[128] read per char. */
  @Benchmark
  public boolean hitPath() {
    return lazyMatcher.matches(MATCH_INPUT);
  }

  /**
   * Steady-state early-rejection throughput: diverse random inputs that mostly contain characters
   * outside the pattern alphabet ([ab]). After the first pass over the 1,000-input pool the DEAD
   * transitions for those characters are cached in the ASCII table, so subsequent calls measure
   * cached-DEAD lookups rather than cold NFA-step+interning overhead. Use {@code hardMissPath} for
   * a fair late-failing comparison where both engines traverse the automaton before rejecting.
   */
  @Benchmark
  public boolean missPath() {
    return lazyMatcher.matches(missInputs[(reggieIndex++ & 0x7FFF_FFFF) % missInputs.length]);
  }

  /** JDK baseline — same pattern, fixed matching input, java.util.regex NFA. */
  @Benchmark
  public boolean jdkHitBaseline() {
    return jdkPattern.matcher(MATCH_INPUT).matches();
  }

  /** JDK baseline — same diverse miss inputs as missPath, independent index. */
  @Benchmark
  public boolean jdkMissBaseline() {
    return jdkPattern.matcher(missInputs[(jdkIndex++ & 0x7FFF_FFFF) % missInputs.length]).matches();
  }

  /**
   * Hard-miss path: all-[ab] inputs that fail after 60-74 complete groups. Forces real NFA
   * traversal before rejection — a fair comparison against jdkHardMissBaseline.
   */
  @Benchmark
  public boolean hardMissPath() {
    return lazyMatcher.matches(
        hardMissInputs[(hardMissIndex++ & 0x7FFF_FFFF) % hardMissInputs.length]);
  }

  /** JDK hard-miss baseline — same late-failing all-[ab] inputs. */
  @Benchmark
  public boolean jdkHardMissBaseline() {
    return jdkPattern
        .matcher(hardMissInputs[(hardMissIndex++ & 0x7FFF_FFFF) % hardMissInputs.length])
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
      // Use only 'a'/'b' so every warm-up step forces a real NFA-derived DFA transition.
      // A 36-char alphabet hits DEAD after one step and adds too few states to fill the cap,
      // causing frozenPath to measure normal cached-DEAD-rejection rather than NFA fallback.
      String alpha = "ab";
      // Fill the cache to trigger freeze
      for (int i = 0; i < 10_000; i++) {
        StringBuilder sb = new StringBuilder(400);
        for (int j = 0; j < 400; j++) sb.append(alpha.charAt(rng.nextInt(alpha.length())));
        matcher.matches(sb.toString());
      }
      // Assert the cache is actually frozen before measuring, so frozenPath truly exercises
      // the NFA-fallback path and not the normal DFA cache. isFrozen() is package-private,
      // so we access it via reflection.
      try {
        Field cacheField = matcher.getClass().getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        LazyDFACache cache = (LazyDFACache) cacheField.get(null);
        Method isFrozen = LazyDFACache.class.getDeclaredMethod("isFrozen");
        isFrozen.setAccessible(true);
        if (!(Boolean) isFrozen.invoke(cache)) {
          throw new IllegalStateException(
              "LazyDFACache not frozen after warm-up — frozenPath would measure the wrong path."
                  + " Increase warm-up iterations or check the pattern's DFA state count.");
        }
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Could not verify frozen state", e);
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
