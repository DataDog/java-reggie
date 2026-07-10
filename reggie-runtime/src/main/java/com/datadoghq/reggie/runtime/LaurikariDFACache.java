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
 * Laurikari-style lazily-materialized DFA cache: same lazily-materialized/ASCII-table caching
 * structure as {@link LazyDFACache}, but each interned DFA state is keyed on (NFA state subset,
 * per-state register vector) via {@link LaurikariStateSetKey} instead of on the subset alone.
 *
 * <p>Generalized from Phase 0's single-scalar "age" register to a fixed-size {@code int[]} register
 * file per state (impl-plan Task 0.5.1). This class is deliberately agnostic to what the vector
 * holds or how ties between colliding arrivals were resolved — that is entirely the calling {@link
 * LaurikariNfaStep}'s business (see its class javadoc for the two coexisting tie-break
 * disciplines). This class only interns whatever {@code (states, regs)} pairs the step function
 * returns and caches transitions between the resulting DFA states.
 *
 * <p>{@link #findLeftmostStart} is Phase 0's specific find()-localization entry point: it assumes
 * register slot 0 is an "age" value (candidate's characters-consumed-since-self-anchoring) and
 * recovers the leftmost start as {@code (pos + 1) - age} the moment an accept state is reached. It
 * is meaningless to call this method with a step function that doesn't populate slot 0 this way
 * (e.g. Phase 0.5's multi-tag capture checkpoint, which instead reads {@link #acceptRegs}
 * directly).
 */
final class LaurikariDFACache {

  /** Same VarHandle/publication discipline as {@link LazyDFACache#TABLES_VH}. */
  static final VarHandle TABLES_VH;

  /** Same VarHandle/publication discipline as {@link LazyDFACache#INT_ARRAY_VH}. */
  static final VarHandle INT_ARRAY_VH;

  /** Same architecture gate as {@link LazyDFACache#NEEDS_INT_ARRAY_ACQUIRE}. */
  static final boolean NEEDS_INT_ARRAY_ACQUIRE;

  static {
    try {
      TABLES_VH = MethodHandles.arrayElementVarHandle(int[][].class);
      INT_ARRAY_VH = MethodHandles.arrayElementVarHandle(int[].class);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    String arch = System.getProperty("os.arch", "");
    NEEDS_INT_ARRAY_ACQUIRE =
        !arch.equals("x86")
            && !arch.equals("x86_64")
            && !arch.equals("amd64")
            && !arch.equals("i386");
  }

  static final int DEFAULT_CAP = 4096;
  static final int UNCACHED = -1;
  static final int DEAD = -2;
  static final int FALLBACK = -3;

  private final ConcurrentHashMap<LaurikariStateSetKey, Integer> stateIndex;
  final int[][] asciiTables; // asciiTables[id] = int[128] or null
  final int[][]
      nfaStateSets; // nfaStateSets[id] = NFA state IDs, order per the driving step's convention
  final int[][][] regs; // regs[id][i] = register vector for nfaStateSets[id][i]
  final boolean[] accepting;
  private final int[] acceptStateIds;
  private final AtomicInteger nextId;
  private volatile boolean frozen;
  private final int cap;

  LaurikariDFACache(int[] startStateSet, int[][] startRegs, int[] acceptStateIds) {
    this(startStateSet, startRegs, acceptStateIds, DEFAULT_CAP);
  }

  // package-private for tests
  LaurikariDFACache(int[] startStateSet, int[][] startRegs, int[] acceptStateIds, int cap) {
    this.cap = cap;
    this.acceptStateIds = acceptStateIds;
    this.stateIndex = new ConcurrentHashMap<>();
    this.asciiTables = new int[cap][];
    this.nfaStateSets = new int[cap][];
    this.regs = new int[cap][][];
    this.accepting = new boolean[cap];
    this.nextId = new AtomicInteger(1); // 0 = start state
    nfaStateSets[0] = startStateSet;
    regs[0] = startRegs;
    accepting[0] = firstAcceptIndex(startStateSet) >= 0;
    stateIndex.put(new LaurikariStateSetKey(startStateSet, startRegs), 0);
  }

  /**
   * O(n) DFA-based search for the leftmost start position of the first completable match in {@code
   * input}, scanning left-to-right from {@code scanFrom} while maintaining a single interned
   * (subset, register-vector) DFA state.
   *
   * <p>Phase 0-specific: assumes register slot 0 is an "age" value and {@code step} merges a fresh
   * self-anchored (age 0) candidate into every application (see {@link LaurikariNfaStep}'s class
   * javadoc, value-based discipline). Unlike {@link LazyDFACache#findFrom}, there is no separate
   * {@code matchStart}/restart-on-DEAD bookkeeping: the current DFA state always reflects every
   * live start candidate at once, and the leftmost start is recovered directly from the winning age
   * the moment an accepting state is reached: {@code (pos + 1) - age}.
   *
   * @return the leftmost match start position (0-based), or {@code -1} if no match exists at or
   *     after {@code scanFrom}
   */
  int findLeftmostStart(String input, int scanFrom, LaurikariNfaStep step) {
    if (input == null) return -1;
    int len = input.length();
    if (accepting[0]) {
      // Zero-length match: state 0 (freshly self-anchored, age 0) is already accepting.
      return scanFrom - acceptAge(0);
    }
    int dfaState = 0;
    for (int pos = scanFrom; pos < len; pos++) {
      int c = input.charAt(pos);
      int[] table =
          NEEDS_INT_ARRAY_ACQUIRE
              ? (int[]) TABLES_VH.getAcquire(asciiTables, dfaState)
              : asciiTables[dfaState];
      int next =
          (table != null && c < 128)
              ? (NEEDS_INT_ARRAY_ACQUIRE ? (int) INT_ARRAY_VH.getAcquire(table, c) : table[c])
              : UNCACHED;
      if (next == UNCACHED) {
        next = lookupOrCompute(dfaState, c, step);
      }
      if (next == FALLBACK) {
        // Cache is frozen; delegate the remaining scan to a plain (uncached) NFA-level
        // re-simulation that tracks ages the same way the cached transitions do. This does NOT
        // need LazyDFACache.nfaFallbackFindFrom's O(n^2) retry-per-start-position loop: because
        // every LaurikariNfaStep application already merges in a fresh self-anchored (age 0)
        // candidate, a single forward pass carrying per-state ages is enough to recover the true
        // leftmost start the moment an accepting state is reached.
        return nfaFallbackFindLeftmostStart(
            input, pos, nfaStateSets[dfaState], regs[dfaState], step);
      }
      if (next == DEAD) {
        // Unreachable in practice: LaurikariNfaStep's step 3/4 always merge in a fresh
        // closure(startNfaState) at age 0, so the merged result can only be empty if the start
        // state's own closure is empty (a degenerate NFA). Kept for parity with LazyDFACache's
        // DEAD handling and as a defensive stop rather than continuing with an unusable state.
        return -1;
      }
      dfaState = next;
      if (accepting[dfaState]) {
        return (pos + 1) - acceptAge(dfaState);
      }
    }
    return -1;
  }

  int lookupOrCompute(int state, int c, LaurikariNfaStep step) {
    LaurikariStepResult result = step.apply(nfaStateSets[state], regs[state], c);
    if (result.states.length == 0) {
      cacheEntry(state, c, DEAD);
      return DEAD;
    }

    LaurikariStateSetKey key = new LaurikariStateSetKey(result.states, result.regs);
    Integer id = stateIndex.get(key);

    if (id == null && !frozen) {
      id =
          stateIndex.computeIfAbsent(
              key,
              k -> {
                int newId = nextId.getAndIncrement();
                if (newId < cap) {
                  nfaStateSets[newId] = k.getStates();
                  regs[newId] = k.getRegs();
                  accepting[newId] = firstAcceptIndex(k.getStates()) >= 0;
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
      TABLES_VH.setRelease(asciiTables, state, t);
    } else {
      INT_ARRAY_VH.setRelease(table, c, value);
    }
  }

  /**
   * NFA fallback for {@link #findLeftmostStart} when the cache is frozen. Continues stepping the
   * raw (state, register) set forward from {@code frozenPos} using {@code step} directly (no
   * caching), returning as soon as an accepting state is reached — see {@link #findLeftmostStart}'s
   * FALLBACK comment for why a single pass suffices here, unlike {@link LazyDFACache}'s
   * boolean-only fallback.
   *
   * @param frozenPos position in the input where the FALLBACK transition was encountered
   * @param nfaStates NFA state subset at {@code frozenPos} (before consuming that position's char)
   * @param nfaRegs {@code nfaStates}' parallel register vectors
   */
  private int nfaFallbackFindLeftmostStart(
      String input, int frozenPos, int[] nfaStates, int[][] nfaRegs, LaurikariNfaStep step) {
    int len = input.length();
    int[] states = nfaStates;
    int[][] r = nfaRegs;
    for (int pos = frozenPos; pos < len; pos++) {
      LaurikariStepResult result = step.apply(states, r, input.charAt(pos));
      states = result.states;
      r = result.regs;
      if (states.length == 0) {
        // Unreachable in practice; see findLeftmostStart's DEAD comment.
        return -1;
      }
      int idx = firstAcceptIndex(states);
      if (idx >= 0) {
        return (pos + 1) - r[idx][0];
      }
    }
    return -1;
  }

  /**
   * @return the age (register slot 0) of the winning accept-state candidate present in the DFA
   *     state {@code dfaState}'s subset, per Phase 0's value-based tie-break (larger age wins among
   *     accept-state members — see {@link #firstAcceptIndex}'s javadoc for why "first" and "larger
   *     age" coincide when this method's caller uses this class as intended).
   */
  private int acceptAge(int dfaState) {
    int idx = firstAcceptIndex(nfaStateSets[dfaState]);
    return regs[dfaState][idx][0];
  }

  /**
   * @return the register vector of the accept-state member of {@code dfaState}'s subset (per Task
   *     0.5.3's multi-tag capture checkpoint, which reads this directly instead of {@link
   *     #findLeftmostStart}'s age-specific math), or {@code null} if no accept state is present.
   */
  int[] acceptRegs(int dfaState) {
    int idx = firstAcceptIndex(nfaStateSets[dfaState]);
    return idx < 0 ? null : regs[dfaState][idx];
  }

  /**
   * @return the index within {@code states} of the first accept-state member (by array order), or
   *     {@code -1} if none of {@code states} is an accept state. "First" is safe for both
   *     coexisting tie-break disciplines: Phase 0's value-based driver never presents more than one
   *     accept-state member per subset in practice (a single shared NFA accept state per pattern,
   *     see {@code LaurikariDFACacheTest}'s traced examples), so "first" and "largest age" coincide
   *     trivially; Phase 0.5's priority-order driver's whole-mapping-discard already guarantees at
   *     most one entry per NFA state id, and "first" here means "highest priority", matching {@code
   *     PikeVMMatcher}'s "first accepting thread in priority order is highest priority" convention.
   */
  private int firstAcceptIndex(int[] states) {
    for (int i = 0; i < states.length; i++) {
      if (isAcceptState(states[i])) return i;
    }
    return -1;
  }

  private boolean isAcceptState(int state) {
    for (int t : acceptStateIds) {
      if (t == state) return true;
    }
    return false;
  }

  // package-private for tests: current number of materialized (interned) DFA states, used to
  // check that the cache stays bounded by NFA structure rather than growing with input length.
  int stateCount() {
    return Math.min(nextId.get(), cap);
  }
}
