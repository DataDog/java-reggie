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

import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/** Emits static NFA data arrays, {@code nfaStep}, and a lazy-DFA {@code matches} method. */
public class LazyDFABytecodeGenerator {

  private static final String SPARSE_SET = "com/datadoghq/reggie/runtime/SparseSet";
  private static final String LAZY_CACHE = "com/datadoghq/reggie/runtime/LazyDFACache";
  private static final String NFA_STEP = "com/datadoghq/reggie/runtime/NfaStep";
  private static final String ARRAYS = "java/util/Arrays";

  private final NFA nfa;
  private final int stateCount;
  private final int[][] transitions; // transitions[id] = flat [min, max, target, ...]
  private final int[][] epsClosure; // epsClosure[id] = sorted int[] of reachable IDs
  private final int[] startSet; // ε-closure of start state
  private final int[] acceptIds; // sorted accept state IDs

  public LazyDFABytecodeGenerator(NFA nfa) {
    this.nfa = nfa;
    this.stateCount = nfa.getStates().size();
    this.transitions = buildTransitions(nfa);
    this.epsClosure = buildEpsClosure(nfa);
    this.startSet = epsClosure[nfa.getStartState().id];
    this.acceptIds = nfa.getAcceptStates().stream().mapToInt(s -> s.id).sorted().toArray();
  }

  /** Declare + initialize all static fields and emit {@code <clinit>}. */
  public void generateStaticFields(ClassWriter cw, String className) {
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "NFA_STATE_COUNT", "I", null, stateCount)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "NFA_TRANSITIONS", "[[I", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "NFA_EPS_CLOSURES", "[[I", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "NFA_START_SET", "[I", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "NFA_ACCEPT_IDS", "[I", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "CACHE", "L" + LAZY_CACHE + ";", null, null)
        .visitEnd();

    MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
    clinit.visitCode();

    // For large NFAs, split array init into helper methods to avoid 64 KB method limit.
    emitSplitInt2DArrayInit(cw, clinit, className, "NFA_TRANSITIONS", transitions, "[[I");
    emitSplitInt2DArrayInit(cw, clinit, className, "NFA_EPS_CLOSURES", epsClosure, "[[I");
    emitInt1DArrayInit(clinit, className, "NFA_START_SET", startSet, "[I");
    emitInt1DArrayInit(clinit, className, "NFA_ACCEPT_IDS", acceptIds, "[I");

    // CACHE = new LazyDFACache(NFA_START_SET, NFA_ACCEPT_IDS)
    clinit.visitTypeInsn(NEW, LAZY_CACHE);
    clinit.visitInsn(DUP);
    clinit.visitFieldInsn(GETSTATIC, className, "NFA_START_SET", "[I");
    clinit.visitFieldInsn(GETSTATIC, className, "NFA_ACCEPT_IDS", "[I");
    clinit.visitMethodInsn(INVOKESPECIAL, LAZY_CACHE, "<init>", "([I[I)V", false);
    clinit.visitFieldInsn(PUTSTATIC, className, "CACHE", "L" + LAZY_CACHE + ";");

    clinit.visitInsn(RETURN);
    clinit.visitMaxs(0, 0);
    clinit.visitEnd();
  }

  /** Emits the {@code nfaStep(int[], int): int[]} instance method. */
  public void generateNfaStepMethod(ClassWriter cw, String className) {
    // slot 0=this, 1=states[], 2=c, 3=current, 4=next, 5=outer-i/si/sz/copy-i,
    // 6=stateId/result, 7=trans/copy-loop-i, 8=j, 9=eps, 10=eps-index
    MethodVisitor mv = cw.visitMethod(0, "nfaStep", "([II)[I", null, null);
    mv.visitCode();

    // SparseSet current = new SparseSet(NFA_STATE_COUNT)
    mv.visitTypeInsn(NEW, SPARSE_SET);
    mv.visitInsn(DUP);
    mv.visitFieldInsn(GETSTATIC, className, "NFA_STATE_COUNT", "I");
    mv.visitMethodInsn(INVOKESPECIAL, SPARSE_SET, "<init>", "(I)V", false);
    mv.visitVarInsn(ASTORE, 3);

    // SparseSet next = new SparseSet(NFA_STATE_COUNT)
    mv.visitTypeInsn(NEW, SPARSE_SET);
    mv.visitInsn(DUP);
    mv.visitFieldInsn(GETSTATIC, className, "NFA_STATE_COUNT", "I");
    mv.visitMethodInsn(INVOKESPECIAL, SPARSE_SET, "<init>", "(I)V", false);
    mv.visitVarInsn(ASTORE, 4);

    // for (int i = 0; i < states.length; i++) current.add(states[i])
    pushInt(mv, 0);
    mv.visitVarInsn(ISTORE, 5);
    Label loopPopStart = new Label();
    Label loopPopEnd = new Label();
    mv.visitLabel(loopPopStart);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, loopPopEnd);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitInsn(IALOAD);
    mv.visitMethodInsn(INVOKEVIRTUAL, SPARSE_SET, "add", "(I)V", false);
    mv.visitIincInsn(5, 1);
    mv.visitJumpInsn(GOTO, loopPopStart);
    mv.visitLabel(loopPopEnd);

    // for (int si = 0; si < current.size(); si++)
    pushInt(mv, 0);
    mv.visitVarInsn(ISTORE, 5);
    Label loopSiStart = new Label();
    Label loopSiEnd = new Label();
    mv.visitLabel(loopSiStart);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitMethodInsn(INVOKEVIRTUAL, SPARSE_SET, "size", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, loopSiEnd);

    // int stateId = current.get(si)
    mv.visitVarInsn(ALOAD, 3);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitMethodInsn(INVOKEVIRTUAL, SPARSE_SET, "get", "(I)I", false);
    mv.visitVarInsn(ISTORE, 6);

    // int[] trans = NFA_TRANSITIONS[stateId]
    mv.visitFieldInsn(GETSTATIC, className, "NFA_TRANSITIONS", "[[I");
    mv.visitVarInsn(ILOAD, 6);
    mv.visitInsn(AALOAD);
    mv.visitVarInsn(ASTORE, 7);

    // for (int j = 0; j < trans.length; j += 3)
    pushInt(mv, 0);
    mv.visitVarInsn(ISTORE, 8);
    Label loopJStart = new Label();
    Label loopJEnd = new Label();
    mv.visitLabel(loopJStart);
    mv.visitVarInsn(ILOAD, 8);
    mv.visitVarInsn(ALOAD, 7);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, loopJEnd);

    // if (c >= trans[j] && c <= trans[j+1])
    Label skipTransition = new Label();
    mv.visitVarInsn(ILOAD, 2); // c
    mv.visitVarInsn(ALOAD, 7); // trans
    mv.visitVarInsn(ILOAD, 8); // j
    mv.visitInsn(IALOAD); // trans[j]
    mv.visitJumpInsn(IF_ICMPLT, skipTransition);
    mv.visitVarInsn(ILOAD, 2); // c
    mv.visitVarInsn(ALOAD, 7); // trans
    mv.visitVarInsn(ILOAD, 8); // j
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitInsn(IALOAD); // trans[j+1]
    mv.visitJumpInsn(IF_ICMPGT, skipTransition);

    // int[] eps = NFA_EPS_CLOSURES[trans[j+2]]
    mv.visitFieldInsn(GETSTATIC, className, "NFA_EPS_CLOSURES", "[[I");
    mv.visitVarInsn(ALOAD, 7); // trans
    mv.visitVarInsn(ILOAD, 8); // j
    mv.visitInsn(ICONST_2);
    mv.visitInsn(IADD);
    mv.visitInsn(IALOAD); // trans[j+2]
    mv.visitInsn(AALOAD);
    mv.visitVarInsn(ASTORE, 9);

    // for (int ei = 0; ei < eps.length; ei++) next.add(eps[ei])
    pushInt(mv, 0);
    mv.visitVarInsn(ISTORE, 10);
    Label loopEpsStart = new Label();
    Label loopEpsEnd = new Label();
    mv.visitLabel(loopEpsStart);
    mv.visitVarInsn(ILOAD, 10);
    mv.visitVarInsn(ALOAD, 9);
    mv.visitInsn(ARRAYLENGTH);
    mv.visitJumpInsn(IF_ICMPGE, loopEpsEnd);
    mv.visitVarInsn(ALOAD, 4); // next
    mv.visitVarInsn(ALOAD, 9); // eps
    mv.visitVarInsn(ILOAD, 10);
    mv.visitInsn(IALOAD);
    mv.visitMethodInsn(INVOKEVIRTUAL, SPARSE_SET, "add", "(I)V", false);
    mv.visitIincInsn(10, 1);
    mv.visitJumpInsn(GOTO, loopEpsStart);
    mv.visitLabel(loopEpsEnd);

    mv.visitLabel(skipTransition);
    mv.visitIincInsn(8, 3); // j += 3
    mv.visitJumpInsn(GOTO, loopJStart);
    mv.visitLabel(loopJEnd);

    mv.visitIincInsn(5, 1); // si++
    mv.visitJumpInsn(GOTO, loopSiStart);
    mv.visitLabel(loopSiEnd);

    // int sz = next.size()
    mv.visitVarInsn(ALOAD, 4);
    mv.visitMethodInsn(INVOKEVIRTUAL, SPARSE_SET, "size", "()I", false);
    mv.visitVarInsn(ISTORE, 5);

    // int[] result = new int[sz]
    mv.visitVarInsn(ILOAD, 5);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, 6);

    // for (int i = 0; i < sz; i++) result[i] = next.get(i)
    pushInt(mv, 0);
    mv.visitVarInsn(ISTORE, 7);
    Label loopCopyStart = new Label();
    Label loopCopyEnd = new Label();
    mv.visitLabel(loopCopyStart);
    mv.visitVarInsn(ILOAD, 7);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitJumpInsn(IF_ICMPGE, loopCopyEnd);
    mv.visitVarInsn(ALOAD, 6);
    mv.visitVarInsn(ILOAD, 7);
    mv.visitVarInsn(ALOAD, 4);
    mv.visitVarInsn(ILOAD, 7);
    mv.visitMethodInsn(INVOKEVIRTUAL, SPARSE_SET, "get", "(I)I", false);
    mv.visitInsn(IASTORE);
    mv.visitIincInsn(7, 1);
    mv.visitJumpInsn(GOTO, loopCopyStart);
    mv.visitLabel(loopCopyEnd);

    // Arrays.sort(result)
    mv.visitVarInsn(ALOAD, 6);
    mv.visitMethodInsn(INVOKESTATIC, ARRAYS, "sort", "([I)V", false);

    mv.visitVarInsn(ALOAD, 6);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits {@code public int[] apply(int[], int)} — the {@link NfaStep} interface method. Delegates
   * to {@code nfaStep} so the generated class satisfies the {@code NfaStep} contract without
   * requiring INVOKEDYNAMIC/LambdaMetafactory (which is problematic in hidden classes).
   */
  public void generateApplyMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "apply", "([II)[I", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "nfaStep", "([II)[I", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits {@code public boolean matches(String input)} delegating to CACHE. Passes {@code this}
   * directly (the generated class implements {@link NfaStep}).
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    mv.visitFieldInsn(GETSTATIC, className, "CACHE", "L" + LAZY_CACHE + ";");
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ALOAD, 0); // this (implements NfaStep)
    mv.visitMethodInsn(
        INVOKEVIRTUAL, LAZY_CACHE, "matches", "(Ljava/lang/String;L" + NFA_STEP + ";)Z", false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits {@code public MatchResult match(String input)}: returns a full-input MatchResultImpl if
   * {@code matches(input)} is true, otherwise null. No capturing groups (group-free pattern).
   */
  public void generateMatchMethod(ClassWriter cw, String className) {
    // Descriptor: (Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;
    String matchResultImpl = "com/datadoghq/reggie/runtime/MatchResultImpl";
    String matchResult = "com/datadoghq/reggie/runtime/MatchResult";
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC, "match", "(Ljava/lang/String;)L" + matchResult + ";", null, null);
    mv.visitCode();

    // if (!matches(input)) return null;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    Label matched = new Label();
    mv.visitJumpInsn(IFNE, matched);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(matched);

    // int len = input == null ? 0 : input.length();
    // new MatchResultImpl(input, new int[]{0, len}, new int[]{0, len}, 0)
    mv.visitTypeInsn(NEW, matchResultImpl);
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
    // starts = new int[]{0, input.length()}
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);
    // ends = new int[]{0, input.length()}
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);
    // groupCount = 0
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKESPECIAL, matchResultImpl, "<init>", "(Ljava/lang/String;[I[II)V", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits {@code public boolean matchesBounded(CharSequence input, int start, int end)}: extracts
   * the subsequence as a String and delegates to {@code matches()}.
   */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();
    // this.matches(input.subSequence(start, end).toString())
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
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Emits {@code public MatchResult matchBounded(CharSequence input, int start, int end)}: returns
   * a MatchResultImpl for the bounded region if it matches, otherwise null.
   */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    String matchResultImpl = "com/datadoghq/reggie/runtime/MatchResultImpl";
    String matchResult = "com/datadoghq/reggie/runtime/MatchResult";
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)L" + matchResult + ";",
            null,
            null);
    mv.visitCode();

    // String sub = input.subSequence(start, end).toString()
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
    mv.visitVarInsn(ASTORE, 4); // sub

    // if (!matches(sub)) return null;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 4);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    Label matchedBounded = new Label();
    mv.visitJumpInsn(IFNE, matchedBounded);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(matchedBounded);

    // new MatchResultImpl(sub, new int[]{0, end-start}, new int[]{0, end-start}, 0)
    mv.visitTypeInsn(NEW, matchResultImpl);
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 4); // sub
    // starts = new int[]{0, end - start}
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitInsn(ISUB);
    mv.visitInsn(IASTORE);
    // ends = new int[]{0, end - start}
    mv.visitInsn(ICONST_2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitInsn(ISUB);
    mv.visitInsn(IASTORE);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKESPECIAL, matchResultImpl, "<init>", "(Ljava/lang/String;[I[II)V", false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // ── private helpers ──────────────────────────────────────────────────────

  // Maximum bytecode bytes per helper method (conservative, well under 64 KB limit).
  private static final int MAX_HELPER_BYTES = 40_000;

  /**
   * Emits a 2-D int array field initializer. For large arrays the bytecode is split across multiple
   * private static helper methods (chunks of states) to stay within the 64 KB JVM method limit.
   */
  private static void emitSplitInt2DArrayInit(
      ClassWriter cw,
      MethodVisitor clinit,
      String className,
      String field,
      int[][] data,
      String desc) {
    // Estimate total bytecode. Each row of length L costs roughly (10 + 8*L) bytes.
    long totalEstimate = 0;
    for (int[] row : data) {
      totalEstimate += 10L + 8L * row.length;
    }

    if (totalEstimate <= MAX_HELPER_BYTES) {
      // Small enough to inline directly into <clinit>
      emitInt2DArrayInit(clinit, className, field, data, desc);
      return;
    }

    // Allocate the outer array and store it in the field first.
    pushInt(clinit, data.length);
    clinit.visitTypeInsn(ANEWARRAY, "[I");
    clinit.visitFieldInsn(PUTSTATIC, className, field, desc);

    // Split into variable-size chunks, each within MAX_HELPER_BYTES.
    int chunkStart = 0;
    int chunkIndex = 0;
    while (chunkStart < data.length) {
      // Find end of this chunk.
      long chunkBytes = 0;
      int chunkEnd = chunkStart;
      while (chunkEnd < data.length) {
        long rowBytes = 10L + 8L * data[chunkEnd].length;
        if (chunkBytes + rowBytes > MAX_HELPER_BYTES && chunkEnd > chunkStart) break;
        chunkBytes += rowBytes;
        chunkEnd++;
      }

      String helperName = "init" + field + "_" + chunkIndex;
      emitPartialArrayHelper(cw, className, helperName, field, data, chunkStart, chunkEnd);
      clinit.visitMethodInsn(INVOKESTATIC, className, helperName, "()V", false);

      chunkStart = chunkEnd;
      chunkIndex++;
    }
  }

  /** Emits a private static helper that fills a range of an already-stored 2-D array field. */
  private static void emitPartialArrayHelper(
      ClassWriter cw,
      String className,
      String methodName,
      String field,
      int[][] data,
      int from,
      int to) {
    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, methodName, "()V", null, null);
    mv.visitCode();
    for (int i = from; i < to; i++) {
      mv.visitFieldInsn(GETSTATIC, className, field, "[[I");
      pushInt(mv, i);
      int[] row = data[i];
      pushInt(mv, row.length);
      mv.visitIntInsn(NEWARRAY, T_INT);
      for (int j = 0; j < row.length; j++) {
        mv.visitInsn(DUP);
        pushInt(mv, j);
        pushInt(mv, row[j]);
        mv.visitInsn(IASTORE);
      }
      mv.visitInsn(AASTORE);
    }
    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  private static void emitInt1DArrayInit(
      MethodVisitor mv, String className, String field, int[] data, String desc) {
    pushInt(mv, data.length);
    mv.visitIntInsn(NEWARRAY, T_INT);
    for (int i = 0; i < data.length; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      pushInt(mv, data[i]);
      mv.visitInsn(IASTORE);
    }
    mv.visitFieldInsn(PUTSTATIC, className, field, desc);
  }

  private static void emitInt2DArrayInit(
      MethodVisitor mv, String className, String field, int[][] data, String desc) {
    pushInt(mv, data.length);
    mv.visitTypeInsn(ANEWARRAY, "[I");
    for (int i = 0; i < data.length; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      int[] row = data[i];
      pushInt(mv, row.length);
      mv.visitIntInsn(NEWARRAY, T_INT);
      for (int j = 0; j < row.length; j++) {
        mv.visitInsn(DUP);
        pushInt(mv, j);
        pushInt(mv, row[j]);
        mv.visitInsn(IASTORE);
      }
      mv.visitInsn(AASTORE);
    }
    mv.visitFieldInsn(PUTSTATIC, className, field, desc);
  }

  private static int[][] buildTransitions(NFA nfa) {
    int n = nfa.getStates().size();
    int[][] result = new int[n][];
    for (NFA.NFAState state : nfa.getStates()) {
      List<Integer> triples = new ArrayList<>();
      for (NFA.Transition t : state.getTransitions()) {
        for (CharSet.Range r : t.chars.getRanges()) {
          triples.add((int) r.start);
          triples.add((int) r.end);
          triples.add(t.target.id);
        }
      }
      result[state.id] = triples.stream().mapToInt(Integer::intValue).toArray();
    }
    return result;
  }

  private static int[][] buildEpsClosure(NFA nfa) {
    int n = nfa.getStates().size();
    int[][] result = new int[n][];
    for (NFA.NFAState state : nfa.getStates()) {
      Set<Integer> closure = new HashSet<>();
      Deque<NFA.NFAState> worklist = new ArrayDeque<>();
      worklist.add(state);
      while (!worklist.isEmpty()) {
        NFA.NFAState s = worklist.poll();
        if (closure.add(s.id)) {
          for (NFA.NFAState eps : s.getEpsilonTransitions()) {
            if (eps.anchor == null) worklist.add(eps);
          }
        }
      }
      result[state.id] = closure.stream().mapToInt(Integer::intValue).sorted().toArray();
    }
    return result;
  }

  static void pushInt(MethodVisitor mv, int v) {
    if (v >= -1 && v <= 5) mv.visitInsn(ICONST_0 + v);
    else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, v);
    else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) mv.visitIntInsn(SIPUSH, v);
    else mv.visitLdcInsn(v);
  }
}
