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
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Impl-plan Task 1.3 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): eligibility gate for
 * {@link LaurikariDfaMatcher}, deciding whether an NFA is safe to route through the Laurikari
 * tagged-DFA capture engine rather than {@link PikeVMMatcher}'s thread simulation.
 *
 * <p><b>Scope note (deliberate narrowing, not an oversight):</b> anchor eligibility here mirrors
 * {@code PikeVMMatcher.findDfaEligible} exactly — only {@code START}/{@code STRING_START}/{@code
 * START_MULTILINE} are handleable, everything else ({@code \b}, {@code $}, {@code \Z}, {@code \z},
 * {@code (?m)$}) is rejected. Design doc §5 sketches a fuller per-step/per-accept {@code regionEnd}
 * check for end anchors and {@code \b}; that is deferred to a follow-up. This narrowing is strictly
 * conservative — it only shrinks the eligible set, never admits a pattern that wasn't already safe
 * — so patterns like {@code SQL_ANSI} (which uses {@code \b} and {@code $}) simply stay on {@code
 * PikeVMMatcher} exactly as they do today.
 */
final class LaurikariEligibility {

  private LaurikariEligibility() {}

  /**
   * @param nfa the pattern's NFA
   * @param usePosixLastMatch {@code PatternAnalyzer.MatchingStrategyResult.usePosixLastMatch} —
   *     must be checked explicitly (design §5: neither the backref/lookaround/atomic-group checks
   *     below nor the anchor check cover it). Excludes capture-ambiguous patterns like {@code
   *     (a|a)+}, whose correct group spans require POSIX-last-match semantics, not the
   *     leftmost-first priority this engine replicates.
   * @return {@code true} if {@code nfa} is safe to route through {@link LaurikariDfaMatcher}
   */
  static boolean isEligible(NFA nfa, boolean usePosixLastMatch) {
    if (usePosixLastMatch) return false;

    boolean hasStartAnchor = false;
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.assertionType != null || s.backrefCheck != null) return false;
      if (s.atomicEntry >= 0 || s.atomicExit >= 0) return false; // atomic groups not DFA-safe
      // Handleable anchors: START (^), STRING_START (\A), START_MULTILINE ((?m)^).
      // Everything else (\b, $, end-class) needs char/end context this engine doesn't supply yet.
      NFA.AnchorType a = s.anchor;
      if (a != null
          && a != NFA.AnchorType.START
          && a != NFA.AnchorType.STRING_START
          && a != NFA.AnchorType.START_MULTILINE) return false;
      if (a != null) hasStartAnchor = true;
    }
    if (!hasStartAnchor) return true; // anchor-free: always eligible

    // Anchored: sound only when every start anchor is leading — i.e. NOT reachable after
    // consuming a character (an anchor inside a loop can fire across empty iterations, which the
    // subset/register model here cannot represent) — same check as PikeVMMatcher.findDfaEligible.
    Set<Integer> reached = new HashSet<>();
    ArrayDeque<NFA.NFAState> q = new ArrayDeque<>();
    for (NFA.NFAState s : nfa.getStates()) {
      for (NFA.Transition t : s.getTransitions()) {
        if (reached.add(t.target.id)) q.add(t.target);
      }
    }
    while (!q.isEmpty()) {
      NFA.NFAState s = q.poll();
      NFA.AnchorType a = s.anchor;
      if (a == NFA.AnchorType.START
          || a == NFA.AnchorType.STRING_START
          || a == NFA.AnchorType.START_MULTILINE) {
        return false; // start anchor reachable after a consume -> not leading-only
      }
      for (NFA.NFAState e : s.getEpsilonTransitions()) {
        if (reached.add(e.id)) q.add(e);
      }
    }
    return true;
  }
}
