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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.ReggieOption;
import com.datadoghq.reggie.ReggieOptions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@code cached(key, pattern[, options])}: user-key namespace, engine key and pooling. */
class CachedKeyTest {

  private static final ReggieOptions NAMED_ONLY =
      ReggieOptions.builder().enable(ReggieOption.CAPTURE_NAMED_ONLY).build();

  private static final String NAMED_PATTERN = "(?:(a)|(?<n>b))+[ab]";

  // Patterns routed to strategies whose matchers write instance state while matching: BitState,
  // hybrid, and a generated NFA matcher (NFA_CLASS_CACHE).
  private static final List<String> MUTABLE_ROUTES =
      List.of("((a)?b){1,200}", "$|x", "^(a)?(a)?b", "(a|b)*(c)", "x(?:a+b+|b+a+){75}");

  @BeforeEach
  void clear() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void repeatedCallsReturnSameInstance() {
    assertSame(
        RuntimeCompiler.cached("k", "\\d+", NAMED_ONLY),
        RuntimeCompiler.cached("k", "\\d+", NAMED_ONLY));
  }

  @Test
  void nulOptionSuffixCannotAliasDifferentPatternUnderUserKey() {
    String suffixPattern = "a\0CAPTURE_NAMED_ONLY";
    RuntimeCompiler.cached("collision", "a", NAMED_ONLY);
    IllegalStateException baseFirst =
        assertThrows(
            IllegalStateException.class,
            () -> RuntimeCompiler.cached("collision", suffixPattern, ReggieOptions.DEFAULT));
    assertTrue(baseFirst.getMessage().endsWith("is already mapped to a different pattern"));

    RuntimeCompiler.clearCache();
    RuntimeCompiler.cached("collision", suffixPattern, ReggieOptions.DEFAULT);
    IllegalStateException suffixFirst =
        assertThrows(
            IllegalStateException.class,
            () -> RuntimeCompiler.cached("collision", "a", NAMED_ONLY));
    assertTrue(suffixFirst.getMessage().endsWith("is already mapped to a different pattern"));
  }

  @Test
  void userKeyDoesNotAliasCompile() {
    RuntimeCompiler.cached("\\d+", "[a-z]+");
    ReggieMatcher m = RuntimeCompiler.compile("\\d+");
    assertTrue(m.matches("123"));
    assertFalse(m.matches("abc"));
  }

  @Test
  void cachedWithOptionsDoesNotLeakIntoDefaultCompile() {
    RuntimeCompiler.cached("k", NAMED_PATTERN, NAMED_ONLY);
    String afterCached = group1(RuntimeCompiler.compile(NAMED_PATTERN));
    RuntimeCompiler.clearCache();
    assertEquals(group1(RuntimeCompiler.compile(NAMED_PATTERN)), afterCached);
  }

  @Test
  void defaultCompileDoesNotLeakIntoCachedWithOptions() {
    RuntimeCompiler.compile(NAMED_PATTERN);
    String afterCompile = group1(RuntimeCompiler.cached("k", NAMED_PATTERN, NAMED_ONLY));
    RuntimeCompiler.clearCache();
    assertEquals(group1(RuntimeCompiler.cached("k", NAMED_PATTERN, NAMED_ONLY)), afterCompile);
  }

  @Test
  void sameKeyDifferentOptionsThrows() {
    RuntimeCompiler.cached("k2", "(a)b", ReggieOptions.DEFAULT);
    assertThrows(
        IllegalStateException.class, () -> RuntimeCompiler.cached("k2", "(a)b", NAMED_ONLY));
  }

  @Test
  void mutableRoutesArePooled() {
    Set<Class<?>> engines = new HashSet<>();
    for (String p : MUTABLE_ROUTES) {
      ReggieMatcher a = RuntimeCompiler.compile(p);
      ReggieMatcher b = RuntimeCompiler.compile(p);
      assertTrue(a != b, p + ": expected a mutable route (fresh instance per compile)");
      engines.add(EngineRouting.engineClass(a));
      assertInstanceOf(PooledMatcher.class, RuntimeCompiler.cached("k:" + p, p), p);
    }
    assertTrue(engines.contains(BitStateMatcher.class), engines.toString());
    assertTrue(engines.contains(HybridMatcher.class), engines.toString());
    assertTrue(engines.size() >= 3, "generated NFA route missing: " + engines);
  }

  @Test
  void pooledCachedMatcherIsThreadSafe() throws Exception {
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (String p : MUTABLE_ROUTES) {
        ReggieMatcher shared = RuntimeCompiler.cached("c:" + p, p);
        Pattern jdk = Pattern.compile(p);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
          int seed = t;
          futures.add(
              pool.submit(
                  () -> {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                      String input = input(seed, i);
                      Matcher j = jdk.matcher(input);
                      MatchResult r = shared.findMatch(input);
                      if (j.find()) {
                        assertNotNull(r, p + " on " + input);
                        assertEquals(j.start(), r.start(), p + " on " + input);
                        assertEquals(j.end(), r.end(), p + " on " + input);
                      } else {
                        assertEquals(null, r, p + " on " + input);
                      }
                    }
                    return null;
                  }));
        }
        start.countDown();
        for (Future<?> f : futures) {
          f.get(60, TimeUnit.SECONDS);
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static String input(int seed, int i) {
    StringBuilder sb = new StringBuilder();
    String alphabet = "abcx";
    int n = 1 + ((seed * 31 + i) % 40);
    for (int k = 0; k < n; k++) {
      sb.append(alphabet.charAt((seed + i * 7 + k * 13) % alphabet.length()));
    }
    return sb.toString();
  }

  private static String group1(ReggieMatcher m) {
    MatchResult r = m.match("abab");
    assertNotNull(r);
    return "[" + r.start(1) + "," + r.end(1) + ")";
  }
}
