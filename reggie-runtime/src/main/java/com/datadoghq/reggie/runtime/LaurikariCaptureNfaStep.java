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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Impl-plan Task 1.2 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): full priority-ordered
 * register-merge determinization over the real {@code 2 * (groupCount + 1)} tags {@link
 * LaurikariTagNumbering} derives, generalizing {@code LaurikariPhase05Test}'s hand-rolled,
 * 2-3-tag-only test driver into shippable production code that works for any {@code groupCount}.
 *
 * <p><b>Register encoding: ages, not absolute positions.</b> {@link LaurikariNfaStep#apply} has no
 * {@code pos} parameter, so — exactly like {@code LaurikariPhase05Test}'s driver — each register
 * holds an <em>age</em> (characters consumed since that tag was last set, or {@code -1} if never
 * set), not an absolute position. Consuming a character ages every currently-set register by 1;
 * closure resets a specific tag's age to {@code 0} the moment it passes through that tag's marker
 * state (a real {@code enterGroup}/{@code exitGroup} state, or an accept state for tag 1 — see
 * {@link LaurikariTagNumbering}). The absolute tag position is recovered once, at the end, as
 * {@code totalConsumed - age} (see {@link #absolutePositions}) — the same formula {@code
 * LaurikariDFACache#acceptAge}/{@code LaurikariPhase05Test#runToEnd} already use for their smaller
 * tag counts. This is a deliberate scope decision, not an oversight: switching to literal absolute
 * positions would need runtime-substituted register operations threaded through a {@code pos}
 * parameter this interface doesn't have — that is exactly the "eventual real mechanism" the {@code
 * LaurikariNfaStep} class javadoc already earmarks as future work, not something Task 1.2 asks for.
 *
 * <p><b>Priority order</b> (design §4, "whole-mapping priority discard"): a DFS through epsilon
 * transitions in {@code curStates}' array order (index 0 = highest priority), first-visit wins —
 * mirrors {@code PikeVMMatcher.addThread}'s {@code for (int i = 0; i < epsilons.size(); i++)}
 * traversal over {@code state.getEpsilonTransitions()} exactly (that list's insertion order is
 * itself documented as load-bearing Perl-priority order by {@code NFA.NFAState}). {@code
 * curStates}' order is therefore load-bearing and must never be sorted/canonicalized, matching
 * {@link LaurikariStateSetKey}'s order-sensitive {@code equals}/{@code hashCode}.
 *
 * <p><b>Scope</b>: no lookaround, backreference, or atomic-group handling — those NFA constructs
 * are simply not traversed specially here (this class only walks {@code getEpsilonTransitions()}/
 * {@code getTransitions()}), matching this task's explicit exclusion of eligibility gating (Task
 * 1.3, not built here).
 *
 * <p><b>Task 1.4a — self-anchoring {@link #applyFind}:</b> {@code find()}-family semantics require
 * re-injecting a fresh "candidate starts here" closure at every character step, exactly mirroring
 * {@code PikeVMMatcher}'s thread-list reinjection at the tail of {@code clist}/{@code nlist} (an
 * implicit lowest-priority {@code .*?} prefix). {@link #apply} and {@link #applyFind} share the
 * same closure machinery via a private {@code apply(..., boolean reinject)}: when {@code reinject}
 * is true, a fresh <em>un-consumed</em>, already-closed start candidate (tag 0 = age 0) is appended
 * directly into this step's output — AFTER every existing candidate's closure — mirroring {@code
 * PikeVMMatcher.findStepClosure}'s {@code sortedUnion(tc, reinjectClosureIds)} exactly: {@code
 * reinjectClosureIds} is itself a closure of the raw start state that is unioned into the result
 * un-transitioned, ready to consume the next character on a later step, not transitioned on this
 * step's {@code c} directly. Getting this right matters because the reinjected candidate must be
 * <b>anchor-blocked</b> ({@link #reinjectStates}/{@link #reinjectRegs}, built by closing over the
 * start state with {@code blockStartAnchor=true}): {@code ^}/{@code \A} must never re-fire from any
 * position other than the true start of input, exactly as {@code PikeVMMatcher.reinjectClosureIds}
 * excludes those branches. {@code (?m)^} additionally depends on whether the character just
 * consumed by <em>this</em> step was {@code '\n'} — see {@link #reinjectAfterNlStates}/{@link
 * #reinjectAfterNlRegs} and {@code PikeVMMatcher.reinjectAfterNlClosureIds}'s identical split. The
 * existing {@code totalConsumed - age} formula in {@link #absolutePositions} needs no
 * special-casing for reinjected candidates: age is still "characters consumed since tag 0 was set,"
 * regardless of when within the scan that set happened.
 */
final class LaurikariCaptureNfaStep implements LaurikariNfaStep {

  private final NFA.NFAState[] statesById;
  private final int[] tagOfState;
  private final boolean[] isAccept;
  private final int startStateId;
  private final int[] initialStates;
  private final int[][] initialRegs;

  /**
   * Anchor-blocked closure of the start state, for reinjection mid-scan (blocks every start anchor,
   * including {@code (?m)^} — safe to use unconditionally when the pattern has no multiline anchor,
   * and as the "previous char wasn't '\n'" case when it does.
   */
  private final int[] reinjectStates;

  private final int[][] reinjectRegs;

  /**
   * Reinjection closure for the "previous consumed char was '\n'" case: crosses {@code (?m)^}
   * (which fires at every line start) but still blocks {@code ^}/{@code \A}. {@code null} when the
   * pattern has no {@code START_MULTILINE} anchor (then {@link #reinjectStates} covers every case).
   */
  private final int[] reinjectAfterNlStates;

  private final int[][] reinjectAfterNlRegs;

  /**
   * True if any NFA state carries one of the 5 position-dependent anchor types ({@code END}, {@code
   * STRING_END}, {@code STRING_END_ABSOLUTE}, {@code END_MULTILINE}, {@code WORD_BOUNDARY}) that
   * must be evaluated live against the real input/position via {@link PikeVMMatcher#checkAnchor} —
   * see the TDFA Phase 2 end-anchor/{@code \b} extension design. {@code false} for every pattern
   * this class handled before that extension, in which case every closure accessor below returns
   * its constructor-precomputed field unchanged (byte-identical to pre-extension behavior).
   */
  final boolean hasNewAnchor;

  /**
   * Parallel to {@link #statesById}: {@code anchorBearingStates[id]} is true if that NFA state
   * carries one of the 5 new anchor types. Exposed via {@link #anchorBearingStates()} so {@link
   * LaurikariDFACache} can flag which of its interned DFA states must never have their outgoing
   * transitions memoized (see that class's {@code anchorSensitive} field).
   */
  private final boolean[] anchorBearingStates;

  final int tagCount;

  LaurikariCaptureNfaStep(NFA nfa, int groupCount) {
    this.statesById = statesById(nfa);
    this.tagOfState = LaurikariTagNumbering.tagOfState(nfa);
    this.isAccept = isAccept(nfa, statesById.length);
    this.startStateId = nfa.getStartState().id;
    this.tagCount = LaurikariTagNumbering.tagCount(groupCount);
    this.anchorBearingStates = anchorBearingStates(statesById);
    this.hasNewAnchor = anyTrue(anchorBearingStates);
    LaurikariStepResult initial = closureFromStart(false, false, "", 0, 0);
    this.initialStates = initial.states;
    this.initialRegs = initial.regs;

    boolean hasMultiline = hasMultilineAnchor(nfa);
    LaurikariStepResult reinject = closureFromStart(true, true, "", 0, 0);
    this.reinjectStates = reinject.states;
    this.reinjectRegs = reinject.regs;
    if (hasMultiline) {
      LaurikariStepResult afterNl = closureFromStart(true, false, "", 0, 0);
      this.reinjectAfterNlStates = afterNl.states;
      this.reinjectAfterNlRegs = afterNl.regs;
    } else {
      this.reinjectAfterNlStates = null;
      this.reinjectAfterNlRegs = null;
    }
  }

  private static boolean hasMultilineAnchor(NFA nfa) {
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.anchor == NFA.AnchorType.START_MULTILINE) return true;
    }
    return false;
  }

  private static boolean isNewAnchorType(NFA.AnchorType a) {
    return a == NFA.AnchorType.END
        || a == NFA.AnchorType.STRING_END
        || a == NFA.AnchorType.STRING_END_ABSOLUTE
        || a == NFA.AnchorType.END_MULTILINE
        || a == NFA.AnchorType.WORD_BOUNDARY;
  }

  private static boolean[] anchorBearingStates(NFA.NFAState[] statesById) {
    boolean[] flags = new boolean[statesById.length];
    for (int i = 0; i < statesById.length; i++) {
      flags[i] = isNewAnchorType(statesById[i].anchor);
    }
    return flags;
  }

  private static boolean anyTrue(boolean[] flags) {
    for (boolean f : flags) {
      if (f) return true;
    }
    return false;
  }

  /**
   * @return {@link #anchorBearingStates}, for {@link LaurikariDFACache} to test its own interned
   *     subsets against — {@code null} when {@link #hasNewAnchor} is false (then every DFA state is
   *     guaranteed non-anchor-sensitive, so the cache need not consult this array at all).
   */
  boolean[] anchorBearingStates() {
    return hasNewAnchor ? anchorBearingStates : null;
  }

  private static boolean[] isAccept(NFA nfa, int stateCount) {
    boolean[] accept = new boolean[stateCount];
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      accept[s.id] = true;
    }
    return accept;
  }

  private static NFA.NFAState[] statesById(NFA nfa) {
    NFA.NFAState[] arr = new NFA.NFAState[nfa.getStates().size()];
    for (NFA.NFAState s : nfa.getStates()) {
      arr[s.id] = s;
    }
    return arr;
  }

  /**
   * @return the (states, regs) pair for a fresh match attempt anchored at some position: the
   *     epsilon-closure of the start state, with tag 0 (group 0's open — never carried by any NFA
   *     state, see {@link LaurikariTagNumbering}) seeded to age 0 and every other tag unset ({@code
   *     -1}). {@code blockStartAnchor}/{@code blockMultilineAnchor} select which anchors this
   *     particular fresh attempt is allowed to cross — see {@link #reinjectStates}'s javadoc.
   */
  private LaurikariStepResult closureFromStart(
      boolean blockStartAnchor,
      boolean blockMultilineAnchor,
      String input,
      int pos,
      int regionEnd) {
    int[] allUnset = new int[tagCount];
    Arrays.fill(allUnset, -1);
    allUnset[0] = 0; // group 0 opens here, age 0
    boolean[] visited = new boolean[statesById.length];
    List<Integer> outIds = new ArrayList<>();
    List<int[]> outRegs = new ArrayList<>();
    addClosure(
        visited,
        outIds,
        outRegs,
        startStateId,
        allUnset,
        blockStartAnchor,
        blockMultilineAnchor,
        input,
        pos,
        regionEnd);
    // Unblocked (blockStartAnchor=false) is initialClosure(), seeding the anchored matches()/
    // match() path: never truncate, mirroring PikeVMMatcher.runMatches/runMatchResult, which keep
    // lower-priority threads alive after a mid-string accept since they may still be needed to
    // satisfy a full-input match (see truncateAtFirstAccept's javadoc on why that cut is only
    // valid for find()-style leftmost-first semantics). Blocked variants seed the find()-family
    // reinject closures, where truncation is correct.
    return blockStartAnchor
        ? truncateAtFirstAccept(outIds, outRegs)
        : new LaurikariStepResult(toArray(outIds), outRegs.toArray(new int[0][]));
  }

  private static int[] toArray(List<Integer> ids) {
    int[] out = new int[ids.size()];
    for (int i = 0; i < out.length; i++) out[i] = ids.get(i);
    return out;
  }

  /**
   * @return {@code regs}, aged by one character (every set register incremented by 1; unset {@code
   *     -1} registers stay unset).
   */
  private static int[] ageAll(int[] regs) {
    int[] out = new int[regs.length];
    for (int i = 0; i < regs.length; i++) {
      out[i] = regs[i] < 0 ? -1 : regs[i] + 1;
    }
    return out;
  }

  /**
   * Priority-ordered epsilon-closure DFS (first-visit wins per {@code visited}, mirroring {@code
   * PikeVMMatcher.addThread}'s traversal order exactly), resetting {@code tagOfState[id]}'s age to
   * 0 at each tag boundary crossed and propagating every other tag's age unchanged. This is Task
   * 1.2's step 2/3: the register ops a transition carries fall out of this rule directly — pass-
   * through tags are copied via {@code ageAll} before the DFS starts, the one tag matching this
   * state's marker (if any) is overwritten to age 0 (the "set-to-current-pos" op, expressed in age
   * terms), and a target NFA state already visited by a higher-priority arrival discards this
   * arrival's entire register vector (step 3's whole-mapping discard) since {@code visited[id]}
   * short-circuits before this state's mapping is ever recorded.
   *
   * <p>{@code blockStartAnchor}/{@code blockMultilineAnchor}, when set, stop the DFS from crossing
   * through a {@code START}/{@code STRING_START} or {@code START_MULTILINE} anchor state
   * (respectively) — mirroring {@code PikeVMMatcher.sortedEpsilonClosure}'s identical skip. The
   * anchor state itself is still recorded (harmless: it carries no tag and has no consuming
   * transitions of its own), only its onward epsilon edges are pruned. Ordinary mid-scan closures
   * (every call site except the reinject-closure construction) pass both flags {@code false}: the
   * eligibility gate ({@code LaurikariEligibility}) already guarantees no anchor is reachable after
   * a consuming transition, so blocking never triggers there anyway.
   */
  private void addClosure(
      boolean[] visited,
      List<Integer> outIds,
      List<int[]> outRegs,
      int id,
      int[] regs,
      boolean blockStartAnchor,
      boolean blockMultilineAnchor,
      String input,
      int pos,
      int regionEnd) {
    if (visited[id]) return;
    visited[id] = true;
    int tag = tagOfState[id];
    int[] r = regs;
    if (tag >= 0) {
      r = regs.clone();
      r[tag] = 0;
    }
    outIds.add(id);
    outRegs.add(r);
    NFA.AnchorType a = statesById[id].anchor;
    if (a != null) {
      if (blockStartAnchor && (a == NFA.AnchorType.START || a == NFA.AnchorType.STRING_START)) {
        return;
      }
      if (blockMultilineAnchor && a == NFA.AnchorType.START_MULTILINE) return;
      if (isNewAnchorType(a) && !PikeVMMatcher.checkAnchor(a, input, pos, 0, regionEnd)) return;
    }
    for (NFA.NFAState e : statesById[id].getEpsilonTransitions()) {
      addClosure(
          visited,
          outIds,
          outRegs,
          e.id,
          r,
          blockStartAnchor,
          blockMultilineAnchor,
          input,
          pos,
          regionEnd);
    }
  }

  /**
   * @return the (states, regs) pair for an anchored match attempt starting at the true beginning of
   *     the scan (every start anchor satisfied) — the seed {@link LaurikariDfaMatcher} uses to
   *     construct its DFA caches' start state.
   */
  LaurikariStepResult initialClosure() {
    return new LaurikariStepResult(initialStates, initialRegs);
  }

  /**
   * Live variant of {@link #initialClosure} for {@code hasNewAnchor} patterns: recomputes the
   * closure against the real input/regionEnd (position 0) instead of returning the
   * constructor-precomputed fields, which were built before any real input existed. Returns the
   * precomputed fields unchanged when {@code !hasNewAnchor} (byte-identical to pre-extension
   * behavior).
   */
  LaurikariStepResult initialClosure(String input, int regionEnd) {
    if (!hasNewAnchor) return initialClosure();
    return closureFromStart(false, false, input, 0, regionEnd);
  }

  /**
   * @return {@link #initialClosure}, truncated at its first accept — the seed {@code
   *     LaurikariDfaMatcher} uses for {@code findCache}'s state 0 (a {@code find()}-family scan
   *     that happens to start at absolute position 0). Unlike {@link #initialClosure} itself (used
   *     for the anchored {@code matches()}/{@code match()} cache, which must never truncate — see
   *     {@link #truncateAtFirstAccept}'s javadoc), a {@code find()}-family scan starting at
   *     position 0 needs the same leftmost-first truncation every other {@code find()} start
   *     position gets.
   */
  LaurikariStepResult truncatedInitialClosure() {
    List<Integer> ids = new ArrayList<>(initialStates.length);
    for (int id : initialStates) ids.add(id);
    return truncateAtFirstAccept(ids, new ArrayList<>(Arrays.asList(initialRegs)));
  }

  /**
   * Live variant of {@link #truncatedInitialClosure} for {@code hasNewAnchor} patterns — see {@link
   * #initialClosure(String, int)}.
   */
  LaurikariStepResult truncatedInitialClosure(String input, int regionEnd) {
    if (!hasNewAnchor) return truncatedInitialClosure();
    LaurikariStepResult initial = closureFromStart(false, false, input, 0, regionEnd);
    List<Integer> ids = new ArrayList<>(initial.states.length);
    for (int id : initial.states) ids.add(id);
    return truncateAtFirstAccept(ids, new ArrayList<>(Arrays.asList(initial.regs)));
  }

  /**
   * @return the anchor-blocked closure {@link LaurikariDfaMatcher} seeds a {@code findFrom(input,
   *     start)} scan from when {@code start > 0} — {@link #initialClosure} is only valid when the
   *     scan truly begins at absolute position 0 ({@code ^}/{@code \A} satisfied); starting mid-
   *     string must never let those anchors fire (see {@code PikeVMMatcher.checkAnchor}'s identical
   *     absolute-0 pinning).
   */
  LaurikariStepResult reinjectClosure() {
    return new LaurikariStepResult(reinjectStates, reinjectRegs);
  }

  /**
   * Live variant of {@link #reinjectClosure} for {@code hasNewAnchor} patterns — see {@link
   * #initialClosure(String, int)}.
   */
  LaurikariStepResult reinjectClosure(String input, int pos, int regionEnd) {
    if (!hasNewAnchor) return reinjectClosure();
    return closureFromStart(true, true, input, pos, regionEnd);
  }

  /**
   * @return {@link #reinjectClosure}'s "previous character was '\n'" variant (crosses {@code
   *     (?m)^}), or {@code null} if the pattern has no {@code START_MULTILINE} anchor (then {@link
   *     #reinjectClosure} covers every case).
   */
  LaurikariStepResult reinjectAfterNlClosure() {
    return reinjectAfterNlStates == null
        ? null
        : new LaurikariStepResult(reinjectAfterNlStates, reinjectAfterNlRegs);
  }

  /**
   * Live variant of {@link #reinjectAfterNlClosure} for {@code hasNewAnchor} patterns — see {@link
   * #initialClosure(String, int)}. Note this is only meaningful when the pattern also has a
   * multiline start anchor ({@code reinjectAfterNlStates != null} in the non-live case); when
   * {@code hasNewAnchor} but the pattern has no multiline start anchor, this still returns {@code
   * null} exactly like the no-arg variant.
   */
  LaurikariStepResult reinjectAfterNlClosure(String input, int pos, int regionEnd) {
    if (!hasNewAnchor) return reinjectAfterNlClosure();
    if (reinjectAfterNlStates == null) return null;
    return closureFromStart(true, false, input, pos, regionEnd);
  }

  LaurikariStepResult apply(int[] curStates, int[][] curRegs, int c) {
    return apply(curStates, curRegs, c, "", 0, 0, false);
  }

  @Override
  public LaurikariStepResult apply(
      int[] curStates, int[][] curRegs, int c, String input, int pos, int regionEnd) {
    return apply(curStates, curRegs, c, input, pos, regionEnd, false);
  }

  /**
   * Self-anchoring variant of {@link #apply} for {@code find()}-family semantics: re-injects a
   * fresh lowest-priority "candidate starts here" closure at this character step, on top of {@code
   * apply}'s ordinary carried-forward candidates. See the class javadoc for why this composes
   * correctly with leftmost-first priority and the age-based {@link #absolutePositions} formula.
   */
  LaurikariStepResult applyFind(int[] curStates, int[][] curRegs, int c) {
    return apply(curStates, curRegs, c, "", 0, 0, true);
  }

  LaurikariStepResult applyFind(
      int[] curStates, int[][] curRegs, int c, String input, int pos, int regionEnd) {
    return apply(curStates, curRegs, c, input, pos, regionEnd, true);
  }

  private LaurikariStepResult apply(
      int[] curStates,
      int[][] curRegs,
      int c,
      String input,
      int pos,
      int regionEnd,
      boolean reinject) {
    int stateCount = statesById.length;
    // Step 1: consuming transitions from every NFA state in the current subset, in the same
    // priority order PikeVMMatcher.addThread walks (curStates' array order, index 0 highest
    // priority) -- first arrival to a given target state wins (step 3's whole-mapping discard).
    boolean[] seenTarget = new boolean[stateCount];
    List<Integer> seedIds = new ArrayList<>();
    List<int[]> seedRegs = new ArrayList<>();
    collectConsumingSeeds(curStates, curRegs, c, seenTarget, seedIds, seedRegs);
    // Step 2/3: epsilon-close each seed in the same priority order, applying each transition's
    // register ops (copy for pass-through tags, set-to-current-pos -- age 0 -- for the tag(s)
    // matching that transition's enterGroup/exitGroup) via addClosure, and discarding a
    // lower-priority arrival's whole mapping the moment a target is already visited.
    boolean[] visited = new boolean[stateCount];
    List<Integer> outIds = new ArrayList<>();
    List<int[]> outRegs = new ArrayList<>();
    for (int i = 0; i < seedIds.size(); i++) {
      addClosure(
          visited,
          outIds,
          outRegs,
          seedIds.get(i),
          seedRegs.get(i),
          false,
          false,
          input,
          pos,
          regionEnd);
    }
    if (reinject) {
      // Lowest priority: a fresh, already-closed, anchor-blocked start candidate appended straight
      // into this step's output -- un-transitioned, ready to consume the *next* character -- so it
      // never displaces a higher-priority (more leftmost) candidate already present. Mirrors
      // PikeVMMatcher.findStepClosure's sortedUnion(tc, reinjectClosureIds) exactly; see the class
      // javadoc for why this must be un-transitioned rather than fed through collectConsumingSeeds.
      int[] rStates;
      int[][] rRegs;
      if (hasNewAnchor) {
        boolean afterNl = reinjectAfterNlStates != null && c == '\n';
        LaurikariStepResult live =
            afterNl
                ? reinjectAfterNlClosure(input, pos, regionEnd)
                : reinjectClosure(input, pos, regionEnd);
        rStates = live.states;
        rRegs = live.regs;
      } else {
        rStates =
            (reinjectAfterNlStates != null && c == '\n') ? reinjectAfterNlStates : reinjectStates;
        rRegs = (reinjectAfterNlStates != null && c == '\n') ? reinjectAfterNlRegs : reinjectRegs;
      }
      for (int i = 0; i < rStates.length; i++) {
        int id = rStates[i];
        if (!visited[id]) {
          visited[id] = true;
          outIds.add(id);
          outRegs.add(rRegs[i]);
        }
      }
    }
    // Step 4: the caller (LaurikariDFACache) interns (states, regs) via LaurikariStateSetKey.
    // Only truncate for applyFind()'s find()-family semantics (reinject=true): a mid-string
    // accept there is already the leftmost, highest-priority match, so lower-priority threads
    // can never win (mirrors PikeVMMatcher.findPosFrom's clistSize = t cut). apply()'s
    // matches()/match() semantics (reinject=false) must keep lower-priority threads alive past a
    // mid-string accept, since the whole input still needs to match (mirrors
    // PikeVMMatcher.runMatches/runMatchResult, which never truncate on clistFirstAccept).
    return reinject
        ? truncateAtFirstAccept(outIds, outRegs)
        : new LaurikariStepResult(toArray(outIds), outRegs.toArray(new int[0][]));
  }

  /**
   * Priority-kill (mirrors {@code PikeVMMatcher}'s {@code clistSize = t} truncation at its
   * first-accept index): once a higher-priority arrival reaches an accept state, every
   * strictly-lower-priority arrival in this subset can never win a leftmost-first match and must
   * not survive into the next step's seed collection. The accept member itself is kept (its
   * register vector is what {@code LaurikariDFACache#acceptRegs} reads), but nothing after it in
   * priority order is retained. Applies uniformly to every closure result, including {@link
   * #initial} — an accept reachable ahead of a lower-priority consuming state at position 0 (e.g.
   * {@code "|a"}) must kill that lower-priority state just as it would mid-scan.
   */
  private LaurikariStepResult truncateAtFirstAccept(List<Integer> outIds, List<int[]> outRegs) {
    int size = outIds.size();
    for (int i = 0; i < size; i++) {
      if (isAccept[outIds.get(i)]) {
        size = i + 1;
        break;
      }
    }
    int[] states = new int[size];
    int[][] regsOut = new int[size][];
    for (int i = 0; i < size; i++) {
      states[i] = outIds.get(i);
      regsOut[i] = outRegs.get(i);
    }
    return new LaurikariStepResult(states, regsOut);
  }

  /**
   * Appends, to {@code seedIds}/{@code seedRegs}, one aged seed per distinct target state reached
   * by a {@code c}-consuming transition from {@code states}[i] (skipping targets already present in
   * {@code seenTarget}, and marking newly-added ones) — the shared core of step 1 for both {@code
   * apply}'s ordinary candidates and {@code applyFind}'s reinjected fresh-start candidate.
   */
  private void collectConsumingSeeds(
      int[] states,
      int[][] regs,
      int c,
      boolean[] seenTarget,
      List<Integer> seedIds,
      List<int[]> seedRegs) {
    for (int i = 0; i < states.length; i++) {
      NFA.NFAState s = statesById[states[i]];
      for (NFA.Transition t : s.getTransitions()) {
        if (t.chars.contains((char) c)) {
          int targetId = t.target.id;
          if (!seenTarget[targetId]) {
            seenTarget[targetId] = true;
            seedIds.add(targetId);
            seedRegs.add(ageAll(regs[i]));
          }
        }
      }
    }
  }

  /**
   * @return absolute tag positions ({@code totalConsumed - age}, or {@code -1} for a tag never set)
   *     recovered from an accept-state register vector after {@code totalConsumed} characters have
   *     been consumed from the anchored start.
   */
  int[] absolutePositions(int[] acceptRegs, int totalConsumed) {
    int[] absolute = new int[tagCount];
    for (int t = 0; t < tagCount; t++) {
      absolute[t] = acceptRegs[t] < 0 ? -1 : totalConsumed - acceptRegs[t];
    }
    return absolute;
  }
}
