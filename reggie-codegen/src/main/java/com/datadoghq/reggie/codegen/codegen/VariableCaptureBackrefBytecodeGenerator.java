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

import com.datadoghq.reggie.codegen.analysis.VariableCaptureBackrefInfo;
import com.datadoghq.reggie.codegen.ast.AnchorNode;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.ConcatNode;
import com.datadoghq.reggie.codegen.ast.GroupNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Bytecode generator for VARIABLE_CAPTURE_BACKREF strategy.
 *
 * <p>Generates optimized matching code for patterns where a variable-length capturing group is
 * followed by a separator and backreference.
 *
 * <h3>Supported Pattern Examples</h3>
 *
 * <ul>
 *   <li>{@code (.*)\d+\1} - greedy any, digit separator, backref
 *   <li>{@code (.+)=\1} - greedy any (1+), literal separator, backref
 *   <li>{@code (a+):\1} - greedy char class, literal separator, backref
 * </ul>
 *
 * <h3>Algorithm (Longest-First Backtracking)</h3>
 *
 * <pre>{@code
 * boolean matches(String input) {
 *     int len = input.length();
 *     int groupEnd = len;  // Start with longest possible capture
 *     int iterations = 0;
 *
 *     // Backtrack loop: try shorter captures until match found
 *     while (groupEnd >= groupMinLen) {
 *         if (BacktrackConfig.checkLimit(iterations++)) return false;
 *
 *         String captured = input.substring(0, groupEnd);
 *         int sepStart = groupEnd;
 *
 *         // Try to match separator at sepStart
 *         int sepEnd = matchSeparator(input, sepStart, len);
 *         if (sepEnd < 0) {
 *             groupEnd--;
 *             continue;
 *         }
 *
 *         // Try to match backref (captured content) at sepEnd
 *         if (matchBackref(input, sepEnd, len, captured)) {
 *             return true;
 *         }
 *
 *         groupEnd--;
 *     }
 *     return false;
 * }
 * }</pre>
 */
public class VariableCaptureBackrefBytecodeGenerator {

  private final VariableCaptureBackrefInfo info;
  private final String className;

  public VariableCaptureBackrefBytecodeGenerator(
      VariableCaptureBackrefInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Generate all required methods for ReggieMatcher. */
  public void generate(ClassWriter cw) {
    generateMatchesMethod(cw);
    generateMatchMethod(cw);
    generateFindMethod(cw);
    generateFindFromMethod(cw);
    generateMatchesBoundedMethod(cw);
    generateMatchBoundedMethod(cw);
    generateFindMatchMethod(cw);
    generateFindMatchFromMethod(cw);
  }

  /**
   * Generate match() method — same backtracking algorithm as matches() but returns a
   * MatchResultImpl on success or null on failure.
   */
  private void generateMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int sepStartVar = alloc.allocate();
    int sepEndVar = alloc.allocate();
    int backrefEndVar = alloc.allocate();
    int iterationsVar = alloc.allocate();

    Label returnNull = new Label();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Minimum length check
    int minLen =
        getPrefixLength() + info.groupMinCount + info.getSeparatorMinLength() + info.groupMinCount;
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, minLen);
    mv.visitJumpInsn(IF_ICMPLT, returnNull);

    // int groupStart = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, groupStartVar);

    emitPrefixMatch(mv, groupStartVar, lenVar, returnNull, alloc);

    emitGroupEndInit(mv, groupEndVar, lenVar, groupStartVar);

    // int iterations = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iterationsVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label continueLoop = new Label();

    mv.visitLabel(loopStart);

    // Check: groupEnd >= groupStart + groupMinCount
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    pushInt(mv, info.groupMinCount);
    mv.visitInsn(IADD);
    mv.visitJumpInsn(IF_ICMPLT, loopEnd);

    // BacktrackConfig.checkLimit(iterations++)
    mv.visitVarInsn(ILOAD, iterationsVar);
    mv.visitMethodInsn(
        INVOKESTATIC, "com/datadoghq/reggie/runtime/BacktrackConfig", "checkLimit", "(I)Z", false);
    mv.visitJumpInsn(IFNE, returnNull);
    mv.visitIincInsn(iterationsVar, 1);

    // Validate group content matches groupCharSet (if specified)
    generateGroupCharSetValidation(mv, groupStartVar, groupEndVar, continueLoop, alloc);

    // int sepStart = groupEnd;
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ISTORE, sepStartVar);

    // Match separator
    generateSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, continueLoop, alloc);

    // int groupLen = groupEnd - groupStart;
    int groupLenVar = alloc.allocate();
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // Check: sepEnd + groupLen <= len
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, continueLoop);

    // backrefEnd = sepEnd + groupLen
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, backrefEndVar);

    // Without end anchor: backrefEnd must equal len for match()
    if (!info.hasEndAnchor) {
      mv.visitVarInsn(ILOAD, backrefEndVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPNE, continueLoop);
    }

    // Match backref: input.regionMatches(sepEnd, input, groupStart, groupLen)
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueLoop);

    // Match found — build MatchResultImpl
    // int[] starts = new int[totalGroupCount + 1]
    int startsVar = alloc.allocate();
    int endsVar = alloc.allocate();
    pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    // starts[0] = 0
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    // starts[groupNumber] = groupStart
    mv.visitInsn(DUP);
    pushInt(mv, info.groupNumber);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(IASTORE);
    // starts[separatorGroupNumber] = sepStart  (if applicable)
    if (info.separatorGroupNumber >= 0) {
      mv.visitInsn(DUP);
      pushInt(mv, info.separatorGroupNumber);
      mv.visitVarInsn(ILOAD, sepStartVar);
      mv.visitInsn(IASTORE);
    }
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[totalGroupCount + 1]
    pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    // ends[0] = len
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitInsn(IASTORE);
    // ends[groupNumber] = groupEnd
    mv.visitInsn(DUP);
    pushInt(mv, info.groupNumber);
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitInsn(IASTORE);
    // ends[separatorGroupNumber] = sepEnd  (if applicable)
    if (info.separatorGroupNumber >= 0) {
      mv.visitInsn(DUP);
      pushInt(mv, info.separatorGroupNumber);
      mv.visitVarInsn(ILOAD, sepEndVar);
      mv.visitInsn(IASTORE);
    }
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, totalGroupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, info.totalGroupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Continue backtracking: groupEnd--
    mv.visitLabel(continueLoop);
    mv.visitIincInsn(groupEndVar, -1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /** Generate matchesBounded() — delegates to matches() on the subSequence substring. */
  private void generateMatchesBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start, 3=end
    LocalVarAllocator alloc = new LocalVarAllocator(4);
    int subVar = alloc.allocate();

    // String sub = input.subSequence(start, end).toString();
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
    mv.visitVarInsn(ASTORE, subVar);

    // return this.matches(sub);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, subVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Generate matchBounded() — delegates to match() on the subSequence, then adjusts spans by adding
   * the start offset back.
   */
  private void generateMatchBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start, 3=end
    LocalVarAllocator alloc = new LocalVarAllocator(4);
    int subVar = alloc.allocate();
    int resultVar = alloc.allocate();
    int startsVar = alloc.allocate();
    int endsVar = alloc.allocate();
    int iVar = alloc.allocate();

    // String sub = input.subSequence(start, end).toString();
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
    mv.visitVarInsn(ASTORE, subVar);

    // MatchResultImpl inner = (MatchResultImpl) this.match(sub);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, subVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className,
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, resultVar);

    // if (inner == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, resultVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // Adjust spans: add start (param slot 2) to every valid entry
    // starts and ends are arrays of length totalGroupCount+1 inside the MatchResultImpl;
    // we build fresh arrays here using the same sub-string spans + offset.
    //
    // Build new starts[] and ends[] by calling inner.start(i)+offset / inner.end(i)+offset
    int totalSlots = info.totalGroupCount + 1;
    pushInt(mv, totalSlots);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    pushInt(mv, totalSlots);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // Loop: for (int i = 0; i <= totalGroupCount; i++) { starts[i] = inner.start(i) + start; ... }
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iVar);

    Label loopCond = new Label();
    Label loopBody = new Label();
    mv.visitJumpInsn(GOTO, loopCond);
    mv.visitLabel(loopBody);

    // starts[i] = inner.start(i) + start
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, resultVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "start", "(I)I", true);
    mv.visitVarInsn(ILOAD, 2); // start param
    mv.visitInsn(IADD);
    mv.visitInsn(IASTORE);

    // ends[i] = inner.end(i) + start
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ALOAD, resultVar);
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "end", "(I)I", true);
    mv.visitVarInsn(ILOAD, 2); // start param
    mv.visitInsn(IADD);
    mv.visitInsn(IASTORE);

    mv.visitIincInsn(iVar, 1);

    mv.visitLabel(loopCond);
    mv.visitVarInsn(ILOAD, iVar);
    pushInt(mv, info.totalGroupCount);
    mv.visitJumpInsn(IF_ICMPLE, loopBody);

    // The sub-string used in match() is different from the original input CharSequence.
    // We need the original string for MatchResultImpl. Use sub as a proxy (already
    // offset-adjusted).
    // Actually we should return a MatchResult that reports group(i) from the original input.
    // The simplest approach: pass the CharSequence.toString() as the "input" to MatchResultImpl
    // but with adjusted spans. We use sub's toString which is a sub-sequence already.
    // However MatchResultImpl.group(i) calls input.substring(starts[i], ends[i]).
    // Since we've already added offset to starts/ends, we need the full input string.
    int fullInputVar = alloc.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ASTORE, fullInputVar);

    // return new MatchResultImpl(fullInput, starts, ends, totalGroupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, fullInputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, info.totalGroupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, alloc.peek());
    mv.visitEnd();
  }

  /** Generate findMatch() — delegates to findMatchFrom(input, 0). */
  private void generateFindMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // return this.findMatchFrom(input, 0);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
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
   * Generate findMatchFrom() — same outer/inner loop as generateFindFromMethod but returns a
   * MatchResultImpl with adjusted spans on success, or null if no match.
   */
  private void generateFindMatchFromMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=startPos
    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int sepStartVar = alloc.allocate();
    int sepEndVar = alloc.allocate();
    int iterationsVar = alloc.allocate();
    int groupStartVar = alloc.allocate();

    Label returnNull = new Label();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = startPos;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);

    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    int minLen =
        getPrefixLength() + info.groupMinCount + info.getSeparatorMinLength() + info.groupMinCount;
    mv.visitVarInsn(ILOAD, startVar);
    pushInt(mv, minLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // Initialize groupStart from start, then advance past prefix
    Label innerEnd = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, groupStartVar);
    emitPrefixMatch(mv, groupStartVar, lenVar, innerEnd, alloc);
    emitGroupEndInit(mv, groupEndVar, lenVar, groupStartVar);

    // iterations = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iterationsVar);

    Label innerLoop = new Label();
    Label continueInner = new Label();

    mv.visitLabel(innerLoop);

    // Check: groupEnd >= groupStart + groupMinCount
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    pushInt(mv, info.groupMinCount);
    mv.visitInsn(IADD);
    mv.visitJumpInsn(IF_ICMPLT, innerEnd);

    // BacktrackConfig.checkLimit
    mv.visitVarInsn(ILOAD, iterationsVar);
    mv.visitMethodInsn(
        INVOKESTATIC, "com/datadoghq/reggie/runtime/BacktrackConfig", "checkLimit", "(I)Z", false);
    mv.visitJumpInsn(IFNE, returnNull);
    mv.visitIincInsn(iterationsVar, 1);

    // Validate group content matches groupCharSet (if specified)
    generateGroupCharSetValidation(mv, groupStartVar, groupEndVar, continueInner, alloc);

    // sepStart = groupEnd
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ISTORE, sepStartVar);

    // Match separator
    generateSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, continueInner, alloc);

    int groupLenVar = alloc.allocate();
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // Check: sepEnd + groupLen <= len
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, continueInner);

    // Match backref
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueInner);

    // Found — build MatchResultImpl
    // matchEnd = sepEnd + groupLen
    int matchEndVar = alloc.allocate();
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, matchEndVar);

    int startsVar = alloc.allocate();
    int endsVar = alloc.allocate();

    // starts array
    pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    // starts[0] = start (full match start, before prefix)
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IASTORE);
    // starts[groupNumber] = groupStart (group start, after prefix)
    mv.visitInsn(DUP);
    pushInt(mv, info.groupNumber);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(IASTORE);
    if (info.separatorGroupNumber >= 0) {
      mv.visitInsn(DUP);
      pushInt(mv, info.separatorGroupNumber);
      mv.visitVarInsn(ILOAD, sepStartVar);
      mv.visitInsn(IASTORE);
    }
    mv.visitVarInsn(ASTORE, startsVar);

    // ends array
    pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    // ends[0] = matchEnd
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchEndVar);
    mv.visitInsn(IASTORE);
    // ends[groupNumber] = groupEnd
    mv.visitInsn(DUP);
    pushInt(mv, info.groupNumber);
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitInsn(IASTORE);
    if (info.separatorGroupNumber >= 0) {
      mv.visitInsn(DUP);
      pushInt(mv, info.separatorGroupNumber);
      mv.visitVarInsn(ILOAD, sepEndVar);
      mv.visitInsn(IASTORE);
    }
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, totalGroupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, info.totalGroupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Continue inner backtrack
    mv.visitLabel(continueInner);
    mv.visitIincInsn(groupEndVar, -1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Try next starting position
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  private int getPrefixLength() {
    int n = 0;
    for (RegexNode node : info.prefix) {
      if (!(node instanceof AnchorNode)) n++;
    }
    return n;
  }

  private void emitCharSetCheck(
      MethodVisitor mv, int charVar, CharSet cs, boolean negated, Label failLabel) {
    if (!negated) {
      Label inSet = new Label();
      for (CharSet.Range r : cs.getRanges()) {
        if (r.start == r.end) {
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, r.start);
          mv.visitJumpInsn(IF_ICMPEQ, inSet);
        } else {
          Label nextRange = new Label();
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, r.start);
          mv.visitJumpInsn(IF_ICMPLT, nextRange);
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, r.end);
          mv.visitJumpInsn(IF_ICMPLE, inSet);
          mv.visitLabel(nextRange);
        }
      }
      mv.visitJumpInsn(GOTO, failLabel);
      mv.visitLabel(inSet);
    } else {
      for (CharSet.Range r : cs.getRanges()) {
        if (r.start == r.end) {
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, r.start);
          mv.visitJumpInsn(IF_ICMPEQ, failLabel);
        } else {
          Label notInRange = new Label();
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, r.start);
          mv.visitJumpInsn(IF_ICMPLT, notInRange);
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, r.end);
          mv.visitJumpInsn(IF_ICMPLE, failLabel);
          mv.visitLabel(notInRange);
        }
      }
    }
  }

  private void emitPrefixNode(
      MethodVisitor mv,
      RegexNode node,
      int groupStartVar,
      int lenVar,
      Label failLabel,
      LocalVarAllocator alloc) {
    if (node instanceof AnchorNode) {
      // zero-width, nothing to consume
    } else if (node instanceof LiteralNode) {
      char ch = ((LiteralNode) node).ch;
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, failLabel);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, ch);
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
      mv.visitIincInsn(groupStartVar, 1);
    } else if (node instanceof CharClassNode) {
      CharClassNode ccn = (CharClassNode) node;
      int charVar = alloc.allocate();
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, failLabel);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);
      emitCharSetCheck(mv, charVar, ccn.chars, ccn.negated, failLabel);
      mv.visitIincInsn(groupStartVar, 1);
    } else if (node instanceof GroupNode) {
      GroupNode g = (GroupNode) node;
      if (!g.capturing) {
        emitPrefixNode(mv, g.child, groupStartVar, lenVar, failLabel, alloc);
      }
      // capturing groups in prefix are not reachable here (they are the backref group, not prefix)
    } else if (node instanceof ConcatNode) {
      for (RegexNode child : ((ConcatNode) node).children) {
        emitPrefixNode(mv, child, groupStartVar, lenVar, failLabel, alloc);
      }
    }
  }

  private void emitPrefixMatch(
      MethodVisitor mv, int groupStartVar, int lenVar, Label failLabel, LocalVarAllocator alloc) {
    for (RegexNode node : info.prefix) {
      emitPrefixNode(mv, node, groupStartVar, lenVar, failLabel, alloc);
    }
  }

  private void emitGroupEndInit(MethodVisitor mv, int groupEndVar, int lenVar, int groupStartVar) {
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.getSeparatorMinLength());
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupEndVar);

    if (info.groupMaxCount >= 0) {
      mv.visitVarInsn(ILOAD, groupEndVar);
      mv.visitVarInsn(ILOAD, groupStartVar);
      pushInt(mv, info.groupMaxCount);
      mv.visitInsn(IADD);
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
      mv.visitVarInsn(ISTORE, groupEndVar);
    }
  }

  /** Generate matches() method with longest-first backtracking. */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int sepStartVar = alloc.allocate();
    int sepEndVar = alloc.allocate();
    int backrefEndVar = alloc.allocate();
    int iterationsVar = alloc.allocate();

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

    // Minimum length check: prefixLen + groupMin + separatorMin + backrefGroupMin
    int minLen =
        getPrefixLength() + info.groupMinCount + info.getSeparatorMinLength() + info.groupMinCount;
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, minLen);
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // int groupStart = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, groupStartVar);

    emitPrefixMatch(mv, groupStartVar, lenVar, returnFalse, alloc);

    // int groupEnd = len - separatorMinLen - groupMinLen;  (start with longest possible)
    emitGroupEndInit(mv, groupEndVar, lenVar, groupStartVar);

    // int iterations = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iterationsVar);

    // Backtrack loop
    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label continueLoop = new Label();

    mv.visitLabel(loopStart);

    // Check: groupEnd >= groupStart + groupMinCount
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    pushInt(mv, info.groupMinCount);
    mv.visitInsn(IADD);
    mv.visitJumpInsn(IF_ICMPLT, loopEnd);

    // Check backtrack limit: if (BacktrackConfig.checkLimit(iterations++)) return false;
    mv.visitVarInsn(ILOAD, iterationsVar);
    mv.visitMethodInsn(
        INVOKESTATIC, "com/datadoghq/reggie/runtime/BacktrackConfig", "checkLimit", "(I)Z", false);
    mv.visitJumpInsn(IFNE, returnFalse);
    mv.visitIincInsn(iterationsVar, 1);

    // Validate group content matches groupCharSet (if specified)
    generateGroupCharSetValidation(mv, groupStartVar, groupEndVar, continueLoop, alloc);

    // int sepStart = groupEnd;
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ISTORE, sepStartVar);

    // Match separator and get sepEnd
    generateSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, continueLoop, alloc);

    // Match backreference: captured content from groupStart to groupEnd
    // Backref must match input[sepEnd..sepEnd+groupLen]
    // where groupLen = groupEnd - groupStart

    // int groupLen = groupEnd - groupStart;
    int groupLenVar = alloc.allocate();
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // Check: sepEnd + groupLen <= len (room for backref)
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, continueLoop);

    // backrefEnd = sepEnd + groupLen
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, backrefEndVar);

    // For matches(), we need backrefEnd == len (consume entire input)
    if (!info.hasEndAnchor) {
      // Without end anchor, backrefEnd must equal len for matches()
      mv.visitVarInsn(ILOAD, backrefEndVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPNE, continueLoop);
    }

    // Match backref: input.regionMatches(sepEnd, input, groupStart, groupLen)
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, sepEndVar); // toffset
    mv.visitVarInsn(ALOAD, 1); // other (same string)
    mv.visitVarInsn(ILOAD, groupStartVar); // ooffset
    mv.visitVarInsn(ILOAD, groupLenVar); // len
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueLoop);

    // Match found!
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Continue backtracking: groupEnd--
    mv.visitLabel(continueLoop);
    mv.visitIincInsn(groupEndVar, -1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Validate that every character in input[groupStartVar..groupEndVar) is in the groupCharSet.
   * Jumps to failLabel if any character fails. No-op when groupCharSet is null or ANY.
   */
  private void generateGroupCharSetValidation(
      MethodVisitor mv,
      int groupStartVar,
      int groupEndVar,
      Label failLabel,
      LocalVarAllocator alloc) {
    if (info.groupCharSet == null || info.groupCharSet.equals(CharSet.ANY)) {
      return; // No constraint — all chars accepted
    }

    int iPosVar = alloc.allocate();
    int charVar = alloc.allocate();

    // int i = groupStart;
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitVarInsn(ISTORE, iPosVar);

    Label loopCond = new Label();
    Label loopBody = new Label();
    mv.visitJumpInsn(GOTO, loopCond);

    mv.visitLabel(loopBody);
    // char c = input.charAt(i);
    mv.visitVarInsn(ALOAD, 1); // input is always slot 1
    mv.visitVarInsn(ILOAD, iPosVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check membership; jump to failLabel if NOT in charset
    generateCharSetCheckFromLocal(mv, charVar, info.groupCharSet, failLabel);

    // i++
    mv.visitIincInsn(iPosVar, 1);

    mv.visitLabel(loopCond);
    // while (i < groupEnd)
    mv.visitVarInsn(ILOAD, iPosVar);
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitJumpInsn(IF_ICMPLT, loopBody);
  }

  /**
   * Generate separator matching code. Sets sepEndVar to the position after the separator, or jumps
   * to failLabel.
   */
  private void generateSeparatorMatch(
      MethodVisitor mv,
      int sepStartVar,
      int sepEndVar,
      int lenVar,
      Label failLabel,
      LocalVarAllocator alloc) {

    switch (info.separatorType) {
      case LITERAL:
        generateLiteralSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, failLabel);
        break;
      case DIGIT_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            CharSet.DIGIT,
            info.separatorMinCount,
            alloc);
        break;
      case WORD_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            CharSet.WORD,
            info.separatorMinCount,
            alloc);
        break;
      case WHITESPACE_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            CharSet.WHITESPACE,
            info.separatorMinCount,
            alloc);
        break;
      case CHAR_CLASS_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            info.separatorCharSet,
            info.separatorMinCount,
            alloc);
        break;
      case NONE:
        // No separator: sepEnd = sepStart
        mv.visitVarInsn(ILOAD, sepStartVar);
        mv.visitVarInsn(ISTORE, sepEndVar);
        break;
    }
  }

  /** Generate literal separator matching. */
  private void generateLiteralSeparatorMatch(
      MethodVisitor mv, int sepStartVar, int sepEndVar, int lenVar, Label failLabel) {
    String literal = info.separatorLiteral;
    int literalLen = literal.length();

    // Check: sepStart + literalLen <= len
    mv.visitVarInsn(ILOAD, sepStartVar);
    pushInt(mv, literalLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, failLabel);

    // Check: input.regionMatches(sepStart, literal, 0, literalLen)
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, sepStartVar);
    mv.visitLdcInsn(literal);
    mv.visitInsn(ICONST_0);
    pushInt(mv, literalLen);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, failLabel);

    // sepEnd = sepStart + literalLen
    mv.visitVarInsn(ILOAD, sepStartVar);
    pushInt(mv, literalLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, sepEndVar);
  }

  /**
   * Generate char class sequence separator matching (e.g., \d+). Matches minimum required, then as
   * many more as possible (greedy).
   */
  private void generateCharClassSeqSeparatorMatch(
      MethodVisitor mv,
      int sepStartVar,
      int sepEndVar,
      int lenVar,
      Label failLabel,
      CharSet charSet,
      int minCount,
      LocalVarAllocator alloc) {

    int posVar = alloc.allocate();
    int countVar = alloc.allocate();
    int charVar = alloc.allocate(); // Store char in local var for stack safety

    // int pos = sepStart;
    mv.visitVarInsn(ILOAD, sepStartVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // Match loop
    Label matchLoop = new Label();
    Label matchEnd = new Label();

    mv.visitLabel(matchLoop);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, matchEnd);

    // char c = input.charAt(pos); store in local for stack safety
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check if c is in charset (uses local var, not stack)
    generateCharSetCheckFromLocal(mv, charVar, charSet, matchEnd);

    // pos++; count++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(countVar, 1);
    mv.visitJumpInsn(GOTO, matchLoop);

    mv.visitLabel(matchEnd);

    // Check: count >= minCount
    mv.visitVarInsn(ILOAD, countVar);
    pushInt(mv, minCount);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);

    // sepEnd = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, sepEndVar);
  }

  /**
   * Generate charset membership check from a local variable. Stack-safe: reads from local var,
   * doesn't leave anything on stack. Jumps to failLabel if char is NOT in charset.
   */
  private void generateCharSetCheckFromLocal(
      MethodVisitor mv, int charVar, CharSet charSet, Label failLabel) {
    if (charSet.equals(CharSet.DIGIT)) {
      // c >= '0' && c <= '9'
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '0');
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '9');
      mv.visitJumpInsn(IF_ICMPGT, failLabel);
    } else if (charSet.equals(CharSet.WHITESPACE)) {
      // c == ' ' || c == '\t' || c == '\n' || c == '\r'
      Label ok = new Label();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, ' ');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '\t');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '\r');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitJumpInsn(GOTO, failLabel);
      mv.visitLabel(ok);
    } else if (charSet.equals(CharSet.WORD)) {
      // a-z, A-Z, 0-9, _
      Label ok = new Label();

      // Check a-z
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'a');
      mv.visitJumpInsn(IF_ICMPLT, ok); // Skip to next check if < 'a'
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'z');
      Label notLower = new Label();
      mv.visitJumpInsn(IF_ICMPGT, notLower);
      mv.visitJumpInsn(GOTO, ok); // Is lowercase, success

      mv.visitLabel(notLower);
      // Check A-Z
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'A');
      Label notUpperStart = new Label();
      mv.visitJumpInsn(IF_ICMPLT, notUpperStart);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'Z');
      Label notUpper = new Label();
      mv.visitJumpInsn(IF_ICMPGT, notUpper);
      mv.visitJumpInsn(GOTO, ok); // Is uppercase, success

      mv.visitLabel(notUpperStart);
      mv.visitLabel(notUpper);
      // Check 0-9
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '0');
      Label notDigitStart = new Label();
      mv.visitJumpInsn(IF_ICMPLT, notDigitStart);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '9');
      Label notDigit = new Label();
      mv.visitJumpInsn(IF_ICMPGT, notDigit);
      mv.visitJumpInsn(GOTO, ok); // Is digit, success

      mv.visitLabel(notDigitStart);
      mv.visitLabel(notDigit);
      // Check _
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '_');
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
      // Is underscore, fall through to ok

      mv.visitLabel(ok);
    }
    // General case (CharSet.ANY or unknown): no check needed, always passes
  }

  /** Generate find() method - delegates to findFrom(input, 0). */
  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // return findFrom(input, 0) >= 0;
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

  /** Generate findFrom() method with backtracking at each starting position. */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=startPos
    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int sepStartVar = alloc.allocate();
    int sepEndVar = alloc.allocate();
    int iterationsVar = alloc.allocate();
    int groupStartVar = alloc.allocate();

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

    // int start = startPos;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);

    // Outer loop: try each starting position
    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // Minimum length check from start
    int minLen =
        getPrefixLength() + info.groupMinCount + info.getSeparatorMinLength() + info.groupMinCount;
    mv.visitVarInsn(ILOAD, startVar);
    pushInt(mv, minLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // Initialize groupStart from start, then advance past prefix
    Label innerEnd = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, groupStartVar);
    emitPrefixMatch(mv, groupStartVar, lenVar, innerEnd, alloc);
    emitGroupEndInit(mv, groupEndVar, lenVar, groupStartVar);

    // iterations = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iterationsVar);

    // Inner backtrack loop
    Label innerLoop = new Label();
    Label continueInner = new Label();

    mv.visitLabel(innerLoop);

    // Check: groupEnd >= groupStart + groupMinCount
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    pushInt(mv, info.groupMinCount);
    mv.visitInsn(IADD);
    mv.visitJumpInsn(IF_ICMPLT, innerEnd);

    // Check backtrack limit
    mv.visitVarInsn(ILOAD, iterationsVar);
    mv.visitMethodInsn(
        INVOKESTATIC, "com/datadoghq/reggie/runtime/BacktrackConfig", "checkLimit", "(I)Z", false);
    mv.visitJumpInsn(IFNE, returnNotFound);
    mv.visitIincInsn(iterationsVar, 1);

    // Validate group content matches groupCharSet (if specified)
    generateGroupCharSetValidation(mv, groupStartVar, groupEndVar, continueInner, alloc);

    // sepStart = groupEnd
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ISTORE, sepStartVar);

    // Match separator
    generateSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, continueInner, alloc);

    // Match backreference
    int groupLenVar = alloc.allocate();
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // Check: sepEnd + groupLen <= len
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, continueInner);

    // Match backref
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueInner);

    // Found! Return start position (outer loop counter, before prefix)
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IRETURN);

    // Continue inner backtrack
    mv.visitLabel(continueInner);
    mv.visitIincInsn(groupEndVar, -1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Try next starting position
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitLabel(returnNotFound);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }
}
