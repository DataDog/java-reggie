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
import java.util.Collections;

/**
 * Impl-plan Task 1.4b (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): standalone capture
 * matcher over a {@link LaurikariDFACache}-cached tagged DFA, an O(n) alternative to {@link
 * PikeVMMatcher}'s per-thread-per-character capture-array copying for {@link
 * LaurikariEligibility}-eligible patterns.
 *
 * <p>Two independent {@link LaurikariDFACache} instances share one {@link LaurikariCaptureNfaStep}:
 * one driven by {@link LaurikariCaptureNfaStep#apply} for anchored calls ({@link #matches}/{@link
 * #match}), one driven by {@code applyFind} for self-anchoring calls ({@link #find}/{@link
 * #findFrom}/{@link #findMatch}/{@link #findMatchFrom}) — mirroring how {@code PikeVMMatcher} keeps
 * separate anchored/self-anchoring thread-list drivers over the same NFA.
 *
 * <p>This class does not itself check {@link LaurikariEligibility#isEligible} — same division of
 * responsibility as {@link BitStateMatcher}, which assumes its caller already gated eligibility.
 *
 * <p>On {@link LaurikariDFACache#FALLBACK} (cache-cap overflow, {@code cap} = 4096 states), the
 * entire call is delegated to a lazily-built {@link PikeVMMatcher} rather than continuing the scan
 * from the frozen position — a deliberate scope-narrowing (see the approved plan): correctness-
 * equivalent, simpler than replicating {@link LaurikariDFACache}'s finer-grained tail-continuation
 * for a general N-tag register vector, at the cost of re-doing the whole call on this expected-rare
 * path.
 */
final class LaurikariDfaMatcher extends ReggieMatcher {

  private final NFA nfa;
  private final String patternText;
  private final int groupCount;
  private final LaurikariCaptureNfaStep step;
  private final LaurikariDFACache anchoredCache;
  private final LaurikariDFACache findCache;

  private PikeVMMatcher fallback;
  private long fallbackCount;

  LaurikariDfaMatcher(NFA nfa, String pattern, int groupCount) {
    super(pattern);
    this.nfa = nfa;
    this.patternText = pattern;
    this.groupCount = groupCount;
    this.step = new LaurikariCaptureNfaStep(nfa, groupCount);
    LaurikariStepResult initial = LaurikariCaptureNfaStep.initial(nfa, step);
    int[] acceptIds = acceptStateIds(nfa);
    this.anchoredCache = new LaurikariDFACache(initial.states, initial.regs, acceptIds);
    this.findCache = new LaurikariDFACache(initial.states, initial.regs, acceptIds);
    markNativeRichApi();
  }

  private static int[] acceptStateIds(NFA nfa) {
    int[] ids = new int[nfa.getAcceptStates().size()];
    int i = 0;
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      ids[i++] = s.id;
    }
    return ids;
  }

  /** Number of calls delegated to the {@link PikeVMMatcher} fallback (cache-cap overflow). */
  long fallbackCount() {
    return fallbackCount;
  }

  private PikeVMMatcher fallback() {
    if (fallback == null) {
      fallback = new PikeVMMatcher(nfa, patternText);
    }
    return fallback;
  }

  @Override
  boolean embedsNameMap() {
    return true;
  }

  // -------------------------------------------------------------------------
  // Anchored (matches/match): driven by LaurikariCaptureNfaStep.apply
  // -------------------------------------------------------------------------

  @Override
  public boolean matches(String input) {
    return runAnchored(input) != null;
  }

  @Override
  public MatchResult match(String input) {
    int[] absolute = runAnchored(input);
    if (absolute == null) return null;
    return buildResult(input, absolute);
  }

  /**
   * @return absolute tag positions for a whole-input match, or {@code null} if the entire input
   *     does not match, or the fallback sentinel {@code new int[0]}'s caller-visible equivalent
   *     (handled internally — this method never returns without a definitive answer).
   */
  private int[] runAnchored(String input) {
    int len = input.length();
    int dfaState = 0;
    for (int i = 0; i < len; i++) {
      int next = anchoredCache.step(dfaState, input.charAt(i), step);
      if (next == LaurikariDFACache.DEAD) return null;
      if (next == LaurikariDFACache.FALLBACK) {
        fallbackCount++;
        return fallbackAnchored(input);
      }
      dfaState = next;
    }
    if (!anchoredCache.accepting[dfaState]) return null;
    int[] acceptRegs = anchoredCache.acceptRegs(dfaState);
    return step.absolutePositions(acceptRegs, len);
  }

  private int[] fallbackAnchored(String input) {
    MatchResult r = fallback().match(input);
    if (r == null) return null;
    return toAbsolute(r);
  }

  // -------------------------------------------------------------------------
  // Self-anchoring (find/findFrom/findMatch/findMatchFrom): driven by applyFind
  // -------------------------------------------------------------------------

  @Override
  public boolean find(String input) {
    return findFrom(input, 0) >= 0;
  }

  @Override
  public int findFrom(String input, int start) {
    int[] absolute = runFind(input, start);
    return absolute == null ? -1 : absolute[0];
  }

  @Override
  public MatchResult findMatch(String input) {
    return findMatchFrom(input, 0);
  }

  @Override
  public MatchResult findMatchFrom(String input, int start) {
    int[] absolute = runFind(input, start);
    if (absolute == null) return null;
    return buildResult(input, absolute);
  }

  /**
   * Self-anchoring scan of {@code input} from {@code start}: at every position, {@code findCache}
   * (driven by {@code applyFind}) already carries every live start candidate at once — including
   * one freshly reinjected at this very position — so a single left-to-right pass, with no
   * restart-on-DEAD bookkeeping, finds the leftmost completable match.
   *
   * <p>Reaching an accepting state does not end the scan: {@code LaurikariCaptureNfaStep}'s
   * priority-kill truncation already dropped every candidate strictly lower-priority than the
   * accept just observed, but a strictly higher-priority candidate (e.g. a greedy loop's "keep
   * going" branch) may still be alive and could still reach its own, preferred accept later —
   * mirroring {@code PikeVMMatcher}'s "last write wins: each surviving override comes from a
   * strictly higher-priority thread" comment. So: keep scanning while the subset is non-empty,
   * remembering the most recent accept snapshot, and only return once every candidate has died or
   * the input ends.
   *
   * @return absolute tag positions for the leftmost match at or after {@code start}, or {@code
   *     null} if none exists
   */
  private int[] runFind(String input, int start) {
    int clamped = Math.max(0, start);
    int len = input.length();
    if (clamped > len) return null;
    int dfaState = 0;
    int[] best = null;
    if (findCache.accepting[dfaState]) {
      // Zero-length match at the very start of the scan: no characters consumed yet.
      best = shiftByClamped(step.absolutePositions(findCache.acceptRegs(dfaState), 0), clamped);
    }
    for (int i = clamped; i < len; i++) {
      int next = findCache.step(dfaState, input.charAt(i), step);
      if (next == LaurikariDFACache.FALLBACK) {
        fallbackCount++;
        return best == null ? fallbackFind(input, i) : best;
      }
      if (next == LaurikariDFACache.DEAD) {
        if (best != null) return best;
        // No candidate has matched yet: a fresh one is reinjected at the very next character (see
        // LaurikariCaptureNfaStep's applyFind), so resume scanning from a clean state.
        dfaState = 0;
        continue;
      }
      dfaState = next;
      if (findCache.accepting[dfaState]) {
        // Ages are relative to this scan's own step count (i - clamped + 1 apply() calls so far),
        // not the input's absolute indexing -- shift back to absolute positions afterward.
        int[] acceptRegs = findCache.acceptRegs(dfaState);
        best = shiftByClamped(step.absolutePositions(acceptRegs, i + 1 - clamped), clamped);
      }
    }
    return best;
  }

  /** Adds {@code clamped} to every set ({@code >= 0}) entry of {@code relative}, in place. */
  private static int[] shiftByClamped(int[] relative, int clamped) {
    if (clamped != 0) {
      for (int i = 0; i < relative.length; i++) {
        if (relative[i] >= 0) relative[i] += clamped;
      }
    }
    return relative;
  }

  private int[] fallbackFind(String input, int frozenPos) {
    MatchResult r = fallback().findMatchFrom(input, frozenPos);
    if (r == null) return null;
    return toAbsolute(r);
  }

  // -------------------------------------------------------------------------
  // Result construction
  // -------------------------------------------------------------------------

  private int[] toAbsolute(MatchResult r) {
    int[] absolute = new int[2 * (groupCount + 1)];
    for (int g = 0; g <= groupCount; g++) {
      absolute[2 * g] = r.start(g);
      absolute[2 * g + 1] = r.end(g);
    }
    return absolute;
  }

  private MatchResult buildResult(String input, int[] absolute) {
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    for (int g = 0; g <= groupCount; g++) {
      starts[g] = absolute[2 * g];
      ends[g] = absolute[2 * g + 1];
    }
    if (!nameToIndex.isEmpty()) {
      return new NamedMatchResultImpl(input, starts, ends, groupCount, nameToIndex);
    }
    return new MatchResultImpl(input, starts, ends, groupCount, Collections.emptyMap());
  }
}
