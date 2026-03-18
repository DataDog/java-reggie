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
package com.datadoghq.reggie.codegen.codegen;

import static org.objectweb.asm.Opcodes.*;

import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates specialized recursive descent parser bytecode for context-free patterns.
 *
 * <h3>Pattern Types (Beyond Regular Languages)</h3>
 *
 * <ul>
 *   <li><b>Subroutines</b>: {@code (?R)}, {@code (?1)}, {@code (?&name)}
 *   <li><b>Conditionals</b>: {@code (?(1)yes|no)}
 *   <li><b>Branch reset</b>: {@code (?|alt1|alt2)}
 * </ul>
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern a(?R)?b (recursive balanced a...b)
 * // Generated methods: parse_0() for the entire pattern
 *
 * int parse_0(String input, int pos, int[] groups, int depth) {
 *     if (depth > MAX_DEPTH) return -1;  // Recursion limit
 *
 *     // Match 'a'
 *     if (pos >= input.length() || input.charAt(pos) != 'a') return -1;
 *     pos++;
 *
 *     // Optional recursive call: (?R)?
 *     int savedPos = pos;
 *     int newPos = parse_0(input, pos, groups, depth + 1);  // Recursive call
 *     if (newPos >= 0) {
 *         pos = newPos;
 *     } else {
 *         pos = savedPos;  // Backtrack (optional failed)
 *     }
 *
 *     // Match 'b'
 *     if (pos >= input.length() || input.charAt(pos) != 'b') return -1;
 *     pos++;
 *
 *     return pos;
 * }
 *
 * boolean matches(String input) {
 *     int[] groups = new int[groupCount * 2];
 *     int result = parse_0(input, 0, groups, 0);
 *     return result == input.length();
 * }
 * }</pre>
 *
 * <h3>Implementation Phases</h3>
 *
 * <ul>
 *   <li>Phase 3: Basic framework (method generation infrastructure)
 *   <li>Phase 4: Subroutine implementation with recursion depth and memoization
 *   <li>Phase 5-7: Conditionals, branch reset, integration
 * </ul>
 */
public class RecursiveDescentBytecodeGenerator {

  // Maximum recursion depth before throwing StackOverflowError
  private static final int MAX_RECURSION_DEPTH = 100;

  private final RegexNode ast;
  private final NFA nfa;
  private final int groupCount;
  private final Map<RegexNode, String> nodeToMethodName;
  private final Map<SubroutineNode, Integer> subroutineNodeIds;
  private final Map<Integer, RegexNode> groupNumberToNode;
  private int methodCounter = 0;
  private int nodeIdCounter = 0;

  public RecursiveDescentBytecodeGenerator(RegexNode ast, NFA nfa) {
    this.ast = ast;
    this.nfa = nfa;
    this.groupCount = nfa != null ? nfa.getGroupCount() : countGroups(ast);
    this.nodeToMethodName = new HashMap<>();
    this.subroutineNodeIds = new HashMap<>();
    this.groupNumberToNode = new HashMap<>();

    // Build map of group numbers to their content nodes
    if (ast != null) {
      buildGroupMap(ast);
    }
  }

  /** Count the number of capturing groups in the AST. */
  private int countGroups(RegexNode node) {
    if (node == null) {
      return 0;
    }
    return node.accept(
        new RegexVisitor<Integer>() {
          @Override
          public Integer visitLiteral(LiteralNode node) {
            return 0;
          }

          @Override
          public Integer visitCharClass(CharClassNode node) {
            return 0;
          }

          @Override
          public Integer visitGroup(GroupNode node) {
            int count = node.groupNumber > 0 ? 1 : 0;
            return count + node.child.accept(this);
          }

          @Override
          public Integer visitQuantifier(QuantifierNode node) {
            return node.child.accept(this);
          }

          @Override
          public Integer visitAlternation(AlternationNode node) {
            int total = 0;
            for (RegexNode child : node.alternatives) {
              total += child.accept(this);
            }
            return total;
          }

          @Override
          public Integer visitConcat(ConcatNode node) {
            int total = 0;
            for (RegexNode child : node.children) {
              total += child.accept(this);
            }
            return total;
          }

          @Override
          public Integer visitAnchor(AnchorNode node) {
            return 0;
          }

          @Override
          public Integer visitAssertion(AssertionNode node) {
            return node.subPattern != null ? node.subPattern.accept(this) : 0;
          }

          @Override
          public Integer visitBackreference(BackreferenceNode node) {
            return 0;
          }

          @Override
          public Integer visitSubroutine(SubroutineNode node) {
            return 0;
          }

          @Override
          public Integer visitConditional(ConditionalNode node) {
            int total = node.thenBranch.accept(this);
            if (node.elseBranch != null) {
              total += node.elseBranch.accept(this);
            }
            return total;
          }

          @Override
          public Integer visitBranchReset(BranchResetNode node) {
            int max = 0;
            for (RegexNode alt : node.alternatives) {
              int count = alt.accept(this);
              if (count > max) {
                max = count;
              }
            }
            return max;
          }
        });
  }

  /**
   * Collect all group numbers that appear in a branch reset node's alternatives. These are the
   * groups that should be reset before trying each alternative.
   *
   * @param node The branch reset node to analyze
   * @return Set of group numbers found in the branch reset alternatives
   */
  private Set<Integer> collectBranchResetGroupNumbers(BranchResetNode node) {
    Set<Integer> groupNumbers = new HashSet<>();
    for (RegexNode alt : node.alternatives) {
      collectGroupNumbersFromNode(alt, groupNumbers);
    }
    return groupNumbers;
  }

  /**
   * Recursively collect group numbers from a node tree.
   *
   * @param node The node to analyze
   * @param result The set to populate with discovered group numbers
   */
  private void collectGroupNumbersFromNode(RegexNode node, Set<Integer> result) {
    if (node == null) {
      return;
    }

    node.accept(
        new RegexVisitor<Void>() {
          @Override
          public Void visitLiteral(LiteralNode node) {
            return null;
          }

          @Override
          public Void visitCharClass(CharClassNode node) {
            return null;
          }

          @Override
          public Void visitGroup(GroupNode node) {
            if (node.groupNumber > 0) {
              result.add(node.groupNumber);
            }
            node.child.accept(this);
            return null;
          }

          @Override
          public Void visitQuantifier(QuantifierNode node) {
            node.child.accept(this);
            return null;
          }

          @Override
          public Void visitAlternation(AlternationNode node) {
            for (RegexNode child : node.alternatives) {
              child.accept(this);
            }
            return null;
          }

          @Override
          public Void visitConcat(ConcatNode node) {
            for (RegexNode child : node.children) {
              child.accept(this);
            }
            return null;
          }

          @Override
          public Void visitAnchor(AnchorNode node) {
            return null;
          }

          @Override
          public Void visitAssertion(AssertionNode node) {
            if (node.subPattern != null) {
              node.subPattern.accept(this);
            }
            return null;
          }

          @Override
          public Void visitBackreference(BackreferenceNode node) {
            return null;
          }

          @Override
          public Void visitSubroutine(SubroutineNode node) {
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
            // Don't recurse into nested branch resets
            return null;
          }
        });
  }

  /** Build a map from group numbers to their content nodes. */
  private void buildGroupMap(RegexNode node) {
    node.accept(
        new RegexVisitor<Void>() {
          @Override
          public Void visitLiteral(LiteralNode node) {
            return null;
          }

          @Override
          public Void visitCharClass(CharClassNode node) {
            return null;
          }

          @Override
          public Void visitGroup(GroupNode node) {
            if (node.groupNumber > 0) {
              groupNumberToNode.put(node.groupNumber, node.child);
            }
            node.child.accept(this);
            return null;
          }

          @Override
          public Void visitQuantifier(QuantifierNode node) {
            node.child.accept(this);
            return null;
          }

          @Override
          public Void visitAlternation(AlternationNode node) {
            for (RegexNode child : node.alternatives) {
              child.accept(this);
            }
            return null;
          }

          @Override
          public Void visitConcat(ConcatNode node) {
            for (RegexNode child : node.children) {
              child.accept(this);
            }
            return null;
          }

          @Override
          public Void visitAnchor(AnchorNode node) {
            return null;
          }

          @Override
          public Void visitAssertion(AssertionNode node) {
            if (node.subPattern != null) {
              node.subPattern.accept(this);
            }
            return null;
          }

          @Override
          public Void visitBackreference(BackreferenceNode node) {
            return null;
          }

          @Override
          public Void visitSubroutine(SubroutineNode node) {
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
            // PCRE semantics: (?1) in branch reset calls the FIRST alternative's pattern
            // for that group number, not all alternatives.
            // Example: (?|(x)|(y))(?1) - (?1) always calls (x), regardless of which matched
            java.util.Map<Integer, RegexNode> firstGroupByNumber = new java.util.HashMap<>();

            // Collect FIRST group child for each group number (in order of alternatives)
            for (RegexNode alt : node.alternatives) {
              collectFirstBranchResetGroup(alt, firstGroupByNumber);
            }

            // Store the first pattern for each group number
            for (java.util.Map.Entry<Integer, RegexNode> entry : firstGroupByNumber.entrySet()) {
              groupNumberToNode.put(entry.getKey(), entry.getValue());
            }

            // Still visit children for any nested groups not directly in the branch reset
            for (RegexNode alt : node.alternatives) {
              visitNonBranchResetGroups(alt, this);
            }
            return null;
          }

          private void collectFirstBranchResetGroup(
              RegexNode node, java.util.Map<Integer, RegexNode> map) {
            if (node instanceof GroupNode) {
              GroupNode g = (GroupNode) node;
              if (g.groupNumber > 0) {
                // Only store the FIRST occurrence for each group number
                map.putIfAbsent(g.groupNumber, g.child);
              }
              // Don't recurse into the group's child here - we want top-level groups only
            } else if (node instanceof ConcatNode) {
              for (RegexNode child : ((ConcatNode) node).children) {
                collectFirstBranchResetGroup(child, map);
              }
            }
            // Don't recurse into other node types for top-level group collection
          }

          private void visitNonBranchResetGroups(RegexNode node, RegexVisitor<Void> visitor) {
            if (node instanceof GroupNode) {
              GroupNode g = (GroupNode) node;
              // Visit children of groups for nested groups
              g.child.accept(visitor);
            } else if (node instanceof ConcatNode) {
              for (RegexNode child : ((ConcatNode) node).children) {
                visitNonBranchResetGroups(child, visitor);
              }
            } else if (node instanceof QuantifierNode) {
              visitNonBranchResetGroups(((QuantifierNode) node).child, visitor);
            } else if (node instanceof AlternationNode) {
              for (RegexNode alt : ((AlternationNode) node).alternatives) {
                visitNonBranchResetGroups(alt, visitor);
              }
            }
          }
        });
  }

  /**
   * Compute the set of characters that can start a match for this pattern. Used for first-character
   * optimization in findBoundsFrom. Returns null if the optimization is not applicable (e.g.,
   * pattern starts with ^).
   */
  private CharSet computeFirstCharSet(RegexNode node) {
    if (node == null) {
      return CharSet.ANY; // Conservative: any character
    }

    return node.accept(
        new RegexVisitor<CharSet>() {
          @Override
          public CharSet visitLiteral(LiteralNode node) {
            return CharSet.of(node.ch);
          }

          @Override
          public CharSet visitCharClass(CharClassNode node) {
            // Negated character classes could match almost anything
            // Conservative: if negated, return ANY
            if (node.negated) {
              return CharSet.ANY;
            }
            return node.chars;
          }

          @Override
          public CharSet visitGroup(GroupNode node) {
            return node.child.accept(this);
          }

          @Override
          public CharSet visitQuantifier(QuantifierNode node) {
            // If min == 0, pattern can match empty string, so can't optimize
            if (node.min == 0) {
              return CharSet.ANY;
            }
            return node.child.accept(this);
          }

          @Override
          public CharSet visitConcat(ConcatNode node) {
            if (node.children.isEmpty()) {
              return CharSet.ANY;
            }

            // Skip over zero-width assertions and anchors to find actual first character
            for (RegexNode child : node.children) {
              if (child instanceof AnchorNode) {
                // Anchors don't consume, skip to next
                continue;
              }
              // This child could consume a character, use it
              CharSet charSet = child.accept(this);
              // If this child returns ANY, it means we can't optimize further
              if (!charSet.equals(CharSet.ANY)) {
                return charSet;
              }
              // Child could match anything or is too complex, stop here
              return CharSet.ANY;
            }
            // All children are anchors - can't optimize
            return CharSet.ANY;
          }

          @Override
          public CharSet visitAlternation(AlternationNode node) {
            if (node.alternatives.isEmpty()) {
              return CharSet.ANY;
            }
            // Union of first char sets from all alternatives
            CharSet result = node.alternatives.get(0).accept(this);
            for (int i = 1; i < node.alternatives.size(); i++) {
              CharSet altSet = node.alternatives.get(i).accept(this);
              result = result.union(altSet);
            }
            return result;
          }

          @Override
          public CharSet visitAnchor(AnchorNode node) {
            // Anchors don't consume characters, they're zero-width
            // Return empty set to signal "skip me" in concat context
            // (but this will be handled by concat logic above)
            return CharSet.ANY;
          }

          @Override
          public CharSet visitAssertion(AssertionNode node) {
            // Lookahead/lookbehind don't consume characters
            // Would need to analyze what follows the assertion
            // Conservative: return ANY
            return CharSet.ANY;
          }

          @Override
          public CharSet visitBackreference(BackreferenceNode node) {
            // Backreference content is unknown at analysis time
            return CharSet.ANY;
          }

          @Override
          public CharSet visitSubroutine(SubroutineNode node) {
            // Resolve subroutine target
            RegexNode target;
            if (node.groupNumber == 0) {
              // (?R) - entire pattern
              target = ast;
            } else {
              // (?1), (?2), etc.
              target = groupNumberToNode.get(node.groupNumber);
            }

            if (target == null) {
              return CharSet.ANY;
            }

            // Recursively compute first char set
            // Note: This could infinite loop if pattern is directly self-recursive
            // In practice, most subroutine patterns have some literal/charclass prefix
            return target.accept(this);
          }

          @Override
          public CharSet visitConditional(ConditionalNode node) {
            // Union of then and else branches
            CharSet result = node.thenBranch.accept(this);
            if (node.elseBranch != null) {
              CharSet elseSet = node.elseBranch.accept(this);
              result = result.union(elseSet);
            }
            return result;
          }

          @Override
          public CharSet visitBranchReset(BranchResetNode node) {
            if (node.alternatives.isEmpty()) {
              return CharSet.ANY;
            }
            // Union of first char sets from all alternatives
            CharSet result = node.alternatives.get(0).accept(this);
            for (int i = 1; i < node.alternatives.size(); i++) {
              CharSet altSet = node.alternatives.get(i).accept(this);
              result = result.union(altSet);
            }
            return result;
          }
        });
  }

  /** Main entry point: Generate matcher class with all parsing methods. */
  public byte[] generate(String className) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

    cw.visit(
        V21,
        ACC_PUBLIC | ACC_SUPER,
        className.replace('.', '/'),
        null,
        "com/datadoghq/reggie/runtime/ReggieMatcher",
        null);

    // Generate constructor
    generateConstructor(cw, className);

    // Generate all parser methods
    generateAllParserMethods(cw, className);

    // Generate matches() method
    generateMatchesMethod(cw, className);

    // Generate findBoundsFrom() method
    generateFindBoundsFromMethod(cw, className);

    // Generate matchesBounded() method
    generateMatchesBoundedMethod(cw, className);

    // Generate match() method that returns MatchResult
    generateMatchMethod(cw, className);

    // Generate find() and findFrom() methods
    generateFindMethod(cw, className);
    generateFindFromMethod(cw, className);

    // Generate findMatch() and findMatchFrom() methods
    generateFindMatchMethod(cw, className);
    generateFindMatchFromMethod(cw, className);

    cw.visitEnd();
    return cw.toByteArray();
  }

  /**
   * Generate all parser methods (parseRoot and AST node parsers). This must be called before
   * generating the public API methods.
   */
  public void generateAllParserMethods(ClassWriter cw, String className) {
    // IMPORTANT: Generate parser methods for AST nodes FIRST
    // This must happen before generateParseRootMethod, because parseRoot
    // calls getMethodNameForNode(ast) which adds ast to the map,
    // preventing generateParserMethod from generating it
    generateParserMethod(cw, className, ast);

    // Generate parseRoot method (entry point that delegates to ast parser)
    generateParseRootMethod(cw, className);
  }

  /** Generate constructor that calls super(). */
  public void generateConstructor(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(
        INVOKESPECIAL, "com/datadoghq/reggie/runtime/ReggieMatcher", "<init>", "()V", false);
    mv.visitInsn(RETURN);
    mv.visitMaxs(1, 1);
    mv.visitEnd();
  }

  /** Generate matches() method implementation. Signature: public boolean matches(String input) */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int iVar = allocator.allocate();

    // int[] groups = new int[(groupCount + 1) * 2]
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize all group positions to -1 (unset)
    // for (int i = 0; i < groups.length; i++) groups[i] = -1;
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitInsn(ICONST_0); // i = 0
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar); // i
    mv.visitVarInsn(ALOAD, groupsVar); // groups
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd); // if i >= length, exit loop
    // groups[i] = -1
    mv.visitVarInsn(ALOAD, groupsVar); // groups
    mv.visitVarInsn(ILOAD, iVar); // i
    mv.visitInsn(ICONST_M1); // -1
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1); // i++
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // Call root parser: int result = parse_X_0(input, 0, input.length(), groups, depth)
    String rootParserMethod = getMethodNameForNode(ast);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // pos = 0
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ALOAD, groupsVar); // groups
    mv.visitInsn(ICONST_0); // depth = 0
    mv.visitMethodInsn(
        INVOKESPECIAL, className, rootParserMethod, "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar); // result

    // Check if result == input.length() (full match)
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    Label matchSuccess = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, matchSuccess);
    mv.visitInsn(ICONST_0); // false
    mv.visitInsn(IRETURN);

    mv.visitLabel(matchSuccess);
    mv.visitInsn(ICONST_1); // true
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate findBoundsFrom() implementation with first-character optimization. Signature:
   * protected int findBoundsFrom(CharSequence input, int fromIndex, int[] bounds)
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    // Compute first-character set for optimization
    CharSet firstCharSet = computeFirstCharSet(ast);
    boolean canOptimize = firstCharSet != null && !firstCharSet.equals(CharSet.ANY);

    MethodVisitor mv =
        cw.visitMethod(
            ACC_PROTECTED, "findBoundsFrom", "(Ljava/lang/CharSequence;I[I)I", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=charSeq, 2=fromIndex, 3=bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int stringInputVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int groupsVar = allocator.allocate();
    int posVar = allocator.allocate();
    int iVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int charVar = canOptimize ? allocator.allocate() : -1;

    // Cast CharSequence to String for our parser (limitation for now)
    // S: []
    mv.visitVarInsn(ALOAD, 1); // input (CharSequence)
    // S: [A:CharSequence]
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    // S: [A:String]
    mv.visitVarInsn(ASTORE, stringInputVar);

    // int len = input.length()
    // S: []
    mv.visitVarInsn(ALOAD, stringInputVar);
    // S: [A:String]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I]
    mv.visitVarInsn(ISTORE, lenVar);

    // Allocate groups array: int[] groups = new int[(groupCount + 1) * 2]
    // S: []
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    // S: [I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I]]
    mv.visitVarInsn(ASTORE, groupsVar);

    // Try matching from each position
    Label findMatchPositionLoop = new Label();
    Label findMatchPositionLoopEnd = new Label();
    Label foundMatch = new Label();
    Label firstCharOptimizationSkip = new Label();

    // pos starts at fromIndex
    // S: []
    mv.visitVarInsn(ILOAD, 2); // fromIndex
    // S: [I]
    mv.visitVarInsn(ISTORE, posVar);

    mv.visitLabel(findMatchPositionLoop);
    // if (pos > len) goto findMatchPositionLoopEnd
    // S: []
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I]
    mv.visitJumpInsn(IF_ICMPGT, findMatchPositionLoopEnd);

    // First-character optimization: skip positions that can't match
    if (canOptimize) {
      // if (pos < len) {
      //     char c = input.charAt(pos);
      //     if (!firstCharSet.contains(c)) goto firstCharOptimizationSkip;
      // }

      // S: []
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I]
      mv.visitVarInsn(ILOAD, lenVar);
      // S: [I, I]
      mv.visitJumpInsn(IF_ICMPGE, firstCharOptimizationSkip); // if pos >= len, skip check

      // Get character at position: char c = input.charAt(pos)
      // S: []
      mv.visitVarInsn(ALOAD, stringInputVar);
      // S: [A:String]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [A:String, I]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      // S: [C]
      mv.visitVarInsn(ISTORE, charVar);

      // Check if character is in firstCharSet
      // We need to generate code for CharSet.contains(c)
      // For now, generate a simple range check for common cases
      generateFirstCharSetCheck(mv, firstCharSet, charVar, firstCharOptimizationSkip);
    }

    // Initialize groups to -1
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    // S: []
    mv.visitInsn(ICONST_0); // i = 0
    // S: [I]
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    // S: []
    mv.visitVarInsn(ILOAD, iVar);
    // S: [I]
    mv.visitVarInsn(ALOAD, groupsVar);
    // S: [I, A:[I]]
    mv.visitInsn(ARRAYLENGTH);
    // S: [I, I]
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);

    // groups[i] = -1
    // S: []
    mv.visitVarInsn(ALOAD, groupsVar);
    // S: [A:[I]]
    mv.visitVarInsn(ILOAD, iVar);
    // S: [A:[I], I]
    mv.visitInsn(ICONST_M1); // -1
    // S: [A:[I], I, I]
    mv.visitInsn(IASTORE);
    // S: []
    mv.visitIincInsn(iVar, 1); // i++
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // Try to match at this position: result = parseRoot(input, pos, len, groups)
    // S: []
    mv.visitVarInsn(ALOAD, 0); // this
    // S: [A:this]
    mv.visitVarInsn(ALOAD, stringInputVar);
    // S: [A:this, A:String]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [A:this, A:String, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [A:this, A:String, I, I]
    mv.visitVarInsn(ALOAD, groupsVar);
    // S: [A:this, A:String, I, I, A:[I]]
    mv.visitInsn(ICONST_0); // depth = 0
    mv.visitMethodInsn(INVOKESPECIAL, className, "parseRoot", "(Ljava/lang/String;II[II)I", false);
    // S: [I]
    mv.visitVarInsn(ISTORE, resultVar);

    // If result != -1, we found a match
    // S: []
    mv.visitVarInsn(ILOAD, resultVar);
    // S: [I]
    mv.visitInsn(ICONST_M1);
    // S: [I, I]
    mv.visitJumpInsn(IF_ICMPNE, foundMatch);

    // No match at this position, try next
    // S: []
    mv.visitLabel(firstCharOptimizationSkip); // Landing point for first-char optimization
    mv.visitIincInsn(posVar, 1); // pos++
    mv.visitJumpInsn(GOTO, findMatchPositionLoop);

    mv.visitLabel(foundMatch);
    // Set bounds[0] = pos (start), bounds[1] = result (end)
    // S: []
    mv.visitVarInsn(ALOAD, 3); // bounds
    // S: [A:[I]]
    mv.visitInsn(ICONST_0);
    // S: [A:[I], I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [A:[I], I, I]
    mv.visitInsn(IASTORE);

    // S: []
    mv.visitVarInsn(ALOAD, 3); // bounds
    // S: [A:[I]]
    mv.visitInsn(ICONST_1);
    // S: [A:[I], I]
    mv.visitVarInsn(ILOAD, resultVar);
    // S: [A:[I], I, I]
    mv.visitInsn(IASTORE);

    // Return start position
    // S: []
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I]
    mv.visitInsn(IRETURN);

    mv.visitLabel(findMatchPositionLoopEnd);
    // No match found anywhere
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(5, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate bytecode to check if a character is in a CharSet. If the character is NOT in the set,
   * jumps to notInSetLabel.
   *
   * @param mv MethodVisitor for bytecode generation
   * @param charSet The CharSet to check against
   * @param charVarSlot Local variable slot containing the char to check
   * @param notInSetLabel Label to jump to if char is NOT in the set
   */
  private void generateFirstCharSetCheck(
      MethodVisitor mv, CharSet charSet, int charVarSlot, Label notInSetLabel) {
    if (charSet.isSingleChar()) {
      // Single character: if (c != char) goto notInSet
      // S: []
      mv.visitVarInsn(ILOAD, charVarSlot); // load char c
      // S: [C]
      BytecodeUtil.pushInt(mv, charSet.getSingleChar());
      // S: [C, C]
      mv.visitJumpInsn(IF_ICMPNE, notInSetLabel); // if c != target, skip
      // S: []
    } else if (charSet.isSimpleRange()) {
      // Single range: if (c < start || c > end) goto notInSet
      CharSet.Range range = charSet.getSimpleRange();

      // Check c < start: if true, goto notInSet
      // S: []
      mv.visitVarInsn(ILOAD, charVarSlot); // c
      // S: [C]
      BytecodeUtil.pushInt(mv, range.start);
      // S: [C, C]
      mv.visitJumpInsn(IF_ICMPLT, notInSetLabel);
      // S: []

      // Check c > end: if true, goto notInSet
      mv.visitVarInsn(ILOAD, charVarSlot); // c
      // S: [C]
      BytecodeUtil.pushInt(mv, range.end);
      // S: [C, C]
      mv.visitJumpInsn(IF_ICMPGT, notInSetLabel);
      // S: []
    } else {
      // Multiple ranges: check each range, if any matches, continue; else goto notInSet
      // Logic: if (c in range1 || c in range2 || ... || c in rangeN) continue; else goto notInSet

      Label inSet = new Label(); // If we find a match, jump here to continue

      for (CharSet.Range range : charSet.getRanges()) {
        // For each range, check: if (c >= start && c <= end) goto inSet
        Label nextRange = new Label();

        // Check c < start: if true, try next range
        // S: []
        mv.visitVarInsn(ILOAD, charVarSlot); // c
        // S: [C]
        BytecodeUtil.pushInt(mv, range.start);
        // S: [C, C]
        mv.visitJumpInsn(IF_ICMPLT, nextRange);
        // S: []

        // Check c <= end: if true, we're in this range, goto inSet
        mv.visitVarInsn(ILOAD, charVarSlot); // c
        // S: [C]
        BytecodeUtil.pushInt(mv, range.end);
        // S: [C, C]
        mv.visitJumpInsn(IF_ICMPLE, inSet); // c is in range!
        // S: []

        // Not in this range, try next
        mv.visitLabel(nextRange);
      }

      // Checked all ranges, none matched: goto notInSet
      mv.visitJumpInsn(GOTO, notInSetLabel);

      // Character is in one of the ranges: continue
      mv.visitLabel(inSet);
    }
  }

  /**
   * Generate matchesBounded() implementation. Signature: public boolean matchesBounded(CharSequence
   * input, int start, int end)
   */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start, 3=end
    // Note: slot 1 will be reused for String input
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int iVar = allocator.allocate();

    // Cast CharSequence to String
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ASTORE, 1); // Replace input with String version

    // Allocate groups array: int[] groups = new int[(groupCount + 1) * 2]
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize groups to -1
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitInsn(ICONST_0); // i = 0
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);

    // groups[i] = -1
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_M1); // -1
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1); // i++
    mv.visitJumpInsn(GOTO, initLoopStart);

    mv.visitLabel(initLoopEnd);

    // Call parseRoot: int result = parseRoot(input, 0, input.length(), groups)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "length", "()I", false); // end = input.length()
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ICONST_0); // depth = 0
    mv.visitMethodInsn(INVOKESPECIAL, className, "parseRoot", "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar);

    // Check if we matched the entire bounded region
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    Label matchFailed = new Label();
    mv.visitJumpInsn(IF_ICMPNE, matchFailed); // if result != input.length(), match failed

    // Set group 0 (entire match): groups[0] = 0, groups[1] = result
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ICONST_0); // index 0
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitInsn(IASTORE); // groups[0] = 0

    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ICONST_1); // index 1
    mv.visitVarInsn(ILOAD, 3); // result (end position)
    mv.visitInsn(IASTORE); // groups[1] = result

    // Convert groups array to MatchResult format
    mv.visitInsn(ICONST_1); // true
    mv.visitInsn(IRETURN);

    mv.visitLabel(matchFailed);
    mv.visitInsn(ICONST_0); // false
    mv.visitInsn(IRETURN);

    mv.visitMaxs(5, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate match() method that returns MatchResult with capturing groups. Signature: public
   * MatchResult match(String input)
   */
  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();
    int iVar = allocator.allocate();

    // Allocate groups array: int[] groups = new int[(groupCount + 1) * 2]
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize groups to -1
    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iVar);

    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1);
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // Call parseRoot
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ICONST_0); // depth = 0
    mv.visitMethodInsn(INVOKESPECIAL, className, "parseRoot", "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar);

    // Check if matched entire input
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    Label matchFailed = new Label();
    mv.visitJumpInsn(IF_ICMPNE, matchFailed);

    // Create MatchResult: starts and ends arrays
    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // Copy from groups to starts/ends
    // for (int i = 0; i <= groupCount; i++) {
    //     starts[i] = groups[i * 2];
    //     ends[i] = groups[i * 2 + 1];
    // }
    for (int i = 0; i <= groupCount; i++) {
      // starts[i] = groups[i * 2]
      mv.visitVarInsn(ALOAD, startsVar);
      BytecodeUtil.pushInt(mv, i); // index
      mv.visitVarInsn(ALOAD, groupsVar);
      BytecodeUtil.pushInt(mv, i * 2); // groups index
      mv.visitInsn(IALOAD); // groups[i * 2]
      mv.visitInsn(IASTORE); // starts[i] = ...

      // ends[i] = groups[i * 2 + 1]
      mv.visitVarInsn(ALOAD, endsVar);
      BytecodeUtil.pushInt(mv, i); // index
      mv.visitVarInsn(ALOAD, groupsVar);
      BytecodeUtil.pushInt(mv, i * 2 + 1); // groups index
      mv.visitInsn(IALOAD); // groups[i * 2 + 1]
      mv.visitInsn(IASTORE); // ends[i] = ...
    }

    // new MatchResultImpl(input, starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    BytecodeUtil.pushInt(mv, groupCount); // groupCount
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchFailed);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate find method - searches for pattern in the input string. Signature: public boolean
   * find(String input)
   */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Delegate to findFrom(input, 0)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "findFrom", "(Ljava/lang/String;I)I", false);

    // Return true if result != -1
    mv.visitInsn(ICONST_M1);
    Label notFound = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, notFound);
    mv.visitInsn(ICONST_1); // true
    mv.visitInsn(IRETURN);

    mv.visitLabel(notFound);
    mv.visitInsn(ICONST_0); // false
    mv.visitInsn(IRETURN);

    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  /**
   * Generate findFrom method - searches for pattern starting at the given position. Signature:
   * public int findFrom(String input, int start)
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int boundsVar = allocator.allocate();

    // int[] bounds = new int[2]
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, boundsVar);

    // Call findBoundsFrom(input, start, bounds) - returns int (start position or -1)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "findBoundsFrom", "(Ljava/lang/CharSequence;I[I)I", false);

    // findBoundsFrom returns int (position or -1), just return it directly
    mv.visitInsn(IRETURN);

    mv.visitMaxs(4, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate findMatch() method - finds and returns MatchResult. Signature: public MatchResult
   * findMatch(String input)
   */
  public void generateFindMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Delegate to findMatchFrom(input, 0)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className,
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  /**
   * Generate findMatchFrom() method - finds pattern starting at position and returns MatchResult.
   * Signature: public MatchResult findMatchFrom(String input, int start)
   */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int boundsVar = allocator.allocate();
    int matchStartVar = allocator.allocate();
    int groupsVar = allocator.allocate();
    int resultVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();
    int iVar = allocator.allocate();

    // if (input == null) return null
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int[] bounds = new int[2]
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, boundsVar);

    // int matchStart = findBoundsFrom(input, start, bounds)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "findBoundsFrom", "(Ljava/lang/CharSequence;I[I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart == -1) return null
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(ICONST_M1);
    Label notFound = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, notFound);

    // Match found - allocate groups array: int[] groups = new int[(groupCount + 1) * 2]
    BytecodeUtil.pushInt(mv, (groupCount + 1) * 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupsVar);

    // Initialize groups to -1
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iVar);

    Label initLoopStart = new Label();
    Label initLoopEnd = new Label();
    mv.visitLabel(initLoopStart);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, initLoopEnd);

    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(iVar, 1); // i++
    mv.visitJumpInsn(GOTO, initLoopStart);
    mv.visitLabel(initLoopEnd);

    // Call parseRoot at the matched position to get all group captures
    // int result = parseRoot(input, matchStart, bounds[1], groups, 0)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, matchStartVar); // matchStart (pos)
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IALOAD); // bounds[1] (end)
    mv.visitVarInsn(ALOAD, groupsVar);
    mv.visitInsn(ICONST_0); // depth = 0
    mv.visitMethodInsn(INVOKESPECIAL, className, "parseRoot", "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar);

    // Create MatchResult: allocate starts and ends arrays
    BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    BytecodeUtil.pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // Copy from groups to starts/ends
    for (int i = 0; i <= groupCount; i++) {
      // starts[i] = groups[i * 2]
      mv.visitVarInsn(ALOAD, startsVar);
      BytecodeUtil.pushInt(mv, i);
      mv.visitVarInsn(ALOAD, groupsVar);
      BytecodeUtil.pushInt(mv, i * 2);
      mv.visitInsn(IALOAD);
      mv.visitInsn(IASTORE);

      // ends[i] = groups[i * 2 + 1]
      mv.visitVarInsn(ALOAD, endsVar);
      BytecodeUtil.pushInt(mv, i);
      mv.visitVarInsn(ALOAD, groupsVar);
      BytecodeUtil.pushInt(mv, i * 2 + 1);
      mv.visitInsn(IALOAD);
      mv.visitInsn(IASTORE);
    }

    // new MatchResultImpl(input, starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    BytecodeUtil.pushInt(mv, groupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Not found - return null
    mv.visitLabel(notFound);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(7, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate parseRoot method - entry point that delegates to the root parser. Signature: private
   * int parseRoot(String input, int pos, int end, int[] groups)
   */
  private void generateParseRootMethod(ClassWriter cw, String className) {
    String rootMethod = getMethodNameForNode(ast);

    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, "parseRoot", "(Ljava/lang/String;II[II)I", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
    LocalVarAllocator allocator = new LocalVarAllocator(6);
    int resultVar = allocator.allocate();

    // Save starting position for group 0
    // groups[0] = pos
    mv.visitVarInsn(ALOAD, 4); // groups
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 2); // pos
    mv.visitInsn(IASTORE);

    // Delegate to the root parser method
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // pos
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitVarInsn(ALOAD, 4); // groups
    mv.visitVarInsn(ILOAD, 5); // depth
    mv.visitMethodInsn(INVOKESPECIAL, className, rootMethod, "(Ljava/lang/String;II[II)I", false);
    mv.visitVarInsn(ISTORE, resultVar);

    // If match succeeded, set group 0 end position
    // if (result != -1) groups[1] = result
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitInsn(ICONST_M1);
    Label failed = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, failed);

    // Success: set group 0 end
    mv.visitVarInsn(ALOAD, 4); // groups
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitInsn(IASTORE);

    mv.visitLabel(failed);
    // Return result
    mv.visitVarInsn(ILOAD, resultVar);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, allocator.peek());
    mv.visitEnd();
  }

  /**
   * Generate parser method for a specific AST node. Signature: private int parse_X_N(String input,
   * int pos, int end, int[] groups, int depth)
   */
  private void generateParserMethod(ClassWriter cw, String className, RegexNode node) {
    // Skip if already generated
    if (nodeToMethodName.containsKey(node)) {
      return;
    }

    String methodName = getMethodNameForNode(node);

    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, methodName, "(Ljava/lang/String;II[II)I", null, null);
    mv.visitCode();

    // Visit the node to generate parsing logic
    node.accept(new ParserMethodGenerator(cw, mv, className));

    mv.visitMaxs(0, 0); // Will be computed by ASM
    mv.visitEnd();
  }

  /** Get or create method name for a node. */
  private String getMethodNameForNode(RegexNode node) {
    return nodeToMethodName.computeIfAbsent(
        node,
        n -> {
          String prefix = "parse_" + n.getClass().getSimpleName().replace("Node", "");
          return prefix + "_" + (methodCounter++);
        });
  }

  /** Visitor that generates parser method body for each node type. */
  private class ParserMethodGenerator implements RegexVisitor<Void> {
    private final ClassWriter cw;
    private final MethodVisitor mv;
    private final String className;

    public ParserMethodGenerator(ClassWriter cw, MethodVisitor mv, String className) {
      this.cw = cw;
      this.mv = mv;
      this.className = className;
    }

    @Override
    public Void visitLiteral(LiteralNode node) {
      // Check for epsilon (empty match) - represented as '\0'
      if (node.ch == 0) {
        // Epsilon: match without consuming any input
        // Just return current position
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitInsn(IRETURN);
        return null;
      }

      // LiteralNode now represents a single character
      // Check if we have enough characters left
      // if (pos >= end) return -1;
      // S: []
      mv.visitVarInsn(ILOAD, 2); // pos
      // S: [I]
      mv.visitVarInsn(ILOAD, 3); // end
      // S: [I, I]
      Label lengthOk = new Label();
      mv.visitJumpInsn(IF_ICMPLT, lengthOk);
      // S: []
      mv.visitInsn(ICONST_M1);
      // S: [I]
      mv.visitInsn(IRETURN);

      mv.visitLabel(lengthOk);
      // S: []
      // Check character: if (input.charAt(pos) != node.ch) return -1;
      mv.visitVarInsn(ALOAD, 1); // input
      // S: [A:String]
      mv.visitVarInsn(ILOAD, 2); // pos
      // S: [A:String, I]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      // S: [C]
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, node.ch);
      // S: [C, I]
      Label charMatch = new Label();
      mv.visitJumpInsn(IF_ICMPEQ, charMatch);
      // S: []
      mv.visitInsn(ICONST_M1);
      // S: [I]
      mv.visitInsn(IRETURN);

      mv.visitLabel(charMatch);
      // S: []
      // Return new position (pos + 1)
      mv.visitVarInsn(ILOAD, 2); // pos
      // S: [I]
      mv.visitInsn(ICONST_1);
      // S: [I, I]
      mv.visitInsn(IADD);
      // S: [I]
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitCharClass(CharClassNode node) {
      // Check bounds: if (pos >= end) return -1;
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 3); // end
      Label boundsOk = new Label();
      mv.visitJumpInsn(IF_ICMPLT, boundsOk);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(boundsOk);

      // Get character: char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, 5); // c in slot 5

      // Check if character matches
      Label inRange = new Label();
      Label notInRange = new Label();

      // Check if character is in any range
      CharSet charSet = node.chars;
      for (CharSet.Range range : charSet.getRanges()) {
        char start = range.start;
        char end = range.end;

        // if (c >= start && c <= end) goto inRange
        mv.visitVarInsn(ILOAD, 5); // c
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, start);
        Label checkEnd = new Label();
        mv.visitJumpInsn(IF_ICMPLT, checkEnd); // if c < start, skip this range

        mv.visitVarInsn(ILOAD, 5); // c
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, end);
        mv.visitJumpInsn(IF_ICMPLE, inRange); // if c <= end, character is in range

        mv.visitLabel(checkEnd);
      }

      // Character not in any range
      mv.visitJumpInsn(GOTO, notInRange);

      // Now apply negation logic
      mv.visitLabel(inRange);
      // Character IS in range
      if (node.negated) {
        // Negated: [^...] - fail if in range
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
      } else {
        // Not negated: [...] - succeed if in range
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
      }

      mv.visitLabel(notInRange);
      // Character is NOT in range
      if (node.negated) {
        // Negated: [^...] - succeed if not in range
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitInsn(IRETURN);
      } else {
        // Not negated: [...] - fail if not in range
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
      }

      return null;
    }

    @Override
    public Void visitGroup(GroupNode node) {
      // Generate parser for child
      generateParserMethod(cw, className, node.child);
      String childMethod = getMethodNameForNode(node.child);

      if (node.groupNumber > 0) {
        // Capturing group: record start/end positions
        int startIndex = node.groupNumber * 2;
        int endIndex = node.groupNumber * 2 + 1;

        // Call child parser
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 6); // result in slot 6 (slot 5 is depth parameter)

        // Check if child matched
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(ICONST_M1);
        Label childMatched = new Label();
        mv.visitJumpInsn(IF_ICMPNE, childMatched);

        // Child failed: return -1 immediately without setting groups
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);

        // Child succeeded: set group boundaries
        mv.visitLabel(childMatched);

        // Save start position: groups[startIndex] = pos
        mv.visitVarInsn(ALOAD, 4); // groups
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
        mv.visitVarInsn(ILOAD, 2); // pos (start position before child)
        mv.visitInsn(IASTORE);

        // Save end position: groups[endIndex] = result
        mv.visitVarInsn(ALOAD, 4); // groups
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
        mv.visitVarInsn(ILOAD, 6); // result (end position after child)
        mv.visitInsn(IASTORE);

        // Return result
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IRETURN);
      } else {
        // Non-capturing group: just call child parser
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitInsn(IRETURN);
      }

      return null;
    }

    @Override
    public Void visitQuantifier(QuantifierNode node) {
      // Generate parser for child
      generateParserMethod(cw, className, node.child);
      String childMethod = getMethodNameForNode(node.child);

      // Quantifier matching strategy:
      // - Greedy: match min required, then as many as possible up to max
      // - Non-greedy: match min required, then return immediately (prefer minimum)
      // Local variables: currentPos (slot 6), matchCount (slot 7), result (slot 8)
      // Slot 5 is depth parameter

      mv.visitVarInsn(ILOAD, 2); // currentPos = pos
      mv.visitVarInsn(ISTORE, 6);
      mv.visitInsn(ICONST_0); // matchCount = 0
      mv.visitVarInsn(ISTORE, 7);

      // Match minimum required times (same for both greedy and non-greedy)
      if (node.min > 0) {
        Label minLoopStart = new Label();
        Label minLoopEnd = new Label();

        mv.visitLabel(minLoopStart);
        // if (matchCount >= min) goto minLoopEnd
        mv.visitVarInsn(ILOAD, 7);
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, node.min);
        mv.visitJumpInsn(IF_ICMPGE, minLoopEnd);

        // Try to match child
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 6); // currentPos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 6); // currentPos = result

        // If failed, return -1
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(ICONST_M1);
        Label matchSuccess = new Label();
        mv.visitJumpInsn(IF_ICMPNE, matchSuccess);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(matchSuccess);
        // matchCount++
        mv.visitIincInsn(7, 1);
        mv.visitJumpInsn(GOTO, minLoopStart);

        mv.visitLabel(minLoopEnd);
      }

      // Match as many as possible up to max
      // For non-greedy quantifiers, the preference for fewer matches is handled
      // by generateConcatWithBacktracking when followed by more pattern elements.
      // When standalone or at the end of a pattern, always match max.
      // PCRE semantics: capturing groups should contain values from LAST iteration
      Label greedyLoopStart = new Label();
      Label greedyLoopEnd = new Label();

      mv.visitLabel(greedyLoopStart);
      // if (matchCount >= max) goto greedyLoopEnd
      // Note: max=-1 means unbounded in the AST
      if (node.max != -1 && node.max != Integer.MAX_VALUE) {
        mv.visitVarInsn(ILOAD, 7);
        com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, node.max);
        mv.visitJumpInsn(IF_ICMPGE, greedyLoopEnd);
      }

      // Try to match child
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 6); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 8); // Store result in temporary slot 8

      // If failed (-1), stop matching
      mv.visitVarInsn(ILOAD, 8); // Load result
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, greedyLoopEnd);

      // Success: check if we made progress
      // If result == currentPos (no progress), we matched empty string
      // Accept this match (groups already updated by child parser) but stop looping
      mv.visitVarInsn(ILOAD, 8); // Load result
      mv.visitVarInsn(ILOAD, 6); // Load currentPos
      Label madeProgress = new Label();
      mv.visitJumpInsn(IF_ICMPNE, madeProgress);

      // No progress (empty match): update currentPos and break
      // The child parser already updated the groups, so this is the "last iteration"
      mv.visitVarInsn(ILOAD, 8); // Load successful result
      mv.visitVarInsn(ISTORE, 6); // currentPos = result
      mv.visitJumpInsn(GOTO, greedyLoopEnd);

      mv.visitLabel(madeProgress);
      // Made progress: update currentPos, matchCount++, and continue
      mv.visitVarInsn(ILOAD, 8); // Load successful result
      mv.visitVarInsn(ISTORE, 6); // currentPos = result
      mv.visitIincInsn(7, 1); // matchCount++
      mv.visitJumpInsn(GOTO, greedyLoopStart);

      mv.visitLabel(greedyLoopEnd);

      // Return final position
      mv.visitVarInsn(ILOAD, 6);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitAlternation(AlternationNode node) {
      // Alternation: try each alternative in order
      // If one succeeds, return its result
      // If one fails, restore groups and try the next
      // Local variables: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
      // Additional locals: 6=savedGroups, 7=result

      // Save groups array before trying alternatives
      // int[] savedGroups = groups.clone()
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      mv.visitTypeInsn(CHECKCAST, "[I");
      mv.visitVarInsn(ASTORE, 6); // savedGroups in slot 6

      // Try each alternative
      for (int i = 0; i < node.alternatives.size(); i++) {
        RegexNode alt = node.alternatives.get(i);
        Label nextAlt = new Label();

        // Generate parser method for this alternative if needed
        generateParserMethod(cw, className, alt);
        String altMethod = getMethodNameForNode(alt);

        // Call alternative parser: result = parse_alt(input, pos, end, groups, depth)
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, altMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 7); // result in slot 7

        // If succeeded (result != -1), return result
        mv.visitVarInsn(ILOAD, 7);
        mv.visitInsn(ICONST_M1);
        mv.visitJumpInsn(IF_ICMPEQ, nextAlt); // If failed, try next alternative

        // Success: return result
        mv.visitVarInsn(ILOAD, 7);
        mv.visitInsn(IRETURN);

        // Failed: restore groups and try next
        mv.visitLabel(nextAlt);
        if (i < node.alternatives.size() - 1) {
          // Restore groups array: System.arraycopy(savedGroups, 0, groups, 0, groups.length)
          mv.visitVarInsn(ALOAD, 6); // savedGroups
          mv.visitInsn(ICONST_0); // srcPos
          mv.visitVarInsn(ALOAD, 4); // groups
          mv.visitInsn(ICONST_0); // destPos
          mv.visitVarInsn(ALOAD, 4); // groups
          mv.visitInsn(ARRAYLENGTH); // length
          mv.visitMethodInsn(
              INVOKESTATIC,
              "java/lang/System",
              "arraycopy",
              "(Ljava/lang/Object;ILjava/lang/Object;II)V",
              false);
        }
      }

      // All alternatives failed
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitConcat(ConcatNode node) {
      // Concatenation: match each child in sequence
      // With backtracking support for quantifiers followed by potentially failing children

      // Check if we need backtracking: do we have a quantifier followed by more children?
      // Both greedy and non-greedy quantifiers may need backtracking
      boolean needsBacktracking = false;
      int backtrackChildIndex = -1;

      for (int i = 0; i < node.children.size() - 1; i++) {
        RegexNode child = node.children.get(i);
        if (containsBacktrackingQuantifier(child)) {
          needsBacktracking = true;
          backtrackChildIndex = i;
          break; // For now, handle only the first backtracking quantifier
        }
      }

      if (!needsBacktracking) {
        // Simple case: no backtracking needed
        // Local variable 6: currentPos (slot 5 is depth parameter)
        mv.visitVarInsn(ILOAD, 2); // currentPos = pos
        mv.visitVarInsn(ISTORE, 6);

        // Match each child in sequence
        for (RegexNode child : node.children) {
          generateParserMethod(cw, className, child);
          String childMethod = getMethodNameForNode(child);
          Label successLabel = new Label();

          // Call child parser: currentPos = parse_child(input, currentPos, end, groups, depth)
          mv.visitVarInsn(ALOAD, 0); // this
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, 6); // currentPos
          mv.visitVarInsn(ILOAD, 3); // end
          mv.visitVarInsn(ALOAD, 4); // groups
          mv.visitVarInsn(ILOAD, 5); // depth
          mv.visitMethodInsn(
              INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
          mv.visitVarInsn(ISTORE, 6); // currentPos = result

          // If failed, return -1
          mv.visitVarInsn(ILOAD, 6);
          mv.visitInsn(ICONST_M1);
          mv.visitJumpInsn(IF_ICMPNE, successLabel);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IRETURN);

          mv.visitLabel(successLabel);
        }

        // All children matched: return final position
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IRETURN);
      } else {
        // Complex case: backtracking needed
        // Strategy: save groups before greedy child, try it, then try remaining children
        // If remaining children fail, restore groups and have greedy child backtrack
        generateConcatWithBacktracking(node, backtrackChildIndex);
      }

      return null;
    }

    /**
     * Check if a node contains a quantifier that might need backtracking. Both greedy and
     * non-greedy quantifiers need backtracking when followed by more children.
     */
    private boolean containsBacktrackingQuantifier(RegexNode node) {
      if (node instanceof QuantifierNode) {
        QuantifierNode q = (QuantifierNode) node;
        // Non-greedy quantifiers always need backtracking when followed by more children
        // because they return after minimum, and need to expand if rest fails
        if (!q.greedy && q.min != q.max) {
          return true;
        }
        // Greedy quantifiers need backtracking if they can match multiple times
        // ? (min=0, max=1) greedy doesn't need backtracking because it matches max first
        // + (min=1, max=-1) and * (min=0, max=-1) do need backtracking
        return q.min != q.max && (q.max == -1 || q.max > 1);
      }
      if (node instanceof GroupNode) {
        return containsBacktrackingQuantifier(((GroupNode) node).child);
      }
      return false;
    }

    /**
     * Generate bytecode to save groups array by cloning. Generates: savedGroups = groups.clone()
     *
     * @param groupsVar Local variable slot containing groups array
     * @param savedGroupsVar Local variable slot to store cloned array
     */
    private void generateGroupArraySave(int groupsVar, int savedGroupsVar) {
      mv.visitVarInsn(ALOAD, groupsVar); // groups
      mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      mv.visitTypeInsn(CHECKCAST, "[I");
      mv.visitVarInsn(ASTORE, savedGroupsVar); // savedGroups
    }

    /**
     * Generate bytecode to restore groups array from saved copy. Generates:
     * System.arraycopy(savedGroups, 0, groups, 0, savedGroups.length)
     *
     * @param savedGroupsVar Local variable slot containing saved groups array
     * @param groupsVar Local variable slot containing current groups array
     */
    private void generateGroupArrayRestore(int savedGroupsVar, int groupsVar) {
      mv.visitVarInsn(ALOAD, savedGroupsVar); // savedGroups (src)
      mv.visitInsn(ICONST_0); // srcPos = 0
      mv.visitVarInsn(ALOAD, groupsVar); // groups (dest)
      mv.visitInsn(ICONST_0); // destPos = 0
      mv.visitVarInsn(ALOAD, savedGroupsVar); // savedGroups
      mv.visitInsn(ARRAYLENGTH); // length = savedGroups.length
      mv.visitMethodInsn(
          INVOKESTATIC,
          "java/lang/System",
          "arraycopy",
          "(Ljava/lang/Object;ILjava/lang/Object;II)V",
          false);
    }

    /**
     * Generate concat with backtracking for quantifiers (both greedy and non-greedy).
     *
     * <p>Greedy quantifiers (a+b): Start from maxMatches, decrement to min on failure. Non-greedy
     * quantifiers (a+?b): Start from min, increment to maxMatches on failure.
     */
    private void generateConcatWithBacktracking(ConcatNode node, int backtrackChildIndex) {
      // Local variable allocation:
      // slot 5: currentPos
      // slot 6: savedGroups (int[])
      // slot 7: quantifierStartPos
      // slot 8: maxMatches (greedy match count)
      // slot 9: tryMatchCount (backtracking loop variable)
      // slot 10: matchCount (for quantifier matching loop)
      // slot 11: result (temporary for method calls)
      // slot 14: greedyIterations (BacktrackConfig limit tracking)
      // slot 15: backtrackIterations (BacktrackConfig limit tracking)

      // Initialize currentPos
      mv.visitVarInsn(ILOAD, 2); // currentPos = pos
      mv.visitVarInsn(ISTORE, 5);

      // Process children before the backtracking point
      for (int i = 0; i < backtrackChildIndex; i++) {
        RegexNode child = node.children.get(i);
        generateParserMethod(cw, className, child);
        String childMethod = getMethodNameForNode(child);
        Label successLabel = new Label();

        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 5); // currentPos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 5); // currentPos = result

        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(ICONST_M1);
        mv.visitJumpInsn(IF_ICMPNE, successLabel);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(successLabel);
      }

      // Save groups array AFTER matching children before the backtracking point
      // This preserves any group captures set by those children during backtracking
      generateGroupArraySave(4, 6); // savedGroups = groups.clone()

      // Save position before quantifier
      mv.visitVarInsn(ILOAD, 5); // quantifierStartPos = currentPos
      mv.visitVarInsn(ISTORE, 7);

      // Initialize last iteration tracking (slots 12, 13)
      // For POSIX last-match semantics: capture positions from last successful iteration
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, 12); // lastIterationStart = -1
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, 13); // lastIterationEnd = -1

      // Get the quantifier node and extract parameters
      RegexNode greedyChild = node.children.get(backtrackChildIndex);
      QuantifierNode quantNode = extractQuantifier(greedyChild);

      // Check if this is a capturing group (we need to set group boundaries)
      int captureGroupNumber = -1;
      if (greedyChild instanceof GroupNode) {
        GroupNode groupNode = (GroupNode) greedyChild;
        if (groupNode.groupNumber > 0) {
          captureGroupNumber = groupNode.groupNumber;
        }
      }

      if (quantNode == null) {
        // Not a quantifier, fall back to simple concat
        generateSimpleConcat(node, backtrackChildIndex);
        return;
      }

      // Generate parser for quantifier's child
      generateParserMethod(cw, className, quantNode.child);
      String quantChildMethod = getMethodNameForNode(quantNode.child);

      // First, do a greedy match to find maxMatches
      // matchCount = 0 (slot 10)
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, 10);

      // Initialize greedy iteration counter (slot 14)
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, 14);

      // Match greedily
      Label greedyLoop = new Label();
      Label greedyEnd = new Label();

      mv.visitLabel(greedyLoop);

      // Check backtrack limit to prevent infinite loops
      mv.visitVarInsn(ILOAD, 14);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/BacktrackConfig",
          "checkLimit",
          "(I)Z",
          false);
      Label greedyLimitOk = new Label();
      mv.visitJumpInsn(IFEQ, greedyLimitOk);
      // Limit exceeded: stop greedy matching
      mv.visitJumpInsn(GOTO, greedyEnd);
      mv.visitLabel(greedyLimitOk);
      mv.visitIincInsn(14, 1); // greedyIterations++

      // Check max limit
      if (quantNode.max != -1 && quantNode.max != Integer.MAX_VALUE) {
        mv.visitVarInsn(ILOAD, 10);
        BytecodeUtil.pushInt(mv, quantNode.max);
        mv.visitJumpInsn(IF_ICMPGE, greedyEnd);
      }

      // Try to match
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5); // currentPos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, quantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 11); // result in slot 11

      // If failed, stop
      mv.visitVarInsn(ILOAD, 11);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, greedyEnd);

      // Check for empty match (no progress) - prevents infinite loop on (a?)+
      mv.visitVarInsn(ILOAD, 11); // result
      mv.visitVarInsn(ILOAD, 5); // currentPos (before update)
      Label madeProgressGreedy = new Label();
      mv.visitJumpInsn(IF_ICMPNE, madeProgressGreedy);
      // Empty match: count it but stop looping
      mv.visitIincInsn(10, 1); // matchCount++ for this empty match
      mv.visitJumpInsn(GOTO, greedyEnd);

      mv.visitLabel(madeProgressGreedy);
      // Success: update currentPos and matchCount
      mv.visitVarInsn(ILOAD, 11);
      mv.visitVarInsn(ISTORE, 5); // currentPos = result
      mv.visitIincInsn(10, 1); // matchCount++
      mv.visitJumpInsn(GOTO, greedyLoop);

      mv.visitLabel(greedyEnd);

      // Check if we matched minimum required
      if (quantNode.min > 0) {
        Label minOk = new Label();
        mv.visitVarInsn(ILOAD, 10); // matchCount
        BytecodeUtil.pushInt(mv, quantNode.min);
        mv.visitJumpInsn(IF_ICMPGE, minOk);
        // Failed to match minimum
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(minOk);
      }

      // Save maxMatches (slot 8)
      mv.visitVarInsn(ILOAD, 10);
      mv.visitVarInsn(ISTORE, 8);

      // Now try rest of pattern with backtracking
      // Greedy: tryMatchCount = maxMatches, decrement on failure
      // Non-greedy: tryMatchCount = min, increment on failure
      if (quantNode.greedy) {
        mv.visitVarInsn(ILOAD, 8); // tryMatchCount = maxMatches
      } else {
        BytecodeUtil.pushInt(mv, quantNode.min); // tryMatchCount = min
      }
      mv.visitVarInsn(ISTORE, 9);

      // Initialize backtrack iteration counter (slot 15)
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, 15);

      Label backtrackLoop = new Label();
      Label backtrackEnd = new Label();

      mv.visitLabel(backtrackLoop);

      // Check backtrack limit to prevent infinite backtracking
      mv.visitVarInsn(ILOAD, 15);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/BacktrackConfig",
          "checkLimit",
          "(I)Z",
          false);
      Label backtrackLimitOk = new Label();
      mv.visitJumpInsn(IFEQ, backtrackLimitOk);
      // Limit exceeded: give up and return failure
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(backtrackLimitOk);
      mv.visitIincInsn(15, 1); // backtrackIterations++

      // Check loop termination:
      // Greedy: exit when tryMatchCount < min
      // Non-greedy: exit when tryMatchCount > maxMatches
      mv.visitVarInsn(ILOAD, 9);
      if (quantNode.greedy) {
        BytecodeUtil.pushInt(mv, quantNode.min);
        mv.visitJumpInsn(IF_ICMPLT, backtrackEnd);
      } else {
        mv.visitVarInsn(ILOAD, 8); // maxMatches
        mv.visitJumpInsn(IF_ICMPGT, backtrackEnd);
      }

      // Restore groups by copying contents (not replacing reference)
      generateGroupArrayRestore(
          6, 4); // System.arraycopy(savedGroups, 0, groups, 0, savedGroups.length)

      // Reset position
      mv.visitVarInsn(ILOAD, 7); // quantifierStartPos
      mv.visitVarInsn(ISTORE, 5); // currentPos = quantifierStartPos

      // Match quantifier exactly tryMatchCount times
      // matchCount = 0
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, 10);

      Label matchLoop = new Label();
      Label matchEnd = new Label();

      mv.visitLabel(matchLoop);

      // if (matchCount >= tryMatchCount) goto matchEnd
      mv.visitVarInsn(ILOAD, 10);
      mv.visitVarInsn(ILOAD, 9);
      mv.visitJumpInsn(IF_ICMPGE, matchEnd);

      // Save iteration start position (for POSIX last-match semantics)
      mv.visitVarInsn(ILOAD, 5); // currentPos
      mv.visitVarInsn(ISTORE, 12); // lastIterationStart = currentPos

      // Try to match
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ILOAD, 3);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitMethodInsn(
          INVOKESPECIAL, className, quantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 11);

      // If match failed, this tryMatchCount won't work - backtrack immediately
      mv.visitVarInsn(ILOAD, 11);
      mv.visitInsn(ICONST_M1);
      Label matchSuccess = new Label();
      mv.visitJumpInsn(IF_ICMPNE, matchSuccess);
      // Failed - adjust tryMatchCount: decrement for greedy, increment for non-greedy
      mv.visitIincInsn(9, quantNode.greedy ? -1 : 1);
      mv.visitJumpInsn(GOTO, backtrackLoop);

      mv.visitLabel(matchSuccess);
      // Save iteration end position (for POSIX last-match semantics)
      mv.visitVarInsn(ILOAD, 11); // result position
      mv.visitVarInsn(ISTORE, 13); // lastIterationEnd = result

      // Update position and count
      mv.visitVarInsn(ILOAD, 11);
      mv.visitVarInsn(ISTORE, 5);
      mv.visitIincInsn(10, 1);
      mv.visitJumpInsn(GOTO, matchLoop);

      mv.visitLabel(matchEnd);

      // If this is a capturing group, set boundaries for the ENTIRE quantifier match.
      // For pattern (a+), group 1 should capture "aaa", not just the last "a".
      // The quantifier is INSIDE the group, so we capture from quantifierStartPos to currentPos.
      // Note: POSIX last-match semantics ((a)+ capturing last 'a') is for quantifiers OUTSIDE the
      // group,
      // which is a separate issue handled in GroupCaptureLastMatchTest.
      // groups[captureGroupNumber * 2] = quantifierStartPos
      // groups[captureGroupNumber * 2 + 1] = currentPos
      if (captureGroupNumber > 0) {
        // Set group start from quantifier start position
        // S: [] -> [A:groups, I:index, I:quantifierStartPos]
        mv.visitVarInsn(ALOAD, 4); // groups
        BytecodeUtil.pushInt(mv, captureGroupNumber * 2);
        mv.visitVarInsn(ILOAD, 7); // quantifierStartPos
        // S: [A:groups, I:index, I:quantifierStartPos] -> []
        mv.visitInsn(IASTORE);

        // Set group end from current position (after all iterations)
        // S: [] -> [A:groups, I:index, I:currentPos]
        mv.visitVarInsn(ALOAD, 4); // groups
        BytecodeUtil.pushInt(mv, captureGroupNumber * 2 + 1);
        mv.visitVarInsn(ILOAD, 5); // currentPos (after quantifier matched)
        // S: [A:groups, I:index, I:currentPos] -> []
        mv.visitInsn(IASTORE);
      }

      // Now try remaining children
      // We need to try all of them, and if any fails, backtrack
      // IMPORTANT: Remaining children may also contain backtracking quantifiers
      // that need their own nested backtracking loops
      Label tryRemainingChildren = new Label();
      mv.visitLabel(tryRemainingChildren);

      // Check if remaining children need nested backtracking
      int nestedBacktrackIndex = -1;
      for (int i = backtrackChildIndex + 1; i < node.children.size() - 1; i++) {
        if (containsBacktrackingQuantifier(node.children.get(i))) {
          nestedBacktrackIndex = i;
          break;
        }
      }

      if (nestedBacktrackIndex == -1) {
        // Simple case: no nested backtracking needed
        for (int i = backtrackChildIndex + 1; i < node.children.size(); i++) {
          RegexNode child = node.children.get(i);
          generateParserMethod(cw, className, child);
          String childMethod = getMethodNameForNode(child);

          mv.visitVarInsn(ALOAD, 0);
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitVarInsn(ILOAD, 3);
          mv.visitVarInsn(ALOAD, 4);
          mv.visitVarInsn(ILOAD, 5); // depth
          mv.visitMethodInsn(
              INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
          mv.visitVarInsn(ISTORE, 5);

          // If this child failed, backtrack
          mv.visitVarInsn(ILOAD, 5);
          mv.visitInsn(ICONST_M1);
          Label childSucceeded = new Label();
          mv.visitJumpInsn(IF_ICMPNE, childSucceeded);

          // Failed: adjust tryMatchCount and try again
          mv.visitIincInsn(9, quantNode.greedy ? -1 : 1);
          mv.visitJumpInsn(GOTO, backtrackLoop);

          mv.visitLabel(childSucceeded);
        }
      } else {
        // Complex case: nested backtracking needed
        // Process children before the nested backtrack point
        for (int i = backtrackChildIndex + 1; i < nestedBacktrackIndex; i++) {
          RegexNode child = node.children.get(i);
          generateParserMethod(cw, className, child);
          String childMethod = getMethodNameForNode(child);

          mv.visitVarInsn(ALOAD, 0);
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitVarInsn(ILOAD, 3);
          mv.visitVarInsn(ALOAD, 4);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitMethodInsn(
              INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
          mv.visitVarInsn(ISTORE, 5);

          mv.visitVarInsn(ILOAD, 5);
          mv.visitInsn(ICONST_M1);
          Label childSucceeded = new Label();
          mv.visitJumpInsn(IF_ICMPNE, childSucceeded);
          mv.visitIincInsn(9, quantNode.greedy ? -1 : 1);
          mv.visitJumpInsn(GOTO, backtrackLoop);
          mv.visitLabel(childSucceeded);
        }

        // Generate nested backtracking for the remaining children
        // Use slots 16-21 for nested backtracking (avoiding conflict with outer slots 5-15)
        generateNestedBacktracking(
            node, nestedBacktrackIndex, backtrackLoop, quantNode.greedy ? -1 : 1, 9, 16);
      }

      // All remaining children succeeded
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(IRETURN);

      mv.visitLabel(backtrackEnd);
      // Exhausted all backtrack attempts
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    /**
     * Generate nested backtracking for a quantifier at nestedIndex within remaining children. Uses
     * dynamically allocated slots to support multiple nesting levels.
     *
     * @param node The concat node containing all children
     * @param nestedIndex Index of the backtracking quantifier to handle
     * @param outerBacktrackLoop Label to jump to when this level exhausts backtracking
     * @param outerBacktrackDelta Amount to adjust outer tryMatchCount slot before jumping
     * @param outerTryMatchCountSlot Slot containing outer level's tryMatchCount (for adjustment)
     * @param slotBase Base slot for this nesting level (16, 22, 28, etc.)
     */
    private void generateNestedBacktracking(
        ConcatNode node,
        int nestedIndex,
        Label outerBacktrackLoop,
        int outerBacktrackDelta,
        int outerTryMatchCountSlot,
        int slotBase) {
      // Slots for this level's nested backtracking (relative to slotBase):
      // slotBase + 0: nestedSavedGroups
      // slotBase + 1: nestedQuantifierStartPos
      // slotBase + 2: nestedMaxMatches
      // slotBase + 3: nestedTryMatchCount
      // slotBase + 4: nestedMatchCount
      // slotBase + 5: nestedResult
      final int SAVED_GROUPS_SLOT = slotBase;
      final int QUANT_START_POS_SLOT = slotBase + 1;
      final int MAX_MATCHES_SLOT = slotBase + 2;
      final int TRY_MATCH_COUNT_SLOT = slotBase + 3;
      final int MATCH_COUNT_SLOT = slotBase + 4;
      final int RESULT_SLOT = slotBase + 5;

      RegexNode nestedChild = node.children.get(nestedIndex);
      QuantifierNode nestedQuantNode = extractQuantifier(nestedChild);

      if (nestedQuantNode == null) {
        // Not a quantifier - process normally and continue
        generateParserMethod(cw, className, nestedChild);
        String childMethod = getMethodNameForNode(nestedChild);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 5);

        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(ICONST_M1);
        Label ok = new Label();
        mv.visitJumpInsn(IF_ICMPNE, ok);
        mv.visitIincInsn(outerTryMatchCountSlot, outerBacktrackDelta);
        mv.visitJumpInsn(GOTO, outerBacktrackLoop);
        mv.visitLabel(ok);

        // Process rest
        for (int i = nestedIndex + 1; i < node.children.size(); i++) {
          RegexNode child = node.children.get(i);
          generateParserMethod(cw, className, child);
          String method = getMethodNameForNode(child);
          mv.visitVarInsn(ALOAD, 0);
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitVarInsn(ILOAD, 3);
          mv.visitVarInsn(ALOAD, 4);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitMethodInsn(INVOKESPECIAL, className, method, "(Ljava/lang/String;II[II)I", false);
          mv.visitVarInsn(ISTORE, 5);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitInsn(ICONST_M1);
          Label ok2 = new Label();
          mv.visitJumpInsn(IF_ICMPNE, ok2);
          mv.visitIincInsn(outerTryMatchCountSlot, outerBacktrackDelta);
          mv.visitJumpInsn(GOTO, outerBacktrackLoop);
          mv.visitLabel(ok2);
        }
        return;
      }

      // Check if nested child is a capturing group
      int nestedCaptureGroupNumber = -1;
      if (nestedChild instanceof GroupNode) {
        GroupNode groupNode = (GroupNode) nestedChild;
        if (groupNode.groupNumber > 0) {
          nestedCaptureGroupNumber = groupNode.groupNumber;
        }
      }

      // Save groups for nested backtracking
      generateGroupArraySave(4, SAVED_GROUPS_SLOT);

      // Save position before nested quantifier
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ISTORE, QUANT_START_POS_SLOT);

      // Generate parser for nested quantifier's child
      generateParserMethod(cw, className, nestedQuantNode.child);
      String nestedQuantChildMethod = getMethodNameForNode(nestedQuantNode.child);

      // Greedy match for nested quantifier
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, MATCH_COUNT_SLOT); // nestedMatchCount = 0

      Label nestedGreedyLoop = new Label();
      Label nestedGreedyEnd = new Label();

      mv.visitLabel(nestedGreedyLoop);

      // Check max limit
      if (nestedQuantNode.max != -1 && nestedQuantNode.max != Integer.MAX_VALUE) {
        mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
        BytecodeUtil.pushInt(mv, nestedQuantNode.max);
        mv.visitJumpInsn(IF_ICMPGE, nestedGreedyEnd);
      }

      // Try to match
      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ILOAD, 3);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitMethodInsn(
          INVOKESPECIAL, className, nestedQuantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, RESULT_SLOT);

      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, nestedGreedyEnd);

      // Check for empty match
      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitVarInsn(ILOAD, 5);
      Label nestedMadeProgress = new Label();
      mv.visitJumpInsn(IF_ICMPNE, nestedMadeProgress);
      mv.visitIincInsn(MATCH_COUNT_SLOT, 1);
      mv.visitJumpInsn(GOTO, nestedGreedyEnd);

      mv.visitLabel(nestedMadeProgress);
      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitVarInsn(ISTORE, 5);
      mv.visitIincInsn(MATCH_COUNT_SLOT, 1);
      mv.visitJumpInsn(GOTO, nestedGreedyLoop);

      mv.visitLabel(nestedGreedyEnd);

      // Check minimum
      if (nestedQuantNode.min > 0) {
        Label nestedMinOk = new Label();
        mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
        BytecodeUtil.pushInt(mv, nestedQuantNode.min);
        mv.visitJumpInsn(IF_ICMPGE, nestedMinOk);
        // Failed minimum - backtrack outer
        mv.visitIincInsn(outerTryMatchCountSlot, outerBacktrackDelta);
        mv.visitJumpInsn(GOTO, outerBacktrackLoop);
        mv.visitLabel(nestedMinOk);
      }

      // Save maxMatches
      mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
      mv.visitVarInsn(ISTORE, MAX_MATCHES_SLOT);

      // Initialize tryMatchCount
      if (nestedQuantNode.greedy) {
        mv.visitVarInsn(ILOAD, MAX_MATCHES_SLOT);
      } else {
        BytecodeUtil.pushInt(mv, nestedQuantNode.min);
      }
      mv.visitVarInsn(ISTORE, TRY_MATCH_COUNT_SLOT);

      Label nestedBacktrackLoop = new Label();
      Label nestedBacktrackEnd = new Label();

      mv.visitLabel(nestedBacktrackLoop);

      // Check loop termination
      mv.visitVarInsn(ILOAD, TRY_MATCH_COUNT_SLOT);
      if (nestedQuantNode.greedy) {
        BytecodeUtil.pushInt(mv, nestedQuantNode.min);
        mv.visitJumpInsn(IF_ICMPLT, nestedBacktrackEnd);
      } else {
        mv.visitVarInsn(ILOAD, MAX_MATCHES_SLOT);
        mv.visitJumpInsn(IF_ICMPGT, nestedBacktrackEnd);
      }

      // Restore groups
      generateGroupArrayRestore(SAVED_GROUPS_SLOT, 4);

      // Reset position
      mv.visitVarInsn(ILOAD, QUANT_START_POS_SLOT);
      mv.visitVarInsn(ISTORE, 5);

      // Match exactly tryMatchCount times
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, MATCH_COUNT_SLOT);

      Label nestedMatchLoop = new Label();
      Label nestedMatchEnd = new Label();

      mv.visitLabel(nestedMatchLoop);
      mv.visitVarInsn(ILOAD, MATCH_COUNT_SLOT);
      mv.visitVarInsn(ILOAD, TRY_MATCH_COUNT_SLOT);
      mv.visitJumpInsn(IF_ICMPGE, nestedMatchEnd);

      mv.visitVarInsn(ALOAD, 0);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitVarInsn(ILOAD, 3);
      mv.visitVarInsn(ALOAD, 4);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitMethodInsn(
          INVOKESPECIAL, className, nestedQuantChildMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, RESULT_SLOT);

      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitInsn(ICONST_M1);
      Label nestedMatchSuccess = new Label();
      mv.visitJumpInsn(IF_ICMPNE, nestedMatchSuccess);
      mv.visitIincInsn(TRY_MATCH_COUNT_SLOT, nestedQuantNode.greedy ? -1 : 1);
      mv.visitJumpInsn(GOTO, nestedBacktrackLoop);

      mv.visitLabel(nestedMatchSuccess);
      mv.visitVarInsn(ILOAD, RESULT_SLOT);
      mv.visitVarInsn(ISTORE, 5);
      mv.visitIincInsn(MATCH_COUNT_SLOT, 1);
      mv.visitJumpInsn(GOTO, nestedMatchLoop);

      mv.visitLabel(nestedMatchEnd);

      // Set group boundaries if capturing
      if (nestedCaptureGroupNumber > 0) {
        mv.visitVarInsn(ALOAD, 4);
        BytecodeUtil.pushInt(mv, nestedCaptureGroupNumber * 2);
        mv.visitVarInsn(ILOAD, QUANT_START_POS_SLOT);
        mv.visitInsn(IASTORE);

        mv.visitVarInsn(ALOAD, 4);
        BytecodeUtil.pushInt(mv, nestedCaptureGroupNumber * 2 + 1);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(IASTORE);
      }

      // Try remaining children after nested quantifier
      // Check if there's ANOTHER backtracking quantifier among remaining children
      int deeperNestedIndex = -1;
      for (int i = nestedIndex + 1; i < node.children.size() - 1; i++) {
        if (containsBacktrackingQuantifier(node.children.get(i))) {
          deeperNestedIndex = i;
          break;
        }
      }

      if (deeperNestedIndex == -1) {
        // No more nested backtracking needed - simple iteration
        for (int i = nestedIndex + 1; i < node.children.size(); i++) {
          RegexNode child = node.children.get(i);
          generateParserMethod(cw, className, child);
          String childMethod = getMethodNameForNode(child);

          mv.visitVarInsn(ALOAD, 0);
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitVarInsn(ILOAD, 3);
          mv.visitVarInsn(ALOAD, 4);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitMethodInsn(
              INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
          mv.visitVarInsn(ISTORE, 5);

          mv.visitVarInsn(ILOAD, 5);
          mv.visitInsn(ICONST_M1);
          Label nestedChildSucceeded = new Label();
          mv.visitJumpInsn(IF_ICMPNE, nestedChildSucceeded);

          // Failed - backtrack nested quantifier
          mv.visitIincInsn(TRY_MATCH_COUNT_SLOT, nestedQuantNode.greedy ? -1 : 1);
          mv.visitJumpInsn(GOTO, nestedBacktrackLoop);

          mv.visitLabel(nestedChildSucceeded);
        }
      } else {
        // MORE nested backtracking needed - process children before deeper nested, then recurse
        for (int i = nestedIndex + 1; i < deeperNestedIndex; i++) {
          RegexNode child = node.children.get(i);
          generateParserMethod(cw, className, child);
          String childMethod = getMethodNameForNode(child);

          mv.visitVarInsn(ALOAD, 0);
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitVarInsn(ILOAD, 3);
          mv.visitVarInsn(ALOAD, 4);
          mv.visitVarInsn(ILOAD, 5);
          mv.visitMethodInsn(
              INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
          mv.visitVarInsn(ISTORE, 5);

          mv.visitVarInsn(ILOAD, 5);
          mv.visitInsn(ICONST_M1);
          Label childOk = new Label();
          mv.visitJumpInsn(IF_ICMPNE, childOk);
          mv.visitIincInsn(TRY_MATCH_COUNT_SLOT, nestedQuantNode.greedy ? -1 : 1);
          mv.visitJumpInsn(GOTO, nestedBacktrackLoop);
          mv.visitLabel(childOk);
        }

        // Recursively generate deeper nested backtracking
        // Use slots slotBase + 6 for next level
        generateNestedBacktracking(
            node,
            deeperNestedIndex,
            nestedBacktrackLoop,
            nestedQuantNode.greedy ? -1 : 1,
            TRY_MATCH_COUNT_SLOT,
            slotBase + 6);
      }

      // All children after nested quantifier succeeded - return success (handled by caller)
      // Jump past nestedBacktrackEnd since we succeeded
      Label nestedSuccess = new Label();
      mv.visitJumpInsn(GOTO, nestedSuccess);

      mv.visitLabel(nestedBacktrackEnd);
      // Nested backtracking exhausted - backtrack outer
      mv.visitIincInsn(outerTryMatchCountSlot, outerBacktrackDelta);
      mv.visitJumpInsn(GOTO, outerBacktrackLoop);

      mv.visitLabel(nestedSuccess);
      // Success path continues in caller
    }

    /** Extract QuantifierNode from a node, handling GroupNode wrappers. */
    private QuantifierNode extractQuantifier(RegexNode node) {
      if (node instanceof QuantifierNode) {
        return (QuantifierNode) node;
      }
      if (node instanceof GroupNode) {
        GroupNode groupNode = (GroupNode) node;
        if (groupNode.child instanceof QuantifierNode) {
          return (QuantifierNode) groupNode.child;
        }
      }
      return null;
    }

    /** Generate simple concat code when node is not a quantifier. */
    private void generateSimpleConcat(ConcatNode node, int startIndex) {
      for (int i = startIndex; i < node.children.size(); i++) {
        RegexNode child = node.children.get(i);
        generateParserMethod(cw, className, child);
        String childMethod = getMethodNameForNode(child);

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 5); // currentPos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, childMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 5);

        Label success = new Label();
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(ICONST_M1);
        mv.visitJumpInsn(IF_ICMPNE, success);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(success);
      }

      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(IRETURN);
    }

    @Override
    public Void visitAnchor(AnchorNode node) {
      // Anchors: check position constraints
      if (node.type == AnchorNode.Type.START || node.type == AnchorNode.Type.STRING_START) {
        // ^ or \A: must be at start of input
        // if (pos != 0) return -1;
        // S: []
        mv.visitVarInsn(ILOAD, 2); // pos
        // S: [I]
        Label atStart = new Label();
        mv.visitJumpInsn(IFEQ, atStart);
        // S: []
        mv.visitInsn(ICONST_M1);
        // S: [I]
        mv.visitInsn(IRETURN);
        mv.visitLabel(atStart);
        // S: []
        // Return same position (anchor consumes no characters)
        mv.visitVarInsn(ILOAD, 2);
        // S: [I]
        mv.visitInsn(IRETURN);
      } else if (node.type == AnchorNode.Type.END
          || node.type == AnchorNode.Type.STRING_END
          || node.type == AnchorNode.Type.STRING_END_ABSOLUTE) {
        // $ or \Z or \z: must be at end of input
        // if (pos != end) return -1;
        // S: []
        mv.visitVarInsn(ILOAD, 2); // pos
        // S: [I]
        mv.visitVarInsn(ILOAD, 3); // end
        // S: [I, I]
        Label atEnd = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, atEnd);
        // S: []
        mv.visitInsn(ICONST_M1);
        // S: [I]
        mv.visitInsn(IRETURN);
        mv.visitLabel(atEnd);
        // S: []
        // Return same position
        mv.visitVarInsn(ILOAD, 2);
        // S: [I]
        mv.visitInsn(IRETURN);
      }

      // Unsupported anchor type (WORD_BOUNDARY)
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      return null;
    }

    @Override
    public Void visitAssertion(AssertionNode node) {
      // Assertions: zero-width checks (lookahead, lookbehind)
      // For simplicity, not fully implemented yet
      // Just fail for now
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      return null;
    }

    @Override
    public Void visitBackreference(BackreferenceNode node) {
      // Backreference: \1, \2, etc.
      // Check if the referenced group has been captured
      // If not, fail immediately

      // Implementation for backtracking-based backreference matching
      // This is a simplified version that handles the general case

      int groupNumber = node.groupNumber;
      int startIndex = groupNumber * 2;
      int endIndex = groupNumber * 2 + 1;

      // Defensive bounds checking: ensure startIndex and endIndex are valid
      // if (startIndex < 0 || startIndex >= groups.length) return -1;
      Label startIndexBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(ICONST_0);
      mv.visitJumpInsn(IF_ICMPGE, startIndexBoundsOk); // if startIndex >= 0, continue checking
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(startIndexBoundsOk);

      Label startIndexUpperBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitInsn(ARRAYLENGTH);
      mv.visitJumpInsn(IF_ICMPLT, startIndexUpperBoundsOk); // if startIndex < groups.length, ok
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(startIndexUpperBoundsOk);

      // if (endIndex < 0 || endIndex >= groups.length) return -1;
      Label endIndexBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
      mv.visitInsn(ICONST_0);
      mv.visitJumpInsn(IF_ICMPGE, endIndexBoundsOk); // if endIndex >= 0, continue checking
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(endIndexBoundsOk);

      Label endIndexUpperBoundsOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitInsn(ARRAYLENGTH);
      mv.visitJumpInsn(IF_ICMPLT, endIndexUpperBoundsOk); // if endIndex < groups.length, ok
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(endIndexUpperBoundsOk);

      // Check if group has been captured
      // PCRE semantics: An uncaptured backreference matches an empty string (0 chars)
      // This enables self-referencing patterns like (a\1?){4}
      Label groupCaptured = new Label();
      mv.visitVarInsn(ALOAD, 4); // groups
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(IALOAD);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPNE, groupCaptured);

      // Group not captured - match empty string (return pos unchanged)
      // This allows self-referencing backreferences to work on first iteration
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitInsn(IRETURN);

      mv.visitLabel(groupCaptured);

      // Get group boundaries
      // int groupStart = groups[startIndex];
      mv.visitVarInsn(ALOAD, 4);
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(IALOAD);
      mv.visitVarInsn(ISTORE, 5); // groupStart

      // int groupEnd = groups[endIndex];
      mv.visitVarInsn(ALOAD, 4);
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, endIndex);
      mv.visitInsn(IALOAD);
      mv.visitVarInsn(ISTORE, 6); // groupEnd

      // int groupLen = groupEnd - groupStart;
      mv.visitVarInsn(ILOAD, 6);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(ISUB);
      mv.visitVarInsn(ISTORE, 7); // groupLen

      // Check if we have enough characters left: if (pos + groupLen > end) return -1;
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 7); // groupLen
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, 3); // end
      Label lengthOk = new Label();
      mv.visitJumpInsn(IF_ICMPLE, lengthOk);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(lengthOk);

      // Compare characters: for (int i = 0; i < groupLen; i++)
      //   if (input.charAt(pos + i) != input.charAt(groupStart + i)) return -1;
      Label loopStart = new Label();
      Label loopEnd = new Label();
      mv.visitInsn(ICONST_0); // i = 0
      mv.visitVarInsn(ISTORE, 8); // slot 8 for i

      mv.visitLabel(loopStart);
      mv.visitVarInsn(ILOAD, 8); // i
      mv.visitVarInsn(ILOAD, 7); // groupLen
      mv.visitJumpInsn(IF_ICMPGE, loopEnd); // if i >= groupLen, exit loop

      // char c1 = input.charAt(pos + i)
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 8); // i
      mv.visitInsn(IADD);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // char c2 = input.charAt(groupStart + i)
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 5); // groupStart
      mv.visitVarInsn(ILOAD, 8); // i
      mv.visitInsn(IADD);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // if (c1 != c2) return -1
      Label charsMatch = new Label();
      mv.visitJumpInsn(IF_ICMPEQ, charsMatch);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      mv.visitLabel(charsMatch);
      mv.visitIincInsn(8, 1); // i++
      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(loopEnd);

      // Success: return pos + groupLen
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 7); // groupLen
      mv.visitInsn(IADD);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitSubroutine(SubroutineNode node) {
      // Subroutine call: (?R), (?1), (?&name)
      // Find the target node and call its parser

      RegexNode targetNode;
      if (node.groupNumber == -1) {
        // (?R): call root pattern (recursive call to entire pattern)
        targetNode = ast;
      } else if (node.groupNumber >= 0) {
        // (?1), (?2), etc.: call group by number
        RegexNode groupNode = groupNumberToNode.get(node.groupNumber);
        if (groupNode == null) {
          // Group not found, fail
          // S: []
          mv.visitInsn(ICONST_M1);
          // S: [I]
          mv.visitInsn(IRETURN);
          return null;
        }
        // PCRE semantics: subroutine calls match the pattern but do NOT capture.
        // Use the group's child (the pattern inside) instead of the group itself.
        // This prevents the subroutine from overwriting the original group capture.
        if (groupNode instanceof GroupNode) {
          targetNode = ((GroupNode) groupNode).child;
        } else {
          targetNode = groupNode;
        }
      } else if (node.name != null) {
        // (?&name): named group not supported yet
        // S: []
        mv.visitInsn(ICONST_M1);
        // S: [I]
        mv.visitInsn(IRETURN);
        return null;
      } else {
        // Invalid subroutine node
        // S: []
        mv.visitInsn(ICONST_M1);
        // S: [I]
        mv.visitInsn(IRETURN);
        return null;
      }

      // Check recursion depth limit
      // if (depth >= MAX_RECURSION_DEPTH) throw new StackOverflowError()
      mv.visitVarInsn(ILOAD, 5); // depth
      BytecodeUtil.pushInt(mv, MAX_RECURSION_DEPTH);
      Label depthOk = new Label();
      mv.visitJumpInsn(IF_ICMPLT, depthOk);

      // Throw StackOverflowError
      mv.visitTypeInsn(NEW, "java/lang/StackOverflowError");
      mv.visitInsn(DUP);
      mv.visitLdcInsn("Recursion depth limit exceeded: " + MAX_RECURSION_DEPTH);
      mv.visitMethodInsn(
          INVOKESPECIAL, "java/lang/StackOverflowError", "<init>", "(Ljava/lang/String;)V", false);
      mv.visitInsn(ATHROW);

      mv.visitLabel(depthOk);

      // Generate parser for target node and call it
      // Note: AST nodes no longer have parent references, so we cannot detect
      // if this subroutine is inside a quantifier at bytecode generation time.
      // The quantifier visitor will handle calling the subroutine parser if needed.
      generateParserMethod(cw, className, targetNode);
      String targetMethod = getMethodNameForNode(targetNode);

      // PCRE semantics: subroutine calls match the pattern but do NOT modify group captures.
      // Save groups before call and restore after to preserve original captures.
      // Local vars: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
      // Additional locals: 16=savedGroups, 17=result
      // NOTE: Using slots 16-17 to avoid conflicts with concat (uses slot 6) and backtracking (uses
      // slots 6-15)

      // Save groups array: int[] savedGroups = groups.clone()
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      mv.visitTypeInsn(CHECKCAST, "[I");
      mv.visitVarInsn(ASTORE, 16); // savedGroups in slot 16

      // Call target parser with depth+1: result = parse_target(input, pos, end, groups, depth+1)
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD); // depth + 1
      mv.visitMethodInsn(
          INVOKESPECIAL, className, targetMethod, "(Ljava/lang/String;II[II)I", false);
      mv.visitVarInsn(ISTORE, 17); // result in slot 17

      // Restore groups array: System.arraycopy(savedGroups, 0, groups, 0, savedGroups.length)
      // This preserves the original group captures, implementing PCRE subroutine semantics
      mv.visitVarInsn(ALOAD, 16); // savedGroups (src)
      mv.visitInsn(ICONST_0); // srcPos = 0
      mv.visitVarInsn(ALOAD, 4); // groups (dest)
      mv.visitInsn(ICONST_0); // destPos = 0
      mv.visitVarInsn(ALOAD, 16); // savedGroups
      mv.visitInsn(ARRAYLENGTH); // length
      mv.visitMethodInsn(
          INVOKESTATIC,
          "java/lang/System",
          "arraycopy",
          "(Ljava/lang/Object;ILjava/lang/Object;II)V",
          false);

      // Check if subroutine matched
      mv.visitVarInsn(ILOAD, 17);
      mv.visitInsn(ICONST_M1);
      Label subroutineMatched = new Label();
      mv.visitJumpInsn(IF_ICMPNE, subroutineMatched);

      // Subroutine failed: return -1
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      // Subroutine succeeded: return the result position
      mv.visitLabel(subroutineMatched);
      mv.visitVarInsn(ILOAD, 17);
      mv.visitInsn(IRETURN);

      return null;
    }

    @Override
    public Void visitConditional(ConditionalNode node) {
      // Conditional: (?(condition)then|else)
      // Check if the condition group has been captured

      int conditionGroupNumber = node.condition;
      int startIndex = conditionGroupNumber * 2;
      int endIndex = conditionGroupNumber * 2 + 1;

      // Defensive bounds checking for conditional group reference
      // if (startIndex < 0 || startIndex >= groups.length) fail (use else branch or return -1)
      Label condStartIndexOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitInsn(ICONST_0);
      mv.visitJumpInsn(IF_ICMPGE, condStartIndexOk);
      // Invalid startIndex, treat as group not captured (go to else)
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(condStartIndexOk);

      Label condStartIndexUpperOk = new Label();
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitInsn(ARRAYLENGTH);
      mv.visitJumpInsn(IF_ICMPLT, condStartIndexUpperOk);
      // Invalid startIndex, treat as group not captured
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(condStartIndexUpperOk);

      // Generate parsers for then and else branches
      generateParserMethod(cw, className, node.thenBranch);
      String thenMethod = getMethodNameForNode(node.thenBranch);

      String elseMethod = null;
      if (node.elseBranch != null) {
        generateParserMethod(cw, className, node.elseBranch);
        elseMethod = getMethodNameForNode(node.elseBranch);
      }

      Label elseLabel = new Label();

      // Check if group was captured: if (groups[startIndex] == -1) goto else
      // S: []
      mv.visitVarInsn(ALOAD, 4); // groups
      // S: [A:[I]]
      com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt(mv, startIndex);
      // S: [A:[I], I]
      mv.visitInsn(IALOAD); // groups[startIndex]
      // S: [I]

      // Compare with -1
      mv.visitInsn(ICONST_M1);
      // S: [I, I]
      mv.visitJumpInsn(IF_ICMPEQ, elseLabel); // if == -1, group didn't match, goto else
      // S: []

      // Then branch: group matched
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 2); // pos
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitVarInsn(ILOAD, 5); // depth
      // S: [A:this, A:String, I, I, A:[I], I]
      mv.visitMethodInsn(INVOKESPECIAL, className, thenMethod, "(Ljava/lang/String;II[II)I", false);
      // S: [I]
      mv.visitInsn(IRETURN);

      // Else branch: group didn't match
      mv.visitLabel(elseLabel);
      // S: []
      if (elseMethod != null) {
        // Call else branch parser
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        // S: [A:this, A:String, I, I, A:[I], I]
        mv.visitMethodInsn(
            INVOKESPECIAL, className, elseMethod, "(Ljava/lang/String;II[II)I", false);
        // S: [I]
        mv.visitInsn(IRETURN);
      } else {
        // No else branch: match succeeds with no advancement
        mv.visitVarInsn(ILOAD, 2); // pos
        // S: [I]
        mv.visitInsn(IRETURN);
      }

      return null;
    }

    @Override
    public Void visitBranchReset(BranchResetNode node) {
      // Branch reset: (?|alt1|alt2)
      // All alternatives share the same group numbers
      // Each alternative can set different groups, but they use the same indices
      // Local variables: 0=this, 1=input, 2=pos, 3=end, 4=groups, 5=depth
      // Additional locals: 6=savedGroups, 7=result

      // Save groups array before trying alternatives
      mv.visitVarInsn(ALOAD, 4); // groups
      mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
      mv.visitTypeInsn(CHECKCAST, "[I");
      mv.visitVarInsn(ASTORE, 6); // savedGroups in slot 6

      // Collect the group numbers that are actually inside this branch reset
      Set<Integer> branchResetGroups = collectBranchResetGroupNumbers(node);

      // Try each alternative
      for (int i = 0; i < node.alternatives.size(); i++) {
        RegexNode alt = node.alternatives.get(i);
        Label nextAlt = new Label();

        // Generate parser method for this alternative
        generateParserMethod(cw, className, alt);
        String altMethod = getMethodNameForNode(alt);

        // Reset ONLY the groups that are inside the branch reset
        // This is the "branch reset" behavior: each alternative starts fresh
        // but groups OUTSIDE the branch reset are preserved
        for (int g : branchResetGroups) {
          int startIdx = g * 2;
          int endIdx = g * 2 + 1;

          // groups[startIdx] = -1
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, startIdx);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IASTORE);

          // groups[endIdx] = -1
          mv.visitVarInsn(ALOAD, 4); // groups
          BytecodeUtil.pushInt(mv, endIdx);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IASTORE);
        }

        // Try to match this alternative
        mv.visitVarInsn(ALOAD, 0); // this
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, 2); // pos
        mv.visitVarInsn(ILOAD, 3); // end
        mv.visitVarInsn(ALOAD, 4); // groups
        mv.visitVarInsn(ILOAD, 5); // depth
        mv.visitMethodInsn(
            INVOKESPECIAL, className, altMethod, "(Ljava/lang/String;II[II)I", false);
        mv.visitVarInsn(ISTORE, 7); // result in slot 7

        // If succeeded, return result
        mv.visitVarInsn(ILOAD, 7);
        mv.visitInsn(ICONST_M1);
        mv.visitJumpInsn(IF_ICMPEQ, nextAlt);

        // Success: return result (groups already set by alternative)
        mv.visitVarInsn(ILOAD, 7);
        mv.visitInsn(IRETURN);

        // Failed: restore groups and try next
        mv.visitLabel(nextAlt);
        if (i < node.alternatives.size() - 1) {
          // Restore groups array
          mv.visitVarInsn(ALOAD, 6); // savedGroups
          mv.visitInsn(ICONST_0);
          mv.visitVarInsn(ALOAD, 4); // groups
          mv.visitInsn(ICONST_0);
          mv.visitVarInsn(ALOAD, 4);
          mv.visitInsn(ARRAYLENGTH);
          mv.visitMethodInsn(
              INVOKESTATIC,
              "java/lang/System",
              "arraycopy",
              "(Ljava/lang/Object;ILjava/lang/Object;II)V",
              false);
        }
      }

      // All alternatives failed
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      return null;
    }
  }
}
