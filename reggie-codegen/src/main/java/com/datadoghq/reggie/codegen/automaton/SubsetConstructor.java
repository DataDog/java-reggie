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

  /**
   * Characters that can appear immediately before an END/STRING_END anchor's match position and be
   * consumed by a following consumer: {@code \n}, {@code \r}, NEL (U+0085), LS (U+2028), PS
   * (U+2029). The {@code \r\n} two-char terminator is handled by the codegen guard (it checks
   * {@code pos == end-2} with {@code \r\n}). Used to narrow transition charsets that cross an
   * END/STRING_END guard — see {@link #narrowEndGuardedCharset}.
   */
  static final CharSet LINE_TERMINATORS =
      CharSet.of('\n')
          .union(CharSet.of('\r'))
          .union(CharSet.of('\u0085'))
          .union(CharSet.of('\u2028'))
          .union(CharSet.of('\u2029'));

  private Map<Set<NFA.NFAState>, DFA.DFAState> stateCache;
  private List<DFA.DFAState> allStates;
  private int nextStateId;
  private boolean anchorConditionDiluted;
  private boolean captureAmbiguous;
  // Maps each DFA state's NFA-state set to the priority-ordered list of those NFA states.
  // Lower index = higher priority (Perl thread semantics). Populated by buildDFA.
  private Map<Set<NFA.NFAState>, List<NFA.NFAState>> dfaStateOrdering;

  /**
   * Total determinization work charged so far by this constructor instance. One work unit ≈ one
   * innermost-loop iteration (transition visit, closure merge, charset collection, closure-edge
   * relaxation). Charging the innermost loops bounds total compile work even when DFA state count
   * stays under the state cap but per-state cost is quadratic (e.g. unrolled {n,m} quantifiers over
   * alternations).
   */
  private long determinizationWork;

  /**
   * Wall-clock deadline for a single determinization, in nanoseconds; 0 disables. Set when a
   * buildDFA* entry point starts. The deterministic work budget above bounds charged inner-loop
   * iterations, but uncharged or allocation-dominated paths (and OutOfMemoryError-prone state sets)
   * can still exceed any unit budget — the deadline is the backstop that guarantees bounded compile
   * time regardless of where the time goes.
   */
  private long determinizationDeadlineNanos;

  /**
   * Default wall-clock budget for a single determinization. Generous enough for legitimate large
   * patterns (the ~300-char semver pattern with {0,256} quantifiers determinizes in well under a
   * second after the flattenClosure/memoization fixes) while keeping adversarial patterns bounded.
   * Override via -Dreggie.dfa.deadlineMs (0 disables).
   */
  static final long DETERMINIZATION_DEADLINE_MS = Long.getLong("reggie.dfa.deadlineMs", 10_000L);

  /**
   * Compile-scope total deadline (absolute nanos, 0 = none), set by RuntimeCompiler for the
   * duration of a compile and cleared afterwards. Each determinization clamps its own per-pass
   * deadline to the remaining total, so a tight total deadline (e.g. 2s for request-driven
   * untrusted patterns) actually binds instead of being defeated by the 10s per-pass default. The
   * clamp only ever TIGHTENS: without a total deadline set, behavior is unchanged.
   */
  public static final ThreadLocal<Long> TOTAL_COMPILE_DEADLINE_NANOS = new ThreadLocal<>();

  /** NanoTime sampled at determinization start; re-sampled only every 64K work units. */
  private long lastDeadlineCheckNanos;

  /**
   * Charges {@code units} of determinization work and throws {@link StateExplosionException} if the
   * cumulative budget is exceeded, or if the wall-clock deadline has passed (checked periodically
   * to keep the check cheap).
   */
  private void chargeWork(long units) throws StateExplosionException {
    determinizationWork += units;
    if (determinizationWork > DETERMINIZATION_WORK_BUDGET) {
      throw new StateExplosionException(
          "DFA determinization exceeded work budget ("
              + DETERMINIZATION_WORK_BUDGET
              + " units); pattern is too expensive to determinize — use an NFA strategy");
    }
    if (determinizationDeadlineNanos > 0
        && (determinizationWork & 0xFFFF) == 0) { // every 64K units
      long now = System.nanoTime();
      if (now - lastDeadlineCheckNanos > 1_000_000L) { // avoid double-sampling noise
        lastDeadlineCheckNanos = now;
        if (now > determinizationDeadlineNanos) {
          throw new StateExplosionException(
              "DFA determinization exceeded time budget ("
                  + DETERMINIZATION_DEADLINE_MS
                  + " ms); pattern is too expensive to determinize — use an NFA strategy");
        }
      }
    }
  }

  /**
   * Work budget for a single determinization. Exceeding it throws {@link StateExplosionException},
   * which callers already treat as "use an NFA strategy instead". Calibrated so that legitimate
   * large patterns (e.g. the ~300-char semver pattern with {0,256} bounded quantifiers) determinize
   * successfully while adversarial unrolled-quantifier bombs abort in well under a second. Override
   * for tests/benchmarks via -Dreggie.dfa.workBudget=<n>.
   */
  static final long DETERMINIZATION_WORK_BUDGET =
      Long.getLong("reggie.dfa.workBudget", 200_000_000L);

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
    this.stateCache = new LinkedHashMap<>();
    this.allStates = new ArrayList<>();
    this.nextStateId = 0;
    this.anchorConditionDiluted = false;
    this.captureAmbiguous = false;
    this.dfaStateOrdering = new LinkedHashMap<>();
    this.determinizationWork = 0;
    this.lastDeadlineCheckNanos = System.nanoTime();
    this.determinizationDeadlineNanos =
        DETERMINIZATION_DEADLINE_MS > 0
            ? lastDeadlineCheckNanos + DETERMINIZATION_DEADLINE_MS * 1_000_000L
            : 0L;
    // Clamp to the compile-scope total deadline's remaining time (if one is set): the per-pass
    // deadline only ever tightens, never extends.
    Long totalDeadline = TOTAL_COMPILE_DEADLINE_NANOS.get();
    if (totalDeadline != null && totalDeadline > 0) {
      if (determinizationDeadlineNanos == 0L || totalDeadline < determinizationDeadlineNanos) {
        determinizationDeadlineNanos = totalDeadline;
      }
    }
    try {
      return buildDFAInternal(nfa, computeTags);
    } catch (OutOfMemoryError oom) {
      // The partially-built DFA graph is garbage on unwind; convert the resource exhaustion into
      // the same graceful explosion path the state/work caps use so callers fall back to NFA
      // strategies instead of taking down the host JVM.
      throw new StateExplosionException(
          "DFA determinization exhausted memory (NFA too large to determinize); pattern is too"
              + " expensive to determinize — use an NFA strategy");
    }
  }

  private DFA buildDFAInternal(NFA nfa, boolean computeTags) throws StateExplosionException {

    // Pre-compute anchor-aware epsilon closures for all NFA states. Each entry maps a reachable
    // NFA state to the weakest conjunction of anchors that must hold at the current input
    // position for that state to be live.
    Map<NFA.NFAState, Map<NFA.NFAState, EnumSet<NFA.AnchorType>>> anchoredClosures =
        precomputeAnchoredClosures(nfa);

    // Pre-compute flat epsilon closures (for group-bypass reachability analysis)
    Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures = precomputeEpsilonClosures(nfa);

    // Flatten the anchored closures ONCE. flattenClosure is a pure function of
    // anchoredClosures; it was previously invoked inside the per-(DFA-state, charset)
    // transition loop, rebuilding the entire flattened structure on every transition.
    // With many unrolled {n,m} quantifiers (e.g. the semver pattern) that is
    // O(NFA states × closure size) per transition and dominates compile time.
    Map<NFA.NFAState, Set<NFA.NFAState>> flatAnchoredClosures = flattenClosure(anchoredClosures);

    // For each capturing group g, determine whether there is a path from NFA start to any
    // NFA accept state that does NOT pass through group g's enter marker.  If such a bypass
    // exists, a DFA state that ALSO contains an exit_g NFA state (from the group-taking
    // thread) is capture-ambiguous: computeGroupActions will record g's bounds from the
    // group-taking thread even when the priority-winning thread bypassed it.
    Set<Integer> groupsWithBypass = computeGroupsWithBypass(nfa, epsilonClosures);

    // Start with anchored epsilon-closure of NFA start state
    Map<NFA.NFAState, EnumSet<NFA.AnchorType>> startClosure =
        anchoredClosures.get(nfa.getStartState());
    Set<NFA.NFAState> startClosureSet = startClosure.keySet();
    List<DFA.GroupAction> startGroupActions =
        computeGroupActions(startClosureSet, nfa.getAcceptStates());
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
    if (!captureAmbiguous) {
      captureAmbiguous = hasCaptureAmbiguity(startClosureSet, groupsWithBypass);
    }
    stateCache.put(startClosureSet, start);
    allStates.add(start);
    dfaStateOrdering.put(
        startClosureSet, computeInitialOrdering(nfa.getStartState(), startClosureSet));
    if (startAccepting) {
      List<NFA.NFAState> startOrdering = dfaStateOrdering.get(startClosureSet);
      start.acceptIsPriorityCut =
          computeAcceptIsPriorityCut(
              startClosureSet, startClosure, startOrdering, nfa.getAcceptStates());
      start.hasPriorityConflictTransition =
          computeHasPriorityConflictTransition(
              startClosureSet, startClosure, startOrdering, nfa.getAcceptStates());
    }

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
      List<NFA.NFAState> currentOrdering = dfaStateOrdering.get(current.nfaStates);

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
          if (containsConsumeKillingAnchor(srcCond, chars)) continue;
          for (NFA.Transition trans : nfaState.getTransitions()) {
            chargeWork(1);
            if (trans.chars.intersects(chars)) {
              transitionHasContributor = true;
              if (!srcCond.isEmpty()) anyNonEmptySrcCond = true;
              transitionGuard = mergeWeakest(transitionGuard, srcCond);
              // After consuming a char, prior conditions are discharged. The post-consume
              // closure carries its own conditions starting from the transition target.
              Map<NFA.NFAState, EnumSet<NFA.AnchorType>> postClosure =
                  anchoredClosures.get(trans.target);
              for (Map.Entry<NFA.NFAState, EnumSet<NFA.AnchorType>> e : postClosure.entrySet()) {
                chargeWork(1);
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
          List<DFA.GroupAction> groupActions = computeGroupActions(targets, nfa.getAcceptStates());
          target =
              new DFA.DFAState(
                  nextStateId++,
                  targets,
                  accepting,
                  new ArrayList<>(),
                  groupActions,
                  targetAcceptConditions);
          if (accepting && !captureAmbiguous) {
            captureAmbiguous = hasCaptureAmbiguity(targets, groupsWithBypass);
          }
          stateCache.put(targets, target);
          allStates.add(target);
          dfaStateConditions.put(target, targetsWithCond);
          dfaStateOrdering.put(targets, computeTransitionOrdering(currentOrdering, targets, chars));
          if (accepting) {
            List<NFA.NFAState> targetOrdering = dfaStateOrdering.get(targets);
            target.acceptIsPriorityCut =
                computeAcceptIsPriorityCut(
                    targets, targetsWithCond, targetOrdering, nfa.getAcceptStates());
            target.hasPriorityConflictTransition =
                computeHasPriorityConflictTransition(
                    targets, targetsWithCond, targetOrdering, nfa.getAcceptStates());
          }
          worklist.add(target);
        }

        // Narrow charset for END/STRING_END-guarded consuming transitions to line terminators.
        CharSet narrowedChars = narrowEndGuardedCharset(transitionGuard, chars);
        if (narrowedChars == null) continue;

        // Compute tag operations if requested (Tagged DFA)
        if (computeTags && nfa.getGroupCount() > 0) {
          List<DFA.TagOperation> tagOps =
              computeTagOperations(
                  current.nfaStates,
                  targets,
                  narrowedChars,
                  flatAnchoredClosures,
                  nfa.getAcceptStates(),
                  target.acceptanceAnchorConditions);
          current.addTransition(narrowedChars, target, tagOps, transitionGuard);
        } else {
          current.addTransition(narrowedChars, target, Collections.emptyList(), transitionGuard);
        }
      }

      // Check state explosion (user said compile-time doesn't matter, but set reasonable limit)
      if (stateCache.size() > 10000) {
        throw new StateExplosionException("DFA has >10K states, use NFA instead");
      }
    }

    Set<DFA.DFAState> acceptStates =
        allStates.stream().filter(s -> s.accepting).collect(java.util.stream.Collectors.toSet());

    return new DFA(start, acceptStates, allStates, anchorConditionDiluted, captureAmbiguous);
  }

  /**
   * Pre-compute epsilon closures for all NFA states. This is done once at DFA construction time,
   * not at runtime.
   */

  /**
   * Returns the epsilon-closure of {@code start} as a priority-ordered list. Thread priority is
   * encoded in epsilon insertion order (see NFA.NFAState.getEpsilonTransitions): the first
   * occurrence of a state wins; lower-index = higher priority (PikeVM rule). Used by the PikeVM
   * capture generator and the priority-correct TDFA rework.
   */
  List<NFA.NFAState> orderedEpsilonClosure(NFA.NFAState start) {
    Set<NFA.NFAState> seen = new LinkedHashSet<>();
    orderedEpsilonClosureDfs(start, seen);
    return new ArrayList<>(seen);
  }

  private void orderedEpsilonClosureDfs(NFA.NFAState current, Set<NFA.NFAState> seen) {
    if (!seen.add(current)) return; // already visited (first occurrence wins)
    for (NFA.NFAState next : current.getEpsilonTransitions()) {
      orderedEpsilonClosureDfs(next, seen);
    }
  }

  /**
   * Computes the priority-ordered NFA state list for the initial DFA state. Filters the ordered
   * epsilon closure of the NFA start state to those NFA states that are actually in startClosure
   * (anchored closure may exclude some).
   */
  private List<NFA.NFAState> computeInitialOrdering(
      NFA.NFAState nfaStart, Set<NFA.NFAState> startClosure) {
    List<NFA.NFAState> ordered = orderedEpsilonClosure(nfaStart);
    List<NFA.NFAState> result = new ArrayList<>();
    for (NFA.NFAState s : ordered) {
      if (startClosure.contains(s)) result.add(s);
    }
    return result;
  }

  /**
   * Derives the priority-ordered NFA state list for a target DFA state, given the source DFA
   * state's ordered list and the character set of the transition. For each source NFA state in
   * priority order, follows character transitions that intersect {@code chars}, computes the
   * ordered epsilon closure of each transition target, and appends new states (deduplicating,
   * first-occurrence-wins) that are in {@code targetSet}.
   */
  private List<NFA.NFAState> computeTransitionOrdering(
      List<NFA.NFAState> sourceOrdering, Set<NFA.NFAState> targetSet, CharSet chars) {
    Set<NFA.NFAState> seen = new LinkedHashSet<>();
    for (NFA.NFAState source : sourceOrdering) {
      for (NFA.Transition t : source.getTransitions()) {
        if (!t.chars.intersects(chars)) continue;
        for (NFA.NFAState reachable : orderedEpsilonClosure(t.target)) {
          if (targetSet.contains(reachable)) seen.add(reachable);
        }
      }
    }
    return new ArrayList<>(seen);
  }

  /**
   * Returns true when the accepting DFA state contains an NFA state with consuming transitions
   * whose rank is strictly greater than the minimum accept rank. Such a lower-priority consuming
   * thread can fire in the DFA and select a longer, lower-priority match over the accept — which is
   * wrong. Patterns with such states must be declined when acceptIsPriorityCut is false.
   */
  /**
   * Returns the minimum rank of NFA accept states that are either unconditionally reachable or
   * reachable under START-class anchor conditions (START / STRING_START / START_MULTILINE). Such
   * states DO fire at the match-start position where the greedy scan begins.
   *
   * <p>Accept states conditioned exclusively on END-class anchors (END / STRING_END /
   * STRING_END_ABSOLUTE / END_MULTILINE) are excluded: they fire only at end-of-input and should
   * not drive the priority-cut decision at intermediate positions during the scan.
   */
  private int acceptRankForPriorityCut(
      Set<NFA.NFAState> nfaStates,
      Map<NFA.NFAState, EnumSet<NFA.AnchorType>> conds,
      Map<NFA.NFAState, Integer> rankMap,
      Set<NFA.NFAState> nfaAcceptStates) {
    int minRank = Integer.MAX_VALUE;
    for (NFA.NFAState s : nfaStates) {
      if (!nfaAcceptStates.contains(s)) continue;
      EnumSet<NFA.AnchorType> c = conds != null ? conds.get(s) : null;
      if (c != null && !c.isEmpty() && isEndClassOnly(c)) continue; // END-class only — skip
      minRank = Math.min(minRank, rankMap.getOrDefault(s, Integer.MAX_VALUE));
    }
    return minRank;
  }

  /**
   * Returns true when every anchor in the set is an END-class anchor (fires only at end-of-input).
   */
  private static boolean isEndClassOnly(EnumSet<NFA.AnchorType> anchors) {
    for (NFA.AnchorType a : anchors) {
      switch (a) {
        case END:
        case STRING_END:
        case STRING_END_ABSOLUTE:
        case END_MULTILINE:
          break; // END-class: OK
        default:
          return false; // contains a non-END-class anchor
      }
    }
    return true;
  }

  private boolean computeHasPriorityConflictTransition(
      Set<NFA.NFAState> nfaStates,
      Map<NFA.NFAState, EnumSet<NFA.AnchorType>> stateConditions,
      List<NFA.NFAState> ordering,
      Set<NFA.NFAState> nfaAcceptStates) {
    if (ordering == null || ordering.isEmpty()) return false;
    Map<NFA.NFAState, Integer> rankMap = buildRankMap(ordering);
    int acceptRank = acceptRankForPriorityCut(nfaStates, stateConditions, rankMap, nfaAcceptStates);
    if (acceptRank == Integer.MAX_VALUE) return false;

    for (NFA.NFAState s : nfaStates) {
      if (!s.getTransitions().isEmpty()) {
        if (rankMap.getOrDefault(s, Integer.MAX_VALUE) > acceptRank) return true;
      }
    }
    return false;
  }

  /**
   * Computes whether an accepting DFA state is a priority-cut state. Only unconditionally reachable
   * accept states are considered as the reference rank — conditionally reachable accepts (e.g. via
   * `$`) may not fire at the current position and must not drive the cut decision.
   *
   * <p>True iff the lowest rank unconditional accept NFA state has rank ≤ the lowest rank NFA state
   * with a consuming out-transition. When true the executor must commit immediately.
   */
  private boolean computeAcceptIsPriorityCut(
      Set<NFA.NFAState> nfaStates,
      Map<NFA.NFAState, EnumSet<NFA.AnchorType>> stateConditions,
      List<NFA.NFAState> ordering,
      Set<NFA.NFAState> nfaAcceptStates) {
    if (ordering == null || ordering.isEmpty()) return false;
    Map<NFA.NFAState, Integer> rankMap = buildRankMap(ordering);
    int acceptRank = acceptRankForPriorityCut(nfaStates, stateConditions, rankMap, nfaAcceptStates);
    if (acceptRank == Integer.MAX_VALUE) return false;

    int continueRank = Integer.MAX_VALUE;
    for (NFA.NFAState s : nfaStates) {
      if (!s.getTransitions().isEmpty()) {
        continueRank = Math.min(continueRank, rankMap.getOrDefault(s, Integer.MAX_VALUE));
      }
    }
    return acceptRank <= continueRank;
  }

  /** Returns a map from NFA state to its priority rank (index) in the given ordered list. */
  static Map<NFA.NFAState, Integer> buildRankMap(List<NFA.NFAState> ordered) {
    Map<NFA.NFAState, Integer> ranks = new HashMap<>();
    for (int i = 0; i < ordered.size(); i++) {
      ranks.put(ordered.get(i), i);
    }
    return ranks;
  }

  /**
   * Returns the priority-ordered NFA state list for the given DFA state's NFA set, as computed
   * during DFA construction. Lower index = higher priority. Returns null if not known.
   */
  public List<NFA.NFAState> getOrdering(Set<NFA.NFAState> nfaStates) {
    return dfaStateOrdering != null ? dfaStateOrdering.get(nfaStates) : null;
  }

  private Map<NFA.NFAState, Set<NFA.NFAState>> precomputeEpsilonClosures(NFA nfa)
      throws StateExplosionException {
    Map<NFA.NFAState, Set<NFA.NFAState>> closures = new HashMap<>();

    for (NFA.NFAState state : nfa.getStates()) {
      Set<NFA.NFAState> closure = new HashSet<>();
      computeEpsilonClosure(state, closure);
      closures.put(state, closure);
    }

    return closures;
  }

  /** Compute epsilon closure of a single state using worklist algorithm. */
  private void computeEpsilonClosure(NFA.NFAState start, Set<NFA.NFAState> closure)
      throws StateExplosionException {
    Stack<NFA.NFAState> worklist = new Stack<>();
    worklist.push(start);
    closure.add(start);

    while (!worklist.isEmpty()) {
      NFA.NFAState current = worklist.pop();
      for (NFA.NFAState target : current.getEpsilonTransitions()) {
        chargeWork(1);
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
      NFA nfa) throws StateExplosionException {
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
      NFA.NFAState start) throws StateExplosionException {
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
        chargeWork(1);
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
   * Returns true if the anchor conditions in {@code conds} prevent consuming any character in
   * {@code chars}.
   *
   * <ul>
   *   <li>{@code STRING_END_ABSOLUTE} (\z) requires {@code pos == length} exactly, so no char can
   *       be consumed.
   *   <li>{@code END} ($) and {@code STRING_END} (\Z) match at {@code pos == length} OR at {@code
   *       pos == length-1} when {@code charAt(pos) == '\n'}. They allow consuming a {@code \n}-only
   *       transition (the "$ before terminal newline" path) but block everything else.
   * </ul>
   */
  private static boolean containsConsumeKillingAnchor(
      EnumSet<NFA.AnchorType> conds, CharSet chars) {
    if (conds.contains(NFA.AnchorType.STRING_END_ABSOLUTE)) return true;
    // END/STRING_END: the transition charset is narrowed to line terminators after the
    // partition loop (see narrowEndGuardedCharset), so we no longer kill here.
    return false;
  }

  /**
   * Narrow a transition charset when the guard contains END/STRING_END. A consuming transition that
   * crosses an END/STRING_END anchor can only fire for line terminators at the end of input (the "$
   * before terminal line terminator" path). Narrowing the charset to line terminators prevents the
   * transition from firing for non-line-terminator chars, which the codegen guard would reject
   * anyway. Returns {@code null} if the narrowed charset is empty (no valid consuming transition
   * exists for this anchor).
   */
  private static CharSet narrowEndGuardedCharset(EnumSet<NFA.AnchorType> guard, CharSet chars) {
    if (!guard.contains(NFA.AnchorType.END) && !guard.contains(NFA.AnchorType.STRING_END)) {
      return chars;
    }
    CharSet narrowed = chars.intersection(LINE_TERMINATORS);
    return narrowed.isEmpty() ? null : narrowed;
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
  private List<CharSet> computeDisjointPartition(Set<NFA.NFAState> states)
      throws StateExplosionException {
    // Collect all character sets from outgoing transitions
    List<CharSet> allCharSets = new ArrayList<>();
    for (NFA.NFAState state : states) {
      for (NFA.Transition trans : state.getTransitions()) {
        chargeWork(1);
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

  /** Legacy overload: no accept-state filter (used by buildDFAWithAssertions). */
  private List<DFA.GroupAction> computeGroupActions(Set<NFA.NFAState> nfaStates) {
    return computeGroupActions(nfaStates, Collections.emptySet());
  }

  /**
   * Compute group actions for a DFA state using priority-rank selection. Deduplicates by (groupId,
   * actionType) keeping the marker from the highest-priority thread (lowest rank in the ordered
   * closure). C2.4: at accepting DFA states, markers from threads with rank lower than the NFA
   * accept state's rank are excluded — they lost the acceptance race.
   */
  private List<DFA.GroupAction> computeGroupActions(
      Set<NFA.NFAState> nfaStates, Set<NFA.NFAState> nfaAcceptStates) {
    List<NFA.NFAState> ordered =
        (dfaStateOrdering != null)
            ? dfaStateOrdering.getOrDefault(nfaStates, Collections.emptyList())
            : Collections.emptyList();
    Map<NFA.NFAState, Integer> rankMap = buildRankMap(ordered);

    // C2.4: find the lowest rank (highest priority) NFA accept state in this DFA state.
    int minAcceptRank = Integer.MAX_VALUE;
    if (!nfaAcceptStates.isEmpty()) {
      for (NFA.NFAState s : nfaStates) {
        if (nfaAcceptStates.contains(s)) {
          minAcceptRank = Math.min(minAcceptRank, rankMap.getOrDefault(s, Integer.MAX_VALUE));
        }
      }
    }

    Map<String, DFA.GroupAction> deduped = new HashMap<>();
    Map<String, Integer> dedupedRanks = new HashMap<>();
    for (NFA.NFAState nfaState : nfaStates) {
      int rank = rankMap.getOrDefault(nfaState, Integer.MAX_VALUE);
      if (minAcceptRank < Integer.MAX_VALUE && rank < minAcceptRank)
        continue; // C2.4: thread lost acceptance race
      if (nfaState.enterGroup != null) {
        String key = nfaState.enterGroup + ":ENTER";
        Integer existingRank = dedupedRanks.get(key);
        if (existingRank == null || rank < existingRank) {
          // A group is zero-width (epsilonGroup=true) when its EXIT NFA state is epsilon-reachable
          // from the ENTER NFA state without consuming any character. Zero-width groups start and
          // end at the same position (posVar), not at the pre-character position.
          boolean epsilonGroup = isExitEpsilonReachable(nfaState, nfaState.enterGroup);
          deduped.put(
              key,
              new DFA.GroupAction(
                  nfaState.enterGroup,
                  DFA.GroupAction.ActionType.ENTER,
                  nfaState.id,
                  epsilonGroup));
          dedupedRanks.put(key, rank);
        }
      }
      if (nfaState.exitGroup != null) {
        String key = nfaState.exitGroup + ":EXIT";
        Integer existingRank = dedupedRanks.get(key);
        if (existingRank == null || rank < existingRank) {
          deduped.put(
              key,
              new DFA.GroupAction(
                  nfaState.exitGroup, DFA.GroupAction.ActionType.EXIT, nfaState.id));
          dedupedRanks.put(key, rank);
        }
      }
    }

    if (deduped.isEmpty()) return Collections.emptyList();
    List<DFA.GroupAction> result = new ArrayList<>(deduped.values());
    result.sort(
        Comparator.comparingInt(
            a -> dedupedRanks.getOrDefault(a.groupId + ":" + a.type.name(), Integer.MAX_VALUE)));
    return result;
  }

  /**
   * Returns true when the EXIT NFA state for {@code groupId} is epsilon-reachable from {@code
   * enterState} without consuming any character. Used to mark zero-width groups whose capture span
   * is [posVar, posVar] rather than [preIncrementPos, posVar].
   */
  private static boolean isExitEpsilonReachable(NFA.NFAState enterState, int groupId) {
    Queue<NFA.NFAState> queue = new ArrayDeque<>();
    Set<NFA.NFAState> visited = new HashSet<>();
    queue.add(enterState);
    visited.add(enterState);
    while (!queue.isEmpty()) {
      NFA.NFAState current = queue.poll();
      if (current.exitGroup != null && current.exitGroup == groupId) {
        return true;
      }
      for (NFA.NFAState next : current.getEpsilonTransitions()) {
        if (visited.add(next)) {
          queue.add(next);
        }
      }
    }
    return false;
  }

  /**
   * Returns true when the accepting NFA-state set has a capture-ambiguity for any group: there is a
   * thread that exits group {@code g} (participated) alongside a direct accept state that did not
   * exit group {@code g} (bypassed it). The lowest-state-id heuristic in {@link
   * #computeGroupActions} cannot choose the correct binding in this case.
   *
   * <p>Conservative: may over-detect (false positives cause unnecessary JDK fallback;
   * under-detection would silently produce wrong answers). Always prefer false positives here.
   */
  /**
   * Returns true when the given DFA-state NFA-set is capture-ambiguous for any capturing group g:
   * it contains an exit_g NFA state (the group was completed by some thread) AND group g has a
   * bypass path in the NFA (there is a way to accept without entering g at all). When both are
   * true, {@code computeGroupActions} will emit spurious START/END tags for g from the group-taking
   * thread even though the priority-winning thread may have bypassed g — producing wrong group
   * spans.
   */
  private boolean hasCaptureAmbiguity(Set<NFA.NFAState> nfaStates, Set<Integer> groupsWithBypass) {
    if (groupsWithBypass.isEmpty()) return false;
    for (NFA.NFAState s : nfaStates) {
      // exitGroup: the group was completed by some thread. When a bypass also exists, the
      // lowest-id heuristic may pick the wrong thread's binding.
      if (s.exitGroup != null && groupsWithBypass.contains(s.exitGroup)) {
        return true;
      }
      // enterGroup: the group was entered by some thread. If a bypass also exists and this
      // enter is in a DFA state where the bypass thread can also win, computeGroupActions
      // records a spurious START at the current position even though the bypass thread
      // never exits the group — leaving the span's END unset and producing a [start,-1) span.
      if (s.enterGroup != null && groupsWithBypass.contains(s.enterGroup)) {
        return true;
      }
    }
    return false;
  }

  /**
   * For each capturing group g (1..nfa.getGroupCount()), determines whether there is a path from
   * the NFA start state to any accept state that does NOT pass through group g's enter marker. Uses
   * a BFS over NFA transitions (both epsilon and character), tracking which groups have been
   * entered.
   */
  private Set<Integer> computeGroupsWithBypass(
      NFA nfa, Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures)
      throws StateExplosionException {
    int groupCount = nfa.getGroupCount();
    if (groupCount == 0) return Collections.emptySet();

    Set<Integer> result = new HashSet<>();
    Set<NFA.NFAState> acceptStates = nfa.getAcceptStates();

    for (int g = 1; g <= groupCount; g++) {
      if (canReachAcceptWithoutEnteringGroup(
          nfa.getStartState(), g, acceptStates, epsilonClosures)) {
        result.add(g);
      }
    }
    return result;
  }

  /**
   * Step-by-step BFS (epsilon + character). Blocks any branch at a state with {@code enterGroup ==
   * g} to avoid false bypasses from pre-computed transitive closures (which include states inside
   * the group reachable through the blocked entry). Returns true only if an accept state is
   * reachable without crossing group g's enter marker. Each dequeued state is charged to the
   * determinization work budget: this BFS is O(groups × states), so patterns with thousands of
   * capture groups (e.g. (wa0|wb0)(wa1|wb1)… × 6000) previously ran it unchecked for tens of
   * seconds past the total compile deadline (see find-deadline-coverage-gaps); legitimate patterns
   * (dozens of groups, moderate NFAs) charge only a few hundred thousand units.
   */
  private boolean canReachAcceptWithoutEnteringGroup(
      NFA.NFAState start,
      int g,
      Set<NFA.NFAState> acceptStates,
      Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures)
      throws StateExplosionException {
    Set<NFA.NFAState> visited = new HashSet<>();
    Queue<NFA.NFAState> queue = new ArrayDeque<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      NFA.NFAState cur = queue.poll();
      chargeWork(8); // dequeued state + its epsilon and character edge scans
      if (!visited.add(cur)) continue;
      // Block on entering group g — do not traverse through this state's entry marker
      if (cur.enterGroup != null && cur.enterGroup == g) continue;
      if (acceptStates.contains(cur)) return true;
      // Follow epsilon transitions one step at a time (not transitive closure)
      for (NFA.NFAState eps : cur.getEpsilonTransitions()) {
        if (!visited.contains(eps)) queue.add(eps);
      }
      for (NFA.Transition t : cur.getTransitions()) {
        if (!visited.contains(t.target)) queue.add(t.target);
      }
    }
    return false;
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
      if (canReachGroupExit(targetState, groupId, epsilonClosures)) {
        // This target state is inside the group, so we're actually entering
        return true;
      }
    }

    // None of the target states are inside the group, so we're bypassing it
    return false;
  }

  /**
   * Lazily-computed, memoized map: groupId -> set of NFA states that can reach that group's EXIT
   * marker via epsilon edges and/or character transitions. Computed once per group with a single
   * reverse BFS instead of the previous per-call recursive closure crawl, which was O(|closure| ×
   * |transitions| × |closure|) per invocation and dominated compile time on alternation-heavy
   * unrolled patterns (e.g. (a|b){0,256} chains).
   */
  private final Map<Integer, Set<NFA.NFAState>> groupExitReachability = new HashMap<>();

  /**
   * Check whether {@code state} can reach the EXIT marker of {@code groupId} — i.e. whether the
   * state is "inside" the group. Memoized per group via reverse BFS from the group's EXIT markers.
   */
  private boolean canReachGroupExit(
      NFA.NFAState state, int groupId, Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures) {
    Set<NFA.NFAState> reaching = groupExitReachability.get(groupId);
    if (reaching == null) {
      reaching = computeStatesReachingGroupExit(groupId, epsilonClosures);
      groupExitReachability.put(groupId, reaching);
    }
    return reaching.contains(state);
  }

  /**
   * Reverse BFS from all states carrying {@code exitGroup == groupId}, over reversed epsilon edges
   * and reversed character transitions. The result is exactly the set of states from which the
   * group's EXIT marker is reachable (matching the semantics of the previous recursive
   * implementation, without its 100-state depth under-approximation).
   */
  private Set<NFA.NFAState> computeStatesReachingGroupExit(
      int groupId, Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures) {
    // Build reverse adjacency: target -> sources, over epsilon edges and character transitions.
    Map<NFA.NFAState, List<NFA.NFAState>> reverse = new HashMap<>();
    Deque<NFA.NFAState> queue = new ArrayDeque<>();
    for (NFA.NFAState s : epsilonClosures.keySet()) {
      if (s.exitGroup != null && s.exitGroup == groupId) {
        queue.add(s);
      }
      for (NFA.NFAState t : s.getEpsilonTransitions()) {
        reverse.computeIfAbsent(t, k -> new ArrayList<>(2)).add(s);
      }
      for (NFA.Transition trans : s.getTransitions()) {
        reverse.computeIfAbsent(trans.target, k -> new ArrayList<>(2)).add(s);
      }
    }
    Set<NFA.NFAState> visited = new HashSet<>();
    while (!queue.isEmpty()) {
      NFA.NFAState cur = queue.poll();
      if (!visited.add(cur)) continue;
      List<NFA.NFAState> preds = reverse.get(cur);
      if (preds != null) {
        for (NFA.NFAState p : preds) {
          if (!visited.contains(p)) queue.add(p);
        }
      }
    }
    return visited;
  }

  /**
   * Check if we can reach an EXIT marker for the given group from a state. This helps determine if
   * a state is "inside" a group. Checks both epsilon transitions and character transitions
   * (recursively).
   *
   * @deprecated replaced by the memoized reverse-BFS implementation above; retained signature shape
   *     only via {@link #canReachGroupExit(NFA.NFAState, int, Map)}
   */
  private boolean canReachGroupExit(
      NFA.NFAState state,
      int groupId,
      Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures,
      Set<NFA.NFAState> visited) {
    throw new UnsupportedOperationException(
        "removed: use memoized canReachGroupExit(NFA.NFAState, int, Map)");
  }

  /**
   * Compute tag operations for a DFA transition using priority-rank selection. C2.4: when the
   * target DFA state is accepting, only emits ops from source threads whose rank is ≥ the rank of
   * the source that contributed the NFA accept state — higher-priority threads that failed to
   * accept are excluded.
   */
  private List<DFA.TagOperation> computeTagOperations(
      Set<NFA.NFAState> sourceNFAStates,
      Set<NFA.NFAState> targetNFAStates,
      CharSet charSet,
      Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures,
      Set<NFA.NFAState> nfaAcceptStates)
      throws StateExplosionException {
    return computeTagOperations(
        sourceNFAStates,
        targetNFAStates,
        charSet,
        epsilonClosures,
        nfaAcceptStates,
        EnumSet.noneOf(NFA.AnchorType.class));
  }

  private List<DFA.TagOperation> computeTagOperations(
      Set<NFA.NFAState> sourceNFAStates,
      Set<NFA.NFAState> targetNFAStates,
      CharSet charSet,
      Map<NFA.NFAState, Set<NFA.NFAState>> epsilonClosures,
      Set<NFA.NFAState> nfaAcceptStates,
      EnumSet<NFA.AnchorType> targetAcceptConditions)
      throws StateExplosionException {

    List<NFA.NFAState> sourceOrdered =
        (dfaStateOrdering != null)
            ? dfaStateOrdering.getOrDefault(sourceNFAStates, Collections.emptyList())
            : Collections.emptyList();
    Map<NFA.NFAState, Integer> sourceRankMap = buildRankMap(sourceOrdered);

    // C2.4: find the lowest-rank source whose transition reaches an NFA accept state in the target.
    // Only suppress competing threads when multiple distinct source threads contribute char
    // transitions (contributingSourceCount > 1). When only one source contributes all target
    // states, every NFA state in the source DFA state is from the same thread — no filtering.
    int minAcceptingSourceRank = Integer.MAX_VALUE;
    int contributingSourceCount = 0;
    if (!nfaAcceptStates.isEmpty()) {
      for (NFA.NFAState source : sourceNFAStates) {
        int srcRank = sourceRankMap.getOrDefault(source, Integer.MAX_VALUE);
        for (NFA.Transition t : source.getTransitions()) {
          if (!t.chars.intersects(charSet)) continue;
          Set<NFA.NFAState> closure = epsilonClosures.get(t.target);
          if (closure == null) continue;
          boolean contributes = false;
          for (NFA.NFAState reach : closure) {
            if (!targetNFAStates.contains(reach)) continue;
            contributes = true;
            if (nfaAcceptStates.contains(reach)) {
              minAcceptingSourceRank = Math.min(minAcceptingSourceRank, srcRank);
            }
          }
          if (contributes) {
            contributingSourceCount++;
            break; // count once per source NFA state
          }
        }
      }
    }
    // Apply C2.4 only when genuinely multiple threads compete (contributingSourceCount > 1).
    final boolean applyC24Filter =
        minAcceptingSourceRank < Integer.MAX_VALUE && contributingSourceCount > 1;

    Map<Integer, DFA.TagOperation> tagOps = new HashMap<>();
    Map<Integer, Integer> tagOpRanks = new HashMap<>(); // tagId → best source rank so far

    // FIRST: Check for group ENTER markers in source states.
    // Build a set of source states that are on the accepting path (rank == minAcceptingSourceRank),
    // so that we can detect when a high-priority group-enter marker is in the same NFA thread as
    // the accepting source and must NOT be suppressed by C2.4.
    Set<NFA.NFAState> acceptingSourceStates = new HashSet<>();
    if (applyC24Filter) {
      for (NFA.NFAState source : sourceNFAStates) {
        if (sourceRankMap.getOrDefault(source, Integer.MAX_VALUE) == minAcceptingSourceRank) {
          acceptingSourceStates.add(source);
        }
      }
    }
    for (NFA.NFAState sourceState : sourceNFAStates) {
      if (sourceState.enterGroup == null) continue;
      int srcRank = sourceRankMap.getOrDefault(sourceState, Integer.MAX_VALUE);
      if (applyC24Filter && srcRank < minAcceptingSourceRank) {
        // C2.4: this source has higher priority than the accepting source. Normally skip it.
        // Exception: if ANY accepting-source state is reachable from this enter-marker via epsilon,
        // the enter marker IS on the accepting path and must not be suppressed (e.g. (b)|b where
        // group1_enter precedes b_alt1 in the same thread, and b_alt1 is the accepting source).
        Set<NFA.NFAState> enterClosure = epsilonClosures.get(sourceState);
        boolean onAcceptingPath = false;
        if (enterClosure != null) {
          for (NFA.NFAState acc : acceptingSourceStates) {
            if (enterClosure.contains(acc)) {
              onAcceptingPath = true;
              break;
            }
          }
        }
        if (!onAcceptingPath) continue; // C2.4: suppress — not on the accepting path
      }
      if (applyC24Filter && srcRank > minAcceptingSourceRank) {
        // C2.4B: this source has LOWER priority than the accepting source. If the accepting
        // source bypasses this group (i.e., the group-enter state does NOT lead to the accepting
        // source via epsilon), suppress the START tag — the winning thread does not bind this
        // group (e.g. b|(b) where the bare-b alt1 wins and the group alt2 should be unmatched).
        // A group-enter IS on the accepting path when the accepting-source state is reachable from
        // it via epsilon (meaning they are in the same NFA thread, e.g. (b)|b's group enters
        // before the consuming 'b' state that is the accepting source).
        Set<NFA.NFAState> enterClosure = epsilonClosures.get(sourceState);
        boolean acceptingSourceDownstream = false;
        if (enterClosure != null) {
          for (NFA.NFAState acc : acceptingSourceStates) {
            if (enterClosure.contains(acc)) {
              acceptingSourceDownstream = true;
              break;
            }
          }
        }
        if (!acceptingSourceDownstream) continue; // C2.4B: accepting source bypasses the group
      }
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
        Integer existingRank = tagOpRanks.get(tagId);
        if (existingRank == null || srcRank < existingRank) {
          tagOps.put(tagId, op);
          tagOpRanks.put(tagId, srcRank);
        }
      }
    }

    // SECOND: Track tag operations along character transitions
    for (NFA.NFAState source : sourceNFAStates) {
      int srcRank = sourceRankMap.getOrDefault(source, Integer.MAX_VALUE);
      // C2.4: only skip sources that (a) have lower rank (higher priority) AND (b) actually
      // contribute to NFA acceptance in the target. Non-accepting higher-priority sources must
      // not be excluded — they carry tag ops for continuation paths beyond this state.
      if (applyC24Filter && srcRank < minAcceptingSourceRank) continue; // C2.4
      for (NFA.Transition trans : source.getTransitions()) {
        if (!trans.chars.intersects(charSet)) continue;
        Set<NFA.NFAState> closure = epsilonClosures.get(trans.target);
        for (NFA.NFAState reachable : closure) {
          chargeWork(1);
          if (!targetNFAStates.contains(reachable)) continue;
          if (trans.target.enterGroup != null) {
            int tagId = DFA.TagOperation.tagIdForGroupStart(trans.target.enterGroup);
            DFA.TagOperation op =
                new DFA.TagOperation(
                    tagId,
                    trans.target.enterGroup,
                    DFA.TagOperation.ActionType.START,
                    trans.target.id);
            Integer existingRank = tagOpRanks.get(tagId);
            if (existingRank == null || srcRank < existingRank) {
              tagOps.put(tagId, op);
              tagOpRanks.put(tagId, srcRank);
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
            Integer existingRank = tagOpRanks.get(tagId);
            if (existingRank == null || srcRank < existingRank) {
              tagOps.put(tagId, op);
              tagOpRanks.put(tagId, srcRank);
            }
          }
          trackEpsilonPathTags(trans.target, reachable, tagOps, tagOpRanks, srcRank);
        }
      }
    }

    // C2.4C: when the target DFA state is unconditionally accepting and multiple threads compete,
    // suppress any tag that was recorded exclusively by lower-priority threads (rank >
    // minAcceptingSourceRank). The highest-priority accepting thread wins; if it doesn't record a
    // tag, the tag must not be set by a losing thread (e.g. b|(b) where the bare-b thread wins but
    // the group-thread records group-end — that end must be suppressed so group is unmatched).
    // This is NOT applied when acceptance is anchor-conditional (e.g. $-anchored patterns) because
    // a conditionally-accepting higher-priority thread may not actually win for longer inputs, and
    // suppressing the lower-priority group-tracking thread's tags would produce wrong spans.
    if (applyC24Filter && targetAcceptConditions.isEmpty()) {
      final int minAccRank = minAcceptingSourceRank;
      tagOps
          .entrySet()
          .removeIf(e -> tagOpRanks.getOrDefault(e.getKey(), Integer.MAX_VALUE) > minAccRank);
    }
    List<DFA.TagOperation> result = new ArrayList<>(tagOps.values());
    result.sort(
        Comparator.comparingInt(op -> tagOpRanks.getOrDefault(op.tagId, Integer.MAX_VALUE)));
    return result;
  }

  /**
   * Track tag operations along an epsilon path from {@code start} to {@code end}. Uses BFS. {@code
   * sourceRank} is the priority rank of the source NFA state that took the character transition
   * leading to this epsilon path; it is used as the tiebreak rank for all ops emitted here.
   */
  private void trackEpsilonPathTags(
      NFA.NFAState start,
      NFA.NFAState end,
      Map<Integer, DFA.TagOperation> tagOps,
      Map<Integer, Integer> tagOpRanks,
      int sourceRank)
      throws StateExplosionException {
    if (start == end) return;

    Queue<NFA.NFAState> queue = new ArrayDeque<>();
    Set<NFA.NFAState> visited = new HashSet<>();
    queue.add(start);
    visited.add(start);

    while (!queue.isEmpty()) {
      NFA.NFAState current = queue.poll();

      for (NFA.NFAState next : current.getEpsilonTransitions()) {
        chargeWork(1);
        if (visited.contains(next)) continue;
        visited.add(next);
        queue.add(next);

        if (next.enterGroup != null) {
          int tagId = DFA.TagOperation.tagIdForGroupStart(next.enterGroup);
          DFA.TagOperation op =
              new DFA.TagOperation(
                  tagId, next.enterGroup, DFA.TagOperation.ActionType.START, next.id);
          Integer existingRank = tagOpRanks.get(tagId);
          if (existingRank == null || sourceRank < existingRank) {
            tagOps.put(tagId, op);
            tagOpRanks.put(tagId, sourceRank);
          }
        }

        if (next.exitGroup != null) {
          int tagId = DFA.TagOperation.tagIdForGroupEnd(next.exitGroup);
          DFA.TagOperation op =
              new DFA.TagOperation(tagId, next.exitGroup, DFA.TagOperation.ActionType.END, next.id);
          Integer existingRank = tagOpRanks.get(tagId);
          if (existingRank == null || sourceRank < existingRank) {
            tagOps.put(tagId, op);
            tagOpRanks.put(tagId, sourceRank);
          }
        }

        if (next == end) return;
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
    return buildDFAWithAssertions(nfa, false);
  }

  /**
   * @param literalTierCandidate true when the caller (PatternAnalyzer) determined the pattern has
   *     >=2 literal-extractable lookaheads and SPECIALIZED_LITERAL_LOOKAHEADS would take it if this
   *     build threw. The sub-DFA gate admission declines for such patterns (the gate's
   *     per-candidate scan loses to the intrinsified indexOf), reproducing the pre-gate routing.
   */
  public DFA buildDFAWithAssertions(NFA nfa, boolean literalTierCandidate)
      throws StateExplosionException {
    this.literalTierCandidate = literalTierCandidate;
    this.stateCache = new LinkedHashMap<>();
    this.allStates = new ArrayList<>();
    this.nextStateId = 0;
    this.anchorConditionDiluted = false;
    this.determinizationWork = 0;
    this.lastDeadlineCheckNanos = System.nanoTime();
    this.determinizationDeadlineNanos =
        DETERMINIZATION_DEADLINE_MS > 0
            ? lastDeadlineCheckNanos + DETERMINIZATION_DEADLINE_MS * 1_000_000L
            : 0L;

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
          if (containsConsumeKillingAnchor(srcCond, chars)) continue;
          for (NFA.Transition trans : nfaState.getTransitions()) {
            chargeWork(1);
            if (trans.chars.intersects(chars)) {
              hasContributor = true;
              if (!srcCond.isEmpty()) anyNonEmptySrcCond = true;
              transitionGuard = mergeWeakest(transitionGuard, srcCond);
              for (Map.Entry<NFA.NFAState, EnumSet<NFA.AnchorType>> e :
                  anchoredClosures.get(trans.target).entrySet()) {
                chargeWork(1);
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
        CharSet narrowedChars2 = narrowEndGuardedCharset(transitionGuard, chars);
        if (narrowedChars2 == null) continue;
        current.addTransition(
            narrowedChars2, targetState, Collections.emptyList(), transitionGuard);
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
          // Fixed-width extraction failed. For a LOOKAHEAD whose body carries no groups, anchors
          // or nested assertions, fall back to a sub-DFA gate: compile the assertion body to its
          // own DFA and evaluate it as an anchored run from the assertion position (scan until
          // the gate DFA accepts or dies; {1,64}-style bodies die at their bound, \w+ at the
          // first non-word char). The check attaches to this assertion state as usual, so it
          // fires once at every position where the main DFA walk enters it (for a leading
          // assertion: once per candidate start).
          // Gate admission is groupless-patterns-only: the gate runs on the DFA-with-assertions
          // ladder, whose tagged-capture machinery does not track body groups correctly behind a
          // variable-width assertion (e.g. (?=.*b)(a+)b mis-spans group 1 on the ladder). Grouped
          // patterns keep their pre-gate routing (hybrid/NFA), which handles captures.
          if (literalTierCandidate) {
            throw new UnsupportedOperationException(
                "Literal-indexOf tier candidate: the sub-DFA gate declines so"
                    + " SPECIALIZED_LITERAL_LOOKAHEADS keeps the pattern");
          }
          if (nfa.getGroupCount() == 0
              && (state.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD
                  || state.assertionType == NFA.AssertionType.NEGATIVE_LOOKAHEAD)) {
            check =
                new AssertionCheck(convertAssertionType(state.assertionType), buildGateDfa(state));
          } else {
            throw new UnsupportedOperationException(
                "Complex lookbehind assertion not supported in DFA mode (lookbehinds peek"
                    + " backwards and cannot be evaluated by a forward sub-DFA run)");
          }
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
   * Gate-DFA size cap: a gate is scanned once per candidate start, so pathological assertion bodies
   * (state explosion) stay out and keep the old routing.
   */
  private static final int MAX_GATE_DFA_STATES = 1000;

  /**
   * Set by buildDFAWithAssertions(nfa, true): decline gates for literal-indexOf-tier candidates.
   */
  private boolean literalTierCandidate = false;

  /**
   * Builds the gate DFA for a variable-width lookahead assertion: the assertion body's NFA
   * fragment, subset-constructed on its own. Throws {@link UnsupportedOperationException} for
   * bodies this gate form must not evaluate (capturing groups need boundary tracking the gate does
   * not do; anchors and nested assertions need the main-construction's anchor machinery, which
   * plain {@link #buildDFA} does not apply to a bare fragment).
   */
  private DFA buildGateDfa(NFA.NFAState assertionState) {
    if (assertionState.assertionStartState == null
        || assertionState.assertionAcceptStates == null
        || assertionState.assertionAcceptStates.isEmpty()) {
      throw new UnsupportedOperationException("Malformed assertion for gate DFA");
    }
    List<NFA.NFAState> fragment = collectReachableStates(assertionState.assertionStartState);
    for (NFA.NFAState st : fragment) {
      if (st.enterGroup != null || st.exitGroup != null) {
        throw new UnsupportedOperationException("Capturing group in variable-width lookahead");
      }
      if (st.anchor != null) {
        throw new UnsupportedOperationException("Anchor in variable-width lookahead");
      }
      if (st.assertionType != null) {
        throw new UnsupportedOperationException("Nested assertion in variable-width lookahead");
      }
    }
    NFA subNfa =
        new NFA(
            fragment, assertionState.assertionStartState, assertionState.assertionAcceptStates, 0);
    DFA gateDfa;
    try {
      gateDfa = new SubsetConstructor().buildDFA(subNfa);
    } catch (StateExplosionException | UnsupportedOperationException e) {
      throw new UnsupportedOperationException(
          "Lookahead body not DFA-compilable for gate: " + e.getMessage());
    }
    if (gateDfa.getStateCount() > MAX_GATE_DFA_STATES) {
      throw new UnsupportedOperationException(
          "Gate DFA too large: " + gateDfa.getStateCount() + " states");
    }
    return gateDfa;
  }

  /** All NFA states reachable from {@code start} (including itself), BFS order. */
  private static List<NFA.NFAState> collectReachableStates(NFA.NFAState start) {
    List<NFA.NFAState> order = new ArrayList<>();
    Set<NFA.NFAState> visited = new HashSet<>();
    Deque<NFA.NFAState> work = new ArrayDeque<>();
    work.add(start);
    visited.add(start);
    while (!work.isEmpty()) {
      NFA.NFAState cur = work.poll();
      order.add(cur);
      for (NFA.Transition t : cur.getTransitions()) {
        if (visited.add(t.target)) work.add(t.target);
      }
      for (NFA.NFAState t : cur.getEpsilonTransitions()) {
        if (visited.add(t)) work.add(t);
      }
    }
    return order;
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
