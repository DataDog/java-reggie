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

import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt;
import static org.objectweb.asm.Opcodes.*;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.LiteralAlternationInfo;
import java.util.*;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates optimized bytecode for pure literal alternation patterns like
 * keyword1|keyword2|...|keywordN.
 *
 * <p>Uses trie-based matching with unrolled character comparisons for maximum performance. NO state
 * machines, NO loops, NO allocations - pure straight-line bytecode.
 *
 * <p>Pattern structure: literal1|literal2|...|literalN Examples: foo|bar|baz,
 * keyword1|keyword2|...|keyword15
 *
 * <p>Performance characteristics: - O(1) for each character comparison (vs O(n) for JDK's
 * alternation) - Constant memory (no trie data structure at runtime) - Fully inlined comparisons
 */
public class LiteralAlternationTrieGenerator {

  private final List<String> keywords;
  private final int maxLength;
  private final int minLength;
  private final int groupCount;

  public LiteralAlternationTrieGenerator(LiteralAlternationInfo info, int nfaGroupCount) {
    this.keywords = info.keywords;
    this.maxLength = info.maxLength;
    this.minLength = info.minLength;
    this.groupCount = info.groupCount;
  }

  /** Simple trie node for organizing keywords by common prefixes. */
  private static class TrieNode {
    Map<Character, TrieNode> children = new HashMap<>();
    boolean isTerminal = false; // true if a keyword ends here
  }

  /** Build trie from keywords. */
  private TrieNode buildTrie() {
    TrieNode root = new TrieNode();
    for (String keyword : keywords) {
      TrieNode node = root;
      for (char c : keyword.toCharArray()) {
        node = node.children.computeIfAbsent(c, k -> new TrieNode());
      }
      node.isTerminal = true;
    }
    return root;
  }

  /**
   * Generate matches() method - check if entire string is one of the keywords. Uses true trie
   * traversal for O(maxLength) comparisons instead of O(keywords * avgLength).
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    Label returnFalse = new Label();
    Label returnTrue = new Label();

    // if (input == null) return false;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnFalse);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    int lenVar = 2;
    mv.visitVarInsn(ISTORE, lenVar);

    // Quick length check: if (len < minLength || len > maxLength) return false;
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, minLength);
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, maxLength);
    mv.visitJumpInsn(IF_ICMPGT, returnFalse);

    // Build trie and generate trie-based traversal
    TrieNode root = buildTrie();
    generateTrieTraversal(mv, root, 0, lenVar, returnFalse, returnTrue);

    // return false;
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // return true;
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate bytecode for trie traversal at a specific node and depth. This recursively generates
   * code for each node in the trie.
   */
  private void generateTrieTraversal(
      MethodVisitor mv, TrieNode node, int depth, int lenVar, Label returnFalse, Label returnTrue) {
    if (node.children.isEmpty()) {
      // Leaf node - must be terminal, check if we've consumed all input
      // if (depth == len) return true; else return false;
      mv.visitVarInsn(ILOAD, lenVar);
      pushInt(mv, depth);
      mv.visitJumpInsn(IF_ICMPEQ, returnTrue);
      mv.visitJumpInsn(GOTO, returnFalse);
      return;
    }

    // Non-leaf node
    if (node.isTerminal) {
      // This node is terminal - check if input ends here
      // if (depth == len) return true;
      Label continueTraversal = new Label();
      mv.visitVarInsn(ILOAD, lenVar);
      pushInt(mv, depth);
      mv.visitJumpInsn(IF_ICMPNE, continueTraversal);
      mv.visitJumpInsn(GOTO, returnTrue);
      mv.visitLabel(continueTraversal);
    }

    // Check we haven't exceeded input length
    // if (depth >= len) return false;
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, depth);
    mv.visitJumpInsn(IF_ICMPLE, returnFalse); // if (len <= depth) goto false

    // Get char at current position: char c = input.charAt(depth);
    mv.visitVarInsn(ALOAD, 1); // input
    pushInt(mv, depth);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    int charVar = lenVar + 1 + depth; // Unique slot for each depth level
    mv.visitVarInsn(ISTORE, charVar);

    // Generate dispatch based on number of children
    List<Map.Entry<Character, TrieNode>> children = new ArrayList<>(node.children.entrySet());
    children.sort(Comparator.comparingInt(e -> e.getKey()));

    if (children.size() == 1) {
      // Single child - direct comparison
      Map.Entry<Character, TrieNode> entry = children.get(0);
      char childChar = entry.getKey();
      TrieNode childNode = entry.getValue();

      // if (c != childChar) return false;
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, childChar);
      mv.visitJumpInsn(IF_ICMPNE, returnFalse);

      // Continue to child
      generateTrieTraversal(mv, childNode, depth + 1, lenVar, returnFalse, returnTrue);
    } else if (shouldUseTableSwitch(children)) {
      // Dense character range - use TABLESWITCH for O(1) dispatch
      generateTableSwitch(mv, children, depth, lenVar, charVar, returnFalse, returnTrue);
    } else {
      // Sparse characters - use LOOKUPSWITCH
      generateLookupSwitch(mv, children, depth, lenVar, charVar, returnFalse, returnTrue);
    }
  }

  /**
   * Check if TABLESWITCH would be efficient (dense character range). Use tableswitch if the range
   * is at most 2x the number of entries.
   */
  private boolean shouldUseTableSwitch(List<Map.Entry<Character, TrieNode>> children) {
    if (children.size() < 3) return false; // Not worth it for few entries
    char minChar = children.get(0).getKey();
    char maxChar = children.get(children.size() - 1).getKey();
    int range = maxChar - minChar + 1;
    return range <= children.size() * 2; // Allow up to 50% sparsity
  }

  /** Generate TABLESWITCH for dense character ranges. */
  private void generateTableSwitch(
      MethodVisitor mv,
      List<Map.Entry<Character, TrieNode>> children,
      int depth,
      int lenVar,
      int charVar,
      Label returnFalse,
      Label returnTrue) {
    char minChar = children.get(0).getKey();
    char maxChar = children.get(children.size() - 1).getKey();

    // Create labels for each case
    Label[] caseLabels = new Label[maxChar - minChar + 1];
    Map<Character, Label> charToLabel = new HashMap<>();
    for (Map.Entry<Character, TrieNode> entry : children) {
      Label label = new Label();
      charToLabel.put(entry.getKey(), label);
    }

    // Fill in case labels (default to returnFalse for gaps)
    for (int i = 0; i < caseLabels.length; i++) {
      char c = (char) (minChar + i);
      caseLabels[i] = charToLabel.getOrDefault(c, returnFalse);
    }

    // Generate tableswitch
    mv.visitVarInsn(ILOAD, charVar);
    mv.visitTableSwitchInsn(minChar, maxChar, returnFalse, caseLabels);

    // Generate code for each child
    for (Map.Entry<Character, TrieNode> entry : children) {
      mv.visitLabel(charToLabel.get(entry.getKey()));
      generateTrieTraversal(mv, entry.getValue(), depth + 1, lenVar, returnFalse, returnTrue);
    }
  }

  /** Generate LOOKUPSWITCH for sparse character sets. */
  private void generateLookupSwitch(
      MethodVisitor mv,
      List<Map.Entry<Character, TrieNode>> children,
      int depth,
      int lenVar,
      int charVar,
      Label returnFalse,
      Label returnTrue) {
    int[] keys = new int[children.size()];
    Label[] labels = new Label[children.size()];

    for (int i = 0; i < children.size(); i++) {
      keys[i] = children.get(i).getKey();
      labels[i] = new Label();
    }

    // Generate lookupswitch
    mv.visitVarInsn(ILOAD, charVar);
    mv.visitLookupSwitchInsn(returnFalse, keys, labels);

    // Generate code for each child
    for (int i = 0; i < children.size(); i++) {
      mv.visitLabel(labels[i]);
      generateTrieTraversal(
          mv, children.get(i).getValue(), depth + 1, lenVar, returnFalse, returnTrue);
    }
  }

  /**
   * Generate find() method - find first match in string. Uses trie traversal at each position for
   * efficient matching.
   */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    Label returnFalse = new Label();
    Label returnTrue = new Label();

    // if (input == null) return false;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnFalse);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    int lenVar = 2;
    mv.visitVarInsn(ISTORE, lenVar);

    // Quick check: if (len < minLength) return false;
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, minLength);
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // Loop through all possible starting positions
    int posVar = 3;
    int baseCharVar = 4; // Base slot for char variables (will use baseCharVar + depth)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Build trie for traversal
    TrieNode root = buildTrie();

    mv.visitLabel(loopStart);

    // if (pos + minLength > len) break;
    mv.visitVarInsn(ILOAD, posVar);
    pushInt(mv, minLength);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // Try to match from current position using trie traversal
    Label nextPosition = new Label();
    generateFindTrieTraversal(mv, root, 0, posVar, lenVar, baseCharVar, nextPosition, returnTrue);

    mv.visitLabel(nextPosition);
    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // return false;
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // return true;
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate trie traversal for find operations. Checks characters at posVar + depth offset. */
  private void generateFindTrieTraversal(
      MethodVisitor mv,
      TrieNode node,
      int depth,
      int posVar,
      int lenVar,
      int baseCharVar,
      Label notFound,
      Label found) {
    if (node.children.isEmpty()) {
      // Leaf node - must be terminal, check if position is valid
      // Match found if we got here through all comparisons
      mv.visitJumpInsn(GOTO, found);
      return;
    }

    // Non-leaf node
    if (node.isTerminal) {
      // This node is terminal - we found a match!
      mv.visitJumpInsn(GOTO, found);
      return;
    }

    // Check we haven't exceeded input length
    // if (pos + depth >= len) goto notFound;
    mv.visitVarInsn(ILOAD, posVar);
    pushInt(mv, depth);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, notFound);

    // Get char at current position: char c = input.charAt(pos + depth);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, posVar);
    pushInt(mv, depth);
    mv.visitInsn(IADD);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    int charVar = baseCharVar + depth;
    mv.visitVarInsn(ISTORE, charVar);

    // Generate dispatch based on number of children
    List<Map.Entry<Character, TrieNode>> children = new ArrayList<>(node.children.entrySet());
    children.sort(Comparator.comparingInt(e -> e.getKey()));

    if (children.size() == 1) {
      // Single child - direct comparison
      Map.Entry<Character, TrieNode> entry = children.get(0);
      char childChar = entry.getKey();
      TrieNode childNode = entry.getValue();

      // if (c != childChar) goto notFound;
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, childChar);
      mv.visitJumpInsn(IF_ICMPNE, notFound);

      // Continue to child
      generateFindTrieTraversal(
          mv, childNode, depth + 1, posVar, lenVar, baseCharVar, notFound, found);
    } else if (shouldUseTableSwitch(children)) {
      // Dense character range - use TABLESWITCH
      generateFindTableSwitch(
          mv, children, depth, posVar, lenVar, baseCharVar, charVar, notFound, found);
    } else {
      // Sparse characters - use LOOKUPSWITCH
      generateFindLookupSwitch(
          mv, children, depth, posVar, lenVar, baseCharVar, charVar, notFound, found);
    }
  }

  /** Generate TABLESWITCH for find operations. */
  private void generateFindTableSwitch(
      MethodVisitor mv,
      List<Map.Entry<Character, TrieNode>> children,
      int depth,
      int posVar,
      int lenVar,
      int baseCharVar,
      int charVar,
      Label notFound,
      Label found) {
    char minChar = children.get(0).getKey();
    char maxChar = children.get(children.size() - 1).getKey();

    // Create labels for each case
    Label[] caseLabels = new Label[maxChar - minChar + 1];
    Map<Character, Label> charToLabel = new HashMap<>();
    for (Map.Entry<Character, TrieNode> entry : children) {
      Label label = new Label();
      charToLabel.put(entry.getKey(), label);
    }

    // Fill in case labels (default to notFound for gaps)
    for (int i = 0; i < caseLabels.length; i++) {
      char c = (char) (minChar + i);
      caseLabels[i] = charToLabel.getOrDefault(c, notFound);
    }

    // Generate tableswitch
    mv.visitVarInsn(ILOAD, charVar);
    mv.visitTableSwitchInsn(minChar, maxChar, notFound, caseLabels);

    // Generate code for each child
    for (Map.Entry<Character, TrieNode> entry : children) {
      mv.visitLabel(charToLabel.get(entry.getKey()));
      generateFindTrieTraversal(
          mv, entry.getValue(), depth + 1, posVar, lenVar, baseCharVar, notFound, found);
    }
  }

  /** Generate LOOKUPSWITCH for find operations. */
  private void generateFindLookupSwitch(
      MethodVisitor mv,
      List<Map.Entry<Character, TrieNode>> children,
      int depth,
      int posVar,
      int lenVar,
      int baseCharVar,
      int charVar,
      Label notFound,
      Label found) {
    int[] keys = new int[children.size()];
    Label[] labels = new Label[children.size()];

    for (int i = 0; i < children.size(); i++) {
      keys[i] = children.get(i).getKey();
      labels[i] = new Label();
    }

    // Generate lookupswitch
    mv.visitVarInsn(ILOAD, charVar);
    mv.visitLookupSwitchInsn(notFound, keys, labels);

    // Generate code for each child
    for (int i = 0; i < children.size(); i++) {
      mv.visitLabel(labels[i]);
      generateFindTrieTraversal(
          mv, children.get(i).getValue(), depth + 1, posVar, lenVar, baseCharVar, notFound, found);
    }
  }

  /**
   * Generate findFrom() method - find match starting from given position. Returns the position
   * where match starts, or -1 if not found. Uses trie traversal for efficient matching.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    Label returnNotFound = new Label();
    Label returnFound = new Label();

    // if (input == null) return -1;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnNotFound);

    // if (startPos < 0) startPos = 0;
    Label startPosOk = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFGE, startPosOk);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startPosOk);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    int lenVar = 3;
    mv.visitVarInsn(ISTORE, lenVar);

    // Quick check: if (startPos + minLength > len) return -1;
    mv.visitVarInsn(ILOAD, 2);
    pushInt(mv, minLength);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, returnNotFound);

    // Loop to find match, starting from startPos
    int posVar = 2; // reuse startPos
    int baseCharVar = 4; // Base slot for char variables
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // Build trie for traversal
    TrieNode root = buildTrie();

    mv.visitLabel(loopStart);

    // if (pos + minLength > len) break;
    mv.visitVarInsn(ILOAD, posVar);
    pushInt(mv, minLength);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // Try to match from current position using trie traversal
    Label nextPosition = new Label();
    generateFindTrieTraversal(mv, root, 0, posVar, lenVar, baseCharVar, nextPosition, returnFound);

    mv.visitLabel(nextPosition);
    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // return -1;
    mv.visitLabel(returnNotFound);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    // return pos;
    mv.visitLabel(returnFound);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchesBounded() method - check bounded match. */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    Label returnFalse = new Label();

    // if (input == null) return false;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnFalse);

    // if (startPos < 0 || endPos < startPos) return false;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFLT, returnFalse);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // String str = input.subSequence(startPos, endPos).toString();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);

    // return this.matches(str);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(SWAP);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findBoundsFrom() method - find and return bounds. Uses trie traversal with match
   * length tracking.
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    Label returnFalse = new Label();
    Label returnTrue = new Label();

    // if (input == null || bounds == null) return false;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnFalse);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitJumpInsn(IFNULL, returnFalse);

    // if (startPos < 0) startPos = 0;
    Label startPosOk = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFGE, startPosOk);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startPosOk);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    int lenVar = 4;
    mv.visitVarInsn(ISTORE, lenVar);

    // Quick check: if (startPos + minLength > len) return false;
    mv.visitVarInsn(ILOAD, 2);
    pushInt(mv, minLength);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, returnFalse);

    // Variable to store match length
    int matchLengthVar = 5;
    int posVar = 2; // reuse startPos
    int baseCharVar = 6; // Base slot for char variables (will use baseCharVar + depth)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // Build trie for traversal
    TrieNode root = buildTrie();

    mv.visitLabel(loopStart);

    // if (pos + minLength > len) break;
    mv.visitVarInsn(ILOAD, posVar);
    pushInt(mv, minLength);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // Try to match from current position using trie traversal
    Label nextPosition = new Label();
    Label foundMatch = new Label();
    generateFindBoundsTrieTraversal(
        mv, root, 0, posVar, lenVar, matchLengthVar, baseCharVar, nextPosition, foundMatch);

    mv.visitLabel(nextPosition);
    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // return false;
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // Found match - set bounds and return true
    mv.visitLabel(foundMatch);
    // bounds[0] = pos;
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // bounds[1] = pos + matchLength;
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, matchLengthVar);
    mv.visitInsn(IADD);
    mv.visitInsn(IASTORE);

    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate trie traversal for findBoundsFrom - stores match length at terminal nodes. */
  private void generateFindBoundsTrieTraversal(
      MethodVisitor mv,
      TrieNode node,
      int depth,
      int posVar,
      int lenVar,
      int matchLengthVar,
      int baseCharVar,
      Label notFound,
      Label found) {
    if (node.children.isEmpty()) {
      // Leaf node - must be terminal, store match length and jump to found
      pushInt(mv, depth);
      mv.visitVarInsn(ISTORE, matchLengthVar);
      mv.visitJumpInsn(GOTO, found);
      return;
    }

    // Non-leaf node
    if (node.isTerminal) {
      // This node is terminal - store match length and jump to found
      pushInt(mv, depth);
      mv.visitVarInsn(ISTORE, matchLengthVar);
      mv.visitJumpInsn(GOTO, found);
      return;
    }

    // Check we haven't exceeded input length
    // if (pos + depth >= len) goto notFound;
    mv.visitVarInsn(ILOAD, posVar);
    pushInt(mv, depth);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, notFound);

    // Get char at current position: char c = input.charAt(pos + depth);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, posVar);
    pushInt(mv, depth);
    mv.visitInsn(IADD);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    int charVar = baseCharVar + depth;
    mv.visitVarInsn(ISTORE, charVar);

    // Generate dispatch based on number of children
    List<Map.Entry<Character, TrieNode>> children = new ArrayList<>(node.children.entrySet());
    children.sort(Comparator.comparingInt(e -> e.getKey()));

    if (children.size() == 1) {
      // Single child - direct comparison
      Map.Entry<Character, TrieNode> entry = children.get(0);
      char childChar = entry.getKey();
      TrieNode childNode = entry.getValue();

      // if (c != childChar) goto notFound;
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, childChar);
      mv.visitJumpInsn(IF_ICMPNE, notFound);

      // Continue to child
      generateFindBoundsTrieTraversal(
          mv, childNode, depth + 1, posVar, lenVar, matchLengthVar, baseCharVar, notFound, found);
    } else if (shouldUseTableSwitch(children)) {
      // Dense character range - use TABLESWITCH
      generateFindBoundsTableSwitch(
          mv,
          children,
          depth,
          posVar,
          lenVar,
          matchLengthVar,
          baseCharVar,
          charVar,
          notFound,
          found);
    } else {
      // Sparse characters - use LOOKUPSWITCH
      generateFindBoundsLookupSwitch(
          mv,
          children,
          depth,
          posVar,
          lenVar,
          matchLengthVar,
          baseCharVar,
          charVar,
          notFound,
          found);
    }
  }

  /** Generate TABLESWITCH for findBoundsFrom operations. */
  private void generateFindBoundsTableSwitch(
      MethodVisitor mv,
      List<Map.Entry<Character, TrieNode>> children,
      int depth,
      int posVar,
      int lenVar,
      int matchLengthVar,
      int baseCharVar,
      int charVar,
      Label notFound,
      Label found) {
    char minChar = children.get(0).getKey();
    char maxChar = children.get(children.size() - 1).getKey();

    // Create labels for each case
    Label[] caseLabels = new Label[maxChar - minChar + 1];
    Map<Character, Label> charToLabel = new HashMap<>();
    for (Map.Entry<Character, TrieNode> entry : children) {
      Label label = new Label();
      charToLabel.put(entry.getKey(), label);
    }

    // Fill in case labels (default to notFound for gaps)
    for (int i = 0; i < caseLabels.length; i++) {
      char c = (char) (minChar + i);
      caseLabels[i] = charToLabel.getOrDefault(c, notFound);
    }

    // Generate tableswitch
    mv.visitVarInsn(ILOAD, charVar);
    mv.visitTableSwitchInsn(minChar, maxChar, notFound, caseLabels);

    // Generate code for each child
    for (Map.Entry<Character, TrieNode> entry : children) {
      mv.visitLabel(charToLabel.get(entry.getKey()));
      generateFindBoundsTrieTraversal(
          mv,
          entry.getValue(),
          depth + 1,
          posVar,
          lenVar,
          matchLengthVar,
          baseCharVar,
          notFound,
          found);
    }
  }

  /** Generate LOOKUPSWITCH for findBoundsFrom operations. */
  private void generateFindBoundsLookupSwitch(
      MethodVisitor mv,
      List<Map.Entry<Character, TrieNode>> children,
      int depth,
      int posVar,
      int lenVar,
      int matchLengthVar,
      int baseCharVar,
      int charVar,
      Label notFound,
      Label found) {
    int[] keys = new int[children.size()];
    Label[] labels = new Label[children.size()];

    for (int i = 0; i < children.size(); i++) {
      keys[i] = children.get(i).getKey();
      labels[i] = new Label();
    }

    // Generate lookupswitch
    mv.visitVarInsn(ILOAD, charVar);
    mv.visitLookupSwitchInsn(notFound, keys, labels);

    // Generate code for each child
    for (int i = 0; i < children.size(); i++) {
      mv.visitLabel(labels[i]);
      generateFindBoundsTrieTraversal(
          mv,
          children.get(i).getValue(),
          depth + 1,
          posVar,
          lenVar,
          matchLengthVar,
          baseCharVar,
          notFound,
          found);
    }
  }

  // Group extraction methods - not supported yet (no capturing groups)
  public void generateMatchMethod(ClassWriter cw, String className) {
    generateUnsupportedMethod(
        cw, "match", "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;");
  }

  public void generateFindMatchMethod(ClassWriter cw, String className) {
    generateUnsupportedMethod(
        cw, "findMatch", "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;");
  }

  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    generateUnsupportedMethod(
        cw, "findMatchFrom", "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;");
  }

  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    generateUnsupportedMethod(
        cw,
        "matchBounded",
        "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;");
  }

  private void generateUnsupportedMethod(ClassWriter cw, String methodName, String descriptor) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, methodName, descriptor, null, null);
    mv.visitCode();
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
