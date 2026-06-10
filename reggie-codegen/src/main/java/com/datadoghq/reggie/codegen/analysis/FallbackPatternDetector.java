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

import com.datadoghq.reggie.codegen.ast.AlternationNode;
import com.datadoghq.reggie.codegen.ast.AnchorNode;
import com.datadoghq.reggie.codegen.ast.AssertionNode;
import com.datadoghq.reggie.codegen.ast.BackreferenceNode;
import com.datadoghq.reggie.codegen.ast.BranchResetNode;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.ConcatNode;
import com.datadoghq.reggie.codegen.ast.ConditionalNode;
import com.datadoghq.reggie.codegen.ast.GroupNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.QuantifierNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.ast.RegexVisitor;
import com.datadoghq.reggie.codegen.ast.SubroutineNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects regex patterns that trigger known correctness bugs in the reggie engine. When a bug is
 * detected, callers should fall back to {@code java.util.regex} rather than producing wrong
 * results.
 */
public final class FallbackPatternDetector {

  private FallbackPatternDetector() {}

  /**
   * Returns a human-readable reason if the pattern needs fallback, or {@code null} if reggie can
   * handle it correctly.
   *
   * @param ast the parsed pattern AST
   * @param strategy the strategy selected by {@link PatternAnalyzer}
   */
  public static String needsFallback(RegexNode ast, PatternAnalyzer.MatchingStrategy strategy) {
    Visitor v = new Visitor();
    ast.accept(v);

    // Lookahead inside a quantified group (issue #28): the NFA engine still produces wrong
    // results for assertions evaluated across loop iterations.
    if (v.lookaheadInQuantifier) {
      return "lookahead inside quantified group";
    }

    // Anchor inside a quantifier that is itself inside a capturing group: the generators do not
    // correctly track per-iteration capture boundaries when a zero-width anchor is repeated.
    // Patterns like (${0,3}) produce wrong match spans or false matches.
    if (hasAnchorInQuantifierInCapturingGroup(ast)) {
      return "anchor inside quantifier within capturing group: capture span tracking incorrect";
    }

    // Any anchor (start/end) inside a quantifier with range ≠ {1,1} produces wrong
    // match positions in all DFA/NFA strategies. The capturing-group sub-case is caught
    // by the guard above; this catches all remaining cases.
    if (hasAnchorInQuantifier(ast)) {
      return "anchor inside quantifier: zero-width anchor with quantifier produces incorrect match positions";
    }

    // END/STRING_END anchor ($, \Z) immediately before a non-newline char consumer: while the
    // "$ then consume terminal \\n" path is handled correctly, other combinations (e.g. \\Z[^c])
    // are not modeled by the DFA and produce wrong boolean or span results.
    if (hasEndAnchorBeforeNonNewlineConsumer(ast)) {
      return "end-anchor before non-newline consumer: DFA does not model this path correctly";
    }

    // RECURSIVE_DESCENT uses a greedy-first descent parser with limited backtracking (quantifiers
    // followed by fixed suffixes). It does NOT implement general alternation backtracking: when an
    // alternation's first branch partially matches but the following context fails, the parser
    // cannot retry a different branch. Lazy quantifiers expose this because they interact heavily
    // with alternation (e.g. a|ab matches "ab" requires the engine to try both branches). Until
    // the generator is extended with full continuation-passing backtracking, lazy patterns route
    // to java.util.regex which handles them correctly.
    //
    // OPTIMIZED_NFA_WITH_BACKREFS findMatchFromMethod always returns the LONGEST match (it tries
    // all end positions and keeps the maximum). Lazy quantifiers require the SHORTEST match.
    // Without proper lazy-aware result selection, these patterns produce wrong spans.
    if ((strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT
            || strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS)
        && v.hasLazyQuantifier) {
      return "lazy quantifier: requires shortest-match semantics not supported by this strategy";
    }

    // Thompson NFA group-state contamination (OPTIMIZED_NFA_WITH_BACKREFS) and RECURSIVE_DESCENT
    // backtracking limitations: both fail when a backref \N appears in one alternative of an
    // alternation but group N is defined in a DIFFERENT alternative of the same alternation.
    if ((strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
            || strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT)
        && hasCrossAlternativeBackref(ast)) {
      return "cross-alternative backref: group captured in one branch, used in another";
    }

    // Parallel NFA simulation uses shared group arrays across all active paths. When a backref
    // \N references a group that can capture the empty string (nullable), the greedy path may
    // record a non-zero groupLen while the empty-capture path needs groupLen=0. The shared
    // array records the wrong value, causing the backref check to fail or spuriously succeed.
    if (strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_BACKREFS
        && hasNullableBackrefGroup(ast)) {
      return "backref to nullable group: parallel NFA simulation records wrong capture span";
    }

    // FIXED_REPETITION_BACKREF assumes groupLen > 0 when verifying repeated backrefs; when the
    // referenced group captures the empty string, the loop never advances and produces a wrong
    // result (false positive or false negative).
    if (strategy == PatternAnalyzer.MatchingStrategy.FIXED_REPETITION_BACKREF
        && hasNullableBackrefGroup(ast)) {
      return "backref to nullable group: fixed-repetition backref loop does not handle empty capture";
    }

    // RECURSIVE_DESCENT backref matching fails when a backref to a nullable group appears inside
    // another capturing group: the recursive parser propagates the zero-length capture into nested
    // group boundaries incorrectly, mismatching the expected position. Simple top-level quantified
    // backrefs like \1+ are handled correctly; only nested-inside-group uses are affected.
    if (strategy == PatternAnalyzer.MatchingStrategy.RECURSIVE_DESCENT
        && hasNullableBackrefInsideCapturingGroup(ast)) {
      return "backref to nullable group inside capturing group: "
          + "recursive descent parser mishandles zero-length capture in nested group context";
    }

    // Tagged DFA (DFA_UNROLLED_WITH_GROUPS, DFA_SWITCH_WITH_GROUPS) group-span computation is
    // unreliable when an optional element (quantifier with min=0) at the top-level concat precedes
    // a capturing group. The TDFA priority-ordering cannot correctly resolve whether the group
    // start position belongs to the skipped or matched optional prefix path, producing wrong
    // group-start values (e.g. "-?(-?.{3})." gives g1=[1,3] instead of [0,3]).
    if ((strategy == PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_GROUPS
            || strategy == PatternAnalyzer.MatchingStrategy.DFA_SWITCH_WITH_GROUPS)
        && hasOptionalPrefixBeforeCapturingGroup(ast)) {
      return "optional prefix before capturing group: "
          + "tagged DFA group-span computation produces wrong group-start position";
    }

    // OPTIMIZED_NFA_WITH_LOOKAROUND NFA simulation produces wrong results when a lookahead
    // assertion appears inside an alternation branch. The NFA thread scheduler does not correctly
    // isolate assertion evaluation per branch.
    if (strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND
        && hasLookaheadInAlternation(ast)) {
      return "lookahead inside alternation branch: "
          + "NFA thread scheduler does not correctly isolate assertions per branch";
    }

    // Generator now caps the initial groupEnd to info.groupMaxCount when the group has a bounded
    // quantifier, so this fallback condition is no longer needed.

    // Generator handles LiteralNode and CharClassNode prefix nodes via emitPrefixMatch.
    // Still fall back for patterns with complex prefix nodes the generator cannot handle.
    if (strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF
        && hasNonAnchorPrefixBeforeBackrefGroup(ast)) {
      return "variable-capture backref with unsupported prefix node type: "
          + "generator only handles literal and char-class prefix nodes";
    }

    // Outer quantifier wraps the entire capturing group: (X)+\N or (X){n,}\N. The
    // backref engine cannot determine the correct last-iteration capture. Routes to JDK.
    if (strategy == PatternAnalyzer.MatchingStrategy.VARIABLE_CAPTURE_BACKREF
        && hasOuterQuantifierOnBackrefGroup(ast)) {
      return "quantified capturing group with backref: "
          + "outer quantifier on group not supported by backref engine";
    }

    // DFA generators track the boolean match result but do not track capturing-group span
    // boundaries during execution. When a capturing group appears inside a quantifier, the DFA
    // cannot tell which loop iteration captured what. PatternAnalyzer routes such patterns to
    // PIKEVM_CAPTURE (non-assertion path) or OPTIMIZED_NFA_WITH_LOOKAROUND (assertion path)
    // before DFA strategies are selected, so this fallback condition is no longer needed.

    // OPTIMIZED_NFA prefix-overlap alternation (e.g. fo|foo): PatternAnalyzer sets
    // alternationPriorityConflict=true for all patterns where the DFA has an accepting state
    // with outgoing transitions, and RuntimeCompiler falls back to JDK before this method is
    // called. This guard is unreachable in practice and has been removed. The actual fix
    // (routing to OPTIMIZED_NFA instead of JDK when alternation priority is not a correctness
    // concern) is deferred.

    return null;
  }

  /**
   * Returns true if any AlternationNode in {@code ast} has a backref \N in one alternative where
   * group N's capturing paren is in a DIFFERENT alternative of that same alternation.
   */
  private static boolean hasCrossAlternativeBackref(RegexNode ast) {
    if (ast instanceof AlternationNode) {
      AlternationNode alt = (AlternationNode) ast;
      List<RegexNode> alts = alt.alternatives;
      @SuppressWarnings("unchecked")
      Set<Integer>[] groups = new Set[alts.size()];
      @SuppressWarnings("unchecked")
      Set<Integer>[] backrefs = new Set[alts.size()];
      Set<Integer> allGroupsInAlt = new HashSet<>();
      for (int i = 0; i < alts.size(); i++) {
        groups[i] = new HashSet<>();
        backrefs[i] = new HashSet<>();
        collectGroupsInSubtree(alts.get(i), groups[i]);
        collectBackrefsInSubtree(alts.get(i), backrefs[i]);
        allGroupsInAlt.addAll(groups[i]);
      }
      for (int i = 0; i < alts.size(); i++) {
        for (int groupNum : backrefs[i]) {
          if (!groups[i].contains(groupNum) && allGroupsInAlt.contains(groupNum)) {
            return true;
          }
        }
      }
      for (RegexNode alternative : alts) {
        if (hasCrossAlternativeBackref(alternative)) return true;
      }
      return false;
    }
    if (ast instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) ast).children) {
        if (hasCrossAlternativeBackref(child)) return true;
      }
    }
    if (ast instanceof GroupNode) {
      return hasCrossAlternativeBackref(((GroupNode) ast).child);
    }
    if (ast instanceof QuantifierNode) {
      return hasCrossAlternativeBackref(((QuantifierNode) ast).child);
    }
    return false;
  }

  private static void collectGroupsInSubtree(RegexNode node, Set<Integer> groups) {
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (g.capturing) groups.add(g.groupNumber);
      collectGroupsInSubtree(g.child, groups);
    } else if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) collectGroupsInSubtree(c, groups);
    } else if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) collectGroupsInSubtree(a, groups);
    } else if (node instanceof QuantifierNode) {
      collectGroupsInSubtree(((QuantifierNode) node).child, groups);
    }
  }

  private static void collectBackrefsInSubtree(RegexNode node, Set<Integer> backrefs) {
    if (node instanceof BackreferenceNode) {
      backrefs.add(((BackreferenceNode) node).groupNumber);
    } else if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) collectBackrefsInSubtree(c, backrefs);
    } else if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives)
        collectBackrefsInSubtree(a, backrefs);
    } else if (node instanceof GroupNode) {
      collectBackrefsInSubtree(((GroupNode) node).child, backrefs);
    } else if (node instanceof QuantifierNode) {
      collectBackrefsInSubtree(((QuantifierNode) node).child, backrefs);
    }
  }

  /**
   * Returns true if any capturing group in the AST contains a backref \N that references a group
   * whose content is nullable (can match the empty string). The RECURSIVE_DESCENT engine's backref
   * matching correctly handles simple top-level quantified backrefs like {@code \1+}, but fails
   * when the zero-length capture propagates through a nested group boundary.
   */
  private static boolean hasNullableBackrefInsideCapturingGroup(RegexNode ast) {
    Set<Integer> backrefNums = new HashSet<>();
    collectBackrefsInSubtree(ast, backrefNums);
    if (backrefNums.isEmpty()) return false;
    // Check if any capturing group body contains a backref to a nullable group.
    return captGroupContainsNullableBackref(ast, backrefNums, ast);
  }

  private static boolean captGroupContainsNullableBackref(
      RegexNode node, Set<Integer> backrefNums, RegexNode fullAst) {
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (g.capturing) {
        // If this group's body contains a backref to a nullable group, report.
        Set<Integer> innerRefs = new HashSet<>();
        collectBackrefsInSubtree(g.child, innerRefs);
        for (int ref : innerRefs) {
          if (backrefNums.contains(ref) && isGroupNullable(fullAst, ref)) return true;
        }
      }
      return captGroupContainsNullableBackref(g.child, backrefNums, fullAst);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (captGroupContainsNullableBackref(c, backrefNums, fullAst)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (captGroupContainsNullableBackref(a, backrefNums, fullAst)) return true;
      }
      return false;
    }
    if (node instanceof QuantifierNode) {
      return captGroupContainsNullableBackref(((QuantifierNode) node).child, backrefNums, fullAst);
    }
    return false;
  }

  /**
   * Returns true if any backref \N references a group whose content is nullable (can match the
   * empty string). In parallel NFA simulation, when such a group exists, the shared group-capture
   * arrays may be overwritten by the greedy (non-empty) path before the empty-capture path's
   * backref check runs, causing the check to use the wrong capture span.
   */
  private static boolean hasNullableBackrefGroup(RegexNode ast) {
    Set<Integer> backrefNums = new HashSet<>();
    collectBackrefsInSubtree(ast, backrefNums);
    if (backrefNums.isEmpty()) return false;
    for (int groupNum : backrefNums) {
      if (isGroupNullable(ast, groupNum)) return true;
    }
    return false;
  }

  /** Walk the AST to find the capturing group with the given number and test nullability. */
  private static boolean isGroupNullable(RegexNode node, int groupNum) {
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (g.capturing && g.groupNumber == groupNum) {
        return subtreeIsNullable(g.child);
      }
      return isGroupNullable(g.child, groupNum);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (isGroupNullable(c, groupNum)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (isGroupNullable(a, groupNum)) return true;
      }
      return false;
    }
    if (node instanceof QuantifierNode) {
      return isGroupNullable(((QuantifierNode) node).child, groupNum);
    }
    return false;
  }

  /** Returns true if the subtree can match the empty string (zero characters). */
  private static boolean subtreeIsNullable(RegexNode node) {
    if (node instanceof QuantifierNode) {
      return ((QuantifierNode) node).min == 0 || subtreeIsNullable(((QuantifierNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (!subtreeIsNullable(c)) return false;
      }
      return true;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (subtreeIsNullable(a)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode) {
      return subtreeIsNullable(((GroupNode) node).child);
    }
    // Epsilon literal (ch==0) is the empty-string node produced by the parser for patterns like
    // "(.|)" where the second alternative is empty. AnchorNode is zero-width (nullable).
    // CharClassNode and non-epsilon LiteralNode consume at least one character.
    if (node instanceof LiteralNode) {
      return ((LiteralNode) node).ch == 0;
    }
    return node instanceof AnchorNode;
  }

  /**
   * Returns true if any anchor node appears inside a quantifier (with range != {1}) that is itself
   * inside a capturing group. The generators do not correctly track per-iteration capture
   * boundaries when a zero-width anchor is repeated inside a group.
   */
  private static boolean hasAnchorInQuantifierInCapturingGroup(RegexNode ast) {
    return hasAnchorInQuantifierInCapturingGroupHelper(ast, false);
  }

  private static boolean hasAnchorInQuantifierInCapturingGroupHelper(
      RegexNode node, boolean inCapturing) {
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      if (inCapturing && (q.min != 1 || q.max != 1) && containsAnchor(q.child)) return true;
      return hasAnchorInQuantifierInCapturingGroupHelper(q.child, inCapturing);
    }
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      return hasAnchorInQuantifierInCapturingGroupHelper(g.child, inCapturing || g.capturing);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (hasAnchorInQuantifierInCapturingGroupHelper(c, inCapturing)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (hasAnchorInQuantifierInCapturingGroupHelper(a, inCapturing)) return true;
      }
      return false;
    }
    return false;
  }

  /**
   * Returns true if any AnchorNode appears as the direct or indirect child of a QuantifierNode
   * whose range is not exactly {1,1}. Catches patterns like \A{0,3}, (?:c*^{0,2}), ${3} where a
   * zero-width anchor is given a quantifier.
   */
  private static boolean hasAnchorInQuantifier(RegexNode ast) {
    if (ast instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) ast;
      if ((q.min != 1 || q.max != 1) && containsAnchor(q.child)) return true;
      return hasAnchorInQuantifier(q.child);
    }
    if (ast instanceof GroupNode) return hasAnchorInQuantifier(((GroupNode) ast).child);
    if (ast instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) ast).children) if (hasAnchorInQuantifier(c)) return true;
    }
    if (ast instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) ast).alternatives)
        if (hasAnchorInQuantifier(a)) return true;
    }
    return false;
  }

  private static boolean containsAnchor(RegexNode node) {
    if (node instanceof AnchorNode) return true;
    if (node instanceof GroupNode) return containsAnchor(((GroupNode) node).child);
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (containsAnchor(c)) return true;
      }
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (containsAnchor(alt)) return true;
      }
    }
    if (node instanceof QuantifierNode) return containsAnchor(((QuantifierNode) node).child);
    return false;
  }

  /**
   * Returns true if the AST contains an END-type anchor ($, \Z) immediately before a char-consuming
   * element within the same ConcatNode, where the consuming element can match characters other than
   * {@code \n}. The "$ before terminal \n" path is handled correctly by the DFA (4B fix), but
   * patterns like {@code \Z[^c]} produce wrong results.
   */
  private static boolean hasEndAnchorBeforeNonNewlineConsumer(RegexNode ast) {
    if (ast instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) ast;
      for (int i = 0; i < concat.children.size() - 1; i++) {
        RegexNode child = concat.children.get(i);
        if (child instanceof AnchorNode) {
          AnchorNode anchor = (AnchorNode) child;
          if (anchor.type == AnchorNode.Type.END || anchor.type == AnchorNode.Type.STRING_END) {
            RegexNode next = concat.children.get(i + 1);
            if (!isNewlineOnlyConsumer(next)) return true;
          }
        }
      }
      for (RegexNode c : concat.children) if (hasEndAnchorBeforeNonNewlineConsumer(c)) return true;
    }
    if (ast instanceof GroupNode)
      return hasEndAnchorBeforeNonNewlineConsumer(((GroupNode) ast).child);
    if (ast instanceof QuantifierNode)
      return hasEndAnchorBeforeNonNewlineConsumer(((QuantifierNode) ast).child);
    if (ast instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) ast).alternatives)
        if (hasEndAnchorBeforeNonNewlineConsumer(a)) return true;
    }
    return false;
  }

  /**
   * Returns true if {@code node} can ONLY match the single character {@code \n} (the terminal
   * newline path handled by the 4B DFA fix).
   */
  private static boolean isNewlineOnlyConsumer(RegexNode node) {
    if (node instanceof LiteralNode) return ((LiteralNode) node).ch == '\n';
    if (node instanceof CharClassNode) {
      CharClassNode cc = (CharClassNode) node;
      return cc.chars.isSingleChar() && cc.chars.getSingleChar() == '\n';
    }
    return false;
  }

  /**
   * Returns true if any ConcatNode in the AST has an optional node (quantifier with min=0) that
   * appears before a capturing group at the same concat level. The TDFA priority-ordering
   * computation cannot resolve the group-start position correctly when an optional element may or
   * may not be consumed before the group opens.
   */
  private static boolean hasOptionalPrefixBeforeCapturingGroup(RegexNode ast) {
    if (ast instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) ast;
      boolean seenOptional = false;
      for (RegexNode child : concat.children) {
        if (child instanceof QuantifierNode && ((QuantifierNode) child).min == 0) {
          seenOptional = true;
        } else if (seenOptional && containsCapturingGroup(child)) {
          return true;
        }
      }
      // Recurse into children
      for (RegexNode child : concat.children) {
        if (hasOptionalPrefixBeforeCapturingGroup(child)) return true;
      }
      return false;
    }
    if (ast instanceof GroupNode) {
      return hasOptionalPrefixBeforeCapturingGroup(((GroupNode) ast).child);
    }
    if (ast instanceof QuantifierNode) {
      return hasOptionalPrefixBeforeCapturingGroup(((QuantifierNode) ast).child);
    }
    if (ast instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) ast).alternatives) {
        if (hasOptionalPrefixBeforeCapturingGroup(a)) return true;
      }
      return false;
    }
    return false;
  }

  /** Returns true if the subtree contains at least one capturing GroupNode. */
  private static boolean containsCapturingGroup(RegexNode node) {
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (g.capturing) return true;
      return containsCapturingGroup(g.child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (containsCapturingGroup(c)) return true;
      }
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (containsCapturingGroup(a)) return true;
      }
    }
    if (node instanceof QuantifierNode) {
      return containsCapturingGroup(((QuantifierNode) node).child);
    }
    return false;
  }

  private static boolean isLookahead(AssertionNode.Type t) {
    return t == AssertionNode.Type.POSITIVE_LOOKAHEAD || t == AssertionNode.Type.NEGATIVE_LOOKAHEAD;
  }

  /** Returns true if {@code node} is or recursively contains a lookahead AssertionNode. */
  private static boolean containsLookahead(RegexNode node) {
    if (node instanceof AssertionNode) {
      return isLookahead(((AssertionNode) node).type);
    }
    if (node instanceof GroupNode) {
      return containsLookahead(((GroupNode) node).child);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (containsLookahead(c)) return true;
      }
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (containsLookahead(alt)) return true;
      }
    }
    return false;
  }

  /**
   * Returns true if any lookahead assertion appears inside an {@link AlternationNode} alternative.
   * The OPTIMIZED_NFA engine's thread scheduler does not correctly isolate assertion evaluation per
   * branch when assertions are embedded in alternation alternatives.
   */
  private static boolean hasLookaheadInAlternation(RegexNode ast) {
    return hasLookaheadInAlternationHelper(ast, false);
  }

  private static boolean hasLookaheadInAlternationHelper(RegexNode node, boolean insideAlt) {
    if (node instanceof AssertionNode) {
      AssertionNode a = (AssertionNode) node;
      if (insideAlt && isLookahead(a.type)) return true;
      return hasLookaheadInAlternationHelper(a.subPattern, insideAlt);
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (hasLookaheadInAlternationHelper(alt, true)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode) {
      return hasLookaheadInAlternationHelper(((GroupNode) node).child, insideAlt);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (hasLookaheadInAlternationHelper(c, insideAlt)) return true;
      }
      return false;
    }
    if (node instanceof QuantifierNode) {
      return hasLookaheadInAlternationHelper(((QuantifierNode) node).child, insideAlt);
    }
    return false;
  }

  /**
   * Returns true if the VARIABLE_CAPTURE_BACKREF pattern has a prefix node type that the bytecode
   * generator cannot handle (QuantifierNode, non-capturing GroupNode, or unknown node type).
   * LiteralNode and CharClassNode prefix nodes are now handled by emitPrefixMatch.
   */
  private static boolean hasNonAnchorPrefixBeforeBackrefGroup(RegexNode ast) {
    Set<Integer> backrefNums = new HashSet<>();
    collectBackrefsInSubtree(ast, backrefNums);
    if (backrefNums.isEmpty()) return false;
    if (!(ast instanceof ConcatNode)) return false;
    ConcatNode concat = (ConcatNode) ast;
    for (RegexNode child : concat.children) {
      if (child instanceof AnchorNode) {
        continue;
      }
      if (child instanceof GroupNode) {
        GroupNode g = (GroupNode) child;
        if (g.capturing && backrefNums.contains(g.groupNumber)) return false;
        return true; // non-capturing group in prefix: not handled
      }
      if (child instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) child;
        if (q.child instanceof GroupNode) {
          GroupNode g = (GroupNode) q.child;
          if (g.capturing && backrefNums.contains(g.groupNumber)) return false;
        }
        return true; // quantified node in prefix: not handled
      }
      if (child instanceof LiteralNode || child instanceof CharClassNode) {
        continue; // handled by emitPrefixMatch
      }
      return true; // unknown prefix node type
    }
    return false;
  }

  /**
   * Returns true if any capturing group referenced by a backref has the entire GroupNode wrapped by
   * an outer quantifier at the concat level — i.e., the AST has {@code QuantifierNode(GroupNode(N,
   * ...))} rather than {@code GroupNode(N, QuantifierNode(...))}. The backref engine cannot
   * correctly determine the last-captured value when the group itself is quantified externally.
   * Example: {@code (c)+\1} vs {@code (c+)\1}.
   */
  private static boolean hasOuterQuantifierOnBackrefGroup(RegexNode ast) {
    Set<Integer> backrefNums = new HashSet<>();
    collectBackrefsInSubtree(ast, backrefNums);
    if (backrefNums.isEmpty()) return false;
    return hasQuantifiedGroupWithBackref(ast, backrefNums);
  }

  private static boolean hasQuantifiedGroupWithBackref(RegexNode node, Set<Integer> backrefNums) {
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      if (q.child instanceof GroupNode) {
        GroupNode g = (GroupNode) q.child;
        if (g.capturing && backrefNums.contains(g.groupNumber)) return true;
      }
      return hasQuantifiedGroupWithBackref(q.child, backrefNums);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children)
        if (hasQuantifiedGroupWithBackref(c, backrefNums)) return true;
    }
    if (node instanceof GroupNode)
      return hasQuantifiedGroupWithBackref(((GroupNode) node).child, backrefNums);
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives)
        if (hasQuantifiedGroupWithBackref(a, backrefNums)) return true;
    }
    return false;
  }

  /**
   * Returns true if any capturing group in {@code ast} is nested inside a quantifier whose range
   * allows more than one repetition (min != max, or max > 1, or max == -1/MAX_VALUE meaning
   * unbounded). DFA generators cannot track per-iteration capture boundaries for such groups.
   */
  static boolean hasCapturingGroupInQuantifiedSection(RegexNode ast) {
    return hasCapturingGroupInQuantifiedHelper(ast, false);
  }

  private static boolean hasCapturingGroupInQuantifiedHelper(RegexNode node, boolean inRepeat) {
    if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (g.capturing && inRepeat) return true;
      return hasCapturingGroupInQuantifiedHelper(g.child, inRepeat);
    }
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      boolean repeats = (q.max != 1) || (q.min != q.max);
      return hasCapturingGroupInQuantifiedHelper(q.child, inRepeat || repeats);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (hasCapturingGroupInQuantifiedHelper(c, inRepeat)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (hasCapturingGroupInQuantifiedHelper(a, inRepeat)) return true;
      }
      return false;
    }
    return false;
  }

  /**
   * Returns true if {@code node} can match the empty string (zero-width match possible). Used to
   * distinguish lazy quantifiers whose child is always consuming (safe to run natively) from those
   * whose child can produce a zero-width match (broken in the greedy pre-scan path).
   */
  static boolean isNullable(RegexNode node) {
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      return q.min == 0 || isNullable(q.child);
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (isNullable(alt)) return true;
      }
      return false;
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (!isNullable(child)) return false;
      }
      return true;
    }
    if (node instanceof GroupNode) {
      return isNullable(((GroupNode) node).child);
    }
    if (node instanceof LiteralNode) {
      return ((LiteralNode) node).ch == 0; // epsilon (empty string literal)
    }
    if (node instanceof AssertionNode || node instanceof AnchorNode) {
      return true; // zero-width
    }
    return false; // CharClassNode, BackreferenceNode, etc.
  }

  private static boolean containsLazyQuantifier(RegexNode node) {
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      return !q.greedy || containsLazyQuantifier(q.child);
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (containsLazyQuantifier(alt)) return true;
      }
      return false;
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (containsLazyQuantifier(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode) {
      return containsLazyQuantifier(((GroupNode) node).child);
    }
    return false;
  }

  private static boolean containsBackreference(RegexNode node) {
    if (node instanceof BackreferenceNode) return true;
    if (node instanceof QuantifierNode) {
      return containsBackreference(((QuantifierNode) node).child);
    }
    if (node instanceof AlternationNode) {
      for (RegexNode alt : ((AlternationNode) node).alternatives) {
        if (containsBackreference(alt)) return true;
      }
      return false;
    }
    if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        if (containsBackreference(child)) return true;
      }
      return false;
    }
    if (node instanceof GroupNode) {
      return containsBackreference(((GroupNode) node).child);
    }
    return false;
  }

  private static final class Visitor implements RegexVisitor<Void> {
    boolean lookaheadInQuantifier = false;
    boolean hasLazyQuantifier = false;

    @Override
    public Void visitAssertion(AssertionNode node) {
      node.subPattern.accept(this);
      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      if (containsLookahead(node.child)) {
        lookaheadInQuantifier = true;
      }
      if (!node.greedy) {
        hasLazyQuantifier = true;
      }
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      for (RegexNode c : node.children) {
        c.accept(this);
      }
      return null;
    }

    @Override
    public Void visitAlternation(AlternationNode node) {
      for (RegexNode alt : node.alternatives) {
        alt.accept(this);
      }
      return null;
    }

    @Override
    public Void visitGroup(GroupNode node) {
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitBackreference(BackreferenceNode node) {
      return null;
    }

    @Override
    public Void visitConditional(ConditionalNode node) {
      node.thenBranch.accept(this);
      if (node.elseBranch != null) {
        node.elseBranch.accept(this);
      }
      return null;
    }

    @Override
    public Void visitBranchReset(BranchResetNode node) {
      for (RegexNode alt : node.alternatives) {
        alt.accept(this);
      }
      return null;
    }

    @Override
    public Void visitLiteral(LiteralNode node) {
      return null;
    }

    @Override
    public Void visitCharClass(CharClassNode node) {
      return null;
    }

    @Override
    public Void visitAnchor(AnchorNode node) {
      return null;
    }

    @Override
    public Void visitSubroutine(SubroutineNode node) {
      return null;
    }
  }
}
