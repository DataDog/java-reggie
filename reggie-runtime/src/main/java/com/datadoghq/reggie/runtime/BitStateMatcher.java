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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * BitState NFA-with-capture engine, routed to by {@code
 * PatternAnalyzer.MatchingStrategy#BITSTATE_CAPTURE} (see {@code
 * PatternAnalyzer.isBitStateEligible}). Bounded-backtracking DFS over the shared {@link NFA},
 * linear-time and ReDoS-safe via a {@code (stateId, pos)} visited set (design doc {@code
 * doc/2026-07-03-bitstate-capture-engine-design.md} §3-§5), for patterns free of backreferences,
 * lookaround, atomic groups, possessive quantifiers, and the anchored-nullable-repeated-body shape.
 *
 * <p>DFS explores a state's epsilon/consuming children in NFA insertion order (= Perl priority);
 * children are pushed onto the job stack in reverse order so the highest-priority child pops first,
 * and the first accept reached is therefore the leftmost-first match — identical semantics to
 * {@link PikeVMMatcher}. Captures are tracked in a single mutable {@code caps} array undone via
 * {@code RESTORE} stack frames on backtrack, rather than PikeVM's per-depth capture-array copies.
 *
 * <p>Unanchored search (find/findMatch) is a single left-to-right pass: one visited bitmap covers
 * the whole {@code [scanStart, len]} span and is never cleared between candidate start positions,
 * so a {@code (stateId, pos)} cell already proven dead (or already leading to the match) by an
 * earlier start is not re-expanded for a later one — this is what bounds the unanchored scan to
 * {@code O(stateCount * span)} rather than {@code O(stateCount * span^2)}.
 *
 * <p>When {@code stateCount * (span + 1)} exceeds the per-search cell budget, the whole call is
 * delegated to a lazily-constructed {@link PikeVMMatcher} over the same {@link NFA} (design §8) —
 * this is the only fallback path; it never truncates results silently.
 */
final class BitStateMatcher extends ReggieMatcher {

  // RE2's reference bound for a (pc, pos) visited set: 256K cells. See design doc §5.
  private static final int BUDGET_CELLS = 262_144;

  private static final byte EXPAND = 0;
  private static final byte RESTORE = 1;

  private final NFA nfa;
  private final int groupCount;
  private final int stateCount;
  private final NFA.NFAState[] statesById;
  private final boolean[] isAccept;
  private final int startStateId;

  // Single mutable capture array (slot 2g/2g+1 = start/end of group g) plus the winning snapshot.
  private final int[] caps;
  private final int[] winCaptures;

  // Job stack: parallel arrays, grown geometrically. EXPAND(stateId, pos) / RESTORE(slot, val).
  private byte[] stackKind = new byte[64];
  private int[] stackA = new int[64];
  private int[] stackB = new int[64];
  private int sp;

  // (stateId, pos) visited set for the current top-level call, indexed
  // stateId * visitedWidth + (pos - visitedScanStart). Reused/regrown across calls.
  private boolean[] visited;
  private int visitedScanStart;
  private int visitedWidth;

  private PikeVMMatcher delegate;

  // Counts budget-exceeded delegations to PikeVMMatcher — design §5/§8 require the fallback to be
  // observable (no silent truncation), even though it never changes the returned result.
  private int fallbackCount;

  BitStateMatcher(NFA nfa, String pattern) {
    super(pattern);
    this.nfa = nfa;
    this.groupCount = nfa.getGroupCount();
    this.stateCount = nfa.getStates().size();
    this.statesById = new NFA.NFAState[stateCount];
    for (NFA.NFAState s : nfa.getStates()) {
      statesById[s.id] = s;
    }
    this.isAccept = new boolean[stateCount];
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      isAccept[s.id] = true;
    }
    this.startStateId = nfa.getStartState().id;
    int slotCount = 2 * (groupCount + 1);
    this.caps = new int[slotCount];
    this.winCaptures = new int[slotCount];
    markNativeRichApi();
  }

  private PikeVMMatcher delegate() {
    PikeVMMatcher d = delegate;
    if (d == null) {
      d = new PikeVMMatcher(nfa, pattern);
      if (!nameToIndex.isEmpty()) {
        d.setNameToIndex(nameToIndex);
      }
      delegate = d;
    }
    return d;
  }

  @Override
  protected void setNameToIndex(Map<String, Integer> map) {
    super.setNameToIndex(map);
    if (delegate != null) {
      delegate.setNameToIndex(map);
    }
  }

  @Override
  boolean embedsNameMap() {
    return true;
  }

  private boolean overBudget(int span) {
    boolean over = (long) stateCount * (span + 1) > BUDGET_CELLS;
    if (over) fallbackCount++;
    return over;
  }

  /** Number of calls delegated to {@link PikeVMMatcher} due to exceeding {@link #BUDGET_CELLS}. */
  int fallbackCount() {
    return fallbackCount;
  }

  @Override
  public boolean matches(String input) {
    int len = input.length();
    if (overBudget(len)) return delegate().matches(input);
    beginSearchPass(0, len);
    Arrays.fill(caps, -1);
    caps[0] = 0;
    return search(input, 0, len, len);
  }

  @Override
  public boolean find(String input) {
    Objects.requireNonNull(input, "input");
    return findFrom(input, 0) >= 0;
  }

  @Override
  public int findFrom(String input, int start) {
    int clamped = Math.max(0, start);
    int len = input.length();
    if (clamped > len) return -1;
    int span = len - clamped;
    if (overBudget(span)) return delegate().findFrom(input, start);
    beginSearchPass(clamped, span);
    for (int seed = clamped; seed <= len; seed++) {
      Arrays.fill(caps, -1);
      caps[0] = seed;
      if (search(input, seed, len, -1)) {
        return winCaptures[0];
      }
    }
    return -1;
  }

  @Override
  public MatchResult match(String input) {
    int len = input.length();
    if (overBudget(len)) return delegate().match(input);
    beginSearchPass(0, len);
    Arrays.fill(caps, -1);
    caps[0] = 0;
    if (search(input, 0, len, len)) {
      return PikeVMMatcher.buildCaptureResult(input, winCaptures, groupCount, nameToIndex);
    }
    return null;
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int clamped = Math.max(0, start);
    int len = input.length();
    if (clamped > len) return null;
    int span = len - clamped;
    if (overBudget(span)) return delegate().findMatchFrom(input, start);
    beginSearchPass(clamped, span);
    for (int seed = clamped; seed <= len; seed++) {
      Arrays.fill(caps, -1);
      caps[0] = seed;
      if (search(input, seed, len, -1)) {
        return PikeVMMatcher.buildCaptureResult(input, winCaptures, groupCount, nameToIndex);
      }
    }
    return null;
  }

  // -------------------------------------------------------------------------
  // Core DFS: EXPAND/RESTORE job stack over a single (stateId, pos) visited set.
  // -------------------------------------------------------------------------

  /**
   * Anchored search from {@code (startStateId, scanPos)}. Accepts at the first {@code isAccept}
   * state reached in DFS priority order — leftmost-first — subject to {@code requireEndPos} ({@code
   * >= 0} forces the accept to land exactly there, used by {@code matches()}/{@code match()};
   * {@code -1} accepts anywhere, used by the unanchored find/findMatch pass).
   *
   * <p>Assumes the caller has already reset {@code caps[0]} to {@code scanPos} and every other slot
   * to {@code -1}, and has sized/positioned the shared visited set via {@link #beginSearchPass}.
   * The visited set is intentionally NOT reset here — see {@link #findFrom} / {@link
   * #findMatchFrom}, which run this once per candidate start while sharing one visited set across
   * the whole unanchored pass (design §4.1).
   */
  private boolean search(String input, int scanPos, int spanEnd, int requireEndPos) {
    sp = 0;
    push(EXPAND, startStateId, scanPos);
    while (sp > 0) {
      sp--;
      if (stackKind[sp] == RESTORE) {
        caps[stackA[sp]] = stackB[sp];
        continue;
      }
      int sid = stackA[sp];
      int pos = stackB[sp];
      if (isVisited(sid, pos)) continue;
      markVisited(sid, pos);
      NFA.NFAState s = statesById[sid];

      if (s.anchor != null) {
        // Anchor origin pinned to absolute 0, matching PikeVM's find semantics (design §3.4).
        if (!PikeVMMatcher.checkAnchor(s.anchor, input, pos, 0, spanEnd)) continue;
        pushEpsilonChildrenReversed(s, pos);
        continue;
      }

      // RESTORE frames are pushed before this state's children so they pop after the whole
      // subtree below — undoing the write exactly when control leaves this branch.
      if (s.enterGroup != null) {
        int slot = 2 * s.enterGroup;
        push(RESTORE, slot, caps[slot]);
        caps[slot] = pos;
      }
      if (s.exitGroup != null) {
        int slot = 2 * s.exitGroup + 1;
        push(RESTORE, slot, caps[slot]);
        caps[slot] = pos;
      }

      if (isAccept[sid] && (requireEndPos < 0 || pos == requireEndPos)) {
        caps[1] = pos; // whole-match end; caps[0] was seeded by the caller
        System.arraycopy(caps, 0, winCaptures, 0, caps.length);
        return true;
      }

      List<NFA.NFAState> eps = s.getEpsilonTransitions();
      if (!eps.isEmpty()) {
        pushEpsilonChildrenReversed(s, pos);
        continue;
      }

      if (pos < spanEnd) {
        char c = input.charAt(pos);
        List<NFA.Transition> trs = s.getTransitions();
        for (int i = trs.size() - 1; i >= 0; i--) {
          NFA.Transition tr = trs.get(i);
          if (tr.chars.contains(c)) {
            push(EXPAND, tr.target.id, pos + 1);
          }
        }
      }
    }
    return false;
  }

  private void pushEpsilonChildrenReversed(NFA.NFAState s, int pos) {
    List<NFA.NFAState> eps = s.getEpsilonTransitions();
    for (int i = eps.size() - 1; i >= 0; i--) {
      push(EXPAND, eps.get(i).id, pos);
    }
  }

  private void push(byte kind, int a, int b) {
    if (sp == stackKind.length) {
      int newCap = stackKind.length * 2;
      stackKind = Arrays.copyOf(stackKind, newCap);
      stackA = Arrays.copyOf(stackA, newCap);
      stackB = Arrays.copyOf(stackB, newCap);
    }
    stackKind[sp] = kind;
    stackA[sp] = a;
    stackB[sp] = b;
    sp++;
  }

  // -------------------------------------------------------------------------
  // Visited set — one per top-level call, shared across every seed of an unanchored pass.
  // -------------------------------------------------------------------------

  private void beginSearchPass(int scanStart, int span) {
    int width = span + 1;
    int needed = stateCount * width;
    if (visited == null || visited.length < needed) {
      visited = new boolean[needed];
    } else {
      Arrays.fill(visited, 0, needed, false);
    }
    visitedScanStart = scanStart;
    visitedWidth = width;
  }

  private boolean isVisited(int sid, int pos) {
    return visited[sid * visitedWidth + (pos - visitedScanStart)];
  }

  private void markVisited(int sid, int pos) {
    visited[sid * visitedWidth + (pos - visitedScanStart)] = true;
  }
}
