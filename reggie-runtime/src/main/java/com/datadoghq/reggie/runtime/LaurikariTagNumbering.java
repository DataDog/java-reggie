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

/**
 * Impl-plan Task 1.1 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): derives the full {@code
 * 2 * (groupCount + 1)} tag numbering from an {@link NFA}, generalizing the hand-picked {@code
 * tagSpecs} tables {@code LaurikariPhase05Test} used for exactly 2-3 checkpoint patterns into an
 * automatic, pattern-independent mechanism driven directly by {@link NFA.NFAState#enterGroup}/
 * {@link NFA.NFAState#exitGroup}.
 *
 * <p><b>Slot convention</b> — matches {@code PikeVMMatcher.java}'s existing {@code slotCount = 2 *
 * (groupCount + 1)} layout exactly (see {@code PikeVMMatcher.updateCaptures}, which writes {@code
 * copy[2 * state.enterGroup] = pos} / {@code copy[2 * state.exitGroup + 1] = pos}): tag {@code 2*g}
 * is group {@code g}'s open, tag {@code 2*g+1} is group {@code g}'s close, for {@code g} in {@code
 * 0..groupCount} inclusive (group 0 is the implicit whole-match group).
 *
 * <p><b>Group 0 is not represented by any {@code enterGroup}/{@code exitGroup} NFA state</b> —
 * confirmed against {@code ThompsonBuilder.visitGroup}, which only ever assigns {@code
 * node.groupNumber >= 1} (group 0 is reserved for the whole match and is never wrapped in a
 * dedicated marker state). {@code PikeVMMatcher} itself handles group 0's two slots outside {@code
 * addThread}/{@code updateCaptures} entirely: slot 0 is seeded directly by the caller when a thread
 * is created (e.g. {@code initClist}'s {@code init[0] = pos}), and slot 1 is written directly by
 * the search loop the moment an accept state is reached (e.g. {@code caps[1] = pos} in {@code
 * runMatchResult}), never through a state's {@code enterGroup}/{@code exitGroup} field.
 *
 * <p>This class mirrors that split for tag numbering purposes: {@link #tagOfState} maps every real
 * {@code enterGroup}/{@code exitGroup} marker state to its {@code 2*g}/{@code 2*g+1} tag, AND
 * additionally maps every NFA accept state to tag {@code 1} (group 0's close) — the tag-numbering
 * equivalent of "write slot 1 the moment an accept state is reached." Group 0's open (tag 0) has no
 * per-state marker at all (by construction there is exactly one match attempt in an anchored
 * closure, so tag 0 is seeded once at closure-start time by the caller, exactly as {@code
 * initClist} seeds slot 0) — callers building an initial register file must set tag 0 themselves;
 * this class does not (and structurally cannot) discover a "group 0 open" state to map.
 */
final class LaurikariTagNumbering {

  private LaurikariTagNumbering() {}

  /**
   * @return {@code 2 * (groupCount + 1)}, the number of capture-tag registers for this pattern.
   */
  static int tagCount(int groupCount) {
    return 2 * (groupCount + 1);
  }

  /**
   * @return {@code tagOfState[id]}: the tag index ({@code 2*g} or {@code 2*g+1}) carried by NFA
   *     state {@code id}, or {@code -1} if state {@code id} carries no tag. Accept states are
   *     additionally mapped to tag {@code 1} (group 0's close) unless they already carry a real
   *     {@code enterGroup}/{@code exitGroup} tag (not expected in practice per {@code
   *     ThompsonBuilder}'s dedicated-marker-state construction, but resolved without overwriting a
   *     real group tag if it ever occurs).
   */
  static int[] tagOfState(NFA nfa) {
    int stateCount = nfa.getStates().size();
    int[] tagOfState = new int[stateCount];
    Arrays.fill(tagOfState, -1);
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.enterGroup != null) {
        tagOfState[s.id] = 2 * s.enterGroup;
      }
      if (s.exitGroup != null) {
        tagOfState[s.id] = 2 * s.exitGroup + 1;
      }
    }
    for (NFA.NFAState accept : nfa.getAcceptStates()) {
      if (tagOfState[accept.id] < 0) {
        tagOfState[accept.id] = 1; // group 0's close, the "caps[1] = pos at accept" equivalent
      }
    }
    return tagOfState;
  }
}
