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
 * Benchmark for string anchor features: \A (string start), \Z (string end or before final newline),
 * \z (absolute end).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class StringAnchorBenchmark {

  // Test inputs
  private static final String INPUT_NO_NEWLINE = "prefix-content-suffix";
  private static final String INPUT_WITH_NEWLINE = "prefix-content-suffix\n";
  private static final String INPUT_LONG = "prefix-" + "x".repeat(100) + "-suffix";
  private static final String INPUT_LONG_NEWLINE = "prefix-" + "x".repeat(100) + "-suffix\n";

  // Reggie matchers
  private ReggieMatcher reggieStringStart;
  private ReggieMatcher reggieStringEnd;
  private ReggieMatcher reggieStringEndAbsolute;
  private ReggieMatcher reggieStartAndEnd;

  // JDK matchers
  private Pattern jdkStringStart;
  private Pattern jdkStringEnd;
  private Pattern jdkStringEndAbsolute;
  private Pattern jdkStartAndEnd;

  @Setup
  public void setup() {
    // \A - string start (matches only at beginning, not affected by multiline)
    reggieStringStart = Reggie.compile("\\Aprefix.*");
    jdkStringStart = Pattern.compile("\\Aprefix.*");

    // \Z - string end or before final newline
    reggieStringEnd = Reggie.compile(".*suffix\\Z");
    jdkStringEnd = Pattern.compile(".*suffix\\Z");

    // \z - absolute string end
    reggieStringEndAbsolute = Reggie.compile(".*suffix\\z");
    jdkStringEndAbsolute = Pattern.compile(".*suffix\\z");

    // Combined: \A...\Z
    reggieStartAndEnd = Reggie.compile("\\Aprefix.*suffix\\Z");
    jdkStartAndEnd = Pattern.compile("\\Aprefix.*suffix\\Z");
  }

  // ==================== String Start (\A) ====================

  @Benchmark
  public boolean reggieStringStart_match() {
    return reggieStringStart.matches(INPUT_NO_NEWLINE);
  }

  @Benchmark
  public boolean jdkStringStart_match() {
    return jdkStringStart.matcher(INPUT_NO_NEWLINE).matches();
  }

  // ==================== String End (\Z) ====================

  @Benchmark
  public boolean reggieStringEnd_noNewline() {
    return reggieStringEnd.matches(INPUT_NO_NEWLINE);
  }

  @Benchmark
  public boolean jdkStringEnd_noNewline() {
    return jdkStringEnd.matcher(INPUT_NO_NEWLINE).matches();
  }

  @Benchmark
  public boolean reggieStringEnd_withNewline() {
    return reggieStringEnd.matches(INPUT_WITH_NEWLINE);
  }

  @Benchmark
  public boolean jdkStringEnd_withNewline() {
    return jdkStringEnd.matcher(INPUT_WITH_NEWLINE).matches();
  }

  // ==================== String End Absolute (\z) ====================

  @Benchmark
  public boolean reggieStringEndAbsolute_noNewline() {
    return reggieStringEndAbsolute.matches(INPUT_NO_NEWLINE);
  }

  @Benchmark
  public boolean jdkStringEndAbsolute_noNewline() {
    return jdkStringEndAbsolute.matcher(INPUT_NO_NEWLINE).matches();
  }

  @Benchmark
  public boolean reggieStringEndAbsolute_withNewline() {
    // Should NOT match - \z requires absolute end
    return reggieStringEndAbsolute.matches(INPUT_WITH_NEWLINE);
  }

  @Benchmark
  public boolean jdkStringEndAbsolute_withNewline() {
    return jdkStringEndAbsolute.matcher(INPUT_WITH_NEWLINE).matches();
  }

  // ==================== Combined Anchors ====================

  @Benchmark
  public boolean reggieStartAndEnd_noNewline() {
    return reggieStartAndEnd.matches(INPUT_NO_NEWLINE);
  }

  @Benchmark
  public boolean jdkStartAndEnd_noNewline() {
    return jdkStartAndEnd.matcher(INPUT_NO_NEWLINE).matches();
  }

  @Benchmark
  public boolean reggieStartAndEnd_withNewline() {
    return reggieStartAndEnd.matches(INPUT_WITH_NEWLINE);
  }

  @Benchmark
  public boolean jdkStartAndEnd_withNewline() {
    return jdkStartAndEnd.matcher(INPUT_WITH_NEWLINE).matches();
  }

  // ==================== Performance with Longer Inputs ====================

  @Benchmark
  public boolean reggieStringEnd_long_noNewline() {
    return reggieStringEnd.matches(INPUT_LONG);
  }

  @Benchmark
  public boolean jdkStringEnd_long_noNewline() {
    return jdkStringEnd.matcher(INPUT_LONG).matches();
  }

  @Benchmark
  public boolean reggieStringEnd_long_withNewline() {
    return reggieStringEnd.matches(INPUT_LONG_NEWLINE);
  }

  @Benchmark
  public boolean jdkStringEnd_long_withNewline() {
    return jdkStringEnd.matcher(INPUT_LONG_NEWLINE).matches();
  }
}
