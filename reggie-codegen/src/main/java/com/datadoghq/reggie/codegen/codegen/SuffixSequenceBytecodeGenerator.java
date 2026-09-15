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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.OptionalLiteralElement;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.SequenceElement;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.SuffixSequenceInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates specialized bytecode for suffix-anchored run patterns: {@code [optional literals] run
 * $/\Z/\z} where the run is an unbounded quantified single char-class ({@code \.?0+$}, {@code
 * \s+$}). Every match ends at an anchor end, so {@code findFrom} is a greedy backward scan from the
 * anchor end candidate(s) instead of the forward candidate loop.
 *
 * <h3>Generated algorithm (pattern \.?0+$, anchor $)</h3>
 *
 * <pre>{@code
 * int findFrom(String input, int start) {
 *   if (input == null) return -1;
 *   if (start < 0) start = 0;
 *   int len = input.length();
 *   if (start > len) return -1;
 *   int best = -1, bestEnd = -1;
 *   // Anchor end A: end-of-input. Anchor end B (for $ / \Z, not \z): before the final line
 *   // terminator, when the input ends with one (\n, \r\n, \r, NEL, LS, PS).
 *   for (int end : anchorEnds) {
 *     int s = end;
 *     while (s > 0 && isRunChar(input.charAt(s - 1))) s--;   // greedy backward run
 *     if (end - s >= runMin) {                                // run length floor (+ / {n,})
 *       // optional-literal prefix, right to left: include greedily for the leftmost match
 *       if (s > 0 && input.charAt(s - 1) == '.') s--;
 *       // valid starts are contiguous [s, end - runMin] (all-optional prefix), so the
 *       // leftmost start >= requested start is max(s, start), when start <= end - runMin
 *       int cand = (start <= s) ? s : (start <= end - runMin ? start : -1);
 *       if (cand >= 0 && (best == -1 || cand < best)) { best = cand; bestEnd = end; }
 *     }
 *   }
 *   return best;
 * }
 * boolean matches(String input) { return findFrom(input, 0) == 0; }
 * }</pre>
 *
 * <p>find / findMatch(From) / findBoundsFrom delegate to the same scan (the scan captures the
 * winning anchor end for the match span); matchesBounded / matchBounded delegate via subSequence to
 * matches / match, mirroring {@link FixedSequenceBytecodeGenerator}.
 */
public class SuffixSequenceBytecodeGenerator {
  private final SuffixSequenceInfo info;

  public SuffixSequenceBytecodeGenerator(SuffixSequenceInfo info, int nfaGroupCount) {
    this.info = info;
    // SuffixSequenceInfo is only produced for groupless patterns (detector declines groups).
  }

  /** Line terminators recognized by $ / \Z before the final terminator. */
  private static final char[] LINE_TERMINATORS = {'\n', '\r', '\u0085', '\u2028', '\u2029'};

  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    // if (input == null) return false;
    mv.visitVarInsn(ALOAD, 1);
    Label notNull = new Label();
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);
    // Full-region match: the whole input must be [optional-literal prefix][run]. Scan backward
    // from the absolute end only - matches() requires the entire region, so the $ / \Z
    // before-final-terminator end is NOT usable here ("000\n" does not fully match \.?0+$).
    LocalVarAllocator allocator = new LocalVarAllocator(2); // 0=this, 1=input
    int lenVar = allocator.allocate();
    int scanVar = allocator.allocate();
    int charVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    emitFullMatchScan(mv, 1, lenVar, scanVar, charVar);
    // return s == 0;
    mv.visitVarInsn(ILOAD, scanVar);
    Label isMatch = new Label();
    mv.visitJumpInsn(IFEQ, isMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(isMatch);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits the backward extension from the absolute end (input.length()): run chars backward,
   * require the run-length floor, then the optional-literal prefix greedily. On fall-through
   * scanVar holds the backward-extended start s (a full match is exactly s == 0); when the run
   * floor fails, scanVar is set to -1.
   */
  private void emitFullMatchScan(
      MethodVisitor mv, int inputVar, int lenVar, int scanVar, int charVar) {
    // s = len;
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitVarInsn(ISTORE, scanVar);
    // while (s > 0 && isRunChar(input.charAt(s - 1))) s--;
    Label runLoop = new Label();
    Label runDone = new Label();
    mv.visitLabel(runLoop);
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitJumpInsn(IFLE, runDone);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, scanVar);
    pushInt(mv, 1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);
    generateRunCharCheck(mv, charVar, runDone);
    mv.visitIincInsn(scanVar, -1);
    mv.visitJumpInsn(GOTO, runLoop);
    mv.visitLabel(runDone);
    // if (len - s < runMin) s = -1;
    Label floorOk = new Label();
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitInsn(ISUB);
    pushInt(mv, info.runMin);
    mv.visitJumpInsn(IF_ICMPGE, floorOk);
    pushInt(mv, -1);
    mv.visitVarInsn(ISTORE, scanVar);
    mv.visitLabel(floorOk);
    // Optional-literal prefix, last element to first.
    for (int i = info.elements.size() - 1; i >= 0; i--) {
      SequenceElement elem = info.elements.get(i);
      OptionalLiteralElement opt = (OptionalLiteralElement) elem;
      Label skip = new Label();
      mv.visitVarInsn(ILOAD, scanVar);
      mv.visitJumpInsn(IFLE, skip); // s <= 0: cannot include
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, scanVar);
      pushInt(mv, 1);
      mv.visitInsn(ISUB);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) opt.ch);
      mv.visitJumpInsn(IF_ICMPNE, skip);
      mv.visitIincInsn(scanVar, -1);
      mv.visitLabel(skip);
    }
  }

  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    // if (findFrom(input, 0) >= 0) return true; else false;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    Label noMatch = new Label();
    mv.visitJumpInsn(IFLT, noMatch);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(noMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();
    LocalVarAllocator allocator = new LocalVarAllocator(3); // 0=this, 1=input, 2=start
    mv.visitVarInsn(ALOAD, 1);
    Label notNull = new Label();
    mv.visitJumpInsn(IFNONNULL, notNull);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);
    // if (start < 0) start = 0;
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startNotNeg);

    int lenVar = allocator.allocate();
    int bestVar = allocator.allocate();
    int bestEndVar = allocator.allocate();
    int endVar = allocator.allocate();
    int scanVar = allocator.allocate(); // scan position s / candidate
    int charVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    // if (start > len) return -1;
    Label inRange = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPLE, inRange);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(inRange);

    // best = -1; bestEnd = -1
    pushInt(mv, -1);
    mv.visitVarInsn(ISTORE, bestVar);
    pushInt(mv, -1);
    mv.visitVarInsn(ISTORE, bestEndVar);

    // Anchor end A: end = len
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitVarInsn(ISTORE, endVar);
    emitBackwardScan(mv, 1, 2, endVar, scanVar, charVar, bestVar, bestEndVar);

    // Anchor end B (only for $ / \Z): before the final line terminator, if the input ends with
    // one. \z (STRING_END_ABSOLUTE) matches the absolute end only.
    if (info.anchorType != com.datadoghq.reggie.codegen.ast.AnchorNode.Type.STRING_END_ABSOLUTE) {
      emitTerminatorEndAndScan(mv, 1, lenVar, endVar, scanVar, charVar, bestVar, bestEndVar);
    }

    // return best;
    mv.visitVarInsn(ILOAD, bestVar);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    // Full-region match (same semantics as matches()): backward extension from the absolute
    // end must reach 0; the result span is [0, len). The before-final-terminator $ / \Z end
    // is not usable for a full match.
    LocalVarAllocator allocator = new LocalVarAllocator(2); // 0=this, 1=input
    int lenVar = allocator.allocate();
    int scanVar = allocator.allocate();
    int charVar = allocator.allocate();
    int zeroVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, zeroVar);

    emitFullMatchScan(mv, 1, lenVar, scanVar, charVar);

    // if (s != 0) return null;
    Label isMatch = new Label();
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitJumpInsn(IFEQ, isMatch);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(isMatch);

    // result [0, len)
    emitMatchResult(mv, 1, zeroVar, lenVar);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();
    // return matches(input.subSequence(start, end).toString());
    mv.visitVarInsn(ALOAD, 0);
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
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    // return match(input.subSequence(start, end).toString());
    mv.visitVarInsn(ALOAD, 0);
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

  public void generateFindMatchMethod(ClassWriter cw, String className) {
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
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    LocalVarAllocator allocator = new LocalVarAllocator(3); // 0=this, 1=input, 2=start
    int lenVar = allocator.allocate();
    int bestVar = allocator.allocate();
    int bestEndVar = allocator.allocate();
    int endVar = allocator.allocate();
    int scanVar = allocator.allocate();
    int charVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    pushInt(mv, -1);
    mv.visitVarInsn(ISTORE, bestVar);
    pushInt(mv, -1);
    mv.visitVarInsn(ISTORE, bestEndVar);

    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitVarInsn(ISTORE, endVar);
    emitBackwardScan(mv, 1, 2, endVar, scanVar, charVar, bestVar, bestEndVar);
    if (info.anchorType != com.datadoghq.reggie.codegen.ast.AnchorNode.Type.STRING_END_ABSOLUTE) {
      emitTerminatorEndAndScan(mv, 1, lenVar, endVar, scanVar, charVar, bestVar, bestEndVar);
    }

    // if (best < 0) return null;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, bestVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(found);

    emitMatchResult(mv, 1, bestVar, bestEndVar);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();
    LocalVarAllocator allocator = new LocalVarAllocator(4); // 0=this,1=input,2=start,3=bounds
    int lenVar = allocator.allocate();
    int bestVar = allocator.allocate();
    int bestEndVar = allocator.allocate();
    int endVar = allocator.allocate();
    int scanVar = allocator.allocate();
    int charVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    pushInt(mv, -1);
    mv.visitVarInsn(ISTORE, bestVar);
    pushInt(mv, -1);
    mv.visitVarInsn(ISTORE, bestEndVar);

    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitVarInsn(ISTORE, endVar);
    emitBackwardScan(mv, 1, 2, endVar, scanVar, charVar, bestVar, bestEndVar);
    if (info.anchorType != com.datadoghq.reggie.codegen.ast.AnchorNode.Type.STRING_END_ABSOLUTE) {
      emitTerminatorEndAndScan(mv, 1, lenVar, endVar, scanVar, charVar, bestVar, bestEndVar);
    }

    // if (best < 0) return false;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, bestVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);

    // bounds[0] = best; bounds[1] = bestEnd;
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, bestVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, bestEndVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits: if the input's final char is a line terminator, set end = len - tlen (CRLF = 2) and run
   * the backward scan for that anchor end; otherwise fall through without scanning.
   */
  private void emitTerminatorEndAndScan(
      MethodVisitor mv,
      int inputVar,
      int lenVar,
      int endVar,
      int scanVar,
      int charVar,
      int bestVar,
      int bestEndVar) {
    Label done = new Label();
    // if (len == 0) goto done;
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IFEQ, done);
    // char c = input.charAt(len - 1);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, 1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);
    // if (c is not a terminator) goto done;
    Label isTerm = new Label();
    for (char t : LINE_TERMINATORS) {
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) t);
      Label nextTerm = new Label();
      mv.visitJumpInsn(IF_ICMPNE, nextTerm);
      mv.visitJumpInsn(GOTO, isTerm);
      mv.visitLabel(nextTerm);
    }
    mv.visitJumpInsn(GOTO, done);
    mv.visitLabel(isTerm);
    // end = len - 1;
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, 1);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, endVar);
    // if (c == '\n' && len >= 2 && input.charAt(len - 2) == '\r') end = len - 2;
    Label notCrLf = new Label();
    mv.visitVarInsn(ILOAD, charVar);
    pushInt(mv, (int) '\n');
    mv.visitJumpInsn(IF_ICMPNE, notCrLf);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, 2);
    mv.visitJumpInsn(IF_ICMPLT, notCrLf);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, 2);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    pushInt(mv, (int) '\r');
    mv.visitJumpInsn(IF_ICMPNE, notCrLf);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, 2);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, endVar);
    mv.visitLabel(notCrLf);
    emitBackwardScan(mv, inputVar, 2, endVar, scanVar, charVar, bestVar, bestEndVar);
    mv.visitLabel(done);
  }

  /**
   * Emits the greedy backward scan for one anchored end. On fall-through, (best, bestEnd) hold the
   * best (smallest) candidate start for this end if any; unchanged otherwise.
   *
   * <pre>{@code
   * s = end;
   * while (s > 0 && isRunChar(input.charAt(s - 1))) s--;
   * if (end - s >= runMin) {
   *   // optional-literal prefix, last to first
   *   ... if (s > 0 && input.charAt(s - 1) == ch) s--; ...
   *   cand = (requestedStart <= s) ? s : (requestedStart <= end - runMin ? requestedStart : -1);
   *   if (cand >= 0 && (best == -1 || cand < best)) { best = cand; bestEnd = end; }
   * }
   * }</pre>
   */
  private void emitBackwardScan(
      MethodVisitor mv,
      int inputVar,
      int requestedStartVar,
      int endVar,
      int scanVar,
      int charVar,
      int bestVar,
      int bestEndVar) {
    // s = end;
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitVarInsn(ISTORE, scanVar);

    // while (s > 0 && isRunChar(input.charAt(s - 1))) s--;
    Label runLoop = new Label();
    Label runDone = new Label();
    mv.visitLabel(runLoop);
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitJumpInsn(IFLE, runDone);
    // char c = input.charAt(s - 1);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, scanVar);
    pushInt(mv, 1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);
    // if (!isRunChar(c)) goto runDone;
    generateRunCharCheck(mv, charVar, runDone);
    mv.visitIincInsn(scanVar, -1);
    mv.visitJumpInsn(GOTO, runLoop);
    mv.visitLabel(runDone);

    // if (end - s >= runMin) { ... }
    Label scanDone = new Label();
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitInsn(ISUB);
    pushInt(mv, info.runMin);
    mv.visitJumpInsn(IF_ICMPLT, scanDone);

    // Optional-literal prefix, last element to first: include greedily for leftmost.
    for (int i = info.elements.size() - 1; i >= 0; i--) {
      SequenceElement elem = info.elements.get(i);
      OptionalLiteralElement opt = (OptionalLiteralElement) elem;
      Label skip = new Label();
      // if (s <= 0) goto skip;
      mv.visitVarInsn(ILOAD, scanVar);
      mv.visitJumpInsn(IFLE, skip);
      // if (input.charAt(s - 1) != ch) goto skip;
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, scanVar);
      pushInt(mv, 1);
      mv.visitInsn(ISUB);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) opt.ch);
      mv.visitJumpInsn(IF_ICMPNE, skip);
      // s--;
      mv.visitIincInsn(scanVar, -1);
      mv.visitLabel(skip);
    }

    // cand = (requestedStart <= s) ? s : (requestedStart <= end - runMin ? requestedStart : -1)
    Label viaS = new Label();
    Label haveCand = new Label();
    mv.visitVarInsn(ILOAD, requestedStartVar);
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitJumpInsn(IF_ICMPLE, viaS);
    // requestedStart > s: if (requestedStart > end - runMin) goto scanDone (no candidate)
    mv.visitVarInsn(ILOAD, requestedStartVar);
    mv.visitVarInsn(ILOAD, endVar);
    pushInt(mv, info.runMin);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, scanDone);
    // cand = requestedStart;
    mv.visitVarInsn(ILOAD, requestedStartVar);
    mv.visitVarInsn(ISTORE, scanVar);
    mv.visitJumpInsn(GOTO, haveCand);
    mv.visitLabel(viaS);
    // cand = s (scanVar already holds the backward-extended start)
    mv.visitLabel(haveCand);

    // if (cand >= 0 && (best == -1 || cand < best)) { best = cand; bestEnd = end; }
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitJumpInsn(IFLT, scanDone);
    Label updateBest = new Label();
    mv.visitVarInsn(ILOAD, bestVar);
    mv.visitJumpInsn(IFLT, updateBest);
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitVarInsn(ILOAD, bestVar);
    mv.visitJumpInsn(IF_ICMPGE, scanDone);
    mv.visitLabel(updateBest);
    mv.visitVarInsn(ILOAD, scanVar);
    mv.visitVarInsn(ISTORE, bestVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitVarInsn(ISTORE, bestEndVar);
    mv.visitLabel(scanDone);
  }

  /**
   * Emits an inline charset check that jumps to {@code exitLabel} when the char in {@code charVar}
   * does NOT belong to the run's charset (mirrors FixedSequenceBytecodeGenerator's idiom).
   */
  private void generateRunCharCheck(MethodVisitor mv, int charVar, Label exitLabel) {
    CharSet charset = info.runCharset;
    boolean negated = info.runNegated;
    if (charset.isSingleChar()) {
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      if (negated) {
        mv.visitJumpInsn(IF_ICMPEQ, exitLabel);
      } else {
        mv.visitJumpInsn(IF_ICMPNE, exitLabel);
      }
    } else if (charset.isSimpleRange()) {
      CharSet.Range range = charset.getSimpleRange();
      if (negated) {
        Label notInRange = new Label();
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, notInRange);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, exitLabel);
        mv.visitLabel(notInRange);
      } else {
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, exitLabel);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPGT, exitLabel);
      }
    } else {
      if (negated) {
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, exitLabel);
          mv.visitLabel(tryNext);
        }
      } else {
        Label matches = new Label();
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, matches);
          mv.visitLabel(tryNext);
        }
        // No range matched - exit
        mv.visitJumpInsn(GOTO, exitLabel);
        mv.visitLabel(matches);
      }
    }
  }

  /** Emits {@code new MatchResultImpl(input, new int[]{start}, new int[]{end}, 0)}. */
  private void emitMatchResult(MethodVisitor mv, int inputVar, int bestVar, int bestEndVar) {
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    // starts array
    pushInt(mv, 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, bestVar);
    mv.visitInsn(IASTORE);
    // ends array
    pushInt(mv, 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, bestEndVar);
    mv.visitInsn(IASTORE);
    // groupCount = 0 (detector declines capturing groups)
    pushInt(mv, 0);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
  }
}
