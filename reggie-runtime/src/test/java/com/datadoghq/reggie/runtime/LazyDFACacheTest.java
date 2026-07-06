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

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LazyDFACacheTest {

  // Minimal NfaStep: state {0} +'a'→ {1}, state {1} +'b'→ {2}, anything else → dead
  private static final NfaStep TWO_STEP =
      (states, c) -> {
        if (states.length == 1 && states[0] == 0 && c == 'a') return new int[] {1};
        if (states.length == 1 && states[0] == 1 && c == 'b') return new int[] {2};
        return new int[0];
      };

  @Test
  void testCacheMissInterns() {
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertTrue(cache.matches("ab", TWO_STEP));
    assertFalse(cache.matches("a", TWO_STEP));
    assertFalse(cache.matches("abc", TWO_STEP));
  }

  @Test
  void testCacheHitUsesAsciiTable() {
    AtomicInteger callCount = new AtomicInteger();
    NfaStep counting =
        (states, c) -> {
          callCount.incrementAndGet();
          return TWO_STEP.apply(states, c);
        };
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    cache.matches("ab", counting); // cold: step called twice
    int coldCalls = callCount.getAndSet(0);
    assertEquals(2, coldCalls);

    cache.matches("ab", counting); // warm: ASCII hit, step NOT called
    assertEquals(0, callCount.get());
  }

  @Test
  void testDeadStateEarlyExit() {
    AtomicInteger callCount = new AtomicInteger();
    NfaStep dead =
        (states, c) -> {
          callCount.incrementAndGet();
          return new int[0];
        };
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {1});
    assertFalse(cache.matches("abc", dead));
    assertEquals(1, callCount.get()); // stops after first dead step
  }

  @Test
  void testFreezeAtCap() {
    int cap = 3;
    NfaStep gen =
        (states, c) -> {
          if (states.length == 1 && c == 'a') return new int[] {states[0] + 1};
          return new int[0];
        };
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {999}, cap);
    assertFalse(cache.matches("aaa", gen));
    assertTrue(cache.isFrozen());
    assertFalse(cache.matches("aaa", gen)); // still works after freeze
  }

  @Test
  void testFallbackMatchCorrect() {
    int cap = 1; // freeze immediately after start state
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2}, cap);
    assertTrue(cache.matches("ab", TWO_STEP));
    assertFalse(cache.matches("a", TWO_STEP));
    assertTrue(cache.isFrozen());
  }

  @Test
  void testAcceptStateRecognition() {
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {0});
    assertTrue(cache.matches("", TWO_STEP));
    LazyDFACache cache2 = new LazyDFACache(new int[] {0}, new int[] {99});
    assertFalse(cache2.matches("", TWO_STEP));
  }

  @Test
  void testNonAsciiCharFallsBackToNfaStep() {
    AtomicInteger callCount = new AtomicInteger();
    NfaStep tracker =
        (states, c) -> {
          callCount.incrementAndGet();
          return new int[0];
        };
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {1});
    cache.matches("a", tracker);
    callCount.set(0);
    cache.matches("Ā", tracker); // c >= 128
    assertEquals(1, callCount.get());
  }

  // ── matchesBounded coverage ────────────────────────────────────────────────

  @Test
  void testMatchesBoundedHappyPath() {
    // "xxab" — bounded region [2,4) matches "ab"
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    String input = "xx" + "ab";
    assertTrue(cache.matchesBounded(input, 2, 4, TWO_STEP));
    // Parity: same result as matches on the substring
    assertEquals(cache.matches("ab", TWO_STEP), cache.matchesBounded(input, 2, 4, TWO_STEP));
  }

  @Test
  void testMatchesBoundedEmptyRegion() {
    // start == end → empty region; accepting only if start state itself accepts
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertFalse(cache.matchesBounded("abc", 1, 1, TWO_STEP)); // start state not accepting
    LazyDFACache accepting = new LazyDFACache(new int[] {0}, new int[] {0});
    assertTrue(accepting.matchesBounded("abc", 1, 1, TWO_STEP)); // start state is accepting
  }

  @Test
  void testMatchesBoundedFrozenFallback() {
    // cap=1 freezes immediately; nfaFallbackMatchBounded must respect end boundary
    int cap = 1;
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2}, cap);
    String input = "xxab";
    // bounded region [2,4) = "ab" — should match via fallback
    assertTrue(cache.matchesBounded(input, 2, 4, TWO_STEP));
    // bounded region [2,3) = "a" only — should not match
    assertFalse(cache.matchesBounded(input, 2, 3, TWO_STEP));
    assertTrue(cache.isFrozen());
  }

  @Test
  void testMatchesBoundedNonAsciiBoundaryAtCode128() {
    // c == 128 (U+0080) must bypass the ASCII table (c >= 128 guard)
    AtomicInteger callCount = new AtomicInteger();
    NfaStep tracker =
        (states, c) -> {
          callCount.incrementAndGet();
          return new int[0];
        };
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {1});
    String withCode128 = "\u0080"; // code point 128, exactly the boundary
    cache.matchesBounded(withCode128, 0, 1, tracker);
    // Must have called nfaStep (not the ASCII table) for c == 128
    assertEquals(1, callCount.get());
  }

  @Test
  void testCacheEntryNonAsciiBoundaryAtCode128() {
    // cacheEntry guard: c >= 128 must skip the ASCII table for c == 128
    AtomicInteger callCount = new AtomicInteger();
    NfaStep tracker =
        (states, c) -> {
          callCount.incrementAndGet();
          return new int[0];
        };
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {1});
    String withCode128 = "\u0080";
    cache.matches(withCode128, tracker); // first call — must not cache entry
    callCount.set(0);
    cache.matches(withCode128, tracker); // second call — must still invoke nfaStep (not cached)
    assertEquals(1, callCount.get());
  }

  // ── sentinel constant parity ───────────────────────────────────────────────

  @Test
  void testSentinelConstantsParity() throws Exception {
    // UNCACHED, DEAD, FALLBACK must match the values embedded in generated bytecode.
    // LazyDFABytecodeGenerator keeps local copies (different module); this test detects drift.
    Class<?> generatorClass =
        Class.forName("com.datadoghq.reggie.codegen.codegen.LazyDFABytecodeGenerator");

    Field genUncached = generatorClass.getDeclaredField("UNCACHED");
    Field genDead = generatorClass.getDeclaredField("DEAD");
    Field genFallback = generatorClass.getDeclaredField("FALLBACK");
    genUncached.setAccessible(true);
    genDead.setAccessible(true);
    genFallback.setAccessible(true);

    assertEquals(LazyDFACache.UNCACHED, genUncached.get(null), "UNCACHED sentinel mismatch");
    assertEquals(LazyDFACache.DEAD, genDead.get(null), "DEAD sentinel mismatch");
    assertEquals(LazyDFACache.FALLBACK, genFallback.get(null), "FALLBACK sentinel mismatch");
  }

  @Test
  void testConcurrentInterning() throws Exception {
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {1});
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);
    AtomicReference<Boolean> r1 = new AtomicReference<>(), r2 = new AtomicReference<>();

    Thread t1 =
        new Thread(
            () -> {
              ready.countDown();
              try {
                go.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              r1.set(cache.matches("a", TWO_STEP));
            });
    Thread t2 =
        new Thread(
            () -> {
              ready.countDown();
              try {
                go.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              r2.set(cache.matches("a", TWO_STEP));
            });
    t1.start();
    t2.start();
    ready.await();
    go.countDown();
    t1.join();
    t2.join();
    assertTrue(r1.get());
    assertTrue(r2.get());
  }

  // ── findFrom coverage ─────────────────────────────────────────────────────

  @Test
  void testFindFromExactMatch() {
    // "ab" at position 0
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertEquals(0, cache.findFrom("ab", 0, TWO_STEP));
  }

  @Test
  void testFindFromEmbedded() {
    // "xxab" — match starts at position 2
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertEquals(2, cache.findFrom("xxab", 0, TWO_STEP));
  }

  @Test
  void testFindFromNoMatch() {
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertEquals(-1, cache.findFrom("xxxx", 0, TWO_STEP));
  }

  @Test
  void testFindFromStartOffsetSkipsEarlierMatch() {
    // "abab" — match at 0 and 2; starting from 1 should find match at 2
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertEquals(2, cache.findFrom("abab", 1, TWO_STEP));
  }

  @Test
  void testFindFromNullInput() {
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertEquals(-1, cache.findFrom(null, 0, TWO_STEP));
  }

  @Test
  void testFindFromFrozenFallback() {
    // cap=1 forces immediate freeze; nfaFallbackFindFrom must find the match
    int cap = 1;
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2}, cap);
    assertEquals(0, cache.findFrom("ab", 0, TWO_STEP));
    assertTrue(cache.isFrozen());
    // Second call also works (frozen path)
    LazyDFACache cache2 = new LazyDFACache(new int[] {0}, new int[] {2}, cap);
    assertEquals(2, cache2.findFrom("xxab", 0, TWO_STEP));
  }

  // ── findEnd coverage ───────────────────────────────────────────────────────

  @Test
  void testFindEndMatchEndsBeforeLimit() {
    // TWO_STEP: {0} +'a'→{1}, {1} +'b'→{2} (accept), anything else→dead.
    // "ab" completes the match at pos 2; the trailing "zzzz" dies immediately,
    // so findEnd must stop at the true end (2), not run to limit (6).
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    String input = "abzzzz";
    assertEquals(2, cache.findEnd(input, 0, input.length(), TWO_STEP));
  }

  @Test
  void testFindEndNonDyingTailReturnsLimit() {
    // A step function representing a "(?s).*" tail style automaton that never dies:
    // the single state loops on every char and is always accepting.
    NfaStep nonDying = (states, c) -> new int[] {0};
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {0});
    String input = "abcdefgh";
    assertEquals(input.length(), cache.findEnd(input, 0, input.length(), nonDying));
  }

  @Test
  void testFindEndStartStateAccepting() {
    // Case A: start state accepts and nothing else can be consumed -> bound is exactly start.
    LazyDFACache deadEnd = new LazyDFACache(new int[] {0}, new int[] {0});
    NfaStep noTransitions = (states, c) -> new int[0];
    assertEquals(3, deadEnd.findEnd("xxxx", 3, 4, noTransitions));

    // Case B: start state accepts, and one more accepting state can be reached by
    // consuming further input, before eventually dying -> bound extends past start.
    // {0} (accept) +'a'→ {1} (accept) +'a'→ dead.
    NfaStep extendOnce =
        (states, c) -> {
          if (states.length == 1 && states[0] == 0 && c == 'a') return new int[] {1};
          return new int[0];
        };
    LazyDFACache extendCache = new LazyDFACache(new int[] {0}, new int[] {0, 1});
    assertEquals(1, extendCache.findEnd("aa", 0, 2, extendOnce));
  }

  @Test
  void testFindEndCacheFreezeDifferential() {
    // cap=2 forces the cache to freeze after the start state plus one more, forcing
    // the nfaFallbackFindEnd path; result must match an unfrozen cache on the same input.
    NfaStep step =
        (s, c) ->
            s.length == 1 && s[0] == 0 && c == 'a'
                ? new int[] {1}
                : s.length == 1 && s[0] == 1 && c == 'a'
                    ? new int[] {2}
                    : s.length == 1 && s[0] == 2 && c == 'b' ? new int[] {3} : new int[0];
    LazyDFACache frozenCache = new LazyDFACache(new int[] {0}, new int[] {2}, 2);
    LazyDFACache unfrozenCache = new LazyDFACache(new int[] {0}, new int[] {2});

    String input = "aaaab";
    int frozenResult = frozenCache.findEnd(input, 0, input.length(), step);
    int unfrozenResult = unfrozenCache.findEnd(input, 0, input.length(), step);
    assertTrue(frozenCache.isFrozen());
    assertEquals(unfrozenResult, frozenResult, "findEnd result should match unfrozen cache");
    assertEquals(2, unfrozenResult); // sanity: "aa" reaches accepting state 2 at pos 2
  }

  @Test
  void testFindEndNoAcceptingStateReturnsMinusOne() {
    // No accepting state ever reached before DEAD; returns -1.
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {999}); // no accepting states
    NfaStep dead = (s, c) -> new int[0];
    assertEquals(-1, cache.findEnd("abc", 0, 3, dead));
  }

  @Test
  void testFindEndNullInput() {
    LazyDFACache cache = new LazyDFACache(new int[] {0}, new int[] {2});
    assertEquals(-1, cache.findEnd(null, 0, 4, TWO_STEP));
  }
}
