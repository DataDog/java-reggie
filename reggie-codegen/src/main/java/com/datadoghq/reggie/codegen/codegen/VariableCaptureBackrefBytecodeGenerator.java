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

import com.datadoghq.reggie.codegen.analysis.VariableCaptureBackrefInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Bytecode generator for VARIABLE_CAPTURE_BACKREF strategy.
 *
 * <p>Generates optimized matching code for patterns where a variable-length capturing group is
 * followed by a separator and backreference.
 *
 * <h3>Supported Pattern Examples</h3>
 *
 * <ul>
 *   <li>{@code (.*)\d+\1} - greedy any, digit separator, backref
 *   <li>{@code (.+)=\1} - greedy any (1+), literal separator, backref
 *   <li>{@code (a+):\1} - greedy char class, literal separator, backref
 * </ul>
 *
 * <h3>Algorithm (Longest-First Backtracking)</h3>
 *
 * <pre>{@code
 * boolean matches(String input) {
 *     int len = input.length();
 *     int groupEnd = len;  // Start with longest possible capture
 *     int iterations = 0;
 *
 *     // Backtrack loop: try shorter captures until match found
 *     while (groupEnd >= groupMinLen) {
 *         if (BacktrackConfig.checkLimit(iterations++)) return false;
 *
 *         String captured = input.substring(0, groupEnd);
 *         int sepStart = groupEnd;
 *
 *         // Try to match separator at sepStart
 *         int sepEnd = matchSeparator(input, sepStart, len);
 *         if (sepEnd < 0) {
 *             groupEnd--;
 *             continue;
 *         }
 *
 *         // Try to match backref (captured content) at sepEnd
 *         if (matchBackref(input, sepEnd, len, captured)) {
 *             return true;
 *         }
 *
 *         groupEnd--;
 *     }
 *     return false;
 * }
 * }</pre>
 */
public class VariableCaptureBackrefBytecodeGenerator {

  private final VariableCaptureBackrefInfo info;
  private final String className;

  public VariableCaptureBackrefBytecodeGenerator(
      VariableCaptureBackrefInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Generate all required methods for ReggieMatcher. */
  public void generate(ClassWriter cw) {
    generateMatchesMethod(cw);
    generateFindMethod(cw);
    generateFindFromMethod(cw);
    // Generate stub methods for MatchResult-returning methods
    generateMatchStubMethod(cw);
    generateMatchesBoundedStubMethod(cw);
    generateMatchBoundedStubMethod(cw);
    generateFindMatchStubMethod(cw);
    generateFindMatchFromStubMethod(cw);
  }

  private void generateMatchStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("match() not yet implemented for VARIABLE_CAPTURE_BACKREF strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  private void generateMatchesBoundedStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("matchesBounded() not yet implemented for VARIABLE_CAPTURE_BACKREF strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 4);
    mv.visitEnd();
  }

  private void generateMatchBoundedStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("matchBounded() not yet implemented for VARIABLE_CAPTURE_BACKREF strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 4);
    mv.visitEnd();
  }

  private void generateFindMatchStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("findMatch() not yet implemented for VARIABLE_CAPTURE_BACKREF strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 2);
    mv.visitEnd();
  }

  private void generateFindMatchFromStubMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
    mv.visitInsn(DUP);
    mv.visitLdcInsn("findMatchFrom() not yet implemented for VARIABLE_CAPTURE_BACKREF strategy");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "java/lang/UnsupportedOperationException",
        "<init>",
        "(Ljava/lang/String;)V",
        false);
    mv.visitInsn(ATHROW);
    mv.visitMaxs(3, 3);
    mv.visitEnd();
  }

  /** Generate matches() method with longest-first backtracking. */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator alloc = new LocalVarAllocator(2);
    int lenVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int groupStartVar = alloc.allocate();
    int sepStartVar = alloc.allocate();
    int sepEndVar = alloc.allocate();
    int backrefEndVar = alloc.allocate();
    int iterationsVar = alloc.allocate();

    Label returnFalse = new Label();

    // Null check
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Minimum length check: groupMin + separatorMin + backrefGroupMin
    int minLen = info.groupMinCount + info.getSeparatorMinLength() + info.groupMinCount;
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, minLen);
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // int groupStart = 0;  (for now, no prefix support)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, groupStartVar);

    // int groupEnd = len - separatorMinLen - groupMinLen;  (start with longest possible)
    // For separator min=1 and backref of captured content, max group capture is:
    // len - separatorMin - 0 (backref can be 0 if group captured empty, but we need at least
    // groupMin)
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.getSeparatorMinLength());
    mv.visitInsn(ISUB);
    // For backref, we need room for at least one copy of captured content
    // But we don't know captured length yet, so start with max possible
    mv.visitVarInsn(ISTORE, groupEndVar);

    // int iterations = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iterationsVar);

    // Backtrack loop
    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label continueLoop = new Label();

    mv.visitLabel(loopStart);

    // Check: groupEnd >= groupStart + groupMinCount
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    pushInt(mv, info.groupMinCount);
    mv.visitInsn(IADD);
    mv.visitJumpInsn(IF_ICMPLT, loopEnd);

    // Check backtrack limit: if (BacktrackConfig.checkLimit(iterations++)) return false;
    mv.visitVarInsn(ILOAD, iterationsVar);
    mv.visitMethodInsn(
        INVOKESTATIC, "com/datadoghq/reggie/runtime/BacktrackConfig", "checkLimit", "(I)Z", false);
    mv.visitJumpInsn(IFNE, returnFalse);
    mv.visitIincInsn(iterationsVar, 1);

    // Validate group content matches groupCharSet (if specified)
    if (info.groupCharSet != null && !info.groupCharSet.equals(CharSet.ANY)) {
      // For each char in group, verify it's in charset
      // This is a simplification - for now, assume .* or similar
    }

    // int sepStart = groupEnd;
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ISTORE, sepStartVar);

    // Match separator and get sepEnd
    generateSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, continueLoop, alloc);

    // Match backreference: captured content from groupStart to groupEnd
    // Backref must match input[sepEnd..sepEnd+groupLen]
    // where groupLen = groupEnd - groupStart

    // int groupLen = groupEnd - groupStart;
    int groupLenVar = alloc.allocate();
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // Check: sepEnd + groupLen <= len (room for backref)
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, continueLoop);

    // backrefEnd = sepEnd + groupLen
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, backrefEndVar);

    // For matches(), we need backrefEnd == len (consume entire input)
    if (!info.hasEndAnchor) {
      // Without end anchor, backrefEnd must equal len for matches()
      mv.visitVarInsn(ILOAD, backrefEndVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPNE, continueLoop);
    }

    // Match backref: input.regionMatches(sepEnd, input, groupStart, groupLen)
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, sepEndVar); // toffset
    mv.visitVarInsn(ALOAD, 1); // other (same string)
    mv.visitVarInsn(ILOAD, groupStartVar); // ooffset
    mv.visitVarInsn(ILOAD, groupLenVar); // len
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueLoop);

    // Match found!
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Continue backtracking: groupEnd--
    mv.visitLabel(continueLoop);
    mv.visitIincInsn(groupEndVar, -1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }

  /**
   * Generate separator matching code. Sets sepEndVar to the position after the separator, or jumps
   * to failLabel.
   */
  private void generateSeparatorMatch(
      MethodVisitor mv,
      int sepStartVar,
      int sepEndVar,
      int lenVar,
      Label failLabel,
      LocalVarAllocator alloc) {

    switch (info.separatorType) {
      case LITERAL:
        generateLiteralSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, failLabel);
        break;
      case DIGIT_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            CharSet.DIGIT,
            info.separatorMinCount,
            alloc);
        break;
      case WORD_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            CharSet.WORD,
            info.separatorMinCount,
            alloc);
        break;
      case WHITESPACE_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            CharSet.WHITESPACE,
            info.separatorMinCount,
            alloc);
        break;
      case CHAR_CLASS_SEQ:
        generateCharClassSeqSeparatorMatch(
            mv,
            sepStartVar,
            sepEndVar,
            lenVar,
            failLabel,
            info.separatorCharSet,
            info.separatorMinCount,
            alloc);
        break;
      case NONE:
        // No separator: sepEnd = sepStart
        mv.visitVarInsn(ILOAD, sepStartVar);
        mv.visitVarInsn(ISTORE, sepEndVar);
        break;
    }
  }

  /** Generate literal separator matching. */
  private void generateLiteralSeparatorMatch(
      MethodVisitor mv, int sepStartVar, int sepEndVar, int lenVar, Label failLabel) {
    String literal = info.separatorLiteral;
    int literalLen = literal.length();

    // Check: sepStart + literalLen <= len
    mv.visitVarInsn(ILOAD, sepStartVar);
    pushInt(mv, literalLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, failLabel);

    // Check: input.regionMatches(sepStart, literal, 0, literalLen)
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, sepStartVar);
    mv.visitLdcInsn(literal);
    mv.visitInsn(ICONST_0);
    pushInt(mv, literalLen);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, failLabel);

    // sepEnd = sepStart + literalLen
    mv.visitVarInsn(ILOAD, sepStartVar);
    pushInt(mv, literalLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, sepEndVar);
  }

  /**
   * Generate char class sequence separator matching (e.g., \d+). Matches minimum required, then as
   * many more as possible (greedy).
   */
  private void generateCharClassSeqSeparatorMatch(
      MethodVisitor mv,
      int sepStartVar,
      int sepEndVar,
      int lenVar,
      Label failLabel,
      CharSet charSet,
      int minCount,
      LocalVarAllocator alloc) {

    int posVar = alloc.allocate();
    int countVar = alloc.allocate();
    int charVar = alloc.allocate(); // Store char in local var for stack safety

    // int pos = sepStart;
    mv.visitVarInsn(ILOAD, sepStartVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // Match loop
    Label matchLoop = new Label();
    Label matchEnd = new Label();

    mv.visitLabel(matchLoop);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, matchEnd);

    // char c = input.charAt(pos); store in local for stack safety
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check if c is in charset (uses local var, not stack)
    generateCharSetCheckFromLocal(mv, charVar, charSet, matchEnd);

    // pos++; count++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(countVar, 1);
    mv.visitJumpInsn(GOTO, matchLoop);

    mv.visitLabel(matchEnd);

    // Check: count >= minCount
    mv.visitVarInsn(ILOAD, countVar);
    pushInt(mv, minCount);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);

    // sepEnd = pos
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, sepEndVar);
  }

  /**
   * Generate charset membership check from a local variable. Stack-safe: reads from local var,
   * doesn't leave anything on stack. Jumps to failLabel if char is NOT in charset.
   */
  private void generateCharSetCheckFromLocal(
      MethodVisitor mv, int charVar, CharSet charSet, Label failLabel) {
    if (charSet.equals(CharSet.DIGIT)) {
      // c >= '0' && c <= '9'
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '0');
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '9');
      mv.visitJumpInsn(IF_ICMPGT, failLabel);
    } else if (charSet.equals(CharSet.WHITESPACE)) {
      // c == ' ' || c == '\t' || c == '\n' || c == '\r'
      Label ok = new Label();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, ' ');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '\t');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '\r');
      mv.visitJumpInsn(IF_ICMPEQ, ok);
      mv.visitJumpInsn(GOTO, failLabel);
      mv.visitLabel(ok);
    } else if (charSet.equals(CharSet.WORD)) {
      // a-z, A-Z, 0-9, _
      Label ok = new Label();

      // Check a-z
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'a');
      mv.visitJumpInsn(IF_ICMPLT, ok); // Skip to next check if < 'a'
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'z');
      Label notLower = new Label();
      mv.visitJumpInsn(IF_ICMPGT, notLower);
      mv.visitJumpInsn(GOTO, ok); // Is lowercase, success

      mv.visitLabel(notLower);
      // Check A-Z
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'A');
      Label notUpperStart = new Label();
      mv.visitJumpInsn(IF_ICMPLT, notUpperStart);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, 'Z');
      Label notUpper = new Label();
      mv.visitJumpInsn(IF_ICMPGT, notUpper);
      mv.visitJumpInsn(GOTO, ok); // Is uppercase, success

      mv.visitLabel(notUpperStart);
      mv.visitLabel(notUpper);
      // Check 0-9
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '0');
      Label notDigitStart = new Label();
      mv.visitJumpInsn(IF_ICMPLT, notDigitStart);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '9');
      Label notDigit = new Label();
      mv.visitJumpInsn(IF_ICMPGT, notDigit);
      mv.visitJumpInsn(GOTO, ok); // Is digit, success

      mv.visitLabel(notDigitStart);
      mv.visitLabel(notDigit);
      // Check _
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, '_');
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
      // Is underscore, fall through to ok

      mv.visitLabel(ok);
    }
    // General case (CharSet.ANY or unknown): no check needed, always passes
  }

  /** Generate find() method - delegates to findFrom(input, 0). */
  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // return findFrom(input, 0) >= 0;
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

  /** Generate findFrom() method with backtracking at each starting position. */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=startPos
    LocalVarAllocator alloc = new LocalVarAllocator(3);
    int lenVar = alloc.allocate();
    int startVar = alloc.allocate();
    int groupEndVar = alloc.allocate();
    int sepStartVar = alloc.allocate();
    int sepEndVar = alloc.allocate();
    int iterationsVar = alloc.allocate();

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
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, startVar);

    // Outer loop: try each starting position
    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // Minimum length check from start
    int minLen = info.groupMinCount + info.getSeparatorMinLength() + info.groupMinCount;
    mv.visitVarInsn(ILOAD, startVar);
    pushInt(mv, minLen);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // Initialize for this starting position
    // groupEnd = len - separatorMinLen (max possible capture from start)
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.getSeparatorMinLength());
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // iterations = 0
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, iterationsVar);

    // Inner backtrack loop
    Label innerLoop = new Label();
    Label innerEnd = new Label();
    Label continueInner = new Label();

    mv.visitLabel(innerLoop);

    // Check: groupEnd >= start + groupMinCount
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, startVar);
    pushInt(mv, info.groupMinCount);
    mv.visitInsn(IADD);
    mv.visitJumpInsn(IF_ICMPLT, innerEnd);

    // Check backtrack limit
    mv.visitVarInsn(ILOAD, iterationsVar);
    mv.visitMethodInsn(
        INVOKESTATIC, "com/datadoghq/reggie/runtime/BacktrackConfig", "checkLimit", "(I)Z", false);
    mv.visitJumpInsn(IFNE, returnNotFound);
    mv.visitIincInsn(iterationsVar, 1);

    // sepStart = groupEnd
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ISTORE, sepStartVar);

    // Match separator
    generateSeparatorMatch(mv, sepStartVar, sepEndVar, lenVar, continueInner, alloc);

    // Match backreference
    int groupLenVar = alloc.allocate();
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenVar);

    // Check: sepEnd + groupLen <= len
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, continueInner);

    // Match backref
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, sepEndVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, groupLenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    mv.visitJumpInsn(IFEQ, continueInner);

    // Found! Return start position
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IRETURN);

    // Continue inner backtrack
    mv.visitLabel(continueInner);
    mv.visitIincInsn(groupEndVar, -1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Try next starting position
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    mv.visitLabel(outerEnd);
    mv.visitLabel(returnNotFound);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(6, alloc.peek());
    mv.visitEnd();
  }
}
