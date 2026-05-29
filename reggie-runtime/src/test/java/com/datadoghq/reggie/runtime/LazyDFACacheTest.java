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
}
