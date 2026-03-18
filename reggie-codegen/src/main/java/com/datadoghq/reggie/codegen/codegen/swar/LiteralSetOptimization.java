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
package com.datadoghq.reggie.codegen.codegen.swar;

import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt;
import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.MethodVisitor;

/**
 * SWAR optimization for finding any of a small set of literal characters. Example patterns: [.,;:],
 * [+-], stopping characters
 *
 * <p>Currently supports up to 4 literals via SWARUtils.findFirstOf. For patterns with 5+ literals,
 * this optimization should not be used.
 */
public class LiteralSetOptimization extends SWAROptimization {
  private final char[] literals;

  public LiteralSetOptimization(char[] literals) {
    if (literals.length == 0 || literals.length > 4) {
      throw new IllegalArgumentException("Literal set must contain 1-4 characters");
    }
    this.literals = literals;
  }

  @Override
  public void generateFindNextBytecode(
      MethodVisitor mv, int inputVarSlot, int startVarSlot, int lenVarSlot) {
    // For now, fall back to single byte search for the first literal
    // TODO: Generate bytecode to call SWARUtils.findFirstOf with all literals

    // start = SWARHelper.findNextByte(input, start, len, literals[0]);
    mv.visitVarInsn(ALOAD, inputVarSlot); // load input
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) literals[0]); // load first literal as int
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextByte",
        "(Ljava/lang/String;III)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  protected void generateFindNextBytecodeWithViewDirect(
      MethodVisitor mv, int viewVarSlot, int startVarSlot, int lenVarSlot) {
    // For now, fall back to single byte search for the first literal
    // TODO: Generate bytecode to call SWARUtils.findFirstOf with all literals

    // start = SWARHelper.findNextByte(view, start, len, literals[0]);
    mv.visitVarInsn(ALOAD, viewVarSlot); // load view
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) literals[0]); // load first literal as int
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextByte",
        "(Lcom/datadoghq/reggie/runtime/StringView;III)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  public double estimateSpeedup(int expectedStringLength) {
    // Small literal set: SWAR gives 3-5x for very sparse matches
    return expectedStringLength > 100 ? 4.0 : 2.0;
  }

  public char[] getLiterals() {
    return literals;
  }
}
