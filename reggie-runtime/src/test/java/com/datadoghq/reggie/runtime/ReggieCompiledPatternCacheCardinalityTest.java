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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReggieCompiledPatternCacheCardinalityTest {
  @AfterEach
  void clearLegacyCaches() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void highCardinalityNativeStreamPlateausAtCapacityWithoutLegacyCacheMutation() {
    int legacyPatterns = RuntimeCompiler.cacheSize();
    int legacyStructures = RuntimeCompiler.structuralCacheSize();
    ReggieCompiledPatternCompiler compiler = new ReggieCompiledPatternCompiler(3);
    ReggieCompiledPattern first = compile(compiler, 0);
    for (int index = 1; index <= 127; index++) {
      assertTrue(compile(compiler, index) != null);
      assertTrue(compiler.cacheSize() <= 3);
    }
    ReggieCompiledPattern recompiled = compile(compiler, 0);
    assertNotSame(first, recompiled);
    assertEquals(3, compiler.cacheSize());
    assertEquals(legacyPatterns, RuntimeCompiler.cacheSize());
    assertEquals(legacyStructures, RuntimeCompiler.structuralCacheSize());

    ReggieMatchState state = first.newState();
    CharSequence input = new GuardedSequence("prefix0value");
    assertTrue(state.matches(input, 0, input.length()));
    assertEquals(7, state.start("value"));
    assertEquals(12, state.end("value"));
  }

  @Test
  void concurrentHighCardinalityStreamsStayBoundedAndIsolated() throws Exception {
    int legacyPatterns = RuntimeCompiler.cacheSize();
    int legacyStructures = RuntimeCompiler.structuralCacheSize();
    ReggieCompiledPatternCompiler compiler = new ReggieCompiledPatternCompiler(4);
    ExecutorService executor = Executors.newFixedThreadPool(4);
    CountDownLatch ready = new CountDownLatch(4);
    CountDownLatch start = new CountDownLatch(1);
    try {
      @SuppressWarnings("unchecked")
      Future<Void>[] workers = new Future[4];
      for (int worker = 0; worker < workers.length; worker++) {
        int offset = worker * 100;
        workers[worker] =
            executor.submit(
                () -> {
                  ready.countDown();
                  await(start);
                  for (int index = offset; index < offset + 100; index++) {
                    assertTrue(compile(compiler, index) != null);
                    assertTrue(compiler.cacheSize() <= 4);
                  }
                  return null;
                });
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      for (Future<Void> worker : workers) worker.get(15, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertTrue(compiler.cacheSize() <= 4);
    assertEquals(legacyPatterns, RuntimeCompiler.cacheSize());
    assertEquals(legacyStructures, RuntimeCompiler.structuralCacheSize());
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS))
        throw new AssertionError("timed out waiting for start");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static ReggieCompiledPattern compile(ReggieCompiledPatternCompiler compiler, int index) {
    ReggieCompilationResult result =
        compiler.tryCompile(
            new ReggieCompileRequest("prefix" + index + "(?<value>\\S+)", ReggieCompileFlag.NONE));
    assertTrue(result.isAdmitted(), () -> "rejection: " + result.rejection());
    return result.pattern();
  }

  private static final class GuardedSequence implements CharSequence {
    private final String value;

    GuardedSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new AssertionError("matching must not materialize a subsequence");
    }

    @Override
    public String toString() {
      throw new AssertionError("matching must not materialize input");
    }
  }
}
