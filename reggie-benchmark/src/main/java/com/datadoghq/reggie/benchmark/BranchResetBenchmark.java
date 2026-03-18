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
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmark for PCRE branch reset patterns (?|alt1|alt2). Branch reset allows alternatives to
 * reuse the same group numbers. JDK Pattern does NOT support branch reset, so only Reggie
 * benchmarks are included.
 *
 * <p>Demonstrates efficient alternation with group reuse.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class BranchResetBenchmark {

  // Reggie patterns with branch reset
  private ReggieMatcher reggieSimple;
  private ReggieMatcher reggieMultipleGroups;
  private ReggieMatcher reggieWithQuantifiers;
  private ReggieMatcher reggieUneven;

  // Test data for simple branch reset: (?|(abc)|(xyz))
  private static final String SIMPLE_FIRST = "abc";
  private static final String SIMPLE_SECOND = "xyz";

  // Test data for multiple groups: (?|(a)(b)(c)|(x)(y)(z))
  private static final String MULTI_FIRST = "abc";
  private static final String MULTI_SECOND = "xyz";

  // Test data for quantifiers: (?|(a+)|(b+))
  private static final String QUANT_FIRST = "aaa";
  private static final String QUANT_SECOND = "bbb";

  // Test data for uneven groups: (?|(a)(b)(c)|(x))
  private static final String UNEVEN_FIRST = "abc";
  private static final String UNEVEN_SECOND = "x";

  @Setup
  public void setup() {
    // Simple branch reset
    reggieSimple = RuntimeCompiler.compile("(?|(abc)|(xyz))");

    // Multiple groups
    reggieMultipleGroups = RuntimeCompiler.compile("(?|(a)(b)(c)|(x)(y)(z))");

    // With quantifiers
    reggieWithQuantifiers = RuntimeCompiler.compile("(?|(a+)|(b+))");

    // Uneven groups
    reggieUneven = RuntimeCompiler.compile("(?|(a)(b)(c)|(x))");
  }

  // ==================== Simple Branch Reset Benchmarks ====================

  @Benchmark
  public boolean reggieSimpleFirstAlt() {
    return reggieSimple.matches(SIMPLE_FIRST);
  }

  @Benchmark
  public boolean reggieSimpleSecondAlt() {
    return reggieSimple.matches(SIMPLE_SECOND);
  }

  // ==================== Multiple Groups Benchmarks ====================

  @Benchmark
  public boolean reggieMultipleGroupsFirstAlt() {
    return reggieMultipleGroups.matches(MULTI_FIRST);
  }

  @Benchmark
  public boolean reggieMultipleGroupsSecondAlt() {
    return reggieMultipleGroups.matches(MULTI_SECOND);
  }

  // ==================== Quantifier Benchmarks ====================

  @Benchmark
  public boolean reggieQuantifiersFirstAlt() {
    return reggieWithQuantifiers.matches(QUANT_FIRST);
  }

  @Benchmark
  public boolean reggieQuantifiersSecondAlt() {
    return reggieWithQuantifiers.matches(QUANT_SECOND);
  }

  // ==================== Uneven Groups Benchmarks ====================

  @Benchmark
  public boolean reggieUnevenFirstAlt() {
    return reggieUneven.matches(UNEVEN_FIRST);
  }

  @Benchmark
  public boolean reggieUnevenSecondAlt() {
    return reggieUneven.matches(UNEVEN_SECOND);
  }
}
