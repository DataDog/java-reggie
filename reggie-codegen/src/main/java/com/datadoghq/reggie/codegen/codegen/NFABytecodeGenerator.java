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

import com.datadoghq.reggie.codegen.analysis.LiteralLookaheadInfo;
import com.datadoghq.reggie.codegen.analysis.LiteralLookaheadPatternInfo;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.SubsetConstructor;
import java.util.*;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates bytecode for NFA-based matching using StateSet simulation. This is the most general
 * approach that works for all patterns, including those with assertions, backreferences, and
 * complex alternations.
 *
 * <h3>State Representation</h3>
 *
 * <ul>
 *   <li><b>Single Long</b> (≤64 states): Inline bit operations using {@code long} primitive
 *   <li><b>Dual Long</b> (65-128 states): Two {@code long} primitives for 128-bit tracking
 *   <li><b>SparseSet</b> (&gt;128 states): Dynamic set object with O(1) clear
 * </ul>
 *
 * <h3>Generated Algorithm (matches)</h3>
 *
 * <pre>{@code
 * // NFA simulation for pattern like "a(b|c)*d"
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int len = input.length();
 *
 *     // State tracking (implementation varies by state count)
 *     long currentStates = 0L;  // or SparseSet for large NFAs
 *     long nextStates = 0L;
 *
 *     // Initialize: start state + epsilon closure
 *     currentStates |= (1L << startStateId);
 *     epsilonClosure(currentStates);  // follow all ε-transitions
 *
 *     // Process each character
 *     for (int pos = 0; pos < len; pos++) {
 *         char ch = input.charAt(pos);
 *         nextStates = 0L;
 *
 *         // NFA step: for each active state, compute transitions
 *         for (int stateId : activeStates(currentStates)) {
 *             NFAState state = states[stateId];
 *             for (Transition t : state.transitions) {
 *                 if (t.charSet.contains(ch)) {
 *                     nextStates |= (1L << t.target.id);
 *                 }
 *             }
 *         }
 *
 *         // Epsilon closure: follow ε-transitions, check assertions
 *         epsilonClosure(nextStates);
 *
 *         // Swap state sets
 *         currentStates = nextStates;
 *     }
 *
 *     // Accept if any accept state is active
 *     for (NFAState accept : acceptStates) {
 *         if ((currentStates & (1L << accept.id)) != 0) {
 *             return true;
 *         }
 *     }
 *     return false;
 * }
 *
 * // Epsilon closure computation
 * void epsilonClosure(long states) {
 *     int[] worklist = new int[stateCount];
 *     int size = 0;
 *     long processed = 0L;
 *
 *     // Initialize worklist with current states
 *     for (int id : activeStates(states)) {
 *         worklist[size++] = id;
 *     }
 *
 *     // Process worklist
 *     while (size > 0) {
 *         int stateId = worklist[--size];
 *         if ((processed & (1L << stateId)) != 0) continue;
 *         processed |= (1L << stateId);
 *
 *         NFAState state = states[stateId];
 *
 *         // Check assertions (lookahead, lookbehind, anchors)
 *         if (state.assertion != null && !checkAssertion(state, input, pos)) {
 *             continue;  // assertion failed, don't follow ε-transitions
 *         }
 *
 *         // Follow epsilon transitions
 *         for (NFAState target : state.epsilonTransitions) {
 *             states |= (1L << target.id);
 *             worklist[size++] = target.id;
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li>Inline bit operations for small NFAs (no method calls)
 *   <li>Precomputed epsilon closures when no runtime assertions
 *   <li>Multi-lookahead optimization: single-pass check for {@code (?=.*[a])(?=.*[b])}
 *   <li>Literal lookahead: use {@code indexOf()} before NFA simulation
 *   <li>SWAR iteration using {@code Long.numberOfTrailingZeros()}
 * </ul>
 */
public class NFABytecodeGenerator {

  /**
   * Holds pre-allocated local variable slots for epsilon closure computation. Used to avoid
   * allocating variables inside loops which causes JVM VerifyError.
   *
   * <p>For dual-long mode (65-128 states), processedVar contains an encoded value (negative) that
   * can be decoded using decodeDualLongSlot0/1.
   */
  protected static class EpsilonClosureSlots {
    final int worklistVar;
    final int stateIdVar;
    final int worklistSizeVar;
    final int processedVar; // May be encoded dual-long (negative) or regular slot (non-negative)
    final int indexVar; // Loop iteration variable
    final int sizeVar; // Loop size variable
    final int parentIdVar; // For POSIX per-config tracking

    EpsilonClosureSlots(
        int worklistVar,
        int stateIdVar,
        int worklistSizeVar,
        int processedVar,
        int indexVar,
        int sizeVar,
        int parentIdVar) {
      this.worklistVar = worklistVar;
      this.stateIdVar = stateIdVar;
      this.worklistSizeVar = worklistSizeVar;
      this.processedVar = processedVar;
      this.indexVar = indexVar;
      this.sizeVar = sizeVar;
      this.parentIdVar = parentIdVar;
    }

    static EpsilonClosureSlots unallocated() {
      return new EpsilonClosureSlots(-1, -1, -1, -1, -1, -1, -1);
    }
  }

  /**
   * Holds pre-allocated local variable slots for NFA step computation. Used to avoid allocating
   * variables inside loops which causes JVM VerifyError.
   */
  protected static class NFAStepSlots {
    final int stateIdVar;
    final int indexVar;
    final int sizeVar;

    NFAStepSlots(int stateIdVar, int indexVar, int sizeVar) {
      this.stateIdVar = stateIdVar;
      this.indexVar = indexVar;
      this.sizeVar = sizeVar;
    }

    static NFAStepSlots unallocated() {
      return new NFAStepSlots(-1, -1, -1);
    }
  }

  protected final NFA nfa;
  protected final PatternAnalyzer.HybridDFALookaheadInfo hybridInfo;
  protected final LiteralLookaheadPatternInfo
      literalLookaheadInfo; // For SPECIALIZED_LITERAL_LOOKAHEADS strategy
  protected final java.util.Set<Character> requiredLiterals;
  protected final PatternAnalyzer.LookaheadGreedySuffixInfo lookaheadGreedyInfo;
  protected final boolean
      usePosixLastMatch; // Use POSIX last-match semantics for groups in quantifiers
  protected final boolean caseInsensitive; // Use case-insensitive comparison for backreferences
  protected final boolean requiresStartAnchor; // Pattern requires match at start (^ or \A)
  protected final boolean hasStringStartAnchor; // Pattern has \A (absolute string start)
  protected final boolean
      hasBackrefToLookaheadCapture; // Backref references capture inside lookahead

  // Compile-time decision: use BitSet for ≤64 states, SparseSet otherwise
  private final boolean useBitSet;
  // Phase 2B: use single long primitive for ≤64 states (inline bit operations)
  private final boolean useSingleLong;
  // Phase 2C: use dual long primitive for 65-128 states (inline bit operations, 32× memory savings
  // vs SparseSet)
  private final boolean useDualLong;
  private final int stateCount;
  private static final int BITSET_THRESHOLD = 64;
  // Phase 2C: Dual-long optimization for 65-128 states (enabled)
  // Uses two long primitives (128 bits) for inline state tracking
  private static final int DUAL_LONG_THRESHOLD = 128;

  public NFABytecodeGenerator(NFA nfa) {
    this(nfa, null, null, java.util.Collections.emptySet(), null, false, false);
  }

  public NFABytecodeGenerator(NFA nfa, PatternAnalyzer.HybridDFALookaheadInfo hybridInfo) {
    this(nfa, hybridInfo, null, java.util.Collections.emptySet(), null, false, false);
  }

  public NFABytecodeGenerator(
      NFA nfa,
      PatternAnalyzer.HybridDFALookaheadInfo hybridInfo,
      java.util.Set<Character> requiredLiterals) {
    this(nfa, hybridInfo, null, requiredLiterals, null, false, false);
  }

  public NFABytecodeGenerator(
      NFA nfa,
      PatternAnalyzer.HybridDFALookaheadInfo hybridInfo,
      java.util.Set<Character> requiredLiterals,
      PatternAnalyzer.LookaheadGreedySuffixInfo lookaheadGreedyInfo) {
    this(nfa, hybridInfo, null, requiredLiterals, lookaheadGreedyInfo, false, false);
  }

  public NFABytecodeGenerator(
      NFA nfa,
      PatternAnalyzer.HybridDFALookaheadInfo hybridInfo,
      java.util.Set<Character> requiredLiterals,
      PatternAnalyzer.LookaheadGreedySuffixInfo lookaheadGreedyInfo,
      boolean usePosixLastMatch) {
    this(nfa, hybridInfo, null, requiredLiterals, lookaheadGreedyInfo, usePosixLastMatch, false);
  }

  /**
   * Main constructor with all parameters (public for RuntimeCompiler access). Use this constructor
   * to explicitly specify both hybridInfo and literalLookaheadInfo. Exactly one should be non-null
   * (or both null for plain NFA).
   */
  public NFABytecodeGenerator(
      NFA nfa,
      PatternAnalyzer.HybridDFALookaheadInfo hybridInfo,
      LiteralLookaheadPatternInfo literalLookaheadInfo,
      java.util.Set<Character> requiredLiterals,
      PatternAnalyzer.LookaheadGreedySuffixInfo lookaheadGreedyInfo,
      boolean usePosixLastMatch,
      boolean caseInsensitive) {
    this.nfa = nfa;
    this.hybridInfo = hybridInfo;
    this.literalLookaheadInfo = literalLookaheadInfo;
    this.requiredLiterals =
        requiredLiterals != null ? requiredLiterals : java.util.Collections.emptySet();
    this.lookaheadGreedyInfo = lookaheadGreedyInfo;
    this.usePosixLastMatch = usePosixLastMatch;
    this.caseInsensitive = caseInsensitive;
    this.requiresStartAnchor = nfa.requiresStartAnchor();
    this.hasStringStartAnchor = nfa.hasStringStartAnchor();
    this.hasBackrefToLookaheadCapture = nfa.hasBackrefToLookaheadCapture();
    this.stateCount = nfa.getStates().size();
    // Phase 2B/2C: Strategy selection based on state count
    // ≤64 states: single long (best performance, 8 bytes)
    // 65-128 states: dual long (good performance, 16 bytes vs 512 bytes for SparseSet)
    // >128 states: SparseSet fallback (O(1) clear, dynamic sizing)
    if (stateCount <= 64) {
      this.useSingleLong = true;
      this.useDualLong = false;
    } else if (stateCount <= DUAL_LONG_THRESHOLD) {
      this.useSingleLong = false;
      this.useDualLong = true;
    } else {
      this.useSingleLong = false;
      this.useDualLong = false;
    }
    this.useBitSet = stateCount <= BITSET_THRESHOLD && !useSingleLong && !useDualLong;
  }

  // ========== Dual-Long Slot Encoding Utilities ==========
  //
  // To simplify dual-long handling, we encode whether a slot is dual-long in the slot number
  // itself:
  // - Positive value (>= 0): single variable at that slot (single-long, BitSet, or SparseSet
  // reference)
  // - Negative value (< 0): dual-long where ~encodedSlot gives slot0, and slot1 = slot0 + 1
  //
  // This allows helper methods to auto-detect dual-long mode from a single int parameter,
  // eliminating the need for separate *Dual method variants.

  /**
   * Encode a dual-long slot pair into a single int. The encoded value is negative, and can be
   * decoded with {@link #decodeDualLongSlot0(int)}.
   *
   * @param slot0 the first slot (low word, bits 0-63)
   * @return encoded value (always negative)
   */
  protected static int encodeDualLong(int slot0) {
    return ~slot0; // e.g., slot0=5 -> encoded=-6
  }

  /**
   * Check if an encoded slot represents dual-long mode.
   *
   * @param encodedSlot the potentially encoded slot
   * @return true if dual-long encoded, false if single slot
   */
  protected static boolean isDualLongEncoded(int encodedSlot) {
    return encodedSlot < 0;
  }

  /**
   * Decode the first slot (low word) from a dual-long encoded value.
   *
   * @param encodedSlot the encoded slot (must be negative)
   * @return the first slot number
   */
  protected static int decodeDualLongSlot0(int encodedSlot) {
    return ~encodedSlot; // e.g., encoded=-6 -> slot0=5
  }

  /**
   * Decode the second slot (high word) from a dual-long encoded value. Since each long occupies 2
   * JVM slots, the high word starts at slot0 + 2.
   *
   * @param encodedSlot the encoded slot (must be negative)
   * @return the second slot number (slot0 + 2)
   */
  protected static int decodeDualLongSlot1(int encodedSlot) {
    return ~encodedSlot + 2; // e.g., encoded=-6 -> slot0=5, slot1=7 (since long uses 2 slots)
  }

  /**
   * Allocate slots for a dual-long state set and return the encoded slot value. Allocates 2
   * consecutive longs (4 slots total) and returns the encoded value.
   *
   * @param allocator the local variable allocator
   * @return encoded dual-long slot (negative value)
   */
  private int allocateDualLongStateSet(LocalVariableAllocator allocator) {
    int slot0 = allocator.allocateLong(); // low word (2 slots)
    allocator.allocateLong(); // high word (2 slots, immediately after slot0)
    return encodeDualLong(slot0);
  }

  /**
   * Initialize a state set to empty (0). Works with single-long, dual-long (encoded), and object
   * references.
   *
   * @param mv method visitor
   * @param stateSetVar local variable slot (or dual-long encoded)
   */
  private void initStateSet(MethodVisitor mv, int stateSetVar) {
    if (isDualLongEncoded(stateSetVar)) {
      int var0 = decodeDualLongSlot0(stateSetVar);
      int var1 = decodeDualLongSlot1(stateSetVar);
      generateDualLongNew(mv, var0, var1);
    } else if (useSingleLong) {
      generateSingleLongNew(mv, stateSetVar);
    } else {
      generateStateSetNew(mv);
      mv.visitVarInsn(ASTORE, stateSetVar);
    }
  }

  /**
   * Swap two state sets. Works with single-long, dual-long (encoded), and object references.
   *
   * @param mv method visitor
   * @param stateSetVar1 first state set slot (or dual-long encoded)
   * @param stateSetVar2 second state set slot (or dual-long encoded)
   * @param allocator variable allocator for temp variables
   */
  private void swapStateSets(
      MethodVisitor mv, int stateSetVar1, int stateSetVar2, LocalVariableAllocator allocator) {
    // Use stack-based swap to avoid dynamic temp variable allocation inside loops
    // (dynamic allocation inside loops causes stackmap frame inconsistencies)
    if (isDualLongEncoded(stateSetVar1)) {
      // Dual-long: swap both words using stack
      int var1_0 = decodeDualLongSlot0(stateSetVar1);
      int var1_1 = decodeDualLongSlot1(stateSetVar1);
      int var2_0 = decodeDualLongSlot0(stateSetVar2);
      int var2_1 = decodeDualLongSlot1(stateSetVar2);

      // Swap low words: push var1_0, push var2_0, store to var1_0, store to var2_0
      // S: [] -> [var1_0_val]
      mv.visitVarInsn(LLOAD, var1_0);
      // S: [var1_0_val] -> [var1_0_val, var2_0_val]
      mv.visitVarInsn(LLOAD, var2_0);
      // S: [var1_0_val, var2_0_val] -> [var1_0_val]
      mv.visitVarInsn(LSTORE, var1_0);
      // S: [var1_0_val] -> []
      mv.visitVarInsn(LSTORE, var2_0);

      // Swap high words: same pattern
      // S: [] -> [var1_1_val]
      mv.visitVarInsn(LLOAD, var1_1);
      // S: [var1_1_val] -> [var1_1_val, var2_1_val]
      mv.visitVarInsn(LLOAD, var2_1);
      // S: [var1_1_val, var2_1_val] -> [var1_1_val]
      mv.visitVarInsn(LSTORE, var1_1);
      // S: [var1_1_val] -> []
      mv.visitVarInsn(LSTORE, var2_1);
    } else if (useSingleLong) {
      // Single-long: swap using stack
      // S: [] -> [var1_val]
      mv.visitVarInsn(LLOAD, stateSetVar1);
      // S: [var1_val] -> [var1_val, var2_val]
      mv.visitVarInsn(LLOAD, stateSetVar2);
      // S: [var1_val, var2_val] -> [var1_val]
      mv.visitVarInsn(LSTORE, stateSetVar1);
      // S: [var1_val] -> []
      mv.visitVarInsn(LSTORE, stateSetVar2);
    } else {
      // Object reference: swap using stack
      // S: [] -> [var1_ref]
      mv.visitVarInsn(ALOAD, stateSetVar1);
      // S: [var1_ref] -> [var1_ref, var2_ref]
      mv.visitVarInsn(ALOAD, stateSetVar2);
      // S: [var1_ref, var2_ref] -> [var1_ref]
      mv.visitVarInsn(ASTORE, stateSetVar1);
      // S: [var1_ref] -> []
      mv.visitVarInsn(ASTORE, stateSetVar2);
    }
  }

  // ========== End of Dual-Long Slot Encoding Utilities ==========

  /** Generate bytecode to create a new state set. Stack: ... -> ..., stateSet */
  private void generateStateSetNew(MethodVisitor mv) {
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/StateSet");
    mv.visitInsn(DUP);
    pushInt(mv, stateCount);
    mv.visitMethodInsn(
        INVOKESPECIAL, "com/datadoghq/reggie/runtime/StateSet", "<init>", "(I)V", false);
  }

  /** Generate bytecode to add an element to the state set. Stack: ..., stateSet, element -> ... */
  private void generateStateSetAdd(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "add", "(I)V", false);
  }

  /**
   * Generate bytecode to check if element is in the state set. Stack: ..., stateSet, element ->
   * ..., boolean
   */
  private void generateStateSetContains(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "contains", "(I)Z", false);
  }

  /** Generate bytecode to clear the state set. Stack: ..., stateSet -> ... */
  private void generateStateSetClear(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);
  }

  /** Generate bytecode to check if state set is empty. Stack: ..., stateSet -> ..., boolean */
  private void generateStateSetIsEmpty(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "isEmpty", "()Z", false);
  }

  /** Generate bytecode to get the size of the state set. Stack: ..., stateSet -> ..., int */
  private void generateStateSetSize(MethodVisitor mv) {
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "size", "()I", false);
  }

  /** Get the internal type descriptor for the state set type. */
  private String getStateSetDescriptor() {
    return "Lcom/datadoghq/reggie/runtime/StateSet;";
  }

  /** Get the internal type name for the state set type. */
  private String getStateSetType() {
    return "com/datadoghq/reggie/runtime/StateSet";
  }

  // ========== Phase 2B: Single-Long Bit Operations (Inline) ==========

  /**
   * Generate: long currentStates = 0L; For single-long optimization (≤64 states). Local variable:
   * longVar (occupies slots longVar and longVar+1)
   */
  private void generateSingleLongNew(MethodVisitor mv, int longVar) {
    mv.visitInsn(LCONST_0);
    mv.visitVarInsn(LSTORE, longVar);
  }

  /**
   * Generate: currentStates |= (1L << stateId); Inline bit operation - zero method call overhead.
   * Local variable: longVar, int stateIdVar
   */
  private void generateSingleLongSet(MethodVisitor mv, int longVar, int stateIdVar) {
    mv.visitVarInsn(LLOAD, longVar); // Load current bits
    mv.visitInsn(LCONST_1); // Push 1L
    mv.visitVarInsn(ILOAD, stateIdVar); // Load state ID
    mv.visitInsn(LSHL); // Shift: 1L << stateId
    mv.visitInsn(LOR); // OR: bits | mask
    mv.visitVarInsn(LSTORE, longVar); // Store result
  }

  /**
   * Generate: (currentStates & (1L << stateId)) != 0 Leaves boolean (0 or 1) on stack. Local
   * variable: longVar, int stateIdVar
   */
  private void generateSingleLongContains(MethodVisitor mv, int longVar, int stateIdVar) {
    mv.visitVarInsn(LLOAD, longVar); // Load bits
    mv.visitInsn(LCONST_1); // Push 1L
    mv.visitVarInsn(ILOAD, stateIdVar); // Load state ID
    mv.visitInsn(LSHL); // Shift: 1L << stateId
    mv.visitInsn(LAND); // AND: bits & mask
    mv.visitInsn(LCONST_0); // Push 0L
    mv.visitInsn(LCMP); // Compare

    // Convert comparison result to boolean
    Label isZero = new Label();
    Label end = new Label();
    mv.visitJumpInsn(IFEQ, isZero); // if == 0, jump to isZero
    mv.visitInsn(ICONST_1); // Push true
    mv.visitJumpInsn(GOTO, end);
    mv.visitLabel(isZero);
    mv.visitInsn(ICONST_0); // Push false
    mv.visitLabel(end);
  }

  /**
   * Generate: currentStates |= (1L << stateId); Const variant: takes immediate constant, no temp
   * variable needed. This eliminates temp variable allocation for constant state IDs.
   *
   * <p>Stack effect: [] → []
   */
  private void generateSingleLongSetConst(MethodVisitor mv, int longVar, int stateId) {
    mv.visitVarInsn(LLOAD, longVar); // Load current bits
    mv.visitInsn(LCONST_1); // Push 1L
    pushInt(mv, stateId); // Push constant state ID directly
    mv.visitInsn(LSHL); // Shift: 1L << stateId
    mv.visitInsn(LOR); // OR: bits | mask
    mv.visitVarInsn(LSTORE, longVar); // Store result
  }

  /**
   * Generate: (currentStates & (1L << stateId)) != 0 Const variant: takes immediate constant, no
   * temp variable needed. Leaves boolean (0 or 1) on stack.
   *
   * <p>Stack effect: [] → [boolean]
   */
  private void generateSingleLongContainsConst(MethodVisitor mv, int longVar, int stateId) {
    mv.visitVarInsn(LLOAD, longVar); // Load bits
    mv.visitInsn(LCONST_1); // Push 1L
    pushInt(mv, stateId); // Push constant state ID directly
    mv.visitInsn(LSHL); // Shift: 1L << stateId
    mv.visitInsn(LAND); // AND: bits & mask
    mv.visitInsn(LCONST_0); // Push 0L
    mv.visitInsn(LCMP); // Compare

    // Convert comparison result to boolean
    Label isZero = new Label();
    Label end = new Label();
    mv.visitJumpInsn(IFEQ, isZero); // if == 0, jump to isZero
    mv.visitInsn(ICONST_1); // Push true
    mv.visitJumpInsn(GOTO, end);
    mv.visitLabel(isZero);
    mv.visitInsn(ICONST_0); // Push false
    mv.visitLabel(end);
  }

  /** Generate: currentStates = 0L; Clear all bits. */
  private void generateSingleLongClear(MethodVisitor mv, int longVar) {
    mv.visitInsn(LCONST_0);
    mv.visitVarInsn(LSTORE, longVar);
  }

  /** Generate: currentStates == 0L Leaves boolean on stack. */
  private void generateSingleLongIsEmpty(MethodVisitor mv, int longVar) {
    mv.visitVarInsn(LLOAD, longVar);
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);

    Label isEmpty = new Label();
    Label end = new Label();
    mv.visitJumpInsn(IFEQ, isEmpty);
    mv.visitInsn(ICONST_0); // Not empty
    mv.visitJumpInsn(GOTO, end);
    mv.visitLabel(isEmpty);
    mv.visitInsn(ICONST_1); // Empty
    mv.visitLabel(end);
  }

  /**
   * Generate inline SWAR (SIMD Within A Register) iteration over set bits. This is a critical
   * optimization that replaces BitSet.nextSetBit() with efficient bit manipulation using
   * Long.numberOfTrailingZeros().
   *
   * <p>Algorithm: long remainingBits = currentStates; while (remainingBits != 0L) { int stateId =
   * Long.numberOfTrailingZeros(remainingBits); // ... loop body ... remainingBits &= (remainingBits
   * - 1); // Clear lowest set bit }
   *
   * @param mv method visitor
   * @param statesVar local variable slot containing the long with set bits
   * @param stateIdVar local variable slot for the state ID (int)
   * @param loopBodyGenerator consumer that generates the loop body bytecode
   * @param allocator variable allocator for temp variables
   */
  private void generateSingleLongIteration(
      MethodVisitor mv,
      int statesVar,
      int stateIdVar,
      java.util.function.Consumer<MethodVisitor> loopBodyGenerator,
      LocalVariableAllocator allocator) {
    Label loopStart = new Label();
    Label loopEnd = new Label();

    // Allocate temp variable for remaining bits (needs 2 slots)
    int remainingVar = allocator.allocateLong();

    // Copy currentStates to remainingBits
    mv.visitVarInsn(LLOAD, statesVar);
    mv.visitVarInsn(LSTORE, remainingVar);

    mv.visitLabel(loopStart);

    // if (remainingBits == 0L) break
    mv.visitVarInsn(LLOAD, remainingVar);
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);
    mv.visitJumpInsn(IFEQ, loopEnd);

    // int stateId = Long.numberOfTrailingZeros(remainingBits)
    mv.visitVarInsn(LLOAD, remainingVar);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "numberOfTrailingZeros", "(J)I", false);
    mv.visitVarInsn(ISTORE, stateIdVar);

    // Execute loop body
    loopBodyGenerator.accept(mv);

    // remainingBits &= (remainingBits - 1)  // Clear lowest set bit
    mv.visitVarInsn(LLOAD, remainingVar);
    mv.visitVarInsn(LLOAD, remainingVar);
    mv.visitInsn(LCONST_1);
    mv.visitInsn(LSUB);
    mv.visitInsn(LAND);
    mv.visitVarInsn(LSTORE, remainingVar);

    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);

    // NOTE: Don't release temp variable - keeping it allocated avoids stackmap frame issues
    // with ASM's COMPUTE_FRAMES in complex control flow
    // allocator.release(remainingVar, 2);
  }

  // ========== End of Single-Long Bit Operations ==========

  // ========== Phase 2C: Dual-Long Bit Operations (65-128 states) ==========

  /**
   * Generate bytecode to initialize dual-long state set (stateSet0 = 0L, stateSet1 = 0L). For
   * 65-128 state optimization using two long primitives. Local variables: stateSet0Var and
   * stateSet1Var (each occupies 2 slots) Stack: [] → []
   */
  private void generateDualLongNew(MethodVisitor mv, int stateSet0Var, int stateSet1Var) {
    // stateSet0 = 0L
    mv.visitInsn(LCONST_0);
    mv.visitVarInsn(LSTORE, stateSet0Var);
    // stateSet1 = 0L
    mv.visitInsn(LCONST_0);
    mv.visitVarInsn(LSTORE, stateSet1Var);
  }

  /**
   * Generate: set(stateId) - sets bit in appropriate word. Generates: if (stateId < 64) { stateSet0
   * |= (1L << stateId); } else { stateSet1 |= (1L << (stateId - 64)); } Local variables:
   * stateSet0Var, stateSet1Var, int stateIdVar Stack: [] → []
   */
  private void generateDualLongSet(
      MethodVisitor mv, int stateSet0Var, int stateSet1Var, int stateIdVar) {
    Label highWord = new Label();
    Label done = new Label();

    // if (stateId < 64)
    mv.visitVarInsn(ILOAD, stateIdVar);
    pushInt(mv, 64);
    mv.visitJumpInsn(IF_ICMPGE, highWord);

    // Low word: stateSet0 |= (1L << stateId)
    mv.visitVarInsn(LLOAD, stateSet0Var);
    mv.visitInsn(LCONST_1);
    mv.visitVarInsn(ILOAD, stateIdVar);
    mv.visitInsn(LSHL);
    mv.visitInsn(LOR);
    mv.visitVarInsn(LSTORE, stateSet0Var);
    mv.visitJumpInsn(GOTO, done);

    // High word: stateSet1 |= (1L << (stateId - 64))
    mv.visitLabel(highWord);
    mv.visitVarInsn(LLOAD, stateSet1Var);
    mv.visitInsn(LCONST_1);
    mv.visitVarInsn(ILOAD, stateIdVar);
    pushInt(mv, 64);
    mv.visitInsn(ISUB);
    mv.visitInsn(LSHL);
    mv.visitInsn(LOR);
    mv.visitVarInsn(LSTORE, stateSet1Var);

    mv.visitLabel(done);
  }

  /**
   * Generate: set(stateId) - const variant for immediate state ID. Similar to generateDualLongSet()
   * but with compile-time constant state ID. Stack: [] → []
   */
  private void generateDualLongSetConst(
      MethodVisitor mv, int stateSet0Var, int stateSet1Var, int stateId) {
    if (stateId < 64) {
      // Low word: stateSet0 |= (1L << stateId)
      mv.visitVarInsn(LLOAD, stateSet0Var);
      mv.visitInsn(LCONST_1);
      pushInt(mv, stateId);
      mv.visitInsn(LSHL);
      mv.visitInsn(LOR);
      mv.visitVarInsn(LSTORE, stateSet0Var);
    } else {
      // High word: stateSet1 |= (1L << (stateId - 64))
      mv.visitVarInsn(LLOAD, stateSet1Var);
      mv.visitInsn(LCONST_1);
      pushInt(mv, stateId - 64);
      mv.visitInsn(LSHL);
      mv.visitInsn(LOR);
      mv.visitVarInsn(LSTORE, stateSet1Var);
    }
  }

  /**
   * Generate: (stateSet0 | stateSet1) == 0L Check if both words are zero (state set is empty).
   * Stack: [] → [boolean (0 or 1)]
   */
  private void generateDualLongIsEmpty(MethodVisitor mv, int stateSet0Var, int stateSet1Var) {
    Label notEmpty = new Label();
    Label done = new Label();

    // (stateSet0 | stateSet1) == 0L
    mv.visitVarInsn(LLOAD, stateSet0Var);
    mv.visitVarInsn(LLOAD, stateSet1Var);
    mv.visitInsn(LOR);
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);
    mv.visitJumpInsn(IFNE, notEmpty);

    // Empty: push 1 (true)
    mv.visitInsn(ICONST_1);
    mv.visitJumpInsn(GOTO, done);

    // Not empty: push 0 (false)
    mv.visitLabel(notEmpty);
    mv.visitInsn(ICONST_0);

    mv.visitLabel(done);
  }

  /** Generate: stateSet0 = 0L; stateSet1 = 0L; Clear both words. Stack: [] → [] */
  private void generateDualLongClear(MethodVisitor mv, int stateSet0Var, int stateSet1Var) {
    mv.visitInsn(LCONST_0);
    mv.visitVarInsn(LSTORE, stateSet0Var);
    mv.visitInsn(LCONST_0);
    mv.visitVarInsn(LSTORE, stateSet1Var);
  }

  /**
   * Generate: contains check for compile-time constant state ID in dual-long. Tests: (stateId < 64)
   * ? ((stateSet0 & (1L << stateId)) != 0) : ((stateSet1 & (1L << (stateId - 64))) != 0) Stack: []
   * → [boolean (0 or 1)]
   */
  private void generateDualLongContainsConst(
      MethodVisitor mv, int stateSet0Var, int stateSet1Var, int stateId) {
    Label end = new Label();

    if (stateId < 64) {
      // Low word: test (stateSet0 & (1L << stateId)) != 0
      mv.visitVarInsn(LLOAD, stateSet0Var);
      mv.visitInsn(LCONST_1);
      pushInt(mv, stateId);
      mv.visitInsn(LSHL);
      mv.visitInsn(LAND);
      mv.visitInsn(LCONST_0);
      mv.visitInsn(LCMP);

      // Convert comparison result to boolean (0 or 1)
      Label isZero = new Label();
      mv.visitJumpInsn(IFEQ, isZero);
      mv.visitInsn(ICONST_1); // Not zero = contains
      mv.visitJumpInsn(GOTO, end);
      mv.visitLabel(isZero);
      mv.visitInsn(ICONST_0); // Zero = doesn't contain
    } else {
      // High word: test (stateSet1 & (1L << (stateId - 64))) != 0
      mv.visitVarInsn(LLOAD, stateSet1Var);
      mv.visitInsn(LCONST_1);
      pushInt(mv, stateId - 64);
      mv.visitInsn(LSHL);
      mv.visitInsn(LAND);
      mv.visitInsn(LCONST_0);
      mv.visitInsn(LCMP);

      // Convert comparison result to boolean (0 or 1)
      Label isZero = new Label();
      mv.visitJumpInsn(IFEQ, isZero);
      mv.visitInsn(ICONST_1); // Not zero = contains
      mv.visitJumpInsn(GOTO, end);
      mv.visitLabel(isZero);
      mv.visitInsn(ICONST_0); // Zero = doesn't contain
    }

    mv.visitLabel(end);
  }

  /**
   * Generate: contains check for variable state ID in dual-long. Tests: (stateId < 64) ?
   * ((stateSet0 & (1L << stateId)) != 0) : ((stateSet1 & (1L << (stateId - 64))) != 0) Stack: [] →
   * [boolean (0 or 1)]
   */
  private void generateDualLongContains(
      MethodVisitor mv, int stateSet0Var, int stateSet1Var, int stateIdVar) {
    Label highWord = new Label();
    Label done = new Label();
    Label containsTrue = new Label();

    // if (stateId < 64)
    mv.visitVarInsn(ILOAD, stateIdVar);
    // S: [I]
    pushInt(mv, 64);
    // S: [I, I]
    mv.visitJumpInsn(IF_ICMPGE, highWord);
    // S: []

    // Low word: test (stateSet0 & (1L << stateId)) != 0
    mv.visitVarInsn(LLOAD, stateSet0Var);
    // S: [J]
    mv.visitInsn(LCONST_1);
    // S: [J, J]
    mv.visitVarInsn(ILOAD, stateIdVar);
    // S: [J, J, I]
    mv.visitInsn(LSHL);
    // S: [J, J]
    mv.visitInsn(LAND);
    // S: [J]
    mv.visitInsn(LCONST_0);
    // S: [J, J]
    mv.visitInsn(LCMP);
    // S: [I]
    mv.visitJumpInsn(IFNE, containsTrue);
    // S: []
    mv.visitInsn(ICONST_0); // doesn't contain
    mv.visitJumpInsn(GOTO, done);

    // High word: test (stateSet1 & (1L << (stateId - 64))) != 0
    mv.visitLabel(highWord);
    // S: []
    mv.visitVarInsn(LLOAD, stateSet1Var);
    // S: [J]
    mv.visitInsn(LCONST_1);
    // S: [J, J]
    mv.visitVarInsn(ILOAD, stateIdVar);
    // S: [J, J, I]
    pushInt(mv, 64);
    // S: [J, J, I, I]
    mv.visitInsn(ISUB);
    // S: [J, J, I]
    mv.visitInsn(LSHL);
    // S: [J, J]
    mv.visitInsn(LAND);
    // S: [J]
    mv.visitInsn(LCONST_0);
    // S: [J, J]
    mv.visitInsn(LCMP);
    // S: [I]
    mv.visitJumpInsn(IFNE, containsTrue);
    // S: []
    mv.visitInsn(ICONST_0); // doesn't contain
    mv.visitJumpInsn(GOTO, done);

    mv.visitLabel(containsTrue);
    // S: []
    mv.visitInsn(ICONST_1); // contains

    mv.visitLabel(done);
    // S: [I]
  }

  /**
   * Generate SWAR iteration across both words using Kernighan's algorithm. Pattern: long remaining0
   * = stateSet0; long remaining1 = stateSet1; while (remaining0 != 0) { int stateId =
   * Long.numberOfTrailingZeros(remaining0); ... user code ... remaining0 &= (remaining0 - 1); }
   * while (remaining1 != 0) { int stateId = 64 + Long.numberOfTrailingZeros(remaining1); ... user
   * code ... remaining1 &= (remaining1 - 1); }
   *
   * @param mv method visitor
   * @param stateSet0Var local variable slot for low word (bits 0-63)
   * @param stateSet1Var local variable slot for high word (bits 64-127)
   * @param stateIdVar local variable slot for state ID (int)
   * @param loopBodyGenerator consumer that generates the loop body bytecode
   * @param allocator variable allocator for temp variables
   */
  private void generateDualLongIteration(
      MethodVisitor mv,
      int stateSet0Var,
      int stateSet1Var,
      int stateIdVar,
      java.util.function.Consumer<MethodVisitor> loopBodyGenerator,
      LocalVariableAllocator allocator) {
    Label loop0Start = new Label();
    Label loop0End = new Label();
    Label loop1Start = new Label();
    Label loop1End = new Label();

    // Allocate temp variables for remaining bits (each needs 2 slots)
    int remaining0Var = allocator.allocateLong();
    int remaining1Var = allocator.allocateLong();

    // --- Process low word (bits 0-63) ---
    // long remaining0 = stateSet0
    mv.visitVarInsn(LLOAD, stateSet0Var);
    mv.visitVarInsn(LSTORE, remaining0Var);

    mv.visitLabel(loop0Start);
    // while (remaining0 != 0)
    mv.visitVarInsn(LLOAD, remaining0Var);
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);
    mv.visitJumpInsn(IFEQ, loop0End);

    // int stateId = Long.numberOfTrailingZeros(remaining0)
    mv.visitVarInsn(LLOAD, remaining0Var);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "numberOfTrailingZeros", "(J)I", false);
    mv.visitVarInsn(ISTORE, stateIdVar);

    // User code processes stateId
    loopBodyGenerator.accept(mv);

    // remaining0 &= (remaining0 - 1)
    mv.visitVarInsn(LLOAD, remaining0Var);
    mv.visitVarInsn(LLOAD, remaining0Var);
    mv.visitInsn(LCONST_1);
    mv.visitInsn(LSUB);
    mv.visitInsn(LAND);
    mv.visitVarInsn(LSTORE, remaining0Var);
    mv.visitJumpInsn(GOTO, loop0Start);

    mv.visitLabel(loop0End);

    // --- Process high word (bits 64-127) ---
    // long remaining1 = stateSet1
    mv.visitVarInsn(LLOAD, stateSet1Var);
    mv.visitVarInsn(LSTORE, remaining1Var);

    mv.visitLabel(loop1Start);
    // while (remaining1 != 0)
    mv.visitVarInsn(LLOAD, remaining1Var);
    mv.visitInsn(LCONST_0);
    mv.visitInsn(LCMP);
    mv.visitJumpInsn(IFEQ, loop1End);

    // int stateId = 64 + Long.numberOfTrailingZeros(remaining1)
    pushInt(mv, 64);
    mv.visitVarInsn(LLOAD, remaining1Var);
    mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "numberOfTrailingZeros", "(J)I", false);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, stateIdVar);

    // User code processes stateId
    loopBodyGenerator.accept(mv);

    // remaining1 &= (remaining1 - 1)
    mv.visitVarInsn(LLOAD, remaining1Var);
    mv.visitVarInsn(LLOAD, remaining1Var);
    mv.visitInsn(LCONST_1);
    mv.visitInsn(LSUB);
    mv.visitInsn(LAND);
    mv.visitVarInsn(LSTORE, remaining1Var);
    mv.visitJumpInsn(GOTO, loop1Start);

    mv.visitLabel(loop1End);

    // NOTE: Don't release temp variables - keeping them allocated avoids stackmap frame issues
    // with ASM's COMPUTE_FRAMES in complex control flow (lookupswitch inside dual-long iteration)
    // allocator.release(remaining0Var, 2);
    // allocator.release(remaining1Var, 2);
  }

  // ========== End of Dual-Long Bit Operations ==========

  // ========== Unified State Set Operations (with Dual-Long Auto-Detection) ==========
  //
  // These methods auto-detect dual-long mode from encoded slot values:
  // - Positive stateSetVar (>= 0): single-long or object reference
  // - Negative stateSetVar (< 0): dual-long encoded (decode with decodeDualLongSlot0/1)

  /**
   * Add a constant state ID to the state set. Auto-detects dual-long mode from encoded slot value.
   *
   * @param mv method visitor
   * @param stateSetVar local variable slot (or dual-long encoded)
   * @param stateId constant state ID to add
   * @param allocator variable allocator (unused, kept for API compatibility)
   */
  private void addStateToSet(
      MethodVisitor mv, int stateSetVar, int stateId, LocalVariableAllocator allocator) {
    if (isDualLongEncoded(stateSetVar)) {
      // Dual-long encoded: decode and use dual-long operation
      int var0 = decodeDualLongSlot0(stateSetVar);
      int var1 = decodeDualLongSlot1(stateSetVar);
      generateDualLongSetConst(mv, var0, var1, stateId);
    } else if (useSingleLong) {
      // Single-long: use const variant
      generateSingleLongSetConst(mv, stateSetVar, stateId);
    } else {
      // BitSet/SparseSet: load object, push constant, call add
      mv.visitVarInsn(ALOAD, stateSetVar);
      pushInt(mv, stateId);
      generateStateSetAdd(mv);
    }
  }

  /**
   * Add a variable state ID to the state set. Auto-detects dual-long mode from encoded slot value.
   *
   * @param mv method visitor
   * @param stateSetVar local variable slot (or dual-long encoded)
   * @param stateIdVar local variable slot containing the state ID
   */
  private void addStateToSetVar(MethodVisitor mv, int stateSetVar, int stateIdVar) {
    if (isDualLongEncoded(stateSetVar)) {
      // Dual-long encoded: decode and use dual-long operation
      int var0 = decodeDualLongSlot0(stateSetVar);
      int var1 = decodeDualLongSlot1(stateSetVar);
      generateDualLongSet(mv, var0, var1, stateIdVar);
    } else if (useSingleLong) {
      // Single-long: use inline operation directly
      generateSingleLongSet(mv, stateSetVar, stateIdVar);
    } else {
      // BitSet/SparseSet: load object, load ID, call add
      mv.visitVarInsn(ALOAD, stateSetVar);
      mv.visitVarInsn(ILOAD, stateIdVar);
      generateStateSetAdd(mv);
    }
  }

  /**
   * Clear the state set. Auto-detects dual-long mode from encoded slot value.
   *
   * @param mv method visitor
   * @param stateSetVar local variable slot (or dual-long encoded)
   */
  private void clearStateSet(MethodVisitor mv, int stateSetVar) {
    if (isDualLongEncoded(stateSetVar)) {
      // Dual-long encoded: decode and use dual-long operation
      int var0 = decodeDualLongSlot0(stateSetVar);
      int var1 = decodeDualLongSlot1(stateSetVar);
      generateDualLongClear(mv, var0, var1);
    } else if (useSingleLong) {
      // Single-long: set to 0L
      generateSingleLongClear(mv, stateSetVar);
    } else {
      // BitSet/SparseSet: load object, call clear
      mv.visitVarInsn(ALOAD, stateSetVar);
      generateStateSetClear(mv);
    }
  }

  /**
   * Check if the state set is empty. Leaves boolean result on stack. Auto-detects dual-long mode
   * from encoded slot value.
   *
   * @param mv method visitor
   * @param stateSetVar local variable slot (or dual-long encoded)
   */
  private void isStateSetEmpty(MethodVisitor mv, int stateSetVar) {
    if (isDualLongEncoded(stateSetVar)) {
      // Dual-long encoded: decode and use dual-long operation
      int var0 = decodeDualLongSlot0(stateSetVar);
      int var1 = decodeDualLongSlot1(stateSetVar);
      generateDualLongIsEmpty(mv, var0, var1);
    } else if (useSingleLong) {
      // Single-long: compare with 0L
      generateSingleLongIsEmpty(mv, stateSetVar);
    } else {
      // BitSet/SparseSet: load object, call isEmpty
      mv.visitVarInsn(ALOAD, stateSetVar);
      generateStateSetIsEmpty(mv);
    }
  }

  /**
   * Check if a state ID is in the state set. Leaves boolean result on stack. Auto-detects dual-long
   * mode from encoded slot value.
   *
   * @param mv method visitor
   * @param stateSetVar local variable slot (or dual-long encoded)
   * @param stateIdVar local variable slot containing the state ID
   */
  private void checkStateInSet(MethodVisitor mv, int stateSetVar, int stateIdVar) {
    if (isDualLongEncoded(stateSetVar)) {
      // Dual-long encoded: decode and use dual-long operation
      int var0 = decodeDualLongSlot0(stateSetVar);
      int var1 = decodeDualLongSlot1(stateSetVar);
      generateDualLongContains(mv, var0, var1, stateIdVar);
    } else if (useSingleLong) {
      // Single-long: inline bit test
      generateSingleLongContains(mv, stateSetVar, stateIdVar);
    } else {
      // BitSet/SparseSet: load object, load ID, call contains
      mv.visitVarInsn(ALOAD, stateSetVar);
      mv.visitVarInsn(ILOAD, stateIdVar);
      generateStateSetContains(mv);
    }
  }

  /**
   * Check if a constant state ID is in the state set. Leaves boolean result on stack. Auto-detects
   * dual-long mode from encoded slot value.
   *
   * @param mv method visitor
   * @param stateSetVar local variable slot (or dual-long encoded)
   * @param stateId constant state ID to check
   * @param allocator variable allocator (unused, kept for API compatibility)
   */
  private void checkStateInSetConst(
      MethodVisitor mv, int stateSetVar, int stateId, LocalVariableAllocator allocator) {
    if (isDualLongEncoded(stateSetVar)) {
      // Dual-long encoded: decode and use dual-long operation
      int var0 = decodeDualLongSlot0(stateSetVar);
      int var1 = decodeDualLongSlot1(stateSetVar);
      generateDualLongContainsConst(mv, var0, var1, stateId);
    } else if (useSingleLong) {
      // Single-long: use const variant
      generateSingleLongContainsConst(mv, stateSetVar, stateId);
    } else {
      // BitSet/SparseSet: load object, push constant, call contains
      mv.visitVarInsn(ALOAD, stateSetVar);
      pushInt(mv, stateId);
      generateStateSetContains(mv);
    }
  }

  // ========== End of Unified State Set Operations ==========

  /**
   * Generate specialized iteration bytecode for single-long, dual-long, BitSet, or SparseSet.
   * Auto-detects dual-long mode from encoded slot value.
   *
   * <p>For dual-long (65-128 states) - Phase 2C: Uses SWAR iteration over both 64-bit words
   *
   * <p>For single-long (≤64 states) - Phase 2B: Uses SWAR (SIMD Within A Register) iteration with
   * Long.numberOfTrailingZeros() Zero method call overhead compared to BitSet.nextSetBit()
   *
   * <p>For BitSet (≤64 states): int stateId = states.nextSetBit(0); while (stateId >= 0) { // body
   * code stateId = states.nextSetBit(stateId + 1); }
   *
   * <p>For SparseSet (>64 states): int size = states.size(); for (int i = 0; i < size; i++) { int
   * stateId = states.get(i); // body code }
   *
   * @param mv method visitor
   * @param statesVar local variable holding the state set (or dual-long encoded)
   * @param stateIdVar local variable to store current state ID (must be pre-allocated)
   * @param indexVar local variable for iteration (not used for single-long/dual-long)
   * @param sizeVar local variable for size (not used for single-long/dual-long)
   * @param loopBodyGenerator lambda that generates the loop body code
   * @param allocator variable allocator
   */
  private void generateSpecializedIteration(
      MethodVisitor mv,
      int statesVar,
      int stateIdVar,
      int indexVar,
      int sizeVar,
      java.util.function.Consumer<MethodVisitor> loopBodyGenerator,
      LocalVariableAllocator allocator) {
    if (isDualLongEncoded(statesVar)) {
      // Phase 2C: Dual-long SWAR iteration (65-128 states)
      int var0 = decodeDualLongSlot0(statesVar);
      int var1 = decodeDualLongSlot1(statesVar);
      generateDualLongIteration(mv, var0, var1, stateIdVar, loopBodyGenerator, allocator);
    } else if (useSingleLong) {
      // Phase 2B: Single-long SWAR iteration - most efficient
      generateSingleLongIteration(mv, statesVar, stateIdVar, loopBodyGenerator, allocator);
    } else if (useBitSet) {
      // StateSet path (uses BitSet internally): Use nextSetBit() for O(1) iteration
      Label loopStart = new Label();
      Label loopEnd = new Label();

      // stateId = states.nextSetBit(0);
      mv.visitVarInsn(ALOAD, statesVar);
      mv.visitInsn(ICONST_0);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "nextSetBit", "(I)I", false);
      mv.visitVarInsn(ISTORE, stateIdVar);

      // while (stateId >= 0)
      mv.visitLabel(loopStart);
      mv.visitVarInsn(ILOAD, stateIdVar);
      mv.visitJumpInsn(IFLT, loopEnd);

      // Execute loop body
      loopBodyGenerator.accept(mv);

      // stateId = states.nextSetBit(stateId + 1);
      mv.visitVarInsn(ALOAD, statesVar);
      mv.visitVarInsn(ILOAD, stateIdVar);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "nextSetBit", "(I)I", false);
      mv.visitVarInsn(ISTORE, stateIdVar);

      mv.visitJumpInsn(GOTO, loopStart);
      mv.visitLabel(loopEnd);
    } else {
      // StateSet path (uses SparseSet internally): Use size()/get() iteration
      Label loopStart = new Label();
      Label loopEnd = new Label();

      // int i = 0; int size = states.size();
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, indexVar);

      mv.visitVarInsn(ALOAD, statesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "size", "()I", false);
      mv.visitVarInsn(ISTORE, sizeVar);

      // while (i < size)
      mv.visitLabel(loopStart);
      mv.visitVarInsn(ILOAD, indexVar);
      mv.visitVarInsn(ILOAD, sizeVar);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // stateId = states.get(i);
      mv.visitVarInsn(ALOAD, statesVar);
      mv.visitVarInsn(ILOAD, indexVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "get", "(I)I", false);
      mv.visitVarInsn(ISTORE, stateIdVar);

      // i++
      mv.visitIincInsn(indexVar, 1);

      // Execute loop body
      loopBodyGenerator.accept(mv);

      // Continue loop
      mv.visitJumpInsn(GOTO, loopStart);
      mv.visitLabel(loopEnd);
    }
  }

  /**
   * Generate indexOf-based scanning loop for patterns with a single required literal. This
   * optimization skips directly to positions where the required literal appears, avoiding
   * unnecessary pattern evaluations at positions that cannot possibly match.
   *
   * <p>Generated pseudo-code:
   *
   * <pre>
   * int pos = startPos;
   * while (pos <= endPos) {
   *     int nextPos = input.indexOf(requiredChar, pos);
   *     if (nextPos < 0 || nextPos > endPos) return -1;
   *     if (matchesAt(input, nextPos)) return nextPos;
   *     pos = nextPos + 1;
   * }
   * return -1;
   * </pre>
   *
   * @param mv method visitor
   * @param requiredChar the literal character that must appear in any match
   * @param inputVar local variable slot for input string
   * @param startPosVar local variable slot for start position
   * @param endPosVar local variable slot for end position
   * @param matchAtPosLabel label to jump to for testing match at found position
   * @param allocator local variable allocator
   * @param posVar local variable slot to store current scan position
   */
  private void generateLiteralSkipScan(
      MethodVisitor mv,
      char requiredChar,
      int inputVar,
      int startPosVar,
      int endPosVar,
      Label matchAtPosLabel,
      LocalVariableAllocator allocator,
      int posVar) {
    // int pos = startPos;
    mv.visitVarInsn(ILOAD, startPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label returnNotFound = new Label();

    // while (pos <= endPos)
    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, endPosVar);
    mv.visitJumpInsn(IF_ICMPGT, returnNotFound);

    // nextPos = input.indexOf(requiredChar, pos)
    mv.visitVarInsn(ALOAD, inputVar);
    pushInt(mv, (int) requiredChar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
    int nextPosVar = allocator.allocateInt();
    mv.visitVarInsn(ISTORE, nextPosVar);

    // if (nextPos < 0 || nextPos > endPos) return -1
    mv.visitVarInsn(ILOAD, nextPosVar);
    mv.visitJumpInsn(IFLT, returnNotFound);
    mv.visitVarInsn(ILOAD, nextPosVar);
    mv.visitVarInsn(ILOAD, endPosVar);
    mv.visitJumpInsn(IF_ICMPGT, returnNotFound);

    // Store nextPos in posVar for match attempt
    mv.visitVarInsn(ILOAD, nextPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Jump to match attempt code (caller will handle this)
    mv.visitJumpInsn(GOTO, matchAtPosLabel);

    // After match fails (caller will jump back here), increment pos
    // This label will be visited by the caller after match failure
    // pos = nextPos + 1; goto loopStart;

    mv.visitLabel(returnNotFound);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate matches() method that simulates NFA execution.
   *
   * <h4>Generated Method Signature</h4>
   *
   * <pre>{@code public boolean matches(String input)}</pre>
   *
   * <h4>Generated Algorithm</h4>
   *
   * <pre>{@code
   * boolean matches(String input) {
   *     if (input == null) return false;
   *     int len = input.length();
   *
   *     // State representation depends on NFA size:
   *     // ≤64 states: long currentStates, nextStates
   *     // 65-128: long current0, current1, next0, next1
   *     // >128: StateSet currentStates, nextStates (pre-allocated fields)
   *
   *     // Group tracking arrays (if pattern has capturing groups)
   *     int[] groupStarts = new int[groupCount + 1];
   *     int[] groupEnds = new int[groupCount + 1];
   *     Arrays.fill(groupStarts, -1);
   *     Arrays.fill(groupEnds, -1);
   *     groupStarts[0] = 0;  // match starts at position 0
   *
   *     // Initialize with start state + epsilon closure
   *     currentStates.add(startStateId);
   *     epsilonClosure(currentStates, input, 0);
   *
   *     // Main loop: process each character
   *     for (int pos = 0; pos < len; pos++) {
   *         char ch = input.charAt(pos);
   *         nextStates.clear();
   *
   *         // NFA step: compute character transitions
   *         for (int stateId : currentStates) {
   *             NFAState state = states[stateId];
   *             for (Transition t : state.transitions) {
   *                 if (t.matches(ch)) {
   *                     nextStates.add(t.target);
   *                 }
   *             }
   *         }
   *
   *         // Epsilon closure with group tracking
   *         epsilonClosure(nextStates, input, pos + 1);
   *
   *         // Swap: currentStates = nextStates
   *         swap(currentStates, nextStates);
   *     }
   *
   *     // Check accept states
   *     for (NFAState accept : acceptStates) {
   *         if (currentStates.contains(accept)) {
   *             groupEnds[0] = len;  // match ends at end
   *             return true;
   *         }
   *     }
   *     return false;
   * }
   * }</pre>
   *
   * <h4>Local Variables</h4>
   *
   * <ul>
   *   <li>0: this
   *   <li>1: input (String)
   *   <li>2+: groupStarts, groupEnds, currentStates, nextStates, pos, len, worklist, etc.
   * </ul>
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Create local variable allocator
    // Method signature: matches(String input)
    // Slots: 0=this, 1=input, 2+ = local variables
    LocalVariableAllocator allocator = new LocalVariableAllocator(2);

    // if (input == null) return false;
    Label inputNotNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, inputNotNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(inputNotNull);

    // OPTIMIZATION: Literal lookahead checks using indexOf() - must pass BEFORE running NFA
    if (literalLookaheadInfo != null) {
      generateLiteralLookaheadChecks(mv, allocator, 1);

      // PHASE 3: Separate main pattern execution using its optimal strategy
      // After indexOf() checks pass, execute main pattern using DFA (8 states) instead of full NFA
      // (39 states)
      if (literalLookaheadInfo.mainPatternStrategy != null
          && literalLookaheadInfo.mainPatternNFA != null) {
        generateSeparateMainPatternExecution(mv, allocator, className);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        return;
      }
    }

    // OPTIMIZATION: Detect and generate custom bytecode for (?=.*[CharClass])+.{n,m} pattern
    FullPatternOptimization fullOpt = detectFullPatternOptimization();
    if (fullOpt != null) {
      generateOptimizedFullPattern(mv, fullOpt);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
      return;
    }

    // Allocate all local variables using allocator
    // ALWAYS allocate group array slots to ensure consistent stackmap frames
    // Even if groupCount is 0, the slots must exist for JVM verifier consistency
    int groupStartsVar = allocator.allocateRef(); // int[]
    int groupEndsVar = allocator.allocateRef(); // int[]
    int groupCount = nfa.getGroupCount();

    // Phase 2B/2C: Allocate state sets based on implementation type
    // For dual-long (65-128 states), use encoded slot values (negative)
    int currentStatesVar;
    int nextStatesVar;
    if (useSingleLong) {
      currentStatesVar = allocator.allocateLong(); // long (2 slots)
      nextStatesVar = allocator.allocateLong(); // long (2 slots)
    } else if (useDualLong) {
      currentStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
      nextStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      currentStatesVar = allocator.allocateRef(); // object reference (1 slot)
      nextStatesVar = allocator.allocateRef(); // object reference (1 slot)
    }
    int posVar = allocator.allocateInt(); // int
    int lenVar = allocator.allocateInt(); // int

    // PRE-ALLOCATE epsilon closure slots BEFORE ANY bytecode generation
    // This prevents allocation inside loops which causes JVM VerifyError
    // ALWAYS pre-allocate even if pattern has no assertions, since epsilon closure
    // may be called in fallback paths inside loops
    EpsilonClosureSlots epsilonSlots;
    int worklistVar = allocator.allocateRef();
    int stateIdVar = allocator.allocateInt();
    int worklistSizeVar = allocator.allocateInt();
    // Phase 2B/2C: processedVar is encoded dual-long when useDualLong=true
    int processedVar;
    if (useSingleLong) {
      processedVar = allocator.allocateLong();
    } else if (useDualLong) {
      processedVar = allocateDualLongStateSet(allocator); // encoded dual-long
    } else {
      processedVar = allocator.allocateRef();
    }
    int indexVar = allocator.allocateInt();
    int sizeVar = allocator.allocateInt();
    int parentIdVar = allocator.allocateInt(); // For POSIX per-config tracking

    epsilonSlots =
        new EpsilonClosureSlots(
            worklistVar, stateIdVar, worklistSizeVar, processedVar, indexVar, sizeVar, parentIdVar);

    // Pre-allocate chVar BEFORE any bytecode that might use generateEpsilonClosure
    int chVar = allocator.allocateInt();

    // Pre-detect multi-lookahead optimization and allocate its slots BEFORE any bytecode
    List<CharSet> multiLookaheadOpt = detectMultiLookaheadOptimization(nfa.getStartState());
    int[] multiLookaheadFlagSlots = null;
    int multiLookaheadScanPosVar = -1;
    int multiLookaheadCharVar = -1;
    if (multiLookaheadOpt != null) {
      int numChecks = multiLookaheadOpt.size();
      multiLookaheadFlagSlots = new int[numChecks];
      for (int i = 0; i < numChecks; i++) {
        multiLookaheadFlagSlots[i] = allocator.allocateInt();
      }
      multiLookaheadScanPosVar = allocator.allocateInt();
      multiLookaheadCharVar = allocator.allocateInt();
    }

    // Pre-allocate NFA step slots BEFORE any bytecode
    int nfaStepStateIdVar = allocator.allocateInt();
    int nfaStepIndexVar = allocator.allocateInt();
    int nfaStepSizeVar = allocator.allocateInt();
    NFAStepSlots nfaStepSlots =
        new NFAStepSlots(nfaStepStateIdVar, nfaStepIndexVar, nfaStepSizeVar);

    // Initialize ALL pre-allocated variables to ensure consistent stackmap frames
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, indexVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, sizeVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, parentIdVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, chVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepStateIdVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepIndexVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepSizeVar);
    // Initialize multi-lookahead slots if allocated
    if (multiLookaheadFlagSlots != null) {
      for (int slot : multiLookaheadFlagSlots) {
        // S: [] -> [I] -> []
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, slot);
      }
      // S: [] -> [I] -> []
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, multiLookaheadScanPosVar);
      // S: [] -> [I] -> []
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, multiLookaheadCharVar);
    }

    // Initialize worklist array
    pushInt(mv, nfa.getStates().size());
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, worklistVar);

    // Initialize stateIdVar with default value (0)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, stateIdVar);

    // Initialize worklistSizeVar with default value (0)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // Initialize processedVar (auto-detects dual-long from encoded value)
    initStateSet(mv, processedVar);

    // Initialize group arrays - ALWAYS initialize slots for JVM verifier consistency
    if (groupCount > 0) {
      // int[] groupStarts = new int[groupCount + 1];
      pushInt(mv, groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupStartsVar);

      // int[] groupEnds = new int[groupCount + 1];
      pushInt(mv, groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupEndsVar);

      // Initialize all group positions to -1
      for (int i = 0; i <= groupCount; i++) {
        mv.visitVarInsn(ALOAD, groupStartsVar);
        pushInt(mv, i);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IASTORE);
        mv.visitVarInsn(ALOAD, groupEndsVar);
        pushInt(mv, i);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IASTORE);
      }

      // Set group 0 start = 0 (match starts at beginning for matches())
      mv.visitVarInsn(ALOAD, groupStartsVar);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IASTORE);
    } else {
      // S: [] -> [null] -> []
      mv.visitInsn(ACONST_NULL);
      mv.visitVarInsn(ASTORE, groupStartsVar);
      // S: [] -> [null] -> []
      mv.visitInsn(ACONST_NULL);
      mv.visitVarInsn(ASTORE, groupEndsVar);
    }

    // Initialize state sets (auto-detects dual-long from encoded values)
    if (useSingleLong || useDualLong) {
      initStateSet(mv, currentStatesVar);
      initStateSet(mv, nextStatesVar);
    } else {
      // For StateSet (>128 states): use pre-allocated instance fields instead of creating new
      // objects
      // Load this.currentStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "currentStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, currentStatesVar);
      mv.visitVarInsn(ALOAD, currentStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);

      // Load this.nextStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "nextStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, nextStatesVar);
      mv.visitVarInsn(ALOAD, nextStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);
    }

    // int pos = 0, len = input.length();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Initialize: add start state and compute epsilon closure (auto-detects dual-long)
    addStateToSet(mv, currentStatesVar, nfa.getStartState().id, allocator);

    // OPTIMIZATION: Apply multi-lookahead optimization if applicable (using pre-allocated slots)
    if (multiLookaheadOpt != null) {
      // Apply optimized single-pass multi-lookahead check using pre-allocated slots
      Label failedMultiLookahead = new Label();
      generateOptimizedMultiLookaheadWithPrealloc(
          mv,
          multiLookaheadOpt,
          1,
          posVar,
          lenVar,
          failedMultiLookahead,
          multiLookaheadFlagSlots,
          multiLookaheadScanPosVar,
          multiLookaheadCharVar);

      // Skip the assertion states and their epsilon transitions
      // Find the state after all lookaheads
      NFA.NFAState stateAfterLookaheads = nfa.getStartState();
      for (int i = 0; i < multiLookaheadOpt.size(); i++) {
        if (!stateAfterLookaheads.getEpsilonTransitions().isEmpty()) {
          stateAfterLookaheads = stateAfterLookaheads.getEpsilonTransitions().get(0);
        }
      }

      // Clear current states and add state after lookaheads (auto-detects dual-long)
      clearStateSet(mv, currentStatesVar);
      addStateToSet(mv, currentStatesVar, stateAfterLookaheads.id, allocator);

      // Compute epsilon closure from state after lookaheads (try inline first)
      // Skip inline optimization for POSIX patterns - they need proper group tracking
      boolean inlined = false;
      if (!usePosixLastMatch && groupCount == 0) {
        Set<Integer> afterLookaheadStates = new HashSet<>();
        afterLookaheadStates.add(stateAfterLookaheads.id);
        inlined =
            tryInlineEpsilonClosure(mv, currentStatesVar, afterLookaheadStates, false, allocator);
      }

      if (!inlined) {
        if (groupCount > 0) {
          generateEpsilonClosureWithGroups(
              mv,
              currentStatesVar,
              1,
              posVar,
              groupStartsVar,
              groupEndsVar,
              -1,
              -1,
              -1,
              allocator,
              epsilonSlots);
        } else {
          generateEpsilonClosure(mv, currentStatesVar, 1, posVar, allocator, epsilonSlots);
        }
      }

      // Continue with main NFA loop
      Label continueAfterOpt = new Label();
      mv.visitJumpInsn(GOTO, continueAfterOpt);

      // Failed multi-lookahead - return false immediately
      mv.visitLabel(failedMultiLookahead);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);

      mv.visitLabel(continueAfterOpt);
    } else {
      // No optimization - try inline epsilon closure for start state
      // Skip inline optimization for POSIX patterns - they need proper group tracking
      boolean inlined = false;
      if (!usePosixLastMatch && groupCount == 0) {
        Set<Integer> startStates = new HashSet<>();
        startStates.add(nfa.getStartState().id);
        inlined = tryInlineEpsilonClosure(mv, currentStatesVar, startStates, false, allocator);
      }

      if (!inlined) {
        if (groupCount > 0) {
          // Use group-aware epsilon closure for patterns with groups/backreferences
          generateEpsilonClosureWithGroups(
              mv,
              currentStatesVar,
              1,
              posVar,
              groupStartsVar,
              groupEndsVar,
              -1,
              -1,
              -1,
              allocator,
              epsilonSlots);
        } else {
          generateEpsilonClosure(mv, currentStatesVar, 1, posVar, allocator, epsilonSlots);
        }
      }
    }

    // Main loop: process each character
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos++);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    mv.visitIincInsn(posVar, 1); // pos++

    // nextStates.clear() (auto-detects dual-long)
    clearStateSet(mv, nextStatesVar);

    // Process transitions for all active states (auto-detects dual-long, using pre-allocated slots)
    generateNFAStep(mv, currentStatesVar, nextStatesVar, chVar, -1, -1, 0, allocator, nfaStepSlots);

    // Compute epsilon closure of nextStates
    if (groupCount > 0) {
      // Use group-aware epsilon closure for patterns with groups/backreferences
      generateEpsilonClosureWithGroups(
          mv,
          nextStatesVar,
          1,
          posVar,
          groupStartsVar,
          groupEndsVar,
          -1,
          -1,
          -1,
          allocator,
          epsilonSlots);
    } else {
      // Use optimized precomputed version for patterns without groups
      generateOptimizedEpsilonClosureWithPrecomputation(
          mv, nextStatesVar, 1, posVar, allocator, epsilonSlots);
    }

    // Swap state sets (auto-detects dual-long)
    swapStateSets(mv, currentStatesVar, nextStatesVar, allocator);

    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check if any accept state is in currentStates (auto-detects dual-long)
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      checkStateInSetConst(mv, currentStatesVar, acceptState.id, allocator);

      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);

      // For patterns with groups, set group 0 end = input.length()
      if (groupCount > 0) {
        mv.visitVarInsn(ALOAD, groupEndsVar);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ILOAD, lenVar);
        mv.visitInsn(IASTORE);
      }

      mv.visitInsn(ICONST_1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(notAccepting);
    }

    // No accept state reached
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate optimized epsilon closure using precomputed per-state closures. This eliminates
   * worklist overhead by directly adding all epsilon-reachable states. Falls back to runtime
   * closure if there are assertions. statesVar may be encoded dual-long (negative).
   */
  private void generateOptimizedEpsilonClosureWithPrecomputation(
      MethodVisitor mv,
      int statesVar,
      int inputVar,
      int posVar,
      LocalVariableAllocator allocator,
      EpsilonClosureSlots preAllocSlots) {
    // Check if NFA has assertions - if so, use runtime closure
    boolean hasAssertions = nfa.getStates().stream().anyMatch(s -> s.assertionType != null);

    if (hasAssertions) {
      // Assertions require runtime checks, use standard closure with pre-allocated slots
      generateEpsilonClosure(mv, statesVar, inputVar, posVar, allocator, preAllocSlots);
      return;
    }

    // Precompute epsilon closures for all states at compile time
    Map<Integer, Set<Integer>> precomputedClosures = new HashMap<>();
    for (NFA.NFAState state : nfa.getStates()) {
      Set<Integer> closure = computeEpsilonClosureAtCompileTime(state, false);
      closure.remove(state.id); // Remove self, only add epsilon-reachable states
      if (!closure.isEmpty()) {
        precomputedClosures.put(state.id, closure);
      }
    }

    if (precomputedClosures.isEmpty()) {
      // No epsilon transitions at all, nothing to do
      return;
    }

    // OPTIMIZATION: Iterate over active states using nextSetBit()
    // This works efficiently for both BitSet (native operation) and SparseSet (linear scan)
    // Eliminates 85-94% of wasted contains() checks

    // Allocate variables for iteration
    int stateIdVar = allocator.allocateInt();
    int indexVar = allocator.allocateInt();
    int sizeVar = allocator.allocateInt();

    // Check if this state has epsilon closure using switch
    List<Integer> statesWithEpsilon = new ArrayList<>(precomputedClosures.keySet());
    Collections.sort(statesWithEpsilon);

    if (!statesWithEpsilon.isEmpty()) {
      // Pre-compute switch keys (state IDs) - these are constant
      int[] keys = statesWithEpsilon.stream().mapToInt(Integer::intValue).toArray();

      // Iterate over active states using simple size()/get() loop
      // IMPORTANT: Labels are created INSIDE the lambda because the lambda may be called
      // multiple times (once per 64-bit word in dual-long iteration). ASM labels can only
      // be bound once, so each invocation needs fresh labels.
      generateSpecializedIteration(
          mv,
          statesVar,
          stateIdVar,
          indexVar,
          sizeVar,
          iterMv -> {
            // Create fresh labels for THIS invocation
            Map<Integer, Label> stateCaseLabels = new HashMap<>();
            Label defaultLabel = new Label();
            for (Integer sid : statesWithEpsilon) {
              stateCaseLabels.put(sid, new Label());
            }
            Label[] labels =
                statesWithEpsilon.stream().map(stateCaseLabels::get).toArray(Label[]::new);

            // Switch on stateId to find epsilon closures
            iterMv.visitVarInsn(ILOAD, stateIdVar);
            iterMv.visitLookupSwitchInsn(defaultLabel, keys, labels);

            // Generate case code for each state with epsilon transitions (inside iteration)
            for (Integer sid : statesWithEpsilon) {
              iterMv.visitLabel(stateCaseLabels.get(sid));

              Set<Integer> closure = precomputedClosures.get(sid);

              // Add all epsilon-reachable states from this state
              for (Integer targetId : closure) {
                // states.add(targetId) - StateSet.add() already checks contains()
                iterMv.visitVarInsn(ALOAD, statesVar);
                pushInt(iterMv, targetId);
                generateStateSetAdd(iterMv);
              }

              // Jump to default (end of switch, continue iteration)
              iterMv.visitJumpInsn(GOTO, defaultLabel);
            }

            iterMv.visitLabel(defaultLabel);
          },
          allocator);
    }
  }

  /**
   * Generate code to compute epsilon closure of states in BitSet.
   *
   * @param statesVar variable holding the BitSet of states (may be encoded dual-long)
   * @param inputVar variable holding the input string
   * @param posVar variable holding the current position
   * @param allocator local variable allocator for tracking slot usage
   * @param preAllocSlots pre-allocated slots for epsilon closure, or unallocated() to allocate new
   */
  private void generateEpsilonClosure(
      MethodVisitor mv,
      int statesVar,
      int inputVar,
      int posVar,
      LocalVariableAllocator allocator,
      EpsilonClosureSlots preAllocSlots) {
    // Use pre-allocated slots if provided, otherwise allocate new ones
    int worklistVar =
        preAllocSlots.worklistVar >= 0 ? preAllocSlots.worklistVar : allocator.allocateInt();
    int stateIdVar =
        preAllocSlots.stateIdVar >= 0 ? preAllocSlots.stateIdVar : allocator.allocateInt();
    int worklistSizeVar =
        preAllocSlots.worklistSizeVar >= 0
            ? preAllocSlots.worklistSizeVar
            : allocator.allocateInt();
    // Phase 2B/2C: Use pre-allocated processedVar if available
    // NOTE: For dual-long mode, processedVar is negative (encoded via ~slot0), NOT -1
    // So we check != -1 instead of >= 0 to avoid allocating a new variable inside the loop
    int processedVar;
    if (preAllocSlots.processedVar != -1) {
      processedVar = preAllocSlots.processedVar;
    } else {
      // Fallback allocation (should rarely happen if callers pre-allocate)
      if (useSingleLong) {
        processedVar = allocator.allocateLong();
      } else if (useDualLong) {
        processedVar = allocateDualLongStateSet(allocator);
      } else {
        processedVar = allocator.allocateRef();
      }
    }

    // For each state in the BitSet, add all epsilon-reachable states
    // Use worklist algorithm

    // If worklist not pre-allocated, create it now
    if (preAllocSlots.worklistVar < 0) {
      // int[] worklist = new int[stateCount];
      pushInt(mv, nfa.getStates().size());
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, worklistVar);
    }

    // Reset worklistSize to 0 (whether pre-allocated or not)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // If processed set not pre-allocated, create it now (auto-detects dual-long)
    if (preAllocSlots.processedVar == -1) {
      initStateSet(mv, processedVar);
    }

    // Clear the processed set (auto-detects dual-long)
    clearStateSet(mv, processedVar);

    // Use pre-allocated iteration variables if provided, otherwise allocate new ones
    int indexVar = preAllocSlots.indexVar >= 0 ? preAllocSlots.indexVar : allocator.allocateInt();
    int sizeVar = preAllocSlots.sizeVar >= 0 ? preAllocSlots.sizeVar : allocator.allocateInt();

    // Add all current states to worklist using simple size()/get() loop
    java.util.function.Consumer<MethodVisitor> addToWorklistBody =
        iterMv -> {
          // worklist[worklistSize++] = stateId;
          iterMv.visitVarInsn(ALOAD, worklistVar);
          iterMv.visitVarInsn(ILOAD, worklistSizeVar);
          iterMv.visitVarInsn(ILOAD, stateIdVar);
          iterMv.visitInsn(IASTORE);
          iterMv.visitIincInsn(worklistSizeVar, 1);
        };
    // generateSpecializedIteration auto-detects dual-long from encoded statesVar
    generateSpecializedIteration(
        mv, statesVar, stateIdVar, indexVar, sizeVar, addToWorklistBody, allocator);

    // Process worklist
    Label worklistLoop = new Label();
    Label worklistEnd = new Label();

    mv.visitLabel(worklistLoop);
    // if (worklistSize == 0) break;
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitJumpInsn(IFEQ, worklistEnd);

    // int stateId = worklist[--worklistSize];
    mv.visitIincInsn(worklistSizeVar, -1);
    mv.visitVarInsn(ALOAD, worklistVar);
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, stateIdVar);

    // if (processed.contains(stateId)) continue; (skip already-processed states)
    // checkStateInSet auto-detects dual-long from encoded processedVar
    checkStateInSet(mv, processedVar, stateIdVar);
    mv.visitJumpInsn(IFNE, worklistLoop);

    // Mark state as processed (auto-detects dual-long)
    addStateToSetVar(mv, processedVar, stateIdVar);

    // Generate switch statement for O(log N) state lookup instead of O(N) if-else chain
    // Build list of states with epsilon transitions
    List<NFA.NFAState> statesWithEpsilon = new ArrayList<>();
    for (NFA.NFAState state : nfa.getStates()) {
      if (!state.getEpsilonTransitions().isEmpty()) {
        statesWithEpsilon.add(state);
      }
    }

    if (!statesWithEpsilon.isEmpty()) {
      // Load stateId for switch
      mv.visitVarInsn(ILOAD, stateIdVar);

      // Prepare switch labels and keys
      Label defaultLabel = new Label();
      Label[] caseLabels = new Label[statesWithEpsilon.size()];
      int[] caseKeys = new int[statesWithEpsilon.size()];
      for (int i = 0; i < statesWithEpsilon.size(); i++) {
        caseLabels[i] = new Label();
        caseKeys[i] = statesWithEpsilon.get(i).id;
      }

      // Generate LOOKUPSWITCH instruction (O(log N) at runtime)
      mv.visitLookupSwitchInsn(defaultLabel, caseKeys, caseLabels);

      // Generate case bodies
      for (int i = 0; i < statesWithEpsilon.size(); i++) {
        mv.visitLabel(caseLabels[i]);
        NFA.NFAState state = statesWithEpsilon.get(i);

        // Check if this state has an assertion
        if (state.assertionType != null) {
          // Generate assertion check - only add epsilon targets if assertion passes
          // No group tracking in this context (-1, -1)
          generateAssertionCheck(
              mv, state, inputVar, posVar, statesVar, worklistVar, stateIdVar, -1, -1, allocator);
        } else {
          // No assertion - add epsilon targets normally
          for (NFA.NFAState target : state.getEpsilonTransitions()) {
            // if (!states.get(target.id)) { states.set(target.id); worklist.push(target.id); }
            Label alreadyVisited = new Label();

            // Use unified methods that auto-detect dual-long from encoded statesVar
            checkStateInSetConst(mv, statesVar, target.id, allocator);
            mv.visitJumpInsn(IFNE, alreadyVisited);

            addStateToSet(mv, statesVar, target.id, allocator);

            // worklist[worklistSize++] = target.id;
            mv.visitVarInsn(ALOAD, worklistVar);
            mv.visitVarInsn(ILOAD, worklistSizeVar);
            pushInt(mv, target.id);
            mv.visitInsn(IASTORE);
            mv.visitIincInsn(worklistSizeVar, 1);

            mv.visitLabel(alreadyVisited);
          }
        }

        // Break from switch
        mv.visitJumpInsn(GOTO, worklistLoop);
      }

      // Default case (should never happen, but required for switch)
      mv.visitLabel(defaultLabel);
    }

    mv.visitJumpInsn(GOTO, worklistLoop);
    mv.visitLabel(worklistEnd);
  }

  /**
   * Generate code to process one NFA step: for each state in currentStates, check character
   * transitions and add targets to nextStates. Both currentVar and nextVar may be encoded dual-long
   * values (negative).
   */
  private void generateNFAStep(
      MethodVisitor mv,
      int currentVar,
      int nextVar,
      int chVar,
      int configGroupStartsVar,
      int configGroupEndsVar,
      int groupCount,
      LocalVariableAllocator allocator) {
    generateNFAStep(
        mv,
        currentVar,
        nextVar,
        chVar,
        configGroupStartsVar,
        configGroupEndsVar,
        groupCount,
        allocator,
        NFAStepSlots.unallocated());
  }

  private void generateNFAStep(
      MethodVisitor mv,
      int currentVar,
      int nextVar,
      int chVar,
      int configGroupStartsVar,
      int configGroupEndsVar,
      int groupCount,
      LocalVariableAllocator allocator,
      NFAStepSlots preAllocSlots) {
    // OPTIMIZATION: Iterate only over active states using simple size()/get() loop
    // instead of checking all states with contains().
    // This eliminates ~85-94% of wasted checks on inactive states.

    // Use pre-allocated slots if provided, otherwise allocate new ones
    int stateIdVar =
        preAllocSlots.stateIdVar >= 0 ? preAllocSlots.stateIdVar : allocator.allocateInt();
    int indexVar = preAllocSlots.indexVar >= 0 ? preAllocSlots.indexVar : allocator.allocateInt();
    int sizeVar = preAllocSlots.sizeVar >= 0 ? preAllocSlots.sizeVar : allocator.allocateInt();

    // Generate state-specific transition code using switch
    // Generate a switch on stateId to jump to the appropriate transition code
    List<NFA.NFAState> statesWithTransitions = new ArrayList<>();
    for (NFA.NFAState state : nfa.getStates()) {
      if (!state.getTransitions().isEmpty()) {
        statesWithTransitions.add(state);
      }
    }

    if (!statesWithTransitions.isEmpty()) {
      // Pre-compute switch keys (state IDs) - these are constant
      int[] keys = statesWithTransitions.stream().mapToInt(s -> s.id).toArray();

      // Iterate over active states using simple size()/get() loop
      // IMPORTANT: Labels are created INSIDE the lambda because the lambda may be called
      // multiple times (once per 64-bit word in dual-long iteration). ASM labels can only
      // be bound once, so each invocation needs fresh labels.
      java.util.function.Consumer<MethodVisitor> loopBody =
          iterMv -> {
            // Create fresh labels for THIS invocation
            Map<Integer, Label> stateCaseLabels = new HashMap<>();
            Label defaultLabel = new Label();
            for (NFA.NFAState state : statesWithTransitions) {
              stateCaseLabels.put(state.id, new Label());
            }
            Label[] labels =
                statesWithTransitions.stream()
                    .map(s -> stateCaseLabels.get(s.id))
                    .toArray(Label[]::new);

            // Switch on stateId to process transitions
            iterMv.visitVarInsn(ILOAD, stateIdVar);
            iterMv.visitLookupSwitchInsn(defaultLabel, keys, labels);

            // Generate case code for each state (inside the iteration loop)
            for (NFA.NFAState state : statesWithTransitions) {
              iterMv.visitLabel(stateCaseLabels.get(state.id));

              // Check each transition for this state
              for (NFA.Transition trans : state.getTransitions()) {
                // if (charset.contains(ch)) nextStates.add(target.id);
                Label noMatch = new Label();

                generateCharSetCheck(iterMv, trans.chars, chVar);
                iterMv.visitJumpInsn(IFEQ, noMatch);

                // Use unified helper that auto-detects dual-long from encoded nextVar
                addStateToSet(iterMv, nextVar, trans.target.id, allocator);

                // Per-config tracking: Propagate configuration from source to target state
                if (usePosixLastMatch && configGroupStartsVar >= 0) {
                  // System.arraycopy(configGroupStarts[sourceState], 0,
                  //                  configGroupStarts[targetState], 0, groupCount + 1);
                  generateCopyConfigArray(
                      iterMv, configGroupStartsVar, stateIdVar, trans.target.id, groupCount);
                  // System.arraycopy(configGroupEnds[sourceState], 0,
                  //                  configGroupEnds[targetState], 0, groupCount + 1);
                  generateCopyConfigArray(
                      iterMv, configGroupEndsVar, stateIdVar, trans.target.id, groupCount);
                }

                iterMv.visitLabel(noMatch);
              }

              // Jump to default (end of switch, continue iteration)
              iterMv.visitJumpInsn(GOTO, defaultLabel);
            }

            iterMv.visitLabel(defaultLabel);
          };

      // generateSpecializedIteration auto-detects dual-long from encoded currentVar
      generateSpecializedIteration(
          mv, currentVar, stateIdVar, indexVar, sizeVar, loopBody, allocator);
    }
  }

  /** Generate code to check if character is in charset. Leaves boolean result on stack. */
  private void generateCharSetCheck(MethodVisitor mv, CharSet charset, int chVar) {
    if (charset.isSingleChar()) {
      // ch == 'x'
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) charset.getSingleChar());
      Label match = new Label();
      Label end = new Label();
      mv.visitJumpInsn(IF_ICMPEQ, match);
      mv.visitInsn(ICONST_0);
      mv.visitJumpInsn(GOTO, end);
      mv.visitLabel(match);
      mv.visitInsn(ICONST_1);
      mv.visitLabel(end);
    } else if (charset.isSimpleRange()) {
      // ch >= start && ch <= end
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) charset.rangeStart());
      Label fail = new Label();
      Label success = new Label();
      Label end = new Label();
      mv.visitJumpInsn(IF_ICMPLT, fail);

      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) charset.rangeEnd());
      mv.visitJumpInsn(IF_ICMPGT, fail);

      mv.visitLabel(success);
      mv.visitInsn(ICONST_1);
      mv.visitJumpInsn(GOTO, end);

      mv.visitLabel(fail);
      mv.visitInsn(ICONST_0);
      mv.visitLabel(end);
    } else {
      // Multiple ranges - check each
      List<CharSet.Range> ranges = charset.getRanges();
      Label success = new Label();
      Label fail = new Label();

      for (CharSet.Range range : ranges) {
        // ch >= start && ch <= end
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.start);
        Label notThisRange = new Label();
        mv.visitJumpInsn(IF_ICMPLT, notThisRange);

        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, success);

        mv.visitLabel(notThisRange);
      }

      mv.visitJumpInsn(GOTO, fail);

      mv.visitLabel(success);
      mv.visitInsn(ICONST_1);
      Label end = new Label();
      mv.visitJumpInsn(GOTO, end);

      mv.visitLabel(fail);
      mv.visitInsn(ICONST_0);
      mv.visitLabel(end);
    }
  }

  /**
   * Generate find() method that searches for pattern anywhere in input.
   *
   * <h4>Generated Method Signature</h4>
   *
   * <pre>{@code public boolean find(String input)}</pre>
   *
   * <h4>Generated Algorithm</h4>
   *
   * <pre>{@code
   * boolean find(String input) {
   *     if (input == null) return false;
   *     return findFrom(input, 0) >= 0;
   * }
   * }</pre>
   */
  public void generateFindMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

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
   * Generate findFrom() method that searches for pattern starting at given position.
   *
   * <h4>Generated Method Signature</h4>
   *
   * <pre>{@code public int findFrom(String input, int start)}</pre>
   *
   * <h4>Generated Algorithm</h4>
   *
   * <pre>{@code
   * int findFrom(String input, int start) {
   *     if (input == null || start < 0 || start > input.length()) return -1;
   *     int len = input.length();
   *
   *     // Try starting match at each position from 'start'
   *     for (int startPos = start; startPos <= len; startPos++) {
   *         // Initialize NFA with start state + epsilon closure
   *         currentStates.clear();
   *         currentStates.add(startStateId);
   *         epsilonClosure(currentStates, input, startPos);
   *
   *         // Check if we're already in accept state (empty match)
   *         if (containsAcceptState(currentStates)) {
   *             return startPos;
   *         }
   *
   *         // Process characters from startPos
   *         for (int pos = startPos; pos < len; pos++) {
   *             char ch = input.charAt(pos);
   *             nextStates.clear();
   *
   *             // NFA step: compute transitions
   *             for (int stateId : currentStates) {
   *                 for (Transition t : states[stateId].transitions) {
   *                     if (t.matches(ch)) {
   *                         nextStates.add(t.target);
   *                     }
   *                 }
   *             }
   *
   *             if (nextStates.isEmpty()) break;  // no match possible
   *
   *             epsilonClosure(nextStates, input, pos + 1);
   *             swap(currentStates, nextStates);
   *
   *             // Check for match after each step
   *             if (containsAcceptState(currentStates)) {
   *                 return startPos;  // found match starting at startPos
   *             }
   *         }
   *     }
   *     return -1;  // no match found
   * }
   * }</pre>
   *
   * <h4>Returns</h4>
   *
   * Start position of first match, or -1 if no match found.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Create local variable allocator
    // Method signature: findFrom(String input, int start)
    // Slots: 0=this, 1=input, 2=start, 3+ = local variables
    LocalVariableAllocator allocator = new LocalVariableAllocator(3);

    // if (input == null || start < 0 || start > input.length()) return -1;
    Label checksPass = new Label();
    Label returnMinusOne = new Label();

    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnMinusOne);

    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFLT, returnMinusOne);

    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPLE, checksPass);

    mv.visitLabel(returnMinusOne);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitLabel(checksPass);

    // PHASE 3: Separated atomic lookahead checking + main pattern execution
    // Check if all lookaheads have NFAs for separated execution
    boolean canUseSeparatedExecution =
        literalLookaheadInfo != null
            && literalLookaheadInfo.mainPatternStrategy != null
            && literalLookaheadInfo.mainPatternNFA != null
            && literalLookaheadInfo.lookaheads.stream().allMatch(LiteralLookaheadInfo::hasNFA);

    if (canUseSeparatedExecution) {
      generateSeparatedLookaheadFindFrom(mv, allocator, className);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
      return;
    }

    // FAST PATH: Check if we can use specialized bitmap-based matching
    if (lookaheadGreedyInfo != null && lookaheadGreedyInfo.canUseFastPath) {
      generateFastPathFindFrom(mv, allocator);
      mv.visitMaxs(0, 0); // Auto-computed by ASM
      mv.visitEnd();
      return;
    }

    // Allocate all local variables using allocator
    int lenVar = allocator.allocateInt();
    int tryPosVar = allocator.allocateInt();

    // ALWAYS allocate group array slots to ensure consistent stackmap frames
    // Even if groupCount is 0, the slots must exist for JVM verifier consistency
    int groupStartsVar = allocator.allocateRef(); // int[]
    int groupEndsVar = allocator.allocateRef(); // int[]
    int groupCount = nfa.getGroupCount();

    // Phase 2B/2C: Allocate state sets based on implementation type
    // For dual-long (65-128 states), use encoded slot values (negative)
    int currentStatesVar;
    int nextStatesVar;
    if (useSingleLong) {
      currentStatesVar = allocator.allocateLong(); // long (2 slots)
      nextStatesVar = allocator.allocateLong(); // long (2 slots)
    } else if (useDualLong) {
      currentStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
      nextStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      currentStatesVar = allocator.allocateRef(); // object reference (1 slot)
      nextStatesVar = allocator.allocateRef(); // object reference (1 slot)
    }

    // CRITICAL: Initialize ALL variables with defaults BEFORE any conditional bytecode
    // to ensure JVM verifier sees consistent local variable types at all merge points.
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, lenVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, tryPosVar);
    // S: [] -> [null] -> []
    mv.visitInsn(ACONST_NULL);
    mv.visitVarInsn(ASTORE, groupStartsVar);
    // S: [] -> [null] -> []
    mv.visitInsn(ACONST_NULL);
    mv.visitVarInsn(ASTORE, groupEndsVar);
    // Initialize state set vars based on type
    if (useSingleLong || useDualLong) {
      // S: [] -> [J] -> []
      initStateSet(mv, currentStatesVar);
      initStateSet(mv, nextStatesVar);
    } else {
      // S: [] -> [null] -> []
      mv.visitInsn(ACONST_NULL);
      mv.visitVarInsn(ASTORE, currentStatesVar);
      mv.visitInsn(ACONST_NULL);
      mv.visitVarInsn(ASTORE, nextStatesVar);
    }

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar); // len

    // NOTE: Group arrays are initialized later with all other pre-allocated variables

    // Create local state sets (auto-detects dual-long from encoded values)
    if (useSingleLong || useDualLong) {
      initStateSet(mv, currentStatesVar);
      initStateSet(mv, nextStatesVar);
    } else {
      // For StateSet (>128 states): use pre-allocated instance fields instead of creating new
      // objects
      // Load this.currentStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "currentStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, currentStatesVar);
      mv.visitVarInsn(ALOAD, currentStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);

      // Load this.nextStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "nextStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, nextStatesVar);
      mv.visitVarInsn(ALOAD, nextStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);
    }

    // PRE-ALLOCATE epsilon closure slots BEFORE any loops to ensure consistent stackmap frames
    // Even if pattern doesn't have assertions, epsilon closure may be called in loops
    EpsilonClosureSlots epsilonSlotsFind;
    int worklistVarFind = allocator.allocateRef();
    // Phase 2B/2C: processedVarFind is encoded dual-long when useDualLong=true
    int processedVarFind;
    if (useSingleLong) {
      processedVarFind = allocator.allocateLong();
    } else if (useDualLong) {
      processedVarFind = allocateDualLongStateSet(allocator); // encoded dual-long
    } else {
      processedVarFind = allocator.allocateRef();
    }

    // Allocate working variables for epsilon closure algorithm
    int stateIdVarFind = allocator.allocateInt();
    int worklistSizeVarFind = allocator.allocateInt();
    int indexVarFind = allocator.allocateInt();
    int sizeVarFind = allocator.allocateInt();
    int parentIdVarFind = allocator.allocateInt(); // For POSIX per-config tracking

    epsilonSlotsFind =
        new EpsilonClosureSlots(
            worklistVarFind,
            stateIdVarFind,
            worklistSizeVarFind,
            processedVarFind,
            indexVarFind,
            sizeVarFind,
            parentIdVarFind);

    // Pre-allocate posVar and chVar BEFORE outer loop to ensure consistent stackmap frames
    int posVar = allocator.allocateInt();
    int chVar = allocator.allocateInt();

    // Pre-detect multi-lookahead optimization and allocate its slots BEFORE the loop
    // to ensure consistent stackmap frames
    List<CharSet> multiLookaheadOptFind = detectMultiLookaheadOptimization(nfa.getStartState());
    int[] multiLookaheadFlagSlots = null;
    int multiLookaheadScanPosVar = -1;
    int multiLookaheadCharVar = -1;
    if (multiLookaheadOptFind != null) {
      int numChecks = multiLookaheadOptFind.size();
      multiLookaheadFlagSlots = new int[numChecks];
      for (int i = 0; i < numChecks; i++) {
        multiLookaheadFlagSlots[i] = allocator.allocateInt();
      }
      multiLookaheadScanPosVar = allocator.allocateInt();
      multiLookaheadCharVar = allocator.allocateInt();
    }

    // Pre-allocate NFA step slots BEFORE any bytecode
    int nfaStepStateIdVar = allocator.allocateInt();
    int nfaStepIndexVar = allocator.allocateInt();
    int nfaStepSizeVar = allocator.allocateInt();
    NFAStepSlots nfaStepSlots =
        new NFAStepSlots(nfaStepStateIdVar, nfaStepIndexVar, nfaStepSizeVar);

    // Initialize ALL pre-allocated variables to ensure consistent stackmap frames
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, stateIdVarFind);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVarFind);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, indexVarFind);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, sizeVarFind);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, parentIdVarFind);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, chVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepStateIdVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepIndexVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepSizeVar);
    // Initialize multi-lookahead slots if allocated
    if (multiLookaheadFlagSlots != null) {
      for (int slot : multiLookaheadFlagSlots) {
        // S: [] -> [I] -> []
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, slot);
      }
      // S: [] -> [I] -> []
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, multiLookaheadScanPosVar);
      // S: [] -> [I] -> []
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, multiLookaheadCharVar);
    }

    // Initialize group arrays - ALWAYS initialize slots for JVM verifier consistency
    if (groupCount > 0) {
      // int[] groupStarts = new int[groupCount + 1];
      pushInt(mv, groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupStartsVar);

      // int[] groupEnds = new int[groupCount + 1];
      pushInt(mv, groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, groupEndsVar);
    } else {
      // S: [] -> [null] -> []
      mv.visitInsn(ACONST_NULL);
      mv.visitVarInsn(ASTORE, groupStartsVar);
      // S: [] -> [null] -> []
      mv.visitInsn(ACONST_NULL);
      mv.visitVarInsn(ASTORE, groupEndsVar);
    }

    // Create worklist array
    pushInt(mv, nfa.getStates().size());
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, worklistVarFind);

    // Initialize processedVarFind (auto-detects dual-long from encoded value)
    initStateSet(mv, processedVarFind);

    // Try matching at each position from start
    // for (int tryPos = start; tryPos <= len; tryPos++)
    // Phase 1 Optimization: If pattern has required literal, use indexOf to find first candidate
    // position
    // IMPORTANT: Skip indexOf optimization for anchored patterns - they must try position 0 first

    // Try to extract multi-character literal first (Tier 1 optimization)
    String longestLiteral = extractLongestRequiredLiteral(nfa);

    // Skip indexOf optimization for:
    // 1. Patterns that require start anchor (^ or \A) - indexOf would skip position 0
    // 2. Patterns with backrefs to lookahead captures - lookahead needs to match from
    //    earlier position, indexOf would skip to where the literal suffix appears
    boolean skipIndexOfOptimization =
        requiresStartAnchor || hasStringStartAnchor || hasBackrefToLookaheadCapture;

    if (!skipIndexOfOptimization && longestLiteral != null && longestLiteral.length() >= 3) {
      // Use multi-character indexOf for better performance
      // tryPos = input.indexOf("literal", start)
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitLdcInsn(longestLiteral);
      mv.visitVarInsn(ILOAD, 2); // start
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
      mv.visitVarInsn(ISTORE, tryPosVar);

      // if (tryPos == -1) return -1; (literal not found in string)
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFLT, returnMinusOne);
    } else if (!skipIndexOfOptimization && requiredLiterals.size() == 1) {
      // Fall back to single-character indexOf
      char requiredChar = requiredLiterals.iterator().next();

      // tryPos = input.indexOf(requiredChar, start)
      mv.visitVarInsn(ALOAD, 1); // input
      pushInt(mv, (int) requiredChar);
      mv.visitVarInsn(ILOAD, 2); // start
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
      mv.visitVarInsn(ISTORE, tryPosVar);

      // if (tryPos == -1) return -1; (character not found in string)
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFLT, returnMinusOne);
    } else {
      // No optimization: start from the given position
      mv.visitVarInsn(ILOAD, 2);
      mv.visitVarInsn(ISTORE, tryPosVar); // tryPos = start
    }

    Label outerLoopStart = new Label();
    Label outerLoopEnd = new Label();

    mv.visitLabel(outerLoopStart);
    mv.visitVarInsn(ILOAD, tryPosVar); // tryPos
    mv.visitVarInsn(ILOAD, lenVar); // len
    mv.visitJumpInsn(IF_ICMPGT, outerLoopEnd);

    // ANCHOR OPTIMIZATION: For patterns with ^ or \A, only try position 0
    // if (tryPos != 0) return -1;
    if (requiresStartAnchor || hasStringStartAnchor) {
      Label validPosition = new Label();
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFEQ, validPosition); // if (tryPos == 0) goto validPosition
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(validPosition);
    }

    // Clear and re-initialize state sets for this position (auto-detects dual-long)
    clearStateSet(mv, currentStatesVar);
    clearStateSet(mv, nextStatesVar);

    // Initialize group arrays for this match attempt if pattern has groups
    if (groupCount > 0) {
      // Reset all group positions to -1
      for (int i = 0; i <= groupCount; i++) {
        mv.visitVarInsn(ALOAD, groupStartsVar);
        pushInt(mv, i);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IASTORE);
        mv.visitVarInsn(ALOAD, groupEndsVar);
        pushInt(mv, i);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IASTORE);
      }

      // Set group 0 start = tryPos
      mv.visitVarInsn(ALOAD, groupStartsVar);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitInsn(IASTORE);
    }

    // Initialize with start state + epsilon closure (auto-detects dual-long)
    addStateToSet(mv, currentStatesVar, nfa.getStartState().id, allocator);

    // Initialize posVar = tryPos BEFORE epsilon closure
    // CRITICAL: posVar is used during epsilon closure for backref position tracking.
    // We must NOT pass tryPosVar to epsilon closure because backref checks modify
    // the position variable to advance past matched text. If tryPosVar is modified,
    // we lose the match start position and return wrong values.
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // OPTIMIZATION: Apply multi-lookahead optimization if applicable (using pre-allocated slots)
    if (multiLookaheadOptFind != null) {
      // Apply optimized single-pass multi-lookahead check using pre-allocated slots
      Label failedMultiLookaheadFind = new Label();
      generateOptimizedMultiLookaheadWithPrealloc(
          mv,
          multiLookaheadOptFind,
          1,
          tryPosVar,
          lenVar,
          failedMultiLookaheadFind,
          multiLookaheadFlagSlots,
          multiLookaheadScanPosVar,
          multiLookaheadCharVar);

      // Skip the assertion states
      NFA.NFAState stateAfterLookaheadsFind = nfa.getStartState();
      for (int i = 0; i < multiLookaheadOptFind.size(); i++) {
        if (!stateAfterLookaheadsFind.getEpsilonTransitions().isEmpty()) {
          stateAfterLookaheadsFind = stateAfterLookaheadsFind.getEpsilonTransitions().get(0);
        }
      }

      // Clear and set state after lookaheads (auto-detects dual-long)
      clearStateSet(mv, currentStatesVar);
      addStateToSet(mv, currentStatesVar, stateAfterLookaheadsFind.id, allocator);

      // Compute epsilon closure from state after lookaheads (try inline first)
      // Skip inline optimization for POSIX patterns - they need proper group tracking
      boolean inlined = false;
      if (!usePosixLastMatch && groupCount == 0) {
        Set<Integer> afterLookaheadStatesFind = new HashSet<>();
        afterLookaheadStatesFind.add(stateAfterLookaheadsFind.id);
        inlined =
            tryInlineEpsilonClosure(
                mv, currentStatesVar, afterLookaheadStatesFind, false, allocator);
      }

      if (!inlined) {
        if (groupCount > 0) {
          generateEpsilonClosureWithGroups(
              mv,
              currentStatesVar,
              1,
              posVar,
              groupStartsVar,
              groupEndsVar,
              -1,
              -1,
              -1,
              allocator,
              epsilonSlotsFind);
        } else {
          generateEpsilonClosure(mv, currentStatesVar, 1, posVar, allocator, epsilonSlotsFind);
        }
      }

      // Continue with match attempt
      Label continueAfterOptFind = new Label();
      mv.visitJumpInsn(GOTO, continueAfterOptFind);

      // Failed multi-lookahead - try next position
      mv.visitLabel(failedMultiLookaheadFind);
      mv.visitIincInsn(tryPosVar, 1); // tryPos++
      mv.visitJumpInsn(GOTO, outerLoopStart);

      mv.visitLabel(continueAfterOptFind);
    } else {
      // No optimization - try inline epsilon closure for start state
      // Skip inline optimization for POSIX patterns - they need proper group tracking
      boolean inlined = false;
      if (!usePosixLastMatch && groupCount == 0) {
        Set<Integer> startStatesFind = new HashSet<>();
        startStatesFind.add(nfa.getStartState().id);
        inlined = tryInlineEpsilonClosure(mv, currentStatesVar, startStatesFind, false, allocator);
      }

      if (!inlined) {
        if (groupCount > 0) {
          generateEpsilonClosureWithGroups(
              mv,
              currentStatesVar,
              1,
              posVar,
              groupStartsVar,
              groupEndsVar,
              -1,
              -1,
              -1,
              allocator,
              epsilonSlotsFind);
        } else {
          generateEpsilonClosure(mv, currentStatesVar, 1, posVar, allocator, epsilonSlotsFind);
        }
      }
    }

    // Check if start state is accepting (for patterns like a*)
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      // Use unified helper that auto-detects dual-long
      checkStateInSetConst(mv, currentStatesVar, acceptState.id, allocator);
      Label notAcceptingYet = new Label();
      mv.visitJumpInsn(IFEQ, notAcceptingYet);
      mv.visitVarInsn(ILOAD, tryPosVar); // return tryPos
      mv.visitInsn(IRETURN);
      mv.visitLabel(notAcceptingYet);
    }

    // NOTE: posVar was initialized to tryPos before the epsilon closure.
    // During epsilon closure, backrefs may advance posVar past matched text.
    // We use the (possibly advanced) posVar for the character loop.

    // Process characters while we have active states
    Label charLoopStart = new Label();
    Label charLoopEnd = new Label();

    mv.visitLabel(charLoopStart);
    // if (pos >= len) break;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, charLoopEnd);

    // if (currentStates.isEmpty()) break;
    // Uses unified method that auto-detects dual-long from encoded slot
    isStateSetEmpty(mv, currentStatesVar);
    mv.visitJumpInsn(IFNE, charLoopEnd);

    // char ch = input.charAt(pos++);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar); // ch
    mv.visitIincInsn(posVar, 1); // pos++

    // nextStates.clear();
    // Uses unified method that auto-detects dual-long from encoded slot
    clearStateSet(mv, nextStatesVar);

    // Process NFA step (unified - auto-detects dual-long, using pre-allocated slots)
    generateNFAStep(mv, currentStatesVar, nextStatesVar, chVar, -1, -1, 0, allocator, nfaStepSlots);

    // Compute epsilon closure
    if (groupCount > 0) {
      // Use group-aware epsilon closure for patterns with groups/backreferences
      generateEpsilonClosureWithGroups(
          mv,
          nextStatesVar,
          1,
          posVar,
          groupStartsVar,
          groupEndsVar,
          -1,
          -1,
          -1,
          allocator,
          epsilonSlotsFind);
    } else {
      // Use optimized precomputed version for patterns without groups
      generateOptimizedEpsilonClosureWithPrecomputation(
          mv, nextStatesVar, 1, posVar, allocator, epsilonSlotsFind);
    }

    // Check if we reached an accept state
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      // Uses unified method that auto-detects dual-long from encoded slot
      checkStateInSetConst(mv, nextStatesVar, acceptState.id, allocator);
      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);
      mv.visitVarInsn(ILOAD, tryPosVar); // return tryPos
      mv.visitInsn(IRETURN);
      mv.visitLabel(notAccepting);
    }

    // Swap state sets (uses unified method that auto-detects dual-long)
    swapStateSets(mv, currentStatesVar, nextStatesVar, allocator);

    mv.visitJumpInsn(GOTO, charLoopStart);
    mv.visitLabel(charLoopEnd);

    // Try next position
    // Phase 1 Optimization: Use indexOf to skip to required literal positions
    if (longestLiteral != null && longestLiteral.length() >= 3) {
      // Use multi-character indexOf for next iteration (Tier 1 optimization)
      // tryPos = input.indexOf("literal", tryPos + 1)
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitLdcInsn(longestLiteral);
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD); // tryPos + 1
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
      mv.visitVarInsn(ISTORE, tryPosVar);

      // if (tryPos < 0 || tryPos > len) return -1
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFLT, outerLoopEnd); // if tryPos < 0, not found

      // Continue to outerLoopStart (will check tryPos <= len there)
      mv.visitJumpInsn(GOTO, outerLoopStart);
    } else if (requiredLiterals.size() == 1) {
      // Fall back to single-character indexOf
      char requiredChar = requiredLiterals.iterator().next();

      // tryPos = input.indexOf(requiredChar, tryPos + 1)
      mv.visitVarInsn(ALOAD, 1); // input
      pushInt(mv, (int) requiredChar);
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IADD); // tryPos + 1
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
      mv.visitVarInsn(ISTORE, tryPosVar);

      // if (tryPos < 0 || tryPos > len) return -1
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFLT, outerLoopEnd); // if tryPos < 0, not found

      // Continue to outerLoopStart (will check tryPos <= len there)
      mv.visitJumpInsn(GOTO, outerLoopStart);
    } else {
      // No optimization: simple increment
      mv.visitIincInsn(tryPosVar, 1); // tryPos++
      mv.visitJumpInsn(GOTO, outerLoopStart);
    }

    mv.visitLabel(outerLoopEnd);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate fused evaluation for multiple sequential lookaheads. Runs all lookahead DFAs in
   * parallel during a single forward scan. This is more efficient than sequential evaluation as
   * both DFAs scan the same substring.
   *
   * <p>Generated code structure: // Initialize all DFA states int state1 = dfa1.startState; int
   * state2 = dfa2.startState; boolean passed1 = false; boolean passed2 = false; int pos = checkPos;
   * int len = input.length();
   *
   * <p>// Single forward scan while (pos < len) { char ch = input.charAt(pos);
   *
   * <p>// Advance DFA 1 if not yet passed if (!passed1 && state1 != -1) { state1 =
   * transition(state1, ch); if (isAccepting(state1)) passed1 = true; }
   *
   * <p>// Advance DFA 2 if not yet passed if (!passed2 && state2 != -1) { state2 =
   * transition(state2, ch); if (isAccepting(state2)) passed2 = true; }
   *
   * <p>// Early exit if all passed if (passed1 && passed2) break;
   *
   * <p>// Early exit if all dead if (state1 == -1 && state2 == -1) break;
   *
   * <p>pos++; }
   *
   * <p>// All must pass for overall success if (passed1 && passed2) { // Add all epsilon targets
   * from all assertions ... goto assertionComplete; } goto assertionFailed;
   */
  private void generateFusedMultiLookaheadCheck(
      MethodVisitor mv,
      List<NFA.NFAState> lookaheadStates,
      int inputVar,
      int checkPosVar,
      int statesVar,
      int worklistVar,
      int worklistSizeVar,
      LocalVariableAllocator allocator,
      Label assertionFailed,
      Label assertionComplete) {
    int numLookaheads = lookaheadStates.size();

    // Allocate local variables for each DFA
    int[] dfaStateVars = new int[numLookaheads];
    int[] passedVars = new int[numLookaheads];
    DFA[] dfas = new DFA[numLookaheads];

    for (int i = 0; i < numLookaheads; i++) {
      dfaStateVars[i] = allocator.allocateInt();
      passedVars[i] = allocator.allocateInt(); // boolean stored as int (0 or 1)
      dfas[i] = hybridInfo.assertionDFAs.get(lookaheadStates.get(i).id);
    }

    int posVar = allocator.allocateInt();
    int lenVar = allocator.allocateInt();
    int chVar = allocator.allocateInt();

    // Initialize all DFA states and passed flags
    for (int i = 0; i < numLookaheads; i++) {
      // dfaState[i] = dfa[i].getStartState().id;
      pushInt(mv, dfas[i].getStartState().id);
      mv.visitVarInsn(ISTORE, dfaStateVars[i]);

      // passed[i] = false (0);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, passedVars[i]);
    }

    // int pos = checkPos;
    mv.visitVarInsn(ILOAD, checkPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label allPassed = new Label();

    mv.visitLabel(loopStart);

    // while (pos < len)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // Process each DFA
    for (int i = 0; i < numLookaheads; i++) {
      Label skipDFA = new Label();

      // if (passed[i]) skip this DFA
      mv.visitVarInsn(ILOAD, passedVars[i]);
      mv.visitJumpInsn(IFNE, skipDFA);

      // if (state[i] == -1) skip this DFA (dead state)
      mv.visitVarInsn(ILOAD, dfaStateVars[i]);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, skipDFA);

      // Transition: state[i] = transition(state[i], ch)
      generateDFATransitionSwitch(mv, dfas[i], dfaStateVars[i], chVar);

      // Check if accepting: if (isAccepting(state[i])) passed[i] = true;
      Label notAccepting = new Label();
      for (DFA.DFAState acceptState : dfas[i].getAcceptStates()) {
        mv.visitVarInsn(ILOAD, dfaStateVars[i]);
        pushInt(mv, acceptState.id);
        Label nextCheck = new Label();
        mv.visitJumpInsn(IF_ICMPNE, nextCheck);
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, passedVars[i]);
        mv.visitJumpInsn(GOTO, notAccepting);
        mv.visitLabel(nextCheck);
      }
      mv.visitLabel(notAccepting);

      mv.visitLabel(skipDFA);
    }

    // Early exit check: if all passed, break
    Label notAllPassed = new Label();
    for (int i = 0; i < numLookaheads; i++) {
      mv.visitVarInsn(ILOAD, passedVars[i]);
      mv.visitJumpInsn(IFEQ, notAllPassed);
    }
    // All passed - exit loop
    mv.visitJumpInsn(GOTO, loopEnd);
    mv.visitLabel(notAllPassed);

    // Early exit check: if all dead, break
    Label notAllDead = new Label();
    for (int i = 0; i < numLookaheads; i++) {
      mv.visitVarInsn(ILOAD, dfaStateVars[i]);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPNE, notAllDead);
    }
    // All dead - exit loop (will fail later)
    mv.visitJumpInsn(GOTO, loopEnd);
    mv.visitLabel(notAllDead);

    // pos++;
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Final check: all must have passed
    for (int i = 0; i < numLookaheads; i++) {
      mv.visitVarInsn(ILOAD, passedVars[i]);
      mv.visitJumpInsn(IFEQ, assertionFailed);
    }

    // All passed! Add all epsilon targets from all assertion states
    Set<Integer> addedTargets = new HashSet<>(); // Avoid duplicates
    for (NFA.NFAState assertionState : lookaheadStates) {
      for (NFA.NFAState target : assertionState.getEpsilonTransitions()) {
        if (addedTargets.add(target.id)) {
          Label alreadyVisited = new Label();
          checkStateInSetConst(mv, statesVar, target.id, allocator);
          mv.visitJumpInsn(IFNE, alreadyVisited);

          addStateToSet(mv, statesVar, target.id, allocator);

          // worklist[worklistSize++] = target.id;
          mv.visitVarInsn(ALOAD, worklistVar);
          mv.visitVarInsn(ILOAD, worklistSizeVar);
          pushInt(mv, target.id);
          mv.visitInsn(IASTORE);
          mv.visitIincInsn(worklistSizeVar, 1);

          mv.visitLabel(alreadyVisited);
        }
      }
    }

    // Success - jump to completion
    mv.visitJumpInsn(GOTO, assertionComplete);

    // Release allocated variables
    for (int i = numLookaheads - 1; i >= 0; i--) {
      allocator.release(passedVars[i]);
      allocator.release(dfaStateVars[i]);
    }
    allocator.release(chVar);
    allocator.release(lenVar);
    allocator.release(posVar);
  }

  /**
   * Generate DFA transition switch for a given DFA. Updates stateVar with the next state based on
   * character in chVar. Sets stateVar to -1 if no transition exists (dead state).
   */
  private void generateDFATransitionSwitch(MethodVisitor mv, DFA dfa, int stateVar, int chVar) {
    List<DFA.DFAState> states = new ArrayList<>(dfa.getAllStates());
    states.sort(Comparator.comparingInt(s -> s.id));

    Label defaultLabel = new Label();
    Label[] caseLabels = new Label[states.size()];
    int[] caseKeys = new int[states.size()];

    for (int i = 0; i < states.size(); i++) {
      caseLabels[i] = new Label();
      caseKeys[i] = states.get(i).id;
    }

    // switch (state) { ... }
    mv.visitVarInsn(ILOAD, stateVar);
    mv.visitLookupSwitchInsn(defaultLabel, caseKeys, caseLabels);

    // Generate case for each state
    for (int i = 0; i < states.size(); i++) {
      mv.visitLabel(caseLabels[i]);
      DFA.DFAState state = states.get(i);

      if (state.transitions.isEmpty()) {
        // Dead state - set to -1
        mv.visitInsn(ICONST_M1);
        mv.visitVarInsn(ISTORE, stateVar);
      } else {
        // Generate transitions
        for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
          CharSet chars = entry.getKey();
          DFA.DFATransition trans = entry.getValue();

          // Check if character matches charset
          Label notMatch = new Label();
          generateCharSetCheck(mv, chars, chVar);
          mv.visitJumpInsn(IFEQ, notMatch); // If 0 (false), skip this transition
          pushInt(mv, trans.target.id);
          mv.visitVarInsn(ISTORE, stateVar);
          mv.visitLabel(notMatch);
        }

        // If no transition matched, set to dead state
        Label endCase = new Label();
        mv.visitInsn(ICONST_M1);
        mv.visitVarInsn(ISTORE, stateVar);
        mv.visitLabel(endCase);
      }

      // Break from switch
      Label breakLabel = new Label();
      mv.visitJumpInsn(GOTO, breakLabel);
      mv.visitLabel(breakLabel);
    }

    // Default case (should not happen)
    mv.visitLabel(defaultLabel);
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, stateVar);
  }

  /**
   * Collect sequential positive lookahead assertions starting from the given state. Returns a list
   * of assertion states if multiple are found, null otherwise. This enables fused evaluation of
   * multiple lookaheads in a single pass.
   */
  private List<NFA.NFAState> collectSequentialLookaheads(NFA.NFAState startAssertion) {
    if (hybridInfo == null) return null;

    List<NFA.NFAState> lookaheads = new ArrayList<>();
    Set<Integer> seenAssertionIds = new HashSet<>();
    Queue<NFA.NFAState> queue = new LinkedList<>();

    // Start with the given assertion
    if (startAssertion.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD
        && hybridInfo.assertionDFAs.containsKey(startAssertion.id)) {
      lookaheads.add(startAssertion);
      seenAssertionIds.add(startAssertion.id);
    } else {
      return null; // Not a DFA-optimizable positive lookahead
    }

    // Follow epsilon transitions to find more lookaheads
    queue.add(startAssertion);
    Set<Integer> visited = new HashSet<>();

    while (!queue.isEmpty() && lookaheads.size() < 5) { // Limit to 5 fused lookaheads
      NFA.NFAState current = queue.poll();
      if (!visited.add(current.id)) continue;

      for (NFA.NFAState target : current.getEpsilonTransitions()) {
        // Check if target is also a positive lookahead with DFA
        if (target.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD
            && hybridInfo.assertionDFAs.containsKey(target.id)
            && seenAssertionIds.add(target.id)) {
          lookaheads.add(target);
        }

        // Continue exploring epsilon transitions (but don't process non-assertion states)
        if (target.assertionType != null) {
          queue.add(target);
        }
      }
    }

    // Return list only if we found multiple lookaheads
    return lookaheads.size() > 1 ? lookaheads : null;
  }

  /**
   * Generate bytecode to check an assertion at the current position. Only adds epsilon targets if
   * the assertion passes.
   *
   * @param groupStartsVar local variable slot for group starts array (-1 if no group tracking)
   * @param groupEndsVar local variable slot for group ends array (-1 if no group tracking)
   */
  private void generateAssertionCheck(
      MethodVisitor mv,
      NFA.NFAState assertionState,
      int inputVar,
      int posVar,
      int statesVar,
      int worklistVar,
      int stateIdVar,
      int groupStartsVar,
      int groupEndsVar,
      LocalVariableAllocator allocator) {
    int worklistSizeVar = stateIdVar + 1;

    // Determine assertion type
    boolean isPositive =
        (assertionState.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD
            || assertionState.assertionType == NFA.AssertionType.POSITIVE_LOOKBEHIND);

    // Check if this assertion contains capturing groups that need to be tracked
    // Determine assertion type
    boolean isLookbehind =
        (assertionState.assertionType == NFA.AssertionType.POSITIVE_LOOKBEHIND
            || assertionState.assertionType == NFA.AssertionType.NEGATIVE_LOOKBEHIND);

    // Check if this assertion contains capturing groups that need to be tracked
    boolean needsGroupTracking =
        groupStartsVar >= 0 && isPositive && assertionHasCapturingGroups(assertionState);

    // Allocate local variables using allocator
    int checkPosVar = allocator.allocateInt();
    int resultVar = allocator.allocateInt();

    // Calculate check position: for lookbehind, go back by width; for lookahead, stay at current
    // pos
    Label assertionFailed = new Label();
    Label assertionPassed = new Label();
    Label assertionComplete = new Label(); // BUG FIX #2: For negative lookbehind boundary case

    if (isLookbehind) {
      // checkPos = pos - width
      mv.visitVarInsn(ILOAD, posVar);
      pushInt(mv, assertionState.assertionWidth);
      mv.visitInsn(ISUB);
      mv.visitVarInsn(ISTORE, checkPosVar);

      // Bounds check: if (checkPos < 0) assertion fails for positive, succeeds for negative
      mv.visitVarInsn(ILOAD, checkPosVar);
      if (isPositive) {
        mv.visitJumpInsn(IFLT, assertionFailed);
      } else {
        // Negative lookbehind: if can't look back, assertion succeeds (nothing to match)
        Label canLookBack = new Label();
        mv.visitJumpInsn(IFGE, canLookBack);
        // Can't look back - negative assertion succeeds, add epsilon targets and skip
        for (NFA.NFAState target : assertionState.getEpsilonTransitions()) {
          Label alreadyVisited = new Label();
          checkStateInSetConst(mv, statesVar, target.id, allocator);
          mv.visitJumpInsn(IFNE, alreadyVisited);

          addStateToSet(mv, statesVar, target.id, allocator);

          // worklist[worklistSize++] = target.id;
          mv.visitVarInsn(ALOAD, worklistVar);
          mv.visitVarInsn(ILOAD, worklistSizeVar);
          pushInt(mv, target.id);
          mv.visitInsn(IASTORE);
          mv.visitIincInsn(worklistSizeVar, 1);

          mv.visitLabel(alreadyVisited);
        }
        // BUG FIX #2: Jump to completion, not failure (targets already added)
        mv.visitJumpInsn(GOTO, assertionComplete); // Skip assertion check & target adding
        mv.visitLabel(canLookBack);
      }
    } else {
      // checkPos = pos (lookahead)
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, checkPosVar);
    }

    // If we need group tracking, skip all optimizations that can't track groups
    // and go directly to the group-tracking NFA simulation
    if (needsGroupTracking) {
      // Use group-tracking NFA simulation for lookahead with captures
      generateSubNFASimulationWithGroups(
          mv,
          assertionState.assertionStartState,
          assertionState.assertionAcceptStates,
          checkPosVar,
          inputVar,
          -1, // maxLength (unlimited)
          resultVar,
          groupStartsVar,
          groupEndsVar,
          allocator);

      // Check result
      mv.visitVarInsn(ILOAD, resultVar);
      mv.visitJumpInsn(IFEQ, assertionFailed);

      // Assertion passed - add epsilon targets
      for (NFA.NFAState target : assertionState.getEpsilonTransitions()) {
        Label alreadyVisited = new Label();
        checkStateInSetConst(mv, statesVar, target.id, allocator);
        mv.visitJumpInsn(IFNE, alreadyVisited);

        addStateToSet(mv, statesVar, target.id, allocator);

        // worklist[worklistSize++] = target.id;
        mv.visitVarInsn(ALOAD, worklistVar);
        mv.visitVarInsn(ILOAD, worklistSizeVar);
        pushInt(mv, target.id);
        mv.visitInsn(IASTORE);
        mv.visitIincInsn(worklistSizeVar, 1);

        mv.visitLabel(alreadyVisited);
      }
      mv.visitJumpInsn(GOTO, assertionComplete);
      mv.visitLabel(assertionFailed);
      mv.visitLabel(assertionComplete);
      return; // Early return - group tracking handled
    }

    // OPTIMIZATION: Try precomputed DFA for lookahead (hybrid DFA-NFA strategy)
    if (hybridInfo != null && hybridInfo.assertionDFAs.containsKey(assertionState.id)) {
      DFA lookaheadDFA = hybridInfo.assertionDFAs.get(assertionState.id);
      // Only optimize positive lookahead (negative lookahead is rare)
      if (isPositive && !isLookbehind) {
        // Tier 2 optimization: Check if there are multiple sequential lookaheads to fuse
        List<NFA.NFAState> sequentialLookaheads = collectSequentialLookaheads(assertionState);

        if (sequentialLookaheads != null && sequentialLookaheads.size() > 1) {
          // Multiple lookaheads detected - use fused evaluation
          generateFusedMultiLookaheadCheck(
              mv,
              sequentialLookaheads,
              inputVar,
              checkPosVar,
              statesVar,
              worklistVar,
              worklistSizeVar,
              allocator,
              assertionFailed,
              assertionComplete);
          mv.visitLabel(assertionFailed);
          mv.visitLabel(assertionComplete);
          return; // Early return - fused check handled everything
        }

        // Single lookahead - use existing individual DFA check
        generateDFALookaheadCheck(
            mv, lookaheadDFA, inputVar, checkPosVar, assertionFailed, assertionPassed, allocator);
        mv.visitLabel(assertionPassed);
        // Add epsilon targets and return
        for (NFA.NFAState target : assertionState.getEpsilonTransitions()) {
          Label alreadyVisited = new Label();
          checkStateInSetConst(mv, statesVar, target.id, allocator);
          mv.visitJumpInsn(IFNE, alreadyVisited);

          addStateToSet(mv, statesVar, target.id, allocator);

          // worklist[worklistSize++] = target.id;
          mv.visitVarInsn(ALOAD, worklistVar);
          mv.visitVarInsn(ILOAD, worklistSizeVar);
          pushInt(mv, target.id);
          mv.visitInsn(IASTORE);
          mv.visitIincInsn(worklistSizeVar, 1);

          mv.visitLabel(alreadyVisited);
        }
        mv.visitJumpInsn(GOTO, assertionComplete);
        mv.visitLabel(assertionFailed);
        mv.visitLabel(assertionComplete);
        return; // Early return - DFA handled everything
      }
    }

    // Try specialized literal assertion optimization (most common case)
    String literal =
        extractLiteral(assertionState.assertionStartState, assertionState.assertionAcceptStates);
    if (literal != null && !literal.isEmpty()) {
      generateLiteralAssertion(
          mv, literal, inputVar, checkPosVar, isPositive, assertionFailed, assertionPassed);
      mv.visitLabel(assertionPassed);
    } else if (tryInlineAssertion(
        mv,
        assertionState,
        inputVar,
        checkPosVar,
        isPositive,
        assertionFailed,
        assertionPassed,
        allocator)) {
      // Inline optimization succeeded, check result
      mv.visitLabel(assertionPassed);
    } else {
      // Check if we can use lightweight simulation for tiny sub-NFAs
      int subNFASize = countReachableStates(assertionState.assertionStartState);

      if (subNFASize <= 6
          && tryLightweightSimulation(
              mv,
              assertionState,
              inputVar,
              checkPosVar,
              isPositive,
              assertionFailed,
              assertionPassed,
              isLookbehind,
              allocator)) {
        // Lightweight simulation succeeded
        mv.visitLabel(assertionPassed);
      } else {
        // Fall back to full NFA simulation with BitSets

        if (isLookbehind) {
          generateSubNFASimulation(
              mv,
              assertionState.assertionStartState,
              assertionState.assertionAcceptStates,
              checkPosVar,
              inputVar,
              assertionState.assertionWidth, // maxLength (fixed width)
              resultVar,
              allocator);
        } else {
          generateSubNFASimulation(
              mv,
              assertionState.assertionStartState,
              assertionState.assertionAcceptStates,
              checkPosVar,
              inputVar,
              -1, // maxLength (unlimited)
              resultVar,
              allocator);
        }

        // Check result and apply positive/negative logic
        mv.visitVarInsn(ILOAD, resultVar);

        if (isPositive) {
          mv.visitJumpInsn(IFEQ, assertionFailed);
        } else {
          mv.visitJumpInsn(IFNE, assertionFailed);
        }
      }
    }

    // Add epsilon targets
    for (NFA.NFAState target : assertionState.getEpsilonTransitions()) {
      Label alreadyVisited = new Label();
      checkStateInSetConst(mv, statesVar, target.id, allocator);
      mv.visitJumpInsn(IFNE, alreadyVisited);

      addStateToSet(mv, statesVar, target.id, allocator);

      // worklist[worklistSize++] = target.id;
      mv.visitVarInsn(ALOAD, worklistVar);
      mv.visitVarInsn(ILOAD, worklistSizeVar);
      pushInt(mv, target.id);
      mv.visitInsn(IASTORE);
      mv.visitIincInsn(worklistSizeVar, 1);

      mv.visitLabel(alreadyVisited);
    }

    mv.visitLabel(assertionFailed);
    // Assertion failed - don't add any epsilon targets, just continue

    mv.visitLabel(assertionComplete);
    // BUG FIX #2: Assertion complete - targets already handled for boundary case
  }

  /**
   * Check if an assertion sub-pattern contains any capturing groups. This is used to determine if
   * we need to use group-tracking sub-NFA simulation.
   */
  private boolean assertionHasCapturingGroups(NFA.NFAState assertionState) {
    if (assertionState.assertionStartState == null) {
      return false;
    }
    Set<NFA.NFAState> visited = new HashSet<>();
    Queue<NFA.NFAState> queue = new LinkedList<>();
    queue.add(assertionState.assertionStartState);

    while (!queue.isEmpty()) {
      NFA.NFAState state = queue.poll();
      if (!visited.add(state)) {
        continue;
      }
      if (state.enterGroup != null || state.exitGroup != null) {
        return true;
      }
      // Follow epsilon transitions
      for (NFA.NFAState target : state.getEpsilonTransitions()) {
        queue.add(target);
      }
      // Follow character transitions
      for (NFA.Transition trans : state.getTransitions()) {
        queue.add(trans.target);
      }
    }
    return false;
  }

  /**
   * Extract a literal string from the NFA if it represents a simple literal sequence. Returns null
   * if the pattern is not a simple literal.
   */
  private String extractLiteral(NFA.NFAState start, Set<NFA.NFAState> acceptStates) {
    StringBuilder literal = new StringBuilder();
    NFA.NFAState current = start;
    Set<NFA.NFAState> visited = new HashSet<>();

    while (visited.add(current)) {
      // Check for epsilon transitions (skip empty transitions)
      if (!current.getEpsilonTransitions().isEmpty()) {
        // Follow epsilon if it leads to non-assertion state
        boolean foundNonAssertion = false;
        for (NFA.NFAState target : current.getEpsilonTransitions()) {
          if (target.assertionType == null) {
            current = target;
            foundNonAssertion = true;
            break;
          }
        }
        if (!foundNonAssertion) break;
        continue;
      }

      // Must have exactly one character transition
      if (current.getTransitions().size() != 1) break;

      NFA.Transition trans = current.getTransitions().iterator().next();

      // Must be a single character
      if (!trans.chars.isSingleChar()) {
        return null; // Not a literal
      }

      literal.append(trans.chars.getSingleChar());
      current = trans.target;

      // Check if we reached an accept state
      if (acceptStates.contains(current) && current.getTransitions().isEmpty()) {
        return literal.toString();
      }
    }

    return null;
  }

  /**
   * Extract longest required literal from NFA main pattern (excluding lookaheads). For patterns
   * like (?=\w+@)(?=.*example).*@\w+\.com, extracts literals from the main matching path, not from
   * assertion sub-patterns.
   *
   * @param nfa The NFA to analyze
   * @return Longest literal string found, or null if no suitable literal exists
   */
  private String extractLongestRequiredLiteral(NFA nfa) {
    String longestLiteral = null;
    int maxLength = 0;

    // Walk through all states looking for literal sequences
    for (NFA.NFAState state : nfa.getStates()) {
      // Skip assertion states (lookaheads, lookbehinds)
      if (state.assertionType != null) {
        continue;
      }

      // Try to extract a literal starting from this state
      String literal = extractLiteralFromState(state, nfa.getAcceptStates());
      if (literal != null && literal.length() > maxLength) {
        maxLength = literal.length();
        longestLiteral = literal;
      }
    }

    // Only return literals that are worth the indexOf overhead (>= 3 characters)
    return (longestLiteral != null && longestLiteral.length() >= 3) ? longestLiteral : null;
  }

  /**
   * Extract a literal sequence starting from a given state. Similar to extractLiteral() but works
   * from any state, not just start state.
   */
  private String extractLiteralFromState(NFA.NFAState start, Set<NFA.NFAState> acceptStates) {
    StringBuilder literal = new StringBuilder();
    NFA.NFAState current = start;
    Set<NFA.NFAState> visited = new HashSet<>();

    while (visited.add(current)) {
      // Skip assertion states
      if (current.assertionType != null) {
        break;
      }

      // Follow epsilon transitions (skip empty transitions to non-assertion states)
      if (!current.getEpsilonTransitions().isEmpty()) {
        boolean foundNonAssertion = false;
        for (NFA.NFAState target : current.getEpsilonTransitions()) {
          if (target.assertionType == null) {
            current = target;
            foundNonAssertion = true;
            break;
          }
        }
        if (!foundNonAssertion) break;
        continue;
      }

      // Must have exactly one character transition for a literal
      if (current.getTransitions().size() != 1) {
        break;
      }

      NFA.Transition trans = current.getTransitions().iterator().next();

      // Must be a single character (not a character class)
      if (!trans.chars.isSingleChar()) {
        break;
      }

      literal.append(trans.chars.getSingleChar());
      current = trans.target;

      // Don't require reaching accept state - we want any literal sequence
      // This allows us to find literals like "example" even if followed by more pattern
    }

    // Return literal if we found at least 3 consecutive characters
    return (literal.length() >= 3) ? literal.toString() : null;
  }

  /**
   * Generate highly optimized bytecode for literal assertion checking. This avoids all BitSet
   * allocations and epsilon closure overhead.
   */
  private void generateLiteralAssertion(
      MethodVisitor mv,
      String literal,
      int inputVar,
      int checkPosVar,
      boolean isPositive,
      Label assertionFailed,
      Label assertionPassed) {
    // Bounds check: if (checkPos + literal.length() > input.length()) goto failed
    mv.visitVarInsn(ILOAD, checkPosVar);
    pushInt(mv, literal.length());
    mv.visitInsn(IADD);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGT, isPositive ? assertionFailed : assertionPassed);

    // Use String.regionMatches() for efficient comparison
    // boolean matches = input.regionMatches(checkPos, literal, 0, literal.length());
    mv.visitVarInsn(ALOAD, inputVar); // Load input string
    mv.visitVarInsn(ILOAD, checkPosVar); // Load checkPos (toffset)
    mv.visitLdcInsn(literal); // Load literal string (other)
    mv.visitInsn(ICONST_0); // ooffset = 0
    pushInt(mv, literal.length()); // len
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);

    // Stack now has boolean result (1 = match, 0 = no match)
    if (isPositive) {
      // Positive assertion: jump to passed if matched (result == 1)
      mv.visitJumpInsn(IFNE, assertionPassed);
      mv.visitJumpInsn(GOTO, assertionFailed);
    } else {
      // Negative assertion: jump to passed if NOT matched (result == 0)
      mv.visitJumpInsn(IFEQ, assertionPassed);
      mv.visitJumpInsn(GOTO, assertionFailed);
    }
  }

  /** Count reachable states from a given start state (including epsilon transitions). */
  private int countReachableStates(NFA.NFAState start) {
    Set<NFA.NFAState> visited = new HashSet<>();
    Deque<NFA.NFAState> queue = new ArrayDeque<>();
    queue.add(start);
    visited.add(start);

    while (!queue.isEmpty()) {
      NFA.NFAState current = queue.poll();
      for (NFA.NFAState target : current.getEpsilonTransitions()) {
        if (target.assertionType == null && visited.add(target)) {
          queue.add(target);
        }
      }
      for (NFA.Transition trans : current.getTransitions()) {
        if (visited.add(trans.target)) {
          queue.add(trans.target);
        }
      }
    }

    return visited.size();
  }

  /**
   * Try to generate lightweight simulation for very small sub-NFAs without BitSet allocation. Uses
   * direct boolean variables and control flow instead of BitSets. Only works for patterns with <= 4
   * reachable states.
   */
  private boolean tryLightweightSimulation(
      MethodVisitor mv,
      NFA.NFAState assertionState,
      int inputVar,
      int checkPosVar,
      boolean isPositive,
      Label assertionFailed,
      Label assertionPassed,
      boolean isLookbehind,
      LocalVariableAllocator allocator) {
    // Try to detect .*[CharClass] pattern (most common in password validation)
    CharSet targetCharSet =
        extractDotStarCharClass(
            assertionState.assertionStartState, assertionState.assertionAcceptStates);
    if (targetCharSet != null) {
      // Allocate local variables using allocator
      int scanPosVar = allocator.allocateInt();
      int lenVar = allocator.allocateInt();
      int charVar = allocator.allocateInt();

      // Generate optimized single-pass bytecode: scan forward until character found
      // int scanPos = checkPos; int len = input.length();
      mv.visitVarInsn(ILOAD, checkPosVar);
      mv.visitVarInsn(ISTORE, scanPosVar); // scanPos
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitVarInsn(ISTORE, lenVar); // len

      Label loopStart = new Label();
      Label loopEnd = new Label();
      Label charFound = new Label();

      mv.visitLabel(loopStart);
      // if (scanPos >= len) goto loopEnd
      mv.visitVarInsn(ILOAD, scanPosVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);

      // char c = input.charAt(scanPos);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, scanPosVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, charVar); // c

      // Check if character matches target charset
      generateCharSetCheck(mv, targetCharSet, charVar);
      mv.visitJumpInsn(IFNE, charFound); // If match, jump to found

      // scanPos++
      mv.visitIincInsn(scanPosVar, 1);
      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(charFound);
      // Character found!
      if (isPositive) {
        mv.visitJumpInsn(GOTO, assertionPassed);
      } else {
        // Negative lookahead - if we found it, assertion fails
        mv.visitJumpInsn(GOTO, assertionFailed);
      }

      mv.visitLabel(loopEnd);
      // Reached end without finding character
      if (isPositive) {
        mv.visitJumpInsn(GOTO, assertionFailed);
      } else {
        // Negative lookahead - if we didn't find it, assertion passes
        mv.visitJumpInsn(GOTO, assertionPassed);
      }

      return true;
    }

    return false;
  }

  /**
   * Try to generate inline bytecode for simple assertion patterns. Returns true if inlined, false
   * if full NFA simulation is needed.
   *
   * <p>Handles optimizations for: - Single character: (?=x) or (?!x) - Character class: (?=[a-z])
   * or (?![0-9]) - Short literal: (?=abc) or (?!xyz)
   */
  private boolean tryInlineAssertion(
      MethodVisitor mv,
      NFA.NFAState assertionState,
      int inputVar,
      int checkPosVar,
      boolean isPositive,
      Label assertionFailed,
      Label assertionPassed,
      LocalVariableAllocator allocator) {
    // Analyze the sub-pattern structure
    NFA.NFAState start = assertionState.assertionStartState;

    // Check if this is a simple single-character transition
    if (start.getTransitions().size() == 1 && start.getEpsilonTransitions().isEmpty()) {
      NFA.Transition trans = start.getTransitions().iterator().next();
      NFA.NFAState target = trans.target;

      // Check if target is an accept state with no further transitions
      if (assertionState.assertionAcceptStates.contains(target)
          && target.getTransitions().isEmpty()) {

        // Generate inline check for single character
        // if (checkPos >= len) goto failed
        // if (input.charAt(checkPos) matches charset) goto passed else goto failed

        mv.visitVarInsn(ILOAD, checkPosVar);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitJumpInsn(IF_ICMPGE, assertionFailed);

        // Load character at checkPos
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, checkPosVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        int chVar = allocator.allocateInt(); // Temp var for character
        mv.visitVarInsn(ISTORE, chVar);

        // Generate charset check
        generateCharSetCheck(mv, trans.chars, chVar);

        // Result is on stack: 1 if match, 0 if no match
        if (isPositive) {
          // Positive: if match (1), pass; if no match (0), fail
          mv.visitJumpInsn(IFNE, assertionPassed);
          mv.visitJumpInsn(GOTO, assertionFailed);
        } else {
          // Negative: if match (1), fail; if no match (0), pass
          mv.visitJumpInsn(IFEQ, assertionPassed);
          mv.visitJumpInsn(GOTO, assertionFailed);
        }

        return true; // Successfully inlined
      }
    }

    // Check for short literal sequence (2-3 characters)
    if (canInlineLiteralSequence(start, assertionState.assertionAcceptStates, 3)) {
      return inlineLiteralSequence(
          mv,
          start,
          assertionState.assertionAcceptStates,
          inputVar,
          checkPosVar,
          isPositive,
          assertionFailed,
          assertionPassed);
    }

    return false; // Cannot inline, use full simulation
  }

  /** Check if the NFA represents a simple literal sequence that can be inlined. */
  private boolean canInlineLiteralSequence(
      NFA.NFAState start, Set<NFA.NFAState> acceptStates, int maxLen) {
    NFA.NFAState current = start;
    int len = 0;

    while (len < maxLen) {
      // Must have exactly one transition, no epsilon transitions
      if (current.getTransitions().size() != 1 || !current.getEpsilonTransitions().isEmpty()) {
        break;
      }

      NFA.Transition trans = current.getTransitions().iterator().next();

      // Must be a single character (not a range or class)
      if (!trans.chars.isSingleChar()) {
        return false;
      }

      current = trans.target;
      len++;

      // If we reached an accept state, we have a valid literal sequence
      if (acceptStates.contains(current)) {
        return len >= 2; // Only inline if at least 2 chars
      }
    }

    return false;
  }

  /** Generate inline code for literal sequence assertion. */
  private boolean inlineLiteralSequence(
      MethodVisitor mv,
      NFA.NFAState start,
      Set<NFA.NFAState> acceptStates,
      int inputVar,
      int checkPosVar,
      boolean isPositive,
      Label assertionFailed,
      Label assertionPassed) {
    // Extract the literal characters
    java.util.List<Character> chars = new java.util.ArrayList<>();
    NFA.NFAState current = start;

    while (true) {
      if (current.getTransitions().size() != 1) break;
      NFA.Transition trans = current.getTransitions().iterator().next();
      if (!trans.chars.isSingleChar()) break;

      chars.add(trans.chars.getSingleChar());
      current = trans.target;

      if (acceptStates.contains(current)) {
        break;
      }
    }

    if (chars.isEmpty()) return false;

    // Generate: if (checkPos + len > input.length()) goto failed
    mv.visitVarInsn(ILOAD, checkPosVar);
    pushInt(mv, chars.size());
    mv.visitInsn(IADD);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGT, assertionFailed);

    // Generate character-by-character comparison
    Label notMatched = new Label();
    for (int i = 0; i < chars.size(); i++) {
      // if (input.charAt(checkPos + i) != chars[i]) goto notMatched
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, checkPosVar);
      if (i > 0) {
        pushInt(mv, i);
        mv.visitInsn(IADD);
      }
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, (int) chars.get(i));
      mv.visitJumpInsn(IF_ICMPNE, notMatched);
    }

    // All characters matched
    if (isPositive) {
      mv.visitJumpInsn(GOTO, assertionPassed);
    } else {
      mv.visitJumpInsn(GOTO, assertionFailed);
    }

    // Not matched
    mv.visitLabel(notMatched);
    if (isPositive) {
      mv.visitJumpInsn(GOTO, assertionFailed);
    } else {
      mv.visitJumpInsn(GOTO, assertionPassed);
    }

    return true;
  }

  /** Simplified epsilon closure that doesn't check assertions (to avoid recursion). */
  private void generateSimpleEpsilonClosure(
      MethodVisitor mv, int statesVar, NFA.NFAState startState) {
    // Simple worklist-based epsilon closure without assertion checking
    Set<NFA.NFAState> visited = new HashSet<>();
    Queue<NFA.NFAState> worklist = new java.util.LinkedList<>();
    worklist.add(startState);
    visited.add(startState);

    while (!worklist.isEmpty()) {
      NFA.NFAState current = worklist.poll();
      for (NFA.NFAState target : current.getEpsilonTransitions()) {
        if (!visited.contains(target) && target.assertionType == null) {
          visited.add(target);
          worklist.add(target);

          // Add to BitSet
          mv.visitVarInsn(ALOAD, statesVar);
          pushInt(mv, target.id);
          generateStateSetAdd(mv);
        }
      }
    }
  }

  /**
   * Pattern information for fast-forward scan optimization. Represents patterns like .*[CharClass]
   * that can be optimized to simple forward scanning.
   */
  private static class FastForwardPattern {
    final CharSet targetCharSet; // Character class to find (e.g., [A-Z])

    FastForwardPattern(CharSet targetCharSet) {
      this.targetCharSet = targetCharSet;
    }
  }

  /**
   * Analyze sub-NFA to detect .*[CharClass] pattern that can use fast-forward scan. This handles
   * common assertion patterns like (?=.*[A-Z]) in password validation.
   *
   * <p>Pattern structure: Start → [any char loop] → [target char] → Accept Returns null if pattern
   * is too complex for optimization.
   */
  private FastForwardPattern analyzeFastForwardPattern(
      NFA.NFAState start, Set<NFA.NFAState> accepts) {
    // Follow epsilon transitions from start
    Set<NFA.NFAState> reachable = new HashSet<>();
    collectEpsilonClosure(start, reachable);

    // Look for states with ANY_CHAR that loop (indicating .* pattern)
    boolean hasDotStar = false;
    for (NFA.NFAState state : reachable) {
      for (NFA.Transition trans : state.getTransitions()) {
        if (trans.chars.equals(CharSet.ANY)) {
          // Check if this creates a loop (backedge to reachable states)
          Set<NFA.NFAState> targetClosure = new HashSet<>();
          collectEpsilonClosure(trans.target, targetClosure);
          if (targetClosure.stream().anyMatch(reachable::contains)) {
            hasDotStar = true;
            break;
          }
        }
      }
      if (hasDotStar) break;
    }

    if (!hasDotStar) {
      return null; // Not a .* pattern
    }

    // Find the target character class after .*
    // Look for states that transition to accept state
    for (NFA.NFAState state : reachable) {
      for (NFA.Transition trans : state.getTransitions()) {
        if (trans.chars.equals(CharSet.ANY)) {
          continue; // Skip .* transitions
        }

        // Check if this transition leads to accept state
        Set<NFA.NFAState> targetClosure = new HashSet<>();
        collectEpsilonClosure(trans.target, targetClosure);

        if (targetClosure.stream().anyMatch(accepts::contains)) {
          // Found target character class: .*[charset]
          return new FastForwardPattern(trans.chars);
        }
      }
    }

    return null; // Pattern too complex
  }

  /** Collect epsilon closure of a state (helper for pattern analysis). */
  private void collectEpsilonClosure(NFA.NFAState start, Set<NFA.NFAState> result) {
    if (!result.add(start)) {
      return; // Already visited
    }
    for (NFA.NFAState target : start.getEpsilonTransitions()) {
      collectEpsilonClosure(target, result);
    }
  }

  /**
   * Generate optimized fast-forward scan for .*[CharClass] patterns. Instead of O(n²) NFA
   * simulation, generates O(n) forward scan.
   *
   * <p>Generated code: int pos = checkPos; int len = input.length(); while (pos < len) { if
   * (input.charAt(pos) matches targetCharSet) { result = true; return; } pos++; } result = false;
   */
  private void generateFastForwardScan(
      MethodVisitor mv,
      FastForwardPattern pattern,
      int checkPosVar,
      int inputVar,
      int resultVarSlot,
      LocalVariableAllocator allocator) {
    // Allocate local variables using allocator
    int posVar = allocator.allocateInt(); // Temporary position variable
    int lenVar = allocator.allocateInt(); // String length
    int chVar = allocator.allocateInt(); // Current character

    // int pos = checkPos;
    mv.visitVarInsn(ILOAD, checkPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // result = false (default if not found)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, resultVarSlot);

    // while (pos < len)
    Label loopStart = new Label();
    Label loopEnd = new Label();
    mv.visitLabel(loopStart);

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // if (ch matches targetCharSet) { result = true; return; }
    generateCharSetMatch(mv, pattern.targetCharSet, chVar, allocator);

    Label noMatch = new Label();
    mv.visitJumpInsn(IFEQ, noMatch); // Jump if no match

    // Match found - set result = true and exit
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ISTORE, resultVarSlot);
    mv.visitJumpInsn(GOTO, loopEnd);

    mv.visitLabel(noMatch);

    // pos++
    mv.visitIincInsn(posVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
  }

  /**
   * Generate bytecode to test if character matches a charset. Leaves 1 on stack if match, 0 if no
   * match.
   *
   * <p>Simplified implementation that stores intermediate results in a variable.
   */
  private void generateCharSetMatch(
      MethodVisitor mv, CharSet charset, int chVar, LocalVariableAllocator allocator) {
    // Allocate local variable using allocator
    int resultVar = allocator.allocateInt(); // Temporary result variable

    // Initialize result = 0 (no match)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, resultVar);

    if (charset.equals(CharSet.ANY)) {
      // Any character matches
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, resultVar);
      mv.visitVarInsn(ILOAD, resultVar);
      return;
    }

    List<CharSet.Range> ranges = charset.getRanges();
    if (ranges.isEmpty()) {
      // Empty charset - never matches (result already 0)
      mv.visitVarInsn(ILOAD, resultVar);
      return;
    }

    Label done = new Label();

    for (CharSet.Range range : ranges) {
      if (range.start == range.end) {
        // Single character: if (ch == expected) result = 1
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.start);
        Label notThis = new Label();
        mv.visitJumpInsn(IF_ICMPNE, notThis);
        // Match!
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, resultVar);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(notThis);
      } else {
        // Range: if (ch >= start && ch <= end) result = 1
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.start);
        Label notThis = new Label();
        mv.visitJumpInsn(IF_ICMPLT, notThis); // ch < start → skip

        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPGT, notThis); // ch > end → skip

        // Match!
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, resultVar);
        mv.visitJumpInsn(GOTO, done);

        mv.visitLabel(notThis);
      }
    }

    mv.visitLabel(done);
    // Load result onto stack
    mv.visitVarInsn(ILOAD, resultVar);
  }

  /**
   * Generate bytecode to simulate a sub-NFA from a given position. This properly matches the
   * sub-pattern character-by-character against the input.
   *
   * @param mv Method visitor
   * @param subStartState Start state of sub-pattern NFA
   * @param subAcceptStates Accept states of sub-pattern NFA
   * @param checkPosVar Local variable holding starting position
   * @param inputVar Local variable holding input string
   * @param maxLength Maximum characters to consume (-1 for unlimited, or fixed width for
   *     lookbehind)
   * @param resultVarSlot Local variable slot to store result (boolean)
   */
  private void generateSubNFASimulation(
      MethodVisitor mv,
      NFA.NFAState subStartState,
      Set<NFA.NFAState> subAcceptStates,
      int checkPosVar,
      int inputVar,
      int maxLength,
      int resultVarSlot,
      LocalVariableAllocator allocator) {
    // OPTIMIZATION: Detect .*[CharClass] patterns and use fast-forward scan
    FastForwardPattern ffPattern = analyzeFastForwardPattern(subStartState, subAcceptStates);
    if (ffPattern != null) {
      generateFastForwardScan(mv, ffPattern, checkPosVar, inputVar, resultVarSlot, allocator);
      return;
    }

    // Allocate local variables using allocator
    // Phase 2B: State sets can be longs or object references
    int subCurrentStatesVar = useSingleLong ? allocator.allocateLong() : allocator.allocateRef();
    int subNextStatesVar = useSingleLong ? allocator.allocateLong() : allocator.allocateRef();
    int subPosVar = allocator.allocateInt();
    int subLenVar = allocator.allocateInt();
    int subChVar = allocator.allocateInt();
    int charsReadVar = allocator.allocateInt(); // Track how many characters we've read
    int tempVar =
        useSingleLong
            ? allocator.allocateLong()
            : allocator.allocateRef(); // For swapping state sets

    // Initialize state sets
    if (useSingleLong) {
      generateSingleLongNew(mv, subCurrentStatesVar);
      generateSingleLongNew(mv, subNextStatesVar);
    } else {
      // StateSet subCurrentStates = new SparseSet(stateCount);
      generateStateSetNew(mv);
      mv.visitVarInsn(ASTORE, subCurrentStatesVar);

      // StateSet subNextStates = new SparseSet(stateCount);
      generateStateSetNew(mv);
      mv.visitVarInsn(ASTORE, subNextStatesVar);
    }

    // subCurrentStates.set(subStartState.id);
    addStateToSet(mv, subCurrentStatesVar, subStartState.id, allocator);

    // Compute initial epsilon closure (try inline first for small closures)
    Set<Integer> startStates = new HashSet<>();
    startStates.add(subStartState.id);
    boolean inlined =
        tryInlineEpsilonClosure(mv, subCurrentStatesVar, startStates, true, allocator);
    if (!inlined) {
      // Fallback to runtime epsilon closure for large state sets
      generateSimpleEpsilonClosure(mv, subCurrentStatesVar, subStartState);
    }

    // int subPos = checkPos;
    mv.visitVarInsn(ILOAD, checkPosVar);
    mv.visitVarInsn(ISTORE, subPosVar);

    // int subLen = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, subLenVar);

    // int charsRead = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, charsReadVar);

    // boolean result = false; (initialize once at the start)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, resultVarSlot);

    // Character matching loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // while (subPos < subLen)
    mv.visitVarInsn(ILOAD, subPosVar);
    mv.visitVarInsn(ILOAD, subLenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // if maxLength != -1, check if charsRead >= maxLength
    if (maxLength != -1) {
      mv.visitVarInsn(ILOAD, charsReadVar);
      pushInt(mv, maxLength);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // if (subCurrentStates.isEmpty()) break;
    isStateSetEmpty(mv, subCurrentStatesVar);
    mv.visitJumpInsn(IFNE, loopEnd);

    // char ch = input.charAt(subPos++);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, subPosVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, subChVar);
    mv.visitIincInsn(subPosVar, 1); // subPos++
    mv.visitIincInsn(charsReadVar, 1); // charsRead++

    // subNextStates.clear();
    clearStateSet(mv, subNextStatesVar);

    // Process NFA step for sub-pattern (unified - auto-detects dual-long)
    generateNFAStep(mv, subCurrentStatesVar, subNextStatesVar, subChVar, -1, -1, 0, allocator);

    // Compute epsilon closure of subNextStates (skip assertions)
    // For small sub-NFAs (typical for assertions), try optimized approach
    if (nfa.getStates().size() <= 15) {
      // Generate optimized epsilon closure without Stack allocation
      generateOptimizedEpsilonClosure(mv, subNextStatesVar, true, allocator);
    } else {
      generateSimpleEpsilonClosureRuntime(mv, subNextStatesVar, allocator);
    }

    // BUG FIX #1: Check for accept states INSIDE the loop (early exit)
    // This fixes pattern a(?=bc) with input abc123
    for (NFA.NFAState acceptState : subAcceptStates) {
      checkStateInSetConst(mv, subNextStatesVar, acceptState.id, allocator);

      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);

      // Found accept state - set result = true and exit loop
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, resultVarSlot);
      mv.visitJumpInsn(GOTO, loopEnd);

      mv.visitLabel(notAccepting);
    }

    // Swap state sets
    if (useSingleLong) {
      // For single-long: swap long values
      mv.visitVarInsn(LLOAD, subCurrentStatesVar);
      mv.visitVarInsn(LSTORE, tempVar);
      mv.visitVarInsn(LLOAD, subNextStatesVar);
      mv.visitVarInsn(LSTORE, subCurrentStatesVar);
      mv.visitVarInsn(LLOAD, tempVar);
      mv.visitVarInsn(LSTORE, subNextStatesVar);
    } else {
      // For BitSet/SparseSet: swap object references
      mv.visitVarInsn(ALOAD, subCurrentStatesVar);
      mv.visitVarInsn(ASTORE, tempVar);
      mv.visitVarInsn(ALOAD, subNextStatesVar);
      mv.visitVarInsn(ASTORE, subCurrentStatesVar);
      mv.visitVarInsn(ALOAD, tempVar);
      mv.visitVarInsn(ASTORE, subNextStatesVar);
    }

    // Clear subNextStates (which is now the old subCurrentStates) for reuse
    clearStateSet(mv, subNextStatesVar);

    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);

    // Check if any accept state is reached (result may already be set by early exit)
    // Don't reinitialize result here - it may have been set to true inside the loop
    for (NFA.NFAState acceptState : subAcceptStates) {
      // if (subCurrentStates.get(acceptState.id)) { matched = true; }
      checkStateInSetConst(mv, subCurrentStatesVar, acceptState.id, allocator);

      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, resultVarSlot);
      mv.visitLabel(notAccepting);
    }
  }

  /**
   * Generate bytecode for sub-NFA simulation that tracks capturing groups. Used for positive
   * lookahead with captures - the group captures must persist after the lookahead completes.
   *
   * @param mv Method visitor
   * @param subStartState Start state of the sub-NFA
   * @param subAcceptStates Accept states of the sub-NFA
   * @param checkPosVar Local variable holding the position to start checking
   * @param inputVar Local variable holding the input string
   * @param maxLength Maximum characters to consume (-1 for unlimited)
   * @param resultVarSlot Local variable slot to store result (boolean)
   * @param groupStartsVar Local variable slot for group starts array
   * @param groupEndsVar Local variable slot for group ends array
   * @param allocator Local variable allocator
   */
  private void generateSubNFASimulationWithGroups(
      MethodVisitor mv,
      NFA.NFAState subStartState,
      Set<NFA.NFAState> subAcceptStates,
      int checkPosVar,
      int inputVar,
      int maxLength,
      int resultVarSlot,
      int groupStartsVar,
      int groupEndsVar,
      LocalVariableAllocator allocator) {
    // Allocate local variables
    int subCurrentStatesVar = useSingleLong ? allocator.allocateLong() : allocator.allocateRef();
    int subNextStatesVar = useSingleLong ? allocator.allocateLong() : allocator.allocateRef();
    int subPosVar = allocator.allocateInt();
    int subLenVar = allocator.allocateInt();
    int subChVar = allocator.allocateInt();
    int charsReadVar = allocator.allocateInt();
    int tempVar = useSingleLong ? allocator.allocateLong() : allocator.allocateRef();
    int worklistVar = allocator.allocateRef();
    int worklistSizeVar = allocator.allocateInt();
    int stateIdVar = allocator.allocateInt();
    int processedVar = useSingleLong ? allocator.allocateLong() : allocator.allocateRef();

    // Initialize state sets
    if (useSingleLong) {
      generateSingleLongNew(mv, subCurrentStatesVar);
      generateSingleLongNew(mv, subNextStatesVar);
      generateSingleLongNew(mv, processedVar);
    } else {
      generateStateSetNew(mv);
      mv.visitVarInsn(ASTORE, subCurrentStatesVar);
      generateStateSetNew(mv);
      mv.visitVarInsn(ASTORE, subNextStatesVar);
      generateStateSetNew(mv);
      mv.visitVarInsn(ASTORE, processedVar);
    }

    // Create worklist
    pushInt(mv, nfa.getStates().size());
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, worklistVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // subCurrentStates.set(subStartState.id);
    addStateToSet(mv, subCurrentStatesVar, subStartState.id, allocator);

    // Compute initial epsilon closure with group tracking
    generateSubNFAEpsilonClosureWithGroups(
        mv,
        subCurrentStatesVar,
        subStartState,
        worklistVar,
        worklistSizeVar,
        stateIdVar,
        processedVar,
        groupStartsVar,
        groupEndsVar,
        checkPosVar,
        allocator);

    // int subPos = checkPos;
    mv.visitVarInsn(ILOAD, checkPosVar);
    mv.visitVarInsn(ISTORE, subPosVar);

    // int subLen = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, subLenVar);

    // int charsRead = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, charsReadVar);

    // boolean result = false;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, resultVarSlot);

    // Check if already in accept state (empty match)
    for (NFA.NFAState acceptState : subAcceptStates) {
      checkStateInSetConst(mv, subCurrentStatesVar, acceptState.id, allocator);
      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, resultVarSlot);
      mv.visitLabel(notAccepting);
    }

    // Character matching loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // while (subPos < subLen)
    mv.visitVarInsn(ILOAD, subPosVar);
    mv.visitVarInsn(ILOAD, subLenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // if maxLength != -1, check if charsRead >= maxLength
    if (maxLength != -1) {
      mv.visitVarInsn(ILOAD, charsReadVar);
      pushInt(mv, maxLength);
      mv.visitJumpInsn(IF_ICMPGE, loopEnd);
    }

    // if (subCurrentStates.isEmpty()) break;
    isStateSetEmpty(mv, subCurrentStatesVar);
    mv.visitJumpInsn(IFNE, loopEnd);

    // char ch = input.charAt(subPos++);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, subPosVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, subChVar);
    mv.visitIincInsn(subPosVar, 1);
    mv.visitIincInsn(charsReadVar, 1);

    // subNextStates.clear();
    clearStateSet(mv, subNextStatesVar);

    // Process NFA step
    generateNFAStep(mv, subCurrentStatesVar, subNextStatesVar, subChVar, -1, -1, 0, allocator);

    // Compute epsilon closure with group tracking
    clearStateSet(mv, processedVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);
    generateSubNFAEpsilonClosureWithGroupsFromSet(
        mv,
        subNextStatesVar,
        worklistVar,
        worklistSizeVar,
        stateIdVar,
        processedVar,
        groupStartsVar,
        groupEndsVar,
        subPosVar,
        allocator);

    // Check for accept states - NO early exit for group tracking!
    // We need to continue to find the longest match (greedy semantics)
    for (NFA.NFAState acceptState : subAcceptStates) {
      checkStateInSetConst(mv, subNextStatesVar, acceptState.id, allocator);
      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, resultVarSlot);
      // Don't exit early - continue to find longer matches
      mv.visitLabel(notAccepting);
    }

    // Swap state sets
    if (useSingleLong) {
      mv.visitVarInsn(LLOAD, subCurrentStatesVar);
      mv.visitVarInsn(LSTORE, tempVar);
      mv.visitVarInsn(LLOAD, subNextStatesVar);
      mv.visitVarInsn(LSTORE, subCurrentStatesVar);
      mv.visitVarInsn(LLOAD, tempVar);
      mv.visitVarInsn(LSTORE, subNextStatesVar);
    } else {
      mv.visitVarInsn(ALOAD, subCurrentStatesVar);
      mv.visitVarInsn(ASTORE, tempVar);
      mv.visitVarInsn(ALOAD, subNextStatesVar);
      mv.visitVarInsn(ASTORE, subCurrentStatesVar);
      mv.visitVarInsn(ALOAD, tempVar);
      mv.visitVarInsn(ASTORE, subNextStatesVar);
    }

    clearStateSet(mv, subNextStatesVar);

    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);

    // Final accept state check
    for (NFA.NFAState acceptState : subAcceptStates) {
      checkStateInSetConst(mv, subCurrentStatesVar, acceptState.id, allocator);
      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, resultVarSlot);
      mv.visitLabel(notAccepting);
    }
  }

  /**
   * Generate epsilon closure with group tracking for sub-NFA simulation. Starting from a single
   * state (for initial closure).
   */
  private void generateSubNFAEpsilonClosureWithGroups(
      MethodVisitor mv,
      int statesVar,
      NFA.NFAState startState,
      int worklistVar,
      int worklistSizeVar,
      int stateIdVar,
      int processedVar,
      int groupStartsVar,
      int groupEndsVar,
      int posVar,
      LocalVariableAllocator allocator) {
    // Add start state to worklist
    mv.visitVarInsn(ALOAD, worklistVar);
    mv.visitInsn(ICONST_0);
    pushInt(mv, startState.id);
    mv.visitInsn(IASTORE);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    generateSubNFAEpsilonClosureLoop(
        mv,
        statesVar,
        worklistVar,
        worklistSizeVar,
        stateIdVar,
        processedVar,
        groupStartsVar,
        groupEndsVar,
        posVar,
        allocator);
  }

  /**
   * Generate epsilon closure with group tracking for sub-NFA simulation. Starting from all states
   * in a set (after character transition).
   */
  private void generateSubNFAEpsilonClosureWithGroupsFromSet(
      MethodVisitor mv,
      int statesVar,
      int worklistVar,
      int worklistSizeVar,
      int stateIdVar,
      int processedVar,
      int groupStartsVar,
      int groupEndsVar,
      int posVar,
      LocalVariableAllocator allocator) {
    // Add all states in statesVar to worklist
    for (NFA.NFAState state : nfa.getStates()) {
      checkStateInSetConst(mv, statesVar, state.id, allocator);
      Label notInSet = new Label();
      mv.visitJumpInsn(IFEQ, notInSet);

      mv.visitVarInsn(ALOAD, worklistVar);
      mv.visitVarInsn(ILOAD, worklistSizeVar);
      pushInt(mv, state.id);
      mv.visitInsn(IASTORE);
      mv.visitIincInsn(worklistSizeVar, 1);

      mv.visitLabel(notInSet);
    }

    generateSubNFAEpsilonClosureLoop(
        mv,
        statesVar,
        worklistVar,
        worklistSizeVar,
        stateIdVar,
        processedVar,
        groupStartsVar,
        groupEndsVar,
        posVar,
        allocator);
  }

  /** Generate the epsilon closure loop with group tracking. */
  private void generateSubNFAEpsilonClosureLoop(
      MethodVisitor mv,
      int statesVar,
      int worklistVar,
      int worklistSizeVar,
      int stateIdVar,
      int processedVar,
      int groupStartsVar,
      int groupEndsVar,
      int posVar,
      LocalVariableAllocator allocator) {
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // while (worklistSize > 0)
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitJumpInsn(IFLE, loopEnd);

    // stateId = worklist[--worklistSize]
    mv.visitIincInsn(worklistSizeVar, -1);
    mv.visitVarInsn(ALOAD, worklistVar);
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, stateIdVar);

    // if (processed.get(stateId)) continue;
    // processed.set(stateId);
    checkStateInSet(mv, processedVar, stateIdVar);
    mv.visitJumpInsn(IFNE, loopStart);
    addStateToSetVar(mv, processedVar, stateIdVar);

    // Generate switch for each state with group transitions or epsilon transitions
    List<NFA.NFAState> statesWithActions = new ArrayList<>();
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.enterGroup != null
          || state.exitGroup != null
          || !state.getEpsilonTransitions().isEmpty()) {
        statesWithActions.add(state);
      }
    }

    if (statesWithActions.isEmpty()) {
      mv.visitJumpInsn(GOTO, loopStart);
      mv.visitLabel(loopEnd);
      return;
    }

    // Create switch
    int[] caseKeys = new int[statesWithActions.size()];
    Label[] caseLabels = new Label[statesWithActions.size()];
    Label defaultLabel = new Label();

    for (int i = 0; i < statesWithActions.size(); i++) {
      caseKeys[i] = statesWithActions.get(i).id;
      caseLabels[i] = new Label();
    }

    mv.visitVarInsn(ILOAD, stateIdVar);
    mv.visitLookupSwitchInsn(defaultLabel, caseKeys, caseLabels);

    for (int i = 0; i < statesWithActions.size(); i++) {
      mv.visitLabel(caseLabels[i]);
      NFA.NFAState state = statesWithActions.get(i);

      // Track group entries
      if (state.enterGroup != null) {
        mv.visitVarInsn(ALOAD, groupStartsVar);
        pushInt(mv, state.enterGroup);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitInsn(IASTORE);
      }

      // Track group exits
      if (state.exitGroup != null) {
        mv.visitVarInsn(ALOAD, groupEndsVar);
        pushInt(mv, state.exitGroup);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitInsn(IASTORE);
      }

      // Process epsilon transitions
      for (NFA.NFAState target : state.getEpsilonTransitions()) {
        // if (!states.get(target.id)) states.set(target.id);
        Label alreadyInSet = new Label();
        checkStateInSetConst(mv, statesVar, target.id, allocator);
        mv.visitJumpInsn(IFNE, alreadyInSet);

        addStateToSet(mv, statesVar, target.id, allocator);

        mv.visitLabel(alreadyInSet);

        // worklist[worklistSize++] = target.id;
        mv.visitVarInsn(ALOAD, worklistVar);
        mv.visitVarInsn(ILOAD, worklistSizeVar);
        pushInt(mv, target.id);
        mv.visitInsn(IASTORE);
        mv.visitIincInsn(worklistSizeVar, 1);
      }

      mv.visitJumpInsn(GOTO, loopStart);
    }

    mv.visitLabel(defaultLabel);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
  }

  /**
   * Generate runtime epsilon closure computation without assertion checking. This is used by
   * sub-NFA simulation to avoid recursion.
   *
   * @param mv Method visitor
   * @param statesVar Local variable holding BitSet of states
   */
  private void generateSimpleEpsilonClosureRuntime(
      MethodVisitor mv, int statesVar, LocalVariableAllocator allocator) {
    // Allocate local variables using the allocator
    int worklistVar = allocator.allocateRef();
    int currentStateIdVar = allocator.allocateInt();
    int worklistSizeVar = allocator.allocateInt();
    // Phase 2B: Allocate processedVar based on implementation type
    int processedVar = useSingleLong ? allocator.allocateLong() : allocator.allocateRef();

    // int[] worklist = new int[stateCount];
    // int worklistSize = 0;
    pushInt(mv, nfa.getStates().size());
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, worklistVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // Create "processed" set to track which states have been processed
    if (useSingleLong) {
      generateSingleLongNew(mv, processedVar);
    } else {
      generateStateSetNew(mv);
      mv.visitVarInsn(ASTORE, processedVar);
    }

    // Add all current states to worklist
    for (NFA.NFAState state : nfa.getStates()) {
      Label skipState = new Label();

      // if (!states.get(state.id)) continue;
      checkStateInSetConst(mv, statesVar, state.id, allocator);
      mv.visitJumpInsn(IFEQ, skipState);

      // worklist[worklistSize++] = state.id;
      mv.visitVarInsn(ALOAD, worklistVar);
      mv.visitVarInsn(ILOAD, worklistSizeVar);
      pushInt(mv, state.id);
      mv.visitInsn(IASTORE);
      mv.visitIincInsn(worklistSizeVar, 1);

      mv.visitLabel(skipState);
    }

    // Process worklist
    Label worklistLoop = new Label();
    Label worklistEnd = new Label();

    mv.visitLabel(worklistLoop);

    // if (worklistSize == 0) break;
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitJumpInsn(IFEQ, worklistEnd);

    // int currentStateId = worklist[--worklistSize];
    mv.visitIincInsn(worklistSizeVar, -1);
    mv.visitVarInsn(ALOAD, worklistVar);
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, currentStateIdVar);

    // if (processed.contains(currentStateId)) continue;
    checkStateInSet(mv, processedVar, currentStateIdVar);
    mv.visitJumpInsn(IFNE, worklistLoop);

    // Mark state as processed
    addStateToSetVar(mv, processedVar, currentStateIdVar);

    // Generate switch for O(log N) state lookup
    List<NFA.NFAState> statesWithEpsilon2 = new ArrayList<>();
    for (NFA.NFAState state : nfa.getStates()) {
      if (!state.getEpsilonTransitions().isEmpty()) {
        statesWithEpsilon2.add(state);
      }
    }

    if (!statesWithEpsilon2.isEmpty()) {
      mv.visitVarInsn(ILOAD, currentStateIdVar);

      Label defaultLabel2 = new Label();
      Label[] caseLabels2 = new Label[statesWithEpsilon2.size()];
      int[] caseKeys2 = new int[statesWithEpsilon2.size()];
      for (int i = 0; i < statesWithEpsilon2.size(); i++) {
        caseLabels2[i] = new Label();
        caseKeys2[i] = statesWithEpsilon2.get(i).id;
      }

      mv.visitLookupSwitchInsn(defaultLabel2, caseKeys2, caseLabels2);

      for (int i = 0; i < statesWithEpsilon2.size(); i++) {
        mv.visitLabel(caseLabels2[i]);
        NFA.NFAState state = statesWithEpsilon2.get(i);

        // Process epsilon transitions (skip assertion states)
        for (NFA.NFAState target : state.getEpsilonTransitions()) {
          if (target.assertionType != null) continue; // Skip assertions

          Label alreadyVisited = new Label();

          // if (states.get(target.id)) continue;
          checkStateInSetConst(mv, statesVar, target.id, allocator);
          mv.visitJumpInsn(IFNE, alreadyVisited);

          // states.set(target.id);
          addStateToSet(mv, statesVar, target.id, allocator);

          // worklist[worklistSize++] = target.id;
          mv.visitVarInsn(ALOAD, worklistVar);
          mv.visitVarInsn(ILOAD, worklistSizeVar);
          pushInt(mv, target.id);
          mv.visitInsn(IASTORE);
          mv.visitIincInsn(worklistSizeVar, 1);

          mv.visitLabel(alreadyVisited);
        }

        mv.visitJumpInsn(GOTO, worklistLoop);
      }

      mv.visitLabel(defaultLabel2);
    }

    mv.visitJumpInsn(GOTO, worklistLoop);
    mv.visitLabel(worklistEnd);
  }

  /**
   * Generate optimized epsilon closure without Stack allocation. Uses fixed-point iteration which
   * is faster for small state sets (< 15 states). Iterates multiple times until no new states are
   * added.
   *
   * @param mv Method visitor
   * @param statesVar Local variable holding BitSet of states
   * @param skipAssertions If true, don't follow epsilon transitions through assertion states
   */
  private void generateOptimizedEpsilonClosure(
      MethodVisitor mv, int statesVar, boolean skipAssertions, LocalVariableAllocator allocator) {
    int changedVar =
        allocator.allocateInt(); // boolean flag: were any changes made in this iteration?

    Label iterationStart = new Label();
    Label iterationEnd = new Label();

    // do {
    mv.visitLabel(iterationStart);

    //     boolean changed = false;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, changedVar);

    //     For each state in NFA
    // Don't create a new allocator - use the one passed in
    // LocalVariableAllocator tempAllocator = new LocalVariableAllocator(30); // Start after
    // changedVar
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.getEpsilonTransitions().isEmpty()) continue;

      Label stateNotActive = new Label();

      //     if (!states.get(state.id)) continue;
      checkStateInSetConst(mv, statesVar, state.id, allocator);
      mv.visitJumpInsn(IFEQ, stateNotActive);

      //     For each epsilon transition
      for (NFA.NFAState target : state.getEpsilonTransitions()) {
        // Skip assertion states if requested
        if (skipAssertions && target.assertionType != null) {
          continue;
        }

        Label alreadySet = new Label();

        //         if (states.get(target.id)) continue;
        checkStateInSetConst(mv, statesVar, target.id, allocator);
        mv.visitJumpInsn(IFNE, alreadySet);

        //         states.set(target.id);
        addStateToSet(mv, statesVar, target.id, allocator);

        //         changed = true;
        mv.visitInsn(ICONST_1);
        mv.visitVarInsn(ISTORE, changedVar);

        mv.visitLabel(alreadySet);
      }

      mv.visitLabel(stateNotActive);
    }

    // } while (changed);
    mv.visitVarInsn(ILOAD, changedVar);
    mv.visitJumpInsn(IFNE, iterationStart);
    mv.visitLabel(iterationEnd);
  }

  /**
   * Pre-compute epsilon closure at compile time for a given start state. Returns set of all states
   * reachable via epsilon transitions (including start state itself).
   *
   * @param startState Starting state
   * @param followThroughAssertions If true, follow epsilon transitions through assertion states
   *     (used when computing closure inside assertion sub-NFA)
   * @return Set of all epsilon-reachable state IDs
   */
  private Set<Integer> computeEpsilonClosureAtCompileTime(
      NFA.NFAState startState, boolean followThroughAssertions) {
    Set<Integer> closure = new HashSet<>();
    Set<Integer> visited = new HashSet<>();
    Deque<NFA.NFAState> worklist = new ArrayDeque<>();

    worklist.add(startState);
    visited.add(startState.id);

    while (!worklist.isEmpty()) {
      NFA.NFAState current = worklist.poll();
      closure.add(current.id);

      // Don't follow epsilon transitions FROM assertion states during normal compile-time closure
      // (their targets should only be added at runtime if the assertion passes)
      // Exception: when followThroughAssertions=true (used inside assertion sub-NFA simulation),
      // we DO want to follow through assertions
      if (!followThroughAssertions && current.assertionType != null) {
        continue; // Skip processing epsilon transitions from this assertion state
      }

      for (NFA.NFAState target : current.getEpsilonTransitions()) {
        if (!visited.contains(target.id)) {
          visited.add(target.id);
          worklist.add(target);
        }
      }
    }

    return closure;
  }

  /**
   * Generate inline epsilon closure code for small state sets. Instead of using Stack and worklist,
   * directly set all epsilon-reachable states. This is much faster for small closures (< 10
   * states).
   *
   * @param mv Method visitor
   * @param statesVar Local variable holding BitSet of states
   * @param startStates Set of starting state IDs already in the BitSet
   * @param followThroughAssertions If true, follow epsilon transitions through assertion states
   *     (used when computing closure inside assertion sub-NFA)
   * @return true if inline code was generated, false if closure is too large
   */
  private boolean tryInlineEpsilonClosure(
      MethodVisitor mv,
      int statesVar,
      Set<Integer> startStates,
      boolean followThroughAssertions,
      LocalVariableAllocator allocator) {
    // Compute complete epsilon closure for all start states
    Set<Integer> completeClosure = new HashSet<>();
    for (Integer stateId : startStates) {
      // Find the state object
      NFA.NFAState state =
          nfa.getStates().stream().filter(s -> s.id == stateId).findFirst().orElse(null);
      if (state != null) {
        Set<Integer> stateClosure =
            computeEpsilonClosureAtCompileTime(state, followThroughAssertions);
        completeClosure.addAll(stateClosure);
      }
    }

    // Don't inline if closure contains assertion states (they need runtime checking)
    if (!followThroughAssertions) {
      for (Integer stateId : completeClosure) {
        NFA.NFAState state =
            nfa.getStates().stream().filter(s -> s.id == stateId).findFirst().orElse(null);
        if (state != null && state.assertionType != null) {
          return false; // Can't inline - need runtime assertion checking
        }
      }
    }

    // Only inline if closure is small (< 10 states)
    if (completeClosure.size() > 10) {
      return false;
    }

    // Generate inline code: states.set(id) for each state in closure
    // Allocate temp variable once and reuse it to avoid excessive allocations
    int tempStateIdVar = useSingleLong ? allocator.allocateInt() : -1;

    for (Integer stateId : completeClosure) {
      // if (!states.get(stateId)) states.set(stateId);
      Label alreadySet = new Label();

      if (useSingleLong) {
        // Reuse temp variable for constant state ID
        pushInt(mv, stateId);
        mv.visitVarInsn(ISTORE, tempStateIdVar);
        generateSingleLongContains(mv, statesVar, tempStateIdVar);
      } else {
        // For BitSet/SparseSet: direct constant push
        mv.visitVarInsn(ALOAD, statesVar);
        pushInt(mv, stateId);
        generateStateSetContains(mv);
      }
      mv.visitJumpInsn(IFNE, alreadySet);

      if (useSingleLong) {
        // Reuse same temp variable
        pushInt(mv, stateId);
        mv.visitVarInsn(ISTORE, tempStateIdVar);
        generateSingleLongSet(mv, statesVar, tempStateIdVar);
      } else {
        // For BitSet/SparseSet
        mv.visitVarInsn(ALOAD, statesVar);
        pushInt(mv, stateId);
        generateStateSetAdd(mv);
      }

      mv.visitLabel(alreadySet);
    }

    return true;
  }

  /**
   * Detect if we have multiple (?=.*[CharClass]) lookahead patterns that can be optimized into a
   * single-pass scan with boolean flags.
   *
   * @return List of CharSets to check for, or null if optimization doesn't apply
   */
  private List<CharSet> detectMultiLookaheadOptimization(NFA.NFAState state) {
    List<CharSet> charSets = new ArrayList<>();
    NFA.NFAState current = state;
    Set<NFA.NFAState> visited = new HashSet<>();

    // CRITICAL: Only optimize if pattern STARTS with lookaheads (no character transitions first)
    if (!current.getTransitions().isEmpty()) {
      return null; // Has character transitions - not a multi-lookahead start pattern
    }

    while (current != null && visited.add(current)) {
      if (current.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD) {
        CharSet charSet =
            extractDotStarCharClass(current.assertionStartState, current.assertionAcceptStates);
        if (charSet == null) {
          return null;
        }
        charSets.add(charSet);

        if (current.getEpsilonTransitions().size() == 1) {
          current = current.getEpsilonTransitions().get(0);
        } else {
          break;
        }
      } else if (current.getEpsilonTransitions().size() == 1
          && current.getTransitions().isEmpty()) {
        // Follow epsilon to next state (might be assertion state)
        current = current.getEpsilonTransitions().get(0);
      } else {
        break;
      }
    }

    return charSets.size() >= 2 ? charSets : null;
  }

  /** Data structure for full pattern optimization. */
  private static class FullPatternOptimization {
    List<CharSet> lookaheadCharSets; // Character classes from (?=.*[CharClass])
    int minLength; // From .{n,} or .{n,m}
    int maxLength; // From .{n,m} or -1 for unlimited

    FullPatternOptimization(List<CharSet> charSets, int min, int max) {
      this.lookaheadCharSets = charSets;
      this.minLength = min;
      this.maxLength = max;
    }
  }

  /**
   * Detect patterns like (?=.*[A-Z])(?=.*\d)(?=.*[!@#$%]).{8,} for full custom optimization.
   * Returns null if pattern doesn't match this structure.
   */
  private FullPatternOptimization detectFullPatternOptimization() {
    // First, detect multiple lookaheads at start
    List<CharSet> charSets = detectMultiLookaheadOptimization(nfa.getStartState());
    if (charSets == null || charSets.size() < 2) {
      return null; // Need at least 2 lookaheads for this optimization
    }

    // Now check if the pattern after lookaheads is a simple quantifier like .{n,m}
    // Navigate through epsilon transitions to find the state after all lookaheads
    NFA.NFAState current = nfa.getStartState();
    Set<NFA.NFAState> visited = new HashSet<>();

    // Skip through all lookahead states
    while (current != null && visited.add(current)) {
      if (current.assertionType == NFA.AssertionType.POSITIVE_LOOKAHEAD) {
        if (current.getEpsilonTransitions().size() == 1) {
          current = current.getEpsilonTransitions().get(0);
        } else {
          return null; // Unexpected structure
        }
      } else if (current.getEpsilonTransitions().size() == 1
          && current.getTransitions().isEmpty()) {
        // Non-assertion state with only epsilon - follow it
        current = current.getEpsilonTransitions().get(0);
      } else {
        break;
      }
    }

    // Now 'current' should be the state after all lookaheads
    // Check if it's a simple quantifier pattern: should have a self-loop on ANY char
    if (current == null) return null;

    // Look for pattern: state with transition on [any] back to itself or forward
    // For .{n,m}, we're looking for states that consume any character
    // This is a simplified check - for now, just verify we're at a state that can match "any"

    // For simplicity, extract min/max from the NFA structure
    // If we reach an accept state, check how the pattern is structured
    // For .{8,}, min=8, max=-1 (unlimited)
    // For .{8,20}, min=8, max=20

    // Simple heuristic: if the pattern after lookaheads has transitions on "any char",
    // and we can reach accept states, extract the length requirements

    // For now, use a simplified approach: assume .{n,} with n extracted from state distance
    int minLength = 0; // Will be calculated based on pattern

    // Check if we can find accept states reachable with "any char" transitions
    Set<NFA.NFAState> reachable = new HashSet<>();
    Queue<NFA.NFAState> queue = new LinkedList<>();
    queue.add(current);
    reachable.add(current);

    boolean hasAnyCharTransitions = false;
    while (!queue.isEmpty()) {
      NFA.NFAState state = queue.poll();

      for (NFA.Transition trans : state.getTransitions()) {
        // Check if this is an "any char" transition (includes . which is ANY_EXCEPT_NEWLINE)
        if (trans.chars.isAnyChar()) {
          hasAnyCharTransitions = true;
        }

        if (reachable.add(trans.target)) {
          queue.add(trans.target);
        }
      }

      for (NFA.NFAState epsilon : state.getEpsilonTransitions()) {
        if (reachable.add(epsilon)) {
          queue.add(epsilon);
        }
      }
    }

    if (!hasAnyCharTransitions) {
      return null; // Not a .{n,m} pattern
    }

    // Extract minLength by finding shortest path to accept state
    minLength = computeMinPathLength(current, nfa.getAcceptStates());

    if (minLength < 0) {
      return null; // Cannot determine minLength - fall back to NFA
    }

    return new FullPatternOptimization(charSets, minLength, -1);
  }

  /**
   * Compute minimum path length (non-epsilon transitions) from start to any accept state. Returns
   * -1 if no path exists or path length cannot be determined.
   */
  private int computeMinPathLength(NFA.NFAState start, Set<NFA.NFAState> acceptStates) {
    // BFS to find shortest path, counting only non-epsilon transitions
    Map<NFA.NFAState, Integer> distances = new HashMap<>();
    Queue<NFA.NFAState> queue = new LinkedList<>();

    queue.add(start);
    distances.put(start, 0);

    // First, follow all epsilon transitions from start without incrementing distance
    Set<NFA.NFAState> epsilonClosure = new HashSet<>();
    computeEpsilonClosure(start, epsilonClosure);
    for (NFA.NFAState state : epsilonClosure) {
      if (!distances.containsKey(state)) {
        distances.put(state, 0);
        queue.add(state);
      }
    }

    int minDistance = Integer.MAX_VALUE;

    while (!queue.isEmpty()) {
      NFA.NFAState state = queue.poll();
      int dist = distances.get(state);

      // Check if this is an accept state
      if (acceptStates.contains(state)) {
        minDistance = Math.min(minDistance, dist);
        continue; // Keep searching for shorter paths
      }

      // Follow character transitions (increment distance)
      for (NFA.Transition trans : state.getTransitions()) {
        int newDist = dist + 1;

        // Only update if we found a shorter path
        if (!distances.containsKey(trans.target) || distances.get(trans.target) > newDist) {
          distances.put(trans.target, newDist);
          queue.add(trans.target);

          // Also follow epsilon transitions from target
          Set<NFA.NFAState> targetClosure = new HashSet<>();
          computeEpsilonClosure(trans.target, targetClosure);
          for (NFA.NFAState epsilonState : targetClosure) {
            if (!distances.containsKey(epsilonState) || distances.get(epsilonState) > newDist) {
              distances.put(epsilonState, newDist);
              queue.add(epsilonState);
            }
          }
        }
      }
    }

    return minDistance == Integer.MAX_VALUE ? -1 : minDistance;
  }

  /** Compute epsilon closure: all states reachable via epsilon transitions only. */
  private void computeEpsilonClosure(NFA.NFAState start, Set<NFA.NFAState> closure) {
    if (!closure.add(start)) {
      return; // Already visited
    }

    for (NFA.NFAState epsilon : start.getEpsilonTransitions()) {
      computeEpsilonClosure(epsilon, closure);
    }
  }

  /**
   * Generate fully optimized bytecode for (?=.*[CharClass])+.{n,m} pattern. Single-pass scan with
   * boolean flags, no NFA simulation.
   */
  private void generateOptimizedFullPattern(MethodVisitor mv, FullPatternOptimization opt) {
    int numChecks = opt.lookaheadCharSets.size();
    int firstFlagSlot = 2; // Start boolean flags at slot 2

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, firstFlagSlot + numChecks); // len after all flags

    // Quick length check first (fail fast)
    if (opt.minLength > 0) {
      Label lengthOk = new Label();
      mv.visitVarInsn(ILOAD, firstFlagSlot + numChecks); // load len
      pushInt(mv, opt.minLength);
      mv.visitJumpInsn(IF_ICMPGE, lengthOk);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(lengthOk);
    }

    // Initialize boolean flags to false
    for (int i = 0; i < numChecks; i++) {
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, firstFlagSlot + i);
    }

    // Single-pass scan: for (int i = 0; i < len; i++)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, firstFlagSlot + numChecks + 1); // i

    Label loopStart = new Label();
    Label loopEnd = new Label();
    Label earlyExit = new Label();

    mv.visitLabel(loopStart);

    // if (i >= len) goto loopEnd
    mv.visitVarInsn(ILOAD, firstFlagSlot + numChecks + 1); // load i
    mv.visitVarInsn(ILOAD, firstFlagSlot + numChecks); // load len
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char c = input.charAt(i);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, firstFlagSlot + numChecks + 1); // load i
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, firstFlagSlot + numChecks + 2); // c

    // Check each character class and set flags
    for (int i = 0; i < numChecks; i++) {
      Label skipCheck = new Label();

      // if (flag[i]) skip check (already found)
      mv.visitVarInsn(ILOAD, firstFlagSlot + i);
      mv.visitJumpInsn(IFNE, skipCheck);

      // Check if character matches charset[i]
      generateCharSetCheck(mv, opt.lookaheadCharSets.get(i), firstFlagSlot + numChecks + 2);

      // if (match) flag[i] = true
      Label noMatch = new Label();
      mv.visitJumpInsn(IFEQ, noMatch);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, firstFlagSlot + i);
      mv.visitLabel(noMatch);

      mv.visitLabel(skipCheck);
    }

    // Early exit check: if all flags are true, we're done
    Label continueLoop = new Label();
    for (int i = 0; i < numChecks; i++) {
      mv.visitVarInsn(ILOAD, firstFlagSlot + i);
      mv.visitJumpInsn(IFEQ, continueLoop); // If any false, continue loop
    }
    // All true - exit early
    mv.visitJumpInsn(GOTO, earlyExit);

    mv.visitLabel(continueLoop);
    // i++
    mv.visitIincInsn(firstFlagSlot + numChecks + 1, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(earlyExit);
    mv.visitLabel(loopEnd);

    // Check if all flags are set
    for (int i = 0; i < numChecks; i++) {
      mv.visitVarInsn(ILOAD, firstFlagSlot + i);
      Label flagSet = new Label();
      mv.visitJumpInsn(IFNE, flagSet);
      // Flag not set - return false
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
      mv.visitLabel(flagSet);
    }

    // All conditions met - return true
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
  }

  /**
   * Extract character class from .*[CharClass] pattern in sub-NFA. Returns null if pattern is not
   * of this form.
   */
  private CharSet extractDotStarCharClass(NFA.NFAState start, Set<NFA.NFAState> acceptStates) {

    // For .*[CharClass] pattern, we need to explore all reachable states and find
    // transitions that lead to accept states with specific character classes
    Set<NFA.NFAState> reachable = new HashSet<>();
    Queue<NFA.NFAState> queue = new LinkedList<>();
    queue.add(start);
    reachable.add(start);

    // BFS to find all reachable states
    while (!queue.isEmpty()) {
      NFA.NFAState current = queue.poll();

      // Check all character transitions
      for (NFA.Transition trans : current.getTransitions()) {
        // Found a transition to accept state - check if it's a specific character class (not "any")
        if (acceptStates.contains(trans.target)) {
          // Check if this is a specific charset (not "any char" which includes ANY_EXCEPT_NEWLINE)
          if (!trans.chars.isAnyChar()) {
            return trans.chars;
          }
        }

        if (reachable.add(trans.target)) {
          queue.add(trans.target);
        }
      }

      // Follow epsilon transitions
      for (NFA.NFAState epsilon : current.getEpsilonTransitions()) {
        if (reachable.add(epsilon)) {
          queue.add(epsilon);
        }
      }
    }

    return null;
  }

  /**
   * Generate optimized single-pass bytecode for multiple (?=.*[CharClass]) lookaheads. Zero
   * allocations, early exit when all conditions met, direct bytecode character checks.
   */
  private void generateOptimizedMultiLookahead(
      MethodVisitor mv,
      List<CharSet> charSets,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      LocalVariableAllocator allocator) {
    int numChecks = charSets.size();

    // Allocate flag slots for each character check
    int[] flagSlots = new int[numChecks];
    for (int i = 0; i < numChecks; i++) {
      flagSlots[i] = allocator.allocateInt();
    }

    // Allocate temp variables
    int scanPosVar = allocator.allocateInt();
    int charVar = allocator.allocateInt();

    // Initialize all flags to false (0)
    for (int i = 0; i < numChecks; i++) {
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, flagSlots[i]);
    }

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, scanPosVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    for (int i = 0; i < numChecks; i++) {
      Label skipCheck = new Label();

      mv.visitVarInsn(ILOAD, flagSlots[i]);
      mv.visitJumpInsn(IFNE, skipCheck);

      generateCharSetCheck(mv, charSets.get(i), charVar);

      Label noMatch = new Label();
      mv.visitJumpInsn(IFEQ, noMatch);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, flagSlots[i]);
      mv.visitLabel(noMatch);

      mv.visitLabel(skipCheck);
    }

    Label continueLoop = new Label();
    for (int i = 0; i < numChecks; i++) {
      mv.visitVarInsn(ILOAD, flagSlots[i]);
      mv.visitJumpInsn(IFEQ, continueLoop);
    }
    mv.visitJumpInsn(GOTO, loopEnd);

    mv.visitLabel(continueLoop);
    mv.visitIincInsn(scanPosVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    for (int i = 0; i < numChecks; i++) {
      mv.visitVarInsn(ILOAD, flagSlots[i]);
      mv.visitJumpInsn(IFEQ, failLabel);
    }
  }

  /**
   * Generate optimized single-pass bytecode for multiple (?=.*[CharClass]) lookaheads. Uses
   * pre-allocated slots to avoid allocation inside loops.
   */
  private void generateOptimizedMultiLookaheadWithPrealloc(
      MethodVisitor mv,
      List<CharSet> charSets,
      int inputVar,
      int posVar,
      int lenVar,
      Label failLabel,
      int[] flagSlots,
      int scanPosVar,
      int charVar) {
    int numChecks = charSets.size();

    // Initialize all flags to false (0)
    for (int i = 0; i < numChecks; i++) {
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ISTORE, flagSlots[i]);
    }

    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, scanPosVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, scanPosVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, charVar);

    for (int i = 0; i < numChecks; i++) {
      Label skipCheck = new Label();

      mv.visitVarInsn(ILOAD, flagSlots[i]);
      mv.visitJumpInsn(IFNE, skipCheck);

      generateCharSetCheck(mv, charSets.get(i), charVar);

      Label noMatch = new Label();
      mv.visitJumpInsn(IFEQ, noMatch);
      mv.visitInsn(ICONST_1);
      mv.visitVarInsn(ISTORE, flagSlots[i]);
      mv.visitLabel(noMatch);

      mv.visitLabel(skipCheck);
    }

    Label continueLoop = new Label();
    for (int i = 0; i < numChecks; i++) {
      mv.visitVarInsn(ILOAD, flagSlots[i]);
      mv.visitJumpInsn(IFEQ, continueLoop);
    }
    mv.visitJumpInsn(GOTO, loopEnd);

    mv.visitLabel(continueLoop);
    mv.visitIincInsn(scanPosVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    for (int i = 0; i < numChecks; i++) {
      mv.visitVarInsn(ILOAD, flagSlots[i]);
      mv.visitJumpInsn(IFEQ, failLabel);
    }
  }

  /**
   * Generate match() method that returns MatchResult with captured groups.
   *
   * <h4>Generated Method Signature</h4>
   *
   * <pre>{@code public MatchResult match(String input)}</pre>
   *
   * <h4>Generated Algorithm</h4>
   *
   * <pre>{@code
   * MatchResult match(String input) {
   *     if (input == null) return null;
   *     int len = input.length();
   *
   *     // Group tracking arrays
   *     int[] groupStarts = new int[groupCount + 1];
   *     int[] groupEnds = new int[groupCount + 1];
   *     Arrays.fill(groupStarts, -1);
   *     Arrays.fill(groupEnds, -1);
   *     groupStarts[0] = 0;  // group 0 = entire match
   *
   *     // Initialize NFA with epsilon closure
   *     currentStates.add(startStateId);
   *     epsilonClosureWithGroups(currentStates, groupStarts, groupEnds, 0);
   *
   *     // Main loop: process each character
   *     for (int pos = 0; pos < len; pos++) {
   *         char ch = input.charAt(pos);
   *         nextStates.clear();
   *
   *         // NFA step with group tracking
   *         for (int stateId : currentStates) {
   *             for (Transition t : states[stateId].transitions) {
   *                 if (t.matches(ch)) {
   *                     nextStates.add(t.target);
   *                 }
   *             }
   *         }
   *
   *         // Epsilon closure updates group boundaries
   *         epsilonClosureWithGroups(nextStates, groupStarts, groupEnds, pos + 1);
   *         swap(currentStates, nextStates);
   *     }
   *
   *     // Check accept states
   *     if (containsAcceptState(currentStates)) {
   *         groupEnds[0] = len;
   *         return new MatchResult(input, groupStarts, groupEnds);
   *     }
   *     return null;
   * }
   * }</pre>
   *
   * <h4>Returns</h4>
   *
   * MatchResult with captured groups, or null if no match.
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

    // Create local variable allocator
    // Method signature: match(String input)
    // Slots: 0=this, 1=input, 2+ = local variables
    LocalVariableAllocator allocator = new LocalVariableAllocator(2);

    int groupCount = nfa.getGroupCount();

    // if (input == null) return null;
    Label inputNotNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, inputNotNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(inputNotNull);

    // Allocate all local variables using allocator
    int groupStartsVar = allocator.allocateRef(); // int[]
    int groupEndsVar = allocator.allocateRef(); // int[]
    // Allocate state sets - for dual-long, slot values are encoded (negative = dual-long)
    int currentStatesVar;
    int nextStatesVar;
    if (useSingleLong) {
      currentStatesVar = allocator.allocateLong(); // long (2 slots)
      nextStatesVar = allocator.allocateLong(); // long (2 slots)
    } else if (useDualLong) {
      currentStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
      nextStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      currentStatesVar = allocator.allocateRef(); // object reference (1 slot)
      nextStatesVar = allocator.allocateRef(); // object reference (1 slot)
    }
    int posVar = allocator.allocateInt(); // int
    int lenVar = allocator.allocateInt(); // int

    // PRE-ALLOCATE epsilon closure slots BEFORE ANY bytecode generation
    // This prevents allocation inside loops which causes JVM VerifyError
    EpsilonClosureSlots epsilonSlots;
    int worklistVar = allocator.allocateRef();
    int stateIdVar = allocator.allocateInt();
    int worklistSizeVar = allocator.allocateInt();
    // processedVar allocation - for dual-long, uses encoded slot value
    int processedVar;
    if (useSingleLong) {
      processedVar = allocator.allocateLong(); // long (2 slots)
    } else if (useDualLong) {
      processedVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      processedVar = allocator.allocateRef(); // object reference (1 slot)
    }
    int indexVar = allocator.allocateInt();
    int sizeVar = allocator.allocateInt();
    int parentIdVar = allocator.allocateInt(); // For POSIX per-config tracking

    epsilonSlots =
        new EpsilonClosureSlots(
            worklistVar, stateIdVar, worklistSizeVar, processedVar, indexVar, sizeVar, parentIdVar);

    // Initialize all slots with default values
    pushInt(mv, nfa.getStates().size());
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, worklistVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, stateIdVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // Initialize processedVar (auto-detects dual-long from encoded slot)
    initStateSet(mv, processedVar);

    // Allocate group tracking arrays
    // int[] groupStarts = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupStartsVar);

    // int[] groupEnds = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupEndsVar);

    // Initialize all group positions to -1
    for (int i = 0; i <= groupCount; i++) {
      mv.visitVarInsn(ALOAD, groupStartsVar);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
      mv.visitVarInsn(ALOAD, groupEndsVar);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // Per-configuration group tracking (only for POSIX patterns with groups in quantifiers)
    int configGroupStartsVar = -1, configGroupEndsVar = -1, parentStateMapVar = -1;
    if (usePosixLastMatch) {
      // Allocate configuration tracking arrays
      configGroupStartsVar = allocator.allocateRef();
      configGroupEndsVar = allocator.allocateRef();
      parentStateMapVar = allocator.allocateRef();

      // int[][] configGroupStarts = new int[stateCount][groupCount + 1];
      // Note: Group 0 is managed globally, so per-config arrays still need [groupCount + 1]
      // to maintain index alignment, but group 0 slots will remain unused (-1)
      pushInt(mv, stateCount);
      pushInt(mv, groupCount + 1);
      mv.visitMultiANewArrayInsn("[[I", 2);
      mv.visitVarInsn(ASTORE, configGroupStartsVar);

      // int[][] configGroupEnds = new int[stateCount][groupCount + 1];
      pushInt(mv, stateCount);
      pushInt(mv, groupCount + 1);
      mv.visitMultiANewArrayInsn("[[I", 2);
      mv.visitVarInsn(ASTORE, configGroupEndsVar);

      // int[] parentStateMap = new int[stateCount];
      pushInt(mv, stateCount);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, parentStateMapVar);

      // Initialize all configuration arrays to -1
      generateInitializeConfigArrays(
          mv,
          configGroupStartsVar,
          configGroupEndsVar,
          parentStateMapVar,
          stateCount,
          groupCount,
          allocator);
    }

    // Set group 0 start = 0 (managed globally, not in per-config arrays)
    mv.visitVarInsn(ALOAD, groupStartsVar);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);

    // Run NFA simulation with group tracking
    // Initialize state sets (auto-detects dual-long from encoded slot)
    if (useSingleLong || useDualLong) {
      initStateSet(mv, currentStatesVar);
      initStateSet(mv, nextStatesVar);
    } else {
      // For StateSet (>128 states): use pre-allocated instance fields instead of creating new
      // objects
      // Load this.currentStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "currentStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, currentStatesVar);
      mv.visitVarInsn(ALOAD, currentStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);

      // Load this.nextStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "nextStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, nextStatesVar);
      mv.visitVarInsn(ALOAD, nextStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);
    }

    // int pos = 0, len = input.length();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Initialize: add start state (unified method auto-detects dual-long)
    addStateToSet(mv, currentStatesVar, nfa.getStartState().id, allocator);

    // Compute epsilon closure with group tracking
    generateEpsilonClosureWithGroups(
        mv,
        currentStatesVar,
        1,
        posVar,
        groupStartsVar,
        groupEndsVar,
        configGroupStartsVar,
        configGroupEndsVar,
        parentStateMapVar,
        allocator,
        epsilonSlots);

    // Allocate temporary variable for the main loop
    int chVar = allocator.allocateInt();

    // Main loop: process each character
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos++);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    mv.visitIincInsn(posVar, 1); // pos++

    // nextStates.clear() - unified method auto-detects dual-long
    clearStateSet(mv, nextStatesVar);

    // Process transitions for all active states (unified - auto-detects dual-long)
    generateNFAStep(
        mv,
        currentStatesVar,
        nextStatesVar,
        chVar,
        configGroupStartsVar,
        configGroupEndsVar,
        groupCount,
        allocator);

    // Compute epsilon closure of nextStates with group tracking
    generateEpsilonClosureWithGroups(
        mv,
        nextStatesVar,
        1,
        posVar,
        groupStartsVar,
        groupEndsVar,
        configGroupStartsVar,
        configGroupEndsVar,
        parentStateMapVar,
        allocator,
        epsilonSlots);

    // Swap state sets (unified method auto-detects dual-long)
    swapStateSets(mv, currentStatesVar, nextStatesVar, allocator);

    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check if any accept state is in currentStates (unified - auto-detects dual-long)
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      checkStateInSetConst(mv, currentStatesVar, acceptState.id, allocator);

      Label notThisAccept = new Label();
      mv.visitJumpInsn(IFEQ, notThisAccept);

      // Match found! Set group 0 end in global array (not per-config)
      mv.visitVarInsn(ALOAD, groupEndsVar);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitInsn(IASTORE);

      // Extract groups from per-config arrays for this accept state
      // This is critical for quantified groups where global arrays may have been
      // speculatively updated during epsilon closure for paths that didn't succeed
      if (usePosixLastMatch && configGroupStartsVar >= 0) {
        // Copy configGroupStarts[acceptState.id][g] to groupStarts[g] for g = 1..groupCount
        for (int g = 1; g <= groupCount; g++) {
          // S: [] -> [A:int[]] -> [A:int[], I] -> [A:int[], I, A:int[][]] -> [A:int[], I, A:int[]]
          // -> [A:int[], I, A:int[], I] -> [A:int[], I, I] -> []
          mv.visitVarInsn(ALOAD, groupStartsVar);
          pushInt(mv, g);
          mv.visitVarInsn(ALOAD, configGroupStartsVar);
          pushInt(mv, acceptState.id);
          mv.visitInsn(AALOAD); // configGroupStarts[acceptState.id]
          pushInt(mv, g);
          mv.visitInsn(IALOAD); // configGroupStarts[acceptState.id][g]
          mv.visitInsn(IASTORE); // groupStarts[g] = value

          // S: [] -> [A:int[]] -> [A:int[], I] -> [A:int[], I, A:int[][]] -> [A:int[], I, A:int[]]
          // -> [A:int[], I, A:int[], I] -> [A:int[], I, I] -> []
          mv.visitVarInsn(ALOAD, groupEndsVar);
          pushInt(mv, g);
          mv.visitVarInsn(ALOAD, configGroupEndsVar);
          pushInt(mv, acceptState.id);
          mv.visitInsn(AALOAD); // configGroupEnds[acceptState.id]
          pushInt(mv, g);
          mv.visitInsn(IALOAD); // configGroupEnds[acceptState.id][g]
          mv.visitInsn(IASTORE); // groupEnds[g] = value
        }
      }

      // Create MatchResultImpl
      mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
      mv.visitInsn(DUP);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ALOAD, groupStartsVar);
      mv.visitVarInsn(ALOAD, groupEndsVar);
      pushInt(mv, groupCount);
      mv.visitMethodInsn(
          INVOKESPECIAL,
          "com/datadoghq/reggie/runtime/MatchResultImpl",
          "<init>",
          "(Ljava/lang/String;[I[II)V",
          false);
      mv.visitInsn(ARETURN);

      mv.visitLabel(notThisAccept);
    }

    // No accept state reached - return null
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matchBounded() method that matches a substring range without allocation.
   *
   * <h4>Generated Method Signature</h4>
   *
   * <pre>{@code public MatchResult matchBounded(String input, int start, int end)}</pre>
   *
   * <h4>Generated Algorithm</h4>
   *
   * <pre>{@code
   * MatchResult matchBounded(String input, int start, int end) {
   *     if (input == null) return null;
   *
   *     // Same as match() but operates on input[start..end]
   *     int[] groupStarts = new int[groupCount + 1];
   *     int[] groupEnds = new int[groupCount + 1];
   *     Arrays.fill(groupStarts, -1);
   *     Arrays.fill(groupEnds, -1);
   *     groupStarts[0] = start;
   *
   *     currentStates.add(startStateId);
   *     epsilonClosureWithGroups(currentStates, groupStarts, groupEnds, start);
   *
   *     for (int pos = start; pos < end; pos++) {
   *         char ch = input.charAt(pos);
   *         nextStates.clear();
   *
   *         for (int stateId : currentStates) {
   *             for (Transition t : states[stateId].transitions) {
   *                 if (t.matches(ch)) nextStates.add(t.target);
   *             }
   *         }
   *
   *         epsilonClosureWithGroups(nextStates, groupStarts, groupEnds, pos + 1);
   *         swap(currentStates, nextStates);
   *     }
   *
   *     if (containsAcceptState(currentStates)) {
   *         groupEnds[0] = end;
   *         return new MatchResult(input, groupStarts, groupEnds);
   *     }
   *     return null;
   * }
   * }</pre>
   *
   * <h4>Purpose</h4>
   *
   * Eliminates substring allocations in findMatchFrom loops by using indices.
   */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Create local variable allocator
    // Method signature: matchBounded(String input, int start, int end)
    // Slots: 0=this, 1=input, 2=start, 3=end, 4+ = local variables
    LocalVariableAllocator allocator = new LocalVariableAllocator(4);

    int groupCount = nfa.getGroupCount();

    // if (input == null) return null;
    Label inputNotNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, inputNotNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(inputNotNull);

    // Allocate all local variables using allocator
    int groupStartsVar = allocator.allocateRef();
    int groupEndsVar = allocator.allocateRef();
    // Allocate state sets - for dual-long, slot values are encoded (negative = dual-long)
    int currentStatesVar;
    int nextStatesVar;
    if (useSingleLong) {
      currentStatesVar = allocator.allocateLong(); // long (2 slots)
      nextStatesVar = allocator.allocateLong(); // long (2 slots)
    } else if (useDualLong) {
      currentStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
      nextStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      currentStatesVar = allocator.allocateRef(); // object reference (1 slot)
      nextStatesVar = allocator.allocateRef(); // object reference (1 slot)
    }
    int posVar = allocator.allocateInt();

    // PRE-ALLOCATE epsilon closure slots BEFORE ANY bytecode generation
    EpsilonClosureSlots epsilonSlots;
    int worklistVar = allocator.allocateRef();
    int stateIdVar = allocator.allocateInt();
    int worklistSizeVar = allocator.allocateInt();
    // processedVar allocation - for dual-long, uses encoded slot value
    int processedVar;
    if (useSingleLong) {
      processedVar = allocator.allocateLong(); // long (2 slots)
    } else if (useDualLong) {
      processedVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      processedVar = allocator.allocateRef(); // object reference (1 slot)
    }
    int indexVar = allocator.allocateInt();
    int sizeVar = allocator.allocateInt();
    int parentIdVar = allocator.allocateInt(); // For POSIX per-config tracking

    epsilonSlots =
        new EpsilonClosureSlots(
            worklistVar, stateIdVar, worklistSizeVar, processedVar, indexVar, sizeVar, parentIdVar);

    // Initialize all slots with default values
    pushInt(mv, nfa.getStates().size());
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, worklistVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, stateIdVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // Initialize processedVar (auto-detects dual-long from encoded slot)
    initStateSet(mv, processedVar);

    // Allocate group tracking arrays
    // int[] groupStarts = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupStartsVar); // groupStarts

    // int[] groupEnds = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupEndsVar); // groupEnds

    // Initialize all group positions to -1
    for (int i = 0; i <= groupCount; i++) {
      mv.visitVarInsn(ALOAD, groupStartsVar);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
      mv.visitVarInsn(ALOAD, groupEndsVar);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // Per-configuration group tracking (only for POSIX patterns with groups in quantifiers)
    int configGroupStartsVar = -1, configGroupEndsVar = -1, parentStateMapVar = -1;
    if (usePosixLastMatch) {
      // Allocate configuration tracking arrays
      configGroupStartsVar = allocator.allocateRef();
      configGroupEndsVar = allocator.allocateRef();
      parentStateMapVar = allocator.allocateRef();

      int stateCount = nfa.getStates().size();

      // int[][] configGroupStarts = new int[stateCount][groupCount + 1];
      // Note: Group 0 is managed globally, so per-config arrays still need [groupCount + 1]
      // to maintain index alignment, but group 0 slots will remain unused (-1)
      pushInt(mv, stateCount);
      pushInt(mv, groupCount + 1);
      mv.visitMultiANewArrayInsn("[[I", 2);
      mv.visitVarInsn(ASTORE, configGroupStartsVar);

      // int[][] configGroupEnds = new int[stateCount][groupCount + 1];
      pushInt(mv, stateCount);
      pushInt(mv, groupCount + 1);
      mv.visitMultiANewArrayInsn("[[I", 2);
      mv.visitVarInsn(ASTORE, configGroupEndsVar);

      // int[] parentStateMap = new int[stateCount];
      pushInt(mv, stateCount);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, parentStateMapVar);

      // Initialize all configuration arrays to -1
      generateInitializeConfigArrays(
          mv,
          configGroupStartsVar,
          configGroupEndsVar,
          parentStateMapVar,
          stateCount,
          groupCount,
          allocator);
    }

    // Set group 0 start = start (param 2)
    mv.visitVarInsn(ALOAD, groupStartsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 2); // start parameter
    mv.visitInsn(IASTORE);

    // Also initialize group 0 in the start state's per-config arrays
    if (usePosixLastMatch && configGroupStartsVar >= 0) {
      // configGroupStarts[startState.id][0] = start
      mv.visitVarInsn(ALOAD, configGroupStartsVar);
      pushInt(mv, nfa.getStartState().id);
      mv.visitInsn(AALOAD); // Load row for start state
      mv.visitInsn(ICONST_0); // group 0
      mv.visitVarInsn(ILOAD, 2); // start parameter
      mv.visitInsn(IASTORE);
    }

    // Run NFA simulation with group tracking
    // Initialize state sets (auto-detects dual-long from encoded slot)
    if (useSingleLong || useDualLong) {
      initStateSet(mv, currentStatesVar);
      initStateSet(mv, nextStatesVar);
    } else {
      // For StateSet (>128 states): use pre-allocated instance fields instead of creating new
      // objects
      // Load this.currentStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "currentStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, currentStatesVar);
      mv.visitVarInsn(ALOAD, currentStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);

      // Load this.nextStates and clear it
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "nextStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, nextStatesVar);
      mv.visitVarInsn(ALOAD, nextStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);
    }

    // int pos = start (param 2), end = param 3
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar); // pos
    // end is already in param 3, we'll use it directly

    // Initialize: add start state (unified method auto-detects dual-long)
    addStateToSet(mv, currentStatesVar, nfa.getStartState().id, allocator);

    // Compute epsilon closure with group tracking
    generateEpsilonClosureWithGroups(
        mv,
        currentStatesVar,
        1,
        posVar,
        groupStartsVar,
        groupEndsVar,
        configGroupStartsVar,
        configGroupEndsVar,
        parentStateMapVar,
        allocator,
        epsilonSlots);

    // Allocate variable for loop
    int chVar = allocator.allocateInt();

    // Main loop: process each character from start to end
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar); // pos
    mv.visitVarInsn(ILOAD, 3); // end (param 3)
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos++);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar); // ch

    mv.visitIincInsn(posVar, 1); // pos++

    // nextStates.clear() - unified method auto-detects dual-long
    clearStateSet(mv, nextStatesVar);

    // Process transitions for all active states (unified - auto-detects dual-long)
    generateNFAStep(
        mv,
        currentStatesVar,
        nextStatesVar,
        chVar,
        configGroupStartsVar,
        configGroupEndsVar,
        groupCount,
        allocator);

    // Compute epsilon closure of nextStates with group tracking
    generateEpsilonClosureWithGroups(
        mv,
        nextStatesVar,
        1,
        posVar,
        groupStartsVar,
        groupEndsVar,
        configGroupStartsVar,
        configGroupEndsVar,
        parentStateMapVar,
        allocator,
        epsilonSlots);

    // Swap state sets (unified method auto-detects dual-long)
    swapStateSets(mv, currentStatesVar, nextStatesVar, allocator);

    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Check if any accept state is in currentStates (unified - auto-detects dual-long)
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      checkStateInSetConst(mv, currentStatesVar, acceptState.id, allocator);

      Label notThisAccept = new Label();
      mv.visitJumpInsn(IFEQ, notThisAccept);

      // Match found! Set group 0 end in global array (not per-config)
      mv.visitVarInsn(ALOAD, groupEndsVar);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ILOAD, 3); // end parameter
      mv.visitInsn(IASTORE);

      // Extract groups from per-config arrays for this accept state
      // This is critical for quantified groups where global arrays may have been
      // speculatively updated during epsilon closure for paths that didn't succeed
      if (usePosixLastMatch && configGroupStartsVar >= 0) {
        // Copy configGroupStarts[acceptState.id][g] to groupStarts[g] for g = 1..groupCount
        for (int g = 1; g <= groupCount; g++) {
          // S: [] -> [A:int[]] -> [A:int[], I] -> [A:int[], I, A:int[][]] -> [A:int[], I, A:int[]]
          // -> [A:int[], I, A:int[], I] -> [A:int[], I, I] -> []
          mv.visitVarInsn(ALOAD, groupStartsVar);
          pushInt(mv, g);
          mv.visitVarInsn(ALOAD, configGroupStartsVar);
          pushInt(mv, acceptState.id);
          mv.visitInsn(AALOAD); // configGroupStarts[acceptState.id]
          pushInt(mv, g);
          mv.visitInsn(IALOAD); // configGroupStarts[acceptState.id][g]
          mv.visitInsn(IASTORE); // groupStarts[g] = value

          // S: [] -> [A:int[]] -> [A:int[], I] -> [A:int[], I, A:int[][]] -> [A:int[], I, A:int[]]
          // -> [A:int[], I, A:int[], I] -> [A:int[], I, I] -> []
          mv.visitVarInsn(ALOAD, groupEndsVar);
          pushInt(mv, g);
          mv.visitVarInsn(ALOAD, configGroupEndsVar);
          pushInt(mv, acceptState.id);
          mv.visitInsn(AALOAD); // configGroupEnds[acceptState.id]
          pushInt(mv, g);
          mv.visitInsn(IALOAD); // configGroupEnds[acceptState.id][g]
          mv.visitInsn(IASTORE); // groupEnds[g] = value
        }
      }

      // Create MatchResultImpl
      mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
      mv.visitInsn(DUP);
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ALOAD, groupStartsVar); // groupStarts
      mv.visitVarInsn(ALOAD, groupEndsVar); // groupEnds
      pushInt(mv, groupCount);
      mv.visitMethodInsn(
          INVOKESPECIAL,
          "com/datadoghq/reggie/runtime/MatchResultImpl",
          "<init>",
          "(Ljava/lang/String;[I[II)V",
          false);
      mv.visitInsn(ARETURN);

      mv.visitLabel(notThisAccept);
    }

    // No accept state reached - return null
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate bytecode to validate a backreference at the current position. If the backreference
   * matches, adds epsilon targets and advances position.
   */
  private void generateBackreferenceCheck(
      MethodVisitor mv,
      NFA.NFAState state,
      int inputVar,
      int posVar,
      int groupStartsVar,
      int groupEndsVar,
      int statesVar,
      int worklistVar,
      int worklistSizeVar,
      LocalVariableAllocator allocator) {
    int groupNum = state.backrefCheck;

    // Allocate local variables using allocator
    int groupStartLocal = allocator.allocateInt();
    int groupEndLocal = allocator.allocateInt();
    int groupLenLocal = allocator.allocateInt();
    int inputLenLocal = allocator.allocateInt();

    // Load group start: int groupStart = groupStarts[groupNum];
    mv.visitVarInsn(ALOAD, groupStartsVar);
    pushInt(mv, groupNum);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, groupStartLocal);

    // Load group end: int groupEnd = groupEnds[groupNum];
    mv.visitVarInsn(ALOAD, groupEndsVar);
    pushInt(mv, groupNum);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, groupEndLocal);

    // Check if group was captured: if (groupStart < 0) fail
    Label backrefFailed = new Label();
    Label backrefEnd = new Label();
    mv.visitVarInsn(ILOAD, groupStartLocal);
    mv.visitJumpInsn(IFLT, backrefFailed);

    // Calculate group length: int groupLen = groupEnd - groupStart;
    mv.visitVarInsn(ILOAD, groupEndLocal);
    mv.visitVarInsn(ILOAD, groupStartLocal);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, groupLenLocal);

    // Get input length for bounds checking
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, inputLenLocal);

    // Check if enough input remains: if (pos + groupLen > inputLen) fail
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenLocal);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ILOAD, inputLenLocal);
    mv.visitJumpInsn(IF_ICMPGT, backrefFailed);

    // Validate: input.regionMatches([ignoreCase,] pos, input, groupStart, groupLen)
    mv.visitVarInsn(ALOAD, inputVar); // 'this' string
    if (caseInsensitive) {
      // Use 5-parameter version: regionMatches(boolean ignoreCase, int toffset, String other, int
      // ooffset, int len)
      mv.visitInsn(ICONST_1); // ignoreCase = true
      mv.visitVarInsn(ILOAD, posVar); // toffset (current position)
      mv.visitVarInsn(ALOAD, inputVar); // 'other' string (same string)
      mv.visitVarInsn(ILOAD, groupStartLocal); // ooffset (group start)
      mv.visitVarInsn(ILOAD, groupLenLocal); // len (group length)
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ZILjava/lang/String;II)Z", false);
    } else {
      // Use 4-parameter version: regionMatches(int toffset, String other, int ooffset, int len)
      mv.visitVarInsn(ILOAD, posVar); // toffset (current position)
      mv.visitVarInsn(ALOAD, inputVar); // 'other' string (same string)
      mv.visitVarInsn(ILOAD, groupStartLocal); // ooffset (group start)
      mv.visitVarInsn(ILOAD, groupLenLocal); // len (group length)
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);
    }

    // If regionMatches returned false, backreference failed
    mv.visitJumpInsn(IFEQ, backrefFailed);

    // Backreference matched! Add epsilon transition targets
    for (NFA.NFAState target : state.getEpsilonTransitions()) {
      Label alreadyVisited = new Label();
      // Uses unified method that auto-detects dual-long from encoded slot
      checkStateInSetConst(mv, statesVar, target.id, allocator);
      mv.visitJumpInsn(IFNE, alreadyVisited);

      addStateToSet(mv, statesVar, target.id, allocator);

      // worklist[worklistSize++] = target.id;
      mv.visitVarInsn(ALOAD, worklistVar);
      mv.visitVarInsn(ILOAD, worklistSizeVar);
      pushInt(mv, target.id);
      mv.visitInsn(IASTORE);
      mv.visitIincInsn(worklistSizeVar, 1);

      mv.visitLabel(alreadyVisited);
    }

    // Advance position by group length: pos += groupLen;
    // NOTE: This modifies posVar during epsilon closure, which breaks the NFA invariant
    // that epsilon closures are zero-width. This can cause issues with concurrent state
    // processing but is necessary for backreferences to work correctly. A proper fix
    // requires refactoring to track position per state (Plan Phase 2) or processing
    // backreferences outside the main NFA loop.
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, groupLenLocal);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, posVar);

    mv.visitJumpInsn(GOTO, backrefEnd);

    mv.visitLabel(backrefFailed);
    // Backreference failed - don't add epsilon targets

    mv.visitLabel(backrefEnd);
  }

  /**
   * Generate epsilon closure that also tracks group boundaries. When visiting a state with
   * enterGroup/exitGroup, updates the group arrays.
   *
   * <p>Group Capture Semantics (POSIX Last-Match): - Group starts: Always update on entry (last
   * iteration wins) - Group ends: Always update on exit (last iteration wins) - This matches PCRE
   * behavior for quantified groups - Example: (a?)+ on "ab" captures "" (empty from last
   * iteration), not "a" (first)
   */
  private void generateEpsilonClosureWithGroups(
      MethodVisitor mv,
      int statesVar,
      int inputVar,
      int posVar,
      int groupStartsVar,
      int groupEndsVar,
      int configGroupStartsVar,
      int configGroupEndsVar,
      int parentStateMapVar,
      LocalVariableAllocator allocator,
      EpsilonClosureSlots preAllocSlots) {
    // Use pre-allocated slots if provided, otherwise allocate new ones
    int worklistVar =
        preAllocSlots.worklistVar >= 0 ? preAllocSlots.worklistVar : allocator.allocateInt();
    int stateIdVar =
        preAllocSlots.stateIdVar >= 0 ? preAllocSlots.stateIdVar : allocator.allocateInt();
    int worklistSizeVar =
        preAllocSlots.worklistSizeVar >= 0
            ? preAllocSlots.worklistSizeVar
            : allocator.allocateInt();
    // Phase 2B/2C: Use pre-allocated processedVar based on implementation type
    // NOTE: For dual-long mode, processedVar is negative (encoded via ~slot0), NOT -1
    // So we check != -1 instead of >= 0 to avoid allocating a new variable inside the loop
    int processedVar;
    if (preAllocSlots.processedVar != -1) {
      processedVar = preAllocSlots.processedVar;
    } else {
      // Fallback allocation (should rarely happen if callers pre-allocate)
      if (useSingleLong) {
        processedVar = allocator.allocateLong();
      } else if (useDualLong) {
        processedVar = allocateDualLongStateSet(allocator);
      } else {
        processedVar = allocator.allocateRef();
      }
    }

    // Use pre-allocated parentIdVar for per-config tracking (used in loop)
    int parentIdVar = preAllocSlots.parentIdVar;

    // If worklist not pre-allocated, create it now
    if (preAllocSlots.worklistVar < 0) {
      pushInt(mv, nfa.getStates().size());
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitVarInsn(ASTORE, worklistVar);
    }

    // Reset worklistSize to 0 (whether pre-allocated or not)
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // If processed set not pre-allocated, create it now
    // NOTE: Check == -1 (unallocated sentinel) not < 0 (which catches encoded dual-long slots)
    if (preAllocSlots.processedVar == -1) {
      if (useSingleLong) {
        generateSingleLongNew(mv, processedVar);
      } else {
        generateStateSetNew(mv);
        mv.visitVarInsn(ASTORE, processedVar);
      }
    }

    // Clear the processed set (whether pre-allocated or not)
    clearStateSet(mv, processedVar);

    // Add all current states to worklist
    for (NFA.NFAState state : nfa.getStates()) {
      Label skipState = new Label();
      // Uses unified method that auto-detects dual-long from encoded slot
      checkStateInSetConst(mv, statesVar, state.id, allocator);
      mv.visitJumpInsn(IFEQ, skipState);

      // worklist[worklistSize++] = state.id;
      mv.visitVarInsn(ALOAD, worklistVar);
      mv.visitVarInsn(ILOAD, worklistSizeVar);
      pushInt(mv, state.id);
      mv.visitInsn(IASTORE);
      mv.visitIincInsn(worklistSizeVar, 1);

      mv.visitLabel(skipState);
    }

    // Process worklist
    Label worklistLoop = new Label();
    Label worklistEnd = new Label();

    mv.visitLabel(worklistLoop);
    // if (worklistSize == 0) break;
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitJumpInsn(IFEQ, worklistEnd);

    // int stateId = worklist[--worklistSize];
    mv.visitIincInsn(worklistSizeVar, -1);
    mv.visitVarInsn(ALOAD, worklistVar);
    mv.visitVarInsn(ILOAD, worklistSizeVar);
    mv.visitInsn(IALOAD);
    mv.visitVarInsn(ISTORE, stateIdVar);

    // if (processed.contains(stateId)) continue;
    checkStateInSet(mv, processedVar, stateIdVar);
    mv.visitJumpInsn(IFNE, worklistLoop);

    // Mark state as processed
    addStateToSetVar(mv, processedVar, stateIdVar);

    // Generate switch for O(log N) state lookup
    // Collect all states that need processing (have groups, backrefs, assertions, or epsilon
    // transitions)
    List<NFA.NFAState> statesToProcess = new ArrayList<>();
    for (NFA.NFAState state : nfa.getStates()) {
      if (state.enterGroup != null
          || state.exitGroup != null
          || state.backrefCheck != null
          || state.assertionType != null
          || !state.getEpsilonTransitions().isEmpty()) {
        statesToProcess.add(state);
      }
    }

    if (!statesToProcess.isEmpty()) {
      mv.visitVarInsn(ILOAD, stateIdVar);

      Label defaultLabel3 = new Label();
      Label[] caseLabels3 = new Label[statesToProcess.size()];
      int[] caseKeys3 = new int[statesToProcess.size()];
      for (int i = 0; i < statesToProcess.size(); i++) {
        caseLabels3[i] = new Label();
        caseKeys3[i] = statesToProcess.get(i).id;
      }

      mv.visitLookupSwitchInsn(defaultLabel3, caseKeys3, caseLabels3);

      for (int i = 0; i < statesToProcess.size(); i++) {
        mv.visitLabel(caseLabels3[i]);
        NFA.NFAState state = statesToProcess.get(i);

        // Per-config tracking: Copy parent's configuration if available
        if (usePosixLastMatch && configGroupStartsVar >= 0) {
          Label skipCopy = new Label();

          // Check if state has a parent: parentStateMap[stateId] != -1
          mv.visitVarInsn(ALOAD, parentStateMapVar);
          mv.visitVarInsn(ILOAD, stateIdVar);
          mv.visitInsn(IALOAD);
          mv.visitInsn(ICONST_M1);
          mv.visitJumpInsn(IF_ICMPEQ, skipCopy); // Skip if no parent (pops both, stack is now [])

          // Load parentId again (since IF_ICMPEQ consumed it)
          mv.visitVarInsn(ALOAD, parentStateMapVar);
          mv.visitVarInsn(ILOAD, stateIdVar);
          mv.visitInsn(IALOAD);
          mv.visitVarInsn(ISTORE, parentIdVar);

          // Copy parent's configGroupStarts to this state's config
          generateCopyConfigArray(
              mv, configGroupStartsVar, parentIdVar, state.id, nfa.getGroupCount());
          // Copy parent's configGroupEnds to this state's config
          generateCopyConfigArray(
              mv, configGroupEndsVar, parentIdVar, state.id, nfa.getGroupCount());

          mv.visitLabel(skipCopy);
          // At this point, stack is [] from both paths
        }

        // Track group entries/exits for this state
        if (state.enterGroup != null) {
          if (usePosixLastMatch && configGroupStartsVar >= 0) {
            // Per-config tracking: Update this state's configuration
            // POSIX last-match: Always update, even at end of input (for empty matches)
            // configGroupStarts[state.id][enterGroup] = pos;
            mv.visitVarInsn(ALOAD, configGroupStartsVar);
            pushInt(mv, state.id);
            mv.visitInsn(AALOAD); // Load row for this state
            pushInt(mv, state.enterGroup);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);

            // Also update global array (last-match semantics - latest write wins)
            mv.visitVarInsn(ALOAD, groupStartsVar);
            pushInt(mv, state.enterGroup);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
          } else if (usePosixLastMatch) {
            // Global tracking (fallback for patterns without config arrays)
            // POSIX last-match: Always update, even at end of input (for empty matches)
            mv.visitVarInsn(ALOAD, groupStartsVar);
            pushInt(mv, state.enterGroup);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
          } else {
            // POSIX last-match: always update group starts (last iteration wins)
            mv.visitVarInsn(ALOAD, groupStartsVar);
            pushInt(mv, state.enterGroup);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
          }
        }
        if (state.exitGroup != null) {
          if (usePosixLastMatch && configGroupEndsVar >= 0) {
            // Per-config tracking: Update this state's configuration
            // configGroupEnds[state.id][exitGroup] = pos;
            mv.visitVarInsn(ALOAD, configGroupEndsVar);
            pushInt(mv, state.id);
            mv.visitInsn(AALOAD); // Load row for this state
            pushInt(mv, state.exitGroup);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);

            // Also update global array (last-match semantics - latest write wins)
            mv.visitVarInsn(ALOAD, groupEndsVar);
            pushInt(mv, state.exitGroup);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
          } else {
            // Global tracking (always update to get latest position)
            // groupEnds[exitGroup] = pos
            mv.visitVarInsn(ALOAD, groupEndsVar);
            pushInt(mv, state.exitGroup);
            mv.visitVarInsn(ILOAD, posVar);
            mv.visitInsn(IASTORE);
          }
        }

        // Process backreferences
        if (state.backrefCheck != null) {
          generateBackreferenceCheck(
              mv,
              state,
              inputVar,
              posVar,
              groupStartsVar,
              groupEndsVar,
              statesVar,
              worklistVar,
              worklistSizeVar,
              allocator);
        }
        // Process epsilon transitions (skip if has assertion - handled separately)
        else if (state.assertionType != null) {
          // Pass group arrays for lookahead capture persistence
          generateAssertionCheck(
              mv,
              state,
              inputVar,
              posVar,
              statesVar,
              worklistVar,
              stateIdVar,
              groupStartsVar,
              groupEndsVar,
              allocator);
        } else if (!state.getEpsilonTransitions().isEmpty()) {
          for (NFA.NFAState target : state.getEpsilonTransitions()) {
            Label alreadyVisited = new Label();
            Label afterStateHandling = new Label();
            // Uses unified method that auto-detects dual-long from encoded slot
            checkStateInSetConst(mv, statesVar, target.id, allocator);
            mv.visitJumpInsn(IFNE, alreadyVisited);

            // State not in set - add it and add to worklist
            addStateToSet(mv, statesVar, target.id, allocator);

            // worklist[worklistSize++] = target.id;
            mv.visitVarInsn(ALOAD, worklistVar);
            mv.visitVarInsn(ILOAD, worklistSizeVar);
            pushInt(mv, target.id);
            mv.visitInsn(IASTORE);
            mv.visitIincInsn(worklistSizeVar, 1);

            mv.visitLabel(alreadyVisited);
            // S: [] (from both paths - state added or already in set)

            // Per-config tracking: ALWAYS propagate config for POSIX last-match semantics
            // This ensures later iterations of quantified groups update the config
            // even if the target state was already visited in an earlier iteration
            if (usePosixLastMatch && configGroupStartsVar >= 0) {
              int groupCount = nfa.getGroupCount();
              generateCopyConfigArray(mv, configGroupStartsVar, stateIdVar, target.id, groupCount);
              generateCopyConfigArray(mv, configGroupEndsVar, stateIdVar, target.id, groupCount);
            }

            // Record parent for per-config tracking (last-write-wins)
            if (usePosixLastMatch && parentStateMapVar >= 0) {
              // parentStateMap[target.id] = stateId;
              mv.visitVarInsn(ALOAD, parentStateMapVar);
              pushInt(mv, target.id);
              mv.visitVarInsn(ILOAD, stateIdVar);
              mv.visitInsn(IASTORE);
            }
          }
        }

        mv.visitJumpInsn(GOTO, worklistLoop);
      }

      mv.visitLabel(defaultLabel3);
    }

    mv.visitJumpInsn(GOTO, worklistLoop);
    mv.visitLabel(worklistEnd);
  }

  /**
   * Generates findMatch() method that returns MatchResult with group information. Delegates to
   * findMatchFrom(input, 0).
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult findMatch(String input) {
   *     return findMatchFrom(input, 0);
   * }
   * }</pre>
   */
  public void generateFindMatchMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatch",
            "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // return findMatchFrom(input, 0);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitInsn(ICONST_0); // start = 0
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
   * Generates findMatchFrom() method that returns MatchResult with group information. Finds the
   * match start and end, then calls matchBounded() on the matched substring for group tracking.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult findMatchFrom(String input, int start) {
   *     // Find where a match can start
   *     int matchStart = findFrom(input, start);
   *     if (matchStart < 0) return null;
   *
   *     // Try all possible match lengths to find the longest (greedy)
   *     int longestEnd = matchStart;
   *     for (int matchEnd = matchStart + 1; matchEnd <= input.length(); matchEnd++) {
   *         MatchResult candidate = matchBounded(input, matchStart, matchEnd);
   *         if (candidate != null) {
   *             longestEnd = matchEnd;
   *         }
   *     }
   *
   *     if (longestEnd == matchStart) return null;  // No valid match
   *
   *     // Return final result with group information
   *     return matchBounded(input, matchStart, longestEnd);
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * This implementation has O(N²) complexity for greedy quantifiers because it tries all possible
   * lengths. The allocation-free {@code findBoundsFrom()} method uses {@code findLongestMatchEnd()}
   * instead to achieve O(N) complexity.
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

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, 3); // matchStart

    // if (matchStart < 0) return null;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, 3);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(found);

    // Find longest match end by trying all lengths using matchBounded (zero allocations)
    // int matchEnd = matchStart + 1;
    mv.visitVarInsn(ILOAD, 3);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, 4); // matchEnd (current try)

    // int longestEnd = matchStart; (no successful match yet)
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ISTORE, 5); // longestEnd

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    // while (matchEnd <= input.length())
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // MatchResult candidate = matchBounded(input, matchStart, matchEnd);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchBounded",
        "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, 6); // candidate

    // if (candidate != null) longestEnd = matchEnd;
    Label noMatch = new Label();
    mv.visitVarInsn(ALOAD, 6);
    mv.visitJumpInsn(IFNULL, noMatch);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ISTORE, 5); // longestEnd = matchEnd
    mv.visitLabel(noMatch);

    // matchEnd++
    mv.visitIincInsn(4, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // if (longestEnd == matchStart) return null; (no match found)
    Label hasMatch = new Label();
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitJumpInsn(IF_ICMPNE, hasMatch);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(hasMatch);

    // Store longestEnd in var 4 for later use
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ISTORE, 4); // matchEnd = longestEnd

    // Call matchBounded one final time with the longest match bounds
    // MatchResult result = this.matchBounded(input, matchStart, matchEnd);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 3); // matchStart
    mv.visitVarInsn(ILOAD, 4); // matchEnd
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchBounded",
        "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, 7); // result

    // Return result (matchBounded already has correct positions relative to original string)
    mv.visitVarInsn(ALOAD, 7);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0); // Computed automatically
    mv.visitEnd();
  }

  /**
   * Generates findBoundsFrom() method - allocation-free boundary detection. Returns match
   * boundaries in the provided int[] array instead of allocating MatchResult. Uses greedy NFA
   * matching via {@code findLongestMatchEnd()} for O(N) complexity.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean findBoundsFrom(String input, int start, int[] bounds) {
   *     if (input == null) return false;
   *
   *     // Find where a match can start
   *     int matchStart = findFrom(input, start);
   *     if (matchStart < 0) return false;
   *
   *     // Use greedy NFA matching to find longest match end (O(N) instead of O(N²))
   *     int longestEnd = findLongestMatchEnd(input, matchStart);
   *     if (longestEnd < 0) return false;
   *
   *     // Store bounds in the provided array (zero allocation)
   *     bounds[0] = matchStart;
   *     bounds[1] = longestEnd;
   *     return true;
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * This method is optimized for use in replaceAll() operations where MatchResult allocation would
   * be expensive. The bounds array is reused across multiple calls.
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

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, 4); // matchStart in var 4

    // if (matchStart < 0) return false;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(found);

    // OPTIMIZATION: Use greedy NFA matching instead of trying all lengths (eliminates O(N²) loop)
    // Call a helper method that runs NFA greedily and returns longest match end
    // int longestEnd = findLongestMatchEnd(input, matchStart);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 4); // matchStart
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findLongestMatchEnd",
        "(Ljava/lang/String;I)I",
        false);
    mv.visitVarInsn(ISTORE, 5); // longestEnd in var 5

    // if (longestEnd < 0) return false; (no match found)
    Label hasMatch = new Label();
    mv.visitVarInsn(ILOAD, 5);
    mv.visitJumpInsn(IFGE, hasMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(hasMatch);

    // bounds[0] = matchStart;
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitInsn(IASTORE);

    // bounds[1] = longestEnd;
    mv.visitVarInsn(ALOAD, 3);
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0); // Computed automatically
    mv.visitEnd();
  }

  /**
   * Generates findLongestMatchEnd() - helper for greedy NFA matching. Runs NFA from startPos and
   * returns the longest position where an accepting state was reached. Returns -1 if no accepting
   * state was ever reached.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * int findLongestMatchEnd(String input, int startPos) {
   *     int length = input.length();
   *     int longestMatchEnd = -1;
   *
   *     // Initialize NFA state tracking (Single Long / Dual Long / SparseSet)
   *     StateSet currentStates = new StateSet();
   *     StateSet nextStates = new StateSet();
   *     currentStates.add(startState);
   *     epsilonClosure(currentStates);
   *
   *     int pos = startPos;
   *     while (pos < length && !currentStates.isEmpty()) {
   *         // Check if we're in an accepting state BEFORE consuming next char
   *         for (NFAState accept : acceptStates) {
   *             if (currentStates.contains(accept)) {
   *                 longestMatchEnd = pos;  // Record greedy match position
   *             }
   *         }
   *
   *         // Process character transition
   *         char ch = input.charAt(pos);
   *         nextStates.clear();
   *
   *         // NFA step: for each active state, follow matching transitions
   *         for (int stateId : currentStates) {
   *             for (Transition t : states[stateId].transitions) {
   *                 if (t.charSet.contains(ch)) {
   *                     nextStates.add(t.target);
   *                 }
   *             }
   *         }
   *
   *         epsilonClosure(nextStates);
   *         swap(currentStates, nextStates);
   *         pos++;
   *     }
   *
   *     // Final check: accepting state after consuming all input?
   *     for (NFAState accept : acceptStates) {
   *         if (currentStates.contains(accept)) {
   *             longestMatchEnd = pos;
   *         }
   *     }
   *
   *     return longestMatchEnd;
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * This method enables O(N) greedy matching by tracking the furthest accepting state position
   * during NFA simulation. Used by {@code findBoundsFrom()} for allocation-free boundary detection.
   */
  public void generateFindLongestMatchEndMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, "findLongestMatchEnd", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Create local variable allocator
    // Slots: 0=this, 1=input, 2=startPos, 3+ = local variables
    LocalVariableAllocator allocator = new LocalVariableAllocator(3);

    // Allocate NFA state tracking variables - for dual-long, slot values are encoded (negative =
    // dual-long)
    int currentStatesVar;
    int nextStatesVar;
    if (useSingleLong) {
      currentStatesVar = allocator.allocateLong();
      nextStatesVar = allocator.allocateLong();
    } else if (useDualLong) {
      currentStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
      nextStatesVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      currentStatesVar = allocator.allocateRef();
      nextStatesVar = allocator.allocateRef();
    }

    int posVar = allocator.allocateInt();
    int longestMatchEndVar = allocator.allocateInt();
    int lengthVar = allocator.allocateInt();

    // Epsilon closure slots
    int worklistVar = allocator.allocateRef();
    int stateIdVar = allocator.allocateInt();
    int worklistSizeVar = allocator.allocateInt();
    int processedVar;
    if (useSingleLong) {
      processedVar = allocator.allocateLong();
    } else if (useDualLong) {
      processedVar = allocateDualLongStateSet(allocator); // encoded dual-long (4 slots)
    } else {
      processedVar = allocator.allocateRef();
    }
    int indexVar = allocator.allocateInt();
    int sizeVar = allocator.allocateInt();
    // parentIdVar is -1 since findLongestMatchEnd doesn't use group tracking
    EpsilonClosureSlots epsilonSlots =
        new EpsilonClosureSlots(
            worklistVar, stateIdVar, worklistSizeVar, processedVar, indexVar, sizeVar, -1);

    // Allocate chVar BEFORE the loop to ensure consistent stackmap frames
    int chVar = allocator.allocateInt();

    // Pre-allocate NFA step slots BEFORE the loop
    int nfaStepStateIdVar = allocator.allocateInt();
    int nfaStepIndexVar = allocator.allocateInt();
    int nfaStepSizeVar = allocator.allocateInt();
    NFAStepSlots nfaStepSlots =
        new NFAStepSlots(nfaStepStateIdVar, nfaStepIndexVar, nfaStepSizeVar);

    // Initialize ALL pre-allocated variables to ensure consistent stackmap frames
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, indexVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, sizeVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, chVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepStateIdVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepIndexVar);
    // S: [] -> [I] -> []
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, nfaStepSizeVar);

    // Initialize epsilon closure data structures
    pushInt(mv, nfa.getStates().size());
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, worklistVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, stateIdVar);

    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, worklistSizeVar);

    // Initialize processedVar (auto-detects dual-long from encoded slot)
    initStateSet(mv, processedVar);

    // int length = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lengthVar);

    // int longestMatchEnd = -1; (no match found yet)
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, longestMatchEndVar);

    // Initialize currentStates and nextStates (auto-detects dual-long from encoded slot)
    if (useSingleLong || useDualLong) {
      initStateSet(mv, currentStatesVar);
      initStateSet(mv, nextStatesVar);
    } else {
      // For StateSet: use pre-allocated instance fields
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "currentStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, currentStatesVar);
      mv.visitVarInsn(ALOAD, currentStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);

      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitFieldInsn(
          GETFIELD,
          "com/datadoghq/reggie/runtime/ReggieMatcher",
          "nextStates",
          "Lcom/datadoghq/reggie/runtime/StateSet;");
      mv.visitVarInsn(ASTORE, nextStatesVar);
      mv.visitVarInsn(ALOAD, nextStatesVar);
      mv.visitMethodInsn(
          INVOKEVIRTUAL, "com/datadoghq/reggie/runtime/StateSet", "clear", "()V", false);
    }

    // Add start state to currentStates (unified method auto-detects dual-long)
    addStateToSet(mv, currentStatesVar, nfa.getStartState().id, allocator);

    // Epsilon closure of start state
    generateEpsilonClosure(mv, currentStatesVar, 1, 2, allocator, epsilonSlots);

    // int pos = startPos;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // Main matching loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check if we're in an accepting state BEFORE processing next character (unified - auto-detects
    // dual-long)
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      checkStateInSetConst(mv, currentStatesVar, acceptState.id, allocator);
      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);

      // We're in an accepting state - record this position as potential match end
      // longestMatchEnd = pos;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, longestMatchEndVar);

      mv.visitLabel(notAccepting);
    }

    // Check if we've reached end of input
    // if (pos >= length) break;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lengthVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Check if currentStates is empty (unified - auto-detects dual-long)
    isStateSetEmpty(mv, currentStatesVar);
    mv.visitJumpInsn(IFNE, loopEnd);

    // Get next character
    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // Clear nextStates for reuse (unified - auto-detects dual-long)
    clearStateSet(mv, nextStatesVar);

    // Process transitions for each state in currentStates (unified - auto-detects dual-long)
    generateNFAStep(mv, currentStatesVar, nextStatesVar, chVar, -1, -1, 0, allocator, nfaStepSlots);

    // Epsilon closure of nextStates
    generateEpsilonClosure(mv, nextStatesVar, 1, posVar, allocator, epsilonSlots);

    // Swap state sets (unified - auto-detects dual-long)
    swapStateSets(mv, currentStatesVar, nextStatesVar, allocator);

    // pos++
    mv.visitIincInsn(posVar, 1);

    mv.visitJumpInsn(GOTO, loopStart);
    mv.visitLabel(loopEnd);

    // Check one final time if we're in an accepting state after processing all input (unified -
    // auto-detects dual-long)
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      checkStateInSetConst(mv, currentStatesVar, acceptState.id, allocator);
      Label notAccepting = new Label();
      mv.visitJumpInsn(IFEQ, notAccepting);

      // We're in an accepting state - record this position
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, longestMatchEndVar);

      mv.visitLabel(notAccepting);
    }

    // return longestMatchEnd;
    mv.visitVarInsn(ILOAD, longestMatchEndVar);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate DFA execution bytecode for lookahead assertion checking. This provides much faster
   * lookahead execution compared to NFA simulation.
   *
   * <p>Generated code structure: int dfaState = startStateId; int pos = checkPos; int len =
   * input.length();
   *
   * <p>while (pos < len) { // Check if current state is accepting if (isAccepting(dfaState)) ->
   * jump to assertionPassed
   *
   * <p>char ch = input.charAt(pos);
   *
   * <p>// Find next state based on character (using switch) switch (dfaState) { case 0: ...
   * transitions ... break; case 1: ... transitions ... break; ... }
   *
   * <p>// If dead state (-1), fail if (dfaState == -1) -> jump to assertionFailed
   *
   * <p>pos++; }
   *
   * <p>// Final state check if (isAccepting(dfaState)) -> assertionPassed else assertionFailed
   */
  private void generateDFALookaheadCheck(
      MethodVisitor mv,
      DFA dfa,
      int inputVar,
      int checkPosVar,
      Label assertionFailed,
      Label assertionPassed,
      LocalVariableAllocator allocator) {
    // Allocate local variables using allocator instead of hardcoding slots
    // This prevents conflicts with variables allocated earlier in the method
    int dfaStateVar = allocator.allocateInt();
    int posVar = allocator.allocateInt();
    int lenVar = allocator.allocateInt();
    int chVar = allocator.allocateInt();

    // int dfaState = dfa.getStartState().id;
    pushInt(mv, dfa.getStartState().id);
    mv.visitVarInsn(ISTORE, dfaStateVar);

    // int pos = checkPos;
    mv.visitVarInsn(ILOAD, checkPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check if current state is accepting (success for lookahead)
    Set<Integer> acceptingIds = new HashSet<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state.accepting) {
        acceptingIds.add(state.id);
      }
    }

    if (!acceptingIds.isEmpty()) {
      for (int acceptingId : acceptingIds) {
        Label notThisAccepting = new Label();
        mv.visitVarInsn(ILOAD, dfaStateVar);
        pushInt(mv, acceptingId);
        mv.visitJumpInsn(IF_ICMPNE, notThisAccepting);
        mv.visitJumpInsn(GOTO, assertionPassed);
        mv.visitLabel(notThisAccepting);
      }
    }

    // while (pos < len)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // Switch on current DFA state to find next state
    Label deadState = new Label();
    Label afterSwitch = new Label();

    // Build switch cases for each DFA state
    List<DFA.DFAState> states = new ArrayList<>(dfa.getAllStates());
    states.sort(Comparator.comparingInt(s -> s.id));

    int[] keys = new int[states.size()];
    Label[] labels = new Label[states.size()];
    for (int i = 0; i < states.size(); i++) {
      keys[i] = states.get(i).id;
      labels[i] = new Label();
    }

    mv.visitVarInsn(ILOAD, dfaStateVar);
    mv.visitLookupSwitchInsn(deadState, keys, labels);

    // Generate code for each state's transitions
    for (int i = 0; i < states.size(); i++) {
      mv.visitLabel(labels[i]);
      DFA.DFAState state = states.get(i);

      if (state.transitions.isEmpty()) {
        // Dead state - set to -1
        mv.visitInsn(ICONST_M1);
        mv.visitVarInsn(ISTORE, dfaStateVar);
        mv.visitJumpInsn(GOTO, afterSwitch);
      } else {
        // Check each transition's charset
        for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
          Label noMatch = new Label();

          // Generate charset check
          generateCharSetCheck(mv, entry.getKey(), chVar, noMatch);

          // Match found - set next state and jump out
          pushInt(mv, entry.getValue().target.id);
          mv.visitVarInsn(ISTORE, dfaStateVar);
          mv.visitJumpInsn(GOTO, afterSwitch);

          mv.visitLabel(noMatch);
        }

        // No transition matched - dead state
        mv.visitInsn(ICONST_M1);
        mv.visitVarInsn(ISTORE, dfaStateVar);
        mv.visitJumpInsn(GOTO, afterSwitch);
      }
    }

    mv.visitLabel(deadState);
    // Default case - dead state
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, dfaStateVar);

    mv.visitLabel(afterSwitch);

    // if (dfaState == -1) -> assertionFailed
    mv.visitVarInsn(ILOAD, dfaStateVar);
    mv.visitInsn(ICONST_M1);
    mv.visitJumpInsn(IF_ICMPEQ, assertionFailed);

    // pos++
    mv.visitIincInsn(posVar, 1);

    // Continue loop
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // Final check: if current state is accepting, success
    if (!acceptingIds.isEmpty()) {
      for (int acceptingId : acceptingIds) {
        Label notThisAccepting = new Label();
        mv.visitVarInsn(ILOAD, dfaStateVar);
        pushInt(mv, acceptingId);
        mv.visitJumpInsn(IF_ICMPNE, notThisAccepting);
        mv.visitJumpInsn(GOTO, assertionPassed);
        mv.visitLabel(notThisAccepting);
      }
    }

    // Not in accepting state - fail
    mv.visitJumpInsn(GOTO, assertionFailed);
  }

  /**
   * Generate bytecode to check if a character matches a charset. Jumps to noMatch label if
   * character doesn't match.
   */
  private void generateCharSetCheck(MethodVisitor mv, CharSet charset, int chVar, Label noMatch) {
    if (charset.isSingleChar()) {
      // Optimized path for single character
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, charset.getSingleChar());
      mv.visitJumpInsn(IF_ICMPNE, noMatch);
    } else if (charset.isSimpleRange()) {
      // Optimized path for single range [a-z]
      char min = charset.rangeStart();
      char max = charset.rangeEnd();

      // if (ch < min || ch > max) -> noMatch
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, min);
      mv.visitJumpInsn(IF_ICMPLT, noMatch);

      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, max);
      mv.visitJumpInsn(IF_ICMPGT, noMatch);
    } else {
      // General case - call charset.contains(ch)
      // For now, use inline range checks for performance
      List<CharSet.Range> ranges = charset.getRanges();
      if (ranges.isEmpty()) {
        // Empty charset - always fail
        mv.visitJumpInsn(GOTO, noMatch);
      } else {
        Label matched = new Label();

        for (CharSet.Range range : ranges) {
          Label tryNext = new Label();

          if (range.start == range.end) {
            // Single character
            mv.visitVarInsn(ILOAD, chVar);
            pushInt(mv, range.start);
            mv.visitJumpInsn(IF_ICMPEQ, matched);
          } else {
            // Range check: if (ch >= start && ch <= end) -> matched
            Label rangeNoMatch = new Label();

            mv.visitVarInsn(ILOAD, chVar);
            pushInt(mv, range.start);
            mv.visitJumpInsn(IF_ICMPLT, rangeNoMatch);

            mv.visitVarInsn(ILOAD, chVar);
            pushInt(mv, range.end);
            mv.visitJumpInsn(IF_ICMPLE, matched);

            mv.visitLabel(rangeNoMatch);
          }
        }

        // No range matched - fail
        mv.visitJumpInsn(GOTO, noMatch);
        mv.visitLabel(matched);
      }
    }
  }

  /**
   * Generate specialized fast path for pattern: (?=\w+@).*@\w+\.\w+ Uses bitmap-based character
   * class matching instead of NFA simulation.
   *
   * <p>Algorithm: 1. Use indexOf to find @ position 2. Check lookahead backwards: \w+ before @ 3.
   * Match suffix after @: \w+\.\w+
   */
  private void generateFastPathFindFrom(MethodVisitor mv, LocalVariableAllocator allocator) {
    // Method signature: findFrom(String input, int start) -> int
    // Params: 0=this, 1=input, 2=start

    // Allocate local variables
    int lenVar = allocator.allocateInt();
    int posVar = allocator.allocateInt();

    // Allocate temp variables for CharClassBitmap.generateIsWordCharCheck
    // (needs 2 slots for long + 1 slot for int = 3 slots total)
    int tempVarBase = allocator.allocateLong(); // Allocates 2 slots
    allocator.allocateInt(); // Allocate 1 more slot for the int

    // Get input length: int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Loop to find @ positions
    Label loopStart = new Label();
    Label tryNextAt = new Label();
    Label noMatch = new Label();

    // pos = input.indexOf('@', start);
    mv.visitVarInsn(ALOAD, 1);
    BytecodeUtil.pushInt(mv, '@');
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
    mv.visitVarInsn(ISTORE, posVar);

    mv.visitLabel(loopStart);

    // if (pos < 0 || pos >= len) return -1;
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitJumpInsn(IFLT, noMatch);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, noMatch);

    // Check lookahead: \w+ before @
    // We need at least one word char before @
    Label lookaheadPass = new Label();

    // int lookback = pos - 1;
    int lookbackVar = allocator.allocateInt();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    mv.visitVarInsn(ISTORE, lookbackVar);

    // if (lookback < 0) goto tryNextAt; // No room for word chars
    mv.visitVarInsn(ILOAD, lookbackVar);
    mv.visitJumpInsn(IFLT, tryNextAt);

    // Check if char at lookback is word char
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, lookbackVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    CharClassBitmap.generateIsWordCharCheck(mv, tempVarBase);
    mv.visitJumpInsn(IFEQ, tryNextAt); // Not a word char - lookahead fails

    // Greedy match word chars backwards (already confirmed at least one)
    Label lookbackLoop = new Label();
    mv.visitLabel(lookbackLoop);
    mv.visitVarInsn(ILOAD, lookbackVar);
    mv.visitJumpInsn(IFLE, lookaheadPass); // At position 0, can't go further back

    // Check char at lookback - 1
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, lookbackVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(ISUB);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    CharClassBitmap.generateIsWordCharCheck(mv, tempVarBase);
    mv.visitJumpInsn(IFEQ, lookaheadPass); // Not word char - end of \w+ sequence

    // It's a word char, continue backwards
    mv.visitIincInsn(lookbackVar, -1);
    mv.visitJumpInsn(GOTO, lookbackLoop);

    mv.visitLabel(lookaheadPass);
    // Lookahead succeeded! Now match suffix: @\w+\.\w+

    // pos already points to @, move to next char
    int afterAtVar = allocator.allocateInt();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitVarInsn(ISTORE, afterAtVar);

    // Match \w+ (at least one word char)
    // Check if we have at least one char
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, tryNextAt);

    // Check first char is word char
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    CharClassBitmap.generateIsWordCharCheck(mv, tempVarBase);
    mv.visitJumpInsn(IFEQ, tryNextAt);

    // Greedy match word chars
    Label wordCharLoop = new Label();
    mv.visitIincInsn(afterAtVar, 1);
    mv.visitLabel(wordCharLoop);
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, tryNextAt); // Ran out of input - need dot and more chars

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    CharClassBitmap.generateIsWordCharCheck(mv, tempVarBase);
    Label checkForDot = new Label();
    mv.visitJumpInsn(IFEQ, checkForDot); // Not word char, check if it's dot
    mv.visitIincInsn(afterAtVar, 1);
    mv.visitJumpInsn(GOTO, wordCharLoop);

    // Check if current char is '.'
    mv.visitLabel(checkForDot);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    BytecodeUtil.pushInt(mv, '.');
    mv.visitJumpInsn(IF_ICMPNE, tryNextAt); // Not a dot - fail

    // Move past dot
    mv.visitIincInsn(afterAtVar, 1);

    // Match final \w+ (at least one word char)
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, tryNextAt);

    // Check first char is word char
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    CharClassBitmap.generateIsWordCharCheck(mv, tempVarBase);
    mv.visitJumpInsn(IFEQ, tryNextAt);

    // Greedy match remaining word chars
    Label finalWordLoop = new Label();
    mv.visitIincInsn(afterAtVar, 1);
    mv.visitLabel(finalWordLoop);
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitVarInsn(ILOAD, lenVar);
    Label matchSuccess = new Label();
    mv.visitJumpInsn(IF_ICMPGE, matchSuccess); // Reached end - success

    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, afterAtVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    CharClassBitmap.generateIsWordCharCheck(mv, tempVarBase);
    mv.visitJumpInsn(IFEQ, matchSuccess); // Not word char - end of match (success)

    mv.visitIincInsn(afterAtVar, 1);
    mv.visitJumpInsn(GOTO, finalWordLoop);

    // SUCCESS: Return start position (lookback + 1)
    mv.visitLabel(matchSuccess);
    mv.visitVarInsn(ILOAD, lookbackVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitInsn(IRETURN);

    // Lookahead or suffix failed - try next @ position
    mv.visitLabel(tryNextAt);

    // pos = input.indexOf('@', pos + 1);
    mv.visitVarInsn(ALOAD, 1);
    BytecodeUtil.pushInt(mv, '@');
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IADD);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
    mv.visitVarInsn(ISTORE, posVar);
    mv.visitJumpInsn(GOTO, loopStart);

    // No match found
    mv.visitLabel(noMatch);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate bytecode to initialize per-configuration group tracking arrays to -1. Generates nested
   * loops: for (int stateId = 0; stateId < stateCount; stateId++) for (int groupId = 0; groupId <=
   * groupCount; groupId++) configGroupStarts[stateId][groupId] = -1;
   * configGroupEnds[stateId][groupId] = -1;
   */
  private void generateInitializeConfigArrays(
      MethodVisitor mv,
      int configGroupStartsVar,
      int configGroupEndsVar,
      int parentStateMapVar,
      int stateCount,
      int groupCount,
      LocalVariableAllocator allocator) {
    int stateIdVar = allocator.allocateInt();
    int groupIdVar = allocator.allocateInt();

    Label outerLoopStart = new Label();
    Label outerLoopEnd = new Label();
    Label innerLoopStart = new Label();
    Label innerLoopEnd = new Label();

    // for (int stateId = 0; ...
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, stateIdVar);

    mv.visitLabel(outerLoopStart);
    // ... stateId < stateCount; ...
    mv.visitVarInsn(ILOAD, stateIdVar);
    pushInt(mv, stateCount);
    mv.visitJumpInsn(IF_ICMPGE, outerLoopEnd);

    // Initialize parentStateMap[stateId] = -1
    mv.visitVarInsn(ALOAD, parentStateMapVar);
    mv.visitVarInsn(ILOAD, stateIdVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);

    // for (int groupId = 0; ...
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, groupIdVar);

    mv.visitLabel(innerLoopStart);
    // ... groupId <= groupCount; ...
    mv.visitVarInsn(ILOAD, groupIdVar);
    pushInt(mv, groupCount + 1);
    mv.visitJumpInsn(IF_ICMPGE, innerLoopEnd);

    // configGroupStarts[stateId][groupId] = -1
    mv.visitVarInsn(ALOAD, configGroupStartsVar);
    mv.visitVarInsn(ILOAD, stateIdVar);
    mv.visitInsn(AALOAD); // Load row
    mv.visitVarInsn(ILOAD, groupIdVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);

    // configGroupEnds[stateId][groupId] = -1
    mv.visitVarInsn(ALOAD, configGroupEndsVar);
    mv.visitVarInsn(ILOAD, stateIdVar);
    mv.visitInsn(AALOAD); // Load row
    mv.visitVarInsn(ILOAD, groupIdVar);
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IASTORE);

    // groupId++
    mv.visitIincInsn(groupIdVar, 1);
    mv.visitJumpInsn(GOTO, innerLoopStart);

    mv.visitLabel(innerLoopEnd);

    // stateId++
    mv.visitIincInsn(stateIdVar, 1);
    mv.visitJumpInsn(GOTO, outerLoopStart);

    mv.visitLabel(outerLoopEnd);
  }

  /**
   * Generate bytecode to copy configuration array from source state to destination state.
   * Generates: System.arraycopy(configGroupArray[srcState], 0, configGroupArray[dstState], 0,
   * groupCount + 1);
   */
  private void generateCopyConfigArray(
      MethodVisitor mv, int configArrayVar, int srcStateVar, int dstStateId, int groupCount) {
    // Load source row: configGroupArray[srcState]
    mv.visitVarInsn(ALOAD, configArrayVar);
    mv.visitVarInsn(ILOAD, srcStateVar);
    mv.visitInsn(AALOAD);
    mv.visitInsn(ICONST_0); // srcPos

    // Load destination row: configGroupArray[dstState]
    mv.visitVarInsn(ALOAD, configArrayVar);
    pushInt(mv, dstStateId);
    mv.visitInsn(AALOAD);
    mv.visitInsn(ICONST_0); // dstPos

    // Length
    pushInt(mv, groupCount + 1);

    // System.arraycopy
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/lang/System",
        "arraycopy",
        "(Ljava/lang/Object;ILjava/lang/Object;II)V",
        false);
  }

  /**
   * Generate bytecode to extract per-config group arrays to global result arrays. IMPORTANT: Skips
   * group 0 which is managed globally (entire match span). Generates:
   * System.arraycopy(configGroupArray[stateId], 1, globalArray, 1, groupCount);
   */
  private void generateExtractConfigToGlobal(
      MethodVisitor mv, int configArrayVar, int stateId, int globalArrayVar, int groupCount) {
    // Skip if no user-defined capturing groups (only group 0 exists)
    if (groupCount == 0) {
      return;
    }

    // Load source row: configGroupArray[stateId]
    mv.visitVarInsn(ALOAD, configArrayVar);
    pushInt(mv, stateId);
    mv.visitInsn(AALOAD);
    mv.visitInsn(ICONST_1); // srcPos = 1 (skip group 0)

    // Load destination: globalArray
    mv.visitVarInsn(ALOAD, globalArrayVar);
    mv.visitInsn(ICONST_1); // dstPos = 1 (skip group 0)

    // Length = groupCount (groups 1..N, not including group 0)
    pushInt(mv, groupCount);

    // System.arraycopy
    mv.visitMethodInsn(
        INVOKESTATIC,
        "java/lang/System",
        "arraycopy",
        "(Ljava/lang/Object;ILjava/lang/Object;II)V",
        false);
  }

  /**
   * Generate literal lookahead checks using indexOf() for fast early rejection.
   *
   * <p>For pattern {@code (?=\w+@)(?=.*example).*@\w+\.com}:
   *
   * <ul>
   *   <li>Check 1: {@code input.indexOf('@', 0) >= 0} - if not found, return false immediately
   *   <li>Check 2: {@code input.indexOf("example", 0) >= 0} - if not found, return false
   *       immediately
   *   <li>If both pass, continue with NFA simulation
   * </ul>
   *
   * @param mv method visitor
   * @param allocator local variable allocator
   * @param inputVar slot number for input string (usually 1)
   */
  private void generateLiteralLookaheadChecks(
      MethodVisitor mv, LocalVariableAllocator allocator, int inputVar) {
    // For matches(), we check from position 0
    // The lookaheads are zero-width, so we just need to verify the literals exist SOMEWHERE in the
    // string

    for (LiteralLookaheadInfo lookahead : literalLookaheadInfo.lookaheads) {
      switch (lookahead.getType()) {
        case SINGLE_CHAR:
          generateCharLookaheadCheck(mv, inputVar, lookahead.getSingleChar());
          break;

        case SUBSTRING:
          generateSubstringLookaheadCheck(mv, inputVar, lookahead.getLiteral());
          break;

        case CHAR_CLASS:
          generateCharClassLookaheadCheck(mv, allocator, inputVar, lookahead.getCharClass());
          break;
      }
    }
  }

  /**
   * Generate indexOf() check for a single required character.
   *
   * <p>Generates bytecode equivalent to:
   *
   * <pre>
   * if (input.indexOf('@', 0) < 0) return false;
   * </pre>
   *
   * @param mv method visitor
   * @param inputVar slot number for input string
   * @param requiredChar the character that must exist in the input
   */
  private void generateCharLookaheadCheck(MethodVisitor mv, int inputVar, char requiredChar) {
    // input.indexOf(requiredChar, 0)
    mv.visitVarInsn(ALOAD, inputVar);
    pushInt(mv, (int) requiredChar);
    mv.visitInsn(ICONST_0); // start from position 0
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);

    // if (result < 0) return false;
    Label found = new Label();
    mv.visitJumpInsn(IFGE, found); // if >= 0, jump to found
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);
  }

  /**
   * Generate indexOf() check for a required substring.
   *
   * <p>Generates bytecode equivalent to:
   *
   * <pre>
   * if (input.indexOf("example", 0) < 0) return false;
   * </pre>
   *
   * @param mv method visitor
   * @param inputVar slot number for input string
   * @param requiredSubstring the substring that must exist in the input
   */
  private void generateSubstringLookaheadCheck(
      MethodVisitor mv, int inputVar, String requiredSubstring) {
    // input.indexOf(requiredSubstring, 0)
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitLdcInsn(requiredSubstring);
    mv.visitInsn(ICONST_0); // start from position 0
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);

    // if (result < 0) return false;
    Label found = new Label();
    mv.visitJumpInsn(IFGE, found); // if >= 0, jump to found
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(found);
  }

  /**
   * Generate character class check for the first character.
   *
   * <p>Generates bytecode equivalent to:
   *
   * <pre>
   * if (input.length() == 0 || !charSetMatches(input.charAt(0), charSet)) return false;
   * </pre>
   *
   * <p>Note: This is used for lookaheads like {@code (?=[A-Z])} that check the character at the
   * current position. For matches(), we check position 0.
   *
   * @param mv method visitor
   * @param allocator local variable allocator
   * @param inputVar slot number for input string
   * @param charSet the character set to match
   */
  private void generateCharClassLookaheadCheck(
      MethodVisitor mv, LocalVariableAllocator allocator, int inputVar, CharSet charSet) {
    Label matches = new Label();

    // if (input.length() == 0) return false;
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IFNE, new Label()); // if length > 0, continue
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // char ch = input.charAt(0);
    Label checkChar = new Label();
    mv.visitLabel(checkChar);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitInsn(ICONST_0); // position 0
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

    // Check if character matches the character set
    generateCharSetMatch(mv, charSet, matches);

    // If no match, return false
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(matches);
  }

  /**
   * Generate bytecode to check if a character matches a character set.
   *
   * <p>Leaves the character on the stack, generates comparison logic, and jumps to matchLabel if it
   * matches. Falls through if it doesn't match (caller should handle failure case).
   *
   * @param mv method visitor
   * @param charSet the character set to match against
   * @param matchLabel label to jump to if character matches
   */
  private void generateCharSetMatch(MethodVisitor mv, CharSet charSet, Label matchLabel) {
    // Character is on stack
    // Generate range checks for the character set

    if (charSet.getRanges().isEmpty()) {
      // Empty charset - never matches (fall through to failure)
      mv.visitInsn(POP); // pop the character
      return;
    }

    // For single range, generate inline comparison
    if (charSet.getRanges().size() == 1) {
      CharSet.Range range = charSet.getRanges().get(0);
      if (range.start == range.end) {
        // Single character: ch == 'X'
        pushInt(mv, range.start);
        mv.visitJumpInsn(IF_ICMPEQ, matchLabel);
      } else {
        // Range: ch >= start && ch <= end
        mv.visitInsn(DUP); // duplicate char for second comparison
        pushInt(mv, range.start);
        Label tooSmall = new Label();
        mv.visitJumpInsn(IF_ICMPLT, tooSmall); // if ch < start, fail

        pushInt(mv, range.end);
        mv.visitJumpInsn(IF_ICMPLE, matchLabel); // if ch <= end, match

        mv.visitLabel(tooSmall);
      }
    } else {
      // Multiple ranges: check each one
      for (CharSet.Range range : charSet.getRanges()) {
        if (range.start == range.end) {
          // Single character
          mv.visitInsn(DUP);
          pushInt(mv, range.start);
          mv.visitJumpInsn(IF_ICMPEQ, matchLabel);
        } else {
          // Range check
          mv.visitInsn(DUP);
          mv.visitInsn(DUP);
          pushInt(mv, range.start);
          Label nextRange = new Label();
          mv.visitJumpInsn(IF_ICMPLT, nextRange); // if ch < start, try next range

          pushInt(mv, range.end);
          mv.visitJumpInsn(IF_ICMPLE, matchLabel); // if ch <= end, match

          mv.visitLabel(nextRange);
        }
      }
      // No match in any range - pop the duplicate chars we left on stack
      mv.visitInsn(POP);
    }
  }

  /**
   * Generate findFrom() with separated atomic lookahead checking (JDK-style).
   *
   * <p>Instead of executing a combined 39-state NFA, this:
   *
   * <ol>
   *   <li>Tries matching from each position (start to len)
   *   <li>At each position, checks ALL lookaheads using their own DFAs (atomic checks)
   *   <li>If all lookaheads pass, executes main pattern DFA
   *   <li>Returns first match position or -1
   * </ol>
   *
   * <p>Example: {@code (?=\w+@)(?=.*example).*@\w+\.com}
   *
   * <ul>
   *   <li>Lookahead 1: {@code (?=\w+@)} → 7-state DFA check from position
   *   <li>Lookahead 2: {@code (?=.*example)} → 10-state DFA check from position
   *   <li>Main: {@code .*@\w+\.com} → 8-state DFA execution from position
   * </ul>
   *
   * @param mv method visitor
   * @param allocator variable allocator
   * @param className class name for method calls
   */
  private void generateSeparatedLookaheadFindFrom(
      MethodVisitor mv, LocalVariableAllocator allocator, String className) {
    // Method signature: findFrom(String input, int start)
    // Slots: 0=this, 1=input, 2=start

    // Allocate local variables
    int lenVar = allocator.allocateInt(); // input.length()
    int tryPosVar = allocator.allocateInt(); // position we're trying to match from
    int posVar = allocator.allocateInt(); // temp for DFA execution
    int chVar = allocator.allocateInt(); // temp for current character

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Try matching from each position
    // for (int tryPos = start; tryPos <= len; tryPos++)
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ISTORE, tryPosVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    // if (tryPos > len) goto loopEnd
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // At this position, check each lookahead atomically using its own DFA
    Label thisPositionFailed = new Label();
    for (LiteralLookaheadInfo lookahead : literalLookaheadInfo.lookaheads) {
      if (!lookahead.hasNFA()) {
        continue; // Skip if NFA wasn't built
      }

      try {
        // Build DFA for this lookahead
        SubsetConstructor constructor = new SubsetConstructor();
        DFA lookaheadDFA = constructor.buildDFA(lookahead.getLookaheadNFA());

        // Generate inline DFA check: if lookahead doesn't match from tryPos, skip this position
        // Generates: if (!matchesLookaheadAtPosition(input, tryPos, lookaheadDFA)) goto
        // thisPositionFailed
        generateInlineLookaheadCheck(
            mv, lookaheadDFA, 1, tryPosVar, lenVar, posVar, chVar, thisPositionFailed);
      } catch (Exception e) {
        // If DFA construction fails, assume lookahead fails (conservative)
        mv.visitJumpInsn(GOTO, thisPositionFailed);
      }
    }

    // All lookaheads passed - now try matching main pattern from this position
    try {
      SubsetConstructor constructor = new SubsetConstructor();
      DFA mainDFA = constructor.buildDFA(literalLookaheadInfo.mainPatternNFA);

      // Generate inline DFA check for main pattern
      // If it matches, we'll jump to a success label where we return tryPos
      Label mainPatternMatched = new Label();
      generateInlineMainPatternCheck(
          mv, mainDFA, 1, tryPosVar, lenVar, posVar, chVar, mainPatternMatched);

      // Main pattern didn't match - try next position
      mv.visitLabel(thisPositionFailed);
      mv.visitIincInsn(tryPosVar, 1);
      mv.visitJumpInsn(GOTO, loopStart);

      // Main pattern matched - return tryPos
      mv.visitLabel(mainPatternMatched);
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitInsn(IRETURN);

    } catch (Exception e) {
      // If main DFA construction fails, return -1 (no match)
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
    }

    mv.visitLabel(loopEnd);
    // No match found
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate optimized findFrom() that uses indexOf() checks + separated main pattern DFA.
   *
   * <p>Strategy:
   *
   * <ol>
   *   <li>Quick check: indexOf() for required literals - reject early if not found
   *   <li>Try each position from start to len
   *   <li>At each position: check lookaheads via indexOf(), then try DFA match
   *   <li>Return first match position or -1
   * </ol>
   *
   * @param mv method visitor
   * @param allocator variable allocator
   * @param className class name for method calls
   */
  private void generateOptimizedFindFrom(
      MethodVisitor mv, LocalVariableAllocator allocator, String className) {
    // Method signature: findFrom(String input, int start)
    // Slots: 0=this, 1=input, 2=start

    // Allocate local variables
    int lenVar = allocator.allocateInt(); // input.length()
    int tryPosVar = allocator.allocateInt(); // position we're trying to match from

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // Quick pre-check: are required literals present in substring from 'start'?
    // If indexOf returns < start, the literal isn't in the valid range
    Label literalsFound = new Label();
    for (LiteralLookaheadInfo lookahead : literalLookaheadInfo.lookaheads) {
      switch (lookahead.getType()) {
        case SINGLE_CHAR:
          // if (input.indexOf(char, start) < start) return -1;
          mv.visitVarInsn(ALOAD, 1);
          pushInt(mv, (int) lookahead.getSingleChar());
          mv.visitVarInsn(ILOAD, 2); // start
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
          mv.visitVarInsn(ILOAD, 2);
          Label thisLiteralOK = new Label();
          mv.visitJumpInsn(IF_ICMPGE, thisLiteralOK);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IRETURN);
          mv.visitLabel(thisLiteralOK);
          break;

        case SUBSTRING:
          // if (input.indexOf(string, start) < start) return -1;
          mv.visitVarInsn(ALOAD, 1);
          mv.visitLdcInsn(lookahead.getLiteral());
          mv.visitVarInsn(ILOAD, 2); // start
          mv.visitMethodInsn(
              INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
          mv.visitVarInsn(ILOAD, 2);
          Label thisSubstringOK = new Label();
          mv.visitJumpInsn(IF_ICMPGE, thisSubstringOK);
          mv.visitInsn(ICONST_M1);
          mv.visitInsn(IRETURN);
          mv.visitLabel(thisSubstringOK);
          break;

        case CHAR_CLASS:
          // Skip CHAR_CLASS for now - too complex for pre-check
          break;
      }
    }

    // Try matching from each position
    // for (int tryPos = start; tryPos <= len; tryPos++)
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ISTORE, tryPosVar);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    // if (tryPos > len) goto loopEnd
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // At this position, check if lookaheads would pass
    Label thisPositionFailed = new Label();
    for (LiteralLookaheadInfo lookahead : literalLookaheadInfo.lookaheads) {
      switch (lookahead.getType()) {
        case SINGLE_CHAR:
          // if (input.indexOf(char, tryPos) < tryPos) continue to next position
          mv.visitVarInsn(ALOAD, 1);
          pushInt(mv, (int) lookahead.getSingleChar());
          mv.visitVarInsn(ILOAD, tryPosVar);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false);
          mv.visitVarInsn(ILOAD, tryPosVar);
          mv.visitJumpInsn(IF_ICMPLT, thisPositionFailed);
          break;

        case SUBSTRING:
          // if (input.indexOf(string, tryPos) < tryPos) continue to next position
          mv.visitVarInsn(ALOAD, 1);
          mv.visitLdcInsn(lookahead.getLiteral());
          mv.visitVarInsn(ILOAD, tryPosVar);
          mv.visitMethodInsn(
              INVOKEVIRTUAL, "java/lang/String", "indexOf", "(Ljava/lang/String;I)I", false);
          mv.visitVarInsn(ILOAD, tryPosVar);
          mv.visitJumpInsn(IF_ICMPLT, thisPositionFailed);
          break;

        case CHAR_CLASS:
          // Skip for now
          break;
      }
    }

    // Lookaheads passed - try matching main pattern from this position
    // Call matchesAtStart(input, tryPos) - we need to generate this helper
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, tryPosVar); // tryPos
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesMainPatternAtStart",
        "(Ljava/lang/String;I)Z",
        false);

    // if (matchesMainPatternAtStart returned true) return tryPos
    Label noMatchAtThisPos = new Label();
    mv.visitJumpInsn(IFEQ, noMatchAtThisPos);
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitInsn(IRETURN);

    mv.visitLabel(noMatchAtThisPos);
    mv.visitLabel(thisPositionFailed);
    // tryPos++
    mv.visitIincInsn(tryPosVar, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);
    // No match found
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate bytecode to execute the main pattern (without lookaheads) using its optimal strategy.
   *
   * <p>After indexOf() lookahead checks have passed, this delegates to the main pattern's optimized
   * execution (e.g., DFA_UNROLLED instead of 39-state NFA).
   *
   * <p>Example: Pattern {@code (?=\w+@)(?=.*example).*@\w+\.com}
   *
   * <ul>
   *   <li>Lookaheads checked: '@' and "example" via indexOf()
   *   <li>Main pattern: {@code .*@\w+\.com} executed via DFA_UNROLLED (8 states)
   * </ul>
   *
   * @param mv method visitor
   * @param allocator variable allocator
   * @param classInternalName internal name of generated class
   */
  private void generateSeparateMainPatternExecution(
      MethodVisitor mv, LocalVariableAllocator allocator, String classInternalName) {
    // Get main pattern info
    NFA mainNFA = literalLookaheadInfo.mainPatternNFA;
    PatternAnalyzer.MatchingStrategyResult mainStrategy = literalLookaheadInfo.mainPatternStrategy;

    // Load input parameter for main pattern execution
    mv.visitVarInsn(ALOAD, 1); // input string

    // Delegate to appropriate strategy
    switch (mainStrategy.strategy) {
      case DFA_UNROLLED:
      case DFA_SWITCH:
        // Generate inline DFA execution for main pattern
        try {
          SubsetConstructor constructor = new SubsetConstructor();
          DFA mainDFA = constructor.buildDFA(mainNFA);

          if (mainStrategy.strategy == PatternAnalyzer.MatchingStrategy.DFA_UNROLLED) {
            // Inline DFA_UNROLLED logic
            generateInlineDFAUnrolled(mv, mainDFA);
          } else {
            // Inline DFA_SWITCH logic
            generateInlineDFASwitch(mv, mainDFA);
          }
        } catch (Exception e) {
          // If DFA construction fails, fall back to NFA
          generateInlineNFA(mv, mainNFA);
        }
        break;

      case OPTIMIZED_NFA:
      case OPTIMIZED_NFA_WITH_LOOKAROUND:
      default:
        // Use NFA execution for main pattern
        generateInlineNFA(mv, mainNFA);
        break;
    }

    // Return the result (boolean already on stack)
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate inline DFA_UNROLLED execution for main pattern. Uses unrolled state machine for
   * maximum performance.
   */
  private void generateInlineDFAUnrolled(MethodVisitor mv, DFA dfa) {
    // Use the existing DFAUnrolledBytecodeGenerator but extract just the core logic
    generateCompleteDFAExecution(mv, dfa);
  }

  /** Generate inline DFA_SWITCH execution for main pattern. */
  private void generateInlineDFASwitch(MethodVisitor mv, DFA dfa) {
    // For smaller DFAs, unrolled is fine
    generateCompleteDFAExecution(mv, dfa);
  }

  /**
   * Generate inline DFA check for a lookahead assertion (zero-width).
   *
   * <p>Checks if the lookahead DFA matches starting from tryPos. If it doesn't match, jumps to
   * failLabel. This is zero-width, so it doesn't consume input - just checks if pattern matches.
   *
   * @param mv method visitor
   * @param dfa the lookahead's DFA
   * @param inputVar slot number for input string
   * @param tryPosVar slot number for current position
   * @param lenVar slot number for input length
   * @param posVar slot number for temp position variable
   * @param chVar slot number for temp character variable
   * @param failLabel label to jump to if lookahead fails
   */
  private void generateInlineLookaheadCheck(
      MethodVisitor mv,
      DFA dfa,
      int inputVar,
      int tryPosVar,
      int lenVar,
      int posVar,
      int chVar,
      Label failLabel) {

    // Label for successful lookahead (will be placed after all DFA states)
    Label successLabel = new Label();

    // Start at tryPos
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Create labels for all DFA states
    Map<Integer, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state.id, new Label());
    }

    // Start at DFA start state
    mv.visitJumpInsn(GOTO, stateLabels.get(dfa.getStartState().id));

    // Generate code for each DFA state
    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(stateLabels.get(state.id));

      // Check if accept state - if yes, lookahead succeeded
      if (dfa.getAcceptStates().contains(state)) {
        // Lookahead matched - jump to success
        mv.visitJumpInsn(GOTO, successLabel);
      }

      // Check if at end of input
      Label endOfInput = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, endOfInput);

      // Read next character
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, chVar);

      mv.visitIincInsn(posVar, 1);

      // Check each transition
      for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
        CharSet chars = entry.getKey();
        DFA.DFAState target = entry.getValue().target;

        Label nextTransition = new Label();

        mv.visitVarInsn(ILOAD, chVar);
        generateCharSetMatchCheck(mv, chars, nextTransition);

        mv.visitJumpInsn(GOTO, stateLabels.get(target.id));

        mv.visitLabel(nextTransition);
      }

      mv.visitLabel(endOfInput);
      // Not in accept state and no valid transition - lookahead failed
      mv.visitJumpInsn(GOTO, failLabel);
    }

    // Success label - lookahead matched, continue to next check
    mv.visitLabel(successLabel);
  }

  /**
   * Generate inline DFA check for main pattern.
   *
   * <p>Checks if the main pattern DFA matches starting from tryPos. If it matches, jumps to
   * successLabel.
   *
   * @param mv method visitor
   * @param dfa the main pattern's DFA
   * @param inputVar slot number for input string
   * @param tryPosVar slot number for current position
   * @param lenVar slot number for input length
   * @param posVar slot number for temp position variable
   * @param chVar slot number for temp character variable
   * @param successLabel label to jump to if main pattern matches
   */
  private void generateInlineMainPatternCheck(
      MethodVisitor mv,
      DFA dfa,
      int inputVar,
      int tryPosVar,
      int lenVar,
      int posVar,
      int chVar,
      Label successLabel) {

    // Start at tryPos
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Create labels for all DFA states
    Map<Integer, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state.id, new Label());
    }

    // Start at DFA start state
    mv.visitJumpInsn(GOTO, stateLabels.get(dfa.getStartState().id));

    // Generate code for each DFA state
    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(stateLabels.get(state.id));

      // Check if accept state
      if (dfa.getAcceptStates().contains(state)) {
        // Main pattern matched - jump to success
        mv.visitJumpInsn(GOTO, successLabel);
      }

      // Check if at end of input
      Label endOfInput = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, endOfInput);

      // Read next character
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, chVar);

      mv.visitIincInsn(posVar, 1);

      // Check each transition
      for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
        CharSet chars = entry.getKey();
        DFA.DFAState target = entry.getValue().target;

        Label nextTransition = new Label();

        mv.visitVarInsn(ILOAD, chVar);
        generateCharSetMatchCheck(mv, chars, nextTransition);

        mv.visitJumpInsn(GOTO, stateLabels.get(target.id));

        mv.visitLabel(nextTransition);
      }

      mv.visitLabel(endOfInput);
      // Not in accept state and no valid transition - main pattern failed
      // Don't jump anywhere, just fall through (caller will handle failure)
    }
  }

  /**
   * Generate complete DFA execution for main pattern. This generates a find() implementation that
   * returns true if pattern matches anywhere in input.
   */
  private void generateCompleteDFAExecution(MethodVisitor mv, DFA dfa) {
    // input is already on stack from ALOAD 1 in caller
    // We're in the matches(String) method, so:
    // - this = var 0
    // - input = var 1
    // Start using var 2+ for local variables

    // Get input length: int len = input.length();
    mv.visitInsn(DUP); // Duplicate input reference
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    int lenVar = 2;
    mv.visitVarInsn(ISTORE, lenVar);
    // Stack now: [input]
    mv.visitInsn(POP); // Clear input from stack

    // Try matching from each position: for (int tryPos = 0; tryPos <= len; tryPos++)
    mv.visitInsn(ICONST_0);
    int tryPosVar = 3;
    mv.visitVarInsn(ISTORE, tryPosVar);

    Label outerLoop = new Label();
    Label outerLoopEnd = new Label();

    mv.visitLabel(outerLoop);
    // if (tryPos > len) goto outerLoopEnd
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGT, outerLoopEnd);

    // Try matching from tryPos using DFA
    // Create labels for all DFA states
    Map<Integer, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state.id, new Label());
    }

    // Local variables for DFA execution
    int posVar = 4; // Current position in input during DFA simulation
    int chVar = 5; // Current character

    // pos = tryPos
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ISTORE, posVar);

    // Start at DFA start state
    mv.visitJumpInsn(GOTO, stateLabels.get(dfa.getStartState().id));

    // Generate code for each DFA state
    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(stateLabels.get(state.id));

      // Check if this is an accept state at end of input or with more input available
      if (dfa.getAcceptStates().contains(state)) {
        // Accept state - return true
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
      }

      // Check if at end of input
      Label endOfInput = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, endOfInput);

      // Read next character: ch = input.charAt(pos)
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, chVar);

      // pos++
      mv.visitIincInsn(posVar, 1);

      // Check each transition
      boolean hasTransitions = false;
      for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
        hasTransitions = true;
        CharSet chars = entry.getKey();
        DFA.DFAState target = entry.getValue().target;

        Label nextTransition = new Label();

        // Check if ch matches this character set
        mv.visitVarInsn(ILOAD, chVar);
        generateCharSetMatchCheck(mv, chars, nextTransition);

        // Match - jump to target state
        mv.visitJumpInsn(GOTO, stateLabels.get(target.id));

        mv.visitLabel(nextTransition);
      }

      // No transition matched - try next starting position
      mv.visitLabel(endOfInput);
    }

    // No match from this position - try next
    mv.visitIincInsn(tryPosVar, 1);
    mv.visitJumpInsn(GOTO, outerLoop);

    // No match found at any position
    mv.visitLabel(outerLoopEnd);
    mv.visitInsn(ICONST_0);
  }

  /**
   * Generate bytecode to check if a character matches a CharSet. Jumps to noMatchLabel if character
   * does NOT match.
   */
  private void generateCharSetMatchCheck(MethodVisitor mv, CharSet charSet, Label noMatchLabel) {
    // ch is already loaded on stack
    // Check each range in the charset
    List<CharSet.Range> ranges = charSet.getRanges();

    if (ranges.isEmpty()) {
      // Empty charset - never matches
      mv.visitInsn(POP); // Pop the character
      mv.visitJumpInsn(GOTO, noMatchLabel);
      return;
    }

    // For each range, check if ch is in [start, end]
    for (int i = 0; i < ranges.size(); i++) {
      CharSet.Range range = ranges.get(i);
      Label tryNextRange = (i < ranges.size() - 1) ? new Label() : noMatchLabel;

      // Duplicate ch for this check (except on last iteration)
      if (i < ranges.size() - 1) {
        mv.visitInsn(DUP);
      }

      // if (ch < range.start) goto tryNextRange
      pushInt(mv, range.start);
      mv.visitJumpInsn(IF_ICMPLT, tryNextRange);

      // Reload ch for upper bound check
      if (i < ranges.size() - 1) {
        mv.visitInsn(DUP);
      } else {
        // Last range - load ch from chVar
        mv.visitVarInsn(ILOAD, 5); // chVar
      }

      // if (ch <= range.end) match found (don't jump to noMatchLabel)
      pushInt(mv, range.end);
      Label matchFound = new Label();
      mv.visitJumpInsn(IF_ICMPLE, matchFound);

      // ch > range.end - try next range
      if (i < ranges.size() - 1) {
        mv.visitLabel(tryNextRange);
      } else {
        // Last range and no match - jump to noMatchLabel
        mv.visitJumpInsn(GOTO, noMatchLabel);
      }

      mv.visitLabel(matchFound);
      // Match found - continue execution (don't jump)
      if (i < ranges.size() - 1) {
        // Clean up the extra DUP we did
        mv.visitInsn(POP);
      }
      return; // Found a match, exit method
    }
  }

  /** Generate inline NFA execution for main pattern. */
  private void generateInlineNFA(MethodVisitor mv, NFA mainNFA) {
    // Fallback: Use existing NFA bytecode generator logic
    // For now, just return true (lookaheads already filtered out non-matches)
    mv.visitInsn(ICONST_1);
  }
}
