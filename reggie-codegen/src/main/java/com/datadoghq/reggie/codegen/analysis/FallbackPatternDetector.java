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
import java.util.List;

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

    // Bug 2: lookahead inside a quantified group
    if (v.lookaheadInQuantifier) {
      return "lookahead inside quantified group";
    }
    // Bug 3: lookbehind immediately followed by unbounded quantifier
    if (v.lookbehindBeforeUnbounded) {
      return "lookbehind followed by unbounded quantifier";
    }
    // Bug 4: alternation inside lookbehind
    if (v.alternationInLookbehind) {
      return "alternation inside lookbehind";
    }
    // Bug 5: lookbehind and lookahead used together (sandwich / interaction)
    if (v.hasLookbehind && v.hasLookahead) {
      return "lookbehind and lookahead combined";
    }
    return null;
  }

  private static boolean isLookahead(AssertionNode.Type t) {
    return t == AssertionNode.Type.POSITIVE_LOOKAHEAD || t == AssertionNode.Type.NEGATIVE_LOOKAHEAD;
  }

  private static boolean isLookbehind(AssertionNode.Type t) {
    return t == AssertionNode.Type.POSITIVE_LOOKBEHIND
        || t == AssertionNode.Type.NEGATIVE_LOOKBEHIND;
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

  /** Returns true if {@code node} is or directly contains an AlternationNode. */
  private static boolean containsDirectAlternation(RegexNode node) {
    if (node instanceof AlternationNode) {
      return true;
    }
    if (node instanceof GroupNode) {
      return ((GroupNode) node).child instanceof AlternationNode;
    }
    if (node instanceof ConcatNode) {
      for (RegexNode c : ((ConcatNode) node).children) {
        if (c instanceof AlternationNode) return true;
      }
    }
    return false;
  }

  private static final class Visitor implements RegexVisitor<Void> {
    boolean lookaheadInQuantifier = false;
    boolean lookbehindBeforeUnbounded = false;
    boolean alternationInLookbehind = false;
    boolean hasLookahead = false;
    boolean hasLookbehind = false;

    @Override
    public Void visitAssertion(AssertionNode node) {
      if (isLookahead(node.type)) {
        hasLookahead = true;
      }
      if (isLookbehind(node.type)) {
        hasLookbehind = true;
        if (containsDirectAlternation(node.subPattern)) {
          alternationInLookbehind = true;
        }
      }
      node.subPattern.accept(this);
      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      if (containsLookahead(node.child)) {
        lookaheadInQuantifier = true;
      }
      node.child.accept(this);
      return null;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      List<RegexNode> ch = node.children;
      for (int i = 0; i < ch.size() - 1; i++) {
        if (ch.get(i) instanceof AssertionNode) {
          AssertionNode a = (AssertionNode) ch.get(i);
          // Detects direct adjacency only: (?<=\d)[a-z]+
          // A quantifier wrapped inside a group is not detected here.
          if (isLookbehind(a.type) && ch.get(i + 1) instanceof QuantifierNode) {
            QuantifierNode q = (QuantifierNode) ch.get(i + 1);
            if (q.max == -1 || q.max == Integer.MAX_VALUE) {
              lookbehindBeforeUnbounded = true;
            }
          }
        }
      }
      for (RegexNode c : ch) {
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
