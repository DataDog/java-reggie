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

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.MethodVisitor;

/**
 * SWAR optimization for finding hex digits [0-9a-fA-F]. This is a specialized optimization for the
 * common pattern of hex digits.
 *
 * <p>Generates bytecode that calls: SWARHelper.findNextHexPosition(input, start, len)
 */
public class HexDigitOptimization extends SWAROptimization {

  public HexDigitOptimization() {
    // No parameters needed - always matches [0-9a-fA-F]
  }

  @Override
  public void generateFindNextBytecode(
      MethodVisitor mv, int inputVarSlot, int startVarSlot, int lenVarSlot) {
    // start = SWARHelper.findNextHexPosition(input, start, len);
    mv.visitVarInsn(ALOAD, inputVarSlot); // load input
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextHexPosition",
        "(Ljava/lang/String;II)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  protected void generateFindNextBytecodeWithViewDirect(
      MethodVisitor mv, int viewVarSlot, int startVarSlot, int lenVarSlot) {
    // start = SWARHelper.findNextHexPosition(view, start, len);
    mv.visitVarInsn(ALOAD, viewVarSlot); // load view
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextHexPosition",
        "(Lcom/datadoghq/reggie/runtime/StringView;II)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  public double estimateSpeedup(int expectedStringLength) {
    // Hex digits: highly optimized, gives 3-4x speedup
    return expectedStringLength > 100 ? 4.0 : 2.0;
  }
}
