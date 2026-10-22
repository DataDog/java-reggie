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
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/** Utility methods for bytecode generation. */
public class BytecodeUtil {

  /**
   * Push an integer constant onto the stack efficiently. Avoids autoboxing issues with
   * visitLdcInsn().
   *
   * @param mv the method visitor
   * @param value the integer value to push
   */
  public static void pushInt(MethodVisitor mv, int value) {
    if (value >= -1 && value <= 5) {
      // Use ICONST_X for small values
      mv.visitInsn(ICONST_0 + value);
    } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
      mv.visitIntInsn(BIPUSH, value);
    } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
      mv.visitIntInsn(SIPUSH, value);
    } else {
      mv.visitLdcInsn(value);
    }
  }

  private static final String LINE_TERMINATORS = "com/datadoghq/reggie/runtime/LineTerminators";

  /** Push the int local {@code fromVar}, or {@code 0} when {@code fromVar} is {@code -1}. */
  public static void pushFromOrZero(MethodVisitor mv, int fromVar) {
    if (fromVar < 0) {
      mv.visitInsn(ICONST_0);
    } else {
      mv.visitVarInsn(ILOAD, fromVar);
    }
  }

  /**
   * Push the input end: the int local {@code endVar}, or, when {@code endVar} is {@code -1}, the
   * length of the input in local slot 1 (a {@link CharSequence} when {@code charSequence}, else a
   * {@link String}).
   */
  public static void pushEndOrLength(MethodVisitor mv, int endVar, boolean charSequence) {
    if (endVar >= 0) {
      mv.visitVarInsn(ILOAD, endVar);
    } else if (charSequence) {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true);
    } else {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    }
  }

  /**
   * Emit {@code LineTerminators.isMultilineStart(input, pos, start, end)}. Stack: {@code
   * CharSequence, int, int, int} → {@code boolean}.
   */
  public static void invokeMultilineStart(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKESTATIC,
        LINE_TERMINATORS,
        "isMultilineStart",
        "(Ljava/lang/CharSequence;III)Z",
        false);
  }

  /**
   * Emit {@code LineTerminators.isMultilineEnd(input, pos, end)}. Stack: {@code CharSequence, int,
   * int} → {@code boolean}.
   */
  public static void invokeMultilineEnd(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKESTATIC, LINE_TERMINATORS, "isMultilineEnd", "(Ljava/lang/CharSequence;II)Z", false);
  }

  /**
   * Emit {@code LineTerminators.isEnd(input, pos, end)}. Stack: {@code CharSequence, int, int} →
   * {@code boolean}.
   */
  public static void invokeEnd(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKESTATIC, LINE_TERMINATORS, "isEnd", "(Ljava/lang/CharSequence;II)Z", false);
  }

  /**
   * Emit a membership test of the char in local {@code cVar} against {@code cs}: jumps to {@code
   * failLabel} when the char is not in the set, falls through when it is. The sorted ranges are
   * bisected on their start chars, so a set of n ranges costs at most ceil(log2(n)) + 2 compares
   * per char. Bound compares already implied by the bisection (initially {@code [0, 0xFFFF]}) are
   * omitted.
   *
   * <p>When the set has several ranges and one of them covers all of printable ASCII ({@code [0x20,
   * 0x7E]}), that range is tested first (one compare for each of its bounds other than 0 and
   * 0xFFFF) and the remaining ranges are bisected only for chars outside it.
   */
  public static void emitCharSetCheck(MethodVisitor mv, CharSet cs, int cVar, Label failLabel) {
    List<CharSet.Range> ranges = cs.getRanges();
    if (ranges.isEmpty()) {
      mv.visitJumpInsn(GOTO, failLabel);
      return;
    }
    int hot = -1;
    if (ranges.size() > 1) {
      for (int i = 0; i < ranges.size(); i++) {
        CharSet.Range r = ranges.get(i);
        if (r.start <= 0x20 && r.end >= 0x7E) {
          hot = i;
          break;
        }
      }
    }
    if (hot < 0) {
      emitRangeBisection(mv, ranges, 0, ranges.size() - 1, 0, 0xFFFF, cVar, failLabel);
      return;
    }
    CharSet.Range h = ranges.get(hot);
    Label inSet = new Label();
    int lo = 0;
    int hi = 0xFFFF;
    if (h.start == 0) {
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, h.end);
      mv.visitJumpInsn(IF_ICMPLE, inSet);
      lo = h.end + 1;
    } else if (h.end == 0xFFFF) {
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, h.start);
      mv.visitJumpInsn(IF_ICMPGE, inSet);
      hi = h.start - 1;
    } else {
      Label notHot = new Label();
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, h.start);
      mv.visitJumpInsn(IF_ICMPLT, notHot);
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, h.end);
      mv.visitJumpInsn(IF_ICMPLE, inSet);
      mv.visitLabel(notHot);
    }
    List<CharSet.Range> rest = new ArrayList<>(ranges);
    rest.remove(hot);
    emitRangeBisection(mv, rest, 0, rest.size() - 1, lo, hi, cVar, failLabel);
    mv.visitLabel(inSet);
  }

  /**
   * Emits the test for {@code ranges[from..to]}, given that the char is already known to lie in
   * {@code [lo, hi]} and outside every range not in {@code [from, to]}.
   */
  private static void emitRangeBisection(
      MethodVisitor mv,
      List<CharSet.Range> ranges,
      int from,
      int to,
      int lo,
      int hi,
      int cVar,
      Label failLabel) {
    if (from == to) {
      CharSet.Range r = ranges.get(from);
      if (r.start == r.end && r.start > lo && r.end < hi) {
        mv.visitVarInsn(ILOAD, cVar);
        pushInt(mv, r.start);
        mv.visitJumpInsn(IF_ICMPNE, failLabel);
        return;
      }
      if (r.start > lo) {
        mv.visitVarInsn(ILOAD, cVar);
        pushInt(mv, r.start);
        mv.visitJumpInsn(IF_ICMPLT, failLabel);
      }
      if (r.end < hi) {
        mv.visitVarInsn(ILOAD, cVar);
        pushInt(mv, r.end);
        mv.visitJumpInsn(IF_ICMPGT, failLabel);
      }
      return;
    }
    int mid = (from + to + 1) >>> 1;
    int split = ranges.get(mid).start;
    Label right = new Label();
    Label inSet = new Label();
    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, split);
    mv.visitJumpInsn(IF_ICMPGE, right);
    emitRangeBisection(mv, ranges, from, mid - 1, lo, split - 1, cVar, failLabel);
    mv.visitJumpInsn(GOTO, inSet);
    mv.visitLabel(right);
    emitRangeBisection(mv, ranges, mid, to, split, hi, cVar, failLabel);
    mv.visitLabel(inSet);
  }
}
