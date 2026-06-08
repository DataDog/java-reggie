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

    // Anchor inside a quantifier (e.g. ${2}, \z{n}) creates unusual NFA/DFA shapes that the
    // current generators don't handle correctly.
    if (v.hasAnchorInQuantifier) {
      return "anchor inside quantifier: ${n}, \\z{n}, etc.";
    }

    // END-type anchor ($, \Z) immediately before a char consumer within a concat: Reggie's DFA
    // prunes consuming transitions from END-conditioned states, missing the valid "$ then consume
    // final \\n" path that Java regex allows. Route to JDK for correct semantics.
    if (hasEndAnchorBeforeConsumer(ast)) {
      return "end-anchor before consumer: $ or \\Z followed by char-consuming element";
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

    // OptionalGroupBackref generator has a bug in the "group did not participate" path:
    // it treats \N as vacuously satisfied (matching empty) when the optional group was skipped.
    // Java semantics: \N to a non-participating group FAILS.
    // The bug only manifests for (X)? forms where X is non-nullable (the group might not
    // participate at all). The (X|) empty-alt form always captures something, so no bug there.
    if (strategy == PatternAnalyzer.MatchingStrategy.OPTIONAL_GROUP_BACKREF
        && hasNonNullableQuantifiedOptionalGroupWithBackref(ast)) {
      return "optional group backref with non-nullable (X)? form: unmatched group wrongly "
          + "treated as empty";
    }

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
   * Returns true if the pattern has a (X)? optional group (not an (X|) empty-alt group) AND a
   * backref to that group. The (X)? form can result in the group not participating at all; when X
   * is non-nullable, backref \N to the non-participating group should fail per Java semantics, but
   * OptionalGroupBackrefBytecodeGenerator incorrectly treats it as empty.
   */
  private static boolean hasNonNullableQuantifiedOptionalGroupWithBackref(RegexNode ast) {
    Set<Integer> backrefs = new HashSet<>();
    collectBackrefsInSubtree(ast, backrefs);
    if (backrefs.isEmpty()) return false;
    return hasQuantifiedOptionalGroupForBackref(ast, backrefs);
  }

  /**
   * Walk the AST looking for QuantifierNode(min=0,max=1,child=GroupNode(N,...)) where N is in the
   * backref set and the group content is non-nullable.
   */
  private static boolean hasQuantifiedOptionalGroupForBackref(
      RegexNode node, Set<Integer> backrefs) {
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      if (q.min == 0 && q.max == 1 && q.child instanceof GroupNode) {
        GroupNode g = (GroupNode) q.child;
        if (g.capturing && backrefs.contains(g.groupNumber) && !subtreeIsNullable(g.child)) {
          return true;
        }
      }
      return hasQuantifiedOptionalGroupForBackref(q.child, backrefs);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children)
        if (hasQuantifiedOptionalGroupForBackref(c, backrefs)) return true;
    }
    if (node instanceof GroupNode)
      return hasQuantifiedOptionalGroupForBackref(((GroupNode) node).child, backrefs);
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives)
        if (hasQuantifiedOptionalGroupForBackref(a, backrefs)) return true;
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
    // AnchorNode is zero-width (nullable); LiteralNode and CharClassNode are not.
    return node instanceof AnchorNode;
  }

  /**
   * Returns true if the AST contains an END-type anchor ($, \Z, \z) immediately before a
   * char-consuming element within the same ConcatNode. Such patterns rely on `$` matching "before
   * final newline" and then the subsequent element consuming that newline — a semantic that
   * Reggie's DFA cannot express because it prunes consuming transitions from END-conditioned states
   * without tracking whether the end-char is a newline at runtime.
   */
  private static boolean hasEndAnchorBeforeConsumer(RegexNode ast) {
    if (ast instanceof ConcatNode) {
      ConcatNode concat = (ConcatNode) ast;
      for (int i = 0; i < concat.children.size() - 1; i++) {
        RegexNode child = concat.children.get(i);
        if (child instanceof AnchorNode) {
          AnchorNode anchor = (AnchorNode) child;
          if (anchor.type == AnchorNode.Type.END || anchor.type == AnchorNode.Type.STRING_END) {
            // Next sibling is a char consumer — this pattern needs JDK
            return true;
          }
        }
      }
      for (RegexNode c : concat.children) if (hasEndAnchorBeforeConsumer(c)) return true;
    }
    if (ast instanceof GroupNode) return hasEndAnchorBeforeConsumer(((GroupNode) ast).child);
    if (ast instanceof QuantifierNode)
      return hasEndAnchorBeforeConsumer(((QuantifierNode) ast).child);
    if (ast instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) ast).alternatives)
        if (hasEndAnchorBeforeConsumer(a)) return true;
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
    return false;
  }

  private static final class Visitor implements RegexVisitor<Void> {
    boolean lookaheadInQuantifier = false;
    boolean hasLazyQuantifier = false;
    boolean hasAnchorInQuantifier = false;

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
      if (containsAnchor(node.child) && (node.min != 1 || node.max != 1)) {
        // Anchor inside a quantifier (other than {1}): the quantifier tries to repeat a
        // zero-width assertion. This creates unusual NFA/DFA shapes that the current
        // generators don't handle correctly (e.g. ${2}, ${0,3}, \z{2}).
        hasAnchorInQuantifier = true;
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
