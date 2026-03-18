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
 * JMH benchmark for PCRE subroutine patterns. These patterns use (?R) for recursion and (?N) for
 * numbered subroutine calls. JDK Pattern does NOT support these PCRE-specific features, so only
 * Reggie benchmarks are included.
 *
 * <p>Demonstrates Reggie's support for context-free patterns beyond regular languages.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class SubroutineBenchmark {

  // Reggie patterns with subroutines
  private ReggieMatcher reggieNumberedSubroutine;
  private ReggieMatcher reggieRecursivePattern;
  private ReggieMatcher reggieNestedRecursion;

  // Test data for numbered subroutine: (a+)(?1) matches repeated 'a' sequences
  private static final String NUMBERED_MATCH = "aa"; // matches (a+)(?1)
  private static final String NUMBERED_MATCH_LONG = "aaaa"; // matches (a+)(?1)
  private static final String NUMBERED_NO_MATCH = "ab"; // doesn't match

  // Test data for recursive pattern: a(?R)b matches nested a...b
  private static final String RECURSIVE_SIMPLE = "ab"; // matches a(?R)?b
  private static final String RECURSIVE_NESTED = "aabb"; // matches a(?R)?b
  private static final String RECURSIVE_DEEP = "aaabbb"; // matches a(?R)?b
  private static final String RECURSIVE_NO_MATCH = "aab"; // doesn't match

  // Test data for nested recursion: (a(?1)?b) matches balanced a...b
  private static final String NESTED_SIMPLE = "ab";
  private static final String NESTED_BALANCED = "aabb";
  private static final String NESTED_DEEP = "aaabbb";

  @Setup
  public void setup() {
    // Numbered subroutine: (a+)(?1) - calls group 1 again
    reggieNumberedSubroutine = RuntimeCompiler.compile("(a+)(?1)");

    // Recursive pattern: a(?R)?b - optional recursion
    reggieRecursivePattern = RuntimeCompiler.compile("a(?R)?b");

    // Nested recursion: (a(?1)?b) - group calls itself
    reggieNestedRecursion = RuntimeCompiler.compile("(a(?1)?b)");
  }

  // ==================== Numbered Subroutine Benchmarks ====================

  @Benchmark
  public boolean reggieNumberedSubroutineMatch() {
    return reggieNumberedSubroutine.matches(NUMBERED_MATCH);
  }

  @Benchmark
  public boolean reggieNumberedSubroutineMatchLong() {
    return reggieNumberedSubroutine.matches(NUMBERED_MATCH_LONG);
  }

  @Benchmark
  public boolean reggieNumberedSubroutineNoMatch() {
    return reggieNumberedSubroutine.matches(NUMBERED_NO_MATCH);
  }

  // ==================== Recursive Pattern Benchmarks ====================

  @Benchmark
  public boolean reggieRecursiveSimple() {
    return reggieRecursivePattern.matches(RECURSIVE_SIMPLE);
  }

  @Benchmark
  public boolean reggieRecursiveNested() {
    return reggieRecursivePattern.matches(RECURSIVE_NESTED);
  }

  @Benchmark
  public boolean reggieRecursiveDeep() {
    return reggieRecursivePattern.matches(RECURSIVE_DEEP);
  }

  @Benchmark
  public boolean reggieRecursiveNoMatch() {
    return reggieRecursivePattern.matches(RECURSIVE_NO_MATCH);
  }

  // ==================== Nested Recursion Benchmarks ====================

  @Benchmark
  public boolean reggieNestedRecursionSimple() {
    return reggieNestedRecursion.matches(NESTED_SIMPLE);
  }

  @Benchmark
  public boolean reggieNestedRecursionBalanced() {
    return reggieNestedRecursion.matches(NESTED_BALANCED);
  }

  @Benchmark
  public boolean reggieNestedRecursionDeep() {
    return reggieNestedRecursion.matches(NESTED_DEEP);
  }
}
