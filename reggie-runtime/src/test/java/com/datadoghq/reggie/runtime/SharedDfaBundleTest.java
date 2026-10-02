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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The per-NFA {@link PikeVMMatcher.DfaBundle} sharing: matchers built for the same NFA must reuse
 * one set of lazily-materialized DFA caches (the per-op matcher construction otherwise recomputes
 * the warm DFA on every compile — the dominant cost when a service compiles per operation), and
 * concurrent use of a shared cache must stay correct.
 */
class SharedDfaBundleTest {

  private static NFA nfa(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    return new ThompsonBuilder(true).build(ast, countGroups(pattern));
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean inClass = false;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '(' && !inClass) {
        count++;
      } else if (c == ')' && !inClass) {
        // no-op; group counting only needs open parens
      }
    }
    return count;
  }

  private static LazyDFACache findDfa(PikeVMMatcher matcher) throws Exception {
    Field f = PikeVMMatcher.class.getDeclaredField("findDfa");
    f.setAccessible(true);
    return (LazyDFACache) f.get(matcher);
  }

  private static LazyDFACache rejectDfa(PikeVMMatcher matcher) throws Exception {
    Field f = PikeVMMatcher.class.getDeclaredField("rejectDfa");
    f.setAccessible(true);
    return (LazyDFACache) f.get(matcher);
  }

  private static LazyDFACache rejectDfa(BitStateMatcher matcher) throws Exception {
    Field f = BitStateMatcher.class.getDeclaredField("rejectDfa");
    f.setAccessible(true);
    return (LazyDFACache) f.get(matcher);
  }

  @Test
  void matchersOfOneBundleShareDfaCaches() throws Exception {
    // billing-style pattern: eligible for the exact findDfa
    String pattern = "(?:^|,)[hH][oO][sS][tT]:[a-zA-Z_0-9]+";
    NFA nfa = nfa(pattern);
    PikeVMMatcher.DfaBundle bundle = new PikeVMMatcher.DfaBundle(nfa);
    assertNotNull(bundle.findDfa, "pattern should be findDfa-eligible");
    PikeVMMatcher m1 = new PikeVMMatcher(nfa, pattern, bundle);
    PikeVMMatcher m2 = new PikeVMMatcher(nfa, pattern, bundle);
    assertSame(bundle.findDfa, findDfa(m1));
    assertSame(bundle.findDfa, findDfa(m2));
    assertTrue(m1.matches("host:abc"));
    assertTrue(m2.matches(",host:9"));
  }

  @Test
  void matchersWithoutBundleBuildPrivateCaches() throws Exception {
    String pattern = "(?:^|,)[hH][oO][sS][tT]:[a-zA-Z_0-9]+";
    NFA nfa = nfa(pattern);
    PikeVMMatcher m1 = new PikeVMMatcher(nfa, pattern);
    PikeVMMatcher m2 = new PikeVMMatcher(nfa, pattern);
    assertNotNull(findDfa(m1));
    assertNotNull(findDfa(m2));
    assertTrue(findDfa(m1) != findDfa(m2), "no-bundle ctors must not share caches");
  }

  @Test
  void anchoredPatternsShareRejectDfa() throws Exception {
    // word-boundary anchored: findDfa ineligible (needs char context), reject DFA built
    String pattern = "\\bhost:[0-9]+";
    NFA nfa = nfa(pattern);
    PikeVMMatcher.DfaBundle bundle = new PikeVMMatcher.DfaBundle(nfa);
    assertNotNull(bundle.rejectDfa, "anchored assertion-free pattern should get a reject DFA");
    PikeVMMatcher m1 = new PikeVMMatcher(nfa, pattern, bundle);
    PikeVMMatcher m2 = new PikeVMMatcher(nfa, pattern, bundle);
    assertSame(bundle.rejectDfa, rejectDfa(m1));
    assertSame(bundle.rejectDfa, rejectDfa(m2));
  }

  @Test
  void bitStateMatchersShareRejectBundle() throws Exception {
    String pattern = "^(.+?)\\.([^/]+)";
    NFA nfa = nfa(pattern);
    RejectDfaFactory.Bundle bundle = RejectDfaFactory.build(nfa);
    assertNotNull(bundle, "lazy-group pattern should get a reject bundle");
    BitStateMatcher m1 = new BitStateMatcher(nfa, pattern, null, bundle);
    BitStateMatcher m2 = new BitStateMatcher(nfa, pattern, null, bundle);
    assertSame(bundle.dfa, rejectDfa(m1));
    assertSame(bundle.dfa, rejectDfa(m2));
  }

  /** Concurrent population of one shared cache across threads must not corrupt results. */
  @Test
  void concurrentUseOfSharedCacheIsCorrect() throws Exception {
    String pattern = "(?:^|,)[hH][oO][sS][tT]:[a-zA-Z_0-9]+";
    java.util.regex.Pattern jdk = Pattern.compile(pattern);
    NFA nfa = nfa(pattern);
    PikeVMMatcher.DfaBundle bundle = new PikeVMMatcher.DfaBundle(nfa);
    int threads = Runtime.getRuntime().availableProcessors();
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CountDownLatch start = new CountDownLatch(1);
      @SuppressWarnings("unchecked")
      Future<Boolean>[] results = (Future<Boolean>[]) new Future<?>[threads];
      for (int t = 0; t < threads; t++) {
        results[t] =
            pool.submit(
                () -> {
                  start.await();
                  boolean ok = true;
                  for (int i = 0; i < 5_000; i++) {
                    String input = (i % 2 == 0) ? ("x,host:" + i) : ("no match here " + i);
                    PikeVMMatcher m = new PikeVMMatcher(nfa, pattern, bundle);
                    boolean expected = jdk.matcher(input).find();
                    ok &= (m.find(input) == expected);
                  }
                  return ok;
                });
      }
      start.countDown();
      for (Future<Boolean> r : results) {
        assertTrue(r.get(60, TimeUnit.SECONDS), "no thread observed a wrong result");
      }
    } finally {
      pool.shutdownNow();
    }
  }
}
