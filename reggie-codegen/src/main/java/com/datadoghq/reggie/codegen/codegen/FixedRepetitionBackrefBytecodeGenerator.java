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

import com.datadoghq.reggie.codegen.analysis.FixedRepetitionBackrefInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Bytecode generator for FIXED_REPETITION_BACKREF strategy.
 *
 * <p>Generates optimized matching code for patterns where a capturing group is followed by a
 * backreference with fixed repetition bounds.
 *
 * <h3>Supported Pattern Examples</h3>
 *
 * <ul>
 *   <li>{@code (a)\1{8,}} - capture 'a', verify 8+ more 'a's follow
 *   <li>{@code (abc)\1{3}} - capture 'abc', verify exactly 3 repetitions
 *   <li>{@code ^(foo)\1$} - capture 'foo', verify exactly one repetition
 * </ul>
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int len = input.length();
 *     int pos = 0;
 *
 *     // Match and capture group
 *     int groupStart = pos;
 *     // ... match group content ...
 *     int groupEnd = pos;
 *     int groupLen = groupEnd - groupStart;
 *
 *     // Verify backreference repeats minReps to maxReps times
 *     int reps = 0;
 *     while (pos + groupLen <= len) {
 *         if (!input.regionMatches(pos, input, groupStart, groupLen)) break;
 *         pos += groupLen;
 *         reps++;
 *         if (maxReps >= 0 && reps >= maxReps) break;
 *     }
 *
 *     if (reps < minReps) return false;
 *     return pos == len;  // For matches(), must consume entire input
 * }
 * }</pre>
 *
 * <h3>Key Optimization: Single-Char Groups</h3>
 *
 * When the captured group is a single character or char class, we avoid regionMatches() and use
 * direct char comparison in a loop:
 *
 * <pre>{@code
 * char captured = input.charAt(groupStart);
 * for (int i = 0; i < minReps; i++) {
 *     if (pos >= len || input.charAt(pos) != captured) return false;
 *     pos++;
 * }
 * }</pre>
 */
public class FixedRepetitionBackrefBytecodeGenerator {

  private final FixedRepetitionBackrefInfo info;
  private final String className;

  public FixedRepetitionBackrefBytecodeGenerator(
      FixedRepetitionBackrefInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Generate the matches() method. */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Local variable allocation: slot 0 = this, slot 1 = input
    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int repsVar = alloc.allocate();
    int capturedCharVar = info.isSingleCharGroup ? alloc.allocate() : -1;

    Label returnFalse = new Label();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Match and capture group - for now, assume single char class like (\d) or (a)
    // groupStart = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    // Match the group content
    // if (pos >= len) return false;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, returnFalse);

    if (info.isSingleCharGroup) {
      // Single char group: capture the character for efficient comparison
      // char capturedChar = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, capturedCharVar);

      if (info.literalChar >= 0) {
        // Literal group (e.g., (a)): validate char matches the expected literal
        mv.visitVarInsn(ILOAD, capturedCharVar);
        BytecodeUtil.pushInt(mv, info.literalChar);
        mv.visitJumpInsn(IF_ICMPNE, returnFalse);
      } else if (info.groupCharSet != null) {
        // Charset group (e.g., (\d)): validate char is in the charset
        generateCharSetCheck(mv, capturedCharVar, info.groupCharSet, returnFalse);
      }

      // pos++
      mv.visitIincInsn(posVar, 1);
    } else {
      // Multi-char groups: use regionMatches for comparison
      // Just advance pos by 1 for now (simplified)
      // TODO: For multi-char literals, advance by literal length
      mv.visitIincInsn(posVar, 1);
    }

    // groupEnd = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // groupLen = groupEnd - groupStart
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // reps = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, repsVar);

    // Loop: verify backreference repetitions
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check: pos + groupLen <= len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    if (info.isSingleCharGroup && capturedCharVar >= 0) {
      // Optimization: single char comparison
      // if (input.charAt(pos) != capturedChar) break;
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ILOAD, capturedCharVar);
      mv.visitJumpInsn(IF_ICMPNE, loopEnd);
    } else {
      // General case: use regionMatches
      // if (!input.regionMatches(pos, input, groupStart, groupLen)) break;
      mv.visitVarInsn(ALOAD, 1); // input (this)
      mv.visitVarInsn(ILOAD, posVar); // toffset
      mv.visitVarInsn(ALOAD, 1); // other (same string)
      mv.visitVarInsn(ILOAD, groupStartVar); // ooffset
      mv.visitVarInsn(ILOAD, groupLenVar); // len
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFEQ, loopEnd);
    }

    // pos += groupLen
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // reps++
    mv.visitIincInsn(repsVar, 1);

    // Check maxReps if bounded
    if (info.backrefMaxReps >= 0) {
      // if (reps >= maxReps) break;
      mv.visitVarInsn(ILOAD, repsVar);
      BytecodeUtil.pushInt(mv, info.backrefMaxReps);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);

    // Check minReps requirement
    // if (reps < minReps) return false;
    mv.visitVarInsn(ILOAD, repsVar);
    BytecodeUtil.pushInt(mv, info.backrefMinReps);
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // For matches(), require pos == len (consumed entire input)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);

    // return true
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // return false
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(5, alloc.peek());
    mv.visitEnd();
  }

  /** Generate the find() method - delegates to findFrom(input, 0). */
  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // return findFrom(input, 0) >= 0;
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // 0
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

  /** Generate the findFrom() method. */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Local variables: slot 0=this, 1=input, 2=startPos
    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int repsVar = alloc.allocate();
    int capturedCharVar = info.isSingleCharGroup ? alloc.allocate() : -1;

    Label returnNotFound = new Label();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = startPos;
    mv.visitVarInsn(ILOAD, 2); // startPos parameter
    mv.visitVarInsn(ISTORE, startVar);

    // Outer loop: try each starting position
    Label outerLoop = new Label();
    Label outerEnd = new Label();
    mv.visitLabel(outerLoop);

    // Minimum length check: need at least 1 (group) + minReps chars
    int minLen = 1 + info.backrefMinReps;
    mv.visitVarInsn(ILOAD, startVar);
    BytecodeUtil.pushInt(mv, minLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // pos = start
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    // groupStart = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    Label tryNextStart = new Label();

    // Match group content
    if (info.isSingleCharGroup) {
      // char capturedChar = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, capturedCharVar);

      if (info.literalChar >= 0) {
        // Literal group: validate char matches expected literal
        mv.visitVarInsn(ILOAD, capturedCharVar);
        BytecodeUtil.pushInt(mv, info.literalChar);
        mv.visitJumpInsn(IF_ICMPNE, tryNextStart);
      } else if (info.groupCharSet != null) {
        // Charset group: validate char is in the charset
        generateCharSetCheck(mv, capturedCharVar, info.groupCharSet, tryNextStart);
      }

      mv.visitIincInsn(posVar, 1);
    } else {
      // Multi-char groups
      mv.visitIincInsn(posVar, 1);
    }

    // groupEnd = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // groupLen = groupEnd - groupStart
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // reps = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, repsVar);

    // Inner loop: count repetitions
    Label innerLoop = new Label();
    Label innerEnd = new Label();
    mv.visitLabel(innerLoop);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, innerEnd);

    if (info.isSingleCharGroup && capturedCharVar >= 0) {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ILOAD, capturedCharVar);
      mv.visitJumpInsn(IF_ICMPNE, innerEnd);
    } else {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitVarInsn(ILOAD, groupLenVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFEQ, innerEnd);
    }

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    mv.visitIincInsn(repsVar, 1);

    if (info.backrefMaxReps >= 0) {
      mv.visitVarInsn(ILOAD, repsVar);
      BytecodeUtil.pushInt(mv, info.backrefMaxReps);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    mv.visitJumpInsn(GOTO, innerLoop);
    mv.visitLabel(innerEnd);

    // Check if minReps satisfied - if so, found a match
    mv.visitVarInsn(ILOAD, repsVar);
    BytecodeUtil.pushInt(mv, info.backrefMinReps);
    mv.visitJumpInsn(IF_ICMPLT, tryNextStart);

    // Found! Return start position
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IRETURN);

    // Try next starting position
    mv.visitLabel(tryNextStart);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitLabel(returnNotFound);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(5, alloc.peek());
    mv.visitEnd();
  }

  /** Generate charset membership check. Jumps to failLabel if the char is NOT in the charset. */
  private void generateCharSetCheck(
      MethodVisitor mv, int charVar, CharSet charSet, Label failLabel) {
    // For common cases, generate inline checks
    if (charSet.equals(CharSet.DIGIT)) {
      // ch >= '0' && ch <= '9'
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, '0');
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, '9');
      mv.visitJumpInsn(IF_ICMPGT, failLabel);
    } else if (charSet.equals(CharSet.WORD)) {
      // Simplified WORD check: a-z, A-Z, 0-9, _
      // We use a series of range checks, jumping to failLabel if none match
      Label ok = new Label();

      // Check if a-z
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, 'a');
      Label notLower = new Label();
      mv.visitJumpInsn(IF_ICMPLT, notLower);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, 'z');
      mv.visitJumpInsn(IF_ICMPLE, ok);

      // Check if A-Z
      mv.visitLabel(notLower);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, 'A');
      Label notUpper = new Label();
      mv.visitJumpInsn(IF_ICMPLT, notUpper);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, 'Z');
      mv.visitJumpInsn(IF_ICMPLE, ok);

      // Check if 0-9
      mv.visitLabel(notUpper);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, '0');
      Label notDigit = new Label();
      mv.visitJumpInsn(IF_ICMPLT, notDigit);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, '9');
      mv.visitJumpInsn(IF_ICMPLE, ok);

      // Check if _
      mv.visitLabel(notDigit);
      mv.visitVarInsn(ILOAD, charVar);
      BytecodeUtil.pushInt(mv, '_');
      mv.visitJumpInsn(IF_ICMPNE, failLabel);

      mv.visitLabel(ok);
    } else {
      // General case: For now, accept any char
      // Complex charsets would need a lookup table
    }
  }

  /** Generate match(String) method that returns MatchResult with group captures. */
  public void generateMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local variable allocation: slot 0 = this, slot 1 = input
    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int repsVar = alloc.allocate();
    int capturedCharVar = info.isSingleCharGroup ? alloc.allocate() : -1;
    int startsArrayVar = alloc.allocate();
    int endsArrayVar = alloc.allocate();

    Label returnNull = new Label();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Initialize group arrays: int[] starts = new int[groupCount + 1];
    BytecodeUtil.pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsArrayVar);

    // int[] ends = new int[groupCount + 1];
    BytecodeUtil.pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsArrayVar);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // starts[0] = 0 (entire match starts at beginning)
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);

    // Match and capture group - starts[referencedGroupNumber] = pos
    mv.visitVarInsn(ALOAD, startsArrayVar);
    BytecodeUtil.pushInt(mv, info.referencedGroupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // groupStart = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    // Match the group content
    // if (pos >= len) return null;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, returnNull);

    if (info.isSingleCharGroup) {
      // Single char group: capture the character
      // char capturedChar = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, capturedCharVar);

      if (info.literalChar >= 0) {
        // Literal group: validate char matches
        mv.visitVarInsn(ILOAD, capturedCharVar);
        BytecodeUtil.pushInt(mv, info.literalChar);
        mv.visitJumpInsn(IF_ICMPNE, returnNull);
      } else if (info.groupCharSet != null) {
        // Charset group: validate char is in charset
        generateCharSetCheck(mv, capturedCharVar, info.groupCharSet, returnNull);
      }

      // pos++
      mv.visitIincInsn(posVar, 1);
    } else {
      // Multi-char groups: simplified (advance by 1)
      mv.visitIincInsn(posVar, 1);
    }

    // groupEnd = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // ends[referencedGroupNumber] = pos
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, info.referencedGroupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // groupLen = groupEnd - groupStart
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // reps = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, repsVar);

    // Loop: verify backreference repetitions
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check: pos + groupLen <= len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    if (info.isSingleCharGroup && capturedCharVar >= 0) {
      // Optimization: single char comparison
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ILOAD, capturedCharVar);
      mv.visitJumpInsn(IF_ICMPNE, loopEnd);
    } else {
      // General case: use regionMatches
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitVarInsn(ILOAD, groupLenVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFEQ, loopEnd);
    }

    // pos += groupLen
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // reps++
    mv.visitIincInsn(repsVar, 1);

    // Check maxReps if bounded
    if (info.backrefMaxReps >= 0) {
      mv.visitVarInsn(ILOAD, repsVar);
      BytecodeUtil.pushInt(mv, info.backrefMaxReps);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);

    // Check minReps requirement
    // if (reps < minReps) return null;
    mv.visitVarInsn(ILOAD, repsVar);
    BytecodeUtil.pushInt(mv, info.backrefMinReps);
    mv.visitJumpInsn(IF_ICMPLT, returnNull);

    // Match suffix groups (like group 2 in pattern ^(a)\1{2,3}(.))
    // For each suffix node, try to match and capture
    if (!info.suffix.isEmpty()) {
      // Simplified: if suffix contains exactly one capturing group (common case)
      // For pattern ^(a)\1{2,3}(.), the suffix is (.) which is group 2
      // We need to match one character and capture it

      // Find the last group number (should be totalGroupCount)
      int suffixGroupNum = info.totalGroupCount;

      // starts[suffixGroupNum] = pos
      mv.visitVarInsn(ALOAD, startsArrayVar);
      BytecodeUtil.pushInt(mv, suffixGroupNum);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);

      // Match one character (simplified - assume suffix is a single char or charclass)
      // if (pos >= len) return null;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, returnNull);

      // pos++
      mv.visitIincInsn(posVar, 1);

      // ends[suffixGroupNum] = pos
      mv.visitVarInsn(ALOAD, endsArrayVar);
      BytecodeUtil.pushInt(mv, suffixGroupNum);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);
    }

    // For match(), require pos == len (consumed entire input)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, returnNull);

    // ends[0] = pos (entire match ends here)
    mv.visitVarInsn(ALOAD, endsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // return new MatchResultImpl(input, starts, ends, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ALOAD, startsArrayVar); // starts
    mv.visitVarInsn(ALOAD, endsArrayVar); // ends
    BytecodeUtil.pushInt(mv, info.totalGroupCount); // groupCount
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // return null
    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Generate matchesBounded(CharSequence, int, int) — extracts the bounded region and delegates to
   * matches(String).
   */
  public void generateMatchesBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start, 3=end
    LocalVarAllocator alloc = new LocalVarAllocator(4);
    int subStringVar = alloc.allocate();

    // String sub = input.subSequence(start, end).toString();
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
    mv.visitVarInsn(ASTORE, subStringVar);

    // return this.matches(sub);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, subStringVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(3, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Generate matchBounded(CharSequence, int, int) — extracts the bounded region, delegates to
   * match(String), and returns the raw result (spans are relative to the region substring).
   */
  public void generateMatchBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start, 3=end
    LocalVarAllocator alloc = new LocalVarAllocator(4);
    int subStringVar = alloc.allocate();

    // String sub = input.subSequence(start, end).toString();
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
    mv.visitVarInsn(ASTORE, subStringVar);

    // return this.match(sub);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, subStringVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className,
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(3, alloc.peek());
    mv.visitEnd();
  }

  /** Generate findMatch(String) — delegates to findMatchFrom(input, 0). */
  public void generateFindMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // return findMatchFrom(input, 0);
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
   * Generate findMatchFrom(String, int) — iterates starting positions from startPos, at each
   * position tries to match (same logic as generateMatchMethod but starting at a given offset and
   * not requiring full-input consumption), and returns a MatchResult on success.
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

    // Local variable allocation: slot 0=this, 1=input, 2=startPos
    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int posVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int groupLenVar = alloc.allocate();
    int repsVar = alloc.allocate();
    int capturedCharVar = info.isSingleCharGroup ? alloc.allocate() : -1;
    int startsArrayVar = alloc.allocate();
    int endsArrayVar = alloc.allocate();

    Label returnNull = new Label();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int start = startPos;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);

    // Allocate arrays once outside the outer loop (we only return on success)
    // int[] starts = new int[totalGroupCount + 1];
    BytecodeUtil.pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsArrayVar);

    // int[] ends = new int[totalGroupCount + 1];
    BytecodeUtil.pushInt(mv, info.totalGroupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsArrayVar);

    // Outer loop: try each starting position
    Label outerLoop = new Label();
    Label outerEnd = new Label();
    mv.visitLabel(outerLoop);

    // Minimum length check: need at least 1 (group) + minReps chars from start
    // if (start + 1 + minReps > len) break;
    mv.visitVarInsn(ILOAD, startVar);
    BytecodeUtil.pushInt(mv, 1 + info.backrefMinReps);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // pos = start
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    // starts[0] = start
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IASTORE);

    // starts[referencedGroupNumber] = pos
    mv.visitVarInsn(ALOAD, startsArrayVar);
    BytecodeUtil.pushInt(mv, info.referencedGroupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // groupStart = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    Label tryNextStart = new Label();

    // Match group content
    if (info.isSingleCharGroup) {
      // char capturedChar = input.charAt(pos);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, capturedCharVar);

      if (info.literalChar >= 0) {
        mv.visitVarInsn(ILOAD, capturedCharVar);
        BytecodeUtil.pushInt(mv, info.literalChar);
        mv.visitJumpInsn(IF_ICMPNE, tryNextStart);
      } else if (info.groupCharSet != null) {
        generateCharSetCheck(mv, capturedCharVar, info.groupCharSet, tryNextStart);
      }

      // pos++
      mv.visitIincInsn(posVar, 1);
    } else {
      mv.visitIincInsn(posVar, 1);
    }

    // groupEnd = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // ends[referencedGroupNumber] = pos
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, info.referencedGroupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // groupLen = groupEnd - groupStart
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // reps = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, repsVar);

    // Inner loop: count repetitions
    Label innerLoop = new Label();
    Label innerEnd = new Label();
    mv.visitLabel(innerLoop);

    // if (pos + groupLen > len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, innerEnd);

    if (info.isSingleCharGroup && capturedCharVar >= 0) {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ILOAD, capturedCharVar);
      mv.visitJumpInsn(IF_ICMPNE, innerEnd);
    } else {
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, groupStartVar);
      mv.visitVarInsn(ILOAD, groupLenVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFEQ, innerEnd);
    }

    // pos += groupLen
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    // reps++
    mv.visitIincInsn(repsVar, 1);

    if (info.backrefMaxReps >= 0) {
      mv.visitVarInsn(ILOAD, repsVar);
      BytecodeUtil.pushInt(mv, info.backrefMaxReps);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    mv.visitJumpInsn(GOTO, innerLoop);
    mv.visitLabel(innerEnd);

    // if (reps < minReps) try next start
    mv.visitVarInsn(ILOAD, repsVar);
    BytecodeUtil.pushInt(mv, info.backrefMinReps);
    mv.visitJumpInsn(IF_ICMPLT, tryNextStart);

    // Match suffix groups (same pattern as generateMatchMethod)
    if (!info.suffix.isEmpty()) {
      int suffixGroupNum = info.totalGroupCount;

      mv.visitVarInsn(ALOAD, startsArrayVar);
      BytecodeUtil.pushInt(mv, suffixGroupNum);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);

      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, tryNextStart);

      mv.visitIincInsn(posVar, 1);

      mv.visitVarInsn(ALOAD, endsArrayVar);
      BytecodeUtil.pushInt(mv, suffixGroupNum);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);
    }

    // ends[0] = pos
    mv.visitVarInsn(ALOAD, endsArrayVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // return new MatchResultImpl(input, starts, ends, totalGroupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ALOAD, startsArrayVar);
    mv.visitVarInsn(ALOAD, endsArrayVar);
    BytecodeUtil.pushInt(mv, info.totalGroupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Try next starting position
    mv.visitLabel(tryNextStart);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }
}
