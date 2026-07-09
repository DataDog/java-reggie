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

import static com.datadoghq.reggie.codegen.codegen.BytecodeUtil.pushInt;
import static org.objectweb.asm.Opcodes.*;

/**
 * Bytecode generator for OPTIONAL_GROUP_BACKREF strategy.
 *
 * <p>Generates optimized matching code for patterns where optional capturing groups are followed by
 * backreferences to those groups. Two group forms are supported:
 *
 * <ul>
 *   <li>{@code (X)?} — quantified optional: group may not participate at all. When skipped, the
 *       backref {@code \N} FAILS (Java semantics: non-participating group makes backref fail).
 *   <li>{@code (X|)} — empty-alt form: group always participates, capturing either {@code X} or
 *       the empty string. Backref matches empty when the empty alt was taken.
 * </ul>
 *
 * <h3>Supported Pattern Examples</h3>
 *
 * <ul>
 *   <li>{@code (a)?\1} - quantified optional 'a', then backref
 *   <li>{@code (a|)\1} - empty-alt 'a', then backref
 *   <li>{@code (foo)?bar\1} - optional group, literal, backref
 *   <li>{@code ^(a)?(b)?\1\2$} - multiple optional groups with backrefs
 * </ul>
 *
 * <h3>Java Semantics</h3>
 *
 * <ul>
 *   <li>Group matched (captured non-empty or empty via empty-alt): backref must match the captured
 *       content
 *   <li>Group did not participate ({@code (X)?} form, skipped): backref FAILS
 * </ul>
 *
 * <h3>Generated Algorithm (for (a)?\\1)</h3>
 *
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
 *     // group1Matched stays false if 'a' not present — group did NOT participate
 *
 *     // Match backref
 *     if (!group1Matched) return false;  // non-participating group: backref fails
 *     int groupLen = group1End - group1Start;
 *     if (groupLen > 0) {
 *         if (pos + groupLen > len) return false;
 *         if (!input.regionMatches(pos, input, group1Start, groupLen)) return false;
 *         pos += groupLen;
 *     }
 *     // else: group captured empty — backref matches empty (do nothing)
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
        // matchesBounded, matchBounded, findMatchFrom: handled by base-class defaults
        // (matchesBounded → matches(substring), matchBounded → match(substring),
        //  findMatchFrom → jdkFindMatchFrom)
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

        // Match optional groups (with real backtracking, fix for Bug 1), backrefs, and suffix.
        generateMatchAtPosition(mv, alloc, lenVar, posVar, startsVar, endsVar, countVar, grpLenVar,
            returnNull);

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
     * Recursively generates the optional-group decision tree with real backtracking (fix for
     * Bug 1 in doc/2026-07-09-optional-group-backref-backtracking-bug.md): each group's
     * content-present branch is tried first (greedy), and on any downstream failure (a later
     * group, a backref, the suffix, or the completion check) control falls back to that group's
     * absent/empty branch instead of failing outright. The absent branch explicitly resets
     * starts[]/ends[] to -1 so a group that ultimately did not participate never leaks a stale
     * capture from an earlier, abandoned attempt.
     *
     * <p>Backtrack order matches PCRE/Java: the last-decided group (highest groupIdx, nested
     * deepest) is retried first, exactly mirroring how {@code (a)?(b)?} backtracks {@code b}
     * before {@code a}.
     *
     * <p>Every leaf either jumps to {@code overallSuccess} (all groups+backrefs+suffix+
     * completion check passed) or, after every combination is exhausted, to {@code failLabel}.
     * Bytecode size is O(2^N) in the number of optional groups since the tail (backref/suffix/
     * completion check) is duplicated once per leaf; {@code detectOptionalGroupBackref} caps N
     * to keep this bounded.
     */
    private void generateGroupBacktrackTree(MethodVisitor mv, LocalVarAllocator alloc, int groupIdx,
            int lenVar, int posVar, int startsVar, int endsVar, int countVar, int grpLenVar,
            boolean requireFullConsumption, Label overallSuccess, Label failLabel) {

        if (groupIdx == info.optionalGroups.size()) {
            generateBackrefsSuffixAndCompletionCheck(mv, lenVar, posVar, startsVar, endsVar,
                countVar, grpLenVar, requireFullConsumption, failLabel);
            mv.visitJumpInsn(GOTO, overallSuccess);
            return;
        }

        OptionalGroupEntry entry = info.optionalGroups.get(groupIdx);
        int savedPosVar = alloc.allocate();

        // Save pos so the absent branch can undo any partial advance from the present branch.
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ISTORE, savedPosVar);

        Label absentBranch = new Label();

        if (entry.isSingleChar && entry.literalChar >= 0) {
            // Check bounds: if (pos >= len) goto absentBranch
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGE, absentBranch);

            // Check char: if (input.charAt(pos) != literalChar) goto absentBranch
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, entry.literalChar);
            mv.visitJumpInsn(IF_ICMPNE, absentBranch);

            // Char matched: capture it
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
            mv.visitIincInsn(posVar, 1);
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
        } else if (entry.literalString != null) {
            // Multi-char literal string (e.g., "cow" in (cow|))
            int strLen = entry.literalString.length();

            // Check bounds: if (pos + strLen > len) goto absentBranch
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, strLen);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPGT, absentBranch);

            mv.visitVarInsn(ALOAD, 1);  // input string
            mv.visitVarInsn(ILOAD, posVar);  // toffset
            mv.visitLdcInsn(entry.literalString);  // other string
            mv.visitInsn(ICONST_0);  // ooffset
            pushInt(mv, strLen);  // len
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
            mv.visitJumpInsn(IFEQ, absentBranch);

            // String matched - capture it
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
            mv.visitIincInsn(posVar, strLen);
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
        } else {
            // Unreachable: detectOptionalGroupBackref rejects any OptionalGroupEntry that is
            // neither (isSingleChar && literalChar >= 0) nor literalString != null (Bug 4 fix),
            // so every entry reaching codegen satisfies one of the two branches above.
            throw new IllegalStateException(
                "OptionalGroupEntry for group " + entry.groupNumber
                    + " has neither a literal char nor a literal string — detector invariant violated");
        }

        // Present branch: content matched. Recurse; any downstream failure falls back to this
        // group's absent branch (the real backtracking retry that Bug 1 was missing).
        generateGroupBacktrackTree(mv, alloc, groupIdx + 1, lenVar, posVar, startsVar, endsVar,
            countVar, grpLenVar, requireFullConsumption, overallSuccess, absentBranch);

        mv.visitLabel(absentBranch);
        mv.visitVarInsn(ILOAD, savedPosVar);
        mv.visitVarInsn(ISTORE, posVar);
        if (entry.alwaysCaptures) {
            // (X|) form: group always participates — falls back to an empty capture.
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
        } else {
            // (X)? form: group did not participate — clear any stale capture from the
            // abandoned present-branch attempt (this is the fix for Bug 1's stale-capture
            // symptom: group1 reported as [0,1) when JDK reports it unmatched).
            mv.visitVarInsn(ALOAD, startsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(ICONST_M1);
            mv.visitInsn(IASTORE);
            mv.visitVarInsn(ALOAD, endsVar);
            pushInt(mv, entry.groupNumber);
            mv.visitInsn(ICONST_M1);
            mv.visitInsn(IASTORE);
        }

        // Absent branch: recurse with the caller's failLabel — once every combination is
        // exhausted, this is what ultimately reports overall failure.
        generateGroupBacktrackTree(mv, alloc, groupIdx + 1, lenVar, posVar, startsVar, endsVar,
            countVar, grpLenVar, requireFullConsumption, overallSuccess, failLabel);
    }

    /**
     * Generates the backref-verification, suffix-matching, and completion-check tail shared by
     * every leaf of {@link #generateGroupBacktrackTree}. On any failure, jumps to failLabel.
     *
     * @param requireFullConsumption if true, requires pos == len at the end (unconditional full-
     *     match semantics for matches()/match()); if false, only enforced when the pattern has
     *     an end anchor (fix for Bug 3 — find()/findMatch()/findFrom() previously ignored
     *     hasEndAnchor entirely). Callers pass {@code info.hasEndAnchor} for find-family methods.
     */
    private void generateBackrefsSuffixAndCompletionCheck(MethodVisitor mv, int lenVar, int posVar,
            int startsVar, int endsVar, int countVar, int grpLenVar,
            boolean requireFullConsumption, Label failLabel) {

        // Match quantified backrefs
        for (BackrefEntry entry : info.backrefEntries) {
            Label backrefEnd = new Label();
            Label groupNotMatched = new Label();
            // detectOptionalGroupBackref requires every backref to reference one of the
            // optional groups ("All backrefs must reference optional groups"), so groupEntry is
            // never null here.
            OptionalGroupEntry groupEntry = info.getGroupEntry(entry.groupNumber);
            boolean alwaysCaptures = groupEntry.alwaysCaptures;

            // starts[groupNum] < 0 means group did not participate
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
            // Empty repeated any number of times is still empty — always satisfies
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
            if (!alwaysCaptures && entry.minCount > 0) {
                // (X)? form: group did not participate, and the backref is required at least
                // once — Java semantics: \N to a non-participating group always fails, so a
                // mandatory repetition can never be satisfied.
                mv.visitJumpInsn(GOTO, failLabel);
            }
            // Either (X|) form (group always captures — empty backref vacuously satisfied), or
            // (X)? form with minCount == 0: the quantifier is satisfied by zero repetitions
            // without ever needing \N to succeed (confirmed against JDK: "(a)?\1{0,3}a" matches
            // "a" with group 1 unmatched).

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

        if (requireFullConsumption) {
            // matches()/match(): unconditional full-match semantics.
            // find()/findMatch()/findFrom() with an end anchor (Bug 3 fix): a found match must
            // still extend to the end of input.
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitVarInsn(ILOAD, lenVar);
            mv.visitJumpInsn(IF_ICMPNE, failLabel);
        }
    }

    /**
     * Generate bytecode to try matching at the current position (for match() with anchor).
     * Falls through to the next instruction on success; jumps to failLabel on failure. Always
     * requires full consumption (pos == len).
     */
    private void generateMatchAtPosition(MethodVisitor mv, LocalVarAllocator alloc,
            int lenVar, int posVar, int startsVar, int endsVar, int countVar, int grpLenVar,
            Label failLabel) {
        Label success = new Label();
        generateGroupBacktrackTree(mv, alloc, 0, lenVar, posVar, startsVar, endsVar, countVar,
            grpLenVar, true, success, failLabel);
        mv.visitLabel(success);
    }

    /**
     * Generate bytecode to try matching at the current position (for findMatch/find without
     * requiring the match to consume the whole string). Falls through to the next instruction on
     * success; jumps to failLabel on failure. Requires full consumption only when the pattern has
     * an end anchor (fix for Bug 3).
     */
    private void generateMatchAtPositionForFind(MethodVisitor mv, LocalVarAllocator alloc,
            int lenVar, int posVar, int startsVar, int endsVar, int countVar, int grpLenVar,
            Label failLabel) {
        Label success = new Label();
        generateGroupBacktrackTree(mv, alloc, 0, lenVar, posVar, startsVar, endsVar, countVar,
            grpLenVar, info.hasEndAnchor, success, failLabel);
        mv.visitLabel(success);
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
        int startsVar = alloc.allocate();
        int endsVar = alloc.allocate();
        int countVar = alloc.allocate(); // for backref loop
        int grpLenVar = alloc.allocate(); // for backref length

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

        // int[] starts/ends = new int[groupCount + 1], initialized to -1 (not participated)
        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, startsVar);
        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, endsVar);
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

        // Match optional groups (with real backtracking, fix for Bug 1), backrefs, and suffix.
        generateMatchAtPosition(mv, alloc, lenVar, posVar, startsVar, endsVar, countVar, grpLenVar,
            returnFalse);

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
        int startsVar = alloc.allocate();
        int endsVar = alloc.allocate();
        int countVar = alloc.allocate();
        int grpLenVar = alloc.allocate();

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

        // Create arrays once outside the loop
        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, startsVar);
        pushInt(mv, info.totalGroupCount + 1);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitVarInsn(ASTORE, endsVar);

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

        // Initialize arrays to -1 (not participated)
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

        // Try to match optional groups (with real backtracking, fix for Bug 1), backrefs, and
        // suffix — including the end-anchor check (fix for Bug 3).
        generateMatchAtPositionForFind(mv, alloc, lenVar, posVar, startsVar, endsVar, countVar,
            grpLenVar, tryNextStart);


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
