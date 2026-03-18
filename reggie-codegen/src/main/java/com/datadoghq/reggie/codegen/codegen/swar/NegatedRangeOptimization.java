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
 * SWAR optimization for finding a character NOT in a range. Example patterns: [^0-9], [^a-z], \D
 * (non-digit)
 *
 * <p>Generates bytecode that calls: SWARHelper.findNextNotInRange(input, start, len, low, high)
 *
 * <p>This optimization is only beneficial when the negated range is narrow (≤64 chars), as this
 * means most bytes will match and SWAR can skip efficiently.
 */
public class NegatedRangeOptimization extends SWAROptimization {
  private final char low;
  private final char high;

  public NegatedRangeOptimization(char low, char high) {
    this.low = low;
    this.high = high;
  }

  @Override
  public void generateFindNextBytecode(
      MethodVisitor mv, int inputVarSlot, int startVarSlot, int lenVarSlot) {
    // start = SWARHelper.findNextNotInRange(input, start, len, low, high);
    mv.visitVarInsn(ALOAD, inputVarSlot); // load input
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) low); // load low as int
    pushInt(mv, (int) high); // load high as int
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextNotInRange",
        "(Ljava/lang/String;IIII)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  protected void generateFindNextBytecodeWithViewDirect(
      MethodVisitor mv, int viewVarSlot, int startVarSlot, int lenVarSlot) {
    // start = SWARHelper.findNextNotInRange(view, start, len, low, high);
    mv.visitVarInsn(ALOAD, viewVarSlot); // load view
    mv.visitVarInsn(ILOAD, startVarSlot); // load start
    mv.visitVarInsn(ILOAD, lenVarSlot); // load len
    pushInt(mv, (int) low); // load low as int
    pushInt(mv, (int) high); // load high as int
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/SWARHelper",
        "findNextNotInRange",
        "(Lcom/datadoghq/reggie/runtime/StringView;IIII)I",
        false);
    mv.visitVarInsn(ISTORE, startVarSlot); // store result back to start
  }

  @Override
  public double estimateSpeedup(int expectedStringLength) {
    // Negated range: beneficial when range is narrow (most chars match)
    // For narrow ranges like [^0-9] (10 chars), speedup is good
    int rangeSize = high - low + 1;
    if (rangeSize <= 10) {
      return expectedStringLength > 100 ? 2.5 : 1.5;
    } else if (rangeSize <= 26) {
      return expectedStringLength > 100 ? 2.0 : 1.3;
    } else {
      return expectedStringLength > 100 ? 1.5 : 1.1;
    }
  }

  public char getLow() {
    return low;
  }

  public char getHigh() {
    return high;
  }
}
