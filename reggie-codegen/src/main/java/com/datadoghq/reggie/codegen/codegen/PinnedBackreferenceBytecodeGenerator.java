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

import com.datadoghq.reggie.codegen.analysis.PinnedBackreferenceInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Bytecode generator for PINNED_BACKREFERENCE strategy.
 *
 * <p>Generates matching code for patterns where a capturing group's content charset is provably
 * disjoint from whatever immediately follows it (and, if present, from an optional separator
 * between the group's close and the backreference site). Because the group's closing boundary is
 * therefore unambiguous, matching a candidate reduces to: forward-scan the group's content to its
 * charset boundary, forward-scan the separator (if any) to its own charset boundary, then a single
 * length-matched equality check against the backreference site. No retry or backtracking loop is
 * needed for a given start position.
 *
 * <h3>Supported Pattern Shape</h3>
 *
 * <ul>
 *   <li>{@code (\w+)\s+\1} - word charset disjoint from whitespace separator charset
 * </ul>
 *
 * <p>Modeled directly on {@link FixedRepetitionBackrefBytecodeGenerator} (single forward
 * verification pass, no retry loop) rather than {@code VariableCaptureBackrefBytecodeGenerator}'s
 * backtracking-over-length approach.
 *
 * <p>Limitation: like {@code FixedRepetitionBackrefInfo}, this generator assumes the backreferenced
 * group is the pattern's only capturing group (no independent prefix/suffix groups) - {@link
 * #generateMatchMethod} and {@link #generateFindMatchFromMethod} report a single group's span.
 */
public class PinnedBackreferenceBytecodeGenerator {

  private final PinnedBackreferenceInfo info;
  private final String className;

  public PinnedBackreferenceBytecodeGenerator(PinnedBackreferenceInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Total capturing group count this generator supports (the referenced group only). */
  private int totalGroupCount() {
    return info.groupIndex;
  }

  /** Generate the matches() method. */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int scanCharVar = alloc.allocate();
    int sepStartVar = info.hasSeparator() ? alloc.allocate() : -1;
    int sepLenVar = info.hasSeparator() ? alloc.allocate() : -1;

    Label returnFalse = new Label();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.groupCharSet);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);
    generateGroupLengthCheck(mv, groupLenVar, returnFalse);

    if (info.hasSeparator()) {
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, sepStartVar);
      generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.separatorCharSet);
      generateSeparatorLengthCheck(mv, posVar, sepStartVar, sepLenVar, returnFalse);
    }

    generateBackrefCheck(mv, posVar, lenVar, groupStartVar, groupLenVar, returnFalse);

    // matches() requires the whole input consumed.
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);

    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(5, alloc.peek());
    mv.visitEnd();
  }

  /** Generate the find() method - delegates to findFrom(input, 0). */
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

  /**
   * Minimum number of chars a match needs from a given start: {@code groupMinLength} for the group,
   * {@code separatorMinLength} for the separator (if present), plus {@code groupMinLength} again
   * for the backreference echo, which always matches the group's length.
   */
  private int minCandidateLength() {
    return 2 * info.groupMinLength + (info.hasSeparator() ? info.separatorMinLength : 0);
  }

  /** Generate the findFrom() method. */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int scanCharVar = alloc.allocate();
    int sepStartVar = info.hasSeparator() ? alloc.allocate() : -1;
    int sepLenVar = info.hasSeparator() ? alloc.allocate() : -1;

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = Math.max(0, startPos);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, startVar);
    mv.visitLabel(startNotNeg);

    Label outerLoop = new Label();
    Label outerEnd = new Label();
    Label tryNextStart = new Label();
    mv.visitLabel(outerLoop);

    // if (start + minCandidateLength > len) break;
    mv.visitVarInsn(ILOAD, startVar);
    BytecodeUtil.pushInt(mv, minCandidateLength());
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.groupCharSet);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);
    generateGroupLengthCheck(mv, groupLenVar, tryNextStart);

    if (info.hasSeparator()) {
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, sepStartVar);
      generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.separatorCharSet);
      generateSeparatorLengthCheck(mv, posVar, sepStartVar, sepLenVar, tryNextStart);
    }

    generateBackrefCheck(mv, posVar, lenVar, groupStartVar, groupLenVar, tryNextStart);

    // Found - return start position.
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IRETURN);

    mv.visitLabel(tryNextStart);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(5, alloc.peek());
    mv.visitEnd();
  }

  /** Generate match(String) method that returns MatchResult with the referenced group's span. */
  public void generateMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int scanCharVar = alloc.allocate();
    int sepStartVar = info.hasSeparator() ? alloc.allocate() : -1;
    int sepLenVar = info.hasSeparator() ? alloc.allocate() : -1;
    int startsArrayVar = alloc.allocate();
    int endsArrayVar = alloc.allocate();

    Label returnNull = new Label();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    BytecodeUtil.pushInt(mv, totalGroupCount() + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsArrayVar);

    BytecodeUtil.pushInt(mv, totalGroupCount() + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsArrayVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // starts[0] = 0
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);

    // starts[groupIndex] = pos
    mv.visitVarInsn(ALOAD, startsArrayVar);
    BytecodeUtil.pushInt(mv, info.groupIndex);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.groupCharSet);

    // groupLen = pos - groupStart; require it meets the quantifier's minimum.
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);
    generateGroupLengthCheck(mv, groupLenVar, returnNull);

    // ends[groupIndex] = pos
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, info.groupIndex);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    if (info.hasSeparator()) {
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, sepStartVar);
      generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.separatorCharSet);
      generateSeparatorLengthCheck(mv, posVar, sepStartVar, sepLenVar, returnNull);
    }

    generateBackrefCheck(mv, posVar, lenVar, groupStartVar, groupLenVar, returnNull);

    // matches() semantics: require full input consumed.
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, returnNull);

    // ends[0] = pos
    mv.visitVarInsn(ALOAD, endsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, totalGroupCount());
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Generate matchesBounded(CharSequence, int, int) - extracts the bounded region and delegates to
   * matches(String).
   */
  public void generateMatchesBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(4);
    int subStringVar = alloc.allocate();

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
    mv.visitVarInsn(ASTORE, subStringVar);

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, subStringVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(3, alloc.peek());
    mv.visitEnd();
  }

  /** Generate findMatch(String) - delegates to findMatchFrom(input, 0). */
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
   * Generate findMatchFrom(String, int) - iterates start positions from startPos; at each, scans
   * the group and (optional) separator to their charset boundaries and verifies the backreference
   * with a single equality check (no retry). Returns a MatchResult on success, without requiring
   * full-input consumption.
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

    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int scanCharVar = alloc.allocate();
    int sepStartVar = info.hasSeparator() ? alloc.allocate() : -1;
    int sepLenVar = info.hasSeparator() ? alloc.allocate() : -1;
    int startsArrayVar = alloc.allocate();
    int endsArrayVar = alloc.allocate();

    Label returnNull = new Label();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = Math.max(0, startPos);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, startVar);
    mv.visitLabel(startNotNeg);

    BytecodeUtil.pushInt(mv, totalGroupCount() + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsArrayVar);

    BytecodeUtil.pushInt(mv, totalGroupCount() + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsArrayVar);

    Label outerLoop = new Label();
    Label outerEnd = new Label();
    Label tryNextStart = new Label();
    mv.visitLabel(outerLoop);

    // if (start + minCandidateLength > len) break;
    mv.visitVarInsn(ILOAD, startVar);
    BytecodeUtil.pushInt(mv, minCandidateLength());
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    // starts[0] = start
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IASTORE);

    // starts[groupIndex] = pos
    mv.visitVarInsn(ALOAD, startsArrayVar);
    BytecodeUtil.pushInt(mv, info.groupIndex);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.groupCharSet);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);
    generateGroupLengthCheck(mv, groupLenVar, tryNextStart);

    // ends[groupIndex] = pos
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, info.groupIndex);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    if (info.hasSeparator()) {
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, sepStartVar);
      generateCharSetScan(mv, posVar, lenVar, scanCharVar, info.separatorCharSet);
      generateSeparatorLengthCheck(mv, posVar, sepStartVar, sepLenVar, tryNextStart);
    }

    generateBackrefCheck(mv, posVar, lenVar, groupStartVar, groupLenVar, tryNextStart);

    // ends[0] = pos
    mv.visitVarInsn(ALOAD, endsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, totalGroupCount());
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitLabel(tryNextStart);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Forward-scan while {@code input.charAt(pos)} is a member of {@code charSet}, advancing {@code
   * posVar} past every such char. Safe as a single pass (no retry) because of the disjointness
   * proof that produced this {@link PinnedBackreferenceInfo}: the boundary this scan stops at is
   * the only candidate boundary.
   */
  private void generateCharSetScan(
      MethodVisitor mv, int posVar, int lenVar, int charVar, CharSet charSet) {
    Label loop = new Label();
    Label end = new Label();
    mv.visitLabel(loop);

    // if (pos >= len) goto end;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, end);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (c not in charSet) goto end;
    generateCharSetCheck(mv, charVar, charSet, end);

    // pos++
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loop);

    mv.visitLabel(end);
  }

  /**
   * Single length-matched equality check of the backreference site against the captured group:
   * {@code if (pos + groupLen > len || !input.regionMatches(pos, input, groupStart, groupLen)) goto
   * failLabel;} then advances {@code posVar} by {@code groupLenVar}.
   */
  private void generateBackrefCheck(
      MethodVisitor mv,
      int posVar,
      int lenVar,
      int groupStartVar,
      int groupLenVar,
      Label failLabel) {
    // if (pos + groupLen > len) goto failLabel;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, failLabel);

    // if (!input.regionMatches(pos, input, groupStart, groupLen)) goto failLabel;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, failLabel);

    // pos += groupLen;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);
  }

  /**
   * Generate charset membership check. Jumps to failLabel if the char is NOT in the charset.
   * Generalized (unlike {@link FixedRepetitionBackrefBytecodeGenerator}'s DIGIT/WORD-only variant)
   * to handle arbitrary charsets via range unrolling, following the same range-check technique
   * {@code GreedyCharClassBytecodeGenerator} uses.
   */
  private void generateCharSetCheck(
      MethodVisitor mv, int charVar, CharSet charSet, Label failLabel) {
    if (charSet.isSingleChar()) {
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, charSet.getSingleChar());
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
    } else if (charSet.isSimpleRange()) {
      CharSet.Range range = charSet.getSimpleRange();
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, range.start);
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, range.end);
      mv.visitJumpInsn(IF_ICMPGT, failLabel);
    } else {
      Label matches = new Label();
      for (CharSet.Range range : charSet.getRanges()) {
        Label tryNext = new Label();
        mv.visitVarInsn(ILOAD, charVar);
        BytecodeUtil.pushInt(mv, range.start);
        mv.visitJumpInsn(IF_ICMPLT, tryNext);
        mv.visitVarInsn(ILOAD, charVar);
        BytecodeUtil.pushInt(mv, range.end);
        mv.visitJumpInsn(IF_ICMPLE, matches);
        mv.visitLabel(tryNext);
      }
      mv.visitJumpInsn(GOTO, failLabel);
      mv.visitLabel(matches);
    }
  }

  /**
   * Jumps to {@code failLabel} unless the scanned group run meets the quantifier's minimum length
   * ({@code info.groupMinLength}).
   */
  private void generateGroupLengthCheck(MethodVisitor mv, int groupLenVar, Label failLabel) {
    mv.visitVarInsn(ILOAD, groupLenVar);
    BytecodeUtil.pushInt(mv, info.groupMinLength);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
  }

  /**
   * Computes the scanned separator run length ({@code posVar - sepStartVar}), stores it in {@code
   * sepLenVar}, and jumps to {@code failLabel} unless that length falls within the separator
   * quantifier's bounds ({@code info.separatorMinLength}..{@code info.separatorMaxLength}, where -1
   * means unbounded).
   */
  private void generateSeparatorLengthCheck(
      MethodVisitor mv, int posVar, int sepStartVar, int sepLenVar, Label failLabel) {
    // sepLen = pos - sepStart
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, sepStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, sepLenVar);

    // if (sepLen < info.separatorMinLength) goto failLabel;
    mv.visitVarInsn(ILOAD, sepLenVar);
    BytecodeUtil.pushInt(mv, info.separatorMinLength);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);

    if (info.separatorMaxLength != -1) {
      // if (sepLen > info.separatorMaxLength) goto failLabel;
      mv.visitVarInsn(ILOAD, sepLenVar);
      BytecodeUtil.pushInt(mv, info.separatorMaxLength);
      mv.visitJumpInsn(IF_ICMPGT, failLabel);
    }
  }
}
