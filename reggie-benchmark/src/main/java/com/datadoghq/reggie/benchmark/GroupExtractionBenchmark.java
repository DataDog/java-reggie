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

import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Consolidated JMH benchmark for capturing group extraction across pattern types. Compares Reggie's
 * group extraction performance with JDK's Pattern.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class GroupExtractionBenchmark {

  // Phone pattern with 3 capturing groups
  private static final String PHONE_PATTERN = "(\\d{3})-(\\d{3})-(\\d{4})";
  private static final String PHONE_INPUT = "123-456-7890";

  // Email pattern with 2 capturing groups (user and domain)
  private static final String EMAIL_PATTERN = "([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})";
  private static final String EMAIL_INPUT = "user@example.com";

  // Simple single group pattern
  private static final String DIGITS_PATTERN = "(\\d+)";
  private static final String DIGITS_INPUT = "12345";

  // Nested groups pattern (deeply nested)
  private static final String NESTED_PATTERN = "((((x))))";
  private static final String NESTED_INPUT = "x";

  // Repeating groups pattern (last iteration captured)
  private static final String REPEATING_PATTERN = "(a+|b)+";
  private static final String REPEATING_INPUT = "aaaabaaab";

  // Alternation with variable-length groups
  private static final String ALTERNATION_PATTERN = "(fo|foo)";
  private static final String ALTERNATION_INPUT_SHORT = "fo";
  private static final String ALTERNATION_INPUT_LONG = "foo";

  // Find pattern (not anchored, for findMatch testing)
  private static final String FIND_PATTERN = "(\\d{3})-(\\d{3})-(\\d{4})";
  private static final String FIND_INPUT = "Call me at 123-456-7890 or 987-654-3210";

  private Pattern jdkPhonePattern;
  private Pattern jdkEmailPattern;
  private Pattern jdkDigitsPattern;
  private Pattern jdkNestedPattern;
  private Pattern jdkRepeatingPattern;
  private Pattern jdkAlternationPattern;
  private Pattern jdkFindPattern;

  // RE2J patterns (supports capturing groups)
  private com.google.re2j.Pattern re2jPhonePattern;
  private com.google.re2j.Pattern re2jEmailPattern;
  private com.google.re2j.Pattern re2jDigitsPattern;
  private com.google.re2j.Pattern re2jNestedPattern;
  private com.google.re2j.Pattern re2jRepeatingPattern;
  private com.google.re2j.Pattern re2jAlternationPattern;
  private com.google.re2j.Pattern re2jFindPattern;

  private ReggieMatcher reggiePhoneMatcher;
  private ReggieMatcher reggieEmailMatcher;
  private ReggieMatcher reggieDigitsMatcher;
  private ReggieMatcher reggieNestedMatcher;
  private ReggieMatcher reggieRepeatingMatcher;
  private ReggieMatcher reggieAlternationMatcher;
  private ReggieMatcher reggieFindMatcher;

  @Setup
  public void setup() {
    jdkPhonePattern = Pattern.compile(PHONE_PATTERN);
    jdkEmailPattern = Pattern.compile(EMAIL_PATTERN);
    jdkDigitsPattern = Pattern.compile(DIGITS_PATTERN);
    jdkNestedPattern = Pattern.compile(NESTED_PATTERN);
    jdkRepeatingPattern = Pattern.compile(REPEATING_PATTERN);
    jdkAlternationPattern = Pattern.compile(ALTERNATION_PATTERN);
    jdkFindPattern = Pattern.compile(FIND_PATTERN);

    reggiePhoneMatcher = RuntimeCompiler.compile(PHONE_PATTERN);
    reggieEmailMatcher = RuntimeCompiler.compile(EMAIL_PATTERN);
    reggieDigitsMatcher = RuntimeCompiler.compile(DIGITS_PATTERN);
    reggieNestedMatcher = RuntimeCompiler.compile(NESTED_PATTERN);
    reggieRepeatingMatcher = RuntimeCompiler.compile(REPEATING_PATTERN);
    reggieAlternationMatcher = RuntimeCompiler.compile(ALTERNATION_PATTERN);
    reggieFindMatcher = RuntimeCompiler.compile(FIND_PATTERN);

    // RE2J patterns
    re2jPhonePattern = com.google.re2j.Pattern.compile(PHONE_PATTERN);
    re2jEmailPattern = com.google.re2j.Pattern.compile(EMAIL_PATTERN);
    re2jDigitsPattern = com.google.re2j.Pattern.compile(DIGITS_PATTERN);
    re2jNestedPattern = com.google.re2j.Pattern.compile(NESTED_PATTERN);
    re2jRepeatingPattern = com.google.re2j.Pattern.compile(REPEATING_PATTERN);
    re2jAlternationPattern = com.google.re2j.Pattern.compile(ALTERNATION_PATTERN);
    re2jFindPattern = com.google.re2j.Pattern.compile(FIND_PATTERN);
  }

  // ===== Phone Pattern (3 groups) =====

  @Benchmark
  public String[] reggiePhoneGroups() {
    MatchResult result = reggiePhoneMatcher.match(PHONE_INPUT);
    if (result != null) {
      return new String[] {result.group(1), result.group(2), result.group(3)};
    }
    return null;
  }

  @Benchmark
  public String[] jdkPhoneGroups() {
    Matcher m = jdkPhonePattern.matcher(PHONE_INPUT);
    if (m.matches()) {
      return new String[] {m.group(1), m.group(2), m.group(3)};
    }
    return null;
  }

  // ===== Email Pattern (2 groups) =====

  @Benchmark
  public String[] reggieEmailGroups() {
    MatchResult result = reggieEmailMatcher.match(EMAIL_INPUT);
    if (result != null) {
      return new String[] {result.group(1), result.group(2)};
    }
    return null;
  }

  @Benchmark
  public String[] jdkEmailGroups() {
    Matcher m = jdkEmailPattern.matcher(EMAIL_INPUT);
    if (m.matches()) {
      return new String[] {m.group(1), m.group(2)};
    }
    return null;
  }

  // ===== Digits Pattern (1 group) =====

  @Benchmark
  public String reggieDigitsGroup() {
    MatchResult result = reggieDigitsMatcher.match(DIGITS_INPUT);
    if (result != null) {
      return result.group(1);
    }
    return null;
  }

  @Benchmark
  public String jdkDigitsGroup() {
    Matcher m = jdkDigitsPattern.matcher(DIGITS_INPUT);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  // ===== Nested Groups Pattern (4 groups) =====

  @Benchmark
  public String[] reggieNestedGroups() {
    MatchResult result = reggieNestedMatcher.match(NESTED_INPUT);
    if (result != null) {
      return new String[] {result.group(1), result.group(2), result.group(3), result.group(4)};
    }
    return null;
  }

  @Benchmark
  public String[] jdkNestedGroups() {
    Matcher m = jdkNestedPattern.matcher(NESTED_INPUT);
    if (m.matches()) {
      return new String[] {m.group(1), m.group(2), m.group(3), m.group(4)};
    }
    return null;
  }

  // ===== Repeating Groups Pattern (1 group, last iteration) =====

  @Benchmark
  public String reggieRepeatingGroup() {
    MatchResult result = reggieRepeatingMatcher.match(REPEATING_INPUT);
    if (result != null) {
      return result.group(1);
    }
    return null;
  }

  @Benchmark
  public String jdkRepeatingGroup() {
    Matcher m = jdkRepeatingPattern.matcher(REPEATING_INPUT);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  // ===== Alternation Pattern (short alternative) =====

  @Benchmark
  public String reggieAlternationShort() {
    MatchResult result = reggieAlternationMatcher.match(ALTERNATION_INPUT_SHORT);
    if (result != null) {
      return result.group(1);
    }
    return null;
  }

  @Benchmark
  public String jdkAlternationShort() {
    Matcher m = jdkAlternationPattern.matcher(ALTERNATION_INPUT_SHORT);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  // ===== Alternation Pattern (long alternative) =====

  @Benchmark
  public String reggieAlternationLong() {
    MatchResult result = reggieAlternationMatcher.match(ALTERNATION_INPUT_LONG);
    if (result != null) {
      return result.group(1);
    }
    return null;
  }

  @Benchmark
  public String jdkAlternationLong() {
    Matcher m = jdkAlternationPattern.matcher(ALTERNATION_INPUT_LONG);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  // ===== Find Operation with Groups (findMatch vs find + group) =====

  @Benchmark
  public String[] reggieFindWithGroups() {
    MatchResult result = reggieFindMatcher.findMatch(FIND_INPUT);
    if (result != null) {
      return new String[] {result.group(1), result.group(2), result.group(3)};
    }
    return null;
  }

  @Benchmark
  public String[] jdkFindWithGroups() {
    Matcher m = jdkFindPattern.matcher(FIND_INPUT);
    if (m.find()) {
      return new String[] {m.group(1), m.group(2), m.group(3)};
    }
    return null;
  }

  // ===== RE2J Benchmarks =====

  @Benchmark
  public String[] re2jPhoneGroups() {
    com.google.re2j.Matcher m = re2jPhonePattern.matcher(PHONE_INPUT);
    if (m.matches()) {
      return new String[] {m.group(1), m.group(2), m.group(3)};
    }
    return null;
  }

  @Benchmark
  public String[] re2jEmailGroups() {
    com.google.re2j.Matcher m = re2jEmailPattern.matcher(EMAIL_INPUT);
    if (m.matches()) {
      return new String[] {m.group(1), m.group(2)};
    }
    return null;
  }

  @Benchmark
  public String re2jDigitsGroup() {
    com.google.re2j.Matcher m = re2jDigitsPattern.matcher(DIGITS_INPUT);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  @Benchmark
  public String[] re2jNestedGroups() {
    com.google.re2j.Matcher m = re2jNestedPattern.matcher(NESTED_INPUT);
    if (m.matches()) {
      return new String[] {m.group(1), m.group(2), m.group(3), m.group(4)};
    }
    return null;
  }

  @Benchmark
  public String re2jRepeatingGroup() {
    com.google.re2j.Matcher m = re2jRepeatingPattern.matcher(REPEATING_INPUT);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  @Benchmark
  public String re2jAlternationShort() {
    com.google.re2j.Matcher m = re2jAlternationPattern.matcher(ALTERNATION_INPUT_SHORT);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  @Benchmark
  public String re2jAlternationLong() {
    com.google.re2j.Matcher m = re2jAlternationPattern.matcher(ALTERNATION_INPUT_LONG);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  @Benchmark
  public String[] re2jFindWithGroups() {
    com.google.re2j.Matcher m = re2jFindPattern.matcher(FIND_INPUT);
    if (m.find()) {
      return new String[] {m.group(1), m.group(2), m.group(3)};
    }
    return null;
  }
}
