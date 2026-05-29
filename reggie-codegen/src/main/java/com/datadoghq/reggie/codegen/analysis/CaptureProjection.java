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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** AST-level capture projection utilities. */
public final class CaptureProjection {
  private CaptureProjection() {}

  /**
   * Rewrites unnamed captures that are not needed by regex semantics into non-capturing groups.
   * Named groups keep their original group numbers so callers that discovered group indexes from
   * the original regex (for example Grok) can keep using those indexes.
   */
  public static RegexNode preserveNamedAndSemanticCaptures(RegexNode ast) {
    Set<Integer> semanticGroups = new HashSet<>();
    collectSemanticGroupReferences(ast, semanticGroups);
    return rewrite(ast, semanticGroups);
  }

  private static void collectSemanticGroupReferences(RegexNode node, Set<Integer> semanticGroups) {
    if (node instanceof BackreferenceNode) {
      semanticGroups.add(((BackreferenceNode) node).groupNumber);
    } else if (node instanceof ConditionalNode) {
      ConditionalNode conditional = (ConditionalNode) node;
      semanticGroups.add(conditional.condition);
      collectSemanticGroupReferences(conditional.thenBranch, semanticGroups);
      if (conditional.elseBranch != null) {
        collectSemanticGroupReferences(conditional.elseBranch, semanticGroups);
      }
    } else if (node instanceof SubroutineNode) {
      SubroutineNode subroutine = (SubroutineNode) node;
      if (subroutine.groupNumber > 0) {
        semanticGroups.add(subroutine.groupNumber);
      }
    } else if (node instanceof GroupNode) {
      collectSemanticGroupReferences(((GroupNode) node).child, semanticGroups);
    } else if (node instanceof QuantifierNode) {
      collectSemanticGroupReferences(((QuantifierNode) node).child, semanticGroups);
    } else if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        collectSemanticGroupReferences(child, semanticGroups);
      }
    } else if (node instanceof AlternationNode) {
      for (RegexNode alternative : ((AlternationNode) node).alternatives) {
        collectSemanticGroupReferences(alternative, semanticGroups);
      }
    } else if (node instanceof AssertionNode) {
      collectSemanticGroupReferences(((AssertionNode) node).subPattern, semanticGroups);
    } else if (node instanceof BranchResetNode) {
      for (RegexNode alternative : ((BranchResetNode) node).alternatives) {
        collectSemanticGroupReferences(alternative, semanticGroups);
      }
    }
  }

  private static RegexNode rewrite(RegexNode node, Set<Integer> semanticGroups) {
    if (node instanceof GroupNode) {
      GroupNode group = (GroupNode) node;
      RegexNode child = rewrite(group.child, semanticGroups);
      boolean keepCapturing =
          group.capturing && (group.name != null || semanticGroups.contains(group.groupNumber));
      return new GroupNode(child, keepCapturing ? group.groupNumber : 0, keepCapturing, group.name);
    }
    if (node instanceof QuantifierNode) {
      QuantifierNode quantifier = (QuantifierNode) node;
      return new QuantifierNode(
          rewrite(quantifier.child, semanticGroups),
          quantifier.min,
          quantifier.max,
          quantifier.greedy);
    }
    if (node instanceof ConcatNode) {
      List<RegexNode> children = new ArrayList<>();
      for (RegexNode child : ((ConcatNode) node).children) {
        children.add(rewrite(child, semanticGroups));
      }
      return new ConcatNode(children);
    }
    if (node instanceof AlternationNode) {
      List<RegexNode> alternatives = new ArrayList<>();
      for (RegexNode alternative : ((AlternationNode) node).alternatives) {
        alternatives.add(rewrite(alternative, semanticGroups));
      }
      return new AlternationNode(alternatives);
    }
    if (node instanceof AssertionNode) {
      AssertionNode assertion = (AssertionNode) node;
      return new AssertionNode(
          assertion.type, rewrite(assertion.subPattern, semanticGroups), assertion.fixedWidth);
    }
    if (node instanceof ConditionalNode) {
      ConditionalNode conditional = (ConditionalNode) node;
      return new ConditionalNode(
          conditional.condition,
          rewrite(conditional.thenBranch, semanticGroups),
          conditional.elseBranch != null ? rewrite(conditional.elseBranch, semanticGroups) : null);
    }
    if (node instanceof BranchResetNode) {
      BranchResetNode branchReset = (BranchResetNode) node;
      List<RegexNode> alternatives = new ArrayList<>();
      for (RegexNode alternative : branchReset.alternatives) {
        alternatives.add(rewrite(alternative, semanticGroups));
      }
      return new BranchResetNode(alternatives, branchReset.maxGroupNumber);
    }
    return node;
  }
}
