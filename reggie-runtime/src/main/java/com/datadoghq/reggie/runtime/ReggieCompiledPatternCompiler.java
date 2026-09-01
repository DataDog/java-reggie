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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/** Bounded instance-owned cache for native named linear-token-sequence compilation. */
public final class ReggieCompiledPatternCompiler {
  static final ReggieNativeCompileBudget DEFAULT_BUDGET = new ReggieNativeCompileBudget(16_384);

  /**
   * Number of independently-locked cache segments used once {@code maximumEntries} is large enough
   * to make striping worthwhile. Splitting the cache into stripes means concurrent lookups for keys
   * in different segments never contend on the same monitor, unlike a single global lock guarding
   * one access-ordered {@link LinkedHashMap}. Below this threshold a single segment is used
   * instead, since a segment holding only one or two entries would defeat the point of caching
   * (frequent evictions from key collisions across segments).
   */
  private static final int STRIPE_COUNT = 16;

  private final int maximumEntries;
  private final ReggieNativeCompileBudget budget;
  private final Segment[] segments;
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
    this(maximumEntries, DEFAULT_BUDGET, ReggieCompiledPattern::tryCompileNative, () -> {});
  }

  public ReggieCompiledPatternCompiler(int maximumEntries, ReggieNativeCompileBudget budget) {
    this(maximumEntries, budget, ReggieCompiledPattern::tryCompileNative, () -> {});
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
    int stripeCount = maximumEntries >= STRIPE_COUNT ? STRIPE_COUNT : 1;
    int perSegmentCapacity = (maximumEntries + stripeCount - 1) / stripeCount;
    this.segments = new Segment[stripeCount];
    for (int i = 0; i < stripeCount; i++) {
      segments[i] = new Segment(perSegmentCapacity);
    }
  }

  private Segment segmentFor(ReggieCompileRequest request) {
    return segments[Math.floorMod(request.hashCode(), segments.length)];
  }

  public ReggieCompilationResult tryCompile(ReggieCompileRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.source().length() > budget.maximumSourceLength()) {
      return ReggieCompilationResult.rejected(ReggieCompilationRejection.SOURCE_TOO_LONG);
    }
    Segment segment = segmentFor(request);
    ReggieCompiledPattern cached = segment.get(request);
    if (cached != null) return ReggieCompilationResult.admitted(cached);
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
      cached = segment.get(request);
      if (cached != null) {
        ReggieCompilationResult result = ReggieCompilationResult.admitted(cached);
        mine.complete(result);
        return result;
      }
      ReggieCompilationResult result = admission.apply(request);
      if (result.isAdmitted()) {
        segment.put(request, result.pattern());
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
    int size = 0;
    for (Segment segment : segments) {
      size += segment.size();
    }
    return size;
  }

  public void clearCache() {
    for (Segment segment : segments) {
      segment.clear();
    }
  }

  int inFlightRegistrations() {
    synchronized (this) {
      return inFlightRegistrations;
    }
  }

  private static final long AWAIT_TIMEOUT_SECONDS = 30;

  private static ReggieCompilationResult await(CompletableFuture<ReggieCompilationResult> future) {
    try {
      return future.get(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) throw runtimeException;
      if (cause instanceof Error error) throw error;
      throw new RuntimeException(cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("interrupted while awaiting in-flight compilation", e);
    } catch (TimeoutException e) {
      throw new RuntimeException("timed out awaiting in-flight compilation", e);
    }
  }

  /** One independently-locked, bounded, access-ordered LRU shard of the compilation cache. */
  private static final class Segment {
    private final int capacity;
    private final Map<ReggieCompileRequest, ReggieCompiledPattern> entries =
        new LinkedHashMap<>(16, 0.75f, true);

    Segment(int capacity) {
      this.capacity = capacity;
    }

    synchronized ReggieCompiledPattern get(ReggieCompileRequest request) {
      return entries.get(request);
    }

    synchronized void put(ReggieCompileRequest request, ReggieCompiledPattern pattern) {
      entries.put(request, pattern);
      while (entries.size() > capacity) entries.remove(entries.keySet().iterator().next());
    }

    synchronized int size() {
      return entries.size();
    }

    synchronized void clear() {
      entries.clear();
    }
  }
}
