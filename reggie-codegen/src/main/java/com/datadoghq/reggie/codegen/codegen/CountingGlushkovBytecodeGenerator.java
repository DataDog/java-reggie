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

import com.datadoghq.reggie.codegen.automaton.GlushkovAutomaton;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates counting Glushkov matcher bytecode that delegates to {@code CountingGlushkovRuntime}.
 * The generated class mirrors the structure of classes produced by {@link
 * BitParallelGlushkovBytecodeGenerator}: static array fields decoded once in {@code <clinit>}, and
 * public methods that push those fields plus the scalar automaton constants inline before each
 * runtime call.
 *
 * <p>Unlike {@link BitParallelGlushkovBytecodeGenerator}, this generator embeds loop-back edges
 * directly into the FOLLOW array (each accepting position's follow set is augmented with the
 * initial set) to support repeated-match counting without a separate reverse scan.
 */
public final class CountingGlushkovBytecodeGenerator {
  private static final String RUNTIME = "com/datadoghq/reggie/runtime/CountingGlushkovRuntime";
  private static final int STRING_CHUNK_CHARS = 10_000;

  private final GlushkovAutomaton g;
  private final int counterMin;
  private final int counterMax;

  public CountingGlushkovBytecodeGenerator(GlushkovAutomaton g, int counterMin, int counterMax) {
    this.g = g;
    this.counterMin = counterMin;
    this.counterMax = counterMax;
  }

  // -------------------------------------------------------------------------
  // Static field declarations + <clinit>
  // -------------------------------------------------------------------------

  public void generateStaticData(ClassWriter cw, String className) {
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "FOLLOW", "[J", null, null).visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "ENTRY", "[J", null, null).visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "ASCII_CLASSES", "[I", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "RANGE_STARTS", "[C", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "RANGE_ENDS", "[C", null, null).visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "RANGE_CLASSES", "[I", null, null)
        .visitEnd();

    // Build follow-with-loopbacks: for each accepting position p, augment its
    // follow set with the initial set so repeated matches can restart in-place.
    long[] followWithLoopbacks = new long[g.follow.length];
    for (int p = 0; p < g.follow.length; p++) {
      followWithLoopbacks[p] = g.follow[p];
      if ((g.accept & (1L << p)) != 0L) {
        followWithLoopbacks[p] |= g.initial;
      }
    }

    MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
    mv.visitCode();

    pushStringArray(mv, encodeLongs(followWithLoopbacks));
    pushInt(mv, followWithLoopbacks.length);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/BitParallelGlushkovRuntime",
        "decodeLongArray",
        "([Ljava/lang/String;I)[J",
        false);
    mv.visitFieldInsn(PUTSTATIC, className, "FOLLOW", "[J");

    pushStringArray(mv, encodeLongs(g.entry));
    pushInt(mv, g.entry.length);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/BitParallelGlushkovRuntime",
        "decodeLongArray",
        "([Ljava/lang/String;I)[J",
        false);
    mv.visitFieldInsn(PUTSTATIC, className, "ENTRY", "[J");

    pushStringArray(mv, encodeRle(g.asciiClasses));
    pushInt(mv, g.asciiClasses.length);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/DFATableRuntime",
        "decodeRleIntArray",
        "([Ljava/lang/String;I)[I",
        false);
    mv.visitFieldInsn(PUTSTATIC, className, "ASCII_CLASSES", "[I");

    pushStringArray(mv, split(new String(g.rangeStarts)));
    pushInt(mv, g.rangeStarts.length);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/DFATableRuntime",
        "decodeCharArray",
        "([Ljava/lang/String;I)[C",
        false);
    mv.visitFieldInsn(PUTSTATIC, className, "RANGE_STARTS", "[C");

    pushStringArray(mv, split(new String(g.rangeEnds)));
    pushInt(mv, g.rangeEnds.length);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/DFATableRuntime",
        "decodeCharArray",
        "([Ljava/lang/String;I)[C",
        false);
    mv.visitFieldInsn(PUTSTATIC, className, "RANGE_ENDS", "[C");

    pushStringArray(mv, encodeRle(g.rangeClasses));
    pushInt(mv, g.rangeClasses.length);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/DFATableRuntime",
        "decodeRleIntArray",
        "([Ljava/lang/String;I)[I",
        false);
    mv.visitFieldInsn(PUTSTATIC, className, "RANGE_CLASSES", "[I");

    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // -------------------------------------------------------------------------
  // Public matcher methods (same descriptors as BitParallelGlushkovBytecodeGenerator)
  // -------------------------------------------------------------------------

  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 1);
    pushScalarArgs(mv);
    pushArrayArgs(mv, className);
    mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "matches", matchesDescriptor(), false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindMethod(ClassWriter cw, String className) {
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
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    pushScalarArgs(mv);
    pushArrayArgs(mv, className);
    mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "findFrom", findFromDescriptor(), false);
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
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    Label matched = new Label();
    mv.visitJumpInsn(IFNE, matched);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(matched);
    newMatchResult(mv, 1, 0, -1);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchIntoMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchInto", "(Ljava/lang/String;[I[I)Z", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/util/Objects",
        "requireNonNull",
        "(Ljava/lang/Object;)Ljava/lang/Object;",
        false);
    mv.visitInsn(POP);
    mv.visitVarInsn(ALOAD, 2);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/util/Objects",
        "requireNonNull",
        "(Ljava/lang/Object;)Ljava/lang/Object;",
        false);
    mv.visitInsn(POP);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/util/Objects",
        "requireNonNull",
        "(Ljava/lang/Object;)Ljava/lang/Object;",
        false);
    mv.visitInsn(POP);
    mv.visitVarInsn(ALOAD, 2);
    mv.visitInsn(ARRAYLENGTH);
    Label startsOk = new Label();
    mv.visitJumpInsn(IFGT, startsOk);
    throwBounds(mv);
    mv.visitLabel(startsOk);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ARRAYLENGTH);
    Label endsOk = new Label();
    mv.visitJumpInsn(IFGT, endsOk);
    throwBounds(mv);
    mv.visitLabel(endsOk);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    Label success = new Label();
    mv.visitJumpInsn(IFNE, success);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(success);
    mv.visitVarInsn(ALOAD, 2);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
    pushScalarArgs(mv);
    pushArrayArgs(mv, className);
    mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "matchesBounded", matchesBoundedDescriptor(), false);
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
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", false);
    Label matched = new Label();
    mv.visitJumpInsn(IFNE, matched);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(matched);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    int stringVar = 4;
    mv.visitVarInsn(ASTORE, stringVar);
    newMatchResult(mv, stringVar, 2, 3);
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
        className,
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
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    int boundsVar = 3;
    mv.visitVarInsn(ASTORE, boundsVar);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", false);
    Label matched = new Label();
    mv.visitJumpInsn(IFNE, matched);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(matched);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    pushInt(mv, 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IALOAD);
    mv.visitInsn(IASTORE);
    pushInt(mv, 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IALOAD);
    mv.visitInsn(IASTORE);
    mv.visitInsn(ICONST_0);
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

  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ALOAD, 3);
    pushScalarArgs(mv);
    pushArrayArgs(mv, className);
    mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "findBoundsFrom", findBoundsFromDescriptor(), false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // -------------------------------------------------------------------------
  // Argument-pushing helpers
  // -------------------------------------------------------------------------

  /**
   * Push scalar automaton constants: initial (long), accept (long), nullable (boolean), counterMin
   * (int), counterMax (int).
   */
  private void pushScalarArgs(MethodVisitor mv) {
    mv.visitLdcInsn(g.initial);
    mv.visitLdcInsn(g.accept);
    mv.visitInsn(g.nullable ? ICONST_1 : ICONST_0);
    pushInt(mv, counterMin);
    pushInt(mv, counterMax);
  }

  /**
   * Push the array arguments in runtime-call order: FOLLOW, ASCII_CLASSES, RANGE_STARTS,
   * RANGE_ENDS, RANGE_CLASSES, ENTRY.
   */
  private void pushArrayArgs(MethodVisitor mv, String className) {
    mv.visitFieldInsn(GETSTATIC, className, "FOLLOW", "[J");
    mv.visitFieldInsn(GETSTATIC, className, "ASCII_CLASSES", "[I");
    mv.visitFieldInsn(GETSTATIC, className, "RANGE_STARTS", "[C");
    mv.visitFieldInsn(GETSTATIC, className, "RANGE_ENDS", "[C");
    mv.visitFieldInsn(GETSTATIC, className, "RANGE_CLASSES", "[I");
    mv.visitFieldInsn(GETSTATIC, className, "ENTRY", "[J");
  }

  // -------------------------------------------------------------------------
  // Runtime call descriptors
  // -------------------------------------------------------------------------

  /**
   * matches(CharSequence, long initial, long accept, boolean nullable, int counterMin, int
   * counterMax, long[] follow, int[] asciiClasses, char[] rangeStarts, char[] rangeEnds, int[]
   * rangeClasses, long[] entry)Z
   */
  private static String matchesDescriptor() {
    return "(Ljava/lang/CharSequence;JJZII[J[I[C[C[I[J)Z";
  }

  /**
   * matchesBounded(CharSequence, int start, int end, long initial, long accept, boolean nullable,
   * int counterMin, int counterMax, long[] follow, int[] asciiClasses, char[] rangeStarts, char[]
   * rangeEnds, int[] rangeClasses, long[] entry)Z
   */
  private static String matchesBoundedDescriptor() {
    return "(Ljava/lang/CharSequence;IIJJZII[J[I[C[C[I[J)Z";
  }

  /**
   * findFrom(CharSequence, int from, long initial, long accept, boolean nullable, int counterMin,
   * int counterMax, long[] follow, int[] asciiClasses, char[] rangeStarts, char[] rangeEnds, int[]
   * rangeClasses, long[] entry)I
   */
  private static String findFromDescriptor() {
    return "(Ljava/lang/CharSequence;IJJZII[J[I[C[C[I[J)I";
  }

  /**
   * findBoundsFrom(CharSequence, int from, int[] bounds, long initial, long accept, boolean
   * nullable, int counterMin, int counterMax, long[] follow, int[] asciiClasses, char[]
   * rangeStarts, char[] rangeEnds, int[] rangeClasses, long[] entry)Z
   */
  private static String findBoundsFromDescriptor() {
    return "(Ljava/lang/CharSequence;I[IJJZII[J[I[C[C[I[J)Z";
  }

  // -------------------------------------------------------------------------
  // MatchResult construction (mirrors BitParallelGlushkovBytecodeGenerator exactly)
  // -------------------------------------------------------------------------

  private static void newMatchResult(
      MethodVisitor mv, int inputVar, int startVarOrConst, int endVar) {
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    pushInt(mv, 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    if (startVarOrConst == 0) {
      mv.visitInsn(ICONST_0);
    } else {
      mv.visitVarInsn(ILOAD, startVarOrConst);
    }
    mv.visitInsn(IASTORE);
    pushInt(mv, 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    if (endVar == -1) {
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    } else {
      mv.visitVarInsn(ILOAD, endVar);
    }
    mv.visitInsn(IASTORE);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
  }

  private static void throwBounds(MethodVisitor mv) {
    mv.visitTypeInsn(NEW, "java/lang/IndexOutOfBoundsException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("group arrays must have length at least 1 for this pattern");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/IndexOutOfBoundsException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
  }

  // -------------------------------------------------------------------------
  // Encoding helpers (identical to BitParallelGlushkovBytecodeGenerator)
  // -------------------------------------------------------------------------

  /**
   * Encodes a {@code long[]} as 4 chars per value (bits 48-63, 32-47, 16-31, 0-15, high to low),
   * then splits into STRING_CHUNK_CHARS-wide chunks. Matches the decoding in {@code
   * BitParallelGlushkovRuntime.decodeLongArray}.
   */
  private static String[] encodeLongs(long[] values) {
    StringBuilder sb = new StringBuilder(values.length * 4);
    for (long v : values) {
      sb.append((char) ((v >>> 48) & 0xFFFFL));
      sb.append((char) ((v >>> 32) & 0xFFFFL));
      sb.append((char) ((v >>> 16) & 0xFFFFL));
      sb.append((char) (v & 0xFFFFL));
    }
    return split(sb.toString());
  }

  private static String[] encodeRle(int[] values) {
    StringBuilder encoded = new StringBuilder();
    int index = 0;
    while (index < values.length) {
      int value = values[index];
      int count = 1;
      while (index + count < values.length && values[index + count] == value) {
        count++;
      }
      appendInt(encoded, value);
      appendInt(encoded, count);
      index += count;
    }
    return split(encoded.toString());
  }

  private static void appendInt(StringBuilder builder, int value) {
    builder.append((char) (value >>> 16));
    builder.append((char) value);
  }

  private static String[] split(String value) {
    int chunkCount = Math.max(1, (value.length() + STRING_CHUNK_CHARS - 1) / STRING_CHUNK_CHARS);
    String[] chunks = new String[chunkCount];
    for (int i = 0; i < chunkCount; i++) {
      int start = i * STRING_CHUNK_CHARS;
      int end = Math.min(value.length(), start + STRING_CHUNK_CHARS);
      chunks[i] = value.substring(start, end);
    }
    return chunks;
  }

  private static void pushStringArray(MethodVisitor mv, String[] chunks) {
    pushInt(mv, chunks.length);
    mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
    for (int i = 0; i < chunks.length; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      mv.visitLdcInsn(chunks[i]);
      mv.visitInsn(AASTORE);
    }
  }
}
