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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReggieNativeCompileBudgetTest {
  @Test
  void admitsAtBoundaryAndRejectsBeforeAdmissionOrInflightRegistration() {
    int legacyPatterns = RuntimeCompiler.cacheSize();
    int legacyStructures = RuntimeCompiler.structuralCacheSize();
    AtomicInteger admissions = new AtomicInteger();
    ReggieCompiledPatternCompiler compiler =
        new ReggieCompiledPatternCompiler(
            2,
            new ReggieNativeCompileBudget(13),
            request -> {
              admissions.incrementAndGet();
              return ReggieCompiledPattern.tryCompileNative(request);
            });

    ReggieCompilationResult admitted =
        compiler.tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE));
    assertTrue(admitted.isAdmitted());
    int registrations = compiler.inFlightRegistrations();

    ReggieCompilationResult rejected =
        compiler.tryCompile(new ReggieCompileRequest("x".repeat(14), ReggieCompileFlag.NONE));
    assertFalse(rejected.isAdmitted());
    assertEquals(ReggieCompilationRejection.SOURCE_TOO_LONG, rejected.rejection());
    assertThrows(IllegalStateException.class, rejected::pattern);
    assertEquals(1, admissions.get());
    assertEquals(registrations, compiler.inFlightRegistrations());
    assertEquals(1, compiler.cacheSize());
    assertEquals(legacyPatterns, RuntimeCompiler.cacheSize());
    assertEquals(legacyStructures, RuntimeCompiler.structuralCacheSize());
  }

  @Test
  void validatesBudgetAndUsesUtf16SourceLength() {
    assertThrows(IllegalArgumentException.class, () -> new ReggieNativeCompileBudget(0));
    ReggieCompiledPatternCompiler compiler =
        new ReggieCompiledPatternCompiler(1, new ReggieNativeCompileBudget(1));
    ReggieCompilationResult result =
        compiler.tryCompile(new ReggieCompileRequest("😀", ReggieCompileFlag.NONE));
    assertEquals(ReggieCompilationRejection.SOURCE_TOO_LONG, result.rejection());
  }

  @Test
  void concurrentOverBudgetRequestsDoNotEnterInflightOrOtherInstance() throws Exception {
    ReggieCompiledPatternCompiler limited =
        new ReggieCompiledPatternCompiler(1, new ReggieNativeCompileBudget(1));
    ReggieCompiledPatternCompiler independent = new ReggieCompiledPatternCompiler(1);
    ReggieCompiledPattern preserved =
        independent
            .tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE))
            .pattern();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ReggieCompilationResult> first =
          executor.submit(
              () -> limited.tryCompile(new ReggieCompileRequest("xx", ReggieCompileFlag.NONE)));
      Future<ReggieCompilationResult> second =
          executor.submit(
              () -> limited.tryCompile(new ReggieCompileRequest("yy", ReggieCompileFlag.NONE)));
      assertEquals(ReggieCompilationRejection.SOURCE_TOO_LONG, first.get().rejection());
      assertEquals(ReggieCompilationRejection.SOURCE_TOO_LONG, second.get().rejection());
      assertEquals(0, limited.inFlightRegistrations());
      assertEquals(0, limited.cacheSize());
      assertEquals(1, independent.cacheSize());
      assertTrue(
          preserved
              == independent
                  .tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE))
                  .pattern());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void boundarySizedRequestRetainsSingleFlightBehavior() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch waiter = new CountDownLatch(1);
    AtomicInteger admissions = new AtomicInteger();
    ReggieCompiledPatternCompiler compiler =
        new ReggieCompiledPatternCompiler(
            2,
            new ReggieNativeCompileBudget(13),
            request -> {
              admissions.incrementAndGet();
              started.countDown();
              await(release);
              return ReggieCompiledPattern.tryCompileNative(request);
            },
            waiter::countDown);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      ReggieCompileRequest request =
          new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE);
      Future<ReggieCompilationResult> first = executor.submit(() -> compiler.tryCompile(request));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Future<ReggieCompilationResult> second = executor.submit(() -> compiler.tryCompile(request));
      assertTrue(waiter.await(5, TimeUnit.SECONDS));
      release.countDown();
      assertTrue(first.get().isAdmitted());
      assertTrue(second.get().isAdmitted());
      assertEquals(1, admissions.get());
    } finally {
      executor.shutdownNow();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new AssertionError("timed out waiting for admission");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
