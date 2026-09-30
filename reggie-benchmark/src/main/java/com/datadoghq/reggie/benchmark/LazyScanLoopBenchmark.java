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
import org.openjdk.jmh.annotations.*;

/**
 * Review-evidence benchmark for the lazy-min extension (LAZY_LOOP {@code min > 0} admitted into
 * DETERMINISTIC_CHAIN_BYTECODE; capture snapshot/restore per failed tail try). Covers the re-routed
 * lazy shapes the review flagged — the empty-tail degenerate forms ({@code \d+?}, {@code <.+?>},
 * previously BITSTATE_CAPTURE fast-pathed ~2.3x vs JDK) and the capture-heavy form ({@code
 * ^(.+?)\.}) that pays the per-try capture restore.
 *
 * <p>Run: {@code ./gradlew :reggie-benchmark:jmh -Pjmh.args="LazyScanLoopBenchmark -wi 1 -w 1 -i 3
 * -r 1 -f 1"}. Before/after procedure and recorded numbers: .sphinx/address/benchmark-evidence.md.
 * Acceptance: the chain route stays within 1.5x of the JDK baseline per shape (reggie >= ~0.67x
 * JDK).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 1, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class LazyScanLoopBenchmark {

  /** ~200 digits split into runs by letters — the empty-tail lazy scan walks digit-run starts. */
  private static final String DIGITS_TEXT =
      "x123a4567b89c012345d6789e01234f56789g0123h4567i8901j2345k6789l0123m4567n8901o2345p6789q0123r4567s8901t2345u6789v0123w4567x8901y2345z6789";

  /** ~200 tags — the empty-tail lazy scan walks tag starts. */
  private static final String TAGS_TEXT = "<t>".repeat(200);

  /** Long dot-separated token — the capture-heavy shape (per-try capture restore cost). */
  private static final String DOTTED_TEXT =
      "a.b.c.d.e.f.g.h.i.j.k.l.m.n.o.p.q.r.s.t.u.v.w.x.y.z".repeat(8);

  private ReggieMatcher reggieDigits;
  private Pattern jdkDigits;

  private ReggieMatcher reggieTags;
  private Pattern jdkTags;

  private ReggieMatcher reggieDotted;
  private Pattern jdkDotted;

  @Setup(Level.Trial)
  public void setup() {
    reggieDigits = Reggie.compile("\\d+?");
    jdkDigits = Pattern.compile("\\d+?");
    reggieTags = Reggie.compile("<.+?>");
    jdkTags = Pattern.compile("<.+?>");
    reggieDotted = Reggie.compile("^(.+?)\\.");
    jdkDotted = Pattern.compile("^(.+?)\\.");
  }

  // ===== \d+? (empty tail, min = 1, POS_EQ_LEN not applicable — unanchored find) =====

  @Benchmark
  public boolean reggieDigitsFind() {
    return reggieDigits.find(DIGITS_TEXT);
  }

  @Benchmark
  public boolean jdkDigitsFind() {
    return jdkDigits.matcher(DIGITS_TEXT).find();
  }

  // ===== <.+?> (empty tail, min = 1) =====

  @Benchmark
  public boolean reggieTagsFind() {
    return reggieTags.find(TAGS_TEXT);
  }

  @Benchmark
  public boolean jdkTagsFind() {
    return jdkTags.matcher(TAGS_TEXT).find();
  }

  // ===== ^(.+?)\. (capture-heavy: full capture restore per failed tail try) =====

  @Benchmark
  public boolean reggieDottedFind() {
    return reggieDotted.find(DOTTED_TEXT);
  }

  @Benchmark
  public boolean jdkDottedFind() {
    return jdkDotted.matcher(DOTTED_TEXT).find();
  }
}
