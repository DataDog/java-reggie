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

  /**
   * Number of {@link #lookaheadClass} buckets. See that method's javadoc for why exactly these 3
   * suffice to make every one of the 6 new anchor types' {@code checkAnchor} outcome a pure
   * function of {@code (consumed char, lookahead class)} away from {@code regionEnd}.
   */
  private static final int LOOKAHEAD_CLASSES = 3;

  private static final int LOOKAHEAD_WORD = 0;
  private static final int LOOKAHEAD_NEWLINE = 1;
  private static final int LOOKAHEAD_OTHER = 2;

  /**
   * Below this distance from {@code regionEnd}, {@code checkAnchor}'s {@code END}/{@code
   * STRING_END}/{@code STRING_END_ABSOLUTE} cases depend on more than just {@link #lookaheadClass}
   * (up to 2 characters of literal lookahead for a {@code \r\n} pair, plus the {@code pos ==
   * regionEnd} case itself) — see {@link #lookaheadClass}'s javadoc. Transitions this close to the
   * end are always recomputed live, never memoized.
   */
  private static final int ANCHOR_LOOKAHEAD_LIVE_MARGIN = 2;

  private final ConcurrentHashMap<LaurikariStateSetKey, Integer> stateIndex;
  final int[][] asciiTables; // asciiTables[id] = int[128] or null

  /**
   * {@code anchorLookaheadTables[id]} = {@code int[128 * LOOKAHEAD_CLASSES]} or {@code null} — the
   * anchor-sensitive counterpart to {@link #asciiTables}, indexed by {@code consumed-char *
   * LOOKAHEAD_CLASSES + lookaheadClass(...)}. See {@link #step}.
   */
  final int[][] anchorLookaheadTables;

  final int[][]
      nfaStateSets; // nfaStateSets[id] = NFA state IDs, order per the driving step's convention
  final int[][][] regs; // regs[id][i] = register vector for nfaStateSets[id][i]
  final boolean[] accepting;

  /**
   * {@code anchorSensitive[id]} is true when DFA state {@code id}'s NFA subset touches at least one
   * of the 6 position-dependent anchor types ({@code END}, {@code STRING_END}, {@code
   * STRING_END_ABSOLUTE}, {@code END_MULTILINE}, {@code WORD_BOUNDARY}, {@code NON_WORD_BOUNDARY})
   * — see the TDFA Phase 2 end-anchor/{@code \b} extension design. Such states' outgoing
   * transitions must never be memoized in {@link #asciiTables} keyed on the consumed char alone
   * (the cached result would silently apply one call's anchor outcome to a different
   * position/call); {@link #step} instead memoizes them in {@link #anchorLookaheadTables},
   * additionally keyed on {@link #lookaheadClass}, except within {@link
   * #ANCHOR_LOOKAHEAD_LIVE_MARGIN} characters of {@code regionEnd}, where it always calls {@link
   * #lookupOrCompute} directly. Always all-false when {@code anchorBearingStates} is {@code null}
   * (every pre-extension caller), so behavior for those callers is byte-identical to before this
   * field existed.
   *
   * <p>{@link #lookupOrCompute}'s caching gate additionally checks this flag on the
   * <em>destination</em> state of a transition, not just the source: a source state with no
   * anchor-bearing member of its own can still transition into a destination whose subset newly
   * touches one (e.g. entering the first repetition of a one-or-more loop that feeds a trailing
   * anchor). {@code addClosure} adds an anchor-bearing NFA state to a closure unconditionally the
   * moment it becomes epsilon-reachable, so this is a purely structural, position-independent check
   * — see issue #108, where checking only the source let a live, position-specific {@code
   * checkAnchor} outcome get memoized under {@code (state, c)} and wrongly replayed at a later
   * position.
   */
  final boolean[] anchorSensitive;

  /**
   * Parallel to {@link LaurikariCaptureNfaStep#statesById}: {@code anchorBearingStates[nfaId]} is
   * true if that NFA state carries one of the 6 new anchor types. {@code null} for callers with no
   * anchor-sensitive states (every Phase 0/0.5 driver, and any {@code hasNewAnchor == false}
   * pattern) — then {@link #anchorSensitive} stays all-false without needing to consult this array.
   */
  private final boolean[] anchorBearingStates;

  private final int[] acceptStateIds;
  private final AtomicInteger nextId;
  private volatile boolean frozen;
  private final int cap;

  LaurikariDFACache(int[] startStateSet, int[][] startRegs, int[] acceptStateIds) {
    this(startStateSet, startRegs, acceptStateIds, null, DEFAULT_CAP);
  }

  LaurikariDFACache(
      int[] startStateSet, int[][] startRegs, int[] acceptStateIds, boolean[] anchorBearingStates) {
    this(startStateSet, startRegs, acceptStateIds, anchorBearingStates, DEFAULT_CAP);
  }

  // package-private for tests
  LaurikariDFACache(int[] startStateSet, int[][] startRegs, int[] acceptStateIds, int cap) {
    this(startStateSet, startRegs, acceptStateIds, null, cap);
  }

  // package-private for tests
  LaurikariDFACache(
      int[] startStateSet,
      int[][] startRegs,
      int[] acceptStateIds,
      boolean[] anchorBearingStates,
      int cap) {
    this.cap = cap;
    this.acceptStateIds = acceptStateIds;
    this.anchorBearingStates = anchorBearingStates;
    this.stateIndex = new ConcurrentHashMap<>();
    this.asciiTables = new int[cap][];
    this.anchorLookaheadTables = new int[cap][];
    this.nfaStateSets = new int[cap][];
    this.regs = new int[cap][][];
    this.accepting = new boolean[cap];
    this.anchorSensitive = new boolean[cap];
    this.nextId = new AtomicInteger(1); // 0 = start state
    nfaStateSets[0] = startStateSet;
    regs[0] = startRegs;
    accepting[0] = firstAcceptIndex(startStateSet) >= 0;
    anchorSensitive[0] = isAnchorSensitive(startStateSet);
    stateIndex.put(new LaurikariStateSetKey(startStateSet, startRegs), 0);
  }

  private boolean isAnchorSensitive(int[] states) {
    if (anchorBearingStates == null) return false;
    for (int s : states) {
      if (anchorBearingStates[s]) return true;
    }
    return false;
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
      int next = step(dfaState, c, step, input, pos + 1, len);
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

  /**
   * O(1) cached transition lookup, falling back to {@link #lookupOrCompute} only on a genuine cache
   * miss — the same ASCII-table-then-fallback check {@link #findLeftmostStart} inlines at the top
   * of its per-character loop, factored out so callers that need the full register vector (rather
   * than {@link #findLeftmostStart}'s Phase-0-specific single-age recovery) don't have to pay for a
   * closure recomputation (via {@link LaurikariNfaStep#apply}) and a hashed state-set lookup on
   * every character.
   */
  int step(int state, int c, LaurikariNfaStep nfaStep) {
    return step(state, c, nfaStep, "", 0, 0);
  }

  int step(int state, int c, LaurikariNfaStep nfaStep, String input, int pos, int regionEnd) {
    if (anchorSensitive[state]) {
      if (c >= 128 || regionEnd - pos <= ANCHOR_LOOKAHEAD_LIVE_MARGIN) {
        return lookupOrCompute(state, c, nfaStep, input, pos, regionEnd);
      }
      int cls = lookaheadClass(input, pos);
      int[] table =
          NEEDS_INT_ARRAY_ACQUIRE
              ? (int[]) TABLES_VH.getAcquire(anchorLookaheadTables, state)
              : anchorLookaheadTables[state];
      if (table != null) {
        int idx = c * LOOKAHEAD_CLASSES + cls;
        int cached =
            NEEDS_INT_ARRAY_ACQUIRE ? (int) INT_ARRAY_VH.getAcquire(table, idx) : table[idx];
        if (cached != UNCACHED) return cached;
      }
      int next = lookupOrCompute(state, c, nfaStep, input, pos, regionEnd);
      if (next != FALLBACK) cacheAnchorLookaheadEntry(state, c, cls, next);
      return next;
    }
    int[] table =
        NEEDS_INT_ARRAY_ACQUIRE
            ? (int[]) TABLES_VH.getAcquire(asciiTables, state)
            : asciiTables[state];
    int next =
        (table != null && c < 128)
            ? (NEEDS_INT_ARRAY_ACQUIRE ? (int) INT_ARRAY_VH.getAcquire(table, c) : table[c])
            : UNCACHED;
    return next == UNCACHED ? lookupOrCompute(state, c, nfaStep, input, pos, regionEnd) : next;
  }

  int lookupOrCompute(int state, int c, LaurikariNfaStep step) {
    return lookupOrCompute(state, c, step, "", 0, 0);
  }

  int lookupOrCompute(
      int state, int c, LaurikariNfaStep step, String input, int pos, int regionEnd) {
    LaurikariStepResult result =
        step.apply(nfaStateSets[state], regs[state], c, input, pos, regionEnd);
    int id = intern(result.states, result.regs);
    // Gating on anchorSensitive[state] alone is not enough: a source state whose OWN subset
    // has no anchor-bearing member can still transition into a destination whose subset newly
    // touches one (e.g. the first repetition of a one-or-more loop feeding a trailing anchor).
    // addClosure adds an anchor-bearing NFA state to a closure unconditionally the moment it is
    // epsilon-reachable, so anchorSensitive[id] is a structural (position-independent) signal
    // that THIS transition's closure build evaluated a live, position-specific checkAnchor call
    // -- caching it under (state, c) alone would silently replay that one call's outcome at a
    // different position later (issue #108).
    boolean destinationAnchorSensitive = id >= 0 && anchorSensitive[id];
    if (id != FALLBACK && !anchorSensitive[state] && !destinationAnchorSensitive) {
      cacheEntry(state, c, id);
    }
    return id;
  }

  /**
   * Interns an arbitrary (states, regs) closure as a DFA state, reusing an existing entry if an
   * equal one is already cached. Unlike {@link #lookupOrCompute}, this has no {@code (state, c)}
   * transition to cache the result under — used by {@link LaurikariDfaMatcher} to seed a {@code
   * findFrom(start > 0)} scan directly from a precomputed closure (state 0 is only valid when the
   * scan begins at absolute position 0).
   */
  int intern(int[] states, int[][] stateRegs) {
    if (states.length == 0) return DEAD;
    LaurikariStateSetKey key = new LaurikariStateSetKey(states, stateRegs);
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
                  anchorSensitive[newId] = isAnchorSensitive(k.getStates());
                  return newId;
                }
                return null; // over cap: don't insert, keeps map bounded at cap entries
              });
      if (id == null) {
        frozen = true;
        return FALLBACK;
      }
    }
    return id == null ? FALLBACK : id;
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
   * Classifies the not-yet-consumed character at {@code input.charAt(pos)} into one of {@link
   * #LOOKAHEAD_CLASSES} buckets, sufficient (together with the already-consumed char {@code c},
   * which is a separate cache dimension) to determine every one of the 6 new anchor types' {@code
   * PikeVMMatcher.checkAnchor} outcome, <b>provided</b> the caller has already excluded positions
   * within {@link #ANCHOR_LOOKAHEAD_LIVE_MARGIN} of {@code regionEnd} (guaranteed by {@link
   * #step}):
   *
   * <ul>
   *   <li>{@code END}/{@code STRING_END}/{@code STRING_END_ABSOLUTE}: always false away from {@code
   *       regionEnd} — no dependency on this class at all.
   *   <li>{@code END_MULTILINE}: {@code pos < regionEnd && charAt(pos) == '\n'} — true iff {@link
   *       #LOOKAHEAD_NEWLINE}.
   *   <li>{@code WORD_BOUNDARY}: {@code isWordChar(charAt(pos-1)) != isWordChar(charAt( pos))}. The
   *       first operand is {@code PikeVMMatcher.isWordChar((char) c)} (already a cache dimension);
   *       the second is true iff {@link #LOOKAHEAD_WORD} (a newline is never a word char, so {@link
   *       #LOOKAHEAD_NEWLINE} and {@link #LOOKAHEAD_WORD} are mutually exclusive).
   *   <li>{@code NON_WORD_BOUNDARY}: the negation of {@code WORD_BOUNDARY}'s equation above — same
   *       two operands, {@code ==} instead of {@code !=} — so it depends on this class exactly the
   *       same way.
   * </ul>
   */
  private static int lookaheadClass(String input, int pos) {
    char next = input.charAt(pos);
    if (next == '\n') return LOOKAHEAD_NEWLINE;
    return PikeVMMatcher.isWordChar(next) ? LOOKAHEAD_WORD : LOOKAHEAD_OTHER;
  }

  private void cacheAnchorLookaheadEntry(int state, int c, int cls, int value) {
    int idx = c * LOOKAHEAD_CLASSES + cls;
    int[] table = anchorLookaheadTables[state];
    if (table == null) {
      int[] t = new int[128 * LOOKAHEAD_CLASSES];
      Arrays.fill(t, UNCACHED);
      t[idx] = value;
      TABLES_VH.setRelease(anchorLookaheadTables, state, t);
    } else {
      INT_ARRAY_VH.setRelease(table, idx, value);
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
      LaurikariStepResult result = step.apply(states, r, input.charAt(pos), input, pos + 1, len);
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
