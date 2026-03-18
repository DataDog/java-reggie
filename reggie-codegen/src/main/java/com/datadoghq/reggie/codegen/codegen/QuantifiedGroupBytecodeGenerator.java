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

import com.datadoghq.reggie.codegen.analysis.QuantifiedGroupInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates optimized bytecode for quantified capturing groups with POSIX last-match semantics.
 *
 * <h3>Pattern Types</h3>
 *
 * {@code (child)+}, {@code (child)*}, {@code (child){n,m}} Examples: {@code (a)+}, {@code (a|b)+},
 * {@code ([a-z])*}
 *
 * <h3>Generated Algorithm</h3>
 *
 * This strategy generates bytecode that explicitly tracks the last successful iteration,
 * implementing POSIX semantics where groups capture from the LAST iteration.
 *
 * <pre>{@code
 * // For pattern (a)+
 * int matchPattern(String input) {
 *     int pos = 0, len = input.length();
 *     int lastIterStart = -1, lastIterEnd = -1;
 *     int count = 0;
 *
 *     while (pos < len) {
 *         int iterStart = pos;
 *         if (input.charAt(pos) == 'a') {
 *             pos++;
 *             count++;
 *             // Update on SUCCESS only (POSIX semantics)
 *             lastIterStart = iterStart;
 *             lastIterEnd = pos;
 *         } else {
 *             break;
 *         }
 *     }
 *
 *     if (count >= 1) {
 *         // Group captures from LAST successful iteration
 *         groups[2] = lastIterStart;
 *         groups[3] = lastIterEnd;
 *         return pos;
 *     }
 *     return -1;
 * }
 * }</pre>
 *
 * <h3>Key Feature</h3>
 *
 * Explicit tracking of last successful iteration ensures POSIX compliance - groups capture from the
 * LAST iteration, not the first. This is essential for patterns like {@code ([a-z])+} matching
 * "abc" where group 1 should capture "c", not "a".
 */
public class QuantifiedGroupBytecodeGenerator {

  private final QuantifiedGroupInfo info;
  private final String className;

  public QuantifiedGroupBytecodeGenerator(QuantifiedGroupInfo info, String className) {
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

    // Slot 0 = this, 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int countVar = allocator.allocate();

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

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label failLabel = new Label();

    // while (pos < len)
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Check max bound if not unbounded
    if (!info.isUnbounded()) {
      // if (count >= max) break;
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxQuantifier);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    if (info.hasNestedQuantifier()) {
      // Nested quantifier: match multiple chars per outer iteration
      Label iterSuccess = new Label();
      generateNestedQuantifierMatchForMatches(
          mv, inputVar, posVar, lenVar, loopEnd, iterSuccess, allocator);
      mv.visitLabel(iterSuccess);
      // count++ happens below
    } else if (info.hasComplexAlternation) {
      // Complex alternation: handle in-loop
      Label iterSuccess = new Label();
      generateComplexAlternationMatchForMatches(
          mv, inputVar, posVar, lenVar, loopEnd, iterSuccess, allocator);
      mv.visitLabel(iterSuccess);
    } else {
      // Simple case: single char per iteration
      // char ch = input.charAt(pos);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

      // Check if character matches
      generateCharCheck(mv, loopEnd, allocator);

      // pos++ (count++ is done after the if/else)
      mv.visitIincInsn(posVar, 1);
    }

    // count++;
    mv.visitIincInsn(countVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check minimum constraint: if (count < min) fail
    if (info.minQuantifier > 0) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.minQuantifier);
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
    }

    // Check that we consumed entire input: pos == len
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

  /**
   * Generate match() method - matches from start, extracts groups. Returns: MatchResult or null
   *
   * <p>This is the core method that implements POSIX last-match semantics.
   */
  public void generateMatchMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, 1 = input
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int countVar = allocator.allocate();
    int lastIterStartVar = allocator.allocate();
    int lastIterEndVar = allocator.allocate();
    int iterStartVar = allocator.allocate();

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

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // int lastIterStart = -1;  (sentinel for "no match")
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, lastIterStartVar);

    // int lastIterEnd = -1;
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, lastIterEndVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label failLabel = new Label();

    // while (pos < len)
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Check max bound
    if (!info.isUnbounded()) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxQuantifier);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // int iterStart = pos;  (save position BEFORE matching attempt)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, iterStartVar);

    // Generate iteration matching code
    Label iterSuccess = new Label();
    if (info.hasComplexAlternation) {
      // Complex alternation with internal quantifiers: try each alternative
      generateComplexAlternationMatch(
          mv, inputVar, posVar, lenVar, loopEnd, iterSuccess, allocator);
    } else if (info.hasNestedQuantifier()) {
      // Nested quantifier: ([a-z]+)* - inner quantifier matches multiple chars per outer iteration
      generateNestedQuantifierMatch(mv, inputVar, posVar, lenVar, loopEnd, iterSuccess, allocator);
    } else {
      // Simple case: single char per iteration
      // char ch = input.charAt(pos);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      generateCharCheck(mv, loopEnd, allocator);
      mv.visitIincInsn(posVar, 1); // pos++
    }

    mv.visitLabel(iterSuccess);
    // Match! Update state:
    // count++;
    mv.visitIincInsn(countVar, 1);

    // lastIterStart = iterStart; lastIterEnd = pos;  (update AFTER success)
    mv.visitVarInsn(ILOAD, iterStartVar);
    mv.visitVarInsn(ISTORE, lastIterStartVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, lastIterEndVar);

    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check minimum constraint
    if (info.minQuantifier > 0) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.minQuantifier);
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
    }

    // Check that we consumed entire input
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, failLabel);

    // Success! Build MatchResult
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    // int[] starts = new int[]{0, lastIterStart};
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    pushInt(mv, 0); // group 0 starts at 0
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, lastIterStartVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[]{pos, lastIterEnd};
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, lastIterEndVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, 1);
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

  /**
   * Generate character check bytecode. Leaves stack empty, jumps to exitLabel if character doesn't
   * match.
   *
   * @param mv MethodVisitor
   * @param exitLabel Label to jump to if check fails
   * @param allocator LocalVarAllocator for allocating temp variables
   *     <p>Stack on entry: [I] (the character to check) Stack on exit: []
   */
  private void generateCharCheck(MethodVisitor mv, Label exitLabel, LocalVarAllocator allocator) {
    if (info.isSingleCharLiteral()) {
      // Single literal: if (ch != 'a') goto exit
      char ch = info.literal.charAt(0);
      pushInt(mv, ch);
      mv.visitJumpInsn(IF_ICMPNE, exitLabel);
    } else if (info.isAlternation) {
      // Alternation of simple elements: use combined charset
      int charVar = allocator.allocate();
      mv.visitVarInsn(ISTORE, charVar);
      generateCharSetCheck(mv, info.charSet, charVar, exitLabel, false);
    } else {
      // Char class
      int charVar = allocator.allocate();
      mv.visitVarInsn(ISTORE, charVar);
      generateCharSetCheck(mv, info.charSet, charVar, exitLabel, false);
    }
  }

  /** Generate charset check: if (!charset.contains(ch)) goto exitLabel */
  private void generateCharSetCheck(
      MethodVisitor mv, CharSet charset, int charVar, Label exitLabel, boolean negated) {
    if (charset.isSingleChar()) {
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
      pushInt(mv, (int) c); // S: [I] -> [I, I]
      mv.visitJumpInsn(negated ? IF_ICMPEQ : IF_ICMPNE, exitLabel); // S: [I, I] -> []
    } else if (charset.isSimpleRange()) {
      // Single range check: if (ch < min || ch > max) goto exitLabel;
      char min = charset.rangeStart();
      char max = charset.rangeEnd();

      if (!negated) {
        // Normal: exit if NOT in range
        mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
        pushInt(mv, (int) min); // S: [I] -> [I, I]
        mv.visitJumpInsn(IF_ICMPLT, exitLabel); // S: [I, I] -> []

        mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
        pushInt(mv, (int) max); // S: [I] -> [I, I]
        mv.visitJumpInsn(IF_ICMPGT, exitLabel); // S: [I, I] -> []
      } else {
        // Negated: exit if IN range
        Label done = new Label();

        mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
        pushInt(mv, (int) min); // S: [I] -> [I, I]
        mv.visitJumpInsn(IF_ICMPLT, done); // S: [I, I] -> []

        mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
        pushInt(mv, (int) max); // S: [I] -> [I, I]
        mv.visitJumpInsn(IF_ICMPLE, exitLabel); // S: [I, I] -> []

        mv.visitLabel(done);
      }
    } else {
      // Multiple ranges
      Label matched = new Label();

      for (CharSet.Range range : charset.getRanges()) {
        char min = range.start;
        char max = range.end;

        Label nextRange = new Label();
        mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
        pushInt(mv, (int) min); // S: [I] -> [I, I]
        mv.visitJumpInsn(IF_ICMPLT, nextRange); // S: [I, I] -> []

        mv.visitVarInsn(ILOAD, charVar); // S: [] -> [I]
        pushInt(mv, (int) max); // S: [I] -> [I, I]
        mv.visitJumpInsn(IF_ICMPLE, matched); // S: [I, I] -> []

        mv.visitLabel(nextRange);
      }

      // No range matched
      if (!negated) {
        mv.visitJumpInsn(GOTO, exitLabel);
      } else {
        Label continueLabel = new Label();
        mv.visitJumpInsn(GOTO, continueLabel);
        mv.visitLabel(matched);
        mv.visitJumpInsn(GOTO, exitLabel);
        mv.visitLabel(continueLabel);
        return;
      }

      mv.visitLabel(matched);
    }
  }

  /**
   * Generate nested quantifier matching for matches() method. Similar to
   * generateNestedQuantifierMatch but doesn't track iteration start/end.
   */
  private void generateNestedQuantifierMatchForMatches(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      Label successLabel,
      LocalVarAllocator allocator) {
    int charVar = allocator.allocate();
    int innerCountVar = allocator.allocate();

    // int innerCount = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, innerCountVar);

    // Inner greedy loop: match as many chars as possible
    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);
    // if (pos >= len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    // Check inner max bound if not unbounded
    if (info.innerMaxQuantifier != Integer.MAX_VALUE) {
      mv.visitVarInsn(ILOAD, innerCountVar);
      pushInt(mv, info.innerMaxQuantifier);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check if char matches the charset (handling negation)
    if (info.isNegatedCharSet()) {
      generateCharSetCheckForAlt(mv, info.charSet, charVar, innerEnd, true);
    } else {
      generateCharSetCheckForAlt(mv, info.charSet, charVar, innerEnd);
    }

    // pos++; innerCount++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(innerCountVar, 1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Check inner min bound
    mv.visitVarInsn(ILOAD, innerCountVar);
    pushInt(mv, info.innerMinQuantifier);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);

    mv.visitJumpInsn(GOTO, successLabel);
  }

  /**
   * Generate complex alternation matching for matches() method. Similar to
   * generateComplexAlternationMatch but for matches() context.
   */
  private void generateComplexAlternationMatchForMatches(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      Label successLabel,
      LocalVarAllocator allocator) {
    int numAlts = info.alternationCharSets.length;
    int charVar = allocator.allocate();
    int altStartVar = allocator.allocate();
    int altCountVar = allocator.allocate();

    for (int i = 0; i < numAlts; i++) {
      Label tryNextAlt = (i < numAlts - 1) ? new Label() : failLabel;
      CharSet altCharSet = info.alternationCharSets[i];
      int altMin = info.alternationMinBounds[i];
      int altMax = info.alternationMaxBounds[i];
      // Check if this alternative's charset is negated
      boolean altNegated = info.alternationNegated != null && info.alternationNegated[i];

      if (altMin == 1 && altMax == 1) {
        // Simple single-char alternative
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPGE, tryNextAlt);

        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, charVar);

        generateCharSetCheckForAlt(mv, altCharSet, charVar, tryNextAlt, altNegated);

        mv.visitIincInsn(posVar, 1);
        mv.visitJumpInsn(GOTO, successLabel);
      } else {
        // Greedy quantified alternative
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ISTORE, altStartVar);

        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, altCountVar);

        Label greedyLoop = new Label();
        Label greedyEnd = new Label();

        mv.visitLabel(greedyLoop);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPGE, greedyEnd);

        if (altMax != Integer.MAX_VALUE) {
          mv.visitVarInsn(ILOAD, altCountVar);
          pushInt(mv, altMax);
          mv.visitJumpInsn(IF_ICMPGE, greedyEnd);
        }

        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, charVar);

        generateCharSetCheckForAlt(mv, altCharSet, charVar, greedyEnd, altNegated);

        mv.visitIincInsn(posVar, 1);
        mv.visitIincInsn(altCountVar, 1);
        mv.visitJumpInsn(GOTO, greedyLoop);

        mv.visitLabel(greedyEnd);
        mv.visitVarInsn(ILOAD, altCountVar);
        pushInt(mv, altMin);
        Label minOk = new Label();
        mv.visitJumpInsn(IF_ICMPGE, minOk);

        mv.visitVarInsn(ILOAD, altStartVar);
        mv.visitVarInsn(ISTORE, posVar);
        mv.visitJumpInsn(GOTO, tryNextAlt);

        mv.visitLabel(minOk);
        mv.visitJumpInsn(GOTO, successLabel);
      }

      if (i < numAlts - 1) {
        mv.visitLabel(tryNextAlt);
      }
    }
  }

  /**
   * Generate bytecode for nested quantifier matching. Handles patterns like ([a-z]+)* where each
   * outer iteration greedily matches multiple characters using the inner quantifier.
   *
   * <p>For pattern ([ab]+)*: - Each outer iteration: match 1+ chars from [ab] - The whole match
   * captures the last iteration's content
   *
   * @param mv MethodVisitor
   * @param inputVar Variable slot for input string
   * @param posVar Variable slot for current position
   * @param lenVar Variable slot for string length
   * @param failLabel Label to jump to if inner min not met
   * @param successLabel Label to jump to on successful iteration
   * @param allocator LocalVarAllocator for temp variables
   */
  private void generateNestedQuantifierMatch(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      Label successLabel,
      LocalVarAllocator allocator) {
    int charVar = allocator.allocate();
    int innerCountVar = allocator.allocate();

    // int innerCount = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, innerCountVar);

    // Inner greedy loop: match as many chars as possible
    Label innerLoop = new Label();
    Label innerEnd = new Label();

    mv.visitLabel(innerLoop);
    // if (pos >= len) break
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerEnd);

    // Check inner max bound if not unbounded
    if (info.innerMaxQuantifier != Integer.MAX_VALUE) {
      mv.visitVarInsn(ILOAD, innerCountVar);
      pushInt(mv, info.innerMaxQuantifier);
      mv.visitJumpInsn(IF_ICMPGE, innerEnd);
    }

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    // Check if char matches the charset (handling negation)
    if (info.isNegatedCharSet()) {
      // Negated charset: char must NOT be in the charset
      generateCharSetCheckForAlt(mv, info.charSet, charVar, innerEnd, true);
    } else {
      // Normal charset: char must be in the charset
      generateCharSetCheckForAlt(mv, info.charSet, charVar, innerEnd);
    }

    // pos++; innerCount++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(innerCountVar, 1);
    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerEnd);

    // Check inner min bound: if (innerCount < innerMin) fail this outer iteration
    mv.visitVarInsn(ILOAD, innerCountVar);
    pushInt(mv, info.innerMinQuantifier);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);

    // Success! Jump to success label
    mv.visitJumpInsn(GOTO, successLabel);
  }

  /**
   * Generate bytecode for complex alternation matching. Tries each alternative in order; for
   * alternatives with internal quantifiers, matches greedily before moving to the next alternative.
   *
   * <p>For pattern (a+|b)+: - Alternative 0: a+ (greedy, min=1) - Alternative 1: b (single char)
   *
   * @param mv MethodVisitor
   * @param inputVar Variable slot for input string
   * @param posVar Variable slot for current position
   * @param lenVar Variable slot for string length
   * @param failLabel Label to jump to if no alternative matches
   * @param successLabel Label to jump to on successful match
   * @param allocator LocalVarAllocator for temp variables
   */
  private void generateComplexAlternationMatch(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      Label successLabel,
      LocalVarAllocator allocator) {
    int numAlts = info.alternationCharSets.length;

    // Allocate temp variables for this method
    int charVar = allocator.allocate();
    int altStartVar = allocator.allocate();
    int altCountVar = allocator.allocate();

    for (int i = 0; i < numAlts; i++) {
      Label tryNextAlt = (i < numAlts - 1) ? new Label() : failLabel;
      CharSet altCharSet = info.alternationCharSets[i];
      int altMin = info.alternationMinBounds[i];
      int altMax = info.alternationMaxBounds[i];

      // Check if this alternative's charset is negated
      boolean altNegated = info.alternationNegated != null && info.alternationNegated[i];

      if (altMin == 1 && altMax == 1) {
        // Simple single-char alternative: just check one character
        // if (pos >= len) goto tryNextAlt
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPGE, tryNextAlt);

        // char ch = input.charAt(pos);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, charVar);

        // if (!altCharSet.contains(ch)) goto tryNextAlt (or if altCharSet.contains(ch) for negated)
        generateCharSetCheckForAlt(mv, altCharSet, charVar, tryNextAlt, altNegated);

        // Match! pos++;
        mv.visitIincInsn(posVar, 1);
        mv.visitJumpInsn(GOTO, successLabel);

      } else {
        // Greedy quantified alternative (like a+ or [a-z]+)
        // int altStartPos = pos;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ISTORE, altStartVar);

        // int altCount = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, altCountVar);

        // Greedy loop: match as many as possible
        Label greedyLoop = new Label();
        Label greedyEnd = new Label();

        mv.visitLabel(greedyLoop);
        // if (pos >= len) break
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPGE, greedyEnd);

        // Check max bound if not unbounded
        if (altMax != Integer.MAX_VALUE) {
          mv.visitVarInsn(ILOAD, altCountVar);
          pushInt(mv, altMax);
          mv.visitJumpInsn(IF_ICMPGE, greedyEnd);
        }

        // char ch = input.charAt(pos);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, charVar);

        // if (!altCharSet.contains(ch)) break (or if altCharSet.contains(ch) for negated)
        generateCharSetCheckForAlt(mv, altCharSet, charVar, greedyEnd, altNegated);

        // pos++; altCount++;
        mv.visitIincInsn(posVar, 1);
        mv.visitIincInsn(altCountVar, 1);
        mv.visitJumpInsn(GOTO, greedyLoop);

        mv.visitLabel(greedyEnd);
        // Check min bound
        mv.visitVarInsn(ILOAD, altCountVar);
        pushInt(mv, altMin);
        Label minOk = new Label();
        mv.visitJumpInsn(IF_ICMPGE, minOk);

        // Min not met, restore pos and try next alternative
        mv.visitVarInsn(ILOAD, altStartVar);
        mv.visitVarInsn(ISTORE, posVar);
        mv.visitJumpInsn(GOTO, tryNextAlt);

        mv.visitLabel(minOk);
        // Success!
        mv.visitJumpInsn(GOTO, successLabel);
      }

      // Label for trying next alternative
      if (i < numAlts - 1) {
        mv.visitLabel(tryNextAlt);
      }
    }
  }

  /**
   * Generate charset check for alternation matching. If char is not in charset, jump to exitLabel.
   */
  private void generateCharSetCheckForAlt(
      MethodVisitor mv, CharSet charset, int charVar, Label exitLabel) {
    generateCharSetCheckForAlt(mv, charset, charVar, exitLabel, false);
  }

  /**
   * Generate charset check for alternation matching with negation support. If negated=false: char
   * must be IN charset, else jump to exitLabel. If negated=true: char must NOT be in charset, else
   * jump to exitLabel.
   */
  private void generateCharSetCheckForAlt(
      MethodVisitor mv, CharSet charset, int charVar, Label exitLabel, boolean negated) {
    if (charset.isSingleChar()) {
      char c = charset.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, (int) c);
      // Normal: exit if NOT equal. Negated: exit if equal
      mv.visitJumpInsn(negated ? IF_ICMPEQ : IF_ICMPNE, exitLabel);
    } else if (charset.isSimpleRange()) {
      char min = charset.rangeStart();
      char max = charset.rangeEnd();
      if (!negated) {
        // Normal: exit if NOT in range
        // if (ch < min || ch > max) goto exitLabel
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) min);
        mv.visitJumpInsn(IF_ICMPLT, exitLabel);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) max);
        mv.visitJumpInsn(IF_ICMPGT, exitLabel);
      } else {
        // Negated: exit if IN range
        // if (ch >= min && ch <= max) goto exitLabel
        Label notInRange = new Label();
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) min);
        mv.visitJumpInsn(IF_ICMPLT, notInRange);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) max);
        mv.visitJumpInsn(IF_ICMPLE, exitLabel);
        mv.visitLabel(notInRange);
      }
    } else {
      // Multiple ranges
      Label matched = new Label();
      for (CharSet.Range range : charset.getRanges()) {
        Label nextRange = new Label();
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, nextRange);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, matched);
        mv.visitLabel(nextRange);
      }
      // No range matched
      if (!negated) {
        // Normal: no match means exit
        mv.visitJumpInsn(GOTO, exitLabel);
        mv.visitLabel(matched);
      } else {
        // Negated: no match means continue, match means exit
        Label continueLabel = new Label();
        mv.visitJumpInsn(GOTO, continueLabel);
        mv.visitLabel(matched);
        mv.visitJumpInsn(GOTO, exitLabel);
        mv.visitLabel(continueLabel);
      }
    }
  }

  /** Generate find() method - searches for match anywhere. */
  public void generateFindMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Delegate to findMatchFrom
    mv.visitVarInsn(ALOAD, 0); // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 1); // S: [...] -> [..., A:String]
    mv.visitInsn(ICONST_0); // S: [...] -> [..., I]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false); // S: [...] -> [A:MR]

    // return result != null;
    Label notNull = new Label();
    mv.visitInsn(DUP); // S: [A:MR] -> [A:MR, A:MR]
    mv.visitJumpInsn(IFNONNULL, notNull); // S: [...] -> [A:MR]
    mv.visitInsn(POP); // S: [A:MR] -> []
    mv.visitInsn(ICONST_0); // S: [] -> [I]
    mv.visitInsn(IRETURN); // S: [I] -> []

    mv.visitLabel(notNull);
    mv.visitInsn(POP); // S: [A:MR] -> []
    mv.visitInsn(ICONST_1); // S: [] -> [I]
    mv.visitInsn(IRETURN); // S: [I] -> []

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findMatchFrom() - searches for match from position. */
  public void generateFindMatchFromMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int inputVar = 1;
    int startPosVar = 2;
    int lenVar = allocator.allocate();
    int posVar = allocator.allocate();
    int countVar = allocator.allocate();
    int lastIterStartVar = allocator.allocate();
    int lastIterEndVar = allocator.allocate();
    int iterStartVar = allocator.allocate();
    int matchStartVar = allocator.allocate();

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

    // Try matching from each position starting at startPos
    Label outerLoop = new Label();
    Label notFound = new Label();

    mv.visitLabel(outerLoop);

    // if (startPos > len) return null;
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, notFound);

    // int matchStart = startPos;
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // int pos = startPos;
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int count = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, countVar);

    // int lastIterStart = -1, lastIterEnd = -1;
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, lastIterStartVar);
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, lastIterEndVar);

    Label innerLoop = new Label();
    Label innerLoopEnd = new Label();
    Label tryNextPos = new Label();

    // Inner loop: match as many as possible
    mv.visitLabel(innerLoop);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, innerLoopEnd);

    // Check max bound
    if (!info.isUnbounded()) {
      mv.visitVarInsn(ILOAD, countVar);
      pushInt(mv, info.maxQuantifier);
      mv.visitJumpInsn(IF_ICMPGE, innerLoopEnd);
    }

    // int iterStart = pos;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, iterStartVar);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // Check if character matches
    generateCharCheck(mv, innerLoopEnd, allocator);

    // Match! pos++; count++;
    mv.visitIincInsn(posVar, 1);
    mv.visitIincInsn(countVar, 1);

    // lastIterStart = iterStart; lastIterEnd = pos;
    mv.visitVarInsn(ILOAD, iterStartVar);
    mv.visitVarInsn(ISTORE, lastIterStartVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, lastIterEndVar);

    mv.visitJumpInsn(GOTO, innerLoop);

    mv.visitLabel(innerLoopEnd);

    // Check minimum constraint
    mv.visitVarInsn(ILOAD, countVar);
    pushInt(mv, info.minQuantifier);
    mv.visitJumpInsn(IF_ICMPLT, tryNextPos);

    // Success! Build MatchResult
    int startsVar = allocator.allocate();
    int endsVar = allocator.allocate();

    // int[] starts = new int[]{matchStart, lastIterStart};
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, lastIterStartVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, startsVar);

    // int[] ends = new int[]{pos, lastIterEnd};
    pushInt(mv, 2);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, lastIterEndVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ASTORE, endsVar);

    // return new MatchResultImpl(input, starts, ends, 1);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitVarInsn(ALOAD, endsVar);
    pushInt(mv, 1);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);

    // Try next position
    mv.visitLabel(tryNextPos);
    mv.visitIincInsn(startPosVar, 1); // startPos++
    mv.visitJumpInsn(GOTO, outerLoop);

    // Not found
    mv.visitLabel(notFound);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate findFrom() method - returns int position of match. */
  public void generateFindFromMethod(ClassWriter cw) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // MatchResult r = this.findMatchFrom(input, start);
    mv.visitVarInsn(ALOAD, 0); // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 1); // S: [...] -> [..., A:String]
    mv.visitVarInsn(ILOAD, 2); // S: [...] -> [..., I]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false); // S: [...] -> [A:MR]

    // if (r == null) return -1;
    mv.visitInsn(DUP); // S: [A:MR] -> [A:MR, A:MR]
    Label notNull = new Label();
    mv.visitJumpInsn(IFNONNULL, notNull); // S: [...] -> [A:MR]
    mv.visitInsn(POP); // S: [A:MR] -> []
    pushInt(mv, -1); // S: [] -> [I]
    mv.visitInsn(IRETURN); // S: [I] -> []

    // return r.start();
    mv.visitLabel(notNull);
    mv.visitMethodInsn(
        INVOKEINTERFACE,
        "com/datadoghq/reggie/runtime/MatchResult",
        "start",
        "()I",
        true); // S: [A:MR] -> [I]
    mv.visitInsn(IRETURN); // S: [I] -> []

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
    mv.visitVarInsn(ALOAD, 0); // S: [] -> [A:this]
    mv.visitVarInsn(ALOAD, 1); // S: [...] -> [..., A:String]
    pushInt(mv, 0); // S: [...] -> [..., I]
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false); // S: [...] -> [A:MR]
    mv.visitInsn(ARETURN); // S: [A:MR] -> []

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchesBounded() method - checks if substring matches. */
  public void generateMatchesBoundedMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, 1 = input, 2 = start, 3 = end
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    int sVar = allocator.allocate();

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
    mv.visitVarInsn(ASTORE, sVar);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, sVar);
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

    // Slot 0 = this, 1 = input, 2 = start, 3 = end
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int inputVar = 1;
    int startVar = 2;
    int endVar = 3;
    int sVar = allocator.allocate();

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
    mv.visitVarInsn(ASTORE, sVar);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, sVar);
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
