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

import com.datadoghq.reggie.codegen.analysis.GreedyBacktrackInfo;
import com.datadoghq.reggie.codegen.ast.CharClassNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates bytecode for greedy patterns that require backtracking.
 *
 * <p>Handles patterns where a greedy quantifier (.*, .+) must "give back" characters for a
 * following pattern element to match.
 *
 * <p>Algorithm for pattern like (.*)bar on "foobar": 1. Try longest possible match for .* first
 * (all chars: "foobar") 2. Check if suffix "bar" matches at the end - no, we consumed everything 3.
 * Backtrack: try shorter match for .* ("fooba") 4. Check if suffix "bar" matches - no 5. Continue
 * backtracking until suffix matches: .* = "foo", suffix "bar" matches
 *
 * <p>For patterns like (.*)(\\d+) on "abc123": 1. .* tries to match everything "abc123" 2. \\d+
 * needs at least 1 digit - backtrack 3. .* = "abc12", \\d+ = "3" - both satisfied
 *
 * <p>The generator produces specialized bytecode that works backwards from the end, finding where
 * the suffix can match, then capturing the greedy group accordingly.
 */
public class GreedyBacktrackBytecodeGenerator {

  private final GreedyBacktrackInfo info;
  private final String className;

  public GreedyBacktrackBytecodeGenerator(GreedyBacktrackInfo info, String className) {
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
    generateFindBoundsFromMethod(cw);
  }

  /**
   * Generate matches() method - checks if entire string matches pattern.
   *
   * <p>Algorithm for (.*)bar: 1. Find last occurrence of suffix "bar" in input 2. Verify .* matches
   * everything before that position 3. For .+ (min=1), ensure at least 1 char is captured
   */
  public void generateMatchesMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);
    // S: []

    // int len = input.length();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = 0;
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, posVar);

    Label returnFalse = new Label();

    // Match prefix (if any)
    int nextVar =
        generatePrefixMatching(mv, inputVar, posVar, lenVar, returnFalse, allocator.peek());

    // Now we need to find where the suffix matches from the end and work backwards
    // For (.*)bar matching "foobar": find where "bar" starts from end

    switch (info.suffixType) {
      case LITERAL:
        generateLiteralSuffixBacktrackMatches(mv, inputVar, posVar, lenVar, returnFalse, nextVar);
        break;
      case QUANTIFIED_CHAR_CLASS:
        generateQuantifiedCharClassSuffixBacktrackMatches(
            mv, inputVar, posVar, lenVar, returnFalse, nextVar);
        break;
      case WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS:
        generateWordBoundaryQuantifiedCharClassSuffixBacktrackMatches(
            mv, inputVar, posVar, lenVar, returnFalse, nextVar);
        break;
      default:
        // Unsupported - fall through to return false
        mv.visitJumpInsn(GOTO, returnFalse);
        break;
    }

    mv.visitLabel(returnFalse);
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate backtracking logic for literal suffix. Pattern: (.*)literal
   *
   * <p>Algorithm for (.*)bar on "foobar": 1. Find "bar" at end of input (starting at position len -
   * 3 = 3) 2. Check input.endsWith("bar") - yes 3. Greedy group = input[pos..len-3] = "foo" 4.
   * Verify greedy min count (0 for .*, 1 for .+)
   */
  private void generateLiteralSuffixBacktrackMatches(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label returnFalse, int nextVar) {

    String literal = info.suffixLiteral;
    int literalLen = literal.length();

    // Check if input is long enough for prefix + suffix
    // Minimum: prefix consumed + greedy min + literal length
    int minTotalLen = info.greedyMinCount + literalLen;

    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB); // remaining = len - pos
    // S: [I] -> [I, I]
    pushInt(mv, minTotalLen);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, returnFalse); // if remaining < minTotalLen, fail

    // For matches(), the literal must be at the end of input
    // Check: input.endsWith(literal)
    // Equivalent to: input.regionMatches(len - literalLen, literal, 0, literalLen)

    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [A:String, I] -> [A:String, I, I]
    pushInt(mv, literalLen);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB); // len - literalLen
    // S: [A:String, I] -> [A:String, I, A:String]
    mv.visitLdcInsn(literal);
    // S: [A:String, I, A:String] -> [A:String, I, A:String, I]
    mv.visitInsn(ICONST_0);
    // S: [A:String, I, A:String, I] -> [A:String, I, A:String, I, I]
    pushInt(mv, literalLen);
    // S: [A:String, I, A:String, I, I] -> [Z]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    // S: [Z] -> []
    mv.visitJumpInsn(IFEQ, returnFalse); // if !regionMatches, fail

    // Verify greedy group has at least min chars (0 for .*, 1 for .+)
    if (info.greedyMinCount > 0) {
      // greedyLen = len - literalLen - pos
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, lenVar);
      // S: [I] -> [I, I]
      pushInt(mv, literalLen);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB); // greedyLen = len - literalLen - pos
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, returnFalse); // if greedyLen < minCount, fail
    }

    // If we also need to verify greedy charset matches all chars in the range
    // Skip validation if charset is "any" (matches all chars)
    if (info.greedyCharSet != null && !isAnyCharSet(info.greedyCharSet)) {
      // Loop to verify each char in greedy range matches charset
      int checkPosVar = nextVar;
      int greedyEndVar = nextVar + 1;

      // greedyEnd = len - literalLen
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, lenVar);
      // S: [I] -> [I, I]
      pushInt(mv, literalLen);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, greedyEndVar);

      // checkPos = pos
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, checkPosVar);

      Label checkLoop = new Label();
      Label checkDone = new Label();

      mv.visitLabel(checkLoop);
      // S: []
      // if (checkPos >= greedyEnd) goto checkDone
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, checkPosVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, greedyEndVar);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPGE, checkDone);

      // char c = input.charAt(checkPos)
      // S: [] -> [A:String]
      mv.visitVarInsn(ALOAD, inputVar);
      // S: [A:String] -> [A:String, I]
      mv.visitVarInsn(ILOAD, checkPosVar);
      // S: [A:String, I] -> [C]
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // if (!charset.contains(c)) goto returnFalse
      generateCharSetCheckFail(mv, info.greedyCharSet, returnFalse);
      // S: []

      // checkPos++
      mv.visitIincInsn(checkPosVar, 1);
      mv.visitJumpInsn(GOTO, checkLoop);

      mv.visitLabel(checkDone);
      // S: []
    }

    // Success!
    // S: [] -> [I]
    mv.visitInsn(ICONST_1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate backtracking logic for quantified char class suffix. Pattern: (.*)(\d+)
   *
   * <p>Algorithm for (.*)(\\d+) on "abc123": 1. Find rightmost sequence of digits from end 2.
   * Greedy .* captures everything before the digits 3. Verify .* min count constraint
   */
  private void generateQuantifiedCharClassSuffixBacktrackMatches(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label returnFalse, int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;

    // Algorithm for (.*)(\d+) on "abc123":
    // 1. Greedy priority: give MINIMUM to suffix (1 digit), rest to greedy group
    // 2. suffixStart = len - suffixMinCount = 6 - 1 = 5
    // 3. Verify chars from suffixStart to len match charset
    // 4. Everything from pos to suffixStart goes to greedy group

    int suffixStartVar = nextVar;

    // Calculate: suffixStart = len - suffixMinCount
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixStartVar);

    // Check: suffixStart >= pos (enough room for greedy group)
    // if (suffixStart < pos) goto returnFalse
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // Verify all suffix chars match charset (mandatory for quantified patterns)
    int checkPosVar = nextVar + 1;

    // checkPos = suffixStart
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, checkPosVar);

    Label checkLoop = new Label();
    Label checkDone = new Label();

    mv.visitLabel(checkLoop);
    // S: []
    // if (checkPos >= len) goto checkDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, checkPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, checkDone);

    // char c = input.charAt(checkPos)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, checkPosVar);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // if (!charset.contains(c)) goto returnFalse
    generateCharSetCheckFail(mv, suffixCharSet, returnFalse);
    // S: []

    // checkPos++
    mv.visitIincInsn(checkPosVar, 1);
    mv.visitJumpInsn(GOTO, checkLoop);

    mv.visitLabel(checkDone);
    // S: []

    // Success!
    // S: [] -> [I]
    mv.visitInsn(ICONST_1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate backtracking logic for word boundary + quantified char class suffix. Pattern:
   * (.*)\b(\d+)
   *
   * <p>Algorithm for (.*)\b(\d+) on "I have 2 numbers: 53147": 1. Greedy priority: give MINIMUM to
   * suffix (1 digit for \d+) 2. suffixStart = len - suffixMinCount 3. Check word boundary at
   * suffixStart: char before must be non-word (if any) 4. Verify chars from suffixStart to len
   * match charset (\d) 5. Everything from pos to suffixStart goes to greedy group
   */
  private void generateWordBoundaryQuantifiedCharClassSuffixBacktrackMatches(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label returnFalse, int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;

    int suffixStartVar = nextVar;
    int charVar = nextVar + 1;

    // Algorithm: Scan backward from end to find where suffix chars start
    // For (.*)\b(\d+) on "abc 123", we need to find where digits begin (position of '1')

    // suffixStart = len
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixStartVar);

    // Scan backward while chars are in suffix charset
    Label scanLoop = new Label();
    Label scanDone = new Label();

    mv.visitLabel(scanLoop);
    // if (suffixStart <= pos) goto scanDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, scanDone);

    // char c = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isInCharSet(c)) goto scanDone
    Label inCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, inCharSet);
    mv.visitJumpInsn(GOTO, scanDone);

    mv.visitLabel(inCharSet);
    // suffixStart--
    mv.visitIincInsn(suffixStartVar, -1);
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(scanDone);

    // Verify suffix min count: (len - suffixStart) >= suffixMinCount
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, returnFalse);

    // Verify greedy min count: (suffixStart - pos) >= greedyMinCount
    if (info.greedyMinCount > 0) {
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, suffixStartVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, returnFalse);
    }

    // Check word boundary at suffixStart:
    // If suffixStart > pos, the char before must be non-word char
    Label boundaryOk = new Label();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, boundaryOk); // if suffixStart <= pos, boundary is at start (ok)

    // char prevChar = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // If prevChar is a word char, no boundary exists - fail
    generateWordCharCheck(mv, returnFalse, boundaryOk);

    mv.visitLabel(boundaryOk);
    // S: []

    // All checks passed - return true
    // S: [] -> [I]
    mv.visitInsn(ICONST_1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
  }

  /** Generate match() method - matches from start, extracts groups. */
  public void generateMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local vars: 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);
    // S: []

    // int len = input.length();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = 0;
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, posVar);

    Label returnNull = new Label();

    // Match prefix (if any)
    int nextVar =
        generatePrefixMatching(mv, inputVar, posVar, lenVar, returnNull, allocator.peek());

    // Do backtracking match with group capture
    switch (info.suffixType) {
      case LITERAL:
        generateLiteralSuffixBacktrackMatch(mv, inputVar, posVar, lenVar, returnNull, nextVar);
        break;
      case QUANTIFIED_CHAR_CLASS:
        generateQuantifiedCharClassSuffixBacktrackMatch(
            mv, inputVar, posVar, lenVar, returnNull, nextVar);
        break;
      case WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS:
        generateWordBoundaryQuantifiedCharClassSuffixBacktrackMatch(
            mv, inputVar, posVar, lenVar, returnNull, nextVar);
        break;
      default:
        mv.visitJumpInsn(GOTO, returnNull);
        break;
    }

    mv.visitLabel(returnNull);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate literal suffix backtrack match with group capture. */
  private void generateLiteralSuffixBacktrackMatch(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label returnNull, int nextVar) {

    String literal = info.suffixLiteral;
    int literalLen = literal.length();
    int totalGroups = info.totalGroupCount;

    // Check minimum length
    int minTotalLen = info.greedyMinCount + literalLen;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    pushInt(mv, minTotalLen);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, returnNull);

    // Check literal at end
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [A:String, I] -> [A:String, I, I]
    pushInt(mv, literalLen);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [A:String, I, A:String]
    mv.visitLdcInsn(literal);
    // S: [A:String, I, A:String] -> [A:String, I, A:String, I]
    mv.visitInsn(ICONST_0);
    // S: [A:String, I, A:String, I] -> [A:String, I, A:String, I, I]
    pushInt(mv, literalLen);
    // S: [A:String, I, A:String, I, I] -> [Z]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    // S: [Z] -> []
    mv.visitJumpInsn(IFEQ, returnNull);

    // Verify greedy min count
    int greedyEndVar = nextVar;
    // greedyEnd = len - literalLen
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> [I, I]
    pushInt(mv, literalLen);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, greedyEndVar);

    if (info.greedyMinCount > 0) {
      // greedyLen = greedyEnd - pos
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, greedyEndVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, returnNull);
    }

    // Build MatchResult
    // Group 0: [0, len]
    // Group 1 (greedy): [pos, greedyEnd]
    generateMatchResult(mv, inputVar, posVar, lenVar, greedyEndVar, totalGroups);
  }

  /** Generate quantified char class suffix backtrack match with group capture. */
  private void generateQuantifiedCharClassSuffixBacktrackMatch(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label returnNull, int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;
    int totalGroups = info.totalGroupCount;

    int suffixStartVar = nextVar;
    int suffixEndVar = nextVar + 1;
    int charVar = nextVar + 2;

    // Greedy backtracking: scan BACKWARD to find rightmost match for suffix
    // For (.*)(\d+) on "I have 2 numbers: 53147", we want:
    //   Group 1 = "I have 2 numbers: 5314" (greedy takes maximum)
    //   Group 2 = "7" (suffix takes minimum/rightmost occurrence)

    // suffixEnd = len
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixEndVar);

    // suffixStart = len (start scanning backward from end)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixStartVar);

    // Scan backwards to find rightmost sequence matching suffix charset
    // Stop when we have suffixMinCount matching chars (greedy gives minimum to suffix)
    Label scanLoop = new Label();
    Label scanDone = new Label();

    mv.visitLabel(scanLoop);
    // if (suffixStart <= pos) goto scanDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, scanDone);

    // if ((suffixEnd - suffixStart) >= suffixMinCount) goto scanDone (found enough)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixEndVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, scanDone);

    // char c = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isInCharSet(c)) goto scanDone
    Label inCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, inCharSet);
    mv.visitJumpInsn(GOTO, scanDone);

    mv.visitLabel(inCharSet);
    // suffixStart--
    mv.visitIincInsn(suffixStartVar, -1);
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(scanDone);

    // Verify suffix min count: (suffixEnd - suffixStart) >= suffixMinCount
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixEndVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, returnNull);

    // Verify greedy min count: (suffixStart - pos) >= greedyMinCount
    if (info.greedyMinCount > 0) {
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, suffixStartVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, returnNull);
    }

    // Build MatchResult with two groups
    // Group 0: [0, len]
    // Group 1 (greedy): [pos, suffixStart]
    // Group 2 (suffix): [suffixStart, suffixEnd]
    generateMatchResultWithSuffixGroup(
        mv, inputVar, posVar, lenVar, suffixStartVar, suffixEndVar, totalGroups);
  }

  /**
   * Generate match() backtracking logic for word boundary + quantified char class suffix. Pattern:
   * (.*)\b(\d+)
   *
   * <p>Algorithm for (.*)\b(\d+) on "I have 2 numbers: 53147": 1. Scan backward from end to find
   * where digits start 2. Verify word boundary exists at that position (char before is non-word) 3.
   * Build match result with groups
   */
  private void generateWordBoundaryQuantifiedCharClassSuffixBacktrackMatch(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label returnNull, int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;
    int totalGroups = info.totalGroupCount;

    int suffixStartVar = nextVar;
    int suffixEndVar = nextVar + 1;
    int charVar = nextVar + 2;

    // suffixEnd = len
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixEndVar);

    // suffixStart = len (start scanning backward from end)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixStartVar);

    // Scan backwards to find where suffix chars start
    Label scanLoop = new Label();
    Label scanDone = new Label();

    mv.visitLabel(scanLoop);
    // if (suffixStart <= pos) goto scanDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, scanDone);

    // char c = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isInCharSet(c)) goto scanDone
    Label inCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, inCharSet);
    mv.visitJumpInsn(GOTO, scanDone);

    mv.visitLabel(inCharSet);
    // suffixStart--
    mv.visitIincInsn(suffixStartVar, -1);
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(scanDone);

    // Verify suffix min count: (suffixEnd - suffixStart) >= suffixMinCount
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixEndVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, returnNull);

    // Verify greedy min count: (suffixStart - pos) >= greedyMinCount
    if (info.greedyMinCount > 0) {
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, suffixStartVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, returnNull);
    }

    // Check word boundary at suffixStart
    // If suffixStart > pos, the char before must be non-word char
    Label boundaryOk = new Label();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, boundaryOk); // if suffixStart <= pos, boundary is at start

    // char prevChar = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // Check if prevChar is a word char - if so, no boundary exists
    generateWordCharCheck(mv, returnNull, boundaryOk);

    mv.visitLabel(boundaryOk);
    // S: []

    // Build MatchResult with two groups
    generateMatchResultWithSuffixGroup(
        mv, inputVar, posVar, lenVar, suffixStartVar, suffixEndVar, totalGroups);
  }

  /**
   * Generate code to check if char on stack is a word char. If it IS a word char, jump to
   * failLabel. If it is NOT a word char (boundary exists), jump to successLabel. Stack: [C] -> []
   */
  private void generateWordCharCheck(MethodVisitor mv, Label failLabel, Label successLabel) {
    // Word chars: [0-9A-Za-z_]
    Label notDigit = new Label();
    Label notUpperCase = new Label();
    Label notLowerCase = new Label();
    Label isWordChar = new Label();

    // S: [C]
    mv.visitInsn(DUP); // S: [C, C]
    // Check 0-9
    pushInt(mv, '0'); // S: [C, C, I]
    mv.visitJumpInsn(IF_ICMPLT, notDigit); // S: [C]
    mv.visitInsn(DUP); // S: [C, C]
    pushInt(mv, '9'); // S: [C, C, I]
    mv.visitJumpInsn(IF_ICMPLE, isWordChar); // S: [C]

    mv.visitLabel(notDigit);
    // Check A-Z
    mv.visitInsn(DUP); // S: [C, C]
    pushInt(mv, 'A'); // S: [C, C, I]
    mv.visitJumpInsn(IF_ICMPLT, notUpperCase); // S: [C]
    mv.visitInsn(DUP); // S: [C, C]
    pushInt(mv, 'Z'); // S: [C, C, I]
    mv.visitJumpInsn(IF_ICMPLE, isWordChar); // S: [C]

    mv.visitLabel(notUpperCase);
    // Check a-z
    mv.visitInsn(DUP); // S: [C, C]
    pushInt(mv, 'a'); // S: [C, C, I]
    mv.visitJumpInsn(IF_ICMPLT, notLowerCase); // S: [C]
    mv.visitInsn(DUP); // S: [C, C]
    pushInt(mv, 'z'); // S: [C, C, I]
    mv.visitJumpInsn(IF_ICMPLE, isWordChar); // S: [C]

    mv.visitLabel(notLowerCase);
    // Check underscore
    // S: [C]
    mv.visitInsn(DUP); // S: [C, C]
    pushInt(mv, '_'); // S: [C, C, I]
    mv.visitJumpInsn(IF_ICMPEQ, isWordChar); // S: [C]
    // Not a word char - boundary exists, pop remaining char
    mv.visitInsn(POP); // S: []
    mv.visitJumpInsn(GOTO, successLabel);

    mv.visitLabel(isWordChar);
    // S: [C] - pop remaining char and fail (no boundary)
    mv.visitInsn(POP);
    mv.visitJumpInsn(GOTO, failLabel);
  }

  /** Generate MatchResult with single capturing group. */
  private void generateMatchResult(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, int greedyEndVar, int totalGroups) {

    int startsVar = greedyEndVar + 1;
    int endsVar = greedyEndVar + 2;

    // int[] starts = new int[totalGroups + 1]
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    pushInt(mv, 0); // group 0 start = 0
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, posVar); // group 1 start = pos (after prefix)
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[totalGroups + 1]
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, lenVar); // group 0 end = len
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, greedyEndVar); // group 1 end = greedyEnd
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, totalGroups)
    // S: [] -> [A:MatchResultImpl]
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    // S: [A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl]
    mv.visitInsn(DUP);
    // S: [A:MatchResultImpl, A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl, A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String] -> [A:MatchResultImpl, A:MatchResultImpl,
    // A:String, A:[I]
    mv.visitVarInsn(ALOAD, startsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I]
    mv.visitVarInsn(ALOAD, endsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I, I]
    pushInt(mv, totalGroups);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I, I] -> [A:MatchResultImpl]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    // S: [A:MatchResultImpl] -> []
    mv.visitInsn(ARETURN);
  }

  /** Generate MatchResult with two capturing groups (greedy + suffix). */
  private void generateMatchResultWithSuffixGroup(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      int suffixStartVar,
      int suffixEndVar,
      int totalGroups) {

    int startsVar = suffixEndVar + 1;
    int endsVar = suffixEndVar + 2;

    // int[] starts = new int[totalGroups + 1]
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    pushInt(mv, 0); // group 0 start = 0
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, posVar); // group 1 start = pos
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    if (totalGroups >= 2) {
      // S: [A:[I] -> [A:[I, A:[I]
      mv.visitInsn(DUP);
      // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
      pushInt(mv, 2);
      // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
      mv.visitVarInsn(ILOAD, suffixStartVar); // group 2 start = suffixStart
      // S: [A:[I, A:[I, I, I] -> [A:[I]
      mv.visitInsn(IASTORE);
    }
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[totalGroups + 1]
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, lenVar); // group 0 end = len
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar); // group 1 end = suffixStart
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    if (totalGroups >= 2) {
      // S: [A:[I] -> [A:[I, A:[I]
      mv.visitInsn(DUP);
      // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
      pushInt(mv, 2);
      // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
      mv.visitVarInsn(ILOAD, suffixEndVar); // group 2 end = suffixEnd
      // S: [A:[I, A:[I, I, I] -> [A:[I]
      mv.visitInsn(IASTORE);
    }
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, totalGroups)
    // S: [] -> [A:MatchResultImpl]
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    // S: [A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl]
    mv.visitInsn(DUP);
    // S: [A:MatchResultImpl, A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl, A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String] -> [A:MatchResultImpl, A:MatchResultImpl,
    // A:String, A:[I]
    mv.visitVarInsn(ALOAD, startsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I]
    mv.visitVarInsn(ALOAD, endsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I, I]
    pushInt(mv, totalGroups);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I, I] -> [A:MatchResultImpl]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    // S: [A:MatchResultImpl] -> []
    mv.visitInsn(ARETURN);
  }

  // --- Helper methods for find() and other operations ---

  /** Generate find() method. */
  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // return findFrom(input, 0) >= 0;
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 0);
    // S: [A:this] -> [A:this, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:this, A:String] -> [A:this, A:String, I]
    mv.visitInsn(ICONST_0);
    // S: [A:this, A:String, I] -> [I]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);

    Label returnTrue = new Label();
    Label end = new Label();
    // S: [I] -> []
    mv.visitJumpInsn(IFGE, returnTrue);
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitJumpInsn(GOTO, end);
    mv.visitLabel(returnTrue);
    // S: [] -> [I]
    mv.visitInsn(ICONST_1);
    mv.visitLabel(end);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findFrom() method. */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // For patterns without prefix, findFrom just tries matching from each position
    // For patterns with prefix, search for prefix first

    // Local vars: 0=this, 1=input, 2=startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int inputVar = 1;
    int startPosVar = 2;
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [I]
    mv.visitInsn(ICONST_M1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    // int pos = startPos;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, startPosVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, posVar);

    Label notFound = new Label();
    int nextVar = allocator.peek();

    if (info.prefix.isEmpty() && info.suffixType == GreedyBacktrackInfo.SuffixType.LITERAL) {
      // For (.*)literal, use indexOf to find the literal
      String literal = info.suffixLiteral;

      Label searchLoop = new Label();
      mv.visitLabel(searchLoop);

      // int found = input.indexOf(literal, pos);
      int foundVar = nextVar;
      // S: [] -> [A:String]
      mv.visitVarInsn(ALOAD, inputVar);
      // S: [A:String] -> [A:String, A:String]
      mv.visitLdcInsn(literal);
      // S: [A:String, A:String] -> [A:String, A:String, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [A:String, A:String, I] -> [I]
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, foundVar);

      // if (found < 0) return -1;
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, foundVar);
      // S: [I] -> []
      mv.visitJumpInsn(IFLT, notFound);

      // Check greedy min count: found - pos >= greedyMinCount
      if (info.greedyMinCount > 0) {
        // S: [] -> [I]
        mv.visitVarInsn(ILOAD, foundVar);
        // S: [I] -> [I, I]
        mv.visitVarInsn(ILOAD, posVar);
        // S: [I, I] -> [I]
        mv.visitInsn(ISUB);
        // S: [I] -> [I, I]
        pushInt(mv, info.greedyMinCount);
        Label minOk = new Label();
        // S: [I, I] -> []
        mv.visitJumpInsn(IF_ICMPGE, minOk);

        // greedyLen < min, try next occurrence
        // pos = found + 1
        // S: [] -> [I]
        mv.visitVarInsn(ILOAD, foundVar);
        // S: [I] -> [I, I]
        mv.visitInsn(ICONST_1);
        // S: [I, I] -> [I]
        mv.visitInsn(IADD);
        // S: [I] -> []
        mv.visitVarInsn(ISTORE, posVar);
        mv.visitJumpInsn(GOTO, searchLoop);

        mv.visitLabel(minOk);
      }

      // Found! Return starting position
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I] -> []
      mv.visitInsn(IRETURN);
    } else {
      // General case: try matching from each position
      Label posLoop = new Label();
      Label posLoopEnd = new Label();

      mv.visitLabel(posLoop);
      // if (pos > len - minMatchLen) goto notFound
      int minMatchLen = info.greedyMinCount + info.getSuffixMinLength();
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, lenVar);
      // S: [I, I] -> [I, I, I]
      pushInt(mv, minMatchLen);
      // S: [I, I, I] -> [I, I]
      mv.visitInsn(ISUB);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPGT, posLoopEnd);

      // Try to match at this position
      // Save pos for potential return
      int savedPosVar = nextVar;
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I] -> []
      mv.visitVarInsn(ISTORE, savedPosVar);

      Label tryNext = new Label();

      // Match prefix
      int afterPrefixVar =
          generatePrefixMatchingWithFail(mv, inputVar, posVar, lenVar, tryNext, nextVar + 1);

      // Try backtracking match
      switch (info.suffixType) {
        case LITERAL:
          generateFindFromLiteralSuffix(
              mv, inputVar, posVar, lenVar, savedPosVar, tryNext, afterPrefixVar);
          break;
        case QUANTIFIED_CHAR_CLASS:
          generateFindFromQuantifiedCharClassSuffix(
              mv, inputVar, posVar, lenVar, savedPosVar, tryNext, afterPrefixVar);
          break;
        case WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS:
          generateFindFromWordBoundaryQuantifiedCharClassSuffix(
              mv, inputVar, posVar, lenVar, savedPosVar, tryNext, afterPrefixVar);
          break;
        default:
          mv.visitJumpInsn(GOTO, tryNext);
          break;
      }

      mv.visitLabel(tryNext);
      // pos++
      mv.visitIincInsn(posVar, 1);
      mv.visitJumpInsn(GOTO, posLoop);

      mv.visitLabel(posLoopEnd);
    }

    mv.visitLabel(notFound);
    // S: [] -> [I]
    mv.visitInsn(ICONST_M1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findFrom logic for literal suffix. */
  private void generateFindFromLiteralSuffix(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      int savedPosVar,
      Label tryNext,
      int nextVar) {

    String literal = info.suffixLiteral;
    int literalLen = literal.length();

    // Find literal starting from pos
    int foundVar = nextVar;
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, A:String]
    mv.visitLdcInsn(literal);
    // S: [A:String, A:String] -> [A:String, A:String, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [A:String, A:String, I] -> [I]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, foundVar);

    // if (found < 0) goto tryNext;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, foundVar);
    // S: [I] -> []
    mv.visitJumpInsn(IFLT, tryNext);

    // Verify greedy min count
    if (info.greedyMinCount > 0) {
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, foundVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, tryNext);
    }

    // Success! Return saved start position
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, savedPosVar);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
  }

  /** Generate findFrom logic for quantified char class suffix. */
  private void generateFindFromQuantifiedCharClassSuffix(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      int savedPosVar,
      Label tryNext,
      int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;

    // Scan forward to find where suffix chars start
    int scanPosVar = nextVar;
    int charVar = nextVar + 1;

    // scanPos = pos
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, scanPosVar);

    // Skip chars not in suffix charset (greedy match)
    Label skipLoop = new Label();
    Label skipDone = new Label();

    mv.visitLabel(skipLoop);
    // if (scanPos >= len) goto tryNext
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, skipDone);

    // char c = input.charAt(scanPos)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (isInCharSet(c)) goto skipDone - found start of suffix
    Label inCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, inCharSet);
    // Not in charset, continue skipping
    mv.visitIincInsn(scanPosVar, 1);
    mv.visitJumpInsn(GOTO, skipLoop);

    mv.visitLabel(inCharSet);
    // Found a suffix char

    mv.visitLabel(skipDone);
    // scanPos now points to first suffix char (or end of input)

    // Verify greedy min count: scanPos - pos >= greedyMinCount
    if (info.greedyMinCount > 0) {
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, scanPosVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, tryNext);
    }

    // Count suffix chars: must have at least suffixMinCount
    int suffixCountVar = charVar + 1;
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixCountVar);

    int countPosVar = suffixCountVar + 1;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, countPosVar);

    Label countLoop = new Label();
    Label countDone = new Label();

    mv.visitLabel(countLoop);
    // if (countPos >= len) goto countDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, countPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, countDone);

    // char c = input.charAt(countPos)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, countPosVar);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isInCharSet(c)) goto countDone
    Label stillIn = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, stillIn);
    mv.visitJumpInsn(GOTO, countDone);

    mv.visitLabel(stillIn);
    mv.visitIincInsn(suffixCountVar, 1);
    mv.visitIincInsn(countPosVar, 1);
    mv.visitJumpInsn(GOTO, countLoop);

    mv.visitLabel(countDone);

    // Verify suffix min count
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixCountVar);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, tryNext);

    // Success!
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, savedPosVar);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate findFrom logic for word boundary + quantified char class suffix. Pattern: (.*)\b(\d+)
   */
  private void generateFindFromWordBoundaryQuantifiedCharClassSuffix(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      int savedPosVar,
      Label tryNext,
      int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;

    // Scan forward to find where suffix chars start
    int scanPosVar = nextVar;
    int charVar = nextVar + 1;

    // scanPos = pos
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, scanPosVar);

    // Skip chars not in suffix charset (greedy match)
    Label skipLoop = new Label();
    Label skipDone = new Label();

    mv.visitLabel(skipLoop);
    // if (scanPos >= len) goto tryNext
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, skipDone);

    // char c = input.charAt(scanPos)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (isInCharSet(c)) goto skipDone - found start of suffix
    Label inCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, inCharSet);
    // Not in charset, continue skipping
    mv.visitIincInsn(scanPosVar, 1);
    mv.visitJumpInsn(GOTO, skipLoop);

    mv.visitLabel(inCharSet);
    // Found a suffix char

    mv.visitLabel(skipDone);
    // scanPos now points to first suffix char (or end of input)

    // Verify greedy min count: scanPos - pos >= greedyMinCount
    if (info.greedyMinCount > 0) {
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, scanPosVar);
      // S: [I] -> [I, I]
      mv.visitVarInsn(ILOAD, posVar);
      // S: [I, I] -> [I]
      mv.visitInsn(ISUB);
      // S: [I] -> [I, I]
      pushInt(mv, info.greedyMinCount);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, tryNext);
    }

    // Check word boundary at scanPos (suffix start)
    // If scanPos > pos, the char before must be non-word char
    Label boundaryOk = new Label();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, boundaryOk); // if scanPos <= pos, boundary at start

    // char prevChar = input.charAt(scanPos - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // Check if prevChar is a word char - if so, no boundary, try next
    generateWordCharCheck(mv, tryNext, boundaryOk);

    mv.visitLabel(boundaryOk);

    // Count suffix chars: must have at least suffixMinCount
    int suffixCountVar = charVar + 1;
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixCountVar);

    int countPosVar = suffixCountVar + 1;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, countPosVar);

    Label countLoop = new Label();
    Label countDone = new Label();

    mv.visitLabel(countLoop);
    // if (countPos >= len) goto countDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, countPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, countDone);

    // char c = input.charAt(countPos)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, countPosVar);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isInCharSet(c)) goto countDone
    Label stillIn = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, stillIn);
    mv.visitJumpInsn(GOTO, countDone);

    mv.visitLabel(stillIn);
    mv.visitIincInsn(suffixCountVar, 1);
    mv.visitIincInsn(countPosVar, 1);
    mv.visitJumpInsn(GOTO, countLoop);

    mv.visitLabel(countDone);

    // Verify suffix min count
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixCountVar);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, tryNext);

    // Success!
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, savedPosVar);
    // S: [I] -> []
    mv.visitInsn(IRETURN);
  }

  // --- Remaining methods ---

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
    // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 0);
    // S: [A:this] -> [A:this, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:this, A:String] -> [A:this, A:String, I]
    mv.visitInsn(ICONST_0);
    // S: [A:this, A:String, I] -> [A:MatchResult]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    // S: [A:MatchResult] -> []
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindMatchFromMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // For now, simplified implementation that delegates to findFrom and then builds result
    // TODO: Optimize to capture groups during find

    // Local vars: 0=this, 1=input, 2=startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int inputVar = 1;
    int startPosVar = 2;
    int lenVar = allocator.allocate();
    int foundVar = allocator.allocate();

    // Null check
    Label notNull = new Label();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> []
    mv.visitJumpInsn(IFNONNULL, notNull);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int len = input.length();
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, lenVar);

    // int found = findFrom(input, startPos);
    // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 0);
    // S: [A:this] -> [A:this, A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:this, A:String] -> [A:this, A:String, I]
    mv.visitVarInsn(ILOAD, startPosVar);
    // S: [A:this, A:String, I] -> [I]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, foundVar);

    // if (found < 0) return null;
    Label foundMatch = new Label();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, foundVar);
    // S: [I] -> []
    mv.visitJumpInsn(IFGE, foundMatch);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);

    mv.visitLabel(foundMatch);
    // Now do full matching to capture groups
    // This is similar to match() but starting at found position

    Label returnNull = new Label();

    // Use the found position as starting point
    int posVar = allocator.allocate();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, foundVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, posVar);

    // Match prefix (if any)
    int nextVar =
        generatePrefixMatching(mv, inputVar, posVar, lenVar, returnNull, allocator.peek());

    // Do backtracking match with group capture
    switch (info.suffixType) {
      case LITERAL:
        generateFindMatchLiteralSuffix(mv, inputVar, posVar, lenVar, foundVar, nextVar, returnNull);
        break;
      case QUANTIFIED_CHAR_CLASS:
        generateFindMatchQuantifiedCharClassSuffix(mv, inputVar, posVar, lenVar, foundVar, nextVar);
        break;
      case WORD_BOUNDARY_QUANTIFIED_CHAR_CLASS:
        generateFindMatchWordBoundaryQuantifiedCharClassSuffix(
            mv, inputVar, posVar, lenVar, foundVar, nextVar);
        break;
      default:
        mv.visitJumpInsn(GOTO, returnNull);
        break;
    }

    mv.visitLabel(returnNull);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate findMatch logic for literal suffix.
   *
   * <p>For greedy matching (e.g., foo(.*)bar), we need to find the LAST occurrence of the suffix to
   * maximize what the greedy quantifier captures.
   *
   * <p>Example: "The food is under the bar in the barn." with pattern foo(.*)bar - Find "foo" at
   * position 4 (in "food") - Find LAST "bar" = position 29 (in "bar" before "n.") - Group 1
   * captures "d is under the bar in the " (positions 7-29)
   */
  private void generateFindMatchLiteralSuffix(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      int foundVar,
      int nextVar,
      Label returnNull) {

    String literal = info.suffixLiteral;
    int literalLen = literal.length();
    int totalGroups = info.totalGroupCount;

    // Find LAST occurrence of literal after prefix (for greedy matching)
    int literalPosVar = nextVar;
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, A:String]
    mv.visitLdcInsn(literal);
    // S: [A:String, A:String] -> [I]
    // Use lastIndexOf to find the rightmost occurrence (greedy behavior)
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(Ljava/lang/String;)I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, literalPosVar);

    // Verify the found position is >= pos (after prefix) and >= 0
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, literalPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLT, returnNull);

    // Build MatchResult
    // Group 0: [found, literalPos + literalLen]
    // Group 1: [pos, literalPos]
    int matchEndVar = nextVar + 1;
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, literalPosVar);
    // S: [I] -> [I, I]
    pushInt(mv, literalLen);
    // S: [I, I] -> [I]
    mv.visitInsn(IADD);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, matchEndVar);

    generateMatchResultForFind(
        mv, inputVar, foundVar, matchEndVar, posVar, literalPosVar, totalGroups, nextVar + 2);
    // generateMatchResultForFind ends with ARETURN, so no fall-through to returnNull
  }

  /** Generate findMatch logic for quantified char class suffix. */
  private void generateFindMatchQuantifiedCharClassSuffix(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, int foundVar, int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;
    int totalGroups = info.totalGroupCount;

    int scanPosVar = nextVar;
    int charVar = nextVar + 1;
    int matchEndVar = nextVar + 2;
    int suffixStartVar = nextVar + 3;
    int suffixEndVar = nextVar + 4;

    // Step 1: Find where the match ends by scanning BACKWARD from end to find rightmost suffix
    // For (.*)(\d+) on "I have 2 numbers: 53147" starting at pos=0:
    //   - Scan backward from end (23) to find last digit: found at 22 (char='7')
    //   - Scan backward while digits continue: 22, 21, 20, 19, 18 all digits
    //   - Stop at 17 (char=' ', not digit)
    //   - Match ends at 23, rightmost suffix starts at 18

    // scanPos = lenVar (start from end)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, scanPosVar);

    // Scan backward to find last char in suffix charset
    Label findLastLoop = new Label();
    Label foundLast = new Label();

    mv.visitLabel(findLastLoop);
    // if (scanPos <= posVar) goto foundLast (no suffix chars found, shouldn't happen if findFrom
    // succeeded)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, foundLast);

    // char c = input.charAt(scanPos - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (isInCharSet(c)) goto foundLast - found rightmost suffix char
    Label inCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, inCharSet);
    // Not in charset, continue scanning backward
    mv.visitIincInsn(scanPosVar, -1);
    mv.visitJumpInsn(GOTO, findLastLoop);

    mv.visitLabel(inCharSet);
    mv.visitLabel(foundLast);
    // scanPos now points just after the last suffix char (match end)
    // matchEnd = scanPos
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, matchEndVar);

    // Step 3: Do greedy backtracking within [posVar, matchEnd] to split greedy/suffix
    // Scan backward from matchEnd to give minimum to suffix
    // suffixEnd = matchEnd
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, matchEndVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixEndVar);

    // suffixStart = suffixEnd
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixEndVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixStartVar);

    Label backtrackLoop = new Label();
    Label backtrackDone = new Label();

    mv.visitLabel(backtrackLoop);
    // if (suffixStart <= posVar) goto backtrackDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, backtrackDone);

    // if ((suffixEnd - suffixStart) >= suffixMinCount) goto backtrackDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixEndVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, backtrackDone);

    // char c = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isInCharSet(c)) goto backtrackDone
    Label isInSuffixCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, isInSuffixCharSet);
    mv.visitJumpInsn(GOTO, backtrackDone);

    mv.visitLabel(isInSuffixCharSet);
    // It's a suffix char, include it
    mv.visitIincInsn(suffixStartVar, -1);
    mv.visitJumpInsn(GOTO, backtrackLoop);

    mv.visitLabel(backtrackDone);

    // Build MatchResult with two groups
    // Group 0: [foundVar, matchEndVar] - full match (foundVar is match start returned by findFrom)
    // Group 1: [posVar, suffixStartVar] - greedy group (posVar is after prefix)
    // Group 2: [suffixStartVar, suffixEndVar] - suffix group
    generateMatchResultForFindWithSuffixGroup(
        mv,
        inputVar,
        foundVar,
        matchEndVar,
        posVar,
        suffixStartVar,
        suffixEndVar,
        totalGroups,
        suffixEndVar + 1);
  }

  /**
   * Generate findMatch logic for word boundary + quantified char class suffix. Pattern: (.*)\b(\d+)
   */
  private void generateFindMatchWordBoundaryQuantifiedCharClassSuffix(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, int foundVar, int nextVar) {

    CharSet suffixCharSet = info.suffixCharSet;
    int suffixMinCount = info.suffixMinCount;
    int totalGroups = info.totalGroupCount;

    int scanPosVar = nextVar;
    int charVar = nextVar + 1;
    int matchEndVar = nextVar + 2;
    int suffixStartVar = nextVar + 3;
    int suffixEndVar = nextVar + 4;

    Label returnNull = new Label();

    // Step 1: Find where the match ends by scanning BACKWARD from end to find rightmost suffix
    // scanPos = lenVar (start from end)
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, lenVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, scanPosVar);

    // Scan backward to find last char in suffix charset
    Label findLastLoop = new Label();
    Label foundLast = new Label();

    mv.visitLabel(findLastLoop);
    // if (scanPos <= posVar) goto foundLast
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, foundLast);

    // char c = input.charAt(scanPos - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (isInCharSet(c)) goto foundLast - found rightmost suffix char
    Label inCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, inCharSet);
    // Not in charset, continue scanning backward
    mv.visitIincInsn(scanPosVar, -1);
    mv.visitJumpInsn(GOTO, findLastLoop);

    mv.visitLabel(inCharSet);
    mv.visitLabel(foundLast);
    // scanPos now points just after the last suffix char (match end)
    // matchEnd = scanPos
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, scanPosVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, matchEndVar);

    // Step 2: Scan backward from matchEnd to find suffix start
    // suffixEnd = matchEnd
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, matchEndVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixEndVar);

    // suffixStart = suffixEnd
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixEndVar);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, suffixStartVar);

    Label backtrackLoop = new Label();
    Label backtrackDone = new Label();

    mv.visitLabel(backtrackLoop);
    // if (suffixStart <= posVar) goto backtrackDone
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, backtrackDone);

    // NOTE: For word boundary, we do NOT have the early-exit condition based on suffixMinCount.
    // We must scan ALL suffix chars to find where they actually START (the word boundary position).

    // char c = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    // S: [C] -> []
    mv.visitVarInsn(ISTORE, charVar);

    // if (!isInCharSet(c)) goto backtrackDone
    Label isInSuffixCharSet = new Label();
    generateCharSetCheckJump(mv, suffixCharSet, charVar, isInSuffixCharSet);
    mv.visitJumpInsn(GOTO, backtrackDone);

    mv.visitLabel(isInSuffixCharSet);
    // It's a suffix char, include it
    mv.visitIincInsn(suffixStartVar, -1);
    mv.visitJumpInsn(GOTO, backtrackLoop);

    mv.visitLabel(backtrackDone);

    // Step 3: Verify we found enough suffix chars
    // if ((suffixEnd - suffixStart) < suffixMinCount) goto returnNull
    Label haveSuffix = new Label();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixEndVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I, I] -> [I]
    mv.visitInsn(ISUB);
    // S: [I] -> [I, I]
    pushInt(mv, suffixMinCount);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPGE, haveSuffix);
    mv.visitJumpInsn(GOTO, returnNull);

    mv.visitLabel(haveSuffix);

    // Step 4: Check word boundary at suffixStart
    Label boundaryOk = new Label();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [I] -> [I, I]
    mv.visitVarInsn(ILOAD, posVar);
    // S: [I, I] -> []
    mv.visitJumpInsn(IF_ICMPLE, boundaryOk); // if suffixStart <= pos, boundary at start

    // char prevChar = input.charAt(suffixStart - 1)
    // S: [] -> [A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:String] -> [A:String, I]
    mv.visitVarInsn(ILOAD, suffixStartVar);
    // S: [A:String, I] -> [A:String, I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:String, I, I] -> [A:String, I]
    mv.visitInsn(ISUB);
    // S: [A:String, I] -> [C]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // Check if prevChar is a word char - if so, no boundary, return null
    generateWordCharCheck(mv, returnNull, boundaryOk);

    mv.visitLabel(boundaryOk);

    // Build MatchResult with two groups
    generateMatchResultForFindWithSuffixGroup(
        mv,
        inputVar,
        foundVar,
        matchEndVar,
        posVar,
        suffixStartVar,
        suffixEndVar,
        totalGroups,
        suffixEndVar + 1);

    mv.visitLabel(returnNull);
    // S: [] -> [A:null]
    mv.visitInsn(ACONST_NULL);
    // S: [A:null] -> []
    mv.visitInsn(ARETURN);
  }

  /** Generate MatchResult for find operations (single group). */
  private void generateMatchResultForFind(
      MethodVisitor mv,
      int inputVar,
      int matchStartVar,
      int matchEndVar,
      int greedyStartVar,
      int greedyEndVar,
      int totalGroups,
      int nextVar) {

    int startsVar = nextVar;
    int endsVar = nextVar + 1;

    // int[] starts = new int[totalGroups + 1]
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, matchStartVar); // group 0 start
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, greedyStartVar); // group 1 start
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[totalGroups + 1]
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, matchEndVar); // group 0 end
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, greedyEndVar); // group 1 end
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(...)
    // S: [] -> [A:MatchResultImpl]
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    // S: [A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl]
    mv.visitInsn(DUP);
    // S: [A:MatchResultImpl, A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl, A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String] -> [A:MatchResultImpl, A:MatchResultImpl,
    // A:String, A:[I]
    mv.visitVarInsn(ALOAD, startsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I]
    mv.visitVarInsn(ALOAD, endsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I, I]
    pushInt(mv, totalGroups);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I, I] -> [A:MatchResultImpl]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    // S: [A:MatchResultImpl] -> []
    mv.visitInsn(ARETURN);
  }

  /** Generate MatchResult for find operations (two groups). */
  private void generateMatchResultForFindWithSuffixGroup(
      MethodVisitor mv,
      int inputVar,
      int matchStartVar,
      int matchEndVar,
      int greedyStartVar,
      int suffixStartVar,
      int suffixEndVar,
      int totalGroups,
      int nextVar) {

    int startsVar = nextVar;
    int endsVar = nextVar + 1;

    // int[] starts
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // Group 0 start
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, matchStartVar);
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // Group 1 start
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, greedyStartVar);
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    if (totalGroups >= 2) {
      // Group 2 start
      // S: [A:[I] -> [A:[I, A:[I]
      mv.visitInsn(DUP);
      // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
      pushInt(mv, 2);
      // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
      mv.visitVarInsn(ILOAD, suffixStartVar);
      // S: [A:[I, A:[I, I, I] -> [A:[I]
      mv.visitInsn(IASTORE);
    }
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends
    // S: [] -> [I]
    pushInt(mv, totalGroups + 1);
    // S: [I] -> [A:[I]
    mv.visitIntInsn(NEWARRAY, T_INT);
    // Group 0 end
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 0);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, matchEndVar);
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    // Group 1 end
    // S: [A:[I] -> [A:[I, A:[I]
    mv.visitInsn(DUP);
    // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
    pushInt(mv, 1);
    // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
    mv.visitVarInsn(ILOAD, suffixStartVar); // greedy ends where suffix starts
    // S: [A:[I, A:[I, I, I] -> [A:[I]
    mv.visitInsn(IASTORE);
    if (totalGroups >= 2) {
      // Group 2 end
      // S: [A:[I] -> [A:[I, A:[I]
      mv.visitInsn(DUP);
      // S: [A:[I, A:[I] -> [A:[I, A:[I, I]
      pushInt(mv, 2);
      // S: [A:[I, A:[I, I] -> [A:[I, A:[I, I, I]
      mv.visitVarInsn(ILOAD, suffixEndVar);
      // S: [A:[I, A:[I, I, I] -> [A:[I]
      mv.visitInsn(IASTORE);
    }
    // S: [A:[I] -> []
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(...)
    // S: [] -> [A:MatchResultImpl]
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    // S: [A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl]
    mv.visitInsn(DUP);
    // S: [A:MatchResultImpl, A:MatchResultImpl] -> [A:MatchResultImpl, A:MatchResultImpl, A:String]
    mv.visitVarInsn(ALOAD, inputVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String] -> [A:MatchResultImpl, A:MatchResultImpl,
    // A:String, A:[I]
    mv.visitVarInsn(ALOAD, startsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I]
    mv.visitVarInsn(ALOAD, endsVar);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I] -> [A:MatchResultImpl,
    // A:MatchResultImpl, A:String, A:[I, A:[I, I]
    pushInt(mv, totalGroups);
    // S: [A:MatchResultImpl, A:MatchResultImpl, A:String, A:[I, A:[I, I] -> [A:MatchResultImpl]
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    // S: [A:MatchResultImpl] -> []
    mv.visitInsn(ARETURN);
  }

  public void generateMatchesBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=start, 3=end
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int subStringVar = allocator.allocate();

    // Simplified: convert to String and check bounds
    // S: [] -> [A:CharSequence]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:CharSequence] -> [A:CharSequence, I]
    mv.visitVarInsn(ILOAD, 2);
    // S: [A:CharSequence, I] -> [A:CharSequence, I, I]
    mv.visitVarInsn(ILOAD, 3);
    // S: [A:CharSequence, I, I] -> [A:CharSequence]
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    // S: [A:CharSequence] -> [A:String]
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    // S: [A:String] -> []
    mv.visitVarInsn(ASTORE, subStringVar);

    // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 0);
    // S: [A:this] -> [A:this, A:String]
    mv.visitVarInsn(ALOAD, subStringVar);
    // S: [A:this, A:String] -> [Z]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    // S: [Z] -> []
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

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
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int subStringVar = allocator.allocate();

    // Simplified: convert to String
    // S: [] -> [A:CharSequence]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:CharSequence] -> [A:CharSequence, I]
    mv.visitVarInsn(ILOAD, 2);
    // S: [A:CharSequence, I] -> [A:CharSequence, I, I]
    mv.visitVarInsn(ILOAD, 3);
    // S: [A:CharSequence, I, I] -> [A:CharSequence]
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "java/lang/CharSequence",
        "subSequence",
        "(II)Ljava/lang/CharSequence;",
        true);
    // S: [A:CharSequence] -> [A:String]
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);
    // S: [A:String] -> []
    mv.visitVarInsn(ASTORE, subStringVar);

    // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 0);
    // S: [A:this] -> [A:this, A:String]
    mv.visitVarInsn(ALOAD, subStringVar);
    // S: [A:this, A:String] -> [A:MatchResult]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    // S: [A:MatchResult] -> []
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindBoundsFromMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // Local vars: 0=this, 1=input, 2=startPos, 3=bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int foundVar = allocator.allocate();

    // int found = findFrom(input, startPos);
    // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 0);
    // S: [A:this] -> [A:this, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:this, A:String] -> [A:this, A:String, I]
    mv.visitVarInsn(ILOAD, 2);
    // S: [A:this, A:String, I] -> [I]
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    // S: [I] -> []
    mv.visitVarInsn(ISTORE, foundVar);

    // if (found < 0) return false;
    Label foundMatch = new Label();
    // S: [] -> [I]
    mv.visitVarInsn(ILOAD, foundVar);
    // S: [I] -> []
    mv.visitJumpInsn(IFGE, foundMatch);
    // S: [] -> [I]
    mv.visitInsn(ICONST_0);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitLabel(foundMatch);
    // bounds[0] = found; bounds[1] = found + matchLen (approximate)
    // S: [] -> [A:[I]
    mv.visitVarInsn(ALOAD, 3);
    // S: [A:[I] -> [A:[I, I]
    mv.visitInsn(ICONST_0);
    // S: [A:[I, I] -> [A:[I, I, I]
    mv.visitVarInsn(ILOAD, foundVar);
    // S: [A:[I, I, I] -> []
    mv.visitInsn(IASTORE);

    // For now, set end = len (conservative)
    // S: [] -> [A:[I]
    mv.visitVarInsn(ALOAD, 3);
    // S: [A:[I] -> [A:[I, I]
    mv.visitInsn(ICONST_1);
    // S: [A:[I, I] -> [A:[I, I, A:String]
    mv.visitVarInsn(ALOAD, 1);
    // S: [A:[I, I, A:String] -> [A:[I, I, I]
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    // S: [A:[I, I, I] -> []
    mv.visitInsn(IASTORE);

    // S: [] -> [I]
    mv.visitInsn(ICONST_1);
    // S: [I] -> []
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // --- Helper methods ---

  /**
   * Generate prefix matching code.
   *
   * @return next available local variable slot
   */
  private int generatePrefixMatching(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label failLabel, int nextVar) {
    for (RegexNode node : info.prefix) {
      if (node instanceof LiteralNode) {
        LiteralNode lit = (LiteralNode) node;
        // if (pos >= len || input.charAt(pos) != ch) goto fail;
        // S: [] -> [I]
        mv.visitVarInsn(ILOAD, posVar);
        // S: [I] -> [I, I]
        mv.visitVarInsn(ILOAD, lenVar);
        // S: [I, I] -> []
        mv.visitJumpInsn(IF_ICMPGE, failLabel);

        // S: [] -> [A:String]
        mv.visitVarInsn(ALOAD, inputVar);
        // S: [A:String] -> [A:String, I]
        mv.visitVarInsn(ILOAD, posVar);
        // S: [A:String, I] -> [C]
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        // S: [C] -> [C, I]
        pushInt(mv, lit.ch);
        // S: [C, I] -> []
        mv.visitJumpInsn(IF_ICMPNE, failLabel);

        // pos++
        mv.visitIincInsn(posVar, 1);
      } else if (node instanceof CharClassNode) {
        CharClassNode cc = (CharClassNode) node;
        // if (pos >= len) goto fail;
        // S: [] -> [I]
        mv.visitVarInsn(ILOAD, posVar);
        // S: [I] -> [I, I]
        mv.visitVarInsn(ILOAD, lenVar);
        // S: [I, I] -> []
        mv.visitJumpInsn(IF_ICMPGE, failLabel);

        // char c = input.charAt(pos);
        // S: [] -> [A:String]
        mv.visitVarInsn(ALOAD, inputVar);
        // S: [A:String] -> [A:String, I]
        mv.visitVarInsn(ILOAD, posVar);
        // S: [A:String, I] -> [C]
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

        // if (!charSet.contains(c)) goto fail;
        if (cc.negated) {
          // Negated: fail if char IS in charset
          Label notInSet = new Label();
          generateCharSetCheckJumpStack(mv, cc.chars, notInSet);
          mv.visitJumpInsn(GOTO, failLabel);
          mv.visitLabel(notInSet);
        } else {
          generateCharSetCheckFail(mv, cc.chars, failLabel);
        }

        // pos++
        mv.visitIincInsn(posVar, 1);
      }
    }
    return nextVar;
  }

  /**
   * Generate prefix matching with explicit fail label.
   *
   * @return next available local variable slot
   */
  private int generatePrefixMatchingWithFail(
      MethodVisitor mv, int inputVar, int posVar, int lenVar, Label failLabel, int nextVar) {
    return generatePrefixMatching(mv, inputVar, posVar, lenVar, failLabel, nextVar);
  }

  /** Generate charset check - jumps to target if char (on stack) IS in charset. */
  private void generateCharSetCheckJumpStack(MethodVisitor mv, CharSet chars, Label target) {
    // Char is on stack
    if (chars.isSingleChar()) {
      char c = chars.getSingleChar();
      // S: [C] -> [C, I]
      pushInt(mv, c);
      // S: [C, I] -> []
      mv.visitJumpInsn(IF_ICMPEQ, target);
    } else if (chars.isSimpleRange()) {
      CharSet.Range range = chars.getSimpleRange();
      // S: [C] -> [C, C]
      mv.visitInsn(DUP);
      // S: [C, C] -> [C, C, I]
      pushInt(mv, range.start);
      Label notInRange = new Label();
      // S: [C, C, I] -> [C]
      mv.visitJumpInsn(IF_ICMPLT, notInRange);
      // S: [C] -> [C, I]
      pushInt(mv, range.end);
      // S: [C, I] -> []
      mv.visitJumpInsn(IF_ICMPLE, target);
      mv.visitLabel(notInRange);
      // S: [C] -> []
      mv.visitInsn(POP);
    } else {
      // Multiple ranges - check each
      for (CharSet.Range range : chars.getRanges()) {
        // S: [C] -> [C, C]
        mv.visitInsn(DUP);
        // S: [C, C] -> [C, C, I]
        pushInt(mv, range.start);
        Label tryNext = new Label();
        // S: [C, C, I] -> [C]
        mv.visitJumpInsn(IF_ICMPLT, tryNext);
        // S: [C] -> [C, C]
        mv.visitInsn(DUP);
        // S: [C, C] -> [C, C, I]
        pushInt(mv, range.end);
        Label inRange = new Label();
        // S: [C, C, I] -> [C]
        mv.visitJumpInsn(IF_ICMPLE, inRange);
        mv.visitLabel(tryNext);
        continue;

        // Can't easily break out - use simpler approach
      }
      // Complex ranges - use helper
      mv.visitInsn(POP);
    }
  }

  /** Generate charset check - jumps to target if char (in variable) IS in charset. */
  private void generateCharSetCheckJump(
      MethodVisitor mv, CharSet chars, int charVar, Label target) {
    if (chars.isSingleChar()) {
      char c = chars.getSingleChar();
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, charVar);
      // S: [I] -> [I, I]
      pushInt(mv, c);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPEQ, target);
    } else if (chars.isSimpleRange()) {
      CharSet.Range range = chars.getSimpleRange();
      // if (c >= start && c <= end) goto target
      Label notInRange = new Label();
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, charVar);
      // S: [I] -> [I, I]
      pushInt(mv, range.start);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLT, notInRange);
      // S: [] -> [I]
      mv.visitVarInsn(ILOAD, charVar);
      // S: [I] -> [I, I]
      pushInt(mv, range.end);
      // S: [I, I] -> []
      mv.visitJumpInsn(IF_ICMPLE, target);
      mv.visitLabel(notInRange);
    } else {
      // Multiple ranges
      for (CharSet.Range range : chars.getRanges()) {
        Label tryNext = new Label();
        // S: [] -> [I]
        mv.visitVarInsn(ILOAD, charVar);
        // S: [I] -> [I, I]
        pushInt(mv, range.start);
        // S: [I, I] -> []
        mv.visitJumpInsn(IF_ICMPLT, tryNext);
        // S: [] -> [I]
        mv.visitVarInsn(ILOAD, charVar);
        // S: [I] -> [I, I]
        pushInt(mv, range.end);
        // S: [I, I] -> []
        mv.visitJumpInsn(IF_ICMPLE, target);
        mv.visitLabel(tryNext);
      }
    }
  }

  /** Generate charset check - jumps to fail if char (on stack) is NOT in charset. */
  private void generateCharSetCheckFail(MethodVisitor mv, CharSet chars, Label failLabel) {
    // Char is on stack
    if (chars.isSingleChar()) {
      char c = chars.getSingleChar();
      // S: [C] -> [C, I]
      pushInt(mv, c);
      // S: [C, I] -> []
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
    } else if (chars.isSimpleRange()) {
      CharSet.Range range = chars.getSimpleRange();
      // if (c < start || c > end) goto fail
      Label failPop = new Label();
      Label inRange = new Label();
      // S: [C] -> [C, C]
      mv.visitInsn(DUP);
      // S: [C, C] -> [C, C, I]
      pushInt(mv, range.start);
      // S: [C, C, I] -> [C]
      mv.visitJumpInsn(IF_ICMPLT, failPop);
      // S: [C] -> [C, C]
      mv.visitInsn(DUP);
      // S: [C, C] -> [C, C, I]
      pushInt(mv, range.end);
      // S: [C, C, I] -> [C]
      mv.visitJumpInsn(IF_ICMPGT, failPop);
      // In range - pop char and continue
      // S: [C] -> []
      mv.visitInsn(POP);
      // S: [] -> []
      mv.visitJumpInsn(GOTO, inRange);
      // Out of range - pop char and fail
      mv.visitLabel(failPop);
      // S: [C] -> []
      mv.visitInsn(POP);
      // S: [] -> []
      mv.visitJumpInsn(GOTO, failLabel);
      mv.visitLabel(inRange);
      // S: []
    } else {
      // Multiple ranges - check if in ANY range
      Label ok = new Label();
      for (CharSet.Range range : chars.getRanges()) {
        Label tryNext = new Label();
        // S: [C] -> [C, C]
        mv.visitInsn(DUP);
        // S: [C, C] -> [C, C, I]
        pushInt(mv, range.start);
        // S: [C, C, I] -> [C]
        mv.visitJumpInsn(IF_ICMPLT, tryNext);
        // S: [C] -> [C, C]
        mv.visitInsn(DUP);
        // S: [C, C] -> [C, C, I]
        pushInt(mv, range.end);
        // S: [C, C, I] -> [C]
        mv.visitJumpInsn(IF_ICMPLE, ok);
        mv.visitLabel(tryNext);
      }
      // Not in any range - fail
      // S: [C] -> []
      mv.visitInsn(POP);
      mv.visitJumpInsn(GOTO, failLabel);
      mv.visitLabel(ok);
      // S: [C] -> []
      mv.visitInsn(POP);
    }
  }

  /**
   * Check if a charset matches all characters (no validation needed). This is true for CharSet.ANY
   * and CharSet.ANY_EXCEPT_NEWLINE (used by `.`).
   */
  private boolean isAnyCharSet(CharSet charset) {
    return charset.equals(CharSet.ANY) || charset.equals(CharSet.ANY_EXCEPT_NEWLINE);
  }
}
