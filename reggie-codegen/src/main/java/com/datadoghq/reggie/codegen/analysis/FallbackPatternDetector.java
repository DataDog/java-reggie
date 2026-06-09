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
import java.util.ArrayList;
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

    // detectVariableCaptureBackref returns null for nullable groups (groupQuantifier.min == 0),
    // routing them to OPTIMIZED_NFA_WITH_BACKREFS. This guard can no longer fire.

    // NESTED_QUANTIFIED_GROUPS bytecode generator falls through to an "accept any char" stub
    // when the innermost quantifier level contains an AlternationNode. Patterns like ((a|b)+)*
    // match characters they should not.
    if (strategy == PatternAnalyzer.MatchingStrategy.NESTED_QUANTIFIED_GROUPS
        && hasAlternationInNestedQuantifierContent(ast)) {
      return "nested quantified groups with alternation in inner content: "
          + "generator uses accept-any-char stub instead of alternation matching";
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

    // DFA generators track the boolean match result but do not track capturing-group span
    // boundaries during execution. When a capturing group appears inside a quantifier, the DFA
    // cannot tell which loop iteration captured what. PatternAnalyzer routes such patterns to
    // PIKEVM_CAPTURE (non-assertion path) or OPTIMIZED_NFA_WITH_LOOKAROUND (assertion path)
    // before DFA strategies are selected, so this fallback condition is no longer needed.

    // OPTIMIZED_NFA with overlapping literal alternatives: the NFA simulation takes the first
    // matching branch (leftmost-first) rather than the longest, diverging from JDK's semantics
    // when one alternative is a literal prefix of another (e.g. fo|foo on 'foo').
    if (strategy == PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA
        && hasAlternationWithPrefixOverlap(ast)) {
      return "alternation with prefix-overlap: leftmost-first ordering diverges from JDK longest-match";
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
   * Returns true if the NESTED_QUANTIFIED_GROUPS pattern has an alternation as the content of any
   * quantifier level. The bytecode generator falls through to an "accept any char" stub for {@link
   * AlternationNode} content, producing wrong matches.
   */
  private static boolean hasAlternationInNestedQuantifierContent(RegexNode ast) {
    return hasAlternationInQuantifierContentHelper(ast, false);
  }

  private static boolean hasAlternationInQuantifierContentHelper(
      RegexNode node, boolean insideQuantifier) {
    if (node instanceof QuantifierNode) {
      QuantifierNode q = (QuantifierNode) node;
      if (insideQuantifier && q.child instanceof GroupNode) {
        RegexNode content = ((GroupNode) q.child).child;
        if (content instanceof AlternationNode) return true;
        return hasAlternationInQuantifierContentHelper(content, true);
      }
      return hasAlternationInQuantifierContentHelper(q.child, true);
    }
    if (node instanceof GroupNode) {
      return hasAlternationInQuantifierContentHelper(((GroupNode) node).child, insideQuantifier);
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (hasAlternationInQuantifierContentHelper(c, insideQuantifier)) return true;
      }
      return false;
    }
    if (node instanceof AlternationNode) {
      for (RegexNode a : ((AlternationNode) node).alternatives) {
        if (hasAlternationInQuantifierContentHelper(a, insideQuantifier)) return true;
      }
      return false;
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
   * Returns true if any {@link AlternationNode} in {@code ast} has two literal-string alternatives
   * where one is a strict prefix of the other (e.g., {@code fo|foo}). The OPTIMIZED_NFA engine
   * takes the first matching branch (leftmost-first), but JDK uses a simulation that can prefer the
   * longer branch, leading to different group-capture positions.
   */
  private static boolean hasAlternationWithPrefixOverlap(RegexNode ast) {
    if (ast instanceof AlternationNode) {
      List<String> lits = new ArrayList<>();
      for (RegexNode alt : ((AlternationNode) ast).alternatives) {
        String s = extractLiteralStringFpd(alt);
        if (s != null) lits.add(s);
      }
      for (int i = 0; i < lits.size(); i++) {
        for (int j = 0; j < lits.size(); j++) {
          if (i != j && lits.get(j).startsWith(lits.get(i)) && !lits.get(j).equals(lits.get(i))) {
            return true;
          }
        }
      }
    }
    if (ast instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) ast).children) {
        if (hasAlternationWithPrefixOverlap(c)) return true;
      }
    }
    if (ast instanceof GroupNode) return hasAlternationWithPrefixOverlap(((GroupNode) ast).child);
    if (ast instanceof QuantifierNode)
      return hasAlternationWithPrefixOverlap(((QuantifierNode) ast).child);
    return false;
  }

  /** Extracts a literal string from an AST node if it consists solely of literal characters. */
  private static String extractLiteralStringFpd(RegexNode node) {
    if (node instanceof LiteralNode) return String.valueOf(((LiteralNode) node).ch);
    if (node instanceof ConcatNode) {
      StringBuilder sb = new StringBuilder();
      for (RegexNode c : ((ConcatNode) node).children) {
        String s = extractLiteralStringFpd(c);
        if (s == null) return null;
        sb.append(s);
      }
      return sb.toString();
    }
    return null;
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
