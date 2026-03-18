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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.GreedyCharClassInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates highly optimized bytecode for greedy character class patterns.
 *
 * <h3>Pattern Types</h3>
 *
 * Single capturing group with char class + greedy quantifier: {@code (\d+)}, {@code ([a-z]*)},
 * {@code (\w+)}, {@code ([0-9]{2,5})}
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern (\d+)
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int pos = 0;
 *     int len = input.length();
 *
 *     // Greedy loop: consume all matching chars
 *     while (pos < len && isDigit(input.charAt(pos))) {
 *         pos++;
 *     }
 *
 *     // Check min matches and consumed all input
 *     return pos >= minMatches && pos == len;
 * }
 *
 * MatchResult match(String input) {
 *     if (!matches(input)) return null;
 *
 *     // Group 0 = entire match, Group 1 = captured content
 *     int[] starts = {0, 0};
 *     int[] ends = {input.length(), input.length()};
 *     return new MatchResultImpl(input, starts, ends, 1);
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li>Simple while loop with inline char checks
 *   <li>NO state machines, NO switches
 *   <li>Zero allocations in hot path
 *   <li>SWAR optimization for bulk character checking when applicable
 * </ul>
 */
public class GreedyCharClassBytecodeGenerator {

  private final CharSet charset;
  private final boolean negated;
  private final int minMatches;
  private final int maxMatches; // -1 for unbounded
  private final int groupCount;

  public GreedyCharClassBytecodeGenerator(GreedyCharClassInfo info, int groupCount) {
    this.charset = info.charset;
    this.negated = info.negated;
    this.minMatches = info.minMatches;
    this.maxMatches = info.maxMatches;
    this.groupCount = groupCount;
  }

  /**
   * Analyze greedy character set to determine if SWAR optimization is applicable. Uses compile-time
   * pattern analysis to generate specialized bytecode.
   *
   * @return SWAROptimization for the greedy charset, or null if not optimizable
   */
  private com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization analyzeGreedyCharSet() {
    return SWARPatternAnalyzer.analyzeForSWAR(this.charset, this.negated);
  }

  /**
   * Generate matches() method - boolean check only. Structure: int pos = 0; while (pos < len &&
   * matchesCharset(input.charAt(pos))) pos++; return pos >= minMatches && pos == len;
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int cVar = allocator.allocate();

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

    // Greedy matching loop: while (pos < len && matchesCharset(ch)) pos++;
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Check maxMatches bound if applicable
    if (maxMatches > 0) {
      // if (pos >= maxMatches) break;
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, maxMatches);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);

    // Inline charset check - if doesn't match, break
    generateInlineCharSetCheck(mv, cVar, loopEnd, negated);

    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check minMatches constraint: if (pos < minMatches) return false;
    if (minMatches > 0) {
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, minMatches);
      Label minOk = new Label();
      mv.visitJumpInsn(IF_ICMPGE, minOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(minOk);
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

  /**
   * Generate inline character set check. For \d: if (c < '0' || c > '9') goto exitLoop; For [a-z]:
   * if (c < 'a' || c > 'z') goto exitLoop; For multiple ranges: check each range with OR logic
   *
   * @param negated if true, invert the logic
   */
  private void generateInlineCharSetCheck(
      MethodVisitor mv, int charVar, Label exitLoop, boolean negated) {
    if (charset.isSingleChar()) {
      // Single character check
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      if (negated) {
        // Negated: if (c == target) goto exitLoop;
        mv.visitJumpInsn(IF_ICMPEQ, exitLoop);
      } else {
        // Normal: if (c != target) goto exitLoop;
        mv.visitJumpInsn(IF_ICMPNE, exitLoop);
      }
    } else if (charset.isSimpleRange()) {
      // Single range check
      CharSet.Range range = charset.getSimpleRange();

      if (negated) {
        // Negated range: if (c >= start && c <= end) goto exitLoop;
        Label notInRange = new Label();
        // if (c < start) goto notInRange;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, notInRange);
        // if (c <= end) goto exitLoop;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, exitLoop);
        mv.visitLabel(notInRange);
      } else {
        // Normal range: if (c < start || c > end) goto exitLoop;
        // if (c < start) goto exitLoop;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, exitLoop);
        // if (c > end) goto exitLoop;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPGT, exitLoop);
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
          // if (c <= range.end) goto exitLoop;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, exitLoop);
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
        // No range matched - exit loop
        mv.visitJumpInsn(GOTO, exitLoop);
        mv.visitLabel(matches);
      }
    }
  }

  /** Generate find() method - delegates to findFrom(). */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // return findFrom(input, 0) >= 0;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);

    Label returnTrue = new Label();
    Label end = new Label();
    mv.visitJumpInsn(IFGE, returnTrue);
    mv.visitInsn(ICONST_0);
    mv.visitJumpInsn(GOTO, end);
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitLabel(end);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findFrom() method - scan for first matching position. */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = start
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int inputVar = 1;
    int startVar = 2;
    int lenVar = allocator.allocate();

    // if (input == null || start < 0 || start > input.length()) return -1;
    Label checksPass = new Label();
    Label returnMinusOne = new Label();

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNULL, returnMinusOne);

    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFLT, returnMinusOne);

    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPLE, checksPass);

    mv.visitLabel(returnMinusOne);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitLabel(checksPass);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // OPTIMIZATION 1: Single character - use String.indexOf()
    if (!negated && charset.isSingleChar()) {
      // Optimization: start = input.indexOf(ch, start);
      mv.visitVarInsn(ALOAD, inputVar);
      pushInt(mv, (int) charset.getSingleChar());
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
      mv.visitInsn(IRETURN); // Return result directly (indexOf returns -1 if not found)
    }
    // OPTIMIZATION 2: Analyze charset at compile time for SWAR
    else if (analyzeGreedyCharSet() != null) {
      com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization swarOpt = analyzeGreedyCharSet();
      // SWAR OPTIMIZATION: Use pattern-specific optimized search
      // Note: SWAR optimization uses parameter slots directly
      swarOpt.generateFindNextBytecode(mv, inputVar, startVar, lenVar);

      // if (start < 0) return -1; (no match found)
      Label found = new Label();
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitJumpInsn(IFGE, found);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);

      // Match found - return position
      mv.visitLabel(found);
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitInsn(IRETURN);
    } else {
      // STANDARD: Character-by-character search loop
      int cVar = allocator.allocate();
      Label searchLoop = new Label();
      Label searchEnd = new Label();

      mv.visitLabel(searchLoop);
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, searchEnd);

      // Try match at position i: check first char
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, cVar);

      // Quick check: does first char match charset?
      Label tryNext = new Label();
      generateInlineCharSetCheck(mv, cVar, tryNext, negated);

      // First char matches - this is a candidate position
      // Return this position (match will be validated by match() method later)
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitInsn(IRETURN);

      mv.visitLabel(tryNext);
      // i++;
      mv.visitIincInsn(startVar, 1);
      mv.visitJumpInsn(GOTO, searchLoop);

      mv.visitLabel(searchEnd);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate match() method - returns MatchResult with group positions. */
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
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitJumpInsn(IFNE, matchSuccess);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchSuccess);

    // new MatchResultImpl(input, new int[]{0, 0}, new int[]{input.length(), input.length()},
    // groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input

    // starts array: {0, 0}  (group 0 and group 1 both start at 0)
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    if (groupCount > 0) {
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IASTORE);
    }

    // ends array: {input.length(), input.length()}
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);
    if (groupCount > 0) {
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitInsn(IASTORE);
    }

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

  /** Generate findMatch() method - delegates to findMatchFrom(). */
  public void generateFindMatchMethod(ClassWriter cw, String className) {
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
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findMatchFrom() method - find and return MatchResult with group positions. */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = start
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int inputVar = 1;
    int startVar = 2;
    int matchStartVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int cVar = allocator.allocate();
    int matchLenVar = allocator.allocate();

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
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

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = matchStart;
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Greedy scan from matchStart
    Label scanLoop = new Label();
    Label scanEnd = new Label();

    mv.visitLabel(scanLoop);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, scanEnd);

    // Check maxMatches bound if applicable
    if (maxMatches > 0) {
      // if ((pos - matchStart) >= maxMatches) break;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, matchStartVar);
      mv.visitInsn(ISUB);
      pushInt(mv, maxMatches);
      mv.visitJumpInsn(IF_ICMPGE, scanEnd);
    }

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);

    // Inline charset check - if doesn't match, break
    generateInlineCharSetCheck(mv, cVar, scanEnd, negated);

    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(scanEnd);

    // int matchEnd = pos;
    // int matchLen = matchEnd - matchStart;
    mv.visitVarInsn(ILOAD, posVar); // matchEnd
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, matchLenVar);

    // Check minMatches constraint
    if (minMatches > 0) {
      // if (matchLen < minMatches) return null;
      mv.visitVarInsn(ILOAD, matchLenVar);
      pushInt(mv, minMatches);
      Label minOk = new Label();
      mv.visitJumpInsn(IF_ICMPGE, minOk);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(minOk);
    }

    // new MatchResultImpl(input, new int[]{matchStart, matchStart}, new int[]{matchEnd, matchEnd},
    // groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);

    // starts array: {matchStart, matchStart}
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);
    if (groupCount > 0) {
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ILOAD, matchStartVar);
      mv.visitInsn(IASTORE);
    }

    // ends array: {matchEnd, matchEnd}
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar); // matchEnd (pos)
    mv.visitInsn(IASTORE);
    if (groupCount > 0) {
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ILOAD, posVar); // matchEnd (pos)
      mv.visitInsn(IASTORE);
    }

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

  /** Generate matchesBounded() method - boolean check on bounded region. */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Delegate to matches() for now - can optimize later if needed
    // return matches(input.subSequence(start, end).toString());
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
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

  /** Generate matchBounded() method - returns MatchResult on bounded region. */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Delegate to match() for now
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
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

  /**
   * Generate findBoundsFrom() method - allocation-free alternative to findMatchFrom(). Stores match
   * start/end positions in the provided bounds array instead of allocating MatchResult. Signature:
   * public boolean findBoundsFrom(String input, int start, int[] bounds)
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = start, 3 = bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int inputVar = 1;
    int startVar = 2;
    int boundsVar = 3;
    int matchStartVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int cVar = allocator.allocate();
    int matchLenVar = allocator.allocate();

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
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

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = matchStart;
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Greedy scan from matchStart
    Label scanLoop = new Label();
    Label scanEnd = new Label();

    mv.visitLabel(scanLoop);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, scanEnd);

    // Check maxMatches bound if applicable
    if (maxMatches > 0) {
      // if ((pos - matchStart) >= maxMatches) break;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, matchStartVar);
      mv.visitInsn(ISUB);
      pushInt(mv, maxMatches);
      mv.visitJumpInsn(IF_ICMPGE, scanEnd);
    }

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);

    // Inline charset check - if doesn't match, break
    generateInlineCharSetCheck(mv, cVar, scanEnd, negated);

    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(scanEnd);

    // int matchEnd = pos;
    // int matchLen = matchEnd - matchStart;
    mv.visitVarInsn(ILOAD, posVar); // matchEnd
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, matchLenVar);

    // Check minMatches constraint
    if (minMatches > 0) {
      // if (matchLen < minMatches) return false;
      mv.visitVarInsn(ILOAD, matchLenVar);
      pushInt(mv, minMatches);
      Label minOk = new Label();
      mv.visitJumpInsn(IF_ICMPGE, minOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(minOk);
    }

    // bounds[0] = matchStart;
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);

    // bounds[1] = matchEnd;
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, posVar); // matchEnd (pos)
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
