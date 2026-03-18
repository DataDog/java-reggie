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

import com.datadoghq.reggie.codegen.analysis.LinearPatternInfo;
import com.datadoghq.reggie.codegen.analysis.LinearPatternInfo.LinearOperation;
import com.datadoghq.reggie.codegen.analysis.LinearPatternInfo.QuantifierData;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates specialized bytecode for linear patterns (no branching/alternation).
 *
 * <h3>Pattern Types</h3>
 *
 * Linear patterns with no alternation: literals, character classes, quantifiers, capturing groups,
 * and backreferences in sequence.
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern (\w+)@\1  (backreference pattern)
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int len = input.length();
 *     int pos = 0;
 *     int[] groupStarts = new int[2];  // group 0, group 1
 *     int[] groupEnds = new int[2];
 *
 *     // Literal/quantifier sequence - tight loop
 *     groupStarts[1] = pos;
 *     while (pos < len && isWordChar(input.charAt(pos))) pos++;
 *     groupEnds[1] = pos;
 *
 *     // Literal '@'
 *     if (pos >= len || input.charAt(pos) != '@') return false;
 *     pos++;
 *
 *     // Backreference \1 using regionMatches
 *     int group1Len = groupEnds[1] - groupStarts[1];
 *     if (pos + group1Len > len) return false;
 *     if (!input.regionMatches(pos, input, groupStarts[1], group1Len)) return false;
 *     pos += group1Len;
 *
 *     return pos == len;
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li>Simple position tracking (int pos)
 *   <li>Group arrays (int[] groupStarts, int[] groupEnds)
 *   <li>Tight loops for quantifiers
 *   <li>String.regionMatches() for backreferences
 *   <li>No BitSet, no state machine, no epsilon closure
 * </ul>
 *
 * <h3>Performance</h3>
 *
 * Expected 10-50x speedup over NFA simulation for backreference patterns due to fully compiled
 * execution with no interpretation overhead.
 */
public class LinearPatternBytecodeGenerator {

  private final LinearPatternInfo patternInfo;
  private final boolean caseInsensitive; // Use case-insensitive comparison for backreferences

  public LinearPatternBytecodeGenerator(LinearPatternInfo patternInfo) {
    this(patternInfo, false);
  }

  public LinearPatternBytecodeGenerator(LinearPatternInfo patternInfo, boolean caseInsensitive) {
    if (patternInfo == null) {
      throw new IllegalArgumentException("Pattern info cannot be null");
    }
    this.patternInfo = patternInfo;
    this.caseInsensitive = caseInsensitive;
  }

  /** Generate matches() method - check if entire input matches pattern. */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int groupStartsVar = allocator.allocate();
    int groupEndsVar = allocator.allocate();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Initialize group arrays if needed
    if (patternInfo.hasBackreferences || patternInfo.groupCount > 0) {
      // int[] groupStarts = new int[groupCount + 1];
      BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupStartsVar);

      // int[] groupEnds = new int[groupCount + 1];
      BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupEndsVar);

      // Initialize to -1 (not captured)
      Label initLoop = new Label();
      Label initEnd = new Label();
      int initIndexVar = allocator.allocate();

      // int i = 0;
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, initIndexVar);

      mv.visitLabel(initLoop);
      // while (i <= groupCount)
      mv.visitVarInsn(ILOAD, initIndexVar);
      BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
      mv.visitJumpInsn(IF_ICMPGE, initEnd);

      // groupStarts[i] = -1;
      mv.visitVarInsn(ALOAD, groupStartsVar);
      mv.visitVarInsn(ILOAD, initIndexVar);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);

      // groupEnds[i] = -1;
      mv.visitVarInsn(ALOAD, groupEndsVar);
      mv.visitVarInsn(ILOAD, initIndexVar);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);

      // i++
      mv.visitIincInsn(initIndexVar, 1);
      mv.visitJumpInsn(GOTO, initLoop);
      mv.visitLabel(initEnd);
    }

    // Generate operation sequence
    Label returnFalse = new Label();
    VarContext ctx =
        new VarContext(
            inputVar, lenVar, posVar, groupStartsVar, groupEndsVar, allocator.peek(), returnFalse);
    generateOperations(mv, patternInfo.operations, ctx);

    // For matches(), must consume entire input
    // if (pos != len) return false;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // return false;
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate find(String) method - wrapper around findFrom. */
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
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // startPos
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
   * Generate findFrom(String, int) method - find match starting at position. Returns position of
   * match, or -1 if not found.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // if (input == null) return -1;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // Variable slot allocation:
    // 0: this
    // 1: input (String)
    // 2: startPos (int)
    // 3: len (int)
    // 4: searchPos (int) - outer loop for trying different start positions
    // 5+: group arrays and match state
    int lenVar = 3;
    int searchPosVar = 4;
    int groupStartsVar = 5;
    int groupEndsVar = 6;
    int posVar = 7; // Current position during matching
    int nextFreeVar = 8;

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Initialize group arrays if needed
    if (patternInfo.hasBackreferences || patternInfo.groupCount > 0) {
      BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupStartsVar);

      BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupEndsVar);
    }

    // int searchPos = startPos;
    mv.visitVarInsn(ILOAD, 2); // startPos
    mv.visitVarInsn(ISTORE, searchPosVar);

    Label searchLoop = new Label();
    Label searchEnd = new Label();
    Label tryMatch = new Label();

    // Outer loop: try matching at each position
    mv.visitLabel(searchLoop);

    // while (searchPos <= len)
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, searchEnd);

    // Reset group arrays
    if (patternInfo.hasBackreferences || patternInfo.groupCount > 0) {
      Label resetLoop = new Label();
      Label resetEnd = new Label();
      int resetIndexVar = nextFreeVar++;

      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, resetIndexVar);

      mv.visitLabel(resetLoop);
      mv.visitVarInsn(ILOAD, resetIndexVar);
      BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
      mv.visitJumpInsn(IF_ICMPGE, resetEnd);

      mv.visitVarInsn(ALOAD, groupStartsVar);
      mv.visitVarInsn(ILOAD, resetIndexVar);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);

      mv.visitVarInsn(ALOAD, groupEndsVar);
      mv.visitVarInsn(ILOAD, resetIndexVar);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);

      mv.visitIincInsn(resetIndexVar, 1);
      mv.visitJumpInsn(GOTO, resetLoop);
      mv.visitLabel(resetEnd);
    }

    // int pos = searchPos;
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Try matching from this position
    Label matchFailed = new Label();
    VarContext ctx =
        new VarContext(1, lenVar, posVar, groupStartsVar, groupEndsVar, nextFreeVar, matchFailed);
    generateOperations(mv, patternInfo.operations, ctx);

    // Match succeeded - return searchPos
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitInsn(IRETURN);

    // Match failed - try next position
    mv.visitLabel(matchFailed);
    mv.visitIincInsn(searchPosVar, 1);
    mv.visitJumpInsn(GOTO, searchLoop);

    // No match found
    mv.visitLabel(searchEnd);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate bytecode for a sequence of operations. */
  private void generateOperations(
      MethodVisitor mv, List<LinearOperation> operations, VarContext ctx) {
    for (LinearOperation op : operations) {
      generateOperation(mv, op, ctx);
    }
  }

  /** Generate bytecode for a single operation. */
  private void generateOperation(MethodVisitor mv, LinearOperation op, VarContext ctx) {
    switch (op.type) {
      case MATCH_LITERAL:
        generateLiteralMatch(mv, (String) op.data, ctx);
        break;
      case MATCH_CHARCLASS:
        generateCharClassMatch(mv, (CharSet) op.data, ctx);
        break;
      case MATCH_QUANTIFIER:
        generateQuantifierMatch(mv, (QuantifierData) op.data, ctx);
        break;
      case START_GROUP:
        generateGroupStart(mv, (Integer) op.data, ctx);
        break;
      case END_GROUP:
        generateGroupEnd(mv, (Integer) op.data, ctx);
        break;
      case CHECK_BACKREF:
        generateBackrefCheck(mv, (Integer) op.data, ctx);
        break;
      case CHECK_ANCHOR:
        generateAnchorCheck(mv, (LinearPatternInfo.AnchorType) op.data, ctx);
        break;
      case CHECK_WORD_BOUNDARY:
        generateWordBoundaryCheck(mv, (Boolean) op.data, ctx);
        break;
      default:
        throw new UnsupportedOperationException("Operation not yet implemented: " + op.type);
    }
  }

  /**
   * Generate: match literal string. if (!input.regionMatches(pos, literal, 0, literal.length()))
   * fail; pos += literal.length();
   */
  private void generateLiteralMatch(MethodVisitor mv, String literal, VarContext ctx) {
    if (literal.isEmpty()) {
      return; // Nothing to match
    }

    // Check bounds: if (pos + literal.length() > len) fail;
    mv.visitVarInsn(ILOAD, ctx.posVar);
    BytecodeUtil.pushInt(mv, literal.length());
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPGT, ctx.failLabel);

    // Check match: if (!input.regionMatches(pos, literal, 0, literal.length())) fail;
    mv.visitVarInsn(ALOAD, ctx.inputVar);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitLdcInsn(literal);
    mv.visitInsn(ICONST_0);
    BytecodeUtil.pushInt(mv, literal.length());
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    Label matched = new Label();
    mv.visitJumpInsn(IFNE, matched);
    mv.visitJumpInsn(GOTO, ctx.failLabel);
    mv.visitLabel(matched);

    // pos += literal.length();
    mv.visitIincInsn(ctx.posVar, literal.length());
  }

  /**
   * Generate: match single character from character class. if (pos >= len) fail; char ch =
   * input.charAt(pos); if (!charSet.contains(ch)) fail; pos++;
   */
  private void generateCharClassMatch(MethodVisitor mv, CharSet charSet, VarContext ctx) {
    // Check bounds
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPGE, ctx.failLabel);

    // char ch = input.charAt(pos);
    int chVar = ctx.allocateTemp();
    mv.visitVarInsn(ALOAD, ctx.inputVar);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // Check if ch is in character class
    generateCharSetCheck(mv, charSet, chVar, ctx.failLabel);

    // pos++
    mv.visitIincInsn(ctx.posVar, 1);
  }

  /** Generate: quantifier loop (+ * ? {n,m}). */
  private void generateQuantifierMatch(MethodVisitor mv, QuantifierData quantData, VarContext ctx) {
    if (!quantData.greedy) {
      throw new UnsupportedOperationException(
          "Reluctant quantifiers not yet supported in linear patterns");
    }

    int min = quantData.min;
    int max = quantData.max;

    if (max == 0) {
      return; // Zero matches - nothing to do
    }

    // For fixed count {n}, just repeat n times
    if (min == max && max >= 0) {
      for (int i = 0; i < min; i++) {
        generateOperations(mv, quantData.childOperations, ctx);
      }
      return;
    }

    // For variable count, use a loop
    int countVar = ctx.allocateTemp();
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    mv.visitLabel(loopStart);

    // Check maximum bound (if not unlimited)
    if (max > 0) {
      // if (count >= max) break;
      mv.visitVarInsn(ILOAD, countVar);
      BytecodeUtil.pushInt(mv, max);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // Try matching child operations
    // Save current position in case match fails
    int savedPosVar = ctx.allocateTemp();
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ISTORE, savedPosVar);

    // Try matching - if fails, restore position and break
    Label childFailed = new Label();
    Label originalFailLabel = ctx.failLabel;
    ctx.failLabel = childFailed;

    generateOperations(mv, quantData.childOperations, ctx);

    // Match succeeded
    ctx.failLabel = originalFailLabel;

    // count++
    mv.visitIincInsn(countVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    // Child match failed - restore position
    mv.visitLabel(childFailed);
    ctx.failLabel = originalFailLabel;
    mv.visitVarInsn(ILOAD, savedPosVar);
    mv.visitVarInsn(ISTORE, ctx.posVar);

    mv.visitLabel(loopEnd);

    // Check minimum bound
    // if (count < min) fail;
    mv.visitVarInsn(ILOAD, countVar);
    BytecodeUtil.pushInt(mv, min);
    mv.visitJumpInsn(IF_ICMPLT, ctx.failLabel);
  }

  /** Generate: groupStarts[groupNum] = pos; */
  private void generateGroupStart(MethodVisitor mv, int groupNum, VarContext ctx) {
    mv.visitVarInsn(ALOAD, ctx.groupStartsVar);
    BytecodeUtil.pushInt(mv, groupNum);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitInsn(IASTORE);
  }

  /** Generate: groupEnds[groupNum] = pos; */
  private void generateGroupEnd(MethodVisitor mv, int groupNum, VarContext ctx) {
    mv.visitVarInsn(ALOAD, ctx.groupEndsVar);
    BytecodeUtil.pushInt(mv, groupNum);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitInsn(IASTORE);
  }

  /**
   * Generate: backreference check. int groupStart = groupStarts[groupNum]; int groupEnd =
   * groupEnds[groupNum]; if (groupStart < 0) fail; // group not captured int groupLen = groupEnd -
   * groupStart; if (pos + groupLen > len) fail; if (!input.regionMatches(pos, input, groupStart,
   * groupLen)) fail; pos += groupLen;
   */
  private void generateBackrefCheck(MethodVisitor mv, int groupNum, VarContext ctx) {
    int groupStartVar = ctx.allocateTemp();
    int groupEndVar = ctx.allocateTemp();
    int groupLenVar = ctx.allocateTemp();

    // int groupStart = groupStarts[groupNum];
    mv.visitVarInsn(ALOAD, ctx.groupStartsVar);
    BytecodeUtil.pushInt(mv, groupNum);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, groupStartVar);

    // if (groupStart < 0) fail;
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitJumpInsn(IFLT, ctx.failLabel);

    // int groupEnd = groupEnds[groupNum];
    mv.visitVarInsn(ALOAD, ctx.groupEndsVar);
    BytecodeUtil.pushInt(mv, groupNum);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // int groupLen = groupEnd - groupStart;
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // if (pos + groupLen > len) fail;
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPGT, ctx.failLabel);

    // if (!input.regionMatches([ignoreCase,] pos, input, groupStart, groupLen)) fail;
    mv.visitVarInsn(ALOAD, ctx.inputVar);
    if (caseInsensitive) {
      // Use 5-parameter version: regionMatches(boolean ignoreCase, int toffset, String other, int
      // ooffset, int len)
      mv.visitInsn(ICONST_1); // ignoreCase = true
      mv.visitVarInsn(ILOAD, ctx.posVar); // toffset
      mv.visitVarInsn(ALOAD, ctx.inputVar); // other
      mv.visitVarInsn(ILOAD, groupStartVar); // ooffset
      mv.visitVarInsn(ILOAD, groupLenVar); // len
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ZILjava/lang/String;II)Z", false);
    } else {
      // Use 4-parameter version: regionMatches(int toffset, String other, int ooffset, int len)
      mv.visitVarInsn(ILOAD, ctx.posVar); // toffset
      mv.visitVarInsn(ALOAD, ctx.inputVar); // other
      mv.visitVarInsn(ILOAD, groupStartVar); // ooffset
      mv.visitVarInsn(ILOAD, groupLenVar); // len
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    }
    Label matched = new Label();
    mv.visitJumpInsn(IFNE, matched);
    mv.visitJumpInsn(GOTO, ctx.failLabel);
    mv.visitLabel(matched);

    // pos += groupLen;
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, ctx.posVar);
  }

  /** Generate: anchor check (^ or $). */
  private void generateAnchorCheck(
      MethodVisitor mv, LinearPatternInfo.AnchorType anchorType, VarContext ctx) {
    switch (anchorType) {
      case START:
        // if (pos != 0) fail;
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitJumpInsn(IFNE, ctx.failLabel);
        break;
      case END:
        // if (pos != len) fail;
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitVarInsn(ILOAD, ctx.lenVar);
        mv.visitJumpInsn(IF_ICMPNE, ctx.failLabel);
        break;
      case START_MULTILINE:
        // if (pos != 0 && (pos == 0 || input.charAt(pos-1) != '\n')) fail;
        // Simplified: if (pos == 0 || input.charAt(pos-1) == '\n') pass; else fail;
        Label startOk = new Label();

        // if (pos == 0) goto startOk;
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitJumpInsn(IFEQ, startOk);

        // if (input.charAt(pos-1) == '\n') goto startOk;
        mv.visitVarInsn(ALOAD, ctx.inputVar);
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\n');
        mv.visitJumpInsn(IF_ICMPEQ, startOk);

        // fail;
        mv.visitJumpInsn(GOTO, ctx.failLabel);

        mv.visitLabel(startOk);
        break;
      case END_MULTILINE:
        // if (pos == len || input.charAt(pos) == '\n') pass; else fail;
        Label endOk = new Label();

        // if (pos == len) goto endOk;
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitVarInsn(ILOAD, ctx.lenVar);
        mv.visitJumpInsn(IF_ICMPEQ, endOk);

        // if (input.charAt(pos) == '\n') goto endOk;
        mv.visitVarInsn(ALOAD, ctx.inputVar);
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        BytecodeUtil.pushInt(mv, '\n');
        mv.visitJumpInsn(IF_ICMPEQ, endOk);

        // fail;
        mv.visitJumpInsn(GOTO, ctx.failLabel);

        mv.visitLabel(endOk);
        break;
    }
  }

  /**
   * Generate: word boundary check (\b or \B).
   *
   * <p>Word boundary \b: matches when one side is word char and other is not Non-word-boundary \B:
   * matches when both sides are same (both word or both non-word)
   *
   * <p>Algorithm: boolean beforeIsWord = (pos > 0) && isWordChar(input.charAt(pos-1)); boolean
   * afterIsWord = (pos < len) && isWordChar(input.charAt(pos)); boolean isBoundary = (beforeIsWord
   * != afterIsWord); if (positive && !isBoundary) fail; // \b if (!positive && isBoundary) fail; //
   * \B
   */
  private void generateWordBoundaryCheck(MethodVisitor mv, boolean positive, VarContext ctx) {
    int beforeIsWordVar = ctx.allocateTemp();
    int afterIsWordVar = ctx.allocateTemp();

    Label checkAfter = new Label();
    Label afterChecked = new Label();
    Label checkBoundary = new Label();

    // Check before position: beforeIsWord = (pos > 0) && isWordChar(input.charAt(pos-1))
    mv.visitInsn(ICONST_0); // default to false
    mv.visitVarInsn(ISTORE, beforeIsWordVar);

    // if (pos > 0)
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitJumpInsn(IFLE, checkAfter);

    // char beforeCh = input.charAt(pos - 1);
    int beforeChVar = ctx.allocateTemp();
    mv.visitVarInsn(ALOAD, ctx.inputVar);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, beforeChVar);

    // beforeIsWord = isWordChar(beforeCh)
    Label beforeNotWord = new Label();
    generateIsWordCharCheck(mv, beforeChVar, beforeNotWord);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ISTORE, beforeIsWordVar);
    mv.visitLabel(beforeNotWord);

    // Check after position: afterIsWord = (pos < len) && isWordChar(input.charAt(pos))
    mv.visitLabel(checkAfter);
    mv.visitInsn(ICONST_0); // default to false
    mv.visitVarInsn(ISTORE, afterIsWordVar);

    // if (pos < len)
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPGE, checkBoundary);

    // char afterCh = input.charAt(pos);
    int afterChVar = ctx.allocateTemp();
    mv.visitVarInsn(ALOAD, ctx.inputVar);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, afterChVar);

    // afterIsWord = isWordChar(afterCh)
    Label afterNotWord = new Label();
    generateIsWordCharCheck(mv, afterChVar, afterNotWord);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ISTORE, afterIsWordVar);
    mv.visitLabel(afterNotWord);

    // Check boundary: isBoundary = (beforeIsWord != afterIsWord)
    mv.visitLabel(checkBoundary);
    mv.visitVarInsn(ILOAD, beforeIsWordVar);
    mv.visitVarInsn(ILOAD, afterIsWordVar);

    if (positive) {
      // \b: want boundary (beforeIsWord != afterIsWord)
      // if (beforeIsWord == afterIsWord) fail;
      mv.visitJumpInsn(IF_ICMPEQ, ctx.failLabel);
    } else {
      // \B: want non-boundary (beforeIsWord == afterIsWord)
      // if (beforeIsWord != afterIsWord) fail;
      mv.visitJumpInsn(IF_ICMPNE, ctx.failLabel);
    }
  }

  /**
   * Generate: check if character is a word character [a-zA-Z0-9_]. If not a word char, jumps to
   * notWordLabel. Otherwise falls through.
   */
  private void generateIsWordCharCheck(MethodVisitor mv, int chVar, Label notWordLabel) {
    Label isWord = new Label();

    // ch >= 'a' && ch <= 'z'
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'a');
    Label notLower = new Label();
    mv.visitJumpInsn(IF_ICMPLT, notLower);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'z');
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
    mv.visitJumpInsn(IF_ICMPEQ, isWord);

    // Not a word char - jump to notWordLabel
    mv.visitJumpInsn(GOTO, notWordLabel);

    mv.visitLabel(isWord);
    // Is a word char - fall through
  }

  /**
   * Generate: character set check. Uses optimized checks for common character classes (\w, \d, \s).
   */
  private void generateCharSetCheck(MethodVisitor mv, CharSet charSet, int chVar, Label failLabel) {
    // Check for common character classes using CharSet constants
    if (charSet.equals(CharSet.WORD)) {
      // \w: [a-zA-Z0-9_]
      generateWordCharCheck(mv, chVar, failLabel);
    } else if (charSet.equals(CharSet.DIGIT)) {
      // \d: [0-9]
      generateDigitCheck(mv, chVar, failLabel);
    } else if (charSet.equals(CharSet.WHITESPACE)) {
      // \s: whitespace
      generateWhitespaceCheck(mv, chVar, failLabel);
    } else {
      // Generic character set check - generate range checks for all ranges in the CharSet
      // TODO: Optimize this for performance (e.g., bitmap-based checks for small ranges)
      Label matched = new Label();

      // For each range in the character set, check if ch is in range
      for (CharSet.Range range : charSet.getRanges()) {
        // if (ch >= range.start && ch <= range.end) goto matched;
        mv.visitVarInsn(ILOAD, chVar);
        BytecodeUtil.pushInt(mv, range.start);
        Label notInRange = new Label();
        mv.visitJumpInsn(IF_ICMPLT, notInRange);

        mv.visitVarInsn(ILOAD, chVar);
        BytecodeUtil.pushInt(mv, range.end);
        mv.visitJumpInsn(IF_ICMPLE, matched);

        mv.visitLabel(notInRange);
      }

      // No range matched - fail
      mv.visitJumpInsn(GOTO, failLabel);

      mv.visitLabel(matched);
    }
  }

  /** Generate: \w check (word character: [a-zA-Z0-9_]). */
  private void generateWordCharCheck(MethodVisitor mv, int chVar, Label failLabel) {
    Label isWord = new Label();

    // ch >= 'a' && ch <= 'z'
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'a');
    Label notLower = new Label();
    mv.visitJumpInsn(IF_ICMPLT, notLower);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, 'z');
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
    mv.visitJumpInsn(IF_ICMPEQ, isWord);

    // Not a word char - fail
    mv.visitJumpInsn(GOTO, failLabel);

    mv.visitLabel(isWord);
  }

  /** Generate: \d check (digit: [0-9]). */
  private void generateDigitCheck(MethodVisitor mv, int chVar, Label failLabel) {
    // ch >= '0' && ch <= '9'
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '0');
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitIntInsn(BIPUSH, '9');
    mv.visitJumpInsn(IF_ICMPGT, failLabel);
  }

  /** Generate: \s check (whitespace). */
  private void generateWhitespaceCheck(MethodVisitor mv, int chVar, Label failLabel) {
    Label isWhitespace = new Label();

    // Use Character.isWhitespace(ch)
    mv.visitVarInsn(ILOAD, chVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Character", "isWhitespace", "(C)Z", false);
    mv.visitJumpInsn(IFNE, isWhitespace);

    // Not whitespace - fail
    mv.visitJumpInsn(GOTO, failLabel);

    mv.visitLabel(isWhitespace);
  }

  /** Context for variable slot allocation during bytecode generation. */
  private static class VarContext {
    final int inputVar;
    final int lenVar;
    final int posVar;
    final int groupStartsVar;
    final int groupEndsVar;
    int nextFreeVar;
    Label failLabel;

    VarContext(
        int inputVar,
        int lenVar,
        int posVar,
        int groupStartsVar,
        int groupEndsVar,
        int nextFreeVar,
        Label failLabel) {
      this.inputVar = inputVar;
      this.lenVar = lenVar;
      this.posVar = posVar;
      this.groupStartsVar = groupStartsVar;
      this.groupEndsVar = groupEndsVar;
      this.nextFreeVar = nextFreeVar;
      this.failLabel = failLabel;
    }

    int allocateTemp() {
      return nextFreeVar++;
    }
  }

  /** Generate match() method - returns MatchResult for full string match. */
  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // if (!matches(input)) return null;
    Label matchSuccess = new Label();
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitJumpInsn(IFNE, matchSuccess);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchSuccess);

    // new MatchResultImpl(input, new int[]{0, ...}, new int[]{input.length(), ...}, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input

    // starts array
    BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    for (int i = 1; i <= patternInfo.groupCount; i++) {
      mv.visitInsn(DUP);
      BytecodeUtil.pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // ends array
    BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);
    for (int i = 1; i <= patternInfo.groupCount; i++) {
      mv.visitInsn(DUP);
      BytecodeUtil.pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    BytecodeUtil.pushInt(mv, patternInfo.groupCount);

    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);

    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchesBounded() method. */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // For now, delegate to string-based matches
    // TODO: Optimize for CharSequence if needed
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchBounded() method. */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // if (!matchesBounded(input, start, end)) return null;
    Label matchSuccess = new Label();
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesBounded",
        "(Ljava/lang/CharSequence;II)Z",
        false);
    mv.visitJumpInsn(IFNE, matchSuccess);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchSuccess);

    // new MatchResultImpl(input.toString(), starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);

    // starts array
    BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitInsn(IASTORE);
    for (int i = 1; i <= patternInfo.groupCount; i++) {
      mv.visitInsn(DUP);
      BytecodeUtil.pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // ends array
    BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitInsn(IASTORE);
    for (int i = 1; i <= patternInfo.groupCount; i++) {
      mv.visitInsn(DUP);
      BytecodeUtil.pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    BytecodeUtil.pushInt(mv, patternInfo.groupCount);

    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);

    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findMatch() method. */
  public void generateFindMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // return findMatchFrom(input, 0);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findMatchFrom() method. This method does the full matching with group tracking (not
   * just position).
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

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // Variable slot allocation:
    // 0: this
    // 1: input (String)
    // 2: startPos (int)
    // 3: len (int)
    // 4: searchPos (int) - outer loop for trying different start positions
    // 5: groupStarts (int[])
    // 6: groupEnds (int[])
    // 7: pos (int) - current position during matching
    // 8+: temporaries
    int lenVar = 3;
    int searchPosVar = 4;
    int groupStartsVar = 5;
    int groupEndsVar = 6;
    int posVar = 7;
    int nextFreeVar = 8;

    // int len = input.length();
    // S: [] -> [I]
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    // int[] groupStarts = new int[groupCount + 1];
    // S: [] -> [I]
    BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
    // S: [I] -> [A:int[]]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:int[]] -> []
    mv.visitVarInsn(ASTORE, groupStartsVar);

    // int[] groupEnds = new int[groupCount + 1];
    // S: [] -> [I]
    BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
    // S: [I] -> [A:int[]]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:int[]] -> []
    mv.visitVarInsn(ASTORE, groupEndsVar);

    // int searchPos = startPos;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, 2); // startPos
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, searchPosVar);

    Label searchLoop = new Label();
    Label searchEnd = new Label();

    // Outer loop: try matching at each position
    mv.visitLabel(searchLoop);
    // S: []

    // while (searchPos <= len)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, searchPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGT, searchEnd);

    // Reset group arrays to -1
    int resetIndexVar = nextFreeVar++;
    Label resetLoop = new Label();
    Label resetEnd = new Label();

    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, resetIndexVar);

    mv.visitLabel(resetLoop);
    // S: []
    mv.visitVarInsn(ILOAD, resetIndexVar);
    BytecodeUtil.pushInt(mv, patternInfo.groupCount + 1);
    mv.visitJumpInsn(IF_ICMPGE, resetEnd);

    // groupStarts[i] = -1;
    mv.visitVarInsn(ALOAD, groupStartsVar);
    mv.visitVarInsn(ILOAD, resetIndexVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);

    // groupEnds[i] = -1;
    mv.visitVarInsn(ALOAD, groupEndsVar);
    mv.visitVarInsn(ILOAD, resetIndexVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);

    mv.visitIincInsn(resetIndexVar, 1);
    mv.visitJumpInsn(GOTO, resetLoop);
    mv.visitLabel(resetEnd);
    // S: []

    // int pos = searchPos;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, searchPosVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, posVar);

    // Try matching from this position
    Label matchFailed = new Label();
    VarContext ctx =
        new VarContext(1, lenVar, posVar, groupStartsVar, groupEndsVar, nextFreeVar, matchFailed);
    generateOperations(mv, patternInfo.operations, ctx);

    // Match succeeded!
    // Set group 0 bounds: groupStarts[0] = searchPos; groupEnds[0] = pos;
    // S: [] -> [A:int[]]
    mv.visitVarInsn(ALOAD, groupStartsVar);
    // S: [A:int[]] -> [A:int[], I]
    mv.visitInsn(ICONST_0);
    // S: [A:int[], I] -> [A:int[], I, I]
    mv.visitVarInsn(ILOAD, searchPosVar);
    // S: [A:int[], I, I] -> []
    mv.visitInsn(IASTORE);

    // S: [] -> [A:int[]]
    mv.visitVarInsn(ALOAD, groupEndsVar);
    // S: [A:int[]] -> [A:int[], I]
    mv.visitInsn(ICONST_0);
    // S: [A:int[], I] -> [A:int[], I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [A:int[], I, I] -> []
    mv.visitInsn(IASTORE);

    // return new MatchResultImpl(input, groupStarts, groupEnds, groupCount);
    // S: [] -> [A:MatchResultImpl]
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    // S: [...] -> [..., A:MatchResultImpl]
    mv.visitInsn(DUP);
    // S: [...] -> [..., A:String]
    mv.visitVarInsn(ALOAD, 1); // input
    // S: [...] -> [..., A:int[]]
    mv.visitVarInsn(ALOAD, groupStartsVar);
    // S: [...] -> [..., A:int[]]
    mv.visitVarInsn(ALOAD, groupEndsVar);
    // S: [...] -> [..., I]
    BytecodeUtil.pushInt(mv, patternInfo.groupCount);
    // S: [...] -> [A:MatchResultImpl]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    // S: [A:MatchResultImpl] -> []
    mv.visitInsn(ARETURN);

    // Match failed - try next position
    mv.visitLabel(matchFailed);
    // S: []
    mv.visitIincInsn(searchPosVar, 1);
    mv.visitJumpInsn(GOTO, searchLoop);

    // No match found
    mv.visitLabel(searchEnd);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findBoundsFrom() method. */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // int pos = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, 4); // pos

    // if (pos < 0) return false;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(found);

    // bounds[0] = pos;
    mv.visitVarInsn(ALOAD, 3); // bounds
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 4); // pos
    mv.visitInsn(IASTORE);

    // bounds[1] = input.length(); // TODO: calculate actual end
    mv.visitVarInsn(ALOAD, 3); // bounds
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);

    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
