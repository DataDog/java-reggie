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

public final class LazyDFACache {

  /**
   * Array-element VarHandle for {@code int[][]} slots. Used to publish newly filled {@code
   * int[128]} tables with release semantics and to read them with acquire semantics, establishing a
   * happens-before between the writer (in {@link #cacheEntry}) and any reader (in {@link #matches}
   * or in the generated inlined hot loop) on all JMM-conformant platforms including ARM.
   */
  static final VarHandle TABLES_VH;

  static {
    try {
      TABLES_VH = MethodHandles.arrayElementVarHandle(int[][].class);
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
      int next = (table != null && c < 128) ? table[c] : UNCACHED;
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
      table[c] = value; // idempotent: same key always maps to same value
    }
  }

  boolean nfaFallbackMatch(String input, int fromPos, int[] nfaSet, NfaStep nfaStep) {
    int[] states = nfaStep.apply(nfaSet, input.charAt(fromPos));
    for (int pos = fromPos + 1; pos < input.length(); pos++) {
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
