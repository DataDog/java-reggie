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

import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Interpreted memoized-priority backtracker (Task 8, "shape B"), extended with counted-loop
 * support.
 *
 * <p>Runs the Thompson NFA as a priority-ordered DFS (Perl leftmost / first-alternative / greedy
 * order, encoded by {@link NFA.NFAState#getEpsilonTransitions()} insertion order), with memoization
 * on {@code (state, pos, referenced-group spans, counted-loop counters)} so that (a) divergent
 * backref-span paths stay distinct, (b) the first arrival in preorder — the highest-priority one —
 * wins, and (c) the search is finite/polynomial. The first accepting configuration found in
 * preorder is the Perl-correct match, including non-referenced group spans.
 *
 * <p>Counted loops: NFA states with {@code countedLoopId != null} are decision points of a lowered
 * bounded quantifier x{min,max}. The DFS carries per-loop iteration counts on each frame; the
 * marker emits an iterate successor (body entry, count+1, allowed while count &lt; max-min) and a
 * stop successor (fragment exit, always allowed — min copies precede the marker), in greedy or lazy
 * priority order. This is exactly the reference backtracking semantics for bounded repetition, so
 * x{min,max} matches identically to java.util.regex while the NFA stays compact (the semver {0,256}
 * family: 567k unrolled states → ~2.6k, see find-bounded-quantifier-regression). Other engines must
 * never receive such NFAs — NFA#hasCountedLoops() gates routing.
 *
 * <p>This deliberately mirrors {@link PikeVMMatcher}'s capture-slot convention (slot {@code 2k} =
 * start, {@code 2k+1} = end of group {@code k}; group 0 = whole match) and reuses the same anchor
 * semantics, but is a separate engine: PikeVM is strictly lock-step (one char per step) and cannot
 * express a backref's variable {@code +L} advance nor per-path loop counters.
 *
 * <p>First increment: implements the boolean + rich first-match API exercised by the backref
 * regression suite ({@code matches}, {@code find}, {@code findFrom}, {@code match}, {@code
 * findMatch}, {@code findMatchFrom}). {@code matchInto}/{@code matchBounded}/{@code
 * findLongestMatchEnd} inherit the base defaults for now.
 *
 * <p>Hot-path layout (matching re2j-class per-step constants while keeping the compact counter NFA
 * — re2j instead fully expands bounded repetition: 419k instructions / ~7MB for the semver family,
 * precisely so it can dedupe threads on pc alone with a sparse set; see Cox, "Regular Expression
 * Matching Can Be Simple And Fast", 2007, and the re2j Machine/Queue sources):
 *
 * <ul>
 *   <li>Flattened NFA: all state data in primitive arrays (int[][] adjacency, CharSet[][] classes)
 *       — no per-pop List/iterator dispatch.
 *   <li>Pass-through compression: side-effect-free states with one epsilon out and nothing else
 *       (concatenation glue) are retargeted away at construction; the DFS never pops them.
 *   <li>Join-point-only memoization: the visited set is consulted only at states with ≥2 incoming
 *       edges (plus the start). Every cycle enters through such a state, so finiteness holds;
 *       re-visiting a configuration through a unique predecessor is provably identical work (the
 *       subtree outcome is a function of the configuration), so first-arrival priority is preserved
 *       — memoizing straight-line states only ever re-detected the same fact at higher cost.
 *   <li>Live-key masking: the memo row contains only spans/counters that can actually VARY at that
 *       state — a counter whose loop no path to this state has entered is constant 0; a referenced
 *       span whose group no path has written is constant -1. Rows for the semver pattern shrink
 *       from 15 ints to ~4-6.
 *   <li>The stack is four parallel primitive arrays and the capture vector is cloned copy-on-write
 *       (only when a state writes a group boundary; only the iterate branch clones counters).
 * </ul>
 *
 * <p>Every per-call structure lives in a dimension-keyed per-thread {@link Workspace} (stack, memo
 * with O(1) generation reset, scratch row); matchers stay stateless across calls and thread-safe.
 */
public final class BackrefBacktrackMatcher extends ReggieMatcher {

  private static final int[] NO_LOOPS = new int[0];

  /**
   * Per-match-call step budget for the DFS (frames popped). The memo keeps the search finite, but
   * adversarially ambiguous bounded-quantifier shapes can still expand a very large configuration
   * space before exhausting it — the same inputs on which java.util.regex backtracking burns
   * unbounded time. This budget converts that into a fast, bounded MatchBudgetExceededException.
   * Override with -Dreggie.countedloop.maxSteps.
   */
  private static final long MAX_MATCH_STEPS =
      Long.getLong("reggie.countedloop.maxSteps", 2_000_000L);

  private final int groupCount;
  private final int slotCount;
  private final int stateCount;
  private final int startStateId;

  // ---- Flattened NFA, indexed by state id (see class javadoc) ----
  private final int[][] epsIds; // epsilon successors, priority order
  private final int[][] charTargetIds; // parallel to charSets
  private final CharSet[][] charSets;
  private final int[] enterGroupById; // -1 = none
  private final int[] exitGroupById; // -1 = none
  private final int[] backrefById; // -1 = none
  private final NFA.AnchorType[] anchorById; // null = none
  private final boolean[] acceptById;
  private final boolean[] markerById; // counted-loop decision point
  private final int[] mBodyEntry; // valid where markerById
  private final int[] mStop;
  private final int[] mTailMax;
  private final int[] mSlot; // counter slot, dense
  private final boolean[] mLazy;

  private final boolean[] isJoin; // memoization checkpoints (>=2 in-edges, plus start)
  private final int[][] keyGroupIdx; // caps slots for spans that can vary, per state
  private final int[][] keyLoopIdx; // counter slots that can vary, per state
  private final int maxRowLen;

  private final int[] referencedGroups; // sorted; groups that are targets of some \k
  private final int loopSlotCount; // number of distinct counted loops
  private final boolean caseInsensitive;

  public BackrefBacktrackMatcher(NFA nfa, String pattern) {
    super(pattern);
    this.groupCount = nfa.getGroupCount();
    this.slotCount = 2 * (groupCount + 1);
    int maxId = 0;
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.id > maxId) {
        maxId = s.id;
      }
    }
    int n = maxId + 1;
    this.stateCount = n;

    // ---- 1. Flatten the NFA into primitive arrays. ----
    this.epsIds = new int[n][];
    this.charTargetIds = new int[n][];
    this.charSets = new CharSet[n][];
    this.enterGroupById = new int[n];
    this.exitGroupById = new int[n];
    this.backrefById = new int[n];
    this.anchorById = new NFA.AnchorType[n];
    this.acceptById = new boolean[n];
    this.markerById = new boolean[n];
    this.mBodyEntry = new int[n];
    this.mStop = new int[n];
    this.mTailMax = new int[n];
    this.mSlot = new int[n];
    this.mLazy = new boolean[n];
    Arrays.fill(enterGroupById, -1);
    Arrays.fill(exitGroupById, -1);
    Arrays.fill(backrefById, -1);

    Set<Integer> refs = new HashSet<>();
    java.util.TreeMap<Integer, Integer> loopSlots = new java.util.TreeMap<>();
    for (NFA.NFAState s : nfa.getStates()) {
      int id = s.id;
      if (s.enterGroup != null) {
        enterGroupById[id] = s.enterGroup;
      }
      if (s.exitGroup != null) {
        exitGroupById[id] = s.exitGroup;
      }
      if (s.backrefCheck != null) {
        backrefById[id] = s.backrefCheck;
        refs.add(s.backrefCheck);
      }
      if (s.anchor != null) {
        anchorById[id] = s.anchor;
      }
      List<NFA.NFAState> eps = s.getEpsilonTransitions();
      epsIds[id] = new int[eps.size()];
      for (int i = 0; i < eps.size(); i++) {
        epsIds[id][i] = eps.get(i).id;
      }
      List<NFA.Transition> tr = s.getTransitions();
      charTargetIds[id] = new int[tr.size()];
      charSets[id] = new CharSet[tr.size()];
      for (int i = 0; i < tr.size(); i++) {
        charTargetIds[id][i] = tr.get(i).target.id;
        charSets[id][i] = tr.get(i).chars;
      }
      if (s.countedLoopId != null) {
        markerById[id] = true;
        Integer slot = loopSlots.get(s.countedLoopId);
        if (slot == null) {
          slot = loopSlots.size();
          loopSlots.put(s.countedLoopId, slot);
        }
        mSlot[id] = slot;
        mBodyEntry[id] = s.countedLoopBodyEntryId;
        mStop[id] = s.countedLoopStopId;
        mTailMax[id] = s.countedLoopTailMax;
        mLazy[id] = s.countedLoopLazy;
      }
    }
    for (NFA.NFAState a : nfa.getAcceptStates()) {
      acceptById[a.id] = true;
    }
    this.startStateId = nfa.getStartState().id;
    this.loopSlotCount = loopSlots.size();
    int[] r = new int[refs.size()];
    int ri = 0;
    for (int g : refs) {
      r[ri++] = g;
    }
    Arrays.sort(r);
    this.referencedGroups = r;
    this.caseInsensitive = pattern.contains("(?i)");

    // ---- 2. Pass-through compression: bypass side-effect-free glue states entirely. ----
    boolean[] pure = new boolean[n];
    for (int i = 0; i < n; i++) {
      pure[i] =
          !acceptById[i]
              && i != startStateId
              && enterGroupById[i] < 0
              && exitGroupById[i] < 0
              && backrefById[i] < 0
              && anchorById[i] == null
              && !markerById[i]
              && epsIds[i].length == 1
              && charTargetIds[i].length == 0
              && epsIds[i][0] != i;
    }
    int[] resolved = new int[n];
    Arrays.fill(resolved, -1);
    for (int s = 0; s < n; s++) {
      for (int i = 0; i < epsIds[s].length; i++) {
        epsIds[s][i] = resolve(epsIds[s][i], pure, resolved);
      }
      for (int i = 0; i < charTargetIds[s].length; i++) {
        charTargetIds[s][i] = resolve(charTargetIds[s][i], pure, resolved);
      }
      if (markerById[s]) {
        mBodyEntry[s] = resolve(mBodyEntry[s], pure, resolved);
        mStop[s] = resolve(mStop[s], pure, resolved);
      }
    }

    // ---- 3. Join points: memoize only states with >=2 incoming edges (plus the start). ----
    // Every cycle is entered through such a state (entry + back edge), and every convergence of
    // distinct configurations passes one, so this alone preserves finiteness and first-arrival
    // priority; the rest of the memo was pure overhead.
    int[] inDeg = new int[n];
    for (int s = 0; s < n; s++) {
      for (int t : epsIds[s]) {
        inDeg[t]++;
      }
      for (int t : charTargetIds[s]) {
        inDeg[t]++;
      }
      if (markerById[s]) {
        inDeg[mStop[s]]++;
        inDeg[mBodyEntry[s]]++;
      }
    }
    this.isJoin = new boolean[n];
    for (int i = 0; i < n; i++) {
      isJoin[i] = inDeg[i] >= 2;
    }
    isJoin[startStateId] = true;

    // ---- 4. Liveness: which key components can actually VARY at each state. ----
    // A counter varies at s iff some path start->s entered its loop, i.e. s is reachable from the
    // loop's body entry. A referenced span varies at s iff s is reachable from a state that writes
    // that group's boundary. Anything else is a constant (0 / -1) along all paths to s and would
    // only bloat the row.
    boolean[][] loopLive = new boolean[loopSlotCount][];
    for (int j = 0; j < loopSlotCount; j++) {
      loopLive[j] = new boolean[n];
    }
    int[] queue = new int[n];
    for (int s = 0; s < n; s++) {
      if (markerById[s] && !loopLive[mSlot[s]][mBodyEntry[s]]) {
        markReachable(loopLive[mSlot[s]], mBodyEntry[s], queue);
      }
    }
    boolean[][] groupLive = new boolean[n][]; // by group id, referenced groups only
    for (int g : referencedGroups) {
      boolean[] live = new boolean[n];
      boolean any = false;
      for (int s = 0; s < n; s++) {
        if (enterGroupById[s] == g || exitGroupById[s] == g) {
          if (!live[s]) {
            markReachable(live, s, queue);
          }
          any = true;
        }
      }
      if (any) {
        groupLive[g] = live;
      }
    }

    // ---- 5. Per-state key layouts (fixed layout per state; variable row length in the table).
    // ----
    this.keyGroupIdx = new int[n][];
    this.keyLoopIdx = new int[n][];
    int maxLen = 2;
    for (int s = 0; s < n; s++) {
      List<Integer> gi = null;
      for (int g : referencedGroups) {
        if (groupLive[g] != null && groupLive[g][s]) {
          if (gi == null) {
            gi = new ArrayList<>(4);
          }
          gi.add(2 * g);
          gi.add(2 * g + 1);
        }
      }
      List<Integer> li = null;
      for (int j = 0; j < loopSlotCount; j++) {
        if (loopLive[j][s]) {
          if (li == null) {
            li = new ArrayList<>(4);
          }
          li.add(j);
        }
      }
      keyGroupIdx[s] = gi == null ? EMPTY : toFlat(gi);
      keyLoopIdx[s] = li == null ? EMPTY : toFlat(li);
      int len = 2 + keyGroupIdx[s].length + keyLoopIdx[s].length;
      if (len > maxLen) {
        maxLen = len;
      }
    }
    this.maxRowLen = maxLen;
  }

  private static final int[] EMPTY = new int[0];

  /** Follows pure pass-through chains to their first observable state (construction-time only). */
  private int resolve(int s, boolean[] pure, int[] resolved) {
    int r = resolved[s];
    if (r >= 0) {
      return r;
    }
    int cur = s;
    for (int steps = 0; steps <= stateCount && pure[cur]; steps++) {
      cur = epsIds[cur][0];
    }
    // If the chain cycles among pure states, cur is a cycle member: leave it — its in-degree
    // (entry + back edge) keeps it a join point, so the memo still guards the cycle.
    resolved[s] = cur;
    return cur;
  }

  /** Forward reachability (eps + char + marker branches) from {@code seed} into {@code live}. */
  private void markReachable(boolean[] live, int seed, int[] queue) {
    int qt = 0;
    live[seed] = true;
    queue[qt++] = seed;
    for (int qh = 0; qh < qt; qh++) {
      int u = queue[qh];
      for (int t : epsIds[u]) {
        if (!live[t]) {
          live[t] = true;
          queue[qt++] = t;
        }
      }
      for (int t : charTargetIds[u]) {
        if (!live[t]) {
          live[t] = true;
          queue[qt++] = t;
        }
      }
      if (markerById[u]) {
        if (!live[mBodyEntry[u]]) {
          live[mBodyEntry[u]] = true;
          queue[qt++] = mBodyEntry[u];
        }
        if (!live[mStop[u]]) {
          live[mStop[u]] = true;
          queue[qt++] = mStop[u];
        }
      }
    }
  }

  private static int[] toFlat(List<Integer> xs) {
    int[] out = new int[xs.size()];
    for (int i = 0; i < xs.size(); i++) {
      out[i] = xs.get(i);
    }
    return out;
  }

  // ---- Hot-path containers (per-thread, see class javadoc) ----

  /**
   * Parallel primitive stacks: (state, pos, caps, loops) per slot. No per-push object allocation.
   */
  private static final class FrameStack {
    private int[] states = new int[64];
    private int[] positions = new int[64];
    private int[][] capsStack = new int[64][];
    private int[][] loopsStack = new int[64][];
    private int size;

    boolean isEmpty() {
      return size == 0;
    }

    void push(int state, int pos, int[] caps, int[] loops) {
      if (size == states.length) {
        int n = states.length * 2;
        states = Arrays.copyOf(states, n);
        positions = Arrays.copyOf(positions, n);
        capsStack = Arrays.copyOf(capsStack, n);
        loopsStack = Arrays.copyOf(loopsStack, n);
      }
      states[size] = state;
      positions[size] = pos;
      capsStack[size] = caps;
      loopsStack[size] = loops;
      size++;
    }

    void pop() {
      size--; // top elements still readable until the next push, which is all the DFS needs
    }
  }

  /**
   * Two-level visited set for the DFS memo checkpoints (join points).
   *
   * <p>Level 1: open-addressing table keyed on the packed long {@code (state << 32) | pos}, with a
   * generation stamp per slot for O(1) reset between match calls. Level 2: the distinct
   * configurations (live spans + counters) seen at that (state, pos), stored in a preallocated int
   * arena and chained per slot.
   *
   * <p>Why two levels: on non-adversarial inputs nearly every join arrival is the FIRST at its
   * (state, pos) — the memo is insurance against re-entry, which mostly happens when backtracking
   * fails back into an alternative. Keying the hash on one long (instead of hashing the whole row)
   * makes the common arrival a single fmix64 + probe + arena append, with rows compared by direct
   * int equality only when a (state, pos) genuinely repeats. An empty row (no live components)
   * still prunes correctly: any re-arrival at the same (state, pos) with an empty row is by
   * definition the same configuration.
   *
   * <p>The memo is what keeps the search finite; {@link #MAX_MATCH_STEPS} bounds rows ever appended
   * per call (one per popped join), which bounds the arena and table sizes.
   */
  private static final class MemoSet {
    private long[] keys;
    private long[] stamps; // per-slot generation; stamps[i] == gen means "present this call"
    private int[] heads; // arena row index of the newest row at each slot
    private int mask;
    private long gen = 1;
    private int[] arena; // rows: [len, ints..., nextRowIdx] chained per slot
    private int arenaTop;
    private int size;

    MemoSet() {
      int cap = 64; // tiny start: short matches never pay for a big table
      this.keys = new long[cap];
      this.stamps = new long[cap];
      this.heads = new int[cap];
      this.mask = cap - 1;
      this.arena = new int[512];
    }

    /** Marks everything stale without touching memory: the next call starts empty. */
    void reset() {
      gen++;
      size = 0;
      arenaTop = 0; // dead rows are simply overwritten by the next call's appends
    }

    /** Returns true if this configuration was newly recorded; false if already visited. */
    boolean add(int state, int pos, int[] row, int len) {
      final long k = ((long) state << 32) | (pos & 0xffffffffL);
      int idx = mix(k) & mask;
      while (stamps[idx] == gen) {
        if (keys[idx] == k) {
          for (int a = heads[idx]; a >= 0; a = arena[a + arena[a] + 1]) {
            int l = arena[a];
            if (l == len) {
              int i = 0;
              while (i < l && arena[a + 1 + i] == row[i]) {
                i++;
              }
              if (i == l) {
                return false; // higher-priority arrival already explored this configuration
              }
            }
          }
          heads[idx] = append(row, len, heads[idx]); // distinct config at a seen (state, pos)
          size++;
          return true;
        }
        idx = (idx + 1) & mask;
      }
      stamps[idx] = gen; // first arrival at this (state, pos)
      keys[idx] = k;
      heads[idx] = append(row, len, -1);
      size++;
      if (size * 3 > (mask + 1) * 2) {
        growKeys();
      }
      return true;
    }

    private int append(int[] row, int len, int next) {
      if (arenaTop + len + 2 > arena.length) {
        int newLen = Math.max(arena.length * 2, arenaTop + len + 2);
        arena = Arrays.copyOf(arena, newLen);
      }
      int a = arenaTop;
      arena[a] = len;
      // Manual copy: rows are a handful of ints — System.arraycopy's setup cost dominates.
      for (int i = 0; i < len; i++) {
        arena[a + 1 + i] = row[i];
      }
      arena[a + 1 + len] = next;
      arenaTop = a + len + 2;
      return a;
    }

    private void growKeys() {
      int oldCap = mask + 1;
      int newCap = oldCap * 2;
      long[] oldKeys = keys;
      long[] oldStamps = stamps;
      int[] oldHeads = heads;
      keys = new long[newCap];
      stamps = new long[newCap];
      heads = new int[newCap];
      mask = newCap - 1;
      for (int i = 0; i < oldCap; i++) {
        if (oldStamps[i] != gen) {
          continue;
        }
        int idx = mix(oldKeys[i]) & mask;
        while (stamps[idx] == gen) {
          idx = (idx + 1) & mask;
        }
        stamps[idx] = gen;
        keys[idx] = oldKeys[i];
        heads[idx] = oldHeads[i];
      }
    }

    private static int mix(long k) {
      k *= 0x9e3779b97f4a7c15L;
      k ^= k >>> 29;
      k *= 0xbf58476d1ce4e5b9L;
      k ^= k >>> 32;
      return (int) k;
    }
  }

  /**
   * Per-thread reusable workspace (stack + memo + scratch row + seed vectors), keyed on the
   * matcher's three dimensions. Sharing across matcher instances (not just calls) keeps
   * compile-per-call services from pinning one workspace per pattern; the dimension check makes a
   * different-shaped pattern fall back to a fresh workspace. All mutable state is method-local
   * during a match: matchers stay thread-safe under concurrent calls.
   */
  private static final ThreadLocal<Workspace> WORKSPACE = new ThreadLocal<>();

  private Workspace acquireWorkspace() {
    Workspace ws = WORKSPACE.get();
    if (ws == null
        || ws.maxRowLen != maxRowLen
        || ws.slotCount != slotCount
        || ws.loopSlotCount != loopSlotCount) {
      ws = new Workspace(maxRowLen, slotCount, loopSlotCount);
      WORKSPACE.set(ws);
      return ws;
    }
    ws.stack.size = 0; // retained arrays, fresh search
    ws.memo.reset(); // generation bump: O(1) table clear
    return ws;
  }

  private static final class Workspace {
    final int maxRowLen;
    final int slotCount;
    final int loopSlotCount;
    final FrameStack stack = new FrameStack();
    final MemoSet memo;
    final int[] row;
    final int[] seed;
    final int[] seedLoops;

    Workspace(int maxRowLen, int slotCount, int loopSlotCount) {
      this.maxRowLen = maxRowLen;
      this.slotCount = slotCount;
      this.loopSlotCount = loopSlotCount;
      this.memo = new MemoSet();
      this.row = new int[maxRowLen];
      this.seed = new int[slotCount];
      this.seedLoops = loopSlotCount == 0 ? NO_LOOPS : new int[loopSlotCount]; // never mutated
    }
  }

  @Override
  public boolean matches(String input) {
    return runSearch(input, 0, input.length(), true) != null;
  }

  @Override
  public boolean find(String input) {
    return findFrom(input, 0) >= 0;
  }

  @Override
  public int findFrom(String input, int start) {
    int len = input.length();
    int clamped = Math.max(0, start);
    if (clamped > len) {
      return -1;
    }
    for (int s = clamped; s <= len; s++) {
      if (runSearch(input, s, len, false) != null) {
        return s;
      }
    }
    return -1;
  }

  @Override
  public MatchResult match(String input) {
    int[] caps = runSearch(input, 0, input.length(), true);
    return caps == null ? null : buildResult(input, caps);
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int len = input.length();
    int clamped = Math.max(0, start);
    if (clamped > len) {
      return null;
    }
    for (int s = clamped; s <= len; s++) {
      int[] caps = runSearch(input, s, len, false);
      if (caps != null) {
        return buildResult(input, caps);
      }
    }
    return null;
  }

  /**
   * Priority-ordered memoized DFS from a fixed match start. Returns the winning capture vector
   * (group 0 = whole match) or {@code null}.
   *
   * @param matchStart where group 0 begins (the seed position)
   * @param regionEnd end of the searchable region (input length)
   * @param wholeInput when true (matches/match), accept only at {@code pos == regionEnd}; otherwise
   *     (find*), accept at the first accepting state reached in preorder
   */
  private int[] runSearch(String input, int matchStart, int regionEnd, boolean wholeInput) {
    final int regionStart = 0; // anchors evaluate against the absolute input origin
    Workspace ws = acquireWorkspace();
    int[] seed = ws.seed;
    Arrays.fill(seed, -1);
    seed[0] = matchStart;

    FrameStack stack = ws.stack;
    // The seed frame carries the workspace's zeroed counter vector (it is never mutated — the
    // iterate branch clones before incrementing; NO_LOOPS is the no-counted-loops sentinel).
    int[] seedLoops = ws.seedLoops;
    stack.push(startStateId, matchStart, seed, seedLoops);

    int[] row = ws.row;
    MemoSet memo = ws.memo;
    long steps = 0;

    while (!stack.isEmpty()) {
      if (++steps > MAX_MATCH_STEPS) {
        throw new MatchBudgetExceededException(
            "Counted-loop match step budget exceeded ("
                + MAX_MATCH_STEPS
                + " frames; tune via -Dreggie.countedloop.maxSteps) — input is too ambiguous for"
                + " bounded exploration");
      }
      final int state = stack.states[stack.size - 1];
      final int pos = stack.positions[stack.size - 1];
      final int[] caps = stack.capsStack[stack.size - 1];
      final int[] loops = stack.loopsStack[stack.size - 1];
      stack.pop();

      // Memo checkpoint: only join points (>=2 in-edges or the start) can be re-reached by a
      // distinct configuration; straight-line states are re-explored at most once per distinct
      // predecessor configuration, which is provably identical work.
      if (isJoin[state]) {
        int[] gi = keyGroupIdx[state];
        int[] li = keyLoopIdx[state];
        int k = 0;
        for (int i = 0; i < gi.length; i++) {
          row[k++] = caps[gi[i]];
        }
        for (int i = 0; i < li.length; i++) {
          row[k++] = loops[li[i]];
        }
        if (!memo.add(state, pos, row, k)) {
          continue; // first (higher-priority) arrival already explored this configuration
        }
      }

      // Accept check (first accept popped in preorder == highest-priority match). Mutating
      // group 0's end in place is safe: the stack is dead on return and no later read happens.
      if (acceptById[state] && (!wholeInput || pos == regionEnd)) {
        caps[1] = pos; // group-0 end
        return caps;
      }

      // Counted-loop marker: decision point of a lowered x{min,max}. Stop is always allowed
      // (>= min copies have run); iterate allowed while count < tailMax, carrying count+1.
      // Greedy prefers iterate, lazy prefers stop (Perl order).
      if (markerById[state]) {
        int slot = mSlot[state];
        if (loops[slot] < mTailMax[state]) {
          // Only the iterate branch diverges: clone the counter vector for it (the stop branch
          // and all frames below the stack still share the parent's vector).
          int[] iterLoops = loops.clone();
          iterLoops[slot]++;
          if (mLazy[state]) {
            stack.push(mBodyEntry[state], pos, caps, iterLoops);
            stack.push(mStop[state], pos, caps, loops);
          } else {
            stack.push(mStop[state], pos, caps, loops);
            stack.push(mBodyEntry[state], pos, caps, iterLoops);
          }
        } else {
          stack.push(mStop[state], pos, caps, loops);
        }
        continue;
      }

      // Apply this state's group boundaries to a copy that all successors inherit — but only
      // clone when this state actually writes a boundary (most don't; siblings below the stack
      // must keep seeing the parent's vector).
      final int eg = enterGroupById[state];
      final int xg = exitGroupById[state];
      int[] c = caps;
      if (eg >= 0 || xg >= 0) {
        c = caps.clone();
        if (eg >= 0) {
          c[2 * eg] = pos;
        }
        if (xg >= 0) {
          c[2 * xg + 1] = pos;
        }
      }

      // Push successors lowest-priority-first so the stack pops them in Perl priority order.
      final int br = backrefById[state];
      if (br >= 0) {
        int gs = c[2 * br];
        int ge = c[2 * br + 1];
        if (gs >= 0) {
          int l = ge - gs;
          if (l == 0) {
            int[] t = epsIds[state];
            for (int i = t.length - 1; i >= 0; i--) {
              stack.push(t[i], pos, c, loops);
            }
          } else if (l > 0
              && pos + l <= regionEnd
              && input.regionMatches(caseInsensitive, pos, input, gs, l)) {
            int[] t = epsIds[state];
            for (int i = t.length - 1; i >= 0; i--) {
              stack.push(t[i], pos + l, c, loops);
            }
          }
        }
      } else if (anchorById[state] != null) {
        if (checkAnchor(anchorById[state], input, pos, regionStart, regionEnd)) {
          int[] t = epsIds[state];
          for (int i = t.length - 1; i >= 0; i--) {
            stack.push(t[i], pos, c, loops);
          }
        }
      } else {
        int[] t = epsIds[state];
        for (int i = t.length - 1; i >= 0; i--) {
          stack.push(t[i], pos, c, loops);
        }
        if (pos < regionEnd) {
          char ch = input.charAt(pos);
          int[] targets = charTargetIds[state];
          CharSet[] sets = charSets[state];
          for (int i = targets.length - 1; i >= 0; i--) {
            if (sets[i].contains(ch)) {
              stack.push(targets[i], pos + 1, c, loops);
            }
          }
        }
      }
    }
    return null;
  }

  private MatchResult buildResult(String input, int[] caps) {
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    for (int g = 0; g <= groupCount; g++) {
      starts[g] = caps[2 * g];
      ends[g] = caps[2 * g + 1];
    }
    return new MatchResultImpl(input, starts, ends, groupCount);
  }

  /**
   * ASCII word-char predicate used by {@code \b}/{@code \B} — mirrors {@link
   * PikeVMMatcher#isWordChar}.
   */
  private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
  }

  /** Mirrors {@link PikeVMMatcher}'s anchor semantics. */
  private static boolean checkAnchor(
      NFA.AnchorType anchor, String input, int pos, int regionStart, int regionEnd) {
    switch (anchor) {
      case START:
      case STRING_START:
        return pos == regionStart;
      case END:
      case STRING_END:
        if (pos == regionEnd) return true;
        return pos == regionEnd - 1 && input.charAt(pos) == '\n';
      case STRING_END_ABSOLUTE:
        return pos == regionEnd;
      case START_MULTILINE:
        return pos == regionStart || (pos > 0 && input.charAt(pos - 1) == '\n');
      case END_MULTILINE:
        return pos == regionEnd || (pos < regionEnd && input.charAt(pos) == '\n');
      case WORD_BOUNDARY:
        {
          boolean beforeWord = pos > 0 && isWordChar(input.charAt(pos - 1));
          boolean afterWord = pos < regionEnd && isWordChar(input.charAt(pos));
          return beforeWord != afterWord;
        }
      case NON_WORD_BOUNDARY:
        {
          boolean beforeWord = pos > 0 && isWordChar(input.charAt(pos - 1));
          boolean afterWord = pos < regionEnd && isWordChar(input.charAt(pos));
          return beforeWord == afterWord;
        }
      case RESET_MATCH:
        return true;
      default:
        return false;
    }
  }
}
