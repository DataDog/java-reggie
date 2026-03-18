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
package com.datadoghq.reggie.codegen.parsing;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.*;
import org.junit.jupiter.api.Test;

/**
 * Tests for new AST nodes: SubroutineNode, ConditionalNode, BranchResetNode. Phase 1: Verify
 * patterns parse successfully (don't need to match yet).
 */
class NewASTNodesTest {

  private RegexNode parse(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    return parser.parse(pattern);
  }

  // ==================== Subroutine Tests ====================

  @Test
  void testParseRecursiveSubroutine() throws Exception {
    // (?R) - recursive call to entire pattern
    RegexNode node = parse("a(?R)b");

    // Expected structure: Concat[Literal('a'), Subroutine(-1), Literal('b')]
    assertInstanceOf(ConcatNode.class, node);
    ConcatNode concat = (ConcatNode) node;
    assertEquals(3, concat.children.size());

    assertInstanceOf(LiteralNode.class, concat.children.get(0));
    assertEquals('a', ((LiteralNode) concat.children.get(0)).ch);

    assertInstanceOf(SubroutineNode.class, concat.children.get(1));
    SubroutineNode sub = (SubroutineNode) concat.children.get(1);
    assertEquals(-1, sub.groupNumber, "(?R) should have groupNumber = -1");
    assertNull(sub.name, "(?R) should not have a name");

    assertInstanceOf(LiteralNode.class, concat.children.get(2));
    assertEquals('b', ((LiteralNode) concat.children.get(2)).ch);
  }

  @Test
  void testParseNumberedSubroutine() throws Exception {
    // (?1) - call to group 1
    RegexNode node = parse("(a+)(?1)");

    assertInstanceOf(ConcatNode.class, node);
    ConcatNode concat = (ConcatNode) node;
    assertEquals(2, concat.children.size());

    // First child: capturing group (a+)
    assertInstanceOf(GroupNode.class, concat.children.get(0));

    // Second child: subroutine call (?1)
    assertInstanceOf(SubroutineNode.class, concat.children.get(1));
    SubroutineNode sub = (SubroutineNode) concat.children.get(1);
    assertEquals(1, sub.groupNumber);
    assertNull(sub.name);
  }

  @Test
  void testParseNamedSubroutine() throws Exception {
    // (?&foo) - call to named group
    RegexNode node = parse("a(?&foo)b");

    assertInstanceOf(ConcatNode.class, node);
    ConcatNode concat = (ConcatNode) node;
    assertEquals(3, concat.children.size());

    assertInstanceOf(SubroutineNode.class, concat.children.get(1));
    SubroutineNode sub = (SubroutineNode) concat.children.get(1);
    assertEquals(-2, sub.groupNumber, "Named subroutine should have groupNumber = -2");
    assertEquals("foo", sub.name);
  }

  // ==================== Conditional Tests ====================

  @Test
  void testParseConditionalWithElse() throws Exception {
    // (?(1)yes|no) - conditional with both branches
    RegexNode node = parse("(a)?(?(1)b|c)");

    assertInstanceOf(ConcatNode.class, node);
    ConcatNode concat = (ConcatNode) node;
    assertEquals(2, concat.children.size());

    // First child: (a)?
    assertInstanceOf(QuantifierNode.class, concat.children.get(0));

    // Second child: (?(1)b|c)
    assertInstanceOf(ConditionalNode.class, concat.children.get(1));
    ConditionalNode cond = (ConditionalNode) concat.children.get(1);
    assertEquals(1, cond.condition, "Condition should check group 1");

    // Then branch: b
    assertInstanceOf(LiteralNode.class, cond.thenBranch);
    assertEquals('b', ((LiteralNode) cond.thenBranch).ch);

    // Else branch: c
    assertNotNull(cond.elseBranch, "Else branch should be present");
    assertInstanceOf(LiteralNode.class, cond.elseBranch);
    assertEquals('c', ((LiteralNode) cond.elseBranch).ch);
  }

  @Test
  void testParseConditionalWithoutElse() throws Exception {
    // (?(1)yes) - conditional with only then branch
    RegexNode node = parse("(a)?(?(1)b)");

    assertInstanceOf(ConcatNode.class, node);
    ConcatNode concat = (ConcatNode) node;
    assertEquals(2, concat.children.size());

    // Second child: (?(1)b)
    assertInstanceOf(ConditionalNode.class, concat.children.get(1));
    ConditionalNode cond = (ConditionalNode) concat.children.get(1);
    assertEquals(1, cond.condition);

    // Then branch: b
    assertInstanceOf(LiteralNode.class, cond.thenBranch);
    assertEquals('b', ((LiteralNode) cond.thenBranch).ch);

    // No else branch
    assertNull(cond.elseBranch, "Else branch should be null");
  }

  @Test
  void testParseConditionalWithComplexBranches() throws Exception {
    // (?(1)abc|xyz) - conditional with multi-char branches
    RegexNode node = parse("(a)?(?(1)abc|xyz)");

    assertInstanceOf(ConcatNode.class, node);
    ConcatNode concat = (ConcatNode) node;

    assertInstanceOf(ConditionalNode.class, concat.children.get(1));
    ConditionalNode cond = (ConditionalNode) concat.children.get(1);

    // Then branch: abc (concatenation of 3 literals)
    assertInstanceOf(ConcatNode.class, cond.thenBranch);
    ConcatNode thenConcat = (ConcatNode) cond.thenBranch;
    assertEquals(3, thenConcat.children.size());

    // Else branch: xyz
    assertNotNull(cond.elseBranch);
    assertInstanceOf(ConcatNode.class, cond.elseBranch);
    ConcatNode elseConcat = (ConcatNode) cond.elseBranch;
    assertEquals(3, elseConcat.children.size());
  }

  // ==================== Branch Reset Tests ====================

  @Test
  void testParseBranchReset() throws Exception {
    // (?|abc|xyz) - branch reset with two alternatives
    RegexNode node = parse("(?|abc|xyz)");

    assertInstanceOf(BranchResetNode.class, node);
    BranchResetNode branchReset = (BranchResetNode) node;

    assertEquals(2, branchReset.alternatives.size(), "Should have 2 alternatives");

    // First alternative: abc
    assertInstanceOf(ConcatNode.class, branchReset.alternatives.get(0));
    ConcatNode alt1 = (ConcatNode) branchReset.alternatives.get(0);
    assertEquals(3, alt1.children.size());

    // Second alternative: xyz
    assertInstanceOf(ConcatNode.class, branchReset.alternatives.get(1));
    ConcatNode alt2 = (ConcatNode) branchReset.alternatives.get(1);
    assertEquals(3, alt2.children.size());
  }

  @Test
  void testParseBranchResetWithGroups() throws Exception {
    // (?|(a)|(b)) - branch reset with groups
    // Group numbers should reset for each alternative
    RegexNode node = parse("(?|(a)|(b))");

    assertInstanceOf(BranchResetNode.class, node);
    BranchResetNode branchReset = (BranchResetNode) node;

    assertEquals(2, branchReset.alternatives.size());

    // First alternative: (a)
    assertInstanceOf(GroupNode.class, branchReset.alternatives.get(0));
    GroupNode group1 = (GroupNode) branchReset.alternatives.get(0);
    assertEquals(1, group1.groupNumber, "First alternative should have group 1");

    // Second alternative: (b) - should also be group 1 (reset)
    assertInstanceOf(GroupNode.class, branchReset.alternatives.get(1));
    GroupNode group2 = (GroupNode) branchReset.alternatives.get(1);
    assertEquals(1, group2.groupNumber, "Second alternative should also have group 1 (reset)");
  }

  @Test
  void testParseBranchResetWithMultipleAlternatives() throws Exception {
    // (?|a|b|c|d) - branch reset with 4 alternatives
    RegexNode node = parse("(?|a|b|c|d)");

    assertInstanceOf(BranchResetNode.class, node);
    BranchResetNode branchReset = (BranchResetNode) node;

    assertEquals(4, branchReset.alternatives.size(), "Should have 4 alternatives");
  }

  // ==================== Error Cases ====================

  @Test
  void testSubroutineThrowsOnExecution() {
    // Verify that ThompsonBuilder throws UnsupportedOperationException
    // when it encounters subroutine nodes (not yet implemented)
    RegexNode node = assertDoesNotThrow(() -> parse("(?R)"));
    assertInstanceOf(SubroutineNode.class, node);

    // ThompsonBuilder should throw when trying to build NFA
    assertThrows(
        UnsupportedOperationException.class,
        () -> {
          com.datadoghq.reggie.codegen.automaton.ThompsonBuilder builder =
              new com.datadoghq.reggie.codegen.automaton.ThompsonBuilder();
          builder.build(node, 0);
        },
        "ThompsonBuilder should reject subroutine patterns (not yet implemented)");
  }

  @Test
  void testConditionalBuildsNFA() {
    // Verify that conditional nodes can be built into NFA
    RegexNode node = assertDoesNotThrow(() -> parse("(?(1)a|b)"));
    assertInstanceOf(ConditionalNode.class, node);

    // Should successfully build NFA now that conditionals are implemented
    assertDoesNotThrow(
        () -> {
          com.datadoghq.reggie.codegen.automaton.ThompsonBuilder builder =
              new com.datadoghq.reggie.codegen.automaton.ThompsonBuilder();
          builder.build(node, 0);
        },
        "ThompsonBuilder should build NFA for conditional patterns");
  }

  @Test
  void testBranchResetBuildsNFA() {
    // Verify that branch reset nodes can be built into NFA
    RegexNode node = assertDoesNotThrow(() -> parse("(?|a|b)"));
    assertInstanceOf(BranchResetNode.class, node);

    // Should successfully build NFA now that branch reset is implemented
    assertDoesNotThrow(
        () -> {
          com.datadoghq.reggie.codegen.automaton.ThompsonBuilder builder =
              new com.datadoghq.reggie.codegen.automaton.ThompsonBuilder();
          builder.build(node, 0);
        },
        "ThompsonBuilder should build NFA for branch reset patterns");
  }
}
