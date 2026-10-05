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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.Map;
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
 *
 * <p>The shared bundles are held through {@link SoftReference} (bounded retention under heap
 * pressure — see RuntimeCompiler's cache entries); the eviction tests at the bottom pin the
 * rebuild-on-clear path that makes that sound.
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
    // Review #140 r4165029351: matchers must retain the bundle itself, so the entry's
    // SoftReference cannot be GC-cleared while live matchers still pin the bundle's caches.
    assertSame(bundle, sourceBundle(m1));
    assertSame(bundle, sourceBundle(m2));
    assertTrue(m1.matches("host:abc"));
    assertTrue(m2.matches(",host:9"));
  }

  /** The matcher-held bundle reachability anchor (PikeVMMatcher.sourceBundle). */
  private static PikeVMMatcher.DfaBundle sourceBundle(PikeVMMatcher m) throws Exception {
    Field f = PikeVMMatcher.class.getDeclaredField("sourceBundle");
    f.setAccessible(true);
    return (PikeVMMatcher.DfaBundle) f.get(m);
  }

  /**
   * {@link RejectDfaFactory#NONE} marks a known-ineligible NFA: BitStateMatcher must skip its
   * matcher-private build retry (review #140 r4165029362) and run without a reject DFA.
   */
  @Test
  void noneSentinelSkipsPrivateRejectBuild() throws Exception {
    String pattern = "\\bhost:[0-9]+";
    NFA nfa = nfa(pattern);
    BitStateMatcher m = new BitStateMatcher(nfa, pattern, null, RejectDfaFactory.NONE);
    Field f = BitStateMatcher.class.getDeclaredField("rejectDfa");
    f.setAccessible(true);
    assertNull(f.get(m), "NONE must resolve to no reject DFA, not a private rebuild");
    assertTrue(m.matches("host:123"));
    assertFalse(m.matches("host:abc"));
    assertFalse(m.matches("noservicehere"));
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

  // ── Eviction: a GC-cleared SoftReference in a cache entry must yield a correct rebuilt bundle.
  // \b(a?)+x routes to PIKEVM_CAPTURE and \b(a)+x to BITSTATE_CAPTURE (word boundary skips the
  // hybrid; bitstate eligibility splits the two — verified via RuntimeCompiler.describeRouting).
  private static Object cacheEntry(String cacheField, String pattern) throws Exception {
    Field f = RuntimeCompiler.class.getDeclaredField(cacheField);
    f.setAccessible(true);
    @SuppressWarnings("unchecked")
    Map<Object, Object> cache = (Map<Object, Object>) f.get(null);
    Object entry = cache.get(pattern);
    assertNotNull(entry, "compile must register the pattern in " + cacheField);
    return entry;
  }

  private static void evictBundle(Object entry, String fieldName) throws Exception {
    Field f = entry.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    f.set(entry, new SoftReference<>(null));
  }

  @SuppressWarnings("unchecked")
  private static <T> T referencedBundle(Object entry, String fieldName) throws Exception {
    Field f = entry.getClass().getDeclaredField(fieldName);
    f.setAccessible(true);
    return ((SoftReference<T>) f.get(entry)).get();
  }

  @Test
  void evictedPikeVmBundleIsRebuiltCorrectly() throws Exception {
    String pattern = "\\b(a?)+x";
    assertTrue(Reggie.compile(pattern).matches("ax")); // builds the entry + bundle
    Object entry = cacheEntry("PIKEVM_NFA_CACHE", pattern);
    PikeVMMatcher.DfaBundle original = referencedBundle(entry, "dfaBundle");
    assertNotNull(original, "first compile must have built the bundle strongly");

    evictBundle(entry, "dfaBundle"); // simulate GC having cleared the SoftReference

    ReggieMatcher rebuilt = Reggie.compile(pattern);
    PikeVMMatcher.DfaBundle second = referencedBundle(entry, "dfaBundle");
    assertNotNull(second, "evicted entry must rebuild the bundle, not stay null");
    assertTrue(second != original, "rebuilt bundle must not be the evicted instance");
    assertTrue(rebuilt.matches("ax"));
    assertTrue(rebuilt.matches("x")); // (a?)+ can iterate on the empty string
    assertTrue(rebuilt.find(" ax")); // space->a is a word boundary
    assertFalse(rebuilt.find("yax")); // no word boundary before 'a' mid-word
    assertFalse(rebuilt.matches("a"));
  }

  @Test
  void evictedBitStateBundleIsRebuiltCorrectly() throws Exception {
    String pattern = "\\b(a)+x";
    assertTrue(Reggie.compile(pattern).matches("ax")); // builds the entry + setup bundle
    Object entry = cacheEntry("BITSTATE_NFA_CACHE", pattern);
    BitStateMatcher.Bundle original = referencedBundle(entry, "bundle");
    assertNotNull(original, "first compile must have built the setup bundle strongly");

    evictBundle(entry, "bundle"); // simulate GC having cleared the SoftReference

    ReggieMatcher rebuilt = Reggie.compile(pattern);
    BitStateMatcher.Bundle second = referencedBundle(entry, "bundle");
    assertNotNull(second, "evicted entry must rebuild the setup bundle, not stay null");
    assertTrue(second != original, "rebuilt bundle must not be the evicted instance");
    assertTrue(rebuilt.matches("ax"));
    assertTrue(rebuilt.matches("aaax"));
    assertTrue(rebuilt.find(" aax")); // space->a is a word boundary
    assertFalse(rebuilt.find("yaax")); // no word boundary before 'a' mid-word
    assertFalse(rebuilt.matches("x"));
    assertFalse(rebuilt.matches("a"));
  }
}
