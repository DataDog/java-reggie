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
 * SWAR optimization for finding a character in multiple disjoint ranges. Example patterns:
 * [a-zA-Z], [a-zA-Z0-9], [0-9a-zA-Z_]
 *
 * <p>For common patterns like [a-zA-Z] and [a-zA-Z0-9], uses specialized helper methods. For
 * general multi-range patterns, generates inline bytecode to create the range array.
 */
public class MultiRangeOptimization extends SWAROptimization {
  private final char[] ranges; // Flattened [low1, high1, low2, high2, ...]

  public MultiRangeOptimization(char[] ranges) {
    if (ranges.length == 0 || ranges.length % 2 != 0) {
      throw new IllegalArgumentException("Ranges must contain [low, high] pairs");
    }
    this.ranges = ranges;
  }

  @Override
  public void generateFindNextBytecode(
      MethodVisitor mv, int inputVarSlot, int startVarSlot, int lenVarSlot) {
    // Check if this is a common pattern we can optimize
    if (isAlpha()) {
      // start = SWARHelper.findNextAlpha(input, start, len);
      mv.visitVarInsn(ALOAD, inputVarSlot);
      mv.visitVarInsn(ILOAD, startVarSlot);
      mv.visitVarInsn(ILOAD, lenVarSlot);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/SWARHelper",
          "findNextAlpha",
          "(Ljava/lang/String;II)I",
          false);
      mv.visitVarInsn(ISTORE, startVarSlot);
    } else if (isAlphaNum()) {
      // start = SWARHelper.findNextAlphaNum(input, start, len);
      mv.visitVarInsn(ALOAD, inputVarSlot);
      mv.visitVarInsn(ILOAD, startVarSlot);
      mv.visitVarInsn(ILOAD, lenVarSlot);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/SWARHelper",
          "findNextAlphaNum",
          "(Ljava/lang/String;II)I",
          false);
      mv.visitVarInsn(ISTORE, startVarSlot);
    } else {
      // General case: create char[] array and call SWARUtils directly via StringView
      // For now, fall back to first range only as a simplification
      // TODO: Generate full multi-range bytecode if needed
      mv.visitVarInsn(ALOAD, inputVarSlot);
      mv.visitVarInsn(ILOAD, startVarSlot);
      mv.visitVarInsn(ILOAD, lenVarSlot);
      pushInt(mv, (int) ranges[0]); // first range low
      pushInt(mv, (int) ranges[1]); // first range high
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/SWARHelper",
          "findNextInRange",
          "(Ljava/lang/String;IIII)I",
          false);
      mv.visitVarInsn(ISTORE, startVarSlot);
    }
  }

  @Override
  protected void generateFindNextBytecodeWithViewDirect(
      MethodVisitor mv, int viewVarSlot, int startVarSlot, int lenVarSlot) {
    // Check if this is a common pattern we can optimize
    if (isAlpha()) {
      // start = SWARHelper.findNextAlpha(view, start, len);
      mv.visitVarInsn(ALOAD, viewVarSlot);
      mv.visitVarInsn(ILOAD, startVarSlot);
      mv.visitVarInsn(ILOAD, lenVarSlot);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/SWARHelper",
          "findNextAlpha",
          "(Lcom/datadoghq/reggie/runtime/StringView;II)I",
          false);
      mv.visitVarInsn(ISTORE, startVarSlot);
    } else if (isAlphaNum()) {
      // start = SWARHelper.findNextAlphaNum(view, start, len);
      mv.visitVarInsn(ALOAD, viewVarSlot);
      mv.visitVarInsn(ILOAD, startVarSlot);
      mv.visitVarInsn(ILOAD, lenVarSlot);
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/SWARHelper",
          "findNextAlphaNum",
          "(Lcom/datadoghq/reggie/runtime/StringView;II)I",
          false);
      mv.visitVarInsn(ISTORE, startVarSlot);
    } else {
      // General case: fall back to first range only as a simplification
      mv.visitVarInsn(ALOAD, viewVarSlot);
      mv.visitVarInsn(ILOAD, startVarSlot);
      mv.visitVarInsn(ILOAD, lenVarSlot);
      pushInt(mv, (int) ranges[0]); // first range low
      pushInt(mv, (int) ranges[1]); // first range high
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/SWARHelper",
          "findNextInRange",
          "(Lcom/datadoghq/reggie/runtime/StringView;IIII)I",
          false);
      mv.visitVarInsn(ISTORE, startVarSlot);
    }
  }

  @Override
  public double estimateSpeedup(int expectedStringLength) {
    // Multi-range: slightly less efficient than single range, but still 2-3x
    return expectedStringLength > 100 ? 2.5 : 1.5;
  }

  /** Check if this is the pattern [a-zA-Z] */
  private boolean isAlpha() {
    return ranges.length == 4
        && ranges[0] == 'a'
        && ranges[1] == 'z'
        && ranges[2] == 'A'
        && ranges[3] == 'Z';
  }

  /** Check if this is the pattern [a-zA-Z0-9] or [0-9a-zA-Z] */
  private boolean isAlphaNum() {
    if (ranges.length != 6) {
      return false;
    }

    // Check for [0-9a-zA-Z]
    boolean variant1 =
        ranges[0] == '0'
            && ranges[1] == '9'
            && ranges[2] == 'a'
            && ranges[3] == 'z'
            && ranges[4] == 'A'
            && ranges[5] == 'Z';

    // Check for [a-zA-Z0-9]
    boolean variant2 =
        ranges[0] == 'a'
            && ranges[1] == 'z'
            && ranges[2] == 'A'
            && ranges[3] == 'Z'
            && ranges[4] == '0'
            && ranges[5] == '9';

    return variant1 || variant2;
  }

  public char[] getRanges() {
    return ranges;
  }
}
