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
  private boolean anchorConditionDiluted;

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
    this.anchorConditionDiluted = false;

    // Pre-compute anchor-aware epsilon closures for all NFA states. Each entry maps a reachable
    // NFA state to the weakest conjunction of anchors that must hold at the current input
    // position for that state to be live.
    Map<NFA.NFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> anchoredClosures =
        precomputeAnchoredClosures(nfa);

    // Start with anchored epsilon-closure of NFA start state
    Map<NFA.NFAState, EnumSet<NFA.AnchorType>> startClosure =
        anchoredClosures.get(nfa.getStartState());
    Set<NFA.NFAState> startClosureSet = startClosure.keySet();
    List<DFA.GroupAction> startGroupActions = computeGroupActions(startClosureSet);
    EnumSet<NFA.AnchorType> startAcceptConditions =
        computeAcceptanceConditions(startClosure, nfa.getAcceptStates());
    boolean startAccepting =
        containsAcceptState(startClosureSet, nfa.getAcceptStates())
            || !startAcceptConditions.isEmpty();
    DFA.DFAState start =
        new DFA.DFAState(
            nextStateId++,
            startClosureSet,
            startAccepting,
            new ArrayList<>(),
            startGroupActions,
            startAcceptConditions);
    stateCache.put(startClosureSet, start);
    allStates.add(start);

    Queue<DFA.DFAState> worklist = new ArrayDeque<>();
    worklist.add(start);
    // Per-DFA-state anchor conditions, mirroring DFAState.nfaStates set membership.
    Map<DFA.DFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> dfaStateConditions =
        new HashMap<>();
    dfaStateConditions.put(start, startClosure);

    while (!worklist.isEmpty()) {
      DFA.DFAState current = worklist.poll();
      Map<NFA.NFAState, EnumSet<NFA.AnchorType>> currentConditions =
          dfaStateConditions.get(current);

      // Compute disjoint partition of outgoing character sets
      List<CharSet> partition = computeDisjointPartition(current.nfaStates);

      for (CharSet chars : partition) {
        // Find all NFA states reachable on this charset, along with the weakest anchor
        // condition required at the *source* position to take any contributing transition.
        Map<NFA.NFAState, EnumSet<NFA.AnchorType>> targetsWithCond = new HashMap<>();
        EnumSet<NFA.AnchorType> transitionGuard = null; // weakest across contributing sources
        boolean transitionHasContributor = false;
        boolean anyNonEmptySrcCond = false;
        for (NFA.NFAState nfaState : current.nfaStates) {
          EnumSet<NFA.AnchorType> srcCond = currentConditions.get(nfaState);
          if (srcCond == null) continue; // unreachable
          // END-class anchors require pos == length; they cannot gate a consuming transition.
          if (containsConsumeKillingAnchor(srcCond)) continue;
          for (NFA.Transition trans : nfaState.getTransitions()) {
            if (trans.chars.intersects(chars)) {
              transitionHasContributor = true;
              if (!srcCond.isEmpty()) anyNonEmptySrcCond = true;
              transitionGuard = mergeWeakest(transitionGuard, srcCond);
              // After consuming a char, prior conditions are discharged. The post-consume
              // closure carries its own conditions starting from the transition target.
              Map<NFA.NFAState, EnumSet<NFA.AnchorType>> postClosure =
                  anchoredClosures.get(trans.target);
              for (Map.Entry<NFA.NFAState, EnumSet<NFA.AnchorType>> e : postClosure.entrySet()) {
                targetsWithCond.merge(
                    e.getKey(), EnumSet.copyOf(e.getValue()), SubsetConstructor::mergeWeakestInto);
              }
            }
          }
        }

        if (!transitionHasContributor || targetsWithCond.isEmpty()) continue;
        if (transitionGuard == null) transitionGuard = EnumSet.noneOf(NFA.AnchorType.class);
        // Anchor dilution: an unconditional contributor erased a non-empty anchor guard.
        if (transitionGuard.isEmpty() && anyNonEmptySrcCond) anchorConditionDiluted = true;

        Set<NFA.NFAState> targets = targetsWithCond.keySet();

        // Get or create DFA state
        DFA.DFAState target = stateCache.get(targets);
        if (target == null) {
          EnumSet<NFA.AnchorType> targetAcceptConditions =
              computeAcceptanceConditions(targetsWithCond, nfa.getAcceptStates());
          boolean accepting =
              containsAcceptState(targets, nfa.getAcceptStates())
                  || !targetAcceptConditions.isEmpty();
          List<DFA.GroupAction> groupActions = computeGroupActions(targets);
          target =
              new DFA.DFAState(
                  nextStateId++,
                  targets,
                  accepting,
                  new ArrayList<>(),
                  groupActions,
                  targetAcceptConditions);
          stateCache.put(targets, target);
          allStates.add(target);
          dfaStateConditions.put(target, targetsWithCond);
          worklist.add(target);
        }

        // Compute tag operations if requested (Tagged DFA)
        if (computeTags && nfa.getGroupCount() > 0) {
          List<DFA.TagOperation> tagOps =
              computeTagOperations(
                  current.nfaStates, targets, chars, flattenClosure(anchoredClosures));
          current.addTransition(chars, target, tagOps, transitionGuard);
        } else {
          current.addTransition(chars, target, Collections.emptyList(), transitionGuard);
        }
      }

      // Check state explosion (user said compile-time doesn't matter, but set reasonable limit)
      if (stateCache.size() > 10000) {
        throw new StateExplosionException("DFA has >10K states, use NFA instead");
      }
    }

    Set<DFA.DFAState> acceptStates =
        allStates.stream().filter(s -> s.accepting).collect(java.util.stream.Collectors.toSet());

    return new DFA(start, acceptStates, allStates, anchorConditionDiluted);
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
   * Pre-compute anchor-aware epsilon closures: for each NFA state, a map from each ε-reachable
   * state to the *weakest conjunction of anchor types* that must hold at the current input position
   * to live there. An empty {@link EnumSet} means unconditional reachability.
   */
  private Map<NFA.NFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> precomputeAnchoredClosures(
      NFA nfa) {
    Map<NFA.NFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> closures = new HashMap<>();
    for (NFA.NFAState state : nfa.getStates()) {
      closures.put(state, computeAnchoredEpsilonClosure(state));
    }
    return closures;
  }

  /**
   * Compute the anchor-aware ε-closure from {@code start}. When a state in the BFS frontier has
   * {@code anchor != null}, that anchor is added to the condition under which each ε-successor is
   * reachable. Multiple paths to the same state merge to the weakest conjunction (intersection).
   */
  private Map<NFA.NFAState, EnumSet<NFA.AnchorType>> computeAnchoredEpsilonClosure(
      NFA.NFAState start) {
    Map<NFA.NFAState, EnumSet<NFA.AnchorType>> result = new HashMap<>();
    result.put(start, EnumSet.noneOf(NFA.AnchorType.class));
    Deque<NFA.NFAState> worklist = new ArrayDeque<>();
    worklist.add(start);
    while (!worklist.isEmpty()) {
      NFA.NFAState current = worklist.poll();
      EnumSet<NFA.AnchorType> currentCond = result.get(current);
      EnumSet<NFA.AnchorType> propagated;
      if (current.anchor != null && isPositionAnchor(current.anchor)) {
        propagated = EnumSet.copyOf(currentCond);
        propagated.add(current.anchor);
      } else {
        propagated = currentCond;
      }
      for (NFA.NFAState target : current.getEpsilonTransitions()) {
        EnumSet<NFA.AnchorType> existing = result.get(target);
        if (existing == null) {
          result.put(target, EnumSet.copyOf(propagated));
          worklist.add(target);
        } else {
          // Weakest wins: intersection of existing and propagated. If that loosens the
          // requirement, store and re-propagate.
          EnumSet<NFA.AnchorType> merged = EnumSet.copyOf(existing);
          merged.retainAll(propagated);
          if (!merged.equals(existing)) {
            // Two non-empty but disjoint anchor sets meeting at the same state: their
            // intersection is empty (unconditional), erasing both anchors.
            if (merged.isEmpty() && !existing.isEmpty() && !propagated.isEmpty()) {
              anchorConditionDiluted = true;
            }
            result.put(target, merged);
            worklist.add(target);
          }
        }
      }
    }
    return result;
  }

  /**
   * Returns true if the given anchor type is one this fix knows how to gate at the DFA level. Word
   * boundaries and reset-match anchors are handled elsewhere; they are not treated as positional
   * gating here.
   */
  private static boolean isPositionAnchor(NFA.AnchorType type) {
    switch (type) {
      case START:
      case END:
      case START_MULTILINE:
      case END_MULTILINE:
      case STRING_START:
      case STRING_END:
      case STRING_END_ABSOLUTE:
        return true;
      case WORD_BOUNDARY:
      case RESET_MATCH:
      default:
        return false;
    }
  }

  /**
   * Returns true if any anchor in the set requires {@code pos == length} (or near-end), which makes
   * a consuming char-transition impossible. Used to prune dead transitions.
   */
  private static boolean containsConsumeKillingAnchor(EnumSet<NFA.AnchorType> conds) {
    return conds.contains(NFA.AnchorType.END) || conds.contains(NFA.AnchorType.STRING_END_ABSOLUTE);
    // Note: STRING_END (\Z) and END_MULTILINE allow consuming a final newline, but precise
    // handling there would require char-set intersection. Conservative pruning is safe for
    // the present scope; an extension can refine if needed.
  }

  /**
   * Compute weakest acceptance conditions across all accept NFA states in {@code closure}. Returns
   * an empty set if any accept state is unconditionally reachable; otherwise the weakest
   * single-conjunction condition. Callers treat empty as "unconditionally accepting".
   *
   * <p>Side effect: sets {@link #anchorConditionDiluted} when multiple accept states have non-empty
   * but disjoint conditions whose intersection collapses to empty.
   */
  private EnumSet<NFA.AnchorType> computeAcceptanceConditions(
      Map<NFA.NFAState, EnumSet<NFA.AnchorType>> closure, Set<NFA.NFAState> acceptStates) {
    EnumSet<NFA.AnchorType> best = null;
    for (NFA.NFAState s : closure.keySet()) {
      if (!acceptStates.contains(s)) continue;
      EnumSet<NFA.AnchorType> cond = closure.get(s);
      if (cond.isEmpty()) return EnumSet.noneOf(NFA.AnchorType.class);
      if (best == null) best = EnumSet.copyOf(cond);
      else best.retainAll(cond);
    }
    if (best != null && best.isEmpty()) {
      // All accept states had non-empty conditions, but they were disjoint — intersection
      // collapsed to empty (unconditional). The DFA would accept without checking any anchor.
      anchorConditionDiluted = true;
    }
    return best == null ? EnumSet.noneOf(NFA.AnchorType.class) : best;
  }

  /** Merge two weakest-condition values via intersection. */
  private static EnumSet<NFA.AnchorType> mergeWeakest(
      EnumSet<NFA.AnchorType> a, EnumSet<NFA.AnchorType> b) {
    if (a == null) return EnumSet.copyOf(b);
    if (b == null) return a;
    EnumSet<NFA.AnchorType> r = EnumSet.copyOf(a);
    r.retainAll(b);
    return r;
  }

  /** {@link Map#merge} remapping function for weakest-condition merging. */
  private static EnumSet<NFA.AnchorType> mergeWeakestInto(
      EnumSet<NFA.AnchorType> existing, EnumSet<NFA.AnchorType> incoming) {
    EnumSet<NFA.AnchorType> r = EnumSet.copyOf(existing);
    r.retainAll(incoming);
    return r;
  }

  /**
   * Flatten anchored-closure data structure back to the legacy {@code Map<NFAState, Set<NFAState>>}
   * shape consumed by tag-operation computation, which only cares about set membership, not anchor
   * conditions.
   */
  private static Map<NFA.NFAState, Set<NFA.NFAState>> flattenClosure(
      Map<NFA.NFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> anchored) {
    Map<NFA.NFAState, Set<NFA.NFAState>> flat = new HashMap<>();
    for (Map.Entry<NFA.NFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> e :
        anchored.entrySet()) {
      flat.put(e.getKey(), e.getValue().keySet());
    }
    return flat;
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
    this.anchorConditionDiluted = false;

    // Pre-compute anchor-aware epsilon closures
    Map<NFA.NFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> anchoredClosures =
        precomputeAnchoredClosures(nfa);

    // Extract assertions from NFA
    Map<NFA.NFAState, List<AssertionCheck>> assertionMap = extractAssertions(nfa);

    // Start with anchored epsilon-closure of NFA start state
    Map<NFA.NFAState, EnumSet<NFA.AnchorType>> startClosure =
        anchoredClosures.get(nfa.getStartState());
    Set<NFA.NFAState> startClosureSet = startClosure.keySet();
    DFA.DFAState start =
        createDFAStateWithAssertions(
            startClosureSet,
            assertionMap,
            nfa.getAcceptStates(),
            computeAcceptanceConditions(startClosure, nfa.getAcceptStates()));
    stateCache.put(startClosureSet, start);
    allStates.add(start);

    Queue<DFA.DFAState> worklist = new ArrayDeque<>();
    worklist.add(start);
    Map<DFA.DFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> dfaStateConditions =
        new HashMap<>();
    dfaStateConditions.put(start, startClosure);

    while (!worklist.isEmpty()) {
      DFA.DFAState current = worklist.poll();
      Map<NFA.NFAState, EnumSet<NFA.AnchorType>> currentConditions =
          dfaStateConditions.get(current);

      // State explosion check (threshold: 300 states)
      if (allStates.size() > 300) {
        throw new StateExplosionException("DFA with assertions exceeded 300 states");
      }

      // Compute disjoint partition of outgoing character sets
      List<CharSet> partition = computeDisjointPartition(current.nfaStates);

      for (CharSet chars : partition) {
        Map<NFA.NFAState, EnumSet<NFA.AnchorType>> targetsWithCond = new HashMap<>();
        EnumSet<NFA.AnchorType> transitionGuard = null;
        boolean hasContributor = false;
        boolean anyNonEmptySrcCond = false;
        for (NFA.NFAState nfaState : current.nfaStates) {
          EnumSet<NFA.AnchorType> srcCond = currentConditions.get(nfaState);
          if (srcCond == null) continue;
          if (containsConsumeKillingAnchor(srcCond)) continue;
          for (NFA.Transition trans : nfaState.getTransitions()) {
            if (trans.chars.intersects(chars)) {
              hasContributor = true;
              if (!srcCond.isEmpty()) anyNonEmptySrcCond = true;
              transitionGuard = mergeWeakest(transitionGuard, srcCond);
              for (Map.Entry<NFA.NFAState, EnumSet<NFA.AnchorType>> e :
                  anchoredClosures.get(trans.target).entrySet()) {
                targetsWithCond.merge(
                    e.getKey(), EnumSet.copyOf(e.getValue()), SubsetConstructor::mergeWeakestInto);
              }
            }
          }
        }

        if (!hasContributor || targetsWithCond.isEmpty()) continue;
        if (transitionGuard == null) transitionGuard = EnumSet.noneOf(NFA.AnchorType.class);
        if (transitionGuard.isEmpty() && anyNonEmptySrcCond) anchorConditionDiluted = true;

        Set<NFA.NFAState> targets = targetsWithCond.keySet();
        DFA.DFAState targetState = stateCache.get(targets);
        if (targetState == null) {
          targetState =
              createDFAStateWithAssertions(
                  targets,
                  assertionMap,
                  nfa.getAcceptStates(),
                  computeAcceptanceConditions(targetsWithCond, nfa.getAcceptStates()));
          stateCache.put(targets, targetState);
          allStates.add(targetState);
          dfaStateConditions.put(targetState, targetsWithCond);
          worklist.add(targetState);
        }
        current.addTransition(chars, targetState, Collections.emptyList(), transitionGuard);
      }
    }

    // Collect accept states
    Set<DFA.DFAState> acceptStates =
        allStates.stream().filter(s -> s.accepting).collect(java.util.stream.Collectors.toSet());

    return new DFA(start, acceptStates, allStates, anchorConditionDiluted);
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

        // Attach the assertion to the assertion NFA state itself so it fires only
        // in DFA states whose NFA closure directly contains this assertion state.
        // Attaching to epsilon targets would cause the assertion to re-fire in
        // later DFA states when those targets remain in the closure (e.g. via a
        // loop back), which is incorrect for both lookahead and lookbehind after
        // unbounded quantifiers.
        map.computeIfAbsent(state, k -> new ArrayList<>()).add(check);
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
    return createDFAStateWithAssertions(
        nfaStates, assertionMap, acceptStates, EnumSet.noneOf(NFA.AnchorType.class));
  }

  /** Create DFA state with assertion annotations, group actions, and acceptance anchor cond. */
  private DFA.DFAState createDFAStateWithAssertions(
      Set<NFA.NFAState> nfaStates,
      Map<NFA.NFAState, List<AssertionCheck>> assertionMap,
      Set<NFA.NFAState> acceptStates,
      EnumSet<NFA.AnchorType> acceptanceAnchorConditions) {

    List<AssertionCheck> assertions = new ArrayList<>();
    List<DFA.GroupAction> groupActions = computeGroupActions(nfaStates);

    boolean accepting =
        containsAcceptState(nfaStates, acceptStates) || !acceptanceAnchorConditions.isEmpty();
    DFA.DFAState dfaState =
        new DFA.DFAState(
            nextStateId++,
            nfaStates,
            accepting,
            assertions,
            groupActions,
            acceptanceAnchorConditions);

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
