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

import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.emitCharSetCheck;
import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.invokeEnd;
import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt;
import static org.objectweb.asm.Opcodes.*;

import com.datadoghq.reggie.codegen.analysis.GreedySuffixInfo;
import com.datadoghq.reggie.codegen.analysis.GreedySuffixInfo.EndKind;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates the GREEDY_SUFFIX matcher for {@code P (G{m,}) [\b] T [END]} (see {@link
 * GreedySuffixInfo} and doc/plans/greedy-suffix-strategy.md).
 *
 * <p>All APIs go through one static {@code search}. For each start {@code st} it checks P, runs G
 * greedily to {@code R}, and gives back from the highest feasible suffix start {@code q} down to
 * {@code st + w + m}, returning the first {@code q} where the suffix matches. This is the order in
 * which a backtracking engine tries the candidates, so the spans agree with it.
 *
 * <p>When every {@code q} fails at {@code st}, the next start is {@code R - w + 1}: a later start
 * whose G run begins inside {@code [st + w, R]} ends its run at the same {@code R}, so it tries a
 * subset of the same {@code q}s, and whether the suffix matches at {@code q} does not depend on the
 * start.
 */
public class GreedySuffixBytecodeGenerator {

  private static final String CHAR_SEQUENCE = "java/lang/CharSequence";

  /**
   * The chars {@link CharSet#ANY_EXCEPT_NEWLINE} excludes, in the order the run scan looks for
   * them.
   */
  private static final char[] LINE_TERMINATORS = {'\n', '\r', '\u0085', '\u2028', '\u2029'};

  /**
   * Chars of a String G run scanned one by one before switching to {@code String.indexOf}, whose
   * fixed per-call cost is higher than this loop on short runs.
   */
  private static final int LOOP_SCAN = 64;

  private static final String MATCH_RESULT = "com/datadoghq/reggie/runtime/MatchResult";
  private static final String MATCH_RESULT_IMPL = "com/datadoghq/reggie/runtime/MatchResultImpl";
  private static final String SEARCH_DESC = "(Ljava/lang/CharSequence;IIIZZ)J";
  private static final String RUN_END_DESC = "(Ljava/lang/CharSequence;II)I";
  private static final String SUFFIX_END_DESC = "(Ljava/lang/CharSequence;II)I";
  private static final String RESULT_DESC =
      "(Ljava/lang/String;III)Lcom/datadoghq/reggie/runtime/MatchResult;";

  private final GreedySuffixInfo info;
  private final int w;
  private final int minSuffix;

  public GreedySuffixBytecodeGenerator(GreedySuffixInfo info) {
    this.info = info;
    this.w = info.prefix.length;
    this.minSuffix = info.minSuffixWidth();
  }

  private static String cn(String className) {
    return className.replace('.', '/');
  }

  public void generateAll(ClassWriter cw, String className) {
    if (info.wordBoundary) {
      generateIsWordCharMethod(cw);
      generateIsBoundaryMethod(cw, className);
    }
    if (!info.greedy.equals(CharSet.ANY)) {
      generateRunEndMethod(cw);
    }
    generateSearchMethod(cw, className);
    generateSuffixEndMethod(cw);
    generateResultMethod(cw);

    generateMatchesMethod(cw, className);
    generateMatchMethod(cw, className);
    generateMatchesBoundedMethod(cw, className);
    generateMatchBoundedMethod(cw, className);
    generateFindMethod(cw, className);
    generateFindFromMethod(cw, className);
    generateFindMatchMethod(cw, className);
    generateFindMatchFromMethod(cw, className);
    generateFindBoundsFromMethod(cw, className);
  }

  private static void emitCharAt(MethodVisitor mv) {
    mv.visitMethodInsn(INVOKEINTERFACE, CHAR_SEQUENCE, "charAt", "(I)C", true);
  }

  /** {@code private static boolean isWordChar(char c)}: ASCII {@code [a-zA-Z0-9_]}. */
  private void generateIsWordCharMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "isWordChar", "(C)Z", null, null);
    mv.visitCode();
    Label no = new Label();
    emitCharSetCheck(mv, CharSet.WORD, 0, no);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(no);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static boolean isBoundary(CharSequence s, int p, int lo, int n)}: exactly one of
   * {@code p > lo && isWordChar(s[p-1])} and {@code p < n && isWordChar(s[p])} holds.
   */
  private void generateIsBoundaryMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC, "isBoundary", "(Ljava/lang/CharSequence;III)Z", null, null);
    mv.visitCode();
    int sVar = 0;
    int pVar = 1;
    int loVar = 2;
    int nVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int beforeVar = allocator.allocate();

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, beforeVar);
    Label skipBefore = new Label();
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ILOAD, loVar);
    mv.visitJumpInsn(IF_ICMPLE, skipBefore);
    mv.visitVarInsn(ALOAD, sVar);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    emitCharAt(mv);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "isWordChar", "(C)Z", false);
    mv.visitVarInsn(ISTORE, beforeVar);
    mv.visitLabel(skipBefore);

    // after = p < n && isWordChar(s[p]); return before != after
    Label afterFalse = new Label();
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitJumpInsn(IF_ICMPGE, afterFalse);
    mv.visitVarInsn(ALOAD, sVar);
    mv.visitVarInsn(ILOAD, pVar);
    emitCharAt(mv);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "isWordChar", "(C)Z", false);
    mv.visitVarInsn(ILOAD, beforeVar);
    mv.visitInsn(IXOR);
    mv.visitInsn(IRETURN);
    mv.visitLabel(afterFalse);
    mv.visitVarInsn(ILOAD, beforeVar);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static long search(CharSequence s, int lo, int from, int n, boolean anchored,
   * boolean requireEnd)}: the first match starting at or after {@code from} within {@code [lo, n)},
   * packed as {@code (st << 32) | q}, or {@code -1}. {@code anchored} tries only {@code st ==
   * from}; {@code requireEnd} accepts only a match ending at {@code n}.
   *
   * <p>{@code requireEnd} returns at the first {@code q} where the suffix matches. For a fixed
   * suffix only {@code q = n - |T|} is tried. For the captured run, the first {@code q} with a run
   * of at least {@code k} decides: if that run does not reach {@code n}, a lower {@code q} has a
   * run that stops at the same char or exceeds {@code max}.
   *
   * <p>With END after the captured run, the run cannot contain a line terminator (the detector
   * declines it), so END can only hold where the full run (at most {@code max}) ends.
   */
  private void generateSearchMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "search", SEARCH_DESC, null, null);
    mv.visitCode();
    int sVar = 0;
    int loVar = 1;
    int fromVar = 2;
    int nVar = 3;
    int anchoredVar = 4;
    int requireEndVar = 5;
    LocalVarAllocator allocator = new LocalVarAllocator(6);
    int stVar = allocator.allocate();
    int lastVar = allocator.allocate();
    int aVar = allocator.allocate();
    int rVar = allocator.allocate();
    int qHiVar = allocator.allocate();
    int qLoVar = allocator.allocate();
    int qVar = allocator.allocate();
    int cVar = allocator.allocate();
    int eVar = allocator.allocate();
    boolean captured = info.hasCapturedRun();
    int rcVar = captured ? allocator.allocate() : -1;
    int xVar = captured ? allocator.allocate() : -1;
    int maxOrInf = info.runMax == -1 ? Integer.MAX_VALUE : info.runMax;

    // st = from; last = n - (w + m + minSuffix)
    mv.visitVarInsn(ILOAD, fromVar);
    mv.visitVarInsn(ISTORE, stVar);
    mv.visitVarInsn(ILOAD, nVar);
    pushInt(mv, w + info.greedyMin + minSuffix);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, lastVar);

    if (captured) {
      // X caps the run counter: k suffices to decide a match unless the run must end at END/n.
      if (info.endKind != EndKind.NONE) {
        pushInt(mv, maxOrInf);
        mv.visitVarInsn(ISTORE, xVar);
      } else {
        Label notRequired = new Label();
        Label xDone = new Label();
        mv.visitVarInsn(ILOAD, requireEndVar);
        mv.visitJumpInsn(IFEQ, notRequired);
        pushInt(mv, maxOrInf);
        mv.visitJumpInsn(GOTO, xDone);
        mv.visitLabel(notRequired);
        pushInt(mv, info.runMin);
        mv.visitLabel(xDone);
        mv.visitVarInsn(ISTORE, xVar);
      }
    }

    Label stLoop = new Label();
    Label prefixFail = new Label();
    Label stFail = new Label();
    Label notFound = new Label();

    mv.visitLabel(stLoop);
    mv.visitVarInsn(ILOAD, stVar);
    mv.visitVarInsn(ILOAD, lastVar);
    mv.visitJumpInsn(IF_ICMPGT, notFound);

    // P at st (st <= last keeps every read inside [st, n))
    for (int i = 0; i < w; i++) {
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitVarInsn(ILOAD, stVar);
      pushInt(mv, i);
      mv.visitInsn(IADD);
      emitCharAt(mv);
      mv.visitVarInsn(ISTORE, cVar);
      emitCharSetCheck(mv, info.prefix[i], cVar, prefixFail);
    }

    // a = st + w; R = end of the greedy G run from a
    mv.visitVarInsn(ILOAD, stVar);
    pushInt(mv, w);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, aVar);
    if (info.greedy.equals(CharSet.ANY)) {
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitVarInsn(ISTORE, rVar);
    } else {
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitVarInsn(ILOAD, aVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(INVOKESTATIC, cn(className), "runEnd", RUN_END_DESC, false);
      mv.visitVarInsn(ISTORE, rVar);
    }

    // qHi = min(R, n - minSuffix); qLo = a + m, raised where the suffix must end at or near n
    mv.visitVarInsn(ILOAD, rVar);
    mv.visitVarInsn(ILOAD, nVar);
    pushInt(mv, minSuffix);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
    mv.visitVarInsn(ISTORE, qHiVar);
    mv.visitVarInsn(ILOAD, aVar);
    pushInt(mv, info.greedyMin);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, qLoVar);
    if (!captured) {
      int t = info.fixedSuffix.length;
      if (info.endKind == EndKind.END) {
        emitRaiseQLo(mv, qLoVar, nVar, t + 2);
      }
      if (info.endKind == EndKind.ABSOLUTE) {
        emitRaiseQLo(mv, qLoVar, nVar, t);
      } else {
        Label noRaise = new Label();
        mv.visitVarInsn(ILOAD, requireEndVar);
        mv.visitJumpInsn(IFEQ, noRaise);
        emitRaiseQLo(mv, qLoVar, nVar, t);
        mv.visitLabel(noRaise);
      }
    }

    if (captured) {
      // rc = forward run of C from qHi, capped at X
      Label rcLoop = new Label();
      Label rcDone = new Label();
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, rcVar);
      mv.visitLabel(rcLoop);
      mv.visitVarInsn(ILOAD, rcVar);
      mv.visitVarInsn(ILOAD, xVar);
      mv.visitJumpInsn(IF_ICMPGE, rcDone);
      mv.visitVarInsn(ILOAD, qHiVar);
      mv.visitVarInsn(ILOAD, rcVar);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitJumpInsn(IF_ICMPGE, rcDone);
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitVarInsn(ILOAD, qHiVar);
      mv.visitVarInsn(ILOAD, rcVar);
      mv.visitInsn(IADD);
      emitCharAt(mv);
      mv.visitVarInsn(ISTORE, cVar);
      emitCharSetCheck(mv, info.runCharSet, cVar, rcDone);
      mv.visitIincInsn(rcVar, 1);
      mv.visitJumpInsn(GOTO, rcLoop);
      mv.visitLabel(rcDone);
    }

    // for (q = qHi; q >= qLo; q--)
    Label qLoop = new Label();
    Label qNext = new Label();
    Label found = new Label();
    mv.visitVarInsn(ILOAD, qHiVar);
    mv.visitVarInsn(ISTORE, qVar);
    mv.visitLabel(qLoop);
    mv.visitVarInsn(ILOAD, qVar);
    mv.visitVarInsn(ILOAD, qLoVar);
    mv.visitJumpInsn(IF_ICMPLT, stFail);

    if (captured) {
      // if (q < qHi) rc = C(s[q]) ? min(rc + 1, X) : 0
      Label rcKept = new Label();
      Label notInRun = new Label();
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ILOAD, qHiVar);
      mv.visitJumpInsn(IF_ICMPGE, rcKept);
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitVarInsn(ILOAD, qVar);
      emitCharAt(mv);
      mv.visitVarInsn(ISTORE, cVar);
      emitCharSetCheck(mv, info.runCharSet, cVar, notInRun);
      mv.visitVarInsn(ILOAD, rcVar);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, xVar);
      mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
      mv.visitVarInsn(ISTORE, rcVar);
      mv.visitJumpInsn(GOTO, rcKept);
      mv.visitLabel(notInRun);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, rcVar);
      mv.visitLabel(rcKept);
    }

    if (info.wordBoundary) {
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ILOAD, loVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "isBoundary", "(Ljava/lang/CharSequence;III)Z", false);
      mv.visitJumpInsn(IFEQ, qNext);
    }

    if (captured) {
      // if (rc < k) continue; e = q + rc
      mv.visitVarInsn(ILOAD, rcVar);
      pushInt(mv, info.runMin);
      mv.visitJumpInsn(IF_ICMPLT, qNext);
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ILOAD, rcVar);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ISTORE, eVar);
    } else {
      // T at q (q <= n - |T|); e = q + |T|
      for (int j = 0; j < info.fixedSuffix.length; j++) {
        mv.visitVarInsn(ALOAD, sVar);
        mv.visitVarInsn(ILOAD, qVar);
        pushInt(mv, j);
        mv.visitInsn(IADD);
        emitCharAt(mv);
        mv.visitVarInsn(ISTORE, cVar);
        emitCharSetCheck(mv, info.fixedSuffix[j], cVar, qNext);
      }
      mv.visitVarInsn(ILOAD, qVar);
      pushInt(mv, info.fixedSuffix.length);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ISTORE, eVar);
    }

    // if (requireEnd) return e == n ? pack : -1
    Label notRequireEnd = new Label();
    mv.visitVarInsn(ILOAD, requireEndVar);
    mv.visitJumpInsn(IFEQ, notRequireEnd);
    mv.visitVarInsn(ILOAD, eVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitJumpInsn(IF_ICMPEQ, found);
    mv.visitJumpInsn(GOTO, notFound);
    mv.visitLabel(notRequireEnd);

    if (info.endKind == EndKind.END) {
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitVarInsn(ILOAD, eVar);
      mv.visitVarInsn(ILOAD, nVar);
      invokeEnd(mv);
      mv.visitJumpInsn(IFEQ, qNext);
    } else if (info.endKind == EndKind.ABSOLUTE) {
      mv.visitVarInsn(ILOAD, eVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitJumpInsn(IF_ICMPNE, qNext);
    }
    mv.visitJumpInsn(GOTO, found);

    mv.visitLabel(qNext);
    mv.visitIincInsn(qVar, -1);
    mv.visitJumpInsn(GOTO, qLoop);

    // Every q failed: skip to the first start whose G run would begin after R.
    mv.visitLabel(stFail);
    mv.visitVarInsn(ILOAD, anchoredVar);
    mv.visitJumpInsn(IFNE, notFound);
    mv.visitVarInsn(ILOAD, rVar);
    pushInt(mv, w - 1);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, stVar);
    mv.visitJumpInsn(GOTO, stLoop);

    mv.visitLabel(prefixFail);
    mv.visitVarInsn(ILOAD, anchoredVar);
    mv.visitJumpInsn(IFNE, notFound);
    mv.visitIincInsn(stVar, 1);
    mv.visitJumpInsn(GOTO, stLoop);

    // return ((long) st << 32) | q
    mv.visitLabel(found);
    mv.visitVarInsn(ILOAD, stVar);
    mv.visitInsn(I2L);
    pushInt(mv, 32);
    mv.visitInsn(LSHL);
    mv.visitVarInsn(ILOAD, qVar);
    mv.visitInsn(I2L);
    mv.visitInsn(LOR);
    mv.visitInsn(LRETURN);

    mv.visitLabel(notFound);
    mv.visitLdcInsn(-1L);
    mv.visitInsn(LRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static int runEnd(CharSequence s, int a, int n)}: the end of the G run from
   * {@code a}, i.e. the first line terminator in {@code [a, n)}, or {@code n}. Kept out of {@code
   * search} so that {@code search} stays small enough for C2 to inline it into its callers.
   *
   * <p>Checks chars one by one, up to {@link #LOOP_SCAN} of them for a String. The rest of a String
   * run is scanned with {@code String.indexOf}, once per terminator, each call bounded by the
   * position found so far.
   */
  private void generateRunEndMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "runEnd", RUN_END_DESC, null, null);
    mv.visitCode();
    int sVar = 0;
    int aVar = 1;
    int nVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int limVar = allocator.allocate();
    int rVar = allocator.allocate();
    int cVar = allocator.allocate();
    int iVar = allocator.allocate();
    Label rLoop = new Label();
    Label rDone = new Label();
    Label loopLimitSet = new Label();
    Label tailScan = new Label();

    // lim = s instanceof String ? a + min(n - a, LOOP_SCAN) : n
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitVarInsn(ISTORE, limVar);
    mv.visitVarInsn(ALOAD, sVar);
    mv.visitTypeInsn(INSTANCEOF, "java/lang/String");
    mv.visitJumpInsn(IFEQ, loopLimitSet);
    mv.visitVarInsn(ILOAD, aVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitVarInsn(ILOAD, aVar);
    mv.visitInsn(ISUB);
    pushInt(mv, LOOP_SCAN);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, limVar);
    mv.visitLabel(loopLimitSet);

    // for (R = a; R < lim; R++) if (!G(s[R])) return R
    mv.visitVarInsn(ILOAD, aVar);
    mv.visitVarInsn(ISTORE, rVar);
    mv.visitLabel(rLoop);
    mv.visitVarInsn(ILOAD, rVar);
    mv.visitVarInsn(ILOAD, limVar);
    mv.visitJumpInsn(IF_ICMPGE, tailScan);
    mv.visitVarInsn(ALOAD, sVar);
    mv.visitVarInsn(ILOAD, rVar);
    emitCharAt(mv);
    mv.visitVarInsn(ISTORE, cVar);
    emitCharSetCheck(mv, info.greedy, cVar, rDone);
    mv.visitIincInsn(rVar, 1);
    mv.visitJumpInsn(GOTO, rLoop);

    // R == lim; lim < n only for a String: n = first terminator in [lim, n), if any
    mv.visitLabel(tailScan);
    mv.visitVarInsn(ILOAD, rVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitJumpInsn(IF_ICMPGE, rDone);
    for (char t : LINE_TERMINATORS) {
      Label notFound = new Label();
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitTypeInsn(CHECKCAST, "java/lang/String");
      pushInt(mv, t);
      mv.visitVarInsn(ILOAD, limVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(III)I", false);
      mv.visitInsn(DUP);
      mv.visitVarInsn(ISTORE, iVar);
      mv.visitJumpInsn(IFLT, notFound);
      mv.visitVarInsn(ILOAD, iVar);
      mv.visitVarInsn(ISTORE, nVar);
      mv.visitLabel(notFound);
    }
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitInsn(IRETURN);

    mv.visitLabel(rDone);
    mv.visitVarInsn(ILOAD, rVar);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Emits {@code qLo = max(qLo, n - d)}. */
  private static void emitRaiseQLo(MethodVisitor mv, int qLoVar, int nVar, int d) {
    mv.visitVarInsn(ILOAD, qLoVar);
    mv.visitVarInsn(ILOAD, nVar);
    pushInt(mv, d);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
    mv.visitVarInsn(ISTORE, qLoVar);
  }

  /**
   * {@code private static int suffixEnd(CharSequence s, int q, int n)}: the end of the suffix that
   * matched at {@code q}. Fixed: {@code q + |T|}. Captured: {@code q} plus the run of C from {@code
   * q}, capped at {@code max}.
   */
  private void generateSuffixEndMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "suffixEnd", SUFFIX_END_DESC, null, null);
    mv.visitCode();
    int sVar = 0;
    int qVar = 1;
    int nVar = 2;
    if (!info.hasCapturedRun()) {
      mv.visitVarInsn(ILOAD, qVar);
      pushInt(mv, info.fixedSuffix.length);
      mv.visitInsn(IADD);
      mv.visitInsn(IRETURN);
    } else {
      LocalVarAllocator allocator = new LocalVarAllocator(3);
      int eVar = allocator.allocate();
      int limitVar = allocator.allocate();
      int cVar = allocator.allocate();
      // limit = max == -1 ? n : (int) min((long) q + max, n)
      if (info.runMax == -1) {
        mv.visitVarInsn(ILOAD, nVar);
      } else {
        mv.visitVarInsn(ILOAD, qVar);
        mv.visitInsn(I2L);
        mv.visitLdcInsn((long) info.runMax);
        mv.visitInsn(LADD);
        mv.visitVarInsn(ILOAD, nVar);
        mv.visitInsn(I2L);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(JJ)J", false);
        mv.visitInsn(L2I);
      }
      mv.visitVarInsn(ISTORE, limitVar);
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ISTORE, eVar);
      Label loop = new Label();
      Label done = new Label();
      mv.visitLabel(loop);
      mv.visitVarInsn(ILOAD, eVar);
      mv.visitVarInsn(ILOAD, limitVar);
      mv.visitJumpInsn(IF_ICMPGE, done);
      mv.visitVarInsn(ALOAD, sVar);
      mv.visitVarInsn(ILOAD, eVar);
      emitCharAt(mv);
      mv.visitVarInsn(ISTORE, cVar);
      emitCharSetCheck(mv, info.runCharSet, cVar, done);
      mv.visitIincInsn(eVar, 1);
      mv.visitJumpInsn(GOTO, loop);
      mv.visitLabel(done);
      mv.visitVarInsn(ILOAD, eVar);
      mv.visitInsn(IRETURN);
    }
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static MatchResult result(String input, int st, int q, int e)}: group 0 is
   * {@code [st, e)}, group 1 is {@code [st + w, q)}, and the captured run is {@code [q, e)}.
   */
  private void generateResultMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "result", RESULT_DESC, null, null);
    mv.visitCode();
    int inputVar = 0;
    int stVar = 1;
    int qVar = 2;
    int eVar = 3;
    int groupCount = info.groupCount();

    mv.visitTypeInsn(NEW, MATCH_RESULT_IMPL);
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);

    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    emitArrayStore(mv, 0, stVar, 0);
    emitArrayStore(mv, 1, stVar, w);
    if (groupCount == 2) {
      emitArrayStore(mv, 2, qVar, 0);
    }

    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    emitArrayStore(mv, 0, eVar, 0);
    emitArrayStore(mv, 1, qVar, 0);
    if (groupCount == 2) {
      emitArrayStore(mv, 2, eVar, 0);
    }

    pushInt(mv, groupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL, MATCH_RESULT_IMPL, "<init>", "(Ljava/lang/String;[I[II)V", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** With an int[] on the stack, emits {@code array[index] = var + delta}, keeping the array. */
  private static void emitArrayStore(MethodVisitor mv, int index, int var, int delta) {
    mv.visitInsn(DUP);
    pushInt(mv, index);
    mv.visitVarInsn(ILOAD, var);
    if (delta != 0) {
      pushInt(mv, delta);
      mv.visitInsn(IADD);
    }
    mv.visitInsn(IASTORE);
  }

  /** Emits {@code res = search(s, lo, from, n, anchored, requireEnd)} with ints taken from vars. */
  private static void emitSearch(
      MethodVisitor mv,
      String className,
      int sVar,
      int loVar,
      int fromVar,
      int nVar,
      boolean anchored,
      int resVar) {
    mv.visitVarInsn(ALOAD, sVar);
    if (loVar < 0) {
      mv.visitInsn(ICONST_0);
    } else {
      mv.visitVarInsn(ILOAD, loVar);
    }
    if (fromVar < 0) {
      mv.visitInsn(ICONST_0);
    } else {
      mv.visitVarInsn(ILOAD, fromVar);
    }
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitInsn(anchored ? ICONST_1 : ICONST_0);
    mv.visitInsn(anchored ? ICONST_1 : ICONST_0);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "search", SEARCH_DESC, false);
    mv.visitVarInsn(LSTORE, resVar);
  }

  /** Emits {@code if (res < 0) goto failLabel}. */
  private static void emitIfNotFound(MethodVisitor mv, int resVar, Label failLabel) {
    mv.visitVarInsn(LLOAD, resVar);
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);
    mv.visitJumpInsn(IFLT, failLabel);
  }

  /** Emits {@code if (input == null) return <zero of the return type>}. */
  private static void emitNullGuard(MethodVisitor mv, int inputVar, int returnOpcode) {
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    if (returnOpcode == ARETURN) {
      mv.visitInsn(ACONST_NULL);
    } else {
      mv.visitInsn(returnOpcode == IRETURN ? ICONST_0 : ICONST_M1);
    }
    mv.visitInsn(returnOpcode == ARETURN ? ARETURN : IRETURN);
    mv.visitLabel(notNull);
  }

  private static void emitLength(MethodVisitor mv, int inputVar, int nVar) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEINTERFACE, CHAR_SEQUENCE, "length", "()I", true);
    mv.visitVarInsn(ISTORE, nVar);
  }

  /** {@code public boolean matches(String input)}. */
  private void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    emitNullGuard(mv, inputVar, IRETURN);
    emitLength(mv, inputVar, nVar);
    emitSearch(mv, className, inputVar, -1, -1, nVar, true, resVar);
    Label fail = new Label();
    emitIfNotFound(mv, resVar, fail);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public MatchResult match(String input)}. */
  private void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC, "match", "(Ljava/lang/String;)L" + MATCH_RESULT + ";", null, null);
    mv.visitCode();
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    emitNullGuard(mv, inputVar, ARETURN);
    emitLength(mv, inputVar, nVar);
    emitSearch(mv, className, inputVar, -1, -1, nVar, true, resVar);
    Label fail = new Label();
    emitIfNotFound(mv, resVar, fail);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(LLOAD, resVar);
    mv.visitInsn(L2I);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "result", RESULT_DESC, false);
    mv.visitInsn(ARETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public boolean matchesBounded(CharSequence input, int start, int end)}. */
  private void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int resVar = allocator.allocateWide();
    emitSearch(mv, className, inputVar, startVar, startVar, endVar, true, resVar);
    Label fail = new Label();
    emitIfNotFound(mv, resVar, fail);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code public MatchResult matchBounded(CharSequence input, int start, int end)}: spans are
   * relative to the original input.
   */
  private void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)L" + MATCH_RESULT + ";",
            null,
            null);
    mv.visitCode();
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int resVar = allocator.allocateWide();
    emitSearch(mv, className, inputVar, startVar, startVar, endVar, true, resVar);
    Label fail = new Label();
    emitIfNotFound(mv, resVar, fail);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEINTERFACE, CHAR_SEQUENCE, "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(LLOAD, resVar);
    mv.visitInsn(L2I);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "result", RESULT_DESC, false);
    mv.visitInsn(ARETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public boolean find(String input)}: {@code findFrom(input, 0) >= 0}. */
  private void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(INVOKEVIRTUAL, cn(className), "findFrom", "(Ljava/lang/String;I)I", false);
    Label found = new Label();
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits the find prologue shared by findFrom/findMatchFrom/findBoundsFrom: a null input or a
   * start past the end jumps to {@code failLabel}, a negative start is clamped to 0, then {@code
   * res = search(input, 0, start, n, false, false)} and a miss jumps to {@code failLabel}.
   */
  private static void emitFindPrologue(
      MethodVisitor mv,
      String className,
      int inputVar,
      int startVar,
      int nVar,
      int resVar,
      Label failLabel) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNULL, failLabel);
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, startVar);
    mv.visitLabel(startNotNeg);
    emitLength(mv, inputVar, nVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitJumpInsn(IF_ICMPGT, failLabel);
    emitSearch(mv, className, inputVar, -1, startVar, nVar, false, resVar);
    emitIfNotFound(mv, resVar, failLabel);
  }

  /** Emits {@code stVar = (int) (res >>> 32); qVar = (int) res}. */
  private static void emitUnpack(MethodVisitor mv, int resVar, int stVar, int qVar) {
    mv.visitVarInsn(LLOAD, resVar);
    pushInt(mv, 32);
    mv.visitInsn(LUSHR);
    mv.visitInsn(L2I);
    mv.visitVarInsn(ISTORE, stVar);
    mv.visitVarInsn(LLOAD, resVar);
    mv.visitInsn(L2I);
    mv.visitVarInsn(ISTORE, qVar);
  }

  /** {@code public int findFrom(String input, int start)}. */
  private void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();
    int inputVar = 1;
    int startVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    Label fail = new Label();
    emitFindPrologue(mv, className, inputVar, startVar, nVar, resVar, fail);
    mv.visitVarInsn(LLOAD, resVar);
    pushInt(mv, 32);
    mv.visitInsn(LUSHR);
    mv.visitInsn(L2I);
    mv.visitInsn(IRETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public MatchResult findMatch(String input)}: {@code findMatchFrom(input, 0)}. */
  private void generateFindMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC, "findMatch", "(Ljava/lang/String;)L" + MATCH_RESULT + ";", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        cn(className),
        "findMatchFrom",
        "(Ljava/lang/String;I)L" + MATCH_RESULT + ";",
        false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public MatchResult findMatchFrom(String input, int start)}. */
  private void generateFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC, "findMatchFrom", "(Ljava/lang/String;I)L" + MATCH_RESULT + ";", null, null);
    mv.visitCode();
    int inputVar = 1;
    int startVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    int stVar = allocator.allocate();
    int qVar = allocator.allocate();
    Label fail = new Label();
    emitFindPrologue(mv, className, inputVar, startVar, nVar, resVar, fail);
    emitUnpack(mv, resVar, stVar, qVar);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, stVar);
    mv.visitVarInsn(ILOAD, qVar);
    emitSuffixEnd(mv, className, inputVar, qVar, nVar);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "result", RESULT_DESC, false);
    mv.visitInsn(ARETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public boolean findBoundsFrom(String input, int start, int[] bounds)}. */
  private void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();
    int inputVar = 1;
    int startVar = 2;
    int boundsVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    int stVar = allocator.allocate();
    int qVar = allocator.allocate();
    Label fail = new Label();
    emitFindPrologue(mv, className, inputVar, startVar, nVar, resVar, fail);
    emitUnpack(mv, resVar, stVar, qVar);
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, stVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_1);
    emitSuffixEnd(mv, className, inputVar, qVar, nVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Pushes {@code suffixEnd(input, q, n)}. */
  private static void emitSuffixEnd(
      MethodVisitor mv, String className, int inputVar, int qVar, int nVar) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, qVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "suffixEnd", SUFFIX_END_DESC, false);
  }
}
