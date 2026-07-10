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

import com.datadoghq.reggie.codegen.analysis.SpecializedOptionalGroupInfo;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Bytecode generator for {@code SPECIALIZED_OPTIONAL_GROUP} strategy.
 *
 * <p>Generates matching code for the narrow shape {@code (anchor-start)? (optional-group)
 * (suffix-literal-char) (anchor-end)?}: exactly one optional ({@code min=0,max=1}) capturing group
 * whose entire content is a single literal char, immediately followed by a single literal char
 * suffix. Examples: {@code (a)?b}, {@code ^(a)?b$}, {@code (?<x>a)?b}.
 *
 * <h3>Why this generator exists instead of reusing {@code OptionalGroupBackrefBytecodeGenerator}
 * </h3>
 *
 * <p>See {@code doc/2026-07-09-optional-group-backref-backtracking-bug.md}: that generator's
 * group-then-suffix matching greedily consumes the optional group's content and never retries the
 * group-absent branch when the suffix subsequently fails (its Bug 1), and separately drops prefix
 * content (Bug 2) and never enforces its own computed end-anchor flag (Bug 3). {@link
 * #generateTryMatchAt} is written from scratch specifically to avoid Bug 1: the group-present
 * branch's suffix/full-consumption check, on failure, falls through into the group-absent branch
 * (see the "explicit fall-through retry" comment below) rather than returning a failure - this is
 * exactly the backtrack step Bug 1 was missing. {@code SpecializedOptionalGroupInfo} enforces no
 * prefix support and enforces both anchors it records, closing Bugs 2 and 3 by construction rather
 * than by extending the buggy generator.
 *
 * <h3>{@code matches()}/{@code find()} full-consumption distinction</h3>
 *
 * <p>{@code matches()} and {@code match()} always require the entire input to be consumed,
 * independent of whether the pattern has an explicit {@code $}/{@code \z} - that is what {@code
 * matches()} means. {@code find()}/{@code findFrom()}/{@code findMatch()}/{@code findMatchFrom()}
 * only require full consumption when {@link SpecializedOptionalGroupInfo#hasEndAnchor} is true (an
 * explicit {@code $}/{@code \z} in the pattern); otherwise they accept a match ending mid-string.
 * Both behaviors are produced by the same {@link #generateTryMatchAt} method, called with a
 * different {@code requireFullConsumption} argument by each caller.
 *
 * <h3>Unoverridden methods</h3>
 *
 * <p>Deliberately does not override {@code matchesBounded}, {@code matchBounded}, {@code
 * findBoundsFrom} ({@link com.datadoghq.reggie.runtime.ReggieMatcher#matchesBounded}, {@code
 * #matchBounded}, {@code #findBoundsFrom}) - the base class's default implementations delegate to
 * {@code matches}/{@code match}/{@code findMatchFrom} respectively (with an extra substring
 * allocation), which is correct for this strategy; this is a deliberate, documented perf deferral,
 * not an oversight.
 */
public class SpecializedOptionalGroupBytecodeGenerator {

  private final SpecializedOptionalGroupInfo info;
  private final String className;

  public SpecializedOptionalGroupBytecodeGenerator(
      SpecializedOptionalGroupInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Total capturing group count this generator supports (the optional group only). */
  private int totalGroupCount() {
    return info.groupNumber;
  }

  /** Generate the matches() method - always requires full-input consumption. */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int posVar = alloc.allocate();
    int charVar = alloc.allocate();
    int tmpVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int endVar = alloc.allocate();

    Label returnFalse = new Label();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    generateTryMatchAt(
        mv, posVar, lenVar, charVar, tmpVar, groupStartVar, groupEndVar, endVar, true, returnFalse);

    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(3, alloc.peek());
    mv.visitEnd();
  }

  /** Generate the find() method - delegates to findFrom(input, 0). */
  public void generateFindMethod(ClassWriter cw) {
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

    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  /**
   * Generate the findFrom() method. When {@link SpecializedOptionalGroupInfo#hasStartAnchor} is
   * true, only position 0 can ever match (the pattern requires the match to start at the input's
   * beginning), so the scan loop is restricted to that single position instead of scanning every
   * position from {@code start} and relying on {@link #generateTryMatchAt}'s internal anchor check
   * to fail each one - correct either way, but scanning only the one viable position is faster.
   */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int posVar = alloc.allocate();
    int charVar = alloc.allocate();
    int tmpVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int endVar = alloc.allocate();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = Math.max(0, startPos);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, startVar);
    mv.visitLabel(startNotNeg);

    Label notFound = new Label();

    if (info.hasStartAnchor) {
      // Only pos == 0 can ever match; if the caller's start is already past 0, no match exists.
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitJumpInsn(IFGT, notFound);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, posVar);

      generateTryMatchAt(
          mv,
          posVar,
          lenVar,
          charVar,
          tmpVar,
          groupStartVar,
          groupEndVar,
          endVar,
          info.hasEndAnchor,
          notFound);

      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IRETURN);

      mv.visitLabel(notFound);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    } else {
      Label outerLoop = new Label();
      Label outerEnd = new Label();
      Label tryNextStart = new Label();
      mv.visitLabel(outerLoop);

      // if (start > len) break; (a candidate needs at least 1 char for the suffix)
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGT, outerEnd);

      mv.visitVarInsn(ILOAD, startVar);
      mv.visitVarInsn(ISTORE, posVar);

      generateTryMatchAt(
          mv,
          posVar,
          lenVar,
          charVar,
          tmpVar,
          groupStartVar,
          groupEndVar,
          endVar,
          info.hasEndAnchor,
          tryNextStart);

      mv.visitVarInsn(ILOAD, startVar);
      mv.visitInsn(IRETURN);

      mv.visitLabel(tryNextStart);
      mv.visitIincInsn(startVar, 1);
      mv.visitJumpInsn(GOTO, outerLoop);

      mv.visitLabel(outerEnd);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    mv.visitMaxs(3, alloc.peek());
    mv.visitEnd();
  }

  /** Generate match(String) method that returns a MatchResult with the optional group's span. */
  public void generateMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int posVar = alloc.allocate();
    int charVar = alloc.allocate();
    int tmpVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int endVar = alloc.allocate();
    int startsArrayVar = alloc.allocate();
    int endsArrayVar = alloc.allocate();

    Label returnNull = new Label();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    generateTryMatchAt(
        mv, posVar, lenVar, charVar, tmpVar, groupStartVar, groupEndVar, endVar, true, returnNull);

    generateBuildResult(
        mv, posVar, endVar, groupStartVar, groupEndVar, startsArrayVar, endsArrayVar);
    mv.visitInsn(ARETURN);

    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(5, alloc.peek());
    mv.visitEnd();
  }

  /** Generate findMatch(String) - delegates to findMatchFrom(input, 0). */
  public void generateFindMatchMethod(ClassWriter cw) {
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

    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  /**
   * Generate findMatchFrom(String, int) - same start-position scan restriction as {@link
   * #generateFindFromMethod} when {@link SpecializedOptionalGroupInfo#hasStartAnchor} is true.
   */
  public void generateFindMatchFromMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int posVar = alloc.allocate();
    int charVar = alloc.allocate();
    int tmpVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int endVar = alloc.allocate();
    int startsArrayVar = alloc.allocate();
    int endsArrayVar = alloc.allocate();

    Label returnNull = new Label();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = Math.max(0, startPos);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, startVar);
    mv.visitLabel(startNotNeg);

    if (info.hasStartAnchor) {
      // Only pos == 0 can ever match.
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitJumpInsn(IFGT, returnNull);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, posVar);

      generateTryMatchAt(
          mv,
          posVar,
          lenVar,
          charVar,
          tmpVar,
          groupStartVar,
          groupEndVar,
          endVar,
          info.hasEndAnchor,
          returnNull);

      generateBuildResult(
          mv, posVar, endVar, groupStartVar, groupEndVar, startsArrayVar, endsArrayVar);
      mv.visitInsn(ARETURN);

      mv.visitLabel(returnNull);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
    } else {
      Label outerLoop = new Label();
      Label outerEnd = new Label();
      Label tryNextStart = new Label();
      mv.visitLabel(outerLoop);

      // if (start > len) break;
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGT, outerEnd);

      mv.visitVarInsn(ILOAD, startVar);
      mv.visitVarInsn(ISTORE, posVar);

      generateTryMatchAt(
          mv,
          posVar,
          lenVar,
          charVar,
          tmpVar,
          groupStartVar,
          groupEndVar,
          endVar,
          info.hasEndAnchor,
          tryNextStart);

      generateBuildResult(
          mv, posVar, endVar, groupStartVar, groupEndVar, startsArrayVar, endsArrayVar);
      mv.visitInsn(ARETURN);

      mv.visitLabel(tryNextStart);
      mv.visitIincInsn(startVar, 1);
      mv.visitJumpInsn(GOTO, outerLoop);

      mv.visitLabel(outerEnd);
      mv.visitLabel(returnNull);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
    }

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Builds and pushes a new {@code MatchResultImpl} using the overall span {@code [posVar, endVar)}
   * and the optional group's span {@code [groupStartVar, groupEndVar)} (or {@code -1,-1} if it did
   * not participate, as {@link #generateTryMatchAt} already sets those slots to).
   */
  private void generateBuildResult(
      MethodVisitor mv,
      int posVar,
      int endVar,
      int groupStartVar,
      int groupEndVar,
      int startsArrayVar,
      int endsArrayVar) {
    BytecodeUtil.pushInt(mv, totalGroupCount() + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsArrayVar);

    BytecodeUtil.pushInt(mv, totalGroupCount() + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsArrayVar);

    // starts[0] = pos; ends[0] = end
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ALOAD, endsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitInsn(IASTORE);

    // starts[groupNumber] = groupStart; ends[groupNumber] = groupEnd
    mv.visitVarInsn(ALOAD, startsArrayVar);
    BytecodeUtil.pushInt(mv, info.groupNumber);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, info.groupNumber);
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitInsn(IASTORE);

    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, totalGroupCount());
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
  }

  /**
   * Core per-position match attempt, shared (with a different {@code requireFullConsumption} value)
   * by every generate*Method above. Tries the optional group's literal char at {@code posVar}
   * followed by the suffix literal char; if that fails (either the group's char didn't match, or it
   * did but the suffix/full-consumption check subsequently failed), falls through - does NOT
   * return/fail immediately - to retry with the group treated as absent, requiring only the suffix
   * literal char at {@code posVar}. That fallthrough is the backtrack step {@code
   * OptionalGroupBackrefBytecodeGenerator}'s Bug 1 (see the class javadoc) is missing.
   *
   * <p>On success, sets {@code groupStartVar}/{@code groupEndVar} to the optional group's span (or
   * {@code -1}/{@code -1} if it did not participate) and {@code endVar} to the overall match's end
   * position, then falls through past this method's own final label. {@code posVar} is read-only
   * here: the overall match's start is always its value at entry, and the caller is responsible for
   * that value satisfying {@link SpecializedOptionalGroupInfo#hasStartAnchor} where applicable
   * (both the {@code pos == 0} check inside this method and, in {@code find()}/{@code findFrom()},
   * the outer scan-loop restriction to a single position - see {@link #generateFindFromMethod}). On
   * failure, jumps to {@code failLabel}.
   *
   * @param requireFullConsumption {@code true} for matches()/match() (always required); {@code
   *     info.hasEndAnchor} for find()/findFrom()/findMatch()/findMatchFrom() (required only when
   *     the pattern has an explicit {@code $}/{@code \z})
   */
  private void generateTryMatchAt(
      MethodVisitor mv,
      int posVar,
      int lenVar,
      int charVar,
      int tmpVar,
      int groupStartVar,
      int groupEndVar,
      int endVar,
      boolean requireFullConsumption,
      Label failLabel) {
    Label groupAbsent = new Label();
    Label matched = new Label();

    if (info.hasStartAnchor) {
      // if (pos != 0) goto failLabel;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitJumpInsn(IFNE, failLabel);
    }

    // --- group-present attempt: charAt(pos) == groupChar && charAt(pos+1) == suffixChar ---
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, groupAbsent);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);
    mv.visitVarInsn(ILOAD, charVar);
    BytecodeUtil.pushInt(mv, info.groupChar);
    mv.visitJumpInsn(IF_ICMPNE, groupAbsent);

    // tmp = afterGroup = pos + 1
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, tmpVar);

    mv.visitVarInsn(ILOAD, tmpVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, groupAbsent);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, tmpVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);
    mv.visitVarInsn(ILOAD, charVar);
    BytecodeUtil.pushInt(mv, info.suffixChar);
    mv.visitJumpInsn(IF_ICMPNE, groupAbsent);

    // Group participates: groupStart = pos, groupEnd = afterGroup.
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);
    mv.visitVarInsn(ILOAD, tmpVar);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // end = afterSuffix = afterGroup + 1
    mv.visitVarInsn(ILOAD, tmpVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, endVar);

    if (requireFullConsumption) {
      // Explicit fall-through retry (not a return/fail): if full consumption is required and this
      // suffix match didn't reach the end of input, do NOT fail outright here - fall through to
      // the group-absent branch below and retry with the group treated as absent.
      mv.visitVarInsn(ILOAD, endVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPNE, groupAbsent);
    }

    mv.visitJumpInsn(GOTO, matched);

    // --- group-absent attempt: charAt(pos) == suffixChar ---
    mv.visitLabel(groupAbsent);
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, groupStartVar);
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, groupEndVar);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, failLabel);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);
    mv.visitVarInsn(ILOAD, charVar);
    BytecodeUtil.pushInt(mv, info.suffixChar);
    mv.visitJumpInsn(IF_ICMPNE, failLabel);

    // end = afterSuffix = pos + 1
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, endVar);

    if (requireFullConsumption) {
      mv.visitVarInsn(ILOAD, endVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
    }

    mv.visitLabel(matched);
  }
}
