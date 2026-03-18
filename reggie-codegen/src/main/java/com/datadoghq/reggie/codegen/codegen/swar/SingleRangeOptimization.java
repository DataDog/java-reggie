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
 * SWAR optimization for finding a character in a single range. Example patterns: [0-9], [a-z],
 * [A-Z], [0-5]
 *
 * <p>Generates bytecode that calls: SWARHelper.findNextInRange(input, start, len, low, high)
 */
public class SingleRangeOptimization extends SWAROptimization {
  private final char low;
  private final char high;

  public SingleRangeOptimization(char low, char high) {
    this.low = low;
    this.high = high;
  }

  @Override
  public void generateFindNextBytecode(
      MethodVisitor mv, int inputVarSlot, int startVarSlot, int lenVarSlot) {
    // start = SWARHelper.findNextInRange(input, start, len, low, high);
    mv.visitVarInsn(ALOAD, inputVarSlot); // load input
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) low); // load low as int
    pushInt(mv, (int) high); // load high as int
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextInRange",
        "(Ljava/lang/String;IIII)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  protected void generateFindNextBytecodeWithViewDirect(
      MethodVisitor mv, int viewVarSlot, int startVarSlot, int lenVarSlot) {
    // start = SWARHelper.findNextInRange(view, start, len, low, high);
    mv.visitVarInsn(ALOAD, viewVarSlot); // load view
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) low); // load low as int
    pushInt(mv, (int) high); // load high as int
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextInRange",
        "(Lcom/datadoghq/reggie/runtime/StringView;IIII)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  public double estimateSpeedup(int expectedStringLength) {
    // Single range: SWAR gives ~3x for strings >100 chars
    return expectedStringLength > 100 ? 3.0 : 1.5;
  }

  public char getLow() {
    return low;
  }

  public char getHigh() {
    return high;
  }
}
