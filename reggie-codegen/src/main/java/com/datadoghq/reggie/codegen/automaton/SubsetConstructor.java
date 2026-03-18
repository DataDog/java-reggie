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
 * Implements subset construction algorithm to convert NFA to DFA. Uses pre-computed epsilon
 * closures for efficiency.
 */
public class SubsetConstructor {

  private Map<Set<NFA.NFAState>, DFA.DFAState> stateCache;
  private List<DFA.DFAState> allStates;
  private int nextStateId;

  public DFA buildDFA(NFA nfa) throws StateExplosionException {
    return buildDFA(nfa, false);
  }

  /**
   * Build DFA with optional tag computation for Tagged DFA.
   *
   * @param nfa The NFA to convert
   * @param computeTags If true, compute tag operations on transitions (Tagged DFA)
   * @return DFA with or without tag operations
   * @throws StateExplosionException if DFA has too many states
   */
  public DFA buildDFA(NFA nfa, boolean computeTags) throws StateExplosionException {
    this.stateCache = new HashMap<>();
    this.allStates = new ArrayList<>();
    this.nextStateId = 0;

    // Pre-compute epsilon closures for all NFA states
    Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures = precomputeEpsilonClosures(nfa);

    // Start with epsilon-closure of NFA start state
    Set<NFA.NFAState> startClosure = epsilonClosures.get(nfa.getStartState());
    boolean startAccepting = containsAcceptState(startClosure, nfa.getAcceptStates());
    List<DFA.GroupAction> startGroupActions = computeGroupActions(startClosure);
    DFA.DFAState start =
        new DFA.DFAState(
            nextStateId++, startClosure, startAccepting, new ArrayList<>(), startGroupActions);
    stateCache.put(startClosure, start);
    allStates.add(start);

    Queue<DFA.DFAState> worklist = new ArrayDeque<>();
    worklist.add(start);

    while (!worklist.isEmpty()) {
      DFA.DFAState current = worklist.poll();

      // Compute disjoint partition of outgoing character sets
      List<CharSet> partition = computeDisjointPartition(current.nfaStates);

      for (CharSet chars : partition) {
        // Find all NFA states reachable on this charset
        Set<NFA.NFAState> targets = new HashSet<>();
        for (NFA.NFAState nfaState : current.nfaStates) {
          for (NFA.Transition trans : nfaState.getTransitions()) {
            if (trans.chars.intersects(chars)) {
              // Add epsilon closure of target state
              targets.addAll(epsilonClosures.get(trans.target));
            }
          }
        }

        if (targets.isEmpty()) continue;

        // Get or create DFA state
        DFA.DFAState target = stateCache.get(targets);
        if (target == null) {
          boolean accepting = containsAcceptState(targets, nfa.getAcceptStates());
          List<DFA.GroupAction> groupActions = computeGroupActions(targets);
          target =
              new DFA.DFAState(nextStateId++, targets, accepting, new ArrayList<>(), groupActions);
          stateCache.put(targets, target);
          allStates.add(target);
          worklist.add(target);
        }

        // Compute tag operations if requested (Tagged DFA)
        if (computeTags && nfa.getGroupCount() > 0) {
          List<DFA.TagOperation> tagOps =
              computeTagOperations(current.nfaStates, targets, chars, epsilonClosures);
          current.addTransition(chars, target, tagOps);
        } else {
          current.addTransition(chars, target);
        }
      }

      // Check state explosion (user said compile-time doesn't matter, but set reasonable limit)
      if (stateCache.size() > 10000) {
        throw new StateExplosionException("DFA has >10K states, use NFA instead");
      }
    }

    Set<DFA.DFAState> acceptStates =
        allStates.stream().filter(s -> s.accepting).collect(java.util.stream.Collectors.toSet());

    return new DFA(start, acceptStates, allStates);
  }

  /**
   * Pre-compute epsilon closures for all NFA states. This is done once at DFA construction time,
   * not at runtime.
   */
  private Map<NFA.NFAState, Set<NFA.NFAState>> precomputeEpsilonClosures(NFA nfa) {
    Map<NFA.NFAState, Set<NFA.NFAState>> closures = new HashMap<>();

    for (NFA.NFAState state : nfa.getStates()) {
      Set<NFA.NFAState> closure = new HashSet<>();
      computeEpsilonClosure(state, closure);
      closures.put(state, closure);
    }

    return closures;
  }

  /** Compute epsilon closure of a single state using worklist algorithm. */
  private void computeEpsilonClosure(NFA.NFAState start, Set<NFA.NFAState> closure) {
    Stack<NFA.NFAState> worklist = new Stack<>();
    worklist.push(start);
    closure.add(start);

    while (!worklist.isEmpty()) {
      NFA.NFAState current = worklist.pop();
      for (NFA.NFAState target : current.getEpsilonTransitions()) {
        if (!closure.contains(target)) {
          closure.add(target);
          worklist.push(target);
        }
      }
    }
  }

  /**
   * Critical algorithm: splits overlapping character sets into disjoint ranges. Example: [a-z] and
   * [e-m] → [a-d], [e-m], [n-z]
   *
   * <p>This ensures that for any character, there's exactly one transition to follow.
   */
  private List<CharSet> computeDisjointPartition(Set<NFA.NFAState> states) {
    // Collect all character sets from outgoing transitions
    List<CharSet> allCharSets = new ArrayList<>();
    for (NFA.NFAState state : states) {
      for (NFA.Transition trans : state.getTransitions()) {
        allCharSets.add(trans.chars);
      }
    }

    if (allCharSets.isEmpty()) {
      return Collections.emptyList();
    }

    // Use interval refinement algorithm
    // Start with all ranges from all charsets
    List<CharSet.Range> allRanges = new ArrayList<>();
    for (CharSet cs : allCharSets) {
      allRanges.addAll(cs.getRanges());
    }

    if (allRanges.isEmpty()) {
      return Collections.emptyList();
    }

    // Sort ranges by start position
    allRanges.sort(Comparator.comparingInt(r -> r.start));

    // Split overlapping ranges into disjoint segments
    List<CharSet.Range> disjointRanges = new ArrayList<>();
    List<Integer> splitPoints = new ArrayList<>();

    // Collect all split points (start and end+1 of each range)
    for (CharSet.Range range : allRanges) {
      splitPoints.add((int) range.start);
      if (range.end < Character.MAX_VALUE) {
        splitPoints.add((int) range.end + 1);
      }
    }

    // Remove duplicates and sort
    splitPoints = new ArrayList<>(new TreeSet<>(splitPoints));

    // Create disjoint ranges between consecutive split points
    for (int i = 0; i < splitPoints.size() - 1; i++) {
      char start = (char) (int) splitPoints.get(i);
      char end = (char) (splitPoints.get(i + 1) - 1);
      disjointRanges.add(new CharSet.Range(start, end));
    }

    // Add final range if needed
    if (!splitPoints.isEmpty()) {
      int lastPoint = splitPoints.get(splitPoints.size() - 1);
      boolean hasRangeToMax =
          allRanges.stream().anyMatch(r -> r.end == Character.MAX_VALUE && r.start <= lastPoint);
      if (hasRangeToMax && lastPoint <= Character.MAX_VALUE) {
        disjointRanges.add(new CharSet.Range((char) lastPoint, Character.MAX_VALUE));
      }
    }

    // Convert disjoint ranges to CharSets
    // Only keep ranges that are actually used by at least one transition
    List<CharSet> result = new ArrayList<>();
    for (CharSet.Range range : disjointRanges) {
      CharSet rangeSet = CharSet.range(range.start, range.end);
      // Check if this range intersects with any original charset
      boolean used = allCharSets.stream().anyMatch(cs -> cs.intersects(rangeSet));
      if (used) {
        result.add(rangeSet);
      }
    }

    return result;
  }

  /** Check if a set of NFA states contains any accept state. */
  private boolean containsAcceptState(Set<NFA.NFAState> states, Set<NFA.NFAState> acceptStates) {
    for (NFA.NFAState state : states) {
      if (acceptStates.contains(state)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Compute group actions for a DFA state based on its constituent NFA states. Uses
   * leftmost-longest semantics: lower NFA state IDs have higher priority.
   *
   * @param nfaStates Set of NFA states that make up this DFA state
   * @return List of group actions, deduplicated by (groupId, actionType) with highest priority
   */
  private List<DFA.GroupAction> computeGroupActions(Set<NFA.NFAState> nfaStates) {
    // Collect all group markers from NFA states
    List<DFA.GroupAction> actions = new ArrayList<>();

    for (NFA.NFAState nfaState : nfaStates) {
      if (nfaState.enterGroup != null) {
        actions.add(
            new DFA.GroupAction(
                nfaState.enterGroup, DFA.GroupAction.ActionType.ENTER, nfaState.id));
      }
      if (nfaState.exitGroup != null) {
        actions.add(
            new DFA.GroupAction(nfaState.exitGroup, DFA.GroupAction.ActionType.EXIT, nfaState.id));
      }
    }

    if (actions.isEmpty()) {
      return Collections.emptyList();
    }

    // Deduplicate: keep only the action with highest priority (lowest NFA state ID)
    // for each (groupId, actionType) pair
    Map<String, DFA.GroupAction> deduped = new HashMap<>();
    for (DFA.GroupAction action : actions) {
      String key = action.groupId + ":" + action.type;
      DFA.GroupAction existing = deduped.get(key);
      if (existing == null || action.priority < existing.priority) {
        deduped.put(key, action);
      }
    }

    // Sort by priority for deterministic ordering
    List<DFA.GroupAction> result = new ArrayList<>(deduped.values());
    result.sort(Comparator.comparingInt(a -> a.priority));
    return result;
  }

  /**
   * Check if a character transition actually enters a group or bypasses it via epsilon. For
   * optional groups like (b)?, we don't want to emit START tags when taking the bypass path.
   *
   * @param enterState The NFA state with the enterGroup marker
   * @param groupId The group ID to check
   * @param sourceNFAStates Source DFA state's NFA states
   * @param targetNFAStates Target DFA state's NFA states
   * @param charSet Character set for the transition
   * @param epsilonClosures Pre-computed epsilon closures
   * @return true if the transition actually enters the group, false if bypassing
   */
  private boolean isGroupActuallyEntered(
      NFA.NFAState enterState,
      int groupId,
      Set<NFA.NFAState> sourceNFAStates,
      Set<NFA.NFAState> targetNFAStates,
      CharSet charSet,
      Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures) {

    // Strategy: Check if any of the target states are reachable by following
    // character transitions from source states that would go THROUGH the group content
    // (i.e., states between the ENTER and EXIT markers for this group)

    // Find states in target that have entered this group but not yet exited
    for (NFA.NFAState targetState : targetNFAStates) {
      // Check if this target state is "inside" the group
      // We do this by checking if we can reach an EXIT marker for this group from here
      if (canReachGroupExit(targetState, groupId, epsilonClosures, new HashSet<>())) {
        // This target state is inside the group, so we're actually entering
        return true;
      }
    }

    // None of the target states are inside the group, so we're bypassing it
    return false;
  }

  /**
   * Check if we can reach an EXIT marker for the given group from a state. This helps determine if
   * a state is "inside" a group. Checks both epsilon transitions and character transitions
   * (recursively).
   */
  private boolean canReachGroupExit(
      NFA.NFAState state,
      int groupId,
      Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures,
      Set<NFA.NFAState> visited) {

    if (visited.contains(state)) return false;
    visited.add(state);

    // Check the epsilon closure of this state
    Set<NFA.NFAState> closure = epsilonClosures.get(state);
    for (NFA.NFAState reachable : closure) {
      if (reachable.exitGroup != null && reachable.exitGroup == groupId) {
        return true; // Found EXIT marker for this group
      }
    }

    // Also check if we can reach EXIT via character transitions
    // This is important for groups like (fo|foo) where EXIT is after character consumption
    for (NFA.NFAState reachable : closure) {
      for (NFA.Transition trans : reachable.getTransitions()) {
        // Follow character transitions recursively
        Set<NFA.NFAState> targetClosure = epsilonClosures.get(trans.target);
        for (NFA.NFAState targetState : targetClosure) {
          if (targetState.exitGroup != null && targetState.exitGroup == groupId) {
            return true;
          }
          // Recursive check (with depth limit to avoid infinite loops)
          if (visited.size() < 100) { // Reasonable depth limit
            if (canReachGroupExit(targetState, groupId, epsilonClosures, visited)) {
              return true;
            }
          }
        }
      }
    }

    return false;
  }

  /**
   * Compute tag operations for a DFA transition (Tagged DFA / Laurikari's algorithm). Tracks which
   * group boundaries are crossed when transitioning from source NFA states to target NFA states via
   * a character transition and epsilon closure.
   *
   * @param sourceNFAStates NFA states in the source DFA state
   * @param targetNFAStates NFA states in the target DFA state
   * @param charSet Character set for this transition
   * @param epsilonClosures Pre-computed epsilon closures
   * @return List of tag operations to perform on this transition
   */
  private List<DFA.TagOperation> computeTagOperations(
      Set<NFA.NFAState> sourceNFAStates,
      Set<NFA.NFAState> targetNFAStates,
      CharSet charSet,
      Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures) {

    // Collect all tag operations encountered along paths from source to target
    Map<Integer, DFA.TagOperation> tagOps = new HashMap<>();

    // FIRST: Check for group ENTER markers in source states
    // Only emit these if the transition actually enters the group (not bypassing it)
    for (NFA.NFAState sourceState : sourceNFAStates) {
      if (sourceState.enterGroup != null) {
        // Check if the target states include any states that come AFTER this ENTER marker
        // This indicates we're actually entering the group, not bypassing it via epsilon
        boolean actuallyEntering =
            isGroupActuallyEntered(
                sourceState,
                sourceState.enterGroup,
                sourceNFAStates,
                targetNFAStates,
                charSet,
                epsilonClosures);

        if (actuallyEntering) {
          int tagId = DFA.TagOperation.tagIdForGroupStart(sourceState.enterGroup);
          DFA.TagOperation op =
              new DFA.TagOperation(
                  tagId, sourceState.enterGroup, DFA.TagOperation.ActionType.START, sourceState.id);
          // Keep highest priority (lowest NFA state ID)
          DFA.TagOperation existing = tagOps.get(tagId);
          if (existing == null || op.priority < existing.priority) {
            tagOps.put(tagId, op);
          }
        }
      }
    }

    // SECOND: Track tag operations along character transitions
    for (NFA.NFAState source : sourceNFAStates) {
      // For each character transition from this source state
      for (NFA.Transition trans : source.getTransitions()) {
        if (!trans.chars.intersects(charSet)) continue;

        // Follow epsilon closure to find all reachable states
        Set<NFA.NFAState> closure = epsilonClosures.get(trans.target);

        // Only consider paths that lead to states in the target DFA state
        for (NFA.NFAState reachable : closure) {
          if (!targetNFAStates.contains(reachable)) continue;

          // Track tag operations along this path
          // 1. Group operations on the character transition target
          if (trans.target.enterGroup != null) {
            int tagId = DFA.TagOperation.tagIdForGroupStart(trans.target.enterGroup);
            DFA.TagOperation op =
                new DFA.TagOperation(
                    tagId,
                    trans.target.enterGroup,
                    DFA.TagOperation.ActionType.START,
                    trans.target.id);
            // Keep highest priority (lowest NFA state ID)
            DFA.TagOperation existing = tagOps.get(tagId);
            if (existing == null || op.priority < existing.priority) {
              tagOps.put(tagId, op);
            }
          }

          if (trans.target.exitGroup != null) {
            int tagId = DFA.TagOperation.tagIdForGroupEnd(trans.target.exitGroup);
            DFA.TagOperation op =
                new DFA.TagOperation(
                    tagId,
                    trans.target.exitGroup,
                    DFA.TagOperation.ActionType.END,
                    trans.target.id);
            DFA.TagOperation existing = tagOps.get(tagId);
            if (existing == null || op.priority < existing.priority) {
              tagOps.put(tagId, op);
            }
          }

          // 2. Group operations along the epsilon closure path
          // We need to track which states we visited to reach 'reachable'
          trackEpsilonPathTags(trans.target, reachable, tagOps);
        }
      }
    }

    // Sort by priority for deterministic ordering
    List<DFA.TagOperation> result = new ArrayList<>(tagOps.values());
    result.sort(Comparator.comparingInt(op -> op.priority));
    return result;
  }

  /**
   * Track tag operations along an epsilon path from start to end. Uses BFS to find the path and
   * collect group operations.
   */
  private void trackEpsilonPathTags(
      NFA.NFAState start, NFA.NFAState end, Map<Integer, DFA.TagOperation> tagOps) {
    if (start == end) return;

    // BFS to find path and collect group operations
    Queue<NFA.NFAState> queue = new ArrayDeque<>();
    Set<NFA.NFAState> visited = new HashSet<>();
    queue.add(start);
    visited.add(start);

    while (!queue.isEmpty()) {
      NFA.NFAState current = queue.poll();

      for (NFA.NFAState next : current.getEpsilonTransitions()) {
        if (visited.contains(next)) continue;
        visited.add(next);
        queue.add(next);

        // Collect group operations on this epsilon transition
        if (next.enterGroup != null) {
          int tagId = DFA.TagOperation.tagIdForGroupStart(next.enterGroup);
          DFA.TagOperation op =
              new DFA.TagOperation(
                  tagId, next.enterGroup, DFA.TagOperation.ActionType.START, next.id);
          DFA.TagOperation existing = tagOps.get(tagId);
          if (existing == null || op.priority < existing.priority) {
            tagOps.put(tagId, op);
          }
        }

        if (next.exitGroup != null) {
          int tagId = DFA.TagOperation.tagIdForGroupEnd(next.exitGroup);
          DFA.TagOperation op =
              new DFA.TagOperation(tagId, next.exitGroup, DFA.TagOperation.ActionType.END, next.id);
          DFA.TagOperation existing = tagOps.get(tagId);
          if (existing == null || op.priority < existing.priority) {
            tagOps.put(tagId, op);
          }
        }

        if (next == end) return; // Found the end, stop searching
      }
    }
  }

  /**
   * Build DFA from NFA with assertion support. Extracts fixed-width assertions from NFA and embeds
   * them in DFA states.
   *
   * @param nfa The NFA to convert
   * @return DFA with assertion annotations
   * @throws StateExplosionException if DFA has too many states (>300)
   * @throws UnsupportedOperationException if assertions are not fixed-width
   */
  public DFA buildDFAWithAssertions(NFA nfa) throws StateExplosionException {
    this.stateCache = new HashMap<>();
    this.allStates = new ArrayList<>();
    this.nextStateId = 0;

    // Pre-compute epsilon closures for all NFA states
    Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures = precomputeEpsilonClosures(nfa);

    // Extract assertions from NFA
    Map<NFA.NFAState, List<AssertionCheck>> assertionMap = extractAssertions(nfa);

    // Start with epsilon-closure of NFA start state
    Set<NFA.NFAState> startClosure = epsilonClosures.get(nfa.getStartState());
    boolean startAccepting = containsAcceptState(startClosure, nfa.getAcceptStates());
    DFA.DFAState start =
        createDFAStateWithAssertions(startClosure, assertionMap, nfa.getAcceptStates());
    stateCache.put(startClosure, start);
    allStates.add(start);

    Queue<DFA.DFAState> worklist = new ArrayDeque<>();
    worklist.add(start);

    while (!worklist.isEmpty()) {
      DFA.DFAState current = worklist.poll();

      // State explosion check (threshold: 300 states)
      if (allStates.size() > 300) {
        throw new StateExplosionException("DFA with assertions exceeded 300 states");
      }

      // Compute disjoint partition of outgoing character sets
      List<CharSet> partition = computeDisjointPartition(current.nfaStates);

      for (CharSet chars : partition) {
        // Find all NFA states reachable on this charset
        Set<NFA.NFAState> targets = new HashSet<>();
        for (NFA.NFAState nfaState : current.nfaStates) {
          for (NFA.Transition trans : nfaState.getTransitions()) {
            if (trans.chars.intersects(chars)) {
              // Add epsilon closure of target state
              targets.addAll(epsilonClosures.get(trans.target));
            }
          }
        }

        if (!targets.isEmpty()) {
          // Get or create DFA state for this target set
          DFA.DFAState targetState = stateCache.get(targets);
          if (targetState == null) {
            boolean accepting = containsAcceptState(targets, nfa.getAcceptStates());
            targetState =
                createDFAStateWithAssertions(targets, assertionMap, nfa.getAcceptStates());
            stateCache.put(targets, targetState);
            allStates.add(targetState);
            worklist.add(targetState);
          }

          // Add transition
          current.addTransition(chars, targetState);
        }
      }
    }

    // Collect accept states
    Set<DFA.DFAState> acceptStates =
        allStates.stream().filter(s -> s.accepting).collect(java.util.stream.Collectors.toSet());

    return new DFA(start, acceptStates, allStates);
  }

  /** Helper class to hold assertion extraction results. */
  private static class AssertionExtractionResult {
    final String literal; // Non-null if literal extraction succeeded
    final List<CharSet> charSets; // Non-null if charSet extraction succeeded
    final List<AssertionCheck.GroupCapture> groups;

    AssertionExtractionResult(String literal, List<AssertionCheck.GroupCapture> groups) {
      this.literal = literal;
      this.charSets = null;
      this.groups = groups;
    }

    AssertionExtractionResult(List<CharSet> charSets, List<AssertionCheck.GroupCapture> groups) {
      this.literal = null;
      this.charSets = charSets;
      this.groups = groups;
    }
  }

  /** Extract assertions from NFA states and convert to DFA assertion checks. */
  private Map<NFA.NFAState, List<AssertionCheck>> extractAssertions(NFA nfa) {
    Map<NFA.NFAState, List<AssertionCheck>> map = new HashMap<>();

    for (NFA.NFAState state : nfa.getStates()) {
      if (state.assertionType != null) {
        // Note: assertionWidth is -1 for lookaheads (width not computed)
        // For lookaheads, we determine if fixed-width by trying to extract literal

        // Try extracting with group tracking
        AssertionExtractionResult result = extractFromAssertion(state);
        AssertionCheck check;
        AssertionCheck.Type type = convertAssertionType(state.assertionType);

        if (result.literal != null) {
          // Simple literal assertion
          if (state.assertionWidth > 0 && state.assertionWidth != result.literal.length()) {
            throw new UnsupportedOperationException(
                "Lookbehind width mismatch: expected "
                    + state.assertionWidth
                    + " but literal is "
                    + result.literal.length());
          }
          check = new AssertionCheck(type, result.literal, 0, result.groups);
        } else if (result.charSets != null) {
          // Verify width matches for lookbehinds
          if (state.assertionWidth > 0 && state.assertionWidth != result.charSets.size()) {
            throw new UnsupportedOperationException(
                "Lookbehind width mismatch: expected "
                    + state.assertionWidth
                    + " but charSet sequence is "
                    + result.charSets.size());
          }
          check = new AssertionCheck(type, result.charSets, 0, result.groups);
        } else {
          throw new UnsupportedOperationException(
              "Complex assertion patterns not yet supported in DFA mode");
        }

        // Attach to epsilon targets
        for (NFA.NFAState target : state.getEpsilonTransitions()) {
          map.computeIfAbsent(target, k -> new ArrayList<>()).add(check);
        }
      }
    }

    return map;
  }

  /**
   * Extract content and groups from assertion's sub-NFA. Returns extraction result with either
   * literal or charSets (one will be non-null).
   */
  private AssertionExtractionResult extractFromAssertion(NFA.NFAState assertionState) {
    StringBuilder literal = new StringBuilder();
    List<CharSet> charSets = new ArrayList<>();
    List<AssertionCheck.GroupCapture> groups = new ArrayList<>();
    boolean isLiteral = true;

    NFA.NFAState current = assertionState.assertionStartState;
    int position = 0; // Current position in assertion content

    // Track open groups: groupNumber -> startPosition
    Map<Integer, Integer> openGroups = new HashMap<>();

    int iterations = 0;
    while (current != null && !assertionState.assertionAcceptStates.contains(current)) {
      iterations++;
      if (iterations > 100) {
        return new AssertionExtractionResult((String) null, Collections.emptyList());
      }

      // Check for group enter/exit
      if (current.enterGroup != null) {
        openGroups.put(current.enterGroup, position);
      }
      if (current.exitGroup != null) {
        Integer startPos = openGroups.remove(current.exitGroup);
        if (startPos != null) {
          int length = position - startPos;
          groups.add(new AssertionCheck.GroupCapture(current.exitGroup, startPos, length));
        }
      }

      List<NFA.Transition> transitions = current.getTransitions();
      List<NFA.NFAState> epsilonTransitions = current.getEpsilonTransitions();

      if (transitions.size() == 1) {
        // Follow character transition
        NFA.Transition trans = transitions.get(0);
        charSets.add(trans.chars);

        if (trans.chars.isSingleChar()) {
          literal.append(trans.chars.getSingleChar());
        } else {
          isLiteral = false;
        }

        position++;
        current = trans.target;
      } else if (transitions.size() == 0 && epsilonTransitions.size() == 1) {
        // Follow epsilon transition (no character consumed)
        current = epsilonTransitions.get(0);
      } else {
        // Branching or empty - not a simple sequence
        return new AssertionExtractionResult((String) null, Collections.emptyList());
      }
    }

    // Check for group exit at accept state
    if (current != null) {
      if (current.exitGroup != null) {
        Integer startPos = openGroups.remove(current.exitGroup);
        if (startPos != null) {
          int length = position - startPos;
          groups.add(new AssertionCheck.GroupCapture(current.exitGroup, startPos, length));
        }
      }
    }

    if (isLiteral && literal.length() > 0) {
      return new AssertionExtractionResult(literal.toString(), groups);
    } else if (!charSets.isEmpty()) {
      return new AssertionExtractionResult(charSets, groups);
    } else {
      return new AssertionExtractionResult((String) null, Collections.emptyList());
    }
  }

  /**
   * Extract literal string from assertion's sub-NFA. Returns null if not a simple literal pattern.
   */
  private String extractLiteralFromAssertion(NFA.NFAState assertionState) {
    StringBuilder literal = new StringBuilder();
    NFA.NFAState current = assertionState.assertionStartState;

    int iterations = 0;
    while (current != null && !assertionState.assertionAcceptStates.contains(current)) {
      iterations++;
      if (iterations > 100) {
        return null; // Likely infinite loop
      }

      List<NFA.Transition> transitions = current.getTransitions();
      List<NFA.NFAState> epsilonTransitions = current.getEpsilonTransitions();

      if (transitions.size() == 1) {
        // Follow character transition
        NFA.Transition trans = transitions.get(0);
        if (!trans.chars.isSingleChar()) {
          return null; // Not a literal character
        }

        literal.append(trans.chars.getSingleChar());
        current = trans.target;
      } else if (transitions.size() == 0 && epsilonTransitions.size() == 1) {
        // Follow epsilon transition (no character consumed)
        current = epsilonTransitions.get(0);
      } else {
        return null; // Not a simple literal
      }
    }

    return literal.toString();
  }

  /**
   * Extract character class sequence from assertion's sub-NFA. Returns null if not a simple
   * fixed-width character class sequence. Supports patterns like [A-Z], \d, [0-9][a-f], etc.
   */
  private List<CharSet> extractCharSetSequenceFromAssertion(NFA.NFAState assertionState) {
    List<CharSet> charSets = new ArrayList<>();
    NFA.NFAState current = assertionState.assertionStartState;

    int iterations = 0;
    while (current != null && !assertionState.assertionAcceptStates.contains(current)) {
      iterations++;
      if (iterations > 100) {
        return null; // Likely infinite loop
      }

      List<NFA.Transition> transitions = current.getTransitions();
      List<NFA.NFAState> epsilonTransitions = current.getEpsilonTransitions();

      if (transitions.size() == 1) {
        // Follow character transition
        NFA.Transition trans = transitions.get(0);
        charSets.add(trans.chars);
        current = trans.target;
      } else if (transitions.size() == 0 && epsilonTransitions.size() == 1) {
        // Follow epsilon transition (no character consumed)
        current = epsilonTransitions.get(0);
      } else {
        return null; // Branching or empty - not a simple sequence
      }
    }

    return charSets.isEmpty() ? null : charSets;
  }

  /** Convert NFA assertion type to DFA assertion type. */
  private AssertionCheck.Type convertAssertionType(NFA.AssertionType nfaType) {
    switch (nfaType) {
      case POSITIVE_LOOKAHEAD:
        return AssertionCheck.Type.POSITIVE_LOOKAHEAD;
      case NEGATIVE_LOOKAHEAD:
        return AssertionCheck.Type.NEGATIVE_LOOKAHEAD;
      case POSITIVE_LOOKBEHIND:
        return AssertionCheck.Type.POSITIVE_LOOKBEHIND;
      case NEGATIVE_LOOKBEHIND:
        return AssertionCheck.Type.NEGATIVE_LOOKBEHIND;
      default:
        throw new IllegalArgumentException("Unknown assertion type: " + nfaType);
    }
  }

  /** Create DFA state with assertion annotations and group actions. */
  private DFA.DFAState createDFAStateWithAssertions(
      Set<NFA.NFAState> nfaStates,
      Map<NFA.NFAState, List<AssertionCheck>> assertionMap,
      Set<NFA.NFAState> acceptStates) {

    List<AssertionCheck> assertions = new ArrayList<>();
    List<DFA.GroupAction> groupActions = computeGroupActions(nfaStates);

    DFA.DFAState dfaState =
        new DFA.DFAState(
            nextStateId++,
            nfaStates,
            containsAcceptState(nfaStates, acceptStates),
            assertions,
            groupActions);

    // Aggregate assertions from all NFA states
    for (NFA.NFAState nfaState : nfaStates) {
      List<AssertionCheck> checks = assertionMap.get(nfaState);
      if (checks != null) {
        assertions.addAll(checks);
      }
    }

    return dfaState;
  }
}
