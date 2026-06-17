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

import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Interpreted PikeVM over a Thompson NFA. Implements Perl leftmost-greedy (first-thread-wins)
 * priority for correct native group-span extraction.
 *
 * <p>The VM runs two thread lists (clist/nlist) over the NFA states in lock-step, one step per
 * input character. Thread count is bounded by the NFA state count, so the overall complexity is
 * O(n·m) and the VM is ReDoS-safe.
 *
 * <p>Each thread carries a {@code int[]} capture-slot array. Slot {@code 2*k} holds the start and
 * slot {@code 2*k+1} holds the end of group {@code k}. Group 0 is the whole match; groups 1..n are
 * capturing groups. Unmatched groups are represented as {@code -1}.
 *
 * <p>Per-call buffers are pre-allocated at construction time; no allocations occur inside the hot
 * matching loop.
 */
public final class PikeVMMatcher extends ReggieMatcher {

  private final NFA nfa;
  private final int groupCount;
  private final int stateCount;

  // Thread-list storage: two rings of (stateId, captures[]) indexed by list-position.
  // Each list stores at most stateCount threads (each state appears at most once).
  private final int[] clistIds;
  private final int[] nlistIds;
  private final int[][] clistCaptures;
  private final int[][] nlistCaptures;
  private int clistSize;
  private int nlistSize;

  // T1.5: index of the first (highest-priority) accepting thread currently in clist, or -1.
  // Maintained incrementally as clist is populated (resetClist / addThread leaf / swapLists) so the
  // per-position accept check is O(1) instead of an O(clistSize) scan over isAccept[] every step.
  private int clistFirstAccept = -1;

  // "in-list" guards: prevent adding the same NFA state twice per step.
  private final boolean[] inClist;
  private final boolean[] inNlist;

  // Reusable capture array for the winning thread result.
  private final int[] winCaptures;

  // Scratch capture arrays for DFS capture recording during addThread.
  // One per DFS depth level; bounded by stateCount.
  private final int[][] scratchCaptures;

  // Per-clist-slot marker: true when the slot was added via a path that passed through TWO OR
  // MORE distinct anchor states at pos=regionStart. This identifies unrolled-quantifier consuming
  // threads (e.g. `a copy3` in `(^a?){3}` reached via copy1-^, copy2-^, copy3-^) while
  // leaving single-anchor consuming threads (e.g. `b` in `\A(?:b|1)?` or `(^b)*`) untouched.
  // The count is tracked as an `int anchorCount` parameter in addThread.
  private final boolean[] clistViaMultipleAnchors;

  // NFA states indexed by id for O(1) lookup.
  private final NFA.NFAState[] statesById;

  // Accept-state mask for O(1) accept check.
  private final boolean[] isAccept;

  // For each GroupExit state (indexed by state id): true when the group body can produce an
  // empty match (i.e. there is an epsilon-only path from the corresponding GroupEntry to this
  // GroupExit). Used by the trailing-empty-iteration rebind to avoid propagating captures when
  // the loop body requires character consumption (e.g. `(.)+` vs `(.*[_]*)+`).
  private final boolean[] groupBodyNullable;

  // T1.2 required-first-char prefilter. firstByteAscii[c] is true when some first-consuming
  // transition reachable from the start state (via the epsilon closure, crossing anchor states)
  // can accept ASCII char c. A find() start position whose (ASCII) char is not in this set cannot
  // begin a match — UNLESS the pattern can match the empty string, in which case prefilterUsable is
  // false and no position is skipped. Non-ASCII positions are conservatively never skipped (sound).
  private final boolean[] firstByteAscii;
  private final boolean prefilterUsable;

  // T1.4 boolean find() fast path: a SELF-ANCHORING lazy DFA. The step re-injects the start-state
  // closure on every character (an implicit ".*?" prefix), so every candidate start position is
  // tracked simultaneously in one left-to-right scan — unlike LazyDFACache.findFrom over the raw
  // NFA, which loses viable later starts on its restart-on-DEAD. Used ONLY for boolean find() and
  // only when the pattern is anchor/assertion/backref-free; findFrom() (position), matches(),
  // findMatch()/match()/group all stay on the priority-correct thread simulation. null =
  // ineligible.
  private final LazyDFACache findDfa;
  private final NfaStep findStep;
  private final boolean findCanMatchEmpty;
  private int[] startClosureIds; // set in ctor before findStep is used; immutable thereafter

  /** Construct a PikeVMMatcher over the given NFA and pattern string. */
  public PikeVMMatcher(NFA nfa, String pattern) {
    super(pattern);
    this.nfa = nfa;
    this.groupCount = nfa.getGroupCount();
    this.stateCount = nfa.getStates().size();

    clistIds = new int[stateCount];
    nlistIds = new int[stateCount];
    int slotCount = 2 * (groupCount + 1);
    clistCaptures = new int[stateCount][slotCount];
    nlistCaptures = new int[stateCount][slotCount];
    inClist = new boolean[stateCount];
    inNlist = new boolean[stateCount];
    winCaptures = new int[slotCount];
    scratchCaptures = new int[stateCount + 1][slotCount];
    clistViaMultipleAnchors = new boolean[stateCount];

    statesById = new NFA.NFAState[stateCount];
    for (NFA.NFAState s : nfa.getStates()) {
      statesById[s.id] = s;
    }

    // Precompute groupBodyNullable: for each GroupExit state, determine whether there is
    // an epsilon-only path from its matching GroupEntry to that GroupExit.
    groupBodyNullable = computeGroupBodyNullable(nfa);
    isAccept = new boolean[stateCount];
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      isAccept[s.id] = true;
    }

    // T1.2: precompute the required-first-char prefilter from the start-state epsilon closure.
    firstByteAscii = new boolean[128];
    prefilterUsable = computeFirstByteFilter(nfa, firstByteAscii);

    // T1.4: build the self-anchoring boolean find() DFA when the pattern is anchor/assertion/
    // backref-free (those need position context the position-independent step can't supply).
    if (findDfaEligible(nfa)) {
      startClosureIds = sortedEpsilonClosure(new int[] {nfa.getStartState().id});
      boolean empty = false;
      for (int id : startClosureIds) {
        if (isAccept[id]) {
          empty = true;
          break;
        }
      }
      findCanMatchEmpty = empty;
      int[] acceptArr = new int[nfa.getAcceptStates().size()];
      int ai = 0;
      for (NFA.NFAState s : nfa.getAcceptStates()) acceptArr[ai++] = s.id;
      findDfa = new LazyDFACache(startClosureIds, acceptArr);
      // Self-anchoring step: closure(targets(cur, c)) UNION startClosure.
      findStep = (cur, c) -> sortedClosureUnionStart(transitionTargets(cur, (char) c));
    } else {
      findDfa = null;
      findStep = null;
      findCanMatchEmpty = false;
    }

    markNativeRichApi();
  }

  /** Eligible for the boolean find() fast path: no anchors, assertions, or backreferences. */
  private static boolean findDfaEligible(NFA nfa) {
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.anchor != null || s.assertionType != null || s.backrefCheck != null) return false;
    }
    return true;
  }

  /** Targets of consuming transitions on {@code ch} from the given NFA state ids (unsorted). */
  private int[] transitionTargets(int[] stateIds, char ch) {
    boolean[] seen = new boolean[stateCount]; // dedup targets to bound size by stateCount
    int[] tmp = new int[stateCount];
    int n = 0;
    for (int id : stateIds) {
      for (NFA.Transition tr : statesById[id].getTransitions()) {
        if (tr.chars.contains(ch) && !seen[tr.target.id]) {
          seen[tr.target.id] = true;
          tmp[n++] = tr.target.id;
        }
      }
    }
    return Arrays.copyOf(tmp, n);
  }

  /** Sorted, de-duplicated epsilon closure of the given seed ids (anchor-free patterns). */
  private int[] sortedEpsilonClosure(int[] seed) {
    boolean[] inSet = new boolean[stateCount];
    int[] stack = new int[stateCount];
    int sp = 0;
    for (int id : seed) {
      if (!inSet[id]) {
        inSet[id] = true;
        stack[sp++] = id;
      }
    }
    int count = sp;
    while (sp > 0) {
      int id = stack[--sp];
      for (NFA.NFAState e : statesById[id].getEpsilonTransitions()) {
        if (!inSet[e.id]) {
          inSet[e.id] = true;
          stack[sp++] = e.id;
          count++;
        }
      }
    }
    int[] out = new int[count];
    int oi = 0;
    for (int id = 0; id < stateCount; id++) if (inSet[id]) out[oi++] = id;
    return out; // ascending
  }

  /** Sorted closure of {@code targets} UNIONed with the start closure (the self-anchoring step). */
  private int[] sortedClosureUnionStart(int[] targets) {
    boolean[] inSet = new boolean[stateCount];
    int[] stack = new int[stateCount];
    int sp = 0;
    for (int id : targets) {
      if (!inSet[id]) {
        inSet[id] = true;
        stack[sp++] = id;
      }
    }
    for (int id : startClosureIds) {
      if (!inSet[id]) {
        inSet[id] = true;
        stack[sp++] = id;
      }
    }
    int count = sp;
    while (sp > 0) {
      int id = stack[--sp];
      for (NFA.NFAState e : statesById[id].getEpsilonTransitions()) {
        if (!inSet[e.id]) {
          inSet[e.id] = true;
          stack[sp++] = e.id;
          count++;
        }
      }
    }
    int[] out = new int[count];
    int oi = 0;
    for (int id = 0; id < stateCount; id++) if (inSet[id]) out[oi++] = id;
    return out; // ascending
  }

  /**
   * Populate {@code firstByteAscii} with the ASCII chars that some first-consuming transition can
   * accept, by walking the epsilon closure of the start state (crossing anchor states, which never
   * consume). Returns {@code true} iff the prefilter is usable: the pattern cannot match the empty
   * string (no accept state is reachable epsilon-only from start) AND at least one ASCII char
   * cannot begin a match (otherwise skipping never fires and the per-position check is pure
   * overhead).
   */
  private static boolean computeFirstByteFilter(NFA nfa, boolean[] firstByteAscii) {
    java.util.Set<Integer> seen = new java.util.HashSet<>();
    java.util.ArrayDeque<NFA.NFAState> q = new java.util.ArrayDeque<>();
    NFA.NFAState start = nfa.getStartState();
    q.add(start);
    seen.add(start.id);
    boolean canMatchEmpty = false;
    while (!q.isEmpty()) {
      NFA.NFAState s = q.poll();
      if (nfa.getAcceptStates().contains(s)) {
        canMatchEmpty = true; // accept reachable without consuming any char
      }
      for (NFA.Transition t : s.getTransitions()) {
        for (int c = 0; c < 128; c++) {
          if (t.chars.contains((char) c)) firstByteAscii[c] = true;
        }
      }
      for (NFA.NFAState e : s.getEpsilonTransitions()) {
        if (seen.add(e.id)) q.add(e);
      }
    }
    if (canMatchEmpty) return false;
    for (boolean b : firstByteAscii) {
      if (!b) return true; // some ASCII char cannot start a match → skipping can fire
    }
    return false; // every ASCII char can start (e.g. \S+/.* lead) → prefilter is a no-op
  }

  // -------------------------------------------------------------------------
  // ReggieMatcher public API
  // -------------------------------------------------------------------------

  @Override
  public boolean matches(String input) {
    return runMatches(input, 0, input.length());
  }

  @Override
  public boolean find(String input) {
    if (findDfa != null) {
      // Empty-matchable patterns match (the empty string) at every position, including "".
      if (findCanMatchEmpty) return true;
      // Self-anchoring DFA: a non-negative result means the pattern matched some substring.
      return findDfa.findFrom(input, 0, findStep) >= 0;
    }
    return findFrom(input, 0) >= 0;
  }

  @Override
  public int findFrom(String input, int start) {
    return findStartFrom(input, start);
  }

  @Override
  public MatchResult match(String input) {
    return runMatchResult(input, 0, input.length());
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    return findMatchResultFrom(input, start);
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    return runMatches(input.subSequence(start, end).toString(), 0, end - start);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    String region = input.subSequence(start, end).toString();
    MatchResult r = runMatchResult(region, 0, region.length());
    if (r == null || start == 0) return r;
    return shiftResult(r, start, input.toString());
  }

  // -------------------------------------------------------------------------
  // Core PikeVM — matches() semantics (whole region must match)
  // -------------------------------------------------------------------------

  private boolean runMatches(String input, int regionStart, int regionEnd) {
    initClist(input, regionStart, regionStart, regionEnd);

    for (int pos = regionStart; pos <= regionEnd; pos++) {
      // First (highest-priority) accept thread in the current list, or -1 (O(1), see
      // clistFirstAccept).
      int t = clistFirstAccept;
      if (t >= 0) {
        if (pos == regionEnd) return true;
        // Zero-length accept at region start: JDK prevents consuming threads that traversed
        // two or more distinct anchor states (e.g. copy2-^ and copy3-^ in (^a?){3}) from
        // extending a zero-length match into a full-input match. Threads that passed through
        // only one anchor (e.g. \A then 1 in \A(?:b|1)?) are retained as legitimate paths.
        if (pos == regionStart) {
          // keepLowerPriority=true: lower-priority threads may still produce a full-input match.
          pruneAnchorDerivedAtStart(t, true);
        }
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, regionStart, regionEnd);
      swapLists();
    }
    return false;
  }

  private MatchResult runMatchResult(String input, int regionStart, int regionEnd) {
    initClist(input, regionStart, regionStart, regionEnd);

    for (int pos = regionStart; pos <= regionEnd; pos++) {
      int t = clistFirstAccept;
      if (t >= 0) {
        if (pos == regionEnd) {
          int[] caps = Arrays.copyOf(clistCaptures[t], winCaptures.length);
          caps[1] = pos;
          return buildResult(input, caps);
        }
        // Same zero-length-accept pruning as runMatches(), keeping lower-priority threads.
        if (pos == regionStart) {
          pruneAnchorDerivedAtStart(t, true);
        }
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, regionStart, regionEnd);
      swapLists();
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Core PikeVM — find() semantics (match anywhere)
  // -------------------------------------------------------------------------

  private int findStartFrom(String input, int fromPos) {
    int len = input.length();
    for (int start = fromPos; start <= len; start++) {
      // T1.2 prefilter: skip start positions whose char cannot begin any match.
      if (prefilterUsable && start < len) {
        char c = input.charAt(start);
        if (c < 128 && !firstByteAscii[c]) continue;
      }
      if (tryFindAt(input, start, fromPos, len) >= 0) return start;
    }
    return -1;
  }

  /**
   * Try matching starting at {@code tryPos}; returns match-end position or -1. {@code regionStart}
   * is the fixed search-region origin used for start-anchor evaluation (^, \A); it does not move
   * with {@code tryPos}.
   */
  private int tryFindAt(String input, int tryPos, int regionStart, int regionEnd) {
    initClist(input, tryPos, regionStart, regionEnd);

    for (int pos = tryPos; pos <= regionEnd; pos++) {
      if (clistFirstAccept >= 0) {
        return pos; // match ends here
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, regionStart, regionEnd);
      swapLists();
    }
    return -1;
  }

  private MatchResult findMatchResultFrom(String input, int fromPos) {
    int len = input.length();
    for (int start = fromPos; start <= len; start++) {
      // T1.2 prefilter: skip start positions whose char cannot begin any match.
      if (prefilterUsable && start < len) {
        char c = input.charAt(start);
        if (c < 128 && !firstByteAscii[c]) continue;
      }
      MatchResult r = tryFindMatchAt(input, start, fromPos, len);
      if (r != null) return r;
    }
    return null;
  }

  private MatchResult tryFindMatchAt(String input, int tryPos, int regionStart, int regionEnd) {
    initClist(input, tryPos, regionStart, regionEnd);

    // Greedy PikeVM rule: when a thread at index t accepts, threads at indices > t (lower priority)
    // cannot produce a better match. Truncate the clist to [0..t-1] so only higher-priority
    // non-accept threads continue. This lets a higher-priority thread that hasn't accepted yet
    // (but will at a later position) override the current accept — giving greedy longest-match from
    // the highest-priority thread (e.g. (_)? prefers consuming _ over the empty match, while
    // (fo|foo) prefers "fo" over "foo" since "fo" is the higher-priority first alternative).
    //
    // Exception — zero-length match at tryPos: JDK semantics prevent anchor-derived consuming
    // threads from overriding a zero-length accept. When the first accept fires at tryPos,
    // retain only non-anchor-derived higher-priority threads so that legitimate greedy consuming
    // paths (e.g. `a` in `(a)*`) can still extend the match while anchor-driven empty-iteration
    // consuming paths (e.g. `a` copy3 in `(^a?){3}`) cannot.
    MatchResult best = null;

    for (int pos = tryPos; pos <= regionEnd; pos++) {
      int t = clistFirstAccept;
      if (t >= 0) {
        int[] caps = Arrays.copyOf(clistCaptures[t], winCaptures.length);
        caps[1] = pos;
        best = buildResult(input, caps);
        if (pos == tryPos) {
          // Zero-length match at start: prune anchor-derived high-priority threads; discard
          // lower-priority threads (keepLowerPriority=false) per Perl priority rules.
          pruneAnchorDerivedAtStart(t, false);
        } else {
          clistSize = t;
        }
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, regionStart, regionEnd);
      swapLists();
      if (clistSize == 0) break;
    }
    return best;
  }

  // -------------------------------------------------------------------------
  // Step helpers
  // -------------------------------------------------------------------------

  /** Seed clist with the start-state thread at position {@code pos}. */
  private void initClist(String input, int pos, int regionStart, int regionEnd) {
    resetClist();
    int[] init = scratchCaptures[0];
    Arrays.fill(init, -1);
    init[0] = pos; // tentative whole-match start
    addThread(nfa.getStartState(), init, pos, 0, 0, false, input, regionStart, regionEnd);
  }

  /** Advance each thread in clist by character {@code ch}, populating nlist. */
  private void stepChar(char ch, int nextPos, String input, int regionStart, int regionEnd) {
    for (int t = 0; t < clistSize; t++) {
      NFA.NFAState state = statesById[clistIds[t]];
      int[] caps = clistCaptures[t];
      for (NFA.Transition tr : state.getTransitions()) {
        if (tr.chars.contains(ch)) {
          int[] nc = scratchCaptures[0];
          System.arraycopy(caps, 0, nc, 0, nc.length);
          addThreadToNlist(tr.target, nc, nextPos, 0, input, regionStart, regionEnd);
        }
      }
    }
  }

  // -------------------------------------------------------------------------
  // addThread: priority-ordered epsilon closure with capture recording
  // -------------------------------------------------------------------------

  /**
   * Add a thread rooted at {@code state} to clist. Performs a DFS through epsilon transitions in
   * insertion order (= Perl priority). Capture slots are updated inline for enterGroup/exitGroup
   * states.
   *
   * <p>{@code anchorCount} counts the distinct anchor states that fired at pos=regionStart on the
   * DFS path from the clist root to {@code state}. {@code anchorFollowedBySkip} is set when a
   * non-first epsilon (i.e. a quantifier-skip path) of a non-anchor, non-group state was traversed
   * while {@code anchorCount > 0}. Leaf states are marked in {@link #clistViaMultipleAnchors} when
   * both {@code anchorFollowedBySkip} is true and {@code anchorCount >= 2}: this identifies
   * unrolled-quantifier consuming threads (e.g. {@code a copy3} in {@code (^a?){3}}) that arrived
   * via anchor firings interleaved with quantifier skips, distinguishing them from direct-sequence
   * anchored paths (e.g. {@code \A{3}a} where anchorFollowedBySkip remains false).
   */
  private void addThread(
      NFA.NFAState state,
      int[] captures,
      int pos,
      int depth,
      int anchorCount,
      boolean anchorFollowedBySkip,
      String input,
      int regionStart,
      int regionEnd) {
    if (inClist[state.id]) return;

    if (state.anchor != null) {
      if (!checkAnchor(state.anchor, input, pos, regionStart, regionEnd)) return;
      inClist[state.id] = true;
      // Increment the anchor count; anchorFollowedBySkip is not reset by anchor firing.
      for (NFA.NFAState next : state.getEpsilonTransitions()) {
        addThread(
            next,
            captures,
            pos,
            depth,
            anchorCount + 1,
            anchorFollowedBySkip,
            input,
            regionStart,
            regionEnd);
      }
      return;
    }

    int[] ownCaptures = updateCaptures(state, captures, pos, depth);

    List<NFA.NFAState> epsilons = state.getEpsilonTransitions();
    if (!epsilons.isEmpty()) {
      inClist[state.id] = true;
      int[] passedCaptures = ownCaptures;
      // Determine the "skip-after-anchor" flag for each epsilon child: set to true when
      // taking a non-first epsilon of a non-anchor, non-group state while anchors have fired.
      // This identifies quantifier-skip paths (e.g. a? skip to next copy) as distinct from
      // anchor-chaining epsilons (e.g. \A{3}a where consecutive \A anchors fire in sequence).
      boolean isQuantifierSkipContext =
          anchorCount > 0
              && state.anchor == null
              && state.enterGroup == null
              && state.exitGroup == null;
      for (int i = 0; i < epsilons.size(); i++) {
        NFA.NFAState next = epsilons.get(i);
        boolean childAnchorFollowedBySkip =
            anchorFollowedBySkip || (i > 0 && isQuantifierSkipContext);
        // Trailing-empty-iteration rebind: when the loop-back epsilon of a '+' quantifier
        // enters a capturing group (enterGroup) and that group was not already in clist, the
        // addThread call below will run updateCaptures(GroupEntry, ...) writing updated
        // group-start into scratchCaptures[depth+2]. Record this BEFORE the call so we can
        // propagate those captures to subsequent sibling epsilons (e.g. the exit/accept path).
        // Scoped to loop-back: only propagate when the current state is a group EXIT (exitGroup
        // not null), which identifies the "GroupExit → GroupEntry loop-back" pattern of '+' and
        // '*'. For '?' entry states (exitGroup==null), the siblings are independent optional/skip
        // paths and must not receive updated captures from the try-match sibling.
        boolean willUpdateGroupEntry =
            state.exitGroup != null
                && groupBodyNullable[state.id]
                && next.enterGroup != null
                && !inClist[next.id];
        addThread(
            next,
            passedCaptures,
            pos,
            depth + 1,
            anchorCount,
            childAnchorFollowedBySkip,
            input,
            regionStart,
            regionEnd);
        if (willUpdateGroupEntry) {
          int scratchIdx = Math.min(depth + 2, scratchCaptures.length - 1);
          passedCaptures = scratchCaptures[scratchIdx];
        }
      }
      return;
    }

    // Leaf: has character transitions or is an accept state.
    inClist[state.id] = true;
    clistIds[clistSize] = state.id;
    System.arraycopy(ownCaptures, 0, clistCaptures[clistSize], 0, ownCaptures.length);
    // Mark as "via skip-after-anchor with 2+ anchor fires": signals unrolled-quantifier
    // consuming threads that must not override a zero-length match (e.g. `a copy3` in
    // `(^a?){3}` but NOT `a` in `\A{3}a` where anchorFollowedBySkip remains false).
    clistViaMultipleAnchors[clistSize] = anchorFollowedBySkip && anchorCount >= 2;
    if (clistFirstAccept < 0 && isAccept[state.id]) clistFirstAccept = clistSize;
    clistSize++;
  }

  /** Add a thread rooted at {@code state} to nlist (same logic as addThread). */
  private void addThreadToNlist(
      NFA.NFAState state,
      int[] captures,
      int pos,
      int depth,
      String input,
      int regionStart,
      int regionEnd) {
    if (inNlist[state.id]) return;

    if (state.anchor != null) {
      if (!checkAnchor(state.anchor, input, pos, regionStart, regionEnd)) return;
      inNlist[state.id] = true;
      for (NFA.NFAState next : state.getEpsilonTransitions()) {
        addThreadToNlist(next, captures, pos, depth, input, regionStart, regionEnd);
      }
      return;
    }

    int[] ownCaptures = updateCaptures(state, captures, pos, depth);

    List<NFA.NFAState> epsilons = state.getEpsilonTransitions();
    if (!epsilons.isEmpty()) {
      inNlist[state.id] = true;
      int[] passedCaptures = ownCaptures;
      for (NFA.NFAState next : epsilons) {
        // Trailing-empty-iteration rebind: mirror the scoped logic from addThread above.
        // Only propagate when the current state is a group EXIT (loop-back context) AND
        // the group body is nullable (can produce an empty match). The nullable check prevents
        // spurious rebind when the loop body requires character consumption (e.g. `(.)+`
        // cannot empty-iterate, so the capture must not be rebound to [pos,pos)).
        boolean willUpdateGroupEntry =
            state.exitGroup != null
                && groupBodyNullable[state.id]
                && next.enterGroup != null
                && !inNlist[next.id];
        addThreadToNlist(next, passedCaptures, pos, depth + 1, input, regionStart, regionEnd);
        if (willUpdateGroupEntry) {
          int scratchIdx = Math.min(depth + 2, scratchCaptures.length - 1);
          passedCaptures = scratchCaptures[scratchIdx];
        }
      }
      return;
    }

    inNlist[state.id] = true;
    nlistIds[nlistSize] = state.id;
    System.arraycopy(ownCaptures, 0, nlistCaptures[nlistSize], 0, ownCaptures.length);
    nlistSize++;
  }

  /**
   * Return captures updated for the current state's group annotations. When the state has no group
   * markers the original array is returned unchanged; otherwise a scratch copy is used.
   */
  private int[] updateCaptures(NFA.NFAState state, int[] captures, int pos, int depth) {
    if (state.enterGroup == null && state.exitGroup == null) {
      return captures;
    }
    int scratchIdx = Math.min(depth + 1, scratchCaptures.length - 1);
    int[] copy = scratchCaptures[scratchIdx];
    System.arraycopy(captures, 0, copy, 0, captures.length);
    if (state.enterGroup != null) {
      copy[2 * state.enterGroup] = pos;
    }
    if (state.exitGroup != null) {
      copy[2 * state.exitGroup + 1] = pos;
    }
    return copy;
  }

  // -------------------------------------------------------------------------
  // Anchor checking
  // -------------------------------------------------------------------------

  private static boolean checkAnchor(
      NFA.AnchorType anchor, String input, int pos, int regionStart, int regionEnd) {
    switch (anchor) {
      case START:
      case STRING_START:
        return pos == regionStart;
      case END:
      case STRING_END:
        // $ and \Z both match at end of input or just before a trailing \n.
        if (pos == regionEnd) return true;
        return pos == regionEnd - 1 && input.charAt(pos) == '\n';
      case STRING_END_ABSOLUTE:
        // \z matches only at the absolute end of input.
        return pos == regionEnd;
      case START_MULTILINE:
        return pos == regionStart || (pos > 0 && input.charAt(pos - 1) == '\n');
      case END_MULTILINE:
        return pos == regionEnd || (pos < regionEnd && input.charAt(pos) == '\n');
      case WORD_BOUNDARY:
        {
          boolean beforeWord = pos > 0 && Character.isLetterOrDigit(input.charAt(pos - 1));
          boolean afterWord = pos < regionEnd && Character.isLetterOrDigit(input.charAt(pos));
          return beforeWord != afterWord;
        }
      case RESET_MATCH:
        return true;
      default:
        return false;
    }
  }

  // -------------------------------------------------------------------------
  // List management
  // -------------------------------------------------------------------------

  private void resetClist() {
    // Full clear: selective clearing misses non-leaf epsilon states whose inClist flag was set
    // inside addThread but whose id was never appended to clistIds.
    Arrays.fill(inClist, false);
    Arrays.fill(clistViaMultipleAnchors, false);
    clistSize = 0;
    clistFirstAccept = -1;
  }

  /**
   * Prune clist when a zero-length accept is detected at the start position.
   *
   * <p>For {@code matches()}/{@code match()} (called with {@code keepLowerPriority=true}): removes
   * multi-anchor-derived threads at indices 0..{@code acceptIdx-1} (higher priority), then appends
   * the lower-priority threads at indices {@code acceptIdx+1..clistSize-1}. This preserves
   * legitimate consuming threads like {@code b} in {@code a?|b} (lower priority than the empty
   * accept) that are still needed to satisfy a full-input match.
   *
   * <p>For {@code findMatch()} (called with {@code keepLowerPriority=false}): same high-priority
   * pruning, but lower-priority threads (t &gt; acceptIdx) are discarded per Perl priority rules (a
   * lower-priority thread cannot produce a better match than the current best).
   *
   * <p>The inClist flags for removed multi-anchor-derived threads remain {@code true} so they
   * cannot re-enter clist through subsequent character steps.
   */
  private void pruneAnchorDerivedAtStart(int acceptIdx, boolean keepLowerPriority) {
    int write = 0;
    for (int t = 0; t < acceptIdx; t++) {
      if (!clistViaMultipleAnchors[t]) {
        if (write != t) {
          clistIds[write] = clistIds[t];
          System.arraycopy(clistCaptures[t], 0, clistCaptures[write], 0, clistCaptures[t].length);
          clistViaMultipleAnchors[write] = false;
        }
        write++;
      }
      // multi-anchor-derived: leave inClist[id]=true so the thread cannot re-enter
    }
    // The accepting thread at acceptIdx is dropped here; the compacted clist's first-accept index
    // is recomputed by the next swapLists. Invalidate to avoid observing a stale positive.
    clistFirstAccept = -1;
    if (keepLowerPriority) {
      // Append lower-priority threads (t > acceptIdx) — needed for full-input match checks.
      for (int t = acceptIdx + 1; t < clistSize; t++) {
        if (write != t) {
          clistIds[write] = clistIds[t];
          System.arraycopy(clistCaptures[t], 0, clistCaptures[write], 0, clistCaptures[t].length);
          clistViaMultipleAnchors[write] = clistViaMultipleAnchors[t];
        }
        write++;
      }
    }
    clistSize = write;
  }

  private void resetNlist() {
    // Full clear: selective clearing misses non-leaf epsilon states whose inNlist flag was set
    // inside addThreadToNlist but whose id was never appended to nlistIds.
    Arrays.fill(inNlist, false);
    nlistSize = 0;
  }

  private void swapLists() {
    int len = winCaptures.length;
    // Move nlist into clist.
    for (int i = 0; i < nlistSize; i++) {
      inClist[clistIds[i < clistSize ? i : 0]] = false; // clear old clist guards lazily below
    }
    // Full reset of clist guards then re-set from nlist.
    Arrays.fill(inClist, false);
    clistFirstAccept = -1;
    for (int i = 0; i < nlistSize; i++) {
      clistIds[i] = nlistIds[i];
      System.arraycopy(nlistCaptures[i], 0, clistCaptures[i], 0, len);
      inClist[nlistIds[i]] = true;
      inNlist[nlistIds[i]] = false;
      // nlist is built in priority order, so the first accepting entry is the highest priority.
      if (clistFirstAccept < 0 && isAccept[nlistIds[i]]) clistFirstAccept = i;
    }
    clistSize = nlistSize;
    nlistSize = 0;
  }

  // -------------------------------------------------------------------------
  // Result construction
  // -------------------------------------------------------------------------

  private MatchResult buildResult(String input, int[] caps) {
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    for (int g = 0; g <= groupCount; g++) {
      starts[g] = caps[2 * g];
      ends[g] = caps[2 * g + 1];
    }
    return new MatchResultImpl(input, starts, ends, groupCount, Collections.emptyMap());
  }

  /**
   * Precompute, for each GroupExit state, whether the group body is nullable (can produce an empty
   * match). This is true when there exists an epsilon-only path from the corresponding GroupEntry
   * to the GroupExit. Used to guard the trailing-empty-iteration rebind: propagation happens only
   * when the group body is nullable, matching JDK semantics that prevent rebind when the loop body
   * requires character consumption (e.g. {@code (.)+} has a non-nullable body; propagation must not
   * fire there even though the GroupExit → GroupEntry loop-back epsilon exists).
   */
  private static boolean[] computeGroupBodyNullable(NFA nfa) {
    List<NFA.NFAState> states = nfa.getStates();
    int n = states.size();
    boolean[] nullable = new boolean[n];
    // For each state, determine if it can reach itself (or a GroupExit peer) via epsilon-only
    // paths.
    // We compute per-state epsilon-closure reachability (boolean[] of reachable state IDs).
    // Since groups match GroupEntry → body → GroupExit, we check:
    // for each GroupEntry state E, can GroupExit state X (E's pair) be reached via epsilon only?
    //
    // Approach: compute epsilon closure of each GroupEntry, mark GroupExit states reachable.
    // The epsilon closure is computed as a simple BFS/DFS over epsilon transitions.
    NFA.NFAState[] byId = new NFA.NFAState[n];
    for (NFA.NFAState s : states) {
      byId[s.id] = s;
    }
    // For each GroupExit state, check if it's reachable from the corresponding GroupEntry.
    // We identify GroupEntry states (those with enterGroup != null) and track which GroupExit
    // states (with exitGroup == enterGroup) are reachable via epsilon.
    boolean[] visited = new boolean[n];
    int[] stack = new int[n];
    for (NFA.NFAState entryState : states) {
      if (entryState.enterGroup == null) continue;
      Integer groupId = entryState.enterGroup;
      // BFS/DFS epsilon-only reachability from entryState
      Arrays.fill(visited, false);
      int top = 0;
      stack[top++] = entryState.id;
      visited[entryState.id] = true;
      while (top > 0) {
        NFA.NFAState cur = byId[stack[--top]];
        if (cur.exitGroup != null && cur.exitGroup.equals(groupId)) {
          // Found the matching GroupExit reachable via epsilon from this GroupEntry.
          nullable[cur.id] = true;
        }
        for (NFA.NFAState next : cur.getEpsilonTransitions()) {
          if (!visited[next.id]) {
            visited[next.id] = true;
            stack[top++] = next.id;
          }
        }
      }
    }
    return nullable;
  }

  private static MatchResult shiftResult(MatchResult r, int delta, String originalInput) {
    int gc = r.groupCount();
    int[] starts = new int[gc + 1];
    int[] ends = new int[gc + 1];
    for (int i = 0; i <= gc; i++) {
      int s = r.start(i);
      int e = r.end(i);
      starts[i] = s == -1 ? -1 : s + delta;
      ends[i] = e == -1 ? -1 : e + delta;
    }
    return new MatchResultImpl(originalInput, starts, ends, gc, Collections.emptyMap());
  }
}
