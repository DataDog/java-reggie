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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmark comparing lookahead/lookbehind assertion performance. Tests patterns with (?=...),
 * (?!...), (?<=...), (?<!...)
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class AssertionBenchmark {

  // JDK patterns - simple literals (DFA)
  private Pattern jdkPositiveLookahead;
  private Pattern jdkNegativeLookahead;
  private Pattern jdkPositiveLookbehind;
  private Pattern jdkNegativeLookbehind;
  private Pattern jdkPasswordValidation;

  // JDK patterns - character classes (DFA)
  private Pattern jdkCharClassLookbehind;
  private Pattern jdkCharClassLookahead;

  // JDK patterns - complex (NFA fallback)
  private Pattern jdkVariableWidthLookahead;
  private Pattern jdkAlternationLookbehind;

  // Reggie patterns
  private AssertionPatterns patterns;

  // Test data - simple literal patterns
  private static final String LOOKAHEAD_MATCH = "abc123"; // matches a(?=bc)
  private static final String LOOKAHEAD_NO_MATCH = "axc123"; // doesn't match a(?=bc)
  private static final String LOOKBEHIND_MATCH = "abc"; // matches (?<=ab)c
  private static final String LOOKBEHIND_NO_MATCH = "xbc"; // doesn't match (?<=ab)c
  private static final String PASSWORD_VALID = "Password1!"; // has uppercase, digit, special
  private static final String PASSWORD_INVALID = "password"; // missing requirements

  // Test data - character class patterns
  private static final String CHARCLASS_MATCH = "Abc"; // matches (?<=[A-Z])c
  private static final String CHARCLASS_NO_MATCH = "abc"; // doesn't match (?<=[A-Z])c
  private static final String DIGIT_MATCH = "123"; // matches (?=[0-9])
  private static final String DIGIT_NO_MATCH = "abc"; // doesn't match (?=[0-9])

  // Test data - complex patterns (may force NFA fallback)
  private static final String VAR_LOOKAHEAD_MATCH = "abc1"; // matches (?=\w*\d)
  private static final String VAR_LOOKAHEAD_NO_MATCH = "abc"; // doesn't match (?=\w*\d)
  private static final String ALT_MATCH = "ac"; // matches (?<=a|b)c
  private static final String ALT_NO_MATCH = "xc"; // doesn't match (?<=a|b)c

  @Setup
  public void setup() {
    // JDK patterns - simple literals (DFA)
    jdkPositiveLookahead = Pattern.compile("a(?=bc)");
    jdkNegativeLookahead = Pattern.compile("a(?!bc)");
    jdkPositiveLookbehind = Pattern.compile("(?<=ab)c");
    jdkNegativeLookbehind = Pattern.compile("(?<!ab)c");
    jdkPasswordValidation = Pattern.compile("(?=.*[A-Z])(?=.*\\d)(?=.*[!@#$%]).{8,}");

    // JDK patterns - character classes (DFA)
    jdkCharClassLookbehind = Pattern.compile("(?<=[A-Z])c");
    jdkCharClassLookahead = Pattern.compile("(?=[0-9])");

    // JDK patterns - complex (may force NFA fallback)
    jdkVariableWidthLookahead = Pattern.compile("(?=\\w*\\d)");
    jdkAlternationLookbehind = Pattern.compile("(?<=a|b)c");

    // Reggie patterns
    patterns = Reggie.patterns(AssertionPatterns.class);
  }

  // Positive lookahead benchmarks
  @Benchmark
  public boolean reggiePositiveLookaheadMatch() {
    return patterns.positiveLookahead().find(LOOKAHEAD_MATCH);
  }

  @Benchmark
  public boolean jdkPositiveLookaheadMatch() {
    return jdkPositiveLookahead.matcher(LOOKAHEAD_MATCH).find();
  }

  @Benchmark
  public boolean reggiePositiveLookaheadNoMatch() {
    return patterns.positiveLookahead().find(LOOKAHEAD_NO_MATCH);
  }

  @Benchmark
  public boolean jdkPositiveLookaheadNoMatch() {
    return jdkPositiveLookahead.matcher(LOOKAHEAD_NO_MATCH).find();
  }

  // Negative lookahead benchmarks
  @Benchmark
  public boolean reggieNegativeLookaheadMatch() {
    return patterns.negativeLookahead().find(LOOKAHEAD_NO_MATCH);
  }

  @Benchmark
  public boolean jdkNegativeLookaheadMatch() {
    return jdkNegativeLookahead.matcher(LOOKAHEAD_NO_MATCH).find();
  }

  // Positive lookbehind benchmarks
  @Benchmark
  public boolean reggiePositiveLookbehindMatch() {
    return patterns.positiveLookbehind().find(LOOKBEHIND_MATCH);
  }

  @Benchmark
  public boolean jdkPositiveLookbehindMatch() {
    return jdkPositiveLookbehind.matcher(LOOKBEHIND_MATCH).find();
  }

  @Benchmark
  public boolean reggiePositiveLookbehindNoMatch() {
    return patterns.positiveLookbehind().find(LOOKBEHIND_NO_MATCH);
  }

  @Benchmark
  public boolean jdkPositiveLookbehindNoMatch() {
    return jdkPositiveLookbehind.matcher(LOOKBEHIND_NO_MATCH).find();
  }

  // Negative lookbehind benchmarks
  @Benchmark
  public boolean reggieNegativeLookbehindMatch() {
    return patterns.negativeLookbehind().find(LOOKBEHIND_NO_MATCH);
  }

  @Benchmark
  public boolean jdkNegativeLookbehindMatch() {
    return jdkNegativeLookbehind.matcher(LOOKBEHIND_NO_MATCH).find();
  }

  // Complex pattern: password validation with multiple lookaheads
  @Benchmark
  public boolean reggiePasswordValidationMatch() {
    return patterns.passwordValidation().matches(PASSWORD_VALID);
  }

  @Benchmark
  public boolean jdkPasswordValidationMatch() {
    return jdkPasswordValidation.matcher(PASSWORD_VALID).matches();
  }

  @Benchmark
  public boolean reggiePasswordValidationNoMatch() {
    return patterns.passwordValidation().matches(PASSWORD_INVALID);
  }

  @Benchmark
  public boolean jdkPasswordValidationNoMatch() {
    return jdkPasswordValidation.matcher(PASSWORD_INVALID).matches();
  }

  // Character class lookbehind benchmarks (DFA-friendly)
  @Benchmark
  public boolean reggieCharClassLookbehindMatch() {
    return patterns.charClassLookbehind().find(CHARCLASS_MATCH);
  }

  @Benchmark
  public boolean jdkCharClassLookbehindMatch() {
    return jdkCharClassLookbehind.matcher(CHARCLASS_MATCH).find();
  }

  @Benchmark
  public boolean reggieCharClassLookbehindNoMatch() {
    return patterns.charClassLookbehind().find(CHARCLASS_NO_MATCH);
  }

  @Benchmark
  public boolean jdkCharClassLookbehindNoMatch() {
    return jdkCharClassLookbehind.matcher(CHARCLASS_NO_MATCH).find();
  }

  // Character class lookahead benchmarks (DFA-friendly)
  @Benchmark
  public boolean reggieCharClassLookaheadMatch() {
    return patterns.charClassLookahead().find(DIGIT_MATCH);
  }

  @Benchmark
  public boolean jdkCharClassLookaheadMatch() {
    return jdkCharClassLookahead.matcher(DIGIT_MATCH).find();
  }

  @Benchmark
  public boolean reggieCharClassLookaheadNoMatch() {
    return patterns.charClassLookahead().find(DIGIT_NO_MATCH);
  }

  @Benchmark
  public boolean jdkCharClassLookaheadNoMatch() {
    return jdkCharClassLookahead.matcher(DIGIT_NO_MATCH).find();
  }

  // Variable-width lookahead benchmarks (may force NFA fallback)
  @Benchmark
  public boolean reggieVariableWidthLookaheadMatch() {
    return patterns.variableWidthLookahead().find(VAR_LOOKAHEAD_MATCH);
  }

  @Benchmark
  public boolean jdkVariableWidthLookaheadMatch() {
    return jdkVariableWidthLookahead.matcher(VAR_LOOKAHEAD_MATCH).find();
  }

  @Benchmark
  public boolean reggieVariableWidthLookaheadNoMatch() {
    return patterns.variableWidthLookahead().find(VAR_LOOKAHEAD_NO_MATCH);
  }

  @Benchmark
  public boolean jdkVariableWidthLookaheadNoMatch() {
    return jdkVariableWidthLookahead.matcher(VAR_LOOKAHEAD_NO_MATCH).find();
  }

  // Alternation lookbehind benchmarks (NFA fallback)
  @Benchmark
  public boolean reggieAlternationLookbehindMatch() {
    return patterns.alternationLookbehind().find(ALT_MATCH);
  }

  @Benchmark
  public boolean jdkAlternationLookbehindMatch() {
    return jdkAlternationLookbehind.matcher(ALT_MATCH).find();
  }

  @Benchmark
  public boolean reggieAlternationLookbehindNoMatch() {
    return patterns.alternationLookbehind().find(ALT_NO_MATCH);
  }

  @Benchmark
  public boolean jdkAlternationLookbehindNoMatch() {
    return jdkAlternationLookbehind.matcher(ALT_NO_MATCH).find();
  }
}
