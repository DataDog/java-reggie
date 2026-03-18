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

import com.datadoghq.reggie.codegen.analysis.ConcatQuantifiedGroupsInfo;
import com.datadoghq.reggie.codegen.analysis.ConcatQuantifiedGroupsInfo.GroupInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates optimized bytecode for concatenated quantified capturing groups.
 *
 * <h3>Pattern Types</h3>
 *
 * {@code (a+)+(b+)+}, {@code ([a-z]+)+([0-9]+)+}
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern (a+)+(b+)+ matching "aaabbb"
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int pos = 0, len = input.length();
 *
 *     // Group 1: (a+)+
 *     int group1Start = pos;
 *     int count1 = 0;
 *     while (pos < len && input.charAt(pos) == 'a') {
 *         pos++; count1++;
 *     }
 *     if (count1 < 1) return false;
 *     int group1End = pos;
 *
 *     // Group 2: (b+)+
 *     int group2Start = pos;
 *     int count2 = 0;
 *     while (pos < len && input.charAt(pos) == 'b') {
 *         pos++; count2++;
 *     }
 *     if (count2 < 1) return false;
 *     int group2End = pos;
 *
 *     return pos == len;
 * }
 * }</pre>
 *
 * <h3>Notes</h3>
 *
 * Each group is processed sequentially with POSIX last-match semantics. For "aaabbb": Group 1 =
 * "aaa", Group 2 = "bbb"
 */
public class ConcatQuantifiedGroupsBytecodeGenerator {

  private final ConcatQuantifiedGroupsInfo info;
  private final String className;

  public ConcatQuantifiedGroupsBytecodeGenerator(
      ConcatQuantifiedGroupsInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Generate all required methods for ReggieMatcher. */
  public void generate(ClassWriter cw) {
    generateMatchesMethod(cw);
    generateMatchMethod(cw);
    generateFindMethod(cw);
    generateFindFromMethod(cw);
    generateFindMatchMethod(cw);
    generateFindMatchFromMethod(cw);
    generateMatchesBoundedMethod(cw);
    generateMatchBoundedMethod(cw);
  }

  /** Generate matches() method. */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    Label failLabel = new Label();

    // Process each group in sequence
    for (GroupInfo g : info.groups) {
      generateGroupMatchForMatches(mv, g, inputVar, posVar, lenVar, failLabel, allocator);
    }

    // Check that we consumed entire input: pos == len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    Label returnTrue = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, returnTrue);

    // Fail
    mv.visitLabel(failLabel);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // Success
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate group matching for matches() method (no group capture). */
  private void generateGroupMatchForMatches(
      MethodVisitor mv,
      GroupInfo g,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      LocalVarAllocator allocator) {
    // Allocate temp vars for this group
    int outerCountVar = allocator.allocate();
    int innerCountVar = allocator.allocate();
    int charVar = allocator.allocate();

    // int outerCount = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, outerCountVar);

    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // if (pos >= len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, outerEnd);

    // Check outer max bound
    if (!g.isOuterUnbounded()) {
      mv.visitVarInsn(ILOAD, outerCountVar);
      pushInt(mv, g.outerMax);
      mv.visitJumpInsn(IF_ICMPGE, outerEnd);
    }

    // int innerCount = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, innerCountVar);

    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);

    // if (pos >= len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    // Check inner max bound
    if (!g.isInnerUnbounded()) {
      mv.visitVarInsn(ILOAD, innerCountVar);
      pushInt(mv, g.innerMax);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check if char matches
    generateCharSetCheck(mv, g.charSet, charVar, innerEnd);

    // pos++; innerCount++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(innerCountVar, 1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Check inner min bound
    mv.visitVarInsn(ILOAD, innerCountVar);
    pushInt(mv, g.innerMin);
    mv.visitJumpInsn(IF_ICMPLT, outerEnd); // Break outer if inner min not met

    // Successful iteration
    mv.visitIincInsn(outerCountVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);

    // Check outer min bound
    mv.visitVarInsn(ILOAD, outerCountVar);
    pushInt(mv, g.outerMin);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
  }

  /** Generate match() method - matches from start, extracts groups. */
  public void generateMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    Label failLabel = new Label();
    int numGroups = info.groups.size();

    // Allocate per-group tracking vars: lastIterStart, lastIterEnd
    int[] lastIterStartVars = new int[numGroups];
    int[] lastIterEndVars = new int[numGroups];
    for (int i = 0; i < numGroups; i++) {
      lastIterStartVars[i] = allocator.allocate();
      lastIterEndVars[i] = allocator.allocate();
    }

    // Initialize last-iter tracking vars for each group to -1
    for (int i = 0; i < numGroups; i++) {
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, lastIterStartVars[i]);
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, lastIterEndVars[i]);
    }

    // Process each group
    for (int i = 0; i < numGroups; i++) {
      GroupInfo g = info.groups.get(i);
      generateGroupMatchWithCapture(
          mv,
          g,
          inputVar,
          posVar,
          lenVar,
          lastIterStartVars[i],
          lastIterEndVars[i],
          failLabel,
          allocator);
    }

    // Check that we consumed entire input
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, failLabel);

    // Build MatchResult
    int arraySize = numGroups + 1;
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // starts[0] = 0, ends[0] = pos
    mv.visitVarInsn(ALOAD, startsVar);
    pushInt(mv, 0);
    pushInt(mv, 0);
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // Fill in each group
    for (int i = 0; i < numGroups; i++) {
      int groupIdx = i + 1;

      mv.visitVarInsn(ALOAD, startsVar);
      pushInt(mv, groupIdx);
      mv.visitVarInsn(ILOAD, lastIterStartVars[i]);
      mv.visitInsn(IASTORE);

      mv.visitVarInsn(ALOAD, endsVar);
      pushInt(mv, groupIdx);
      mv.visitVarInsn(ILOAD, lastIterEndVars[i]);
      mv.visitInsn(IASTORE);
    }

    // return new MatchResultImpl(input, starts, ends, numGroups);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, numGroups);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Fail
    mv.visitLabel(failLabel);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate group matching with capture tracking. */
  private void generateGroupMatchWithCapture(
      MethodVisitor mv,
      GroupInfo g,
      int inputVar,
      int posVar,
      int lenVar,
      int lastIterStartVar,
      int lastIterEndVar,
      Label failLabel,
      LocalVarAllocator allocator) {
    // Allocate temp vars for this group
    int outerCountVar = allocator.allocate();
    int iterStartVar = allocator.allocate();
    int innerCountVar = allocator.allocate();
    int charVar = allocator.allocate();

    // int outerCount = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, outerCountVar);

    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // if (pos >= len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, outerEnd);

    // Check outer max bound
    if (!g.isOuterUnbounded()) {
      mv.visitVarInsn(ILOAD, outerCountVar);
      pushInt(mv, g.outerMax);
      mv.visitJumpInsn(IF_ICMPGE, outerEnd);
    }

    // int iterStart = pos;  (save position before this iteration)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, iterStartVar);

    // Inner loop to match chars
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, innerCountVar);

    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);

    // if (pos >= len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    // Check inner max bound
    if (!g.isInnerUnbounded()) {
      mv.visitVarInsn(ILOAD, innerCountVar);
      pushInt(mv, g.innerMax);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check if char matches
    generateCharSetCheck(mv, g.charSet, charVar, innerEnd);

    // pos++; innerCount++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(innerCountVar, 1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Check inner min bound
    mv.visitVarInsn(ILOAD, innerCountVar);
    pushInt(mv, g.innerMin);
    mv.visitJumpInsn(IF_ICMPLT, outerEnd);

    // Successful iteration! Update lastIterStart/End
    mv.visitVarInsn(ILOAD, iterStartVar);
    mv.visitVarInsn(ISTORE, lastIterStartVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, lastIterEndVar);

    mv.visitIincInsn(outerCountVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);

    // Check outer min bound
    mv.visitVarInsn(ILOAD, outerCountVar);
    pushInt(mv, g.outerMin);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
  }

  /** Generate charset check: if (!charset.contains(ch)) goto exitLabel */
  private void generateCharSetCheck(
      MethodVisitor mv, CharSet charset, int charVar, Label exitLabel) {
    if (charset.isSingleChar()) {
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
      pushInt(mv, (int) c); // S: [I] -> [I, I]
      mv.visitJumpInsn(IF_ICMPNE, exitLabel);
    } else if (charset.isSimpleRange()) {
      char min = charset.rangeStart();
      char max = charset.rangeEnd();
      // if (ch < min || ch > max) goto exitLabel
      mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
      pushInt(mv, (int) min); // S: [I] -> [I, I]
      mv.visitJumpInsn(IF_ICMPLT, exitLabel);
      mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
      pushInt(mv, (int) max); // S: [I] -> [I, I]
      mv.visitJumpInsn(IF_ICMPGT, exitLabel);
    } else {
      // Multiple ranges
      Label matched = new Label();
      for (CharSet.Range range : charset.getRanges()) {
        Label nextRange = new Label();
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, nextRange);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, matched);
        mv.visitLabel(nextRange);
      }
      mv.visitJumpInsn(GOTO, exitLabel);
      mv.visitLabel(matched);
    }
  }

  // Delegate methods to find variants

  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);

    Label notNull = new Label();
    mv.visitInsn(DUP);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(POP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(notNull);
    mv.visitInsn(POP);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);

    mv.visitInsn(DUP);
    Label notNull = new Label();
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(POP);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);

    mv.visitLabel(notNull);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "start", "()I", true);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    pushInt(mv, 0);
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
   * Generate findMatchFrom() - searches for match from position. For now, do simple position
   * iteration.
   */
  public void generateFindMatchFromMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int inputVar = 1;
    int startPosVar = 2;
    int lenVar = allocator.allocate();
    int matchStartVar = allocator.allocate();
    int posVar = allocator.allocate();

    int numGroups = info.groups.size();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Allocate per-group tracking vars
    int[] lastIterStartVars = new int[numGroups];
    int[] lastIterEndVars = new int[numGroups];
    for (int i = 0; i < numGroups; i++) {
      lastIterStartVars[i] = allocator.allocate();
      lastIterEndVars[i] = allocator.allocate();
    }

    Label outerLoop = new Label();
    Label notFound = new Label();

    mv.visitLabel(outerLoop);

    // if (startPos > len) return null;
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, notFound);

    // Save matchStart
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // int pos = startPos;
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    Label tryNextPos = new Label();

    // Initialize last-iter vars for each group
    for (int i = 0; i < numGroups; i++) {
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, lastIterStartVars[i]);
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, lastIterEndVars[i]);
    }

    // Process each group
    for (int i = 0; i < numGroups; i++) {
      GroupInfo g = info.groups.get(i);
      generateGroupMatchWithCaptureFind(
          mv,
          g,
          inputVar,
          posVar,
          lenVar,
          lastIterStartVars[i],
          lastIterEndVars[i],
          tryNextPos,
          allocator);
    }

    // Success! Build MatchResult
    int arraySize = numGroups + 1;
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // starts[0] = matchStart, ends[0] = pos
    mv.visitVarInsn(ALOAD, startsVar);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    for (int i = 0; i < numGroups; i++) {
      int groupIdx = i + 1;

      mv.visitVarInsn(ALOAD, startsVar);
      pushInt(mv, groupIdx);
      mv.visitVarInsn(ILOAD, lastIterStartVars[i]);
      mv.visitInsn(IASTORE);

      mv.visitVarInsn(ALOAD, endsVar);
      pushInt(mv, groupIdx);
      mv.visitVarInsn(ILOAD, lastIterEndVars[i]);
      mv.visitInsn(IASTORE);
    }

    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, numGroups);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Try next position
    mv.visitLabel(tryNextPos);
    mv.visitIincInsn(startPosVar, 1); // startPos++
    mv.visitJumpInsn(GOTO, outerLoop);

    // Not found
    mv.visitLabel(notFound);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Similar to generateGroupMatchWithCapture but uses different slot base for find methods. */
  private void generateGroupMatchWithCaptureFind(
      MethodVisitor mv,
      GroupInfo g,
      int inputVar,
      int posVar,
      int lenVar,
      int lastIterStartVar,
      int lastIterEndVar,
      Label failLabel,
      LocalVarAllocator allocator) {
    // Allocate temp vars
    int outerCountVar = allocator.allocate();
    int iterStartVar = allocator.allocate();
    int innerCountVar = allocator.allocate();
    int charVar = allocator.allocate();

    // int outerCount = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, outerCountVar);

    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // if (pos >= len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, outerEnd);

    if (!g.isOuterUnbounded()) {
      mv.visitVarInsn(ILOAD, outerCountVar);
      pushInt(mv, g.outerMax);
      mv.visitJumpInsn(IF_ICMPGE, outerEnd);
    }

    // int iterStart = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, iterStartVar);

    // Inner loop
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, innerCountVar);

    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    if (!g.isInnerUnbounded()) {
      mv.visitVarInsn(ILOAD, innerCountVar);
      pushInt(mv, g.innerMax);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    generateCharSetCheck(mv, g.charSet, charVar, innerEnd);

    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(innerCountVar, 1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Check inner min
    mv.visitVarInsn(ILOAD, innerCountVar);
    pushInt(mv, g.innerMin);
    mv.visitJumpInsn(IF_ICMPLT, outerEnd);

    // Success: update lastIter
    mv.visitVarInsn(ILOAD, iterStartVar);
    mv.visitVarInsn(ISTORE, lastIterStartVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, lastIterEndVar);

    mv.visitIincInsn(outerCountVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);

    // Check outer min
    mv.visitVarInsn(ILOAD, outerCountVar);
    pushInt(mv, g.outerMin);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
  }

  public void generateMatchesBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = start, 3 = end
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    int sVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);

    mv.visitVarInsn(ASTORE, sVar);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, sVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = start, 3 = end
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    int sVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);

    mv.visitVarInsn(ASTORE, sVar);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, sVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
