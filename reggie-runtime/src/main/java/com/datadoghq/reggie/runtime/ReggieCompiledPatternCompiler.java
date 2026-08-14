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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Bounded instance-owned cache for native named linear-token-sequence compilation. */
public final class ReggieCompiledPatternCompiler {
  private static final ReggieNativeCompileBudget DEFAULT_BUDGET =
      new ReggieNativeCompileBudget(16_384);
  private final int maximumEntries;
  private final ReggieNativeCompileBudget budget;
  private final Map<ReggieCompileRequest, ReggieCompiledPattern> cache =
      new LinkedHashMap<>(16, 0.75f, true);
  private final ConcurrentHashMap<ReggieCompileRequest, CompletableFuture<ReggieCompilationResult>>
      inFlight = new ConcurrentHashMap<>();
  private final Function<ReggieCompileRequest, ReggieCompilationResult> admission;
  private final Runnable waiterArrived;
  private int inFlightRegistrations;

  /**
   * Creates a compiler with the supplied cache capacity and a 16,384 UTF-16-code-unit source
   * budget.
   */
  public ReggieCompiledPatternCompiler(int maximumEntries) {
    this(maximumEntries, DEFAULT_BUDGET, ReggieCompiledPattern::tryCompileNative);
  }

  public ReggieCompiledPatternCompiler(int maximumEntries, ReggieNativeCompileBudget budget) {
    this(maximumEntries, budget, ReggieCompiledPattern::tryCompileNative);
  }

  ReggieCompiledPatternCompiler(
      int maximumEntries, Function<ReggieCompileRequest, ReggieCompilationResult> admission) {
    this(maximumEntries, DEFAULT_BUDGET, admission, () -> {});
  }

  ReggieCompiledPatternCompiler(
      int maximumEntries,
      ReggieNativeCompileBudget budget,
      Function<ReggieCompileRequest, ReggieCompilationResult> admission) {
    this(maximumEntries, budget, admission, () -> {});
  }

  ReggieCompiledPatternCompiler(
      int maximumEntries,
      Function<ReggieCompileRequest, ReggieCompilationResult> admission,
      Runnable waiterArrived) {
    this(maximumEntries, DEFAULT_BUDGET, admission, waiterArrived);
  }

  ReggieCompiledPatternCompiler(
      int maximumEntries,
      ReggieNativeCompileBudget budget,
      Function<ReggieCompileRequest, ReggieCompilationResult> admission,
      Runnable waiterArrived) {
    if (maximumEntries <= 0) throw new IllegalArgumentException("maximumEntries must be positive");
    this.maximumEntries = maximumEntries;
    this.budget = Objects.requireNonNull(budget, "budget");
    this.admission = Objects.requireNonNull(admission, "admission");
    this.waiterArrived = Objects.requireNonNull(waiterArrived, "waiterArrived");
  }

  public ReggieCompilationResult tryCompile(ReggieCompileRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.source().length() > budget.maximumSourceLength()) {
      return ReggieCompilationResult.rejected(ReggieCompilationRejection.SOURCE_TOO_LONG);
    }
    synchronized (cache) {
      ReggieCompiledPattern cached = cache.get(request);
      if (cached != null) return ReggieCompilationResult.admitted(cached);
    }
    CompletableFuture<ReggieCompilationResult> mine = new CompletableFuture<>();
    synchronized (this) {
      inFlightRegistrations++;
    }
    CompletableFuture<ReggieCompilationResult> existing = inFlight.putIfAbsent(request, mine);
    if (existing != null) {
      waiterArrived.run();
      return await(existing);
    }
    try {
      synchronized (cache) {
        ReggieCompiledPattern cached = cache.get(request);
        if (cached != null) {
          ReggieCompilationResult result = ReggieCompilationResult.admitted(cached);
          mine.complete(result);
          return result;
        }
      }
      ReggieCompilationResult result = admission.apply(request);
      if (result.isAdmitted()) {
        synchronized (cache) {
          cache.put(request, result.pattern());
          while (cache.size() > maximumEntries) cache.remove(cache.keySet().iterator().next());
        }
      }
      mine.complete(result);
      return result;
    } catch (Throwable failure) {
      mine.completeExceptionally(failure);
      if (failure instanceof RuntimeException runtimeException) throw runtimeException;
      if (failure instanceof Error error) throw error;
      throw new RuntimeException(failure);
    } finally {
      inFlight.remove(request, mine);
    }
  }

  public int cacheSize() {
    synchronized (cache) {
      return cache.size();
    }
  }

  public void clearCache() {
    synchronized (cache) {
      cache.clear();
    }
  }

  int inFlightRegistrations() {
    synchronized (this) {
      return inFlightRegistrations;
    }
  }

  private static ReggieCompilationResult await(CompletableFuture<ReggieCompilationResult> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      if (cause instanceof Error error) throw error;
      throw new RuntimeException(cause);
    }
  }
}
