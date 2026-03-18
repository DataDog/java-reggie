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
package com.datadoghq.reggie.codegen.analysis;

import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;

/**
 * Computes structural hash for pattern analysis results to enable bytecode caching.
 *
 * <p>Two patterns have the same structural hash if they would generate equivalent bytecode: - Same
 * matching strategy - Same DFA topology (state count, transition structure) - Same character sets
 * on transitions (including case-sensitivity) - Same group count - Same feature flags
 *
 * <p>The hash does NOT include: - Pattern string (generated identifiers) - Class names or UUIDs
 * (generated identifiers)
 */
public final class StructuralHash {

  private StructuralHash() {
    // Utility class
  }

  /**
   * Compute structural hash from analysis result and NFA.
   *
   * @param result Analysis result containing strategy and DFA
   * @param nfa NFA containing group count
   * @param caseInsensitive Whether backreferences use case-insensitive comparison
   * @return Hash code representing the structural equivalence class
   */
  public static int compute(
      PatternAnalyzer.MatchingStrategyResult result, NFA nfa, boolean caseInsensitive) {
    int hash = 17;

    // Strategy is the primary discriminator
    hash = 31 * hash + result.strategy.hashCode();

    // Group count affects bytecode structure (memory allocation, array sizes)
    hash = 31 * hash + nfa.getGroupCount();

    // Feature flags affect code generation
    hash = 31 * hash + (result.useTaggedDFA ? 1 : 0);
    hash = 31 * hash + (result.usePosixLastMatch ? 1 : 0);

    // Case-insensitive backreference comparison affects bytecode generation
    hash = 31 * hash + (caseInsensitive ? 1 : 0);

    // Anchor types affect bytecode (different end-of-string semantics)
    // \z (STRING_END_ABSOLUTE) matches only at absolute end
    // \Z (STRING_END) matches at end or before final newline
    hash = 31 * hash + (nfa.hasStringEndAbsoluteAnchor() ? 1 : 0);
    hash = 31 * hash + (nfa.hasStringEndAnchor() ? 1 : 0);
    hash = 31 * hash + (nfa.hasStringStartAnchor() ? 1 : 0);

    // DFA content hash (if applicable)
    if (result.dfa != null) {
      hash = 31 * hash + computeDFATopologyHash(result.dfa);
    }

    // NFA content hash - includes character sets which are critical for
    // distinguishing patterns with different case-sensitivity
    hash = 31 * hash + nfa.contentHashCode();

    // PatternInfo provides structural hash including class type
    if (result.patternInfo != null) {
      hash = 31 * hash + result.patternInfo.structuralHashCode();
    }

    return hash;
  }

  /**
   * Compute hash of DFA including topology and content.
   *
   * <p>Includes: - State count - Transition counts per state - Accept state count - Max out-degree
   * (transition density) - Character sets on transitions (critical for case-sensitivity)
   *
   * <p>Excludes: - State IDs (generated identifiers)
   */
  private static int computeDFATopologyHash(DFA dfa) {
    int hash = 1;

    // State count is a primary structural characteristic
    hash = 31 * hash + dfa.getStateCount();

    // Accept state count
    hash = 31 * hash + dfa.getAcceptStates().size();

    // Max out-degree (affects code generation strategy: unrolled vs switch vs table)
    hash = 31 * hash + dfa.getMaxOutDegree();

    // Transition structure including character sets
    // Character sets MUST be included to distinguish patterns with different
    // case-sensitivity (e.g., "(ab)c" vs "(a(?i)b)c")
    for (DFA.DFAState state : dfa.getAllStates()) {
      // Number of outgoing transitions
      hash = 31 * hash + state.transitions.size();

      // Accepting state flag
      hash = 31 * hash + (state.accepting ? 1 : 0);

      // Group action count (affects bytecode for group tracking)
      hash = 31 * hash + state.groupActions.size();

      // Assertion check count (affects bytecode for assertion validation)
      hash = 31 * hash + state.assertionChecks.size();

      // Include character sets - critical for case sensitivity
      for (var entry : state.transitions.entrySet()) {
        hash = 31 * hash + entry.getKey().hashCode();
      }
    }

    return hash;
  }

  /**
   * Computes a stable hash for a pattern analysis result.
   *
   * <p>This is a convenience method for testing and debugging that doesn't require an NFA. For
   * production use, prefer compute(result, nfa) which includes group count.
   *
   * @param result Analysis result
   * @return Hash code (less accurate without group count)
   */
  public static int computeWithoutGroupCount(PatternAnalyzer.MatchingStrategyResult result) {
    int hash = 17;
    hash = 31 * hash + result.strategy.hashCode();
    hash = 31 * hash + (result.useTaggedDFA ? 1 : 0);
    hash = 31 * hash + (result.usePosixLastMatch ? 1 : 0);

    if (result.dfa != null) {
      hash = 31 * hash + computeDFATopologyHash(result.dfa);
    }

    if (result.patternInfo != null) {
      hash = 31 * hash + result.patternInfo.structuralHashCode();
    }

    return hash;
  }
}
