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
 * JMH benchmark for PCRE conditional patterns. These patterns use (?(condition)yes|no) to branch
 * based on whether a group matched. JDK Pattern does NOT support these PCRE-specific features, so
 * only Reggie benchmarks are included.
 *
 * <p>Demonstrates Reggie's support for context-sensitive patterns.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ConditionalBenchmark {

  // Reggie patterns with conditionals
  private ReggieMatcher reggieBalancedParens;
  private ReggieMatcher reggieSimpleConditional;
  private ReggieMatcher reggieConditionalNoElse;
  private ReggieMatcher reggieConditionalWithQuantifiers;
  private ReggieMatcher reggieNestedConditionals;

  // Test data for balanced parentheses: (\()?blah(?(1)\))
  private static final String BALANCED_WITH_PARENS = "(blah)";
  private static final String BALANCED_WITHOUT_PARENS = "blah";
  private static final String BALANCED_UNBALANCED_OPEN = "(blah";
  private static final String BALANCED_UNBALANCED_CLOSE = "blah)";

  // Test data for simple conditional: (a)?(?(1)b|c)
  private static final String SIMPLE_THEN_BRANCH = "ab";
  private static final String SIMPLE_ELSE_BRANCH = "c";
  private static final String SIMPLE_NO_MATCH_1 = "ac";
  private static final String SIMPLE_NO_MATCH_2 = "b";

  // Test data for no else branch: (x)?(?(1)y)
  private static final String NO_ELSE_BOTH = "xy";
  private static final String NO_ELSE_NEITHER = "";
  private static final String NO_ELSE_NO_MATCH = "x";

  // Test data for quantifiers: (a+)?(?(1)b+|c+)
  private static final String QUANT_THEN = "aaabbb";
  private static final String QUANT_ELSE = "ccc";

  // Test data for nested: (a)?(b)?(?(1)x)(?(2)y)
  private static final String NESTED_BOTH = "abxy";
  private static final String NESTED_FIRST = "ax";
  private static final String NESTED_SECOND = "by";
  private static final String NESTED_NEITHER = "";

  @Setup
  public void setup() {
    // Balanced parentheses: (\()?blah(?(1)\))
    reggieBalancedParens = RuntimeCompiler.compile("(\\()?blah(?(1)\\))");

    // Simple conditional: (a)?(?(1)b|c)
    reggieSimpleConditional = RuntimeCompiler.compile("(a)?(?(1)b|c)");

    // No else branch: (x)?(?(1)y)
    reggieConditionalNoElse = RuntimeCompiler.compile("(x)?(?(1)y)");

    // With quantifiers: (a+)?(?(1)b+|c+)
    reggieConditionalWithQuantifiers = RuntimeCompiler.compile("(a+)?(?(1)b+|c+)");

    // Nested conditionals: (a)?(b)?(?(1)x)(?(2)y)
    reggieNestedConditionals = RuntimeCompiler.compile("(a)?(b)?(?(1)x)(?(2)y)");
  }

  // ==================== Balanced Parentheses Benchmarks ====================

  @Benchmark
  public boolean reggieBalancedParensWithParens() {
    return reggieBalancedParens.matches(BALANCED_WITH_PARENS);
  }

  @Benchmark
  public boolean reggieBalancedParensWithoutParens() {
    return reggieBalancedParens.matches(BALANCED_WITHOUT_PARENS);
  }

  @Benchmark
  public boolean reggieBalancedParensUnbalancedOpen() {
    return reggieBalancedParens.matches(BALANCED_UNBALANCED_OPEN);
  }

  @Benchmark
  public boolean reggieBalancedParensUnbalancedClose() {
    return reggieBalancedParens.matches(BALANCED_UNBALANCED_CLOSE);
  }

  // ==================== Simple Conditional Benchmarks ====================

  @Benchmark
  public boolean reggieSimpleConditionalThenBranch() {
    return reggieSimpleConditional.matches(SIMPLE_THEN_BRANCH);
  }

  @Benchmark
  public boolean reggieSimpleConditionalElseBranch() {
    return reggieSimpleConditional.matches(SIMPLE_ELSE_BRANCH);
  }

  @Benchmark
  public boolean reggieSimpleConditionalNoMatch1() {
    return reggieSimpleConditional.matches(SIMPLE_NO_MATCH_1);
  }

  @Benchmark
  public boolean reggieSimpleConditionalNoMatch2() {
    return reggieSimpleConditional.matches(SIMPLE_NO_MATCH_2);
  }

  // ==================== No Else Branch Benchmarks ====================

  @Benchmark
  public boolean reggieConditionalNoElseBoth() {
    return reggieConditionalNoElse.matches(NO_ELSE_BOTH);
  }

  @Benchmark
  public boolean reggieConditionalNoElseNeither() {
    return reggieConditionalNoElse.matches(NO_ELSE_NEITHER);
  }

  @Benchmark
  public boolean reggieConditionalNoElseNoMatch() {
    return reggieConditionalNoElse.matches(NO_ELSE_NO_MATCH);
  }

  // ==================== Quantifier Benchmarks ====================

  @Benchmark
  public boolean reggieConditionalQuantifiersThen() {
    return reggieConditionalWithQuantifiers.matches(QUANT_THEN);
  }

  @Benchmark
  public boolean reggieConditionalQuantifiersElse() {
    return reggieConditionalWithQuantifiers.matches(QUANT_ELSE);
  }

  // ==================== Nested Conditional Benchmarks ====================

  @Benchmark
  public boolean reggieNestedConditionalsBoth() {
    return reggieNestedConditionals.matches(NESTED_BOTH);
  }

  @Benchmark
  public boolean reggieNestedConditionalsFirst() {
    return reggieNestedConditionals.matches(NESTED_FIRST);
  }

  @Benchmark
  public boolean reggieNestedConditionalsSecond() {
    return reggieNestedConditionals.matches(NESTED_SECOND);
  }

  @Benchmark
  public boolean reggieNestedConditionalsNeither() {
    return reggieNestedConditionals.matches(NESTED_NEITHER);
  }
}
