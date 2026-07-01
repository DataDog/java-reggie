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
    return compute(result, nfa, caseInsensitive, 17L, 31L, false);
  }

  /**
   * Second, independent structural hash (different seed + polynomial multiplier, and the alternate
   * NFA content hash) used by the runtime structural-cache for <b>verify-on-hit</b>: when two
   * structurally-distinct patterns collide on {@link #compute}, this value is overwhelmingly likely
   * to differ, so the cache detects the false-hit and regenerates the correct class instead of
   * returning a wrong matcher. Combined with {@link #compute} this gives a ~2⁻¹²⁸ residual.
   */
  public static long computeVerification(
      PatternAnalyzer.MatchingStrategyResult result, NFA nfa, boolean caseInsensitive) {
    return compute(result, nfa, caseInsensitive, 0xCBF29CE484222325L, 1099511628211L, true);
  }

  private static long compute(
      PatternAnalyzer.MatchingStrategyResult result,
      NFA nfa,
      boolean caseInsensitive,
      long seed,
      long mult,
      boolean alt) {
    long hash = seed;

    hash = mult * hash + result.strategy.ordinal();
    hash = mult * hash + nfa.getGroupCount();
    hash = mult * hash + (result.useTaggedDFA ? 1 : 0);
    hash = mult * hash + (result.usePosixLastMatch ? 1 : 0);
    hash = mult * hash + (result.hasAtomicGroups ? 1 : 0);
    hash = mult * hash + (caseInsensitive ? 1 : 0);

    // Each anchor type is a separate bit so that patterns differing only in which
    // anchor they use always produce different hashes.
    hash = mult * hash + (nfa.hasEndAnchor() ? 1 : 0); // $
    hash = mult * hash + (nfa.hasStartAnchor() ? 1 : 0); // ^
    hash = mult * hash + (nfa.hasStringEndAbsoluteAnchor() ? 1 : 0); // \z
    hash = mult * hash + (nfa.hasStringEndAnchor() ? 1 : 0); // \Z
    hash = mult * hash + (nfa.hasStringStartAnchor() ? 1 : 0); // \A
    hash = mult * hash + (nfa.hasMultilineStartAnchor() ? 1 : 0); // ^ in (?m)
    hash = mult * hash + (nfa.hasMultilineEndAnchor() ? 1 : 0); // $ in (?m)

    if (result.dfa != null) {
      hash = mult * hash + computeDFATopologyHash(result.dfa, mult);
    }

    hash = mult * hash + (alt ? nfa.contentHashCodeAlt() : nfa.contentHashCode());

    if (result.patternInfo != null) {
      hash = mult * hash + result.patternInfo.structuralHashCode();
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
    return computeWithoutGroupCount(result, 17L, 31L);
  }

  /**
   * Verify-on-hit companion to {@link #computeWithoutGroupCount}; see {@link #computeVerification}.
   */
  public static long computeVerificationWithoutGroupCount(
      PatternAnalyzer.MatchingStrategyResult result) {
    return computeWithoutGroupCount(result, 0xCBF29CE484222325L, 1099511628211L);
  }

  private static long computeWithoutGroupCount(
      PatternAnalyzer.MatchingStrategyResult result, long seed, long mult) {
    long hash = seed;
    hash = mult * hash + result.strategy.ordinal();
    hash = mult * hash + (result.useTaggedDFA ? 1 : 0);
    hash = mult * hash + (result.usePosixLastMatch ? 1 : 0);
    hash = mult * hash + (result.hasAtomicGroups ? 1 : 0);

    if (result.dfa != null) {
      hash = mult * hash + computeDFATopologyHash(result.dfa, mult);
    }

    if (result.patternInfo != null) {
      hash = mult * hash + result.patternInfo.structuralHashCode();
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
  private static long computeDFATopologyHash(DFA dfa, long mult) {
    long hash = 1L;

    hash = mult * hash + dfa.getStateCount();
    hash = mult * hash + dfa.getAcceptStates().size();
    hash = mult * hash + dfa.getMaxOutDegree();

    for (DFA.DFAState state : dfa.getAllStates()) {
      hash = mult * hash + state.transitions.size();
      hash = mult * hash + (state.accepting ? 1 : 0);
      hash = mult * hash + (state.acceptIsPriorityCut ? 1 : 0);

      // Acceptance anchor conditions: use ordinal bitmask, not EnumSet.hashCode(), because
      // System.identityHashCode() can return 0, making {END} look the same as {}.
      hash = mult * hash + anchorBitmask(state.acceptanceAnchorConditions);

      for (var ga : state.groupActions) {
        hash = mult * hash + ga.groupId;
        hash = mult * hash + ga.type.ordinal();
        hash = mult * hash + (ga.epsilonGroup ? 1 : 0);
      }

      hash = mult * hash + state.assertionChecks.size();
      for (var ac : state.assertionChecks) {
        hash = mult * hash + ac.type.ordinal();
        hash = mult * hash + (ac.literal != null ? ac.literal.hashCode() : 0);
        hash = mult * hash + ac.offset;
        hash = mult * hash + ac.width;
        if (ac.charSets != null) {
          for (var cs : ac.charSets) {
            hash = mult * hash + cs.hashCode();
          }
        }
        for (var gc : ac.groups) {
          hash = mult * hash + gc.groupNumber;
          hash = mult * hash + gc.startOffset;
          hash = mult * hash + gc.length;
        }
      }

      for (var entry : state.transitions.entrySet()) {
        hash = mult * hash + entry.getKey().hashCode();
        hash = mult * hash + anchorBitmask(entry.getValue().entryGuard);
        for (var tagOp : entry.getValue().tagOps) {
          hash = mult * hash + tagOp.tagId;
          hash = mult * hash + tagOp.type.ordinal();
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
