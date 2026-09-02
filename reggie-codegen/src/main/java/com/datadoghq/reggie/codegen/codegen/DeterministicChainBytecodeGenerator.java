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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.DeterministicChainInfo;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates straight-line bytecode for the deterministic chain family ({@code
 * DETERMINISTIC_CHAIN_BYTECODE}, see doc/2026-09-01-deterministic-chain-bytecode-design.md).
 *
 * <h3>Stage 3 scope (v3)</h3>
 *
 * A single chain branch with all v1 elements plus CAPTURE (group start/end locals written along the
 * single deterministic pass), LIT_ALT (bounded sequential tries with position restore), OPT (greedy
 * two-attempt: with-prefix first, restart from the element start on failure — OPT never contains
 * captures, the detector declines capturing optional groups), LAZY_LOOP (scan loop: try the tail at
 * the current position, on tail failure consume one loop-class char and retry), and {@code
 * ^}/{@code $} anchors ({@code ^} non-multiline: the branch is tried only at scan position 0;
 * {@code $}: the match end must sit at region end, or before any line terminator when multiline).
 *
 * <p>Generated surface: {@code matches}/{@code match}/{@code find}/{@code findFrom}/{@code
 * findMatch}/{@code findMatchFrom}/{@code findBoundsFrom}. Captures live in int locals — one
 * start/end slot pair per group — written exactly once per successful path (the chain is
 * deterministic: no give-back), so no reset is ever needed and the boolean paths stay
 * allocation-free. {@code matchesBounded}/{@code matchBounded} inherit the ReggieMatcher defaults
 * (region substring) for now.
 *
 * <h3>Capture ends at lazy loops</h3>
 *
 * {@code (\w*?)@}: the group's content ends where the lazy run stopped — at the <em>start</em> of
 * the winning tail try, not after the tail consumed its chars. The emitter passes each nested seq
 * the list of capture groups that close at that seq's end ({@code closingAtEnd}); a LAZY_LOOP that
 * is the last element of its seq writes those groups' end slots at every scan position, and the
 * winning try's write is the one that counts (last write wins). Wrappers skip their own end-write
 * when their nested seq effectively ends at such a loop ({@link #seqEffectivelyEndsAtLazyLoop}); an
 * OPT in the tail position writes its seq's {@code closingAtEnd} groups on the skip path (position
 * restored to the element start) and delegates to the loop on the matched path.
 *
 * <h3>Not yet in v3</h3>
 *
 * Alternation of chains (stage 4) and the find() work budget + PikeVM fallback (design doc §4) — so
 * a gate-passing but failing try re-consumes its run at every following position and find can be
 * O(n²) on adversarial inputs. The strategy is not wired into routing until stage 5, so no
 * user-facing pattern can reach this.
 */
public class DeterministicChainBytecodeGenerator {

  /** The chain branches, in pattern (priority) order. */
  private final List<DeterministicChainInfo.ChainBranch> branches;

  /** Capturing groups in the pattern; slot arrays are sized groupCount+1 (index 0 unused). */
  private final int groupCount;

  /**
   * Work budget for the find-family scans: {@code (4 + totalElemCount) * (len + 1)} (design §4) —
   * generous enough that any single linear pass with all its tries never trips it, tight enough
   * that quadratic re-consumption trips it early.
   */
  private final int budgetConst;

  /** Union of the unanchored branches' first-sets — the scan position gate. */
  private final boolean[] unionFirstSet = new boolean[128];

  /** Minimum match width over the unanchored branches (the scan's trailing-position bound). */
  private final int minUnanchoredMinWidth;

  /** True when every branch is ^-anchored (no scan loop; one try at position 0). */
  private final boolean allAnchored;

  /** True when at least one branch is ^-anchored (position 0 skips the union gate). */
  private final boolean hasAnchored;

  public DeterministicChainBytecodeGenerator(DeterministicChainInfo info, int groupCount) {
    this.branches = info.branches;
    this.groupCount = groupCount;
    int elems = 0;
    for (DeterministicChainInfo.ChainBranch b : branches) {
      elems += countElems(b.seq);
      if (!b.startAnchored) {
        for (int c = 0; c < 128; c++) {
          unionFirstSet[c] |= b.firstSetAscii[c];
        }
      }
    }
    this.budgetConst = 4 + elems;
    int minW = Integer.MAX_VALUE;
    boolean all = true;
    for (DeterministicChainInfo.ChainBranch b : branches) {
      if (b.startAnchored) {
        continue;
      }
      all = false;
      minW = Math.min(minW, b.minWidth);
    }
    this.allAnchored = all;
    this.hasAnchored = anyStartAnchored(branches);
    this.minUnanchoredMinWidth = all ? 0 : minW;
  }

  /** Total element count of a chain tree (the budget constant's scale factor). */
  private static int countElems(DeterministicChainInfo.ChainSeq seq) {
    int n = 0;
    for (DeterministicChainInfo.ChainElem e : seq.elems) {
      n++;
      if (e.nested != null) {
        n += countElems(e.nested);
      }
    }
    return n;
  }

  /**
   * Per-lazy-loop success predicate (see emitLazyLoop): what a successful tail try must satisfy.
   */
  private enum LazyPredicate {
    /** find() semantics: a successful tail try is accepted unconditionally. */
    NONE,
    /** matches()/match(): the tail try's end must be the input end (pos == len). */
    POS_EQ_LEN,
    /** find with a $-anchored branch: the tail try's end must satisfy the $ anchor check. */
    END_ANCHOR
  }

  /** Slots and scratch locals shared by all emission helpers of one method. */
  private static final class EmitCtx {
    final MethodVisitor mv;
    final LocalVarAllocator alloc;
    final int inputVar;
    final int lenVar;
    final int posVar;
    final int cVar;

    /** Capture start slot per group number (index 0 unused), or null when groupCount == 0. */
    final int[] capStart;

    final int[] capEnd;
    final LazyPredicate lazyPredicate;

    /** The $-anchor mode for the lazy END_ANCHOR predicate (see emitEndAnchorCheck). */
    final boolean lazyMultiline;

    /**
     * Innermost enclosing retry label (a LIT_ALT downstreamFail or an OPT optRetry) for the current
     * emission point — JDK backtracking retries the bounded alternatives of the innermost retryable
     * construct on any downstream failure, so element failures and the method-level end checks
     * target this when set. Never reset: a retry stays valid through the end of the try; later
     * retryables overwrite it (last one on the emission path is innermost).
     */
    Label retryFail;

    EmitCtx(
        MethodVisitor mv,
        LocalVarAllocator alloc,
        int inputVar,
        int lenVar,
        int posVar,
        int cVar,
        int groupCount,
        LazyPredicate lazyPredicate,
        boolean lazyMultiline) {
      this.mv = mv;
      this.alloc = alloc;
      this.inputVar = inputVar;
      this.lenVar = lenVar;
      this.posVar = posVar;
      this.cVar = cVar;
      this.lazyPredicate = lazyPredicate;
      this.lazyMultiline = lazyMultiline;
      if (groupCount > 0) {
        this.capStart = new int[groupCount + 1];
        this.capEnd = new int[groupCount + 1];
        for (int g = 1; g <= groupCount; g++) {
          this.capStart[g] = alloc.allocate();
          this.capEnd[g] = alloc.allocate();
        }
      } else {
        this.capStart = null;
        this.capEnd = null;
      }
    }
  }

  /**
   * The effective failure target at the current emission point (innermost retry, else the base).
   */
  private static Label effFail(EmitCtx ctx, Label base) {
    return ctx.retryFail != null ? ctx.retryFail : base;
  }

  // =====================================================================================
  // matches / match — one anchored try from position 0.
  // =====================================================================================

  /**
   * Generates {@code matches(String)}: one try from position 0, success iff the try consumed the
   * whole input. Single try ⇒ O(n) by construction, no budget needed (design doc §4). {@code ^} is
   * a no-op here and {@code $} is subsumed by the whole-input check.
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int cVar = allocator.allocate();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    EmitCtx ctx =
        new EmitCtx(
            mv,
            allocator,
            inputVar,
            lenVar,
            posVar,
            cVar,
            groupCount,
            LazyPredicate.POS_EQ_LEN,
            false);
    Label fail = new Label();
    // Branch tries in pattern (priority) order — a failed branch's inner retries are dead, so
    // the retry chain is suspended per branch; the whole-input check failure first retries the
    // innermost alternative inside the CURRENT branch, then moves to the next branch.
    for (DeterministicChainInfo.ChainBranch b : branches) {
      Label branchFail = new Label();
      Label savedRetry = ctx.retryFail;
      ctx.retryFail = null;
      // A failed earlier branch leaves a partially consumed pos and stale capture slots: reset
      // both (groups outside this branch must end unmatched).
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, posVar);
      emitResetCaptures(mv, ctx);
      emitChainTry(ctx, b.seq, 0, List.of(), branchFail, List.of());
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPNE, effFail(ctx, branchFail));
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(branchFail);
      ctx.retryFail = savedRetry;
    }
    mv.visitLabel(fail);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generates {@code match(String)}: the same try, then a MatchResult with the group spans. */
  public void generateMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "match",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int cVar = allocator.allocate();

    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    EmitCtx ctx =
        new EmitCtx(
            mv,
            allocator,
            inputVar,
            lenVar,
            posVar,
            cVar,
            groupCount,
            LazyPredicate.POS_EQ_LEN,
            false);
    Label fail = new Label();
    // Branch tries in pattern (priority) order; whole-input failure retries the innermost
    // alternative inside the current branch first, then the next branch.
    for (DeterministicChainInfo.ChainBranch b : branches) {
      Label branchFail = new Label();
      Label savedRetry = ctx.retryFail;
      ctx.retryFail = null;
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, posVar);
      emitResetCaptures(mv, ctx);
      emitChainTry(ctx, b.seq, 0, List.of(), branchFail, List.of());
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPNE, effFail(ctx, branchFail));
      emitBuildResult(ctx, true, -1);
      mv.visitLabel(branchFail);
      ctx.retryFail = savedRetry;
    }
    mv.visitLabel(fail);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // =====================================================================================
  // find / findFrom — the scan family.
  // =====================================================================================

  /** Generates {@code find(String)}: delegates to {@code findFrom(input, 0) >= 0}. */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    Label found = new Label();
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates {@code findFrom(String, int)}. Unanchored: scan positions from {@code start},
   * first-consumed-char gate per position, chain try from each gate hit, first success returns the
   * match start. Anchored ({@code ^}): a single try from position 0 (start must be 0). {@code $} is
   * checked at try success and re-enters the scan on failure.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();
    emitScanMethod(
        cw,
        mv,
        "findFrom",
        "(Ljava/lang/String;I)I",
        ScanReturn.INT_RETURN_POSITION,
        ScanReturn.FAIL_PUSH_M1_INT_RETURN,
        className);
  }

  /** Generates {@code findBoundsFrom(String, int, int[])}: same scan, bounds[] out, boolean. */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();
    emitScanMethod(
        cw,
        mv,
        "findBoundsFrom",
        "(Ljava/lang/String;I[I)Z",
        ScanReturn.BOOL_BOUNDS,
        ScanReturn.FAIL_PUSH_0_INT_RETURN,
        className);
  }

  /** Generates {@code findMatch(String)}: delegates to {@code findMatchFrom(input, 0)}. */
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

  /** Generates {@code findMatchFrom(String, int)}: the scan, returning a MatchResult on success. */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();
    emitScanMethod(
        cw,
        mv,
        "findMatchFrom",
        "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
        ScanReturn.MATCH_RESULT,
        ScanReturn.FAIL_PUSH_NULL_ARETURN,
        className);
  }

  /** The three success shapes of the scan methods. */
  private enum ScanReturn {
    INT_RETURN_POSITION, // findFrom: IRETURN p
    BOOL_BOUNDS, // findBoundsFrom: bounds[0]=p, bounds[1]=pos, IRETURN true
    MATCH_RESULT, // findMatchFrom: build result [p, pos] + captures
    FAIL_PUSH_M1_INT_RETURN, // failure: push -1, IRETURN
    FAIL_PUSH_0_INT_RETURN, // failure: push 0, IRETURN
    FAIL_PUSH_NULL_ARETURN // failure: push null, ARETURN
  }

  /**
   * Shared scan-method body. {@code successShape} picks the success emission, {@code failShape} the
   * failure emission; every other failure inside the method jumps to the same fail emission.
   */
  private void emitScanMethod(
      ClassWriter cw,
      MethodVisitor mv,
      String methodName,
      String methodDesc,
      ScanReturn successShape,
      ScanReturn failShape,
      String className) {
    boolean hasBoundsArray = successShape == ScanReturn.BOOL_BOUNDS;
    LocalVarAllocator allocator = new LocalVarAllocator(hasBoundsArray ? 4 : 3);
    int inputVar = 1;
    int startVar = 2;
    int boundsVar = hasBoundsArray ? 3 : -1;
    int lenVar = allocator.allocate();
    int pVar = allocator.allocate();
    int posVar = allocator.allocate();
    int cVar = allocator.allocate();
    int workVar = allocator.allocate();

    Label checksPass = new Label();
    Label failLabel = new Label();
    Label overflowLabel = new Label();

    // Guards: null input / negative or too-large start (Reggie convention: fail value, JDK throws).
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNULL, failLabel);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFLT, failLabel);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
    mv.visitJumpInsn(GOTO, checksPass);
    mv.visitLabel(failLabel);
    emitFailShape(mv, failShape);
    mv.visitLabel(checksPass);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, pVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, workVar);

    // A $-anchored branch accepts only a tail try ending at a valid $ position, so the per-try
    // lazy predicate is END_ANCHOR when ANY branch is $-anchored (per-branch: an unanchored
    // branch never reaches its predicate with a $-failing end — an unanchored branch's tail try
    // succeeds on its own; the predicate applies only to $-anchored branch tries... but the
    // predicate is per-method, so only set it when all branches agree; mixed patterns get the
    // conservative NONE and rely on the post-try $-check per branch).
    boolean anyEndAnchored = false;
    boolean allEndAnchored = true;
    for (DeterministicChainInfo.ChainBranch b : branches) {
      if (b.endAnchored) {
        anyEndAnchored = true;
      } else {
        allEndAnchored = false;
      }
    }
    boolean uniformMultiline = true;
    for (DeterministicChainInfo.ChainBranch b : branches) {
      if (b.endAnchored && b.multilineEnd != branches.get(0).multilineEnd) {
        uniformMultiline = false;
      }
    }
    LazyPredicate predicate =
        allEndAnchored && anyEndAnchored && uniformMultiline
            ? LazyPredicate.END_ANCHOR
            : LazyPredicate.NONE;
    boolean lazyMultiline = anyEndAnchored && uniformMultiline && branches.get(0).multilineEnd;
    EmitCtx ctx =
        new EmitCtx(
            mv, allocator, inputVar, lenVar, posVar, cVar, groupCount, predicate, lazyMultiline);
    Label scanEnd = new Label();
    Label tryFail = new Label();

    // The per-scan-position branch-try sequence, shared by the scan loop and the all-anchored
    // fast path. Anchored branches are tried only at p == 0; each unanchored branch is preceded
    // by its own first-set gate (O(1) reject). Branch try failures charge the work budget.
    if (allAnchored) {
      // Every branch is ^-anchored: no scan; one try at position 0 (start must be 0). Bounded —
      // no budget needed.
      mv.visitVarInsn(ILOAD, pVar);
      mv.visitJumpInsn(IFNE, scanEnd);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, posVar);
      emitBranchTries(mv, ctx, pVar, posVar, cVar, boundsVar, successShape, tryFail, scanEnd);
      mv.visitLabel(tryFail);
      mv.visitJumpInsn(GOTO, scanEnd);
    } else {
      List<int[]> unionRuns = firstSetRuns(unionFirstSet);
      Label scanTop = new Label();
      Label gateFail = tryFail; // gate skip and branch failures merge at the advance block
      mv.visitLabel(scanTop);
      // while (p <= len - minUnanchoredMinWidth) — the cheapest unanchored branch needs its
      // minWidth chars from p.
      mv.visitVarInsn(ILOAD, pVar);
      mv.visitVarInsn(ILOAD, lenVar);
      pushInt(mv, minUnanchoredMinWidth);
      mv.visitInsn(ISUB);
      mv.visitJumpInsn(IF_ICMPGT, scanEnd);

      // pos = p first: a gate-skipped position charges a clean +1 (pos == p) in the advance block.
      mv.visitVarInsn(ILOAD, pVar);
      mv.visitVarInsn(ISTORE, posVar);

      // Union first-set gate: no unanchored branch can start at p unless charAt(p) hits.
      // Position 0 skips the union gate when any branch is ^-anchored — anchored branches are
      // tried only there and their first chars are not part of the union.
      if (hasAnchored) {
        Label gateCheck = new Label();
        Label afterGate = new Label();
        mv.visitVarInsn(ILOAD, pVar);
        mv.visitJumpInsn(IFNE, gateCheck);
        mv.visitJumpInsn(GOTO, afterGate);
        mv.visitLabel(gateCheck);
        emitUnionGate(mv, inputVar, pVar, cVar, unionRuns, gateFail);
        mv.visitLabel(afterGate);
      } else {
        emitUnionGate(mv, inputVar, pVar, cVar, unionRuns, gateFail);
      }

      emitBranchTries(mv, ctx, pVar, posVar, cVar, boundsVar, successShape, tryFail, scanEnd);

      // Advance one scan position and retry. Branch failures charge (pos - p) + 1 here (pos is
      // the failed try's consumed end; a clean gate skip leaves pos == p for +1).
      mv.visitLabel(tryFail);
      mv.visitVarInsn(ILOAD, workVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, pVar);
      mv.visitInsn(ISUB);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD);
      mv.visitInsn(IADD);
      mv.visitVarInsn(ISTORE, workVar);
      mv.visitVarInsn(ILOAD, workVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD);
      pushInt(mv, budgetConst);
      mv.visitInsn(IMUL);
      mv.visitJumpInsn(IF_ICMPGT, overflowLabel);
      mv.visitIincInsn(pVar, 1);
      mv.visitJumpInsn(GOTO, scanTop);
    }

    // Scan exhausted (or all-anchored try failed): legit no-match.
    mv.visitLabel(scanEnd);
    emitFailShape(mv, failShape);

    // Budget overflow: the whole call is re-run by the lazily-parsed PikeVM fallback (linear,
    // correct; design §4). fbCount++ then delegate by shape.
    mv.visitLabel(overflowLabel);
    emitFallbackDelegation(mv, inputVar, startVar, boundsVar, successShape, className);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Emits {@code capStart[g] = capEnd[g] = -1} for every group (a fresh try's unmatched state). */
  private void emitResetCaptures(MethodVisitor mv, EmitCtx ctx) {
    if (ctx.capStart == null) {
      return;
    }
    for (int g = 1; g <= groupCount; g++) {
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, ctx.capStart[g]);
      mv.visitInsn(ICONST_M1);
      mv.visitVarInsn(ISTORE, ctx.capEnd[g]);
    }
  }

  private static boolean anyStartAnchored(List<DeterministicChainInfo.ChainBranch> branches) {
    for (DeterministicChainInfo.ChainBranch b : branches) {
      if (b.startAnchored) {
        return true;
      }
    }
    return false;
  }

  /** Emits {@code charAt(p) ∈ unionRuns, else goto gateFail}. */
  private void emitUnionGate(
      MethodVisitor mv, int inputVar, int pVar, int cVar, List<int[]> unionRuns, Label gateFail) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);
    emitCharInRuns(mv, cVar, unionRuns, gateFail);
  }

  /**
   * Emits the per-position branch tries in pattern (priority) order. Each branch: ^ guard (p == 0
   * for anchored branches), first-set gate (unanchored), chain try, $-check, success shape. A
   * branch's inner retry chain is suspended (its alternatives are dead once the branch failed) and
   * its failures flow to {@code tryFail} (the scan's advance block).
   */
  private void emitBranchTries(
      MethodVisitor mv,
      EmitCtx ctx,
      int pVar,
      int posVar,
      int cVar,
      int boundsVar,
      ScanReturn successShape,
      Label tryFail,
      Label scanEnd) {
    for (DeterministicChainInfo.ChainBranch b : branches) {
      Label branchFail = new Label();
      Label savedRetry = ctx.retryFail;
      ctx.retryFail = null;

      if (b.startAnchored) {
        // ^ (non-multiline): this branch matches only at absolute scan position 0.
        mv.visitVarInsn(ILOAD, pVar);
        mv.visitJumpInsn(IFNE, branchFail);
      } else {
        // Per-branch first-set gate: O(1) reject of positions this branch cannot start at.
        List<int[]> runs = firstSetRuns(b.firstSetAscii);
        mv.visitVarInsn(ALOAD, ctx.inputVar);
        mv.visitVarInsn(ILOAD, pVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        mv.visitVarInsn(ISTORE, cVar);
        emitCharInRuns(mv, cVar, runs, branchFail);
      }
      // Reset the try: a failed earlier branch leaves a partially consumed pos and stale
      // capture slots (groups outside this branch must end unmatched).
      mv.visitVarInsn(ILOAD, pVar);
      mv.visitVarInsn(ISTORE, posVar);
      emitResetCaptures(mv, ctx);

      emitChainTry(ctx, b.seq, 0, List.of(), branchFail, List.of());
      if (b.endAnchored) {
        emitEndAnchorCheck(ctx, effFail(ctx, branchFail), b.multilineEnd);
      }
      emitSuccessShape(mv, ctx, pVar, posVar, boundsVar, successShape);
      mv.visitLabel(branchFail);
      ctx.retryFail = savedRetry;
    }
  }

  /** Emits the overflow-path delegation to the lazily-parsed PikeVM fallback, per return shape. */
  private void emitFallbackDelegation(
      MethodVisitor mv,
      int inputVar,
      int startVar,
      int boundsVar,
      ScanReturn successShape,
      String className) {
    String internal = className.replace('.', '/');
    // fbCount++
    mv.visitVarInsn(ALOAD, 0);
    mv.visitInsn(DUP);
    mv.visitFieldInsn(GETFIELD, internal, "fbCount", "J");
    mv.visitInsn(LCONST_1);
    mv.visitInsn(LADD);
    mv.visitFieldInsn(PUTFIELD, internal, "fbCount", "J");
    // return fb().<method>(args...)
    mv.visitVarInsn(ALOAD, 0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, internal, "fb", "()Lcom/datadoghq/reggie/runtime/ReggieMatcher;", false);
    mv.visitVarInsn(ALOAD, inputVar);
    switch (successShape) {
      case INT_RETURN_POSITION:
        mv.visitVarInsn(ILOAD, startVar);
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "com/datadoghq/reggie/runtime/ReggieMatcher",
            "findFrom",
            "(Ljava/lang/String;I)I",
            false);
        mv.visitInsn(IRETURN);
        break;
      case BOOL_BOUNDS:
        mv.visitVarInsn(ILOAD, startVar);
        mv.visitVarInsn(ALOAD, boundsVar);
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "com/datadoghq/reggie/runtime/ReggieMatcher",
            "findBoundsFrom",
            "(Ljava/lang/String;I[I)Z",
            false);
        mv.visitInsn(IRETURN);
        break;
      case MATCH_RESULT:
        mv.visitVarInsn(ILOAD, startVar);
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            "com/datadoghq/reggie/runtime/ReggieMatcher",
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            false);
        mv.visitInsn(ARETURN);
        break;
      default:
        throw new IllegalStateException(successShape.toString());
    }
  }

  /**
   * Generates the fallback support: {@code fb} / {@code fbCount} fields, the lazy {@code fb()}
   * (parse-at-overflow: this.pattern -> RegexParser -> ThompsonBuilder -> PikeVMMatcher, cold path,
   * once per matcher instance — keeps the generated constructor signature identical across the dual
   * paths), and the observable {@code fallbackCount()}.
   */
  public void generateFallbackSupport(ClassWriter cw, String className) {
    String internal = className.replace('.', '/');
    cw.visitField(ACC_PRIVATE, "fb", "Lcom/datadoghq/reggie/runtime/ReggieMatcher;", null, null);
    cw.visitField(ACC_PRIVATE, "fbCount", "J", null, null);

    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "fallbackCount", "()J", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, internal, "fbCount", "J");
    mv.visitInsn(LRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();

    mv =
        cw.visitMethod(
            ACC_PRIVATE, "fb", "()Lcom/datadoghq/reggie/runtime/ReggieMatcher;", null, null);
    mv.visitCode();
    Label done = new Label();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, internal, "fb", "Lcom/datadoghq/reggie/runtime/ReggieMatcher;");
    mv.visitJumpInsn(IFNONNULL, done);
    // fb = new PikeVMMatcher(new ThompsonBuilder().build(new RegexParser().parse(pattern),
    // groupCount), pattern)
    mv.visitVarInsn(ALOAD, 0);
    // NEW+DUP keeps the fallback ref under the construction operands: <init> pops the top
    // [dup, NFA, pattern] (void — pushes nothing back), then PUTFIELD consumes [this, init].
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/PikeVMMatcher");
    mv.visitInsn(DUP);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/codegen/parsing/RegexParser");
    mv.visitInsn(DUP);
    mv.visitMethodInsn(
        INVOKESPECIAL, "com/datadoghq/reggie/codegen/parsing/RegexParser", "<init>", "()V", false);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, internal, "pattern", "Ljava/lang/String;");
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        "com/datadoghq/reggie/codegen/parsing/RegexParser",
        "parse",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/codegen/ast/RegexNode;",
        false);
    mv.visitVarInsn(ASTORE, 1); // scratch local for the AST
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/codegen/automaton/ThompsonBuilder");
    mv.visitInsn(DUP);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/codegen/automaton/ThompsonBuilder",
        "<init>",
        "()V",
        false);
    mv.visitVarInsn(ALOAD, 1);
    pushInt(mv, groupCount);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        "com/datadoghq/reggie/codegen/automaton/ThompsonBuilder",
        "build",
        "(Lcom/datadoghq/reggie/codegen/ast/RegexNode;I)Lcom/datadoghq/reggie/codegen/automaton/NFA;",
        false);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, internal, "pattern", "Ljava/lang/String;");
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/PikeVMMatcher",
        "<init>",
        "(Lcom/datadoghq/reggie/codegen/automaton/NFA;Ljava/lang/String;)V",
        false);
    mv.visitFieldInsn(PUTFIELD, internal, "fb", "Lcom/datadoghq/reggie/runtime/ReggieMatcher;");
    mv.visitLabel(done);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitFieldInsn(GETFIELD, internal, "fb", "Lcom/datadoghq/reggie/runtime/ReggieMatcher;");
    mv.visitInsn(ARETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  private void emitFailShape(MethodVisitor mv, ScanReturn shape) {
    switch (shape) {
      case FAIL_PUSH_M1_INT_RETURN:
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IRETURN);
        break;
      case FAIL_PUSH_0_INT_RETURN:
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        break;
      case FAIL_PUSH_NULL_ARETURN:
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(ARETURN);
        break;
      default:
        throw new IllegalStateException(shape.toString());
    }
  }

  private void emitSuccessShape(
      MethodVisitor mv, EmitCtx ctx, int pVar, int posVar, int boundsVar, ScanReturn shape) {
    switch (shape) {
      case INT_RETURN_POSITION:
        mv.visitVarInsn(ILOAD, pVar);
        mv.visitInsn(IRETURN);
        break;
      case BOOL_BOUNDS:
        mv.visitVarInsn(ALOAD, boundsVar);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ILOAD, pVar);
        mv.visitInsn(IASTORE);
        mv.visitVarInsn(ALOAD, boundsVar);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitInsn(IASTORE);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        break;
      case MATCH_RESULT:
        emitBuildResult(ctx, false, pVar);
        break;
      default:
        throw new IllegalStateException(shape.toString());
    }
  }

  /**
   * Emits {@code new MatchResultImpl(input, starts, ends, groupCount); return}. Group 0 spans [0|p,
   * pos]; groups 1..n from the capture slots.
   *
   * @param matchStartIsZero true for match(): the try started at absolute 0 (push constant)
   * @param pVar scan start slot for find-family results (-1 when matchStartIsZero)
   */
  private void emitBuildResult(EmitCtx ctx, boolean matchStartIsZero, int pVar) {
    MethodVisitor mv = ctx.mv;
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, ctx.inputVar);

    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    if (matchStartIsZero) {
      mv.visitInsn(ICONST_0);
    } else {
      mv.visitVarInsn(ILOAD, pVar);
    }
    mv.visitInsn(IASTORE);
    for (int g = 1; g <= groupCount; g++) {
      mv.visitInsn(DUP);
      pushInt(mv, g);
      mv.visitVarInsn(ILOAD, ctx.capStart[g]);
      mv.visitInsn(IASTORE);
    }

    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitInsn(IASTORE);
    for (int g = 1; g <= groupCount; g++) {
      mv.visitInsn(DUP);
      pushInt(mv, g);
      mv.visitVarInsn(ILOAD, ctx.capEnd[g]);
      mv.visitInsn(IASTORE);
    }

    pushInt(mv, groupCount);
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/MatchResultImpl",
        "<init>",
        "(Ljava/lang/String;[I[II)V",
        false);
    mv.visitInsn(ARETURN);
  }

  // =====================================================================================
  // End-anchor ($) check.
  // =====================================================================================

  /**
   * Emits the {@code $} check on the try-end position, jumping to {@code failTarget} when the match
   * end is not a valid {@code $} position: non-multiline = region end only; multiline = region end
   * or immediately before a line terminator (LF, CR, NEL, LS, PS — the JDK's set).
   */
  private void emitEndAnchorCheck(EmitCtx ctx, Label failTarget, boolean multilineEnd) {
    MethodVisitor mv = ctx.mv;
    Label ok = new Label();
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPEQ, ok);
    if (multilineEnd) {
      // pos < len here: check charAt(pos) against the JDK line-terminator set.
      mv.visitVarInsn(ALOAD, ctx.inputVar);
      mv.visitVarInsn(ILOAD, ctx.posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, ctx.cVar);
      int[] terminators = {'\n', '\r', '\u0085', '\u2028', '\u2029'};
      for (int t : terminators) {
        mv.visitVarInsn(ILOAD, ctx.cVar);
        pushInt(mv, t);
        mv.visitJumpInsn(IF_ICMPEQ, ok);
      }
    }
    mv.visitJumpInsn(GOTO, failTarget);
    mv.visitLabel(ok);
  }

  // =====================================================================================
  // Chain try emission.
  // =====================================================================================

  /**
   * One level of the continuation a lazy loop's tail walks through when the loop is the last
   * element of its seq: the elements of {@code seq} from {@code nextIdx} (with the seq's closing
   * groups written at its end), and then — when {@code jump} is set — an immediate jump instead of
   * continuing to the next level. A {@code seq == null} level is a pure jump (the OPT boundary: a
   * matched optional completes by jumping to its optDone, beyond which the enclosing emission
   * continues in the main flow).
   */
  private static final class TailLevel {
    final DeterministicChainInfo.ChainSeq seq; // null for a pure jump level
    final int nextIdx;
    final List<Integer> closingAtEnd;
    final Label jump;

    TailLevel(
        DeterministicChainInfo.ChainSeq seq, int nextIdx, List<Integer> closingAtEnd, Label jump) {
      this.seq = seq;
      this.nextIdx = nextIdx;
      this.closingAtEnd = closingAtEnd;
      this.jump = jump;
    }

    static TailLevel jump(Label label) {
      return new TailLevel(null, 0, List.of(), label);
    }
  }

  /**
   * Emits the straight-line try of {@code seq}'s elements from index {@code from} to the end,
   * advancing {@code posVar} as elements consume. On element failure jumps to {@code failLabel}
   * (the try leaves no other state: greedy loops never give back, OPT/LIT_ALT restore the position
   * themselves, and capture slots are rewritten by the next try).
   *
   * <p>{@code closingAtEnd} lists capture groups whose <em>end</em> slot must be written at this
   * seq's completion position — by this emitter when the last element is a plain consumer, or by
   * the terminator elements otherwise (LAZY_LOOP writes per scan position; OPT writes on the skip
   * path and delegates on the matched path).
   *
   * <p>{@code contLevels} is the continuation after this seq: what a LAZY_LOOP at this seq's end
   * must emit as its tail (see {@link #emitLazyLoop}).
   *
   * @return true when a lazy loop's tail consumed this seq's continuation — the enclosing emitter
   *     must then stop emitting elements (everything through the continuation is already inline in
   *     the loop's tail) and skip its own follow-up writes (correct values were written by the
   *     tail's walk or the loop's scanTop writes).
   */
  private boolean emitChainTry(
      EmitCtx ctx,
      DeterministicChainInfo.ChainSeq seq,
      int from,
      List<Integer> closingAtEnd,
      Label failLabel,
      List<TailLevel> contLevels) {
    List<DeterministicChainInfo.ChainElem> elems = seq.elems;
    for (int i = from; i < elems.size(); i++) {
      DeterministicChainInfo.ChainElem e = elems.get(i);
      boolean isLast = i == elems.size() - 1;
      // Failure target for this element: the innermost enclosing LIT_ALT/OPT retry, else the
      // region's base — JDK backtracks through the bounded alternatives on any downstream failure.
      Label elemFail = effFail(ctx, failLabel);
      switch (e.kind) {
        case LITERAL:
          emitLiteral(ctx, e.literal, elemFail);
          break;
        case CLASS1:
          emitClass1(ctx, e.charSet, elemFail);
          break;
        case GREEDY_LOOP:
          emitGreedyLoop(ctx, e.charSet, e.min, elemFail);
          break;
        case LIT_ALT:
          // The alt machinery consumed the seq rest (and the continuation, when covered).
          return emitLitAlt(ctx, e, seq, i, closingAtEnd, contLevels, elemFail);
        case CAPTURE:
          if (emitCapture(ctx, e, seq, i, isLast, closingAtEnd, contLevels, elemFail)) {
            return true;
          }
          break;
        case OPT:
          // The opt machinery consumed the seq rest (and the continuation, when covered).
          return emitOpt(ctx, e, seq, i, isLast, closingAtEnd, contLevels, elemFail);
        case LAZY_LOOP:
          emitLazyLoop(ctx, e, seq, i, closingAtEnd, contLevels, elemFail);
          return true; // the loop's tail walk consumed the rest of the seq (and beyond)
        default:
          throw new IllegalStateException("v3: unexpected element " + e.kind);
      }
    }
    emitEndWriteIfPlain(ctx, seq, closingAtEnd);
    return false;
  }

  /**
   * Writes the {@code closingAtEnd} groups' end slots at the current position — only when the seq's
   * last element is a plain consumer (literal/class/greedy-loop/lit-alt); the terminator elements
   * (capture/opt) write them on their own paths (a LAZY_LOOP at seq end writes per scan position;
   * the OPT's rest emission writes via its child closingAtEnd on the matched path and the skip path
   * writes at the restored position).
   */
  private void emitEndWriteIfPlain(
      EmitCtx ctx, DeterministicChainInfo.ChainSeq seq, List<Integer> closingAtEnd) {
    if (closingAtEnd.isEmpty()) {
      return;
    }
    if (seq == null || seq.elems.isEmpty()) {
      return;
    }
    DeterministicChainInfo.ChainElem last = seq.elems.get(seq.elems.size() - 1);
    switch (last.kind) {
      case LITERAL:
      case CLASS1:
      case GREEDY_LOOP:
      case LIT_ALT:
        for (int g : closingAtEnd) {
          ctx.mv.visitVarInsn(ILOAD, ctx.posVar);
          ctx.mv.visitVarInsn(ISTORE, ctx.capEnd[g]);
        }
        break;
      default:
        break; // terminator element already wrote the closing groups
    }
  }

  /**
   * Capture: start slot at entry, nested seq, end slot at nested completion. The nested emission's
   * continuation is the rest of the parent seq (then the parent's own continuation), so a lazy loop
   * inside the capture scans across the capture boundary. On the consumed path the wrapper skips
   * its end write (the correct value was already written at the right position by the tail's walk
   * or the loop's scanTop writes).
   */
  private boolean emitCapture(
      EmitCtx ctx,
      DeterministicChainInfo.ChainElem e,
      DeterministicChainInfo.ChainSeq parent,
      int parentIdx,
      boolean isLast,
      List<Integer> closingAtEnd,
      List<TailLevel> contLevels,
      Label failLabel) {
    MethodVisitor mv = ctx.mv;
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ISTORE, ctx.capStart[e.groupNumber]);
    List<Integer> childClosing =
        isLast ? append(closingAtEnd, e.groupNumber) : List.of(e.groupNumber);
    List<TailLevel> childCont =
        prepend(new TailLevel(parent, parentIdx + 1, closingAtEnd, null), contLevels);
    boolean consumed = emitChainTry(ctx, e.nested, 0, childClosing, failLabel, childCont);
    if (!consumed) {
      mv.visitVarInsn(ILOAD, ctx.posVar);
      mv.visitVarInsn(ISTORE, ctx.capEnd[e.groupNumber]);
    }
    return consumed;
  }

  /**
   * LIT_ALT: bounded sequential tries in JDK priority order with downstream backtracking — an
   * alternative is committed only when the REST of the chain also matches; a downstream failure
   * restores the position and tries the next alternative (JDK {@code (a|ab|abc)(1|12|123)} on "a12"
   * matches "a"+"12" exactly this way). The rest is emitted once after the alternative bodies: each
   * successful alternative jumps to it, and its failures flow to {@code downstreamFail} which
   * retries the dispatcher with the next index.
   *
   * <p>The dispatcher compares a runtime alt-index (bounded: alternatives ≤ MAX_CHAIN_LIT_ALT=16),
   * so the rest is never duplicated — code size stays linear in the chain.
   */
  private boolean emitLitAlt(
      EmitCtx ctx,
      DeterministicChainInfo.ChainElem e,
      DeterministicChainInfo.ChainSeq seq,
      int i,
      List<Integer> closingAtEnd,
      List<TailLevel> contLevels,
      Label failLabel) {
    MethodVisitor mv = ctx.mv;
    // Exhausted alternatives fail the enclosing retry chain (captured before this element sets its
    // own retry label).
    Label upstream = effFail(ctx, failLabel);
    int backupVar = ctx.alloc.allocate();
    int altVar = ctx.alloc.allocate();
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ISTORE, backupVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, altVar);

    int n = e.literals.size();
    Label retryLoop = new Label();
    Label downstreamFail = new Label();
    Label restStart = new Label();
    Label[] altLabels = new Label[n];
    for (int k = 0; k < n; k++) {
      altLabels[k] = new Label();
    }

    // Dispatcher: altVar in [0, n) → alternative body; beyond → upstream failure.
    mv.visitLabel(retryLoop);
    for (int k = 0; k < n; k++) {
      mv.visitVarInsn(ILOAD, altVar);
      pushInt(mv, k);
      mv.visitJumpInsn(IF_ICMPEQ, altLabels[k]);
    }
    mv.visitJumpInsn(GOTO, upstream);

    // Alternative bodies: try the literal, jump to the (shared) rest on success.
    for (int k = 0; k < n; k++) {
      mv.visitLabel(altLabels[k]);
      emitLiteral(ctx, e.literals.get(k), downstreamFail);
      mv.visitJumpInsn(GOTO, restStart);
    }

    // Downstream failure: restore the position, next alternative, redispatch.
    mv.visitLabel(downstreamFail);
    mv.visitVarInsn(ILOAD, backupVar);
    mv.visitVarInsn(ISTORE, ctx.posVar);
    mv.visitIincInsn(altVar, 1);
    mv.visitJumpInsn(GOTO, retryLoop);

    // The rest of the seq (then the continuation levels), emitted once; failures retry the next
    // alternative. retryFail stays downstreamFail: the retry remains valid for everything after.
    mv.visitLabel(restStart);
    ctx.retryFail = downstreamFail;
    return emitTailWalk(
        ctx, prepend(new TailLevel(seq, i + 1, closingAtEnd, null), contLevels), downstreamFail);
  }

  /**
   * OPT: greedy two-attempt with downstream backtracking. With-prefix first; if the nested chain
   * fails, or a downstream failure occurs after a successful with-prefix try, restart from the
   * element start without the prefix (JDK {@code (?:a)?a} on "a" matches via the skip path). The
   * two paths share one emission of the rest via a runtime path flag: rest failures re-enter the
   * opt-retry block, which routes to the skip path once and to the upstream retry chain after.
   */
  private boolean emitOpt(
      EmitCtx ctx,
      DeterministicChainInfo.ChainElem e,
      DeterministicChainInfo.ChainSeq seq,
      int i,
      boolean isLast,
      List<Integer> closingAtEnd,
      List<TailLevel> contLevels,
      Label failLabel) {
    MethodVisitor mv = ctx.mv;
    // Both paths exhausted → the enclosing retry chain (captured before setting the opt retry).
    Label upstream = effFail(ctx, failLabel);
    int backupVar = ctx.alloc.allocate();
    int skipFlagVar = ctx.alloc.allocate();
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ISTORE, backupVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, skipFlagVar);

    Label skipStart = new Label();
    Label optWithDone = new Label();
    Label restStart = new Label();
    Label optRetry = new Label();
    Label optDone = new Label();

    // Nested try (its failures skip directly); a lazy loop inside the nested scans across the
    // boundary and jumps to optWithDone (the with-path completion) via the continuation jump.
    // The skip path is the immediate retry for the nested region, so the outer retry chain is
    // suspended here (the optRetry below re-links it as the exhausted-skip upstream).
    Label savedRetry = ctx.retryFail;
    ctx.retryFail = null;
    emitChainTry(
        ctx,
        e.nested,
        0,
        isLast ? closingAtEnd : List.of(),
        skipStart,
        List.of(TailLevel.jump(optWithDone)));
    ctx.retryFail = savedRetry;
    mv.visitJumpInsn(GOTO, optWithDone);

    // Skip path: restore the position, mark the path, run the (same) rest.
    mv.visitLabel(skipStart);
    mv.visitVarInsn(ILOAD, backupVar);
    mv.visitVarInsn(ISTORE, ctx.posVar);
    if (isLast) {
      // The OPT is the seq's last element: the closing groups end at the restored position.
      for (int g : closingAtEnd) {
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitVarInsn(ISTORE, ctx.capEnd[g]);
      }
    }
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ISTORE, skipFlagVar);
    mv.visitJumpInsn(GOTO, restStart);

    mv.visitLabel(optWithDone);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, skipFlagVar);

    // The rest of the seq (then the continuation levels), emitted once; failures re-enter
    // optRetry. retryFail stays optRetry: the retry remains valid for everything after.
    mv.visitLabel(restStart);
    ctx.retryFail = optRetry;
    boolean covered =
        emitTailWalk(
            ctx, prepend(new TailLevel(seq, i + 1, closingAtEnd, null), contLevels), optRetry);
    // The rest matched: done. Only rest failures jump to the optRetry block below.
    mv.visitJumpInsn(GOTO, optDone);

    // optRetry: with-path failure → skip path (once); skip-path failure → upstream retry chain.
    mv.visitLabel(optRetry);
    mv.visitVarInsn(ILOAD, skipFlagVar);
    mv.visitJumpInsn(IFNE, upstream);
    mv.visitJumpInsn(GOTO, skipStart);
    mv.visitLabel(optDone);
    return covered;
  }

  /**
   * LAZY_LOOP: scan loop. At each position try the tail — the rest of the current seq and then the
   * continuation levels (the tail crosses capture/OPT boundaries into the enclosing seqs). A
   * failing tail restores the position, consumes one loop-class char, and retries; a char outside
   * the loop class fails the chain (through the retry chain). When the loop is the seq's last
   * element the seq's closing groups end at the loop stop, so their end slots are written at every
   * scan position (last write wins).
   *
   * <p>A SUCCESSFUL tail try is accepted only when the method's lazy predicate holds ({@link
   * LazyPredicate}): find() accepts the first success; matches()/$-anchored scans require the tail
   * end to be the input end / a valid $ position — otherwise the scan continues (one more loop
   * char), which is exactly JDK's lazy backtracking, linearly.
   */
  private void emitLazyLoop(
      EmitCtx ctx,
      DeterministicChainInfo.ChainElem e,
      DeterministicChainInfo.ChainSeq seq,
      int i,
      List<Integer> closingAtEnd,
      List<TailLevel> contLevels,
      Label failLabel) {
    MethodVisitor mv = ctx.mv;
    boolean loopIsSeqEnd = i == seq.elems.size() - 1;
    int backupVar = ctx.alloc.allocate();
    Label scanTop = new Label();
    Label tailFail = new Label();
    Label loopDone = new Label();
    mv.visitLabel(scanTop);
    if (loopIsSeqEnd) {
      for (int g : closingAtEnd) {
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitVarInsn(ISTORE, ctx.capEnd[g]);
      }
    }
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ISTORE, backupVar);
    emitTailWalk(ctx, prepend(new TailLevel(seq, i + 1, closingAtEnd, null), contLevels), tailFail);
    switch (ctx.lazyPredicate) {
      case NONE:
        break;
      case POS_EQ_LEN:
        mv.visitVarInsn(ILOAD, ctx.posVar);
        mv.visitVarInsn(ILOAD, ctx.lenVar);
        mv.visitJumpInsn(IF_ICMPNE, tailFail);
        break;
      case END_ANCHOR:
        emitEndAnchorCheck(ctx, tailFail, ctx.lazyMultiline);
        break;
    }
    mv.visitJumpInsn(GOTO, loopDone);
    mv.visitLabel(tailFail);
    // Restore, then advance one loop-class char or fail the chain.
    mv.visitVarInsn(ILOAD, backupVar);
    mv.visitVarInsn(ISTORE, ctx.posVar);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPGE, failLabel);
    emitCharAt(ctx, ctx.posVar, ctx.cVar);
    emitCharSetCheck(ctx, e.charSet, ctx.cVar, failLabel);
    mv.visitIincInsn(ctx.posVar, 1);
    mv.visitJumpInsn(GOTO, scanTop);
    mv.visitLabel(loopDone);
  }

  /**
   * Emits the tail try: each level's remaining elements in order, the level's closing writes at its
   * seq end, and either the level's jump or the next level.
   *
   * @return true when the walk covered all levels (control flows to the end of the last level, or a
   *     deeper lazy loop's own walk consumed the remaining levels); false when it stopped at a jump
   *     level (an OPT boundary — the enclosing OPT's main flow continues after the jump).
   */
  private boolean emitTailWalk(EmitCtx ctx, List<TailLevel> levels, Label failLabel) {
    for (int li = 0; li < levels.size(); li++) {
      TailLevel lvl = levels.get(li);
      if (lvl.seq == null) {
        ctx.mv.visitJumpInsn(GOTO, lvl.jump);
        return false;
      }
      boolean consumed =
          emitChainTry(
              ctx,
              lvl.seq,
              lvl.nextIdx,
              lvl.closingAtEnd,
              failLabel,
              levels.subList(li + 1, levels.size()));
      if (consumed) {
        return true;
      }
      if (lvl.jump != null) {
        ctx.mv.visitJumpInsn(GOTO, lvl.jump);
        return false;
      }
    }
    return true;
  }

  private static List<TailLevel> prepend(TailLevel head, List<TailLevel> rest) {
    List<TailLevel> out = new ArrayList<>(rest.size() + 1);
    out.add(head);
    out.addAll(rest);
    return out;
  }

  /**
   * LIT_ALT: sequential tries in priority order, position restored between tries (a literal
   * consumes as it matches, so a partial match must be undone before the next try). All literals
   * fail ⇒ the chain fails.
   */
  private void emitLitAlt(EmitCtx ctx, List<String> literals, Label failLabel) {
    MethodVisitor mv = ctx.mv;
    int backupVar = ctx.alloc.allocate();
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ISTORE, backupVar);
    Label done = new Label();
    for (String alt : literals) {
      Label altFail = new Label();
      emitLiteral(ctx, alt, altFail);
      mv.visitJumpInsn(GOTO, done);
      mv.visitLabel(altFail);
      mv.visitVarInsn(ILOAD, backupVar);
      mv.visitVarInsn(ISTORE, ctx.posVar);
    }
    mv.visitJumpInsn(GOTO, failLabel);
    mv.visitLabel(done);
  }

  // =====================================================================================
  // Leaf element emission.
  // =====================================================================================

  /** Emits an unrolled literal match: bounds check once, then one charAt compare per char. */
  private void emitLiteral(EmitCtx ctx, String literal, Label failLabel) {
    MethodVisitor mv = ctx.mv;
    // if (len - pos < literal.length()) fail;
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitInsn(ISUB);
    pushInt(mv, literal.length());
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
    for (int i = 0; i < literal.length(); i++) {
      emitCharAt(ctx, ctx.posVar, -1);
      pushInt(mv, literal.charAt(i));
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
      mv.visitIincInsn(ctx.posVar, 1);
    }
  }

  /** Emits a single char-class consume: bounds check, set check, advance. */
  private void emitClass1(EmitCtx ctx, CharSet cs, Label failLabel) {
    MethodVisitor mv = ctx.mv;
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPGE, failLabel);
    emitCharAt(ctx, ctx.posVar, ctx.cVar);
    emitCharSetCheck(ctx, cs, ctx.cVar, failLabel);
    mv.visitIincInsn(ctx.posVar, 1);
  }

  /**
   * Emits the greedy no-give-back loop: consume the maximal run, then check min (0 or 1). The
   * detector's disjointness admission guarantees the loop class and the rest of the chain share no
   * first char, so the maximal run is the only candidate — no position restore, no give-back.
   */
  private void emitGreedyLoop(EmitCtx ctx, CharSet cs, int min, Label failLabel) {
    MethodVisitor mv = ctx.mv;
    int runStartVar = ctx.alloc.allocate();
    if (min > 0) {
      mv.visitVarInsn(ILOAD, ctx.posVar);
      mv.visitVarInsn(ISTORE, runStartVar);
    }
    Label loopStart = new Label();
    Label loopEnd = new Label();
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, ctx.posVar);
    mv.visitVarInsn(ILOAD, ctx.lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    emitCharAt(ctx, ctx.posVar, ctx.cVar);
    emitCharSetCheck(ctx, cs, ctx.cVar, loopEnd);
    mv.visitIincInsn(ctx.posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);
    if (min > 0) {
      mv.visitVarInsn(ILOAD, ctx.posVar);
      mv.visitVarInsn(ILOAD, runStartVar);
      mv.visitInsn(ISUB);
      pushInt(mv, min);
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
    }
  }

  /**
   * Emits {@code input.charAt(pos)} — into {@code cVar} when ≥ 0, else leaves the char on stack.
   */
  private void emitCharAt(EmitCtx ctx, int posVar, int cVar) {
    MethodVisitor mv = ctx.mv;
    mv.visitVarInsn(ALOAD, ctx.inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    if (cVar >= 0) {
      mv.visitVarInsn(ISTORE, cVar);
    }
  }

  /**
   * Emits an unrolled check of the char in {@code cVar} against {@code cs} (already
   * negation-resolved), jumping to {@code failLabel} when the char is NOT in the set: single char,
   * single range, or unrolled multi-range.
   */
  private void emitCharSetCheck(EmitCtx ctx, CharSet cs, int cVar, Label failLabel) {
    MethodVisitor mv = ctx.mv;
    if (cs.isSingleChar()) {
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, cs.getSingleChar());
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
    } else if (cs.isSimpleRange()) {
      CharSet.Range range = cs.getSimpleRange();
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, range.start);
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, range.end);
      mv.visitJumpInsn(IF_ICMPGT, failLabel);
    } else {
      Label inSet = new Label();
      for (CharSet.Range range : cs.getRanges()) {
        Label tryNext = new Label();
        mv.visitVarInsn(ILOAD, cVar);
        pushInt(mv, range.start);
        mv.visitJumpInsn(IF_ICMPLT, tryNext);
        mv.visitVarInsn(ILOAD, cVar);
        pushInt(mv, range.end);
        mv.visitJumpInsn(IF_ICMPLE, inSet);
        mv.visitLabel(tryNext);
      }
      mv.visitJumpInsn(GOTO, failLabel);
      mv.visitLabel(inSet);
    }
  }

  // =====================================================================================
  // Scan gate.
  // =====================================================================================

  /** Converts an ASCII bitmap to maximal [lo, hi] runs for unrolled gate checks. */
  private static List<int[]> firstSetRuns(boolean[] firstSetAscii) {
    List<int[]> runs = new ArrayList<>();
    int i = 0;
    while (i < firstSetAscii.length) {
      if (!firstSetAscii[i]) {
        i++;
        continue;
      }
      int lo = i;
      while (i < firstSetAscii.length && firstSetAscii[i]) {
        i++;
      }
      runs.add(new int[] {lo, i - 1});
    }
    return runs;
  }

  /**
   * Emits an unrolled membership test of the char in {@code cVar} against [lo, hi] runs, jumping to
   * {@code notInRuns} when no run contains it.
   */
  private static void emitCharInRuns(
      MethodVisitor mv, int cVar, List<int[]> runs, Label notInRuns) {
    if (runs.isEmpty()) {
      mv.visitJumpInsn(GOTO, notInRuns);
      return;
    }
    Label inRuns = new Label();
    for (int[] run : runs) {
      Label tryNext = new Label();
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, run[0]);
      mv.visitJumpInsn(IF_ICMPLT, tryNext);
      mv.visitVarInsn(ILOAD, cVar);
      pushInt(mv, run[1]);
      mv.visitJumpInsn(IF_ICMPLE, inRuns);
      mv.visitLabel(tryNext);
    }
    mv.visitJumpInsn(GOTO, notInRuns);
    mv.visitLabel(inRuns);
  }

  private static List<Integer> append(List<Integer> base, int g) {
    List<Integer> out = new ArrayList<>(base.size() + 1);
    out.add(g);
    out.addAll(base);
    return out;
  }
}
