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

import com.datadoghq.reggie.codegen.analysis.OptionalGroupBackrefInfo;
import com.datadoghq.reggie.codegen.analysis.OptionalGroupBackrefInfo.BackrefEntry;
import com.datadoghq.reggie.codegen.analysis.OptionalGroupBackrefInfo.OptionalGroupEntry;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import java.util.HashMap;
import java.util.Map;

import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt;
import static org.objectweb.asm.Opcodes.*;

/**
 * Bytecode generator for OPTIONAL_GROUP_BACKREF strategy.
 *
 * Generates optimized matching code for patterns where optional capturing
 * groups are followed by backreferences to those groups.
 *
 * <h3>Supported Pattern Examples</h3>
 * <ul>
 *   <li>{@code (a)?\1} - optional 'a', then backref</li>
 *   <li>{@code (foo)?bar\1} - optional group, literal, backref</li>
 *   <li>{@code ^(a)?(b)?\1\2$} - multiple optional groups with backrefs</li>
 * </ul>
 *
 * <h3>PCRE Semantics</h3>
 * <ul>
 *   <li>If optional group matched: backref must match the captured content</li>
 *   <li>If optional group didn't match: backref matches empty string</li>
 * </ul>
 *
 * <h3>Generated Algorithm</h3>
 * <pre>{@code
 * boolean matches(String input) {
 *     int len = input.length();
 *     int pos = 0;
 *
 *     // Try to match optional group
 *     boolean group1Matched = false;
 *     int group1Start = -1, group1End = -1;
 *     if (pos < len && input.charAt(pos) == 'a') {
 *         group1Matched = true;
 *         group1Start = pos;
 *         pos++;
 *         group1End = pos;
 *     }
 *
 *     // Match backref
 *     if (group1Matched) {
 *         // Must match captured content
 *         int groupLen = group1End - group1Start;
 *         if (pos + groupLen > len) return false;
 *         if (!input.regionMatches(pos, input, group1Start, groupLen)) return false;
 *         pos += groupLen;
 *     }
 *     // else: group didn't match, backref matches empty (do nothing)
 *
 *     return pos == len;
 * }
 * }</pre>
 */
public class OptionalGroupBackrefBytecodeGenerator {

    private final OptionalGroupBackrefInfo info;
    private final String className;

    public OptionalGroupBackrefBytecodeGenerator(OptionalGroupBackrefInfo info, String className) {
        this.info = info;
        this.className = className;
    }

    /**
     * Generate all required methods for ReggieMatcher.
     */
    public void generate(ClassWriter cw) {
        generateMatchesMethod(cw);
        generateFindMethod(cw);
        generateFindFromMethod(cw);
        generateMatchMethod(cw);
        generateFindMatchMethod(cw);
        // Generate stub methods for bounded variants (less commonly used)
        generateMatchesBoundedStubMethod(cw);
        generateMatchBoundedStubMethod(cw);
        generateFindMatchFromStubMethod(cw);
    }

    /**
     * Generate match(String) that returns MatchResult with group captures.
     *
     * Pseudocode:
     * {@code
     * MatchResult match(String input) {
     *     if (input == null) return null;
     *     int len = input.length();
     *     int pos = 0;
     *     int[] starts = new int[groupCount + 1];
     *     int[] ends = new int[groupCount + 1];
     *     Arrays.fill(starts, -1);
     *     Arrays.fill(ends, -1);
     *
     *     // Match optional groups, tracking positions
     *     for each group: if char matches, set starts[g]=pos, advance, set ends[g]=pos
     *
     *     // Match quantified backrefs
     *     for each backref: if group captured, verify N repetitions
     *
     *     // Match suffix
     *     if (suffixChar >= 0) { check and advance }
     *
     *     if (pos != len) return null;
     *     starts[0] = 0; ends[0] = pos;
     *     return new MatchResultImpl(input, starts, ends, groupCount);
     * }
     * }
     */
    private void generateMatchMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;", null, null);
        mv.visitCode();

        // Local vars: 0=this, 1=input
        LocalVarAllocator alloc = new LocalVarAllocator(2);
        int lenVar = alloc.allocate();
        int posVar = alloc.allocate();
        int startsVar = alloc.allocate();
        int endsVar = alloc.allocate();
        int countVar = alloc.allocate(); // for backref loop
        int grpLenVar = alloc.allocate(); // for backref length

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

        // int pos = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, posVar);

        // int[] starts = new int[groupCount + 1];
        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, startsVar);

        // int[] ends = new int[groupCount + 1];
        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, endsVar);

        // Initialize all to -1
        for (int i = 0; i <= info.totalGroupCount; i++) {
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, i);
            mv.visitInsn(ICONST_M1);
            mv.visitInsn(IASTORE);

            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, i);
            mv.visitInsn(ICONST_M1);
            mv.visitInsn(IASTORE);
        }

        // Match optional groups - for (X|) patterns, always capture (either content or empty)
        for (OptionalGroupEntry entry : info.optionalGroups) {
            if (entry.isSingleChar && entry.literalChar >= 0) {
                Label charMatched = new Label();
                Label charNotMatched = new Label();
                Label groupEnd = new Label();

                // Check if char matches
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGE, charNotMatched);

                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                pushInt(mv, entry.literalChar);
                mv.visitJumpInsn(IF_ICMPEQ, charMatched);

                // Char not matched - capture empty (starts = pos, ends = pos)
                mv.visitLabel(charNotMatched);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);
                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);
                mv.visitJumpInsn(GOTO, groupEnd);

                // Char matched - capture it
                mv.visitLabel(charMatched);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);
                mv.visitIincInsn(posVar, 1);
                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitLabel(groupEnd);
            } else if (entry.literalString != null) {
                // Multi-char literal string (e.g., "cow" in (cow|))
                Label stringMatched = new Label();
                Label stringNotMatched = new Label();
                Label groupEnd = new Label();

                int strLen = entry.literalString.length();

                // Check if enough characters remain: pos + strLen <= len
                mv.visitVarInsn(ILOAD, posVar);
                pushInt(mv, strLen);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGT, stringNotMatched);

                // Use String.regionMatches(int, String, int, int)
                // input.regionMatches(pos, literalString, 0, strLen)
                mv.visitVarInsn(ALOAD, 1);  // input string
                mv.visitVarInsn(ILOAD, posVar);  // toffset
                mv.visitLdcInsn(entry.literalString);  // other string
                mv.visitInsn(ICONST_0);  // ooffset
                pushInt(mv, strLen);  // len
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
                mv.visitJumpInsn(IFNE, stringMatched);  // IFNE = if not equal to 0 (i.e., true)

                // String not matched - capture empty (starts = pos, ends = pos)
                mv.visitLabel(stringNotMatched);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);
                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);
                mv.visitJumpInsn(GOTO, groupEnd);

                // String matched - capture it
                mv.visitLabel(stringMatched);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);
                mv.visitIincInsn(posVar, strLen);  // advance by string length
                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitLabel(groupEnd);
            }
        }

        // Match quantified backrefs
        for (BackrefEntry entry : info.backrefEntries) {
            Label backrefEnd = new Label();
            Label groupNotMatched = new Label();

            // if (starts[groupNum] < 0) skip backref check (shouldn't happen for (X|) groups)
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitJumpInsn(IFLT, groupNotMatched);

            // grpLen = ends[groupNum] - starts[groupNum]
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitInsn(ISUB);
            mv.visitVarInsn(ISTORE, grpLenVar);

            // If grpLen == 0, backref matches empty (skip loop)
            // Empty repeated any number of times is still empty
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitJumpInsn(IFEQ, backrefEnd);

            // count = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, countVar);

            Label loopStart = new Label();
            Label loopEnd = new Label();

            mv.visitLabel(loopStart);

            // if (count >= max) break (skip check if max == -1, meaning unlimited)
            if (entry.maxCount >= 0) {
                mv.visitVarInsn(ILOAD, countVar);
                pushInt(mv, entry.maxCount);
                mv.visitJumpInsn(IF_ICMPGE, loopEnd);
            }

            // if (pos + grpLen > len) break
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, loopEnd);

            // if (!regionMatches) break
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches",
                "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, loopEnd);

            // pos += grpLen; count++
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);
            mv.visitIincInsn(countVar, 1);
            mv.visitJumpInsn(GOTO, loopStart);

            mv.visitLabel(loopEnd);

            // if (count < min) return null
            mv.visitVarInsn(ILOAD, countVar);
            pushInt(mv, entry.minCount);
            mv.visitJumpInsn(IF_ICMPLT, returnNull);

            mv.visitJumpInsn(GOTO, backrefEnd);

            mv.visitLabel(groupNotMatched);
            // Group captured empty - backref satisfied

            mv.visitLabel(backrefEnd);
        }

        // Match suffix literal or suffix group
        if (info.suffixLiteralChar >= 0) {
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGE, returnNull);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, info.suffixLiteralChar);
            mv.visitJumpInsn(IF_ICMPNE, returnNull);

            mv.visitIincInsn(posVar, 1);
        } else if (info.suffixGroupLiteral != null) {
            // Match suffix capturing group with literal content (e.g., "(bell)" in "^(cow|)\1(bell)")
            int suffixLen = info.suffixGroupLiteral.length();

            // Check bounds: pos + suffixLen <= len
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, returnNull);

            // Compare using regionMatches: input.regionMatches(pos, suffixLiteral, 0, suffixLen)
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitLdcInsn(info.suffixGroupLiteral);
            mv.visitInsn(ICONST_0);
            pushInt(mv, suffixLen);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, returnNull);

            // Capture suffix group: starts[suffixGroupNumber] = pos
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, info.suffixGroupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);

            // Advance pos by suffixLen
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);

            // Capture suffix group end: ends[suffixGroupNumber] = pos (after advance)
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, info.suffixGroupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
        }

        // Check pos == len for full match
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPNE, returnNull);

        // Set group 0: starts[0] = 0, ends[0] = pos
        mv.visitVarInsn(ALOAD, startsVar);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IASTORE);

        mv.visitVarInsn(ALOAD, endsVar);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitInsn(IASTORE);

        // Return new MatchResultImpl(input, starts, ends, groupCount)
        mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitVarInsn(ALOAD, startsVar);
        mv.visitVarInsn(ALOAD, endsVar);
        pushInt(mv, info.totalGroupCount);
        mv.visitMethodInsn(INVOKESPECIAL, "com/datadoghq/reggie/runtime/MatchResultImpl", "<init>",
            "(Ljava/lang/String;[I[II)V", false);
        mv.visitInsn(ARETURN);

        mv.visitLabel(returnNull);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(6, alloc.peek());
        mv.visitEnd();
    }

    /**
     * Generate stub for matchesBounded(CharSequence, int, int).
     */
    private void generateMatchesBoundedStubMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matchesBounded",
            "(Ljava/lang/CharSequence;II)Z", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("matchesBounded() not yet implemented for OPTIONAL_GROUP_BACKREF strategy");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(3, 4);
        mv.visitEnd();
    }

    /**
     * Generate stub for matchBounded(CharSequence, int, int).
     */
    private void generateMatchBoundedStubMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("matchBounded() not yet implemented for OPTIONAL_GROUP_BACKREF strategy");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(3, 4);
        mv.visitEnd();
    }

    /**
     * Generate findMatch(String) that finds a match and returns MatchResult.
     *
     * For patterns with start anchor, this is equivalent to match().
     * For patterns without start anchor, tries each starting position.
     */
    private void generateFindMatchMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;", null, null);
        mv.visitCode();

        // Local vars: 0=this, 1=input
        LocalVarAllocator alloc = new LocalVarAllocator(2);
        int lenVar = alloc.allocate();
        int startVar = alloc.allocate();
        int posVar = alloc.allocate();
        int startsVar = alloc.allocate();
        int endsVar = alloc.allocate();
        int countVar = alloc.allocate();
        int grpLenVar = alloc.allocate();

        Label returnNull = new Label();

        // Null check
        Label notNull = new Label();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitJumpInsn(IFNONNULL, notNull);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        mv.visitLabel(notNull);

        // int len = input.length()
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitVarInsn(ISTORE, lenVar);

        // Create arrays once outside the loop
        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, startsVar);

        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, endsVar);

        // For patterns with start anchor, only try position 0
        if (info.hasStartAnchor) {
            // start = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, startVar);

            // Initialize arrays to -1
            for (int i = 0; i <= info.totalGroupCount; i++) {
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, i);
                mv.visitInsn(ICONST_M1);
                mv.visitInsn(IASTORE);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, i);
                mv.visitInsn(ICONST_M1);
                mv.visitInsn(IASTORE);
            }

            // pos = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, posVar);

            // Try to match at position 0 (use find variant - no pos==len check needed)
            generateMatchAtPositionForFind(mv, alloc, lenVar, posVar, startsVar, endsVar, countVar, grpLenVar, returnNull);

            // If we get here, match succeeded
            // Set group 0
            mv.visitVarInsn(ALOAD, startsVar);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ILOAD, startVar);
            mv.visitInsn(IASTORE);

            mv.visitVarInsn(ALOAD, endsVar);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);

            // Return MatchResultImpl
            mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, startsVar);
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, info.totalGroupCount);
            mv.visitMethodInsn(INVOKESPECIAL, "com/datadoghq/reggie/runtime/MatchResultImpl", "<init>",
                "(Ljava/lang/String;[I[II)V", false);
            mv.visitInsn(ARETURN);

        } else {
            // No start anchor - try each position
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, startVar);

            Label outerLoop = new Label();
            Label outerEnd = new Label();

            mv.visitLabel(outerLoop);

            // while (start <= len)
            mv.visitVarInsn(ILOAD, startVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, outerEnd);

            Label tryNextStart = new Label();

            // Initialize arrays to -1
            for (int i = 0; i <= info.totalGroupCount; i++) {
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, i);
                mv.visitInsn(ICONST_M1);
                mv.visitInsn(IASTORE);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, i);
                mv.visitInsn(ICONST_M1);
                mv.visitInsn(IASTORE);
            }

            // pos = start
            mv.visitVarInsn(ILOAD, startVar);
            mv.visitVarInsn(ISTORE, posVar);

            // Try to match at this position
            generateMatchAtPositionForFind(mv, alloc, lenVar, posVar, startsVar, endsVar, countVar, grpLenVar, tryNextStart);

            // Match succeeded
            mv.visitVarInsn(ALOAD, startsVar);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ILOAD, startVar);
            mv.visitInsn(IASTORE);

            mv.visitVarInsn(ALOAD, endsVar);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);

            mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, startsVar);
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, info.totalGroupCount);
            mv.visitMethodInsn(INVOKESPECIAL, "com/datadoghq/reggie/runtime/MatchResultImpl", "<init>",
                "(Ljava/lang/String;[I[II)V", false);
            mv.visitInsn(ARETURN);

            mv.visitLabel(tryNextStart);
            mv.visitIincInsn(startVar, 1);
            mv.visitJumpInsn(GOTO, outerLoop);

            mv.visitLabel(outerEnd);
        }

        mv.visitLabel(returnNull);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);

        mv.visitMaxs(6, alloc.peek());
        mv.visitEnd();
    }

    /**
     * Generate bytecode to try matching at the current position (for match() with anchor).
     * On failure, jumps to failLabel.
     */
    private void generateMatchAtPosition(MethodVisitor mv, LocalVarAllocator alloc,
            int lenVar, int posVar, int startsVar, int endsVar, int countVar, int grpLenVar,
            Label failLabel) {

        // Match optional groups
        for (OptionalGroupEntry entry : info.optionalGroups) {
            Label groupEnd = new Label();

            if (entry.isSingleChar && entry.literalChar >= 0) {
                Label captureEmpty = new Label();
                Label nonEmptyMatched = new Label();

                // Check bounds: if (pos >= len) goto captureEmpty
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGE, captureEmpty);

                // Check char match: if (input.charAt(pos) != literalChar) goto captureEmpty
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                pushInt(mv, entry.literalChar);
                mv.visitJumpInsn(IF_ICMPNE, captureEmpty);

                // Non-empty matched: capture it
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitIincInsn(posVar, 1);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitJumpInsn(GOTO, nonEmptyMatched);

                // Capture empty alternative: starts[groupNum] = pos, ends[groupNum] = pos
                mv.visitLabel(captureEmpty);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitLabel(nonEmptyMatched);
            } else if (entry.literalString != null) {
                // Multi-char literal string (e.g., "cow" in (cow|))
                Label captureEmptyMultiChar = new Label();
                Label nonEmptyMatchedMultiChar = new Label();

                int strLen = entry.literalString.length();

                // Check if enough characters remain: if (pos + strLen > len) goto captureEmptyMultiChar
                mv.visitVarInsn(ILOAD, posVar);
                pushInt(mv, strLen);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGT, captureEmptyMultiChar);

                // Use String.regionMatches(int, String, int, int)
                mv.visitVarInsn(ALOAD, 1);  // input string
                mv.visitVarInsn(ILOAD, posVar);  // toffset
                mv.visitLdcInsn(entry.literalString);  // other string
                mv.visitInsn(ICONST_0);  // ooffset
                pushInt(mv, strLen);  // len
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
                mv.visitJumpInsn(IFEQ, captureEmptyMultiChar);  // If false (0), capture empty

                // String matched - capture it
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitIincInsn(posVar, strLen);  // advance by string length

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitJumpInsn(GOTO, nonEmptyMatchedMultiChar);

                // Capture empty alternative: starts[groupNum] = pos, ends[groupNum] = pos
                mv.visitLabel(captureEmptyMultiChar);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitLabel(nonEmptyMatchedMultiChar);
            }

            mv.visitLabel(groupEnd);
        }

        // Match quantified backrefs
        for (BackrefEntry entry : info.backrefEntries) {
            Label backrefEnd = new Label();
            Label groupNotMatched = new Label();

            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitJumpInsn(IFLT, groupNotMatched);

            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitInsn(ISUB);
            mv.visitVarInsn(ISTORE, grpLenVar);

            // Handle zero-width backref (empty group capture)
            // PCRE semantics: empty string repeated N times is still empty (always matches)
            Label nonZeroWidth = new Label();
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitJumpInsn(IFNE, nonZeroWidth);

            // Zero-width: skip loop and count check entirely - empty always satisfies any count
            mv.visitJumpInsn(GOTO, backrefEnd);

            mv.visitLabel(nonZeroWidth);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, countVar);

            Label loopStart = new Label();
            Label loopEnd = new Label();

            mv.visitLabel(loopStart);

            // Check count < max (skip if max == -1, unlimited)
            if (entry.maxCount >= 0) {
                mv.visitVarInsn(ILOAD, countVar);
                pushInt(mv, entry.maxCount);
                mv.visitJumpInsn(IF_ICMPGE, loopEnd);
            }

            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, loopEnd);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches",
                "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, loopEnd);

            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);
            mv.visitIincInsn(countVar, 1);
            mv.visitJumpInsn(GOTO, loopStart);

            mv.visitLabel(loopEnd);

            mv.visitVarInsn(ILOAD, countVar);
            pushInt(mv, entry.minCount);
            mv.visitJumpInsn(IF_ICMPLT, failLabel);

            mv.visitJumpInsn(GOTO, backrefEnd);

            mv.visitLabel(groupNotMatched);

            mv.visitLabel(backrefEnd);
        }

        // Match suffix literal or suffix group
        if (info.suffixLiteralChar >= 0) {
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGE, failLabel);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, info.suffixLiteralChar);
            mv.visitJumpInsn(IF_ICMPNE, failLabel);

            mv.visitIincInsn(posVar, 1);
        } else if (info.suffixGroupLiteral != null) {
            // Match suffix capturing group with literal content
            int suffixLen = info.suffixGroupLiteral.length();

            // Check bounds: pos + suffixLen <= len
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, failLabel);

            // Compare using regionMatches
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitLdcInsn(info.suffixGroupLiteral);
            mv.visitInsn(ICONST_0);
            pushInt(mv, suffixLen);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, failLabel);

            // Capture suffix group: starts[suffixGroupNumber] = pos
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, info.suffixGroupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);

            // Advance pos by suffixLen
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);

            // Capture suffix group end: ends[suffixGroupNumber] = pos
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, info.suffixGroupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
        }

        // For match(), require pos == len
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPNE, failLabel);
    }

    /**
     * Generate bytecode to try matching at the current position (for findMatch without anchor).
     * On failure, jumps to failLabel. Does NOT check pos == len.
     */
    private void generateMatchAtPositionForFind(MethodVisitor mv, LocalVarAllocator alloc,
            int lenVar, int posVar, int startsVar, int endsVar, int countVar, int grpLenVar,
            Label failLabel) {

        // Match optional groups
        for (OptionalGroupEntry entry : info.optionalGroups) {
            Label groupEnd = new Label();

            if (entry.isSingleChar && entry.literalChar >= 0) {
                Label captureEmpty = new Label();
                Label nonEmptyMatched = new Label();

                // Check bounds: if (pos >= len) goto captureEmpty
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGE, captureEmpty);

                // Check char match: if (input.charAt(pos) != literalChar) goto captureEmpty
                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                pushInt(mv, entry.literalChar);
                mv.visitJumpInsn(IF_ICMPNE, captureEmpty);

                // Non-empty matched: capture it
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitIincInsn(posVar, 1);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitJumpInsn(GOTO, nonEmptyMatched);

                // Capture empty alternative: starts[groupNum] = pos, ends[groupNum] = pos
                mv.visitLabel(captureEmpty);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitLabel(nonEmptyMatched);
            } else if (entry.literalString != null) {
                // Multi-char literal string (e.g., "cow" in (cow|))
                Label captureEmptyMultiChar = new Label();
                Label nonEmptyMatchedMultiChar = new Label();
                int strLen = entry.literalString.length();

                // Check if enough characters remain: if (pos + strLen > len) goto captureEmptyMultiChar
                mv.visitVarInsn(ILOAD, posVar);
                pushInt(mv, strLen);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGT, captureEmptyMultiChar);

                // Use String.regionMatches(int, String, int, int)
                mv.visitVarInsn(ALOAD, 1);  // input string
                mv.visitVarInsn(ILOAD, posVar);  // toffset
                mv.visitLdcInsn(entry.literalString);  // other string
                mv.visitInsn(ICONST_0);  // ooffset
                pushInt(mv, strLen);  // len
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
                mv.visitJumpInsn(IFEQ, captureEmptyMultiChar);  // If false (0), capture empty

                // String matched - capture it
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitIincInsn(posVar, strLen);  // advance by string length

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitJumpInsn(GOTO, nonEmptyMatchedMultiChar);

                // Capture empty alternative: starts[groupNum] = pos, ends[groupNum] = pos
                mv.visitLabel(captureEmptyMultiChar);
                mv.visitVarInsn(ALOAD, startsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitVarInsn(ALOAD, endsVar);
                pushInt(mv, entry.groupNumber);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitInsn(IASTORE);

                mv.visitLabel(nonEmptyMatchedMultiChar);
            }

            mv.visitLabel(groupEnd);
        }

        // Match quantified backrefs
        for (BackrefEntry entry : info.backrefEntries) {
            Label backrefEnd = new Label();
            Label groupNotMatched = new Label();

            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitJumpInsn(IFLT, groupNotMatched);

            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitInsn(ISUB);
            mv.visitVarInsn(ISTORE, grpLenVar);

            // Handle zero-width backref (empty group capture)
            // PCRE semantics: empty string repeated N times is still empty (always matches)
            Label nonZeroWidth = new Label();
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitJumpInsn(IFNE, nonZeroWidth);

            // Zero-width: skip loop and count check entirely - empty always satisfies any count
            mv.visitJumpInsn(GOTO, backrefEnd);

            mv.visitLabel(nonZeroWidth);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, countVar);

            Label loopStart = new Label();
            Label loopEnd = new Label();

            mv.visitLabel(loopStart);

            // Check count < max (skip if max == -1, unlimited)
            if (entry.maxCount >= 0) {
                mv.visitVarInsn(ILOAD, countVar);
                pushInt(mv, entry.maxCount);
                mv.visitJumpInsn(IF_ICMPGE, loopEnd);
            }

            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, loopEnd);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(IALOAD);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches",
                "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, loopEnd);

            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, grpLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);
            mv.visitIincInsn(countVar, 1);
            mv.visitJumpInsn(GOTO, loopStart);

            mv.visitLabel(loopEnd);

            mv.visitVarInsn(ILOAD, countVar);
            pushInt(mv, entry.minCount);
            mv.visitJumpInsn(IF_ICMPLT, failLabel);

            mv.visitJumpInsn(GOTO, backrefEnd);

            mv.visitLabel(groupNotMatched);

            mv.visitLabel(backrefEnd);
        }

        // Match suffix literal or suffix group
        if (info.suffixLiteralChar >= 0) {
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGE, failLabel);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, info.suffixLiteralChar);
            mv.visitJumpInsn(IF_ICMPNE, failLabel);

            mv.visitIincInsn(posVar, 1);
        } else if (info.suffixGroupLiteral != null) {
            // Match suffix capturing group with literal content
            int suffixLen = info.suffixGroupLiteral.length();

            // Check bounds: pos + suffixLen <= len
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, failLabel);

            // Compare using regionMatches
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitLdcInsn(info.suffixGroupLiteral);
            mv.visitInsn(ICONST_0);
            pushInt(mv, suffixLen);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, failLabel);

            // Capture suffix group: starts[suffixGroupNumber] = pos
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, info.suffixGroupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);

            // Advance pos by suffixLen
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);

            // Capture suffix group end: ends[suffixGroupNumber] = pos
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, info.suffixGroupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
        }

        // Note: findMatch doesn't require pos == len (partial match is OK)
    }

    /**
     * Generate stub for findMatchFrom(String, int).
     */
    private void generateFindMatchFromStubMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(DUP);
        mv.visitLdcInsn("findMatchFrom() not yet implemented for OPTIONAL_GROUP_BACKREF strategy");
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(ATHROW);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    /**
     * Generate matches() method.
     */
    public void generateMatchesMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches",
            "(Ljava/lang/String;)Z", null, null);
        mv.visitCode();

        // Local vars: 0=this, 1=input
        LocalVarAllocator alloc = new LocalVarAllocator(2);
        int lenVar = alloc.allocate();
        int posVar = alloc.allocate();

        // Allocate vars for each optional group: matched flag, start, end, groupLen
        // Pre-allocate ALL variables upfront to ensure consistent local variable slots
        // across all code paths (required by JVM verifier)
        Map<Integer, int[]> groupVars = new HashMap<>();
        for (OptionalGroupEntry entry : info.optionalGroups) {
            int matchedVar = alloc.allocate();  // boolean: group matched?
            int startVar = alloc.allocate();    // group start position
            int endVar = alloc.allocate();      // group end position
            int groupLenVar = alloc.allocate(); // group length (for backref verification)
            groupVars.put(entry.groupNumber, new int[]{matchedVar, startVar, endVar, groupLenVar});
        }

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

        // int pos = 0;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, posVar);

        // Initialize ALL group variables to 0 (required by JVM verifier for consistent
        // local variable types across all code paths)
        for (int[] vars : groupVars.values()) {
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[0]);  // matched = false
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[1]);  // start = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[2]);  // end = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[3]);  // groupLen = 0
        }

        // Match each optional group - for (X|) patterns, always capture (either content or empty)
        for (OptionalGroupEntry entry : info.optionalGroups) {
            int[] vars = groupVars.get(entry.groupNumber);
            int matchedVar = vars[0];
            int startVar = vars[1];
            int endVar = vars[2];

            if (entry.isSingleChar && entry.literalChar >= 0) {
                Label charMatched = new Label();
                Label charNotMatched = new Label();
                Label groupEnd = new Label();

                // Check if char matches
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGE, charNotMatched);

                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                pushInt(mv, entry.literalChar);
                mv.visitJumpInsn(IF_ICMPEQ, charMatched);

                // Char not matched - capture empty (matched=true, start=pos, end=pos)
                mv.visitLabel(charNotMatched);
                mv.visitInsn(ICONST_1);
                mv.visitVarInsn(ISTORE, matchedVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, startVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, endVar);
                mv.visitJumpInsn(GOTO, groupEnd);

                // Char matched - capture it
                mv.visitLabel(charMatched);
                mv.visitInsn(ICONST_1);
                mv.visitVarInsn(ISTORE, matchedVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, startVar);
                mv.visitIincInsn(posVar, 1);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, endVar);

                mv.visitLabel(groupEnd);
            } else if (entry.literalString != null) {
                // Multi-char literal string (e.g., "cow" in (cow|))
                Label stringMatched = new Label();
                Label stringNotMatched = new Label();
                Label groupEnd = new Label();

                int strLen = entry.literalString.length();

                // Check if enough characters remain: pos + strLen <= len
                mv.visitVarInsn(ILOAD, posVar);
                pushInt(mv, strLen);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGT, stringNotMatched);

                // Use String.regionMatches(int, String, int, int)
                mv.visitVarInsn(ALOAD, 1);  // input string
                mv.visitVarInsn(ILOAD, posVar);  // toffset
                mv.visitLdcInsn(entry.literalString);  // other string
                mv.visitInsn(ICONST_0);  // ooffset
                pushInt(mv, strLen);  // len
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
                mv.visitJumpInsn(IFNE, stringMatched);  // IFNE = if not equal to 0 (i.e., true)

                // String not matched - capture empty (matched=true, start=pos, end=pos)
                mv.visitLabel(stringNotMatched);
                mv.visitInsn(ICONST_1);
                mv.visitVarInsn(ISTORE, matchedVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, startVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, endVar);
                mv.visitJumpInsn(GOTO, groupEnd);

                // String matched - capture it
                mv.visitLabel(stringMatched);
                mv.visitInsn(ICONST_1);
                mv.visitVarInsn(ISTORE, matchedVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, startVar);
                mv.visitIincInsn(posVar, strLen);  // advance by string length
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, endVar);

                mv.visitLabel(groupEnd);
            } else {
                // Complex group content - for now, skip (needs more work)
                // TODO: Handle charset and complex groups
            }
        }

        // Match middle elements (if any)
        // TODO: Handle middle nodes

        // Match quantified backrefs
        int countVar = alloc.allocate(); // for loop counter
        for (OptionalGroupBackrefInfo.BackrefEntry entry : info.backrefEntries) {
            int[] vars = groupVars.get(entry.groupNumber);
            if (vars == null) {
                // Backref to non-optional group - shouldn't happen with proper detection
                continue;
            }
            int matchedVar = vars[0];
            int startVar = vars[1];
            int endVar = vars[2];
            int groupLenVar = vars[3];

            Label backrefEnd = new Label();
            Label groupNotMatched = new Label();

            // if (!groupMatched) skip backref - shouldn't happen for (X|) patterns now
            mv.visitVarInsn(ILOAD, matchedVar);
            mv.visitJumpInsn(IFEQ, groupNotMatched);

            // Group matched: compute groupLen
            mv.visitVarInsn(ILOAD, endVar);
            mv.visitVarInsn(ILOAD, startVar);
            mv.visitInsn(ISUB);
            mv.visitVarInsn(ISTORE, groupLenVar);

            // If groupLen == 0, backref matches empty (skip loop)
            // Empty repeated any number of times is still empty
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitJumpInsn(IFEQ, backrefEnd);

            // Match backref min to max times
            // int count = 0;
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, countVar);

            Label loopStart = new Label();
            Label loopEnd = new Label();

            mv.visitLabel(loopStart);

            // Check count < max (skip check if max == -1, meaning unlimited)
            if (entry.maxCount >= 0) {
                mv.visitVarInsn(ILOAD, countVar);
                pushInt(mv, entry.maxCount);
                mv.visitJumpInsn(IF_ICMPGE, loopEnd);
            }

            // Check pos + groupLen <= len
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, loopEnd);

            // Check regionMatches
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, startVar);
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches",
                "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, loopEnd);

            // Match found - advance pos and count
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);
            mv.visitIincInsn(countVar, 1);
            mv.visitJumpInsn(GOTO, loopStart);

            mv.visitLabel(loopEnd);

            // Check count >= min
            mv.visitVarInsn(ILOAD, countVar);
            pushInt(mv, entry.minCount);
            mv.visitJumpInsn(IF_ICMPLT, returnFalse);

            mv.visitJumpInsn(GOTO, backrefEnd);

            // Group captured empty: backref matches empty (any quantifier is satisfied)
            mv.visitLabel(groupNotMatched);

            mv.visitLabel(backrefEnd);
        }

        // Match suffix literal or suffix group (if any)
        if (info.suffixLiteralChar >= 0) {
            // if (pos >= len) return false;
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGE, returnFalse);

            // if (input.charAt(pos) != suffixChar) return false;
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, info.suffixLiteralChar);
            mv.visitJumpInsn(IF_ICMPNE, returnFalse);

            mv.visitIincInsn(posVar, 1);
        } else if (info.suffixGroupLiteral != null) {
            // Match suffix capturing group with literal content (no capture in matches())
            int suffixLen = info.suffixGroupLiteral.length();

            // Check bounds: pos + suffixLen <= len
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, returnFalse);

            // Compare using regionMatches
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitLdcInsn(info.suffixGroupLiteral);
            mv.visitInsn(ICONST_0);
            pushInt(mv, suffixLen);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, returnFalse);

            // Advance pos by suffixLen
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);
        }

        // For matches(), require pos == len
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPNE, returnFalse);

        // Return true
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(returnFalse);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);

        mv.visitMaxs(6, alloc.peek());
        mv.visitEnd();
    }

    /**
     * Generate find() method - delegates to findFrom(input, 0).
     */
    public void generateFindMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find",
            "(Ljava/lang/String;)Z", null, null);
        mv.visitCode();

        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKEVIRTUAL, className, "findFrom",
            "(Ljava/lang/String;I)I", false);
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

    /**
     * Generate findFrom() method.
     */
    public void generateFindFromMethod(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom",
            "(Ljava/lang/String;I)I", null, null);
        mv.visitCode();

        // Local vars: 0=this, 1=input, 2=startPos
        LocalVarAllocator alloc = new LocalVarAllocator(3);
        int lenVar = alloc.allocate();
        int startVar = alloc.allocate();
        int posVar = alloc.allocate();

        // Allocate vars for each optional group: matched flag, start, end, groupLen
        // Pre-allocate ALL variables upfront to ensure consistent local variable slots
        Map<Integer, int[]> groupVars = new HashMap<>();
        for (OptionalGroupEntry entry : info.optionalGroups) {
            int matchedVar = alloc.allocate();
            int grpStartVar = alloc.allocate();
            int grpEndVar = alloc.allocate();
            int groupLenVar = alloc.allocate(); // pre-allocated for backref verification
            groupVars.put(entry.groupNumber, new int[]{matchedVar, grpStartVar, grpEndVar, groupLenVar});
        }

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

        // Check: start <= len (can match at end for empty pattern)
        mv.visitVarInsn(ILOAD, startVar);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitJumpInsn(IF_ICMPGT, outerEnd);

        // pos = start
        mv.visitVarInsn(ILOAD, startVar);
        mv.visitVarInsn(ISTORE, posVar);

        Label tryNextStart = new Label();

        // Initialize ALL group variables to 0 (required by JVM verifier)
        for (int[] vars : groupVars.values()) {
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[0]);  // matched = false
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[1]);  // start = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[2]);  // end = 0
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, vars[3]);  // groupLen = 0
        }

        // Try to match optional groups
        for (OptionalGroupEntry entry : info.optionalGroups) {
            int[] vars = groupVars.get(entry.groupNumber);
            int matchedVar = vars[0];
            int grpStartVar = vars[1];
            int grpEndVar = vars[2];

            Label groupEnd = new Label();

            if (entry.isSingleChar && entry.literalChar >= 0) {
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGE, groupEnd);

                mv.visitVarInsn(ALOAD, 1);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
                pushInt(mv, entry.literalChar);
                mv.visitJumpInsn(IF_ICMPNE, groupEnd);

                mv.visitInsn(ICONST_1);
                mv.visitVarInsn(ISTORE, matchedVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, grpStartVar);
                mv.visitIincInsn(posVar, 1);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, grpEndVar);
            } else if (entry.literalString != null) {
                // Multi-char literal string (e.g., "cow" in (cow|))
                int strLen = entry.literalString.length();

                // Check if enough characters remain: pos + strLen <= len
                mv.visitVarInsn(ILOAD, posVar);
                pushInt(mv, strLen);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ILOAD, lenVar);
                mv.visitJumpInsn(IF_ICMPGT, groupEnd);

                // Use String.regionMatches(int, String, int, int)
                mv.visitVarInsn(ALOAD, 1);  // input string
                mv.visitVarInsn(ILOAD, posVar);  // toffset
                mv.visitLdcInsn(entry.literalString);  // other string
                mv.visitInsn(ICONST_0);  // ooffset
                pushInt(mv, strLen);  // len
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
                mv.visitJumpInsn(IFEQ, groupEnd);  // If false (0), skip to end

                // String matched - capture it
                mv.visitInsn(ICONST_1);
                mv.visitVarInsn(ISTORE, matchedVar);
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, grpStartVar);
                mv.visitIincInsn(posVar, strLen);  // advance by string length
                mv.visitVarInsn(ILOAD, posVar);
                mv.visitVarInsn(ISTORE, grpEndVar);
            }

            mv.visitLabel(groupEnd);
        }

        // Check quantified backrefs
        int countVar = alloc.allocate();
        for (BackrefEntry entry : info.backrefEntries) {
            int[] vars = groupVars.get(entry.groupNumber);
            if (vars == null) continue;
            int matchedVar = vars[0];
            int grpStartVar = vars[1];
            int grpEndVar = vars[2];
            int groupLenVar = vars[3];

            Label backrefEnd = new Label();
            Label groupNotMatched = new Label();

            mv.visitVarInsn(ILOAD, matchedVar);
            mv.visitJumpInsn(IFEQ, groupNotMatched);

            // Group matched: compute groupLen
            mv.visitVarInsn(ILOAD, grpEndVar);
            mv.visitVarInsn(ILOAD, grpStartVar);
            mv.visitInsn(ISUB);
            mv.visitVarInsn(ISTORE, groupLenVar);

            // Handle zero-width backref (empty group capture)
            // PCRE semantics: empty string repeated N times is still empty (always matches)
            Label nonZeroWidthFind = new Label();
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitJumpInsn(IFNE, nonZeroWidthFind);
            // Zero-width: skip loop, backref satisfied
            mv.visitJumpInsn(GOTO, backrefEnd);
            mv.visitLabel(nonZeroWidthFind);

            // Match backref min to max times
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, countVar);

            Label loopStart = new Label();
            Label loopEnd = new Label();

            mv.visitLabel(loopStart);

            // Check count < max (skip if max == -1, unlimited)
            if (entry.maxCount >= 0) {
                mv.visitVarInsn(ILOAD, countVar);
                pushInt(mv, entry.maxCount);
                mv.visitJumpInsn(IF_ICMPGE, loopEnd);
            }

            // Check pos + groupLen <= len
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, loopEnd);

            // Check regionMatches
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, grpStartVar);
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches",
                "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, loopEnd);

            // Match found - advance
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, groupLenVar);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);
            mv.visitIincInsn(countVar, 1);
            mv.visitJumpInsn(GOTO, loopStart);

            mv.visitLabel(loopEnd);

            // Check count >= min
            mv.visitVarInsn(ILOAD, countVar);
            pushInt(mv, entry.minCount);
            mv.visitJumpInsn(IF_ICMPLT, tryNextStart);

            mv.visitJumpInsn(GOTO, backrefEnd);

            mv.visitLabel(groupNotMatched);
            // Group captured empty - backref matches empty (always satisfied)

            mv.visitLabel(backrefEnd);
        }

        // Match suffix literal or suffix group (if any)
        if (info.suffixLiteralChar >= 0) {
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGE, tryNextStart);

            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, info.suffixLiteralChar);
            mv.visitJumpInsn(IF_ICMPNE, tryNextStart);

            mv.visitIincInsn(posVar, 1);
        } else if (info.suffixGroupLiteral != null) {
            // Match suffix capturing group with literal content (no capture in find())
            int suffixLen = info.suffixGroupLiteral.length();

            // Check bounds: pos + suffixLen <= len
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, tryNextStart);

            // Compare using regionMatches
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitLdcInsn(info.suffixGroupLiteral);
            mv.visitInsn(ICONST_0);
            pushInt(mv, suffixLen);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, tryNextStart);

            // Advance pos by suffixLen (no capture needed for find())
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, suffixLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, posVar);
        }

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

        mv.visitMaxs(6, alloc.peek());
        mv.visitEnd();
    }
}
