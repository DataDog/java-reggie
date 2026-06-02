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
import java.util.EnumSet;

/**
 * Computes structural hash for pattern analysis results to enable bytecode caching.
 *
 * <p>Two patterns have the same structural hash if they would generate equivalent bytecode: - Same
 * matching strategy - Same DFA topology (state count, transition structure) - Same character sets
 * on transitions (including case-sensitivity) - Same group count - Same feature flags
 *
 * <p>The hash does NOT include: - Pattern string (generated identifiers) - Class names or UUIDs
 * (generated identifiers)
 *
 * <p><b>Implementation note on hash width</b>: all public methods return {@code long} (64-bit). The
 * Level-2 structural cache key must be 64-bit to make birthday collisions essentially impossible
 * for realistic pattern sets (~10 k patterns → P_collision ≈ 2.7 × 10⁻⁹). Using an {@code int} key
 * was observed to cause silent misidentification of structurally distinct patterns as cache hits,
 * producing wrong match results.
 *
 * <p><b>Implementation note on enum hashing</b>: all enum values are hashed via {@link
 * Enum#ordinal()} rather than {@link Object#hashCode()} because {@code hashCode()} delegates to
 * {@code System.identityHashCode()}, which is not guaranteed to be non-zero. A zero identity hash
 * makes a non-empty {@link EnumSet} indistinguishable from an empty one.
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
   * @return 64-bit hash representing the structural equivalence class
   */
  public static long compute(
      PatternAnalyzer.MatchingStrategyResult result, NFA nfa, boolean caseInsensitive) {
    long hash = 17L;

    hash = 31L * hash + result.strategy.ordinal();
    hash = 31L * hash + nfa.getGroupCount();
    hash = 31L * hash + (result.useTaggedDFA ? 1 : 0);
    hash = 31L * hash + (result.usePosixLastMatch ? 1 : 0);
    hash = 31L * hash + (caseInsensitive ? 1 : 0);

    // Each anchor type is a separate bit so that patterns differing only in which
    // anchor they use always produce different hashes.
    hash = 31L * hash + (nfa.hasEndAnchor() ? 1 : 0); // $
    hash = 31L * hash + (nfa.hasStartAnchor() ? 1 : 0); // ^
    hash = 31L * hash + (nfa.hasStringEndAbsoluteAnchor() ? 1 : 0); // \z
    hash = 31L * hash + (nfa.hasStringEndAnchor() ? 1 : 0); // \Z
    hash = 31L * hash + (nfa.hasStringStartAnchor() ? 1 : 0); // \A
    hash = 31L * hash + (nfa.hasMultilineStartAnchor() ? 1 : 0); // ^ in (?m)
    hash = 31L * hash + (nfa.hasMultilineEndAnchor() ? 1 : 0); // $ in (?m)

    if (result.dfa != null) {
      hash = 31L * hash + computeDFATopologyHash(result.dfa);
    }

    hash = 31L * hash + nfa.contentHashCode();

    if (result.patternInfo != null) {
      hash = 31L * hash + result.patternInfo.structuralHashCode();
    }

    return hash;
  }

  /**
   * Compute hash without NFA (for patterns that skip NFA construction, e.g. RECURSIVE_DESCENT).
   *
   * @param result Analysis result
   * @return 64-bit hash representing the structural equivalence class
   */
  public static long computeWithoutGroupCount(PatternAnalyzer.MatchingStrategyResult result) {
    long hash = 17L;
    hash = 31L * hash + result.strategy.ordinal();
    hash = 31L * hash + (result.useTaggedDFA ? 1 : 0);
    hash = 31L * hash + (result.usePosixLastMatch ? 1 : 0);

    if (result.dfa != null) {
      hash = 31L * hash + computeDFATopologyHash(result.dfa);
    }

    if (result.patternInfo != null) {
      hash = 31L * hash + result.patternInfo.structuralHashCode();
    }

    return hash;
  }

  /**
   * Compute hash of DFA including topology and content.
   *
   * <p>Includes: state count, transition counts, accept state count, max out-degree, character sets
   * on transitions, per-state acceptance anchor conditions, per-transition entry guards, per-state
   * group-action content (groupId + type), per-transition tag-operation content (tagId + type),
   * assertion-check content (type, literal, offset, width, charSets, group captures).
   *
   * <p>Excludes: state IDs (generated identifiers).
   */
  private static long computeDFATopologyHash(DFA dfa) {
    long hash = 1L;

    hash = 31L * hash + dfa.getStateCount();
    hash = 31L * hash + dfa.getAcceptStates().size();
    hash = 31L * hash + dfa.getMaxOutDegree();

    for (DFA.DFAState state : dfa.getAllStates()) {
      hash = 31L * hash + state.transitions.size();
      hash = 31L * hash + (state.accepting ? 1 : 0);
      hash = 31L * hash + (state.acceptIsPriorityCut ? 1 : 0);

      // Acceptance anchor conditions: use ordinal bitmask, not EnumSet.hashCode(), because
      // System.identityHashCode() can return 0, making {END} look the same as {}.
      hash = 31L * hash + anchorBitmask(state.acceptanceAnchorConditions);

      for (var ga : state.groupActions) {
        hash = 31L * hash + ga.groupId;
        hash = 31L * hash + ga.type.ordinal();
      }

      hash = 31L * hash + state.assertionChecks.size();
      for (var ac : state.assertionChecks) {
        hash = 31L * hash + ac.type.ordinal();
        hash = 31L * hash + (ac.literal != null ? ac.literal.hashCode() : 0);
        hash = 31L * hash + ac.offset;
        hash = 31L * hash + ac.width;
        if (ac.charSets != null) {
          for (var cs : ac.charSets) {
            hash = 31L * hash + cs.hashCode();
          }
        }
        for (var gc : ac.groups) {
          hash = 31L * hash + gc.groupNumber;
          hash = 31L * hash + gc.startOffset;
          hash = 31L * hash + gc.length;
        }
      }

      for (var entry : state.transitions.entrySet()) {
        hash = 31L * hash + entry.getKey().hashCode();
        hash = 31L * hash + anchorBitmask(entry.getValue().entryGuard);
        for (var tagOp : entry.getValue().tagOps) {
          hash = 31L * hash + tagOp.tagId;
          hash = 31L * hash + tagOp.type.ordinal();
        }
      }
    }

    return hash;
  }

  /** Stable bitmask over an EnumSet of AnchorType using ordinal() values. */
  private static int anchorBitmask(EnumSet<NFA.AnchorType> anchors) {
    int mask = 0;
    for (NFA.AnchorType a : anchors) {
      mask |= (1 << a.ordinal());
    }
    return mask;
  }
}
