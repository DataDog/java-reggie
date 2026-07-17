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
import java.util.Objects;
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

  /**
   * Returns the source capture layout when every numbered capture has one unambiguous source
   * definition suitable for the conservative native full-capture profile.
   *
   * <p>This deliberately declines branch-reset and non-empty alternatives: their numeric capture
   * layout is not a single canonical source-to-operation mapping.
   */
  public static FullCaptureLayout fullCaptureLayout(RegexNode ast) {
    Objects.requireNonNull(ast, "ast");
    Set<Integer> indexes = new HashSet<>();
    if (!collectFullCaptureLayout(ast, indexes)) return null;
    int groupCount = indexes.stream().mapToInt(Integer::intValue).max().orElse(0);
    for (int index = 1; index <= groupCount; index++) {
      if (!indexes.contains(index)) return null;
    }
    return new FullCaptureLayout(groupCount, indexes);
  }

  /** Canonical source group-number layout for the direct native full-capture profile. */
  public record FullCaptureLayout(int groupCount, Set<Integer> indexes) {
    public FullCaptureLayout {
      indexes = Set.copyOf(indexes);
    }
  }

  private static boolean collectFullCaptureLayout(RegexNode node, Set<Integer> indexes) {
    if (node instanceof GroupNode group) {
      if (!group.capturing) return collectFullCaptureLayout(group.child, indexes);
      return group.groupNumber > 0
          && indexes.add(group.groupNumber)
          && isDirectCaptureSource(group.child);
    }
    if (node instanceof ConcatNode concat) {
      for (RegexNode child : concat.children) {
        if (!collectFullCaptureLayout(child, indexes)) return false;
      }
      return true;
    }
    if (node instanceof QuantifierNode quantifier) {
      return collectFullCaptureLayout(quantifier.child, indexes);
    }
    if (node instanceof AlternationNode alternation) {
      if (alternation.alternatives.size() != 2) return false;
      RegexNode present = null;
      for (RegexNode alternative : alternation.alternatives) {
        if (isEmpty(alternative)) continue;
        if (present != null) return false;
        present = alternative;
      }
      return present != null && collectFullCaptureLayout(present, indexes);
    }
    if (node instanceof BranchResetNode
        || node instanceof AssertionNode
        || node instanceof ConditionalNode
        || node instanceof SubroutineNode
        || node instanceof BackreferenceNode) return false;
    return true;
  }

  private static boolean isDirectCaptureSource(RegexNode node) {
    if (node instanceof GroupNode group) {
      return !group.capturing && isDirectCaptureSource(group.child);
    }
    if (node instanceof AlternationNode || node instanceof BranchResetNode) return false;
    return !containsCapture(node);
  }

  private static boolean containsCapture(RegexNode node) {
    if (node instanceof GroupNode group) return group.capturing || containsCapture(group.child);
    if (node instanceof QuantifierNode quantifier) return containsCapture(quantifier.child);
    if (node instanceof ConcatNode concat) {
      return concat.children.stream().anyMatch(CaptureProjection::containsCapture);
    }
    if (node instanceof AlternationNode alternation) {
      return alternation.alternatives.stream().anyMatch(CaptureProjection::containsCapture);
    }
    return node instanceof BranchResetNode;
  }

  private static boolean isEmpty(RegexNode node) {
    return node instanceof LiteralNode literal && literal.ch == 0
        || node instanceof ConcatNode concat && concat.children.isEmpty();
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
