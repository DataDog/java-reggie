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
 * JMH benchmark for complex NFA patterns not covered by existing benchmarks. Tests edge cases like
 * very long alternations, deeply nested quantifiers, mixed complex patterns, and real-world
 * patterns.
 *
 * <p>NOTE: Some patterns may use DFA strategies if Reggie can optimize them: - Nested quantifiers:
 * DFA_UNROLLED (2 states) - JSON string: DFA_UNROLLED (6 states) - IPv6: DFA_SWITCH (40 states) -
 * Lookahead+backref: HYBRID_DFA_LOOKAHEAD
 *
 * <p>This is expected and demonstrates Reggie's optimization capabilities.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(3)
public class ComplexNFABenchmark {

  // JDK patterns
  private Pattern jdkLongAlternation;
  private Pattern jdkNestedQuantifiers;
  private Pattern jdkMultipleStars;
  private Pattern jdkLookaheadBackref;
  private Pattern jdkLookbehindBackref;
  private Pattern jdkJsonString;
  private Pattern jdkIPv6;
  private Pattern jdkComplexEmail;

  // Reggie patterns
  private ReggieMatcher reggieLongAlternation;
  private ReggieMatcher reggieNestedQuantifiers;
  private ReggieMatcher reggieMultipleStars;
  private ReggieMatcher reggieLookaheadBackref;
  private ReggieMatcher reggieLookbehindBackref;
  private ReggieMatcher reggieJsonString;
  private ReggieMatcher reggieIPv6;
  private ReggieMatcher reggieComplexEmail;

  // Test data - Long alternation (100 keywords)
  private static final String LONG_ALT_MATCH = "keyword50";
  private static final String LONG_ALT_NO_MATCH = "notakeyword";

  // Test data - Nested quantifiers
  private static final String NESTED_QUANT_MATCH = "aaaa";
  private static final String NESTED_QUANT_NO_MATCH = "b";

  // Test data - Multiple stars
  private static final String MULTI_STAR_MATCH = "aabbccddee";
  private static final String MULTI_STAR_NO_MATCH = "xyz";

  // Test data - Lookahead + backreference
  private static final String LOOKAHEAD_BACKREF_MATCH = "Test word word test";
  private static final String LOOKAHEAD_BACKREF_NO_MATCH = "Test word different test";

  // Test data - Lookbehind + backreference
  private static final String LOOKBEHIND_BACKREF_MATCH = "prefixhellohellosuffix";
  private static final String LOOKBEHIND_BACKREF_NO_MATCH = "prefixhelloworld suffix";

  // Test data - JSON string
  private static final String JSON_STRING_MATCH = "\"hello world\"";
  private static final String JSON_STRING_ESCAPED_MATCH = "\"escaped \\\" quote\"";
  private static final String JSON_STRING_NO_MATCH = "\"unclosed";

  // Test data - IPv6
  private static final String IPV6_MATCH = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
  private static final String IPV6_SHORT_MATCH = "2001:db8:85a3:0:0:8a2e:370:7334";
  private static final String IPV6_NO_MATCH = "192.168.1.1";

  // Test data - Complex email with lookahead
  private static final String COMPLEX_EMAIL_MATCH = "user@example.com";
  private static final String COMPLEX_EMAIL_LONG_MATCH =
      "very.long.email.address.within.limit@example.com";
  private static final String COMPLEX_EMAIL_NO_MATCH = "user@";

  @Setup
  public void setup() {
    // Build long alternation pattern (100 keywords)
    StringBuilder longAlt = new StringBuilder();
    for (int i = 1; i <= 100; i++) {
      if (i > 1) longAlt.append("|");
      longAlt.append("keyword").append(i);
    }

    // JDK patterns
    jdkLongAlternation = Pattern.compile(longAlt.toString());
    jdkNestedQuantifiers = Pattern.compile("((((a+)+)+)+)");
    jdkMultipleStars = Pattern.compile("(a*b*c*d*e*)");
    jdkLookaheadBackref = Pattern.compile("(?=.*[A-Z]).*\\b(\\w+)\\s+\\1\\b");
    jdkLookbehindBackref = Pattern.compile("(?<=prefix)(\\w+)\\1(?=suffix)");
    jdkJsonString = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    jdkIPv6 = Pattern.compile("([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}");
    jdkComplexEmail =
        Pattern.compile("(?=.{1,64}@)[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    // Reggie patterns
    reggieLongAlternation = RuntimeCompiler.compile(longAlt.toString());
    reggieNestedQuantifiers = RuntimeCompiler.compile("((((a+)+)+)+)");
    reggieMultipleStars = RuntimeCompiler.compile("(a*b*c*d*e*)");
    reggieLookaheadBackref = RuntimeCompiler.compile("(?=.*[A-Z]).*\\b(\\w+)\\s+\\1\\b");
    reggieLookbehindBackref = RuntimeCompiler.compile("(?<=prefix)(\\w+)\\1(?=suffix)");
    reggieJsonString = RuntimeCompiler.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    reggieIPv6 = RuntimeCompiler.compile("([0-9a-f]{1,4}:){7}[0-9a-f]{1,4}");
    reggieComplexEmail =
        RuntimeCompiler.compile("(?=.{1,64}@)[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
  }

  // ===== Long Alternation Benchmarks =====

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

  // ===== Nested Quantifiers Benchmarks =====

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

  // ===== Multiple Stars Benchmarks =====

  @Benchmark
  public boolean reggieMultipleStarsMatch() {
    return reggieMultipleStars.matches(MULTI_STAR_MATCH);
  }

  @Benchmark
  public boolean jdkMultipleStarsMatch() {
    return jdkMultipleStars.matcher(MULTI_STAR_MATCH).matches();
  }

  @Benchmark
  public boolean reggieMultipleStarsNoMatch() {
    return reggieMultipleStars.matches(MULTI_STAR_NO_MATCH);
  }

  @Benchmark
  public boolean jdkMultipleStarsNoMatch() {
    return jdkMultipleStars.matcher(MULTI_STAR_NO_MATCH).matches();
  }

  // ===== Lookahead + Backreference Benchmarks =====

  @Benchmark
  public boolean reggieLookaheadBackrefMatch() {
    return reggieLookaheadBackref.find(LOOKAHEAD_BACKREF_MATCH);
  }

  @Benchmark
  public boolean jdkLookaheadBackrefMatch() {
    return jdkLookaheadBackref.matcher(LOOKAHEAD_BACKREF_MATCH).find();
  }

  @Benchmark
  public boolean reggieLookaheadBackrefNoMatch() {
    return reggieLookaheadBackref.find(LOOKAHEAD_BACKREF_NO_MATCH);
  }

  @Benchmark
  public boolean jdkLookaheadBackrefNoMatch() {
    return jdkLookaheadBackref.matcher(LOOKAHEAD_BACKREF_NO_MATCH).find();
  }

  // ===== Lookbehind + Backreference Benchmarks =====

  @Benchmark
  public boolean reggieLookbehindBackrefMatch() {
    return reggieLookbehindBackref.find(LOOKBEHIND_BACKREF_MATCH);
  }

  @Benchmark
  public boolean jdkLookbehindBackrefMatch() {
    return jdkLookbehindBackref.matcher(LOOKBEHIND_BACKREF_MATCH).find();
  }

  @Benchmark
  public boolean reggieLookbehindBackrefNoMatch() {
    return reggieLookbehindBackref.find(LOOKBEHIND_BACKREF_NO_MATCH);
  }

  @Benchmark
  public boolean jdkLookbehindBackrefNoMatch() {
    return jdkLookbehindBackref.matcher(LOOKBEHIND_BACKREF_NO_MATCH).find();
  }

  // ===== JSON String Benchmarks =====

  @Benchmark
  public boolean reggieJsonStringMatch() {
    return reggieJsonString.matches(JSON_STRING_MATCH);
  }

  @Benchmark
  public boolean jdkJsonStringMatch() {
    return jdkJsonString.matcher(JSON_STRING_MATCH).matches();
  }

  @Benchmark
  public boolean reggieJsonStringEscapedMatch() {
    return reggieJsonString.matches(JSON_STRING_ESCAPED_MATCH);
  }

  @Benchmark
  public boolean jdkJsonStringEscapedMatch() {
    return jdkJsonString.matcher(JSON_STRING_ESCAPED_MATCH).matches();
  }

  @Benchmark
  public boolean reggieJsonStringNoMatch() {
    return reggieJsonString.matches(JSON_STRING_NO_MATCH);
  }

  @Benchmark
  public boolean jdkJsonStringNoMatch() {
    return jdkJsonString.matcher(JSON_STRING_NO_MATCH).matches();
  }

  // ===== IPv6 Benchmarks =====

  @Benchmark
  public boolean reggieIPv6Match() {
    return reggieIPv6.matches(IPV6_MATCH);
  }

  @Benchmark
  public boolean jdkIPv6Match() {
    return jdkIPv6.matcher(IPV6_MATCH).matches();
  }

  @Benchmark
  public boolean reggieIPv6ShortMatch() {
    return reggieIPv6.matches(IPV6_SHORT_MATCH);
  }

  @Benchmark
  public boolean jdkIPv6ShortMatch() {
    return jdkIPv6.matcher(IPV6_SHORT_MATCH).matches();
  }

  @Benchmark
  public boolean reggieIPv6NoMatch() {
    return reggieIPv6.matches(IPV6_NO_MATCH);
  }

  @Benchmark
  public boolean jdkIPv6NoMatch() {
    return jdkIPv6.matcher(IPV6_NO_MATCH).matches();
  }

  // ===== Complex Email Benchmarks =====

  @Benchmark
  public boolean reggieComplexEmailMatch() {
    return reggieComplexEmail.matches(COMPLEX_EMAIL_MATCH);
  }

  @Benchmark
  public boolean jdkComplexEmailMatch() {
    return jdkComplexEmail.matcher(COMPLEX_EMAIL_MATCH).matches();
  }

  @Benchmark
  public boolean reggieComplexEmailLongMatch() {
    return reggieComplexEmail.matches(COMPLEX_EMAIL_LONG_MATCH);
  }

  @Benchmark
  public boolean jdkComplexEmailLongMatch() {
    return jdkComplexEmail.matcher(COMPLEX_EMAIL_LONG_MATCH).matches();
  }

  @Benchmark
  public boolean reggieComplexEmailNoMatch() {
    return reggieComplexEmail.matches(COMPLEX_EMAIL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkComplexEmailNoMatch() {
    return jdkComplexEmail.matcher(COMPLEX_EMAIL_NO_MATCH).matches();
  }
}
