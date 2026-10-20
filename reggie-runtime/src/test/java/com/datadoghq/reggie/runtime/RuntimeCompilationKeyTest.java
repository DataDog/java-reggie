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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.ReggieOption;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cache-identity regressions for literal NUL patterns. Pattern-side NUL is supported data, not a
 * key separator ({@code kb/find-nul-pattern-truncation}).
 */
class RuntimeCompilationKeyTest {

  private static final ReggieOptions NAMED_ONLY =
      ReggieOptions.builder().enable(ReggieOption.CAPTURE_NAMED_ONLY).build();
  private static final ReggieOptions ALLOW_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  private static final String STATELESS_BASE = "a";
  private static final String STATELESS_SUFFIX = "a\0CAPTURE_NAMED_ONLY";
  private static final String MUTABLE_BASE = "(a|b)*(c)";
  private static final String MUTABLE_SUFFIX = MUTABLE_BASE + "\0ALLOW_JDK_FALLBACK";
  private static final String BACKTRACK_BASE = "(.*) \\((.*)\\)";
  private static final String BACKTRACK_SUFFIX = BACKTRACK_BASE + "\0ALLOW_JDK_FALLBACK";

  @BeforeEach
  @AfterEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void statelessCacheIdentityIsStructuralInBothOrders() {
    assertPairInBothOrders(
        STATELESS_BASE,
        NAMED_ONLY,
        STATELESS_SUFFIX,
        ReggieOptions.DEFAULT,
        STATELESS_BASE,
        STATELESS_SUFFIX,
        true);
  }

  @Test
  void mutableFactoryCacheIdentityIsStructuralInBothOrders() {
    assertMutableFixture(MUTABLE_BASE, ALLOW_FALLBACK);
    RuntimeCompiler.clearCache();
    assertMutableFixture(MUTABLE_SUFFIX, ReggieOptions.DEFAULT);
    RuntimeCompiler.clearCache();

    assertPairInBothOrders(
        MUTABLE_BASE,
        ALLOW_FALLBACK,
        MUTABLE_SUFFIX,
        ReggieOptions.DEFAULT,
        "abc",
        "abc\0ALLOW_JDK_FALLBACK",
        false);
  }

  @Test
  void runtimeBacktrackCacheIdentityIsStructuralInBothOrders() {
    assertBacktrackRoute(BACKTRACK_BASE);
    assertBacktrackRoute(BACKTRACK_SUFFIX);
    RuntimeCompiler.clearCache();

    assertBacktrackPair(false);
    RuntimeCompiler.clearCache();
    assertBacktrackPair(true);
  }

  private static void assertPairInBothOrders(
      String basePattern,
      ReggieOptions baseOptions,
      String suffixPattern,
      ReggieOptions suffixOptions,
      String baseInput,
      String suffixInput,
      boolean assertCacheSize) {
    assertPair(
        basePattern,
        baseOptions,
        suffixPattern,
        suffixOptions,
        baseInput,
        suffixInput,
        assertCacheSize);
    RuntimeCompiler.clearCache();
    assertPair(
        suffixPattern,
        suffixOptions,
        basePattern,
        baseOptions,
        suffixInput,
        baseInput,
        assertCacheSize);
  }

  private static void assertPair(
      String firstPattern,
      ReggieOptions firstOptions,
      String secondPattern,
      ReggieOptions secondOptions,
      String firstInput,
      String secondInput,
      boolean assertCacheSize) {
    ReggieMatcher first = RuntimeCompiler.compile(firstPattern, firstOptions);
    ReggieMatcher second = RuntimeCompiler.compile(secondPattern, secondOptions);

    assertTrue(first.matches(firstInput));
    assertFalse(first.matches(secondInput));
    assertTrue(second.matches(secondInput));
    assertFalse(second.matches(firstInput));
    if (assertCacheSize) {
      assertEquals(2, RuntimeCompiler.cacheSize());
    }
  }

  private static void assertMutableFixture(String pattern, ReggieOptions options) {
    ReggieMatcher first = RuntimeCompiler.compile(pattern, options);
    ReggieMatcher second = RuntimeCompiler.compile(pattern, options);
    assertNotSame(first, second);
    assertEquals(HybridMatcher.class, EngineRouting.engineClass(first));
  }

  private static void assertBacktrackRoute(String pattern) {
    String routing = RuntimeCompiler.describeRouting(pattern).routing;
    assertTrue(routing.startsWith("BACKREF_BACKTRACK"), pattern + " -> " + routing);
    RuntimeCompiler.clearCache();
  }

  private static void assertBacktrackPair(boolean suffixFirst) {
    ReggieMatcher base;
    ReggieMatcher suffix;
    if (suffixFirst) {
      suffix = RuntimeCompiler.compile(BACKTRACK_SUFFIX, ReggieOptions.DEFAULT);
      base = RuntimeCompiler.compile(BACKTRACK_BASE, ALLOW_FALLBACK);
    } else {
      base = RuntimeCompiler.compile(BACKTRACK_BASE, ALLOW_FALLBACK);
      suffix = RuntimeCompiler.compile(BACKTRACK_SUFFIX, ReggieOptions.DEFAULT);
    }

    String baseInput = "foo (bar)";
    String suffixInput = baseInput + "\0ALLOW_JDK_FALLBACK";
    assertWholeMatchEqualsJdk(BACKTRACK_BASE, baseInput, base);
    assertWholeMatchEqualsJdk(BACKTRACK_SUFFIX, suffixInput, suffix);
    assertFalse(base.matches(suffixInput));
    assertNull(base.match(suffixInput));
    assertFalse(suffix.matches(baseInput));
    assertNull(suffix.match(baseInput));
  }

  private static void assertWholeMatchEqualsJdk(
      String pattern, String input, ReggieMatcher reggieMatcher) {
    Matcher jdk = Pattern.compile(pattern).matcher(input);
    boolean expected = jdk.matches();
    assertEquals(expected, reggieMatcher.matches(input));
    MatchResult actual = reggieMatcher.match(input);
    assertNotNull(actual);
    assertEquals(jdk.groupCount(), actual.groupCount());
    for (int group = 0; group <= jdk.groupCount(); group++) {
      assertEquals(jdk.start(group), actual.start(group), "start(" + group + ")");
      assertEquals(jdk.end(group), actual.end(group), "end(" + group + ")");
      assertEquals(jdk.group(group), actual.group(group), "group(" + group + ")");
    }
  }
}
