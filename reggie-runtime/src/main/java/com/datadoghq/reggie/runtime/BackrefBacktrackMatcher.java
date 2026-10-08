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

import com.datadoghq.reggie.codegen.automaton.BacktrackSupport;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

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
 * <p>Lookarounds: an assertion state evaluates its body (characters, anchors, epsilons, group
 * boundaries and backreferences — see {@link BacktrackSupport#unsupportedFeature}) at the current
 * position. A body without groups or backreferences runs as a boolean Thompson simulation. Any
 * other body runs as a priority-ordered sub-search over the incoming captures: a positive
 * lookaround keeps the captures of the first body match, a negative one leaves the groups
 * unchanged. The outcome is a function of the position and the spans the body reads, which the main
 * memo already keys wherever they vary; a group written inside a positive lookaround body counts as
 * written at the assertion state for memo liveness.
 *
 * <p>Empty iterations: an unbounded loop ({@code X*}, {@code X+} and their lazy forms) re-enters
 * its body only when the iteration that just ended consumed input, as java.util.regex's {@code
 * Loop} does. Each frame carries one progress bit per loop head (the target of a loop-back
 * epsilon), cleared when the head is entered and set by any consuming step; a loop-back edge is
 * taken only with its bit set. An empty iteration therefore runs to completion, keeps the captures
 * it wrote and leaves the loop ({@code ((?:ba)?(?<=a))+c} on {@code "bac"} ends with group 1 =
 * 2..2). The bits are part of the memo key wherever they can still gate a loop-back edge.
 *
 * <p>First increment: implements the boolean + rich first-match API exercised by the backref
 * regression suite ({@code matches}, {@code find}, {@code findFrom}, {@code match}, {@code
 * findMatch}, {@code findMatchFrom}) plus the allocation-free {@code matchInto}/{@code
 * findMatchInto}. {@code matchBounded}/{@code findLongestMatchEnd} inherit the base defaults for
 * now.
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
 *   <li>The stack is parallel primitive arrays; capture and counter vectors live in a stack-ordered
 *       int arena and are copied on write (only when a state writes a group boundary; only the
 *       iterate branch copies counters).
 * </ul>
 *
 * <p>Every per-call structure lives in a dimension-keyed per-thread {@link Workspace} (stack,
 * arena, memo with O(1) generation reset, scratch row); matchers stay stateless across calls and
 * thread-safe.
 */
public final class BackrefBacktrackMatcher extends ReggieMatcher {

  /** Progress mask after a consuming step: every open loop iteration has advanced. */
  private static final long PROGRESSED = -1L;

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
  private final NFA.AssertionType[] assertTypeById; // null = none
  private final int[] assertStartById; // assertion body entry, valid where assertTypeById != null
  private final int[] assertWidthById; // lookbehind width
  private final int[] subAcceptFor; // assertion state whose body this state accepts, or -1
  private final boolean[] assertNeedsCaps; // assertion body writes a group or reads a backref
  private final int[][] assertBodyKey; // capture slots written and read within the body

  private final boolean[][] epsIsBack; // per state, parallel to epsIds; null = no loop-back edge
  private final long[] headBit; // progress bit of the loop headed by this state, 0 = not a head
  private final long[] keyProgressMask; // progress bits that can still gate a back edge, per state

  private final boolean[] isJoin; // memoization checkpoints (>=2 in-edges, plus start)
  private final int[][] keyGroupIdx; // caps slots for spans that can vary, per state
  private final int[][] keyLoopIdx; // counter slots that can vary, per state
  private final int maxRowLen;

  private final int[] referencedGroups; // sorted; groups that are targets of some \k
  private final int loopSlotCount; // number of distinct counted loops
  private final int[][] nestedLoopSlots; // per loop slot: slots of loops nested in its body
  private final boolean caseInsensitive;
  private final CharSet startChars; // chars that can begin a match; null = any position may

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
    this.assertTypeById = new NFA.AssertionType[n];
    this.assertStartById = new int[n];
    this.assertWidthById = new int[n];
    this.subAcceptFor = new int[n];
    this.assertNeedsCaps = new boolean[n];
    this.assertBodyKey = new int[n][];
    int[][] assertBodyGroups = new int[n][]; // groups written in a positive lookaround's body
    Arrays.fill(subAcceptFor, -1);
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
      if (s.assertionType != null) {
        assertTypeById[id] = s.assertionType;
        assertStartById[id] = s.assertionStartState.id;
        assertWidthById[id] = s.assertionWidth;
        for (NFA.NFAState a : s.assertionAcceptStates) {
          subAcceptFor[a.id] = id;
        }
        int[] bodyGroups = bodyIds(s.assertionStartState, false);
        int[] bodyRefs = bodyIds(s.assertionStartState, true);
        assertNeedsCaps[id] = bodyGroups.length > 0 || bodyRefs.length > 0;
        List<Integer> key = new ArrayList<>();
        for (int g : bodyGroups) {
          if (contains(bodyRefs, g)) {
            key.add(2 * g);
            key.add(2 * g + 1);
          }
        }
        assertBodyKey[id] = toFlat(key);
        if (s.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD
            || s.assertionType == NFA.AssertionType.POSITIVE_LOOKBEHIND) {
          assertBodyGroups[id] = bodyGroups;
        }
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

    // Loop-back epsilon edges and their heads (ids are still the NFA's: no compression yet).
    // unsupportedFeature() admits at most 64 heads, one progress bit each.
    this.epsIsBack = new boolean[n][];
    this.headBit = new long[n];
    int headCount = 0;
    List<int[]> backEdges = BacktrackSupport.loopBackEdges(nfa);
    for (int[] be : backEdges) {
      int head = epsIds[be[0]][be[1]];
      if (headBit[head] == 0) {
        if (headCount == BacktrackSupport.MAX_LOOP_HEADS) {
          throw new IllegalArgumentException(
              "more than " + BacktrackSupport.MAX_LOOP_HEADS + " unbounded loops");
        }
        headBit[head] = 1L << headCount++;
      }
      if (epsIsBack[be[0]] == null) {
        epsIsBack[be[0]] = new boolean[epsIds[be[0]].length];
      }
      epsIsBack[be[0]][be[1]] = true;
    }

    // ---- 2. Pass-through compression: bypass side-effect-free glue states entirely. ----
    // Loop heads and back-edge sources are kept: their positions in epsIds carry the gating.
    boolean[] pure = new boolean[n];
    for (int i = 0; i < n; i++) {
      pure[i] =
          !acceptById[i]
              && i != startStateId
              && enterGroupById[i] < 0
              && exitGroupById[i] < 0
              && backrefById[i] < 0
              && anchorById[i] == null
              && assertTypeById[i] == null
              && subAcceptFor[i] < 0
              && !markerById[i]
              && headBit[i] == 0
              && epsIsBack[i] == null
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
      if (assertTypeById[s] != null) {
        assertStartById[s] = resolve(assertStartById[s], pure, resolved);
      }
    }

    // Nested-loop slots per loop: loops whose markers are reachable from this loop's body entry
    // without passing the loop's own stop state. An outer iteration entering its body must start
    // the nested loops' counters at zero — the inner maximum applies per outer iteration, not
    // across all of them (JDK: (?:x(?:yy){0,6000}){0,500} consumes a full inner tail in EVERY
    // outer iteration; the shared counter vector would cap the inner loop at its total budget).
    this.nestedLoopSlots = new int[loopSlotCount][];
    int[] markerStateOfSlot = new int[loopSlotCount];
    for (int s = 0; s < n; s++) {
      if (markerById[s]) {
        markerStateOfSlot[mSlot[s]] = s;
      }
    }
    for (int slot = 0; slot < loopSlotCount; slot++) {
      int marker = markerStateOfSlot[slot];
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      boolean[] seen = new boolean[n];
      queue.add(mBodyEntry[marker]);
      seen[mBodyEntry[marker]] = true;
      TreeSet<Integer> nested = new TreeSet<>();
      while (!queue.isEmpty()) {
        int cur = queue.poll();
        if (cur == mStop[marker]) {
          continue; // leaving the loop body — anything past it is not nested
        }
        if (markerById[cur] && mSlot[cur] != slot) {
          nested.add(mSlot[cur]);
        }
        for (int next : epsIds[cur]) {
          if (!seen[next]) {
            seen[next] = true;
            queue.add(next);
          }
        }
        for (int next : charTargetIds[cur]) {
          if (!seen[next]) {
            seen[next] = true;
            queue.add(next);
          }
        }
      }
      int[] slots = new int[nested.size()];
      int si = 0;
      for (int k : nested) {
        slots[si++] = k;
      }
      nestedLoopSlots[slot] = slots;
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
        if (enterGroupById[s] == g || exitGroupById[s] == g || contains(assertBodyGroups[s], g)) {
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

    // A progress bit can gate a back edge from s only if s lies in that loop's body: reachable
    // from the head and able to reach one of its back-edge sources without passing the head (the
    // head itself clears the bit before anything reads it).
    this.keyProgressMask = new long[n];
    if (headCount > 0) {
      int[][] preds = predecessors(n);
      boolean[] fromHead = new boolean[n];
      boolean[] toSource = new boolean[n];
      for (int h = 0; h < n; h++) {
        if (headBit[h] == 0) {
          continue;
        }
        Arrays.fill(fromHead, false);
        markReachable(fromHead, h, queue);
        Arrays.fill(toSource, false);
        int qt = 0;
        for (int[] be : backEdges) {
          int src = be[0];
          if (epsIds[src][be[1]] == h && !toSource[src]) {
            toSource[src] = true;
            queue[qt++] = src;
          }
        }
        for (int qh = 0; qh < qt; qh++) {
          for (int p : preds[queue[qh]]) {
            if (p != h && !toSource[p]) {
              toSource[p] = true;
              queue[qt++] = p;
            }
          }
        }
        for (int s = 0; s < n; s++) {
          if (fromHead[s] && toSource[s] && s != h) {
            keyProgressMask[s] |= headBit[h];
          }
        }
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
      int len =
          2 + keyGroupIdx[s].length + keyLoopIdx[s].length + (keyProgressMask[s] != 0 ? 2 : 0);
      if (len > maxLen) {
        maxLen = len;
      }
    }
    this.maxRowLen = maxLen;

    // ---- 6. Start filter: the chars a match can begin with. ----
    // Union of the char transitions reachable from the start through non-consuming states
    // (assertions, anchors, group boundaries, counted-loop markers). No filter when an accept is
    // reachable that way (empty match) or a backreference is: a group captured inside a
    // lookahead can make it consume at the start position.
    CharSet first = CharSet.empty();
    boolean filterable = true;
    boolean[] seen = new boolean[n];
    int qt = 0;
    queue[qt++] = startStateId;
    seen[startStateId] = true;
    for (int qh = 0; qh < qt && filterable; qh++) {
      int s = queue[qh];
      if (acceptById[s] || backrefById[s] >= 0) {
        filterable = false;
      }
      for (CharSet cs : charSets[s]) {
        first = first.union(cs);
      }
      for (int t : epsIds[s]) {
        if (!seen[t]) {
          seen[t] = true;
          queue[qt++] = t;
        }
      }
      if (markerById[s]) {
        for (int t : new int[] {mBodyEntry[s], mStop[s]}) {
          if (!seen[t]) {
            seen[t] = true;
            queue[qt++] = t;
          }
        }
      }
    }
    this.startChars = filterable ? first : null;
  }

  private static final int[] EMPTY = new int[0];

  private static boolean contains(int[] xs, int x) {
    if (xs != null) {
      for (int v : xs) {
        if (v == x) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Groups whose boundaries ({@code refs == false}) or backreferences ({@code refs == true}) appear
   * in the lookaround body entered at {@code start}.
   */
  private static int[] bodyIds(NFA.NFAState start, boolean refs) {
    TreeSet<Integer> groups = new TreeSet<>();
    Set<NFA.NFAState> seen = new HashSet<>();
    ArrayDeque<NFA.NFAState> queue = new ArrayDeque<>();
    seen.add(start);
    queue.add(start);
    while (!queue.isEmpty()) {
      NFA.NFAState s = queue.poll();
      if (refs) {
        if (s.backrefCheck != null) {
          groups.add(s.backrefCheck);
        }
      } else {
        if (s.enterGroup != null) {
          groups.add(s.enterGroup);
        }
        if (s.exitGroup != null) {
          groups.add(s.exitGroup);
        }
      }
      for (NFA.NFAState t : s.getEpsilonTransitions()) {
        if (seen.add(t)) {
          queue.add(t);
        }
      }
      for (NFA.Transition t : s.getTransitions()) {
        if (seen.add(t.target)) {
          queue.add(t.target);
        }
      }
    }
    int[] out = new int[groups.size()];
    int i = 0;
    for (int g : groups) {
      out[i++] = g;
    }
    return out;
  }

  /** Reverse adjacency (eps + char + marker branches) of the compressed graph. */
  private int[][] predecessors(int n) {
    int[] deg = new int[n];
    for (int s = 0; s < n; s++) {
      for (int t : epsIds[s]) {
        deg[t]++;
      }
      for (int t : charTargetIds[s]) {
        deg[t]++;
      }
      if (markerById[s]) {
        deg[mBodyEntry[s]]++;
        deg[mStop[s]]++;
      }
    }
    int[][] preds = new int[n][];
    for (int s = 0; s < n; s++) {
      preds[s] = new int[deg[s]];
      deg[s] = 0;
    }
    for (int s = 0; s < n; s++) {
      for (int t : epsIds[s]) {
        preds[t][deg[t]++] = s;
      }
      for (int t : charTargetIds[s]) {
        preds[t][deg[t]++] = s;
      }
      if (markerById[s]) {
        preds[mBodyEntry[s]][deg[mBodyEntry[s]]++] = s;
        preds[mStop[s]][deg[mStop[s]]++] = s;
      }
    }
    return preds;
  }

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
   * Int arena holding the DFS's capture and counter vectors (offsets into {@link #data}), freed in
   * stack order: a vector allocated while a frame is processed is reachable only from that frame's
   * successors, so popping a frame resets {@link #top} to its push-time mark. Retained across
   * calls: steady-state matching allocates nothing.
   */
  private static final class IntArena {
    int[] data = new int[256];
    int top;

    /** Reserves {@code len} ints; re-read {@link #data} afterwards (it may have grown). */
    int alloc(int len) {
      int off = top;
      if (off + len > data.length) {
        data = Arrays.copyOf(data, Math.max(data.length * 2, off + len));
      }
      top = off + len;
      return off;
    }

    /** Allocates a copy of the {@code len} ints at {@code from}. */
    int copy(int from, int len) {
      int off = alloc(len);
      int[] d = data;
      for (int i = 0; i < len; i++) {
        d[off + i] = d[from + i];
      }
      return off;
    }
  }

  /**
   * Parallel primitive stacks: (state, pos, caps, loops, progress, arena mark) per slot; caps and
   * loops are {@link IntArena} offsets. No per-push object allocation.
   */
  private static final class FrameStack {
    private final IntArena arena;
    private int[] states = new int[64];
    private int[] positions = new int[64];
    private int[] capsStack = new int[64];
    private int[] loopsStack = new int[64];
    private long[] progress = new long[64];
    private int[] marks = new int[64];
    private int size;

    FrameStack(IntArena arena) {
      this.arena = arena;
    }

    boolean isEmpty() {
      return size == 0;
    }

    void push(int state, int pos, int caps, int loops, long prog) {
      if (size == states.length) {
        int n = states.length * 2;
        states = Arrays.copyOf(states, n);
        positions = Arrays.copyOf(positions, n);
        capsStack = Arrays.copyOf(capsStack, n);
        loopsStack = Arrays.copyOf(loopsStack, n);
        progress = Arrays.copyOf(progress, n);
        marks = Arrays.copyOf(marks, n);
      }
      states[size] = state;
      positions[size] = pos;
      capsStack[size] = caps;
      loopsStack[size] = loops;
      progress[size] = prog;
      marks[size] = arena.top;
      size++;
    }

    void pop() {
      size--; // top elements still readable until the next push, which is all the DFS needs
      arena.top = marks[size]; // vectors allocated since this frame's push are unreachable
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
    ws.memo.reset(); // generation bump: O(1) table clear
    return ws;
  }

  private static final class Workspace {
    final int maxRowLen;
    final int slotCount;
    final int loopSlotCount;
    final IntArena arena = new IntArena();
    final FrameStack stack = new FrameStack(arena);
    final MemoSet memo;
    final int[] row;
    // Lookaround body simulation scratch (state lists, closure stack, generation-stamped marks),
    // grown on demand and retained across calls.
    int[] subCur = EMPTY;
    int[] subNext = EMPTY;
    int[] subStack = EMPTY;
    int[] subMarks = EMPTY;
    int subGen;
    // Sub-search state for lookaround bodies with groups, created on first use.
    FrameStack bodyStack;
    MemoSet bodyMemo;
    int[] bodyRow;

    Workspace(int maxRowLen, int slotCount, int loopSlotCount) {
      this.maxRowLen = maxRowLen;
      this.slotCount = slotCount;
      this.loopSlotCount = loopSlotCount;
      this.memo = new MemoSet();
      this.row = new int[maxRowLen];
    }
  }

  @Override
  public boolean matches(String input) {
    return runSearch(acquireWorkspace(), input, 0, input.length(), true) >= 0;
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
    Workspace ws = acquireWorkspace();
    for (int s = clamped; s <= len; s++) {
      s = nextStart(input, s, len);
      if (s < 0) {
        return -1;
      }
      if (runSearch(ws, input, s, len, false) >= 0) {
        return s;
      }
    }
    return -1;
  }

  @Override
  public MatchResult match(String input) {
    Workspace ws = acquireWorkspace();
    int caps = runSearch(ws, input, 0, input.length(), true);
    return caps < 0 ? null : buildResult(input, ws.arena.data, caps);
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    Workspace ws = acquireWorkspace();
    int caps = searchFrom(ws, input, start);
    return caps < 0 ? null : buildResult(input, ws.arena.data, caps);
  }

  @Override
  public boolean matchInto(String input, int[] groupStarts, int[] groupEnds) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(groupStarts, "groupStarts");
    Objects.requireNonNull(groupEnds, "groupEnds");
    Workspace ws = acquireWorkspace();
    int caps = runSearch(ws, input, 0, input.length(), true);
    if (caps < 0) {
      return false;
    }
    copyCaps(ws.arena.data, caps, groupStarts, groupEnds);
    return true;
  }

  @Override
  public boolean findMatchInto(String input, int start, int[] groupStarts, int[] groupEnds) {
    Objects.requireNonNull(input, "input");
    Objects.requireNonNull(groupStarts, "groupStarts");
    Objects.requireNonNull(groupEnds, "groupEnds");
    Workspace ws = acquireWorkspace();
    int caps = searchFrom(ws, input, start);
    if (caps < 0) {
      return false;
    }
    copyCaps(ws.arena.data, caps, groupStarts, groupEnds);
    return true;
  }

  /**
   * Arena offset of the capture vector of the leftmost match starting at or after {@code start}, or
   * -1.
   */
  private int searchFrom(Workspace ws, String input, int start) {
    int len = input.length();
    int clamped = Math.max(0, start);
    if (clamped > len) {
      return -1;
    }
    for (int s = clamped; s <= len; s++) {
      s = nextStart(input, s, len);
      if (s < 0) {
        return -1;
      }
      int caps = runSearch(ws, input, s, len, false);
      if (caps >= 0) {
        return caps;
      }
    }
    return -1;
  }

  /**
   * First position at or after {@code s} where a match can begin per {@link #startChars}, or -1.
   * With a filter, a match consumes at least one char, so {@code len} is never a candidate.
   */
  private int nextStart(String input, int s, int len) {
    CharSet first = startChars;
    if (first == null) {
      return s;
    }
    for (; s < len; s++) {
      if (first.contains(input.charAt(s))) {
        return s;
      }
    }
    return -1;
  }

  private void copyCaps(int[] d, int caps, int[] groupStarts, int[] groupEnds) {
    if (groupStarts.length <= groupCount || groupEnds.length <= groupCount) {
      throw new IndexOutOfBoundsException(
          "group arrays must have length at least " + (groupCount + 1) + " for this pattern");
    }
    for (int g = 0; g <= groupCount; g++) {
      groupStarts[g] = d[caps + 2 * g];
      groupEnds[g] = d[caps + 2 * g + 1];
    }
  }

  /**
   * Priority-ordered memoized DFS from a fixed match start. Returns the arena offset of the winning
   * capture vector (group 0 = whole match) or -1.
   *
   * <p>The memo of {@code ws} may carry the configurations of earlier failed calls on the same
   * input and region: a failed call explored every configuration it memoized to exhaustion, and a
   * configuration's outcome does not depend on the match start (group 0's start is never read and
   * anchors evaluate against the absolute origin), so those entries remain valid failures.
   *
   * @param matchStart where group 0 begins (the seed position)
   * @param regionEnd end of the searchable region (input length)
   * @param wholeInput when true (matches/match), accept only at {@code pos == regionEnd}; otherwise
   *     (find*), accept at the first accepting state reached in preorder
   */
  private int runSearch(
      Workspace ws, String input, int matchStart, int regionEnd, boolean wholeInput) {
    final int regionStart = 0; // anchors evaluate against the absolute input origin
    FrameStack stack = ws.stack;
    IntArena arena = ws.arena;
    stack.size = 0; // retained arrays, fresh search
    arena.top = 0;
    int seed = arena.alloc(slotCount);
    // The seed frame's zeroed counter vector is never mutated: the iterate branch copies it
    // before incrementing.
    int seedLoops = arena.alloc(loopSlotCount);
    int[] d = arena.data;
    Arrays.fill(d, seed, seed + slotCount, -1);
    d[seed] = matchStart;
    Arrays.fill(d, seedLoops, seedLoops + loopSlotCount, 0);
    stack.push(startStateId, matchStart, seed, seedLoops, PROGRESSED);

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
      final int caps = stack.capsStack[stack.size - 1];
      final int loops = stack.loopsStack[stack.size - 1];
      // Entering a loop head starts a new iteration of that loop: nothing consumed yet.
      final long prog = stack.progress[stack.size - 1] & ~headBit[state];
      stack.pop();
      d = arena.data;

      // Memo checkpoint: only join points (>=2 in-edges or the start) can be re-reached by a
      // distinct configuration; straight-line states are re-explored at most once per distinct
      // predecessor configuration, which is provably identical work.
      if (isJoin[state]) {
        int[] gi = keyGroupIdx[state];
        int[] li = keyLoopIdx[state];
        int k = 0;
        for (int i = 0; i < gi.length; i++) {
          row[k++] = d[caps + gi[i]];
        }
        for (int i = 0; i < li.length; i++) {
          row[k++] = d[loops + li[i]];
        }
        long pm = keyProgressMask[state];
        if (pm != 0) {
          pm &= prog;
          row[k++] = (int) pm;
          row[k++] = (int) (pm >>> 32);
        }
        if (!memo.add(state, pos, row, k)) {
          continue; // first (higher-priority) arrival already explored this configuration
        }
      }

      // Accept check (first accept popped in preorder == highest-priority match). Mutating
      // group 0's end in place is safe: the stack is dead on return and no later read happens.
      if (acceptById[state] && (!wholeInput || pos == regionEnd)) {
        d[caps + 1] = pos; // group-0 end
        return caps;
      }

      // Counted-loop marker: decision point of a lowered x{min,max}. Stop is always allowed
      // (>= min copies have run); iterate allowed while count < tailMax, carrying count+1.
      // Greedy prefers iterate, lazy prefers stop (Perl order).
      if (markerById[state]) {
        int slot = mSlot[state];
        if (d[loops + slot] < mTailMax[state]) {
          // Only the iterate branch diverges: copy the counter vector for it (the stop branch
          // and all frames below the stack still share the parent's vector). Entering the body
          // resets nested loops' counters — each outer iteration gets a fresh inner budget.
          int iterLoops = arena.copy(loops, loopSlotCount);
          d = arena.data;
          d[iterLoops + slot]++;
          for (int nested : nestedLoopSlots[slot]) {
            d[iterLoops + nested] = 0;
          }
          if (mLazy[state]) {
            stack.push(mBodyEntry[state], pos, caps, iterLoops, prog);
            stack.push(mStop[state], pos, caps, loops, prog);
          } else {
            stack.push(mStop[state], pos, caps, loops, prog);
            stack.push(mBodyEntry[state], pos, caps, iterLoops, prog);
          }
        } else {
          stack.push(mStop[state], pos, caps, loops, prog);
        }
        continue;
      }

      // Apply this state's group boundaries to a copy that all successors inherit — but only
      // copy when this state actually writes a boundary (most don't; siblings below the stack
      // must keep seeing the parent's vector).
      final int eg = enterGroupById[state];
      final int xg = exitGroupById[state];
      int c = caps;
      if (eg >= 0 || xg >= 0) {
        c = arena.copy(caps, slotCount);
        d = arena.data;
        if (eg >= 0) {
          d[c + 2 * eg] = pos;
        }
        if (xg >= 0) {
          d[c + 2 * xg + 1] = pos;
        }
      }

      // Push successors lowest-priority-first so the stack pops them in Perl priority order.
      final int br = backrefById[state];
      if (br >= 0) {
        int gs = d[c + 2 * br];
        int ge = d[c + 2 * br + 1];
        if (gs >= 0) {
          int l = ge - gs;
          if (l == 0) {
            pushEpsilons(stack, state, pos, c, loops, prog);
          } else if (l > 0
              && pos + l <= regionEnd
              && input.regionMatches(caseInsensitive, pos, input, gs, l)) {
            pushEpsilons(stack, state, pos + l, c, loops, PROGRESSED);
          }
        }
      } else if (assertTypeById[state] != null) {
        if (assertNeedsCaps[state]) {
          int ac = assertionCaptures(ws, input, state, pos, regionEnd, c);
          if (ac >= 0) {
            pushEpsilons(stack, state, pos, ac, loops, prog);
          }
        } else if (assertionHolds(ws, input, state, pos, regionEnd)) {
          pushEpsilons(stack, state, pos, c, loops, prog);
        }
      } else if (anchorById[state] == NFA.AnchorType.RESET_MATCH) {
        // \K moves group 0's start; successors inherit it through a copy like a group write.
        // Group 0 is never part of a memo key, so the memo stays valid.
        c = arena.copy(c, slotCount);
        d = arena.data;
        d[c] = pos;
        pushEpsilons(stack, state, pos, c, loops, prog);
      } else if (anchorById[state] != null) {
        if (checkAnchor(anchorById[state], input, pos, regionStart, regionEnd)) {
          pushEpsilons(stack, state, pos, c, loops, prog);
        }
      } else {
        pushEpsilons(stack, state, pos, c, loops, prog);
        if (pos < regionEnd) {
          char ch = input.charAt(pos);
          int[] targets = charTargetIds[state];
          CharSet[] sets = charSets[state];
          for (int i = targets.length - 1; i >= 0; i--) {
            if (sets[i].contains(ch)) {
              stack.push(targets[i], pos + 1, c, loops, PROGRESSED);
            }
          }
        }
      }
    }
    return -1;
  }

  /**
   * Pushes {@code state}'s epsilon successors lowest-priority-first. A loop-back edge is skipped
   * when its loop's current iteration consumed nothing: that iteration's captures stand and only
   * the exit path continues (java.util.regex {@code Loop}).
   */
  private void pushEpsilons(FrameStack stack, int state, int pos, int c, int loops, long prog) {
    int[] t = epsIds[state];
    boolean[] back = epsIsBack[state];
    for (int i = t.length - 1; i >= 0; i--) {
      if (back != null && back[i] && (prog & headBit[t[i]]) == 0) {
        continue;
      }
      stack.push(t[i], pos, c, loops, prog);
    }
  }

  /**
   * Evaluates the lookaround at {@code state}, whose body has no groups or backreferences, for
   * position {@code pos}: the outcome depends on the position alone and the DFS memo stays valid.
   */
  private boolean assertionHolds(Workspace ws, String input, int state, int pos, int regionEnd) {
    NFA.AssertionType type = assertTypeById[state];
    boolean matched;
    if (type == NFA.AssertionType.POSITIVE_LOOKAHEAD
        || type == NFA.AssertionType.NEGATIVE_LOOKAHEAD) {
      matched = bodyMatches(ws, input, state, pos, regionEnd, -1);
    } else {
      int from = pos - assertWidthById[state];
      matched = from >= 0 && bodyMatches(ws, input, state, from, regionEnd, pos);
    }
    boolean positive =
        type == NFA.AssertionType.POSITIVE_LOOKAHEAD
            || type == NFA.AssertionType.POSITIVE_LOOKBEHIND;
    return matched == positive;
  }

  /**
   * Evaluates the lookaround at {@code state}, whose body writes groups or reads backreferences,
   * for position {@code pos}. Returns the arena offset of the capture vector to continue with, or
   * -1 when the assertion fails: the first body match's captures for a positive lookaround, {@code
   * caps} unchanged for a negative one.
   */
  private int assertionCaptures(
      Workspace ws, String input, int state, int pos, int regionEnd, int caps) {
    NFA.AssertionType type = assertTypeById[state];
    int matched;
    if (type == NFA.AssertionType.POSITIVE_LOOKAHEAD
        || type == NFA.AssertionType.NEGATIVE_LOOKAHEAD) {
      matched = bodyCaptures(ws, input, state, pos, regionEnd, -1, caps);
    } else {
      int from = pos - assertWidthById[state];
      matched = from >= 0 ? bodyCaptures(ws, input, state, from, regionEnd, pos, caps) : -1;
    }
    if (type == NFA.AssertionType.POSITIVE_LOOKAHEAD
        || type == NFA.AssertionType.POSITIVE_LOOKBEHIND) {
      return matched;
    }
    return matched < 0 ? caps : -1;
  }

  /**
   * Priority-ordered DFS over the body of assertion {@code owner} from {@code from}, starting with
   * {@code caps}. Returns the arena offset of the captures at the first body accept reached in
   * priority order — anywhere for a lookahead ({@code mustEnd < 0}), exactly at {@code mustEnd} for
   * a lookbehind — or -1. A body accept state is a dangling fragment exit whose continuation would
   * be its last epsilon, so accepting there ranks below all of its own successors: it is pushed as
   * a deferred frame ({@code ~state}) beneath them ({@code (?=(a)?)} accepts at its entry only
   * after {@code (a)} fails). The outer captures are fixed for one call, so the memo keys (state,
   * position, the spans of groups the body both writes and reads, gating progress bits).
   */
  private int bodyCaptures(
      Workspace ws, String input, int owner, int from, int regionEnd, int mustEnd, int caps) {
    if (ws.bodyStack == null) {
      ws.bodyStack = new FrameStack(ws.arena); // its frames lie above the outer ones in the arena
      ws.bodyMemo = new MemoSet();
      ws.bodyRow = new int[ws.slotCount + 2]; // body key: capture slots + progress mask
    }
    final int[] key = assertBodyKey[owner];
    FrameStack stack = ws.bodyStack;
    IntArena arena = ws.arena;
    MemoSet memo = ws.bodyMemo;
    int[] row = ws.bodyRow;
    stack.size = 0;
    memo.reset();
    int limit = mustEnd < 0 ? regionEnd : mustEnd;
    stack.push(assertStartById[owner], from, caps, 0, PROGRESSED); // bodies have no counted loops
    long steps = 0;
    while (!stack.isEmpty()) {
      if (++steps > MAX_MATCH_STEPS) {
        throw new MatchBudgetExceededException(
            "Lookaround match step budget exceeded ("
                + MAX_MATCH_STEPS
                + " frames; tune via -Dreggie.countedloop.maxSteps) — input is too ambiguous for"
                + " bounded exploration");
      }
      final int top = stack.states[stack.size - 1];
      final int pos = stack.positions[stack.size - 1];
      final int c0 = stack.capsStack[stack.size - 1];
      if (top < 0) {
        return c0; // deferred accept: every higher-priority continuation failed
      }
      final int state = top;
      final long prog = stack.progress[stack.size - 1] & ~headBit[state];
      stack.pop();
      int[] d = arena.data;
      if (isJoin[state]) {
        int k = 0;
        for (int i = 0; i < key.length; i++) {
          row[k++] = d[c0 + key[i]];
        }
        long pm = keyProgressMask[state];
        if (pm != 0) {
          pm &= prog;
          row[k++] = (int) pm;
          row[k++] = (int) (pm >>> 32);
        }
        if (!memo.add(state, pos, row, k)) {
          continue;
        }
      }
      int c = c0;
      final int eg = enterGroupById[state];
      final int xg = exitGroupById[state];
      if (eg >= 0 || xg >= 0) {
        c = arena.copy(c0, ws.slotCount);
        d = arena.data;
        if (eg >= 0) {
          d[c + 2 * eg] = pos;
        }
        if (xg >= 0) {
          d[c + 2 * xg + 1] = pos;
        }
      }
      final boolean accepts = subAcceptFor[state] == owner && (mustEnd < 0 || pos == mustEnd);
      final int br = backrefById[state];
      if (br >= 0) {
        // Never an accept state: a backreference fragment exits through a separate state.
        int gs = d[c + 2 * br];
        int ge = d[c + 2 * br + 1];
        if (gs >= 0) {
          int l = ge - gs;
          if (l == 0) {
            pushEpsilons(stack, state, pos, c, 0, prog);
          } else if (l > 0
              && pos + l <= limit
              && input.regionMatches(caseInsensitive, pos, input, gs, l)) {
            pushEpsilons(stack, state, pos + l, c, 0, PROGRESSED);
          }
        }
        continue;
      }
      if (anchorById[state] != null) {
        if (checkAnchor(anchorById[state], input, pos, 0, regionEnd)) {
          if (accepts) {
            stack.push(~state, pos, c, 0, prog);
          }
          pushEpsilons(stack, state, pos, c, 0, prog);
        }
        continue;
      }
      if (accepts) {
        stack.push(~state, pos, c, 0, prog);
      }
      pushEpsilons(stack, state, pos, c, 0, prog);
      if (pos < limit) {
        char ch = input.charAt(pos);
        int[] targets = charTargetIds[state];
        CharSet[] sets = charSets[state];
        for (int i = targets.length - 1; i >= 0; i--) {
          if (sets[i].contains(ch)) {
            stack.push(targets[i], pos + 1, c, 0, PROGRESSED);
          }
        }
      }
    }
    return -1;
  }

  /**
   * Boolean Thompson simulation of the body of assertion {@code owner} from {@code from}: true when
   * a body accept state is reached — anywhere for a lookahead ({@code mustEnd < 0}), exactly at
   * {@code mustEnd} for a lookbehind.
   */
  private boolean bodyMatches(
      Workspace ws, String input, int owner, int from, int regionEnd, int mustEnd) {
    if (ws.subMarks.length < stateCount) {
      ws.subCur = new int[stateCount];
      ws.subNext = new int[stateCount];
      ws.subStack = new int[stateCount];
      ws.subMarks = new int[stateCount];
      ws.subGen = 0;
    }
    int[] cur = ws.subCur;
    int[] next = ws.subNext;
    nextGen(ws);
    int n = closure(ws, input, assertStartById[owner], from, regionEnd, cur, 0);
    for (int p = from; ; p++) {
      if (mustEnd < 0 || p == mustEnd) {
        for (int i = 0; i < n; i++) {
          if (subAcceptFor[cur[i]] == owner) {
            return true;
          }
        }
      }
      if (n == 0 || p >= regionEnd || (mustEnd >= 0 && p >= mustEnd)) {
        return false;
      }
      char ch = input.charAt(p);
      nextGen(ws);
      int m = 0;
      for (int i = 0; i < n; i++) {
        int[] targets = charTargetIds[cur[i]];
        CharSet[] sets = charSets[cur[i]];
        for (int k = 0; k < targets.length; k++) {
          if (sets[k].contains(ch)) {
            m = closure(ws, input, targets[k], p + 1, regionEnd, next, m);
          }
        }
      }
      int[] tmp = cur;
      cur = next;
      next = tmp;
      n = m;
    }
  }

  /** Starts a fresh mark generation; resets the marks once the counter would wrap. */
  private static void nextGen(Workspace ws) {
    if (++ws.subGen == Integer.MAX_VALUE) {
      Arrays.fill(ws.subMarks, 0);
      ws.subGen = 1;
    }
  }

  /**
   * Adds the epsilon closure of {@code seed} at {@code pos} to {@code list[size..]} (states not yet
   * marked in the current generation; anchors that fail at {@code pos} cut their branch) and
   * returns the new size.
   */
  private int closure(
      Workspace ws, String input, int seed, int pos, int regionEnd, int[] list, int size) {
    int[] marks = ws.subMarks;
    int gen = ws.subGen;
    int[] stack = ws.subStack;
    if (marks[seed] == gen) {
      return size;
    }
    marks[seed] = gen;
    int top = 0;
    stack[top++] = seed;
    while (top > 0) {
      int s = stack[--top];
      if (anchorById[s] != null && !checkAnchor(anchorById[s], input, pos, 0, regionEnd)) {
        continue;
      }
      list[size++] = s;
      int[] eps = epsIds[s];
      for (int i = eps.length - 1; i >= 0; i--) {
        int t = eps[i];
        if (marks[t] != gen) {
          marks[t] = gen;
          stack[top++] = t;
        }
      }
    }
    return size;
  }

  private MatchResult buildResult(String input, int[] d, int caps) {
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    for (int g = 0; g <= groupCount; g++) {
      starts[g] = d[caps + 2 * g];
      ends[g] = d[caps + 2 * g + 1];
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
        return LineTerminators.isEnd(input, pos, regionEnd);
      case STRING_END_ABSOLUTE:
        return pos == regionEnd;
      case START_MULTILINE:
        return LineTerminators.isMultilineStart(input, pos, regionStart, regionEnd);
      case END_MULTILINE:
        return LineTerminators.isMultilineEnd(input, pos, regionEnd);
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
