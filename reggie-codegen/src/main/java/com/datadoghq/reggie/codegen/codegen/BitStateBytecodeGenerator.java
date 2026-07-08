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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.PrefixGuardedScanInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates pure straight-line, always-native bytecode for the "prefix-guarded scan" structural
 * family recognized by {@code PatternAnalyzer.detectPrefixGuardedScan} (see
 * doc/2026-07-08-bitstate-bytecode-generator-design.md), industrializing the hand-written reference
 * at {@code reggie-benchmark/.../CommandPatternPrototype.java}: {@code
 * ^(?:leadingWs(?:keyword1|keyword2|...)separatorWs)?\b?mandatoryCharSet+\b?trailingWs*(tailCharSet*)}.
 *
 * <p>The single optional prefix is resolved by two independent straight-line attempts (with the
 * prefix, then without) selected by one {@code if} — no job-stack, no visited bitmap, no undo-log.
 *
 * <p>The mandatory scan's greedy stopping point is corrected for a required trailing {@code \b}
 * with a single bounded backward give-back scan, not general backtracking. {@code
 * CommandPatternPrototype}'s comment claiming the trailing {@code \b} after {@code \S+} is always
 * implied by "non-space followed by space/EOF" does not hold for every {@code mandatoryCharSet}:
 * e.g. input {@code "a!"} against {@code \b\S+\b}, the maximal {@code \S+} match ends on the
 * non-word character {@code '!'} followed by end-of-input — not a word-boundary transition — so
 * real {@code java.util.regex} backtracks {@code \S+} by one character to land on a boundary. This
 * generator reproduces that via the bounded give-back scan in {@code matchMandatory} below, rather
 * than the prototype's shortcut (which would silently produce a wrong capture-group span for such
 * inputs).
 */
public class BitStateBytecodeGenerator {

  private final PrefixGuardedScanInfo info;
  private final int groupCount;

  public BitStateBytecodeGenerator(PrefixGuardedScanInfo info, int groupCount) {
    this.info = info;
    this.groupCount = groupCount;
  }

  private static String cn(String className) {
    return className.replace('.', '/');
  }

  /** Registers all helper + public entry-point methods on {@code cw}. */
  public void generateAll(ClassWriter cw, String className) {
    generateIsWordCharMethod(cw);
    generateIsBoundaryMethod(cw, className);
    if (info.hasOptionalPrefix()) {
      generateSkipLeadingWsMethod(cw);
      generateMatchKeywordMethod(cw);
      generateSkipSeparatorMethod(cw);
    }
    generateMatchMandatoryMethod(cw, className);
    generateTryMatchAtMethod(cw, className);

    generateMatchesMethod(cw, className);
    generateFindMethod(cw, className);
    generateFindFromMethod(cw, className);
    generateMatchMethod(cw, className);
    generateMatchesBoundedMethod(cw, className);
    generateMatchBoundedMethod(cw, className);
    generateFindMatchMethod(cw, className);
    generateFindMatchFromMethod(cw, className);
    generateFindBoundsFromMethod(cw, className);
  }

  // ---------------------------------------------------------------------------------------
  // Charset check emission (mirrors GreedyCharClassBytecodeGenerator.generateInlineCharSetCheck,
  // parameterized by an explicit CharSet argument since this generator handles several distinct
  // charsets in one class instead of one fixed instance field). PrefixGuardedScanInfo's charsets
  // are already negation-resolved (PatternAnalyzer.effectiveCharSet), so this never needs a
  // negated variant.
  // ---------------------------------------------------------------------------------------

  /** Emits: {@code if (charVar not in charSet) goto exitLabel;} */
  private static void emitCharSetCheck(
      MethodVisitor mv, int charVar, Label exitLabel, CharSet charSet) {
    if (charSet.isSingleChar()) {
      char c = charSet.getSingleChar();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, c);
      mv.visitJumpInsn(IF_ICMPNE, exitLabel);
    } else if (charSet.isSimpleRange()) {
      CharSet.Range range = charSet.getSimpleRange();
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, range.start);
      mv.visitJumpInsn(IF_ICMPLT, exitLabel);
      mv.visitVarInsn(ILOAD, charVar);
      pushInt(mv, range.end);
      mv.visitJumpInsn(IF_ICMPGT, exitLabel);
    } else {
      Label matches = new Label();
      for (CharSet.Range range : charSet.getRanges()) {
        Label tryNext = new Label();
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, range.start);
        mv.visitJumpInsn(IF_ICMPLT, tryNext);
        mv.visitVarInsn(ILOAD, charVar);
        pushInt(mv, range.end);
        mv.visitJumpInsn(IF_ICMPLE, matches);
        mv.visitLabel(tryNext);
      }
      mv.visitJumpInsn(GOTO, exitLabel);
      mv.visitLabel(matches);
    }
  }

  /**
   * Emits a greedy "while (p < n && charAt(p) in charSet) p++;" loop, leaving the final p in pVar.
   */
  private static void emitGreedyScanLoop(
      MethodVisitor mv, int inputVar, int pVar, int nVar, int cVar, CharSet charSet) {
    Label loopStart = new Label();
    Label loopEnd = new Label();
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);
    emitCharSetCheck(mv, cVar, loopEnd, charSet);
    mv.visitIincInsn(pVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);
  }

  // ---------------------------------------------------------------------------------------
  // Helper methods, generated once per class, shared by tryMatchAt/matchMandatory and the public
  // entry points. All are private static: the generated class has no runtime instance state (all
  // pattern-specific data — charsets, keyword literals, boundary requirements — is baked directly
  // into instructions at generation time).
  // ---------------------------------------------------------------------------------------

  /** {@code private static boolean isWordChar(char c)} — the fixed PCRE/Java \b word-char set. */
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
   * {@code private static boolean isBoundary(String input, int p, int n)} — true iff exactly one of
   * {@code (p>0 && isWordChar(input.charAt(p-1)))} / {@code (p<n && isWordChar(input.charAt(p)))}
   * holds (mirrors {@code CommandPatternPrototype.isWordBoundary} exactly).
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
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "isWordChar", "(C)Z", false);
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
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "isWordChar", "(C)Z", false);
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

  /**
   * {@code private static int skipLeadingWs(String input, int p, int n)} — greedy {@code
   * leadingWsCharSet*} scan, returns the position after the scan.
   */
  private void generateSkipLeadingWsMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC, "skipLeadingWs", "(Ljava/lang/String;II)I", null, null);
    mv.visitCode();
    int inputVar = 0;
    int pVar = 1;
    int nVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int cVar = allocator.allocate();

    emitGreedyScanLoop(mv, inputVar, pVar, nVar, cVar, info.leadingWsCharSet);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static int matchKeyword(String input, int p, int n)} — first-char-dispatched
   * literal comparisons per keyword (mirrors {@code CommandPatternPrototype.matchKeyword}, but
   * data-driven from {@code info.keywords}), returns {@code p + keyword.length()} on a match, or
   * {@code -1}.
   */
  private void generateMatchKeywordMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC, "matchKeyword", "(Ljava/lang/String;II)I", null, null);
    mv.visitCode();
    int inputVar = 0;
    int pVar = 1;
    int nVar = 2;

    for (String keyword : info.keywords) {
      Label tryNext = new Label();
      int len = keyword.length();

      // if (p + len > n) goto tryNext;
      mv.visitVarInsn(ILOAD, pVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitJumpInsn(IF_ICMPGT, tryNext);

      for (int i = 0; i < len; i++) {
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, pVar);
        pushInt(mv, i);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        pushInt(mv, keyword.charAt(i));
        mv.visitJumpInsn(IF_ICMPNE, tryNext);
      }

      // success: return p + len;
      mv.visitVarInsn(ILOAD, pVar);
      pushInt(mv, len);
      mv.visitInsn(IADD);
      mv.visitInsn(IRETURN);

      mv.visitLabel(tryNext);
    }

    pushInt(mv, -1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static int skipSeparator(String input, int p, int n)} — greedy {@code
   * separatorCharSet{separatorMin,}} scan; returns the position after the scan, or {@code -1} if
   * fewer than {@code separatorMin} characters matched.
   */
  private void generateSkipSeparatorMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC, "skipSeparator", "(Ljava/lang/String;II)I", null, null);
    mv.visitCode();
    int inputVar = 0;
    int pVar = 1;
    int nVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int startVar = allocator.allocate();
    int cVar = allocator.allocate();

    // int start = p;
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ISTORE, startVar);

    emitGreedyScanLoop(mv, inputVar, pVar, nVar, cVar, info.separatorCharSet);

    // if (p - start < separatorMin) return -1;
    Label minOk = new Label();
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(ISUB);
    pushInt(mv, info.separatorMin);
    mv.visitJumpInsn(IF_ICMPGE, minOk);
    pushInt(mv, -1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(minOk);

    mv.visitVarInsn(ILOAD, pVar);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static long matchMandatory(String input, int p, int n)} — matches {@code
   * \b?mandatoryCharSet+\b?trailingWs*(tailCharSet*)} starting at {@code p}. Returns the tail
   * group's span packed as {@code (tailStart << 32) | tailEnd}, or {@code -1L} on failure.
   *
   * <p>Unlike {@code CommandPatternPrototype.matchMandatory}, this performs a bounded backward
   * give-back scan when {@code trailingWordBoundary} is required and the maximal greedy match does
   * not land on a boundary (see class javadoc for why this is necessary for correctness).
   */
  private void generateMatchMandatoryMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC, "matchMandatory", "(Ljava/lang/String;II)J", null, null);
    mv.visitCode();
    int inputVar = 0;
    int pVar = 1;
    int nVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int qVar = allocator.allocate();
    int cVar = allocator.allocate();
    int minEndVar = allocator.allocate(); // p + mandatoryMin, the give-back floor
    int rVar = allocator.allocate();

    Label fail = new Label();

    // if (leadingWordBoundary && !isBoundary(input, p, n)) return -1L;
    if (info.leadingWordBoundary) {
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, pVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "isBoundary", "(Ljava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFEQ, fail);
    }

    // int q = p;
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ISTORE, qVar);

    emitGreedyScanLoop(mv, inputVar, qVar, nVar, cVar, info.mandatoryCharSet);

    // int minEnd = p + mandatoryMin;
    mv.visitVarInsn(ILOAD, pVar);
    pushInt(mv, info.mandatoryMin);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, minEndVar);

    // if (q < minEnd) return -1L;  (fewer than mandatoryMin chars matched)
    mv.visitVarInsn(ILOAD, qVar);
    mv.visitVarInsn(ILOAD, minEndVar);
    mv.visitJumpInsn(IF_ICMPLT, fail);

    if (info.trailingWordBoundary) {
      // while (q > minEnd && !isBoundary(input, q, n)) q--;
      Label backLoop = new Label();
      Label backEnd = new Label();
      mv.visitLabel(backLoop);
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ILOAD, minEndVar);
      mv.visitJumpInsn(IF_ICMPLE, backEnd);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "isBoundary", "(Ljava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFNE, backEnd);
      mv.visitIincInsn(qVar, -1);
      mv.visitJumpInsn(GOTO, backLoop);
      mv.visitLabel(backEnd);

      // if (!isBoundary(input, q, n)) return -1L;
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "isBoundary", "(Ljava/lang/String;II)Z", false);
      mv.visitJumpInsn(IFEQ, fail);
    }

    // int r = q;
    mv.visitVarInsn(ILOAD, qVar);
    mv.visitVarInsn(ISTORE, rVar);

    if (info.trailingCharSet != null) {
      emitGreedyScanLoop(mv, inputVar, rVar, nVar, cVar, info.trailingCharSet);
    }

    // Tail scan: greedily consume tailCharSet from r; tailStart = r (before the scan).
    int tailStartVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, rVar);
    mv.visitVarInsn(ISTORE, tailStartVar);
    emitGreedyScanLoop(mv, inputVar, rVar, nVar, cVar, info.tailCharSet);

    // return ((long) tailStart << 32) | (long) tailEnd;   (tailEnd is now in rVar)
    mv.visitVarInsn(ILOAD, tailStartVar);
    mv.visitInsn(I2L);
    pushInt(mv, 32);
    mv.visitInsn(LSHL);
    mv.visitVarInsn(ILOAD, rVar);
    mv.visitInsn(I2L);
    mv.visitInsn(LOR);
    mv.visitInsn(LRETURN);

    mv.visitLabel(fail);
    pushInt(mv, -1);
    mv.visitInsn(I2L);
    mv.visitInsn(LRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code private static long tryMatchAt(String input, int start, int n)} — the single choice
   * point: attempt the optional prefix then the mandatory scan; on failure, retry the mandatory
   * scan alone. Two independent straight-line paths, no recursion (mirrors {@code
   * CommandPatternPrototype.tryMatchAt}).
   */
  private void generateTryMatchAtMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PRIVATE | ACC_STATIC, "tryMatchAt", "(Ljava/lang/String;II)J", null, null);
    mv.visitCode();
    int inputVar = 0;
    int startVar = 1;
    int nVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    if (info.hasOptionalPrefix()) {
      int qVar = allocator.allocate();
      int afterKeywordVar = allocator.allocate();
      int rVar = allocator.allocate();
      int resVar = allocator.allocateWide();

      Label fallback = new Label();

      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "skipLeadingWs", "(Ljava/lang/String;II)I", false);
      mv.visitVarInsn(ISTORE, qVar);

      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, qVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "matchKeyword", "(Ljava/lang/String;II)I", false);
      mv.visitVarInsn(ISTORE, afterKeywordVar);

      mv.visitVarInsn(ILOAD, afterKeywordVar);
      mv.visitJumpInsn(IFLT, fallback);

      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, afterKeywordVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "skipSeparator", "(Ljava/lang/String;II)I", false);
      mv.visitVarInsn(ISTORE, rVar);

      mv.visitVarInsn(ILOAD, rVar);
      mv.visitJumpInsn(IFLT, fallback);

      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, rVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "matchMandatory", "(Ljava/lang/String;II)J", false);
      mv.visitVarInsn(LSTORE, resVar);

      // if (res != -1L) return res;
      mv.visitVarInsn(LLOAD, resVar);
      pushInt(mv, -1);
      mv.visitInsn(I2L);
      mv.visitInsn(LCMP);
      mv.visitJumpInsn(IFEQ, fallback);
      mv.visitVarInsn(LLOAD, resVar);
      mv.visitInsn(LRETURN);

      mv.visitLabel(fallback);
    }

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitMethodInsn(
        INVOKESTATIC, cn(className), "matchMandatory", "(Ljava/lang/String;II)J", false);
    mv.visitInsn(LRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // ---------------------------------------------------------------------------------------
  // Public entry points (mirror GreedyCharClassBytecodeGenerator's method set: matches, find,
  // findFrom, match, matchesBounded, matchBounded, findMatch, findMatchFrom, findBoundsFrom).
  // ---------------------------------------------------------------------------------------

  /** {@code public boolean matches(String input)} — full-string, position-0-anchored match. */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, nVar);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "tryMatchAt", "(Ljava/lang/String;II)J", false);
    mv.visitVarInsn(LSTORE, resVar);

    // if (res == -1L) return false;
    Label ok = new Label();
    mv.visitVarInsn(LLOAD, resVar);
    pushInt(mv, -1);
    mv.visitInsn(I2L);
    mv.visitInsn(LCMP);
    mv.visitJumpInsn(IFNE, ok);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(ok);

    // return ((int) res) == n;  (tailEnd must reach the end of input for matches())
    mv.visitVarInsn(LLOAD, resVar);
    mv.visitInsn(L2I);
    mv.visitVarInsn(ILOAD, nVar);
    Label eq = new Label();
    mv.visitJumpInsn(IF_ICMPEQ, eq);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(eq);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public boolean find(String input)} — delegates to {@code findFrom(input, 0) >= 0}. */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(INVOKEVIRTUAL, cn(className), "findFrom", "(Ljava/lang/String;I)I", false);

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
   * {@code public int findFrom(String input, int start)} — returns the position of the first anchor
   * point (line start under {@code multiline}, else only position 0) at or after {@code start}
   * where {@code tryMatchAt} succeeds, or {@code -1}.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();
    int inputVar = 1;
    int startVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int nVar = allocator.allocate();

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

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, nVar);

    if (!info.multiline) {
      // Only position 0 is ever a valid ^ anchor point without MULTILINE.
      Label tryZero = new Label();
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitJumpInsn(IFLE, tryZero);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(tryZero);

      int resVar = allocator.allocateWide();
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "tryMatchAt", "(Ljava/lang/String;II)J", false);
      mv.visitVarInsn(LSTORE, resVar);

      Label fail = new Label();
      mv.visitVarInsn(LLOAD, resVar);
      pushInt(mv, -1);
      mv.visitInsn(I2L);
      mv.visitInsn(LCMP);
      mv.visitJumpInsn(IFEQ, fail);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(fail);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    } else {
      int lineStartVar = allocator.allocate();
      int resVar = allocator.allocateWide();
      int nlVar = allocator.allocate();

      // int lineStart;
      // if (start <= 0) lineStart = 0;
      // else if (input.charAt(start - 1) == '\n') lineStart = start;
      // else { nl = input.indexOf('\n', start); if (nl < 0) return -1; lineStart = nl + 1; }
      Label startLeZero = new Label();
      Label afterNewline = new Label();
      Label searchNewline = new Label();
      Label lineStartSet = new Label();

      mv.visitVarInsn(ILOAD, startVar);
      mv.visitJumpInsn(IFLE, startLeZero);

      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, startVar);
      pushInt(mv, 1);
      mv.visitInsn(ISUB);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPNE, searchNewline);

      mv.visitJumpInsn(GOTO, afterNewline);

      mv.visitLabel(searchNewline);
      mv.visitVarInsn(ALOAD, inputVar);
      pushInt(mv, '\n');
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
      mv.visitVarInsn(ISTORE, nlVar);
      Label nlFound = new Label();
      mv.visitVarInsn(ILOAD, nlVar);
      mv.visitJumpInsn(IFGE, nlFound);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(nlFound);
      mv.visitVarInsn(ILOAD, nlVar);
      pushInt(mv, 1);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ISTORE, lineStartVar);
      mv.visitJumpInsn(GOTO, lineStartSet);

      mv.visitLabel(afterNewline);
      mv.visitVarInsn(ILOAD, startVar);
      mv.visitVarInsn(ISTORE, lineStartVar);
      mv.visitJumpInsn(GOTO, lineStartSet);

      mv.visitLabel(startLeZero);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, lineStartVar);

      mv.visitLabel(lineStartSet);

      // while (lineStart <= n) { try; advance past next '\n' or break; }
      Label loop = new Label();
      Label loopEnd = new Label();
      mv.visitLabel(loop);
      mv.visitVarInsn(ILOAD, lineStartVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitJumpInsn(IF_ICMPGT, loopEnd);

      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, lineStartVar);
      mv.visitVarInsn(ILOAD, nVar);
      mv.visitMethodInsn(
          INVOKESTATIC, cn(className), "tryMatchAt", "(Ljava/lang/String;II)J", false);
      mv.visitVarInsn(LSTORE, resVar);

      Label noMatch = new Label();
      mv.visitVarInsn(LLOAD, resVar);
      pushInt(mv, -1);
      mv.visitInsn(I2L);
      mv.visitInsn(LCMP);
      mv.visitJumpInsn(IFEQ, noMatch);
      mv.visitVarInsn(ILOAD, lineStartVar);
      mv.visitInsn(IRETURN);
      mv.visitLabel(noMatch);

      mv.visitVarInsn(ALOAD, inputVar);
      pushInt(mv, '\n');
      mv.visitVarInsn(ILOAD, lineStartVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
      mv.visitVarInsn(ISTORE, nlVar);
      mv.visitVarInsn(ILOAD, nlVar);
      mv.visitJumpInsn(IFLT, loopEnd);
      mv.visitVarInsn(ILOAD, nlVar);
      pushInt(mv, 1);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ISTORE, lineStartVar);
      mv.visitJumpInsn(GOTO, loop);

      mv.visitLabel(loopEnd);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Emits: {@code long res = tryMatchAt(input, matchStart, n);} and unpacks tailStart/tailEnd. */
  private void emitTryMatchAndUnpack(
      MethodVisitor mv,
      String className,
      int inputVar,
      int matchStartVar,
      int nVar,
      int resVar,
      int tailStartVar,
      int tailEndVar) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitMethodInsn(INVOKESTATIC, cn(className), "tryMatchAt", "(Ljava/lang/String;II)J", false);
    mv.visitVarInsn(LSTORE, resVar);

    // tailStart = (int) (res >>> 32); tailEnd = (int) res;
    mv.visitVarInsn(LLOAD, resVar);
    pushInt(mv, 32);
    mv.visitInsn(LUSHR);
    mv.visitInsn(L2I);
    mv.visitVarInsn(ISTORE, tailStartVar);
    mv.visitVarInsn(LLOAD, resVar);
    mv.visitInsn(L2I);
    mv.visitVarInsn(ISTORE, tailEndVar);
  }

  /**
   * Emits construction of a {@code MatchResultImpl} for a 1-capturing-group result and returns it.
   */
  private void emitBuildAndReturnMatchResult(
      MethodVisitor mv, int inputVar, int startVar, int tailStartVar, int endVar) {
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);

    // starts: {start, ..., tailStart} at index tailGroupNumber
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, info.tailGroupNumber);
    mv.visitVarInsn(ILOAD, tailStartVar);
    mv.visitInsn(IASTORE);

    // ends: {end, ..., end} — group 0 and the tail group share the same end position.
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitInsn(IASTORE);
    mv.visitInsn(DUP);
    pushInt(mv, info.tailGroupNumber);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitInsn(IASTORE);

    pushInt(mv, groupCount);

    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);
  }

  /** {@code public MatchResult match(String input)} — full-string, position-0-anchored match. */
  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    int tailStartVar = allocator.allocate();
    int tailEndVar = allocator.allocate();
    int startVar = allocator.allocate();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, nVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, startVar);

    emitTryMatchAndUnpack(
        mv, className, inputVar, startVar, nVar, resVar, tailStartVar, tailEndVar);

    // if (res == -1L || tailEnd != n) return null;
    Label ok = new Label();
    mv.visitVarInsn(LLOAD, resVar);
    pushInt(mv, -1);
    mv.visitInsn(I2L);
    mv.visitInsn(LCMP);
    mv.visitJumpInsn(IFNE, ok);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(ok);

    Label full = new Label();
    mv.visitVarInsn(ILOAD, tailEndVar);
    mv.visitVarInsn(ILOAD, nVar);
    mv.visitJumpInsn(IF_ICMPEQ, full);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(full);

    emitBuildAndReturnMatchResult(mv, inputVar, startVar, tailStartVar, tailEndVar);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public boolean matchesBounded(CharSequence, int, int)} — delegates to matches(). */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();
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
    mv.visitMethodInsn(INVOKEVIRTUAL, cn(className), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public MatchResult matchBounded(CharSequence, int, int)} — delegates to match(). */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
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
        cn(className),
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** {@code public MatchResult findMatch(String input)} — delegates to findMatchFrom(input, 0). */
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
        cn(className),
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code public MatchResult findMatchFrom(String input, int start)} — locates the next line start
   * via {@code findFrom} and re-runs {@code tryMatchAt} there to recover the tail span.
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
    int inputVar = 1;
    int startVar = 2;
    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int matchStartVar = allocator.allocate();
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    int tailStartVar = allocator.allocate();
    int tailEndVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, cn(className), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    Label found = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(found);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, nVar);

    emitTryMatchAndUnpack(
        mv, className, inputVar, matchStartVar, nVar, resVar, tailStartVar, tailEndVar);

    emitBuildAndReturnMatchResult(mv, inputVar, matchStartVar, tailStartVar, tailEndVar);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * {@code public boolean findBoundsFrom(String input, int start, int[] bounds)} — allocation-free
   * alternative to {@code findMatchFrom}; only fills group 0's span, matching {@code
   * GreedyCharClassBytecodeGenerator}'s contract.
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();
    int inputVar = 1;
    int startVar = 2;
    int boundsVar = 3;
    LocalVarAllocator allocator = new LocalVarAllocator(4);
    int matchStartVar = allocator.allocate();
    int nVar = allocator.allocate();
    int resVar = allocator.allocateWide();
    int tailStartVar = allocator.allocate();
    int tailEndVar = allocator.allocate();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, cn(className), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    Label found = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, nVar);

    emitTryMatchAndUnpack(
        mv, className, inputVar, matchStartVar, nVar, resVar, tailStartVar, tailEndVar);

    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);
    mv.visitVarInsn(ALOAD, boundsVar);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, tailEndVar);
    mv.visitInsn(IASTORE);

    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
