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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Product automaton over N component {@link DFA}s: each product state is a tuple of component
 * states, and stepping a character steps all N components simultaneously. Built for fused
 * multi-lookahead checks, where N positive-lookahead DFAs must all be evaluated per character at
 * the same input position — a single product-DFA table lookup replaces N per-lookahead switch
 * dispatches.
 *
 * <p>Unlike {@link DFA}, "accepting" is not a single boolean per state: each product state tracks,
 * per component, whether that component individually accepts there (a component can finish before
 * its siblings). A component with no matching transition out of a state goes dead (represented as
 * {@code null} in {@link ProductState#componentStates}) and stays dead — dead components never
 * become accepting again, matching {@code generateDFATransitionSwitch}'s existing dead-state (-1)
 * semantics.
 */
public final class ProductDFA {

  private final ProductState startState;
  private final List<ProductState> allStates;

  private ProductDFA(ProductState startState, List<ProductState> allStates) {
    this.startState = startState;
    this.allStates = allStates;
  }

  public ProductState getStartState() {
    return startState;
  }

  public List<ProductState> getAllStates() {
    return allStates;
  }

  public int getStateCount() {
    return allStates.size();
  }

  /** A tuple of component DFA states, with per-component accepting/dead tracking. */
  public static final class ProductState {
    public final int id;
    public final DFA.DFAState[] componentStates; // null = component is dead
    public final boolean[] componentAccepting;
    public final Map<CharSet, ProductState> transitions = new LinkedHashMap<>();

    ProductState(int id, DFA.DFAState[] componentStates, boolean[] componentAccepting) {
      this.id = id;
      this.componentStates = componentStates;
      this.componentAccepting = componentAccepting;
    }

    public boolean isComponentDead(int i) {
      return componentStates[i] == null;
    }
  }

  /**
   * Build the product automaton over {@code dfas}. Reachable state count is bounded by {@code ∏
   * dfas[i].getStateCount()} (callers budget this before invoking).
   */
  public static ProductDFA build(DFA[] dfas) {
    int n = dfas.length;
    DFA.DFAState[] startTuple = new DFA.DFAState[n];
    for (int i = 0; i < n; i++) {
      startTuple[i] = dfas[i].getStartState();
    }

    Map<TupleKey, ProductState> stateCache = new HashMap<>();
    List<ProductState> allStates = new ArrayList<>();
    List<ProductState> worklist = new ArrayList<>();
    ProductState start = internState(startTuple, stateCache, allStates, worklist);

    int cursor = 0;
    while (cursor < worklist.size()) {
      ProductState current = worklist.get(cursor++);

      List<CharSet> boundaries = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        if (current.componentStates[i] != null) {
          boundaries.addAll(current.componentStates[i].transitions.keySet());
        }
      }
      for (CharSet atom : partitionAlphabet(boundaries)) {
        DFA.DFAState[] targetTuple = new DFA.DFAState[n];
        boolean anyLive = false;
        for (int i = 0; i < n; i++) {
          targetTuple[i] = stepComponent(current.componentStates[i], atom);
          anyLive |= targetTuple[i] != null;
        }
        if (!anyLive) continue; // all components dead - no need to materialize this transition

        ProductState target = internState(targetTuple, stateCache, allStates, worklist);
        current.transitions.put(atom, target);
      }
    }

    return new ProductDFA(start, allStates);
  }

  /**
   * Given a component's current state (or null if dead) and an alphabet atom, returns the
   * component's next state for that atom, or null if the atom doesn't fully match any of the
   * component's transitions (dead) or the component was already dead.
   */
  private static DFA.DFAState stepComponent(DFA.DFAState componentState, CharSet atom) {
    if (componentState == null) return null;
    for (Map.Entry<CharSet, DFA.DFATransition> entry : componentState.transitions.entrySet()) {
      if (!entry.getKey().intersection(atom).isEmpty()) {
        return entry.getValue().target;
      }
    }
    return null;
  }

  private static ProductState internState(
      DFA.DFAState[] tuple,
      Map<TupleKey, ProductState> cache,
      List<ProductState> allStates,
      List<ProductState> worklist) {
    TupleKey key = new TupleKey(tuple);
    ProductState existing = cache.get(key);
    if (existing != null) return existing;

    boolean[] accepting = new boolean[tuple.length];
    for (int i = 0; i < tuple.length; i++) {
      accepting[i] = tuple[i] != null && tuple[i].accepting;
    }
    ProductState created = new ProductState(allStates.size(), tuple.clone(), accepting);
    cache.put(key, created);
    allStates.add(created);
    worklist.add(created);
    return created;
  }

  /**
   * Refines the character universe into disjoint atoms such that every boundary CharSet is a union
   * of atoms (standard partition-refinement for product automata).
   */
  private static List<CharSet> partitionAlphabet(List<CharSet> boundaries) {
    List<CharSet> atoms = new ArrayList<>();
    atoms.add(CharSet.ANY);
    for (CharSet boundary : boundaries) {
      List<CharSet> refined = new ArrayList<>(atoms.size() + 1);
      for (CharSet atom : atoms) {
        CharSet inside = atom.intersection(boundary);
        CharSet outside = atom.minus(boundary);
        if (!inside.isEmpty()) refined.add(inside);
        if (!outside.isEmpty()) refined.add(outside);
      }
      atoms = refined;
    }
    return atoms;
  }

  /** Identity key for a component-state tuple (component DFAState identity, or null for dead). */
  private static final class TupleKey {
    private final DFA.DFAState[] tuple;

    TupleKey(DFA.DFAState[] tuple) {
      this.tuple = tuple;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof TupleKey)) return false;
      return Arrays.equals(tuple, ((TupleKey) o).tuple);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(tuple);
    }
  }
}
