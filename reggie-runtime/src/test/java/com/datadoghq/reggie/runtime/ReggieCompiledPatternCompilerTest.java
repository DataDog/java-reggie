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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReggieCompiledPatternCompilerTest {
  @AfterEach
  void clearLegacyCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void boundsCacheAndUpdatesLruRecency() {
    ReggieCompiledPatternCompiler compiler = new ReggieCompiledPatternCompiler(2);
    ReggieCompiledPattern one = compile(compiler, "(?<value>\\S+)");
    ReggieCompiledPattern two = compile(compiler, "(?<value>\\d+)");
    assertSame(one, compile(compiler, "(?<value>\\S+)"));
    compile(compiler, "(?<value>\\w+)");
    assertEquals(2, compiler.cacheSize());
    assertSame(one, compile(compiler, "(?<value>\\S+)"));
    assertNotSame(two, compile(compiler, "(?<value>\\d+)"));
  }

  @Test
  void rejectsWithoutCachingOrTouchingLegacyCaches() {
    int patterns = RuntimeCompiler.cacheSize();
    int structures = RuntimeCompiler.structuralCacheSize();
    ReggieCompiledPatternCompiler compiler = new ReggieCompiledPatternCompiler(1);
    ReggieCompilationResult rejected =
        compiler.tryCompile(new ReggieCompileRequest("(?<value>[a-z]+)", ReggieCompileFlag.NONE));
    assertFalse(rejected.isAdmitted());
    assertEquals(0, compiler.cacheSize());
    assertEquals(patterns, RuntimeCompiler.cacheSize());
    assertEquals(structures, RuntimeCompiler.structuralCacheSize());
  }

  @Test
  void instancesAndStatesAreIndependent() {
    ReggieCompiledPatternCompiler first = new ReggieCompiledPatternCompiler(1);
    ReggieCompiledPatternCompiler second = new ReggieCompiledPatternCompiler(1);
    ReggieCompiledPattern a = compile(first, "(?<value>\\S+)");
    ReggieCompiledPattern b = compile(second, "(?<value>\\S+)");
    assertNotSame(a, b);
    ReggieMatchState left = a.newState();
    ReggieMatchState right = a.newState();
    assertTrue(left.matches("left", 0, 4));
    assertTrue(right.matches("right", 0, 5));
    assertEquals(4, left.end("value"));
    assertEquals(5, right.end("value"));
    first.clearCache();
    assertEquals(0, first.cacheSize());
    assertEquals(1, second.cacheSize());
  }

  @Test
  void concurrentRequestsShareOneCachedPattern() throws Exception {
    ReggieCompiledPatternCompiler compiler = new ReggieCompiledPatternCompiler(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ReggieCompiledPattern> first =
          executor.submit(() -> compile(compiler, "(?<value>\\S+)"));
      Future<ReggieCompiledPattern> second =
          executor.submit(() -> compile(compiler, "(?<value>\\S+)"));
      assertSame(first.get(), second.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentRequestsPerformOneBlockedAdmission() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch waiterArrived = new CountDownLatch(1);
    AtomicInteger admissions = new AtomicInteger();
    ReggieCompiledPatternCompiler compiler =
        new ReggieCompiledPatternCompiler(
            2,
            request -> {
              admissions.incrementAndGet();
              started.countDown();
              await(release);
              return ReggieCompiledPattern.tryCompileNative(request);
            },
            waiterArrived::countDown);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ReggieCompilationResult> first = executor.submit(() -> compileResult(compiler));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Future<ReggieCompilationResult> second = executor.submit(() -> compileResult(compiler));
      assertTrue(waiterArrived.await(5, TimeUnit.SECONDS));
      release.countDown();
      assertSame(first.get().pattern(), second.get().pattern());
      assertEquals(1, admissions.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void clearDuringBlockedAdmissionDoesNotDeadlock() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ReggieCompiledPatternCompiler compiler =
        new ReggieCompiledPatternCompiler(
            1,
            request -> {
              started.countDown();
              await(release);
              return ReggieCompiledPattern.tryCompileNative(request);
            });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ReggieCompilationResult> result = executor.submit(() -> compileResult(compiler));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      compiler.clearCache();
      release.countDown();
      assertTrue(result.get().isAdmitted());
      assertEquals(1, compiler.cacheSize());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void rejectsInvalidCapacity() {
    assertThrows(IllegalArgumentException.class, () -> new ReggieCompiledPatternCompiler(0));
  }

  @Test
  void keepsNoneAndDotAllRequestsAsDistinctKeys() {
    ReggieCompiledPatternCompiler compiler = new ReggieCompiledPatternCompiler(2);
    ReggieCompiledPattern none = compile(compiler, "(?<value>\\S+)");
    ReggieCompilationResult dotAll =
        compiler.tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.DOTALL));
    assertTrue(dotAll.isAdmitted());
    assertNotSame(none, dotAll.pattern());
    assertEquals(2, compiler.cacheSize());
  }

  @Test
  void exceptionalAdmissionReleasesTheRequestForRetry() {
    AtomicInteger attempts = new AtomicInteger();
    Cancellation cancellation = new Cancellation();
    ReggieCompiledPatternCompiler compiler =
        new ReggieCompiledPatternCompiler(
            1,
            request -> {
              if (attempts.getAndIncrement() == 0) throw cancellation;
              return ReggieCompiledPattern.tryCompileNative(request);
            });
    assertSame(
        cancellation, assertThrows(Cancellation.class, () -> compile(compiler, "(?<value>\\S+)")));
    assertTrue(compile(compiler, "(?<value>\\S+)") != null);
  }

  @Test
  void concurrentWaitersReceiveTheOriginalFailureAndCanRetry() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch waiterArrived = new CountDownLatch(1);
    Cancellation cancellation = new Cancellation();
    AtomicInteger attempts = new AtomicInteger();
    ReggieCompiledPatternCompiler compiler =
        new ReggieCompiledPatternCompiler(
            1,
            request -> {
              if (attempts.getAndIncrement() == 0) {
                started.countDown();
                await(release);
                throw cancellation;
              }
              return ReggieCompiledPattern.tryCompileNative(request);
            },
            waiterArrived::countDown);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ReggieCompilationResult> winner = executor.submit(() -> compileResult(compiler));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Future<ReggieCompilationResult> waiter = executor.submit(() -> compileResult(compiler));
      assertTrue(waiterArrived.await(5, TimeUnit.SECONDS));
      release.countDown();
      assertSame(cancellation, assertThrows(Exception.class, winner::get).getCause());
      assertSame(cancellation, assertThrows(Exception.class, waiter::get).getCause());
      assertTrue(compileResult(compiler).isAdmitted());
    } finally {
      executor.shutdownNow();
    }
  }

  private static ReggieCompiledPattern compile(
      ReggieCompiledPatternCompiler compiler, String source) {
    ReggieCompilationResult result =
        compiler.tryCompile(new ReggieCompileRequest(source, ReggieCompileFlag.NONE));
    assertTrue(result.isAdmitted());
    return result.pattern();
  }

  private static ReggieCompilationResult compileResult(ReggieCompiledPatternCompiler compiler) {
    return compiler.tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE));
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("timed out waiting for test");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static final class Cancellation extends RuntimeException {}
}
