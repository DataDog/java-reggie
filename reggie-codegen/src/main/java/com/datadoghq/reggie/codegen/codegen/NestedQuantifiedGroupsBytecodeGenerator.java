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

import com.datadoghq.reggie.codegen.analysis.NestedQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.NestedQuantifiedGroupsInfo.QuantifierLevel;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Bytecode generator for NESTED_QUANTIFIED_GROUPS strategy.
 *
 * <p>Generates optimized matching code for patterns with nested quantifiers on capturing groups,
 * implementing POSIX last-match semantics.
 *
 * <h3>Pattern Examples</h3>
 *
 * <ul>
 *   <li>{@code ((a|bc)+)*} - outer * with inner + on alternation
 *   <li>{@code ((a+|b)*)?c} - optional outer with inner * on alternation
 *   <li>{@code ^((a|b)+)*ax} - with prefix/suffix
 * </ul>
 *
 * <h3>POSIX Semantics</h3>
 *
 * Groups capture from the LAST iteration of their containing quantifier:
 *
 * <ul>
 *   <li>{@code ((a)+)*} matching "aaa" → Group 1 = "a" (last outer iteration)
 *   <li>Not "aaa" (all iterations concatenated)
 * </ul>
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * boolean matches(String input) {
 *     int pos = 0, len = input.length();
 *
 *     // Track last iteration's group boundaries (POSIX semantics)
 *     int group1Start = -1, group1End = -1;
 *
 *     // Outer loop (for * or + quantifier)
 *     int outerCount = 0;
 *     while (pos < len) {
 *         int outerIterStart = pos;
 *         int innerCount = 0;
 *
 *         // Inner loop (for + or * quantifier inside)
 *         while (pos < len && matchInnerContent(input, pos)) {
 *             innerCount++;
 *             pos = newPos;
 *         }
 *
 *         if (innerCount >= innerMin) {
 *             outerCount++;
 *             // Update group (POSIX: LAST iteration only)
 *             group1Start = outerIterStart;
 *             group1End = pos;
 *         } else {
 *             break;
 *         }
 *     }
 *
 *     return outerCount >= outerMin && pos == len;
 * }
 * }</pre>
 */
public class NestedQuantifiedGroupsBytecodeGenerator {

  private final NestedQuantifiedGroupsInfo info;
  private final String className;

  public NestedQuantifiedGroupsBytecodeGenerator(
      NestedQuantifiedGroupsInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Generate all required methods for ReggieMatcher. */
  public void generate(ClassWriter cw) {
    generateMatchesMethod(cw);
    generateFindMethod(cw);
    generateFindFromMethod(cw);
    // Generate stub methods for MatchResult-returning methods
    generateMatchStubMethod(cw);
    generateMatchesBoundedStubMethod(cw);
    generateMatchBoundedStubMethod(cw);
    generateFindMatchStubMethod(cw);
    generateFindMatchFromStubMethod(cw);
  }

  /** Generate matches() method with nested loops for quantifiers. */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int posVar = alloc.allocate();

    // Allocate vars for group tracking (if any capturing groups)
    int[] groupStartVars = new int[info.totalGroupCount + 1];
    int[] groupEndVars = new int[info.totalGroupCount + 1];
    for (int i = 1; i <= info.totalGroupCount; i++) {
      groupStartVars[i] = alloc.allocate();
      groupEndVars[i] = alloc.allocate();
    }

    // Allocate counter vars for each nesting level
    int[] countVars = new int[info.levels.size()];
    int[] iterStartVars = new int[info.levels.size()];
    for (int i = 0; i < info.levels.size(); i++) {
      countVars[i] = alloc.allocate();
      iterStartVars[i] = alloc.allocate();
    }

    Label returnFalse = new Label();

    // Null check
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

    // Initialize group vars to -1
    for (int i = 1; i <= info.totalGroupCount; i++) {
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, groupStartVars[i]);
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, groupEndVars[i]);
    }

    // Generate nested loops for the quantifier levels
    if (info.levels.size() >= 2) {
      generateNestedLoops(
          mv,
          alloc,
          posVar,
          lenVar,
          countVars,
          iterStartVars,
          groupStartVars,
          groupEndVars,
          returnFalse,
          0);
    }

    // Check outer quantifier min is satisfied
    QuantifierLevel outerLevel = info.getOuterLevel();
    if (outerLevel != null && outerLevel.min > 0) {
      mv.visitVarInsn(ILOAD, countVars[0]);
      pushInt(mv, outerLevel.min);
      mv.visitJumpInsn(IF_ICMPLT, returnFalse);
    }

    // For matches(), require pos == len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);

    // Return true
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /** Generate nested loops for the quantifier levels. */
  private void generateNestedLoops(
      MethodVisitor mv,
      LocalVarAllocator alloc,
      int posVar,
      int lenVar,
      int[] countVars,
      int[] iterStartVars,
      int[] groupStartVars,
      int[] groupEndVars,
      Label returnFalse,
      int levelIdx) {

    if (levelIdx >= info.levels.size()) {
      return;
    }

    QuantifierLevel level = info.levels.get(levelIdx);
    int countVar = countVars[levelIdx];
    int iterStartVar = iterStartVars[levelIdx];
    boolean isLastLevel = (levelIdx == info.levels.size() - 1);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    // Initialize count = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // Loop start
    mv.visitLabel(loopStart);

    // Check pos < len (for unbounded loops)
    if (level.isUnbounded()) {
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // Save iteration start position
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, iterStartVar);

    if (isLastLevel) {
      // Innermost level: match the actual content
      generateContentMatch(mv, level, posVar, lenVar, loopEnd);
    } else {
      // Intermediate level: recurse to inner loop
      int innerCountVar = countVars[levelIdx + 1];

      // Initialize inner count
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, innerCountVar);

      // Generate inner loop
      generateNestedLoops(
          mv,
          alloc,
          posVar,
          lenVar,
          countVars,
          iterStartVars,
          groupStartVars,
          groupEndVars,
          loopEnd,
          levelIdx + 1);

      // Check inner min is satisfied
      QuantifierLevel innerLevel = info.levels.get(levelIdx + 1);
      if (innerLevel.min > 0) {
        mv.visitVarInsn(ILOAD, innerCountVar);
        pushInt(mv, innerLevel.min);
        mv.visitJumpInsn(IF_ICMPLT, loopEnd);
      }
    }

    // Iteration successful, increment count
    mv.visitIincInsn(countVar, 1);

    // Update group capture if this level has a capturing group (POSIX: last iteration)
    if (level.groupNumber > 0 && level.groupNumber <= info.totalGroupCount) {
      mv.visitVarInsn(ILOAD, iterStartVar);
      mv.visitVarInsn(ISTORE, groupStartVars[level.groupNumber]);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, groupEndVars[level.groupNumber]);
    }

    // Check max bound (if not unbounded)
    if (!level.isUnbounded()) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, level.max);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // Continue loop
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
  }

  /** Generate code to match the innermost content (charset, literal, or alternation). */
  private void generateContentMatch(
      MethodVisitor mv, QuantifierLevel level, int posVar, int lenVar, Label failLabel) {

    if (level.charSet != null) {
      // Match charset
      generateCharSetMatch(mv, level.charSet, posVar, lenVar, failLabel);
      mv.visitIincInsn(posVar, 1);
    } else if (level.literal != null && level.literal.length() == 1) {
      // Match single literal char
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, level.literal.charAt(0));
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
      mv.visitIincInsn(posVar, 1);
    } else {
      // General case: accept any char for now
      // TODO: Handle complex alternations
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, failLabel);
      mv.visitIincInsn(posVar, 1);
    }
  }

  /** Generate charset matching code. */
  private void generateCharSetMatch(
      MethodVisitor mv, CharSet charSet, int posVar, int lenVar, Label failLabel) {

    // Load char at position
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    if (charSet.equals(CharSet.DIGIT)) {
      Label ok = new Label();
      mv.visitInsn(DUP);
      pushInt(mv, '0');
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
      pushInt(mv, '9');
      mv.visitJumpInsn(IF_ICMPGT, failLabel);
      mv.visitLabel(ok);
    } else if (charSet.equals(CharSet.WORD)) {
      Label ok = new Label();
      Label checkUpper = new Label();
      Label checkDigit = new Label();
      Label checkUnderscore = new Label();

      mv.visitInsn(DUP);
      pushInt(mv, 'a');
      mv.visitJumpInsn(IF_ICMPLT, checkUpper);
      mv.visitInsn(DUP);
      pushInt(mv, 'z');
      mv.visitJumpInsn(IF_ICMPLE, ok);

      mv.visitLabel(checkUpper);
      mv.visitInsn(DUP);
      pushInt(mv, 'A');
      mv.visitJumpInsn(IF_ICMPLT, checkDigit);
      mv.visitInsn(DUP);
      pushInt(mv, 'Z');
      mv.visitJumpInsn(IF_ICMPLE, ok);

      mv.visitLabel(checkDigit);
      mv.visitInsn(DUP);
      pushInt(mv, '0');
      mv.visitJumpInsn(IF_ICMPLT, checkUnderscore);
      mv.visitInsn(DUP);
      pushInt(mv, '9');
      mv.visitJumpInsn(IF_ICMPLE, ok);

      mv.visitLabel(checkUnderscore);
      pushInt(mv, '_');
      mv.visitJumpInsn(IF_ICMPNE, failLabel);

      mv.visitLabel(ok);
      mv.visitInsn(POP);
    } else {
      // General charset - pop the char and accept
      mv.visitInsn(POP);
    }
  }

  /** Generate find() method - delegates to findFrom(input, 0). */
  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "findFrom", "(Ljava/lang/String;I)I", false);
    Label notFound = new Label();
    mv.visitJumpInsn(IFLT, notFound);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notFound);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  /** Generate findFrom() method with outer search loop. */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=startPos
    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int searchPosVar = alloc.allocate();
    int posVar = alloc.allocate();

    // Allocate vars for nested loop counters
    int[] countVars = new int[info.levels.size()];
    int[] iterStartVars = new int[info.levels.size()];
    for (int i = 0; i < info.levels.size(); i++) {
      countVars[i] = alloc.allocate();
      iterStartVars[i] = alloc.allocate();
    }

    Label returnNotFound = new Label();

    // Null check
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

    // int searchPos = startPos;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, searchPosVar);

    // Outer search loop
    Label searchLoop = new Label();
    Label searchEnd = new Label();
    Label tryNext = new Label();

    mv.visitLabel(searchLoop);

    // Check searchPos <= len
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, searchEnd);

    // pos = searchPos
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Initialize outer count
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVars[0]);

    // Generate simplified nested matching for find
    if (info.levels.size() >= 2) {
      generateFindNestedLoops(mv, posVar, lenVar, countVars, iterStartVars, tryNext, 0);
    }

    // Check outer min is satisfied
    QuantifierLevel outerLevel = info.getOuterLevel();
    if (outerLevel != null && outerLevel.min > 0) {
      mv.visitVarInsn(ILOAD, countVars[0]);
      pushInt(mv, outerLevel.min);
      mv.visitJumpInsn(IF_ICMPLT, tryNext);
    }

    // Found! Return searchPos
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitInsn(IRETURN);

    // Try next position
    mv.visitLabel(tryNext);
    mv.visitIincInsn(searchPosVar, 1);
    mv.visitJumpInsn(GOTO, searchLoop);

    mv.visitLabel(searchEnd);
    mv.visitLabel(returnNotFound);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /** Generate nested loops for find operation. */
  private void generateFindNestedLoops(
      MethodVisitor mv,
      int posVar,
      int lenVar,
      int[] countVars,
      int[] iterStartVars,
      Label failLabel,
      int levelIdx) {

    if (levelIdx >= info.levels.size()) {
      return;
    }

    QuantifierLevel level = info.levels.get(levelIdx);
    int countVar = countVars[levelIdx];
    int iterStartVar = iterStartVars[levelIdx];
    boolean isLastLevel = (levelIdx == info.levels.size() - 1);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    // Initialize count
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    mv.visitLabel(loopStart);

    // Check pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Save iteration start
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, iterStartVar);

    if (isLastLevel) {
      generateContentMatch(mv, level, posVar, lenVar, loopEnd);
    } else {
      int innerCountVar = countVars[levelIdx + 1];
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, innerCountVar);

      generateFindNestedLoops(mv, posVar, lenVar, countVars, iterStartVars, loopEnd, levelIdx + 1);

      QuantifierLevel innerLevel = info.levels.get(levelIdx + 1);
      if (innerLevel.min > 0) {
        mv.visitVarInsn(ILOAD, innerCountVar);
        pushInt(mv, innerLevel.min);
        mv.visitJumpInsn(IF_ICMPLT, loopEnd);
      }
    }

    // Increment count
    mv.visitIincInsn(countVar, 1);

    // Check max
    if (!level.isUnbounded()) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, level.max);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);
  }

  // Stub methods for MatchResult-returning operations

  private void generateMatchStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("match() not yet implemented for NESTED_QUANTIFIED_GROUPS strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  private void generateMatchesBoundedStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("matchesBounded() not yet implemented for NESTED_QUANTIFIED_GROUPS strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 4);
    mv.visitEnd();
  }

  private void generateMatchBoundedStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("matchBounded() not yet implemented for NESTED_QUANTIFIED_GROUPS strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 4);
    mv.visitEnd();
  }

  private void generateFindMatchStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("findMatch() not yet implemented for NESTED_QUANTIFIED_GROUPS strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  private void generateFindMatchFromStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("findMatchFrom() not yet implemented for NESTED_QUANTIFIED_GROUPS strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 3);
    mv.visitEnd();
  }
}
