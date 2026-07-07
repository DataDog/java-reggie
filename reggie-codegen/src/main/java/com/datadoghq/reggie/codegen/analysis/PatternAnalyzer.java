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

import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.DFATableData;
import com.datadoghq.reggie.codegen.automaton.GlushkovAutomaton;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.StateExplosionException;
import com.datadoghq.reggie.codegen.automaton.SubsetConstructor;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Analyzes patterns and recommends bytecode generation strategy. */
public class PatternAnalyzer {

  private static final int DFA_UNROLLED_STATE_LIMIT = 20;
  private static final int DFA_SWITCH_STATE_LIMIT = 300;
  private static final int DFA_TABLE_ESTIMATED_BYTES_LIMIT = 1 << 20;

  private final RegexNode ast;
  private final NFA nfa;

  /** Accumulated guard trace entries for the most recent {@link #analyzeAndRecommend} call. */
  private final List<String> guardTrace = new ArrayList<>();

  public PatternAnalyzer(RegexNode ast, NFA nfa) {
    this.ast = ast;
    this.nfa = nfa;
  }

  /**
   * Records one guard evaluation entry. A checkmark prefix marks guards that fired (caused a
   * routing decision); a space prefix marks guards that were evaluated but did not fire.
   */
  private void addTrace(String guardName, boolean fired) {
    guardTrace.add((fired ? "✓ " : "  ") + guardName);
  }

  /**
   * Check if a pattern requires recursive descent parsing (context-free features). This can be
   * called before building NFA to avoid unnecessary work.
   *
   * @param ast The pattern's AST
   * @return true if pattern uses subroutines, conditionals, or branch reset
   */
  public static boolean requiresRecursiveDescent(RegexNode ast) {
    SubroutineDetector subroutineDetector = new SubroutineDetector();
    if (ast.accept(subroutineDetector)) {
      return true;
    }

    ConditionalDetector conditionalDetector = new ConditionalDetector();
    if (ast.accept(conditionalDetector)) {
      return true;
    }

    BranchResetDetector branchResetDetector = new BranchResetDetector();
    return ast.accept(branchResetDetector);
  }

  /**
   * Check if pattern is OnePass eligible (unambiguous single-path execution). OnePass patterns can
   * track groups without state copying or BitSet overhead.
   *
   * <p>A pattern is OnePass if: 1. For each state, character transitions are disjoint (no overlap)
   * 2. At most one non-marker epsilon transition per state 3. No backreferences (they require
   * backtracking)
   */
  private boolean isOnePassEligible() {
    // Backreferences require backtracking
    if (hasBackreferences(ast)) {
      return false;
    }

    // Assertions can complicate control flow
    if (hasLookaround(ast)) {
      return false;
    }

    // Check each state for ambiguity
    for (NFA.NFAState state : nfa.getStates()) {
      // Check for overlapping character transitions
      List<NFA.Transition> transitions = state.getTransitions();
      for (int i = 0; i < transitions.size(); i++) {
        for (int j = i + 1; j < transitions.size(); j++) {
          CharSet chars1 = transitions.get(i).chars;
          CharSet chars2 = transitions.get(j).chars;
          if (chars1.intersects(chars2)) {
            return false; // Ambiguous: multiple paths for same char
          }
        }
      }

      // Check epsilon transitions
      int totalEpsilons = state.getEpsilonTransitions().size();
      if (totalEpsilons > 1) {
        // Multiple epsilons are ambiguous - OnePass requires at most one
        return false;
      }
    }

    return true;
  }

  /**
   * Analyze pattern characteristics for StringView strategy selection. Determines optimal string
   * access method based on: - Early bailout potential - Expected scan depth - Character comparison
   * intensity - SWAR optimization opportunities
   */
  public PatternCharacteristics analyzeForStringView() {
    boolean earlyBailout = false;
    double scanRatio = 1.0; // pessimistic default (full scan)
    int intensity = 1; // minimal by default
    boolean swar = false;

    // Check for start anchor + restrictive prefix
    if (ast instanceof ConcatNode) {
      List<RegexNode> children = ((ConcatNode) ast).children;
      if (!children.isEmpty()) {
        RegexNode first = children.get(0);

        // Start anchor → early bailout likely
        if (first instanceof AnchorNode && ((AnchorNode) first).type == AnchorNode.Type.START) {
          earlyBailout = true;

          // Check what follows the anchor
          if (children.size() > 1) {
            RegexNode second = children.get(1);

            // Literal prefix → very early bailout
            if (second instanceof LiteralNode) {
              scanRatio = 0.1; // Likely fails on first few chars
            }
            // Small char class → early bailout
            else if (second instanceof CharClassNode) {
              CharClassNode cc = (CharClassNode) second;
              if (!cc.negated && charSetSize(cc.chars) < 20) {
                scanRatio = 0.15; // Restrictive, likely fails early
              }
            }
            // Fixed repetition like ^[A-Z]{5} → bounded scan
            else if (second instanceof QuantifierNode) {
              QuantifierNode quant = (QuantifierNode) second;
              if (quant.min == quant.max && quant.max <= 10) {
                scanRatio = 0.2; // Bounded, fails quickly if no match
              }
            }
          }
        }
        // Literal prefix without anchor → early bailout potential (single char only)
        else if (first instanceof LiteralNode) {
          earlyBailout = true;
          scanRatio = 0.1; // Single literal likely fails quickly
        }
      }
    }
    // Single anchored element
    else if (ast instanceof AnchorNode && ((AnchorNode) ast).type == AnchorNode.Type.START) {
      earlyBailout = true;
      scanRatio = 0.1;
    }

    // Check for SWAR opportunities (hex digits, digits, alphanumeric)
    swar = detectSwarOpportunities(ast);
    if (swar) {
      intensity = 8; // SWAR provides 8x speedup
    }

    // Check for quantifiers (increase intensity and scan ratio)
    QuantifierAnalyzer quantAnalyzer = new QuantifierAnalyzer();
    ast.accept(quantAnalyzer);
    if (quantAnalyzer.hasUnboundedQuantifiers) {
      scanRatio = 1.0; // Must scan extensively
      intensity = Math.max(intensity, 5);
    } else if (quantAnalyzer.hasBoundedQuantifiers) {
      intensity = Math.max(intensity, 3);
    }

    // Character class complexity increases intensity
    CharClassComplexityAnalyzer ccAnalyzer = new CharClassComplexityAnalyzer();
    ast.accept(ccAnalyzer);
    intensity = Math.max(intensity, ccAnalyzer.maxComplexity);

    // Unanchored patterns must scan entire string
    if (!hasStartAnchor(ast)) {
      scanRatio = 1.0;
    }

    return new PatternCharacteristics(earlyBailout, scanRatio, intensity, swar);
  }

  /**
   * Check if pattern has SWAR optimization opportunities. Currently detects hex digit patterns:
   * [0-9a-fA-F]+
   */
  private boolean detectSwarOpportunities(RegexNode node) {
    if (node instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) node;
      if (quant.child instanceof CharClassNode) {
        CharClassNode cc = (CharClassNode) quant.child;
        return isHexDigitCharSet(cc.chars);
      }
    } else if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (detectSwarOpportunities(child)) {
          return true;
        }
      }
    } else if (node instanceof GroupNode) {
      return detectSwarOpportunities(((GroupNode) node).child);
    }
    return false;
  }

  /** Calculate the total number of characters in a CharSet. */
  private int charSetSize(CharSet chars) {
    int size = 0;
    for (CharSet.Range range : chars.getRanges()) {
      size += range.size();
    }
    return size;
  }

  /** Check if a CharSet represents hex digits [0-9a-fA-F]. */
  private boolean isHexDigitCharSet(CharSet chars) {
    // Hex digits: 0-9 (48-57), A-F (65-70), a-f (97-102)
    // Check if charset contains exactly these ranges
    if (charSetSize(chars) != 22) { // 10 + 6 + 6 = 22 characters
      return false;
    }
    // Check all hex digit chars are present
    for (char c = '0'; c <= '9'; c++) {
      if (!chars.contains(c)) return false;
    }
    for (char c = 'A'; c <= 'F'; c++) {
      if (!chars.contains(c)) return false;
    }
    for (char c = 'a'; c <= 'f'; c++) {
      if (!chars.contains(c)) return false;
    }
    return true;
  }

  /** Check if pattern starts with an anchor. */
  private boolean hasStartAnchor(RegexNode node) {
    if (node instanceof AnchorNode) {
      return ((AnchorNode) node).type == AnchorNode.Type.START;
    } else if (node instanceof ConcatNode) {
      List<RegexNode> children = ((ConcatNode) node).children;
      if (!children.isEmpty() && children.get(0) instanceof AnchorNode) {
        return ((AnchorNode) children.get(0)).type == AnchorNode.Type.START;
      }
    }
    return false;
  }

  /**
   * Analyze pattern and recommend optimal bytecode generation strategy. Returns a
   * MatchingStrategyResult containing the strategy and DFA (if constructed).
   */
  public MatchingStrategyResult analyzeAndRecommend() {
    return analyzeAndRecommend(false);
  }

  /**
   * Analyze pattern and recommend optimal bytecode generation strategy.
   *
   * @param ignoreGroupCount if true, don't force NFA for patterns with groups (used by
   *     RuntimeCompiler for hybrid DFA+NFA approach)
   */
  public MatchingStrategyResult analyzeAndRecommend(boolean ignoreGroupCount) {
    guardTrace.clear();
    MatchingStrategyResult result = doAnalyze(ignoreGroupCount);
    result.guardTrace.addAll(guardTrace);
    result.hasAtomicGroups = hasAtomicGroups(ast);
    return routeBitState(result);
  }

  /**
   * Single post-processing funnel: every {@link MatchingStrategy#PIKEVM_CAPTURE} result produced by
   * {@link #doAnalyze} passes through here before being returned. If the pattern is eligible for
   * the BitState capture engine (see {@link #isBitStateEligible}), substitute {@link
   * MatchingStrategy#BITSTATE_CAPTURE} in place of {@link MatchingStrategy#PIKEVM_CAPTURE}. {@code
   * strategy} is final on {@link MatchingStrategyResult}, so a replacement is a new instance with
   * every other field copied over unchanged.
   */
  private MatchingStrategyResult routeBitState(MatchingStrategyResult result) {
    if (result.strategy != MatchingStrategy.PIKEVM_CAPTURE || !isBitStateEligible(ast)) {
      return result;
    }
    MatchingStrategyResult replaced =
        new MatchingStrategyResult(
            MatchingStrategy.BITSTATE_CAPTURE,
            result.dfa,
            result.patternInfo,
            result.useTaggedDFA,
            result.requiredLiterals,
            result.lookaheadGreedyInfo,
            result.usePosixLastMatch);
    replaced.alternationPriorityConflict = result.alternationPriorityConflict;
    replaced.captureAmbiguous = result.captureAmbiguous;
    replaced.anchorConditionDiluted = result.anchorConditionDiluted;
    replaced.hasAtomicGroups = result.hasAtomicGroups;
    replaced.lazyNfa = result.lazyNfa;
    replaced.guardTrace.addAll(result.guardTrace);
    return replaced;
  }

  private MatchingStrategyResult doAnalyze(boolean ignoreGroupCount) {
    // Extract required literals for indexOf optimization (applicable to all strategies)
    java.util.Set<Character> requiredLiterals = extractRequiredLiterals(ast);

    // Priority 1: Check for context-free features (highest priority - requires recursive descent)
    // These features are beyond regular languages and cannot be handled by DFA/NFA
    // Also route non-greedy quantifiers here since they need backtracking support
    // EXCEPTION: Patterns with simple backreferences should go to backreference handlers instead
    // (SPECIALIZED_BACKREFERENCE, OPTIMIZED_NFA_WITH_BACKREFS handle non-greedy correctly)
    // However, quantified backreferences (\1{2,3}, \1+, etc.) MUST use RECURSIVE_DESCENT
    // UNLESS they are optional group backrefs or fixed-repetition backrefs which have specialized
    // handling
    boolean hasBackrefs = hasBackreferences(ast);
    boolean hasQuantifiedBackrefs = hasQuantifiedBackreferences(ast);

    // Check for optional group backreference patterns BEFORE general quantified backref routing
    // Patterns like ^(a|)\1+b, (a)?\1{2} have specialized bytecode generators
    if (hasBackrefs && hasQuantifiedBackrefs) {
      OptionalGroupBackrefInfo optGroupBackrefInfo = detectOptionalGroupBackref(ast);
      if (optGroupBackrefInfo != null) {
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIONAL_GROUP_BACKREF,
            null,
            optGroupBackrefInfo,
            false,
            requiredLiterals);
      }
    }

    // Check for fixed-repetition backreference patterns BEFORE general quantified backref routing
    // Patterns like (a)\1{8,}, (abc)\1{3} don't require backtracking - just verification loop
    if (hasBackrefs && hasQuantifiedBackrefs) {
      FixedRepetitionBackrefInfo fixedRepBackrefInfo = detectFixedRepetitionBackref(ast);
      if (fixedRepBackrefInfo != null) {
        // B6: decline when a non-empty suffix exists — the bytecode generator places the
        // group-end tag after the suffix is consumed, not after the initial group match.
        // Fall through to OPTIMIZED_NFA_WITH_BACKREFS, which handles group spans correctly.
        if (!fixedRepBackrefInfo.suffix.isEmpty()) {
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS,
              null,
              null,
              false,
              java.util.Collections.emptySet());
        }
        return new MatchingStrategyResult(
            MatchingStrategy.FIXED_REPETITION_BACKREF,
            null,
            fixedRepBackrefInfo,
            false,
            requiredLiterals);
      }
    }

    // Patterns with lazy (non-greedy) quantifiers require PikeVM with lazy-aware NFA construction
    // to produce shortest-match semantics. This block must run BEFORE requiresRecursiveDescentFlag
    // so that safe lazy patterns are intercepted here and do not fall through to RECURSIVE_DESCENT.
    // Guards:
    //  - !hasBackrefs: backref routing below handles these correctly
    //  - !hasLookaround: PikeVM traverses assertions as transparent epsilons (wrong for
    //    lookahead/lookbehind)
    //  - !hasPossessiveQuantifiers: possessive/atomic handled by hasAtomicGroups check below
    //  - !hasSubroutines/!hasConditionals/!hasBranchReset: requiresRecursiveDescentFlag below
    //  - !hasCapturingGroupWithNullableBodyInRepeatableQuantifier: PikeVM merges threads at the
    //    same state across quantifier iterations; nullable-body groups under repeatable quantifiers
    //    produce wrong last-iteration spans (generalisation of B16)
    if (hasNonGreedyQuantifiers(ast)
        && !hasBackrefs
        && !hasLookaround(ast)
        && !hasPossessiveQuantifiers(ast)
        && !hasSubroutines(ast)
        && !hasConditionals(ast)
        && !hasBranchReset(ast)
        && !FallbackPatternDetector.hasCapturingGroupWithNullableBodyInRepeatableQuantifier(ast)) {
      addTrace("lazyQuantifierPikeVm", true);
      MatchingStrategyResult lazyResult =
          new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
      lazyResult.lazyNfa = true;
      return lazyResult;
    }

    // Lazy patterns that did not qualify above (e.g. capturing group with nullable body under a
    // repeated quantifier) fall through here. The hasNonGreedyQuantifiers condition in
    // requiresRecursiveDescentFlag routes them to RECURSIVE_DESCENT, which then falls back to
    // JDK via the lazy-quantifier guard in FallbackPatternDetector.
    boolean requiresRecursiveDescentFlag =
        hasSubroutines(ast)
            || hasConditionals(ast)
            || hasBranchReset(ast)
            || hasQuantifiedBackrefs
            || (hasNonGreedyQuantifiers(ast) && !hasBackrefs);
    addTrace("requiresRecursiveDescent", requiresRecursiveDescentFlag);
    if (requiresRecursiveDescentFlag) {
      return new MatchingStrategyResult(
          MatchingStrategy.RECURSIVE_DESCENT,
          null, // no DFA
          new RecursiveDescentPatternInfo(ast), // includes AST structural hash for caching
          false, // useTaggedDFA
          requiredLiterals);
    }

    // Patterns containing atomic groups (?>...) require a backtracking-aware engine (PikeVM)
    // to enforce the no-backtracking-into-the-group constraint.
    if (hasAtomicGroups(ast)) {
      addTrace("hasAtomicGroups", true);
      return new MatchingStrategyResult(
          MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
    }

    // Phase 2: Detect lookahead+greedy+suffix pattern for reverse scanning optimization
    LookaheadGreedySuffixInfo lookaheadGreedyInfo = detectLookaheadGreedySuffix(ast);
    // Check if pattern is eligible for specialized fast path (bitmap-based matching)
    lookaheadGreedyInfo = detectFastPathEligibility(lookaheadGreedyInfo);

    // Check for pattern-specific optimizations first (highest priority)

    // COUNTING_GLUSHKOV: bounded repetition with large bound, group-free body.
    // Checked before STATELESS_LOOP and SPECIALIZED_FIXED_SEQUENCE so that patterns like \d{11}
    // and (?:ab){20} use the O(n) counter-based engine rather than the simpler but less general
    // stateless or fixed-sequence fast paths.
    {
      CountingGlushkovInfo cgInfo = detectCountingGlushkov();
      if (cgInfo != null) {
        addTrace("COUNTING_GLUSHKOV", true);
        return new MatchingStrategyResult(
            MatchingStrategy.COUNTING_GLUSHKOV, null, cgInfo, false, requiredLiterals);
      }
    }

    // Check for stateless patterns (absolute highest priority - eliminates state tracking overhead)
    StatelessPatternInfo statelessInfo = detectStatelessPattern(ast);
    if (statelessInfo != null) {
      return new MatchingStrategyResult(
          MatchingStrategy.STATELESS_LOOP, null, statelessInfo, false, requiredLiterals);
    }

    // Check for pure literal alternation: keyword1|keyword2|...|keywordN
    LiteralAlternationInfo literalAltInfo = detectLiteralAlternation(ast);
    if (literalAltInfo != null) {
      if (nfa != null && nfa.getGroupCount() > 0) {
        // Pattern has capturing groups: use PikeVM for native group-span extraction.
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
      }
      return new MatchingStrategyResult(
          MatchingStrategy.SPECIALIZED_LITERAL_ALTERNATION,
          null,
          literalAltInfo,
          false,
          requiredLiterals);
    }

    // Check for greedy char class with capturing group: (\d+), ([a-z]*)
    GreedyCharClassInfo greedyInfo = detectGreedyCharClass(ast);
    if (greedyInfo != null) {
      return new MatchingStrategyResult(
          MatchingStrategy.SPECIALIZED_GREEDY_CHARCLASS, null, greedyInfo, false, requiredLiterals);
    }

    // Check for greedy backtrack patterns FIRST: (.*)bar, (.*)(\\d+)
    // These require backtracking where greedy .* must give back characters
    // Must be checked BEFORE multi-group greedy, which doesn't support backtracking
    GreedyBacktrackInfo greedyBacktrackInfo = detectGreedyBacktrackPattern(ast);
    if (greedyBacktrackInfo != null) {
      return new MatchingStrategyResult(
          MatchingStrategy.GREEDY_BACKTRACK, null, greedyBacktrackInfo, false, requiredLiterals);
    }

    // Check for multi-group greedy patterns. Decline give-back patterns: a greedy quantified
    // capturing group whose charset overlaps what must follow needs character give-back that the
    // non-backtracking MULTI_GROUP_GREEDY strategy cannot do — it returns NO_MATCH (e.g. (\w+)0 on
    // "ab00"). Declining lets such patterns fall through to the backtracking-capable routing
    // (the :753 requiresBacktrackingForGroups guard → RECURSIVE_DESCENT), which produces correct
    // spans. (GREEDY_BACKTRACK above already handles the (.*)literal shape.)
    MultiGroupGreedyInfo multiGroupInfo =
        requiresBacktrackingForGroups(ast) ? null : detectMultiGroupGreedyPattern(ast);
    if (multiGroupInfo != null) {
      return new MatchingStrategyResult(
          MatchingStrategy.SPECIALIZED_MULTI_GROUP_GREEDY,
          null,
          multiGroupInfo,
          false,
          requiredLiterals);
    }

    // Check for concat+greedy group patterns: prefix(greedy)suffix
    // Decline when the greedy group's char set overlaps the following suffix (e.g.
    // ([ab0]{2,})[^a]): the non-backtracking fast path commits to the greedy match and cannot give
    // characters back to satisfy the suffix, producing a silent wrong answer. Such patterns fall
    // through to a backtracking-capable strategy.
    ConcatGreedyGroupInfo concatGreedyInfo = detectConcatGreedyGroup(ast);
    if (concatGreedyInfo != null && !requiresBacktrackingForGroups(ast)) {
      return new MatchingStrategyResult(
          MatchingStrategy.SPECIALIZED_CONCAT_GREEDY_GROUP,
          null,
          concatGreedyInfo,
          false,
          requiredLiterals);
    }

    // Check for lookaround assertions
    // Try DFA first for simple literal assertions (e.g., (?<=ab)c, a(?=bc))
    // Fall back to NFA for complex assertions (e.g., (?=.*[A-Z]))
    boolean hasLookaroundFlag = hasLookaround(ast);
    addTrace("hasLookaround", hasLookaroundFlag);
    if (hasLookaroundFlag) {
      // CRITICAL: Check if backrefs reference groups inside lookaheads
      // DFA-based strategies can't track capturing groups, so we must use NFA
      if (hasBackrefToLookaheadCapture(ast)) {
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo,
            hasGroupsInRepeatingQuantifiers(ast));
      }

      try {
        // Attempt DFA construction - will throw UnsupportedOperationException
        // if assertions are too complex (not simple literals)
        SubsetConstructor constructor = new SubsetConstructor();
        DFA dfa = constructor.buildDFAWithAssertions(nfa);

        // Success! Assertions are simple literals, use DFA.
        // However, when a lookahead appears inside a capturing group, the DFA path
        // uses match(substring) for group extraction which breaks lookaheads that
        // need to inspect characters beyond the match boundary. Route to NFA instead.
        if (hasLookaheadInsideCapturingGroup(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo,
              hasGroupsInRepeatingQuantifiers(ast));
        }
        if (FallbackPatternDetector.hasCapturingGroupInQuantifiedSection(ast)) {
          // DFA cannot track per-iteration spans; OPTIMIZED_NFA_WITH_LOOKAROUND handles it
          // correctly.
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo,
              true /* groups in quantifier */);
        }
        // Patterns with lookbehind AND capturing groups cannot recover inner group positions via
        // substring re-match: the re-match strips the context before the match start, making the
        // lookbehind fail on the bare substring. Route to NFA which tracks groups correctly.
        if (nfa.getGroupCount() > 0 && hasLookbehind(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo,
              hasGroupsInRepeatingQuantifiers(ast));
        }
        int stateCount = dfa.getStateCount();
        if (stateCount < 20) {
          return new MatchingStrategyResult(
              MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS, dfa, null, false, requiredLiterals);
        } else if (stateCount < 300) {
          return new MatchingStrategyResult(
              MatchingStrategy.DFA_SWITCH_WITH_ASSERTIONS, dfa, null, false, requiredLiterals);
        } else {
          // Too many states - try hybrid DFA for lookaheads first
          HybridDFALookaheadInfo hybridInfo = detectDFACompatibleLookaheads();
          if (hybridInfo != null && !hybridInfo.assertionDFAs.isEmpty()) {
            // Tier 3: Check if this is a multiple sequential lookaheads pattern
            if (hybridInfo.assertionDFAs.size() >= 2) {
              // NEW: Try to extract literals from lookaheads for indexOf() optimization
              List<LiteralLookaheadInfo> literalLookaheads = extractLiteralLookaheads();
              if (literalLookaheads != null && literalLookaheads.size() >= 2) {
                // All lookaheads have extractable literals - use indexOf() + separate main pattern
                return new MatchingStrategyResult(
                    MatchingStrategy.SPECIALIZED_LITERAL_LOOKAHEADS,
                    null,
                    buildLiteralLookaheadInfo(literalLookaheads, requiredLiterals),
                    false,
                    requiredLiterals,
                    lookaheadGreedyInfo);
              }
              // Fall back to fused DFA-based evaluation
              return new MatchingStrategyResult(
                  MatchingStrategy.SPECIALIZED_MULTIPLE_LOOKAHEADS,
                  null,
                  hybridInfo,
                  false,
                  requiredLiterals,
                  lookaheadGreedyInfo);
            }
            return new MatchingStrategyResult(
                MatchingStrategy.HYBRID_DFA_LOOKAHEAD,
                null,
                hybridInfo,
                false,
                requiredLiterals,
                lookaheadGreedyInfo);
          }
          // No DFA-compatible lookaheads - fall back to pure NFA
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
              null,
              null,
              false,
              requiredLiterals,
              lookaheadGreedyInfo,
              hasGroupsInRepeatingQuantifiers(ast));
        }
      } catch (UnsupportedOperationException e) {
        // Complex assertion pattern (e.g., .*[A-Z]) - try hybrid DFA for lookaheads
        HybridDFALookaheadInfo hybridInfo = detectDFACompatibleLookaheads();
        if (hybridInfo != null && !hybridInfo.assertionDFAs.isEmpty()) {
          // Tier 3: Check if this is a multiple sequential lookaheads pattern
          if (hybridInfo.assertionDFAs.size() >= 2) {
            // NEW: Try to extract literals from lookaheads for indexOf() optimization
            List<LiteralLookaheadInfo> literalLookaheads = extractLiteralLookaheads();
            if (literalLookaheads != null && literalLookaheads.size() >= 2) {
              // All lookaheads have extractable literals - use indexOf() + separate main pattern
              return new MatchingStrategyResult(
                  MatchingStrategy.SPECIALIZED_LITERAL_LOOKAHEADS,
                  null,
                  buildLiteralLookaheadInfo(literalLookaheads, requiredLiterals),
                  false,
                  requiredLiterals,
                  lookaheadGreedyInfo);
            }
            // Fall back to fused DFA-based evaluation
            return new MatchingStrategyResult(
                MatchingStrategy.SPECIALIZED_MULTIPLE_LOOKAHEADS,
                null,
                hybridInfo,
                false,
                requiredLiterals,
                lookaheadGreedyInfo);
          }
          return new MatchingStrategyResult(
              MatchingStrategy.HYBRID_DFA_LOOKAHEAD,
              null,
              hybridInfo,
              false,
              requiredLiterals,
              lookaheadGreedyInfo);
        }
        // No DFA-compatible lookaheads - fall back to pure NFA
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo,
            hasGroupsInRepeatingQuantifiers(ast));
      } catch (StateExplosionException e) {
        // DFA state explosion - try hybrid DFA for lookaheads
        HybridDFALookaheadInfo hybridInfo = detectDFACompatibleLookaheads();
        if (hybridInfo != null && !hybridInfo.assertionDFAs.isEmpty()) {
          // Tier 3: Check if this is a multiple sequential lookaheads pattern
          if (hybridInfo.assertionDFAs.size() >= 2) {
            // NEW: Try to extract literals from lookaheads for indexOf() optimization
            List<LiteralLookaheadInfo> literalLookaheads = extractLiteralLookaheads();
            if (literalLookaheads != null && literalLookaheads.size() >= 2) {
              // All lookaheads have extractable literals - use indexOf() + separate main pattern
              return new MatchingStrategyResult(
                  MatchingStrategy.SPECIALIZED_LITERAL_LOOKAHEADS,
                  null,
                  buildLiteralLookaheadInfo(literalLookaheads, requiredLiterals),
                  false,
                  requiredLiterals,
                  lookaheadGreedyInfo);
            }
            // Fall back to fused DFA-based evaluation
            return new MatchingStrategyResult(
                MatchingStrategy.SPECIALIZED_MULTIPLE_LOOKAHEADS,
                null,
                hybridInfo,
                false,
                requiredLiterals,
                lookaheadGreedyInfo);
          }
          return new MatchingStrategyResult(
              MatchingStrategy.HYBRID_DFA_LOOKAHEAD,
              null,
              hybridInfo,
              false,
              requiredLiterals,
              lookaheadGreedyInfo);
        }
        // No DFA-compatible lookaheads - fall back to pure NFA
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND,
            null,
            null,
            false,
            requiredLiterals,
            lookaheadGreedyInfo,
            hasGroupsInRepeatingQuantifiers(ast));
      }
    }

    // Check for fixed-length sequence patterns
    FixedSequenceInfo fixedInfo = detectFixedSequence(ast);
    if (fixedInfo != null) {
      return new MatchingStrategyResult(
          MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE, null, fixedInfo, false, requiredLiterals);
    }

    // Check for bounded quantifier sequence patterns
    BoundedQuantifierInfo boundedInfo = detectBoundedQuantifierSequence(ast);
    if (boundedInfo != null) {
      return new MatchingStrategyResult(
          MatchingStrategy.SPECIALIZED_BOUNDED_QUANTIFIERS,
          null,
          boundedInfo,
          false,
          requiredLiterals);
    }

    // Check for features requiring special handling
    if (hasBackrefs) {
      // Try to detect optional group backreference patterns: (a)?\1, ^(a)?(b)?\1\2$
      // These patterns have NFA capture ambiguity by design (optional group may not participate),
      // but OPTIONAL_GROUP_BACKREF handles this correctly — check before the ambiguity guard.
      OptionalGroupBackrefInfo optGroupBackrefInfo = detectOptionalGroupBackref(ast);
      if (optGroupBackrefInfo != null) {
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIONAL_GROUP_BACKREF,
            null,
            optGroupBackrefInfo,
            false,
            requiredLiterals);
      }

      // Guard: if any capturing group has a bypass path through the NFA (i.e., there is an
      // execution path that reaches acceptance WITHOUT entering that group), the group's spans
      // are ambiguous — the native backref strategies cannot reliably resolve which thread
      // binding is the priority winner. Route to JDK so group spans are always correct.
      if (nfa != null && nfa.getGroupCount() > 0 && hasNfaCaptureAmbiguity(nfa)) {
        MatchingStrategyResult r =
            new MatchingStrategyResult(
                MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
        r.captureAmbiguous = true;
        return r;
      }

      // Try to detect fixed-repetition backreference patterns: (a)\1{8,}, (abc)\1{3}
      // These don't require backtracking - just verification loop
      FixedRepetitionBackrefInfo fixedRepBackrefInfo = detectFixedRepetitionBackref(ast);
      if (fixedRepBackrefInfo != null && fixedRepBackrefInfo.suffix.isEmpty()) {
        return new MatchingStrategyResult(
            MatchingStrategy.FIXED_REPETITION_BACKREF,
            null,
            fixedRepBackrefInfo,
            false,
            requiredLiterals);
      }
      // B6: if suffix is non-empty, fall through to OPTIMIZED_NFA_WITH_BACKREFS below.

      // Try to detect a structurally-pinned backreference boundary: the group's content charset
      // is disjoint from what follows it, so there is only one candidate boundary - no retry
      // needed.
      PinnedBackreferenceInfo pinnedInfo = detectPinnedBackreference(ast);
      if (pinnedInfo != null) {
        return new MatchingStrategyResult(
            MatchingStrategy.PINNED_BACKREFERENCE, null, pinnedInfo, false, requiredLiterals);
      }

      // Try to detect variable-capture backreference patterns: (.*)\d+\1, (.+)=\1
      // These require backtracking from longest to shortest capture
      VariableCaptureBackrefInfo varCaptureBackrefInfo = detectVariableCaptureBackref(ast);
      if (varCaptureBackrefInfo != null) {
        return new MatchingStrategyResult(
            MatchingStrategy.VARIABLE_CAPTURE_BACKREF,
            null,
            varCaptureBackrefInfo,
            false,
            requiredLiterals);
      }

      // Check if pattern is linear (no alternation/branching) - can use direct execution
      // LINEAR_BACKREFERENCE supports group capture and backreference validation via straight-line
      // bytecode
      // IMPORTANT: Linear backreference strategy does NOT support backtracking, so patterns with
      // variable quantifiers in capturing groups must fall through to NFA_WITH_BACKREFS
      if (LinearPatternDetector.isLinear(ast) && !hasVariableQuantifiersInCapturingGroups(ast)) {
        LinearPatternInfo linearInfo = LinearPatternAnalyzer.analyze(ast, nfa.getGroupCount());
        return new MatchingStrategyResult(
            MatchingStrategy.LINEAR_BACKREFERENCE, null, linearInfo, false, requiredLiterals);
      }

      // Try to detect hardcoded backreference patterns (HTML_TAG, REPEATED_WORD, etc.)
      BackreferencePatternInfo backrefInfo = detectSimpleBackreference(ast);
      if (backrefInfo != null) {
        return new MatchingStrategyResult(
            MatchingStrategy.SPECIALIZED_BACKREFERENCE, null, backrefInfo, false, requiredLiterals);
      }
      // Fall back to generic NFA with backreferences.
      // Do NOT pass requiredLiterals here: the indexOf optimization in findFrom assumes the
      // required literal appears at position 0 of a match, but for NFA-with-backrefs patterns
      // the literal typically appears after a variable-length prefix, so the optimization
      // would skip valid start positions and produce false negatives.
      return new MatchingStrategyResult(
          MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS,
          null,
          null,
          false,
          java.util.Collections.emptySet());
    }

    // Check for OnePass eligibility (highest priority for patterns with groups)
    // B3a: skip OnePass for anchor-only capturing group bodies — the OnePass NFA emits a wrong
    // zero-width span for such groups; they are handled below via PIKEVM_CAPTURE.
    boolean hasAnchorOnlyCapturingGroupFlag =
        nfa != null
            && nfa.getGroupCount() > 0
            && FallbackPatternDetector.hasAnchorOnlyCapturingGroup(ast);
    addTrace("B3a: hasAnchorOnlyCapturingGroup", hasAnchorOnlyCapturingGroupFlag);
    boolean isOnePassEligibleFlag =
        !ignoreGroupCount && nfa.getGroupCount() > 0 && isOnePassEligible();
    addTrace("isOnePassEligible", isOnePassEligibleFlag && !hasAnchorOnlyCapturingGroupFlag);
    if (!ignoreGroupCount
        && nfa.getGroupCount() > 0
        && isOnePassEligibleFlag
        && !hasAnchorOnlyCapturingGroupFlag) {
      return new MatchingStrategyResult(
          MatchingStrategy.ONEPASS_NFA, null, null, false, requiredLiterals);
    }

    // Patterns with capturing groups: DFA group tracking works for most patterns
    // Phase 3: Try Tagged DFA first, fall back to NFA only if construction fails
    if (!ignoreGroupCount && nfa.getGroupCount() > 0) {
      // Check if pattern has groups inside repeating quantifiers (needs POSIX semantics)
      boolean needsPosixSemantics = hasGroupsInRepeatingQuantifiers(ast);

      // B3a: anchor-only group body — OnePass NFA emits wrong zero-width span.
      if (hasAnchorOnlyCapturingGroupFlag) {
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE,
            null,
            null,
            false,
            requiredLiterals,
            null,
            needsPosixSemantics);
      }

      // Try specialized strategies for patterns with quantified groups
      // These provide correct POSIX last-match semantics for group capture
      if (needsPosixSemantics) {
        // Try specialized quantified group strategy first
        QuantifiedGroupInfo quantifiedGroupInfo = detectQuantifiedCapturingGroup(ast);
        if (quantifiedGroupInfo != null) {
          return new MatchingStrategyResult(
              MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP,
              null,
              quantifiedGroupInfo,
              false,
              requiredLiterals);
        }

        // Try concatenated quantified groups: (a+)+(b+)+
        ConcatQuantifiedGroupsInfo concatInfo = detectConcatQuantifiedGroups(ast);
        if (concatInfo != null) {
          return new MatchingStrategyResult(
              MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP,
              null,
              concatInfo,
              false,
              requiredLiterals);
        }

        // Try nested quantified groups: ((a|bc)+)*, ((a+|b)*)?c
        NestedQuantifiedGroupsInfo nestedInfo = detectNestedQuantifiedGroups(ast);
        if (nestedInfo != null) {
          return new MatchingStrategyResult(
              MatchingStrategy.NESTED_QUANTIFIED_GROUPS, null, nestedInfo, false, requiredLiterals);
        }

        // No specialized strategy - fall through to DFA
        // Note: DFA gives correct match results but group capture may not follow
        // POSIX last-match semantics (groups may contain first match instead of last)
      }

      // B4: greedy .+ group followed by a non-group suffix — the GREEDY_BACKTRACK indexOf scan
      // overshoots on inputs ending with '\n' because '.' (ANY_EXCEPT_NEWLINE) cannot consume '\n'
      // but the literal-suffix scan stops there. Route to PIKEVM_CAPTURE which handles this
      // correctly. Patterns with a capturing-group suffix are excluded (different scan path).
      boolean b4Flag =
          FallbackPatternDetector.hasGreedyDotPlusGroupWithSuffix(ast)
              && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast);
      addTrace("B4: hasGreedyDotPlusGroupWithSuffix", b4Flag);
      if (b4Flag) {
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE,
            null,
            null,
            false,
            requiredLiterals,
            null,
            needsPosixSemantics);
      }

      // Check if pattern requires backtracking for correct group capture
      // Pattern a([bc]*)(c+d) needs backtracking: ([bc]*) must give back chars to allow (c+d) to
      // match
      if (requiresBacktrackingForGroups(ast)) {
        return new MatchingStrategyResult(
            MatchingStrategy.RECURSIVE_DESCENT,
            null,
            new RecursiveDescentPatternInfo(ast),
            false,
            requiredLiterals);
      }

      // Try DFA with Tagged group tracking
      try {
        SubsetConstructor constructor = new SubsetConstructor();
        // Build DFA with tag computation enabled for Tagged DFA
        DFA dfa = constructor.buildDFA(nfa, true);

        if (hasMisplacedStartAnchorInAlternation(ast)
            && !dfaHasAcceptingStateWithTransitions(dfa)) {
          // Anchor condition diluted in DFA (misplaced anchor in alternation or
          // string-end anchor in alternation). OPTIMIZED_NFA handles anchors as
          // zero-width NFA assertions and gives correct JDK-compatible results.
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        boolean b3bCapFlag =
            (hasStringEndAnchorInAlternation(ast) || hasEndAnchorLeadingInAlternationBranch(ast))
                && !dfaHasAcceptingStateWithTransitions(dfa);
        addTrace("B3b: hasStringEndAnchorInAlternation", b3bCapFlag);
        if (b3bCapFlag) {
          // \Z in alternation with capturing groups: PIKEVM_CAPTURE handles anchors correctly.
          // OPTIMIZED_NFA would be rejected by needsFallback for this combination.
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        // Anchor-diluted patterns: PIKEVM_CAPTURE gives correct leftmost-first semantics for
        // all anchor types. Dilution occurs when the DFA subset construction merges NFA states
        // with disjoint anchor conditions (e.g. ^x and x(y) sharing the same DFA state), causing
        // the DFA to lose the anchor guard. PikeVMMatcher.checkAnchor evaluates all anchor types
        // correctly against the actual search position, so PIKEVM is safe for all diluted shapes —
        // not just alternation patterns. The alternation+accepting-transitions guard is removed.
        addTrace("isAnchorConditionDiluted", dfa.isAnchorConditionDiluted());
        if (dfa.isAnchorConditionDiluted()) {
          // Anchor condition diluted in DFA: capture-ambiguous patterns are safe for PikeVM
          // because PikeVM evaluates anchors natively per position (via checkAnchor) and tracks
          // captures per thread. Non-capture-ambiguous patterns fall back to OPTIMIZED_NFA.
          if (dfa.isCaptureAmbiguous()) {
            return new MatchingStrategyResult(
                MatchingStrategy.PIKEVM_CAPTURE,
                null,
                null,
                false,
                requiredLiterals,
                null,
                needsPosixSemantics);
          }
          MatchingStrategyResult r =
              new MatchingStrategyResult(
                  MatchingStrategy.OPTIMIZED_NFA,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
          r.anchorConditionDiluted = true;
          return r;
        }

        // Residual decline: the acceptIsPriorityCut ordering computation is reliable only for
        // patterns with no quantifiers anywhere (pure fixed-length alternations like (fo|foo),
        // (a|ab), (aa|a)a) AND where the DFA start state is not accepting (no empty-matching
        // alternative like () or (.)? at the start). When either condition fails, NFA-sharing
        // artifacts or accepting-start-state group-action problems produce wrong results.
        // Additionally decline unnamed non-captureAmbiguous patterns with both alternation and
        // quantifiers: the TDFA priority-ordering computation is unreliable for these because
        // optional leading elements (e.g. -?(-?.{3}b).) cause incorrect group-END positions.
        // Capture-ambiguous patterns (already handled via the C2 priority-ordered TDFA) and
        // named-group patterns are exempt.
        boolean quantifiedAltWithGroupBug =
            containsAlternation(ast)
                && containsAnyQuantifier(ast)
                && !hasNamedGroups(ast)
                && !dfa.isCaptureAmbiguous();
        // Safe sub-case: quantifiedAltWithGroupBug patterns without anchors and without quantified
        // capturing groups route to PIKEVM_CAPTURE (Pike VM, leftmost-first, correct group spans).
        // The nullable-branch exclusion was removed: ThompsonBuilder now wraps {0,n} fragments
        // in a skip-entry state so the PikeVM correctly handles greedy quantifiers with zero-rep.
        if (quantifiedAltWithGroupBug
            && !hasAnchorInNfa(nfa)
            && !hasQuantifiedCapturingGroup(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        // DFA-condition sub-case and remaining patterns: JDK fallback.
        if ((containsAlternation(ast) || containsOptionalQuantifier(ast))
            && (quantifiedAltWithGroupBug
                || (containsAnyQuantifier(ast)
                    ? dfaHasAcceptingStateWithTransitions(dfa)
                    : (dfa.getStartState().accepting
                        || hasUnresolvedAcceptingTransitionState(dfa))))) {
          // Alternation priority conflict: PikeVM gives correct first-alternative NFA semantics.
          // Exclude quantified capturing groups with complex bodies (nested quantifier or anchor
          // inside the group body) — those can diverge in PikeVM.
          // Simple bodies like (a|b)+ are safe: no inner quantifier, no inner anchor.
          if (!hasComplexQuantifiedCapturingGroup(ast)) {
            return new MatchingStrategyResult(
                MatchingStrategy.PIKEVM_CAPTURE,
                null,
                null,
                false,
                requiredLiterals,
                null,
                needsPosixSemantics);
          }
          MatchingStrategyResult r =
              new MatchingStrategyResult(
                  MatchingStrategy.OPTIMIZED_NFA,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
          r.alternationPriorityConflict = true;
          return r;
        }

        addTrace("isCaptureAmbiguous → DFA_UNROLLED_WITH_GROUPS", dfa.isCaptureAmbiguous());
        if (dfa.isCaptureAmbiguous()) {
          // For pure-regular, anchor-free patterns the C2 priority-ordered TDFA gives correct
          // spans and can use an inline DFA strategy when the state count is small enough.
          // Patterns with anchors or named groups are also safe for PIKEVM_CAPTURE: PikeVM
          // handles all anchor types natively (since commit 0acfc66), and RuntimeCompiler wraps
          // the result in NameEnrichingMatcher when named groups are present.
          if (!hasNamedGroups(ast) && !hasAnchorInNfa(nfa)) {
            // INVARIANT for any new Class A route that returns PIKEVM_CAPTURE for patterns
            // containing nullable capturing groups in alternation branches:
            // always guard with
            // !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)
            // before returning PIKEVM_CAPTURE, as PikeVM diverges for nullable-content groups
            // (e.g. (0*-?){0,}). RuntimeCompiler also enforces this via needsFallback(), but
            // the PatternAnalyzer guard is the first line of defence.
            // B16: nullable outer quantifier on non-nullable capturing group — TDFA POSIX
            // last-match span wrong. PIKEVM gives correct spans when the group content itself is
            // non-nullable; nullable-content groups (e.g. (0*-?){0,}) are left on the TDFA path
            // and caught by needsFallback.
            if (FallbackPatternDetector.hasNullableOuterQuantifierOnCapturingGroup(ast)
                && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // B10: optional prefix before capturing group — TDFA group-start computation wrong.
            if (FallbackPatternDetector.hasOptionalPrefixBeforeCapturingGroup(ast)) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // B15: capturing group inside quantified alternation — TDFA thread ordering wrong.
            if (FallbackPatternDetector.containsAlternation(ast)
                && FallbackPatternDetector.hasCapturingGroupInQuantifiedSection(ast)) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // Class A: a NULLABLE capturing group in an alternation branch (e.g. 1|()b, ()b|x). The
            // TDFA/group-action capture path commits the zero-width group even when the
            // priority-winning branch bypasses it (binds g1=[0,0); JDK leaves it -1). PikeVM gives
            // correct spans. A non-nullable group like (a) in (a)|b never leaks and stays on the
            // DFA.
            if (FallbackPatternDetector.hasNullableCapturingGroupInAlternationBranch(ast)
                && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // A1: group body starts with a nullable first element — TDFA fires the group-start
            // tag at an epsilon-reachable state, recording match-start instead of group-start.
            boolean a1Flag =
                FallbackPatternDetector.hasCapturingGroupWithNullableFirstElement(ast)
                    && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast);
            addTrace("A1: hasCapturingGroupWithNullableFirstElement", a1Flag);
            if (a1Flag) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // A2: capturing group absent from some alternation branch — TDFA binds the absent
            // group to a wrong span when the branch that lacks the group wins.
            boolean a2Flag =
                FallbackPatternDetector.hasCapturingGroupAbsentFromSomeAlternative(ast)
                    && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast);
            addTrace("A2: hasCapturingGroupAbsentFromSomeAlternative", a2Flag);
            if (a2Flag) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // B4: greedy .+ group followed by a suffix — TDFA extends group-end into the suffix.
            boolean b4CaFlag =
                FallbackPatternDetector.hasGreedyDotPlusGroupWithSuffix(ast)
                    && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast);
            addTrace("B4: hasGreedyDotPlusGroupWithSuffix (captureAmbiguous)", b4CaFlag);
            if (b4CaFlag) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // B5: group body is an alternation whose branches have different min- or max-lengths.
            boolean b5CaFlag =
                FallbackPatternDetector.hasGroupWithVariableLengthAlternationBody(ast)
                    && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast);
            addTrace("B5: hasGroupWithVariableLengthAlternationBody (captureAmbiguous)", b5CaFlag);
            if (b5CaFlag) {
              return new MatchingStrategyResult(
                  MatchingStrategy.PIKEVM_CAPTURE,
                  null,
                  null,
                  false,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
            // Pure-regular, anchor-free: C2 priority-ordered TDFA gives correct spans.
            int stateCount = dfa.getStateCount();
            addTrace(
                "DFA state count → DFA_UNROLLED / DFA_SWITCH / OPTIMIZED_NFA (stateCount="
                    + stateCount
                    + ")",
                true);
            if (stateCount < DFA_UNROLLED_STATE_LIMIT) {
              return new MatchingStrategyResult(
                  MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
                  dfa,
                  null,
                  true,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            } else if (stateCount < DFA_SWITCH_STATE_LIMIT) {
              return new MatchingStrategyResult(
                  MatchingStrategy.DFA_SWITCH_WITH_GROUPS,
                  dfa,
                  null,
                  true,
                  requiredLiterals,
                  null,
                  needsPosixSemantics);
            }
          }
          // Named groups, anchors, or too many DFA states: PikeVM gives correct O(n·m) spans.
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }

        // DFA with groups: choose strategy based on state count.
        // Gates for B16/B10/B15: TDFA cannot correctly compute group spans for these; PIKEVM can.
        // B16: only when group content is non-nullable; nullable-content case left for
        // needsFallback.
        if (FallbackPatternDetector.hasNullableOuterQuantifierOnCapturingGroup(ast)
            && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        if (FallbackPatternDetector.hasOptionalPrefixBeforeCapturingGroup(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        if (FallbackPatternDetector.containsAlternation(ast)
            && FallbackPatternDetector.hasCapturingGroupInQuantifiedSection(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        // A1: group body starts with a nullable first element — TDFA fires the group-start
        // tag at an epsilon-reachable state, recording match-start instead of group-start.
        if (FallbackPatternDetector.hasCapturingGroupWithNullableFirstElement(ast)
            && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        // A2: capturing group absent from some alternation branch — TDFA binds the absent
        // group to a wrong span when the branch that lacks the group wins.
        if (FallbackPatternDetector.hasCapturingGroupAbsentFromSomeAlternative(ast)
            && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        // B5: group body is an alternation whose branches have different min- or max-lengths
        // and at least one branch contains a broad charset — TDFA cannot deterministically assign
        // the group-end tag when a broad-charset alternative competes with the suffix.
        if (FallbackPatternDetector.hasGroupWithVariableLengthAlternationBody(ast)
            && !FallbackPatternDetector.hasNullableGroupContentWithNullableQuantifier(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        // Class E: two interacting variable-length capturing alternations (e.g. (a|ab)(c|bcd)). The
        // first alternation's branches share a prefix, so its capture span is ambiguous until the
        // second alternation resolves it — which the single-register TDFA cannot track
        // ((a|ab)(c|bcd)
        // on "abcd" → g1=[0,2) vs JDK [0,1)). PikeVM gives correct spans. A single capturing
        // alternation followed by a fixed element (e.g. (a|ab)\d) is disambiguated
        // deterministically
        // and stays on the DFA.
        if (hasInteractingCapturingAlternations(ast)) {
          return new MatchingStrategyResult(
              MatchingStrategy.PIKEVM_CAPTURE,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
        int stateCount = dfa.getStateCount();
        if (stateCount < DFA_UNROLLED_STATE_LIMIT) {
          return new MatchingStrategyResult(
              MatchingStrategy.DFA_UNROLLED_WITH_GROUPS,
              dfa,
              null,
              true,
              requiredLiterals,
              null,
              needsPosixSemantics);
        } else if (stateCount < DFA_SWITCH_STATE_LIMIT) {
          // Use switch-based DFA for medium state counts (better cache behavior)
          return new MatchingStrategyResult(
              MatchingStrategy.DFA_SWITCH_WITH_GROUPS,
              dfa,
              null,
              true,
              requiredLiterals,
              null,
              needsPosixSemantics);
        } else {
          // Too many states for DFA, fall back to NFA
          return new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA,
              null,
              null,
              false,
              requiredLiterals,
              null,
              needsPosixSemantics);
        }
      } catch (StateExplosionException e) {
        // Tagged-DFA determinization failed (>10k states). isCaptureAmbiguous() is unavailable
        // (DFA not built), so all patterns — including those with named groups or anchors — fall
        // to PikeVM, which gives correct O(n·m) spans and handles anchors natively.
        // RuntimeCompiler wraps the result in NameEnrichingMatcher when named groups are present.
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE,
            null,
            null,
            false,
            requiredLiterals,
            null,
            needsPosixSemantics);
      }
    }

    // Try standard DFA construction (lookaround already handled above)
    MatchingStrategyResult result;
    try {
      SubsetConstructor constructor = new SubsetConstructor();
      DFA dfa = constructor.buildDFA(nfa);

      // A START-class anchor placed after a consumer inside an alternation branch makes that branch
      // unsatisfiable in find context, but the DFA mishandles it (treats \A/^ as match-start, not
      // input-start). Decline the DFA fast path so we route to a correct strategy / JDK fallback.
      // Only use OPTIMIZED_NFA when there's no accepting-state-with-transitions (no alternation
      // priority conflict); otherwise fall through to the priority-conflict handling below.
      if (hasMisplacedStartAnchorInAlternation(ast) && !dfaHasAcceptingStateWithTransitions(dfa)) {
        // Anchor condition diluted in DFA (misplaced anchor in alternation or
        // string-end anchor in alternation). OPTIMIZED_NFA handles anchors as
        // zero-width NFA assertions and gives correct JDK-compatible results.
        return new MatchingStrategyResult(
            MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
      }
      boolean b3bFlag =
          (hasStringEndAnchorInAlternation(ast) || hasEndAnchorLeadingInAlternationBranch(ast))
              && !dfaHasAcceptingStateWithTransitions(dfa);
      addTrace("B3b: hasStringEndAnchorInAlternation", b3bFlag);
      if (b3bFlag) {
        // \Z or $ in alternation: OPTIMIZED_NFA mishandles find() anchor semantics;
        // route to PIKEVM_CAPTURE which handles \Z/$ correctly.
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
      }
      // Alternation with any accepting DFA state with transitions: PIKEVM_CAPTURE gives correct
      // leftmost-first semantics for nullable/optional/end-anchor alternation branches. Previous
      // exclusions for hasNullableAlternationBranch, subtreeContainsOptional, and
      // hasEndAnchorLeadingInAlternationBranch are removed: ThompsonBuilder now wraps {0,n}
      // fragments in a skip-entry state (preventing mixed char+epsilon states), and
      // PikeVMMatcher.checkAnchor correctly handles the END ($) anchor before a trailing newline.
      // Start-anchors (^, \A) in leading position are safe; the PikeVMMatcher fix ensures they
      // evaluate against the fixed search-region origin, not the per-attempt try-position.
      // This block runs BEFORE the isAnchorConditionDiluted guard below: a diluted-anchor
      // pattern (e.g. ^c|[^1][b]) is handled correctly by PIKEVM, whereas OPTIMIZED_NFA
      // (the dilution fallback target) shares the old find() anchor bug.
      boolean altWithAcceptingTransFlag =
          containsAlternation(ast) && dfaHasAcceptingStateWithTransitions(dfa);
      addTrace(
          "containsAlternation && dfaHasAcceptingStateWithTransitions", altWithAcceptingTransFlag);
      if (altWithAcceptingTransFlag) {
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
      }
      // Anchor-diluted: same as the capturing-group path — PIKEVM_CAPTURE evaluates anchors
      // correctly at each search position, whereas OPTIMIZED_NFA mishandles diluted conditions.
      // anchorConditionDiluted=true on the result signals RuntimeCompiler's hybrid pre-check to
      // skip the hybrid DFA path (a diluted DFA is not safe for the fast-matching pass).
      addTrace("isAnchorConditionDiluted (no-group path)", dfa.isAnchorConditionDiluted());
      if (dfa.isAnchorConditionDiluted()) {
        MatchingStrategyResult r =
            new MatchingStrategyResult(
                MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
        r.anchorConditionDiluted = true;
        return r;
      }

      if (FallbackPatternDetector.hasCapturingGroupInQuantifiedSection(ast)) {
        // DFA cannot track per-iteration spans; PIKEVM_CAPTURE handles capturing groups correctly.
        return new MatchingStrategyResult(
            MatchingStrategy.PIKEVM_CAPTURE, null, null, false, requiredLiterals);
      }
      // Choose DFA strategy based on state count
      int stateCount = dfa.getStateCount();
      addTrace(
          "DFA state count → DFA_UNROLLED / DFA_SWITCH / OPTIMIZED_NFA (stateCount="
              + stateCount
              + ")",
          true);
      if (stateCount < DFA_UNROLLED_STATE_LIMIT) {
        return new MatchingStrategyResult(
            MatchingStrategy.DFA_UNROLLED, dfa, null, false, requiredLiterals);
      } else if (stateCount < DFA_SWITCH_STATE_LIMIT) {
        // Use switch-based DFA for medium state counts (better cache behavior)
        return new MatchingStrategyResult(
            MatchingStrategy.DFA_SWITCH, dfa, null, false, requiredLiterals);
      } else if (GlushkovAutomaton.from(nfa) != null) {
        // Large DFA but a small (<=63-position) NFA: run it as a single-word bit-parallel
        // simulation instead of a table walk. Reaching here means the alternation priority-cut
        // decline above did not fire, so leftmost-longest coincides with leftmost-first.
        return new MatchingStrategyResult(
            MatchingStrategy.BITPARALLEL_GLUSHKOV, null, null, false, requiredLiterals);
      } else if (isDFATableEligible(dfa)) {
        return new MatchingStrategyResult(
            MatchingStrategy.DFA_TABLE, dfa, null, false, requiredLiterals);
      } else {
        // Large DFA not eligible for table backend; fall through to LAZY_DFA promotion check.
        result =
            new MatchingStrategyResult(
                MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
      }
    } catch (StateExplosionException e) {
      // DFA too large to construct. An alternation-free pattern is greedy-only and therefore
      // leftmost-longest-safe even without the DFA priority-cut analysis, so a small-NFA one can
      // still take the bit-parallel path; otherwise fall through to LAZY_DFA promotion.
      if (!containsAlternation(ast) && GlushkovAutomaton.from(nfa) != null) {
        return new MatchingStrategyResult(
            MatchingStrategy.BITPARALLEL_GLUSHKOV, null, null, false, requiredLiterals);
      }
      result =
          new MatchingStrategyResult(
              MatchingStrategy.OPTIMIZED_NFA, null, null, false, requiredLiterals);
    }
    // Promote large anchor-free group-free NFA patterns to the lazy DFA strategy.
    if (result.strategy == MatchingStrategy.OPTIMIZED_NFA
        && nfa != null
        && isLazyDFAEligible(nfa, ast)) {
      result =
          new MatchingStrategyResult(
              MatchingStrategy.LAZY_DFA,
              result.dfa,
              result.patternInfo,
              result.useTaggedDFA,
              result.requiredLiterals,
              result.lookaheadGreedyInfo,
              result.usePosixLastMatch);
    }
    return result;
  }

  private boolean isLazyDFAEligible(NFA nfa, RegexNode ast) {
    return nfa.getStates().size() >= 300
        && !hasCapturingGroups(ast)
        && nfa.getStates().stream().noneMatch(s -> s.anchor != null);
  }

  private boolean hasCapturingGroups(RegexNode node) {
    CapturingGroupDetector detector = new CapturingGroupDetector();
    return node.accept(detector);
  }

  private boolean hasAnchorInNfa(NFA nfa) {
    if (nfa == null) return false;
    for (NFA.NFAState s : nfa.getStates()) {
      if (s.anchor != null) return true;
    }
    return false;
  }

  private boolean hasNamedGroups(RegexNode node) {
    if (node instanceof GroupNode g && g.name != null) return true;
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) if (hasNamedGroups(child)) return true;
    }
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) if (hasNamedGroups(alt)) return true;
    }
    if (node instanceof QuantifierNode q) return hasNamedGroups(q.child);
    return false;
  }

  /**
   * Returns true if any capturing group in the NFA has a "bypass path" — a route from the start
   * state to an accept state that does not pass through that group's enter marker. When a bypass
   * exists, native strategies cannot reliably determine which thread's group binding wins; the
   * pattern must be delegated to java.util.regex.
   */
  /**
   * Returns true if any capturing group in the NFA has a "bypass path" — a route from the NFA start
   * to an accept state that does not cross that group's enter marker. When a bypass exists, native
   * strategies cannot reliably resolve which thread's group binding wins, so the pattern must be
   * delegated to java.util.regex.
   */
  private boolean hasNfaCaptureAmbiguity(NFA nfa) {
    Set<NFA.NFAState> acceptStates = nfa.getAcceptStates();
    int groupCount = nfa.getGroupCount();
    for (int g = 1; g <= groupCount; g++) {
      if (canReachAcceptWithoutEnteringGroupNfa(nfa.getStartState(), g, acceptStates)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Step-by-step BFS (epsilon + character transitions). Stops expanding any branch at a state with
   * {@code enterGroup == g}. Returns true if an accept state is reachable without ever crossing
   * group g's enter marker — i.e., the NFA can accept with group g never entered.
   */
  private boolean canReachAcceptWithoutEnteringGroupNfa(
      NFA.NFAState start, int g, Set<NFA.NFAState> acceptStates) {
    Set<NFA.NFAState> visited = new java.util.HashSet<>();
    java.util.Queue<NFA.NFAState> queue = new java.util.ArrayDeque<>();
    queue.add(start);
    while (!queue.isEmpty()) {
      NFA.NFAState cur = queue.poll();
      if (!visited.add(cur)) continue;
      // Block on entering group g — this branch took the group, not a bypass
      if (cur.enterGroup != null && cur.enterGroup == g) continue;
      if (acceptStates.contains(cur)) return true;
      for (NFA.NFAState eps : cur.getEpsilonTransitions()) {
        if (!visited.contains(eps)) queue.add(eps);
      }
      for (NFA.Transition t : cur.getTransitions()) {
        if (!visited.contains(t.target)) queue.add(t.target);
      }
    }
    return false;
  }

  private boolean isDFATableEligible(DFA dfa) {
    if (nfa != null
        && (nfa.getGroupCount() > 0
            || nfa.hasStartAnchor()
            || nfa.hasEndAnchor()
            || nfa.hasStringStartAnchor()
            || nfa.hasStringEndAnchor()
            || nfa.hasStringEndAbsoluteAnchor()
            || nfa.hasMultilineStartAnchor()
            || nfa.hasMultilineEndAnchor())) {
      return false;
    }

    for (DFA.DFAState state : dfa.getAllStates()) {
      if (!state.assertionChecks.isEmpty()
          || !state.groupActions.isEmpty()
          || !state.acceptanceAnchorConditions.isEmpty()) {
        return false;
      }
      for (DFA.DFATransition transition : state.transitions.values()) {
        if (!transition.tagOps.isEmpty() || !transition.entryGuard.isEmpty()) {
          return false;
        }
      }
    }

    return DFATableData.from(dfa).estimatedBytes() <= DFA_TABLE_ESTIMATED_BYTES_LIMIT;
  }

  private boolean hasBackreferences(RegexNode node) {
    BackrefDetector detector = new BackrefDetector();
    return node.accept(detector);
  }

  /**
   * Returns true if the AST contains an optional quantifier (min=0) INSIDE a capturing group that
   * is itself in a repeating quantifier (outer + or * or {n,m} with n<m). This is the structural
   * pattern that causes DFA/NFA divergence: the optional element creates ambiguity within each
   * group iteration, and the repeating outer loop amplifies it by letting the DFA choose a
   * different (longer) path across iterations than the JDK NFA would.
   *
   * <p>Patterns like (a*b*c*d*e*) are NOT flagged because the group is not inside a repeating
   * quantifier — there is no loop that could cause the DFA to accumulate extra chars.
   */
  private boolean containsOptionalQuantifier(RegexNode node) {
    return hasOptionalInsideRepeatingGroup(node);
  }

  private boolean hasOptionalInsideRepeatingGroup(RegexNode node) {
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      // Repeating group: outer quantifier with max>1 and the child is a group
      if ((q.max == -1 || q.max > 1) && q.min >= 1 && q.child instanceof GroupNode) {
        if (subtreeContainsOptional(q.child)) return true;
      }
      return hasOptionalInsideRepeatingGroup(q.child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children)
        if (hasOptionalInsideRepeatingGroup(c)) return true;
      return false;
    }
    if (node instanceof GroupNode) return hasOptionalInsideRepeatingGroup(((GroupNode) node).child);
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives)
        if (hasOptionalInsideRepeatingGroup(a)) return true;
      return false;
    }
    return false;
  }

  /** Returns true if the subtree contains any QuantifierNode with min=0. */
  private static boolean subtreeContainsOptional(RegexNode node) {
    if (node instanceof QuantifierNode) {
      if (((QuantifierNode) node).min == 0) return true;
      return subtreeContainsOptional(((QuantifierNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) if (subtreeContainsOptional(c)) return true;
      return false;
    }
    if (node instanceof GroupNode) return subtreeContainsOptional(((GroupNode) node).child);
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives)
        if (subtreeContainsOptional(a)) return true;
      return false;
    }
    return false;
  }

  /** Returns true when the AST contains any quantifier node (*, +, ?, {n,m}). */
  private boolean containsAnyQuantifier(RegexNode node) {
    if (node instanceof QuantifierNode) return true;
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children)
        if (containsAnyQuantifier(child)) return true;
      return false;
    }
    if (node instanceof GroupNode) return containsAnyQuantifier(((GroupNode) node).child);
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives)
        if (containsAnyQuantifier(alt)) return true;
      return false;
    }
    return false;
  }

  private boolean containsAlternation(RegexNode node) {
    if (node instanceof AlternationNode) return true;
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (containsAlternation(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode) return containsAlternation(((GroupNode) node).child);
    if (node instanceof QuantifierNode) return containsAlternation(((QuantifierNode) node).child);
    return false;
  }

  /**
   * Returns true if the AST contains any alternation with at least one nullable (empty-matching)
   * branch. PIKEVM_CAPTURE produces incorrect group spans for such patterns.
   */
  private boolean hasNullableAlternationBranch(RegexNode node) {
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) {
        // LiteralNode(ch=0) is the parser's epsilon sentinel for syntactically empty branches
        // (e.g. the trailing arm of "a|" or the body of "()"). isNullable does not handle it.
        if ((alt instanceof LiteralNode l && l.ch == 0)
            || isNullable(alt)
            || hasNullableAlternationBranch(alt)) return true;
      }
      return false;
    }
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        if (hasNullableAlternationBranch(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode g) return hasNullableAlternationBranch(g.child);
    if (node instanceof QuantifierNode q) return hasNullableAlternationBranch(q.child);
    return false;
  }

  /**
   * Returns true if any alternation branch has an end-anchor ({@code $}, {@code \Z}, {@code \z}) in
   * a leading position — i.e., reachable before consuming any character. PIKEVM's {@code find()}
   * evaluates leading end-anchors during epsilon-closure, which can diverge from JDK (e.g. {@code
   * a|$} or {@code $x|y}). Start-anchors ({@code ^}, {@code \A}) in leading position are safe after
   * the PikeVMMatcher anchor-reference fix.
   */
  private static boolean hasEndAnchorLeadingInAlternationBranch(RegexNode node) {
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) {
        if (branchLeadsWithEndAnchor(alt)) return true;
        if (hasEndAnchorLeadingInAlternationBranch(alt)) return true;
      }
      return false;
    }
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        if (hasEndAnchorLeadingInAlternationBranch(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode g) return hasEndAnchorLeadingInAlternationBranch(g.child);
    if (node instanceof QuantifierNode q) return hasEndAnchorLeadingInAlternationBranch(q.child);
    return false;
  }

  /**
   * Returns true if the first reachable node in {@code branch} (skipping group wrappers) is an
   * end-anchor ({@code $}, {@code \Z}, {@code \z}).
   */
  private static boolean branchLeadsWithEndAnchor(RegexNode branch) {
    if (branch instanceof AnchorNode a) {
      return a.type == AnchorNode.Type.END
          || a.type == AnchorNode.Type.STRING_END
          || a.type == AnchorNode.Type.STRING_END_ABSOLUTE;
    }
    if (branch instanceof GroupNode g) return branchLeadsWithEndAnchor(g.child);
    if (branch instanceof QuantifierNode q) return branchLeadsWithEndAnchor(q.child);
    if (branch instanceof ConcatNode c && !c.children.isEmpty()) {
      return branchLeadsWithEndAnchor(c.children.get(0));
    }
    return false;
  }

  /**
   * Returns true if any capturing group in the AST is directly wrapped by a quantifier node (i.e.
   * the group itself is quantified, like {@code (a|b)+}). PIKEVM_CAPTURE produces incorrect group
   * spans for such patterns because repeated group captures overwrite earlier spans. Note: a
   * quantified non-capturing group that wraps a capturing group (e.g. {@code (?:(a|b))+}) is not
   * detected here; however, such patterns are capture-ambiguous and excluded earlier via {@code
   * isCaptureAmbiguous}, so they never reach this check.
   */
  private boolean hasQuantifiedCapturingGroup(RegexNode node) {
    if (node instanceof QuantifierNode q && q.child instanceof GroupNode g && g.capturing) {
      return true;
    }
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        if (hasQuantifiedCapturingGroup(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode g) return hasQuantifiedCapturingGroup(g.child);
    if (node instanceof QuantifierNode q) return hasQuantifiedCapturingGroup(q.child);
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) {
        if (hasQuantifiedCapturingGroup(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /**
   * Returns true if any quantified capturing group in the subtree has a body that contains a nested
   * quantifier or anchor. Such groups can diverge in PikeVM for alternation-priority-conflict
   * patterns (fuzz finding: ([^a]{0,}\z|.){1,}). Simple groups like (a|b) return false.
   */
  private boolean hasComplexQuantifiedCapturingGroup(RegexNode node) {
    if (node instanceof QuantifierNode q && q.child instanceof GroupNode g && g.capturing) {
      if (containsAnyQuantifier(g.child) || containsAnchorInSubtree(g.child)) {
        return true;
      }
    }
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        if (hasComplexQuantifiedCapturingGroup(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode g) return hasComplexQuantifiedCapturingGroup(g.child);
    if (node instanceof QuantifierNode q) return hasComplexQuantifiedCapturingGroup(q.child);
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) {
        if (hasComplexQuantifiedCapturingGroup(alt)) return true;
      }
      return false;
    }
    return false;
  }

  private static boolean containsAnchorInSubtree(RegexNode node) {
    if (node instanceof AnchorNode) return true;
    if (node instanceof ConcatNode c) {
      for (RegexNode child : c.children) {
        if (containsAnchorInSubtree(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode g) return containsAnchorInSubtree(g.child);
    if (node instanceof QuantifierNode q) return containsAnchorInSubtree(q.child);
    if (node instanceof AlternationNode a) {
      for (RegexNode alt : a.alternatives) {
        if (containsAnchorInSubtree(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /**
   * Detects an alternation branch in which a START-class anchor ({@code ^} non-multiline or {@code
   * \A}) is positioned after a character-consuming element. Such a branch is unsatisfiable in find
   * context (the anchor demands absolute input position 0, which can never hold once characters
   * have been consumed), yet the DFA construction treats the anchor as a "match-start" condition
   * and loses that distinction when the branch is merged with siblings — silently accepting matches
   * the JDK rejects. Example: {@code 1[^c]$|.-\A} on "1-0" — branch {@code .-\A} can never match
   * (the \A follows ".-"), but the DFA accepts via that branch and {@code find()} wrongly returns
   * true.
   *
   * <p>Returns {@code true} when any alternation branch anywhere in the AST exhibits this shape, so
   * the caller can decline the DFA fast path and route to a correct strategy.
   */
  private boolean hasMisplacedStartAnchorInAlternation(RegexNode node) {
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (startAnchorPrecededByConsumer(alt, false)
            || hasMisplacedStartAnchorInAlternation(alt)) {
          return true;
        }
      }
      return false;
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (hasMisplacedStartAnchorInAlternation(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode) {
      return hasMisplacedStartAnchorInAlternation(((GroupNode) node).child);
    }
    if (node instanceof QuantifierNode) {
      return hasMisplacedStartAnchorInAlternation(((QuantifierNode) node).child);
    }
    return false;
  }

  /**
   * Detects the combination of an alternation and a {@code \Z} (STRING_END) anchor. {@code \Z}
   * matches at end-of-input OR immediately before a final newline; the DFA's longest-match
   * resolution can accept the "before final newline" position while the JDK greedily prefers
   * consuming the trailing newline via a preceding optional/variable element, yielding a different
   * (shorter) span. Example: {@code [1][^-]?\Z|_{2}} on "1\n" — JDK matches [0,2] (consumes '\n'
   * via [^-]?), the DFA stops at [0,1]. {@code $} and {@code \z} are unaffected. Decline the DFA
   * fast path for this shape so it routes to a correct strategy.
   */
  private boolean hasStringEndAnchorInAlternation(RegexNode node) {
    return containsAlternation(node) && nfa != null && nfa.hasStringEndAnchor();
  }

  /**
   * Returns true if, scanning {@code node} left to right, a START-class anchor appears after at
   * least one element that definitely consumes a character. {@code consumedBefore} carries that
   * "characters already consumed" state into the scan.
   */
  private boolean startAnchorPrecededByConsumer(RegexNode node, boolean consumedBefore) {
    return scanForMisplacedStartAnchor(node, consumedBefore) == SCAN_MISPLACED;
  }

  private static final int SCAN_NO_CONSUME = 0; // no consuming element seen, no anchor violation
  private static final int SCAN_CONSUMED = 1; // at least one consuming element seen
  private static final int SCAN_MISPLACED = 2; // found a misplaced START anchor

  /**
   * Scans a branch for a START-class anchor preceded by a consumer. Returns {@link #SCAN_MISPLACED}
   * if found; otherwise {@link #SCAN_CONSUMED} when the branch definitely consumes a character or
   * {@link #SCAN_NO_CONSUME} when it does not.
   */
  private int scanForMisplacedStartAnchor(RegexNode node, boolean consumedBefore) {
    if (node instanceof AnchorNode) {
      AnchorNode anchor = (AnchorNode) node;
      boolean startClass =
          (anchor.type == AnchorNode.Type.STRING_START)
              || (anchor.type == AnchorNode.Type.START && !anchor.multiline);
      if (startClass && consumedBefore) {
        return SCAN_MISPLACED;
      }
      return consumedBefore ? SCAN_CONSUMED : SCAN_NO_CONSUME;
    }
    if (node instanceof ConcatNode) {
      boolean consumed = consumedBefore;
      for (RegexNode child : ((ConcatNode) node).children) {
        int r = scanForMisplacedStartAnchor(child, consumed);
        if (r == SCAN_MISPLACED) return SCAN_MISPLACED;
        if (r == SCAN_CONSUMED) consumed = true;
      }
      return consumed ? SCAN_CONSUMED : SCAN_NO_CONSUME;
    }
    if (node instanceof GroupNode) {
      return scanForMisplacedStartAnchor(((GroupNode) node).child, consumedBefore);
    }
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      int r = scanForMisplacedStartAnchor(q.child, consumedBefore);
      if (r == SCAN_MISPLACED) return SCAN_MISPLACED;
      // Only treat as a definite consumer when the quantifier requires at least one repetition.
      if (r == SCAN_CONSUMED && q.min >= 1) return SCAN_CONSUMED;
      return consumedBefore ? SCAN_CONSUMED : SCAN_NO_CONSUME;
    }
    if (node instanceof LiteralNode) {
      // Epsilon literal (char 0) consumes nothing.
      if (((LiteralNode) node).ch == 0) {
        return consumedBefore ? SCAN_CONSUMED : SCAN_NO_CONSUME;
      }
      return SCAN_CONSUMED;
    }
    if (node instanceof CharClassNode) {
      return SCAN_CONSUMED;
    }
    // Unknown / non-consuming (assertions, lookarounds): preserve prior consumed state.
    return consumedBefore ? SCAN_CONSUMED : SCAN_NO_CONSUME;
  }

  private boolean dfaHasAcceptingStateWithTransitions(DFA dfa) {
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state.accepting && !state.transitions.isEmpty()) {
        // Unconditional acceptance with outgoing transitions: DFA longest-match
        // will advance past the zero-width match point.
        if (state.acceptanceAnchorConditions.isEmpty()) {
          return true;
        }
        // START-class anchor acceptance with outgoing transitions: at position 0
        // (where ^/\A fire) the DFA can still advance via transitions, diverging
        // from NFA first-alternative semantics. END-class anchors are safe because
        // transitions cannot fire at end-of-input.
        for (NFA.AnchorType a : state.acceptanceAnchorConditions) {
          if (a == NFA.AnchorType.START
              || a == NFA.AnchorType.STRING_START
              || a == NFA.AnchorType.START_MULTILINE) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Returns true when any accepting DFA state with outgoing transitions has {@link
   * DFA.DFAState#acceptIsPriorityCut} set to {@code false}. When this is true, the generator's
   * longest-match semantics cannot be corrected by the priority-cut flag alone — the consuming
   * thread(s) with higher priority than the accept thread may or may not succeed at runtime, and
   * the DFA cannot distinguish the two cases statically. Such patterns must be declined (routed to
   * a correct fallback). When false, every accepting state with outgoing transitions is a
   * priority-cut state and the generator handles it correctly.
   */
  private boolean hasUnresolvedAcceptingTransitionState(DFA dfa) {
    for (DFA.DFAState state : dfa.getAllStates()) {
      // acceptIsPriorityCut=true: generator cuts immediately — priority conflict resolved.
      // acceptIsPriorityCut=false + hasPriorityConflictTransition=true: lower-priority thread
      // can fire and override the accept — DFA longest-match is wrong.
      if (state.accepting && state.hasPriorityConflictTransition && !state.acceptIsPriorityCut) {
        if (state.acceptanceAnchorConditions.isEmpty()) {
          return true;
        }
        for (NFA.AnchorType a : state.acceptanceAnchorConditions) {
          if (a == NFA.AnchorType.START
              || a == NFA.AnchorType.STRING_START
              || a == NFA.AnchorType.START_MULTILINE) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean hasLookaround(RegexNode node) {
    LookaroundDetector detector = new LookaroundDetector();
    return node.accept(detector);
  }

  // DFA path uses match(substring) for group extraction, cutting lookahead context at the boundary.
  private boolean hasLookaheadInsideCapturingGroup(RegexNode node) {
    return hasLookaheadInsideCapturingGroupHelper(node, false);
  }

  private boolean hasLookaheadInsideCapturingGroupHelper(RegexNode node, boolean insideCapturing) {
    if (node instanceof AssertionNode) {
      AssertionNode a = (AssertionNode) node;
      // Only positive/negative lookaheads matter (not lookbehinds)
      if ((a.type == AssertionNode.Type.POSITIVE_LOOKAHEAD
              || a.type == AssertionNode.Type.NEGATIVE_LOOKAHEAD)
          && insideCapturing) {
        return true;
      }
      return hasLookaheadInsideCapturingGroupHelper(a.subPattern, insideCapturing);
    }
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      boolean nowInCapturing = insideCapturing || g.capturing;
      return hasLookaheadInsideCapturingGroupHelper(g.child, nowInCapturing);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (hasLookaheadInsideCapturingGroupHelper(child, insideCapturing)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (hasLookaheadInsideCapturingGroupHelper(alt, insideCapturing)) return true;
      }
      return false;
    }
    if (node instanceof QuantifierNode) {
      return hasLookaheadInsideCapturingGroupHelper(((QuantifierNode) node).child, insideCapturing);
    }
    return false;
  }

  private boolean hasLookbehind(RegexNode node) {
    if (node instanceof AssertionNode) {
      AssertionNode a = (AssertionNode) node;
      if (a.type == AssertionNode.Type.POSITIVE_LOOKBEHIND
          || a.type == AssertionNode.Type.NEGATIVE_LOOKBEHIND) {
        return true;
      }
      return hasLookbehind(a.subPattern);
    }
    if (node instanceof GroupNode) return hasLookbehind(((GroupNode) node).child);
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (hasLookbehind(child)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (hasLookbehind(alt)) return true;
      }
      return false;
    }
    if (node instanceof QuantifierNode) return hasLookbehind(((QuantifierNode) node).child);
    return false;
  }

  /**
   * Check if any backref references a capturing group defined inside a lookahead assertion. This
   * requires falling back to NFA-based matching since DFA can't track groups.
   */
  private boolean hasBackrefToLookaheadCapture(RegexNode node) {
    // First, collect all group numbers inside lookahead assertions
    Set<Integer> lookaheadGroupNumbers = new HashSet<>();
    collectLookaheadGroupNumbers(node, lookaheadGroupNumbers, false);

    if (lookaheadGroupNumbers.isEmpty()) {
      return false; // No groups in lookaheads
    }

    // Then, collect all backref numbers
    Set<Integer> backrefNumbers = new HashSet<>();
    collectBackrefNumbers(node, backrefNumbers);

    // Check if any backref references a lookahead group
    for (Integer backrefNum : backrefNumbers) {
      if (lookaheadGroupNumbers.contains(backrefNum)) {
        return true;
      }
    }
    return false;
  }

  private void collectLookaheadGroupNumbers(
      RegexNode node, Set<Integer> groupNumbers, boolean insideLookahead) {
    if (node instanceof AssertionNode) {
      AssertionNode assertion = (AssertionNode) node;
      if (assertion.type == AssertionNode.Type.POSITIVE_LOOKAHEAD
          || assertion.type == AssertionNode.Type.NEGATIVE_LOOKAHEAD) {
        // Now inside lookahead - collect groups from sub-pattern
        collectLookaheadGroupNumbers(assertion.subPattern, groupNumbers, true);
      } else if (assertion.subPattern != null) {
        collectLookaheadGroupNumbers(assertion.subPattern, groupNumbers, insideLookahead);
      }
    } else if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      if (insideLookahead && group.capturing && group.groupNumber >= 0) {
        groupNumbers.add(group.groupNumber);
      }
      if (group.child != null) {
        collectLookaheadGroupNumbers(group.child, groupNumbers, insideLookahead);
      }
    } else if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        collectLookaheadGroupNumbers(child, groupNumbers, insideLookahead);
      }
    } else if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        collectLookaheadGroupNumbers(alt, groupNumbers, insideLookahead);
      }
    } else if (node instanceof QuantifierNode) {
      collectLookaheadGroupNumbers(((QuantifierNode) node).child, groupNumbers, insideLookahead);
    }
  }

  private void collectBackrefNumbers(RegexNode node, Set<Integer> backrefNumbers) {
    if (node instanceof BackreferenceNode) {
      backrefNumbers.add(((BackreferenceNode) node).groupNumber);
    } else if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        collectBackrefNumbers(child, backrefNumbers);
      }
    } else if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        collectBackrefNumbers(alt, backrefNumbers);
      }
    } else if (node instanceof QuantifierNode) {
      collectBackrefNumbers(((QuantifierNode) node).child, backrefNumbers);
    } else if (node instanceof GroupNode) {
      collectBackrefNumbers(((GroupNode) node).child, backrefNumbers);
    } else if (node instanceof AssertionNode && ((AssertionNode) node).subPattern != null) {
      collectBackrefNumbers(((AssertionNode) node).subPattern, backrefNumbers);
    }
  }

  private boolean hasSubroutines(RegexNode node) {
    SubroutineDetector detector = new SubroutineDetector();
    return node.accept(detector);
  }

  private boolean hasConditionals(RegexNode node) {
    ConditionalDetector detector = new ConditionalDetector();
    return node.accept(detector);
  }

  private boolean hasBranchReset(RegexNode node) {
    BranchResetDetector detector = new BranchResetDetector();
    return node.accept(detector);
  }

  private boolean hasAtomicGroups(RegexNode node) {
    AtomicGroupDetector detector = new AtomicGroupDetector();
    return node.accept(detector);
  }

  private boolean hasNonGreedyQuantifiers(RegexNode node) {
    NonGreedyQuantifierDetector detector = new NonGreedyQuantifierDetector();
    return node.accept(detector);
  }

  private boolean hasPossessiveQuantifiers(RegexNode node) {
    PossessiveQuantifierDetector detector = new PossessiveQuantifierDetector();
    return node.accept(detector);
  }

  /**
   * AST-level eligibility check for {@link MatchingStrategy#BITSTATE_CAPTURE} (see
   * doc/2026-07-03-bitstate-capture-engine-design.md §6). Declines the same non-goals PikeVM
   * already handles correctly: backreferences, lookaround, atomic groups, possessive quantifiers,
   * (§7.5) anchored quantifiers wrapping a nullable {@code ^}/{@code \A}/{@code (?m)^} body, where
   * BitState's first-accept-wins DFS does not reproduce JDK's anchor-derived zero-length-loop
   * suppression, and quantifiers (repeatable more than once) wrapping a nullable body that contains
   * a nested capturing group: the {@code (stateId, pos)} visited set collapses the empty re-entry
   * iteration before it reaches the inner group's exit write, so the reported group span reflects a
   * stale earlier iteration instead of JDK's final (zero-width) one (e.g. {@code (?:(.*[_]*))+} on
   * {@code "a_b"}: JDK reports group 1 as {@code [3,3)}, BitState's DFS reports {@code [0,3)}).
   */
  private boolean isBitStateEligible(RegexNode node) {
    if (hasBackreferences(node)) return false;
    if (hasLookaround(node)) return false;
    if (hasAtomicGroups(node)) return false;
    if (hasPossessiveQuantifiers(node)) return false;
    if (hasAnchoredNullableQuantifierBody(node)) return false;
    return !hasNullableQuantifierBodyWithNestedCapture(node);
  }

  /**
   * Detects a quantifier capable of repeating more than once (max == -1, i.e. unbounded, or max
   * &gt;= 2) whose body both can match the empty string and contains a nested capturing group. See
   * {@link #isBitStateEligible} for why this shape is declined.
   */
  private boolean hasNullableQuantifierBodyWithNestedCapture(RegexNode node) {
    if (node instanceof QuantifierNode) {
      QuantifierNode quantifier = (QuantifierNode) node;
      boolean canRepeat = quantifier.max == -1 || quantifier.max >= 2;
      if (canRepeat
          && isZeroWidthNullable(quantifier.child)
          && hasCapturingGroup(quantifier.child)) {
        return true;
      }
      return hasNullableQuantifierBodyWithNestedCapture(quantifier.child);
    }
    if (node instanceof GroupNode) {
      return hasNullableQuantifierBodyWithNestedCapture(((GroupNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (hasNullableQuantifierBodyWithNestedCapture(child)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (hasNullableQuantifierBodyWithNestedCapture(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /** Returns true if {@code node} contains a capturing group anywhere within it. */
  private boolean hasCapturingGroup(RegexNode node) {
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      if (group.capturing) return true;
      return hasCapturingGroup(group.child);
    }
    if (node instanceof QuantifierNode) {
      return hasCapturingGroup(((QuantifierNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (hasCapturingGroup(child)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (hasCapturingGroup(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /**
   * Detects a quantifier capable of repeating more than once (max == -1, i.e. unbounded, or max
   * &gt;= 2) whose body both contains a start-of-input anchor ({@code ^}, {@code \A}, or {@code
   * (?m)^}) and can match the empty string. This is the {@code (^a?){3}} / {@code (?m)(^x?)+} /
   * {@code \A{3}a} shape from doc/2026-07-03-bitstate-capture-engine-design.md §7.5: unrolling the
   * quantifier produces distinct anchor-state copies that a {@code (stateId, pos)} visited set does
   * not collapse, so a plain first-accept-wins DFS diverges from JDK's rule that a consuming path
   * reached through multiple anchor firings must not override a zero-length match.
   */
  private boolean hasAnchoredNullableQuantifierBody(RegexNode node) {
    if (node instanceof QuantifierNode) {
      QuantifierNode quantifier = (QuantifierNode) node;
      boolean canRepeat = quantifier.max == -1 || quantifier.max >= 2;
      if (canRepeat
          && containsStartAnchor(quantifier.child)
          && isZeroWidthNullable(quantifier.child)) {
        return true;
      }
      return hasAnchoredNullableQuantifierBody(quantifier.child);
    }
    if (node instanceof GroupNode) {
      return hasAnchoredNullableQuantifierBody(((GroupNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (hasAnchoredNullableQuantifierBody(child)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (hasAnchoredNullableQuantifierBody(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /** Returns true if {@code node} contains a start-of-input anchor ({@code ^} or {@code \A}). */
  private boolean containsStartAnchor(RegexNode node) {
    if (node instanceof AnchorNode) {
      AnchorNode.Type type = ((AnchorNode) node).type;
      return type == AnchorNode.Type.START || type == AnchorNode.Type.STRING_START;
    }
    if (node instanceof QuantifierNode) {
      return containsStartAnchor(((QuantifierNode) node).child);
    }
    if (node instanceof GroupNode) {
      return containsStartAnchor(((GroupNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (containsStartAnchor(child)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (containsStartAnchor(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /**
   * Returns true if {@code node} can match the empty string, treating anchors and lookaround as
   * inherently zero-width (unlike {@link #isNullable}, which only tracks quantifier/alternation
   * emptiness and is not used here for that reason).
   */
  private boolean isZeroWidthNullable(RegexNode node) {
    if (node instanceof AnchorNode || node instanceof AssertionNode) {
      return true;
    }
    if (node instanceof QuantifierNode) {
      QuantifierNode quantifier = (QuantifierNode) node;
      return quantifier.min == 0 || isZeroWidthNullable(quantifier.child);
    }
    if (node instanceof GroupNode) {
      return isZeroWidthNullable(((GroupNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (!isZeroWidthNullable(child)) return false;
      }
      return true;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (isZeroWidthNullable(alt)) return true;
      }
      return false;
    }
    return false;
  }

  /**
   * Check if pattern has start or end anchors (^, $). Word boundaries (\b) are not considered
   * anchors for this check.
   */
  private boolean hasAnchors(RegexNode node) {
    AnchorDetector detector = new AnchorDetector();
    return node.accept(detector);
  }

  /**
   * Check if pattern has variable quantifiers (*, +, ?, {n,m}) inside capturing groups. Fixed
   * quantifiers {n} are allowed as they don't require backtracking. This check is important for
   * backreference patterns - LINEAR_BACKREFERENCE doesn't support backtracking, so patterns like
   * (a+)\1 must fall through to NFA_WITH_BACKREFS.
   */
  private boolean hasVariableQuantifiersInCapturingGroups(RegexNode node) {
    VariableLengthInGroupsDetector detector = new VariableLengthInGroupsDetector();
    return node.accept(detector);
  }

  /**
   * Check if pattern has quantified backreferences (e.g., \1{2,3}, \1+, (\1)*). These patterns
   * require backtracking support provided by RECURSIVE_DESCENT strategy. Patterns like (a)\1{2,3}
   * need to match the backreference multiple times with backtracking.
   */
  private boolean hasQuantifiedBackreferences(RegexNode node) {
    QuantifiedBackrefDetector detector = new QuantifiedBackrefDetector();
    return node.accept(detector);
  }

  /**
   * Check if pattern requires backtracking for correct group capture. Patterns like a([bc]*)(c+d)
   * need backtracking because the greedy ([bc]*) may consume characters needed by (c+d), requiring
   * backtracking to find the correct match. DFA cannot handle this correctly because it commits to
   * the greedy match without exploring alternatives.
   *
   * <p>However, if the greedy group's char set does NOT overlap with the following element's first
   * char, then the DFA can handle it correctly. For example: - ([a-z]+)@... : [a-z] doesn't contain
   * '@', so no backtracking needed - ([bc]*)(c+d) : [bc] overlaps with 'c', so backtracking IS
   * needed
   */
  /**
   * Class E detector: a {@link ConcatNode} containing two or more capturing groups that each wrap
   * an alternation, where at least one of those alternations has branches with overlapping
   * first-sets (a shared prefix, e.g. {@code a|ab}). Such a pair, e.g. {@code (a|ab)(c|bcd)}, is
   * mis-captured by the single-register TDFA (g1=[0,2) vs JDK [0,1) on "abcd"). A lone capturing
   * alternation, or one followed by a fixed element, is fine and stays on the DFA.
   */
  private boolean hasInteractingCapturingAlternations(RegexNode node) {
    if (node instanceof GroupNode) {
      return hasInteractingCapturingAlternations(((GroupNode) node).child);
    }
    if (node instanceof QuantifierNode) {
      return hasInteractingCapturingAlternations(((QuantifierNode) node).child);
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (hasInteractingCapturingAlternations(a)) return true;
      }
      return false;
    }
    if (!(node instanceof ConcatNode)) {
      return false;
    }
    ConcatNode concat = (ConcatNode) node;
    int capturingAltGroups = 0;
    boolean anyOverlapping = false;
    for (RegexNode child : concat.children) {
      AlternationNode alt = capturingGroupAlternation(child);
      if (alt != null) {
        capturingAltGroups++;
        if (hasOverlappingBranchFirstSets(alt)) anyOverlapping = true;
      }
      if (hasInteractingCapturingAlternations(child)) return true; // nested
    }
    return capturingAltGroups >= 2 && anyOverlapping;
  }

  /**
   * If {@code node} is a capturing group whose body is (after unwrapping any transparent
   * non-capturing groups) an alternation, return that alternation.
   */
  private AlternationNode capturingGroupAlternation(RegexNode node) {
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (g.capturing) {
        RegexNode body = g.child;
        while (body instanceof GroupNode && !((GroupNode) body).capturing) {
          body = ((GroupNode) body).child;
        }
        if (body instanceof AlternationNode) {
          return (AlternationNode) body;
        }
      }
    }
    return null;
  }

  /** True if two branches of {@code alt} have intersecting first-sets (a shared leading char). */
  private boolean hasOverlappingBranchFirstSets(AlternationNode alt) {
    List<RegexNode> alts = alt.alternatives;
    for (int i = 0; i < alts.size(); i++) {
      CharSet fi = getFirstCharSet(alts.get(i));
      if (fi == null) continue;
      for (int j = i + 1; j < alts.size(); j++) {
        CharSet fj = getFirstCharSet(alts.get(j));
        if (fj != null && fi.intersects(fj)) return true;
      }
    }
    return false;
  }

  private boolean requiresBacktrackingForGroups(RegexNode node) {
    if (!(node instanceof ConcatNode)) {
      return false;
    }

    ConcatNode concat = (ConcatNode) node;
    if (concat.children.size() < 2) {
      return false;
    }

    // Check if any non-last child has a capturing group with a greedy quantifier
    // that overlaps with the following element
    for (int i = 0; i < concat.children.size() - 1; i++) {
      CharSet greedyCharSet = getGreedyGroupCharSet(concat.children.get(i));
      if (greedyCharSet != null) {
        // Found a greedy group - check if it overlaps with the suffix, looking through
        // nullable nodes (e.g. -? in ([1-9]?)-?\d+ can be skipped when [1-9] gives back).
        CharSet nextFirstCharSet = getSuffixFirstCharSetSkippingNullable(concat, i + 1);
        if (nextFirstCharSet == null || greedyCharSet.intersects(nextFirstCharSet)) {
          // Overlap detected (or can't determine) - needs backtracking
          return true;
        }
        // No overlap - DFA can handle this correctly
      }
    }

    return false;
  }

  /**
   * Get the CharSet of a greedy quantifier inside a capturing group. Returns null if the node is
   * not a capturing group with a greedy quantifier, or if the CharSet cannot be determined.
   */
  private CharSet getGreedyGroupCharSet(RegexNode node) {
    if (!(node instanceof GroupNode)) {
      return null;
    }
    GroupNode group = (GroupNode) node;
    if (!group.capturing) {
      return null;
    }
    // Direct quantifier child: (x*)
    if (group.child instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) group.child;
      if (!quant.greedy || quant.min == quant.max) {
        return null;
      }
      return getNodeCharSet(quant.child);
    }
    // ConcatNode child: inspect its last element for a trailing optional greedy quantifier
    // e.g., (\.\d\d[1-9]?) where the last element [1-9]? is an optional greedy quantifier
    if (group.child instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) group.child;
      if (concat.children.isEmpty()) {
        return null;
      }
      RegexNode last = concat.children.get(concat.children.size() - 1);
      if (last instanceof QuantifierNode) {
        QuantifierNode quant = (QuantifierNode) last;
        if (quant.min == 0 && quant.max == 1 && quant.greedy) {
          return getNodeCharSet(quant.child);
        }
      }
    }
    return null;
  }

  /** Get the CharSet that a node can match. Returns null if the CharSet cannot be determined. */
  private CharSet getNodeCharSet(RegexNode node) {
    if (node instanceof CharClassNode) {
      CharClassNode charClass = (CharClassNode) node;
      return charClass.negated ? charClass.chars.complement() : charClass.chars;
    }
    if (node instanceof LiteralNode) {
      LiteralNode lit = (LiteralNode) node;
      return CharSet.of(lit.ch);
    }
    if (node instanceof GroupNode) {
      return getNodeCharSet(((GroupNode) node).child);
    }
    if (node instanceof AlternationNode) {
      // Union of all alternatives' CharSets
      AlternationNode alt = (AlternationNode) node;
      CharSet result = CharSet.empty();
      for (RegexNode child : alt.alternatives) {
        CharSet childSet = getNodeCharSet(child);
        if (childSet == null) {
          return null; // Can't determine
        }
        result = result.union(childSet);
      }
      return result;
    }
    return null; // Unknown node type
  }

  /**
   * Get the CharSet of the first character that a node can match. For concatenations, this is the
   * first child's first char. Returns null if it cannot be determined.
   */
  private CharSet getFirstCharSet(RegexNode node) {
    if (node instanceof CharClassNode) {
      CharClassNode charClass = (CharClassNode) node;
      return charClass.negated ? charClass.chars.complement() : charClass.chars;
    }
    if (node instanceof LiteralNode) {
      LiteralNode lit = (LiteralNode) node;
      return CharSet.of(lit.ch);
    }
    if (node instanceof GroupNode) {
      return getFirstCharSet(((GroupNode) node).child);
    }
    if (node instanceof QuantifierNode) {
      return getFirstCharSet(((QuantifierNode) node).child);
    }
    if (node instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) node;
      if (!concat.children.isEmpty()) {
        return getFirstCharSet(concat.children.get(0));
      }
      return null;
    }
    if (node instanceof AlternationNode) {
      // Union of all alternatives' first chars
      AlternationNode alt = (AlternationNode) node;
      CharSet result = CharSet.empty();
      for (RegexNode child : alt.alternatives) {
        CharSet childSet = getFirstCharSet(child);
        if (childSet == null) {
          return null; // Can't determine
        }
        result = result.union(childSet);
      }
      return result;
    }
    if (node instanceof AnchorNode) {
      return CharSet.empty(); // zero-width assertion, consumes no characters
    }
    return null; // Unknown node type - be conservative
  }

  private boolean isNullable(RegexNode node) {
    if (node instanceof QuantifierNode) {
      return ((QuantifierNode) node).min == 0;
    }
    if (node instanceof GroupNode) {
      return isNullable(((GroupNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (!isNullable(child)) return false;
      }
      return true;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (isNullable(alt)) return true;
      }
      return false;
    }
    return false;
  }

  private CharSet getSuffixFirstCharSetSkippingNullable(ConcatNode concat, int fromIndex) {
    CharSet result = CharSet.empty();
    for (int j = fromIndex; j < concat.children.size(); j++) {
      CharSet firstChars = getFirstCharSet(concat.children.get(j));
      if (firstChars == null) {
        return null;
      }
      result = result.union(firstChars);
      if (!isNullable(concat.children.get(j))) {
        break;
      }
    }
    return result;
  }

  /** Check if node contains a capturing group with a greedy quantifier. */
  private boolean hasGreedyQuantifierInGroup(RegexNode node) {
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      if (group.capturing && group.child instanceof QuantifierNode) {
        QuantifierNode quant = (QuantifierNode) group.child;
        // Check if quantifier is greedy and variable (min != max)
        return quant.greedy && quant.min != quant.max;
      }
    }
    return false;
  }

  /**
   * Check if pattern is a simple single-quantifier pattern. Returns true for patterns like: \d+,
   * \w*, [a-z]+, ^[a-z]+$ Returns false for complex patterns like: [a-z]+@[a-z]+\.com
   */
  private boolean isSingleQuantifierPattern(RegexNode node) {
    // Unwrap anchors
    RegexNode core = node;
    if (node instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) node;
      List<RegexNode> children = concat.children;

      // Remove leading/trailing anchors
      int start = 0;
      int end = children.size();

      if (start < end && children.get(start) instanceof AnchorNode) {
        start++;
      }
      if (start < end && children.get(end - 1) instanceof AnchorNode) {
        end--;
      }

      // After removing anchors, should have exactly 1 child (the quantifier)
      if (end - start != 1) {
        return false;
      }

      core = children.get(start);
    }

    // Check if core is a quantifier with character class child
    if (core instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) core;
      return quant.child instanceof CharClassNode;
    }

    return false;
  }

  /**
   * Check if pattern has capturing groups inside repeating quantifiers. Tagged DFA cannot
   * efficiently handle repeating groups like (a+|b)+ because tag values get overwritten on each
   * iteration. Use NFA with per-config tracking instead.
   *
   * <p>Also used to detect when POSIX last-match semantics are needed.
   */
  private boolean hasGroupsInRepeatingQuantifiers(RegexNode node) {
    GroupInQuantifierDetector detector = new GroupInQuantifierDetector();
    return node.accept(detector);
  }

  /** Analyze all assertions in the pattern to determine DFA compatibility. */
  private AssertionInfo analyzeAssertions(RegexNode node) {
    AssertionAnalyzer analyzer = new AssertionAnalyzer();
    node.accept(analyzer);
    return new AssertionInfo(analyzer.allFixedWidth, analyzer.hasComplexQuantifiers);
  }

  /**
   * Detect DFA-compatible lookahead assertions and build DFAs for their sub-patterns. Returns
   * mapping of NFA assertion state IDs to their corresponding DFAs.
   *
   * <p>Only positive lookaheads are optimized (negative lookaheads and lookbehinds use NFA). DFA
   * construction may fail for sub-patterns with backreferences or state explosion.
   */
  private HybridDFALookaheadInfo detectDFACompatibleLookaheads() {
    Map<Integer, DFA> assertionDFAs = new HashMap<>();
    Map<Integer, AssertionNode> assertionNodes = new HashMap<>();

    // Walk through NFA to find assertion states
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.assertionType == null) {
        continue; // Not an assertion state
      }

      // Only optimize positive lookahead (most common and beneficial)
      if (state.assertionType != NFA.AssertionType.POSITIVE_LOOKAHEAD) {
        continue;
      }

      // Get the assertion sub-pattern NFA states
      if (state.assertionStartState == null || state.assertionAcceptStates == null) {
        continue; // Malformed assertion
      }

      try {
        // Try to build DFA from lookahead sub-pattern
        // Create sub-NFA from assertion states
        List<NFA.NFAState> subStates = collectReachableStates(state.assertionStartState);
        NFA subNFA = new NFA(subStates, state.assertionStartState, state.assertionAcceptStates, 0);

        // Attempt DFA construction
        SubsetConstructor constructor = new SubsetConstructor();
        DFA lookaheadDFA = constructor.buildDFA(subNFA);

        // Success! Store the DFA mapping
        assertionDFAs.put(state.id, lookaheadDFA);

        // Try to find the corresponding AST node for this assertion
        AssertionNode astNode = findAssertionNodeInAST(ast, state);
        if (astNode != null) {
          assertionNodes.put(state.id, astNode);
        }

      } catch (StateExplosionException e) {
        // DFA too large for this lookahead - skip optimization
        continue;
      } catch (Exception e) {
        // Any other error (backreferences, etc.) - skip this lookahead
        continue;
      }
    }

    if (assertionDFAs.isEmpty()) {
      return null; // No DFA-compatible lookaheads found
    }

    return new HybridDFALookaheadInfo(assertionDFAs, assertionNodes);
  }

  /**
   * Extract literal lookaheads that can be optimized using indexOf() instead of DFA simulation.
   *
   * <p>Analyzes positive lookahead assertions to find patterns with extractable literals:
   *
   * <ul>
   *   <li>Single char: {@code (?=\w+@)} → '@'
   *   <li>Substring: {@code (?=.*example)} → "example"
   *   <li>Char class: {@code (?=[A-Z])} → [A-Z]
   * </ul>
   *
   * @return list of extractable lookaheads, or null if none found
   */
  private List<LiteralLookaheadInfo> extractLiteralLookaheads() {
    List<LiteralLookaheadInfo> literalLookaheads = new ArrayList<>();
    // Track which AssertionNode AST objects have already been matched to avoid
    // assigning the same AST node to multiple NFA assertion states when the pattern
    // contains sequential lookaheads of the same type (e.g. (?=.*foo)(?=.*bar)).
    java.util.IdentityHashMap<AssertionNode, Boolean> matchedAssertionNodes =
        new java.util.IdentityHashMap<>();

    // Walk through NFA to find assertion states
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.assertionType == null) {
        continue; // Not an assertion state
      }

      // Only optimize positive lookahead (most common and beneficial)
      if (state.assertionType != NFA.AssertionType.POSITIVE_LOOKAHEAD) {
        continue;
      }

      // Get the assertion sub-pattern from AST, skipping already-matched nodes so that
      // two sequential lookaheads of the same type do not both map to the first AST node.
      AssertionNode astNode = findAssertionNodeInAST(ast, state, matchedAssertionNodes);
      if (astNode == null || astNode.subPattern == null) {
        continue; // No AST node found
      }
      matchedAssertionNodes.put(astNode, Boolean.TRUE); // mark as used

      // PHASE 3: Build NFA for lookahead pattern (for separated atomic execution)
      NFA lookaheadNFA = null;
      try {
        ThompsonBuilder builder = new ThompsonBuilder();
        lookaheadNFA = builder.build(astNode.subPattern, 0); // No group numbering offset
      } catch (Exception e) {
        // If NFA construction fails, continue without NFA (will fall back to indexOf/simple checks)
      }

      // Phase 2: Try to extract multi-character substring first (e.g., "example" from
      // (?=.*example))
      String substring = extractSubstringFromLookahead(astNode.subPattern);
      if (substring != null) {
        // Found substring - use indexOf(String) for 10-15x speedup
        literalLookaheads.add(
            LiteralLookaheadInfo.withNFA(
                LiteralLookaheadInfo.Type.SUBSTRING,
                substring,
                null,
                null,
                state.id,
                astNode.subPattern,
                lookaheadNFA));
        continue;
      }

      // Phase 1: Try to extract required single character (e.g., '@' from (?=\w+@))
      java.util.Set<Character> requiredChars = extractRequiredLiterals(astNode.subPattern);

      if (!requiredChars.isEmpty()) {
        // Found required literals - check if single char or multiple
        if (requiredChars.size() == 1) {
          // Single required character - can use indexOf(char)
          char singleChar = requiredChars.iterator().next();
          literalLookaheads.add(
              LiteralLookaheadInfo.withNFA(
                  LiteralLookaheadInfo.Type.SINGLE_CHAR,
                  null,
                  singleChar,
                  null,
                  state.id,
                  astNode.subPattern,
                  lookaheadNFA));
        }
        // Multiple chars case: skip for now (would need intersection logic)
        // Future: could optimize if all branches share a common prefix
      } else {
        // No required literals from visitor - check for simple char class at start
        LiteralLookaheadInfo charClassInfo =
            detectLeadingCharClass(astNode.subPattern, state.id, lookaheadNFA);
        if (charClassInfo != null) {
          literalLookaheads.add(charClassInfo);
        }
      }
    }

    return literalLookaheads.isEmpty() ? null : literalLookaheads;
  }

  /**
   * Detect if a pattern starts with a simple character class (e.g., [A-Z], \d, \w). Used for
   * lookaheads like {@code (?=[A-Z])} that check the next character.
   *
   * @param pattern the pattern to analyze
   * @param assertionStateId the assertion state ID
   * @return LiteralLookaheadInfo for char class, or null if not applicable
   */
  private LiteralLookaheadInfo detectLeadingCharClass(
      RegexNode pattern, int assertionStateId, NFA lookaheadNFA) {
    RegexNode originalPattern = pattern;

    // Unwrap groups
    while (pattern instanceof GroupNode) {
      pattern = ((GroupNode) pattern).child;
    }

    // Check for direct char class (e.g., [A-Z])
    if (pattern instanceof CharClassNode) {
      CharClassNode charClass = (CharClassNode) pattern;
      return LiteralLookaheadInfo.withNFA(
          LiteralLookaheadInfo.Type.CHAR_CLASS,
          null,
          null,
          charClass.chars,
          assertionStateId,
          originalPattern,
          lookaheadNFA);
    }

    // Check for concat starting with char class (e.g., [A-Z]\w+)
    if (pattern instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) pattern;
      if (!concat.children.isEmpty()) {
        RegexNode firstChild = concat.children.get(0);
        if (firstChild instanceof CharClassNode) {
          CharClassNode charClass = (CharClassNode) firstChild;
          return LiteralLookaheadInfo.withNFA(
              LiteralLookaheadInfo.Type.CHAR_CLASS,
              null,
              null,
              charClass.chars,
              assertionStateId,
              originalPattern,
              lookaheadNFA);
        }
      }
    }

    // Check for quantifier with char class (e.g., \w+ in (?=\w+@))
    // In this case, we don't want to match just the char class, we want the required literal after
    // it
    // So return null here - the required literals extractor will find '@' instead

    return null;
  }

  /**
   * Extract a multi-character substring from a lookahead pattern.
   *
   * <p>Patterns like {@code (?=.*example)} contain a required substring "example" that can be
   * detected using indexOf() instead of full NFA/DFA simulation.
   *
   * <p>This method builds the sub-NFA for the lookahead pattern and walks through it to find
   * literal sequences (similar to NFABytecodeGenerator.extractLongestRequiredLiteral).
   *
   * @param pattern the lookahead's sub-pattern
   * @return extracted substring (>= 3 chars), or null if none found
   */
  private String extractSubstringFromLookahead(RegexNode pattern) {
    try {
      // Build sub-NFA for the lookahead pattern
      ThompsonBuilder builder = new ThompsonBuilder();
      NFA subNFA = builder.build(pattern, 0);

      // Walk through all states looking for literal sequences
      String longestLiteral = null;
      int maxLength = 0;

      for (NFA.NFAState state : subNFA.getStates()) {
        // Skip assertion states (nested lookaheads)
        if (state.assertionType != null) {
          continue;
        }

        // Try to extract a literal starting from this state
        String literal = extractLiteralFromState(state, subNFA.getAcceptStates());
        if (literal != null && literal.length() > maxLength) {
          maxLength = literal.length();
          longestLiteral = literal;
        }
      }

      // Only return literals that are worth the indexOf overhead (>= 3 characters)
      return (longestLiteral != null && longestLiteral.length() >= 3) ? longestLiteral : null;

    } catch (Exception e) {
      // If NFA construction fails, return null
      return null;
    }
  }

  /**
   * Build complete literal lookahead pattern info with separate main pattern analysis.
   *
   * <p>Extracts the main pattern (non-assertion part), builds its NFA, analyzes it to determine
   * optimal strategy, and packages everything together.
   *
   * @param literalLookaheads list of literal lookaheads
   * @param requiredLiterals required literals from full pattern
   * @return complete pattern info with main pattern strategy
   */
  private LiteralLookaheadPatternInfo buildLiteralLookaheadInfo(
      List<LiteralLookaheadInfo> literalLookaheads, java.util.Set<Character> requiredLiterals) {

    // Extract main pattern (without assertions)
    RegexNode mainPatternAST = extractMainPattern(ast);

    if (mainPatternAST == null) {
      // Pattern is only assertions - no main pattern to execute
      return new LiteralLookaheadPatternInfo(literalLookaheads, requiredLiterals);
    }

    try {
      // Build NFA for main pattern only
      ThompsonBuilder builder = new ThompsonBuilder();
      NFA mainPatternNFA = builder.build(mainPatternAST, 0);

      // Analyze main pattern to get optimal strategy
      PatternAnalyzer mainAnalyzer = new PatternAnalyzer(mainPatternAST, mainPatternNFA);
      MatchingStrategyResult mainStrategy =
          mainAnalyzer.analyzeAndRecommend(true); // ignore group count

      return new LiteralLookaheadPatternInfo(
          literalLookaheads, requiredLiterals, mainPatternAST, mainPatternNFA, mainStrategy);
    } catch (Exception e) {
      // If main pattern analysis fails, fall back to simple version
      return new LiteralLookaheadPatternInfo(literalLookaheads, requiredLiterals);
    }
  }

  /**
   * Extract a literal sequence starting from a given NFA state.
   *
   * <p>Follows single-character transitions as long as possible to build up a literal string. Stops
   * when hitting branches, character classes, or cycles.
   *
   * @param start the starting state
   * @param acceptStates the NFA's accept states
   * @return extracted literal string (>= 3 chars), or null
   */
  private String extractLiteralFromState(NFA.NFAState start, Set<NFA.NFAState> acceptStates) {
    StringBuilder literal = new StringBuilder();
    NFA.NFAState current = start;
    Set<NFA.NFAState> visited = new HashSet<>();

    while (visited.add(current)) {
      // Skip assertion states
      if (current.assertionType != null) {
        break;
      }

      // Follow epsilon transitions (skip empty transitions to non-assertion states)
      if (!current.getEpsilonTransitions().isEmpty()) {
        boolean foundNonAssertion = false;
        for (NFA.NFAState target : current.getEpsilonTransitions()) {
          if (target.assertionType == null) {
            current = target;
            foundNonAssertion = true;
            break;
          }
        }
        if (!foundNonAssertion) break;
        continue;
      }

      // Must have exactly one character transition for a literal
      if (current.getTransitions().size() != 1) {
        break;
      }

      NFA.Transition trans = current.getTransitions().iterator().next();

      // Must be a single character (not a character class)
      if (!trans.chars.isSingleChar()) {
        break;
      }

      literal.append(trans.chars.getSingleChar());
      current = trans.target;

      // Don't require reaching accept state - we want any literal sequence
      // This allows us to find literals like "example" even if followed by more pattern
    }

    // Return literal if we found at least 3 consecutive characters
    return (literal.length() >= 3) ? literal.toString() : null;
  }

  /**
   * Extract the main pattern (non-assertion part) from the AST.
   *
   * <p>For patterns like {@code (?=\w+@)(?=.*example).*@\w+\.com}, this extracts just the {@code
   * .*@\w+\.com} part, allowing it to be compiled separately using its optimal strategy (e.g.,
   * DFA_UNROLLED).
   *
   * @param ast the full pattern AST
   * @return main pattern AST without leading assertions, or null if pattern is only assertions
   */
  private RegexNode extractMainPattern(RegexNode ast) {
    // Unwrap groups
    while (ast instanceof GroupNode) {
      ast = ((GroupNode) ast).child;
    }

    // If root is ConcatNode, extract non-assertion children
    if (ast instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) ast;
      List<RegexNode> mainPatternNodes = new ArrayList<>();

      // Skip leading assertion nodes, collect everything else
      for (RegexNode child : concat.children) {
        if (!(child instanceof AssertionNode)) {
          mainPatternNodes.add(child);
        }
      }

      // Return main pattern
      if (mainPatternNodes.isEmpty()) {
        return null; // Pattern is only assertions
      } else if (mainPatternNodes.size() == 1) {
        return mainPatternNodes.get(0);
      } else {
        return new ConcatNode(mainPatternNodes);
      }
    }

    // Not a concat - if it's an assertion, no main pattern; otherwise return as-is
    return (ast instanceof AssertionNode) ? null : ast;
  }

  /** Collect all states reachable from a starting state (for sub-NFA extraction). */
  private List<NFA.NFAState> collectReachableStates(NFA.NFAState start) {
    List<NFA.NFAState> reachable = new ArrayList<>();
    java.util.Set<NFA.NFAState> visited = new java.util.HashSet<>();
    java.util.Queue<NFA.NFAState> queue = new java.util.LinkedList<>();

    queue.add(start);
    visited.add(start);

    while (!queue.isEmpty()) {
      NFA.NFAState current = queue.poll();
      reachable.add(current);

      // Follow character transitions
      for (NFA.Transition trans : current.getTransitions()) {
        if (visited.add(trans.target)) {
          queue.add(trans.target);
        }
      }

      // Follow epsilon transitions
      for (NFA.NFAState epsilon : current.getEpsilonTransitions()) {
        if (visited.add(epsilon)) {
          queue.add(epsilon);
        }
      }
    }

    return reachable;
  }

  /**
   * Find the AssertionNode in the AST that corresponds to an NFA assertion state. This is a
   * best-effort search; may return null if not found.
   */
  private AssertionNode findAssertionNodeInAST(RegexNode node, NFA.NFAState targetState) {
    return findAssertionNodeInAST(node, targetState, null);
  }

  /**
   * Find an AssertionNode in the AST that corresponds to the given NFA assertion state, skipping
   * any nodes already present in {@code alreadyMatched} (identity-based). Pass {@code null} to skip
   * the deduplication check (backward-compatible behaviour).
   */
  private AssertionNode findAssertionNodeInAST(
      RegexNode node,
      NFA.NFAState targetState,
      java.util.IdentityHashMap<AssertionNode, Boolean> alreadyMatched) {
    if (node instanceof AssertionNode) {
      AssertionNode assertion = (AssertionNode) node;
      if (isMatchingAssertion(assertion, targetState)
          && (alreadyMatched == null || !alreadyMatched.containsKey(assertion))) {
        return assertion;
      }
    }

    // Recursively search child nodes
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        AssertionNode result = findAssertionNodeInAST(child, targetState, alreadyMatched);
        if (result != null) return result;
      }
    } else if (node instanceof AlternationNode) {
      for (RegexNode child : ((AlternationNode) node).alternatives) {
        AssertionNode result = findAssertionNodeInAST(child, targetState, alreadyMatched);
        if (result != null) return result;
      }
    } else if (node instanceof GroupNode) {
      return findAssertionNodeInAST(((GroupNode) node).child, targetState, alreadyMatched);
    } else if (node instanceof QuantifierNode) {
      return findAssertionNodeInAST(((QuantifierNode) node).child, targetState, alreadyMatched);
    }

    return null;
  }

  /** Check if an AssertionNode matches an NFA assertion state. */
  private boolean isMatchingAssertion(AssertionNode astNode, NFA.NFAState nfaState) {
    if (nfaState.assertionType == null) return false;

    // Map AST assertion types to NFA assertion types
    switch (astNode.type) {
      case POSITIVE_LOOKAHEAD:
        return nfaState.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD;
      case NEGATIVE_LOOKAHEAD:
        return nfaState.assertionType == NFA.AssertionType.NEGATIVE_LOOKAHEAD;
      case POSITIVE_LOOKBEHIND:
        return nfaState.assertionType == NFA.AssertionType.POSITIVE_LOOKBEHIND;
      case NEGATIVE_LOOKBEHIND:
        return nfaState.assertionType == NFA.AssertionType.NEGATIVE_LOOKBEHIND;
      default:
        return false;
    }
  }

  /** Information about assertions in a pattern. */
  private static class AssertionInfo {
    final boolean allFixedWidth;
    final boolean hasComplexQuantifiers;

    AssertionInfo(boolean allFixedWidth, boolean hasComplexQuantifiers) {
      this.allFixedWidth = allFixedWidth;
      this.hasComplexQuantifiers = hasComplexQuantifiers;
    }
  }

  /** Bytecode generation strategies. */
  public enum MatchingStrategy {
    // Pattern-specific optimizations (highest priority)
    STATELESS_LOOP, // Patterns that don't need state tracking: \w+, (?=\w+@).*@example.com
    SPECIALIZED_GREEDY_CHARCLASS, // Single char class with greedy quantifier: (\d+), ([a-z]*)
    SPECIALIZED_MULTI_GROUP_GREEDY, // Multi-group greedy patterns: ([a-z]+)@([a-z]+)\.com,
    // (\d{3})-(\d+)-(\d{4})
    SPECIALIZED_CONCAT_GREEDY_GROUP, // Prefix + greedy group + suffix: a(b*), x(y*)z, foo(bar+)baz
    SPECIALIZED_FIXED_SEQUENCE, // Fixed-length sequences: \d{3}-\d{3}-\d{4}, \d{4}-\d{2}-\d{2}
    SPECIALIZED_BOUNDED_QUANTIFIERS, // Bounded quantifier sequences:
    // \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3} (IPv4)
    LINEAR_BACKREFERENCE, // Linear patterns with backreferences (no alternation): (\w+)\s+\1,
    // (a+)b\1
    SPECIALIZED_BACKREFERENCE, // Hardcoded backreference patterns: <(\w+)>.*</\1>, \b(\w+)\s+\1\b
    SPECIALIZED_MULTIPLE_LOOKAHEADS, // Sequential lookaheads + main pattern:
    // (?=\w+@)(?=.*example).*@\w+\.com
    SPECIALIZED_LITERAL_LOOKAHEADS, // Lookaheads with extractable literals: (?=\w+@)(?=.*example)
    // using indexOf()
    SPECIALIZED_LITERAL_ALTERNATION, // Pure literal alternations: keyword1|keyword2|...|keywordN
    // using trie
    SPECIALIZED_QUANTIFIED_GROUP, // Quantified capturing groups with POSIX last-match: (a)+,
    // (a|b)+, (a*)+
    GREEDY_BACKTRACK, // Greedy patterns requiring backtracking: (.*)bar, (.*)(\d+)

    // Backreference-specific strategies (require limited backtracking)
    FIXED_REPETITION_BACKREF, // Fixed-repetition backrefs: (a)\1{8,}, (abc|def)=(\1){2,3}
    VARIABLE_CAPTURE_BACKREF, // Variable-capture backrefs: ((.*))\\d+\\1, (a+)\1

    /**
     * Backreference whose group boundary is provably unambiguous (disjoint follow-set): single
     * forward pass, no retry/backtracking.
     */
    PINNED_BACKREFERENCE,
    OPTIONAL_GROUP_BACKREF, // Optional group backrefs: (a)?\\1, (another)?(\1+)test
    NESTED_QUANTIFIED_GROUPS, // Nested quantified groups: ((a|bc)+)*, ((a+|b)*)?c

    // Generic strategies
    ONEPASS_NFA, // Unambiguous pattern - single-thread NFA
    DFA_UNROLLED, // <20 states - fully unrolled (best for tiny DFAs)
    DFA_UNROLLED_WITH_ASSERTIONS, // <20 states - unrolled with fixed-width assertions
    DFA_UNROLLED_WITH_GROUPS, // <20 states - unrolled with inline group tracking
    DFA_SWITCH, // 20-300 states - switch statement (better cache behavior)
    DFA_SWITCH_WITH_ASSERTIONS, // 20-300 states - switch with fixed-width assertions
    DFA_SWITCH_WITH_GROUPS, // 20-300 states - switch with inline group tracking
    DFA_TABLE, // >300 states - table-driven
    BITPARALLEL_GLUSHKOV, // >300 states, <=63 positions - single-word bit-parallel NFA simulation
    COUNTING_GLUSHKOV, // Counting Glushkov automaton: O(n) bound-independent matching for X{n,m}.
    OPTIMIZED_NFA, // DFA state explosion - optimized NFA
    LAZY_DFA, // Large anchor-free group-free NFA - on-the-fly DFA construction
    OPTIMIZED_NFA_WITH_BACKREFS, // Has backreferences
    OPTIMIZED_NFA_WITH_LOOKAROUND, // Has variable-width lookaround assertions (NFA for all)
    HYBRID_DFA_LOOKAHEAD, // Hybrid: DFA for lookahead sub-patterns, NFA for main pattern

    // Context-free strategies (for features beyond regular languages)
    RECURSIVE_DESCENT, // Subroutines, conditionals, branch reset - requires recursive descent
    // parser

    /**
     * Bounded-backtracking NFA-with-capture: same semantics as {@link #PIKEVM_CAPTURE} but without
     * PikeVM's per-character thread-set bookkeeping and capture-array copying. Selected instead of
     * {@link #PIKEVM_CAPTURE} by {@link PatternAnalyzer#isBitStateEligible} (see
     * doc/2026-07-03-bitstate-capture-engine-design.md).
     */
    BITSTATE_CAPTURE,

    /** PikeVM NFA-with-capture: O(n·m) native group extraction, leftmost-greedy, ReDoS-safe. */
    PIKEVM_CAPTURE
  }

  /** Result of pattern analysis containing strategy and optional DFA. */
  public static class MatchingStrategyResult {
    public final MatchingStrategy strategy;
    public final DFA dfa; // null for NFA strategies
    public final PatternInfo patternInfo; // Pattern-specific info for specialized generators
    public final boolean useTaggedDFA; // Use Tagged DFA for group tracking
    public final java.util.Set<Character>
        requiredLiterals; // Required literals for indexOf optimization
    public final LookaheadGreedySuffixInfo
        lookaheadGreedyInfo; // Phase 2: lookahead+greedy+suffix pattern info
    public final boolean
        usePosixLastMatch; // Use POSIX last-match semantics for groups in quantifiers

    /**
     * True when the pattern has alternation and the DFA has an unconditionally-accepting state with
     * further outgoing transitions. In this case the DFA uses longest-match semantics but Java NFA
     * semantics require first-alternative preference. Callers should route to a correct fallback
     * engine (e.g. {@code JavaRegexFallbackMatcher}) rather than using the DFA.
     */
    public boolean alternationPriorityConflict;

    /**
     * True when subset construction detected that an accepting DFA state has constituent NFA
     * threads that disagree about a capturing group's participation — one thread entered and exited
     * the group, another bypassed it, and both are accepting. The lowest-state-id merge in {@code
     * SubsetConstructor.computeGroupActions} cannot choose the correct binding; callers should
     * route to a correct fallback engine (e.g. {@code JavaRegexFallbackMatcher}).
     */
    public boolean captureAmbiguous;

    /**
     * True when DFA construction detected an anchor-condition dilution that is not explained by an
     * explicit misplaced-start or string-end anchor in an alternation. The DFA is structurally
     * valid but semantically incorrect; callers should route to a correct fallback engine (e.g.
     * {@code JavaRegexFallbackMatcher}) rather than using the generated NFA bytecode.
     */
    public boolean anchorConditionDiluted;

    /**
     * True when the pattern contains at least one atomic group ({@code (?>...)}) or possessive
     * quantifier ({@code X*+}, {@code X++}, {@code X?+}, {@code X{n,m}+}). Required so that two
     * patterns differing only in atomic-group markers produce distinct structural hashes and do not
     * collide in the bytecode cache.
     */
    public boolean hasAtomicGroups;

    /**
     * True when the NFA must be built with {@code ThompsonBuilder(lazyAware=true)} to produce
     * shortest-match (lazy) quantifier semantics. Set when the lazy-quantifier routing block
     * selects {@link MatchingStrategy#PIKEVM_CAPTURE}.
     */
    public boolean lazyNfa;

    /** Per-guard routing trace populated by {@link PatternAnalyzer#analyzeAndRecommend}. */
    public final List<String> guardTrace = new ArrayList<>();

    public MatchingStrategyResult(MatchingStrategy strategy, DFA dfa) {
      this(strategy, dfa, null, false, java.util.Collections.emptySet(), null, false);
    }

    public MatchingStrategyResult(MatchingStrategy strategy, DFA dfa, PatternInfo patternInfo) {
      this(strategy, dfa, patternInfo, false, java.util.Collections.emptySet(), null, false);
    }

    public MatchingStrategyResult(
        MatchingStrategy strategy, DFA dfa, PatternInfo patternInfo, boolean useTaggedDFA) {
      this(strategy, dfa, patternInfo, useTaggedDFA, java.util.Collections.emptySet(), null, false);
    }

    public MatchingStrategyResult(
        MatchingStrategy strategy,
        DFA dfa,
        PatternInfo patternInfo,
        boolean useTaggedDFA,
        java.util.Set<Character> requiredLiterals) {
      this(strategy, dfa, patternInfo, useTaggedDFA, requiredLiterals, null, false);
    }

    public MatchingStrategyResult(
        MatchingStrategy strategy,
        DFA dfa,
        PatternInfo patternInfo,
        boolean useTaggedDFA,
        java.util.Set<Character> requiredLiterals,
        LookaheadGreedySuffixInfo lookaheadGreedyInfo) {
      this(strategy, dfa, patternInfo, useTaggedDFA, requiredLiterals, lookaheadGreedyInfo, false);
    }

    public MatchingStrategyResult(
        MatchingStrategy strategy,
        DFA dfa,
        PatternInfo patternInfo,
        boolean useTaggedDFA,
        java.util.Set<Character> requiredLiterals,
        LookaheadGreedySuffixInfo lookaheadGreedyInfo,
        boolean usePosixLastMatch) {
      this.strategy = strategy;
      this.dfa = dfa;
      this.patternInfo = patternInfo;
      this.useTaggedDFA = useTaggedDFA;
      this.requiredLiterals = requiredLiterals;
      this.lookaheadGreedyInfo = lookaheadGreedyInfo;
      this.usePosixLastMatch = usePosixLastMatch;
    }
  }

  /**
   * Detect fixed-repetition backreference patterns: (group)\1{n,m}
   *
   * <p>Examples: - (a)\1{8,} - capture 'a', verify 8+ more 'a's follow - (abc)\1{3} - capture
   * 'abc', verify exactly 3 repetitions
   *
   * <p>Pattern structure: - Optional prefix (anchors, literals) - Capturing group - Quantified
   * backreference to that group - Optional suffix (anchors, literals)
   *
   * @return FixedRepetitionBackrefInfo if detected, null otherwise
   */
  private FixedRepetitionBackrefInfo detectFixedRepetitionBackref(RegexNode ast) {
    // Only handle ConcatNode at top level
    if (!(ast instanceof ConcatNode)) {
      return null;
    }

    ConcatNode concat = (ConcatNode) ast;
    List<RegexNode> children = concat.children;

    // Need at least 2 children: group + quantified backref
    if (children.size() < 2) {
      return null;
    }

    // Find the capturing group and quantified backreference
    int groupIndex = -1;
    int backrefIndex = -1;
    GroupNode capturingGroup = null;
    QuantifierNode quantifiedBackref = null;
    int referencedGroupNumber = -1;

    for (int i = 0; i < children.size(); i++) {
      RegexNode child = children.get(i);

      // Look for capturing group
      if (child instanceof GroupNode) {
        GroupNode group = (GroupNode) child;
        if (group.capturing && groupIndex == -1) {
          groupIndex = i;
          capturingGroup = group;
        }
      }

      // Look for quantified backreference: Quantifier(Backref)
      // Only handle if min >= 1 (so \1* and \1? don't match - those need different handling)
      if (child instanceof QuantifierNode) {
        QuantifierNode quant = (QuantifierNode) child;
        if (quant.child instanceof BackreferenceNode && quant.min >= 1) {
          BackreferenceNode backref = (BackreferenceNode) quant.child;
          // Only handle if this refs the group we found
          if (capturingGroup != null && backref.groupNumber == capturingGroup.groupNumber) {
            backrefIndex = i;
            quantifiedBackref = quant;
            referencedGroupNumber = backref.groupNumber;
          }
        }
      }
    }

    // Must have found both group and quantified backref
    if (groupIndex == -1 || backrefIndex == -1 || groupIndex >= backrefIndex) {
      return null;
    }

    // Only handle simple group content (single char, char class, or literal)
    // Complex patterns like (a|b) need more sophisticated handling
    if (!isSimpleGroupContent(capturingGroup.child)) {
      return null;
    }

    // Extract prefix (nodes before group) and suffix (nodes after backref)
    List<RegexNode> prefix = new ArrayList<>(children.subList(0, groupIndex));
    List<RegexNode> suffix = new ArrayList<>(children.subList(backrefIndex + 1, children.size()));

    // Determine if group is single-char
    boolean isSingleCharGroup = isSingleCharOrCharClass(capturingGroup.child);
    CharSet groupCharSet = extractCharSet(capturingGroup.child);
    int literalChar = extractLiteralChar(capturingGroup.child);

    return new FixedRepetitionBackrefInfo(
        prefix,
        capturingGroup,
        referencedGroupNumber,
        quantifiedBackref.min,
        quantifiedBackref.max,
        suffix,
        nfa.getGroupCount(),
        isSingleCharGroup,
        groupCharSet,
        literalChar);
  }

  /**
   * Detect a structurally-pinned backreference boundary: a capturing group whose content charset is
   * disjoint from whatever immediately follows it (and, if present, from any separator between the
   * group's close and the backreference site). Because the group's closing boundary is therefore
   * unambiguous, matching needs only a single forward scan - no retry/backtracking.
   *
   * <p>Reuses the group/backref-pairing loop shape from {@link #detectFixedRepetitionBackref}.
   *
   * @return a populated {@link PinnedBackreferenceInfo}, or {@code null} if the pattern isn't a
   *     top-level concatenation containing a disjoint-boundary group/backreference pair.
   */
  private PinnedBackreferenceInfo detectPinnedBackreference(RegexNode ast) {
    // Only handle ConcatNode at top level
    if (!(ast instanceof ConcatNode)) {
      return null;
    }

    ConcatNode concat = (ConcatNode) ast;
    List<RegexNode> children = concat.children;

    if (children.size() < 2) {
      return null;
    }

    // Find the capturing group and its matching backreference (same pairing style as
    // detectFixedRepetitionBackref).
    int groupIndex = -1;
    int backrefIndex = -1;
    GroupNode capturingGroup = null;

    for (int i = 0; i < children.size(); i++) {
      RegexNode child = children.get(i);

      if (child instanceof GroupNode) {
        GroupNode group = (GroupNode) child;
        if (group.capturing && groupIndex == -1) {
          groupIndex = i;
          capturingGroup = group;
        }
      }

      if (child instanceof BackreferenceNode) {
        BackreferenceNode backref = (BackreferenceNode) child;
        if (capturingGroup != null && backref.groupNumber == capturingGroup.groupNumber) {
          backrefIndex = i;
        }
      }
    }

    if (groupIndex == -1 || backrefIndex == -1 || groupIndex >= backrefIndex) {
      return null;
    }

    // The generated matcher only ever scans the group, the optional separator, and the
    // backreference echo - it has no code path for anything else in the concatenation. A
    // non-empty prefix/suffix (e.g. the \b anchors in \b(\w+)\s+\1\b) would therefore be silently
    // ignored rather than evaluated, so require the group/backref pair to span the whole pattern.
    if (groupIndex != 0 || backrefIndex != children.size() - 1) {
      return null;
    }

    // The codegen forward-scans the group's content as a single flat "one-or-more chars from
    // groupCharSet" loop with no notion of an upper bound, so only an unbounded (max == -1),
    // greedy, min >= 1 quantifier directly wrapping a charset-bearing child is safe - this
    // excludes both fixed/bounded-repetition groups (e.g. \w{1,4}, whose codegen would ignore the
    // max bound) and composite bodies (e.g. a ConcatNode with a trailing optional quantifier),
    // whose charset isn't representative of the whole group content.
    if (!(capturingGroup.child instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode groupQuant = (QuantifierNode) capturingGroup.child;
    if (!groupQuant.greedy || groupQuant.min < 1 || groupQuant.max != -1) {
      return null;
    }
    // The generated matcher's group count is derived solely from the pinned group's own number;
    // a capturing group nested inside its quantified body would be silently unaccounted for,
    // making group(n) inaccessible or throwing for n beyond the pinned group's index.
    if (hasCapturingGroup(groupQuant.child)) {
      return null;
    }
    CharSet groupCharSet = getNodeCharSet(groupQuant.child);

    // Charset of whatever immediately follows the group in the concat.
    CharSet nextCharSet = getSuffixFirstCharSetSkippingNullable(concat, groupIndex + 1);

    // Disjointness condition 1: group content vs. what follows it.
    if (groupCharSet == null || nextCharSet == null || !groupCharSet.isDisjoint(nextCharSet)) {
      return null;
    }

    // Separator (if any) between the group's close and the backreference site. The codegen scans
    // the separator with the same flat "chars from separatorCharSet" loop, which is only sound
    // for a single homogeneous node (e.g. \s+) - a separator spanning multiple AST nodes (as in
    // the tag-close shape's literal+`.*`+literal delimiter, an intervening capturing group, or an
    // earlier occurrence of the same backreference) is rejected rather than approximated.
    RegexNode separator = null;
    CharSet separatorCharSet = null;
    int separatorMinLength = 0;
    int separatorMaxLength = -1;
    if (backrefIndex > groupIndex + 1) {
      List<RegexNode> sepNodes = children.subList(groupIndex + 1, backrefIndex);
      if (sepNodes.size() != 1) {
        return null;
      }
      separator = sepNodes.get(0);
      if (hasCapturingGroup(separator)) {
        return null;
      }
      separatorCharSet = getFirstCharSet(separator);

      // The scan can't backtrack, so it always consumes the longest run of separator-charset
      // chars available; that's only correct if the separator's quantifier bounds either equal
      // that run (min <= run) or don't cap it below that run (max == -1 or max >= run). Track the
      // bounds here so codegen can reject a candidate whose actual run falls outside them, instead
      // of accepting whatever length the greedy scan happens to consume.
      // A non-capturing group (e.g. (?:\s+)) wraps its child without changing what's matched, so
      // unwrap it before checking for a quantifier - otherwise a wrapped quantifier's bounds are
      // silently replaced with "exactly one occurrence" below.
      RegexNode sepQuantCandidate = separator;
      if (sepQuantCandidate instanceof GroupNode) {
        GroupNode sepGroup = (GroupNode) sepQuantCandidate;
        if (!sepGroup.capturing && !sepGroup.atomic) {
          sepQuantCandidate = sepGroup.child;
        }
      }
      if (sepQuantCandidate instanceof QuantifierNode) {
        QuantifierNode sepQuant = (QuantifierNode) sepQuantCandidate;
        if (!sepQuant.greedy) {
          return null;
        }
        separatorMinLength = sepQuant.min;
        separatorMaxLength = sepQuant.max;
      } else {
        // No quantifier wrapping the separator node means exactly one occurrence.
        separatorMinLength = 1;
        separatorMaxLength = 1;
      }
      if (separatorMinLength < 1) {
        return null;
      }

      // Disjointness condition 2: separator vs. group content.
      if (separatorCharSet == null || !groupCharSet.isDisjoint(separatorCharSet)) {
        return null;
      }
    }

    return new PinnedBackreferenceInfo(
        capturingGroup.groupNumber,
        capturingGroup.child,
        groupCharSet,
        groupQuant.min,
        separator,
        separatorCharSet,
        separatorMinLength,
        separatorMaxLength);
  }

  /** Check if a node represents a single character or character class. */
  private boolean isSingleCharOrCharClass(RegexNode node) {
    if (node instanceof LiteralNode) {
      return true;
    }
    if (node instanceof CharClassNode) {
      return true;
    }
    // Could also check for quantified single char with count 1
    return false;
  }

  /**
   * Check if group content is simple enough for FixedRepetitionBackref. Simple means: single char,
   * char class, or simple literal sequence. Rejects alternations, nested groups, quantifiers.
   */
  private boolean isSimpleGroupContent(RegexNode node) {
    if (node instanceof LiteralNode) {
      return true;
    }
    if (node instanceof CharClassNode) {
      return true;
    }
    // Reject alternation - too complex
    if (node instanceof AlternationNode) {
      return false;
    }
    // Reject nested groups
    if (node instanceof GroupNode) {
      return false;
    }
    // Reject quantifiers inside group - those need different handling
    if (node instanceof QuantifierNode) {
      return false;
    }
    // ConcatNode of simple literals is ok (e.g., "abc")
    if (node instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) node;
      for (RegexNode child : concat.children) {
        if (!(child instanceof LiteralNode)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /** Extract CharSet from a node if it's a char class. */
  private CharSet extractCharSet(RegexNode node) {
    if (node instanceof CharClassNode) {
      return ((CharClassNode) node).chars;
    }
    return null;
  }

  /**
   * Extract literal char from a node if it's a single literal. Returns -1 if not a literal node.
   */
  private int extractLiteralChar(RegexNode node) {
    if (node instanceof LiteralNode) {
      return ((LiteralNode) node).ch;
    }
    return -1;
  }

  /**
   * Extract a literal string from an AST node. Returns the string if the node represents a simple
   * literal sequence, null otherwise.
   */
  /**
   * Check if node is an alternation with one empty alternative. Returns the non-empty content if
   * found, null otherwise.
   *
   * <p>This handles patterns like (a|) or (|a) where one alternative is empty. Empty alternatives
   * are represented as LiteralNode with char 0 (epsilon).
   */
  private RegexNode extractNonEmptyAlternative(RegexNode node) {
    if (!(node instanceof AlternationNode)) {
      return null;
    }

    AlternationNode alt = (AlternationNode) node;
    if (alt.alternatives.size() != 2) {
      // Only handle binary alternation (X|) or (|X)
      return null;
    }

    RegexNode first = alt.alternatives.get(0);
    RegexNode second = alt.alternatives.get(1);

    // Check if first is empty (epsilon)
    if (isEpsilon(first)) {
      return second;
    }
    // Check if second is empty (epsilon)
    if (isEpsilon(second)) {
      return first;
    }

    return null;
  }

  /**
   * Check if a node represents epsilon (empty match). Empty concatenation is represented as
   * LiteralNode with char 0.
   */
  private boolean isEpsilon(RegexNode node) {
    if (node instanceof LiteralNode) {
      return ((LiteralNode) node).ch == 0;
    }
    return false;
  }

  /**
   * Extract BackreferenceNode from a node that might be plain or quantified. Returns null if the
   * node doesn't contain a backref.
   *
   * <p>Handles: - Plain backref: \1 → BackreferenceNode - Quantified backref: \1+, \1*, \1{2,3} →
   * QuantifierNode containing BackreferenceNode
   */
  private BackreferenceNode extractBackref(RegexNode node) {
    if (node instanceof BackreferenceNode) {
      return (BackreferenceNode) node;
    }
    if (node instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) node;
      if (quant.child instanceof BackreferenceNode) {
        return (BackreferenceNode) quant.child;
      }
    }
    return null;
  }

  /**
   * Detect variable-capture backreference patterns. Pattern shape: (.*)\d+\1 or (.+)literal\1
   *
   * <p>These patterns have a variable-length capturing group followed by a separator and a
   * backreference to that group.
   */
  private VariableCaptureBackrefInfo detectVariableCaptureBackref(RegexNode ast) {
    // Only handle ConcatNode at top level
    if (!(ast instanceof ConcatNode)) {
      return null;
    }

    ConcatNode concat = (ConcatNode) ast;
    List<RegexNode> children = concat.children;

    // Need at least 3 children: group + separator + backref
    // Or 2 children if no separator: group + backref
    if (children.size() < 2) {
      return null;
    }

    // Track anchors
    boolean hasStartAnchor = false;
    boolean hasEndAnchor = false;
    int startIdx = 0;
    int endIdx = children.size();

    // Check for start anchor
    if (children.get(0) instanceof AnchorNode) {
      AnchorNode anchor = (AnchorNode) children.get(0);
      if (anchor.type == AnchorNode.Type.START || anchor.type == AnchorNode.Type.STRING_START) {
        hasStartAnchor = true;
        startIdx = 1;
      }
    }

    // Check for end anchor
    if (children.get(children.size() - 1) instanceof AnchorNode) {
      AnchorNode anchor = (AnchorNode) children.get(children.size() - 1);
      if (anchor.type == AnchorNode.Type.END || anchor.type == AnchorNode.Type.STRING_END) {
        hasEndAnchor = true;
        endIdx = children.size() - 1;
      }
    }

    // Find the capturing group with variable quantifier
    int groupIdx = -1;
    GroupNode capturingGroup = null;
    QuantifierNode groupQuantifier = null;

    for (int i = startIdx; i < endIdx; i++) {
      RegexNode child = children.get(i);

      // Look for quantified capturing group
      if (child instanceof QuantifierNode) {
        QuantifierNode quant = (QuantifierNode) child;
        if (quant.child instanceof GroupNode) {
          GroupNode group = (GroupNode) quant.child;
          // Skip pure optional groups (min=0, max=1) - let detectOptionalGroupBackref handle those
          if (quant.min == 0 && quant.max == 1) {
            continue;
          }
          if (group.capturing && isVariableLengthQuantifier(quant)) {
            groupIdx = i;
            capturingGroup = group;
            groupQuantifier = quant;
            break;
          }
        }
      }
      // Also check for group containing quantified content: (.*)
      if (child instanceof GroupNode) {
        GroupNode group = (GroupNode) child;
        if (group.capturing && group.child instanceof QuantifierNode) {
          QuantifierNode quant = (QuantifierNode) group.child;
          if (isVariableLengthQuantifier(quant)) {
            groupIdx = i;
            capturingGroup = group;
            groupQuantifier = quant;
            break;
          }
        }
      }
    }

    if (groupIdx == -1) {
      return null;
    }

    // Find backreference after the group
    int backrefIdx = -1;
    BackreferenceNode backref = null;
    int backrefMin = 1;
    int backrefMax = 1;

    for (int i = groupIdx + 1; i < endIdx; i++) {
      RegexNode child = children.get(i);

      if (child instanceof BackreferenceNode) {
        backref = (BackreferenceNode) child;
        if (backref.groupNumber == capturingGroup.groupNumber) {
          backrefIdx = i;
          break;
        }
      }
      // Check for quantified backref: \1+ or \1{n}
      if (child instanceof QuantifierNode) {
        QuantifierNode quant = (QuantifierNode) child;
        if (quant.child instanceof BackreferenceNode) {
          BackreferenceNode br = (BackreferenceNode) quant.child;
          if (br.groupNumber == capturingGroup.groupNumber) {
            backref = br;
            backrefIdx = i;
            backrefMin = quant.min;
            backrefMax = quant.max;
            break;
          }
        }
      }
    }

    if (backrefIdx == -1) {
      return null;
    }

    // Extract separator between group and backref (if any)
    VariableCaptureBackrefInfo.SeparatorType separatorType =
        VariableCaptureBackrefInfo.SeparatorType.NONE;
    String separatorLiteral = null;
    CharSet separatorCharSet = null;
    int separatorMinCount = 0;
    int separatorGroupNumber = -1;

    if (backrefIdx > groupIdx + 1) {
      // There's a separator between group and backref
      // Collect all elements as separator (may be multiple literals)
      List<RegexNode> sepNodes = children.subList(groupIdx + 1, backrefIdx);
      SeparatorInfo sepInfo = extractSeparatorInfoFromNodes(sepNodes);
      if (sepInfo != null) {
        separatorType = sepInfo.type;
        separatorLiteral = sepInfo.literal;
        separatorCharSet = sepInfo.charSet;
        separatorMinCount = sepInfo.minCount;
        separatorGroupNumber = sepInfo.groupNumber;
      } else {
        // Complex separator - not supported
        return null;
      }
    }

    // Extract group charset
    CharSet groupCharSet = extractGroupCharSet(capturingGroup, groupQuantifier);

    // Build prefix and suffix
    List<RegexNode> prefix = new ArrayList<>(children.subList(startIdx, groupIdx));
    List<RegexNode> suffix = new ArrayList<>(children.subList(backrefIdx + 1, endIdx));

    // If the suffix contains another backreference to the same group, the VARIABLE_CAPTURE_BACKREF
    // strategy cannot handle it correctly — fall through to OPTIMIZED_NFA_WITH_BACKREFS.
    for (RegexNode node : suffix) {
      if (containsBackrefToGroup(node, capturingGroup.groupNumber)) {
        return null;
      }
    }

    // Groups whose content is a LiteralNode (e.g. (a*), (b+)) have their charset extracted as
    // CharSet.ANY by the fallthrough in extractGroupCharSet, so the generator would accept any
    // character instead of restricting to the literal. Nullable such groups (min=0 or nullable
    // content) produce incorrect matches; route to OPTIMIZED_NFA_WITH_BACKREFS instead.
    // Groups with CharClassNode content (any-char .* or bounded [a-z]*) use the correct charset
    // and are handled correctly even when nullable.
    RegexNode quantifierContent = groupQuantifier.child;
    if (!(quantifierContent instanceof CharClassNode)
        && (groupQuantifier.min == 0 || isGroupContentNullable(capturingGroup.child))) {
      return null;
    }

    return new VariableCaptureBackrefInfo(
        prefix,
        capturingGroup.groupNumber,
        groupCharSet,
        groupQuantifier.min,
        groupQuantifier.max,
        separatorType,
        separatorLiteral,
        separatorCharSet,
        separatorMinCount,
        separatorGroupNumber,
        backref.groupNumber,
        backrefMin,
        backrefMax,
        suffix,
        hasStartAnchor,
        hasEndAnchor,
        nfa.getGroupCount());
  }

  /** Check if a quantifier allows variable length (*, +, ?, {n,m} where n != m). */
  private boolean isVariableLengthQuantifier(QuantifierNode quant) {
    return quant.min != quant.max || quant.max < 0;
  }

  /**
   * Returns true if the given node (the content of a capturing group) can match the empty string.
   * Used to detect nullable groups that require routing away from VARIABLE_CAPTURE_BACKREF.
   */
  private boolean isGroupContentNullable(RegexNode node) {
    if (node instanceof QuantifierNode) {
      return ((QuantifierNode) node).min == 0
          || isGroupContentNullable(((QuantifierNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (!isGroupContentNullable(c)) return false;
      }
      return true;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (isGroupContentNullable(alt)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode) {
      return isGroupContentNullable(((GroupNode) node).child);
    }
    // AnchorNode is zero-width; LiteralNode and CharClassNode consume characters.
    return node instanceof AnchorNode;
  }

  private boolean containsBackrefToGroup(RegexNode node, int groupNumber) {
    if (node instanceof BackreferenceNode) {
      return ((BackreferenceNode) node).groupNumber == groupNumber;
    }
    if (node instanceof GroupNode) {
      return containsBackrefToGroup(((GroupNode) node).child, groupNumber);
    }
    if (node instanceof AssertionNode) {
      return containsBackrefToGroup(((AssertionNode) node).subPattern, groupNumber);
    }
    if (node instanceof ConditionalNode) {
      ConditionalNode cn = (ConditionalNode) node;
      return containsBackrefToGroup(cn.thenBranch, groupNumber)
          || (cn.elseBranch != null && containsBackrefToGroup(cn.elseBranch, groupNumber));
    }
    if (node instanceof BranchResetNode) {
      for (RegexNode alt : ((BranchResetNode) node).alternatives) {
        if (containsBackrefToGroup(alt, groupNumber)) return true;
      }
    }
    if (node instanceof QuantifierNode) {
      return containsBackrefToGroup(((QuantifierNode) node).child, groupNumber);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (containsBackrefToGroup(child, groupNumber)) return true;
      }
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (containsBackrefToGroup(alt, groupNumber)) return true;
      }
    }
    return false;
  }

  /** Helper class for separator extraction. */
  private static class SeparatorInfo {
    VariableCaptureBackrefInfo.SeparatorType type;
    String literal;
    CharSet charSet;
    int minCount;
    int groupNumber = -1;
  }

  /** Extract separator info from a list of nodes (handles multiple literals). */
  private SeparatorInfo extractSeparatorInfoFromNodes(List<RegexNode> nodes) {
    if (nodes.isEmpty()) {
      return null;
    }

    // Single node case - delegate to existing method
    if (nodes.size() == 1) {
      return extractSeparatorInfo(nodes.get(0));
    }

    // Multiple nodes - check if all are literals
    StringBuilder sb = new StringBuilder();
    for (RegexNode node : nodes) {
      if (node instanceof LiteralNode) {
        sb.append(((LiteralNode) node).ch);
      } else {
        // Not a simple literal sequence - not supported
        return null;
      }
    }

    SeparatorInfo info = new SeparatorInfo();
    info.type = VariableCaptureBackrefInfo.SeparatorType.LITERAL;
    info.literal = sb.toString();
    info.minCount = sb.length();
    return info;
  }

  /** Extract separator info from a node. */
  private SeparatorInfo extractSeparatorInfo(RegexNode node) {
    SeparatorInfo info = new SeparatorInfo();

    // Literal separator
    if (node instanceof LiteralNode) {
      info.type = VariableCaptureBackrefInfo.SeparatorType.LITERAL;
      info.literal = String.valueOf(((LiteralNode) node).ch);
      info.minCount = 1;
      return info;
    }

    // Concat of literals
    if (node instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) node;
      StringBuilder sb = new StringBuilder();
      for (RegexNode child : concat.children) {
        if (child instanceof LiteralNode) {
          sb.append(((LiteralNode) child).ch);
        } else {
          return null; // Not a simple literal
        }
      }
      info.type = VariableCaptureBackrefInfo.SeparatorType.LITERAL;
      info.literal = sb.toString();
      info.minCount = sb.length();
      return info;
    }

    // Quantified char class: \d+, \w+, \s+
    if (node instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) node;
      RegexNode child = quant.child;

      // Check if it's a capturing group containing the separator
      if (child instanceof GroupNode) {
        GroupNode group = (GroupNode) child;
        if (group.capturing) {
          info.groupNumber = group.groupNumber;
          child = group.child;
          if (child instanceof QuantifierNode) {
            quant = (QuantifierNode) child;
            child = quant.child;
          }
        }
      }

      if (child instanceof CharClassNode) {
        CharClassNode cc = (CharClassNode) child;
        info.charSet = cc.chars;
        info.minCount = quant.min;

        if (cc.chars.equals(CharSet.DIGIT)) {
          info.type = VariableCaptureBackrefInfo.SeparatorType.DIGIT_SEQ;
        } else if (cc.chars.equals(CharSet.WORD)) {
          info.type = VariableCaptureBackrefInfo.SeparatorType.WORD_SEQ;
        } else if (cc.chars.equals(CharSet.WHITESPACE)) {
          info.type = VariableCaptureBackrefInfo.SeparatorType.WHITESPACE_SEQ;
        } else {
          info.type = VariableCaptureBackrefInfo.SeparatorType.CHAR_CLASS_SEQ;
        }
        return info;
      }
    }

    // Single char class
    if (node instanceof CharClassNode) {
      CharClassNode cc = (CharClassNode) node;
      info.charSet = cc.chars;
      info.minCount = 1;

      if (cc.chars.equals(CharSet.DIGIT)) {
        info.type = VariableCaptureBackrefInfo.SeparatorType.DIGIT_SEQ;
      } else if (cc.chars.equals(CharSet.WORD)) {
        info.type = VariableCaptureBackrefInfo.SeparatorType.WORD_SEQ;
      } else if (cc.chars.equals(CharSet.WHITESPACE)) {
        info.type = VariableCaptureBackrefInfo.SeparatorType.WHITESPACE_SEQ;
      } else {
        info.type = VariableCaptureBackrefInfo.SeparatorType.CHAR_CLASS_SEQ;
      }
      return info;
    }

    return null;
  }

  /** Extract charset from a capturing group. */
  private CharSet extractGroupCharSet(GroupNode group, QuantifierNode quant) {
    RegexNode content = group.child;

    // If group contains quantifier, get the quantified element
    if (content instanceof QuantifierNode) {
      content = ((QuantifierNode) content).child;
    }

    if (content instanceof CharClassNode) {
      return ((CharClassNode) content).chars;
    }

    // Dot (any char)
    if (content instanceof CharClassNode) {
      CharClassNode cc = (CharClassNode) content;
      if (cc.chars.equals(CharSet.ANY)) {
        return CharSet.ANY;
      }
    }

    // Default to ANY for .* style groups
    return CharSet.ANY;
  }

  /**
   * Detect optional group backreference patterns. Pattern shape: (a)?\1 or ^(a)?(b)?\1\2$
   *
   * <p>These patterns have optional capturing groups followed by backreferences. Key semantic:
   * backref to unmatched group matches empty string.
   */
  private OptionalGroupBackrefInfo detectOptionalGroupBackref(RegexNode ast) {
    // Only handle ConcatNode at top level
    if (!(ast instanceof ConcatNode)) {
      return null;
    }

    ConcatNode concat = (ConcatNode) ast;
    List<RegexNode> children = concat.children;

    if (children.isEmpty()) {
      return null;
    }

    // Track anchors
    boolean hasStartAnchor = false;
    boolean hasEndAnchor = false;
    int startIdx = 0;
    int endIdx = children.size();

    // Check for start anchor
    if (children.get(0) instanceof AnchorNode) {
      AnchorNode anchor = (AnchorNode) children.get(0);
      if (anchor.type == AnchorNode.Type.START || anchor.type == AnchorNode.Type.STRING_START) {
        hasStartAnchor = true;
        startIdx = 1;
      }
    }

    // Check for end anchor
    if (endIdx > startIdx && children.get(children.size() - 1) instanceof AnchorNode) {
      AnchorNode anchor = (AnchorNode) children.get(children.size() - 1);
      if (anchor.type == AnchorNode.Type.END || anchor.type == AnchorNode.Type.STRING_END) {
        hasEndAnchor = true;
        endIdx = children.size() - 1;
      }
    }

    // Find optional groups and backreferences
    List<OptionalGroupBackrefInfo.OptionalGroupEntry> optionalGroups = new ArrayList<>();
    Set<Integer> optionalGroupNumbers = new HashSet<>();
    List<Integer> backrefGroupNumbers = new ArrayList<>();
    List<OptionalGroupBackrefInfo.BackrefEntry> backrefEntries = new ArrayList<>();
    List<RegexNode> prefix = new ArrayList<>();
    List<RegexNode> middle = new ArrayList<>();
    List<RegexNode> suffix = new ArrayList<>();

    boolean foundOptionalGroup = false;
    boolean foundBackref = false;
    int lastOptionalGroupIdx = -1;
    int firstBackrefIdx = -1;

    for (int i = startIdx; i < endIdx; i++) {
      RegexNode child = children.get(i);

      // Check for optional group: QuantifierNode with min=0, max=1 containing GroupNode
      if (child instanceof QuantifierNode) {
        QuantifierNode quant = (QuantifierNode) child;
        if (quant.min == 0 && quant.max == 1 && quant.child instanceof GroupNode) {
          GroupNode group = (GroupNode) quant.child;
          if (group.capturing) {
            // Found optional capturing group: (X)? form — group may not participate
            boolean isSingleChar = isSingleCharOrCharClass(group.child);
            int literalChar = extractLiteralChar(group.child);
            String literalString = extractLiteralString(group.child);

            optionalGroups.add(
                new OptionalGroupBackrefInfo.OptionalGroupEntry(
                    group.groupNumber,
                    group.child,
                    isSingleChar,
                    literalChar,
                    literalString,
                    false));
            optionalGroupNumbers.add(group.groupNumber);
            foundOptionalGroup = true;
            lastOptionalGroupIdx = i;
            continue;
          }
        }
      }

      // Empty alternation group detection: (X|) or (|X)
      // These have alternation with one empty branch - treat as optional group
      if (child instanceof GroupNode) {
        GroupNode group = (GroupNode) child;
        if (group.capturing) {
          RegexNode nonEmptyContent = extractNonEmptyAlternative(group.child);
          if (nonEmptyContent != null) {
            // Found capturing group with empty alternation: (X|) form — always captures
            boolean isSingleChar = isSingleCharOrCharClass(nonEmptyContent);
            int literalChar = extractLiteralChar(nonEmptyContent);
            String literalString = extractLiteralString(nonEmptyContent);

            optionalGroups.add(
                new OptionalGroupBackrefInfo.OptionalGroupEntry(
                    group.groupNumber,
                    nonEmptyContent,
                    isSingleChar,
                    literalChar,
                    literalString,
                    true));
            optionalGroupNumbers.add(group.groupNumber);
            foundOptionalGroup = true;
            lastOptionalGroupIdx = i;
            continue;
          }
        }
      }

      // Check for backreference (possibly quantified)
      BackreferenceNode backref = extractBackref(child);
      if (backref != null && optionalGroupNumbers.contains(backref.groupNumber)) {
        backrefGroupNumbers.add(backref.groupNumber);
        // Extract quantifier info if present
        int minCount = 1;
        int maxCount = 1;
        if (child instanceof QuantifierNode) {
          QuantifierNode quant = (QuantifierNode) child;
          minCount = quant.min;
          maxCount = quant.max;
        }
        backrefEntries.add(
            new OptionalGroupBackrefInfo.BackrefEntry(backref.groupNumber, minCount, maxCount));
        if (!foundBackref) {
          foundBackref = true;
          firstBackrefIdx = i;
        }
        continue;
      }

      // Categorize other nodes as prefix, middle, or suffix
      if (!foundOptionalGroup) {
        prefix.add(child);
      } else if (!foundBackref) {
        middle.add(child);
      } else {
        suffix.add(child);
      }
    }

    // Must have at least one optional group and one backref to that group
    if (optionalGroups.isEmpty() || backrefGroupNumbers.isEmpty()) {
      return null;
    }

    // All backrefs must reference optional groups
    for (int groupNum : backrefGroupNumbers) {
      if (!optionalGroupNumbers.contains(groupNum)) {
        return null;
      }
    }

    // Extract suffix literal char if suffix is a simple literal
    int suffixLiteralChar = -1;
    int suffixGroupNumber = 0;
    String suffixGroupLiteral = null;

    if (suffix.size() == 1) {
      suffixLiteralChar = extractLiteralChar(suffix.get(0));

      // Check if suffix is a capturing group with literal content
      // Pattern: ^(cow|)\1(bell) - suffix "(bell)" is a group with literal "bell"
      if (suffixLiteralChar < 0 && suffix.get(0) instanceof GroupNode) {
        GroupNode suffixGroup = (GroupNode) suffix.get(0);
        if (suffixGroup.capturing) {
          String literal = extractLiteralString(suffixGroup.child);
          if (literal != null && !literal.isEmpty()) {
            suffixGroupNumber = suffixGroup.groupNumber;
            suffixGroupLiteral = literal;
            // Allow this pattern - we can handle capturing group suffix
          }
        }
      }
    }

    // Only support: empty suffix, single literal char suffix, or capturing group with literal
    // content
    if (!suffix.isEmpty() && suffixLiteralChar < 0 && suffixGroupLiteral == null) {
      return null; // Fall back to another strategy
    }

    return new OptionalGroupBackrefInfo(
        prefix,
        optionalGroups,
        optionalGroupNumbers,
        middle,
        backrefGroupNumbers,
        backrefEntries,
        suffix,
        suffixLiteralChar,
        suffixGroupNumber,
        suffixGroupLiteral,
        hasStartAnchor,
        hasEndAnchor,
        nfa.getGroupCount());
  }

  /**
   * Detect TRUE nested quantified groups: ((a)+)*, ((a|b)+)*
   *
   * <p>These patterns have a quantifier on a capturing group, where the group's DIRECT content is
   * another quantifier (possibly on another group).
   *
   * <p>Structure that matches: Quant → Group → Quant → (content) Examples: - ((a)+)* : Quant(*) →
   * Group1 → Quant(+) → Group2(a) - ((a|b)+)* : Quant(*) → Group1 → Quant(+) → Alternation -
   * (([a-z])+)* : Quant(*) → Group1 → Quant(+) → Group2([a-z])
   *
   * <p>Structure that does NOT match (handled by SPECIALIZED_QUANTIFIED_GROUP): - (a)+ : Single
   * level quantified group - (a|b)+ : Alternation in quantified group - (b+|a)+ : Quant → Group →
   * Alternation → Quant (quantifier inside alternation)
   *
   * <p>Returns NestedQuantifiedGroupsInfo if pattern matches, null otherwise.
   */
  private NestedQuantifiedGroupsInfo detectNestedQuantifiedGroups(RegexNode ast) {
    // Extract children and anchors
    List<RegexNode> children;
    boolean hasStartAnchor = false;
    boolean hasEndAnchor = false;

    if (ast instanceof ConcatNode) {
      children = new ArrayList<>(((ConcatNode) ast).children);

      // Check for anchors
      if (!children.isEmpty() && children.get(0) instanceof AnchorNode) {
        AnchorNode anchor = (AnchorNode) children.get(0);
        if (anchor.type == AnchorNode.Type.START || anchor.type == AnchorNode.Type.STRING_START) {
          hasStartAnchor = true;
          children.remove(0);
        }
      }
      if (!children.isEmpty() && children.get(children.size() - 1) instanceof AnchorNode) {
        AnchorNode anchor = (AnchorNode) children.get(children.size() - 1);
        if (anchor.type == AnchorNode.Type.END || anchor.type == AnchorNode.Type.STRING_END) {
          hasEndAnchor = true;
          children.remove(children.size() - 1);
        }
      }
    } else {
      children = new ArrayList<>();
      children.add(ast);
    }

    if (children.isEmpty()) {
      return null;
    }

    // Find the nested quantified group structure
    // Pattern: prefix + (nested quantified group) + suffix
    List<RegexNode> prefix = new ArrayList<>();
    List<RegexNode> suffix = new ArrayList<>();
    List<NestedQuantifiedGroupsInfo.QuantifierLevel> levels = new ArrayList<>();
    int nestedGroupIdx = -1;

    for (int i = 0; i < children.size(); i++) {
      RegexNode child = children.get(i);

      // Look for: Quant → Group → Quant (true nesting)
      if (child instanceof QuantifierNode) {
        QuantifierNode outerQuant = (QuantifierNode) child;

        // Check if the quantified content is a capturing group
        if (outerQuant.child instanceof GroupNode) {
          GroupNode outerGroup = (GroupNode) outerQuant.child;

          // TRUE NESTING CHECK: The group's child must be a quantifier wrapping a GROUP
          // This distinguishes ((a)+)* from (a?)+ where the inner quantifier wraps literal
          if (hasNestedQuantifiedGroup(outerGroup.child)) {
            nestedGroupIdx = i;

            // Build the nesting levels
            levels = extractQuantifierLevels(outerQuant);

            if (levels.size() >= 2) {
              // Found valid nested quantified group
              break;
            }
            levels.clear();
          }
        }
      }
    }

    if (nestedGroupIdx < 0 || levels.size() < 2) {
      return null;
    }

    // Skip NESTED_QUANTIFIED_GROUPS when any level's content contains alternation.
    // The generator has no AlternationNode handler and falls through to accept-any-char.
    for (NestedQuantifiedGroupsInfo.QuantifierLevel level : levels) {
      if (containsAlternation(level.content)) {
        return null;
      }
    }

    // Collect prefix and suffix
    for (int i = 0; i < nestedGroupIdx; i++) {
      prefix.add(children.get(i));
    }
    for (int i = nestedGroupIdx + 1; i < children.size(); i++) {
      suffix.add(children.get(i));
    }

    return new NestedQuantifiedGroupsInfo(
        levels, prefix, suffix, hasStartAnchor, hasEndAnchor, nfa.getGroupCount());
  }

  /**
   * Check if a node represents TRUE nested quantified GROUP structure.
   *
   * <p>TRUE nested quantified groups means there's an inner quantifier that wraps a GROUP, creating
   * a pattern like ((a)+)* where both the outer and inner levels have quantified groups.
   *
   * <p>Examples of true nested groups (returns true): - QuantifierNode(+) → GroupNode (inner
   * quantifier wraps a group) - GroupNode → QuantifierNode(+) → GroupNode (same, with extra
   * wrapping)
   *
   * <p>Examples of NOT true nested groups (returns false): - QuantifierNode(?) → LiteralNode (inner
   * quantifier on literal, e.g., (a?)+) - QuantifierNode(*) → CharClassNode (inner quantifier on
   * charclass, e.g., ([a-z]*)+) - AlternationNode → [...] (alternation, not nested quantified
   * groups)
   *
   * <p>The key distinction: - (a?)+ → Quant → Group → Quant → LITERAL (NOT nested groups) - ((a)+)*
   * → Quant → Group → Quant → GROUP (TRUE nested groups)
   */
  private boolean hasNestedQuantifiedGroup(RegexNode node) {
    // Direct quantifier - check if it wraps a group
    if (node instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) node;
      return unwrapsToGroup(quant.child);
    }

    // Group wrapping something - recurse into child
    if (node instanceof GroupNode) {
      return hasNestedQuantifiedGroup(((GroupNode) node).child);
    }

    // ConcatNode with a single child - recurse
    if (node instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) node;
      if (concat.children.size() == 1) {
        return hasNestedQuantifiedGroup(concat.children.get(0));
      }
    }

    // AlternationNode or other node types = NOT nested quantified groups
    return false;
  }

  /** Check if a node unwraps to a GroupNode (through concat/group wrappers). */
  private boolean unwrapsToGroup(RegexNode node) {
    if (node instanceof GroupNode) {
      return true;
    }
    if (node instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) node;
      if (concat.children.size() == 1) {
        return unwrapsToGroup(concat.children.get(0));
      }
    }
    return false;
  }

  /** Extract the quantifier levels from a nested structure. Returns list from outer to inner. */
  private List<NestedQuantifiedGroupsInfo.QuantifierLevel> extractQuantifierLevels(
      QuantifierNode outerQuant) {
    List<NestedQuantifiedGroupsInfo.QuantifierLevel> levels = new ArrayList<>();
    RegexNode current = outerQuant;

    while (current instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) current;
      RegexNode child = quant.child;

      int groupNumber = -1;
      RegexNode content = child;

      // If child is a capturing group, extract its info
      if (child instanceof GroupNode) {
        GroupNode group = (GroupNode) child;
        if (group.capturing) {
          groupNumber = group.groupNumber;
        }
        content = group.child;
      }

      // Determine charset/literal for simple content
      CharSet charSet = null;
      String literal = null;
      if (content instanceof CharClassNode) {
        charSet = ((CharClassNode) content).chars;
      } else if (content instanceof LiteralNode) {
        literal = String.valueOf(((LiteralNode) content).ch);
      }

      levels.add(
          new NestedQuantifiedGroupsInfo.QuantifierLevel(
              quant.min, quant.max, groupNumber, content, charSet, literal));

      // Move to inner quantifier if present
      if (content instanceof QuantifierNode) {
        current = (QuantifierNode) content;
      } else if (content instanceof ConcatNode || content instanceof AlternationNode) {
        // Look for quantifier in the content
        QuantifierNode innerQuant = findInnerQuantifier(content);
        if (innerQuant != null) {
          current = innerQuant;
        } else {
          break;
        }
      } else {
        break;
      }
    }

    return levels;
  }

  /** Find the first quantifier node inside a concat or alternation. */
  private QuantifierNode findInnerQuantifier(RegexNode node) {
    if (node instanceof QuantifierNode) {
      return (QuantifierNode) node;
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        QuantifierNode found = findInnerQuantifier(child);
        if (found != null) return found;
      }
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        QuantifierNode found = findInnerQuantifier(alt);
        if (found != null) return found;
      }
    }
    if (node instanceof GroupNode) {
      return findInnerQuantifier(((GroupNode) node).child);
    }
    return null;
  }

  /**
   * Detect simple backreference patterns that can be optimized with specialized generators.
   * Currently detects: - HTML/XML tags: <(\w+)>.*</\1>
   *
   * <p>Returns BackreferencePatternInfo if pattern matches, null otherwise.
   */
  private BackreferencePatternInfo detectSimpleBackreference(RegexNode ast) {
    // Only handle ConcatNode at top level
    if (!(ast instanceof ConcatNode)) {
      return null;
    }

    ConcatNode concat = (ConcatNode) ast;
    List<RegexNode> children = concat.children;

    // Try to detect HTML tag pattern: <(\w+)>.*</\1>
    BackreferencePatternInfo htmlTag = detectHTMLTagPattern(children);
    if (htmlTag != null) {
      return htmlTag;
    }

    // Try to detect repeated word pattern: \b(\w+)\s+\1\b
    BackreferencePatternInfo repeatedWord = detectRepeatedWordPattern(children);
    if (repeatedWord != null) {
      return repeatedWord;
    }

    // Try to detect attribute matching pattern: "([^"]+)"\s*=\s*"\1"
    BackreferencePatternInfo attributeMatch = detectAttributeMatchPattern(children);
    if (attributeMatch != null) {
      return attributeMatch;
    }

    // Try to detect greedy-any backreference pattern: (.*)\d+\1 or (.+)X\1
    BackreferencePatternInfo greedyAnyBackref = detectGreedyAnyBackrefPattern(children);
    if (greedyAnyBackref != null) {
      return greedyAnyBackref;
    }

    return null;
  }

  /**
   * Detect HTML/XML tag pattern: <(\w+)>.*</\1> Structure: literal '<', capturing group with \w+,
   * literal '>', .* quantifier, literal '<', literal '/', backreference \1, literal '>'
   */
  private BackreferencePatternInfo detectHTMLTagPattern(List<RegexNode> children) {
    // Need exactly 8 children: < (\w+) > .* < / \1 >
    if (children.size() != 8) {
      return null;
    }

    int idx = 0;

    // Check for opening '<'
    if (!(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode openBracket = (LiteralNode) children.get(idx);
    if (openBracket.ch != '<') {
      return null;
    }
    idx++;

    // Check for capturing group with \w+ (or similar char class with + quantifier)
    if (!(children.get(idx) instanceof GroupNode)) {
      return null;
    }
    GroupNode tagGroup = (GroupNode) children.get(idx);
    if (!tagGroup.capturing || tagGroup.groupNumber != 1) {
      return null; // Must be first capturing group
    }

    // Group child should be Quantifier(CharClass) for \w+
    if (!(tagGroup.child instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode tagQuantifier = (QuantifierNode) tagGroup.child;
    // Check for + quantifier: min=1, max=-1 (unlimited), greedy=true
    if (tagQuantifier.min != 1
        || (tagQuantifier.max != -1 && tagQuantifier.max != Integer.MAX_VALUE)
        || !tagQuantifier.greedy) {
      return null; // Must be + (one or more, greedy)
    }
    if (!(tagQuantifier.child instanceof CharClassNode)) {
      return null;
    }
    CharClassNode tagCharClass = (CharClassNode) tagQuantifier.child;
    // Accept \w or [a-zA-Z0-9_] or similar
    idx++;

    // Check for closing '>'
    if (!(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode closeBracket = (LiteralNode) children.get(idx);
    if (closeBracket.ch != '>') {
      return null;
    }
    idx++;

    // Check for .* (any char, zero or more, greedy)
    if (!(children.get(idx) instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode middle = (QuantifierNode) children.get(idx);
    // Check for * quantifier: min=0, max=-1 (unlimited), greedy=true
    if (middle.min != 0
        || (middle.max != -1 && middle.max != Integer.MAX_VALUE)
        || !middle.greedy) {
      return null; // Must be * (zero or more, greedy)
    }
    // Child should be DOT or any char class
    idx++;

    // Check for closing tag: '<', '/', backreference, '>'
    if (idx >= children.size()) {
      return null;
    }

    // Check for '<'
    if (!(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode closeTag1 = (LiteralNode) children.get(idx);
    if (closeTag1.ch != '<') {
      return null;
    }
    idx++;

    // Check for '/'
    if (idx >= children.size() || !(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode closeTag2 = (LiteralNode) children.get(idx);
    if (closeTag2.ch != '/') {
      return null;
    }
    idx++;

    // Check for backreference \1
    if (idx >= children.size() || !(children.get(idx) instanceof BackreferenceNode)) {
      return null;
    }
    BackreferenceNode backref = (BackreferenceNode) children.get(idx);
    if (backref.groupNumber != 1) {
      return null; // Must reference group 1
    }
    idx++;

    // Check for closing '>'
    if (idx >= children.size() || !(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode finalBracket = (LiteralNode) children.get(idx);
    if (finalBracket.ch != '>') {
      return null;
    }

    // Pattern matches! Create HTML_TAG pattern info
    CharSet tagCharSet = tagCharClass.chars;
    return new BackreferencePatternInfo(
        BackreferencePatternInfo.BackrefType.HTML_TAG,
        1, // group number
        "<", // openPrefix
        ">", // openSuffix
        tagCharSet, // tagCharSet
        "</", // closePrefix
        ">" // closeSuffix
        );
  }

  /**
   * Detect repeated word pattern: \b(\w+)\s+\1\b Structure: word boundary, capturing group with
   * \w+, \s+, backreference \1, word boundary
   */
  private BackreferencePatternInfo detectRepeatedWordPattern(List<RegexNode> children) {
    // Need exactly 5 children: \b (\w+) \s+ \1 \b
    if (children.size() != 5) {
      return null;
    }

    int idx = 0;

    // Check for opening word boundary \b
    if (!(children.get(idx) instanceof AnchorNode)) {
      return null;
    }
    AnchorNode startBoundary = (AnchorNode) children.get(idx);
    if (startBoundary.type != AnchorNode.Type.WORD_BOUNDARY) {
      return null;
    }
    idx++;

    // Check for capturing group with \w+ (or similar char class with + quantifier)
    if (!(children.get(idx) instanceof GroupNode)) {
      return null;
    }
    GroupNode wordGroup = (GroupNode) children.get(idx);
    if (!wordGroup.capturing || wordGroup.groupNumber != 1) {
      return null; // Must be first capturing group
    }

    // Group child should be Quantifier(CharClass) for \w+
    if (!(wordGroup.child instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode wordQuantifier = (QuantifierNode) wordGroup.child;
    // Check for + quantifier: min=1, max=-1 (unlimited), greedy=true
    if (wordQuantifier.min != 1
        || (wordQuantifier.max != -1 && wordQuantifier.max != Integer.MAX_VALUE)
        || !wordQuantifier.greedy) {
      return null; // Must be + (one or more, greedy)
    }
    if (!(wordQuantifier.child instanceof CharClassNode)) {
      return null;
    }
    CharClassNode wordCharClass = (CharClassNode) wordQuantifier.child;
    // Accept \w or [a-zA-Z0-9_] or similar
    idx++;

    // Check for \s+ (whitespace, one or more, greedy)
    if (!(children.get(idx) instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode spaceQuantifier = (QuantifierNode) children.get(idx);
    // Check for + quantifier: min=1, max=-1 (unlimited), greedy=true
    if (spaceQuantifier.min != 1
        || (spaceQuantifier.max != -1 && spaceQuantifier.max != Integer.MAX_VALUE)
        || !spaceQuantifier.greedy) {
      return null; // Must be + (one or more, greedy)
    }
    if (!(spaceQuantifier.child instanceof CharClassNode)) {
      return null;
    }
    // CharClassNode for \s should contain whitespace characters
    idx++;

    // Check for backreference \1
    if (idx >= children.size() || !(children.get(idx) instanceof BackreferenceNode)) {
      return null;
    }
    BackreferenceNode backref = (BackreferenceNode) children.get(idx);
    if (backref.groupNumber != 1) {
      return null; // Must reference group 1
    }
    idx++;

    // Check for closing word boundary \b
    if (idx >= children.size() || !(children.get(idx) instanceof AnchorNode)) {
      return null;
    }
    AnchorNode endBoundary = (AnchorNode) children.get(idx);
    if (endBoundary.type != AnchorNode.Type.WORD_BOUNDARY) {
      return null;
    }

    // Pattern matches! Create REPEATED_WORD pattern info
    CharSet wordCharSet = wordCharClass.chars;
    return new BackreferencePatternInfo(
        BackreferencePatternInfo.BackrefType.REPEATED_WORD,
        1, // group number
        true, // hasWordBoundary
        wordCharSet, // word character set
        "\\s+" // separator pattern (whitespace)
        );
  }

  /**
   * Detect attribute matching pattern: "([^"]+)"\s*=\s*"\1" Structure: literal '"', capturing group
   * with [^"]+, literal '"', \s*, literal '=', \s*, literal '"', backreference \1, literal '"'
   */
  private BackreferencePatternInfo detectAttributeMatchPattern(List<RegexNode> children) {
    // Need exactly 9 children: " ([^"]+) " \s* = \s* " \1 "
    if (children.size() != 9) {
      return null;
    }

    int idx = 0;

    // Check for opening quote '"'
    if (!(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode openQuote1 = (LiteralNode) children.get(idx);
    if (openQuote1.ch != '"') {
      return null;
    }
    idx++;

    // Check for capturing group with [^"]+ (negated char class with + quantifier)
    if (!(children.get(idx) instanceof GroupNode)) {
      return null;
    }
    GroupNode contentGroup = (GroupNode) children.get(idx);
    if (!contentGroup.capturing || contentGroup.groupNumber != 1) {
      return null; // Must be first capturing group
    }

    // Group child should be Quantifier(CharClass) for [^"]+
    if (!(contentGroup.child instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode contentQuantifier = (QuantifierNode) contentGroup.child;
    // Check for + quantifier: min=1, max=-1 (unlimited), greedy=true
    if (contentQuantifier.min != 1
        || (contentQuantifier.max != -1 && contentQuantifier.max != Integer.MAX_VALUE)
        || !contentQuantifier.greedy) {
      return null; // Must be + (one or more, greedy)
    }
    if (!(contentQuantifier.child instanceof CharClassNode)) {
      return null;
    }
    CharClassNode contentCharClass = (CharClassNode) contentQuantifier.child;
    // Should be negated char class [^"]
    idx++;

    // Check for closing quote '"'
    if (!(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode closeQuote1 = (LiteralNode) children.get(idx);
    if (closeQuote1.ch != '"') {
      return null;
    }
    idx++;

    // Check for \s* (zero or more whitespace)
    if (!(children.get(idx) instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode ws1 = (QuantifierNode) children.get(idx);
    // Check for * quantifier: min=0, max=-1 (unlimited), greedy=true
    if (ws1.min != 0 || (ws1.max != -1 && ws1.max != Integer.MAX_VALUE) || !ws1.greedy) {
      return null; // Must be * (zero or more, greedy)
    }
    if (!(ws1.child instanceof CharClassNode)) {
      return null;
    }
    idx++;

    // Check for '=' literal
    if (!(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode equals = (LiteralNode) children.get(idx);
    if (equals.ch != '=') {
      return null;
    }
    idx++;

    // Check for \s* (zero or more whitespace)
    if (!(children.get(idx) instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode ws2 = (QuantifierNode) children.get(idx);
    // Check for * quantifier: min=0, max=-1 (unlimited), greedy=true
    if (ws2.min != 0 || (ws2.max != -1 && ws2.max != Integer.MAX_VALUE) || !ws2.greedy) {
      return null; // Must be * (zero or more, greedy)
    }
    if (!(ws2.child instanceof CharClassNode)) {
      return null;
    }
    idx++;

    // Check for opening quote '"'
    if (!(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode openQuote2 = (LiteralNode) children.get(idx);
    if (openQuote2.ch != '"') {
      return null;
    }
    idx++;

    // Check for backreference \1
    if (idx >= children.size() || !(children.get(idx) instanceof BackreferenceNode)) {
      return null;
    }
    BackreferenceNode backref = (BackreferenceNode) children.get(idx);
    if (backref.groupNumber != 1) {
      return null; // Must reference group 1
    }
    idx++;

    // Check for closing quote '"'
    if (idx >= children.size() || !(children.get(idx) instanceof LiteralNode)) {
      return null;
    }
    LiteralNode closeQuote2 = (LiteralNode) children.get(idx);
    if (closeQuote2.ch != '"') {
      return null;
    }

    // Pattern matches! Create ATTRIBUTE_MATCH pattern info
    CharSet contentCharSet = contentCharClass.chars;
    return new BackreferencePatternInfo(
        BackreferencePatternInfo.BackrefType.ATTRIBUTE_MATCH,
        1, // group number
        "\"", // quote char
        contentCharSet, // content character set [^"]
        "=" // assignment operator
        );
  }

  /**
   * Detect greedy-any backreference pattern: (.*)\d+\1 or (.+)X\1 Structure: capturing group with
   * .* or .+, separator content, backreference to group Uses backtracking by trying different group
   * lengths from longest to shortest.
   */
  private BackreferencePatternInfo detectGreedyAnyBackrefPattern(List<RegexNode> children) {
    // Need at least 3 children: (.*) separator \1
    if (children.size() < 3) {
      return null;
    }

    // First child must be a capturing group
    if (!(children.get(0) instanceof GroupNode)) {
      return null;
    }
    GroupNode group = (GroupNode) children.get(0);
    if (!group.capturing) {
      return null;
    }
    int groupNumber = group.groupNumber;

    // Group child should be Quantifier on dot or char class for .* or .+
    // Handle nested groups like ((.*)) by unwrapping to find the quantifier
    // Count total groups while unwrapping
    int totalGroupCount = 1; // Start with the outer group
    RegexNode innerNode = group.child;
    while (innerNode instanceof GroupNode) {
      GroupNode innerGroup = (GroupNode) innerNode;
      if (innerGroup.capturing) {
        totalGroupCount++;
      }
      innerNode = innerGroup.child;
    }
    if (!(innerNode instanceof QuantifierNode)) {
      return null;
    }
    QuantifierNode quantifier = (QuantifierNode) innerNode;

    // Check for greedy * or + quantifier
    if (!quantifier.greedy) {
      return null;
    }
    int minCount;
    if (quantifier.min == 0 && (quantifier.max == -1 || quantifier.max == Integer.MAX_VALUE)) {
      minCount = 0; // .* pattern
    } else if (quantifier.min == 1
        && (quantifier.max == -1 || quantifier.max == Integer.MAX_VALUE)) {
      minCount = 1; // .+ pattern
    } else {
      return null; // Not .* or .+
    }

    // Check that quantified node is dot (any character) - NOT a specific char class like \w
    // This pattern is for (.*) or (.+), not (\w+) or similar
    CharSet groupCharSet;
    if (quantifier.child instanceof CharClassNode) {
      CharClassNode charClass = (CharClassNode) quantifier.child;
      groupCharSet = charClass.chars;
      // Only accept if it's a "dot" (any character) charset
      // A dot charset covers the full range 0x0000-0xFFFF (or close to it)
      // \w is only about 63 chars, so we reject it
      // Calculate total size of all ranges
      int charSetSize = 0;
      for (CharSet.Range range : groupCharSet.getRanges()) {
        charSetSize += range.end - range.start + 1;
      }
      if (charSetSize < 1000) {
        return null; // Not a broad enough char class - this is like \w, not .
      }
    } else {
      // Not a char class - can't detect
      return null;
    }

    // Last child must be a backreference to this group
    RegexNode lastChild = children.get(children.size() - 1);
    if (!(lastChild instanceof BackreferenceNode)) {
      return null;
    }
    BackreferenceNode backref = (BackreferenceNode) lastChild;
    if (backref.groupNumber != groupNumber) {
      return null;
    }

    // Middle content is the separator (everything between group and backref)
    // Must have at least one separator element
    if (children.size() < 3) {
      return null;
    }

    // Build separator AST from middle children
    RegexNode separatorNode;
    if (children.size() == 3) {
      separatorNode = children.get(1);
    } else {
      // Multiple separator elements - wrap in ConcatNode
      List<RegexNode> separatorChildren = new ArrayList<>();
      for (int i = 1; i < children.size() - 1; i++) {
        separatorChildren.add(children.get(i));
      }
      separatorNode = new ConcatNode(separatorChildren);
    }

    // Pattern matches! Create GREEDY_ANY_BACKREF pattern info
    return new BackreferencePatternInfo(
        BackreferencePatternInfo.BackrefType.GREEDY_ANY_BACKREF,
        groupNumber,
        groupCharSet,
        minCount,
        separatorNode,
        totalGroupCount);
  }

  /** Visitor to detect capturing groups in AST. */
  private static class CapturingGroupDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.capturing || node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      for (RegexNode alt : node.alternatives) {
        if (alt.accept(this)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Visitor to detect backreferences in AST. */
  private static class BackrefDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return true;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      // Recurse into the assertion's sub-pattern so backrefs inside lookahead/lookbehind are found.
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false; // Subroutines don't contain backreferences themselves
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches for backreferences
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      // Check all alternatives for backreferences
      for (RegexNode alt : node.alternatives) {
        if (alt.accept(this)) {
          return true;
        }
      }
      return false;
    }
  }

  /** Visitor to detect lookaround assertions in AST. */
  private static class LookaroundDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return true; // Found lookaround!
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false; // Subroutines themselves don't contain lookaround (checked separately)
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches for lookaround
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      // Check all alternatives for lookaround
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /** Visitor to detect subroutine calls in AST. */
  private static class SubroutineDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return true; // Found subroutine!
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches for subroutines
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /** Visitor to detect conditional patterns in AST. */
  private static class ConditionalDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false; // Subroutines checked separately
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      return true; // Found conditional!
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /** Visitor to detect branch reset groups in AST. */
  private static class BranchResetDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false; // Subroutines checked separately
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches for branch reset
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return true; // Found branch reset!
    }
  }

  /**
   * Visitor to detect non-greedy (reluctant) quantifiers in AST. Non-greedy quantifiers require
   * backtracking support.
   */
  private static class NonGreedyQuantifierDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      // Check if this quantifier is non-greedy (possessive quantifiers are not lazy).
      // Note: node.possessive is always false in parsed patterns — the parser converts X*+/X++/
      // X?+/X{n,m}+ to GroupNode(atomic=true) wrapping a greedy QuantifierNode rather than setting
      // node.possessive=true. The !node.possessive guard is therefore purely defensive for
      // hypothetical future parser changes and does not change current behavior.
      if (!node.greedy && !node.possessive) {
        return true; // Found non-greedy quantifier!
      }
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /** Visitor to detect possessive quantifiers (X*+, X++, X?+, etc.) in AST. */
  private static class PossessiveQuantifierDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      // Note: node.possessive is always false in parsed patterns. The parser converts X*+/X++/
      // X?+/X{n,m}+ to GroupNode(atomic=true) wrapping a greedy QuantifierNode rather than setting
      // node.possessive=true. This check is therefore currently dead code, but is kept as a
      // defensive guard for hypothetical future parser changes. visitGroup() also checks
      // node.atomic for the current parser representation; both together make this detector
      // redundant-safe regardless of whether the parser encodes possessives via node.possessive or
      // GroupNode(atomic=true).
      if (node.possessive) {
        return true;
      }
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      // Also return true for atomic groups (GroupNode.atomic == true), since the parser converts
      // X*+/X++/X?+ to GroupNode(atomic=true) wrapping a greedy QuantifierNode. This makes the
      // detector redundant-safe: even if a future caller skips the hasAtomicGroups() check, the
      // possessive/atomic construct is still caught here.
      if (node.atomic) {
        return true;
      }
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      // node.subPattern is always non-null by design (final field set in constructor), but guard
      // defensively for consistency with AtomicGroupDetector.visitAssertion.
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /**
   * Visitor to detect start/end anchors (^, $) in AST. Word boundaries (\b) are not considered
   * anchors for this check.
   */
  private static class AnchorDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      // Detect start/end anchors, but not word boundaries
      AnchorNode.Type type = node.type;
      return type == AnchorNode.Type.START || type == AnchorNode.Type.END;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      // Recurse into the assertion's sub-pattern so backrefs inside lookahead/lookbehind are found.
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      // Subroutines are calls, not anchor definitions
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches for anchors
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      // Check all alternatives for anchors
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /**
   * Visitor to detect capturing groups inside repeating quantifiers. Used to determine when POSIX
   * last-match semantics are needed. Example patterns: (a+|b)+, ((a)|(b))*, (x)+
   */
  private static class GroupInQuantifierDetector implements RegexVisitor<Boolean> {
    private boolean inQuantifier = false;

    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      // Only repeating quantifiers (*, +, {n,}) need POSIX semantics
      boolean isRepeating = (node.max == -1 || node.max > 1);

      if (isRepeating) {
        boolean wasInQuantifier = inQuantifier;
        inQuantifier = true;
        boolean result = node.child.accept(this);
        inQuantifier = wasInQuantifier;
        return result;
      } else {
        return node.child.accept(this);
      }
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      // Found capturing group inside quantifier - needs POSIX!
      if (inQuantifier && node.capturing) {
        return true;
      }
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      // Subroutines are calls - don't analyze quantifier nesting
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches while preserving quantifier state
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      // Check all alternatives while preserving quantifier state
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /**
   * Visitor to analyze assertions for DFA compatibility. Determines if assertions are fixed-width
   * (DFA-compatible) or variable-width (NFA-only).
   */
  private static class AssertionAnalyzer implements RegexVisitor<Void> {
    boolean allFixedWidth = true;
    boolean hasComplexQuantifiers = false;

    @Override
    public Void visitLiteral(LiteralNode node) {
      return null;
    }

    @Override
    public Void visitCharClass(CharClassNode node) {
      return null;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      node.children.forEach(child -> child.accept(this));
      return null;
    }

    @Override
    public Void visitAlternation(AlternationNode node) {
      node.alternatives.forEach(alt -> alt.accept(this));
      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      // Check for variable-width quantifiers: *, +, {n,}, {n,m} where n != m
      if (node.min == 0 && node.max == -1) {
        // * quantifier - variable width
        hasComplexQuantifiers = true;
      } else if (node.min == 1 && node.max == -1) {
        // + quantifier - variable width
        hasComplexQuantifiers = true;
      } else if (node.max == -1) {
        // {n,} - variable width
        hasComplexQuantifiers = true;
      } else if (node.min != node.max) {
        // {n,m} where n != m - variable width
        hasComplexQuantifiers = true;
      }
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitGroup(GroupNode node) {
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitAnchor(AnchorNode node) {
      return null;
    }

    @Override
    public Void visitBackreference(BackreferenceNode node) {
      // Backreferences make assertions variable-width
      allFixedWidth = false;
      return null;
    }

    @Override
    public Void visitAssertion(AssertionNode node) {
      // For all assertions (lookahead and lookbehind), analyze sub-pattern
      // to determine if fixed-width. If sub-pattern has complex quantifiers,
      // it's variable-width and must use NFA.

      // Recursively analyze sub-pattern for complex quantifiers
      node.subPattern.accept(this);

      // If sub-pattern has complex quantifiers, assertion is variable-width
      if (hasComplexQuantifiers) {
        allFixedWidth = false;
      }

      return null;
    }

    @Override
    public Void visitSubroutine(SubroutineNode node) {
      // Subroutines are complex and variable-width
      allFixedWidth = false;
      return null;
    }

    @Override
    public Void visitConditional(ConditionalNode node) {
      // Analyze both branches
      node.thenBranch.accept(this);
      if (node.elseBranch != null) {
        node.elseBranch.accept(this);
      }
      return null;
    }

    @Override
    public Void visitBranchReset(BranchResetNode node) {
      // Analyze all alternatives
      node.alternatives.forEach(alt -> alt.accept(this));
      return null;
    }
  }

  /**
   * Visitor to analyze quantifier usage in patterns. Determines if pattern has unbounded or bounded
   * quantifiers.
   */
  private static class QuantifierAnalyzer implements RegexVisitor<Void> {
    boolean hasUnboundedQuantifiers = false;
    boolean hasBoundedQuantifiers = false;

    @Override
    public Void visitLiteral(LiteralNode node) {
      return null;
    }

    @Override
    public Void visitCharClass(CharClassNode node) {
      return null;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      node.children.forEach(child -> child.accept(this));
      return null;
    }

    @Override
    public Void visitAlternation(AlternationNode node) {
      node.alternatives.forEach(alt -> alt.accept(this));
      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      if (node.max == -1) {
        hasUnboundedQuantifiers = true;
      } else {
        hasBoundedQuantifiers = true;
      }
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitGroup(GroupNode node) {
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitAnchor(AnchorNode node) {
      return null;
    }

    @Override
    public Void visitBackreference(BackreferenceNode node) {
      return null;
    }

    @Override
    public Void visitAssertion(AssertionNode node) {
      node.subPattern.accept(this);
      return null;
    }

    @Override
    public Void visitSubroutine(SubroutineNode node) {
      // Subroutines are calls - no quantifiers to analyze
      return null;
    }

    @Override
    public Void visitConditional(ConditionalNode node) {
      // Analyze both branches for quantifiers
      node.thenBranch.accept(this);
      if (node.elseBranch != null) {
        node.elseBranch.accept(this);
      }
      return null;
    }

    @Override
    public Void visitBranchReset(BranchResetNode node) {
      // Analyze all alternatives for quantifiers
      node.alternatives.forEach(alt -> alt.accept(this));
      return null;
    }
  }

  /**
   * Visitor to analyze character class complexity. More complex character classes require more
   * comparisons per character.
   */
  private static class CharClassComplexityAnalyzer implements RegexVisitor<Void> {
    int maxComplexity = 1;

    @Override
    public Void visitLiteral(LiteralNode node) {
      return null;
    }

    @Override
    public Void visitCharClass(CharClassNode node) {
      // Estimate complexity based on charset size and negation
      int complexity = 1;
      if (node.negated) {
        complexity = 3; // Negated classes are more expensive
      } else {
        int size = 0;
        for (CharSet.Range range : node.chars.getRanges()) {
          size += range.size();
        }
        if (size > 10) {
          complexity = 2; // Large character classes
        }
      }
      maxComplexity = Math.max(maxComplexity, complexity);
      return null;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      node.children.forEach(child -> child.accept(this));
      return null;
    }

    @Override
    public Void visitAlternation(AlternationNode node) {
      node.alternatives.forEach(alt -> alt.accept(this));
      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitGroup(GroupNode node) {
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitAnchor(AnchorNode node) {
      return null;
    }

    @Override
    public Void visitBackreference(BackreferenceNode node) {
      return null;
    }

    @Override
    public Void visitAssertion(AssertionNode node) {
      node.subPattern.accept(this);
      return null;
    }

    @Override
    public Void visitSubroutine(SubroutineNode node) {
      // Subroutines are calls - no char class complexity to analyze
      return null;
    }

    @Override
    public Void visitConditional(ConditionalNode node) {
      // Analyze both branches for char class complexity
      node.thenBranch.accept(this);
      if (node.elseBranch != null) {
        node.elseBranch.accept(this);
      }
      return null;
    }

    @Override
    public Void visitBranchReset(BranchResetNode node) {
      // Analyze all alternatives for char class complexity
      node.alternatives.forEach(alt -> alt.accept(this));
      return null;
    }
  }

  /**
   * Visitor to detect variable-length quantifiers inside capturing groups. DFA group tracking only
   * works for fixed-length patterns.
   */
  private static class VariableLengthInGroupsDetector implements RegexVisitor<Boolean> {
    private boolean inGroup = false;

    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      // Check if this is a variable-length quantifier
      boolean isVariableLength = false;
      if (node.min == 0 && node.max == -1) {
        // * quantifier - variable length
        isVariableLength = true;
      } else if (node.min == 1 && node.max == -1) {
        // + quantifier - variable length
        isVariableLength = true;
      } else if (node.max == -1) {
        // {n,} - variable length
        isVariableLength = true;
      } else if (node.min != node.max) {
        // {n,m} where n != m - variable length
        isVariableLength = true;
      }

      // If we're inside a group and found variable-length, return true
      if (inGroup && isVariableLength) {
        return true;
      }

      // Recursively check child
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      // Only check capturing groups (non-capturing groups don't matter)
      if (node.capturing) {
        boolean wasInGroup = inGroup;
        inGroup = true;
        boolean result = node.child.accept(this);
        inGroup = wasInGroup;
        return result;
      } else {
        return node.child.accept(this);
      }
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      // Assertions don't affect group tracking
      return false;
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      // Subroutines are calls - don't analyze for variable-length
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      // Check both branches while preserving inGroup state
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      // Check all alternatives while preserving inGroup state
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /**
   * Visitor to detect quantified backreferences in AST. Detects patterns like \1{2,3}, \1+, (\1)*,
   * etc. that require backtracking.
   *
   * <p>Uses context tracking to detect backreferences inside quantifiers, including when nested
   * inside groups: (\1){2,3}
   */
  private static class QuantifiedBackrefDetector implements RegexVisitor<Boolean> {
    private boolean inQuantifier = false;

    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      // Set inQuantifier flag, then recursively check child
      // This handles both direct quantified backrefs (\1{2,3})
      // and nested ones ((\1){2,3})
      boolean wasInQuantifier = inQuantifier;
      inQuantifier = true;
      boolean result = node.child.accept(this);
      inQuantifier = wasInQuantifier;
      return result;
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      // Continue searching inside the group
      // Preserves inQuantifier state so (\1){2,3} is detected correctly
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      // Found backreference - return true if we're inside a quantifier
      return inQuantifier;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /**
   * Returns true if the subtree of {@code group} contains a {@link BackreferenceNode} whose {@code
   * groupNumber} equals {@code group.groupNumber}. Evaluated at code-generation time.
   *
   * <p>Early exit: if the group's subtree has no backreferences at all, returns false immediately.
   */
  public static boolean hasSelfReferencingBackref(GroupNode group) {
    if (group.groupNumber <= 0) {
      return false;
    }
    // Quick check: does the subtree contain ANY backref?
    BackrefDetector anyBackref = new BackrefDetector();
    if (!group.child.accept(anyBackref)) {
      return false;
    }
    SelfRefBackrefDetector detector = new SelfRefBackrefDetector(group.groupNumber);
    return group.child.accept(detector);
  }

  /** Detects whether a subtree contains a backreference to a specific group number. */
  private static class SelfRefBackrefDetector implements RegexVisitor<Boolean> {
    private final int targetGroupNumber;

    SelfRefBackrefDetector(int targetGroupNumber) {
      this.targetGroupNumber = targetGroupNumber;
    }

    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return node.groupNumber == targetGroupNumber;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /**
   * Visitor that returns {@code true} when the AST contains an atomic group ({@code (?>...)}) or a
   * possessive quantifier ({@code X*+}, {@code X++}, {@code X?+}, {@code X{n,m}+}).
   */
  private static class AtomicGroupDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode n) {
      if (n.possessive) {
        return true; // Possessive quantifiers require atomic-group commit semantics
      }
      return n.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      if (node.atomic) {
        return true;
      }
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  /** Information about a greedy char class pattern like (\d+) or ([a-z]*). */
  public static class GreedyCharClassInfo implements PatternInfo {
    public final CharSet charset;
    public final boolean negated;
    public final int minMatches; // 0 for *, 1 for +
    public final int maxMatches; // -1 for unbounded

    public GreedyCharClassInfo(CharSet charset, boolean negated, int minMatches, int maxMatches) {
      this.charset = charset;
      this.negated = negated;
      this.minMatches = minMatches;
      this.maxMatches = maxMatches;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + (negated ? 1 : 0);
      hash = 31 * hash + minMatches;
      hash = 31 * hash + (maxMatches == -1 ? Integer.MAX_VALUE : maxMatches);
      return hash;
    }
  }

  /**
   * Information about a fixed-length sequence pattern like \d{3}-\d{3}-\d{4} or \d{4}-\d{2}-\d{2}.
   */
  public static class FixedSequenceInfo implements PatternInfo {
    public final List<SequenceElement> elements;
    public final int totalLength; // Total expected length (or -1 if has optional parts)
    public final int minLength; // Minimum length (with optional parts excluded)
    public final int maxLength; // Maximum length (with optional parts included)
    public final int groupCount; // Number of capturing groups

    public FixedSequenceInfo(List<SequenceElement> elements, int groupCount) {
      this.elements = elements;
      this.groupCount = groupCount;
      int min = 0;
      int max = 0;
      for (SequenceElement elem : elements) {
        min += elem.minLength();
        max += elem.maxLength();
      }
      this.minLength = min;
      this.maxLength = max;
      this.totalLength = (min == max) ? min : -1; // -1 if variable length
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + elements.size();
      hash = 31 * hash + minLength;
      hash = 31 * hash + maxLength;
      hash = 31 * hash + groupCount;
      return hash;
    }
  }

  /**
   * Information about bounded quantifier sequence patterns. Examples:
   * \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3} (IPv4), [0-9a-f]{8}-[0-9a-f]{4}-... (UUID)
   */
  public static class BoundedQuantifierInfo implements PatternInfo {
    public final List<BoundedElement> elements;
    public final int minLength;
    public final int maxLength;
    public final int groupCount;

    public BoundedQuantifierInfo(
        List<BoundedElement> elements, int minLength, int maxLength, int groupCount) {
      this.elements = elements;
      this.minLength = minLength;
      this.maxLength = maxLength;
      this.groupCount = groupCount;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + elements.size();
      hash = 31 * hash + minLength;
      hash = 31 * hash + maxLength;
      hash = 31 * hash + groupCount;
      return hash;
    }
  }

  /** Base interface for bounded quantifier elements. */
  public interface BoundedElement {
    int minLength();

    int maxLength();
  }

  /** Character class with bounded quantifier like \d{1,3} or [a-f]{2,5}. */
  public static class BoundedQuantifierElement implements BoundedElement {
    public final CharSet charset;
    public final boolean negated;
    public final int min;
    public final int max;
    public final int groupNumber; // -1 if not captured

    public BoundedQuantifierElement(
        CharSet charset, boolean negated, int min, int max, int groupNumber) {
      this.charset = charset;
      this.negated = negated;
      this.min = min;
      this.max = max;
      this.groupNumber = groupNumber;
    }

    @Override
    public int minLength() {
      return min;
    }

    @Override
    public int maxLength() {
      return max;
    }
  }

  /** Literal character element like '.' or '-' in bounded quantifier patterns. */
  public static class BoundedLiteralElement implements BoundedElement {
    public final char literal;

    public BoundedLiteralElement(char literal) {
      this.literal = literal;
    }

    @Override
    public int minLength() {
      return 1;
    }

    @Override
    public int maxLength() {
      return 1;
    }
  }

  /** Optional literal element like [- ]? in credit card patterns. */
  public static class BoundedOptionalElement implements BoundedElement {
    public final char literal;

    public BoundedOptionalElement(char literal) {
      this.literal = literal;
    }

    @Override
    public int minLength() {
      return 0;
    }

    @Override
    public int maxLength() {
      return 1;
    }
  }

  /** Base interface for sequence elements. */
  public interface SequenceElement {
    int minLength();

    int maxLength();

    int getGroupNumber(); // Which capturing group this element belongs to (-1 if none)
  }

  /** Literal character element like '-' or ':'. */
  public static class LiteralElement implements SequenceElement {
    public final char ch;
    public final int groupNumber;

    public LiteralElement(char ch, int groupNumber) {
      this.ch = ch;
      this.groupNumber = groupNumber;
    }

    @Override
    public int minLength() {
      return 1;
    }

    @Override
    public int maxLength() {
      return 1;
    }

    @Override
    public int getGroupNumber() {
      return groupNumber;
    }
  }

  /** Fixed repetition element like \d{3} or [a-f]{2}. */
  public static class RepetitionElement implements SequenceElement {
    public final CharSet charset;
    public final boolean negated;
    public final int count;
    public final int groupNumber;

    public RepetitionElement(CharSet charset, boolean negated, int count, int groupNumber) {
      this.charset = charset;
      this.negated = negated;
      this.count = count;
      this.groupNumber = groupNumber;
    }

    @Override
    public int minLength() {
      return count;
    }

    @Override
    public int maxLength() {
      return count;
    }

    @Override
    public int getGroupNumber() {
      return groupNumber;
    }
  }

  /** Optional literal element like '-'? or 's'?. */
  public static class OptionalLiteralElement implements SequenceElement {
    public final char ch;
    public final int groupNumber;

    public OptionalLiteralElement(char ch, int groupNumber) {
      this.ch = ch;
      this.groupNumber = groupNumber;
    }

    @Override
    public int minLength() {
      return 0;
    }

    @Override
    public int maxLength() {
      return 1;
    }

    @Override
    public int getGroupNumber() {
      return groupNumber;
    }
  }

  /**
   * Information about a multi-group greedy pattern like ([a-z]+)@([a-z]+)\.com or
   * (\d{3})-(\d+)-(\d{4}). Contains sequence of literal and group segments.
   */
  public static class MultiGroupGreedyInfo implements PatternInfo {
    public final List<Segment> segments;
    public final int groupCount;

    public MultiGroupGreedyInfo(List<Segment> segments, int groupCount) {
      this.segments = segments;
      this.groupCount = groupCount;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + segments.size();
      hash = 31 * hash + groupCount;
      return hash;
    }
  }

  /** Base interface for multi-group pattern segments. */
  public interface Segment {}

  /** Literal segment like '@', '-', or '.com'. */
  public static class LiteralSegment implements Segment {
    public final String literal;

    public LiteralSegment(String literal) {
      this.literal = literal;
    }
  }

  /** Variable-length group segment like ([a-z]+) or (\d+). */
  public static class VariableGroupSegment implements Segment {
    public final CharSet charset;
    public final boolean negated;
    public final int minMatches; // 0 for *, 1 for +
    public final int groupNumber;

    public VariableGroupSegment(CharSet charset, boolean negated, int minMatches, int groupNumber) {
      this.charset = charset;
      this.negated = negated;
      this.minMatches = minMatches;
      this.groupNumber = groupNumber;
    }
  }

  /** Fixed-length group segment like ([a-z]{3}) or (\d{4}). */
  public static class FixedGroupSegment implements Segment {
    public final CharSet charset;
    public final boolean negated;
    public final int length;
    public final int groupNumber;

    public FixedGroupSegment(CharSet charset, boolean negated, int length, int groupNumber) {
      this.charset = charset;
      this.negated = negated;
      this.length = length;
      this.groupNumber = groupNumber;
    }
  }

  /** Anchor segment like ^ (start) or $ (end). */
  public static class AnchorSegment implements Segment {
    public final AnchorNode.Type type;

    public AnchorSegment(AnchorNode.Type type) {
      this.type = type;
    }
  }

  /**
   * Literal group segment like (abc) or (test123). Contains a literal string inside a capturing or
   * non-capturing group.
   */
  public static class LiteralGroupSegment implements Segment {
    public final String literal;
    public final int groupNumber; // 0 for non-capturing, >0 for capturing

    public LiteralGroupSegment(String literal, int groupNumber) {
      this.literal = literal;
      this.groupNumber = groupNumber;
    }
  }

  /**
   * Information about hybrid DFA lookahead optimization. Maps NFA assertion states to precomputed
   * DFAs for their sub-patterns.
   */
  public static class HybridDFALookaheadInfo implements PatternInfo {
    public final java.util.Map<Integer, DFA> assertionDFAs; // assertionStateId -> DFA
    public final java.util.Map<Integer, AssertionNode>
        assertionNodes; // assertionStateId -> AST node

    public HybridDFALookaheadInfo(
        java.util.Map<Integer, DFA> assertionDFAs,
        java.util.Map<Integer, AssertionNode> assertionNodes) {
      this.assertionDFAs = assertionDFAs;
      this.assertionNodes = assertionNodes;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + assertionDFAs.size();
      hash = 31 * hash + assertionNodes.size();
      return hash;
    }
  }

  /**
   * Information about stateless pattern that doesn't require state tracking. These patterns can be
   * matched using simple loops without BitSet/SparseSet overhead.
   *
   * <p>Pattern types: - SINGLE_QUANTIFIER: Simple quantifier like \w+, \d*, [a-z]{5,10} -
   * LOOKAHEAD_LITERAL: Lookahead assertion + literal suffix like (?=\w+@).*@example.com
   */
  public static class StatelessPatternInfo implements PatternInfo {
    public enum PatternType {
      SINGLE_QUANTIFIER, // Single element with quantifier
      LOOKAHEAD_LITERAL // Lookahead assertion followed by .* and literal suffix
    }

    public final PatternType type;
    public final CharSet charset; // For SINGLE_QUANTIFIER: the character set
    public final boolean negated; // For SINGLE_QUANTIFIER: whether charset is negated
    public final int minReps; // For SINGLE_QUANTIFIER: minimum repetitions
    public final int maxReps; // For SINGLE_QUANTIFIER: maximum repetitions (-1 for unbounded)
    public final String literalSuffix; // For LOOKAHEAD_LITERAL: the literal to search for
    public final AssertionNode lookahead; // For LOOKAHEAD_LITERAL: the lookahead assertion

    // Constructor for SINGLE_QUANTIFIER
    public StatelessPatternInfo(CharSet charset, boolean negated, int minReps, int maxReps) {
      this.type = PatternType.SINGLE_QUANTIFIER;
      this.charset = charset;
      this.negated = negated;
      this.minReps = minReps;
      this.maxReps = maxReps;
      this.literalSuffix = null;
      this.lookahead = null;
    }

    // Constructor for LOOKAHEAD_LITERAL
    public StatelessPatternInfo(AssertionNode lookahead, String literalSuffix) {
      this.type = PatternType.LOOKAHEAD_LITERAL;
      this.lookahead = lookahead;
      this.literalSuffix = literalSuffix;
      this.charset = null;
      this.negated = false;
      this.minReps = 0;
      this.maxReps = 0;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + type.hashCode();
      hash = 31 * hash + (negated ? 1 : 0);
      hash = 31 * hash + minReps;
      hash = 31 * hash + (maxReps == -1 ? Integer.MAX_VALUE : maxReps);
      hash = 31 * hash + (literalSuffix != null ? literalSuffix.length() : 0);
      // Include charset to distinguish \d+ from [a-z]+
      if (charset != null) {
        hash = 31 * hash + charset.hashCode();
      }
      return hash;
    }
  }

  /**
   * Information about pure literal alternation patterns like keyword1|keyword2|...|keywordN. These
   * patterns can be optimized using trie-based matching instead of DFA state machines.
   */
  public static class LiteralAlternationInfo implements PatternInfo {
    public final List<String> keywords; // ["keyword1", "keyword2", ...]
    public final int maxLength; // Length of longest keyword
    public final int minLength; // Length of shortest keyword
    public final int groupCount; // Number of capturing groups (0 if none)

    public LiteralAlternationInfo(
        List<String> keywords, int minLength, int maxLength, int groupCount) {
      this.keywords = keywords;
      this.minLength = minLength;
      this.maxLength = maxLength;
      this.groupCount = groupCount;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + keywords.size();
      hash = 31 * hash + minLength;
      hash = 31 * hash + maxLength;
      hash = 31 * hash + groupCount;
      return hash;
    }
  }

  /**
   * Detect greedy char class pattern: single capturing group with char class + greedy quantifier.
   * Examples: (\d+), ([a-z]*), (\w+)
   */
  private GreedyCharClassInfo detectGreedyCharClass(RegexNode ast) {
    // Pattern must be a single capturing group with greedy charclass
    // Plain patterns like \d+ (without capturing group) should use DFA for better performance
    if (!(ast instanceof GroupNode)) {
      return null;
    }

    GroupNode group = (GroupNode) ast;
    if (!group.capturing) {
      return null;
    }

    // Group must contain: CharClass + Greedy Quantifier
    if (!(group.child instanceof QuantifierNode)) {
      return null;
    }

    QuantifierNode quant = (QuantifierNode) group.child;
    if (!quant.greedy) {
      return null; // Must be greedy
    }

    // Check for unbounded or reasonable upper bound
    if (quant.max != -1 && quant.max > 1000) {
      return null; // Too large to optimize
    }
    // {0} always produces an empty match; specialized generator does not handle max=0 correctly.
    if (quant.max == 0) {
      return null;
    }

    if (!(quant.child instanceof CharClassNode)) {
      return null;
    }

    CharClassNode charClass = (CharClassNode) quant.child;

    return new GreedyCharClassInfo(
        charClass.chars,
        charClass.negated,
        quant.min, // 0 for *, 1 for +
        quant.max // -1 for unbounded
        );
  }

  /**
   * Detect fixed-length sequence pattern: concatenation of fixed elements. Examples:
   * \d{3}-\d{3}-\d{4}, \d{4}-\d{2}-\d{2}, (\d{3})-(\d{3})-(\d{4})
   */
  private FixedSequenceInfo detectFixedSequence(RegexNode ast) {
    List<SequenceElement> elements = new ArrayList<>();
    int[] groupCounter = {0}; // Mutable counter for group numbers

    // Handle exact-count literal group repetition: (?:abc){n} → expand to n×|body| literals.
    // This bypasses the maxLength guard below because inlined char comparisons are always faster
    // than DFA_TABLE dispatch for pure-literal repeated patterns.
    if (ast instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) ast;
      if (quant.min == quant.max && quant.min >= 1 && quant.min <= 1000) {
        List<Character> body = extractLiteralBody(quant.child);
        if (body != null && !body.isEmpty()) {
          int totalLen = quant.min * body.size();
          if (totalLen >= 2) {
            List<SequenceElement> expanded = new ArrayList<>(totalLen);
            for (int rep = 0; rep < quant.min; rep++) {
              for (char ch : body) {
                expanded.add(new LiteralElement(ch, -1));
              }
            }
            return new FixedSequenceInfo(expanded, 0);
          }
        }
      }
    }

    // Handle single element (not a sequence, but could be a single fixed element)
    if (!(ast instanceof ConcatNode)) {
      // Try to analyze as single element
      SequenceElement elem = analyzeElement(ast, -1, groupCounter);
      if (elem != null && elem.minLength() == elem.maxLength() && elem.minLength() > 1) {
        // Single fixed element like \d{10} - worth optimizing
        elements.add(elem);
        return new FixedSequenceInfo(elements, groupCounter[0]);
      }
      return null; // Not a sequence
    }

    ConcatNode concat = (ConcatNode) ast;

    // Analyze each child element, using flattenElement to handle multi-char literal groups
    // like (foo)(bar) which analyzeElement alone cannot decompose.
    for (RegexNode child : concat.children) {
      int prevSize = elements.size();
      if (!flattenElement(child, -1, groupCounter, elements)) {
        return null; // Contains unsupported element
      }
      // Reject patterns with optional elements - let BoundedQuantifier or NFA handle them
      for (int i = prevSize; i < elements.size(); i++) {
        if (elements.get(i) instanceof OptionalLiteralElement) {
          return null;
        }
      }
    }

    // Must have at least 2 elements to be worth optimizing as a sequence
    if (elements.size() < 2) {
      return null;
    }

    // Check if it's actually fixed-length or has reasonable bounds
    FixedSequenceInfo info = new FixedSequenceInfo(elements, groupCounter[0]);

    // Only optimize if length is reasonable
    if (info.maxLength > 100) {
      return null; // Too long to unroll
    }

    // Must have some fixed structure (not all optional)
    if (info.minLength == 0) {
      return null; // All optional - not worth optimizing
    }

    return info;
  }

  /**
   * Detect bounded quantifier sequence patterns. Examples: \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}
   * (IPv4), [0-9a-f]{8}-[0-9a-f]{4}-... (UUID)
   *
   * <p>Criteria: - Pattern contains bounded quantifiers {n,m} where 0 < n <= m, m - n <= 5 -
   * Alternates between quantified char classes and literals - Total elements <= 20, max length <=
   * 100 - No unbounded quantifiers, backreferences, or complex alternations
   */
  private BoundedQuantifierInfo detectBoundedQuantifierSequence(RegexNode ast) {
    List<BoundedElement> elements = new ArrayList<>();
    int[] groupCounter = {0}; // Mutable counter for group numbers

    // Handle single element
    if (!(ast instanceof ConcatNode)) {
      BoundedElement elem = analyzeBoundedElement(ast, -1, groupCounter);
      if (elem != null && elem.maxLength() <= 100 && elem.minLength() >= 1) {
        // Reject patterns with capturing groups - this strategy doesn't support group tracking
        if (groupCounter[0] > 0) {
          return null;
        }
        // Single bounded element worth optimizing
        elements.add(elem);
        return new BoundedQuantifierInfo(
            elements, elem.minLength(), elem.maxLength(), groupCounter[0]);
      }
      return null;
    }

    ConcatNode concat = (ConcatNode) ast;

    // Analyze each child element
    for (RegexNode child : concat.children) {
      BoundedElement elem = analyzeBoundedElement(child, -1, groupCounter);
      if (elem == null) {
        return null; // Contains unsupported element
      }
      elements.add(elem);
    }

    // Must have at least 2 elements
    if (elements.size() < 2) {
      return null;
    }

    // Element count threshold
    if (elements.size() > 20) {
      return null; // Too many elements
    }

    // Calculate min/max length
    int minLen = 0;
    int maxLen = 0;
    for (BoundedElement elem : elements) {
      minLen += elem.minLength();
      maxLen += elem.maxLength();
    }

    // Length constraints
    if (maxLen > 100) {
      return null; // Too long
    }
    if (minLen == 0) {
      return null; // All optional
    }

    // Reject patterns with capturing groups - this strategy doesn't support group tracking
    if (groupCounter[0] > 0) {
      return null;
    }

    // Reject when an optional element could greedily consume a character required by a later
    // element. Example: c?c — the optional consumes the only 'c', leaving none for the required
    // literal. The generator has no backtracking, so it would incorrectly return no-match.
    for (int i = 0; i < elements.size() - 1; i++) {
      if (!(elements.get(i) instanceof BoundedOptionalElement)) continue;
      char optCh = ((BoundedOptionalElement) elements.get(i)).literal;
      for (int j = i + 1; j < elements.size(); j++) {
        BoundedElement next = elements.get(j);
        if (next instanceof BoundedLiteralElement
            && ((BoundedLiteralElement) next).literal == optCh) {
          return null;
        }
        if (next instanceof BoundedQuantifierElement) {
          BoundedQuantifierElement qe = (BoundedQuantifierElement) next;
          if (qe.min >= 1 && (qe.charset.contains(optCh) != qe.negated)) {
            return null;
          }
        }
      }
    }

    // Reject when a RANGED quantifier (max > min) could greedily overshoot and consume a character
    // required by a following element whose acceptable first-character set overlaps the
    // quantifier's
    // own character set. Example: [ab]{1,2}b on "ab" — the greedy fast path consumes both 'a' and
    // 'b' for the quantifier, leaving nothing for the trailing 'b'; the correct match requires
    // giving the 'b' back. The non-backtracking generator never does this, so it would incorrectly
    // return no-match. Exact counts ({m}) cannot overshoot and are unaffected. Only decline when a
    // real overlap exists, so disjoint shapes like [xy]{1,2}z continue to use the fast path.
    for (int i = 0; i < elements.size() - 1; i++) {
      if (!(elements.get(i) instanceof BoundedQuantifierElement)) continue;
      BoundedQuantifierElement ranged = (BoundedQuantifierElement) elements.get(i);
      if (ranged.max <= ranged.min) continue; // exact count cannot overshoot
      CharSet rangedSet = ranged.negated ? ranged.charset.complement() : ranged.charset;
      // A greedy overshoot of the ranged element can steal characters from any later element up to
      // the point where a mandatory (min-length) element interrupts the overlap. Scan forward and
      // stop once we reach an element whose first chars cannot be supplied by the ranged set.
      for (int j = i + 1; j < elements.size(); j++) {
        CharSet nextFirst = firstCharSet(elements.get(j));
        if (nextFirst != null && rangedSet.intersects(nextFirst)) {
          return null; // overlap requires backtracking the fast path cannot perform
        }
        // Once the following element's first char cannot come from the ranged set, the overshoot
        // can no longer be misattributed past it (it has minLength >= 1 and acts as a barrier).
        if (elements.get(j).minLength() >= 1) {
          break;
        }
      }
    }

    return new BoundedQuantifierInfo(elements, minLen, maxLen, groupCounter[0]);
  }

  /**
   * Compute the set of characters that may legally appear as the first consumed character of a
   * bounded element, for overlap analysis. Returns {@code null} when the set cannot be determined
   * (treated as no usable overlap information).
   */
  private static CharSet firstCharSet(BoundedElement elem) {
    if (elem instanceof BoundedLiteralElement) {
      return CharSet.of(((BoundedLiteralElement) elem).literal);
    }
    if (elem instanceof BoundedOptionalElement) {
      return CharSet.of(((BoundedOptionalElement) elem).literal);
    }
    if (elem instanceof BoundedQuantifierElement) {
      BoundedQuantifierElement qe = (BoundedQuantifierElement) elem;
      return qe.negated ? qe.charset.complement() : qe.charset;
    }
    return null;
  }

  /**
   * Analyze a single element to see if it's a bounded quantifier element. Returns null if not
   * supported or doesn't meet bounded quantifier criteria.
   */
  private BoundedElement analyzeBoundedElement(
      RegexNode node, int currentGroup, int[] groupCounter) {
    // Handle GroupNode wrapper
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      int groupNum = currentGroup;
      if (group.capturing) {
        groupNum = ++groupCounter[0];
      }
      return analyzeBoundedElement(group.child, groupNum, groupCounter);
    }

    // Handle quantified elements
    if (node instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) node;

      // Check for optional literal: X? where X is a literal
      if (quant.min == 0 && quant.max == 1 && quant.child instanceof LiteralNode) {
        LiteralNode lit = (LiteralNode) quant.child;
        return new BoundedOptionalElement(lit.ch);
      }

      // Must be bounded quantifier with reasonable range
      if (quant.min <= 0 || quant.max == -1) {
        return null; // Unbounded or zero-min not supported
      }

      // Range threshold
      if (quant.max - quant.min > 5) {
        return null; // Range too large (would generate too much code)
      }

      // Child must be a character class
      if (!(quant.child instanceof CharClassNode)) {
        return null;
      }

      CharClassNode charClass = (CharClassNode) quant.child;
      return new BoundedQuantifierElement(
          charClass.chars, charClass.negated, quant.min, quant.max, currentGroup);
    }

    // Handle literal characters
    if (node instanceof LiteralNode) {
      LiteralNode lit = (LiteralNode) node;
      // Skip epsilon nodes (empty match marker)
      if (lit.ch == 0) {
        return null;
      }
      return new BoundedLiteralElement(lit.ch);
    }

    // Everything else is not supported
    return null;
  }

  /**
   * Analyze a single element to see if it's a fixed element. Returns null if not fixed or not
   * supported.
   *
   * @param node the AST node to analyze
   * @param currentGroup the current capturing group number (-1 if not in a group)
   * @param groupCounter array with single element tracking the next group number to assign
   */
  private SequenceElement analyzeElement(RegexNode node, int currentGroup, int[] groupCounter) {
    // Handle GroupNode wrapper
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      int groupNum = currentGroup;
      if (group.capturing) {
        // Preserve original group number (set by the parser) when available; a sequential
        // counter would renumber named groups when NAMED_ONLY strips unnamed ones.
        groupNum = group.groupNumber > 0 ? group.groupNumber : ++groupCounter[0];
        groupCounter[0] = Math.max(groupCounter[0], groupNum);
      }
      // Recursively analyze the group's content with the group number
      SequenceElement elem = analyzeElement(group.child, groupNum, groupCounter);
      return elem; // Return whatever the child analysis found
    }

    // Handle LiteralNode
    if (node instanceof LiteralNode) {
      LiteralNode literal = (LiteralNode) node;
      return new LiteralElement(literal.ch, currentGroup);
    }

    // Handle QuantifierNode
    if (node instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) node;

      // Check for optional literal: X?
      if (quant.min == 0 && quant.max == 1 && quant.child instanceof LiteralNode) {
        LiteralNode literal = (LiteralNode) quant.child;
        return new OptionalLiteralElement(literal.ch, currentGroup);
      }

      // Check for fixed repetition: \d{3}, [a-z]{5}
      if (quant.min == quant.max && quant.child instanceof CharClassNode) {
        CharClassNode charClass = (CharClassNode) quant.child;
        return new RepetitionElement(charClass.chars, charClass.negated, quant.min, currentGroup);
      }

      // Not a fixed quantifier
      return null;
    }

    // Unsupported node type
    return null;
  }

  /**
   * Detect multi-group greedy pattern like ([a-z]+)@([a-z]+)\.com or (\d{3})-(\d+)-(\d{4}).
   *
   * <p>Criteria: - Pattern is a sequence of literals and capturing groups - Groups contain only
   * char class with greedy quantifier (* or +) or fixed repetition {n} - No non-greedy quantifiers,
   * nested groups, alternations, backreferences, or lookaround - At least 2 segments (combination
   * of literals and groups) - At least 1 variable-length group
   */
  private MultiGroupGreedyInfo detectMultiGroupGreedyPattern(RegexNode ast) {
    // Reject patterns with unsupported features
    if (hasBackreferences(ast) || hasLookaround(ast)) {
      return null;
    }

    List<Segment> segments = new ArrayList<>();
    int[] groupCounter = {0}; // Mutable counter for group numbers

    // Handle single element (must be a group)
    if (!(ast instanceof ConcatNode)) {
      Segment seg = analyzeSegment(ast, groupCounter);
      if (seg != null && seg instanceof VariableGroupSegment) {
        // Single variable-length group - let GreedyCharClass handle it
        return null;
      }
      return null; // Not a multi-segment pattern
    }

    ConcatNode concat = (ConcatNode) ast;

    // Analyze each child element
    for (RegexNode child : concat.children) {
      Segment seg = analyzeSegment(child, groupCounter);
      if (seg == null) {
        return null; // Contains unsupported element
      }
      segments.add(seg);
    }

    // Must have at least 2 segments
    if (segments.size() < 2) {
      return null;
    }

    // Must have at least 1 variable-length group
    boolean hasVariableGroup = false;
    for (Segment seg : segments) {
      if (seg instanceof VariableGroupSegment) {
        hasVariableGroup = true;
        break;
      }
    }

    if (!hasVariableGroup) {
      return null; // No variable-length groups - let other optimizations handle it
    }

    // Reject when a variable group before other segments cannot guarantee those segments
    // enough input, because the strategy has no inter-group backtracking:
    //  - minMatches==0 (*): greedily consumes everything, may leave too few for next segment
    //  - isAnyChar (. or .*): consumes every character, including literal separators
    // Example: ([^c]*)([^d]{3,4}) on 'NOTccd' — [^c]* takes 'NOT', [^d]{3,4} needs >=3
    // non-d chars but only 'cc' remain. Route to RECURSIVE_DESCENT.
    for (int i = 0; i < segments.size() - 1; i++) {
      Segment seg = segments.get(i);
      if (seg instanceof VariableGroupSegment) {
        VariableGroupSegment varSeg = (VariableGroupSegment) seg;
        if (varSeg.minMatches == 0 || (!varSeg.negated && varSeg.charset.isAnyChar())) {
          return null; // requires inter-group backtracking
        }
      }
    }

    return new MultiGroupGreedyInfo(segments, groupCounter[0]);
  }

  /**
   * Analyze a single element to see if it's a supported segment for multi-group greedy patterns.
   * Returns null if not supported.
   *
   * <p>Supported segments: - Literals (single char or sequence of chars) - Capturing groups with
   * char class + greedy quantifier: ([a-z]+), (\d*) - Capturing groups with char class + fixed
   * repetition: ([a-z]{3}), (\d{4})
   */
  private Segment analyzeSegment(RegexNode node, int[] groupCounter) {
    // Handle literals (single char or string)
    if (node instanceof LiteralNode) {
      LiteralNode lit = (LiteralNode) node;
      return new LiteralSegment(String.valueOf(lit.ch));
    }

    // Handle capturing and non-capturing groups
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;

      // For now, only support capturing groups
      // TODO: Add support for non-capturing groups after handling inline modifiers correctly
      if (!group.capturing) {
        return null;
      }

      int groupNum = ++groupCounter[0];
      RegexNode child = group.child;

      // Check for char class with quantifier
      if (child instanceof QuantifierNode) {
        QuantifierNode quant = (QuantifierNode) child;

        // Must be greedy
        if (!quant.greedy) {
          return null; // Non-greedy not supported
        }

        // Child must be a character class
        if (!(quant.child instanceof CharClassNode)) {
          return null;
        }

        CharClassNode charClass = (CharClassNode) quant.child;

        // Fixed-length: {n} where n == m
        if (quant.min == quant.max && quant.min > 0) {
          return new FixedGroupSegment(charClass.chars, charClass.negated, quant.min, groupNum);
        }

        // Variable-length: *, +, {n,}, or {n,m} where m != n
        // Reject if max is bounded but very large
        if (quant.max != -1 && quant.max > 1000) {
          return null; // Too large to optimize
        }

        // For *, +, or reasonable {n,m}
        int minMatches = quant.min;
        return new VariableGroupSegment(charClass.chars, charClass.negated, minMatches, groupNum);
      }

      // Check for char class with fixed repetition (no quantifier means {1})
      if (child instanceof CharClassNode) {
        CharClassNode charClass = (CharClassNode) child;
        return new FixedGroupSegment(charClass.chars, charClass.negated, 1, groupNum);
      }

      // Check for literal group: (abc), (test), etc.
      if (child instanceof LiteralNode) {
        LiteralNode lit = (LiteralNode) child;
        return new LiteralGroupSegment(String.valueOf(lit.ch), groupNum);
      }

      // Check for concat of literals: (abc) where child is ConcatNode(Literal, Literal, ...)
      if (child instanceof ConcatNode) {
        ConcatNode concat = (ConcatNode) child;
        StringBuilder literalBuilder = new StringBuilder();

        // Check if all children are literals
        for (RegexNode concatChild : concat.children) {
          if (!(concatChild instanceof LiteralNode)) {
            // Not all literals - can't optimize
            return null;
          }
          LiteralNode lit = (LiteralNode) concatChild;
          literalBuilder.append(lit.ch);
        }

        // All literals - create LiteralGroupSegment
        return new LiteralGroupSegment(literalBuilder.toString(), groupNum);
      }

      // Unsupported group content
      return null;
    }

    // Handle alternations - not supported
    if (node instanceof AlternationNode) {
      return null;
    }

    // Handle assertions - not supported
    if (node instanceof AssertionNode) {
      return null;
    }

    // Handle anchors - support START and END
    if (node instanceof AnchorNode) {
      AnchorNode anchor = (AnchorNode) node;
      // Multiline ^ / $ match line boundaries; the MGG generator only models pos==0 and pos==len.
      // Decline both so these patterns are routed to a correct strategy.
      if (anchor.multiline) {
        return null;
      }
      return new AnchorSegment(anchor.type);
    }

    // Everything else is not supported
    return null;
  }

  /**
   * Detect concat+greedy group patterns: prefix + single greedy group + suffix. Examples: a(b*),
   * x(y*)z, foo(bar+)baz
   *
   * <p>Pattern structure: - Optional prefix: zero or more fixed-width elements (literals, char
   * classes) - Single capturing group containing a greedy quantifier - Optional suffix: zero or
   * more fixed-width elements
   *
   * <p>This strategy generates allocation-free bytecode that correctly handles zero-length group
   * captures (e.g., a(b*) matching "a" captures "" at position 1).
   */
  private ConcatGreedyGroupInfo detectConcatGreedyGroup(RegexNode ast) {
    // Reject unsupported features
    if (hasBackreferences(ast) || hasLookaround(ast)) {
      return null;
    }

    List<RegexNode> allNodes = new ArrayList<>();

    // Flatten the AST into a list of nodes
    if (ast instanceof ConcatNode) {
      allNodes.addAll(((ConcatNode) ast).children);
    } else {
      allNodes.add(ast);
    }

    // Find the single capturing group with greedy quantifier
    int groupIndex = -1;
    GroupNode targetGroup = null;
    int groupNumber = 0;
    int currentGroupNum = 0;

    for (int i = 0; i < allNodes.size(); i++) {
      RegexNode node = allNodes.get(i);

      // Count all groups to track numbering
      if (node instanceof GroupNode) {
        GroupNode group = (GroupNode) node;
        if (group.capturing) {
          currentGroupNum++;

          // Check if this is our target: capturing group with greedy quantifier
          if (group.child instanceof QuantifierNode) {
            QuantifierNode quant = (QuantifierNode) group.child;
            if (quant.greedy) {
              // Found a candidate
              if (targetGroup != null) {
                // Multiple greedy groups - not supported
                return null;
              }
              groupIndex = i;
              targetGroup = group;
              groupNumber = currentGroupNum;
            }
          }
        }
      }
    }

    // Must have exactly one greedy capturing group
    if (targetGroup == null) {
      return null;
    }

    // Extract prefix (everything before the group)
    List<RegexNode> prefix = new ArrayList<>();
    for (int i = 0; i < groupIndex; i++) {
      RegexNode node = allNodes.get(i);
      if (!isSimpleFixedWidth(node)) {
        return null; // Prefix must be simple fixed-width elements
      }
      prefix.add(node);
    }

    // Extract suffix (everything after the group)
    List<RegexNode> suffix = new ArrayList<>();
    for (int i = groupIndex + 1; i < allNodes.size(); i++) {
      RegexNode node = allNodes.get(i);
      if (!isSimpleFixedWidth(node)) {
        return null; // Suffix must be simple fixed-width elements
      }
      suffix.add(node);
    }

    // Analyze the quantifier
    QuantifierNode quant = (QuantifierNode) targetGroup.child;
    RegexNode quantifiedNode = quant.child;

    // Quantified element must be simple: literal or char class
    CharSet quantifierCharSet = null;
    String quantifierLiteral = null;

    if (quantifiedNode instanceof CharClassNode) {
      quantifierCharSet = ((CharClassNode) quantifiedNode).chars;
    } else if (quantifiedNode instanceof LiteralNode) {
      quantifierLiteral = String.valueOf(((LiteralNode) quantifiedNode).ch);
    } else {
      return null; // Unsupported quantified element
    }

    // Convert -1 (unbounded) to Integer.MAX_VALUE for consistent handling
    int maxQuantifier = (quant.max == -1) ? Integer.MAX_VALUE : quant.max;

    return new ConcatGreedyGroupInfo(
        prefix,
        groupNumber,
        quantifiedNode,
        quant.min,
        maxQuantifier,
        suffix,
        quantifierCharSet,
        quantifierLiteral);
  }

  /**
   * Flattens {@code node} into zero or more fixed {@link SequenceElement}s appended to {@code out}.
   * Unlike {@link #analyzeElement}, this recurses into {@link ConcatNode} children of {@link
   * GroupNode}s, enabling patterns like {@code (foo)(bar)} to route to {@link
   * MatchingStrategy#SPECIALIZED_FIXED_SEQUENCE}.
   *
   * @return true on success; false if the subtree contains any unsupported construct (out may be
   *     partially modified — callers must discard on failure)
   */
  private boolean flattenElement(
      RegexNode node, int currentGroup, int[] groupCounter, List<SequenceElement> out) {
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      int groupNum = currentGroup;
      if (group.capturing) {
        groupNum = group.groupNumber > 0 ? group.groupNumber : ++groupCounter[0];
        groupCounter[0] = Math.max(groupCounter[0], groupNum);
      }
      return flattenElement(group.child, groupNum, groupCounter, out);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (!flattenElement(child, currentGroup, groupCounter, out)) {
          return false;
        }
      }
      return true;
    }
    SequenceElement elem = analyzeElement(node, currentGroup, groupCounter);
    if (elem == null) {
      return false;
    }
    out.add(elem);
    return true;
  }

  /**
   * Extracts the literal characters from a pure-literal subtree (LiteralNodes and non-capturing
   * GroupNodes / ConcatNodes only). Returns null if {@code node} contains anything else.
   */
  private List<Character> extractLiteralBody(RegexNode node) {
    if (node instanceof LiteralNode) {
      List<Character> r = new ArrayList<>();
      r.add(((LiteralNode) node).ch);
      return r;
    }
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (g.capturing) return null;
      return extractLiteralBody(g.child);
    }
    if (node instanceof ConcatNode) {
      List<Character> r = new ArrayList<>();
      for (RegexNode child : ((ConcatNode) node).children) {
        List<Character> sub = extractLiteralBody(child);
        if (sub == null) return null;
        r.addAll(sub);
      }
      return r;
    }
    return null;
  }

  /** Check if a node is simple fixed-width (literal or char class without quantifier). */
  private boolean isSimpleFixedWidth(RegexNode node) {
    if (node instanceof LiteralNode) {
      return true;
    }
    if (node instanceof CharClassNode) {
      return true;
    }
    // Non-capturing groups with simple content
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      if (!group.capturing && isSimpleFixedWidth(group.child)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Detect greedy backtrack pattern: patterns where greedy .* must "give back" characters.
   *
   * <p>Examples: - (.*)bar - greedy any followed by literal - (.*)(\d+) - greedy any followed by
   * quantified char class - foo(.*)bar - prefix, greedy any, suffix
   *
   * <p>This strategy uses suffix-first matching: find where suffix matches, then group is
   * everything before.
   */
  private QuantifierNode extractSingleQuantifier(RegexNode node) {
    if (node instanceof QuantifierNode) return (QuantifierNode) node;
    if (node instanceof ConcatNode c
        && c.children.size() == 1
        && c.children.get(0) instanceof QuantifierNode) return (QuantifierNode) c.children.get(0);
    return null;
  }

  private boolean hasLargeBoundQuantifier(RegexNode node) {
    return Boolean.TRUE.equals(node.accept(new LargeBoundQuantifierDetector()));
  }

  private static class LargeBoundQuantifierDetector implements RegexVisitor<Boolean> {
    @Override
    public Boolean visitLiteral(LiteralNode node) {
      return false;
    }

    @Override
    public Boolean visitCharClass(CharClassNode node) {
      return false;
    }

    @Override
    public Boolean visitConcat(ConcatNode node) {
      return node.children.stream().anyMatch(child -> child.accept(this));
    }

    @Override
    public Boolean visitAlternation(AlternationNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }

    @Override
    public Boolean visitQuantifier(QuantifierNode node) {
      // max == -1 means unlimited (*, +, {n,}); treat as large to block COUNTING_GLUSHKOV.
      if (node.max == -1 || node.max > 10) return true;
      return node.child.accept(this);
    }

    @Override
    public Boolean visitGroup(GroupNode node) {
      return node.child.accept(this);
    }

    @Override
    public Boolean visitAnchor(AnchorNode node) {
      return false;
    }

    @Override
    public Boolean visitBackreference(BackreferenceNode node) {
      return false;
    }

    @Override
    public Boolean visitAssertion(AssertionNode node) {
      return node.subPattern != null && node.subPattern.accept(this);
    }

    @Override
    public Boolean visitSubroutine(SubroutineNode node) {
      return false;
    }

    @Override
    public Boolean visitConditional(ConditionalNode node) {
      boolean hasThen = node.thenBranch.accept(this);
      boolean hasElse = node.elseBranch != null && node.elseBranch.accept(this);
      return hasThen || hasElse;
    }

    @Override
    public Boolean visitBranchReset(BranchResetNode node) {
      return node.alternatives.stream().anyMatch(alt -> alt.accept(this));
    }
  }

  private CountingGlushkovInfo detectCountingGlushkov() {
    QuantifierNode q = extractSingleQuantifier(ast);
    if (q == null || q.max <= 10) return null;

    RegexNode body = q.child;
    if (hasAnchors(body) || hasLookaround(body)) return null;
    if (hasCapturingGroups(body)) return null;
    if (hasBackreferences(body)) return null;
    if (hasLargeBoundQuantifier(body)) return null;

    NFA bodyNfa = new ThompsonBuilder().build(body, 0);
    GlushkovAutomaton base = GlushkovAutomaton.from(bodyNfa);
    if (base == null) return null;

    if (base.positionCount > 1 && (base.accept & base.initial) != 0) return null;

    return new CountingGlushkovInfo(base, q.min, q.max);
  }

  private GreedyBacktrackInfo detectGreedyBacktrackPattern(RegexNode ast) {
    // Reject unsupported features
    if (hasBackreferences(ast) || hasLookaround(ast)) {
      return null;
    }

    List<RegexNode> allNodes = new ArrayList<>();

    // Flatten the AST into a list of nodes
    if (ast instanceof ConcatNode) {
      allNodes.addAll(((ConcatNode) ast).children);
    } else {
      allNodes.add(ast);
    }

    // Find a capturing group with greedy .* or .+
    int greedyGroupIndex = -1;
    GroupNode greedyGroup = null;
    int greedyGroupNumber = 0;
    int greedyMinCount = 0;
    CharSet greedyCharSet = null;
    int currentGroupNum = 0;

    for (int i = 0; i < allNodes.size(); i++) {
      RegexNode node = allNodes.get(i);

      if (node instanceof GroupNode) {
        GroupNode group = (GroupNode) node;
        if (group.capturing) {
          currentGroupNum++;

          // Check if this is greedy .* or .+ (broad charset)
          if (group.child instanceof QuantifierNode) {
            QuantifierNode quant = (QuantifierNode) group.child;
            if (quant.greedy && quant.child instanceof CharClassNode) {
              CharClassNode cc = (CharClassNode) quant.child;

              // Calculate charset size
              int charSetSize = 0;
              for (CharSet.Range range : cc.chars.getRanges()) {
                charSetSize += range.end - range.start + 1;
              }

              // .* and .+ have charset size > 60000 (most of Unicode)
              // We also accept broad charsets > 1000 chars
              if (charSetSize > 1000) {
                // Check for .* (min=0) or .+ (min=1)
                if ((quant.min == 0 || quant.min == 1)
                    && (quant.max == -1 || quant.max == Integer.MAX_VALUE)) {

                  if (greedyGroup != null) {
                    // Multiple greedy groups - not supported yet
                    return null;
                  }
                  greedyGroupIndex = i;
                  greedyGroup = group;
                  greedyGroupNumber = currentGroupNum;
                  greedyMinCount = quant.min;
                  greedyCharSet = cc.chars;
                }
              }
            }
          }
        }
      }
    }

    // Must have a greedy capturing group
    if (greedyGroup == null) {
      return null;
    }

    // The GREEDY_BACKTRACK suffix-search fast paths (find/findMatch via indexOf/lastIndexOf) only
    // re-validate the greedy run for the two '.' charsets they reason about precisely: CharSet.ANY
    // (DOTALL '.') and CharSet.ANY_EXCEPT_NEWLINE (default '.'). Any other broad charset (e.g. a
    // large negated class) cannot be soundly handled by the indexOf scan, so decline and let it
    // route to a charset-validating strategy.
    if (greedyCharSet != null
        && !greedyCharSet.equals(CharSet.ANY)
        && !greedyCharSet.equals(CharSet.ANY_EXCEPT_NEWLINE)) {
      return null;
    }

    // There must be something after the greedy group (the suffix)
    if (greedyGroupIndex >= allNodes.size() - 1) {
      return null; // No suffix
    }

    // Decline .+ (min>=1) with broad charset when the suffix is a literal (not a capturing group).
    // The GREEDY_BACKTRACK indexOf scan cannot enforce the min=1 lower bound while surrendering
    // chars to a non-group suffix: on inputs with a trailing newline the scan overshoots, matching
    // the newline via the suffix delimiter check instead of stopping at the non-newline boundary of
    // '.' (ANY_EXCEPT_NEWLINE). Capturing-group suffixes use a different scan path unaffected by
    // this.
    boolean suffixStartsWithCapturingGroup =
        allNodes.get(greedyGroupIndex + 1) instanceof GroupNode sg && sg.capturing;
    if (!suffixStartsWithCapturingGroup
        && greedyMinCount > 0
        && greedyCharSet != null
        && (greedyCharSet.equals(CharSet.ANY)
            || greedyCharSet.equals(CharSet.ANY_EXCEPT_NEWLINE))) {
      return null;
    }

    // Extract prefix (everything before the greedy group)
    List<RegexNode> prefix = new ArrayList<>();
    for (int i = 0; i < greedyGroupIndex; i++) {
      RegexNode node = allNodes.get(i);
      if (!isSimpleFixedWidth(node)) {
        return null; // Prefix must be simple
      }
      prefix.add(node);
    }

    // Extract and analyze suffix (everything after the greedy group)
    List<RegexNode> suffixNodes = new ArrayList<>();
    for (int i = greedyGroupIndex + 1; i < allNodes.size(); i++) {
      suffixNodes.add(allNodes.get(i));
    }

    if (suffixNodes.isEmpty()) {
      return null;
    }

    // Analyze suffix type
    GreedyBacktrackInfo.SuffixType suffixType = null;
    String suffixLiteral = null;
    CharSet suffixCharSet = null;
    int suffixMinCount = 0;
    int suffixGroupNumber = -1;
    boolean hasEndAnchor = false;
    int totalGroupCount = currentGroupNum;

    // Check for end anchor
    if (suffixNodes.get(suffixNodes.size() - 1) instanceof AnchorNode) {
      AnchorNode anchor = (AnchorNode) suffixNodes.get(suffixNodes.size() - 1);
      if (anchor.type == AnchorNode.Type.END) {
        hasEndAnchor = true;
        suffixNodes = suffixNodes.subList(0, suffixNodes.size() - 1);
      }
    }

    if (suffixNodes.isEmpty()) {
      suffixType = GreedyBacktrackInfo.SuffixType.ANCHORED;
    } else if (suffixNodes.size() == 1) {
      RegexNode suffixNode = suffixNodes.get(0);

      if (suffixNode instanceof LiteralNode) {
        // Single literal: (.*)X
        suffixType = GreedyBacktrackInfo.SuffixType.LITERAL;
        suffixLiteral = String.valueOf(((LiteralNode) suffixNode).ch);
      } else if (suffixNode instanceof CharClassNode) {
        // Single char class: (.*)[0-9]
        suffixType = GreedyBacktrackInfo.SuffixType.CHAR_CLASS;
        suffixCharSet = ((CharClassNode) suffixNode).chars;
        suffixMinCount = 1;
      } else if (suffixNode instanceof GroupNode) {
        GroupNode suffixGroup = (GroupNode) suffixNode;
        if (suffixGroup.capturing && suffixGroup.child instanceof QuantifierNode) {
          // Capturing group with quantifier: (.*)(\\d+)
          QuantifierNode quant = (QuantifierNode) suffixGroup.child;
          if (quant.greedy && quant.child instanceof CharClassNode) {
            suffixType = GreedyBacktrackInfo.SuffixType.QUANTIFIED_CHAR_CLASS;
            suffixCharSet = ((CharClassNode) quant.child).chars;
            suffixMinCount = quant.min; // 0 for *, 1 for +
            suffixGroupNumber = greedyGroupNumber + 1;
            totalGroupCount = Math.max(totalGroupCount, suffixGroupNumber);
          }
        }
      }
    } else if (suffixNodes.size() == 2) {
      // Check for word boundary + quantified char class: \b(\d+)
      RegexNode first = suffixNodes.get(0);
      RegexNode second = suffixNodes.get(1);

      if (first instanceof AnchorNode
          && ((AnchorNode) first).type == AnchorNode.Type.WORD_BOUNDARY) {
        // Word boundary followed by capturing group with quantifier
        if (second instanceof GroupNode) {
          GroupNode suffixGroup = (GroupNode) second;
          if (suffixGroup.capturing && suffixGroup.child instanceof QuantifierNode) {
            QuantifierNode quant = (QuantifierNode) suffixGroup.child;
            if (quant.greedy && quant.child instanceof CharClassNode) {
              suffixType = GreedyBacktrackInfo.SuffixType.WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS;
              suffixCharSet = ((CharClassNode) quant.child).chars;
              suffixMinCount = quant.min;
              suffixGroupNumber = greedyGroupNumber + 1;
              totalGroupCount = Math.max(totalGroupCount, suffixGroupNumber);
            }
          }
        }
      }

      // If not word boundary pattern, try to build literal string
      if (suffixType == null) {
        StringBuilder sb = new StringBuilder();
        boolean allLiterals = true;
        for (RegexNode node : suffixNodes) {
          if (node instanceof LiteralNode) {
            sb.append(((LiteralNode) node).ch);
          } else {
            allLiterals = false;
            break;
          }
        }
        if (allLiterals) {
          suffixType = GreedyBacktrackInfo.SuffixType.LITERAL;
          suffixLiteral = sb.toString();
        }
      }
    } else {
      // Multiple suffix elements (>2) - build literal string
      StringBuilder sb = new StringBuilder();
      for (RegexNode node : suffixNodes) {
        if (node instanceof LiteralNode) {
          sb.append(((LiteralNode) node).ch);
        } else {
          // Non-literal in suffix - not supported yet
          return null;
        }
      }
      suffixType = GreedyBacktrackInfo.SuffixType.LITERAL;
      suffixLiteral = sb.toString();
    }

    if (suffixType == null) {
      return null; // Couldn't determine suffix type
    }

    return new GreedyBacktrackInfo(
        prefix,
        greedyGroupNumber,
        greedyMinCount,
        greedyCharSet,
        suffixType,
        suffixLiteral,
        suffixCharSet,
        suffixMinCount,
        suffixGroupNumber,
        hasEndAnchor,
        totalGroupCount,
        suffixNodes);
  }

  /**
   * Extract the main QuantifierNode from a pattern, handling anchors and wrappers.
   *
   * <p>Handles patterns like: - (a)+ -> returns the QuantifierNode directly - ^(a)+$ -> extracts
   * QuantifierNode from anchor-wrapped concat - (?:(a)+) -> extracts from non-capturing group
   * wrapper - ^(?:(a)+)$ -> handles both anchors and wrapper
   */
  private QuantifierNode extractQuantifierFromPattern(RegexNode ast) {
    // Case 1: Direct QuantifierNode
    if (ast instanceof QuantifierNode) {
      return (QuantifierNode) ast;
    }

    // Case 2: Non-capturing group wrapper
    if (ast instanceof GroupNode) {
      GroupNode group = (GroupNode) ast;
      if (!group.capturing) {
        return extractQuantifierFromPattern(group.child);
      }
    }

    // Case 3: ConcatNode with anchors
    if (ast instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) ast;
      QuantifierNode foundQuant = null;

      for (RegexNode child : concat.children) {
        if (child instanceof AnchorNode) {
          // Skip START-type anchors — they are handled by requiresStartAnchor and do not
          // affect SPECIALIZED_QUANTIFIED_GROUP semantics.
          // END-type anchors ($, \Z, \z) make the pattern semantically impossible to match
          // at non-end positions; the specialized generator is unaware of them, so bail out.
          AnchorNode anchor = (AnchorNode) child;
          if (anchor.type == AnchorNode.Type.START || anchor.type == AnchorNode.Type.STRING_START) {
            continue;
          }
          return null; // END / STRING_END / STRING_END_ABSOLUTE / multiline anchors
        } else if (child instanceof QuantifierNode) {
          if (foundQuant != null) {
            // Multiple quantifiers - not a simple pattern
            return null;
          }
          foundQuant = (QuantifierNode) child;
        } else if (child instanceof GroupNode && !((GroupNode) child).capturing) {
          // Non-capturing group wrapper
          QuantifierNode extracted = extractQuantifierFromPattern(child);
          if (extracted != null) {
            if (foundQuant != null) {
              return null;
            }
            foundQuant = extracted;
          } else {
            return null;
          }
        } else {
          // Other node type - not a simple pattern
          return null;
        }
      }

      return foundQuant;
    }

    return null;
  }

  /**
   * Detect quantified capturing group pattern for POSIX last-match semantics. Returns
   * QuantifiedGroupInfo if pattern matches, null otherwise.
   *
   * <p>Detectable patterns: - (a)+ - single char literal in quantified group - (a|b)+ - alternation
   * of single chars in quantified group - ([a-z])* - char class in quantified group
   *
   * <p>Requirements: - Root is a quantifier (*, +, {n,m}), or - Root is a concat with anchors and a
   * single quantified group, or - Root has a non-capturing group wrapper - Quantifier child is a
   * capturing group - Group child is simple: literal, char class, or alternation of simple elements
   */
  private QuantifiedGroupInfo detectQuantifiedCapturingGroup(RegexNode ast) {
    // Extract the quantifier node, handling anchors and non-capturing wrappers
    QuantifierNode quant = extractQuantifierFromPattern(ast);
    if (quant == null) {
      return null;
    }

    // Quantifier child must be a capturing group
    if (!(quant.child instanceof GroupNode)) {
      return null;
    }
    GroupNode group = (GroupNode) quant.child;
    if (!group.capturing) {
      return null;
    }

    // Get group number
    int groupNumber = nfa.getGroupCount() > 0 ? 1 : 0; // First capturing group
    if (groupNumber == 0) {
      return null;
    }

    // Analyze group child
    RegexNode groupChild = group.child;
    CharSet charSet = null;
    String literal = null;
    boolean isAlternation = false;
    CharSet[] alternationCharSets = null;
    boolean isNegatedCC = false; // negation flag for direct CharClassNode groups

    if (groupChild instanceof LiteralNode) {
      LiteralNode lit = (LiteralNode) groupChild;
      // Check for epsilon (empty group) - represented as (char)0
      // Empty groups like (){3,5} can't be handled by QuantifiedGroupBytecodeGenerator
      if (lit.ch == 0) {
        return null;
      }
      // (a)+
      literal = String.valueOf(lit.ch);
      charSet = CharSet.of(lit.ch);
    } else if (groupChild instanceof CharClassNode) {
      // ([a-z])+ or ([^b])+
      CharClassNode ccNode = (CharClassNode) groupChild;
      charSet = ccNode.chars;
      isNegatedCC = ccNode.negated;
    } else if (groupChild instanceof AlternationNode) {
      // (a|b)+ or (a+|b)+
      AlternationNode alt = (AlternationNode) groupChild;
      isAlternation = true;
      int numAlts = alt.alternatives.size();
      alternationCharSets = new CharSet[numAlts];
      int[] altMinBounds = new int[numAlts];
      int[] altMaxBounds = new int[numAlts];
      boolean[] altNegated = new boolean[numAlts]; // Track negation per alternative
      boolean hasComplex = false;

      // Check each alternative: simple char/charset OR quantified char/charset
      for (int i = 0; i < numAlts; i++) {
        RegexNode altChild = alt.alternatives.get(i);
        if (altChild instanceof LiteralNode) {
          // Simple literal: a
          alternationCharSets[i] = CharSet.of(((LiteralNode) altChild).ch);
          altMinBounds[i] = 1;
          altMaxBounds[i] = 1;
          altNegated[i] = false;
        } else if (altChild instanceof CharClassNode) {
          // Simple char class: [a-z] or [^a-z]
          CharClassNode ccNode = (CharClassNode) altChild;
          alternationCharSets[i] = ccNode.chars;
          altMinBounds[i] = 1;
          altMaxBounds[i] = 1;
          altNegated[i] = ccNode.negated;
        } else if (altChild instanceof QuantifierNode) {
          // Quantified element: a+, [a-z]+, [^"]+
          QuantifierNode altQuant = (QuantifierNode) altChild;
          if (altQuant.child instanceof LiteralNode) {
            alternationCharSets[i] = CharSet.of(((LiteralNode) altQuant.child).ch);
            altNegated[i] = false;
          } else if (altQuant.child instanceof CharClassNode) {
            CharClassNode ccNode = (CharClassNode) altQuant.child;
            alternationCharSets[i] = ccNode.chars;
            altNegated[i] = ccNode.negated;
          } else {
            // Nested complex structure not supported
            return null;
          }
          altMinBounds[i] = altQuant.min;
          altMaxBounds[i] = (altQuant.max == -1) ? Integer.MAX_VALUE : altQuant.max;
          hasComplex = true;
        } else {
          // Other complex alternative - not supported
          return null;
        }
      }

      // Decline when a multi-branch alternation contains a quantified branch whose match length is
      // not "exactly one character per iteration". The fast path models the whole alternation as a
      // per-character greedy loop over the UNION charset, which silently ignores branch length
      // constraints. Examples it gets wrong: ([b]|.{3}){1,} on "cb" and ([b]|.{3,}){1,} on "cb" —
      // the union charset becomes "any char", so the loop greedily matches both characters [0,2],
      // but the JDK only matches the single 'b' at [1,2] (the .{3}/.{3,} branch needs >=3 chars and
      // cannot fire). A branch is safe only when it consumes exactly one character per step:
      //   - a single-char branch [1,1] (literal or char class), or
      //   - an UNBOUNDED branch whose minimum is <= 1 (a+, a*, [a-z]+) — repeatedly consuming one
      //     character is equivalent to the union greedy loop.
      // Any branch with minimum >= 2 (e.g. a{2,}, .{3,}) or a bounded maximum >= 2 (e.g. .{3},
      // [a-z]{1,3}) imposes a multi-character length the per-char loop cannot honor.
      if (numAlts > 1) {
        for (int i = 0; i < numAlts; i++) {
          boolean singleChar = altMinBounds[i] == 1 && altMaxBounds[i] == 1;
          boolean unboundedSingleStep =
              altMaxBounds[i] == Integer.MAX_VALUE && altMinBounds[i] <= 1;
          if (!singleChar && !unboundedSingleStep) {
            return null; // multi-length branch the per-char loop cannot honor
          }
        }
      }

      // Build combined charset for all alternatives
      charSet = CharSet.empty();
      for (CharSet cs : alternationCharSets) {
        charSet = charSet.union(cs);
      }

      // Convert -1 (unbounded) to Integer.MAX_VALUE
      int maxQuantifier = (quant.max == -1) ? Integer.MAX_VALUE : quant.max;

      return new QuantifiedGroupInfo(
          groupNumber,
          groupChild,
          quant.min,
          maxQuantifier,
          charSet,
          literal,
          isAlternation,
          alternationCharSets,
          hasComplex,
          altMinBounds,
          altMaxBounds,
          alt.alternatives,
          altNegated);
    } else if (groupChild instanceof QuantifierNode) {
      // ([a-z]+)* or ([^"]+)* - nested quantifier pattern
      QuantifierNode innerQuant = (QuantifierNode) groupChild;
      boolean isNegated = false;

      // Only handle nested quantifiers with min >= 1 (like +, {1,}, {2,})
      // Quantifiers with min=0 (like *, ?, {0,}) can match empty, which requires
      // special handling for POSIX last-match semantics that we don't support yet.
      // Let those patterns fall through to DFA which handles them correctly.
      if (innerQuant.min == 0) {
        return null;
      }

      // When the OUTER quantifier is fixed (min==max, e.g. {4}) and the INNER quantifier is
      // unbounded (like .+), the greedy inner loop consumes ALL remaining chars in the first
      // outer iteration, leaving none for iterations 2..N (backtracking is needed).
      // When the outer quantifier is unbounded (e.g. + or *), only one outer iteration fires
      // on the available chars so the greedy inner loop is correct.
      if ((innerQuant.max == -1 || innerQuant.max == Integer.MAX_VALUE) && quant.min == quant.max) {
        return null;
      }

      // Extract charset from inner quantifier's child
      if (innerQuant.child instanceof LiteralNode) {
        charSet = CharSet.of(((LiteralNode) innerQuant.child).ch);
      } else if (innerQuant.child instanceof CharClassNode) {
        CharClassNode ccNode = (CharClassNode) innerQuant.child;
        charSet = ccNode.chars;
        isNegated = ccNode.negated;
      } else {
        // Complex inner structure not supported
        return null;
      }

      int innerMin = innerQuant.min;
      int innerMax = (innerQuant.max == -1) ? Integer.MAX_VALUE : innerQuant.max;
      int maxQuantifier = (quant.max == -1) ? Integer.MAX_VALUE : quant.max;

      return new QuantifiedGroupInfo(
          groupNumber,
          groupChild,
          quant.min,
          maxQuantifier,
          charSet,
          literal,
          false, // isAlternation
          null, // alternationCharSets
          false, // hasComplexAlternation
          null, // alternationMinBounds
          null, // alternationMaxBounds
          null, // alternationNegated
          null, // alternatives
          true, // hasNestedQuantifier
          innerMin, // innerMinQuantifier
          innerMax, // innerMaxQuantifier
          isNegated // isNegatedCharSet
          );
    } else {
      // Unsupported group child type
      return null;
    }

    // Convert -1 (unbounded) to Integer.MAX_VALUE
    int maxQuantifier = (quant.max == -1) ? Integer.MAX_VALUE : quant.max;

    return new QuantifiedGroupInfo(
        groupNumber,
        groupChild,
        quant.min,
        maxQuantifier,
        charSet,
        literal,
        isAlternation,
        alternationCharSets,
        false, // hasComplexAlternation
        null, // alternationMinBounds
        null, // alternationMaxBounds
        null, // alternationNegated
        null, // alternatives
        false, // hasNestedQuantifier
        1, // innerMinQuantifier
        1, // innerMaxQuantifier
        isNegatedCC); // isNegatedCharSet — propagates [^x] negation correctly
  }

  /**
   * Detect concatenated quantified capturing groups.
   *
   * <p>Detects patterns like: (a+)+(b+)+ Where each element is:
   * Quantifier(Group(Quantifier(CharSet)))
   */
  private ConcatQuantifiedGroupsInfo detectConcatQuantifiedGroups(RegexNode ast) {
    // Pattern must be: Concat([Quantifier(Group(...)), Quantifier(Group(...)), ...])
    if (!(ast instanceof ConcatNode)) {
      return null;
    }
    ConcatNode concat = (ConcatNode) ast;

    java.util.List<ConcatQuantifiedGroupsInfo.GroupInfo> groups = new java.util.ArrayList<>();
    int expectedGroupNumber = 1;

    for (RegexNode child : concat.children) {
      // Each child must be: Quantifier(Group(inner))
      if (!(child instanceof QuantifierNode)) {
        return null;
      }
      QuantifierNode outerQuant = (QuantifierNode) child;

      if (!(outerQuant.child instanceof GroupNode)) {
        return null;
      }
      GroupNode group = (GroupNode) outerQuant.child;
      if (!group.capturing) {
        return null;
      }

      // Get group number (must be sequential)
      int groupNumber = expectedGroupNumber++;

      // Analyze group contents: expect Quantifier(Literal or CharClass)
      RegexNode groupChild = group.child;
      CharSet charSet;
      int innerMin, innerMax;

      if (groupChild instanceof QuantifierNode) {
        // (a+)+ case: inner quantifier
        QuantifierNode innerQuant = (QuantifierNode) groupChild;
        innerMin = innerQuant.min;
        innerMax = (innerQuant.max == -1) ? Integer.MAX_VALUE : innerQuant.max;

        if (innerQuant.child instanceof LiteralNode) {
          charSet = CharSet.of(((LiteralNode) innerQuant.child).ch);
        } else if (innerQuant.child instanceof CharClassNode) {
          charSet = ((CharClassNode) innerQuant.child).chars;
        } else {
          return null;
        }
      } else if (groupChild instanceof LiteralNode) {
        // (a)+ case: no inner quantifier, single char per iteration
        charSet = CharSet.of(((LiteralNode) groupChild).ch);
        innerMin = 1;
        innerMax = 1;
      } else if (groupChild instanceof CharClassNode) {
        // ([a-z])+ case
        charSet = ((CharClassNode) groupChild).chars;
        innerMin = 1;
        innerMax = 1;
      } else {
        return null;
      }

      int outerMin = outerQuant.min;
      int outerMax = (outerQuant.max == -1) ? Integer.MAX_VALUE : outerQuant.max;

      groups.add(
          new ConcatQuantifiedGroupsInfo.GroupInfo(
              groupNumber, charSet, outerMin, outerMax, innerMin, innerMax));
    }

    if (groups.isEmpty()) {
      return null;
    }

    return new ConcatQuantifiedGroupsInfo(groups);
  }

  /**
   * Detect stateless pattern that doesn't require state tracking. Returns StatelessPatternInfo if
   * pattern matches, null otherwise.
   *
   * <p>Detectable patterns: 1. Single quantifier: \w+, \d*, [a-z]{5,10} 2. Lookahead + literal:
   * (?=\w+@).*@example.com
   *
   * <p>Requirements: - No backreferences - No complex alternations - Linear structure (sequence or
   * single element)
   */
  private StatelessPatternInfo detectStatelessPattern(RegexNode ast) {
    // Reject patterns with backreferences or complex features
    if (hasBackreferences(ast)) {
      return null;
    }

    // Pattern Type 1: Single quantifier (CharClass or Literal + Quantifier)
    if (ast instanceof QuantifierNode) {
      QuantifierNode quant = (QuantifierNode) ast;

      // Child must be char class or literal
      if (quant.child instanceof CharClassNode) {
        CharClassNode charClass = (CharClassNode) quant.child;
        return new StatelessPatternInfo(charClass.chars, charClass.negated, quant.min, quant.max);
      } else if (quant.child instanceof LiteralNode) {
        LiteralNode lit = (LiteralNode) quant.child;
        return new StatelessPatternInfo(CharSet.of(lit.ch), false, quant.min, quant.max);
      }
    }

    // Pattern Type 2: Lookahead + .* + literal suffix
    // Structure: ConcatNode(AssertionNode, QuantifierNode(.*, 0, -1), LiteralNodes...)
    if (ast instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) ast;
      List<RegexNode> children = concat.children;

      // Need at least 3 elements: lookahead, .*, literal(s)
      if (children.size() < 3) {
        return null;
      }

      // First element must be a lookahead assertion
      if (!(children.get(0) instanceof AssertionNode)) {
        return null;
      }

      AssertionNode assertion = (AssertionNode) children.get(0);
      if (assertion.type != AssertionNode.Type.POSITIVE_LOOKAHEAD) {
        return null; // Only positive lookahead for now
      }

      // Second element must be .* (any char, zero or more)
      if (!(children.get(1) instanceof QuantifierNode)) {
        return null;
      }

      QuantifierNode dotStar = (QuantifierNode) children.get(1);
      if (dotStar.min != 0 || (dotStar.max != -1 && dotStar.max != Integer.MAX_VALUE)) {
        return null; // Must be * (zero or more)
      }

      // Child of .* should be DOT (any char) or a char class matching any char
      if (!(dotStar.child instanceof CharClassNode)) {
        return null;
      }

      // Remaining elements should form a literal suffix
      StringBuilder literalBuilder = new StringBuilder();
      for (int i = 2; i < children.size(); i++) {
        RegexNode node = children.get(i);
        if (node instanceof LiteralNode) {
          literalBuilder.append(((LiteralNode) node).ch);
        } else if (node instanceof CharClassNode) {
          // Handle escaped characters like \. which parse as CharClassNode
          CharClassNode charClass = (CharClassNode) node;
          if (!charClass.negated && charClass.chars.isSingleChar()) {
            literalBuilder.append(charClass.chars.getSingleChar());
          } else {
            return null;
          }
        } else {
          return null; // Non-literal in suffix
        }
      }

      String literalSuffix = literalBuilder.toString();
      if (literalSuffix.length() < 3) {
        return null; // Suffix too short for Boyer-Moore to be effective
      }

      return new StatelessPatternInfo(assertion, literalSuffix);
    }

    return null; // Pattern doesn't match any stateless pattern
  }

  /**
   * Detect pure literal alternation patterns like keyword1|keyword2|...|keywordN. All alternatives
   * must be pure literals (no regex metacharacters).
   *
   * <p>Examples: - foo|bar|baz (valid) - keyword1|keyword2|...|keyword15 (valid) - foo|bar|b.z
   * (invalid - contains regex metacharacter '.') - foo|bar? (invalid - contains quantifier)
   *
   * @return LiteralAlternationInfo if pattern matches, null otherwise
   */
  private LiteralAlternationInfo detectLiteralAlternation(RegexNode ast) {
    // Unwrap single non-capturing group if present
    RegexNode node = ast;
    while (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      if (group.capturing) {
        return null; // Capturing group at root - not a pure literal alternation
      }
      node = group.child;
    }

    // Must be an alternation at root (after unwrapping groups)
    if (!(node instanceof AlternationNode)) {
      return null;
    }

    AlternationNode alt = (AlternationNode) node;

    // Extract literal string from each alternative
    List<String> keywords = new ArrayList<>();
    for (RegexNode alternative : alt.alternatives) {
      String keyword = extractLiteralString(alternative);
      if (keyword == null) {
        return null; // Alternative contains non-literal elements
      }
      if (keyword.isEmpty()) {
        return null; // Empty alternative not supported
      }
      keywords.add(keyword);
    }

    // Performance thresholds
    if (keywords.size() < 5) {
      return null; // Too few alternatives - DFA is likely faster for small alternations
    }
    if (keywords.size() > 1000) {
      return null; // Too many alternatives - DFA might be more efficient
    }

    // Calculate min/max lengths
    int minLen = keywords.stream().mapToInt(String::length).min().orElse(0);
    int maxLen = keywords.stream().mapToInt(String::length).max().orElse(0);

    if (maxLen > 1000) {
      return null; // Keywords too long - risk of bytecode explosion
    }

    // Currently, we don't support capturing groups in literal alternations
    // This could be added later if needed
    int groupCount = 0;

    return new LiteralAlternationInfo(keywords, minLen, maxLen, groupCount);
  }

  /**
   * Extract a literal string from a regex node if it consists only of literal characters. Returns
   * null if the node contains any regex metacharacters or non-literal elements.
   */
  private String extractLiteralString(RegexNode node) {
    // Unwrap non-capturing groups
    while (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      if (group.capturing) {
        return null; // Capturing groups not supported yet
      }
      node = group.child;
    }

    // Single literal character: 'a'
    if (node instanceof LiteralNode) {
      return String.valueOf(((LiteralNode) node).ch);
    }

    // Concatenation of literals: 'foo' = Concat(Literal('f'), Literal('o'), Literal('o'))
    if (node instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) node;
      StringBuilder sb = new StringBuilder();
      for (RegexNode child : concat.children) {
        if (child instanceof LiteralNode) {
          sb.append(((LiteralNode) child).ch);
        } else {
          return null; // Non-literal element found
        }
      }
      return sb.toString();
    }

    // Any other node type is not a pure literal
    return null;
  }

  /**
   * Phase 2: Detect lookahead+greedy+suffix pattern structure. Pattern: (?=...).*<suffix> where
   * suffix is a concrete pattern.
   *
   * <p>For example: (?=\w+@).*@\w+\.\w+ - Lookahead: (?=\w+@) - Greedy: .* - Suffix: @\w+\.\w+
   *
   * @return LookaheadGreedySuffixInfo if pattern matches, null otherwise
   */
  private LookaheadGreedySuffixInfo detectLookaheadGreedySuffix(RegexNode ast) {
    // Pattern must be a concatenation
    if (!(ast instanceof ConcatNode)) {
      return null;
    }

    ConcatNode concat = (ConcatNode) ast;
    if (concat.children.size() < 3) {
      return null; // Need at least: assertion, greedy, suffix
    }

    // First child must be a positive lookahead assertion
    RegexNode first = concat.children.get(0);
    if (!(first instanceof AssertionNode)) {
      return null;
    }

    AssertionNode assertion = (AssertionNode) first;
    if (assertion.type != AssertionNode.Type.POSITIVE_LOOKAHEAD) {
      return null;
    }

    // Second child should be greedy .* (any character, 0+ times)
    RegexNode second = concat.children.get(1);
    if (!(second instanceof QuantifierNode)) {
      return null;
    }

    QuantifierNode quantifier = (QuantifierNode) second;
    // Check if it's .* (min=0, max=unlimited, greedy)
    if (quantifier.min != 0 || quantifier.max != -1 || !quantifier.greedy) {
      return null;
    }

    // Child of quantifier should be CharClassNode with DOT
    if (!(quantifier.child instanceof CharClassNode)) {
      return null;
    }

    CharClassNode charClass = (CharClassNode) quantifier.child;
    if (charClass.negated || !charClass.chars.equals(CharSet.ANY)) {
      return null;
    }

    // Remaining children form the suffix pattern
    java.util.List<RegexNode> suffixChildren = new java.util.ArrayList<>();
    for (int i = 2; i < concat.children.size(); i++) {
      suffixChildren.add(concat.children.get(i));
    }

    // Create suffix node (if multiple children, wrap in ConcatNode)
    RegexNode suffix;
    if (suffixChildren.size() == 1) {
      suffix = suffixChildren.get(0);
    } else {
      suffix = new ConcatNode(suffixChildren);
    }

    return new LookaheadGreedySuffixInfo(assertion, suffix);
  }

  /** Information about a lookahead+greedy+suffix pattern. */
  public static class LookaheadGreedySuffixInfo implements PatternInfo {
    public final AssertionNode lookahead;
    public final RegexNode suffix;
    public final boolean canUseFastPath; // NEW: true if eligible for bitmap-based fast path

    public LookaheadGreedySuffixInfo(AssertionNode lookahead, RegexNode suffix) {
      this(lookahead, suffix, false);
    }

    public LookaheadGreedySuffixInfo(
        AssertionNode lookahead, RegexNode suffix, boolean canUseFastPath) {
      this.lookahead = lookahead;
      this.suffix = suffix;
      this.canUseFastPath = canUseFastPath;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + (canUseFastPath ? 1 : 0);
      return hash;
    }
  }

  /** Carrier for COUNTING_GLUSHKOV strategy — group-free phase. */
  public static final class CountingGlushkovInfo implements PatternInfo {
    public final GlushkovAutomaton base; // Glushkov for the un-repeated body
    public final int counterMin;
    public final int counterMax;

    public CountingGlushkovInfo(GlushkovAutomaton base, int counterMin, int counterMax) {
      this.base = base;
      this.counterMin = counterMin;
      this.counterMax = counterMax;
    }

    @Override
    public int structuralHashCode() {
      int hash = getClass().getName().hashCode();
      hash = 31 * hash + (base != null ? base.positionCount : 0);
      hash = 31 * hash + counterMin;
      hash = 31 * hash + counterMax;
      return hash;
    }
  }

  /**
   * Detect if lookahead+greedy pattern can use specialized fast path optimization. Specifically
   * detects pattern: (?=\w+@).*@\w+\.\w+
   *
   * <p>Returns updated LookaheadGreedySuffixInfo with canUseFastPath=true if eligible.
   */
  private LookaheadGreedySuffixInfo detectFastPathEligibility(LookaheadGreedySuffixInfo info) {
    if (info == null) {
      return null;
    }

    // Check lookahead pattern: must be \w+@ (word chars followed by @)
    // Pattern: (?=\w+@)
    RegexNode lookaheadPattern = info.lookahead.subPattern;
    if (!(lookaheadPattern instanceof ConcatNode)) {
      return info; // Not eligible
    }

    ConcatNode lookaheadConcat = (ConcatNode) lookaheadPattern;
    if (lookaheadConcat.children.size() != 2) {
      return info; // Expected: \w+ and @
    }

    // First: \w+ (greedy word chars)
    RegexNode first = lookaheadConcat.children.get(0);
    if (!isGreedyWordCharQuantifier(first)) {
      return info;
    }

    // Second: @ literal
    RegexNode second = lookaheadConcat.children.get(1);
    if (!(second instanceof LiteralNode && ((LiteralNode) second).ch == '@')) {
      return info;
    }

    // Check suffix: must be @\w+\.\w+ (@ word+ dot word+)
    if (!(info.suffix instanceof ConcatNode)) {
      return info;
    }

    ConcatNode suffixConcat = (ConcatNode) info.suffix;
    if (suffixConcat.children.size() != 4) {
      return info; // Expected: @, \w+, \., \w+
    }

    // Element 0: @ literal
    if (!(suffixConcat.children.get(0) instanceof LiteralNode
        && ((LiteralNode) suffixConcat.children.get(0)).ch == '@')) {
      return info;
    }

    // Element 1: \w+ (greedy word chars)
    if (!isGreedyWordCharQuantifier(suffixConcat.children.get(1))) {
      return info;
    }

    // Element 2: \. (dot literal)
    if (!(suffixConcat.children.get(2) instanceof LiteralNode
        && ((LiteralNode) suffixConcat.children.get(2)).ch == '.')) {
      return info;
    }

    // Element 3: \w+ (greedy word chars)
    if (!isGreedyWordCharQuantifier(suffixConcat.children.get(3))) {
      return info;
    }

    // Pattern matches! Mark as fast-path eligible
    return new LookaheadGreedySuffixInfo(info.lookahead, info.suffix, true);
  }

  /** Check if node is a greedy quantifier over word character class (\w+). */
  private boolean isGreedyWordCharQuantifier(RegexNode node) {
    if (!(node instanceof QuantifierNode)) {
      return false;
    }

    QuantifierNode quant = (QuantifierNode) node;
    if (quant.min != 1 || quant.max != -1 || !quant.greedy) {
      return false; // Must be + (1 or more, greedy)
    }

    if (!(quant.child instanceof CharClassNode)) {
      return false;
    }

    CharClassNode charClass = (CharClassNode) quant.child;
    // Check if it's \w (word character class)
    // For now, do a simple check - it's a character class and not negated
    // TODO: properly check if it matches word chars
    return !charClass.negated && charClass.chars != null;
  }

  /**
   * Extract required literal characters from the pattern. A literal is "required" if it must appear
   * for the pattern to match.
   *
   * <p>Algorithm: - For concatenation: find literals not inside optional quantifiers - For
   * alternation: only include literals present in ALL branches - Ignore literals inside
   * lookahead/lookbehind (don't consume input)
   *
   * @param ast the pattern AST
   * @return set of required literals, or empty set if none found
   */
  public java.util.Set<Character> extractRequiredLiterals(RegexNode ast) {
    RequiredLiteralsExtractor extractor = new RequiredLiteralsExtractor();
    return ast.accept(extractor);
  }

  /**
   * Visitor to extract required literal characters from a pattern. A literal is "required" if it
   * must be matched for the pattern to succeed.
   *
   * <p>Rules: - Literals inside * or ? quantifiers are NOT required - Literals inside
   * lookahead/lookbehind are NOT required (zero-width) - For concatenation: return all required
   * literals from children - For alternation: return only literals present in ALL branches
   * (intersection)
   */
  private static class RequiredLiteralsExtractor implements RegexVisitor<java.util.Set<Character>> {
    @Override
    public java.util.Set<Character> visitLiteral(LiteralNode node) {
      // Single literal is always required
      java.util.Set<Character> result = new java.util.HashSet<>();
      result.add(node.ch);
      return result;
    }

    @Override
    public java.util.Set<Character> visitCharClass(CharClassNode node) {
      // Character class doesn't contribute a specific required literal
      return java.util.Collections.emptySet();
    }

    @Override
    public java.util.Set<Character> visitConcat(ConcatNode node) {
      // For concatenation: collect all required literals from children
      java.util.Set<Character> result = new java.util.HashSet<>();
      for (RegexNode child : node.children) {
        result.addAll(child.accept(this));
      }
      return result;
    }

    @Override
    public java.util.Set<Character> visitAlternation(AlternationNode node) {
      // For alternation: return intersection (literals required in ALL branches)
      if (node.alternatives.isEmpty()) {
        return java.util.Collections.emptySet();
      }

      // Start with literals from first alternative
      java.util.Set<Character> result =
          new java.util.HashSet<>(node.alternatives.get(0).accept(this));

      // Intersect with literals from remaining alternatives
      for (int i = 1; i < node.alternatives.size(); i++) {
        result.retainAll(node.alternatives.get(i).accept(this));
      }

      return result;
    }

    @Override
    public java.util.Set<Character> visitQuantifier(QuantifierNode node) {
      // Quantifiers with min=0 (* or ?) make their content optional (not required)
      if (node.min == 0) {
        return java.util.Collections.emptySet();
      }

      // Quantifiers with min>0 require their content at least once
      return node.child.accept(this);
    }

    @Override
    public java.util.Set<Character> visitGroup(GroupNode node) {
      // Groups don't affect required literals (pass through to child)
      return node.child.accept(this);
    }

    @Override
    public java.util.Set<Character> visitAnchor(AnchorNode node) {
      // Anchors don't contribute literals
      return java.util.Collections.emptySet();
    }

    @Override
    public java.util.Set<Character> visitBackreference(BackreferenceNode node) {
      // Backreferences don't contribute specific literals (dynamic)
      return java.util.Collections.emptySet();
    }

    @Override
    public java.util.Set<Character> visitAssertion(AssertionNode node) {
      // Assertions (lookahead/lookbehind) are zero-width - don't consume input
      // Their literals are NOT required for matching at the current position
      return java.util.Collections.emptySet();
    }

    @Override
    public java.util.Set<Character> visitSubroutine(SubroutineNode node) {
      // Subroutines are dynamic calls - no specific literals known
      return java.util.Collections.emptySet();
    }

    @Override
    public java.util.Set<Character> visitConditional(ConditionalNode node) {
      // Return intersection (literals required in ALL branches)
      java.util.Set<Character> result = new java.util.HashSet<>(node.thenBranch.accept(this));
      if (node.elseBranch != null) {
        result.retainAll(node.elseBranch.accept(this));
      }
      return result;
    }

    @Override
    public java.util.Set<Character> visitBranchReset(BranchResetNode node) {
      // Return intersection (literals required in ALL alternatives)
      if (node.alternatives.isEmpty()) {
        return java.util.Collections.emptySet();
      }
      java.util.Set<Character> result =
          new java.util.HashSet<>(node.alternatives.get(0).accept(this));
      for (int i = 1; i < node.alternatives.size(); i++) {
        result.retainAll(node.alternatives.get(i).accept(this));
      }
      return result;
    }
  }
}
