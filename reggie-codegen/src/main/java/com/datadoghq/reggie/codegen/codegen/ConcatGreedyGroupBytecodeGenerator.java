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

import com.datadoghq.reggie.codegen.analysis.ConcatGreedyGroupInfo;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates optimized bytecode for concat+greedy group patterns.
 *
 * <h3>Pattern Types</h3>
 *
 * Prefix + single greedy capturing group + suffix: {@code a(b*)}, {@code x(y*)z}, {@code
 * foo(bar+)baz}
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern a(b*)c
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int pos = 0, len = input.length();
 *
 *     // Match prefix 'a'
 *     if (pos >= len || input.charAt(pos) != 'a') return false;
 *     pos++;
 *
 *     // Greedy capture group (b*)
 *     int group1Start = pos;
 *     while (pos < len && input.charAt(pos) == 'b') {
 *         pos++;
 *     }
 *     int group1End = pos;  // May equal group1Start (zero-length capture)
 *
 *     // Match suffix 'c'
 *     if (pos >= len || input.charAt(pos) != 'c') return false;
 *     pos++;
 *
 *     return pos == len;
 * }
 * }</pre>
 *
 * <h3>Key Feature</h3>
 *
 * Correctly handles zero-length group captures: {@code a(b*)} matching "ac" captures "" (empty
 * string) at the position between 'a' and 'c'.
 */
public class ConcatGreedyGroupBytecodeGenerator {

  private final ConcatGreedyGroupInfo info;
  private final String className;

  public ConcatGreedyGroupBytecodeGenerator(ConcatGreedyGroupInfo info, String className) {
    this.info = info;
    this.className = className;
  }

  /** Generate all required methods for ReggieMatcher. */
  public void generate(ClassWriter cw) {
    generateMatchesMethod(cw);
    generateMatchMethod(cw);
    generateFindMethod(cw);
    generateFindFromMethod(cw);
    generateFindMatchMethod(cw);
    generateFindMatchFromMethod(cw);
    generateMatchesBoundedMethod(cw);
    generateMatchBoundedMethod(cw);
  }

  /** Generate matches() method - checks if entire string matches pattern. Returns: boolean */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();

    // Null check
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

    Label failLabel = new Label();

    // Match prefix
    generatePrefixMatching(mv, inputVar, posVar, lenVar, failLabel, allocator);

    // Skip group - just match greedily, don't track boundaries
    generateGreedyQuantifierLoop(mv, inputVar, posVar, lenVar, -1, -1, failLabel, allocator);

    // Match suffix
    generateSuffixMatching(mv, inputVar, posVar, lenVar, failLabel, allocator);

    // Success: check pos == len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    Label returnTrue = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, returnTrue);

    // Fail
    mv.visitLabel(failLabel);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // Success
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate match() method - matches from start, extracts groups. Returns: MatchResult or null */
  public void generateMatchMethod(ClassWriter cw) {
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
    int groupStartVar = allocator.allocate();
    int groupEndVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    // Null check
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

    Label failLabel = new Label();

    // Match prefix
    generatePrefixMatching(mv, inputVar, posVar, lenVar, failLabel, allocator);

    // Group start: int groupStart = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    // Match greedy quantifier (updates pos)
    generateGreedyQuantifierLoop(
        mv, inputVar, posVar, lenVar, groupStartVar, groupEndVar, failLabel, allocator);

    // Group end: int groupEnd = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // Match suffix
    generateSuffixMatching(mv, inputVar, posVar, lenVar, failLabel, allocator);

    // Check that we consumed the entire input: if (pos != len) goto fail;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, failLabel);

    // Success! Build MatchResult
    // int[] starts = new int[]{0, groupStart};
    pushInt(mv, 2); // 2 groups: group 0 and group 1
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    pushInt(mv, 0); // start of group 0
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[]{pos, groupEnd};
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, posVar); // pos (end of entire match)
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, 1); // groupCount = 1 (one capturing group)
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Fail
    mv.visitLabel(failLabel);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate find() method - searches for match anywhere in string. */
  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Delegate to findMatchFrom
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);

    // return result != null;
    Label notNull = new Label();
    mv.visitInsn(DUP);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(POP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(notNull);
    mv.visitInsn(POP);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findMatchFrom() method - searches for match starting from position. */
  public void generateFindMatchFromMethod(ClassWriter cw) {
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
    int posVar = allocator.allocate();
    int groupStartVar = allocator.allocate();
    int groupEndVar = allocator.allocate();
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    // Null check
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

    // Try matching from each position
    Label tryLoop = new Label();
    Label notFound = new Label();

    mv.visitLabel(tryLoop);

    // if (start > len - minPrefixLen) return null;
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, getMinimumMatchLength());
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, notFound);

    // Try matching from this position
    // int pos = start;
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, posVar);

    Label tryNextPos = new Label();

    // Match prefix
    generatePrefixMatching(mv, inputVar, posVar, lenVar, tryNextPos, allocator);

    // Group start
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupStartVar);

    // Match greedy quantifier
    generateGreedyQuantifierLoop(
        mv, inputVar, posVar, lenVar, groupStartVar, groupEndVar, tryNextPos, allocator);

    // Group end
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, groupEndVar);

    // Match suffix
    generateSuffixMatching(mv, inputVar, posVar, lenVar, tryNextPos, allocator);

    // Success! Build MatchResult
    // int[] starts = new int[]{start, groupStart};
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, groupStartVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[]{pos, groupEnd};
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, groupEndVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, 1); // groupCount = 1
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Try next position
    mv.visitLabel(tryNextPos);
    mv.visitIincInsn(startVar, 1); // start++
    mv.visitJumpInsn(GOTO, tryLoop);

    // Not found
    mv.visitLabel(notFound);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate bytecode to match prefix nodes. Updates pos, jumps to failLabel if no match. */
  private void generatePrefixMatching(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      LocalVarAllocator allocator) {
    for (RegexNode node : info.prefix) {
      generateNodeMatching(mv, node, inputVar, posVar, lenVar, failLabel, allocator);
    }
  }

  /** Generate bytecode to match suffix nodes. */
  private void generateSuffixMatching(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      LocalVarAllocator allocator) {
    for (RegexNode node : info.suffix) {
      generateNodeMatching(mv, node, inputVar, posVar, lenVar, failLabel, allocator);
    }
  }

  /** Generate bytecode to match a single node (literal or char class). */
  private void generateNodeMatching(
      MethodVisitor mv,
      RegexNode node,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      LocalVarAllocator allocator) {
    if (node instanceof LiteralNode) {
      char ch = ((LiteralNode) node).ch;

      // if (pos >= len) goto fail;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, failLabel);

      // if (input.charAt(pos) != 'ch') goto fail;
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPNE, failLabel);

      // pos++;
      mv.visitIincInsn(posVar, 1);

    } else if (node instanceof CharClassNode) {
      CharSet charset = ((CharClassNode) node).chars;

      // if (pos >= len) goto fail;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, failLabel);

      // char ch = input.charAt(pos);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      int charVar = allocator.allocate();
      mv.visitVarInsn(ISTORE, charVar);

      // if (!charset.contains(ch)) goto fail;
      generateCharSetCheck(mv, charset, charVar, failLabel, false);

      // pos++;
      mv.visitIincInsn(posVar, 1);
    }
  }

  /** Generate greedy quantifier matching loop. Updates pos, respects min/max bounds. */
  private void generateGreedyQuantifierLoop(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      int groupStartVar,
      int groupEndVar,
      Label failLabel,
      LocalVarAllocator allocator) {
    // int count = 0;
    int countVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check: pos < len
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Check maxQuantifier if bounded
    if (info.maxQuantifier != Integer.MAX_VALUE) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxQuantifier);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // Get character
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    int charVar = allocator.allocate();
    mv.visitVarInsn(ISTORE, charVar);

    // Check if it matches the quantified element
    if (info.isLiteralQuantifier()) {
      char ch = info.quantifierLiteral.charAt(0);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) ch);
      mv.visitJumpInsn(IF_ICMPNE, loopEnd);
    } else if (info.isCharClassQuantifier()) {
      generateCharSetCheck(mv, info.quantifierCharSet, charVar, loopEnd, false);
    }

    // Match! pos++; count++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(countVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check minQuantifier constraint
    if (info.minQuantifier > 0) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.minQuantifier);
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
    }
  }

  /** Generate charset check: if (charset.contains(ch)) continue; else goto exitLabel; */
  private void generateCharSetCheck(
      MethodVisitor mv, CharSet charset, int charVar, Label exitLabel, boolean negated) {
    if (charset.isSingleChar()) {
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      mv.visitJumpInsn(negated ? IF_ICMPEQ : IF_ICMPNE, exitLabel);
    } else if (charset.isSimpleRange()) {
      // Single range check: if (ch < min || ch > max) goto exitLabel;
      char min = charset.rangeStart();
      char max = charset.rangeEnd();

      if (!negated) {
        // Normal: exit if NOT in range
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) min);
        mv.visitJumpInsn(IF_ICMPLT, exitLabel);

        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) max);
        mv.visitJumpInsn(IF_ICMPGT, exitLabel);
      } else {
        // Negated: exit if IN range
        Label inRange = new Label();
        Label done = new Label();

        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) min);
        mv.visitJumpInsn(IF_ICMPLT, done);

        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) max);
        mv.visitJumpInsn(IF_ICMPLE, exitLabel);

        mv.visitLabel(done);
      }
    } else {
      // Multiple ranges - call CharSet.contains()
      // For now, use a simple approach checking each range
      Label matched = new Label();

      for (CharSet.Range range : charset.getRanges()) {
        char min = range.start;
        char max = range.end;

        // if (ch >= min && ch <= max) matched
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) min);
        Label nextRange = new Label();
        mv.visitJumpInsn(IF_ICMPLT, nextRange);

        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) max);
        mv.visitJumpInsn(IF_ICMPLE, matched);

        mv.visitLabel(nextRange);
      }

      // No range matched
      if (!negated) {
        // Normal: character not in charset → exit
        mv.visitJumpInsn(GOTO, exitLabel);
      } else {
        // Negated: character not in charset → continue (don't exit)
        Label continueLabel = new Label();
        mv.visitJumpInsn(GOTO, continueLabel);
        mv.visitLabel(matched);
        // Negated: character IN charset → exit
        mv.visitJumpInsn(GOTO, exitLabel);
        mv.visitLabel(continueLabel);
        return;
      }

      // Normal case: matched label means character IS in charset → continue
      mv.visitLabel(matched);
    }
  }

  /** Calculate minimum match length (prefix + min quantifier + suffix). */
  private int getMinimumMatchLength() {
    return info.prefix.size() + info.minQuantifier + info.suffix.size();
  }

  /**
   * Generate findFrom() method - returns int position of match. Delegates to findMatchFrom() and
   * extracts position.
   */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // MatchResult r = this.findMatchFrom(input, start);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);

    // if (r == null) return -1;
    mv.visitInsn(DUP);
    Label notNull = new Label();
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(POP);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);

    // return r.start();
    mv.visitLabel(notNull);
    mv.visitMethodInsn(
        INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "start", "()I", true);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findMatch() method - like findMatchFrom but starts at 0. */
  public void generateFindMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // return this.findMatchFrom(input, 0);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    pushInt(mv, 0);
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

  /** Generate matchesBounded() method - checks if substring matches. */
  public void generateMatchesBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input, slot 2 = start, slot 3 = end
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int substringVar = allocator.allocate();

    // String s = input.subSequence(start, end).toString();
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

    // return this.matches(s);
    mv.visitVarInsn(ASTORE, substringVar);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, substringVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchBounded() method - returns MatchResult for substring. */
  public void generateMatchBoundedMethod(ClassWriter cw) {
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

    // String s = input.subSequence(start, end).toString();
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

    // return this.match(s);
    mv.visitVarInsn(ASTORE, substringVar);
    mv.visitVarInsn(ALOAD, 0); // this
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
}
