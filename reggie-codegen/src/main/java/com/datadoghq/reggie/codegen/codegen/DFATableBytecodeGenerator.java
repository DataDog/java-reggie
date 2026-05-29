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

import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.DFATableData;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/** Generates compact table-driven DFA bytecode for large pure regular patterns without groups. */
public final class DFATableBytecodeGenerator {
  private static final String RUNTIME = "com/datadoghq/reggie/runtime/DFATableRuntime";
  private static final int STRING_CHUNK_CHARS = 10_000;

  private final DFATableData table;

  public DFATableBytecodeGenerator(DFA dfa) {
    this.table = DFATableData.from(dfa);
  }

  public void generateStaticData(ClassWriter cw, String className) {
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DFA_TRANSITIONS", "[I", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DFA_ACCEPTING", "[Z", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DFA_ASCII_CLASSES", "[I", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DFA_START_ASCII", "[Z", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DFA_RANGE_STARTS", "[C", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DFA_RANGE_ENDS", "[C", null, null)
        .visitEnd();
    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "DFA_RANGE_CLASSES", "[I", null, null)
        .visitEnd();

    MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
    mv.visitCode();

    pushStringArray(mv, encodeRle(table.transitions));
    pushInt(mv, table.transitions.length);
    mv.visitMethodInsn(
        INVOKESTATIC, RUNTIME, "decodeRleIntArray", "([Ljava/lang/String;I)[I", false);
    mv.visitFieldInsn(PUTSTATIC, className, "DFA_TRANSITIONS", "[I");

    pushStringArray(mv, encodeBooleans(table.accepting));
    pushInt(mv, table.accepting.length);
    mv.visitMethodInsn(
        INVOKESTATIC, RUNTIME, "decodeBooleanArray", "([Ljava/lang/String;I)[Z", false);
    mv.visitFieldInsn(PUTSTATIC, className, "DFA_ACCEPTING", "[Z");

    pushStringArray(mv, encodeRle(table.asciiClasses));
    pushInt(mv, table.asciiClasses.length);
    mv.visitMethodInsn(
        INVOKESTATIC, RUNTIME, "decodeRleIntArray", "([Ljava/lang/String;I)[I", false);
    mv.visitFieldInsn(PUTSTATIC, className, "DFA_ASCII_CLASSES", "[I");

    pushStringArray(mv, encodeBooleans(table.startAscii));
    pushInt(mv, table.startAscii.length);
    mv.visitMethodInsn(
        INVOKESTATIC, RUNTIME, "decodeBooleanArray", "([Ljava/lang/String;I)[Z", false);
    mv.visitFieldInsn(PUTSTATIC, className, "DFA_START_ASCII", "[Z");

    pushStringArray(mv, split(new String(table.rangeStarts)));
    pushInt(mv, table.rangeStarts.length);
    mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "decodeCharArray", "([Ljava/lang/String;I)[C", false);
    mv.visitFieldInsn(PUTSTATIC, className, "DFA_RANGE_STARTS", "[C");

    pushStringArray(mv, split(new String(table.rangeEnds)));
    pushInt(mv, table.rangeEnds.length);
    mv.visitMethodInsn(INVOKESTATIC, RUNTIME, "decodeCharArray", "([Ljava/lang/String;I)[C", false);
    mv.visitFieldInsn(PUTSTATIC, className, "DFA_RANGE_ENDS", "[C");

    pushStringArray(mv, encodeRle(table.rangeClasses));
    pushInt(mv, table.rangeClasses.length);
    mv.visitMethodInsn(
        INVOKESTATIC, RUNTIME, "decodeRleIntArray", "([Ljava/lang/String;I)[I", false);
    mv.visitFieldInsn(PUTSTATIC, className, "DFA_RANGE_CLASSES", "[I");

    mv.visitInsn(RETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    pushTableCallArguments(mv, className, 1);
    mv.visitMethodInsn(
        INVOKESTATIC,
        RUNTIME,
        "matches",
        tableCallDescriptor("Ljava/lang/CharSequence;", ")Z"),
        false);
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
    pushTableCallArguments(mv, className, 1, 2);
    mv.visitMethodInsn(
        INVOKESTATIC,
        RUNTIME,
        "findFrom",
        tableCallDescriptor("Ljava/lang/CharSequence;I", ")I"),
        false);
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
    pushTableCallArguments(mv, className, 1, 2, 3);
    mv.visitMethodInsn(
        INVOKESTATIC,
        RUNTIME,
        "matchesBounded",
        tableCallDescriptor("Ljava/lang/CharSequence;II", ")Z"),
        false);
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
    pushFindBoundsTableCallArguments(mv, className, 1, 2, 3);
    mv.visitMethodInsn(
        INVOKESTATIC,
        RUNTIME,
        "findBoundsFrom",
        tableCallDescriptor("Ljava/lang/CharSequence;I[I", ")Z"),
        false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  private void pushTableCallArguments(MethodVisitor mv, String className, int inputVar) {
    mv.visitVarInsn(ALOAD, inputVar);
    pushCommonTableArguments(mv, className);
  }

  private void pushTableCallArguments(
      MethodVisitor mv, String className, int inputVar, int intVar) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, intVar);
    pushCommonTableArguments(mv, className);
  }

  private void pushTableCallArguments(
      MethodVisitor mv, String className, int inputVar, int intVar1, int intVar2) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, intVar1);
    mv.visitVarInsn(ILOAD, intVar2);
    pushCommonTableArguments(mv, className);
  }

  private void pushFindBoundsTableCallArguments(
      MethodVisitor mv, String className, int inputVar, int intVar, int arrayVar) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, intVar);
    mv.visitVarInsn(ALOAD, arrayVar);
    pushCommonTableArguments(mv, className);
  }

  private void pushCommonTableArguments(MethodVisitor mv, String className) {
    pushInt(mv, table.startState);
    pushInt(mv, table.classCount);
    mv.visitFieldInsn(GETSTATIC, className, "DFA_TRANSITIONS", "[I");
    mv.visitFieldInsn(GETSTATIC, className, "DFA_ACCEPTING", "[Z");
    mv.visitFieldInsn(GETSTATIC, className, "DFA_ASCII_CLASSES", "[I");
    mv.visitFieldInsn(GETSTATIC, className, "DFA_START_ASCII", "[Z");
    mv.visitFieldInsn(GETSTATIC, className, "DFA_RANGE_STARTS", "[C");
    mv.visitFieldInsn(GETSTATIC, className, "DFA_RANGE_ENDS", "[C");
    mv.visitFieldInsn(GETSTATIC, className, "DFA_RANGE_CLASSES", "[I");
  }

  private static String tableCallDescriptor(String prefix, String suffix) {
    return "(" + prefix + "II[I[Z[I[Z[C[C[I" + suffix;
  }

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

  private static String[] encodeBooleans(boolean[] values) {
    StringBuilder encoded = new StringBuilder(values.length);
    for (boolean value : values) {
      encoded.append(value ? (char) 1 : (char) 0);
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
