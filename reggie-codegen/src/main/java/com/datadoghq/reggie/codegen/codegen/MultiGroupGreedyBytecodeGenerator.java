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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.*;
import com.datadoghq.reggie.codegen.ast.AnchorNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates highly optimized bytecode for multi-group greedy patterns.
 *
 * <h3>Pattern Types</h3>
 *
 * Sequence of literals and capturing groups with character classes: {@code ([a-z]+)@([a-z]+)\.com},
 * {@code (\d{3})-(\d+)-(\d{4})}
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern ([a-z]+)@([a-z]+)\.com matching "foo@bar.com"
 * MatchResult match(String input) {
 *     if (input == null) return null;
 *     int pos = 0, len = input.length();
 *     int[] starts = new int[3], ends = new int[3];  // group 0,1,2
 *     starts[0] = 0;
 *
 *     // Segment 1: ([a-z]+) - variable group
 *     starts[1] = pos;
 *     while (pos < len && isLowerCase(input.charAt(pos))) pos++;
 *     if (pos == starts[1]) return null;  // Min 1 required
 *     ends[1] = pos;
 *
 *     // Segment 2: @ - literal
 *     if (pos >= len || input.charAt(pos) != '@') return null;
 *     pos++;
 *
 *     // Segment 3: ([a-z]+) - variable group
 *     starts[2] = pos;
 *     while (pos < len && isLowerCase(input.charAt(pos))) pos++;
 *     if (pos == starts[2]) return null;
 *     ends[2] = pos;
 *
 *     // Segment 4: .com - literal
 *     if (!input.regionMatches(pos, ".com", 0, 4)) return null;
 *     pos += 4;
 *
 *     if (pos != len) return null;
 *     ends[0] = pos;
 *     return new MatchResultImpl(input, starts, ends, 2);
 * }
 * }</pre>
 *
 * <h3>Performance</h3>
 *
 * O(n) time, O(1) space in hot path (group arrays allocated once). NO state machines, NO switches,
 * minimal allocations.
 */
public class MultiGroupGreedyBytecodeGenerator {

  private final List<Segment> segments;
  private final int groupCount;

  public MultiGroupGreedyBytecodeGenerator(MultiGroupGreedyInfo info, int groupCount) {
    this.segments = info.segments;
    this.groupCount = groupCount;
  }

  /**
   * Generate match() method - returns MatchResult with group positions. Structure: int pos = 0, len
   * = input.length(); int[] starts = new int[groupCount + 1], ends = new int[groupCount + 1];
   * starts[0] = 0;
   *
   * <p>// Process each segment for (Segment seg : segments) { if (literal) { match literal or
   * return null } if (variableGroup) { greedy scan and record positions } if (fixedGroup) { match
   * exact count and record positions } }
   *
   * <p>if (pos != len) return null; // Must consume all ends[0] = pos; return new
   * MatchResultImpl(input, starts, ends, groupCount);
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
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

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

    // int[] starts = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // starts[0] = 0;
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);

    // Process each segment
    for (Segment seg : segments) {
      if (seg instanceof LiteralSegment) {
        generateLiteralMatch(mv, (LiteralSegment) seg, inputVar, posVar, lenVar);
      } else if (seg instanceof VariableGroupSegment) {
        generateVariableGroupMatch(
            mv,
            (VariableGroupSegment) seg,
            inputVar,
            posVar,
            lenVar,
            startsVar,
            endsVar,
            allocator);
      } else if (seg instanceof FixedGroupSegment) {
        generateFixedGroupMatch(
            mv, (FixedGroupSegment) seg, inputVar, posVar, lenVar, startsVar, endsVar, allocator);
      } else if (seg instanceof PatternAnalyzer.AnchorSegment) {
        generateAnchorMatch(mv, (PatternAnalyzer.AnchorSegment) seg, posVar, lenVar);
      } else if (seg instanceof PatternAnalyzer.LiteralGroupSegment) {
        generateLiteralGroupMatch(
            mv, (PatternAnalyzer.LiteralGroupSegment) seg, inputVar, posVar, lenVar, startsVar);
      }
    }

    // Check that we consumed entire input: if (pos != len) return null;
    Label consumedAll = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPEQ, consumedAll);

    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(consumedAll);

    // ends[0] = pos;
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // return new MatchResultImpl(input, starts, ends, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
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
   * Generate code to match a literal segment. For single char: if (pos >= len || input.charAt(pos)
   * != 'c') return null; pos++; For multi-char: if (!input.regionMatches(pos, "literal", 0, n))
   * return null; pos += n;
   */
  private void generateLiteralMatch(
      MethodVisitor mv, LiteralSegment seg, int inputVar, int posVar, int lenVar) {
    String literal = seg.literal;

    if (literal.length() == 1) {
      // Single character
      char ch = literal.charAt(0);

      // if (pos >= len) return null;
      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasChar);

      // if (input.charAt(pos) != ch) return null;
      Label charMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPEQ, charMatches);

      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(charMatches);

      // pos++;
      mv.visitIincInsn(posVar, 1);
    } else {
      // Multi-character literal
      int len = literal.length();

      // if (pos + len > input.length()) return null;
      Label hasSpace = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLE, hasSpace);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasSpace);

      // if (!input.regionMatches(pos, literal, 0, len)) return null;
      Label regionMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitLdcInsn(literal); // literal string
      mv.visitInsn(ICONST_0); // literal offset
      pushInt(mv, len); // length
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFNE, regionMatches);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(regionMatches);

      // pos += len;
      mv.visitIincInsn(posVar, len);
    }
  }

  /**
   * Generate code to match a variable-length group. Structure: starts[groupNum] = pos; // Match
   * minMatches characters (required) for (int i = 0; i < minMatches; i++) { if (pos >= len ||
   * !matches(input.charAt(pos))) return null; pos++; } // Greedy: match as many as possible while
   * (pos < len && matches(input.charAt(pos))) pos++; ends[groupNum] = pos;
   */
  private void generateVariableGroupMatch(
      MethodVisitor mv,
      VariableGroupSegment seg,
      int inputVar,
      int posVar,
      int lenVar,
      int startsVar,
      int endsVar,
      LocalVarAllocator allocator) {
    // starts[groupNum] = pos;
    mv.visitVarInsn(ALOAD, startsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    int charVar = allocator.allocate();

    // Match minMatches characters (required)
    if (seg.minMatches > 0) {
      // Optimization: Only unroll up to 20 iterations to prevent method bloat
      // For larger counts, generate a runtime loop
      final int UNROLL_THRESHOLD = 20;

      if (seg.minMatches <= UNROLL_THRESHOLD) {
        // Full unrolling for small counts (optimal performance)
        for (int i = 0; i < seg.minMatches; i++) {
          // if (pos >= len) return null;
          Label hasChar = new Label();
          mv.visitVarInsn(ILOAD, posVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitJumpInsn(IF_ICMPLT, hasChar);

          // DEBUG
          mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
          mv.visitLdcInsn("FAIL: min match - pos >= len");
          mv.visitMethodInsn(
              INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);

          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
          mv.visitLabel(hasChar);

          // char c = input.charAt(pos);
          mv.visitVarInsn(ALOAD, inputVar);
          mv.visitVarInsn(ILOAD, posVar);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, charVar);

          // if (!matches(c)) return null;
          Label charMatches = new Label();
          Label charFails = new Label();
          generateInlineCharSetCheck(mv, charVar, seg.charset, seg.negated, charFails);
          // Char matches, jump over the return
          mv.visitJumpInsn(GOTO, charMatches);

          mv.visitLabel(charFails);
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);

          mv.visitLabel(charMatches);
          // pos++;
          mv.visitIincInsn(posVar, 1);
        }
      } else {
        // Runtime loop for large counts (prevents method bloat)
        // int minEnd = pos + minMatches;
        int minEndVar = allocator.allocate();
        mv.visitVarInsn(ILOAD, posVar);
        pushInt(mv, seg.minMatches);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, minEndVar);

        // while (pos < minEnd && pos < len)
        Label minLoop = new Label();
        Label minLoopEnd = new Label();
        mv.visitLabel(minLoop);

        // if (pos >= minEnd) break;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, minEndVar);
        mv.visitJumpInsn(IF_ICMPGE, minLoopEnd);

        // if (pos >= len) return null;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        Label hasChar = new Label();
        mv.visitJumpInsn(IF_ICMPLT, hasChar);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(hasChar);

        // char c = input.charAt(pos);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, charVar);

        // if (!matches(c)) return null;
        Label charMatches = new Label();
        Label charFails = new Label();
        generateInlineCharSetCheck(mv, charVar, seg.charset, seg.negated, charFails);
        mv.visitJumpInsn(GOTO, charMatches);

        mv.visitLabel(charFails);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);

        mv.visitLabel(charMatches);
        // pos++;
        mv.visitIincInsn(posVar, 1);
        mv.visitJumpInsn(GOTO, minLoop);

        mv.visitLabel(minLoopEnd);
      }
    }

    // Greedy: match as many as possible
    // while (pos < len && matches(input.charAt(pos))) pos++;
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // if (pos >= len) break;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // if (!matches(c)) break;
    generateInlineCharSetCheck(mv, charVar, seg.charset, seg.negated, loopEnd);

    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // ends[groupNum] = pos;
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);
  }

  /**
   * Generate code to match a fixed-length group. Structure: starts[groupNum] = pos; for (int i = 0;
   * i < length; i++) { if (pos >= len || !matches(input.charAt(pos))) return null; pos++; }
   * ends[groupNum] = pos;
   */
  private void generateFixedGroupMatch(
      MethodVisitor mv,
      FixedGroupSegment seg,
      int inputVar,
      int posVar,
      int lenVar,
      int startsVar,
      int endsVar,
      LocalVarAllocator allocator) {
    // starts[groupNum] = pos;
    mv.visitVarInsn(ALOAD, startsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    int charVar = allocator.allocate();

    // Match exactly 'length' characters
    for (int i = 0; i < seg.length; i++) {
      // if (pos >= len) return null;
      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasChar);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar);

      // if (!matches(c)) return null;
      Label charMatches = new Label();
      Label charFails = new Label();
      generateInlineCharSetCheck(mv, charVar, seg.charset, seg.negated, charFails);
      mv.visitJumpInsn(GOTO, charMatches);

      mv.visitLabel(charFails);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);

      mv.visitLabel(charMatches);
      // pos++;
      mv.visitIincInsn(posVar, 1);
    }

    // ends[groupNum] = pos;
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);
  }

  /**
   * Generate inline character set check. If the character matches, continue; otherwise jump to
   * failLabel.
   *
   * @param charVar variable containing the character to check
   * @param charset the character set to match against
   * @param negated if true, invert the logic
   * @param failLabel label to jump to if check fails
   */
  private void generateInlineCharSetCheck(
      MethodVisitor mv, int charVar, CharSet charset, boolean negated, Label failLabel) {
    if (charset.isSingleChar()) {
      // Single character check
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      if (negated) {
        // Negated: if (c == target) fail;
        mv.visitJumpInsn(IF_ICMPEQ, failLabel);
      } else {
        // Normal: if (c != target) fail;
        mv.visitJumpInsn(IF_ICMPNE, failLabel);
      }
    } else if (charset.isSimpleRange()) {
      // Single range check
      CharSet.Range range = charset.getSimpleRange();

      if (negated) {
        // Negated range: if (c >= start && c <= end) fail;
        Label notInRange = new Label();
        // if (c < start) goto notInRange (continue);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, notInRange);
        // if (c <= end) fail;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, failLabel);
        mv.visitLabel(notInRange);
      } else {
        // Normal range: if (c < start || c > end) fail;
        // if (c < start) fail;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, failLabel);
        // if (c > end) fail;
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPGT, failLabel);
      }
    } else {
      // Multiple ranges
      if (negated) {
        // Negated: if ANY range matches, fail
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          // if (c < range.start) goto tryNext;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          // if (c <= range.end) fail;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, failLabel);
          mv.visitLabel(tryNext);
        }
        // No range matched - continue (success for negated)
      } else {
        // Normal: if NO range matches, fail
        Label matchFound = new Label();
        for (CharSet.Range range : charset.getRanges()) {
          Label tryNext = new Label();
          // if (c < range.start) goto tryNext;
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.start);
          mv.visitJumpInsn(IF_ICMPLT, tryNext);
          // if (c <= range.end) goto matchFound (success);
          mv.visitVarInsn(ILOAD, charVar);
          pushInt(mv, (int) range.end);
          mv.visitJumpInsn(IF_ICMPLE, matchFound);
          mv.visitLabel(tryNext);
        }
        // No range matched - fail
        mv.visitJumpInsn(GOTO, failLabel);
        mv.visitLabel(matchFound);
      }
    }
  }

  /** Generate matches() method - boolean check only (no groups). */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Delegate to match() and check if not null
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    Label returnTrue = new Label();
    mv.visitJumpInsn(IFNONNULL, returnTrue);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findMatch() method - delegates to findMatchFrom(input, 0). */
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

  /**
   * Generate findMatchFrom() method - scan for match starting from given position. Optimized: add
   * first-character pre-check to avoid substring allocation.
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

    // Slot 0 = this, slot 1 = input, slot 2 = start
    int inputVar = 1;
    int startVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int lenVar = allocator.allocate();
    int resultVar = allocator.allocate();

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Scan loop: for (int pos = start; pos < len; pos++)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // if (start >= len) return null;
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // OPTIMIZATION: First-character pre-check before substring allocation
    // Check if first segment matches at this position
    if (!segments.isEmpty() && segments.get(0) instanceof LiteralSegment) {
      LiteralSegment firstLit = (LiteralSegment) segments.get(0);
      if (firstLit.literal.length() == 1) {
        // Single character: if (input.charAt(start) != firstChar) skip
        Label firstCharMatches = new Label();
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, startVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        pushInt(mv, (int) firstLit.literal.charAt(0));
        mv.visitJumpInsn(IF_ICMPEQ, firstCharMatches);

        // First char doesn't match - skip to next position
        mv.visitIincInsn(startVar, 1);
        mv.visitJumpInsn(GOTO, loopStart);

        mv.visitLabel(firstCharMatches);
      }
    }

    // Try match at this position using matchFromPosition helper
    // MatchResult result = matchFromPosition(input, start, len);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchFromPosition",
        "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, resultVar);

    // if (result != null) return it
    Label tryNext = new Label();
    mv.visitVarInsn(ALOAD, resultVar);
    mv.visitJumpInsn(IFNULL, tryNext);
    mv.visitVarInsn(ALOAD, resultVar);
    mv.visitInsn(ARETURN);

    mv.visitLabel(tryNext);

    // start++;
    mv.visitIincInsn(startVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // No match found
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matchFromPosition() helper - inline matching starting at given position. This
   * eliminates the O(N) substring allocation by matching directly in the input.
   */
  public void generateMatchFromPositionMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchFromPosition",
            "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = startPos, slot 3 = len
    int inputVar = 1;
    int startPosVar = 2;
    int lenVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int posVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    // int pos = startPos;
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int[] starts = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // starts[0] = startPos;
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitInsn(IASTORE);

    // Process each segment (similar to match() but using pos instead of 0)
    for (Segment seg : segments) {
      if (seg instanceof LiteralSegment) {
        generateLiteralMatchInline(mv, (LiteralSegment) seg, posVar, lenVar, inputVar);
      } else if (seg instanceof VariableGroupSegment) {
        generateVariableGroupMatchInline(
            mv, (VariableGroupSegment) seg, posVar, lenVar, inputVar, startsVar, endsVar);
      } else if (seg instanceof FixedGroupSegment) {
        generateFixedGroupMatchInline(
            mv, (FixedGroupSegment) seg, posVar, lenVar, inputVar, startsVar, endsVar);
      } else if (seg instanceof PatternAnalyzer.AnchorSegment) {
        generateAnchorMatchInline(
            mv, (PatternAnalyzer.AnchorSegment) seg, posVar, lenVar, startPosVar);
      } else if (seg instanceof PatternAnalyzer.LiteralGroupSegment) {
        generateLiteralGroupMatchInline(
            mv,
            (PatternAnalyzer.LiteralGroupSegment) seg,
            posVar,
            lenVar,
            inputVar,
            startsVar,
            endsVar);
      }
    }

    // Check that we didn't exceed length: if (pos > len) return null;
    Label consumedValid = new Label();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPLE, consumedValid);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(consumedValid);

    // ends[0] = pos;
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // return new MatchResultImpl(input, starts, ends, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
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

  // Helper methods for inline matching (variants of the existing methods)
  private void generateLiteralMatchInline(
      MethodVisitor mv, LiteralSegment seg, int posVar, int lenVar, int inputVar) {
    String literal = seg.literal;

    if (literal.length() == 1) {
      char ch = literal.charAt(0);

      // if (pos >= len) return null;
      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasChar);

      // if (input.charAt(pos) != ch) return null;
      Label charMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPEQ, charMatches);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(charMatches);

      // pos++;
      mv.visitIincInsn(posVar, 1);
    } else {
      int len = literal.length();

      // if (pos + len > input.length()) return null;
      Label hasSpace = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLE, hasSpace);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasSpace);

      // if (!input.regionMatches(pos, literal, 0, len)) return null;
      Label regionMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitLdcInsn(literal);
      mv.visitInsn(ICONST_0);
      pushInt(mv, len);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFNE, regionMatches);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(regionMatches);

      // pos += len;
      mv.visitIincInsn(posVar, len);
    }
  }

  private void generateVariableGroupMatchInline(
      MethodVisitor mv,
      VariableGroupSegment seg,
      int posVar,
      int lenVar,
      int inputVar,
      int startsVar,
      int endsVar) {
    // starts[groupNum] = pos;
    mv.visitVarInsn(ALOAD, startsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // Match minMatches characters (required)
    if (seg.minMatches > 0) {
      for (int i = 0; i < seg.minMatches; i++) {
        Label hasChar = new Label();
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPLT, hasChar);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(hasChar);

        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, 7); // c in var 7

        Label charMatches = new Label();
        Label charFails = new Label();
        generateInlineCharSetCheck(mv, 7, seg.charset, seg.negated, charFails);
        mv.visitJumpInsn(GOTO, charMatches);

        mv.visitLabel(charFails);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);

        mv.visitLabel(charMatches);

        mv.visitIincInsn(posVar, 1);
      }
    }

    // Greedy: match as many as possible
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, 7);

    generateInlineCharSetCheck(mv, 7, seg.charset, seg.negated, loopEnd);

    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // ends[groupNum] = pos;
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);
  }

  private void generateFixedGroupMatchInline(
      MethodVisitor mv,
      FixedGroupSegment seg,
      int posVar,
      int lenVar,
      int inputVar,
      int startsVar,
      int endsVar) {
    // starts[groupNum] = pos;
    mv.visitVarInsn(ALOAD, startsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);

    // Match exactly 'length' characters
    for (int i = 0; i < seg.length; i++) {
      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasChar);

      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, 7);

      Label charMatches = new Label();
      Label charFails = new Label();
      generateInlineCharSetCheck(mv, 7, seg.charset, seg.negated, charFails);
      mv.visitJumpInsn(GOTO, charMatches);

      mv.visitLabel(charFails);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);

      mv.visitLabel(charMatches);

      mv.visitIincInsn(posVar, 1);
    }

    // ends[groupNum] = pos;
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, seg.groupNumber);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);
  }

  /** Generate find() method - delegates to findFrom(input, 0). */
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

  /**
   * Generate findFrom() method - scan for first matching position. Returns position of match start,
   * or -1 if not found.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start
    int inputVar = 1;
    int startVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int resultVar = allocator.allocate();

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

    // Use findMatchFrom() and return position or -1
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, resultVar);

    // if (result == null) return -1;
    Label hasMatch = new Label();
    mv.visitVarInsn(ALOAD, resultVar);
    mv.visitJumpInsn(IFNONNULL, hasMatch);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    // return result.start(0);
    mv.visitLabel(hasMatch);
    mv.visitVarInsn(ALOAD, resultVar);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "start", "(I)I", true);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchesBounded() method - check if bounded region matches. */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start, slot 3 = end
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int substringVar = allocator.allocate();

    // Extract substring and delegate to matches()
    // String substr = input.subSequence(start, end).toString();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ASTORE, substringVar);

    // return matches(substr);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, substringVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchBounded() method - return MatchResult for bounded region. */
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
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int substringVar = allocator.allocate();

    // Extract substring and delegate to match()
    // String substr = input.subSequence(start, end).toString();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    mv.visitVarInsn(ASTORE, substringVar);

    // return match(substr);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, substringVar);
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
   * Generate findBoundsFrom() method - allocation-free boundary detection. Returns match boundaries
   * in the provided int[] array instead of allocating MatchResult.
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

    // Scan loop: for (int pos = start; pos < len; pos++)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // if (start >= len) return false;
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 4); // len
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // OPTIMIZATION: First-character pre-check before trying match
    Label skipToNext = new Label();
    if (!segments.isEmpty()) {
      Segment firstSeg = segments.get(0);

      if (firstSeg instanceof LiteralSegment) {
        LiteralSegment firstLit = (LiteralSegment) firstSeg;
        if (firstLit.literal.length() == 1) {
          // Single literal character: if (input.charAt(start) != firstChar) skip
          Label firstCharMatches = new Label();
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, 2);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, (int) firstLit.literal.charAt(0));
          mv.visitJumpInsn(IF_ICMPEQ, firstCharMatches);

          // First char doesn't match - skip to next position
          mv.visitJumpInsn(GOTO, skipToNext);

          mv.visitLabel(firstCharMatches);
        } else if (firstLit.literal.length() > 1) {
          // Multi-character literal: use indexOf for better performance
          // int foundPos = input.indexOf(literal, start);
          // if (foundPos < 0) return false;
          // start = foundPos;
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitLdcInsn(firstLit.literal);
          mv.visitVarInsn(ILOAD, 2); // start
          mv.visitMethodInsn(
              INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
          mv.visitVarInsn(ISTORE, 2); // start = foundPos

          // if (start < 0) return false
          Label foundLiteral = new Label();
          mv.visitVarInsn(ILOAD, 2);
          mv.visitJumpInsn(IFGE, foundLiteral);
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
          mv.visitLabel(foundLiteral);
        }
      } else if (firstSeg instanceof VariableGroupSegment) {
        VariableGroupSegment varGroup = (VariableGroupSegment) firstSeg;
        // For minMatches == 0, pattern can start with anything - no optimization
        if (varGroup.minMatches > 0 && !varGroup.negated) {
          // Variable group must match at least once
          // Check if first char is in charset
          // char c = input.charAt(start);
          // if (!charset.contains(c)) skip
          Label charsetMatches = new Label();

          // if (start >= len) skip (no char to check)
          mv.visitVarInsn(ILOAD, 2); // start
          mv.visitVarInsn(ILOAD, 4); // len
          mv.visitJumpInsn(IF_ICMPGE, skipToNext);

          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, 2); // start
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, 5); // char c in slot 5

          generateCharSetCheck(mv, varGroup.charset, 5, skipToNext);

          mv.visitLabel(charsetMatches);
        }
      } else if (firstSeg instanceof FixedGroupSegment) {
        FixedGroupSegment fixedGroup = (FixedGroupSegment) firstSeg;
        if (!fixedGroup.negated) {
          // Fixed group must match - check if first char is in charset
          Label charsetMatches = new Label();

          // if (start >= len) skip
          mv.visitVarInsn(ILOAD, 2); // start
          mv.visitVarInsn(ILOAD, 4); // len
          mv.visitJumpInsn(IF_ICMPGE, skipToNext);

          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, 2); // start
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, 5); // char c in slot 5

          generateCharSetCheck(mv, fixedGroup.charset, 5, skipToNext);

          mv.visitLabel(charsetMatches);
        }
      }
    }

    // Try match at this position using tryMatchBoundsFromPosition helper
    // boolean found = tryMatchBoundsFromPosition(input, start, len, bounds);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "tryMatchBoundsFromPosition",
        "(Ljava/lang/String;II[I)Z",
        false);

    // if (found) return true
    // S: [I] -> []
    mv.visitJumpInsn(IFEQ, skipToNext);
    // S: []
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    // Define skipToNext label here - used by both optimization skips and match failures
    // S: []
    mv.visitLabel(skipToNext);

    // start++;
    // S: [] -> []
    mv.visitIincInsn(2, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // No match found
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate tryMatchBoundsFromPosition() helper - allocation-free inline matching. Returns boolean
   * and fills bounds array instead of allocating MatchResult.
   */
  public void generateTryMatchBoundsFromPositionMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC, "tryMatchBoundsFromPosition", "(Ljava/lang/String;II[I)Z", null, null);
    mv.visitCode();

    // int pos = startPos;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, 5); // pos in var 5

    // Process each segment (same logic as matchFromPosition but without group tracking)
    for (Segment seg : segments) {
      if (seg instanceof LiteralSegment) {
        // Use the existing inline helper but it returns null on failure
        // We need to catch that and return false instead
        generateLiteralMatchInlineForBounds(mv, (LiteralSegment) seg, 5, 3, 1);
      } else if (seg instanceof VariableGroupSegment) {
        generateVariableGroupMatchInlineForBounds(mv, (VariableGroupSegment) seg, 5, 3, 1);
      } else if (seg instanceof FixedGroupSegment) {
        generateFixedGroupMatchInlineForBounds(mv, (FixedGroupSegment) seg, 5, 3, 1);
      } else if (seg instanceof PatternAnalyzer.AnchorSegment) {
        generateAnchorMatchInlineForBounds(mv, (PatternAnalyzer.AnchorSegment) seg, 5, 3, 2);
      } else if (seg instanceof PatternAnalyzer.LiteralGroupSegment) {
        generateLiteralGroupMatchInlineForBounds(
            mv, (PatternAnalyzer.LiteralGroupSegment) seg, 5, 3, 1);
      }
    }

    // Check that we didn't exceed length: if (pos > len) return false;
    Label consumedValid = new Label();
    mv.visitVarInsn(ILOAD, 5); // pos
    mv.visitVarInsn(ILOAD, 3); // len
    mv.visitJumpInsn(IF_ICMPLE, consumedValid);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(consumedValid);

    // bounds[0] = startPos;
    mv.visitVarInsn(ALOAD, 4);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 2); // startPos
    mv.visitInsn(IASTORE);

    // bounds[1] = pos;
    mv.visitVarInsn(ALOAD, 4);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, 5); // pos
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // Allocation-free versions of inline matching helpers (return false instead of null)
  private void generateLiteralMatchInlineForBounds(
      MethodVisitor mv, LiteralSegment seg, int posVar, int lenVar, int inputVar) {
    String literal = seg.literal;

    if (literal.length() == 1) {
      char ch = literal.charAt(0);

      // if (pos >= len) return false;
      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(hasChar);

      // if (input.charAt(pos) != ch) return false;
      Label charMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPEQ, charMatches);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(charMatches);

      // pos++;
      mv.visitIincInsn(posVar, 1);
    } else {
      int len = literal.length();

      // if (pos + len > input.length()) return false;
      Label hasSpace = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLE, hasSpace);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(hasSpace);

      // if (!input.regionMatches(pos, literal, 0, len)) return false;
      Label regionMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitLdcInsn(literal);
      mv.visitInsn(ICONST_0);
      pushInt(mv, len);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFNE, regionMatches);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(regionMatches);

      // pos += len;
      mv.visitIincInsn(posVar, len);
    }
  }

  private void generateVariableGroupMatchInlineForBounds(
      MethodVisitor mv, VariableGroupSegment seg, int posVar, int lenVar, int inputVar) {
    // Skip group position tracking - we don't need it for bounds-only matching

    // Match minMatches characters (required)
    if (seg.minMatches > 0) {
      for (int i = 0; i < seg.minMatches; i++) {
        Label hasChar = new Label();
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPLT, hasChar);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitLabel(hasChar);

        // char c = input.charAt(pos);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, 6); // c in var 6

        // if (!charset.matches(c)) return false;
        Label charMatches = new Label();
        Label charFails = new Label();
        generateInlineCharSetCheck(mv, 6, seg.charset, seg.negated, charFails);
        mv.visitJumpInsn(GOTO, charMatches);

        mv.visitLabel(charFails);
        // Char doesn't match - return false
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);

        mv.visitLabel(charMatches);
        // Char matches - continue
        // pos++;
        mv.visitIincInsn(posVar, 1);
      }
    }

    // Greedy scan: match as many as possible
    Label greedyLoop = new Label();
    Label greedyEnd = new Label();

    mv.visitLabel(greedyLoop);

    // if (pos >= len) goto greedyEnd;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, greedyEnd);

    // char c = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, 6); // c in var 6

    // if (!charset.matches(c)) goto greedyEnd;
    generateInlineCharSetCheck(mv, 6, seg.charset, seg.negated, greedyEnd);

    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, greedyLoop);

    mv.visitLabel(greedyEnd);
  }

  private void generateFixedGroupMatchInlineForBounds(
      MethodVisitor mv, FixedGroupSegment seg, int posVar, int lenVar, int inputVar) {
    // Skip group position tracking

    // Match exactly seg.length characters
    for (int i = 0; i < seg.length; i++) {
      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(hasChar);

      // char c = input.charAt(pos);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, 6); // c in var 6

      // if (!charset.matches(c)) return false;
      Label charMatches = new Label();
      Label charFails = new Label();
      generateInlineCharSetCheck(mv, 6, seg.charset, seg.negated, charFails);
      mv.visitJumpInsn(GOTO, charMatches);

      mv.visitLabel(charFails);
      // Char doesn't match - return false
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);

      mv.visitLabel(charMatches);
      // Char matches - continue
      // pos++;
      mv.visitIincInsn(posVar, 1);
    }
  }

  /** Generate bytecode for anchor segment in match() method. */
  private void generateAnchorMatch(
      MethodVisitor mv, PatternAnalyzer.AnchorSegment seg, int posVar, int lenVar) {
    if (seg.type == AnchorNode.Type.START) {
      // if (pos != 0) return null;
      Label isStart = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitJumpInsn(IFEQ, isStart);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(isStart);
      // S: []
    } else if (seg.type == AnchorNode.Type.END) {
      // if (pos != len) return null;
      Label isEnd = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPEQ, isEnd);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(isEnd);
      // S: []
    }
    // Anchor matched - continue
  }

  /** Generate bytecode for anchor segment inline (for matchFromPosition). */
  private void generateAnchorMatchInline(
      MethodVisitor mv,
      PatternAnalyzer.AnchorSegment seg,
      int posVar,
      int lenVar,
      int startOffsetVar) {
    if (seg.type == AnchorNode.Type.START) {
      // if (pos != startOffset) return null;
      Label isStart = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, startOffsetVar);
      mv.visitJumpInsn(IF_ICMPEQ, isStart);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(isStart);
    } else if (seg.type == AnchorNode.Type.END) {
      // if (pos != len) return null;
      Label isEnd = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPEQ, isEnd);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(isEnd);
    }
  }

  /** Generate bytecode for anchor segment (for findBoundsFrom). */
  private void generateAnchorMatchInlineForBounds(
      MethodVisitor mv,
      PatternAnalyzer.AnchorSegment seg,
      int posVar,
      int lenVar,
      int startOffsetVar) {
    if (seg.type == AnchorNode.Type.START) {
      // if (pos != startOffset) return false;
      Label isStart = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, startOffsetVar);
      mv.visitJumpInsn(IF_ICMPEQ, isStart);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(isStart);
    } else if (seg.type == AnchorNode.Type.END) {
      // if (pos != len) return false;
      Label isEnd = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPEQ, isEnd);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(isEnd);
    }
  }

  /**
   * Generate bytecode for literal group segment in match() method. Matches a literal string and
   * captures it if groupNumber > 0.
   */
  private void generateLiteralGroupMatch(
      MethodVisitor mv,
      PatternAnalyzer.LiteralGroupSegment seg,
      int inputVar,
      int posVar,
      int lenVar,
      int startsVar) {
    String literal = seg.literal;
    int groupNum = seg.groupNumber;

    // Save group start position if capturing
    if (groupNum > 0) {
      // groups[groupNum * 2] = pos;
      mv.visitVarInsn(ALOAD, startsVar);
      pushInt(mv, groupNum * 2);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);
    }

    // Match literal using existing logic (reuse generateLiteralMatch pattern)
    if (literal.length() == 1) {
      char ch = literal.charAt(0);

      // if (pos >= len) return null;
      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasChar);

      // if (input.charAt(pos) != ch) return null;
      Label charMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPEQ, charMatches);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(charMatches);

      // pos++;
      mv.visitIincInsn(posVar, 1);
    } else {
      int len = literal.length();

      // if (pos + len > this.len) return null;
      Label hasEnoughChars = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLE, hasEnoughChars);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasEnoughChars);

      // if (!input.startsWith(literal, pos)) return null;
      Label literalMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitLdcInsn(literal);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;I)Z", false);
      mv.visitJumpInsn(IFNE, literalMatches);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(literalMatches);

      // pos += len;
      mv.visitIincInsn(posVar, len);
    }

    // Save group end position if capturing
    if (groupNum > 0) {
      // groups[groupNum * 2 + 1] = pos;
      mv.visitVarInsn(ALOAD, startsVar);
      pushInt(mv, groupNum * 2 + 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);
    }
  }

  /** Generate bytecode for literal group segment inline (for matchFromPosition). */
  private void generateLiteralGroupMatchInline(
      MethodVisitor mv,
      PatternAnalyzer.LiteralGroupSegment seg,
      int posVar,
      int lenVar,
      int inputVar,
      int groupsVar,
      int unusedVar) {
    String literal = seg.literal;
    int groupNum = seg.groupNumber;

    // Save group start position if capturing
    if (groupNum > 0) {
      mv.visitVarInsn(ALOAD, groupsVar);
      pushInt(mv, groupNum * 2);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);
    }

    // Match literal
    if (literal.length() == 1) {
      char ch = literal.charAt(0);

      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasChar);

      Label charMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPEQ, charMatches);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(charMatches);

      mv.visitIincInsn(posVar, 1);
    } else {
      int len = literal.length();

      Label hasEnoughChars = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLE, hasEnoughChars);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(hasEnoughChars);

      Label literalMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitLdcInsn(literal);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;I)Z", false);
      mv.visitJumpInsn(IFNE, literalMatches);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
      mv.visitLabel(literalMatches);

      mv.visitIincInsn(posVar, len);
    }

    // Save group end position if capturing
    if (groupNum > 0) {
      mv.visitVarInsn(ALOAD, groupsVar);
      pushInt(mv, groupNum * 2 + 1);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitInsn(IASTORE);
    }
  }

  /**
   * Generate bytecode for literal group segment (for findBoundsFrom). No group capture needed in
   * bounds-finding.
   */
  private void generateLiteralGroupMatchInlineForBounds(
      MethodVisitor mv,
      PatternAnalyzer.LiteralGroupSegment seg,
      int posVar,
      int lenVar,
      int inputVar) {
    String literal = seg.literal;

    // Match literal (no group capture)
    if (literal.length() == 1) {
      char ch = literal.charAt(0);

      Label hasChar = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLT, hasChar);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(hasChar);

      Label charMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPEQ, charMatches);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(charMatches);

      mv.visitIincInsn(posVar, 1);
    } else {
      int len = literal.length();

      Label hasEnoughChars = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPLE, hasEnoughChars);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(hasEnoughChars);

      Label literalMatches = new Label();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitLdcInsn(literal);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "startsWith", "(Ljava/lang/String;I)Z", false);
      mv.visitJumpInsn(IFNE, literalMatches);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(literalMatches);

      mv.visitIincInsn(posVar, len);
    }
  }

  /** Utility method to generate debug logging bytecode. Generates: System.out.println(message); */
  private void debugLog(MethodVisitor mv, String message) {
    mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
    mv.visitLdcInsn(message);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
  }

  /**
   * Utility method to generate debug logging bytecode with a dynamic value. Generates:
   * System.out.println(prefix + value);
   */
  private void debugLogWithValue(MethodVisitor mv, String prefix, int varSlot, String type) {
    mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
    mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
    mv.visitInsn(DUP);
    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
    mv.visitLdcInsn(prefix);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        "java/lang/StringBuilder",
        "append",
        "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
        false);

    // Load the variable and append based on type
    if ("I".equals(type)) {
      mv.visitVarInsn(ILOAD, varSlot);
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "java/lang/StringBuilder",
          "append",
          "(I)Ljava/lang/StringBuilder;",
          false);
    } else if ("C".equals(type)) {
      mv.visitVarInsn(ILOAD, varSlot);
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "java/lang/StringBuilder",
          "append",
          "(C)Ljava/lang/StringBuilder;",
          false);
    } else if ("Ljava/lang/String;".equals(type)) {
      mv.visitVarInsn(ALOAD, varSlot);
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          "java/lang/StringBuilder",
          "append",
          "(Ljava/lang/String;)Ljava/lang/StringBuilder;",
          false);
    }

    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
  }

  /**
   * Generate bytecode to check if a character is in a CharSet. If the character is NOT in the set,
   * jumps to notInSetLabel.
   *
   * @param mv MethodVisitor for bytecode generation
   * @param charSet The CharSet to check against
   * @param charVarSlot Local variable slot containing the char to check
   * @param notInSetLabel Label to jump to if char is NOT in the set
   */
  private void generateCharSetCheck(
      MethodVisitor mv, CharSet charSet, int charVarSlot, Label notInSetLabel) {
    if (charSet.isSingleChar()) {
      // Single character: if (c != char) goto notInSet
      mv.visitVarInsn(ILOAD, charVarSlot); // load char c
      pushInt(mv, charSet.getSingleChar());
      mv.visitJumpInsn(IF_ICMPNE, notInSetLabel); // if c != target, skip
    } else if (charSet.isSimpleRange()) {
      // Single range: if (c < start || c > end) goto notInSet
      CharSet.Range range = charSet.getSimpleRange();

      // Check c < start: if true, goto notInSet
      mv.visitVarInsn(ILOAD, charVarSlot); // c
      pushInt(mv, range.start);
      mv.visitJumpInsn(IF_ICMPLT, notInSetLabel);

      // Check c > end: if true, goto notInSet
      mv.visitVarInsn(ILOAD, charVarSlot); // c
      pushInt(mv, range.end);
      mv.visitJumpInsn(IF_ICMPGT, notInSetLabel);
    } else {
      // Multiple ranges: check each range, if any matches, continue; else goto notInSet
      Label inSet = new Label(); // If we find a match, jump here to continue

      for (CharSet.Range range : charSet.getRanges()) {
        // For each range, check: if (c >= start && c <= end) goto inSet
        Label nextRange = new Label();

        // Check c < start: if true, try next range
        mv.visitVarInsn(ILOAD, charVarSlot); // c
        pushInt(mv, range.start);
        mv.visitJumpInsn(IF_ICMPLT, nextRange);

        // Check c <= end: if true, we're in this range, goto inSet
        mv.visitVarInsn(ILOAD, charVarSlot); // c
        pushInt(mv, range.end);
        mv.visitJumpInsn(IF_ICMPLE, inSet); // c is in range!

        // Not in this range, try next
        mv.visitLabel(nextRange);
      }

      // Checked all ranges, none matched: goto notInSet
      mv.visitJumpInsn(GOTO, notInSetLabel);

      // Character is in one of the ranges: continue
      mv.visitLabel(inSet);
    }
  }
}
