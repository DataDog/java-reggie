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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LinearTokenSequenceMatcherConcurrencyTest {
  private static final ReggieOptions NAMED_ONLY = ReggieOptions.builder().namedOnly().build();
  private static final String WITH_OPTIONAL_CAPTURES =
      "10.202.82.195 - - [15/Mar/2019:19:45:35 -0700]  \"POST /config?x=y HTTP/1.1\" "
          + "200 17888";
  private static final String WITHOUT_OPTIONAL_CAPTURES =
      "2001:db8::1 - - [15/Mar/2019:19:45:35 -0700]  \"/health\" 200 -";

  @AfterEach
  void clearCache() {
    Reggie.clearCache();
  }

  @Test
  void cachedNamedOnlyLinearTokenSequenceMatcherIsSafeForConcurrentUse() throws Exception {
    Reggie.clearCache();
    String pattern = testResource("logs-grok-pattern-1.regex");
    Map<String, Integer> groupNumbers = groupNumbers(pattern);
    ReggieMatcher shared = Reggie.compile(pattern, NAMED_ONLY);
    ReggieMatcher second = Reggie.compile(pattern, NAMED_ONLY);
    int groupCount = shared.match(WITH_OPTIONAL_CAPTURES).groupCount();

    assertSame(shared, second);
    assertDelegateType(shared, LinearTokenSequenceMatcher.class);

    int threads = 16;
    int iterations = 1_000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

    for (int thread = 0; thread < threads; thread++) {
      executor.execute(
          () -> {
            ready.countDown();
            try {
              start.await();
              for (int iteration = 0; iteration < iterations; iteration++) {
                assertMatchWithOptionalCaptures(shared, groupNumbers);
                assertMatchWithoutOptionalCaptures(shared, groupNumbers);
                assertFailedMatchLeavesArraysUntouched(shared, groupCount);
                assertTrue(shared.find("noise " + WITH_OPTIONAL_CAPTURES));
                assertFalse(shared.find("noise malformed access log"));
              }
            } catch (Throwable failure) {
              failures.add(failure);
            } finally {
              done.countDown();
            }
          });
    }

    assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not become ready");
    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
    executor.shutdownNow();
    assertTrue(failures.isEmpty(), () -> "concurrent LTS failure: " + failures.peek());
  }

  @Test
  void cachedLinearTokenSequenceMatcherSafelyRollsBackNestedOptionalSequences() throws Exception {
    String pattern = "(?:a(?:b|)|)c";
    Reggie.clearCache();
    ReggieMatcher shared = Reggie.compile(pattern, NAMED_ONLY);

    assertSame(shared, Reggie.compile(pattern, NAMED_ONLY));
    assertDelegateType(shared, LinearTokenSequenceMatcher.class);

    int threads = 16;
    int iterations = 1_000;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

    for (int thread = 0; thread < threads; thread++) {
      executor.execute(
          () -> {
            ready.countDown();
            try {
              start.await();
              for (int iteration = 0; iteration < iterations; iteration++) {
                assertTrue(shared.matches("abc"));
                assertTrue(shared.matches("ac"));
                assertTrue(shared.matches("c"));
                assertFalse(shared.matches("ab"));
                assertNotNull(shared.match("abc"));
                assertNotNull(shared.match("ac"));
                assertNotNull(shared.match("c"));
                assertNull(shared.match("ab"));
                assertTrue(shared.find("noise abc"));
                assertFalse(shared.find("noise ab"));

                int[] starts = {17};
                int[] ends = {19};
                assertFalse(shared.matchInto("ab", starts, ends));
                assertEquals(17, starts[0]);
                assertEquals(19, ends[0]);
              }
            } catch (Throwable failure) {
              failures.add(failure);
            } finally {
              done.countDown();
            }
          });
    }

    assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not become ready");
    start.countDown();
    assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish");
    executor.shutdownNow();
    assertTrue(
        failures.isEmpty(), () -> "concurrent nested-optional LTS failure: " + failures.peek());
  }

  private static void assertMatchWithOptionalCaptures(
      ReggieMatcher matcher, Map<String, Integer> groupNumbers) {
    assertTrue(matcher.matches(WITH_OPTIONAL_CAPTURES));
    MatchResult result = matcher.match(WITH_OPTIONAL_CAPTURES);
    assertNotNull(result);
    assertEquals("POST", result.group("grok4"));
    assertEquals("/config?x=y", result.group("grok5"));
    assertEquals("1.1", result.group("grok6"));
    assertEquals("200", result.group("grok7"));

    int[] starts = new int[result.groupCount() + 1];
    int[] ends = new int[result.groupCount() + 1];
    assertTrue(matcher.matchInto(WITH_OPTIONAL_CAPTURES, starts, ends));
    assertEquals(
        "POST",
        WITH_OPTIONAL_CAPTURES.substring(
            starts[groupNumbers.get("grok4")], ends[groupNumbers.get("grok4")]));
    assertEquals(
        "1.1",
        WITH_OPTIONAL_CAPTURES.substring(
            starts[groupNumbers.get("grok6")], ends[groupNumbers.get("grok6")]));
  }

  private static void assertMatchWithoutOptionalCaptures(
      ReggieMatcher matcher, Map<String, Integer> groupNumbers) {
    assertTrue(matcher.matches(WITHOUT_OPTIONAL_CAPTURES));
    MatchResult result = matcher.match(WITHOUT_OPTIONAL_CAPTURES);
    assertNotNull(result);
    assertNull(result.group("grok4"));
    assertEquals("/health", result.group("grok5"));
    assertNull(result.group("grok6"));
    assertEquals("200", result.group("grok7"));

    int[] starts = new int[result.groupCount() + 1];
    int[] ends = new int[result.groupCount() + 1];
    assertTrue(matcher.matchInto(WITHOUT_OPTIONAL_CAPTURES, starts, ends));
    assertEquals(-1, starts[groupNumbers.get("grok4")]);
    assertEquals(-1, ends[groupNumbers.get("grok4")]);
    assertEquals(-1, starts[groupNumbers.get("grok6")]);
    assertEquals(-1, ends[groupNumbers.get("grok6")]);
  }

  private static void assertFailedMatchLeavesArraysUntouched(
      ReggieMatcher matcher, int groupCount) {
    String input = "not an access log";
    assertFalse(matcher.matches(input));
    assertNull(matcher.match(input));
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    Arrays.fill(starts, 17);
    Arrays.fill(ends, 19);
    assertFalse(matcher.matchInto(input, starts, ends));
    assertTrue(Arrays.stream(starts).allMatch(value -> value == 17));
    assertTrue(Arrays.stream(ends).allMatch(value -> value == 19));
  }

  private static Map<String, Integer> groupNumbers(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    parser.parse(pattern);
    return parser.getGroupNameMap();
  }

  private static String testResource(String name) throws IOException {
    String resource = "/com/datadoghq/reggie/runtime/" + name;
    try (InputStream input =
        LinearTokenSequenceMatcherConcurrencyTest.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException("missing test resource: " + resource);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void assertDelegateType(ReggieMatcher matcher, Class<?> expectedType)
      throws Exception {
    if (matcher.getClass() == expectedType) {
      return;
    }
    Field delegate = matcher.getClass().getDeclaredField("delegate");
    delegate.setAccessible(true);
    assertEquals(expectedType, delegate.get(matcher).getClass());
  }
}
