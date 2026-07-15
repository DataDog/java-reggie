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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.FixedSequenceInfo;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.LiteralElement;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.OptionalLiteralElement;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.RepetitionElement;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.SequenceElement;
import com.datadoghq.reggie.codegen.ast.AnchorNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates specialized bytecode for fixed-length sequence patterns. Examples: {@code
 * \d{3}-\d{3}-\d{4}}, {@code \d{4}-\d{2}-\d{2}}, {@code (\d{3})-(\d{3})-(\d{4})}
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern \d{3}-\d{3}-\d{4} (phone number)
 * boolean matches(String input) {
 *     if (input == null) return false;
 *
 *     // Check length first (fail fast)
 *     if (input.length() != 12) return false;
 *
 *     // Unrolled checks - no loops
 *     if (!isDigit(input.charAt(0))) return false;
 *     if (!isDigit(input.charAt(1))) return false;
 *     if (!isDigit(input.charAt(2))) return false;
 *     if (input.charAt(3) != '-') return false;
 *     if (!isDigit(input.charAt(4))) return false;
 *     // ... all 12 positions checked inline
 *
 *     return true;
 * }
 * }</pre>
 *
 * <h3>Strategy</h3>
 *
 * <ul>
 *   <li>Check input.length() first (fail fast)
 *   <li>Completely unroll all checks (no loops)
 *   <li>Inline all character tests
 *   <li>Handle optional literals with if-else branches
 * </ul>
 */
public class FixedSequenceBytecodeGenerator {
  private final FixedSequenceInfo info;
  private final int groupCount;

  public FixedSequenceBytecodeGenerator(FixedSequenceInfo info, int nfaGroupCount) {
    this.info = info;
    // Use the group count from pattern analysis, which tracks groups in the sequence
    this.groupCount = info.groupCount;
  }

  /**
   * Generate matches() method: matches(String input) Returns true if input matches the entire
   * pattern.
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // Handle null input
    mv.visitVarInsn(ALOAD, 1);
    Label notNull = new Label();
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // If fixed length, check input.length() == expectedLength
    if (info.totalLength != -1) {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      pushInt(mv, info.totalLength);
      Label lengthOk = new Label();
      mv.visitJumpInsn(IF_ICMPEQ, lengthOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(lengthOk);
    } else {
      // Variable length (has optional parts) - check bounds
      int lenVar = allocator.allocate();
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitVarInsn(ISTORE, lenVar);

      // Check minLength <= length <= maxLength
      mv.visitVarInsn(ILOAD, lenVar);
      pushInt(mv, info.minLength);
      Label minOk = new Label();
      mv.visitJumpInsn(IF_ICMPGE, minOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(minOk);

      mv.visitVarInsn(ILOAD, lenVar);
      pushInt(mv, info.maxLength);
      Label maxOk = new Label();
      mv.visitJumpInsn(IF_ICMPLE, maxOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(maxOk);
    }

    Label returnFalse = new Label();

    // Leading/trailing \b or \B check (info.totalLength is always fixed when a boundary is
    // present - boundary-bearing patterns never carry OptionalLiteralElements, see
    // PatternAnalyzer.detectFixedSequence).
    if (info.leadingBoundary != null) {
      generateBoundaryCheckConstPos(mv, 1, 0, info.leadingBoundary, returnFalse, className);
    }
    if (info.trailingBoundary != null) {
      generateBoundaryCheckConstPos(
          mv, 1, info.totalLength, info.trailingBoundary, returnFalse, className);
    }

    // Generate checks for each element
    int pos = 0;
    for (SequenceElement elem : info.elements) {
      pos = generateElementCheck(mv, elem, pos, 1, returnFalse, allocator);
      if (pos == -1) {
        // Element check failed - already generated return false
        break;
      }
    }

    // All checks passed
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Return false if any check failed
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** True if this pattern has a leading/trailing \b or \B requiring the isBoundary helper. */
  public boolean needsBoundaryHelpers() {
    return info.leadingBoundary != null || info.trailingBoundary != null;
  }

  /**
   * Generates the {@code isWordChar}/{@code isBoundary} private static helpers used by {@code
   * matches()}/{@code findFrom()} to check a leading/trailing \b or \B. Mirrors {@code
   * BitStateBytecodeGenerator}'s implementation exactly (this codebase duplicates such helpers per
   * generator class rather than sharing them). Call at most once per generated class, only when
   * {@link #needsBoundaryHelpers()} is true.
   */
  public void generateBoundaryHelperMethods(ClassWriter cw, String className) {
    generateIsWordCharMethod(cw);
    generateIsBoundaryMethod(cw, className);
  }

  /** {@code private static boolean isWordChar(char c)} - the fixed PCRE/Java \b word-char set. */
  private void generateIsWordCharMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "isWordChar", "(C)Z", null, null);
    mv.visitCode();
    int cVar = 0;
    Label checkUpper = new Label();
    Label checkDigit = new Label();
    Label checkUnderscore = new Label();
    Label yes = new Label();
    Label no = new Label();

    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, 'a');
    mv.visitJumpInsn(IF_ICMPLT, checkUpper);
    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, 'z');
    mv.visitJumpInsn(IF_ICMPLE, yes);

    mv.visitLabel(checkUpper);
    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, 'A');
    mv.visitJumpInsn(IF_ICMPLT, checkDigit);
    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, 'Z');
    mv.visitJumpInsn(IF_ICMPLE, yes);

    mv.visitLabel(checkDigit);
    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, '0');
    mv.visitJumpInsn(IF_ICMPLT, checkUnderscore);
    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, '9');
    mv.visitJumpInsn(IF_ICMPLE, yes);

    mv.visitLabel(checkUnderscore);
    mv.visitVarInsn(ILOAD, cVar);
    pushInt(mv, '_');
    mv.visitJumpInsn(IF_ICMPEQ, yes);
    mv.visitJumpInsn(GOTO, no);

    mv.visitLabel(yes);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(no);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static boolean isBoundary(String input, int p, int n)} - true iff exactly one of
   * {@code (p>0 && isWordChar(input.charAt(p-1)))} / {@code (p<n && isWordChar(input.charAt(p)))}
   * holds.
   */
  private void generateIsBoundaryMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC, "isBoundary", "(Ljava/lang/String;II)Z", null, null);
    mv.visitCode();
    int inputVar = 0;
    int pVar = 1;
    int nVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int beforeVar = allocator.allocate();
    int afterVar = allocator.allocate();

    // before = p > 0 && isWordChar(input.charAt(p - 1));
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, beforeVar);
    Label skipBefore = new Label();
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitJumpInsn(IFLE, skipBefore);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, pVar);
    pushInt(mv, 1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitMethodInsn(INVOKESTATIC, className.replace('.', '/'), "isWordChar", "(C)Z", false);
    mv.visitVarInsn(ISTORE, beforeVar);
    mv.visitLabel(skipBefore);

    // after = p < n && isWordChar(input.charAt(p));
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, afterVar);
    Label skipAfter = new Label();
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitJumpInsn(IF_ICMPGE, skipAfter);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitMethodInsn(INVOKESTATIC, className.replace('.', '/'), "isWordChar", "(C)Z", false);
    mv.visitVarInsn(ISTORE, afterVar);
    mv.visitLabel(skipAfter);

    // return before != after;
    Label returnTrue = new Label();
    mv.visitVarInsn(ILOAD, beforeVar);
    mv.visitVarInsn(ILOAD, afterVar);
    mv.visitJumpInsn(IF_ICMPNE, returnTrue);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** isBoundary(input, posConst, input.length()); jumps to failLabel if the anchor doesn't hold. */
  private void generateBoundaryCheckConstPos(
      MethodVisitor mv,
      int inputVar,
      int posConst,
      AnchorNode.Type anchorType,
      Label failLabel,
      String className) {
    mv.visitVarInsn(ALOAD, inputVar);
    pushInt(mv, posConst);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitMethodInsn(
        INVOKESTATIC, className.replace('.', '/'), "isBoundary", "(Ljava/lang/String;II)Z", false);
    mv.visitJumpInsn(anchorType == AnchorNode.Type.WORD_BOUNDARY ? IFEQ : IFNE, failLabel);
  }

  /** isBoundary(input, posVar + offset, lenVar); jumps to failLabel if the anchor doesn't hold. */
  private void generateBoundaryCheckVarPos(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int offset,
      int lenVar,
      AnchorNode.Type anchorType,
      Label failLabel,
      String className) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    if (offset != 0) {
      pushInt(mv, offset);
      mv.visitInsn(IADD);
    }
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitMethodInsn(
        INVOKESTATIC, className.replace('.', '/'), "isBoundary", "(Ljava/lang/String;II)Z", false);
    mv.visitJumpInsn(anchorType == AnchorNode.Type.WORD_BOUNDARY ? IFEQ : IFNE, failLabel);
  }

  /**
   * Generate check for a single element. Returns new position after element, or -1 if this is an
   * optional element (handled with branches).
   */
  private int generateElementCheck(
      MethodVisitor mv,
      SequenceElement elem,
      int pos,
      int inputVar,
      Label returnFalse,
      LocalVarAllocator allocator) {

    if (elem instanceof LiteralElement) {
      LiteralElement lit = (LiteralElement) elem;
      // if (input.charAt(pos) != 'X') return false;
      mv.visitVarInsn(ALOAD, inputVar);
      pushInt(mv, pos);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) lit.ch);
      mv.visitJumpInsn(IF_ICMPNE, returnFalse);
      return pos + 1;

    } else if (elem instanceof RepetitionElement) {
      RepetitionElement rep = (RepetitionElement) elem;
      // Optimization: Only unroll up to 20 iterations to prevent method bloat
      // For larger counts, generate a runtime loop
      final int UNROLL_THRESHOLD = 20;

      // Allocate charVar once for all iterations
      int charVar = allocator.allocate();

      if (rep.count <= UNROLL_THRESHOLD) {
        // Full unrolling for small counts (optimal performance)
        for (int i = 0; i < rep.count; i++) {
          // char c = input.charAt(pos + i);
          mv.visitVarInsn(ALOAD, inputVar);
          pushInt(mv, pos + i);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, charVar);

          // Inline charset check - jump to returnFalse if doesn't match
          generateInlineCharSetCheck(mv, charVar, returnFalse, rep.charset, rep.negated);
        }
      } else {
        // Runtime loop for large counts (prevents method bloat)
        int checkPosVar = allocator.allocate();
        int endPosVar = allocator.allocate();

        // int checkPos = pos;
        pushInt(mv, pos);
        mv.visitVarInsn(ISTORE, checkPosVar);

        // int endPos = pos + count;
        pushInt(mv, pos + rep.count);
        mv.visitVarInsn(ISTORE, endPosVar);

        // while (checkPos < endPos)
        Label loopStart = new Label();
        Label loopEnd = new Label();
        mv.visitLabel(loopStart);

        // if (checkPos >= endPos) break;
        mv.visitVarInsn(ILOAD, checkPosVar);
        mv.visitVarInsn(ILOAD, endPosVar);
        mv.visitJumpInsn(IF_ICMPGE, loopEnd);

        // char c = input.charAt(checkPos);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, checkPosVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, charVar);

        // Inline charset check - jump to returnFalse if doesn't match
        generateInlineCharSetCheck(mv, charVar, returnFalse, rep.charset, rep.negated);

        // checkPos++;
        mv.visitIincInsn(checkPosVar, 1);
        mv.visitJumpInsn(GOTO, loopStart);

        mv.visitLabel(loopEnd);
      }
      return pos + rep.count;

    } else if (elem instanceof OptionalLiteralElement) {
      OptionalLiteralElement opt = (OptionalLiteralElement) elem;
      // if (input.charAt(pos) == 'X') pos++;
      // This requires tracking pos in a local variable - not implemented yet for simplicity
      // For now, treat as required (will be enhanced later if needed)
      mv.visitVarInsn(ALOAD, inputVar);
      pushInt(mv, pos);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) opt.ch);
      Label skip = new Label();
      mv.visitJumpInsn(IF_ICMPNE, skip);
      // Match - this is okay, continue
      mv.visitLabel(skip);
      return pos + 1; // Simplified: always advance (needs proper optional handling)
    }

    return pos;
  }

  /**
   * Generate inline character set check. For \d: if (c < '0' || c > '9') goto exitLabel; For [a-z]:
   * if (c < 'a' || c > 'z') goto exitLabel; For multiple ranges: check each range with OR logic
   *
   * @param charVar local variable containing the character
   * @param exitLabel label to jump to if check fails
   * @param charset the character set to check against
   * @param negated if true, invert the logic
   */
  private void generateInlineCharSetCheck(
      MethodVisitor mv, int charVar, Label exitLabel, CharSet charset, boolean negated) {
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
   * Generate find() method: find(String input) Returns true if pattern is found anywhere in input.
   */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // return findFrom(input, 0) >= 0;
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    Label found = new Label();
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findFrom() method: findFrom(String input, int start) Returns index where pattern is
   * found, or -1 if not found.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // Simple implementation: try matching at each position
    // TODO: Could optimize with Boyer-Moore or similar for literal prefixes

    mv.visitVarInsn(ALOAD, 1); // input
    Label notNull = new Label();
    mv.visitJumpInsn(IFNONNULL, notNull);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // if (start < 0) start = 0;
    Label startNotNeg = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFGE, startNotNeg);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startNotNeg);

    // int len = input.length();
    int lenVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // if (start > len) return -1;
    Label notPastEnd = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPLE, notPastEnd);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notPastEnd);

    // int i = start;
    int iVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, iVar);

    // Allocate charVar for repetition element checks
    int charVar = allocator.allocate();

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // while (i <= len - minLength)
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.minLength);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // Try to match at position i
    // Check if there's enough room for the pattern
    if (info.totalLength != -1) {
      // Fixed length: check i + totalLength <= len
      mv.visitVarInsn(ILOAD, iVar);
      pushInt(mv, info.totalLength);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, lenVar);
      Label hasRoom = new Label();
      mv.visitJumpInsn(IF_ICMPLE, hasRoom);
      // Not enough room - exit loop
      mv.visitJumpInsn(GOTO, loopEnd);
      mv.visitLabel(hasRoom);
    }

    // Generate inline matching checks for all elements at offset i
    Label tryNext = new Label();

    // Leading/trailing \b or \B check at this candidate position (info.totalLength is always
    // fixed when a boundary is present, see PatternAnalyzer.detectFixedSequence).
    if (info.leadingBoundary != null) {
      generateBoundaryCheckVarPos(mv, 1, iVar, 0, lenVar, info.leadingBoundary, tryNext, className);
    }
    if (info.trailingBoundary != null) {
      generateBoundaryCheckVarPos(
          mv, 1, iVar, info.totalLength, lenVar, info.trailingBoundary, tryNext, className);
    }

    int pos = 0;
    for (SequenceElement elem : info.elements) {
      if (elem instanceof LiteralElement) {
        LiteralElement lit = (LiteralElement) elem;
        // if (input.charAt(i + pos) != 'X') goto tryNext;
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ILOAD, iVar);
        if (pos > 0) {
          pushInt(mv, pos);
          mv.visitInsn(IADD);
        }
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        pushInt(mv, (int) lit.ch);
        mv.visitJumpInsn(IF_ICMPNE, tryNext);
        pos++;
      } else if (elem instanceof RepetitionElement) {
        RepetitionElement rep = (RepetitionElement) elem;
        // Check each character in the repetition
        for (int j = 0; j < rep.count; j++) {
          // char c = input.charAt(i + pos + j);
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, iVar);
          pushInt(mv, pos + j);
          mv.visitInsn(IADD);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, charVar);

          // Inline charset check
          generateInlineCharSetCheck(mv, charVar, tryNext, rep.charset, rep.negated);
        }
        pos += rep.count;
      }
      // TODO: Handle OptionalLiteralElement
    }

    // All checks passed - return i
    mv.visitVarInsn(ILOAD, iVar);
    mv.visitInsn(IRETURN);

    // Didn't match at position i - try next position
    mv.visitLabel(tryNext);
    mv.visitIincInsn(iVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // Additional methods - minimal implementations to avoid VerifyError
  // TODO: Implement proper functionality with MatchResult generation

  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // if (!matches(input)) return null;
    Label matchSuccess = new Label();
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitJumpInsn(IFNE, matchSuccess);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchSuccess);

    // Create MatchResultImpl(input, starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input

    // Calculate group positions (group 0 is the full match)
    int arraySize = groupCount + 1;
    int[] groupStarts = new int[arraySize];
    int[] groupEnds = new int[arraySize];

    // Group 0 is always the full match
    groupStarts[0] = 0;
    groupEnds[0] = info.totalLength != -1 ? info.totalLength : info.minLength;

    // Calculate positions for capturing groups.
    // Multiple elements can share the same group number when a multi-char literal group
    // like (foo) is flattened into individual LiteralElements. Set groupStarts[g] from the
    // FIRST element and groupEnds[g] from the LAST element belonging to group g.
    int currentPos = 0;
    boolean[] groupStartSet = new boolean[arraySize];
    for (SequenceElement elem : info.elements) {
      int groupNum = elem.getGroupNumber();
      if (groupNum > 0) {
        if (!groupStartSet[groupNum]) {
          groupStarts[groupNum] = currentPos;
          groupStartSet[groupNum] = true;
        }
        groupEnds[groupNum] = currentPos + elem.minLength();
      }
      currentPos += elem.minLength();
    }

    // Generate starts array (-1 for groups not present in the match, e.g. NAMED_ONLY holes)
    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    for (int i = 0; i < arraySize; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      pushInt(mv, (i == 0 || groupStartSet[i]) ? groupStarts[i] : -1);
      mv.visitInsn(IASTORE);
    }

    // Generate ends array (-1 for groups not present in the match)
    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    for (int i = 0; i < arraySize; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      if (i == 0 && info.totalLength == -1) {
        // Group 0 end is input.length() for variable-length patterns
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      } else {
        pushInt(mv, (i == 0 || groupStartSet[i]) ? groupEnds[i] : -1);
      }
      mv.visitInsn(IASTORE);
    }

    // groupCount
    pushInt(mv, groupCount);

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

  public void generateFindMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Delegate to findMatchFrom(input, 0)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
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

    // Slot 0 = this, slot 1 = input, slot 2 = start
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // int matchStart = findFrom(input, start);
    int matchStartVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) return null;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(found);

    // Calculate matchEnd
    // For fixed-length: matchEnd = matchStart + totalLength
    // For variable-length: need to scan (simplified for now - use minLength)
    int matchLength = info.totalLength != -1 ? info.totalLength : info.minLength;

    // int matchEnd = matchStart + matchLength;
    int matchEndVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, matchStartVar);
    pushInt(mv, matchLength);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, matchEndVar);

    // Create MatchResultImpl(input, starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input

    // Calculate group positions relative to matchStart
    int arraySize = groupCount + 1;

    // Calculate relative positions for capturing groups (relative offsets)
    int[] groupStartOffsets = new int[arraySize];
    int[] groupEndOffsets = new int[arraySize];

    // Group 0 offsets
    groupStartOffsets[0] = 0;
    groupEndOffsets[0] = matchLength;

    // Calculate offsets for capturing groups.
    // Multiple elements can share the same group number when (foo) is flattened to literals.
    int currentPos = 0;
    boolean[] groupStartSet = new boolean[arraySize];
    for (SequenceElement elem : info.elements) {
      int groupNum = elem.getGroupNumber();
      if (groupNum > 0) {
        if (!groupStartSet[groupNum]) {
          groupStartOffsets[groupNum] = currentPos;
          groupStartSet[groupNum] = true;
        }
        groupEndOffsets[groupNum] = currentPos + elem.minLength();
      }
      currentPos += elem.minLength();
    }

    // Generate starts array (matchStart + offset for each group; -1 for holes)
    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    for (int i = 0; i < arraySize; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      if (i == 0 || groupStartSet[i]) {
        // starts[i] = matchStart + groupStartOffsets[i]
        mv.visitVarInsn(ILOAD, matchStartVar);
        if (groupStartOffsets[i] > 0) {
          pushInt(mv, groupStartOffsets[i]);
          mv.visitInsn(IADD);
        }
      } else {
        pushInt(mv, -1); // non-participating group (e.g. stripped by NAMED_ONLY)
      }
      mv.visitInsn(IASTORE);
    }

    // Generate ends array (matchStart + offset for each group; -1 for holes)
    pushInt(mv, arraySize);
    mv.visitIntInsn(NEWARRAY, T_INT);
    for (int i = 0; i < arraySize; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      if (i == 0 || groupStartSet[i]) {
        // ends[i] = matchStart + groupEndOffsets[i]
        mv.visitVarInsn(ILOAD, matchStartVar);
        pushInt(mv, groupEndOffsets[i]);
        mv.visitInsn(IADD);
      } else {
        pushInt(mv, -1); // non-participating group
      }
      mv.visitInsn(IASTORE);
    }

    // groupCount
    pushInt(mv, groupCount);

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

  /**
   * Generate findBoundsFrom() method - allocation-free alternative to findMatchFrom(). Signature:
   * public boolean findBoundsFrom(String input, int start, int[] bounds)
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start, slot 3 = bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    // int matchStart = findFrom(input, start);
    int matchStartVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) return false;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(found);

    // Calculate matchEnd
    // For fixed-length: matchEnd = matchStart + totalLength
    // For variable-length: use minLength (simplified, matches findMatchFrom behavior)
    int matchLength = info.totalLength != -1 ? info.totalLength : info.minLength;

    // bounds[0] = matchStart;
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);

    // bounds[1] = matchStart + matchLength;
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, matchStartVar);
    pushInt(mv, matchLength);
    mv.visitInsn(IADD);
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Delegate to matches() with substring:
    // return matches(input.subSequence(start, end).toString());
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input (CharSequence)
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
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

    // Delegate to match() with substring:
    // return match(input.subSequence(start, end).toString());
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input (CharSequence)
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
