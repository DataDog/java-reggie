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

import java.util.*;

/**
 * Deterministic Finite Automaton (DFA) representation. Each state has deterministic transitions (no
 * epsilon, no ambiguity).
 */
public final class DFA {
  private final DFAState startState;
  private final Set<DFAState> acceptStates;
  private final List<DFAState> allStates;

  /**
   * True when subset construction detected an anchor-condition dilution: two contributors in the
   * same partition slice (or two accept states) had differing anchor conditions, and intersection
   * collapsed them to unconditional. The DFA is structurally valid but may accept inputs that a
   * correctly-anchored automaton would reject. Callers should route to a non-DFA engine.
   */
  private final boolean anchorConditionDiluted;

  /**
   * True when subset construction detected a capture ambiguity: an accepting DFA state contains NFA
   * threads that disagree about a capturing group's participation — one thread entered and exited
   * the group, another bypassed it, and both are accepting. The lowest-state-id merge in {@code
   * SubsetConstructor.computeGroupActions} cannot choose the correct binding. Callers should route
   * to a correct fallback engine (e.g. {@code JavaRegexFallbackMatcher}).
   */
  private final boolean captureAmbiguous;

  public DFA(DFAState startState, Set<DFAState> acceptStates, List<DFAState> allStates) {
    this(startState, acceptStates, allStates, false);
  }

  public DFA(
      DFAState startState,
      Set<DFAState> acceptStates,
      List<DFAState> allStates,
      boolean anchorConditionDiluted) {
    this(startState, acceptStates, allStates, anchorConditionDiluted, false);
  }

  public DFA(
      DFAState startState,
      Set<DFAState> acceptStates,
      List<DFAState> allStates,
      boolean anchorConditionDiluted,
      boolean captureAmbiguous) {
    this.startState = startState;
    this.acceptStates = acceptStates;
    this.allStates = allStates;
    this.anchorConditionDiluted = anchorConditionDiluted;
    this.captureAmbiguous = captureAmbiguous;
  }

  public boolean isAnchorConditionDiluted() {
    return anchorConditionDiluted;
  }

  public boolean isCaptureAmbiguous() {
    return captureAmbiguous;
  }

  public DFAState getStartState() {
    return startState;
  }

  public Set<DFAState> getAcceptStates() {
    return acceptStates;
  }

  public List<DFAState> getAllStates() {
    return allStates;
  }

  /** Returns the total number of states in the DFA. */
  public int getStateCount() {
    return allStates.size();
  }

  /**
   * Returns the maximum number of outgoing transitions from any state. Used to determine code
   * generation strategy.
   */
  public int getMaxOutDegree() {
    return allStates.stream().mapToInt(s -> s.transitions.size()).max().orElse(0);
  }

  /**
   * Returns true if the DFA has dense transitions (many transitions per state). Dense DFAs benefit
   * from table-driven code generation.
   */
  public boolean isDense() {
    return getMaxOutDegree() > 10;
  }

  /** Represents a group capture action (enter or exit) at a DFA state. */
  public static final class GroupAction {
    public final int groupId; // Which group (1-based)
    public final ActionType type; // ENTER or EXIT
    public final int priority; // NFA state ID (for conflict resolution)

    public enum ActionType {
      ENTER,
      EXIT
    }

    public GroupAction(int groupId, ActionType type, int priority) {
      this.groupId = groupId;
      this.type = type;
      this.priority = priority;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      GroupAction that = (GroupAction) o;
      return groupId == that.groupId && priority == that.priority && type == that.type;
    }

    @Override
    public int hashCode() {
      return Objects.hash(groupId, type, priority);
    }
  }

  /**
   * Represents a tag operation on a DFA transition (Tagged DFA / Laurikari's algorithm). Tags
   * record input positions for capturing groups during DFA execution. Each capturing group uses 2
   * tags: one for start position, one for end position.
   */
  public static final class TagOperation {
    public final int tagId; // Tag index to update
    public final int groupId; // Associated group (for debugging)
    public final ActionType type; // Whether this is a group start or end
    public final int priority; // NFA state ID (for conflict resolution)

    public enum ActionType {
      START,
      END
    }

    public TagOperation(int tagId, int groupId, ActionType type, int priority) {
      this.tagId = tagId;
      this.groupId = groupId;
      this.type = type;
      this.priority = priority;
    }

    /**
     * Compute tag ID for a group start position. Group i uses tags [2*i, 2*i+1] for [start, end].
     */
    public static int tagIdForGroupStart(int groupId) {
      return 2 * groupId;
    }

    /** Compute tag ID for a group end position. */
    public static int tagIdForGroupEnd(int groupId) {
      return 2 * groupId + 1;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      TagOperation that = (TagOperation) o;
      return tagId == that.tagId
          && groupId == that.groupId
          && priority == that.priority
          && type == that.type;
    }

    @Override
    public int hashCode() {
      return Objects.hash(tagId, groupId, type, priority);
    }

    @Override
    public String toString() {
      return "Tag["
          + tagId
          + "](group="
          + groupId
          + ", type="
          + type
          + ", priority="
          + priority
          + ")";
    }
  }

  /** Represents a DFA transition with optional tag operations (Tagged DFA). */
  public static final class DFATransition {
    public final DFAState target;
    public final List<TagOperation> tagOps; // Tag operations to perform on this transition

    /**
     * Anchor preconditions that must hold at the *source* position (before consuming a character).
     * Empty = unconditional. Populated when the contributing NFA path crosses a START-class anchor
     * (START / STRING_START / START_MULTILINE) before consuming. Codegen emits a position guard.
     */
    public final EnumSet<NFA.AnchorType> entryGuard;

    public DFATransition(DFAState target) {
      this(target, Collections.emptyList(), EnumSet.noneOf(NFA.AnchorType.class));
    }

    public DFATransition(DFAState target, List<TagOperation> tagOps) {
      this(target, tagOps, EnumSet.noneOf(NFA.AnchorType.class));
    }

    public DFATransition(
        DFAState target, List<TagOperation> tagOps, EnumSet<NFA.AnchorType> entryGuard) {
      this.target = target;
      this.tagOps = tagOps;
      this.entryGuard = entryGuard;
    }

    @Override
    public String toString() {
      return "Transition{target="
          + target.id
          + ", tags="
          + tagOps.size()
          + (entryGuard.isEmpty() ? "" : ", guard=" + entryGuard)
          + "}";
    }
  }

  /** A single state in the DFA. */
  public static final class DFAState {
    public final int id;
    public final Map<CharSet, DFATransition> transitions;
    public final boolean accepting;
    public final Set<NFA.NFAState> nfaStates; // Source NFA states for analysis
    public final List<AssertionCheck>
        assertionChecks; // Assertions to check in this state (prototype)
    public final List<GroupAction> groupActions; // Group capture actions when entering this state

    /**
     * True when, at this accepting state, the highest-priority live NFA thread is the one that
     * accepts — i.e. the lowest-rank accepting NFA state has rank ≤ the lowest-rank NFA state with
     * a consuming out-transition. When true the executor must commit immediately (priority cut)
     * rather than continuing to the longest match. Set by SubsetConstructor.buildDFA after the C1
     * ordered closure is available; false by default for non-accepting or non-determinable states.
     */
    public boolean acceptIsPriorityCut = false;

    /**
     * True when this accepting state contains at least one NFA state with consuming transitions
     * whose rank is strictly greater than the minimum accept rank (lower priority than the accept
     * thread). Such a consuming thread can fire in the DFA and override a higher-priority empty
     * match with a longer, lower-priority match. Patterns with such states need to be declined when
     * the DFA's longest-match cannot be corrected by acceptIsPriorityCut alone. Set by
     * SubsetConstructor.buildDFA; false by default.
     */
    public boolean hasPriorityConflictTransition = false;

    /**
     * Anchor preconditions that must hold *at the current input position* for this state to be
     * considered accepting. Empty = unconditional (existing {@link #accepting} flag semantics).
     * Populated when the only paths to an NFA accept state cross END-class anchors (END /
     * STRING_END / STRING_END_ABSOLUTE / END_MULTILINE) or START-class anchors (START /
     * STRING_START / START_MULTILINE). Codegen emits the corresponding position check before
     * accepting.
     */
    public final EnumSet<NFA.AnchorType> acceptanceAnchorConditions;

    public DFAState(int id, Set<NFA.NFAState> nfaStates, boolean accepting) {
      this(id, nfaStates, accepting, new ArrayList<>(), new ArrayList<>());
    }

    public DFAState(
        int id,
        Set<NFA.NFAState> nfaStates,
        boolean accepting,
        List<AssertionCheck> assertionChecks) {
      this(id, nfaStates, accepting, assertionChecks, new ArrayList<>());
    }

    public DFAState(
        int id,
        Set<NFA.NFAState> nfaStates,
        boolean accepting,
        List<AssertionCheck> assertionChecks,
        List<GroupAction> groupActions) {
      this(
          id,
          nfaStates,
          accepting,
          assertionChecks,
          groupActions,
          EnumSet.noneOf(NFA.AnchorType.class));
    }

    public DFAState(
        int id,
        Set<NFA.NFAState> nfaStates,
        boolean accepting,
        List<AssertionCheck> assertionChecks,
        List<GroupAction> groupActions,
        EnumSet<NFA.AnchorType> acceptanceAnchorConditions) {
      this.id = id;
      this.nfaStates = nfaStates;
      this.accepting = accepting;
      this.assertionChecks = assertionChecks;
      this.groupActions = groupActions;
      this.acceptanceAnchorConditions = acceptanceAnchorConditions;
      this.transitions = new LinkedHashMap<>();
    }

    public void addTransition(CharSet chars, DFAState target) {
      addTransition(chars, target, Collections.emptyList());
    }

    public void addTransition(CharSet chars, DFAState target, List<TagOperation> tagOps) {
      transitions.put(chars, new DFATransition(target, tagOps));
    }

    public void addTransition(
        CharSet chars,
        DFAState target,
        List<TagOperation> tagOps,
        EnumSet<NFA.AnchorType> entryGuard) {
      transitions.put(chars, new DFATransition(target, tagOps, entryGuard));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      DFAState dfaState = (DFAState) o;
      return id == dfaState.id;
    }

    @Override
    public int hashCode() {
      return Objects.hash(id);
    }

    @Override
    public String toString() {
      return "DFAState{id="
          + id
          + ", accepting="
          + accepting
          + ", transitions="
          + transitions.size()
          + "}";
    }
  }
}
