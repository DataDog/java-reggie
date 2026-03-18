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
 * Non-deterministic Finite Automaton with epsilon transitions. Used as intermediate representation
 * for regex patterns.
 */
public final class NFA {

  private final List<NFAState> states;
  private final NFAState startState;
  private final Set<NFAState> acceptStates;
  private final int groupCount;

  public NFA(
      List<NFAState> states, NFAState startState, Set<NFAState> acceptStates, int groupCount) {
    this.states = List.copyOf(states);
    this.startState = startState;
    this.acceptStates = Set.copyOf(acceptStates);
    this.groupCount = groupCount;
  }

  public List<NFAState> getStates() {
    return states;
  }

  public NFAState getStartState() {
    return startState;
  }

  public Set<NFAState> getAcceptStates() {
    return acceptStates;
  }

  public int getGroupCount() {
    return groupCount;
  }

  /**
   * Check if this NFA contains a multiline start anchor (^ in multiline mode).
   *
   * @return true if any state has START_MULTILINE anchor
   */
  public boolean hasMultilineStartAnchor() {
    for (NFAState state : states) {
      if (state.anchor == AnchorType.START_MULTILINE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if this NFA contains a multiline end anchor ($ in multiline mode).
   *
   * @return true if any state has END_MULTILINE anchor
   */
  public boolean hasMultilineEndAnchor() {
    for (NFAState state : states) {
      if (state.anchor == AnchorType.END_MULTILINE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if this NFA contains a non-multiline start anchor (^). For string anchor \A, use
   * hasStringStartAnchor().
   *
   * @return true if any state has START anchor
   */
  public boolean hasStartAnchor() {
    for (NFAState state : states) {
      if (state.anchor == AnchorType.START) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if this NFA contains a non-multiline end anchor ($). For string anchors \Z and \z, use
   * hasStringEndAnchor() and hasStringEndAbsoluteAnchor().
   *
   * @return true if any state has END anchor
   */
  public boolean hasEndAnchor() {
    for (NFAState state : states) {
      if (state.anchor == AnchorType.END) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if this NFA contains a STRING_END anchor (\Z). \Z matches at end of string or before
   * final newline.
   */
  public boolean hasStringEndAnchor() {
    for (NFAState state : states) {
      if (state.anchor == AnchorType.STRING_END) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if this NFA contains a STRING_END_ABSOLUTE anchor (\z). \z matches only at absolute end
   * of string.
   */
  public boolean hasStringEndAbsoluteAnchor() {
    for (NFAState state : states) {
      if (state.anchor == AnchorType.STRING_END_ABSOLUTE) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if this NFA contains a STRING_START anchor (\A). \A matches only at start of string, not
   * affected by multiline.
   */
  public boolean hasStringStartAnchor() {
    for (NFAState state : states) {
      if (state.anchor == AnchorType.STRING_START) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if a start anchor is REQUIRED to match this pattern. Unlike hasStartAnchor(), this
   * returns true only if ALL paths to character transitions go through a START or STRING_START
   * anchor.
   *
   * <p>For example: - "^foo" requires start anchor (returns true) - "(^foo|bar)" does NOT require
   * start anchor (returns false) - "bar" branch can match anywhere - "foo" does NOT require start
   * anchor (returns false)
   *
   * <p>This is used to optimize find() operations - we can skip non-zero positions only when
   * requiresStartAnchor() returns true.
   */
  public boolean requiresStartAnchor() {
    // BFS to find states with character transitions reachable without going through anchor
    Set<NFAState> reachableWithoutAnchor = new HashSet<>();
    Queue<NFAState> queue = new LinkedList<>();
    queue.add(startState);
    reachableWithoutAnchor.add(startState);

    while (!queue.isEmpty()) {
      NFAState state = queue.poll();

      // If this state has an anchor, don't follow its transitions
      // (paths through this state require the anchor)
      if (state.anchor == AnchorType.START || state.anchor == AnchorType.STRING_START) {
        continue;
      }

      // If this state has character transitions, we can match without anchor
      if (!state.getTransitions().isEmpty()) {
        return false;
      }

      // Follow epsilon transitions (but not through anchor states)
      for (NFAState next : state.getEpsilonTransitions()) {
        if (!reachableWithoutAnchor.contains(next)) {
          reachableWithoutAnchor.add(next);
          queue.add(next);
        }
      }
    }

    // Couldn't find any character transitions reachable without going through an anchor
    return true;
  }

  /**
   * Check if this NFA has a backreference that references a group captured inside a lookahead. This
   * is important for indexOf optimization - patterns like (?=(\w+))\1: need to try positions before
   * where the required literal appears, because the lookahead capture happens at that earlier
   * position.
   */
  public boolean hasBackrefToLookaheadCapture() {
    // First, collect all groups that are captured inside lookaheads
    Set<Integer> groupsInLookaheads = new HashSet<>();
    for (NFAState state : states) {
      if (state.assertionType == AssertionType.POSITIVE_LOOKAHEAD
          || state.assertionType == AssertionType.NEGATIVE_LOOKAHEAD) {
        // Traverse the assertion sub-pattern to find captured groups
        collectGroupsInAssertion(
            state.assertionStartState, state.assertionAcceptStates, groupsInLookaheads);
      }
    }

    if (groupsInLookaheads.isEmpty()) {
      return false;
    }

    // Now check if any backref references these groups
    for (NFAState state : states) {
      if (state.backrefCheck != null && groupsInLookaheads.contains(state.backrefCheck)) {
        return true;
      }
    }

    return false;
  }

  /** Helper to collect group numbers captured inside an assertion sub-pattern. */
  private void collectGroupsInAssertion(
      NFAState startState, Set<NFAState> acceptStates, Set<Integer> groups) {
    if (startState == null) return;

    Set<NFAState> visited = new HashSet<>();
    Queue<NFAState> queue = new LinkedList<>();
    queue.add(startState);
    visited.add(startState);

    while (!queue.isEmpty()) {
      NFAState state = queue.poll();

      // Check for group captures
      if (state.enterGroup != null) {
        groups.add(state.enterGroup);
      }

      // Follow transitions within the assertion sub-pattern
      for (Transition t : state.getTransitions()) {
        if (!visited.contains(t.target)) {
          visited.add(t.target);
          queue.add(t.target);
        }
      }

      // Follow epsilon transitions
      for (NFAState next : state.getEpsilonTransitions()) {
        if (!visited.contains(next)) {
          visited.add(next);
          queue.add(next);
        }
      }
    }
  }

  /**
   * Compute a content-based hash code that includes character sets. Critical for distinguishing
   * patterns with different case-sensitivity (e.g., "(ab)c" vs "(a(?i)b)c").
   */
  public int contentHashCode() {
    int hash = 1;
    hash = 31 * hash + states.size();

    for (NFAState state : states) {
      // Include transition count
      hash = 31 * hash + state.getTransitions().size();

      // Include character set content for each transition
      for (Transition t : state.getTransitions()) {
        hash = 31 * hash + t.chars.hashCode();
      }

      // Include epsilon transitions count
      hash = 31 * hash + state.getEpsilonTransitions().size();

      // Include group markers
      hash = 31 * hash + (state.enterGroup != null ? state.enterGroup + 1 : 0);
      hash = 31 * hash + (state.exitGroup != null ? state.exitGroup + 1 : 0);
    }

    return hash;
  }

  /** NFA state with character transitions and epsilon transitions. */
  public static final class NFAState {
    public final int id;
    private final List<Transition> transitions = new ArrayList<>();
    private final List<NFAState> epsilonTransitions = new ArrayList<>();

    // For capturing groups
    public Integer enterGroup = null; // entering group N
    public Integer exitGroup = null; // exiting group N

    // For backreferences
    public Integer backrefCheck = null; // check backreference N

    // For anchors
    public AnchorType anchor = null;

    // For assertions (lookahead/lookbehind)
    public AssertionType assertionType = null;
    public NFAState assertionStartState = null; // Sub-pattern start
    public Set<NFAState> assertionAcceptStates = null; // Sub-pattern accepts
    public int assertionWidth = -1; // For lookbehind

    // For conditional patterns (?(n)then|else)
    public Integer conditionalGroup = null; // Group number to check
    public NFAState thenBranch = null; // Entry if group matched
    public NFAState elseBranch = null; // Entry if group didn't match (may be null)

    public NFAState(int id) {
      this.id = id;
    }

    public void addTransition(CharSet chars, NFAState target) {
      transitions.add(new Transition(chars, target));
    }

    public void addEpsilonTransition(NFAState target) {
      epsilonTransitions.add(target);
    }

    public List<Transition> getTransitions() {
      return Collections.unmodifiableList(transitions);
    }

    public List<NFAState> getEpsilonTransitions() {
      return Collections.unmodifiableList(epsilonTransitions);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("State(" + id);
      if (enterGroup != null) sb.append(", enter=").append(enterGroup);
      if (exitGroup != null) sb.append(", exit=").append(exitGroup);
      if (backrefCheck != null) sb.append(", backref=").append(backrefCheck);
      if (anchor != null) sb.append(", anchor=").append(anchor);
      if (assertionType != null) sb.append(", assertion=").append(assertionType);
      sb.append(")");
      return sb.toString();
    }
  }

  /** Character transition from one state to another. */
  public static final class Transition {
    public final CharSet chars;
    public final NFAState target;

    public Transition(CharSet chars, NFAState target) {
      this.chars = chars;
      this.target = target;
    }

    @Override
    public String toString() {
      return chars + " -> " + target.id;
    }
  }

  /** Anchor types for position matching. */
  public enum AnchorType {
    START, // ^ (non-multiline)
    END, // $ (non-multiline)
    START_MULTILINE, // ^ (multiline mode - matches at start or after \n)
    END_MULTILINE, // $ (multiline mode - matches at end or before \n)
    WORD_BOUNDARY, // \b
    STRING_START, // \A (start of string, not affected by multiline)
    STRING_END, // \Z (end of string or before final newline)
    STRING_END_ABSOLUTE, // \z (absolute end of string)
    RESET_MATCH // \K (reset match start - always succeeds, resets group 0 start)
  }

  /** Assertion types for lookahead/lookbehind. */
  public enum AssertionType {
    POSITIVE_LOOKAHEAD, // (?=...)  - must match ahead
    NEGATIVE_LOOKAHEAD, // (?!...)  - must not match ahead
    POSITIVE_LOOKBEHIND, // (?<=...) - must match behind
    NEGATIVE_LOOKBEHIND // (?<!...) - must not match behind
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("NFA(");
    sb.append("states=").append(states.size());
    sb.append(", start=").append(startState.id);
    sb.append(", accept=").append(acceptStates.stream().map(s -> s.id).toList());
    sb.append(", groups=").append(groupCount);
    sb.append(")");
    return sb.toString();
  }
}
