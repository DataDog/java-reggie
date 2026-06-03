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
import com.datadoghq.reggie.ReggieMatcher;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Throughput benchmark for the BITPARALLEL_GLUSHKOV strategy.
 *
 * <p>The meaningful baseline is not JDK — it is what Reggie <em>previously</em> routed these
 * patterns to:
 *
 * <ul>
 *   <li>{@code .*a.{9}} (~513 DFA states, 19 positions): previously DFA_TABLE.
 *   <li>{@code .*a.{25}} (DFA explodes >10K states, 27 positions): previously LAZY_DFA.
 * </ul>
 *
 * <p>Each pattern is also compared against JDK as an external reference point. JDK has a Boyer-
 * Moore literal-prefix skip that gives it an advantage on {@code .*}-prefixed patterns; the
 * relevant comparison is therefore Glushkov vs the old Reggie path, not Glushkov vs JDK.
 *
 * <p>Two input shapes are exercised per pattern:
 *
 * <ul>
 *   <li>Short embedded: prefix just long enough to require a scan, then the match, no tail.
 *   <li>Long embedded: 300-char prefix + match + 100-char tail (stresses the scan path).
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BitParallelGlushkovBenchmark {

  // ── Pattern 1: .*a.{9} — previously DFA_TABLE (~513 states) ─────────────
  private static final String P1 = ".*a.{9}";
  // Short: 10 non-a chars, then "a" + 9 chars — total 20 chars, scan finds match at pos 0
  private static final String P1_SHORT = "xxxxxxxxxx" + "a" + "123456789";
  // Long: 300 non-a chars, then "a" + 9 chars + 100 tail
  private static final String P1_LONG = "x".repeat(300) + "a" + "y".repeat(9) + "z".repeat(100);

  // ── Pattern 2: .*a.{25} — previously LAZY_DFA (>10K DFA states) ─────────
  private static final String P2 = ".*a.{25}";
  private static final String P2_SHORT = "xxxxxxxxxx" + "a" + "1234567890123456789012345";
  private static final String P2_LONG = "x".repeat(300) + "a" + "y".repeat(25) + "z".repeat(100);

  private ReggieMatcher r1, r2;
  private Pattern j1, j2;

  @Setup(Level.Trial)
  public void setup() {
    r1 = Reggie.compile(P1);
    r2 = Reggie.compile(P2);
    j1 = Pattern.compile(P1);
    j2 = Pattern.compile(P2);
  }

  // ── Pattern 1, short input ────────────────────────────────────────────────

  @Benchmark
  public boolean p1Short_reggie() {
    return r1.find(P1_SHORT);
  }

  @Benchmark
  public boolean p1Short_jdk() {
    return j1.matcher(P1_SHORT).find();
  }

  // ── Pattern 1, long input ─────────────────────────────────────────────────

  @Benchmark
  public boolean p1Long_reggie() {
    return r1.find(P1_LONG);
  }

  @Benchmark
  public boolean p1Long_jdk() {
    return j1.matcher(P1_LONG).find();
  }

  // ── Pattern 2, short input ────────────────────────────────────────────────

  @Benchmark
  public boolean p2Short_reggie() {
    return r2.find(P2_SHORT);
  }

  @Benchmark
  public boolean p2Short_jdk() {
    return j2.matcher(P2_SHORT).find();
  }

  // ── Pattern 2, long input ─────────────────────────────────────────────────

  @Benchmark
  public boolean p2Long_reggie() {
    return r2.find(P2_LONG);
  }

  @Benchmark
  public boolean p2Long_jdk() {
    return j2.matcher(P2_LONG).find();
  }
}
