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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Bounded-backtracking capture engine over a Thompson {@link NFA}: a linear-time, ReDoS-safe DFS
 * (depth-first search) backtracker used as a faster alternative to {@link PikeVMMatcher} for
 * ambiguous capturing patterns whose {@code stateCount × (spanLength + 1)} fits a fixed budget.
 *
 * <p>See {@code doc/2026-07-03-bitstate-capture-engine-design.md} for the algorithm design. In
 * short: a DFS explores the NFA's epsilon closure in Perl priority order (identical child ordering
 * to {@link PikeVMMatcher#getEpsilonTransitions()} traversal), so the first accept state reached is
 * the leftmost-greedy match. A {@code (stateId, pos)} visited set bounds total expansions to {@code
 * stateCount × (span + 1)}, giving linear time and immunity to catastrophic backtracking. A single
 * mutable {@code caps} array (rather than one array per in-flight thread) tracks capture-group
 * slots, with writes undone via {@code RESTORE} jobs interleaved into the same explicit job stack
 * as the {@code EXPAND} (state, pos) jobs — this avoids the per-thread capture-array copies that
 * dominate {@link PikeVMMatcher}'s cost for these patterns.
 *
 * <p>Unanchored search ({@link #find}, {@link #findFrom}, {@link #findMatch}, {@link
 * #findMatchFrom}) is implemented as a <em>single</em> left-to-right DFS pass: the start state is
 * re-seeded at the lowest priority at every position (an implicit {@code .*?} prefix), sharing one
 * visited bitmap across all seeds. This is the RE2/PikeVM "single pass" technique, not the O(n²)
 * anti-pattern of restarting a fresh search at every candidate start position.
 *
 * <p>When the per-search budget ({@code stateCount × (spanLength + 1)}) exceeds {@link
 * #BUDGET_CELLS}, the whole call is delegated to a lazily-constructed {@link PikeVMMatcher} over
 * the same NFA, and {@link #fallbackCount()} is incremented — the budget overflow is observable,
 * never silently truncated.
 *
 * <p>v1 scope (see design doc §10, "P1"): no atomic groups, possessive quantifiers, backreferences,
 * or lookaround — patterns using those constructs must not be routed to this matcher (an
 * eligibility gate elsewhere is responsible for that; this class does not itself defend against
 * them).
 */
final class BitStateMatcher extends ReggieMatcher {

  // RE2's reference visited-set bound: 256K (stateId, pos) cells. Keeps the per-search bitmap
  // small (~1 MB for a generation-stamped int[]) while covering the vast majority of real-world
  // capturing patterns (small state counts, short-to-medium inputs). Larger searches fall back to
  // PikeVMMatcher (see class doc).
  private static final int BUDGET_CELLS = 1 << 18;

  // Sentinel tags stored in stackA to distinguish job-stack frame kinds (see class doc, §3.1 of
  // the design doc). EXPAND frames use stackA >= 0 (the NFA state id).
  private static final int SEED_TAG = -1;
  private static final int RESTORE_TAG = -2;

  private final NFA nfa;
  private final String patternText;
  private final int groupCount;
  private final int stateCount;
  private final int startStateId;
  private final NFA.NFAState[] statesById;
  private final boolean[] isAccept;

  // Flattened per-state transition tables, built once here so the hot DFS loop never calls
  // NFAState.getEpsilonTransitions()/getTransitions() (each allocates a fresh
  // Collections.unmodifiableList wrapper per call). Indexed by state id, in the same Perl
  // priority order as the underlying NFAState lists.
  private final int[][] epsilonTargets;
  private final CharSet[][] transitionCharSets;
  private final int[][] transitionTargets;

  // Input-independent structures, preallocated once (design §5): live capture slots and the
  // winning snapshot. Sized 2 * (groupCount + 1): slot 2*g is group g's start, 2*g+1 its end.
  private final int[] caps;
  private final int[] winCaptures;

  // Explicit job stack shared by EXPAND(stateId, pos), SEED(pos), and RESTORE(slot, val) frames
  // (design §3.1/§3.4). Grows lazily per search (like the visited bitmap below); typical patterns
  // never grow past their initial, stateCount-derived capacity.
  private int[] stackA;
  private int[] stackB;
  private int[] stackC;
  private int stackTop;

  // Visited (stateId, pos) set (design §3.2), generation-stamped so it never needs an O(cells)
  // clear between searches — only a per-search counter bump. Grown lazily up to BUDGET_CELLS;
  // callers never invoke search() with a cell count that would exceed the budget (see
  // exceedsBudget()), so no cell is ever falsely treated as visited due to truncation.
  private int[] visited = new int[0];
  private int visitedGeneration;

  // Lazily-constructed PikeVM fallback used when a search would exceed BUDGET_CELLS (design §8).
  private PikeVMMatcher fallback;
  private long fallbackCount;

  BitStateMatcher(NFA nfa, String pattern) {
    super(pattern);
    this.nfa = nfa;
    this.patternText = pattern;
    this.groupCount = nfa.getGroupCount();
    this.stateCount = nfa.getStates().size();
    this.startStateId = nfa.getStartState().id;

    statesById = new NFA.NFAState[stateCount];
    for (NFA.NFAState s : nfa.getStates()) {
      statesById[s.id] = s;
    }
    isAccept = new boolean[stateCount];
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      isAccept[s.id] = true;
    }

    epsilonTargets = new int[stateCount][];
    transitionCharSets = new CharSet[stateCount][];
    transitionTargets = new int[stateCount][];
    for (int i = 0; i < stateCount; i++) {
      NFA.NFAState s = statesById[i];

      List<NFA.NFAState> eps = s.getEpsilonTransitions();
      int[] epsIds = new int[eps.size()];
      for (int j = 0; j < epsIds.length; j++) {
        epsIds[j] = eps.get(j).id;
      }
      epsilonTargets[i] = epsIds;

      List<NFA.Transition> trans = s.getTransitions();
      CharSet[] charSets = new CharSet[trans.size()];
      int[] targets = new int[trans.size()];
      for (int j = 0; j < charSets.length; j++) {
        NFA.Transition t = trans.get(j);
        charSets[j] = t.chars;
        targets[j] = t.target.id;
      }
      transitionCharSets[i] = charSets;
      transitionTargets[i] = targets;
    }

    int slotCount = 2 * (groupCount + 1);
    caps = new int[slotCount];
    winCaptures = new int[slotCount];

    int initialStackCap = Math.max(64, 4 * stateCount);
    stackA = new int[initialStackCap];
    stackB = new int[initialStackCap];
    stackC = new int[initialStackCap];

    markNativeRichApi();
  }

  // -------------------------------------------------------------------------
  // ReggieMatcher public API
  // -------------------------------------------------------------------------

  @Override
  public boolean matches(String input) {
    if (exceedsBudget(input.length())) {
      fallbackCount++;
      return fallback().matches(input);
    }
    return search(input, 0, input.length(), false, true);
  }

  @Override
  public boolean find(String input) {
    if (input == null) throw new NullPointerException("input");
    if (exceedsBudget(input.length())) {
      fallbackCount++;
      return fallback().find(input);
    }
    return search(input, 0, input.length(), true, false);
  }

  @Override
  public int findFrom(String input, int start) {
    int clamped = Math.max(0, start);
    if (clamped > input.length()) return -1;
    if (exceedsBudget(input.length() - clamped)) {
      fallbackCount++;
      return fallback().findFrom(input, start);
    }
    boolean won = search(input, clamped, input.length(), true, false);
    return won ? winCaptures[0] : -1;
  }

  @Override
  public MatchResult match(String input) {
    if (exceedsBudget(input.length())) {
      fallbackCount++;
      return fallback().match(input);
    }
    boolean won = search(input, 0, input.length(), false, true);
    return won ? buildCaptureResult(input, winCaptures, groupCount) : null;
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int clamped = Math.max(0, start);
    if (clamped > input.length()) return null;
    if (exceedsBudget(input.length() - clamped)) {
      fallbackCount++;
      return fallback().findMatchFrom(input, start);
    }
    boolean won = search(input, clamped, input.length(), true, false);
    return won ? buildCaptureResult(input, winCaptures, groupCount) : null;
  }

  @Override
  public boolean matchesBounded(CharSequence input, int start, int end) {
    if (exceedsBudget(end - start)) {
      fallbackCount++;
      return fallback().matchesBounded(input, start, end);
    }
    String region = input.subSequence(start, end).toString();
    return search(region, 0, region.length(), false, true);
  }

  @Override
  public MatchResult matchBounded(CharSequence input, int start, int end) {
    if (exceedsBudget(end - start)) {
      fallbackCount++;
      return fallback().matchBounded(input, start, end);
    }
    String region = input.subSequence(start, end).toString();
    boolean won = search(region, 0, region.length(), false, true);
    if (!won) return null;
    MatchResult r = buildCaptureResult(region, winCaptures, groupCount);
    if (start == 0) return r;
    return shiftResult(r, start, input.toString());
  }

  // findAll / findMatchInto are inherited from ReggieMatcher's default implementations, built on
  // findMatchFrom, matching PikeVMMatcher (which does not override them either).

  // embedsNameMap() == true tells RuntimeCompiler this matcher's MatchResult returns already
  // resolve group names via nameToIndex (through buildCaptureResult), so it must not wrap this
  // matcher in a NameEnrichingMatcher — doing so would double-apply the name map.
  @Override
  boolean embedsNameMap() {
    return true;
  }

  /** Number of calls delegated to the {@link PikeVMMatcher} fallback (design §5, §8). */
  long fallbackCount() {
    return fallbackCount;
  }

  private boolean exceedsBudget(int spanLen) {
    return (long) stateCount * (spanLen + 1) > BUDGET_CELLS;
  }

  private PikeVMMatcher fallback() {
    if (fallback == null) {
      fallback = new PikeVMMatcher(nfa, patternText);
    }
    return fallback;
  }

  // -------------------------------------------------------------------------
  // Core interpreter (design §3)
  // -------------------------------------------------------------------------

  /**
   * Runs one DFS search over {@code input[scanStart, spanEnd)}.
   *
   * <p>When {@code unanchored} is true, this is the single-pass unanchored scan (design §4.1): the
   * start state is re-seeded at the lowest priority at every position from {@code scanStart} to
   * {@code spanEnd}, sharing one visited bitmap, so the whole scan stays O(stateCount × span) — not
   * a per-start restart. When false, only {@code scanStart} itself is tried (anchored).
   *
   * <p>{@code requireFullMatch} implements {@code matches()}/{@code match()} semantics: an accept
   * state reached before {@code spanEnd} does not end the search (mirrors {@code
   * PikeVMMatcher.runMatches} only accepting when {@code pos == regionEnd}); the DFS simply
   * backtracks and keeps exploring other continuations.
   *
   * <p>On success, {@link #winCaptures} holds the winning capture snapshot (slot 0/1 = the whole
   * match span) and this method returns {@code true}.
   */
  private boolean search(
      String input, int scanStart, int spanEnd, boolean unanchored, boolean requireFullMatch) {
    int spanLen = spanEnd - scanStart;
    ensureVisitedCapacity((long) stateCount * (spanLen + 1));
    visitedGeneration++;
    if (visitedGeneration == Integer.MAX_VALUE) {
      Arrays.fill(visited, 0);
      visitedGeneration = 1;
    }
    Arrays.fill(caps, -1);
    stackTop = 0;

    if (unanchored) {
      pushSeed(scanStart);
    } else {
      pushExpandSeeded(startStateId, scanStart);
    }

    while (stackTop > 0) {
      stackTop--;
      int a = stackA[stackTop];
      int b = stackB[stackTop];
      int c = stackC[stackTop];

      if (a == RESTORE_TAG) {
        caps[b] = c;
        continue;
      }
      if (a == SEED_TAG) {
        int pos = b;
        if (pos <= spanEnd) {
          // Push the next seed FIRST (lowest priority — pops last), then this seed's start-state
          // EXPAND on top (highest priority among what remains — pops first). This realizes the
          // "lowest-priority self-loop" implicit .*? prefix (design §4.1) without JVM recursion.
          pushSeed(pos + 1);
          pushExpandSeeded(startStateId, pos);
        }
        continue;
      }

      int sid = a;
      int pos = b;
      // c == 1 marks this EXPAND job as a genuine seed (a new match attempt starting at `pos`,
      // pushed by pushExpandSeeded), as opposed to merely landing on the start state again via a
      // quantifier loop-back (e.g. `(.)+`'s NFA can revisit the literal start-state id at a later
      // pos without that being a new match attempt). Only seed jobs write group-0's start slot.
      boolean isSeed = c != 0;
      int rel = pos - scanStart;
      int idx = sid * (spanLen + 1) + rel;
      if (visited[idx] == visitedGeneration) continue;
      visited[idx] = visitedGeneration;

      if (isSeed) {
        pushRestore(0, caps[0]);
        caps[0] = pos;
      }

      NFA.NFAState s = statesById[sid];

      if (s.anchor != null) {
        // Anchor origin is pinned to absolute 0 (regionStart argument), matching PikeVMMatcher's
        // find/findFrom semantics: ^/\A never fire at a findFrom(start>0) offset, only at true
        // input start.
        if (!PikeVMMatcher.checkAnchor(s.anchor, input, pos, 0, spanEnd)) continue;
        pushEpsilonChildrenReversed(sid, pos);
        continue;
      }

      // Group-entry/-exit capture writes, with undo (design §3.3). Pushed before this state's
      // children so the RESTORE pops only after the whole subtree below this state is done.
      if (s.enterGroup != null) {
        int slot = 2 * s.enterGroup;
        pushRestore(slot, caps[slot]);
        caps[slot] = pos;
      }
      if (s.exitGroup != null) {
        int slot = 2 * s.exitGroup + 1;
        pushRestore(slot, caps[slot]);
        caps[slot] = pos;
      }

      if (isAccept[sid] && (!requireFullMatch || pos == spanEnd)) {
        System.arraycopy(caps, 0, winCaptures, 0, winCaptures.length);
        winCaptures[1] = pos;
        return true;
      }

      int[] eps = epsilonTargets[sid];
      if (eps.length != 0) {
        pushEpsilonChildrenReversed(sid, pos);
        continue;
      }

      // Consuming leaf.
      if (pos < spanEnd) {
        char ch = input.charAt(pos);
        CharSet[] charSets = transitionCharSets[sid];
        int[] targets = transitionTargets[sid];
        for (int i = charSets.length - 1; i >= 0; i--) {
          if (charSets[i].contains(ch)) {
            pushExpand(targets[i], pos + 1);
          }
        }
      }
    }
    return false;
  }

  /** Pushes {@code sid}'s epsilon children in reverse priority order (highest pops first). */
  private void pushEpsilonChildrenReversed(int sid, int pos) {
    int[] eps = epsilonTargets[sid];
    for (int i = eps.length - 1; i >= 0; i--) {
      pushExpand(eps[i], pos);
    }
  }

  // -------------------------------------------------------------------------
  // Job stack (design §3.1)
  // -------------------------------------------------------------------------

  private void pushExpand(int stateId, int pos) {
    ensureStackCapacity();
    stackA[stackTop] = stateId;
    stackB[stackTop] = pos;
    stackC[stackTop] = 0;
    stackTop++;
  }

  /**
   * Pushes an EXPAND(startStateId, pos) job tagged as a genuine new-match-attempt seed (see the
   * {@code isSeed} comment in {@link #search}), distinguishing it from an ordinary epsilon/
   * consuming push that happens to land on the start state's id again via a loop-back.
   */
  private void pushExpandSeeded(int stateId, int pos) {
    ensureStackCapacity();
    stackA[stackTop] = stateId;
    stackB[stackTop] = pos;
    stackC[stackTop] = 1;
    stackTop++;
  }

  private void pushSeed(int pos) {
    ensureStackCapacity();
    stackA[stackTop] = SEED_TAG;
    stackB[stackTop] = pos;
    stackTop++;
  }

  private void pushRestore(int slot, int val) {
    ensureStackCapacity();
    stackA[stackTop] = RESTORE_TAG;
    stackB[stackTop] = slot;
    stackC[stackTop] = val;
    stackTop++;
  }

  private void ensureStackCapacity() {
    if (stackTop == stackA.length) {
      int newCap = stackA.length * 2;
      stackA = Arrays.copyOf(stackA, newCap);
      stackB = Arrays.copyOf(stackB, newCap);
      stackC = Arrays.copyOf(stackC, newCap);
    }
  }

  // -------------------------------------------------------------------------
  // Visited set (design §3.2)
  // -------------------------------------------------------------------------

  private void ensureVisitedCapacity(long cells) {
    if (visited.length < cells) {
      // Callers only invoke search() after exceedsBudget() has returned false, so cells is always
      // within BUDGET_CELLS here.
      visited = new int[(int) cells];
      visitedGeneration = 0;
    }
  }

  // -------------------------------------------------------------------------
  // Result construction
  // -------------------------------------------------------------------------

  /**
   * Builds a copy of {@code r} with every group span shifted by {@code delta}, anchored to {@code
   * originalInput} so {@link MatchResult#group(int)} returns substrings of the original input.
   * Mirrors {@code PikeVMMatcher.shiftResult}.
   */
  private MatchResult shiftResult(MatchResult r, int delta, String originalInput) {
    int gc = r.groupCount();
    int[] starts = new int[gc + 1];
    int[] ends = new int[gc + 1];
    for (int i = 0; i <= gc; i++) {
      int s = r.start(i);
      int e = r.end(i);
      starts[i] = s == -1 ? -1 : s + delta;
      ends[i] = e == -1 ? -1 : e + delta;
    }
    if (!nameToIndex.isEmpty()) {
      return new NamedMatchResultImpl(originalInput, starts, ends, gc, nameToIndex);
    }
    return new MatchResultImpl(originalInput, starts, ends, gc, Collections.emptyMap());
  }
}
