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

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Utility for bitmap-based character class matching. Provides fast path optimization for common
 * character classes like \w, \d, \s.
 *
 * <p>Architecture: - Pre-computed bitmaps for ASCII range (0-255) using 4 longs - Runtime bitmap
 * generation for custom character classes - Optimized bytecode generation for O(1) membership tests
 */
public class CharClassBitmap {

  /** Character class types supported by fast paths. */
  public enum CharClassType {
    WORD, // \w = [a-zA-Z0-9_]
    DIGIT, // \d = [0-9]
    WHITESPACE, // \s = [ \t\n\r\f\v]
    CUSTOM // User-defined character class
  }

  /**
   * Pre-computed bitmap for word characters (\w = [a-zA-Z0-9_]). Uses 4 longs to cover ASCII range
   * (0-255), where each bit represents one character.
   */
  private static final long[] WORD_CHAR_BITMAP_ASCII = computeWordCharsASCII();

  /** Pre-computed bitmap for digit characters (\d = [0-9]). */
  private static final long[] DIGIT_CHAR_BITMAP_ASCII = computeDigitCharsASCII();

  /** Pre-computed bitmap for whitespace characters (\s = [ \t\n\r\f\v]). */
  private static final long[] WHITESPACE_CHAR_BITMAP_ASCII = computeWhitespaceCharsASCII();

  /**
   * Compute bitmap for word characters in ASCII range (0-255). Word characters: a-z, A-Z, 0-9, _
   */
  private static long[] computeWordCharsASCII() {
    long[] bitmap = new long[4]; // 256 bits / 64 = 4 longs

    // a-z: 97-122
    for (char c = 'a'; c <= 'z'; c++) {
      setBit(bitmap, c);
    }

    // A-Z: 65-90
    for (char c = 'A'; c <= 'Z'; c++) {
      setBit(bitmap, c);
    }

    // 0-9: 48-57
    for (char c = '0'; c <= '9'; c++) {
      setBit(bitmap, c);
    }

    // Underscore: 95
    setBit(bitmap, '_');

    return bitmap;
  }

  /** Compute bitmap for digit characters in ASCII range (0-255). Digit characters: 0-9 */
  private static long[] computeDigitCharsASCII() {
    long[] bitmap = new long[4]; // 256 bits / 64 = 4 longs

    // 0-9: 48-57
    for (char c = '0'; c <= '9'; c++) {
      setBit(bitmap, c);
    }

    return bitmap;
  }

  /**
   * Compute bitmap for whitespace characters in ASCII range (0-255). Whitespace: space, tab,
   * newline, carriage return, form feed, vertical tab
   */
  private static long[] computeWhitespaceCharsASCII() {
    long[] bitmap = new long[4]; // 256 bits / 64 = 4 longs

    setBit(bitmap, ' '); // 32
    setBit(bitmap, '\t'); // 9
    setBit(bitmap, '\n'); // 10
    setBit(bitmap, '\r'); // 13
    setBit(bitmap, '\f'); // 12
    setBit(bitmap, '\u000B'); // Vertical tab: 11

    return bitmap;
  }

  /**
   * Compute bitmap for a custom character class.
   *
   * @param chars String containing all characters in the class
   * @return Bitmap covering ASCII range (0-255)
   */
  public static long[] computeCustomBitmap(String chars) {
    long[] bitmap = new long[4];
    for (int i = 0; i < chars.length(); i++) {
      char c = chars.charAt(i);
      if (c < 256) { // Only handle ASCII for fast path
        setBit(bitmap, c);
      }
    }
    return bitmap;
  }

  /**
   * Compute bitmap for a character range.
   *
   * @param start Start character (inclusive)
   * @param end End character (inclusive)
   * @return Bitmap covering the range
   */
  public static long[] computeRangeBitmap(char start, char end) {
    long[] bitmap = new long[4];
    for (char c = start; c <= end && c < 256; c++) {
      setBit(bitmap, c);
    }
    return bitmap;
  }

  private static void setBit(long[] bitmap, char c) {
    int index = c >>> 6; // Divide by 64
    int bit = c & 0x3F; // Modulo 64
    bitmap[index] |= (1L << bit);
  }

  /** Get pre-computed bitmap for a character class type. */
  public static long[] getBitmapForType(CharClassType type) {
    switch (type) {
      case WORD:
        return WORD_CHAR_BITMAP_ASCII;
      case DIGIT:
        return DIGIT_CHAR_BITMAP_ASCII;
      case WHITESPACE:
        return WHITESPACE_CHAR_BITMAP_ASCII;
      default:
        throw new IllegalArgumentException("Use computeCustomBitmap for CUSTOM type");
    }
  }

  /**
   * Generate bytecode to check character class membership.
   *
   * <p>Expects on stack: char Leaves on stack: boolean (1 if matches, 0 otherwise) Uses temporary
   * local variable slots starting from tempVarBase
   *
   * @param mv MethodVisitor for bytecode generation
   * @param type Character class type
   * @param tempVarBase Base index for temporary variables (needs 3 slots: 2 for long + 1 for int)
   */
  public static void generateCharClassCheck(MethodVisitor mv, CharClassType type, int tempVarBase) {
    long[] bitmap = getBitmapForType(type);
    generateCharClassCheck(mv, bitmap, tempVarBase);
  }

  /**
   * Generate bytecode to check character class membership using custom bitmap.
   *
   * <p>Expects on stack: char Leaves on stack: boolean (1 if matches, 0 otherwise) Uses temporary
   * local variable slots starting from tempVarBase
   *
   * @param mv MethodVisitor for bytecode generation
   * @param bitmap Custom bitmap (4 longs for ASCII range)
   * @param tempVarBase Base index for temporary variables (needs 3 slots)
   */
  public static void generateCharClassCheck(MethodVisitor mv, long[] bitmap, int tempVarBase) {
    // Stack: char

    // Duplicate char for multiple uses
    mv.visitInsn(DUP);
    // Stack: char, char

    // Check if >= 256 (non-ASCII)
    BytecodeUtil.pushInt(mv, 256);
    // Stack: char, char, 256

    Label asciiCheck = new Label();
    mv.visitJumpInsn(IF_ICMPLT, asciiCheck);
    // If char >= 256, not a word char (we only handle ASCII fast path)
    mv.visitInsn(POP); // Remove the duplicate char
    mv.visitInsn(ICONST_0); // Return false
    Label endCheck = new Label();
    mv.visitJumpInsn(GOTO, endCheck);

    // ASCII fast path
    mv.visitLabel(asciiCheck);
    // Stack: char

    // Load the appropriate long from bitmap array
    // index = c >>> 6
    mv.visitInsn(DUP);
    BytecodeUtil.pushInt(mv, 6);
    mv.visitInsn(ISHR);
    // Stack: char, index

    // Switch on index to load the right long constant
    // Since we only have 4 longs (ASCII), generate 4 cases
    Label case0 = new Label();
    Label case1 = new Label();
    Label case2 = new Label();
    Label case3 = new Label();
    Label defaultCase = new Label();

    mv.visitTableSwitchInsn(0, 3, defaultCase, case0, case1, case2, case3);

    // Case 0: bitmap[0]
    mv.visitLabel(case0);
    mv.visitLdcInsn(bitmap[0]);
    Label afterSwitch = new Label();
    mv.visitJumpInsn(GOTO, afterSwitch);

    // Case 1: bitmap[1]
    mv.visitLabel(case1);
    mv.visitLdcInsn(bitmap[1]);
    mv.visitJumpInsn(GOTO, afterSwitch);

    // Case 2: bitmap[2]
    mv.visitLabel(case2);
    mv.visitLdcInsn(bitmap[2]);
    mv.visitJumpInsn(GOTO, afterSwitch);

    // Case 3: bitmap[3]
    mv.visitLabel(case3);
    mv.visitLdcInsn(bitmap[3]);
    mv.visitJumpInsn(GOTO, afterSwitch);

    // Default: return false (shouldn't happen for ASCII)
    mv.visitLabel(defaultCase);
    mv.visitInsn(POP); // Remove char
    mv.visitInsn(ICONST_0);
    mv.visitJumpInsn(GOTO, endCheck);

    // After switch, we have: char, bitmapLong
    mv.visitLabel(afterSwitch);
    // Stack: char, bitmapLong

    // Save bitmapLong to temp variable (uses slots tempVarBase and tempVarBase+1)
    mv.visitVarInsn(LSTORE, tempVarBase);
    // Stack: char

    // Compute bit position: c & 0x3F, and save it
    BytecodeUtil.pushInt(mv, 0x3F);
    mv.visitInsn(IAND);
    mv.visitVarInsn(ISTORE, tempVarBase + 2);
    // Stack: empty

    // Compute mask: 1L << bitPosition
    mv.visitInsn(LCONST_1);
    mv.visitVarInsn(ILOAD, tempVarBase + 2);
    mv.visitInsn(LSHL); // 1L << bitPosition
    // Stack: mask

    // Load bitmapLong and perform AND
    mv.visitVarInsn(LLOAD, tempVarBase);
    mv.visitInsn(LAND);
    // Stack: result

    // Convert to boolean: result != 0
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);
    // Stack: compareResult (-1, 0, or 1)

    // Convert to 1 or 0
    Label isFalse = new Label();
    mv.visitJumpInsn(IFEQ, isFalse);
    mv.visitInsn(ICONST_1);
    mv.visitJumpInsn(GOTO, endCheck);
    mv.visitLabel(isFalse);
    mv.visitInsn(ICONST_0);

    mv.visitLabel(endCheck);
    // Stack: boolean (1 or 0)
  }

  /**
   * Generate bytecode to check if a character is a word character (\w). Backward compatibility
   * wrapper.
   *
   * @deprecated Use generateCharClassCheck(mv, CharClassType.WORD, tempVarBase) instead
   */
  public static void generateIsWordCharCheck(MethodVisitor mv, int tempVarBase) {
    generateCharClassCheck(mv, CharClassType.WORD, tempVarBase);
  }

  /**
   * Generate bytecode for greedy character class matching loop. Matches pattern like \w+, \d+,
   * [a-z]+ (one or more characters in the class).
   *
   * @param mv MethodVisitor for bytecode generation
   * @param type Character class type
   * @param inputVar Local variable holding the String input
   * @param posVar Local variable holding current position (will be updated)
   * @param lenVar Local variable holding input length
   * @param noMatchLabel Label to jump to if no match (position unchanged)
   * @param tempVarBase Base index for temporary local variables (needs 3 slots)
   */
  public static void generateGreedyCharClassLoop(
      MethodVisitor mv,
      CharClassType type,
      int inputVar,
      int posVar,
      int lenVar,
      Label noMatchLabel,
      int tempVarBase) {
    long[] bitmap = getBitmapForType(type);
    generateGreedyCharClassLoop(mv, bitmap, inputVar, posVar, lenVar, noMatchLabel, tempVarBase);
  }

  /**
   * Generate bytecode for greedy character class matching loop with custom bitmap.
   *
   * @param mv MethodVisitor for bytecode generation
   * @param bitmap Custom bitmap (4 longs for ASCII range)
   * @param inputVar Local variable holding the String input
   * @param posVar Local variable holding current position (will be updated)
   * @param lenVar Local variable holding input length
   * @param noMatchLabel Label to jump to if no match (position unchanged)
   * @param tempVarBase Base index for temporary local variables (needs 3 slots)
   */
  public static void generateGreedyCharClassLoop(
      MethodVisitor mv,
      long[] bitmap,
      int inputVar,
      int posVar,
      int lenVar,
      Label noMatchLabel,
      int tempVarBase) {
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // Save start position to check if we matched at least one char
    int startPosVar = posVar + 1; // Use next available local var slot
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, startPosVar);

    // Loop: while (pos < len && charClassMatches(input.charAt(pos))) pos++;
    mv.visitLabel(loopStart);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Get char at pos: input.charAt(pos)
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // Check if char matches class
    generateCharClassCheck(mv, bitmap, tempVarBase);

    // If not matching, exit loop
    mv.visitJumpInsn(IFEQ, loopEnd);

    // Increment pos
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check if we matched at least one character
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitJumpInsn(IF_ICMPLE, noMatchLabel); // If pos <= startPos, no match
  }

  /**
   * Generate bytecode for greedy word character matching loop. Backward compatibility wrapper.
   *
   * @deprecated Use generateGreedyCharClassLoop(mv, CharClassType.WORD, ...) instead
   */
  public static void generateGreedyWordCharLoop(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label noMatchLabel, int tempVarBase) {
    generateGreedyCharClassLoop(
        mv, CharClassType.WORD, inputVar, posVar, lenVar, noMatchLabel, tempVarBase);
  }
}
