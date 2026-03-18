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
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes a linear regex pattern and builds an execution plan.
 *
 * <p>Walks the AST in order and generates a sequence of LinearOperation objects that represent the
 * steps needed to match the pattern. Used by LinearPatternBytecodeGenerator to generate direct
 * execution code.
 */
public class LinearPatternAnalyzer implements RegexVisitor<Void> {

  private final List<LinearPatternInfo.LinearOperation> operations;
  private boolean hasBackreferences;
  private boolean hasAnchors;
  private boolean hasQuantifiers;

  private LinearPatternAnalyzer() {
    this.operations = new ArrayList<>();
    this.hasBackreferences = false;
    this.hasAnchors = false;
    this.hasQuantifiers = false;
  }

  /**
   * Analyzes a linear pattern AST and builds an execution plan.
   *
   * @param ast the pattern AST (must be linear - use LinearPatternDetector.isLinear() first)
   * @param groupCount the number of capturing groups in the pattern
   * @return LinearPatternInfo containing the execution plan
   */
  public static LinearPatternInfo analyze(RegexNode ast, int groupCount) {
    if (ast == null) {
      throw new IllegalArgumentException("AST cannot be null");
    }

    LinearPatternAnalyzer analyzer = new LinearPatternAnalyzer();
    ast.accept(analyzer);

    return new LinearPatternInfo(
        ast,
        groupCount,
        analyzer.hasBackreferences,
        analyzer.hasAnchors,
        analyzer.hasQuantifiers,
        analyzer.operations);
  }

  @Override
  public Void visitLiteral(LiteralNode node) {
    // Match a literal character (convert to string)
    operations.add(
        new LinearPatternInfo.LinearOperation(
            LinearPatternInfo.LinearOperation.Type.MATCH_LITERAL, String.valueOf(node.ch)));
    return null;
  }

  @Override
  public Void visitCharClass(CharClassNode node) {
    // Match a character class
    operations.add(
        new LinearPatternInfo.LinearOperation(
            LinearPatternInfo.LinearOperation.Type.MATCH_CHARCLASS, node.chars));
    return null;
  }

  @Override
  public Void visitConcat(ConcatNode node) {
    // Process children in order
    for (RegexNode child : node.children) {
      child.accept(this);
    }
    return null;
  }

  @Override
  public Void visitAlternation(AlternationNode node) {
    // Should not reach here - LinearPatternDetector should reject patterns with alternation
    throw new IllegalStateException("Linear pattern analyzer cannot handle alternation");
  }

  @Override
  public Void visitQuantifier(QuantifierNode node) {
    hasQuantifiers = true;

    // Build operation list for the quantified child
    LinearPatternAnalyzer childAnalyzer = new LinearPatternAnalyzer();
    node.child.accept(childAnalyzer);

    // Create quantifier data
    LinearPatternInfo.QuantifierData quantData =
        new LinearPatternInfo.QuantifierData(
            node.min, node.max, node.greedy, childAnalyzer.operations);

    // Add quantifier operation
    operations.add(
        new LinearPatternInfo.LinearOperation(
            LinearPatternInfo.LinearOperation.Type.MATCH_QUANTIFIER, quantData));

    return null;
  }

  @Override
  public Void visitGroup(GroupNode node) {
    if (node.capturing) {
      // Record group start
      operations.add(
          new LinearPatternInfo.LinearOperation(
              LinearPatternInfo.LinearOperation.Type.START_GROUP, node.groupNumber));
    }

    // Process child
    node.child.accept(this);

    if (node.capturing) {
      // Record group end
      operations.add(
          new LinearPatternInfo.LinearOperation(
              LinearPatternInfo.LinearOperation.Type.END_GROUP, node.groupNumber));
    }

    return null;
  }

  @Override
  public Void visitAnchor(AnchorNode node) {
    hasAnchors = true;

    // Handle word boundaries separately from line anchors
    if (node.type == AnchorNode.Type.WORD_BOUNDARY) {
      // Word boundary check: \b (always positive, \B not yet supported)
      operations.add(
          new LinearPatternInfo.LinearOperation(
              LinearPatternInfo.LinearOperation.Type.CHECK_WORD_BOUNDARY,
              true // true = \b (positive word boundary)
              ));
      return null;
    }

    // Convert anchor type to AnchorType enum for line anchors
    LinearPatternInfo.AnchorType anchorType;
    switch (node.type) {
      case START:
        anchorType =
            node.multiline
                ? LinearPatternInfo.AnchorType.START_MULTILINE
                : LinearPatternInfo.AnchorType.START;
        break;
      case END:
        anchorType =
            node.multiline
                ? LinearPatternInfo.AnchorType.END_MULTILINE
                : LinearPatternInfo.AnchorType.END;
        break;
      default:
        throw new IllegalArgumentException("Unknown anchor type: " + node.type);
    }

    operations.add(
        new LinearPatternInfo.LinearOperation(
            LinearPatternInfo.LinearOperation.Type.CHECK_ANCHOR, anchorType));

    return null;
  }

  @Override
  public Void visitBackreference(BackreferenceNode node) {
    hasBackreferences = true;

    // Check backreference
    operations.add(
        new LinearPatternInfo.LinearOperation(
            LinearPatternInfo.LinearOperation.Type.CHECK_BACKREF, node.groupNumber));

    return null;
  }

  @Override
  public Void visitAssertion(AssertionNode node) {
    // Should not reach here - LinearPatternDetector should reject patterns with assertions
    throw new IllegalStateException(
        "Linear pattern analyzer cannot handle assertions (lookahead/lookbehind)");
  }

  @Override
  public Void visitSubroutine(SubroutineNode node) {
    throw new IllegalStateException("Linear pattern analyzer cannot handle subroutines");
  }

  @Override
  public Void visitConditional(ConditionalNode node) {
    throw new IllegalStateException("Linear pattern analyzer cannot handle conditionals");
  }

  @Override
  public Void visitBranchReset(BranchResetNode node) {
    throw new IllegalStateException("Linear pattern analyzer cannot handle branch reset");
  }
}
