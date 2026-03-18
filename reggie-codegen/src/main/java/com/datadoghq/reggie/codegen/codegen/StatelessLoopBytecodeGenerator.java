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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.StatelessPatternInfo;
import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates optimized bytecode for stateless patterns that don't require NFA state tracking.
 *
 * <h3>Pattern Types</h3>
 *
 * <ul>
 *   <li><b>SINGLE_QUANTIFIER</b>: {@code \w+}, {@code \d*}, {@code [a-z]{5,10}}
 *   <li><b>LOOKAHEAD_LITERAL</b>: {@code (?=\w+@).*@example.com}
 * </ul>
 *
 * <h3>Generated Algorithm (SINGLE_QUANTIFIER)</h3>
 *
 * <pre>{@code
 * // For pattern \w+
 * boolean matches(String input) {
 *     if (input == null || input.length() == 0) return false;
 *
 *     for (int i = 0; i < input.length(); i++) {
 *         char ch = input.charAt(i);
 *         if (!isWordChar(ch)) return false;
 *     }
 *     return true;
 * }
 * }</pre>
 *
 * <h3>Generated Algorithm (LOOKAHEAD_LITERAL)</h3>
 *
 * <pre>{@code
 * // For pattern (?=\w+@).*@example.com
 * boolean matches(String input) {
 *     if (input == null) return false;
 *
 *     // Check positive lookahead: \w+@
 *     if (!input.matches("\\w+@.*")) return false;
 *
 *     // Check literal suffix
 *     return input.endsWith("@example.com");
 * }
 * }</pre>
 *
 * <h3>Performance</h3>
 *
 * Simple loops without BitSet/SparseSet allocations - eliminates 59% BitSet overhead.
 */
public class StatelessLoopBytecodeGenerator {

  private final StatelessPatternInfo info;
  private CharSet lookaheadCharSet; // Character class from lookahead (e.g., \w or \d)
  private boolean lookaheadNegated; // Whether the character class is negated
  private char terminatorChar; // Terminating character from lookahead (e.g., '@' or 'x')

  public StatelessLoopBytecodeGenerator(StatelessPatternInfo info) {
    this.info = info;
    if (info.type == StatelessPatternInfo.PatternType.LOOKAHEAD_LITERAL) {
      extractLookaheadInfo();
    }
  }

  /**
   * Extract character class and terminator from lookahead pattern. Expected structure:
   * (?=<charset>+<terminator>).*<literal> For example: (?=\w+@).*@example.com or (?=\d+x).*xend
   */
  private void extractLookaheadInfo() {
    RegexNode lookaheadChild = info.lookahead.subPattern;

    // Lookahead child should be a ConcatNode with quantifier + literal
    if (!(lookaheadChild instanceof ConcatNode)) {
      throw new IllegalStateException("Lookahead pattern must be a concatenation");
    }

    ConcatNode concat = (ConcatNode) lookaheadChild;
    List<RegexNode> children = concat.children;

    if (children.size() < 2) {
      throw new IllegalStateException("Lookahead concat must have at least 2 children");
    }

    // First child should be quantifier with character class
    if (!(children.get(0) instanceof QuantifierNode)) {
      throw new IllegalStateException("First child of lookahead must be quantifier");
    }

    QuantifierNode quantifier = (QuantifierNode) children.get(0);
    if (!(quantifier.child instanceof CharClassNode)) {
      throw new IllegalStateException("Quantifier child must be CharClassNode");
    }

    CharClassNode charClass = (CharClassNode) quantifier.child;
    this.lookaheadCharSet = charClass.chars;
    this.lookaheadNegated = charClass.negated;

    // Second child should be the terminator literal
    if (!(children.get(1) instanceof LiteralNode)) {
      throw new IllegalStateException("Second child of lookahead must be literal terminator");
    }

    LiteralNode terminator = (LiteralNode) children.get(1);
    this.terminatorChar = terminator.ch;
  }

  /** Generate inline character set check bytecode. */
  private void generateInlineCharSetCheck(
      MethodVisitor mv, int charVar, Label mismatch, boolean negated, CharSet charset) {
    if (charset.isSingleChar()) {
      // Single character check
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      if (negated) {
        mv.visitJumpInsn(IF_ICMPEQ, mismatch);
      } else {
        mv.visitJumpInsn(IF_ICMPNE, mismatch);
      }
    } else if (charset.isSimpleRange()) {
      // Single range check
      CharSet.Range range = charset.getSimpleRange();

      if (negated) {
        // Negated range: if (c >= start && c <= end) goto mismatch;
        Label notInRange = new Label();
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, notInRange);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, mismatch);
        mv.visitLabel(notInRange);
      } else {
        // Normal range: if (c < start || c > end) goto mismatch;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, mismatch);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPGT, mismatch);
      }
    } else {
      // Multiple ranges - unroll into OR checks
      if (negated) {
        // Negated: if ANY range matches, mismatch
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, mismatch);
          mv.visitLabel(tryNext);
        }
      } else {
        // Normal: if NO range matches, mismatch
        Label matches = new Label();
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, matches);
          mv.visitLabel(tryNext);
        }
        // No range matched - mismatch
        mv.visitJumpInsn(GOTO, mismatch);
        mv.visitLabel(matches);
      }
    }
  }

  /**
   * Generate helper methods required by this generator. Call this before generating the main
   * pattern methods.
   */
  public void generateHelperMethods(ClassWriter cw, String className) {
    if (info.type == StatelessPatternInfo.PatternType.LOOKAHEAD_LITERAL) {
      generateFindMatchStartHelper(cw, className);
    }
  }

  /** Generate matches() method for stateless patterns. */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    switch (info.type) {
      case SINGLE_QUANTIFIER:
        generateSingleQuantifierMatches(cw, className);
        break;
      case LOOKAHEAD_LITERAL:
        generateLookaheadLiteralMatches(cw, className);
        break;
    }
  }

  /** Generate matches() method for single quantifier patterns like \w+ or \d*. */
  private void generateSingleQuantifierMatches(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int pos = 0;
    int posVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    int lenVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int c; (for character storage)
    int charVar = allocator.allocate();

    // int count = 0;
    int countVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // Tight counting loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check character
    Label returnFalse = new Label();
    generateInlineCharSetCheck(mv, charVar, returnFalse, info.negated, info.charset);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // count++;
    mv.visitIincInsn(countVar, 1);

    // Check maxReps bound if applicable
    if (info.maxReps > 0) {
      // if (count >= maxReps) break;
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxReps);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    mv.visitJumpInsn(GOTO, loopStart);

    // Character mismatch - return false
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // Loop complete - check if count >= minReps
    mv.visitLabel(loopEnd);
    mv.visitVarInsn(ILOAD, countVar);
    pushInt(mv, info.minReps);
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // Verify we consumed entire input (for matches() semantics)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, returnFalse);

    // Success
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matches() method for lookahead + literal patterns like (?=\w+@).*@example.com. Checks
   * that input ends with literal and satisfies lookahead.
   */
  private void generateLookaheadLiteralMatches(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // if (!input.endsWith(literalSuffix)) return false;
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitLdcInsn(info.literalSuffix); // literal
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "endsWith", "(Ljava/lang/String;)Z", false);
    Label endsWithLiteral = new Label();
    mv.visitJumpInsn(IFNE, endsWithLiteral);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(endsWithLiteral);

    // int literalPos = input.length() - literalSuffix.length();
    int literalPosVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    pushInt(mv, info.literalSuffix.length());
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, literalPosVar);

    // Verify lookahead at position literalPos
    generateLookaheadVerification(mv, 1, literalPosVar, allocator);

    // Return true (lookahead verified)
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate find() method for stateless patterns. */
  public void generateFindMethod(ClassWriter cw, String className) {
    switch (info.type) {
      case SINGLE_QUANTIFIER:
        generateSingleQuantifierFind(cw, className);
        break;
      case LOOKAHEAD_LITERAL:
        generateLookaheadLiteralFind(cw, className);
        break;
    }
  }

  /** Generate find() method for single quantifier patterns. */
  private void generateSingleQuantifierFind(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    int lenVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // if (len < minReps) return false;
    Label lengthOk = new Label();
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.minReps);
    mv.visitJumpInsn(IF_ICMPGE, lengthOk);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(lengthOk);

    // int start = 0;
    int startVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, startVar);

    // int pos;
    int posVar = allocator.allocate();
    // int c;
    int charVar = allocator.allocate();
    // int count;
    int countVar = allocator.allocate();

    // Outer loop: try each starting position
    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // Check: start <= len - minReps
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.minReps);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // int pos = start;
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // Inner loop: count matches from this position
    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check character
    generateInlineCharSetCheck(mv, charVar, innerEnd, info.negated, info.charset);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // count++;
    mv.visitIincInsn(countVar, 1);

    // Check maxReps bound if applicable
    if (info.maxReps > 0) {
      // if (count >= maxReps) break;
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxReps);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    mv.visitJumpInsn(GOTO, innerLoop);

    // Inner loop end - check if we found a match
    mv.visitLabel(innerEnd);
    mv.visitVarInsn(ILOAD, countVar);
    pushInt(mv, info.minReps);
    Label tryNext = new Label();
    mv.visitJumpInsn(IF_ICMPLT, tryNext);

    // Found a match - return true
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Try next starting position
    mv.visitLabel(tryNext);
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    // No match found
    mv.visitLabel(outerEnd);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate find() method for lookahead + literal patterns. Uses String.indexOf() for literal
   * search, then verifies lookahead.
   */
  private void generateLookaheadLiteralFind(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int pos = input.indexOf(literalSuffix);
    int posVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitLdcInsn(info.literalSuffix); // literal
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;)I", false);
    mv.visitVarInsn(ISTORE, posVar);

    // if (pos < 0) return false;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);

    // Verify lookahead at position pos
    generateLookaheadVerification(mv, 1, posVar, allocator);

    // Return true (lookahead verified)
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate bytecode to verify lookahead assertion. For pattern (?=\w+@), checks that word chars
   * followed by @ exist from start position. Expects: inputVar contains input string, posVar
   * contains literal match position On success: falls through; On failure: jumps to returnFalse
   * label
   *
   * @param mv method visitor
   * @param inputVar local variable slot containing input string
   * @param literalPosVar local variable slot containing literal position
   * @param allocator local variable allocator for temporary variables
   */
  private void generateLookaheadVerification(
      MethodVisitor mv, int inputVar, int literalPosVar, LocalVarAllocator allocator) {
    // For now, implement specific verification for (?=\w+@) pattern
    // TODO: Generalize for other lookahead patterns

    // Allocate local variable slots using allocator
    int scanPosVar = allocator.allocate();
    int charVar = allocator.allocate();
    int wordStartVar = allocator.allocate();

    Label returnFalse = new Label();
    Label verified = new Label();

    // Scan from position 0 to literalPos looking for \w+@ pattern
    // int scanPos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, scanPosVar);

    Label scanLoop = new Label();
    mv.visitLabel(scanLoop);

    // if (scanPos >= literalPos) goto returnFalse (didn't find \w+@)
    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitJumpInsn(IF_ICMPGE, returnFalse);

    // Check if word char at scanPos
    // char c = input.charAt(scanPos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isWordChar(c)) { scanPos++; continue; }
    Label isWord = new Label();
    generateLookaheadCharCheck(mv, charVar, isWord);
    mv.visitIincInsn(scanPosVar, 1); // scanPos++
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(isWord);

    // Found start of word chars, now count them
    // int wordStart = scanPos;
    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitVarInsn(ISTORE, wordStartVar);

    // Count word chars
    Label countLoop = new Label();
    mv.visitLabel(countLoop);

    // scanPos++;
    mv.visitIincInsn(scanPosVar, 1);

    // if (scanPos >= input.length()) goto returnFalse;
    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, returnFalse);

    // c = input.charAt(scanPos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (c == terminator) goto verified (found required pattern)
    mv.visitVarInsn(ILOAD, charVar);
    pushInt(mv, (int) terminatorChar);
    mv.visitJumpInsn(IF_ICMPEQ, verified);

    // if (isWordChar(c)) goto countLoop (continue counting)
    Label continueCount = new Label();
    generateLookaheadCharCheck(mv, charVar, continueCount);

    // Not @ and not word char - restart scan from next position
    mv.visitVarInsn(ILOAD, wordStartVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, scanPosVar); // scanPos = wordStart + 1
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(continueCount);
    mv.visitJumpInsn(GOTO, countLoop);

    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(verified);
    // Falls through to caller
  }

  /**
   * Generate bytecode to check if character matches the lookahead character class. Expects: charVar
   * contains char to check On success: jumps to successLabel; On failure: falls through
   */
  private void generateLookaheadCharCheck(MethodVisitor mv, int charVar, Label successLabel) {
    // Use generateInlineCharSetCheck with inverted logic:
    // We want to jump to successLabel if char MATCHES
    // generateInlineCharSetCheck jumps to its label if char DOESN'T match
    Label notMatch = new Label();
    generateInlineCharSetCheck(mv, charVar, notMatch, lookaheadNegated, lookaheadCharSet);
    // If we get here, char matches - jump to success
    mv.visitJumpInsn(GOTO, successLabel);
    // If char doesn't match, fall through
    mv.visitLabel(notMatch);
  }

  /** Generate findFrom() method for stateless patterns. */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    switch (info.type) {
      case SINGLE_QUANTIFIER:
        generateSingleQuantifierFindFrom(cw, className);
        break;
      case LOOKAHEAD_LITERAL:
        generateLookaheadLiteralFindFrom(cw, className);
        break;
    }
  }

  /** Generate findFrom() method for single quantifier patterns. */
  private void generateSingleQuantifierFindFrom(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // if (input == null) return -1;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    int lenVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // if (start < 0) start = 0;
    Label startOk = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFGE, startOk);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startOk);

    // if (len < minReps) return -1;
    Label lengthOk = new Label();
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.minReps);
    mv.visitJumpInsn(IF_ICMPGE, lengthOk);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(lengthOk);

    // Allocate loop variables
    int posVar = allocator.allocate();
    int charVar = allocator.allocate();
    int countVar = allocator.allocate();

    // Outer loop: try each starting position
    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // Check: start <= len - minReps
    mv.visitVarInsn(ILOAD, 2); // start (param)
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.minReps);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // int pos = start;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // Inner loop: count matches from this position
    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check character
    generateInlineCharSetCheck(mv, charVar, innerEnd, info.negated, info.charset);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // count++;
    mv.visitIincInsn(countVar, 1);

    // Check maxReps bound if applicable
    if (info.maxReps > 0) {
      // if (count >= maxReps) break;
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxReps);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    mv.visitJumpInsn(GOTO, innerLoop);

    // Inner loop end - check if we found a match
    mv.visitLabel(innerEnd);
    mv.visitVarInsn(ILOAD, countVar);
    pushInt(mv, info.minReps);
    Label tryNext = new Label();
    mv.visitJumpInsn(IF_ICMPLT, tryNext);

    // Found a match - return start position
    mv.visitVarInsn(ILOAD, 2); // start (param)
    mv.visitInsn(IRETURN);

    // Try next starting position
    mv.visitLabel(tryNext);
    mv.visitIincInsn(2, 1); // start++ (param)
    mv.visitJumpInsn(GOTO, outerLoop);

    // No match found
    mv.visitLabel(outerEnd);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findFrom() method for lookahead + literal patterns. Uses String.indexOf() with start
   * position for literal search, then finds match start. For pattern (?=\w+@).*@example\.com,
   * returns position where \w+ starts, not where literal is found.
   */
  private void generateLookaheadLiteralFindFrom(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // if (input == null) return -1;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // Normalize start: if (start < 0) start = 0;
    Label startNormalized = new Label();
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitJumpInsn(IFGE, startNormalized);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2); // start = 0
    mv.visitLabel(startNormalized);

    // int searchPos = start;
    int searchPosVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ISTORE, searchPosVar);

    int literalPosVar = allocator.allocate();
    int matchStartVar = allocator.allocate();

    // Loop to find a valid match (one that starts >= start)
    Label searchLoop = new Label();
    Label noMatch = new Label();

    mv.visitLabel(searchLoop);

    // int literalPos = input.indexOf(literalSuffix, searchPos);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitLdcInsn(info.literalSuffix); // literal
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, literalPosVar);

    // if (literalPos < 0) return -1;
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitJumpInsn(IFLT, noMatch);

    // Find where the match actually starts by scanning backwards from literalPos
    // to verify the lookahead and find the actual start of the match
    // int matchStart = findMatchStart(input, literalPos, start);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitVarInsn(ILOAD, 2); // start (don't scan before search start)
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "findMatchStart", "(Ljava/lang/String;II)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) { searchPos = literalPos + 1; continue; } (lookahead verification failed)
    Label matchStartValid = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, matchStartValid);
    // Verification failed, try next literal occurrence
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, searchPosVar);
    mv.visitJumpInsn(GOTO, searchLoop);
    mv.visitLabel(matchStartValid);

    // if (matchStart < start) { searchPos = literalPos + 1; continue; } (match starts before
    // boundary)
    Label boundaryOk = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitJumpInsn(IF_ICMPGE, boundaryOk);
    // Match starts too early, try next literal occurrence
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, searchPosVar);
    mv.visitJumpInsn(GOTO, searchLoop);
    mv.visitLabel(boundaryOk);

    // Found a valid match - return matchStart
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IRETURN);

    // No match found
    mv.visitLabel(noMatch);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate match(String) method - returns MatchResult for full string match. Since stateless
   * patterns don't track groups, returns MatchResult with only match bounds.
   */
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
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // if (matchesBounded(input, 0, input.length())) return new MatchResult(input, 0,
    // input.length(), new int[0]);
    // else return null;

    Label matchFailed = new Label();

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // Call matchesBounded(input, 0, input.length())
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", false);
    mv.visitJumpInsn(IFEQ, matchFailed);

    // Match succeeded - create starts = [0], ends = [input.length()]
    int startsVar = allocator.allocate();
    mv.visitInsn(ICONST_1);
    mv.visitIntInsn(NEWARRAY, T_INT); // starts = new int[1]
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0); // starts[0] = 0
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, startsVar);

    int endsVar = allocator.allocate();
    mv.visitInsn(ICONST_1);
    mv.visitIntInsn(NEWARRAY, T_INT); // ends = new int[1]
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE); // ends[0] = input.length()
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, 0)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitInsn(ICONST_0); // groupCount = 0
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchFailed);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchesBounded(CharSequence, int, int) method - checks if bounded range matches. */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start, slot 3 = end
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // Convert to String for simplicity
    int stringInputVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ASTORE, stringInputVar);

    // Get substring: stringInput.substring(start, end)
    int substringVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, stringInputVar);
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
    mv.visitVarInsn(ASTORE, substringVar);

    // Call matches(substring)
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, substringVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matchBounded(CharSequence, int, int) method - returns MatchResult for bounded match.
   */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start, slot 3 = end
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    Label matchFailed = new Label();

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // if (matchesBounded(input, start, end)) return new MatchResult(input.toString(), start, end,
    // new int[0]);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", false);
    mv.visitJumpInsn(IFEQ, matchFailed);

    // Match succeeded - create starts = [0], ends = [end - start] (relative positions)
    int startsVar = allocator.allocate();
    mv.visitInsn(ICONST_1);
    mv.visitIntInsn(NEWARRAY, T_INT); // starts = new int[1]
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0); // starts[0] = 0 (relative to substring start)
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, startsVar);

    int endsVar = allocator.allocate();
    mv.visitInsn(ICONST_1);
    mv.visitIntInsn(NEWARRAY, T_INT); // ends = new int[1]
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitInsn(ISUB); // end - start (length of match)
    mv.visitInsn(IASTORE); // ends[0] = end - start
    mv.visitVarInsn(ASTORE, endsVar);

    // Extract substring: String matchedStr = input.subSequence(start, end).toString()
    int matchedStrVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1); // input
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
    mv.visitVarInsn(ASTORE, matchedStrVar);

    // return new MatchResultImpl(matchedStr, starts, ends, 0)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, matchedStrVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitInsn(ICONST_0); // groupCount = 0
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchFailed);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findMatch(String) method - returns MatchResult for first find. Delegates to
   * findMatchFrom(input, 0).
   */
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
    mv.visitInsn(ICONST_0); // start = 0
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

  /**
   * Generate findBoundsFrom() method - find first match starting from offset, return bounds via
   * array. Signature: boolean findBoundsFrom(String input, int start, int[] bounds) Returns: true
   * if match found (bounds[0]=start, bounds[1]=end), false otherwise
   *
   * <p>This method enables allocation-free replaceAll() operations.
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    switch (info.type) {
      case SINGLE_QUANTIFIER:
        generateSingleQuantifierFindBoundsFrom(cw, className);
        break;
      case LOOKAHEAD_LITERAL:
        generateLookaheadLiteralFindBoundsFrom(cw, className);
        break;
    }
  }

  /**
   * Generate findBoundsFrom() for single quantifier patterns. Similar to findFrom() but populates
   * bounds array and returns boolean.
   */
  private void generateSingleQuantifierFindBoundsFrom(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start, slot 3 = bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    int lenVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // if (start < 0) start = 0;
    Label startOk = new Label();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFGE, startOk);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2);
    mv.visitLabel(startOk);

    // if (len < minReps) return false;
    Label lengthOk = new Label();
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.minReps);
    mv.visitJumpInsn(IF_ICMPGE, lengthOk);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(lengthOk);

    // Allocate loop variables
    int posVar = allocator.allocate();
    int charVar = allocator.allocate();
    int countVar = allocator.allocate();

    // Outer loop: try each starting position
    Label outerLoop = new Label();
    Label outerEnd = new Label();

    mv.visitLabel(outerLoop);

    // Check: start <= len - minReps
    mv.visitVarInsn(ILOAD, 2); // start (param)
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, info.minReps);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, outerEnd);

    // int pos = start;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // Inner loop: count matches from this position
    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check character
    generateInlineCharSetCheck(mv, charVar, innerEnd, info.negated, info.charset);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // count++;
    mv.visitIincInsn(countVar, 1);

    // Check maxReps bound if applicable
    if (info.maxReps > 0) {
      // if (count >= maxReps) break;
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxReps);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    mv.visitJumpInsn(GOTO, innerLoop);

    // Inner loop end - check if we found a match
    mv.visitLabel(innerEnd);
    mv.visitVarInsn(ILOAD, countVar);
    pushInt(mv, info.minReps);
    Label tryNext = new Label();
    mv.visitJumpInsn(IF_ICMPLT, tryNext);

    // Found a match - set bounds[0] = start, bounds[1] = pos
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 2); // start (param)
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // Return true
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Try next starting position
    mv.visitLabel(tryNext);
    mv.visitIincInsn(2, 1); // start++ (param)
    mv.visitJumpInsn(GOTO, outerLoop);

    // No match found - return false
    mv.visitLabel(outerEnd);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findBoundsFrom() for lookahead + literal patterns. Similar to findFrom() but populates
   * bounds array and returns boolean.
   */
  private void generateLookaheadLiteralFindBoundsFrom(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start, slot 3 = bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // Normalize start: if (start < 0) start = 0;
    Label startNormalized = new Label();
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitJumpInsn(IFGE, startNormalized);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 2); // start = 0
    mv.visitLabel(startNormalized);

    // int searchPos = start;
    int searchPosVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ISTORE, searchPosVar);

    int literalPosVar = allocator.allocate();
    int matchStartVar = allocator.allocate();

    // Loop to find a valid match (one that starts >= start)
    Label searchLoop = new Label();
    Label noMatch = new Label();

    mv.visitLabel(searchLoop);

    // int literalPos = input.indexOf(literalSuffix, searchPos);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitLdcInsn(info.literalSuffix); // literal
    mv.visitVarInsn(ILOAD, searchPosVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, literalPosVar);

    // if (literalPos < 0) return false;
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitJumpInsn(IFLT, noMatch);

    // Find where the match actually starts by scanning backwards from literalPos
    // to verify the lookahead and find the actual start of the match
    // int matchStart = findMatchStart(input, literalPos, start);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitVarInsn(ILOAD, 2); // start (don't scan before search start)
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className, "findMatchStart", "(Ljava/lang/String;II)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) { searchPos = literalPos + 1; continue; } (lookahead verification failed)
    Label matchStartValid = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, matchStartValid);
    // Verification failed, try next literal occurrence
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, searchPosVar);
    mv.visitJumpInsn(GOTO, searchLoop);
    mv.visitLabel(matchStartValid);

    // if (matchStart < start) { searchPos = literalPos + 1; continue; } (match starts before
    // boundary)
    Label boundaryOk = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitJumpInsn(IF_ICMPGE, boundaryOk);
    // Match starts too early, try next literal occurrence
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, searchPosVar);
    mv.visitJumpInsn(GOTO, searchLoop);
    mv.visitLabel(boundaryOk);

    // Found a valid match - set bounds[0] = matchStart, bounds[1] = literalPos +
    // literalSuffix.length()
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);

    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, literalPosVar);
    pushInt(mv, info.literalSuffix.length());
    mv.visitInsn(IADD);
    mv.visitInsn(IASTORE);

    // Return true
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // No match found
    mv.visitLabel(noMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findMatchStart() helper method for lookahead + literal patterns. Scans backward from
   * literal position to find where the match actually starts.
   *
   * <p>Signature: int findMatchStart(String input, int literalPos, int searchStart) Returns:
   * position where \w+ begins (the actual match start), or -1 if verification fails
   */
  private void generateFindMatchStartHelper(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, "findMatchStart", "(Ljava/lang/String;II)I", null, null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = literalPos, 3 = searchStart
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int inputVar = 1;
    int literalPosVar = 2;
    int searchStartVar = 3;
    int wordEndVar = allocator.allocate();
    int cVar = allocator.allocate();
    int wordStartVar = allocator.allocate();

    // For pattern (?=\w+@).*@example.com, when we find literal "@example.com" at position
    // literalPos,
    // the '@' in the lookahead is the same '@' that starts the literal.
    // We need to scan backward from literalPos to find where the \w+ sequence starts.
    // That's the real match start position.

    // The literal starts with '@', so literalPos points to the '@'
    // Verify that literalPos > 0 (need room for at least one \w before @)
    Label returnFailure = new Label();

    // if (literalPos <= 0) return -1; (no room for \w+ before @)
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitJumpInsn(IFLE, returnFailure);

    // if (literalPos <= searchStart) return -1; (no room for \w+ before @)
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitVarInsn(ILOAD, searchStartVar);
    mv.visitJumpInsn(IF_ICMPLE, returnFailure);

    // int wordEnd = literalPos - 1; (last position before @)
    mv.visitVarInsn(ILOAD, literalPosVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, wordEndVar);

    // Verify that wordEnd is a word char
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, wordEndVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);

    Label isWordChar = new Label();
    generateLookaheadCharCheck(mv, cVar, isWordChar);
    // Not a word char - verification failed
    mv.visitJumpInsn(GOTO, returnFailure);

    mv.visitLabel(isWordChar);

    // Scan backward to find start of word char sequence
    // int wordStart = wordEnd;
    mv.visitVarInsn(ILOAD, wordEndVar);
    mv.visitVarInsn(ISTORE, wordStartVar);

    Label scanWordLoop = new Label();
    Label scanWordEnd = new Label();

    mv.visitLabel(scanWordLoop);

    // if (wordStart <= searchStart) break; (reached search boundary)
    mv.visitVarInsn(ILOAD, wordStartVar);
    mv.visitVarInsn(ILOAD, searchStartVar);
    mv.visitJumpInsn(IF_ICMPLE, scanWordEnd);

    // char prevChar = input.charAt(wordStart - 1);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, wordStartVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);

    // if (!isWordChar(prevChar)) break;
    Label prevIsWord = new Label();
    generateLookaheadCharCheck(mv, cVar, prevIsWord);
    mv.visitJumpInsn(GOTO, scanWordEnd);

    mv.visitLabel(prevIsWord);
    // prevChar is word char, continue scanning backward
    mv.visitIincInsn(wordStartVar, -1);
    mv.visitJumpInsn(GOTO, scanWordLoop);

    mv.visitLabel(scanWordEnd);

    // If we stopped because wordStart <= searchStart, check if match extends before searchStart
    // if (wordStart == searchStart && searchStart > 0 && isWordChar(input.charAt(searchStart - 1)))
    // return -1;
    Label searchStartIsActualStart = new Label();

    // Check if wordStart == searchStart
    mv.visitVarInsn(ILOAD, wordStartVar);
    mv.visitVarInsn(ILOAD, searchStartVar);
    mv.visitJumpInsn(
        IF_ICMPNE, searchStartIsActualStart); // If not equal, searchStart is not the issue

    // wordStart == searchStart, check if searchStart > 0
    mv.visitVarInsn(ILOAD, searchStartVar);
    mv.visitJumpInsn(IFLE, searchStartIsActualStart); // If searchStart <= 0, can't check before it

    // Check if charAt(searchStart - 1) is a word char
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, searchStartVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);

    generateLookaheadCharCheck(
        mv, cVar, returnFailure); // If it IS a word char, return -1 (match extends before boundary)

    mv.visitLabel(searchStartIsActualStart);

    // Return wordStart (the actual match start position)
    mv.visitVarInsn(ILOAD, wordStartVar);
    mv.visitInsn(IRETURN);

    mv.visitLabel(returnFailure);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findMatchFrom(String, int) method - returns MatchResult for find from position. Uses
   * findFrom to get position, then determines match end based on pattern type.
   */
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
    int matchEndVar = allocator.allocate();
    int cVar =
        allocator.allocate(); // Used for c (SINGLE_QUANTIFIER) or literalPos (LOOKAHEAD_LITERAL)
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    Label notFound = new Label();

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, className, "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) return null;
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFLT, notFound);

    // Compute matchEnd based on pattern type
    if (info.type == StatelessPatternInfo.PatternType.SINGLE_QUANTIFIER) {
      // For simple quantifier, find where the character class stops matching
      // matchEnd = matchStart;
      // while (matchEnd < input.length() && charsetMatches(input.charAt(matchEnd))) matchEnd++;

      mv.visitVarInsn(ILOAD, matchStartVar);
      mv.visitVarInsn(ISTORE, matchEndVar);

      Label loopStart = new Label();
      Label loopEnd = new Label();

      mv.visitLabel(loopStart);
      // if (matchEnd >= input.length()) break;
      mv.visitVarInsn(ILOAD, matchEndVar);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // char c = input.charAt(matchEnd);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, matchEndVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, cVar);

      // if (!charsetMatches(c)) break;
      Label charsetNotMatch = new Label();
      generateInlineCharSetCheck(mv, cVar, charsetNotMatch, info.negated, info.charset);

      // matchEnd++;
      mv.visitIincInsn(matchEndVar, 1);
      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(charsetNotMatch);
      mv.visitLabel(loopEnd);

    } else {
      // For LOOKAHEAD_LITERAL, find where the literal is, then matchEnd = literalPos +
      // literalSuffix.length()
      // The match spans from matchStart (where \w+ begins) to the end of the literal
      // int literalPos = input.indexOf(literalSuffix, matchStart);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitLdcInsn(info.literalSuffix);
      mv.visitVarInsn(ILOAD, matchStartVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
      mv.visitVarInsn(ISTORE, cVar); // literalPos reuses cVar slot

      // matchEnd = literalPos + literalSuffix.length()
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, info.literalSuffix.length());
      mv.visitInsn(IADD);
      mv.visitVarInsn(ISTORE, matchEndVar);
    }

    // Create starts = [matchStart], ends = [matchEnd]
    mv.visitInsn(ICONST_1);
    mv.visitIntInsn(NEWARRAY, T_INT); // starts = new int[1]
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE); // starts[0] = matchStart
    mv.visitVarInsn(ASTORE, startsVar);

    mv.visitInsn(ICONST_1);
    mv.visitIntInsn(NEWARRAY, T_INT); // ends = new int[1]
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchEndVar);
    mv.visitInsn(IASTORE); // ends[0] = matchEnd
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, 0);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitInsn(ICONST_0); // groupCount = 0
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitLabel(notFound);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
