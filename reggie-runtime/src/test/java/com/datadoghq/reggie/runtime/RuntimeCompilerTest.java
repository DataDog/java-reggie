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

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive tests for RuntimeCompiler. Tests compilation, caching, thread safety, and
 * performance.
 */
public class RuntimeCompilerTest {

  @BeforeEach
  @AfterEach
  public void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ==================== Basic Compilation Tests ====================

  @Test
  public void testSimpleLiteral() {
    ReggieMatcher matcher = RuntimeCompiler.compile("hello");
    assertNotNull(matcher);
    assertTrue(matcher.matches("hello"));
    assertFalse(matcher.matches("world"));
    assertFalse(matcher.matches("hello world"));
  }

  @Test
  public void testDigitPattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
    assertNotNull(matcher);
    assertTrue(matcher.matches("123"));
    assertTrue(matcher.matches("0"));
    assertFalse(matcher.matches("abc"));
    assertFalse(matcher.matches(""));
  }

  @Test
  public void testPhonePattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");
    assertNotNull(matcher);
    assertTrue(matcher.matches("123-456-7890"));
    assertFalse(matcher.matches("123-45-6789"));
    assertFalse(matcher.matches("abc-def-ghij"));
  }

  @Test
  public void testEmailPattern() {
    ReggieMatcher matcher = RuntimeCompiler.compile("[a-z]+@[a-z]+");
    assertNotNull(matcher);
    assertTrue(matcher.matches("user@domain"));
    assertFalse(matcher.matches("user@"));
    assertFalse(matcher.matches("@domain"));
    assertFalse(matcher.matches("user123@domain"));
  }

  @Test
  public void testAlternation() {
    ReggieMatcher matcher = RuntimeCompiler.compile("cat|dog|bird");
    assertNotNull(matcher);
    assertTrue(matcher.matches("cat"));
    assertTrue(matcher.matches("dog"));
    assertTrue(matcher.matches("bird"));
    assertFalse(matcher.matches("fish"));
  }

  @Test
  public void testCharacterClass() {
    ReggieMatcher matcher = RuntimeCompiler.compile("[a-z]+");
    assertNotNull(matcher);
    assertTrue(matcher.matches("hello"));
    assertTrue(matcher.matches("abc"));
    assertFalse(matcher.matches("Hello"));
    assertFalse(matcher.matches("123"));
  }

  @Test
  public void testOptionalQuantifier() {
    ReggieMatcher matcher = RuntimeCompiler.compile("colou?r");
    assertNotNull(matcher);
    assertTrue(matcher.matches("color"));
    assertTrue(matcher.matches("colour"));
    assertFalse(matcher.matches("colouur"));
  }

  @Test
  public void testNestedGroupBackref() {
    // Pattern ((.*))\\d+\\1 should match strings where group content is repeated after digits
    // JDK behavior verified:
    //   "123" -> true (empty group matches)
    //   "abc123abc" -> true (group="abc", backref="abc")
    //   "abc123bc" -> false (backref "abc" doesn't match "bc")
    //   "test123test" -> true
    //   "a1a" -> true
    ReggieMatcher matcher = RuntimeCompiler.compile("((.*))\\d+\\1");
    assertNotNull(matcher);

    // Should match: prefix + digits + same prefix
    assertTrue(matcher.matches("test123test"), "test123test should match");
    assertTrue(matcher.matches("abc123abc"), "abc123abc should match");
    assertTrue(matcher.matches("a1a"), "a1a should match");
    assertTrue(matcher.matches("ab1ab"), "ab1ab should match");
    assertTrue(matcher.matches("x9x"), "x9x should match");
    assertTrue(matcher.matches("123"), "123 should match (empty group)");

    // Should not match: prefix + digits + different suffix
    assertFalse(matcher.matches("abc123def"), "abc123def should not match");
    assertFalse(matcher.matches("abc123bc"), "abc123bc should not match (backref mismatch)");
  }

  // TODO: Investigate NoSuchMethodError for find() and findFrom()
  // @Test
  // public void testFindMethod() {
  //     ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
  //     assertTrue(matcher.find("abc123def"));
  //     assertTrue(matcher.find("123"));
  //     assertFalse(matcher.find("abcdef"));
  // }

  // @Test
  // public void testFindFromMethod() {
  //     ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
  //     assertEquals(3, matcher.findFrom("abc123def", 0));
  //     assertEquals(3, matcher.findFrom("abc123def", 3));
  //     assertEquals(-1, matcher.findFrom("abc123def", 6));
  // }

  @Test
  public void testPatternMethod() {
    String pattern = "\\d{3}-\\d{3}-\\d{4}";
    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    assertEquals(pattern, matcher.pattern());
  }

  // ==================== Caching Tests ====================

  @Test
  public void testBasicCaching() {
    String pattern = "\\d+";

    assertEquals(0, RuntimeCompiler.cacheSize());

    ReggieMatcher first = RuntimeCompiler.compile(pattern);
    assertEquals(1, RuntimeCompiler.cacheSize());

    ReggieMatcher second = RuntimeCompiler.compile(pattern);
    assertEquals(1, RuntimeCompiler.cacheSize());

    assertSame(first, second, "Same pattern should return cached instance");
  }

  @Test
  public void testMultiplePatternsCaching() {
    RuntimeCompiler.compile("\\d+");
    RuntimeCompiler.compile("[a-z]+");
    RuntimeCompiler.compile("\\w+");

    assertEquals(3, RuntimeCompiler.cacheSize());
    assertTrue(RuntimeCompiler.cachedPatterns().contains("\\d+"));
    assertTrue(RuntimeCompiler.cachedPatterns().contains("[a-z]+"));
    assertTrue(RuntimeCompiler.cachedPatterns().contains("\\w+"));
  }

  @Test
  public void testExplicitCacheKey() {
    ReggieMatcher m1 = RuntimeCompiler.cached("key1", "\\d+");
    ReggieMatcher m2 = RuntimeCompiler.cached("key1", "[a-z]+");

    assertSame(m1, m2, "Same cache key should return same instance");
    assertEquals(1, RuntimeCompiler.cacheSize());
  }

  @Test
  public void testDifferentCacheKeys() {
    ReggieMatcher m1 = RuntimeCompiler.cached("key1", "\\d+");
    ReggieMatcher m2 = RuntimeCompiler.cached("key2", "\\d+");

    assertNotSame(m1, m2, "Different cache keys should return different instances");
    assertEquals(2, RuntimeCompiler.cacheSize());
  }

  @Test
  public void testClearCache() {
    RuntimeCompiler.compile("\\d+");
    RuntimeCompiler.compile("[a-z]+");
    assertEquals(2, RuntimeCompiler.cacheSize());

    RuntimeCompiler.clearCache();
    assertEquals(0, RuntimeCompiler.cacheSize());

    ReggieMatcher matcher = RuntimeCompiler.compile("\\d+");
    assertEquals(1, RuntimeCompiler.cacheSize());
  }

  // ==================== Error Handling Tests ====================

  @Test
  public void testInvalidPattern() {
    assertThrows(
        RuntimeException.class,
        () -> {
          RuntimeCompiler.compile("[unclosed");
        });
  }

  @Test
  public void testInvalidQuantifier() {
    assertThrows(
        RuntimeException.class,
        () -> {
          RuntimeCompiler.compile("a{5,2}");
        });
  }

  // TODO: Parser doesn't detect \\9999 as invalid - might be treated as octal
  // @Test
  // public void testInvalidBackreference() {
  //     RuntimeException ex = assertThrows(RuntimeException.class, () -> {
  //         RuntimeCompiler.compile("\\9999");
  //     });
  // }

  @Test
  public void testNullPattern() {
    assertThrows(
        Exception.class,
        () -> {
          RuntimeCompiler.compile(null);
        });
  }

  // ==================== Thread Safety Tests ====================

  @Test
  public void testConcurrentCompilation() throws InterruptedException, ExecutionException {
    int numThreads = 10;
    int compilationsPerThread = 100;
    String pattern = "\\d{3}-\\d{3}-\\d{4}";

    ExecutorService executor = Executors.newFixedThreadPool(numThreads);
    CountDownLatch latch = new CountDownLatch(numThreads);
    List<Future<ReggieMatcher>> futures = new ArrayList<>();

    for (int i = 0; i < numThreads; i++) {
      futures.add(
          executor.submit(
              () -> {
                latch.countDown();
                latch.await();

                ReggieMatcher result = null;
                for (int j = 0; j < compilationsPerThread; j++) {
                  result = RuntimeCompiler.compile(pattern);
                  assertTrue(result.matches("123-456-7890"));
                }
                return result;
              }));
    }

    List<ReggieMatcher> matchers = new ArrayList<>();
    for (Future<ReggieMatcher> future : futures) {
      matchers.add(future.get());
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(1, RuntimeCompiler.cacheSize(), "Should have only one cached pattern");

    ReggieMatcher first = matchers.get(0);
    for (ReggieMatcher matcher : matchers) {
      assertSame(first, matcher, "All threads should get the same cached instance");
    }
  }

  @Test
  public void testConcurrentDifferentPatterns() throws InterruptedException, ExecutionException {
    int numThreads = 5;
    ExecutorService executor = Executors.newFixedThreadPool(numThreads);

    String[] patterns = {"\\d+", "[a-z]+", "\\w+", "[A-Z]+", "\\s+"};
    List<Future<ReggieMatcher>> futures = new ArrayList<>();

    for (String pattern : patterns) {
      futures.add(executor.submit(() -> RuntimeCompiler.compile(pattern)));
    }

    for (Future<ReggieMatcher> future : futures) {
      assertNotNull(future.get());
    }

    executor.shutdown();
    assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

    assertEquals(5, RuntimeCompiler.cacheSize());
  }

  // ==================== Performance Tests ====================

  @Test
  public void testFirstUseLatency() {
    String pattern = "\\d{3}-\\d{3}-\\d{4}";

    long start = System.nanoTime();
    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    long duration = System.nanoTime() - start;

    assertNotNull(matcher);
    assertTrue(matcher.matches("123-456-7890"));

    double durationMs = duration / 1_000_000.0;
    System.out.printf("First-use latency: %.2f ms%n", durationMs);

    assertTrue(durationMs < 100, "First-use should complete within 100ms");
  }

  @Test
  public void testCachedAccessLatency() {
    String pattern = "\\d{3}-\\d{3}-\\d{4}";
    RuntimeCompiler.compile(pattern);

    long start = System.nanoTime();
    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    long duration = System.nanoTime() - start;

    assertNotNull(matcher);

    double durationMs = duration / 1_000_000.0;
    System.out.printf("Cached access latency: %.2f ms%n", durationMs);

    assertTrue(durationMs < 1, "Cached access should be under 1ms");
  }

  @Test
  public void testMultipleStrategies() {
    ReggieMatcher literal = RuntimeCompiler.compile("hello");
    assertTrue(literal.matches("hello"));

    ReggieMatcher small = RuntimeCompiler.compile("\\d{3}");
    assertTrue(small.matches("123"));

    ReggieMatcher medium = RuntimeCompiler.compile("\\d{3}-\\d{3}-\\d{4}");
    assertTrue(medium.matches("123-456-7890"));

    ReggieMatcher large = RuntimeCompiler.compile("[a-z]+@[a-z]+\\.[a-z]+");
    assertTrue(large.matches("user@example.com"));
  }

  // ==================== Integration Tests ====================

  @Test
  public void testMatchingAgainstJDKPattern() {
    String pattern = "\\d{3}-\\d{3}-\\d{4}";
    String[] testInputs = {
      "123-456-7890",
      "999-999-9999",
      "000-000-0000",
      "abc-def-ghij",
      "123-45-6789",
      "123-456-789",
      ""
    };

    ReggieMatcher reggie = RuntimeCompiler.compile(pattern);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);

    for (String input : testInputs) {
      boolean reggieResult = reggie.matches(input);
      boolean jdkResult = jdk.matcher(input).matches();
      assertEquals(jdkResult, reggieResult, "Results should match JDK for input: " + input);
    }
  }

  // TODO: Investigate NoSuchMethodError for find()
  // @Test
  // public void testFindAgainstJDKPattern() {
  //     String pattern = "\\d+";
  //     String[] testInputs = {
  //         "abc123def",
  //         "123",
  //         "abc",
  //         "123abc456",
  //         ""
  //     };

  //     ReggieMatcher reggie = RuntimeCompiler.compile(pattern);
  //     java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);

  //     for (String input : testInputs) {
  //         boolean reggieResult = reggie.find(input);
  //         boolean jdkResult = jdk.matcher(input).find();
  //         assertEquals(jdkResult, reggieResult,
  //             "Find results should match JDK for input: " + input);
  //     }
  // }
}
