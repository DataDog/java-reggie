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

/**
 * Detects if a regex pattern has a linear (non-branching) execution path.
 *
 * <p>A pattern is considered linear if it has no alternation and no ambiguous execution paths.
 * Linear patterns can be compiled to direct execution code without NFA simulation overhead.
 *
 * <p>Examples of linear patterns:
 *
 * <ul>
 *   <li>{@code (\w+)\s+\1} - duplicate word
 *   <li>{@code (a+)b\1} - simple backreference
 *   <li>{@code ([A-Z][a-z]+)\s+\1} - capitalized duplicate
 * </ul>
 *
 * <p>Examples of non-linear patterns:
 *
 * <ul>
 *   <li>{@code (a|b)\1} - alternation creates branching
 *   <li>{@code (a+|b+)\1} - alternation in group
 *   <li>{@code (\w+)(\s+|\t+)\1} - alternation in middle
 * </ul>
 */
public class LinearPatternDetector implements RegexVisitor<Boolean> {

  /**
   * Returns true if the pattern has a linear (non-branching) execution path.
   *
   * <p>Linear patterns have no alternation ({@code |}) and no ambiguous quantifiers. They can be
   * compiled to direct execution code similar to JDK's node-based approach.
   *
   * @param ast the regex AST to analyze
   * @return true if the pattern is linear (no branching), false otherwise
   */
  public static boolean isLinear(RegexNode ast) {
    if (ast == null) {
      return false;
    }

    LinearPatternDetector detector = new LinearPatternDetector();
    Boolean hasBranching = ast.accept(detector);

    // Visitor returns true if branching found, so invert for "isLinear"
    return !hasBranching;
  }

  /** Private constructor - use static {@link #isLinear(RegexNode)} method. */
  private LinearPatternDetector() {}

  /** Visitor pattern: each visit method returns true if the subtree contains branching. */
  @Override
  public Boolean visitLiteral(LiteralNode node) {
    // Literals are always linear (no branching)
    return false;
  }

  @Override
  public Boolean visitCharClass(CharClassNode node) {
    // Character classes are linear (single char match, no branching)
    return false;
  }

  @Override
  public Boolean visitConcat(ConcatNode node) {
    // Concatenation is linear if all children are linear
    for (RegexNode child : node.children) {
      if (child.accept(this)) {
        return true; // Found branching in child
      }
    }
    return false;
  }

  @Override
  public Boolean visitAlternation(AlternationNode node) {
    // Alternation creates branching - not linear
    return true;
  }

  @Override
  public Boolean visitQuantifier(QuantifierNode node) {
    // Quantifiers themselves don't create branching (even * and ? are deterministic in greedy mode)
    // Check the child pattern for branching
    return node.child.accept(this);
  }

  @Override
  public Boolean visitGroup(GroupNode node) {
    // Groups are transparent - check their child
    return node.child.accept(this);
  }

  @Override
  public Boolean visitAnchor(AnchorNode node) {
    // Anchors are linear (just position checks)
    return false;
  }

  @Override
  public Boolean visitBackreference(BackreferenceNode node) {
    // Backreferences are linear (deterministic comparison)
    return false;
  }

  @Override
  public Boolean visitAssertion(AssertionNode node) {
    // Lookahead/lookbehind assertions can have complex logic
    // For now, treat assertions as non-linear (conservative approach)
    // Future optimization: analyze assertion content for linearity
    return true;
  }

  @Override
  public Boolean visitSubroutine(SubroutineNode node) {
    // Subroutines involve recursion/calls - definitely not linear
    return true;
  }

  @Override
  public Boolean visitConditional(ConditionalNode node) {
    // Conditionals have branching based on runtime state - not linear
    return true;
  }

  @Override
  public Boolean visitBranchReset(BranchResetNode node) {
    // Branch reset has complex group handling - not linear
    return true;
  }
}
