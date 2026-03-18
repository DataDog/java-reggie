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

import com.datadoghq.reggie.codegen.analysis.BackreferencePatternInfo;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.QuantifierNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Specialized bytecode generator for backreference patterns. Generates optimized matching code for
 * common patterns that use backreferences (\1, \2, etc.).
 *
 * <h3>Supported Pattern Types</h3>
 *
 * <ul>
 *   <li><b>HTML_TAG</b>: {@code <(\w+)>.*</\1>} - Matches paired HTML tags
 *   <li><b>REPEATED_WORD</b>: {@code \b(\w+)\s+\1\b} - Matches duplicated words
 *   <li><b>ATTRIBUTE_MATCH</b>: {@code "([^"]+)"\s*=\s*"\1"} - Matches quoted attribute pairs
 *   <li><b>GREEDY_ANY_BACKREF</b>: {@code (.*)\d+\1} - Variable-length capture with backref
 * </ul>
 *
 * <h3>Generated Algorithm (HTML_TAG example)</h3>
 *
 * <pre>{@code
 * // Pattern: <(\w+)>.*</\1>
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int len = input.length();
 *     int pos = 0;
 *
 *     // Match opening '<'
 *     if (pos >= len || input.charAt(pos) != '<') return false;
 *     pos++;
 *
 *     // Capture group 1: tag name (\w+)
 *     int group1Start = pos;
 *     while (pos < len && isWordChar(input.charAt(pos))) pos++;
 *     int group1End = pos;
 *     if (group1Start == group1End) return false;  // Empty tag name
 *     int group1Len = group1End - group1Start;
 *
 *     // Match closing '>'
 *     if (pos >= len || input.charAt(pos) != '>') return false;
 *     pos++;
 *
 *     // Search for closing tag using indexOf("</")
 *     while (pos < len) {
 *         int closeStart = input.indexOf("</", pos);
 *         if (closeStart < 0) break;
 *
 *         int backrefPos = closeStart + 2;
 *         // Check backreference matches captured tag name
 *         if (backrefPos + group1Len + 1 <= len &&
 *             input.regionMatches(backrefPos, input, group1Start, group1Len) &&
 *             input.charAt(backrefPos + group1Len) == '>') {
 *             // For matches(), verify at end of input
 *             return (backrefPos + group1Len + 1) == len;
 *         }
 *         pos = closeStart + 1;  // Try next "</>"
 *     }
 *     return false;
 * }
 * }</pre>
 *
 * <h3>Generated Algorithm (GREEDY_ANY_BACKREF example)</h3>
 *
 * <pre>{@code
 * // Pattern: (.*)\d+\1
 * // Uses greedy backtracking: try longest capture first
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int len = input.length();
 *
 *     // Try group lengths from longest to shortest
 *     for (int groupLen = maxPossible; groupLen >= minLen; groupLen--) {
 *         // After group, must have separator (\d+) and backreference
 *         int sepEnd = groupLen + separatorMin;
 *         if (sepEnd > len - groupLen) continue;
 *
 *         // Find separator boundary (where digits end)
 *         for (int sepIdx = sepEnd; sepIdx <= len - groupLen; sepIdx++) {
 *             // Check separator chars are digits
 *             if (!allDigits(input, groupLen, sepIdx)) continue;
 *
 *             // Check backreference matches
 *             if (input.regionMatches(sepIdx, input, 0, groupLen)) {
 *                 return true;
 *             }
 *         }
 *     }
 *     return false;
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li><b>String.indexOf()</b>: Fast substring search for literal delimiters
 *   <li><b>String.regionMatches()</b>: Efficient backreference verification
 *   <li><b>Greedy backtracking</b>: Try longest matches first for (.*) patterns
 *   <li><b>Early termination</b>: Bounds checks eliminate impossible matches quickly
 * </ul>
 */
public class BackreferenceBytecodeGenerator {

  private final BackreferencePatternInfo patternInfo;

  public BackreferenceBytecodeGenerator(BackreferencePatternInfo patternInfo) {
    this.patternInfo = patternInfo;
  }

  /**
   * Generates matches() method - full string matching. Dispatches to pattern-specific
   * implementation based on patternInfo.type.
   *
   * <h3>Implementations</h3>
   *
   * <ul>
   *   <li><b>HTML_TAG</b>: Uses indexOf("&lt;/") to find closing tags
   *   <li><b>REPEATED_WORD</b>: Scans for duplicate consecutive words
   *   <li><b>ATTRIBUTE_MATCH</b>: Parses quoted values and compares
   *   <li><b>GREEDY_ANY_BACKREF</b>: Greedy backtracking on capture length
   * </ul>
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    if (patternInfo.type == BackreferencePatternInfo.BackrefType.HTML_TAG) {
      generateHTMLTagMatchesMethod(cw, className);
    } else if (patternInfo.type == BackreferencePatternInfo.BackrefType.REPEATED_WORD) {
      generateRepeatedWordMatchesMethod(cw, className);
    } else if (patternInfo.type == BackreferencePatternInfo.BackrefType.ATTRIBUTE_MATCH) {
      generateAttributeMatchMatchesMethod(cw, className);
    } else if (patternInfo.type == BackreferencePatternInfo.BackrefType.GREEDY_ANY_BACKREF) {
      generateGreedyAnyBackrefMatchesMethod(cw, className);
    } else {
      throw new UnsupportedOperationException(
          "Pattern type not yet implemented: " + patternInfo.type);
    }
  }

  /**
   * Generates find() method - substring finding. Delegates to findFrom(input, 0).
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean find(String input) {
   *     if (input == null) return false;
   *     return findFrom(input, 0) >= 0;
   * }
   * }</pre>
   */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // return findFrom(input, 0) >= 0;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);

    Label returnTrue = new Label();
    Label end = new Label();
    mv.visitJumpInsn(IFGE, returnTrue);
    mv.visitInsn(ICONST_0);
    mv.visitJumpInsn(GOTO, end);
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitLabel(end);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates findFrom() method - find match starting at given position. Dispatches to
   * pattern-specific implementation.
   *
   * <h3>Generated Algorithm (example for HTML_TAG)</h3>
   *
   * <pre>{@code
   * int findFrom(String input, int startPos) {
   *     if (input == null) return -1;
   *     int len = input.length();
   *
   *     // Try each starting position
   *     for (int start = startPos; start < len; start++) {
   *         // Look for pattern start (e.g., '<' for HTML tags)
   *         if (input.charAt(start) != '<') continue;
   *
   *         int pos = start + 1;
   *         // Capture group 1 (tag name)
   *         int group1Start = pos;
   *         while (pos < len && isWordChar(input.charAt(pos))) pos++;
   *         int group1End = pos;
   *
   *         if (group1Start == group1End) continue;  // Empty tag
   *         if (pos >= len || input.charAt(pos) != '>') continue;
   *         pos++;
   *
   *         // Search for matching closing tag
   *         while ((closeStart = input.indexOf("</", pos)) >= 0) {
   *             if (backreferenceMatches(input, closeStart, group1Start, group1Len)) {
   *                 return start;  // Found match
   *             }
   *             pos = closeStart + 1;
   *         }
   *     }
   *     return -1;  // Not found
   * }
   * }</pre>
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    if (patternInfo.type == BackreferencePatternInfo.BackrefType.HTML_TAG) {
      generateHTMLTagFindFromMethod(cw, className);
    } else if (patternInfo.type == BackreferencePatternInfo.BackrefType.REPEATED_WORD) {
      generateRepeatedWordFindFromMethod(cw, className);
    } else if (patternInfo.type == BackreferencePatternInfo.BackrefType.ATTRIBUTE_MATCH) {
      generateAttributeMatchFindFromMethod(cw, className);
    } else if (patternInfo.type == BackreferencePatternInfo.BackrefType.GREEDY_ANY_BACKREF) {
      generateGreedyAnyBackrefFindFromMethod(cw, className);
    } else {
      throw new UnsupportedOperationException(
          "Pattern type not yet implemented: " + patternInfo.type);
    }
  }

  /**
   * Generate matches() for HTML tag pattern: <(\w+)>.*</\1> Uses String.indexOf("</") optimization
   * for fast scanning.
   */
  private void generateHTMLTagMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int group1StartVar = allocator.allocate();
    int group1EndVar = allocator.allocate();
    int group1LenVar = allocator.allocate();
    int chVar = allocator.allocate();
    int closeStartVar = allocator.allocate();
    int backrefPosVar = allocator.allocate();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    Label returnFalse = new Label();

    // Match opening '<'
    // if (pos >= len || input.charAt(pos) != '<') return false;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, returnFalse);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '<');
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);

    // pos++
    mv.visitIincInsn(posVar, 1);

    // Capture group start
    // int group1Start = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, group1StartVar);

    // Match \w+ (tag name)
    // while (pos < len && isWordChar(input.charAt(pos))) pos++;
    Label wordLoop = new Label();
    Label wordEnd = new Label();
    mv.visitLabel(wordLoop);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, wordEnd);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // Check if word char (simplified: a-z, A-Z, 0-9, _)
    generateWordCharCheck(mv, chVar, wordEnd);

    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, wordLoop);
    mv.visitLabel(wordEnd);

    // int group1End = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, group1EndVar);

    // if (group1Start == group1End) return false; // Empty tag name
    mv.visitVarInsn(ILOAD, group1StartVar);
    mv.visitVarInsn(ILOAD, group1EndVar);
    mv.visitJumpInsn(IF_ICMPEQ, returnFalse);

    // int group1Len = group1End - group1Start;
    mv.visitVarInsn(ILOAD, group1EndVar);
    mv.visitVarInsn(ILOAD, group1StartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, group1LenVar);

    // Match closing '>'
    // if (pos >= len || input.charAt(pos) != '>') return false;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, returnFalse);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '>');
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);

    // pos++
    mv.visitIincInsn(posVar, 1);

    // Search for closing tag using indexOf("</")
    // while (pos < len) {
    Label searchLoop = new Label();
    Label searchEnd = new Label();
    mv.visitLabel(searchLoop);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, searchEnd);

    // int closeStart = input.indexOf("</", pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitLdcInsn("</");
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, closeStartVar);

    // if (closeStart < 0) break;
    mv.visitVarInsn(ILOAD, closeStartVar);
    mv.visitJumpInsn(IFLT, searchEnd);

    // int backrefPos = closeStart + 2;
    mv.visitVarInsn(ILOAD, closeStartVar);
    mv.visitInsn(ICONST_2);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, backrefPosVar);

    // Check bounds: backrefPos + group1Len + 1 <= len
    mv.visitVarInsn(ILOAD, backrefPosVar);
    mv.visitVarInsn(ILOAD, group1LenVar);
    mv.visitInsn(IADD);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    Label tryNext = new Label();
    mv.visitJumpInsn(IF_ICMPGT, tryNext);

    // Check backreference: input.regionMatches(backrefPos, input, group1Start, group1Len)
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, backrefPosVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, group1StartVar);
    mv.visitVarInsn(ILOAD, group1LenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, tryNext);

    // Check closing '>': input.charAt(backrefPos + group1Len) == '>'
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, backrefPosVar);
    mv.visitVarInsn(ILOAD, group1LenVar);
    mv.visitInsn(IADD);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '>');
    mv.visitJumpInsn(IF_ICMPNE, tryNext);

    // Found valid closing tag! For matches(), check if at end
    // return (backrefPos + group1Len + 1) == len;
    mv.visitVarInsn(ILOAD, backrefPosVar);
    mv.visitVarInsn(ILOAD, group1LenVar);
    mv.visitInsn(IADD);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    Label returnTrue = new Label();
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Try next position
    mv.visitLabel(tryNext);
    mv.visitVarInsn(ILOAD, closeStartVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);
    mv.visitJumpInsn(GOTO, searchLoop);

    mv.visitLabel(searchEnd);
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findFrom() for HTML tag pattern. Returns start position of match, or -1 if not found.
   */
  private void generateHTMLTagFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input, slot 2 = startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int lenVar = allocator.allocate();
    int startVar = allocator.allocate();
    int posVar = allocator.allocate();
    int group1StartVar = allocator.allocate();
    int group1EndVar = allocator.allocate();
    int group1LenVar = allocator.allocate();
    int chVar = allocator.allocate();
    int closeStartVar = allocator.allocate();
    int backrefPosVar = allocator.allocate();

    // if (input == null) return -1;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = startPos;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);

    Label returnNotFound = new Label();
    Label outerLoop = new Label();
    Label outerEnd = new Label();

    // Try each starting position
    // for (int start = startPos; start < len; start++) {
    mv.visitLabel(outerLoop);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, outerEnd);

    // int pos = start;
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Check if this position starts with '<'
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    Label tryNextStart = new Label();
    mv.visitJumpInsn(IF_ICMPGE, tryNextStart);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '<');
    mv.visitJumpInsn(IF_ICMPNE, tryNextStart);

    // pos++
    mv.visitIincInsn(posVar, 1);

    // Capture group start
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, group1StartVar);

    // Match \w+ (tag name)
    Label wordLoop = new Label();
    Label wordEnd = new Label();
    mv.visitLabel(wordLoop);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, wordEnd);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    generateWordCharCheck(mv, chVar, wordEnd);

    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, wordLoop);
    mv.visitLabel(wordEnd);

    // int group1End = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, group1EndVar);

    // if (group1Start == group1End) continue; // Empty tag
    mv.visitVarInsn(ILOAD, group1StartVar);
    mv.visitVarInsn(ILOAD, group1EndVar);
    mv.visitJumpInsn(IF_ICMPEQ, tryNextStart);

    // int group1Len = group1End - group1Start;
    mv.visitVarInsn(ILOAD, group1EndVar);
    mv.visitVarInsn(ILOAD, group1StartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, group1LenVar);

    // Match '>'
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, tryNextStart);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '>');
    mv.visitJumpInsn(IF_ICMPNE, tryNextStart);

    // pos++
    mv.visitIincInsn(posVar, 1);

    // Search for closing tag
    Label searchLoop = new Label();
    Label searchEnd = new Label();
    mv.visitLabel(searchLoop);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, searchEnd);

    // int closeStart = input.indexOf("</", pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitLdcInsn("</");
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, closeStartVar);

    // if (closeStart < 0) break;
    mv.visitVarInsn(ILOAD, closeStartVar);
    mv.visitJumpInsn(IFLT, searchEnd);

    // int backrefPos = closeStart + 2;
    mv.visitVarInsn(ILOAD, closeStartVar);
    mv.visitInsn(ICONST_2);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, backrefPosVar);

    // Check bounds
    mv.visitVarInsn(ILOAD, backrefPosVar);
    mv.visitVarInsn(ILOAD, group1LenVar);
    mv.visitInsn(IADD);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    Label tryNext = new Label();
    mv.visitJumpInsn(IF_ICMPGT, tryNext);

    // Check backreference
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, backrefPosVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, group1StartVar);
    mv.visitVarInsn(ILOAD, group1LenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, tryNext);

    // Check closing '>'
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, backrefPosVar);
    mv.visitVarInsn(ILOAD, group1LenVar);
    mv.visitInsn(IADD);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '>');
    mv.visitJumpInsn(IF_ICMPNE, tryNext);

    // Found match! Return start position
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IRETURN);

    // Try next closing tag position
    mv.visitLabel(tryNext);
    mv.visitVarInsn(ILOAD, closeStartVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);
    mv.visitJumpInsn(GOTO, searchLoop);

    mv.visitLabel(searchEnd);

    // Try next starting position
    mv.visitLabel(tryNextStart);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitLabel(returnNotFound);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate word character check (a-z, A-Z, 0-9, _). Jumps to endLabel if NOT a word char. */
  private void generateWordCharCheck(MethodVisitor mv, int chVar, Label endLabel) {
    // ch >= 'a' && ch <= 'z'
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'a');
    Label notLower = new Label();
    mv.visitJumpInsn(IF_ICMPLT, notLower);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'z');
    Label isWord = new Label();
    mv.visitJumpInsn(IF_ICMPLE, isWord);

    mv.visitLabel(notLower);
    // ch >= 'A' && ch <= 'Z'
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'A');
    Label notUpper = new Label();
    mv.visitJumpInsn(IF_ICMPLT, notUpper);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'Z');
    mv.visitJumpInsn(IF_ICMPLE, isWord);

    mv.visitLabel(notUpper);
    // ch >= '0' && ch <= '9'
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '0');
    Label notDigit = new Label();
    mv.visitJumpInsn(IF_ICMPLT, notDigit);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '9');
    mv.visitJumpInsn(IF_ICMPLE, isWord);

    mv.visitLabel(notDigit);
    // ch == '_'
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '_');
    mv.visitJumpInsn(IF_ICMPNE, endLabel);

    mv.visitLabel(isWord);
    // Continue (is word char)
  }

  /**
   * Generate matches() for repeated word pattern: \b(\w+)\s+\1\b Matches duplicated words like "the
   * the" or "word word".
   */
  private void generateRepeatedWordMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int word1StartVar = allocator.allocate();
    int chVar = allocator.allocate();
    int word1EndVar = allocator.allocate();
    int word1LenVar = allocator.allocate();
    int wsStartVar = allocator.allocate();
    int word2StartVar = allocator.allocate();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // if (len == 0) return false;
    Label lenNotZero = new Label();
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IFNE, lenNotZero);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(lenNotZero);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Check start word boundary: pos == 0 or !isWordChar(input.charAt(pos-1))
    // Since pos == 0, boundary is satisfied at start

    // Find first word: capture characters while isWordChar
    // int word1Start = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, word1StartVar);

    // Scan first word
    Label scanWord1 = new Label();
    Label endWord1 = new Label();
    mv.visitLabel(scanWord1);
    // if (pos >= len) return false; (empty first word)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWord1);
    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    // if (!isWordChar(ch)) break;
    Label continueWord1 = new Label();
    generateWordCharCheck(mv, chVar, endWord1);
    mv.visitLabel(continueWord1);
    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanWord1);
    mv.visitLabel(endWord1);

    // int word1End = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, word1EndVar);

    // if (word1End == word1Start) return false; (no word found)
    Label word1NotEmpty = new Label();
    mv.visitVarInsn(ILOAD, word1EndVar);
    mv.visitVarInsn(ILOAD, word1StartVar);
    mv.visitJumpInsn(IF_ICMPNE, word1NotEmpty);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(word1NotEmpty);

    // int word1Len = word1End - word1Start;
    mv.visitVarInsn(ILOAD, word1EndVar);
    mv.visitVarInsn(ILOAD, word1StartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, word1LenVar);

    // Match whitespace \s+
    // int wsStart = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, wsStartVar);

    Label scanWS = new Label();
    Label endWS = new Label();
    mv.visitLabel(scanWS);
    // if (pos >= len) return false;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWS);
    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    // if (!Character.isWhitespace(ch)) break;
    Label continueWS = new Label();
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "isWhitespace", "(C)Z", false);
    mv.visitJumpInsn(IFEQ, endWS);
    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanWS);
    mv.visitLabel(endWS);

    // if (pos == wsStart) return false; (no whitespace)
    Label wsNotEmpty = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, wsStartVar);
    mv.visitJumpInsn(IF_ICMPNE, wsNotEmpty);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(wsNotEmpty);

    // Match backreference: must match word1
    // int word2Start = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, word2StartVar);

    // Check if enough characters remain: pos + word1Len <= len
    Label enoughChars = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, word1LenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPLE, enoughChars);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(enoughChars);

    // Validate: input.regionMatches(word2Start, input, word1Start, word1Len)
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, word2StartVar); // word2Start
    mv.visitVarInsn(ALOAD, 1); // input again (other string)
    mv.visitVarInsn(ILOAD, word1StartVar); // word1Start
    mv.visitVarInsn(ILOAD, word1LenVar); // word1Len
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    Label backrefMatches = new Label();
    mv.visitJumpInsn(IFNE, backrefMatches);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(backrefMatches);

    // pos += word1Len;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, word1LenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // Check end word boundary: pos == len or !isWordChar(input.charAt(pos))
    Label atEnd = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPEQ, atEnd);
    // Check if next char is NOT a word char
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    Label notWordBoundary = new Label();
    generateWordCharCheck(mv, chVar, notWordBoundary);
    // Is word char - not a boundary
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notWordBoundary);
    mv.visitLabel(atEnd);

    // For matches(), must be at end of string
    Label isMatch = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPEQ, isMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(isMatch);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findFrom() for repeated word pattern: \b(\w+)\s+\1\b */
  private void generateRepeatedWordFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input, slot 2 = startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int lenVar = allocator.allocate();
    int startVar = allocator.allocate();
    int chVar = allocator.allocate();
    int posVar = allocator.allocate();
    int word1StartVar = allocator.allocate();
    int word1EndVar = allocator.allocate();
    int word1LenVar = allocator.allocate();
    int wsStartVar = allocator.allocate();
    int word2StartVar = allocator.allocate();

    // if (input == null) return -1;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Normalize startPos
    mv.visitVarInsn(ILOAD, 2);
    Label startPosPositive = new Label();
    mv.visitJumpInsn(IFGE, startPosPositive);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startPosPositive);

    // Try each position
    // for (int start = startPos; start < len; start++)
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Check if at word boundary (start == 0 or prev char is not word char)
    Label checkPattern = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFEQ, checkPattern); // start == 0, boundary OK
    // Check prev char
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    Label prevNotWord = new Label();
    Label continueLoop = new Label();
    generateWordCharCheck(mv, chVar, prevNotWord);
    // Prev is word char - not a boundary, continue loop
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(prevNotWord);

    mv.visitLabel(checkPattern);
    // Try to match pattern from this position
    // Call a helper or inline the matching logic
    // For simplicity, inline similar to matches() but stop at word boundary

    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Scan first word
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, word1StartVar);

    Label scanWord1 = new Label();
    Label endWord1 = new Label();
    mv.visitLabel(scanWord1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWord1);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    Label continueWord1 = new Label();
    generateWordCharCheck(mv, chVar, endWord1);
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanWord1);
    mv.visitLabel(endWord1);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, word1EndVar);

    // if (word1End == word1Start) continue;
    mv.visitVarInsn(ILOAD, word1EndVar);
    mv.visitVarInsn(ILOAD, word1StartVar);
    mv.visitJumpInsn(IF_ICMPEQ, continueLoop);

    mv.visitVarInsn(ILOAD, word1EndVar);
    mv.visitVarInsn(ILOAD, word1StartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, word1LenVar);

    // Match whitespace
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, wsStartVar);

    Label scanWS = new Label();
    Label endWS = new Label();
    mv.visitLabel(scanWS);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWS);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "isWhitespace", "(C)Z", false);
    mv.visitJumpInsn(IFEQ, endWS);
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanWS);
    mv.visitLabel(endWS);

    // if (pos == wsStart) continue;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, wsStartVar);
    mv.visitJumpInsn(IF_ICMPEQ, continueLoop);

    // Match backreference
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, word2StartVar);

    // Check enough chars
    Label enoughChars = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, word1LenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPLE, enoughChars);
    mv.visitJumpInsn(GOTO, continueLoop);
    mv.visitLabel(enoughChars);

    // Validate backreference
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, word2StartVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, word1StartVar);
    mv.visitVarInsn(ILOAD, word1LenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueLoop);

    // pos += word1Len;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, word1LenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // Check end word boundary
    Label matchFound = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPEQ, matchFound);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    Label notWordBoundary = new Label();
    generateWordCharCheck(mv, chVar, notWordBoundary);
    mv.visitJumpInsn(GOTO, continueLoop);
    mv.visitLabel(notWordBoundary);

    mv.visitLabel(matchFound);
    // Return start position
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IRETURN);

    // Continue loop
    mv.visitLabel(continueLoop);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    // No match found
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matches() for attribute matching pattern: "([^"]+)"\s*=\s*"\1" Matches patterns like
   * "foo"="foo" where both sides are identical.
   */
  private void generateAttributeMatchMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int content1StartVar = allocator.allocate();
    int chVar = allocator.allocate();
    int content1EndVar = allocator.allocate();
    int content1LenVar = allocator.allocate();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // if (len < 6) return false; // Minimum: "a"="a"
    Label lenOK = new Label();
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitIntInsn(BIPUSH, 6);
    mv.visitJumpInsn(IF_ICMPGE, lenOK);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(lenOK);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Check opening quote: if (input.charAt(0) != '"') return false;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '"');
    Label openQuoteOK = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, openQuoteOK);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(openQuoteOK);

    // pos = 1;
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ISTORE, posVar);

    // Scan content1: find closing quote
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, content1StartVar);

    Label scanContent1 = new Label();
    Label endContent1 = new Label();
    mv.visitLabel(scanContent1);
    // if (pos >= len) return false;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endContent1);
    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    // if (ch == '"') break;
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '"');
    mv.visitJumpInsn(IF_ICMPEQ, endContent1);
    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanContent1);
    mv.visitLabel(endContent1);

    // int content1End = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, content1EndVar);

    // if (content1End == content1Start) return false; (empty content)
    Label content1NotEmpty = new Label();
    mv.visitVarInsn(ILOAD, content1EndVar);
    mv.visitVarInsn(ILOAD, content1StartVar);
    mv.visitJumpInsn(IF_ICMPNE, content1NotEmpty);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(content1NotEmpty);

    // int content1Len = content1End - content1Start;
    mv.visitVarInsn(ILOAD, content1EndVar);
    mv.visitVarInsn(ILOAD, content1StartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, content1LenVar);

    // Match closing quote: if (pos >= len || input.charAt(pos) != '"') return false;
    Label closeQuote1OK = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endContent1); // Reuse label for return false
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '"');
    Label returnFalse1 = new Label();
    mv.visitJumpInsn(IF_ICMPNE, returnFalse1);
    mv.visitJumpInsn(GOTO, closeQuote1OK);
    mv.visitLabel(returnFalse1);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(closeQuote1OK);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Skip whitespace \s*
    Label skipWS1 = new Label();
    Label endWS1 = new Label();
    mv.visitLabel(skipWS1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWS1);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "isWhitespace", "(C)Z", false);
    mv.visitJumpInsn(IFEQ, endWS1);
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, skipWS1);
    mv.visitLabel(endWS1);

    // Match '=': if (pos >= len || input.charAt(pos) != '=') return false;
    Label equalsOK = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    Label returnFalse2 = new Label();
    mv.visitJumpInsn(IF_ICMPGE, returnFalse2);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '=');
    mv.visitJumpInsn(IF_ICMPNE, returnFalse2);
    mv.visitJumpInsn(GOTO, equalsOK);
    mv.visitLabel(returnFalse2);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(equalsOK);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Skip whitespace \s*
    Label skipWS2 = new Label();
    Label endWS2 = new Label();
    mv.visitLabel(skipWS2);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWS2);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "isWhitespace", "(C)Z", false);
    mv.visitJumpInsn(IFEQ, endWS2);
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, skipWS2);
    mv.visitLabel(endWS2);

    // Match opening quote: if (pos >= len || input.charAt(pos) != '"') return false;
    Label openQuote2OK = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    Label returnFalse3 = new Label();
    mv.visitJumpInsn(IF_ICMPGE, returnFalse3);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '"');
    mv.visitJumpInsn(IF_ICMPNE, returnFalse3);
    mv.visitJumpInsn(GOTO, openQuote2OK);
    mv.visitLabel(returnFalse3);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(openQuote2OK);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Match backreference: must have content1Len chars + closing quote
    // if (pos + content1Len + 1 > len) return false;
    Label enoughChars = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, content1LenVar);
    mv.visitInsn(IADD);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    Label returnFalse4 = new Label();
    mv.visitJumpInsn(IF_ICMPGT, returnFalse4);
    mv.visitJumpInsn(GOTO, enoughChars);
    mv.visitLabel(returnFalse4);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(enoughChars);

    // Validate backreference: input.regionMatches(pos, input, content1Start, content1Len)
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, content1StartVar);
    mv.visitVarInsn(ILOAD, content1LenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    Label backrefMatches = new Label();
    mv.visitJumpInsn(IFNE, backrefMatches);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(backrefMatches);

    // pos += content1Len;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, content1LenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // Match closing quote: if (input.charAt(pos) != '"') return false;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '"');
    Label closeQuote2OK = new Label();
    mv.visitJumpInsn(IF_ICMPNE, returnFalse4);
    mv.visitJumpInsn(GOTO, closeQuote2OK);
    mv.visitLabel(closeQuote2OK);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // For matches(), must be at end: return pos == len;
    Label isMatch = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPEQ, isMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(isMatch);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findFrom() for attribute matching pattern: "([^"]+)"\s*=\s*"\1" */
  private void generateAttributeMatchFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input, slot 2 = startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int lenVar = allocator.allocate();
    int startVar = allocator.allocate();
    int chVar = allocator.allocate();
    int posVar = allocator.allocate();
    int content1StartVar = allocator.allocate();
    int content1EndVar = allocator.allocate();
    int content1LenVar = allocator.allocate();

    // if (input == null) return -1;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Normalize startPos
    mv.visitVarInsn(ILOAD, 2);
    Label startPosPositive = new Label();
    mv.visitJumpInsn(IFGE, startPosPositive);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startPosPositive);

    // Try each position starting with '"'
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Check if starts with '"'
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    Label continueLoop = new Label();
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '"');
    mv.visitJumpInsn(IF_ICMPNE, continueLoop);

    // Try to match from this position (inline similar logic to matches())
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // Scan content
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, content1StartVar);

    Label scanContent = new Label();
    Label endContent = new Label();
    mv.visitLabel(scanContent);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endContent);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '"');
    mv.visitJumpInsn(IF_ICMPEQ, endContent);
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanContent);
    mv.visitLabel(endContent);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, content1EndVar);

    // if empty, continue
    mv.visitVarInsn(ILOAD, content1EndVar);
    mv.visitVarInsn(ILOAD, content1StartVar);
    mv.visitJumpInsn(IF_ICMPEQ, continueLoop);

    mv.visitVarInsn(ILOAD, content1EndVar);
    mv.visitVarInsn(ILOAD, content1StartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, content1LenVar);

    // Match closing quote
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, continueLoop);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '"');
    mv.visitJumpInsn(IF_ICMPNE, continueLoop);
    mv.visitIincInsn(posVar, 1);

    // Skip whitespace
    Label skipWS1 = new Label();
    Label endWS1 = new Label();
    mv.visitLabel(skipWS1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWS1);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "isWhitespace", "(C)Z", false);
    mv.visitJumpInsn(IFEQ, endWS1);
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, skipWS1);
    mv.visitLabel(endWS1);

    // Match '='
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, continueLoop);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '=');
    mv.visitJumpInsn(IF_ICMPNE, continueLoop);
    mv.visitIincInsn(posVar, 1);

    // Skip whitespace
    Label skipWS2 = new Label();
    Label endWS2 = new Label();
    mv.visitLabel(skipWS2);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, endWS2);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "isWhitespace", "(C)Z", false);
    mv.visitJumpInsn(IFEQ, endWS2);
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, skipWS2);
    mv.visitLabel(endWS2);

    // Match opening quote
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, continueLoop);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '"');
    mv.visitJumpInsn(IF_ICMPNE, continueLoop);
    mv.visitIincInsn(posVar, 1);

    // Check enough chars for backreference
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, content1LenVar);
    mv.visitInsn(IADD);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, continueLoop);

    // Validate backreference
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, content1StartVar);
    mv.visitVarInsn(ILOAD, content1LenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueLoop);

    // Advance pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, content1LenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // Match closing quote
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitIntInsn(BIPUSH, '"');
    Label matchFound = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, matchFound);
    mv.visitJumpInsn(GOTO, continueLoop);

    mv.visitLabel(matchFound);
    // Return start position
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IRETURN);

    // Continue loop
    mv.visitLabel(continueLoop);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    // No match found
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matches() for greedy-any backreference pattern: (.*)\d+\1 or (.+)X\1 Algorithm: Try
   * different group lengths from longest to shortest (greedy backtracking). For each group length,
   * check if separator and backreference match.
   */
  private void generateGreedyAnyBackrefMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int lenVar = allocator.allocate();
    int groupLenVar = allocator.allocate();
    int sepEndVar = allocator.allocate();
    int sepIdxVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);
    // S: []

    // int len = input.length();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> [I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    Label returnFalse = new Label();

    // Get separator info from patternInfo
    RegexNode separatorNode = patternInfo.separatorNode;
    int separatorMin = getSeparatorMinLength(separatorNode);
    int groupMinCount = patternInfo.groupMinCount;
    CharSet separatorCharSet = getSeparatorCharSet(separatorNode);

    // Calculate max group length: (len - separatorMin) / 2
    // groupLen starts at max, decrements down to groupMinCount
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar); // len
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, separatorMin);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    mv.visitInsn(ICONST_2);
    // S: [I, I] -> [I]
    mv.visitInsn(IDIV);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, groupLenVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    // S: []

    // while (groupLen >= groupMinCount)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, groupLenVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, groupMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, loopEnd);

    // sepEnd = len - groupLen (start of backreference)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar); // len
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, groupLenVar); // groupLen
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, sepEndVar);

    Label nextGroupLen = new Label();

    // Check if backreference matches: input.regionMatches(0, input, sepEnd, groupLen)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> [A:String, I]
    mv.visitInsn(ICONST_0);
    // S: [A:String, I] -> [A:String, I, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String, I, A:String] -> [A:String, I, A:String, I]
    mv.visitVarInsn(ILOAD, sepEndVar); // sepEnd
    // S: [A:String, I, A:String, I] -> [A:String, I, A:String, I, I]
    mv.visitVarInsn(ILOAD, groupLenVar); // groupLen
    // S: [A:String, I, A:String, I, I] -> [Z]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    // S: [Z] -> []
    mv.visitJumpInsn(IFEQ, nextGroupLen); // backref doesn't match, try shorter group

    // Backreference matches. Now check separator.
    // Separator spans from groupLen to sepEnd.
    // For separator to match, all chars must be in separatorCharSet.
    if (separatorCharSet != null) {
      // sepIdx = groupLen
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, groupLenVar);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, sepIdxVar);

      Label sepLoopStart = new Label();
      Label sepLoopEnd = new Label();
      Label sepFail = new Label();

      mv.visitLabel(sepLoopStart);
      // S: []

      // while (sepIdx < sepEnd)
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, sepIdxVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, sepEndVar);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPGE, sepLoopEnd);

      // char c = input.charAt(sepIdx)
      // S: [] -> [A:String]
      mv.visitVarInsn(ALOAD, 1);
      // S: [A:String] -> [A:String, I]
      mv.visitVarInsn(ILOAD, sepIdxVar);
      // S: [A:String, I] -> [C]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // Check if char is in separatorCharSet
      generateCharSetCheck(mv, separatorCharSet, sepFail);

      // sepIdx++
      mv.visitIincInsn(sepIdxVar, 1);
      mv.visitJumpInsn(GOTO, sepLoopStart);

      mv.visitLabel(sepFail);
      // S: []
      // Separator doesn't match, try shorter group
      mv.visitJumpInsn(GOTO, nextGroupLen);

      mv.visitLabel(sepLoopEnd);
      // S: []
    }

    // Both backreference and separator match!
    // S: [] -> [I]
    mv.visitInsn(ICONST_1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitLabel(nextGroupLen);
    // S: []
    // groupLen--
    mv.visitIincInsn(groupLenVar, -1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    // S: []
    mv.visitLabel(returnFalse);
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findFrom() for greedy-any backreference pattern. Scans for matches starting at each
   * position.
   */
  private void generateGreedyAnyBackrefFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input, slot 2 = startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int remainingVar = allocator.allocate();
    int groupLenVar = allocator.allocate();
    int sepEndVar = allocator.allocate();
    int sepIdxVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [I]
    mv.visitInsn(ICONST_M1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);
    // S: []

    // int len = input.length();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> [I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = startPos;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, 2);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, posVar);

    RegexNode separatorNode = patternInfo.separatorNode;
    int separatorMin = getSeparatorMinLength(separatorNode);
    int groupMinCount = patternInfo.groupMinCount;
    CharSet separatorCharSet = getSeparatorCharSet(separatorNode);

    // Minimum match length: groupMinCount + separatorMin + groupMinCount
    int minMatchLen = groupMinCount * 2 + separatorMin;

    Label posLoopStart = new Label();
    Label posLoopEnd = new Label();
    Label notFound = new Label();

    mv.visitLabel(posLoopStart);
    // S: []

    // while (pos + minMatchLen <= len)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, minMatchLen);
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGT, posLoopEnd);

    // remaining = len - pos
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, remainingVar);

    // groupLen = (remaining - separatorMin) / 2
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, remainingVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, separatorMin);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    mv.visitInsn(ICONST_2);
    // S: [I, I] -> [I]
    mv.visitInsn(IDIV);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, groupLenVar);

    Label groupLoopStart = new Label();
    Label groupLoopEnd = new Label();

    mv.visitLabel(groupLoopStart);
    // S: []

    // while (groupLen >= groupMinCount)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, groupLenVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, groupMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, groupLoopEnd);

    // sepEnd = pos + remaining - groupLen (backref starts here)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, posVar); // pos
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, remainingVar); // remaining
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, groupLenVar); // groupLen
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, sepEndVar);

    Label nextGroupLen = new Label();

    // Check backref: input.regionMatches(pos, input, sepEnd, groupLen)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, posVar); // pos
    // S: [A:String, I] -> [A:String, I, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String, I, A:String] -> [A:String, I, A:String, I]
    mv.visitVarInsn(ILOAD, sepEndVar); // sepEnd
    // S: [A:String, I, A:String, I] -> [A:String, I, A:String, I, I]
    mv.visitVarInsn(ILOAD, groupLenVar); // groupLen
    // S: [A:String, I, A:String, I, I] -> [Z]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    // S: [Z] -> []
    mv.visitJumpInsn(IFEQ, nextGroupLen);

    // Check separator
    if (separatorCharSet != null) {
      // sepIdx = pos + groupLen
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, groupLenVar);
      // S: [I, I] -> [I]
      mv.visitInsn(IADD);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, sepIdxVar);

      Label sepLoopStart = new Label();
      Label sepLoopEnd = new Label();
      Label sepFail = new Label();

      mv.visitLabel(sepLoopStart);
      // S: []

      // while (sepIdx < sepEnd)
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, sepIdxVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, sepEndVar);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPGE, sepLoopEnd);

      // char c = input.charAt(sepIdx)
      // S: [] -> [A:String]
      mv.visitVarInsn(ALOAD, 1);
      // S: [A:String] -> [A:String, I]
      mv.visitVarInsn(ILOAD, sepIdxVar);
      // S: [A:String, I] -> [C]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      generateCharSetCheck(mv, separatorCharSet, sepFail);

      mv.visitIincInsn(sepIdxVar, 1);
      mv.visitJumpInsn(GOTO, sepLoopStart);

      mv.visitLabel(sepFail);
      // S: []
      mv.visitJumpInsn(GOTO, nextGroupLen);

      mv.visitLabel(sepLoopEnd);
      // S: []
    }

    // Match found at pos
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitLabel(nextGroupLen);
    // S: []
    mv.visitIincInsn(groupLenVar, -1);
    mv.visitJumpInsn(GOTO, groupLoopStart);

    mv.visitLabel(groupLoopEnd);
    // S: []
    // Try next start position
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, posLoopStart);

    mv.visitLabel(posLoopEnd);
    mv.visitLabel(notFound);
    // S: [] -> [I]
    mv.visitInsn(ICONST_M1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Get the minimum length of the separator pattern. */
  private int getSeparatorMinLength(RegexNode separatorNode) {
    if (separatorNode instanceof QuantifierNode) {
      return ((QuantifierNode) separatorNode).min;
    } else if (separatorNode instanceof LiteralNode) {
      return 1;
    } else if (separatorNode instanceof CharClassNode) {
      return 1;
    }
    return 1; // Default to 1
  }

  /**
   * Get the CharSet for the separator (if it's a simple char class quantifier). Only supports
   * single-range charsets for bytecode simplicity.
   */
  private CharSet getSeparatorCharSet(RegexNode separatorNode) {
    CharSet result = null;
    if (separatorNode instanceof QuantifierNode) {
      RegexNode child = ((QuantifierNode) separatorNode).child;
      if (child instanceof CharClassNode) {
        result = ((CharClassNode) child).chars;
      }
    } else if (separatorNode instanceof CharClassNode) {
      result = ((CharClassNode) separatorNode).chars;
    }
    // Only support single-range charsets for simpler bytecode
    if (result != null && result.getRanges().size() > 1) {
      return null;
    }
    return result;
  }

  /**
   * Generate bytecode to check if the char on stack is in the CharSet. If not, jumps to failLabel.
   * Char is consumed from stack. Only supports single-range charsets.
   */
  private void generateCharSetCheck(MethodVisitor mv, CharSet charSet, Label failLabel) {
    // Stack: [C]
    // Check each range in the CharSet (only single-range supported)
    List<CharSet.Range> ranges = charSet.getRanges();
    if (ranges.isEmpty()) {
      // Empty charset - always fail
      mv.visitInsn(POP);
      mv.visitJumpInsn(GOTO, failLabel);
      return;
    }

    if (ranges.size() != 1) {
      // Multi-range not supported - fail
      mv.visitInsn(POP);
      mv.visitJumpInsn(GOTO, failLabel);
      return;
    }

    // Single range - simple check
    CharSet.Range range = ranges.get(0);
    if (range.start == range.end) {
      // Single char
      // S: [C] -> []
      BytecodeUtil.pushInt(mv, range.start);
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
    } else {
      // Range: low <= c <= high
      // Must ensure both paths to failLabel have same stack state (empty)
      // S: [C] -> [C, C]
      mv.visitInsn(DUP);
      // S: [C, C] -> [C] after compare
      BytecodeUtil.pushInt(mv, range.start);
      Label inLowBound = new Label();
      // S: [C, C, I] -> [C] (compare consumes top two)
      mv.visitJumpInsn(IF_ICMPGE, inLowBound); // char >= start, continue
      // char < start: pop remaining copy and fail
      // S: [C] -> []
      mv.visitInsn(POP);
      mv.visitJumpInsn(GOTO, failLabel);

      mv.visitLabel(inLowBound);
      // S: [C]
      // Check high bound
      BytecodeUtil.pushInt(mv, range.end);
      // S: [C, I] -> [] (compare consumes both)
      mv.visitJumpInsn(IF_ICMPGT, failLabel); // char > end, fail with empty stack
      // S: [] - char is in range, success
    }
  }

  /**
   * Generates match() method - returns MatchResult with group information. Currently a placeholder
   * that returns null (group extraction not yet implemented for all types).
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult match(String input) {
   *     return null;  // Placeholder
   * }
   * }</pre>
   */
  public void generateMatchMethod(ClassWriter cw, String className) {
    // For now, return null (groups not yet implemented)
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates matchBounded() method - returns MatchResult for bounded region. Currently a
   * placeholder that returns null.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult matchBounded(String input, int start, int end) {
   *     return null;  // Placeholder
   * }
   * }</pre>
   */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates findMatch() method - delegates to findMatchFrom(input, 0).
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult findMatch(String input) {
   *     return findMatchFrom(input, 0);
   * }
   * }</pre>
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

    // return findMatchFrom(input, 0)
    // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 0);
    // S: [A:this] -> [A:this, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:this, A:String] -> [A:this, A:String, I]
    mv.visitInsn(ICONST_0);
    // S: [A:this, A:String, I] -> [A:MatchResult]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    // S: [A:MatchResult] -> []
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates findMatchFrom() method - returns MatchResult with group captures. Only
   * GREEDY_ANY_BACKREF has a full implementation; other types return null placeholder.
   *
   * <h3>Generated Algorithm (GREEDY_ANY_BACKREF)</h3>
   *
   * <pre>{@code
   * // Pattern: (.*)\d+\1 or similar
   * MatchResult findMatchFrom(String input, int startPos) {
   *     if (input == null) return null;
   *     int len = input.length();
   *
   *     for (int pos = startPos; pos < len; pos++) {
   *         int remaining = len - pos;
   *
   *         // Try group lengths from longest to shortest (greedy)
   *         for (int groupLen = (remaining - separatorMin) / 2; groupLen >= minLen; groupLen--) {
   *             int groupEnd = pos + groupLen;
   *
   *             // Try each possible backreference start position
   *             for (int backrefStart = groupEnd + separatorMin; backrefStart <= len - groupLen; backrefStart++) {
   *                 // Verify separator chars (e.g., all digits for \d+)
   *                 if (!validSeparator(input, groupEnd, backrefStart)) continue;
   *
   *                 // Check backreference matches captured group
   *                 if (input.regionMatches(backrefStart, input, pos, groupLen)) {
   *                     int matchEnd = backrefStart + groupLen;
   *
   *                     // Build MatchResult
   *                     int[] starts = {pos, pos};        // group 0, group 1
   *                     int[] ends = {matchEnd, groupEnd}; // group 0, group 1
   *                     return new MatchResultImpl(input, starts, ends, 1);
   *                 }
   *             }
   *         }
   *     }
   *     return null;
   * }
   * }</pre>
   */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    if (patternInfo.type == BackreferencePatternInfo.BackrefType.GREEDY_ANY_BACKREF) {
      generateGreedyAnyBackrefFindMatchFromMethod(cw, className);
    } else {
      // Placeholder for other pattern types
      MethodVisitor mv =
          cw.visitMethod(
              ACC_PUBLIC,
              "findMatchFrom",
              "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
              null,
              null);
      mv.visitCode();
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }
  }

  /**
   * Generate findMatchFrom() for GREEDY_ANY_BACKREF pattern. Returns MatchResult with proper group
   * captures.
   *
   * <p>Algorithm: For each starting position pos: For each groupLen from max to min (greedy - try
   * longest first): For each backrefStart from pos+groupLen+separatorMin to len-groupLen: If
   * input[backrefStart..backrefStart+groupLen] == input[pos..pos+groupLen]: Verify separator chars
   * in input[pos+groupLen..backrefStart] If valid: MATCH FOUND, return MatchResult
   */
  private void generateGreedyAnyBackrefFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Allocate local variables: slot 0 = this, slot 1 = input, slot 2 = startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int remainingVar = allocator.allocate();
    int groupLenVar = allocator.allocate();
    int groupEndVar = allocator.allocate();
    int backrefStartVar = allocator.allocate();
    int matchEndVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();
    int sepIdxVar = allocator.allocate();
    int sepValidVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);
    // S: []

    // int len = input.length();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> [I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = startPos;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, 2);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, posVar);

    RegexNode separatorNode = patternInfo.separatorNode;
    int separatorMin = getSeparatorMinLength(separatorNode);
    int groupMinCount = patternInfo.groupMinCount;
    CharSet separatorCharSet = getSeparatorCharSet(separatorNode);
    int totalGroupCount = patternInfo.totalGroupCount;

    // Minimum match length: groupMinCount + separatorMin + groupMinCount
    int minMatchLen = groupMinCount * 2 + separatorMin;

    Label posLoopStart = new Label();
    Label posLoopEnd = new Label();

    mv.visitLabel(posLoopStart);
    // S: []

    // while (pos + minMatchLen <= len)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, minMatchLen);
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGT, posLoopEnd);

    // remaining = len - pos
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, remainingVar);

    // groupLen = (remaining - separatorMin) / 2  (start with max possible)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, remainingVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, separatorMin);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    mv.visitInsn(ICONST_2);
    // S: [I, I] -> [I]
    mv.visitInsn(IDIV);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, groupLenVar);

    Label groupLoopStart = new Label();
    Label groupLoopEnd = new Label();

    mv.visitLabel(groupLoopStart);
    // S: []

    // while (groupLen >= groupMinCount)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, groupLenVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, groupMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, groupLoopEnd);

    // groupEnd = pos + groupLen
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, groupLenVar);
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, groupEndVar);

    // backrefStart = groupEnd + separatorMin (earliest possible backref start)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, groupEndVar);
    // S: [I] -> [I, I]
    BytecodeUtil.pushInt(mv, separatorMin);
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, backrefStartVar);

    Label backrefLoopStart = new Label();
    Label backrefLoopEnd = new Label();

    mv.visitLabel(backrefLoopStart);
    // S: []

    // while (backrefStart + groupLen <= len)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, backrefStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, groupLenVar);
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGT, backrefLoopEnd);

    // Check backreference: input.regionMatches(backrefStart, input, pos, groupLen)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, backrefStartVar); // backrefStart
    // S: [A:String, I] -> [A:String, I, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String, I, A:String] -> [A:String, I, A:String, I]
    mv.visitVarInsn(ILOAD, posVar); // pos
    // S: [A:String, I, A:String, I] -> [A:String, I, A:String, I, I]
    mv.visitVarInsn(ILOAD, groupLenVar); // groupLen
    // S: [...] -> [Z]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);

    Label nextBackrefStart = new Label();
    // S: [Z] -> []
    mv.visitJumpInsn(IFEQ, nextBackrefStart);

    // Backref matches! Now verify separator chars (if charset is specified)
    if (separatorCharSet != null) {
      // sepValid = true
      // S: [] -> [I]
      mv.visitInsn(ICONST_1);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, sepValidVar);

      // sepIdx = groupEnd
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, groupEndVar);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, sepIdxVar);

      Label sepLoopStart = new Label();
      Label sepLoopEnd = new Label();

      mv.visitLabel(sepLoopStart);
      // S: []

      // while (sepIdx < backrefStart)
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, sepIdxVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, backrefStartVar);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPGE, sepLoopEnd);

      // char c = input.charAt(sepIdx)
      // S: [] -> [A:String]
      mv.visitVarInsn(ALOAD, 1);
      // S: [A:String] -> [A:String, I]
      mv.visitVarInsn(ILOAD, sepIdxVar);
      // S: [A:String, I] -> [C]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // Check if char is in separator char set
      Label sepCharOk = new Label();
      generateCharSetCheck(mv, separatorCharSet, sepCharOk);

      // Char is in separator set - continue
      mv.visitIincInsn(sepIdxVar, 1);
      mv.visitJumpInsn(GOTO, sepLoopStart);

      mv.visitLabel(sepCharOk);
      // S: []
      // Char is NOT in separator set - separator invalid
      // S: [] -> [I]
      mv.visitInsn(ICONST_0);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, sepValidVar); // sepValid = false

      mv.visitLabel(sepLoopEnd);
      // S: []

      // if (!sepValid) continue to nextBackrefStart
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, sepValidVar);
      // S: [I] -> []
      mv.visitJumpInsn(IFEQ, nextBackrefStart);
    }

    // Match found! Create MatchResult
    // matchEnd = backrefStart + groupLen
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, backrefStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, groupLenVar);
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, matchEndVar);

    // int[] starts = new int[totalGroupCount + 1]
    // S: [] -> [I]
    BytecodeUtil.pushInt(mv, totalGroupCount + 1);
    // S: [I] -> [[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [[I] -> []
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[totalGroupCount + 1]
    // S: [] -> [I]
    BytecodeUtil.pushInt(mv, totalGroupCount + 1);
    // S: [I] -> [[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [[I] -> []
    mv.visitVarInsn(ASTORE, endsVar);

    // starts[0] = pos (match start)
    // S: [] -> [[I]
    mv.visitVarInsn(ALOAD, startsVar);
    // S: [[I] -> [[I, I]
    mv.visitInsn(ICONST_0);
    // S: [[I, I] -> [[I, I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [[I, I, I] -> []
    mv.visitInsn(IASTORE);

    // ends[0] = matchEnd (match end)
    // S: [] -> [[I]
    mv.visitVarInsn(ALOAD, endsVar);
    // S: [[I] -> [[I, I]
    mv.visitInsn(ICONST_0);
    // S: [[I, I] -> [[I, I, I]
    mv.visitVarInsn(ILOAD, matchEndVar);
    // S: [[I, I, I] -> []
    mv.visitInsn(IASTORE);

    // For all groups (1..totalGroupCount), they all capture the same content: [pos, pos+groupLen]
    for (int g = 1; g <= totalGroupCount; g++) {
      // starts[g] = pos
      // S: [] -> [[I]
      mv.visitVarInsn(ALOAD, startsVar);
      // S: [[I] -> [[I, I]
      BytecodeUtil.pushInt(mv, g);
      // S: [[I, I] -> [[I, I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [[I, I, I] -> []
      mv.visitInsn(IASTORE);

      // ends[g] = pos + groupLen
      // S: [] -> [[I]
      mv.visitVarInsn(ALOAD, endsVar);
      // S: [[I] -> [[I, I]
      BytecodeUtil.pushInt(mv, g);
      // S: [[I, I] -> [[I, I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [[I, I, I] -> [[I, I, I, I]
      mv.visitVarInsn(ILOAD, groupLenVar);
      // S: [[I, I, I, I] -> [[I, I, I]
      mv.visitInsn(IADD);
      // S: [[I, I, I] -> []
      mv.visitInsn(IASTORE);
    }

    // return new MatchResultImpl(input, starts, ends, totalGroupCount)
    // S: [] -> [A:MatchResultImpl]
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    // S: [A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl]
    mv.visitInsn(DUP);
    // S: [A:MatchResultImpl, A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [...] -> [..., [I]
    mv.visitVarInsn(ALOAD, startsVar);
    // S: [...] -> [..., [I]
    mv.visitVarInsn(ALOAD, endsVar);
    // S: [...] -> [..., I]
    BytecodeUtil.pushInt(mv, totalGroupCount);
    // S: [...] -> [A:MatchResultImpl]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    // S: [A:MatchResultImpl] -> []
    mv.visitInsn(ARETURN);

    mv.visitLabel(nextBackrefStart);
    // S: []

    // backrefStart++
    mv.visitIincInsn(backrefStartVar, 1);
    mv.visitJumpInsn(GOTO, backrefLoopStart);

    mv.visitLabel(backrefLoopEnd);
    // S: []

    // groupLen--
    mv.visitIincInsn(groupLenVar, -1);
    mv.visitJumpInsn(GOTO, groupLoopStart);

    mv.visitLabel(groupLoopEnd);
    // S: []

    // pos++
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, posLoopStart);

    mv.visitLabel(posLoopEnd);
    // S: []

    // return null (no match found)
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
