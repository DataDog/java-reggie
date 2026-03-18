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

  public DFA(DFAState startState, Set<DFAState> acceptStates, List<DFAState> allStates) {
    this.startState = startState;
    this.acceptStates = acceptStates;
    this.allStates = allStates;
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

    public DFATransition(DFAState target) {
      this(target, Collections.emptyList());
    }

    public DFATransition(DFAState target, List<TagOperation> tagOps) {
      this.target = target;
      this.tagOps = tagOps;
    }

    @Override
    public String toString() {
      return "Transition{target=" + target.id + ", tags=" + tagOps.size() + "}";
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
      this.id = id;
      this.nfaStates = nfaStates;
      this.accepting = accepting;
      this.assertionChecks = assertionChecks;
      this.groupActions = groupActions;
      this.transitions = new LinkedHashMap<>();
    }

    public void addTransition(CharSet chars, DFAState target) {
      addTransition(chars, target, Collections.emptyList());
    }

    public void addTransition(CharSet chars, DFAState target, List<TagOperation> tagOps) {
      transitions.put(chars, new DFATransition(target, tagOps));
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
