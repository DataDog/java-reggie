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
 * <h3>Stage 2 scope (v1)</h3>
 *
 * A single chain branch with no anchors and only {@code LITERAL}, {@code CLASS1} and {@code
 * GREEDY_LOOP} elements — the {@code [a-z]+@} / {@code //[^@]+@} / {@code ab*c} shapes. Each
 * consuming element compiles to an unrolled {@code input.charAt(pos) == c} comparison exactly like
 * JDK's compiled nodes; a greedy loop compiles to a tight scan loop with no give-back (the
 * detector's disjointness admission guarantees the maximal run is the only candidate, so the loop
 * never restores positions).
 *
 * <pre>{@code
 * // matches(), chain [a-z]+@ against "user@host":
 * int pos = 0, len = input.length();
 * while (pos < len && 'a' <= c && c <= 'z') pos++;   // loop, min 1
 * if (pos - 0 < 1) return false;
 * if (len - pos < 1) return false;
 * if (input.charAt(pos) != '@') return false;
 * pos++;
 * return pos == len;
 * }</pre>
 *
 * <p>{@code findFrom} scans positions through a first-consumed-char gate (an unrolled range check
 * over the branch's first-set — a match starting at {@code p} always consumes {@code charAt(p)}
 * from that set, so the gate skips non-candidate positions in O(1)) and tries the chain from each
 * gate hit.
 *
 * <h3>Not yet in v1</h3>
 *
 * Captures, {@code LIT_ALT}/{@code OPT}/{@code LAZY_LOOP} elements, {@code ^}/{@code $} anchors,
 * alternation of branches, and the find() work budget + PikeVM fallback (design doc §4) — so a
 * gate-passing but failing try re-consumes its run at every following position and {@code find} can
 * be O(n²) on adversarial inputs (e.g. {@code //[^@]+@} against {@code "/////…"}). The strategy is
 * not wired into routing until stage 5, so no user-facing pattern can reach this.
 *
 * <p>Zero allocation on the boolean paths: all state is int locals.
 */
public class DeterministicChainBytecodeGenerator {

  /** The single (v1) chain branch, admission-checked by the constructor. */
  private final DeterministicChainInfo.ChainBranch branch;

  /** Capturing groups in the pattern; 0 in v1 (CAPTURE elements are rejected below). */
  private final int groupCount;

  public DeterministicChainBytecodeGenerator(DeterministicChainInfo info, int groupCount) {
    if (info.branches.size() != 1) {
      throw new IllegalArgumentException(
          "stage 2 generator handles a single chain branch, got " + info.branches.size());
    }
    DeterministicChainInfo.ChainBranch b = info.branches.get(0);
    if (b.startAnchored || b.endAnchored || b.multilineEnd) {
      throw new IllegalArgumentException("stage 2 generator handles no anchors");
    }
    for (DeterministicChainInfo.ChainElem e : b.seq.elems) {
      switch (e.kind) {
        case LITERAL:
        case CLASS1:
        case GREEDY_LOOP:
          break;
        default:
          throw new IllegalArgumentException("stage 2 generator handles no " + e.kind + " element");
      }
    }
    this.branch = b;
    this.groupCount = groupCount;
  }

  /**
   * Generates {@code matches(String)}: one anchored try from position 0, success iff the try
   * consumed the whole input. Single try ⇒ O(n) by construction, no budget needed (design doc §4).
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int inputVar = 1;
    int posVar = allocator.allocate();
    int lenVar = allocator.allocate();
    int cVar = allocator.allocate();
    int runStartVar = allocator.allocate();

    // if (input == null) return false;
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

    Label fail = new Label();
    emitChainTry(mv, branch.seq, inputVar, lenVar, posVar, cVar, runStartVar, fail);

    // Whole-input semantics: the try succeeded iff it consumed every char.
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPNE, fail);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(fail);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

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
   * Generates {@code findFrom(String, int)}: position scan gated by the branch first-set, chain try
   * from each gate hit, returning the match start of the first success. See the class javadoc for
   * the v1 linearity caveat (budget/fallback arrive in stage 4).
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    LocalVarAllocator allocator = new LocalVarAllocator(3);
    int inputVar = 1;
    int startVar = 2;
    int lenVar = allocator.allocate();
    int pVar = allocator.allocate();
    int posVar = allocator.allocate();
    int cVar = allocator.allocate();
    int runStartVar = allocator.allocate();

    // if (input == null || start < 0 || start > input.length()) return -1;
    Label checksPass = new Label();
    Label returnMinusOne = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNULL, returnMinusOne);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IFLT, returnMinusOne);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitJumpInsn(IF_ICMPLT, returnMinusOne);
    mv.visitJumpInsn(GOTO, checksPass);
    mv.visitLabel(returnMinusOne);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
    mv.visitLabel(checksPass);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int p = start;
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitVarInsn(ISTORE, pVar);

    List<int[]> firstSetRuns = firstSetRuns(branch.firstSetAscii);
    Label scanLoop = new Label();
    Label scanEnd = new Label();
    Label gateFail = new Label();
    Label tryFail = new Label();

    mv.visitLabel(scanLoop);
    // while (p <= len - minWidth) — a match consumes minWidth chars from p at minimum.
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ILOAD, lenVar);
    pushInt(mv, branch.minWidth);
    mv.visitInsn(ISUB);
    mv.visitJumpInsn(IF_ICMPGT, scanEnd);

    // First-set gate: charAt(p) must lie in the branch first-set (necessary condition).
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, cVar);
    emitCharInRuns(mv, cVar, firstSetRuns, gateFail);

    // Try the chain from p (pos is the working scan cursor; p stays the match start).
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitVarInsn(ISTORE, posVar);
    emitChainTry(mv, branch.seq, inputVar, lenVar, posVar, cVar, runStartVar, tryFail);
    mv.visitVarInsn(ILOAD, pVar);
    mv.visitInsn(IRETURN);

    mv.visitLabel(tryFail);
    mv.visitLabel(gateFail);
    mv.visitIincInsn(pVar, 1);
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(scanEnd);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  // =====================================================================================
  // Chain try emission (shared by matches and findFrom).
  // =====================================================================================

  /**
   * Emits the straight-line try of {@code seq} from the position in {@code posVar}, advancing
   * {@code posVar} as elements consume. On element failure jumps to {@code failLabel} (the try
   * leaves no other state — greedy loops never give back, so a failed try just falls to the
   * caller's fail path). {@code cVar} is scratch for the current char, {@code runStartVar} scratch
   * for a greedy loop's run start (min check).
   */
  private void emitChainTry(
      MethodVisitor mv,
      DeterministicChainInfo.ChainSeq seq,
      int inputVar,
      int lenVar,
      int posVar,
      int cVar,
      int runStartVar,
      Label failLabel) {
    for (DeterministicChainInfo.ChainElem e : seq.elems) {
      switch (e.kind) {
        case LITERAL:
          emitLiteral(mv, e.literal, inputVar, lenVar, posVar, failLabel);
          break;
        case CLASS1:
          // if (pos >= len) fail;
          mv.visitVarInsn(ILOAD, posVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitJumpInsn(IF_ICMPGE, failLabel);
          emitCharAt(mv, inputVar, posVar, cVar);
          emitCharSetCheck(mv, e.charSet, cVar, failLabel);
          mv.visitIincInsn(posVar, 1);
          break;
        case GREEDY_LOOP:
          emitGreedyLoop(
              mv, e.charSet, e.min, inputVar, lenVar, posVar, runStartVar, cVar, failLabel);
          break;
        default:
          throw new IllegalStateException("stage 2: unexpected element " + e.kind);
      }
    }
  }

  /** Emits an unrolled literal match: bounds check once, then one charAt compare per char. */
  private void emitLiteral(
      MethodVisitor mv, String literal, int inputVar, int lenVar, int posVar, Label failLabel) {
    // if (len - pos < literal.length()) fail;
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(ISUB);
    pushInt(mv, literal.length());
    mv.visitJumpInsn(IF_ICMPLT, failLabel);
    for (int i = 0; i < literal.length(); i++) {
      emitCharAt(mv, inputVar, posVar, -1);
      pushInt(mv, literal.charAt(i));
      mv.visitJumpInsn(IF_ICMPNE, failLabel);
      mv.visitIincInsn(posVar, 1);
    }
  }

  /**
   * Emits the greedy no-give-back loop: consume the maximal run of chars from {@code cs}, then
   * check the min count (0 or 1). The detector's disjointness admission guarantees the loop class
   * and the rest of the chain share no first char, so the maximal run is the only candidate — no
   * position restore, no give-back.
   */
  private void emitGreedyLoop(
      MethodVisitor mv,
      CharSet cs,
      int min,
      int inputVar,
      int lenVar,
      int posVar,
      int runStartVar,
      int cVar,
      Label failLabel) {
    if (min > 0) {
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, runStartVar);
    }
    Label loopStart = new Label();
    Label loopEnd = new Label();
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    emitCharAt(mv, inputVar, posVar, cVar);
    emitCharSetCheck(mv, cs, cVar, loopEnd); // char not in loop class → run ends
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);
    if (min > 0) {
      // if (pos - runStart < min) fail;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, runStartVar);
      mv.visitInsn(ISUB);
      pushInt(mv, min);
      mv.visitJumpInsn(IF_ICMPLT, failLabel);
    }
  }

  /** Emits {@code input.charAt(pos)} leaving the char on the stack. */
  private void emitCharAt(MethodVisitor mv, int inputVar, int posVar, int cVar) {
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    if (cVar >= 0) {
      mv.visitVarInsn(ISTORE, cVar);
    }
  }

  /**
   * Emits an unrolled check of the char in {@code cVar} against {@code cs} (already
   * negation-resolved), jumping to {@code failLabel} when the char is NOT in the set. Same shape as
   * GreedyCharClassBytecodeGenerator's inline charset check: single char, single range, or unrolled
   * multi-range.
   */
  private void emitCharSetCheck(MethodVisitor mv, CharSet cs, int cVar, Label failLabel) {
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
}
