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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.*;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates highly optimized bytecode for bounded quantifier sequence patterns.
 *
 * <h3>Pattern Types</h3>
 *
 * Multiple bounded quantifiers separated by literals: {@code \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}}
 * (IPv4), {@code [0-9a-f]{8}-[0-9a-f]{4}-...} (UUID)
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3} (IPv4)
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int pos = 0;
 *     int len = input.length();
 *
 *     // Bounds check
 *     if (len < minLength || len > maxLength) return false;
 *
 *     // Element 1: \d{1,3}
 *     int count1 = 0;
 *     while (pos < len && count1 < 3 && isDigit(input.charAt(pos))) {
 *         pos++; count1++;
 *     }
 *     if (count1 < 1) return false;
 *
 *     // Element 2: \.
 *     if (pos >= len || input.charAt(pos) != '.') return false;
 *     pos++;
 *
 *     // Element 3: \d{1,3}
 *     // ... repeat for remaining elements
 *
 *     return pos == len;
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li>Bounded loops with inline character checks
 *   <li>NO state machines, NO switches
 *   <li>SWAR optimization for bulk character checking
 * </ul>
 */
public class BoundedQuantifierBytecodeGenerator {

  private final List<BoundedElement> elements;
  private final int minLength;
  private final int maxLength;
  private final int groupCount;

  // Feature flag for SWAR optimization
  private static final boolean SWAR_ENABLED =
      Boolean.parseBoolean(System.getProperty("reggie.swar.enabled", "true"));

  public BoundedQuantifierBytecodeGenerator(BoundedQuantifierInfo info, int groupCount) {
    this.elements = info.elements;
    this.minLength = info.minLength;
    this.maxLength = info.maxLength;
    this.groupCount = groupCount;
  }

  /**
   * Analyze the first element to determine if SWAR optimization is applicable. Uses compile-time
   * pattern analysis to generate specialized bytecode.
   *
   * @return SWAROptimization for the first element, or null if not optimizable
   */
  private com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization analyzeFirstElement() {
    if (!SWAR_ENABLED || elements.isEmpty()) {
      return null;
    }

    BoundedElement first = elements.get(0);
    if (!(first instanceof BoundedQuantifierElement)) {
      return null;
    }

    BoundedQuantifierElement elem = (BoundedQuantifierElement) first;
    if (elem.min < 1) {
      // Only optimize if at least 1 character required
      return null;
    }

    // Use SWARPatternAnalyzer to determine optimization at compile time
    return SWARPatternAnalyzer.analyzeForSWAR(elem.charset, elem.negated);
  }

  /**
   * Generate matches() method - boolean check only. Structure: int pos = 0; // For each element: //
   * if quantifier: bounded loop with min/max checks // if literal: single char check return pos ==
   * len;
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Generate code for each element
    int nextVarSlot = allocator.peek();
    for (BoundedElement elem : elements) {
      nextVarSlot = generateElementMatching(mv, elem, posVar, lenVar, nextVarSlot);
    }

    // return pos == len (consumed entire input);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    Label returnTrue = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, returnTrue);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findMatch() method - scanning with MatchResult. */
  public void generateFindMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // return findMatchFrom(input, 0);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // fromIndex = 0
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

  /** Generate findMatchFrom() method - scanning from offset with MatchResult. */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 3); // len in var 3

    com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization swarOpt = analyzeFirstElement();

    // Create StringView once at method entry for SWAR optimization
    // StringView view = StringView.of(input);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/StringView",
        "of",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/StringView;",
        false);
    mv.visitVarInsn(ASTORE, 6); // view in var 6

    // Check if view is usable (non-null and Latin-1)
    Label viewUsable = new Label();
    mv.visitVarInsn(ALOAD, 6);
    mv.visitJumpInsn(IFNULL, viewUsable); // null is ok, just skip optimization
    mv.visitVarInsn(ALOAD, 6);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StringView", "isLatin1", "()Z", false);
    mv.visitJumpInsn(IFNE, viewUsable); // isLatin1() == true, keep view
    // isLatin1() == false, set view = null (mark as unusable)
    mv.visitInsn(ACONST_NULL);
    mv.visitVarInsn(ASTORE, 6);
    mv.visitLabel(viewUsable);

    // Scanning loop: for (int start = fromIndex; start < len; start++)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // int start = fromIndex;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, 4); // start in var 4

    mv.visitLabel(loopStart);

    // if (start > len - minLength) return null; // Not enough chars left
    mv.visitVarInsn(ILOAD, 4); // start
    mv.visitVarInsn(ILOAD, 3); // len
    pushInt(mv, minLength);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    if (swarOpt != null) {
      // SWAR optimization: Use StringView-based search for better performance
      // Generates: start = SWARHelper.findNext...(view, start, len);
      // Falls back to String-based search if view is null (UTF-16)
      swarOpt.generateFindNextBytecodeWithView(mv, 6, 1, 4, 3); // view=6, input=1, start=4, len=3

      // if (start < 0) return null; (no match found)
      mv.visitVarInsn(ILOAD, 4);
      mv.visitJumpInsn(IFLT, loopEnd);

      // Check again: if (start > len - minLength) return null;
      mv.visitVarInsn(ILOAD, 4); // start
      mv.visitVarInsn(ILOAD, 3); // len
      pushInt(mv, minLength);
      mv.visitInsn(ISUB);
      mv.visitJumpInsn(IF_ICMPGT, loopEnd);
    }

    // Try match at current position
    // int pos = start;
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ISTORE, 5); // pos in var 5

    // Generate matching logic for all elements
    int nextVarSlot = 7; // var 6 is used by StringView
    Label matchFailed = new Label();
    for (BoundedElement elem : elements) {
      nextVarSlot =
          generateElementMatchingWithFailLabel(
              mv, elem, 5, 3, nextVarSlot, matchFailed); // pos=5, len=3
    }

    // Match succeeded! Create MatchResultImpl
    // new MatchResultImpl(input, new int[]{start}, new int[]{pos}, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input

    // starts array: new int[groupCount + 1]
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 4); // start
    mv.visitInsn(IASTORE);

    // ends array: new int[groupCount + 1]
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 5); // pos (end)
    mv.visitInsn(IASTORE);

    pushInt(mv, groupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Match failed at this position, try next
    mv.visitLabel(matchFailed);
    mv.visitIincInsn(4, 1); // start++
    mv.visitJumpInsn(GOTO, loopStart);

    // No match found
    mv.visitLabel(loopEnd);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findBoundsFrom() method - allocation-free alternative to findMatchFrom(). Signature:
   * public boolean findBoundsFrom(String input, int start, int[] bounds)
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 4); // len in var 4

    com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization swarOpt = analyzeFirstElement();

    // Only create StringView if SWAR optimization is actually used
    // This avoids allocating StringView objects when not needed
    if (swarOpt != null) {
      // Create StringView once at method entry for SWAR optimization
      // StringView view = StringView.of(input);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitMethodInsn(
          INVOKESTATIC,
          "com/datadoghq/reggie/runtime/StringView",
          "of",
          "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/StringView;",
          false);
      mv.visitVarInsn(ASTORE, 7); // view in var 7

      // Check if view is usable (non-null and Latin-1)
      Label viewUsable = new Label();
      mv.visitVarInsn(ALOAD, 7);
      mv.visitJumpInsn(IFNULL, viewUsable); // null is ok, just skip optimization
      mv.visitVarInsn(ALOAD, 7);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StringView", "isLatin1", "()Z", false);
      mv.visitJumpInsn(IFNE, viewUsable); // isLatin1() == true, keep view
      // isLatin1() == false, set view = null (mark as unusable)
      mv.visitInsn(ACONST_NULL);
      mv.visitVarInsn(ASTORE, 7);
      mv.visitLabel(viewUsable);
    }

    // Scanning loop: for (int matchStart = fromIndex; matchStart < len; matchStart++)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // int matchStart = fromIndex;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, 5); // matchStart in var 5

    mv.visitLabel(loopStart);

    // if (matchStart > len - minLength) return false; // Not enough chars left
    mv.visitVarInsn(ILOAD, 5); // matchStart
    mv.visitVarInsn(ILOAD, 4); // len
    pushInt(mv, minLength);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    if (swarOpt != null) {
      // SWAR optimization: Use StringView-based search for better performance
      // Generates: matchStart = SWARHelper.findNext...(view, matchStart, len);
      // Falls back to String-based search if view is null (UTF-16)
      swarOpt.generateFindNextBytecodeWithView(
          mv, 7, 1, 5, 4); // view=7, input=1, matchStart=5, len=4

      // if (matchStart < 0) return false; (no match found)
      mv.visitVarInsn(ILOAD, 5);
      mv.visitJumpInsn(IFLT, loopEnd);

      // Check again: if (matchStart > len - minLength) return false;
      mv.visitVarInsn(ILOAD, 5); // matchStart
      mv.visitVarInsn(ILOAD, 4); // len
      pushInt(mv, minLength);
      mv.visitInsn(ISUB);
      mv.visitJumpInsn(IF_ICMPGT, loopEnd);
    }

    // Try match at current position
    // int pos = matchStart;
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ISTORE, 6); // pos in var 6

    // Generate matching logic for all elements
    // var 7 is used by StringView only if swarOpt != null
    int nextVarSlot = (swarOpt != null) ? 8 : 7;
    Label matchFailed = new Label();
    for (BoundedElement elem : elements) {
      nextVarSlot =
          generateElementMatchingWithFailLabel(
              mv, elem, 6, 4, nextVarSlot, matchFailed); // pos=6, len=4
    }

    // Match succeeded! Store boundaries in bounds array
    // bounds[0] = matchStart;
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 5); // matchStart
    mv.visitInsn(IASTORE);

    // bounds[1] = pos; (match end)
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, 6); // pos (end)
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Match failed at this position, try next
    mv.visitLabel(matchFailed);
    mv.visitIincInsn(5, 1); // matchStart++
    mv.visitJumpInsn(GOTO, loopStart);

    // No match found
    mv.visitLabel(loopEnd);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate find() method - scanning, returns boolean. */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // return findFrom(input, 0) >= 0;
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // fromIndex = 0
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "findFrom", "(Ljava/lang/String;I)I", false);

    // Convert int result to boolean: position >= 0
    Label found = new Label();
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0); // false (not found)
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);
    mv.visitInsn(ICONST_1); // true (found)
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findFrom() method - scanning from offset, returns position or -1. */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // if (input == null) return -1;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1); // -1
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 3); // len in var 3

    com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization swarOpt = analyzeFirstElement();

    // Create StringView once at method entry for SWAR optimization
    // StringView view = StringView.of(input);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKESTATIC,
        "com/datadoghq/reggie/runtime/StringView",
        "of",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/StringView;",
        false);
    mv.visitVarInsn(ASTORE, 6); // view in var 6

    // Check if view is usable (non-null and Latin-1)
    Label viewUsable = new Label();
    mv.visitVarInsn(ALOAD, 6);
    mv.visitJumpInsn(IFNULL, viewUsable); // null is ok, just skip optimization
    mv.visitVarInsn(ALOAD, 6);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StringView", "isLatin1", "()Z", false);
    mv.visitJumpInsn(IFNE, viewUsable); // isLatin1() == true, keep view
    // isLatin1() == false, set view = null (mark as unusable)
    mv.visitInsn(ACONST_NULL);
    mv.visitVarInsn(ASTORE, 6);
    mv.visitLabel(viewUsable);

    // Scanning loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // int start = fromIndex;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, 4); // start in var 4

    mv.visitLabel(loopStart);

    // if (start > len - minLength) return false;
    mv.visitVarInsn(ILOAD, 4); // start
    mv.visitVarInsn(ILOAD, 3); // len
    pushInt(mv, minLength);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    if (swarOpt != null) {
      // SWAR optimization: Use StringView-based search for better performance
      // Generates: start = SWARHelper.findNext...(view, start, len);
      // Falls back to String-based search if view is null (UTF-16)
      swarOpt.generateFindNextBytecodeWithView(mv, 6, 1, 4, 3); // view=6, input=1, start=4, len=3

      // if (start < 0) return false; (no match found)
      mv.visitVarInsn(ILOAD, 4);
      mv.visitJumpInsn(IFLT, loopEnd);

      // Check again: if (start > len - minLength) return false;
      mv.visitVarInsn(ILOAD, 4); // start
      mv.visitVarInsn(ILOAD, 3); // len
      pushInt(mv, minLength);
      mv.visitInsn(ISUB);
      mv.visitJumpInsn(IF_ICMPGT, loopEnd);
    }

    // Try match at current position
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ISTORE, 5); // pos in var 5

    // Generate matching logic
    int nextVarSlot = 7; // var 6 is used by StringView
    Label matchFailed = new Label();
    for (BoundedElement elem : elements) {
      nextVarSlot =
          generateElementMatchingWithFailLabel(
              mv, elem, 5, 3, nextVarSlot, matchFailed); // pos=5, len=3
    }

    // Match succeeded! Return match position (start)
    mv.visitVarInsn(ILOAD, 4); // start (match position)
    mv.visitInsn(IRETURN);

    // Match failed, try next position
    mv.visitLabel(matchFailed);
    mv.visitIincInsn(4, 1); // start++
    mv.visitJumpInsn(GOTO, loopStart);

    // No match found, return -1
    mv.visitLabel(loopEnd);
    mv.visitInsn(ICONST_M1); // -1
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate match() method - anchored match with MatchResult. */
  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Generate matching logic
    int nextVarSlot = allocator.peek();
    Label matchFailed = new Label();
    for (BoundedElement elem : elements) {
      nextVarSlot =
          generateElementMatchingWithFailLabel(mv, elem, posVar, lenVar, nextVarSlot, matchFailed);
    }

    // Check if we consumed entire input
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, matchFailed);

    // Success! Create MatchResultImpl
    // new MatchResultImpl(input, new int[]{0}, new int[]{pos}, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);

    // starts array: new int[groupCount + 1] with [0] = 0
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitInsn(IASTORE);

    // ends array: new int[groupCount + 1] with [0] = pos
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    pushInt(mv, groupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Match failed
    mv.visitLabel(matchFailed);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchesBounded() method - check bounded region, returns boolean. */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = beginIndex, slot 3 = endIndex
    int inputVar = 1;
    int beginVar = 2;
    int endVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int subVar = allocator.allocate();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // String sub = input.subSequence(beginIndex, endIndex).toString();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, beginVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ASTORE, subVar);

    // return matches(sub);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, subVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchBounded() method - match bounded region with MatchResult. */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = beginIndex, slot 3 = endIndex
    int inputVar = 1;
    int beginVar = 2;
    int endVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int subVar = allocator.allocate();

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // String sub = input.subSequence(beginIndex, endIndex).toString();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, beginVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ASTORE, subVar);

    // return match(sub);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, subVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className,
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matching code for a single element (for matches() method). Returns the next available
   * variable slot.
   *
   * @param posSlot the variable slot holding the position
   * @param lenSlot the variable slot holding the length
   */
  private int generateElementMatching(
      MethodVisitor mv, BoundedElement elem, int posSlot, int lenSlot, int nextVarSlot) {
    if (elem instanceof BoundedQuantifierElement) {
      return generateBoundedQuantifierMatching(
          mv, (BoundedQuantifierElement) elem, posSlot, lenSlot, nextVarSlot);
    } else if (elem instanceof BoundedLiteralElement) {
      return generateLiteralMatching(
          mv, (BoundedLiteralElement) elem, posSlot, lenSlot, nextVarSlot);
    } else if (elem instanceof BoundedOptionalElement) {
      return generateOptionalMatching(
          mv, (BoundedOptionalElement) elem, posSlot, lenSlot, nextVarSlot);
    } else {
      throw new IllegalArgumentException("Unknown element type: " + elem.getClass());
    }
  }

  /** Generate matching code for a single element with fail label (for find methods). */
  private int generateElementMatchingWithFailLabel(
      MethodVisitor mv,
      BoundedElement elem,
      int posSlot,
      int lenSlot,
      int nextVarSlot,
      Label matchFailed) {
    if (elem instanceof BoundedQuantifierElement) {
      return generateBoundedQuantifierMatchingWithFail(
          mv, (BoundedQuantifierElement) elem, posSlot, lenSlot, nextVarSlot, matchFailed);
    } else if (elem instanceof BoundedLiteralElement) {
      return generateLiteralMatchingWithFail(
          mv, (BoundedLiteralElement) elem, posSlot, lenSlot, nextVarSlot, matchFailed);
    } else if (elem instanceof BoundedOptionalElement) {
      return generateOptionalMatchingWithFail(
          mv, (BoundedOptionalElement) elem, posSlot, lenSlot, nextVarSlot, matchFailed);
    } else {
      throw new IllegalArgumentException("Unknown element type: " + elem.getClass());
    }
  }

  /**
   * Generate bounded quantifier matching: \d{1,3} Uses fast-path optimization: if min >= 1, check
   * first character before entering loop. Structure: if (min >= 1) { char c = input.charAt(pos); if
   * (!matchesCharSet(c)) return false; pos++; matchCount = 1; while (pos < len && matchCount < max)
   * { c = input.charAt(pos); if (!matchesCharSet(c)) break; pos++; matchCount++; } } else { //
   * Original loop starting from matchCount = 0 }
   */
  private int generateBoundedQuantifierMatching(
      MethodVisitor mv, BoundedQuantifierElement elem, int posSlot, int lenSlot, int nextVarSlot) {
    int matchCountVar = nextVarSlot++;
    int charVar = nextVarSlot++;

    if (elem.min >= 1) {
      // FAST-PATH: Check first character before entering loop
      // This eliminates loop overhead when first character doesn't match

      // if (pos >= len) return false;
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitVarInsn(ILOAD, lenSlot);
      Label lenOk = new Label();
      mv.visitJumpInsn(IF_ICMPLT, lenOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(lenOk);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);

      // if (!matchesCharSet(c)) return false;
      Label firstCharMatches = new Label();
      generateInlineCharSetCheckWithContinue(
          mv, charVar, elem.charset, elem.negated, firstCharMatches);
      // If we get here, first char didn't match
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(firstCharMatches);

      // First char matched! pos++; matchCount = 1;
      mv.visitIincInsn(posSlot, 1);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, matchCountVar);

      // Continue matching remaining characters (matchCount=1 to max)
      Label loopStart = new Label();
      Label loopEnd = new Label();

      mv.visitLabel(loopStart);

      // if (pos >= len) break;
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitVarInsn(ILOAD, lenSlot);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // if (matchCount >= max) break;
      mv.visitVarInsn(ILOAD, matchCountVar);
      pushInt(mv, elem.max);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);

      // if (!matchesCharSet(c)) break;
      generateInlineCharSetCheck(mv, charVar, elem.charset, elem.negated, loopEnd);

      // pos++; matchCount++;
      mv.visitIincInsn(posSlot, 1);
      mv.visitIincInsn(matchCountVar, 1);

      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(loopEnd);

      // Minimum already satisfied (matchCount >= 1), no check needed

    } else {
      // elem.min == 0 (optional quantifier like {0,3})
      // Use original logic without fast-path

      // int matchCount = 0;
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, matchCountVar);

      // Bounded loop
      Label loopStart = new Label();
      Label loopEnd = new Label();

      mv.visitLabel(loopStart);

      // if (pos >= len) break;
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitVarInsn(ILOAD, lenSlot);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // if (matchCount >= max) break;
      mv.visitVarInsn(ILOAD, matchCountVar);
      pushInt(mv, elem.max);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);

      // if (!matchesCharSet(c)) break;
      generateInlineCharSetCheck(mv, charVar, elem.charset, elem.negated, loopEnd);

      // pos++;
      mv.visitIincInsn(posSlot, 1);

      // matchCount++;
      mv.visitIincInsn(matchCountVar, 1);

      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(loopEnd);

      // if (matchCount < min) return false;
      mv.visitVarInsn(ILOAD, matchCountVar);
      pushInt(mv, elem.min);
      Label minOk = new Label();
      mv.visitJumpInsn(IF_ICMPGE, minOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(minOk);
    }

    return nextVarSlot;
  }

  /**
   * Generate bounded quantifier matching with fail label (for find methods). Uses fast-path
   * optimization: if min >= 1, check first character before entering loop.
   */
  private int generateBoundedQuantifierMatchingWithFail(
      MethodVisitor mv,
      BoundedQuantifierElement elem,
      int posSlot,
      int lenSlot,
      int nextVarSlot,
      Label matchFailed) {
    int matchCountVar = nextVarSlot++;
    int charVar = nextVarSlot++;

    if (elem.min >= 1) {
      // FAST-PATH: Check first character before entering loop
      // This eliminates loop overhead when first character doesn't match

      // if (pos >= len) goto matchFailed;
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitVarInsn(ILOAD, lenSlot);
      mv.visitJumpInsn(IF_ICMPGE, matchFailed);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);

      // if (!matchesCharSet(c)) goto matchFailed;
      generateInlineCharSetCheck(mv, charVar, elem.charset, elem.negated, matchFailed);

      // First char matched! pos++; matchCount = 1;
      mv.visitIincInsn(posSlot, 1);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, matchCountVar);

      // Continue matching remaining characters (matchCount=1 to max)
      Label loopStart = new Label();
      Label loopEnd = new Label();

      mv.visitLabel(loopStart);

      // if (pos >= len) break;
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitVarInsn(ILOAD, lenSlot);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // if (matchCount >= max) break;
      mv.visitVarInsn(ILOAD, matchCountVar);
      pushInt(mv, elem.max);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);

      // if (!matchesCharSet(c)) break;
      generateInlineCharSetCheck(mv, charVar, elem.charset, elem.negated, loopEnd);

      // pos++; matchCount++;
      mv.visitIincInsn(posSlot, 1);
      mv.visitIincInsn(matchCountVar, 1);

      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(loopEnd);

      // Minimum already satisfied (matchCount >= 1), no check needed

    } else {
      // elem.min == 0 (optional quantifier like {0,3})
      // Use original logic without fast-path

      // int matchCount = 0;
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, matchCountVar);

      // Bounded loop
      Label loopStart = new Label();
      Label loopEnd = new Label();

      mv.visitLabel(loopStart);

      // if (pos >= len) break;
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitVarInsn(ILOAD, lenSlot);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // if (matchCount >= max) break;
      mv.visitVarInsn(ILOAD, matchCountVar);
      pushInt(mv, elem.max);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, posSlot);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);

      // if (!matchesCharSet(c)) break;
      generateInlineCharSetCheck(mv, charVar, elem.charset, elem.negated, loopEnd);

      // pos++;
      mv.visitIincInsn(posSlot, 1);

      // matchCount++;
      mv.visitIincInsn(matchCountVar, 1);

      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(loopEnd);

      // if (matchCount < min) goto matchFailed;
      mv.visitVarInsn(ILOAD, matchCountVar);
      pushInt(mv, elem.min);
      mv.visitJumpInsn(IF_ICMPLT, matchFailed);
    }

    return nextVarSlot;
  }

  /** Generate literal matching: '.' */
  private int generateLiteralMatching(
      MethodVisitor mv, BoundedLiteralElement elem, int posSlot, int lenSlot, int nextVarSlot) {
    int charVar = nextVarSlot++;

    // if (pos >= len) return false;
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitVarInsn(ILOAD, lenSlot);
    Label lenOk = new Label();
    mv.visitJumpInsn(IF_ICMPLT, lenOk);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(lenOk);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (c != literal) return false;
    mv.visitVarInsn(ILOAD, charVar);
    pushInt(mv, (int) elem.literal);
    Label matches = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, matches);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(matches);

    // pos++;
    mv.visitIincInsn(posSlot, 1);

    return nextVarSlot;
  }

  /** Generate literal matching with fail label. */
  private int generateLiteralMatchingWithFail(
      MethodVisitor mv,
      BoundedLiteralElement elem,
      int posSlot,
      int lenSlot,
      int nextVarSlot,
      Label matchFailed) {
    int charVar = nextVarSlot++;

    // if (pos >= len) goto matchFailed;
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitVarInsn(ILOAD, lenSlot);
    mv.visitJumpInsn(IF_ICMPGE, matchFailed);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (c != literal) goto matchFailed;
    mv.visitVarInsn(ILOAD, charVar);
    pushInt(mv, (int) elem.literal);
    mv.visitJumpInsn(IF_ICMPNE, matchFailed);

    // pos++;
    mv.visitIincInsn(posSlot, 1);

    return nextVarSlot;
  }

  /** Generate optional literal matching: [- ]? */
  private int generateOptionalMatching(
      MethodVisitor mv, BoundedOptionalElement elem, int posSlot, int lenSlot, int nextVarSlot) {
    int charVar = nextVarSlot++;

    // if (pos >= len) return (already matched, optional is OK);
    Label skipOptional = new Label();
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitVarInsn(ILOAD, lenSlot);
    mv.visitJumpInsn(IF_ICMPGE, skipOptional);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (c == literal) pos++; (optional match)
    mv.visitVarInsn(ILOAD, charVar);
    pushInt(mv, (int) elem.literal);
    mv.visitJumpInsn(IF_ICMPNE, skipOptional);
    mv.visitIincInsn(posSlot, 1);

    mv.visitLabel(skipOptional);

    return nextVarSlot;
  }

  /** Generate optional literal matching with fail label. */
  private int generateOptionalMatchingWithFail(
      MethodVisitor mv,
      BoundedOptionalElement elem,
      int posSlot,
      int lenSlot,
      int nextVarSlot,
      Label matchFailed) {
    int charVar = nextVarSlot++;

    // if (pos >= len) skip (optional is OK);
    Label skipOptional = new Label();
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitVarInsn(ILOAD, lenSlot);
    mv.visitJumpInsn(IF_ICMPGE, skipOptional);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, posSlot);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (c == literal) pos++; (optional match)
    mv.visitVarInsn(ILOAD, charVar);
    pushInt(mv, (int) elem.literal);
    mv.visitJumpInsn(IF_ICMPNE, skipOptional);
    mv.visitIincInsn(posSlot, 1);

    mv.visitLabel(skipOptional);

    return nextVarSlot;
  }

  /**
   * Generate inline character set check (copied from GreedyCharClassBytecodeGenerator). For \d: if
   * (c < '0' || c > '9') goto exitLabel; For [a-z]: if (c < 'a' || c > 'z') goto exitLabel; For
   * multiple ranges: check each range with OR logic
   *
   * @param charVar the variable holding the character to check
   * @param charset the character set to match against
   * @param negated if true, invert the logic
   * @param exitLabel the label to jump to if the check fails
   */
  private void generateInlineCharSetCheck(
      MethodVisitor mv, int charVar, CharSet charset, boolean negated, Label exitLabel) {
    if (charset.isSingleChar()) {
      // Single character check
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      if (negated) {
        // Negated: if (c == target) goto exitLabel;
        mv.visitJumpInsn(IF_ICMPEQ, exitLabel);
      } else {
        // Normal: if (c != target) goto exitLabel;
        mv.visitJumpInsn(IF_ICMPNE, exitLabel);
      }
    } else if (charset.isSimpleRange()) {
      // Single range check
      CharSet.Range range = charset.getSimpleRange();

      if (negated) {
        // Negated range: if (c >= start && c <= end) goto exitLabel;
        Label notInRange = new Label();
        // if (c < start) goto notInRange;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, notInRange);
        // if (c <= end) goto exitLabel;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, exitLabel);
        mv.visitLabel(notInRange);
      } else {
        // Normal range: if (c < start || c > end) goto exitLabel;
        // if (c < start) goto exitLabel;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, exitLabel);
        // if (c > end) goto exitLabel;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPGT, exitLabel);
      }
    } else {
      // Multiple ranges - unroll into OR checks
      if (negated) {
        // Negated: if ANY range matches, exit
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          // if (c < range.start) goto tryNext;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          // if (c <= range.end) goto exitLabel;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, exitLabel);
          mv.visitLabel(tryNext);
        }
        // No range matched - continue (char is outside all ranges, which is what we want for
        // negated)
      } else {
        // Normal: if NO range matches, exit
        Label matches = new Label();
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          // if (c < range.start) goto tryNext;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          // if (c <= range.end) goto matches;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, matches);
          mv.visitLabel(tryNext);
        }
        // No range matched - exit
        mv.visitJumpInsn(GOTO, exitLabel);
        mv.visitLabel(matches);
      }
    }
  }

  /**
   * Generate inline character set check that jumps to continueLabel on SUCCESS (match). This is the
   * inverse of generateInlineCharSetCheck which jumps on failure. Used for fast-path where we want
   * to continue execution after a match, but return false otherwise.
   *
   * @param charVar the variable holding the character to check
   * @param charset the character set to match against
   * @param negated if true, invert the logic
   * @param continueLabel the label to jump to if the check SUCCEEDS
   */
  private void generateInlineCharSetCheckWithContinue(
      MethodVisitor mv, int charVar, CharSet charset, boolean negated, Label continueLabel) {
    if (charset.isSingleChar()) {
      // Single character check
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      if (negated) {
        // Negated: if (c != target) goto continueLabel;
        mv.visitJumpInsn(IF_ICMPNE, continueLabel);
      } else {
        // Normal: if (c == target) goto continueLabel;
        mv.visitJumpInsn(IF_ICMPEQ, continueLabel);
      }
    } else if (charset.isSimpleRange()) {
      // Single range check
      CharSet.Range range = charset.getSimpleRange();

      if (negated) {
        // Negated range: if (c < start || c > end) goto continueLabel;
        // if (c < start) goto continueLabel;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, continueLabel);
        // if (c > end) goto continueLabel;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPGT, continueLabel);
        // Falls through if in range (which means failure for negated)
      } else {
        // Normal range: if (c >= start && c <= end) goto continueLabel;
        Label notInRange = new Label();
        // if (c < start) goto notInRange;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, notInRange);
        // if (c <= end) goto continueLabel;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, continueLabel);
        mv.visitLabel(notInRange);
        // Falls through if not in range (which means failure)
      }
    } else {
      // Multiple ranges - check each and jump to continueLabel if any matches
      if (negated) {
        // Negated: if NO range matches, goto continueLabel
        // If ANY range matches, fall through (failure)
        Label failedNegated = new Label();
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          // if (c < range.start) goto tryNext;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          // if (c <= range.end) goto failedNegated (match found = failure for negated)
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, failedNegated);
          mv.visitLabel(tryNext);
        }
        // No range matched - success for negated
        mv.visitJumpInsn(GOTO, continueLabel);
        mv.visitLabel(failedNegated);
        // Falls through (failure)
      } else {
        // Normal: if ANY range matches, goto continueLabel
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          // if (c < range.start) goto tryNext;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          // if (c <= range.end) goto continueLabel;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, continueLabel);
          mv.visitLabel(tryNext);
        }
        // No range matched - fall through (failure)
      }
    }
  }
}
