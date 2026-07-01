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
package com.datadoghq.reggie.codegen.automaton;

import com.datadoghq.reggie.codegen.automaton.NFA.NFAState;
import com.datadoghq.reggie.codegen.automaton.NFA.Transition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Epsilon-free Glushkov (position) automaton derived from a Thompson {@link NFA}, packed for
 * bit-parallel simulation in a single 64-bit word.
 *
 * <p>Each character-consuming NFA transition becomes one <em>position</em> (bit). All in-edges to a
 * position carry the same character set (the Glushkov property), so transitions reduce to: expand
 * the active set along {@link #follow}, then keep the positions whose entry mask contains the input
 * character. The construction only succeeds for capture-free, anchor-free, assertion-free,
 * backreference-free patterns with at most {@link #MAX_POSITIONS} positions; otherwise {@link
 * #from(NFA)} returns {@code null} and the caller falls back to another strategy.
 */
public final class GlushkovAutomaton {

  /** Maximum number of positions that fit the single-word active set (bits 0..62). */
  public static final int MAX_POSITIONS = 63;

  public final int positionCount;

  /** {@code true} iff the empty string is accepted (the start closure reaches an accept state). */
  public final boolean nullable;

  /** Bitmask of positions that may match the first character (Glushkov First set). */
  public final long initial;

  /** Bitmask of accepting positions (Glushkov Last set). */
  public final long accept;

  /** {@code follow[p]} = bitmask of positions that may follow position {@code p}. */
  public final long[] follow;

  /**
   * Transpose of {@link #follow}: {@code followReverse[q]} = positions {@code p} with q in
   * follow[p].
   */
  public final long[] followReverse;

  /** Character set matched by each position (in-edge label). */
  public final CharSet[] positionCharSets;

  /** Number of distinct character equivalence classes. */
  public final int classCount;

  /** ASCII fast path: {@code asciiClasses[ch]} = class id for {@code ch < 128}. */
  public final int[] asciiClasses;

  /** Sorted non-overlapping ranges covering 0..0xFFFF for non-ASCII classification. */
  public final char[] rangeStarts;

  public final char[] rangeEnds;
  public final int[] rangeClasses;

  /** {@code entry[cls]} = bitmask of positions whose character set contains class {@code cls}. */
  public final long[] entry;

  /**
   * {@code true} when every equivalence class activates at least one initial position, i.e. {@code
   * (initial & entry[c]) != 0} for all {@code c}. When true the leftmost match start is always
   * {@code from}, so the reverse scan in {@code findBoundsFrom} can be skipped — only a single
   * forward pass is needed. Typical for patterns with a {@code .*} prefix.
   */
  public final boolean startsAnywhere;

  private GlushkovAutomaton(
      int positionCount,
      boolean nullable,
      long initial,
      long accept,
      long[] follow,
      long[] followReverse,
      CharSet[] positionCharSets,
      int classCount,
      int[] asciiClasses,
      char[] rangeStarts,
      char[] rangeEnds,
      int[] rangeClasses,
      long[] entry) {
    this.positionCount = positionCount;
    this.nullable = nullable;
    this.initial = initial;
    this.accept = accept;
    this.follow = follow;
    this.followReverse = followReverse;
    this.positionCharSets = positionCharSets;
    this.classCount = classCount;
    this.asciiClasses = asciiClasses;
    this.rangeStarts = rangeStarts;
    this.rangeEnds = rangeEnds;
    this.rangeClasses = rangeClasses;
    this.entry = entry;
    boolean sa = true;
    for (long e : entry) {
      if ((initial & e) == 0L) {
        sa = false;
        break;
      }
    }
    this.startsAnywhere = sa;
  }

  /**
   * Returns the single ASCII character (0–127) that must appear at the accepting position of every
   * match, or {@code -1} if none can be identified.
   *
   * <p>When the Last (accept) set has exactly one position {@code p} and exactly one ASCII
   * character activates {@code p} through {@code entry[asciiClasses[c]] >> p & 1 != 0}, that
   * character is required at every match end. The caller can use {@link String#indexOf(int, int)}
   * to skip non-candidate regions in {@code find()}.
   *
   * <p>Returns {@code -1} when: the Last set has more than one position; more than one ASCII
   * character activates the sole accepting position (class too wide); or the position is activated
   * only by non-ASCII characters.
   */
  public int findLastRequiredChar() {
    if (Long.bitCount(accept) != 1) return -1;
    int p = Long.numberOfTrailingZeros(accept);
    int found = -1;
    for (int c = 0; c < 128; c++) {
      int cls = asciiClasses[c];
      if (((entry[cls] >> p) & 1L) != 0L) {
        if (found != -1) return -1; // more than one ASCII char activates p
        found = c;
      }
    }
    return found;
  }

  /**
   * Builds the Glushkov automaton for {@code nfa}, or returns {@code null} if the pattern is
   * ineligible (capturing groups, anchors, assertions, backreferences, conditionals, no positions,
   * or more than {@link #MAX_POSITIONS} positions).
   */
  public static GlushkovAutomaton from(NFA nfa) {
    if (nfa == null || nfa.getGroupCount() > 0) {
      return null;
    }
    for (NFAState s : nfa.getStates()) {
      if (s.enterGroup != null
          || s.exitGroup != null
          || s.backrefCheck != null
          || s.anchor != null
          || s.assertionType != null
          || s.conditionalGroup != null) {
        return null;
      }
    }

    // Enumerate positions: one per character-consuming transition whose source is reachable from
    // the start state. The reachability filter drops orphan transitions left in the state list by
    // ThompsonBuilder's counted-quantifier construction (it builds and discards a probe fragment),
    // which would otherwise inflate the position count and the budget check.
    Set<NFAState> reachable = reachableFrom(nfa.getStartState());
    List<CharSet> charSets = new ArrayList<>();
    List<NFAState> sources = new ArrayList<>();
    List<NFAState> targets = new ArrayList<>();
    for (NFAState s : nfa.getStates()) {
      if (!reachable.contains(s)) {
        continue;
      }
      for (Transition t : s.getTransitions()) {
        charSets.add(t.chars);
        sources.add(s);
        targets.add(t.target);
      }
    }
    int m = charSets.size();
    if (m == 0 || m > MAX_POSITIONS) {
      return null;
    }

    // Memoize epsilon-closures by (dense) state id.
    int maxId = 0;
    for (NFAState s : nfa.getStates()) {
      maxId = Math.max(maxId, s.id);
    }
    @SuppressWarnings("unchecked")
    Set<NFAState>[] closure = new Set[maxId + 1];
    for (NFAState s : nfa.getStates()) {
      closure[s.id] = epsilonClosure(s);
    }

    Set<NFAState> startClosure = closure[nfa.getStartState().id];
    Set<NFAState> acceptStates = nfa.getAcceptStates();
    boolean nullable = intersects(startClosure, acceptStates);

    long initial = 0L;
    long accept = 0L;
    long[] follow = new long[m];
    for (int p = 0; p < m; p++) {
      if (startClosure.contains(sources.get(p))) {
        initial |= 1L << p;
      }
      if (intersects(closure[targets.get(p).id], acceptStates)) {
        accept |= 1L << p;
      }
    }
    for (int p = 0; p < m; p++) {
      Set<NFAState> tClosure = closure[targets.get(p).id];
      long f = 0L;
      for (int p2 = 0; p2 < m; p2++) {
        if (tClosure.contains(sources.get(p2))) {
          f |= 1L << p2;
        }
      }
      follow[p] = f;
    }
    long[] followReverse = new long[m];
    for (int p = 0; p < m; p++) {
      long f = follow[p];
      while (f != 0L) {
        int q = Long.numberOfTrailingZeros(f);
        f &= f - 1;
        followReverse[q] |= 1L << p;
      }
    }

    // Character equivalence classes: partition 0..0xFFFF so every class has a fixed entry mask.
    TreeSet<Integer> boundaries = new TreeSet<>();
    boundaries.add(0);
    boundaries.add(0x10000);
    for (CharSet cs : charSets) {
      for (CharSet.Range r : cs.getRanges()) {
        boundaries.add((int) r.start);
        if (r.end != Character.MAX_VALUE) {
          boundaries.add(((int) r.end) + 1);
        }
      }
    }
    List<Integer> points = new ArrayList<>(boundaries);
    Map<Long, Integer> classIds = new HashMap<>();
    List<Long> classMasks = new ArrayList<>();
    List<Character> rangeStarts = new ArrayList<>();
    List<Character> rangeEnds = new ArrayList<>();
    List<Integer> rangeClasses = new ArrayList<>();
    for (int i = 0; i < points.size() - 1; i++) {
      int start = points.get(i);
      int endExclusive = points.get(i + 1);
      if (start >= endExclusive) {
        continue;
      }
      char representative = (char) start;
      long mask = 0L;
      for (int p = 0; p < m; p++) {
        if (charSets.get(p).contains(representative)) {
          mask |= 1L << p;
        }
      }
      Integer classId = classIds.get(mask);
      if (classId == null) {
        classId = classMasks.size();
        classIds.put(mask, classId);
        classMasks.add(mask);
      }
      char rangeStart = (char) start;
      char rangeEnd = (char) (endExclusive - 1);
      int last = rangeClasses.size() - 1;
      if (last >= 0
          && rangeClasses.get(last).intValue() == classId.intValue()
          && ((int) rangeEnds.get(last)) + 1 == start) {
        rangeEnds.set(last, rangeEnd);
      } else {
        rangeStarts.add(rangeStart);
        rangeEnds.add(rangeEnd);
        rangeClasses.add(classId);
      }
    }

    int classCount = classMasks.size();
    long[] entry = new long[classCount];
    for (int c = 0; c < classCount; c++) {
      entry[c] = classMasks.get(c);
    }
    int[] asciiClasses = new int[128];
    for (int ch = 0; ch < 128; ch++) {
      asciiClasses[ch] = classFor((char) ch, rangeStarts, rangeEnds, rangeClasses);
    }

    return new GlushkovAutomaton(
        m,
        nullable,
        initial,
        accept,
        follow,
        followReverse,
        charSets.toArray(new CharSet[0]),
        classCount,
        asciiClasses,
        toCharArray(rangeStarts),
        toCharArray(rangeEnds),
        toIntArray(rangeClasses),
        entry);
  }

  private static Set<NFAState> reachableFrom(NFAState start) {
    Set<NFAState> seen = new HashSet<>();
    Deque<NFAState> stack = new ArrayDeque<>();
    seen.add(start);
    stack.push(start);
    while (!stack.isEmpty()) {
      NFAState s = stack.pop();
      for (NFAState e : s.getEpsilonTransitions()) {
        if (seen.add(e)) {
          stack.push(e);
        }
      }
      for (Transition t : s.getTransitions()) {
        if (seen.add(t.target)) {
          stack.push(t.target);
        }
      }
    }
    return seen;
  }

  private static Set<NFAState> epsilonClosure(NFAState start) {
    Set<NFAState> seen = new HashSet<>();
    Deque<NFAState> stack = new ArrayDeque<>();
    seen.add(start);
    stack.push(start);
    while (!stack.isEmpty()) {
      NFAState s = stack.pop();
      for (NFAState e : s.getEpsilonTransitions()) {
        if (seen.add(e)) {
          stack.push(e);
        }
      }
    }
    return seen;
  }

  private static boolean intersects(Set<NFAState> a, Set<NFAState> b) {
    Set<NFAState> small = a.size() <= b.size() ? a : b;
    Set<NFAState> large = small == a ? b : a;
    for (NFAState s : small) {
      if (large.contains(s)) {
        return true;
      }
    }
    return false;
  }

  private static int classFor(
      char ch, List<Character> starts, List<Character> ends, List<Integer> classes) {
    for (int i = 0; i < starts.size(); i++) {
      if (ch >= starts.get(i) && ch <= ends.get(i)) {
        return classes.get(i);
      }
    }
    return 0;
  }

  private static char[] toCharArray(List<Character> values) {
    char[] result = new char[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    return result;
  }

  private static int[] toIntArray(List<Integer> values) {
    int[] result = new int[values.size()];
    for (int i = 0; i < values.size(); i++) {
      result[i] = values.get(i);
    }
    return result;
  }
}
