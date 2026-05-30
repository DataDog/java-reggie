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

import com.datadoghq.reggie.Reggie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Concurrency stress test for NFA-backed matchers.
 *
 * <p>NFA-backed matchers (strategies OPTIMIZED_NFA, OPTIMIZED_NFA_WITH_BACKREFS,
 * OPTIMIZED_NFA_WITH_LOOKAROUND, HYBRID_DFA_LOOKAHEAD, SPECIALIZED_MULTIPLE_LOOKAHEADS,
 * SPECIALIZED_LITERAL_LOOKAHEADS, LAZY_DFA) with more than 128 NFA states use pre-allocated
 * instance fields (currentStates, nextStates, epsilonProcessed, configGroupStarts) during matching.
 * Sharing a single instance across threads or calls corrupts these fields.
 *
 * <p>The fix: for NFA-backed strategies, Reggie.compile() returns a fresh instance per call (backed
 * by a cached compiled class) rather than a shared L1-cached instance. Each caller receives its own
 * instance and can use it safely in single-threaded fashion.
 */
public class NfaMatcherConcurrencyTest {

  // Pattern using a lookahead — routes to HYBRID_DFA_LOOKAHEAD strategy with 383 NFA states
  // (>128 threshold). This guarantees StateSet instance-field usage and does NOT go through the
  // hybrid DFA/NFA path that would hide the race (shouldUseHybrid only triggers for OPTIMIZED_NFA).
  private static final String NFA_PATTERN = "(?=(?:a|b|c|d){20}).*(?:e|f|g|h){20}";

  // Input that matches: starts with 20 chars from {a,b,c,d} (satisfies the lookahead),
  // then .* matches, then (?:e|f|g|h){20} matches the last 20 chars.
  private static final String MATCHING_INPUT = "a".repeat(20) + "e".repeat(20);

  // Input that must NOT match
  private static final String NON_MATCHING_INPUT = "x";

  @BeforeEach
  @AfterEach
  public void clearCache() {
    Reggie.clearCache();
  }

  /**
   * 16 threads each call Reggie.compile() to get their own NFA matcher instance, then hammer it
   * 2000 times. Without the fix, all threads would share the SAME instance from L1 cache and
   * corrupt each other's NFA state sets. With the fix each thread gets a fresh instance and results
   * are always consistent.
   */
  @Test
  public void testConcurrentCompileAndMatchNfaPattern() throws Exception {
    int numThreads = 16;
    int iterationsPerThread = 2000;

    AtomicInteger failures = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(numThreads);
    CountDownLatch doneLatch = new CountDownLatch(numThreads);
    ExecutorService pool = Executors.newFixedThreadPool(numThreads);

    for (int t = 0; t < numThreads; t++) {
      pool.submit(
          () -> {
            try {
              startLatch.countDown();
              startLatch.await(); // all threads start simultaneously

              // Each thread gets its own instance via compile() — this is the correct usage.
              // Without the fix, all compile() calls would return the SAME cached instance,
              // causing state corruption when used concurrently.
              ReggieMatcher matcher = Reggie.compile(NFA_PATTERN);

              for (int i = 0; i < iterationsPerThread; i++) {
                boolean matchResult = matcher.matches(MATCHING_INPUT);
                boolean noMatchResult = matcher.matches(NON_MATCHING_INPUT);
                if (!matchResult || noMatchResult) {
                  failures.incrementAndGet();
                }
              }
            } catch (Exception e) {
              failures.incrementAndGet();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "Threads timed out");
    pool.shutdown();

    assertEquals(
        0, failures.get(), "Each thread must get a fresh NFA matcher and produce correct results");
  }

  /**
   * Verify that each Reggie.compile() call for an NFA-backed pattern returns a distinct instance.
   * This is the property that guarantees thread safety: callers receive fresh, exclusive instances
   * rather than a shared mutable object.
   */
  @Test
  public void testEachCompileCallReturnsDistinctNfaInstance() {
    int numInstances = 16;
    List<ReggieMatcher> instances = new ArrayList<>(numInstances);
    for (int i = 0; i < numInstances; i++) {
      instances.add(Reggie.compile(NFA_PATTERN));
    }

    // Every instance must be functionally correct independently
    for (ReggieMatcher m : instances) {
      assertTrue(m.matches(MATCHING_INPUT), "Each instance must match the valid input");
      assertFalse(m.matches(NON_MATCHING_INPUT), "Each instance must reject the invalid input");
    }

    // Every instance must be a distinct object
    for (int i = 0; i < instances.size(); i++) {
      for (int j = i + 1; j < instances.size(); j++) {
        assertNotSame(
            instances.get(i),
            instances.get(j),
            "NFA-backed matchers must not be shared across compile() calls");
      }
    }
  }
}
