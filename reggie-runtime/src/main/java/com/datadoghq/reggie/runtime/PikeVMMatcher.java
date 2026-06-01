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

  // "in-list" guards: prevent adding the same NFA state twice per step.
  private final boolean[] inClist;
  private final boolean[] inNlist;

  // Reusable capture array for the winning thread result.
  private final int[] winCaptures;

  // Scratch capture arrays for DFS capture recording during addThread.
  // One per DFS depth level; bounded by stateCount.
  private final int[][] scratchCaptures;

  // NFA states indexed by id for O(1) lookup.
  private final NFA.NFAState[] statesById;

  // Accept-state mask for O(1) accept check.
  private final boolean[] isAccept;

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

    statesById = new NFA.NFAState[stateCount];
    for (NFA.NFAState s : nfa.getStates()) {
      statesById[s.id] = s;
    }
    isAccept = new boolean[stateCount];
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      isAccept[s.id] = true;
    }
    markNativeRichApi();
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
      // Look for an accept thread in the current list.
      for (int t = 0; t < clistSize; t++) {
        if (isAccept[clistIds[t]] && pos == regionEnd) {
          return true;
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
      for (int t = 0; t < clistSize; t++) {
        if (isAccept[clistIds[t]] && pos == regionEnd) {
          int[] caps = Arrays.copyOf(clistCaptures[t], winCaptures.length);
          caps[1] = pos;
          return buildResult(input, caps);
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
      if (tryFindAt(input, start, len) >= 0) return start;
    }
    return -1;
  }

  /** Try matching starting at {@code tryPos}; returns match-end position or -1. */
  private int tryFindAt(String input, int tryPos, int regionEnd) {
    initClist(input, tryPos, tryPos, regionEnd);

    for (int pos = tryPos; pos <= regionEnd; pos++) {
      for (int t = 0; t < clistSize; t++) {
        if (isAccept[clistIds[t]]) {
          return pos; // match ends here
        }
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, tryPos, regionEnd);
      swapLists();
    }
    return -1;
  }

  private MatchResult findMatchResultFrom(String input, int fromPos) {
    int len = input.length();
    for (int start = fromPos; start <= len; start++) {
      MatchResult r = tryFindMatchAt(input, start, len);
      if (r != null) return r;
    }
    return null;
  }

  private MatchResult tryFindMatchAt(String input, int tryPos, int regionEnd) {
    initClist(input, tryPos, tryPos, regionEnd);

    // Greedy PikeVM rule: when a thread at index t accepts, threads at indices > t (lower priority)
    // cannot produce a better match. Truncate the clist to [0..t-1] so only higher-priority
    // non-accept threads continue. This lets a higher-priority thread that hasn't accepted yet
    // (but will at a later position) override the current accept — giving greedy longest-match from
    // the highest-priority thread (e.g. (_)? prefers consuming _ over the empty match, while
    // (fo|foo) prefers "fo" over "foo" since "fo" is the higher-priority first alternative).
    MatchResult best = null;

    for (int pos = tryPos; pos <= regionEnd; pos++) {
      for (int t = 0; t < clistSize; t++) {
        if (isAccept[clistIds[t]]) {
          int[] caps = Arrays.copyOf(clistCaptures[t], winCaptures.length);
          caps[1] = pos;
          best = buildResult(input, caps);
          clistSize = t; // discard lower-priority threads (indices > t); keep higher (0..t-1)
          break;
        }
      }
      if (pos == regionEnd) break;

      char ch = input.charAt(pos);
      resetNlist();
      stepChar(ch, pos + 1, input, tryPos, regionEnd);
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
    addThread(nfa.getStartState(), init, pos, 0, input, regionStart, regionEnd);
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
   */
  private void addThread(
      NFA.NFAState state,
      int[] captures,
      int pos,
      int depth,
      String input,
      int regionStart,
      int regionEnd) {
    if (inClist[state.id]) return;

    if (state.anchor != null) {
      if (!checkAnchor(state.anchor, input, pos, regionStart, regionEnd)) return;
      inClist[state.id] = true;
      for (NFA.NFAState next : state.getEpsilonTransitions()) {
        addThread(next, captures, pos, depth, input, regionStart, regionEnd);
      }
      return;
    }

    int[] ownCaptures = updateCaptures(state, captures, pos, depth);

    List<NFA.NFAState> epsilons = state.getEpsilonTransitions();
    if (!epsilons.isEmpty()) {
      inClist[state.id] = true;
      for (NFA.NFAState next : epsilons) {
        addThread(next, ownCaptures, pos, depth + 1, input, regionStart, regionEnd);
      }
      return;
    }

    // Leaf: has character transitions or is an accept state.
    inClist[state.id] = true;
    clistIds[clistSize] = state.id;
    System.arraycopy(ownCaptures, 0, clistCaptures[clistSize], 0, ownCaptures.length);
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
      for (NFA.NFAState next : epsilons) {
        addThreadToNlist(next, ownCaptures, pos, depth + 1, input, regionStart, regionEnd);
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
      case STRING_END_ABSOLUTE:
        return pos == regionEnd;
      case STRING_END:
        if (pos == regionEnd) return true;
        return pos == regionEnd - 1 && input.charAt(pos) == '\n';
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
    clistSize = 0;
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
    for (int i = 0; i < nlistSize; i++) {
      clistIds[i] = nlistIds[i];
      System.arraycopy(nlistCaptures[i], 0, clistCaptures[i], 0, len);
      inClist[nlistIds[i]] = true;
      inNlist[nlistIds[i]] = false;
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
