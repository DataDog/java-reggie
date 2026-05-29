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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lazily-materialized DFA cache over NFA execution.
 *
 * <p>On first encounter of a (DFA-state, character) pair the NFA is stepped and the resulting
 * state-set is interned as a new DFA state; on subsequent encounters the cached state id is
 * returned directly from a per-state {@code int[128]} ASCII transition table. The cache is bounded
 * at {@link #DEFAULT_CAP} DFA states; once full it freezes and remaining inputs fall back to plain
 * NFA stepping.
 *
 * <p>The lazily-materialized DFA technique and the state-set interning key idea are adapted from
 * Mike Snoyman's {@code lazydfa} implementation in the {@code glob_perf} benchmark suite ({@code
 * DataDog/experimental/users/dangermike/glob_perf}).
 */
public final class LazyDFACache {

  /**
   * Array-element VarHandle for {@code int[][]} slots. Used to publish newly filled {@code
   * int[128]} tables with release semantics and to read them with acquire semantics, establishing a
   * happens-before between the writer (in {@link #cacheEntry}) and any reader (in {@link #matches}
   * or in the generated inlined hot loop) on all JMM-conformant platforms including ARM.
   */
  static final VarHandle TABLES_VH;

  /**
   * Array-element VarHandle for {@code int[]} slots. Used to write individual transition entries
   * into an already-published ASCII table with release semantics ({@link #cacheEntry}) and to read
   * them with acquire semantics ({@link #matches} and the generated hot loop). This pairs with the
   * {@code nfaStateSets[newId]} and {@code accepting[newId]} initialization done inside {@code
   * computeIfAbsent} on the writer thread, ensuring those writes are visible to any reader that
   * subsequently observes the new DFA state id via {@code getAcquire}. On x86/TSO these compile to
   * plain store/load (zero overhead); on ARM they emit {@code stlr}/{@code ldar}.
   */
  static final VarHandle INT_ARRAY_VH;

  static {
    try {
      TABLES_VH = MethodHandles.arrayElementVarHandle(int[][].class);
      INT_ARRAY_VH = MethodHandles.arrayElementVarHandle(int[].class);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static final int DEFAULT_CAP = 4096;
  static final int UNCACHED = -1;
  static final int DEAD = -2;
  static final int FALLBACK = -3;

  private final ConcurrentHashMap<StateSetKey, Integer> stateIndex;
  // package-private so the generated class (same package) can inline the hot loop
  final int[][] asciiTables; // asciiTables[id] = int[128] or null
  final int[][] nfaStateSets; // nfaStateSets[id] = sorted NFA state IDs
  final boolean[] accepting;
  private final int[] acceptStateIds;
  private final AtomicInteger nextId;
  private volatile boolean frozen;
  private final int cap;

  public LazyDFACache(int[] startStateSet, int[] acceptStateIds) {
    this(startStateSet, acceptStateIds, DEFAULT_CAP);
  }

  // package-private for tests
  LazyDFACache(int[] startStateSet, int[] acceptStateIds, int cap) {
    this.cap = cap;
    this.acceptStateIds = acceptStateIds;
    this.stateIndex = new ConcurrentHashMap<>();
    this.asciiTables = new int[cap][];
    this.nfaStateSets = new int[cap][];
    this.accepting = new boolean[cap];
    this.nextId = new AtomicInteger(1); // 0 = start state
    nfaStateSets[0] = startStateSet;
    accepting[0] = containsAny(startStateSet, acceptStateIds);
    stateIndex.put(new StateSetKey(startStateSet), 0);
  }

  public boolean matches(String input, NfaStep nfaStep) {
    if (input == null) return false;
    int dfaState = 0;
    for (int pos = 0; pos < input.length(); pos++) {
      int c = input.charAt(pos);
      int[] table = (int[]) TABLES_VH.getAcquire(asciiTables, dfaState);
      int next = (table != null && c < 128) ? (int) INT_ARRAY_VH.getAcquire(table, c) : UNCACHED;
      if (next == UNCACHED) {
        next = lookupOrCompute(dfaState, c, nfaStep);
      }
      if (next == DEAD) return false;
      if (next == FALLBACK) return nfaFallbackMatch(input, pos, nfaStateSets[dfaState], nfaStep);
      dfaState = next;
    }
    return accepting[dfaState];
  }

  int lookupOrCompute(int state, int c, NfaStep nfaStep) {
    int[] nextSet = nfaStep.apply(nfaStateSets[state], c);
    if (nextSet.length == 0) {
      cacheEntry(state, c, DEAD);
      return DEAD;
    }

    StateSetKey key = new StateSetKey(nextSet);
    Integer id = stateIndex.get(key);

    if (id == null && !frozen) {
      id =
          stateIndex.computeIfAbsent(
              key,
              k -> {
                int newId = nextId.getAndIncrement();
                if (newId < cap) {
                  nfaStateSets[newId] = k.getStates();
                  accepting[newId] = containsAny(k.getStates(), acceptStateIds);
                  return newId;
                }
                return null; // over cap: don't insert, keeps map bounded at cap entries
              });
      if (id == null) {
        frozen = true;
        return FALLBACK;
      }
    }
    if (id == null) return FALLBACK;

    cacheEntry(state, c, id);
    return id;
  }

  void cacheEntry(int state, int c, int value) {
    if (c >= 128) return;
    int[] table = asciiTables[state];
    if (table == null) {
      int[] t = new int[128];
      Arrays.fill(t, UNCACHED);
      t[c] = value;
      // setRelease: all writes to t[] above are visible to any thread that subsequently
      // observes the non-null slot via getAcquire. This establishes a proper happens-before
      // edge on weakly-ordered platforms (ARM/RISC-V), preventing a stale 0 from being
      // treated as DFA state 0.
      TABLES_VH.setRelease(asciiTables, state, t);
    } else {
      // setRelease: pairs with getAcquire in the hot loop; ensures nfaStateSets/accepting
      // initialization (written on the computeIfAbsent thread) is visible to the reader
      // that sees this new id on ARM/RISC-V. Idempotent: same key always maps to same value.
      INT_ARRAY_VH.setRelease(table, c, value);
    }
  }

  /**
   * Bounded lazy-DFA match over {@code input[start, end)} without copying the substring. Used by
   * the String overload of {@code matchBounded} so that the delegated {@code findMatchFrom} loop
   * never allocates a substring for each candidate region.
   */
  public boolean matchesBounded(String input, int start, int end, NfaStep nfaStep) {
    int dfaState = 0;
    for (int pos = start; pos < end; pos++) {
      int c = input.charAt(pos);
      int[] table = (int[]) TABLES_VH.getAcquire(asciiTables, dfaState);
      int next = (table != null && c < 128) ? (int) INT_ARRAY_VH.getAcquire(table, c) : UNCACHED;
      if (next == UNCACHED) {
        next = lookupOrCompute(dfaState, c, nfaStep);
      }
      if (next == DEAD) return false;
      if (next == FALLBACK) {
        return nfaFallbackMatchBounded(input, pos, end, nfaStateSets[dfaState], nfaStep);
      }
      dfaState = next;
    }
    return accepting[dfaState];
  }

  boolean nfaFallbackMatch(String input, int fromPos, int[] nfaSet, NfaStep nfaStep) {
    int[] states = nfaStep.apply(nfaSet, input.charAt(fromPos));
    for (int pos = fromPos + 1; pos < input.length(); pos++) {
      if (states.length == 0) return false;
      states = nfaStep.apply(states, input.charAt(pos));
    }
    return states.length > 0 && containsAny(states, acceptStateIds);
  }

  private boolean nfaFallbackMatchBounded(
      String input, int fromPos, int end, int[] nfaSet, NfaStep nfaStep) {
    int[] states = nfaStep.apply(nfaSet, input.charAt(fromPos));
    for (int pos = fromPos + 1; pos < end; pos++) {
      if (states.length == 0) return false;
      states = nfaStep.apply(states, input.charAt(pos));
    }
    return states.length > 0 && containsAny(states, acceptStateIds);
  }

  // package-private for tests
  boolean isFrozen() {
    return frozen;
  }

  private static boolean containsAny(int[] set, int[] targets) {
    for (int t : targets) {
      for (int s : set) {
        if (s == t) return true;
      }
    }
    return false;
  }
}
