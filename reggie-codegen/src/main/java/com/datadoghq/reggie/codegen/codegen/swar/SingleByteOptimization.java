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
 * SWAR optimization for finding a single literal byte. Example patterns: 'a', '.', ':', etc.
 *
 * <p>Generates bytecode that calls: SWARHelper.findNextByte(input, start, len, target)
 */
public class SingleByteOptimization extends SWAROptimization {
  private final char target;

  public SingleByteOptimization(char target) {
    this.target = target;
  }

  @Override
  public void generateFindNextBytecode(
      MethodVisitor mv, int inputVarSlot, int startVarSlot, int lenVarSlot) {
    // start = SWARHelper.findNextByte(input, start, len, target);
    mv.visitVarInsn(ALOAD, inputVarSlot); // load input
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) target); // load target as int
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
    // start = SWARHelper.findNextByte(view, start, len, target);
    mv.visitVarInsn(ALOAD, viewVarSlot); // load view
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) target); // load target as int
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
    // Single byte search: SWAR gives 3-5x for sparse matches
    return expectedStringLength > 100 ? 4.0 : 2.0;
  }

  public char getTarget() {
    return target;
  }
}
