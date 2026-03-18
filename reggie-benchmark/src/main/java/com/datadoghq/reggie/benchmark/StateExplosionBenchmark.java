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
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmark for patterns that cause DFA state explosion. These patterns fall back to NFA
 * execution in Reggie. Tests how Reggie's NFA performs compared to JDK's backtracking engine.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class StateExplosionBenchmark {

  // JDK patterns
  private Pattern jdkOptionalSequence;
  private Pattern jdkAlternationHeavy;
  private Pattern jdkNestedQuantifiers;
  private Pattern jdkLongAlternation;

  // RE2J patterns (linear-time, handles state explosion well)
  private com.google.re2j.Pattern re2jOptionalSequence;
  private com.google.re2j.Pattern re2jAlternationHeavy;
  private com.google.re2j.Pattern re2jNestedQuantifiers;
  private com.google.re2j.Pattern re2jLongAlternation;

  // Reggie patterns
  private ReggieMatcher reggieOptionalSequence;
  private ReggieMatcher reggieAlternationHeavy;
  private ReggieMatcher reggieNestedQuantifiers;
  private ReggieMatcher reggieLongAlternation;

  // Test data
  private static final String OPTIONAL_SEQ_MATCH = "aaaaaabbbbbb";
  private static final String OPTIONAL_SEQ_NO_MATCH = "aabbccdd";
  private static final String ALTERNATION_MATCH = "abc123";
  private static final String ALTERNATION_NO_MATCH = "xyz789";
  private static final String NESTED_QUANT_MATCH = "aaabbb";
  private static final String NESTED_QUANT_NO_MATCH = "aabbcc";
  private static final String LONG_ALT_MATCH = "keyword1";
  private static final String LONG_ALT_NO_MATCH = "notakeyword";

  @Setup
  public void setup() {
    // Pattern: Many optional groups can cause state explosion
    // (a)?(a)?(a)?(a)?(a)?(a)?(b)?(b)?(b)?(b)?(b)?(b)?
    jdkOptionalSequence = Pattern.compile("(a)?(a)?(a)?(a)?(a)?(a)?(b)?(b)?(b)?(b)?(b)?(b)?");
    reggieOptionalSequence =
        RuntimeCompiler.compile("(a)?(a)?(a)?(a)?(a)?(a)?(b)?(b)?(b)?(b)?(b)?(b)?");

    // Pattern: Heavy alternation with overlapping possibilities
    // (a|ab|abc)(1|12|123)
    jdkAlternationHeavy = Pattern.compile("(a|ab|abc)(1|12|123)");
    reggieAlternationHeavy = RuntimeCompiler.compile("(a|ab|abc)(1|12|123)");

    // Pattern: Nested quantifiers with capturing
    // ((a+)|(b+))+
    jdkNestedQuantifiers = Pattern.compile("((a+)|(b+))+");
    reggieNestedQuantifiers = RuntimeCompiler.compile("((a+)|(b+))+");

    // Pattern: Long alternation of keywords
    String longAlt =
        "keyword1|keyword2|keyword3|keyword4|keyword5|"
            + "keyword6|keyword7|keyword8|keyword9|keyword10|"
            + "keyword11|keyword12|keyword13|keyword14|keyword15";
    jdkLongAlternation = Pattern.compile(longAlt);
    reggieLongAlternation = RuntimeCompiler.compile(longAlt);

    // RE2J patterns (same patterns - RE2J handles state explosion well)
    re2jOptionalSequence =
        com.google.re2j.Pattern.compile("(a)?(a)?(a)?(a)?(a)?(a)?(b)?(b)?(b)?(b)?(b)?(b)?");
    re2jAlternationHeavy = com.google.re2j.Pattern.compile("(a|ab|abc)(1|12|123)");
    re2jNestedQuantifiers = com.google.re2j.Pattern.compile("((a+)|(b+))+");
    re2jLongAlternation = com.google.re2j.Pattern.compile(longAlt);
  }

  // Optional sequence benchmarks
  @Benchmark
  public boolean reggieOptionalSequenceMatch() {
    return reggieOptionalSequence.matches(OPTIONAL_SEQ_MATCH);
  }

  @Benchmark
  public boolean jdkOptionalSequenceMatch() {
    return jdkOptionalSequence.matcher(OPTIONAL_SEQ_MATCH).matches();
  }

  @Benchmark
  public boolean reggieOptionalSequenceNoMatch() {
    return reggieOptionalSequence.matches(OPTIONAL_SEQ_NO_MATCH);
  }

  @Benchmark
  public boolean jdkOptionalSequenceNoMatch() {
    return jdkOptionalSequence.matcher(OPTIONAL_SEQ_NO_MATCH).matches();
  }

  // Alternation heavy benchmarks
  @Benchmark
  public boolean reggieAlternationHeavyMatch() {
    return reggieAlternationHeavy.matches(ALTERNATION_MATCH);
  }

  @Benchmark
  public boolean jdkAlternationHeavyMatch() {
    return jdkAlternationHeavy.matcher(ALTERNATION_MATCH).matches();
  }

  @Benchmark
  public boolean reggieAlternationHeavyNoMatch() {
    return reggieAlternationHeavy.matches(ALTERNATION_NO_MATCH);
  }

  @Benchmark
  public boolean jdkAlternationHeavyNoMatch() {
    return jdkAlternationHeavy.matcher(ALTERNATION_NO_MATCH).matches();
  }

  // Nested quantifiers benchmarks
  @Benchmark
  public boolean reggieNestedQuantifiersMatch() {
    return reggieNestedQuantifiers.matches(NESTED_QUANT_MATCH);
  }

  @Benchmark
  public boolean jdkNestedQuantifiersMatch() {
    return jdkNestedQuantifiers.matcher(NESTED_QUANT_MATCH).matches();
  }

  @Benchmark
  public boolean reggieNestedQuantifiersNoMatch() {
    return reggieNestedQuantifiers.matches(NESTED_QUANT_NO_MATCH);
  }

  @Benchmark
  public boolean jdkNestedQuantifiersNoMatch() {
    return jdkNestedQuantifiers.matcher(NESTED_QUANT_NO_MATCH).matches();
  }

  // Long alternation benchmarks
  @Benchmark
  public boolean reggieLongAlternationMatch() {
    return reggieLongAlternation.matches(LONG_ALT_MATCH);
  }

  @Benchmark
  public boolean jdkLongAlternationMatch() {
    return jdkLongAlternation.matcher(LONG_ALT_MATCH).matches();
  }

  @Benchmark
  public boolean reggieLongAlternationNoMatch() {
    return reggieLongAlternation.matches(LONG_ALT_NO_MATCH);
  }

  @Benchmark
  public boolean jdkLongAlternationNoMatch() {
    return jdkLongAlternation.matcher(LONG_ALT_NO_MATCH).matches();
  }

  // ===== RE2J Benchmarks =====

  @Benchmark
  public boolean re2jOptionalSequenceMatch() {
    return re2jOptionalSequence.matcher(OPTIONAL_SEQ_MATCH).matches();
  }

  @Benchmark
  public boolean re2jOptionalSequenceNoMatch() {
    return re2jOptionalSequence.matcher(OPTIONAL_SEQ_NO_MATCH).matches();
  }

  @Benchmark
  public boolean re2jAlternationHeavyMatch() {
    return re2jAlternationHeavy.matcher(ALTERNATION_MATCH).matches();
  }

  @Benchmark
  public boolean re2jAlternationHeavyNoMatch() {
    return re2jAlternationHeavy.matcher(ALTERNATION_NO_MATCH).matches();
  }

  @Benchmark
  public boolean re2jNestedQuantifiersMatch() {
    return re2jNestedQuantifiers.matcher(NESTED_QUANT_MATCH).matches();
  }

  @Benchmark
  public boolean re2jNestedQuantifiersNoMatch() {
    return re2jNestedQuantifiers.matcher(NESTED_QUANT_NO_MATCH).matches();
  }

  @Benchmark
  public boolean re2jLongAlternationMatch() {
    return re2jLongAlternation.matcher(LONG_ALT_MATCH).matches();
  }

  @Benchmark
  public boolean re2jLongAlternationNoMatch() {
    return re2jLongAlternation.matcher(LONG_ALT_NO_MATCH).matches();
  }
}
