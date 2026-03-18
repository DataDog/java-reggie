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

import com.datadoghq.reggie.codegen.automaton.AssertionCheck;
import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.DFA;
import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates switch-based DFA bytecode for patterns with 50-300 states. Uses a while loop with
 * tableswitch statement for state transitions. More compact than unrolled
 * (DFAUnrolledBytecodeGenerator), still very efficient.
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // Pattern: any pattern with 50-300 DFA states
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int state = startStateId;
 *     int pos = 0;
 *
 *     // Main loop with switch-based state machine
 *     while (pos < input.length()) {
 *         char ch = input.charAt(pos++);
 *
 *         // O(1) state dispatch via tableswitch
 *         switch (state) {
 *             case 0:  // State 0 transitions
 *                 // Check assertions if any
 *                 if (!checkAssertions()) return false;
 *                 // Character transitions
 *                 if (ch >= 'a' && ch <= 'z') { state = 1; break; }
 *                 if (ch >= '0' && ch <= '9') { state = 2; break; }
 *                 return false;  // Dead state
 *
 *             case 1:  // State 1 transitions
 *                 // ... transitions ...
 *
 *             default:
 *                 return false;  // Invalid state
 *         }
 *     }
 *
 *     // Check if final state is accepting
 *     return isAccepting(state);
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li><b>tableswitch</b>: O(1) state dispatch for sequential state IDs (vs lookupswitch O(log n))
 *   <li><b>Inline char checks</b>: Range comparisons directly in bytecode
 *   <li><b>Compact bytecode</b>: One loop instead of unrolled labels per state
 *   <li><b>Anchor support</b>: ^, $, \A, \Z, \z inline checks
 * </ul>
 *
 * <h3>When Used</h3>
 *
 * Selected by PatternAnalyzer for patterns with 50-300 DFA states. Above 300 states, falls back to
 * NFABytecodeGenerator to avoid excessive bytecode size.
 */
public class DFASwitchBytecodeGenerator {

  private final DFA dfa;
  private final int groupCount;
  private final NFA nfa; // Needed for anchor information
  private final boolean hasMultilineStart;
  private final boolean hasMultilineEnd;
  private final boolean hasStartAnchor;
  private final boolean requiresStartAnchor; // True only if ALL paths need start anchor
  private final boolean hasEndAnchor;
  private final boolean hasStringStartAnchor;
  private final boolean hasStringEndAnchor;
  private final boolean hasStringEndAbsoluteAnchor;

  public DFASwitchBytecodeGenerator(DFA dfa) {
    this(dfa, 0, null);
  }

  public DFASwitchBytecodeGenerator(DFA dfa, int groupCount) {
    this(dfa, groupCount, null);
  }

  public DFASwitchBytecodeGenerator(DFA dfa, int groupCount, NFA nfa) {
    this.dfa = dfa;
    this.groupCount = groupCount;
    this.nfa = nfa;
    this.hasMultilineStart = (nfa != null) && nfa.hasMultilineStartAnchor();
    this.hasMultilineEnd = (nfa != null) && nfa.hasMultilineEndAnchor();
    this.hasStartAnchor = (nfa != null) && nfa.hasStartAnchor();
    this.requiresStartAnchor = (nfa != null) && nfa.requiresStartAnchor();
    this.hasEndAnchor = (nfa != null) && nfa.hasEndAnchor();
    this.hasStringStartAnchor = (nfa != null) && nfa.hasStringStartAnchor();
    this.hasStringEndAnchor = (nfa != null) && nfa.hasStringEndAnchor();
    this.hasStringEndAbsoluteAnchor = (nfa != null) && nfa.hasStringEndAbsoluteAnchor();
  }

  /**
   * Generate matches() method using switch-based state machine. Structure: int state = 0; int pos =
   * 0; while (pos < input.length()) { char ch = input.charAt(pos++); switch (state) { case 0: //
   * transitions for state 0 case 1: // transitions for state 1 ... } } return
   * acceptStates.contains(state);
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int state = 0; (start state ID)
    int stateVar = allocator.allocate();
    pushInt(mv, dfa.getStartState().id);
    mv.visitVarInsn(ISTORE, stateVar);

    // int pos = 0;
    int posVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Main loop: while (pos < input.length())
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // Special check for \Z (STRING_END): if accepting and pos == length-1 and charAt(pos) == '\n',
    // accept
    if (hasStringEndAnchor) {
      // Check if current state is accepting
      // We need to check all accepting states, so generate checks for each
      for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
        Label notThisAcceptState = new Label();

        // if (state != acceptState.id) goto notThisAcceptState
        mv.visitVarInsn(ILOAD, stateVar);
        pushInt(mv, acceptState.id);
        mv.visitJumpInsn(IF_ICMPNE, notThisAcceptState);

        // if (pos == input.length() - 1 && input.charAt(pos) == '\n') return true;
        Label notStringEnd = new Label();

        // Check if pos == length - 1
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitJumpInsn(IF_ICMPNE, notStringEnd);

        // Check if charAt(pos) == '\n'
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        pushInt(mv, '\n');
        mv.visitJumpInsn(IF_ICMPNE, notStringEnd);

        // Both conditions met - accept
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(notStringEnd);
        mv.visitLabel(notThisAcceptState);
      }
    }

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate switch statement for state transitions
    // Note: pos has been incremented, so pass posVar for assertion checking
    generateStateSwitch(mv, stateVar, chVar, posVar, loopStart, allocator);

    // End of input - check if in accept state
    mv.visitLabel(loopEnd);
    generateAcceptCheck(mv, stateVar);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate switch statement that handles all state transitions. OPTIMIZATION: Use tableswitch
   * instead of lookupswitch for O(1) lookup.
   */
  private void generateStateSwitch(
      MethodVisitor mv,
      int stateVar,
      int chVar,
      int posVar,
      Label loopStart,
      LocalVarAllocator allocator) {
    Label defaultLabel = new Label();
    Label[] caseLabels = new Label[dfa.getAllStates().size()];
    int[] caseKeys = new int[dfa.getAllStates().size()];

    // Create labels for each state
    for (int i = 0; i < dfa.getAllStates().size(); i++) {
      caseLabels[i] = new Label();
      caseKeys[i] = i;
    }

    // switch (state)
    mv.visitVarInsn(ILOAD, stateVar);

    // OPTIMIZATION: Use tableswitch for dense, sequential keys (O(1) vs O(log n))
    // State IDs are always sequential starting from 0, making them perfect for tableswitch
    mv.visitTableSwitchInsn(0, dfa.getAllStates().size() - 1, defaultLabel, caseLabels);

    // Generate case for each state
    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(caseLabels[state.id]);
      generateStateCaseCode(mv, state, stateVar, chVar, posVar, loopStart, defaultLabel, allocator);
    }

    // Default case: invalid state, return false
    mv.visitLabel(defaultLabel);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
  }

  /**
   * Generate code for a single state case in the switch. Checks assertions first, then character
   * transitions.
   */
  private void generateStateCaseCode(
      MethodVisitor mv,
      DFA.DFAState state,
      int stateVar,
      int chVar,
      int posVar,
      Label loopStart,
      Label rejectLabel,
      LocalVarAllocator allocator) {
    // Check assertions BEFORE character transitions (critical for correctness)
    // Note: posVar is AFTER pos++ (incremented in main loop before switch)
    // generateAssertionCheck handles the position adjustment internally
    if (!state.assertionChecks.isEmpty()) {
      Label assertionFailed = new Label();
      Label assertionsPassed = new Label();

      for (AssertionCheck assertion : state.assertionChecks) {
        generateAssertionCheck(mv, assertion, posVar, assertionFailed, allocator);
      }

      // All assertions passed - continue to character transitions
      mv.visitJumpInsn(GOTO, assertionsPassed);

      // Assertion failed - reject
      mv.visitLabel(assertionFailed);
      mv.visitJumpInsn(GOTO, rejectLabel);

      mv.visitLabel(assertionsPassed);
    }

    // For each transition, check if ch matches and update state
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);

      // Match found - update state and continue loop
      pushInt(mv, target.id);
      mv.visitVarInsn(ISTORE, stateVar);
      mv.visitJumpInsn(GOTO, loopStart);

      // No match - try next transition
      mv.visitLabel(nextCheck);
    }

    // No transition matched - reject
    mv.visitJumpInsn(GOTO, rejectLabel);
  }

  /**
   * Generate inline bytecode for assertion check (lookahead and lookbehind). NOTE: posVar points to
   * position AFTER the pos++ increment in the main loop. So the character just consumed was at
   * position (posVar - 1). Assertions check from that position.
   */
  private void generateAssertionCheck(
      MethodVisitor mv,
      AssertionCheck assertion,
      int posVar,
      Label assertionFailed,
      LocalVarAllocator allocator) {
    if (assertion.isLookahead()) {
      // Lookahead: peek forward from current position (posVar - 1)
      String literal = assertion.literal;

      if (assertion.isPositive()) {
        // Positive lookahead: Check all chars match
        for (int i = 0; i < literal.length(); i++) {
          char expectedChar = literal.charAt(i);
          int peekOffset = assertion.offset + i;

          // Bounds check: if ((pos - 1) + peekOffset >= input.length()) fail
          mv.visitVarInsn(ILOAD, posVar); // Load pos (already incremented)
          mv.visitInsn(ICONST_M1); // Load -1
          mv.visitInsn(IADD); // pos - 1
          pushInt(mv, peekOffset); // Load peekOffset
          mv.visitInsn(IADD); // (pos - 1) + peekOffset
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
          mv.visitJumpInsn(IF_ICMPGE, assertionFailed);

          // Character check: if (input.charAt((pos - 1) + peekOffset) != expected) fail
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, posVar); // Load pos
          mv.visitInsn(ICONST_M1); // Load -1
          mv.visitInsn(IADD); // pos - 1
          pushInt(mv, peekOffset); // Load peekOffset
          mv.visitInsn(IADD); // (pos - 1) + peekOffset
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, (int) expectedChar);
          mv.visitJumpInsn(IF_ICMPNE, assertionFailed);
        }
      } else {
        // Negative lookahead: Check if pattern is NOT present
        // If ANY char doesn't match, assertion succeeds (skip to end)
        // If ALL chars match, assertion fails
        Label assertionPassed = new Label();

        for (int i = 0; i < literal.length(); i++) {
          char expectedChar = literal.charAt(i);
          int peekOffset = assertion.offset + i;

          // Bounds check: if ((pos - 1) + peekOffset >= input.length())
          // Pattern not present (out of bounds), so negative assertion succeeds
          mv.visitVarInsn(ILOAD, posVar); // Load pos (already incremented)
          mv.visitInsn(ICONST_M1); // Load -1
          mv.visitInsn(IADD); // pos - 1
          pushInt(mv, peekOffset); // Load peekOffset
          mv.visitInsn(IADD); // (pos - 1) + peekOffset
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
          mv.visitJumpInsn(IF_ICMPGE, assertionPassed);

          // Character check: if (input.charAt((pos - 1) + peekOffset) != expected)
          // Pattern doesn't match, so negative assertion succeeds
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, posVar); // Load pos
          mv.visitInsn(ICONST_M1); // Load -1
          mv.visitInsn(IADD); // pos - 1
          pushInt(mv, peekOffset); // Load peekOffset
          mv.visitInsn(IADD); // (pos - 1) + peekOffset
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, (int) expectedChar);
          mv.visitJumpInsn(IF_ICMPNE, assertionPassed);
        }

        // All chars matched - pattern IS present, negative assertion fails
        mv.visitJumpInsn(GOTO, assertionFailed);

        // Pattern not present - negative assertion succeeds
        mv.visitLabel(assertionPassed);
      }
    } else if (assertion.isLookbehind()) {
      // Lookbehind: check backward from current position (posVar - 1)
      int width = assertion.width;

      // Calculate checkPos = (pos - 1) - width
      int checkPosVar = allocator.allocate();
      mv.visitVarInsn(ILOAD, posVar); // Load pos (already incremented)
      mv.visitInsn(ICONST_M1); // Load -1
      mv.visitInsn(IADD); // pos - 1
      pushInt(mv, width); // Load width
      mv.visitInsn(ISUB); // (pos - 1) - width
      mv.visitVarInsn(ISTORE, checkPosVar);

      // Bounds check: if (checkPos < 0)
      mv.visitVarInsn(ILOAD, checkPosVar);
      Label boundsOk = new Label();
      Label assertionPassed = new Label();
      mv.visitJumpInsn(IFGE, boundsOk); // If >= 0, bounds OK

      // Can't look back far enough
      if (assertion.isPositive()) {
        // Positive lookbehind: fail (required pattern not present)
        mv.visitJumpInsn(GOTO, assertionFailed);
      } else {
        // Negative lookbehind: succeed (no pattern to match against)
        // Skip character checking - assertion is satisfied
        mv.visitJumpInsn(GOTO, assertionPassed);
      }

      mv.visitLabel(boundsOk);

      if (assertion.isLiteral) {
        // Literal assertion: use String.regionMatches()
        mv.visitVarInsn(ALOAD, 1); // Load input string
        mv.visitVarInsn(ILOAD, checkPosVar); // Load checkPos (toffset)
        mv.visitLdcInsn(assertion.literal); // Load literal string (other)
        mv.visitInsn(ICONST_0); // ooffset = 0
        pushInt(mv, assertion.literal.length()); // len
        mv.visitMethodInsn(
            INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);

        // Stack now has boolean result (1 = match, 0 = no match)
        if (assertion.isPositive()) {
          mv.visitJumpInsn(IFEQ, assertionFailed);
        } else {
          mv.visitJumpInsn(IFEQ, assertionPassed);
          mv.visitJumpInsn(GOTO, assertionFailed);
          mv.visitLabel(assertionPassed);
        }
      } else {
        // CharSet sequence assertion
        Label mismatch = new Label();
        int chVar = allocator.allocate();

        for (int i = 0; i < assertion.charSets.size(); i++) {
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, checkPosVar);
          if (i > 0) {
            pushInt(mv, i);
            mv.visitInsn(IADD);
          }
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, chVar);

          generateCharSetCheck(mv, assertion.charSets.get(i), chVar, mismatch);
        }

        // All matched
        if (assertion.isPositive()) {
          // Positive: success
        } else {
          mv.visitJumpInsn(GOTO, assertionFailed);
        }
        mv.visitJumpInsn(GOTO, assertionPassed);

        mv.visitLabel(mismatch);
        if (assertion.isPositive()) {
          mv.visitJumpInsn(GOTO, assertionFailed);
        }
        mv.visitLabel(assertionPassed);
      }
      // Positive assertion continues here (already passed)
    } else {
      throw new IllegalStateException("Unknown assertion type: " + assertion.type);
    }
  }

  /** Generate inline character checks (same as unrolled version). */
  private void generateCharSetCheck(MethodVisitor mv, CharSet chars, int chVar, Label noMatch) {
    if (chars.isSingleChar()) {
      // ch == 'x'
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) chars.getSingleChar());
      mv.visitJumpInsn(IF_ICMPNE, noMatch);
    } else if (chars.isSimpleRange()) {
      // ch >= start && ch <= end
      CharSet.Range range = chars.getSimpleRange();

      // if (ch < start) goto noMatch
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) range.start);
      mv.visitJumpInsn(IF_ICMPLT, noMatch);

      // if (ch > end) goto noMatch
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) range.end);
      mv.visitJumpInsn(IF_ICMPGT, noMatch);
    } else {
      // Multiple ranges: unroll into sequential checks
      Label match = new Label();

      for (CharSet.Range range : chars.getRanges()) {
        Label nextRange = new Label();

        // if (ch < start) goto nextRange
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, nextRange);

        // if (ch <= end) goto match
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, match);

        mv.visitLabel(nextRange);
      }

      // No range matched
      mv.visitJumpInsn(GOTO, noMatch);
      mv.visitLabel(match);
    }
  }

  /** Generate code to check if current state is accepting. */
  private void generateAcceptCheck(MethodVisitor mv, int stateVar) {
    // Check if state is in accept states set
    // For efficiency, generate inline checks for small accept sets
    if (dfa.getAcceptStates().size() <= 5) {
      // Small accept set - use inline comparisons
      Label notAccepting = new Label();

      for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
        Label checkNext = new Label();

        mv.visitVarInsn(ILOAD, stateVar);
        pushInt(mv, acceptState.id);
        mv.visitJumpInsn(IF_ICMPNE, checkNext);

        // Found accepting state
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        mv.visitLabel(checkNext);
      }

      mv.visitLabel(notAccepting);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
    } else {
      // Large accept set - use tableswitch or lookupswitch
      // Simple implementation: check each accept state
      Label notAccepting = new Label();
      for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
        Label checkNext = new Label();
        mv.visitVarInsn(ILOAD, stateVar);
        pushInt(mv, acceptState.id);
        mv.visitJumpInsn(IF_ICMPNE, checkNext);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
        mv.visitLabel(checkNext);
      }
      mv.visitLabel(notAccepting);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
    }
  }

  /**
   * Generates find() method - delegates to findFrom(input, 0).
   *
   * <h3>Generated Algorithm</h3>
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
   * Generates findFrom() method using switch-based matching. Uses first char skip optimization to
   * avoid testing impossible positions.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * int findFrom(String input, int start) {
   *     if (input == null || start < 0 || start > input.length()) return -1;
   *     int len = input.length();
   *
   *     for (int tryPos = start; tryPos < len; tryPos++) {
   *         // ANCHOR OPTIMIZATION: For ^ or \A, only try position 0
   *         if (hasStartAnchor && tryPos != 0) return -1;
   *
   *         // MULTILINE ^: Only try position 0 or after '\n'
   *         if (hasMultilineStart && tryPos != 0 && input.charAt(tryPos-1) != '\n') {
   *             continue;
   *         }
   *
   *         // FIRST CHAR SKIP: Skip if char can't start a match
   *         char ch = input.charAt(tryPos);
   *         if (!validFirstChars.contains(ch)) continue;
   *
   *         // Try matching using switch-based DFA
   *         if (matchesAtStart(input, tryPos)) {
   *             return tryPos;
   *         }
   *     }
   *
   *     // Handle empty match at end
   *     if (startState.isAccepting() && tryPos == len) return len;
   *
   *     return -1;
   * }
   * }</pre>
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=start
    LocalVarAllocator allocator = new LocalVarAllocator(3);

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

    // int len = input.length();
    int lenVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, lenVar);

    // int tryPos = start;
    int tryPosVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, tryPosVar);

    // OPTIMIZATION: Analyze first-character filtering at compile time
    com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization swarOpt = analyzeFirstCharFilter();
    CharSet validFirstChars = computeValidFirstChars();

    Label outerLoopStart = new Label();
    Label outerLoopEnd = new Label();

    mv.visitLabel(outerLoopStart);
    // if (tryPos >= len) goto outerLoopEnd (need at least 1 char to test)
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, outerLoopEnd);

    // ANCHOR OPTIMIZATION: Skip positions that can't match due to anchors
    // Use requiresStartAnchor (not hasStartAnchor) to handle alternations like (^foo|bar)
    // where one branch has anchor but pattern can still match at any position via other branch
    if (requiresStartAnchor || hasStringStartAnchor) {
      // Non-multiline ^ or \A: Only try position 0
      // if (tryPos != 0) return -1;
      Label validPosition = new Label();
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFEQ, validPosition);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(validPosition);
    } else if (hasMultilineStart) {
      // Multiline ^: Only try position 0 or positions after '\n'
      // if (tryPos != 0 && input.charAt(tryPos-1) != '\n') goto nextPosition;
      Label validPosition = new Label();
      Label nextPosition = new Label();

      // if (tryPos == 0) goto validPosition;
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFEQ, validPosition);

      // if (input.charAt(tryPos-1) == '\n') goto validPosition;
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, tryPosVar);
      pushInt(mv, 1);
      mv.visitInsn(ISUB);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPEQ, validPosition);

      // Not a valid anchor position - skip to next
      mv.visitLabel(nextPosition);
      mv.visitIincInsn(tryPosVar, 1); // tryPos++
      mv.visitJumpInsn(GOTO, outerLoopStart);

      mv.visitLabel(validPosition);
    }

    if (swarOpt != null && !dfa.getStartState().accepting) {
      // SWAR OPTIMIZATION: Use pattern-specific optimized search for first char
      // Generates: tryPos = SWARHelper.findNext...(input, tryPos, len);
      swarOpt.generateFindNextBytecode(mv, 1, tryPosVar, lenVar);

      // if (tryPos < 0) goto outerLoopEnd (no valid first char found)
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitJumpInsn(IFLT, outerLoopEnd);

      // Check: if (tryPos >= len) goto outerLoopEnd
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitVarInsn(ILOAD, lenVar);
      mv.visitJumpInsn(IF_ICMPGE, outerLoopEnd);
    } else if (validFirstChars != null && !dfa.getStartState().accepting) {
      // STANDARD OPTIMIZATION: First char skip using charAt()
      Label canStartMatch = new Label();

      // char ch = input.charAt(tryPos);
      int chVar = allocator.allocate();
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, chVar);

      // Check if ch can start a match - jump to canStartMatch if yes
      generateFirstCharFilterCheck(mv, validFirstChars, chVar, canStartMatch);

      // Not a valid first char - skip to next position
      mv.visitIincInsn(tryPosVar, 1); // tryPos++
      mv.visitJumpInsn(GOTO, outerLoopStart);

      mv.visitLabel(canStartMatch);
    }

    // Try matching from tryPos
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesAtStart",
        "(Ljava/lang/String;I)Z",
        false);

    Label noMatchHere = new Label();
    mv.visitJumpInsn(IFEQ, noMatchHere);

    // Match found - return tryPos
    mv.visitVarInsn(ILOAD, tryPosVar);
    mv.visitInsn(IRETURN);

    mv.visitLabel(noMatchHere);
    mv.visitIincInsn(tryPosVar, 1); // tryPos++
    mv.visitJumpInsn(GOTO, outerLoopStart);

    mv.visitLabel(outerLoopEnd);

    // Handle tryPos == len case (empty match at end if start state is accepting)
    if (dfa.getStartState().accepting) {
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitVarInsn(ILOAD, lenVar);
      Label notAtEnd = new Label();
      mv.visitJumpInsn(IF_ICMPNE, notAtEnd);
      mv.visitVarInsn(ILOAD, tryPosVar);
      mv.visitInsn(IRETURN);
      mv.visitLabel(notAtEnd);
    }

    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Compute the set of characters that can start a match. Returns null if any character could start
   * a match (e.g., start state is accepting).
   */
  private CharSet computeValidFirstChars() {
    if (dfa.getStartState().accepting) {
      return null; // Any position can match (empty match)
    }

    Map<CharSet, DFA.DFATransition> transitions = dfa.getStartState().transitions;
    if (transitions.isEmpty()) {
      return null;
    }

    // Union all CharSets from start state transitions
    CharSet result = null;
    for (CharSet cs : transitions.keySet()) {
      if (result == null) {
        result = cs;
      } else {
        result = result.union(cs);
      }
    }
    return result;
  }

  /**
   * Analyze first-character filter to determine if SWAR optimization is applicable. Uses
   * compile-time pattern analysis to generate specialized bytecode.
   *
   * @return SWAROptimization for first-char filtering, or null if not optimizable
   */
  private com.datadoghq.reggie.codegen.codegen.swar.SWAROptimization analyzeFirstCharFilter() {
    CharSet validFirstChars = computeValidFirstChars();
    if (validFirstChars == null) {
      return null;
    }

    // Only optimize if first-char set is narrow (most chars don't match)
    // SWAR helps when we can skip large sections
    int totalSize = 0;
    for (CharSet.Range range : validFirstChars.getRanges()) {
      totalSize += (range.end - range.start + 1);
    }

    // If most characters are valid, SWAR won't help much
    if (totalSize > 128) {
      return null;
    }

    // Use SWARPatternAnalyzer to determine optimization
    return SWARPatternAnalyzer.analyzeForSWAR(validFirstChars, false);
  }

  /**
   * Generate code to check if character (in local variable chVar) is in the CharSet. Jumps to
   * matchLabel if it matches, falls through if not.
   */
  private void generateFirstCharFilterCheck(
      MethodVisitor mv, CharSet chars, int chVar, Label matchLabel) {
    for (CharSet.Range range : chars.getRanges()) {
      if (range.start == range.end) {
        // Single character check
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPEQ, matchLabel);
      } else {
        // Range check: if (ch >= start && ch <= end) goto matchLabel
        Label nextRange = new Label();
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, nextRange);
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, matchLabel);
        mv.visitLabel(nextRange);
      }
    }
    // Falls through if no range matched
  }

  /**
   * Generate matchesAtStart() helper using switch-based state machine. Returns true as soon as an
   * accept state is reached.
   */
  public void generateMatchesAtStartMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, "matchesAtStart", "(Ljava/lang/String;I)Z", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // Check if start state is accepting
    if (dfa.getStartState().accepting) {
      if (hasEndAnchor || hasStringEndAnchor || hasStringEndAbsoluteAnchor || hasMultilineEnd) {
        // Must check end anchor before accepting empty match
        Label continueMatching = new Label();

        // Get current position (startPos) and length
        mv.visitVarInsn(ILOAD, 2); // startPos
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);

        if (hasStringEndAbsoluteAnchor) {
          // \z: Accept only if startPos == length
          mv.visitJumpInsn(IF_ICMPNE, continueMatching);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
          mv.visitLabel(continueMatching);
        } else if (hasStringEndAnchor) {
          // \Z: Accept if startPos == length OR (startPos == length-1 AND charAt(startPos) == '\n')
          Label accept = new Label();

          // Stack: startPos, length
          mv.visitInsn(DUP2);
          mv.visitJumpInsn(IF_ICMPEQ, accept); // If startPos == length, accept

          // Check if startPos == length-1
          mv.visitInsn(DUP2);
          mv.visitInsn(SWAP);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(ISUB);
          mv.visitInsn(SWAP);
          mv.visitJumpInsn(IF_ICMPNE, continueMatching); // startPos != length-1

          // Check charAt(startPos) == '\n'
          mv.visitInsn(POP); // Remove length
          mv.visitVarInsn(ALOAD, 1);
          mv.visitInsn(SWAP);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, '\n');
          mv.visitJumpInsn(IF_ICMPNE, continueMatching);

          mv.visitLabel(accept);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
          mv.visitLabel(continueMatching);
        } else if (hasEndAnchor) {
          // Non-multiline $: Accept only if startPos == length
          mv.visitJumpInsn(IF_ICMPNE, continueMatching);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
          mv.visitLabel(continueMatching);
        } else { // hasMultilineEnd
          // Multiline $: Accept if startPos == length OR charAt(startPos) == '\n'
          Label accept = new Label();

          // Stack: startPos, length
          mv.visitInsn(DUP2);
          mv.visitJumpInsn(IF_ICMPEQ, accept); // If startPos == length, accept

          // Check if startPos < length && charAt(startPos) == '\n'
          mv.visitInsn(DUP2);
          mv.visitJumpInsn(IF_ICMPGE, continueMatching); // startPos >= length, can't check charAt

          // Stack: startPos, length
          mv.visitInsn(POP); // Stack: startPos
          mv.visitVarInsn(ALOAD, 1);
          mv.visitInsn(SWAP);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, '\n');
          mv.visitJumpInsn(IF_ICMPNE, continueMatching);

          mv.visitLabel(accept);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);

          mv.visitLabel(continueMatching);
          // Anchor not met, continue to main matching loop
        }
      } else {
        // No end anchor - accept immediately
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
      }
    }

    // int state = 0; (start state)
    int stateVar = allocator.allocate();
    pushInt(mv, dfa.getStartState().id);
    mv.visitVarInsn(ISTORE, stateVar);

    // int pos = startPos;
    int posVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // Main loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Check if current state is accepting (for partial matches)
    Label notAccepting = new Label();
    for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
      if (acceptState == dfa.getStartState()) continue;

      Label checkNext = new Label();
      mv.visitVarInsn(ILOAD, stateVar);
      pushInt(mv, acceptState.id);
      mv.visitJumpInsn(IF_ICMPNE, checkNext);

      // Found accepting state - but must check end anchor if present
      if (hasEndAnchor || hasStringEndAnchor || hasStringEndAbsoluteAnchor || hasMultilineEnd) {
        Label continueMatching = new Label();

        // Get current position and length
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);

        if (hasEndAnchor) {
          // Non-multiline $: Accept only if pos == length
          mv.visitJumpInsn(IF_ICMPNE, continueMatching); // If pos != length, continue matching
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
        } else { // hasMultilineEnd
          // Multiline $: Accept if pos == length OR charAt(pos) == '\n'
          Label accept = new Label();

          // Stack: pos, length
          mv.visitInsn(DUP2); // Stack: pos, length, pos, length
          mv.visitJumpInsn(IF_ICMPEQ, accept); // If pos == length, accept

          // Stack: pos, length
          // Check if pos < length && charAt(pos) == '\n'
          mv.visitInsn(DUP2); // Stack: pos, length, pos, length
          mv.visitJumpInsn(IF_ICMPGE, continueMatching); // pos >= length, can't check charAt

          // Stack: pos, length
          mv.visitInsn(POP); // Stack: pos
          mv.visitVarInsn(ALOAD, 1);
          mv.visitInsn(SWAP); // Stack: input, pos
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, '\n');
          mv.visitJumpInsn(IF_ICMPNE, continueMatching); // Not '\n', continue matching

          mv.visitLabel(accept);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
        }

        mv.visitLabel(continueMatching);
        // Anchor condition not met, continue to checkNext
      } else {
        // No end anchor - accept immediately
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
      }

      mv.visitLabel(checkNext);
    }
    mv.visitLabel(notAccepting);

    // Check if at end of input
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate switch for state transitions
    // Note: pos has been incremented, so pass posVar for assertion checking
    generateStateSwitch(mv, stateVar, chVar, posVar, loopStart, allocator);

    // End of input - check if accepting
    mv.visitLabel(loopEnd);
    generateAcceptCheck(mv, stateVar);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates match() method that returns MatchResult with group information. Uses inline group
   * tracking if DFA has group actions.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult match(String input) {
   *     if (groupCount > 0 && hasGroupActions()) {
   *         return matchWithGroupTracking(input);
   *     }
   *
   *     // Simple path: no groups
   *     if (!matches(input)) return null;
   *
   *     // Return result with group 0 = entire match
   *     int[] starts = {0, -1, ...};   // Unmatched groups = -1
   *     int[] ends = {input.length(), -1, ...};
   *     return new MatchResultImpl(input, starts, ends, groupCount);
   * }
   * }</pre>
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

    // Check for groups - if we have group actions, track them inline
    if (groupCount > 0 && hasGroupActions()) {
      generateMatchWithGroupTracking(mv, className);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
      return;
    }

    // No groups or no group actions - use simple path

    // if (!matches(input)) return null;
    Label matchSuccess = new Label();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitJumpInsn(IFNE, matchSuccess);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchSuccess);

    // new MatchResultImpl(input, new int[]{0, ...}, new int[]{input.length(), ...}, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);

    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    for (int i = 1; i <= groupCount; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);
    for (int i = 1; i <= groupCount; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
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
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates matchesBounded() method - boolean bounded matching (allocation-free). Uses
   * switch-based DFA on the bounded region [start, end).
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean matchesBounded(CharSequence input, int start, int end) {
   *     if (input == null || start < 0 || end > input.length() || start > end) return false;
   *
   *     int state = startStateId;
   *     int pos = start;
   *
   *     while (pos < end) {
   *         char ch = input.charAt(pos++);
   *         switch (state) {
   *             case 0: ... transitions ...
   *             case 1: ... transitions ...
   *             // ...
   *         }
   *     }
   *
   *     return isAccepting(state);
   * }
   * }</pre>
   */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=start, 3=end
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    // if (input == null || start < 0 || end > input.length() || start > end) return false;
    Label returnFalse = new Label();
    Label checksPass = new Label();

    // if (input == null) return false;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnFalse);

    // if (start < 0) return false;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFLT, returnFalse);

    // if (end > input.length()) return false;
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true);
    mv.visitJumpInsn(IF_ICMPGT, returnFalse);

    // if (start > end) return false;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitJumpInsn(IF_ICMPGT, returnFalse);

    // Run DFA on bounded region
    // int state = startState.id;
    int stateVar = allocator.allocate();
    pushInt(mv, dfa.getStartState().id);
    mv.visitVarInsn(ISTORE, stateVar);

    // int pos = start;
    int posVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // Main loop: while (pos < end)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate switch statement for state transitions
    generateBoundedBooleanStateSwitch(mv, 1, 2, 3, stateVar, chVar, posVar, loopStart, returnFalse);

    // End of input - check if in accept state
    mv.visitLabel(loopEnd);

    // Check if state is accepting
    for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
      Label checkNext = new Label();
      mv.visitVarInsn(ILOAD, stateVar);
      pushInt(mv, acceptState.id);
      mv.visitJumpInsn(IF_ICMPNE, checkNext);

      // Found accepting state - but must check end anchor if present
      if (hasEndAnchor || hasStringEndAnchor || hasStringEndAbsoluteAnchor || hasMultilineEnd) {
        Label continueChecking = new Label();

        // At loopEnd, pos == end (we've consumed the bounded region)
        // For end anchor, check if end satisfies anchor condition
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true);

        if (hasEndAnchor) {
          // Non-multiline $: Accept only if pos == length
          mv.visitJumpInsn(IF_ICMPNE, continueChecking);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
          mv.visitLabel(continueChecking);
        } else { // hasMultilineEnd
          // Multiline $: Accept if pos == length OR charAt(pos) == '\n'
          Label accept = new Label();

          // Stack: pos, length
          mv.visitInsn(DUP2);
          mv.visitJumpInsn(IF_ICMPEQ, accept); // If pos == length, accept

          // Check if pos < length && charAt(pos) == '\n'
          mv.visitInsn(DUP2);
          mv.visitJumpInsn(IF_ICMPGE, continueChecking); // pos >= length, can't check charAt

          // Stack: pos, length
          mv.visitInsn(POP); // Stack: pos
          mv.visitVarInsn(ALOAD, 1);
          mv.visitInsn(SWAP);
          mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);
          pushInt(mv, '\n');
          mv.visitJumpInsn(IF_ICMPNE, continueChecking);

          mv.visitLabel(accept);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);

          mv.visitLabel(continueChecking);
          // Anchor not met, check next state
        }
      } else {
        // No end anchor - accept immediately
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
      }

      mv.visitLabel(checkNext);
    }

    // Not accepting - return false
    mv.visitLabel(returnFalse);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate switch statement for bounded boolean matching. */
  private void generateBoundedBooleanStateSwitch(
      MethodVisitor mv,
      int inputVar,
      int startVar,
      int endVar,
      int stateVar,
      int chVar,
      int posVar,
      Label loopStart,
      Label returnFalse) {
    Label defaultLabel = new Label();
    Label[] caseLabels = new Label[dfa.getAllStates().size()];
    int[] caseKeys = new int[dfa.getAllStates().size()];

    for (int i = 0; i < dfa.getAllStates().size(); i++) {
      caseLabels[i] = new Label();
      caseKeys[i] = i;
    }

    mv.visitVarInsn(ILOAD, stateVar);
    // OPTIMIZATION: Use tableswitch for O(1) state lookup
    mv.visitTableSwitchInsn(0, dfa.getAllStates().size() - 1, defaultLabel, caseLabels);

    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(caseLabels[state.id]);
      generateBoundedBooleanStateCaseCode(mv, state, stateVar, chVar, loopStart, returnFalse);
    }

    mv.visitLabel(defaultLabel);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
  }

  /** Generate case code for bounded boolean state transitions. */
  private void generateBoundedBooleanStateCaseCode(
      MethodVisitor mv,
      DFA.DFAState state,
      int stateVar,
      int chVar,
      Label loopStart,
      Label returnFalse) {
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);

      pushInt(mv, target.id);
      mv.visitVarInsn(ISTORE, stateVar);
      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(nextCheck);
    }

    mv.visitJumpInsn(GOTO, returnFalse);
  }

  /**
   * Generates matchBounded() method - bounded matching with MatchResult. Uses switch-based DFA on
   * the bounded region and returns MatchResult.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult matchBounded(CharSequence input, int start, int end) {
   *     if (input == null || start < 0 || end > input.length() || start > end) return null;
   *
   *     if (!matchesBounded(input, start, end)) return null;
   *
   *     // Return MatchResult with group 0 = matched region
   *     int[] starts = {start, -1, ...};
   *     int[] ends = {end, -1, ...};
   *     return new MatchResultImpl(input.toString(), starts, ends, groupCount);
   * }
   * }</pre>
   */
  public void generateMatchBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchBounded",
            "(Ljava/lang/CharSequence;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=start, 3=end
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    // if (input == null || start < 0 || end > input.length() || start > end) return null;
    Label returnNull = new Label();
    Label checksPass = new Label();

    // if (input == null) return null;
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNULL, returnNull);

    // if (start < 0) return null;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitJumpInsn(IFLT, returnNull);

    // if (end > input.length()) return null;
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true);
    mv.visitJumpInsn(IF_ICMPGT, returnNull);

    // if (start > end) return null;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitJumpInsn(IF_ICMPGT, returnNull);

    // Return null for failed checks
    mv.visitLabel(returnNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    // Checks passed - continue with matching
    mv.visitLabel(checksPass);

    // Run DFA on bounded region
    // int state = startState.id;
    int stateVar = allocator.allocate();
    pushInt(mv, dfa.getStartState().id);
    mv.visitVarInsn(ISTORE, stateVar);

    // int pos = start;
    int posVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // Main loop: while (pos < end)
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate switch statement for state transitions
    generateBoundedStateSwitch(mv, 1, 2, 3, stateVar, chVar, posVar, loopStart);

    // End of input - check if in accept state
    mv.visitLabel(loopEnd);

    // Check if state is accepting
    Label notAccepting = new Label();
    for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
      Label checkNext = new Label();
      mv.visitVarInsn(ILOAD, stateVar);
      pushInt(mv, acceptState.id);
      mv.visitJumpInsn(IF_ICMPNE, checkNext);

      // Found accepting state - but must check end anchor if present
      if (hasEndAnchor || hasStringEndAnchor || hasStringEndAbsoluteAnchor || hasMultilineEnd) {
        Label createResult = new Label();

        // At loopEnd, pos == end (we've consumed the bounded region)
        // For end anchor, check if end satisfies anchor condition
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true);

        if (hasEndAnchor) {
          // Non-multiline $: Accept only if pos == length
          mv.visitJumpInsn(IF_ICMPNE, checkNext); // Anchor not met, check next state
          // Fall through to createResult
        } else { // hasMultilineEnd
          // Multiline $: Accept if pos == length OR charAt(pos) == '\n'
          Label checkNewline = new Label();

          // Stack: pos, length
          mv.visitInsn(DUP2);
          mv.visitJumpInsn(IF_ICMPEQ, createResult); // If pos == length, accept

          // Check if pos < length && charAt(pos) == '\n'
          mv.visitInsn(DUP2);
          mv.visitJumpInsn(IF_ICMPGE, checkNext); // pos >= length, can't check charAt

          // Stack: pos, length
          mv.visitInsn(POP); // Stack: pos
          mv.visitVarInsn(ALOAD, 1);
          mv.visitInsn(SWAP);
          mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);
          pushInt(mv, '\n');
          mv.visitJumpInsn(IF_ICMPNE, checkNext); // Not '\n', check next state
        }

        mv.visitLabel(createResult);
      }

      // Accepting state - create MatchResult
      // new MatchResultImpl(input.toString(), new int[]{start}, new int[]{end}, groupCount)
      mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
      mv.visitInsn(DUP);

      // Convert CharSequence to String
      mv.visitVarInsn(ALOAD, 1);
      mv.visitMethodInsn(
          INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);

      // int[] starts = new int[]{start, -1, -1, ...}
      pushInt(mv, groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ILOAD, 2); // start
      mv.visitInsn(IASTORE);
      for (int i = 1; i <= groupCount; i++) {
        mv.visitInsn(DUP);
        pushInt(mv, i);
        mv.visitInsn(ICONST_M1);
        mv.visitInsn(IASTORE);
      }

      // int[] ends = new int[]{end, -1, -1, ...}
      pushInt(mv, groupCount + 1);
      mv.visitIntInsn(NEWARRAY, T_INT);
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ILOAD, 3); // end
      mv.visitInsn(IASTORE);
      for (int i = 1; i <= groupCount; i++) {
        mv.visitInsn(DUP);
        pushInt(mv, i);
        mv.visitInsn(ICONST_M1);
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

      mv.visitLabel(checkNext);
    }

    // Not accepting - return null
    mv.visitLabel(notAccepting);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate switch statement for bounded matching. */
  private void generateBoundedStateSwitch(
      MethodVisitor mv,
      int inputVar,
      int startVar,
      int endVar,
      int stateVar,
      int chVar,
      int posVar,
      Label loopStart) {
    Label defaultLabel = new Label();
    Label[] caseLabels = new Label[dfa.getAllStates().size()];
    int[] caseKeys = new int[dfa.getAllStates().size()];

    for (int i = 0; i < dfa.getAllStates().size(); i++) {
      caseLabels[i] = new Label();
      caseKeys[i] = i;
    }

    mv.visitVarInsn(ILOAD, stateVar);
    // OPTIMIZATION: Use tableswitch for O(1) state lookup
    mv.visitTableSwitchInsn(0, dfa.getAllStates().size() - 1, defaultLabel, caseLabels);

    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(caseLabels[state.id]);
      generateBoundedStateCaseCode(mv, state, stateVar, chVar, loopStart, defaultLabel);
    }

    mv.visitLabel(defaultLabel);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
  }

  /** Generate case code for bounded state transitions. */
  private void generateBoundedStateCaseCode(
      MethodVisitor mv,
      DFA.DFAState state,
      int stateVar,
      int chVar,
      Label loopStart,
      Label rejectLabel) {
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);

      pushInt(mv, target.id);
      mv.visitVarInsn(ISTORE, stateVar);
      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(nextCheck);
    }

    mv.visitJumpInsn(GOTO, rejectLabel);
  }

  /**
   * Generates findMatch() method - delegates to findMatchFrom(input, 0).
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
   * Generate helper method to find match end using single DFA pass (O(n) not O(n²)). Returns the
   * end position of the longest match starting at 'start', or -1 if no match. Signature: int
   * findMatchEnd(CharSequence input, int start)
   */
  private void generateFindMatchEndMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, "findMatchEnd", "(Ljava/lang/CharSequence;I)I", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=start
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // int state = startState.id;
    int stateVar = allocator.allocate();
    pushInt(mv, dfa.getStartState().id);
    mv.visitVarInsn(ISTORE, stateVar);

    // int pos = start;
    int posVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ISTORE, posVar);

    // int lastAccepting = -1;
    int lastAcceptingVar = allocator.allocate();
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, lastAcceptingVar);

    // Check if start state is accepting
    for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
      if (acceptState.id == dfa.getStartState().id) {
        // Start state is accepting (empty match possible)
        mv.visitVarInsn(ILOAD, 2); // start
        mv.visitVarInsn(ISTORE, lastAcceptingVar);
        break;
      }
    }

    // Main loop: while (pos < input.length())
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate switch for state transitions
    generateFindMatchEndStateSwitch(
        mv, stateVar, chVar, lastAcceptingVar, posVar, loopStart, loopEnd);

    // End of loop - return lastAccepting
    mv.visitLabel(loopEnd);
    mv.visitVarInsn(ILOAD, lastAcceptingVar);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate switch statement for findMatchEnd (tracks last accepting position). */
  private void generateFindMatchEndStateSwitch(
      MethodVisitor mv,
      int stateVar,
      int chVar,
      int lastAcceptingVar,
      int posVar,
      Label loopStart,
      Label loopEnd) {
    Label defaultLabel = new Label();
    Label[] caseLabels = new Label[dfa.getAllStates().size()];
    int[] caseKeys = new int[dfa.getAllStates().size()];

    for (int i = 0; i < dfa.getAllStates().size(); i++) {
      caseLabels[i] = new Label();
      caseKeys[i] = i;
    }

    mv.visitVarInsn(ILOAD, stateVar);
    // OPTIMIZATION: Use tableswitch for O(1) state lookup
    mv.visitTableSwitchInsn(0, dfa.getAllStates().size() - 1, defaultLabel, caseLabels);

    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(caseLabels[state.id]);
      generateFindMatchEndCaseCode(
          mv, state, stateVar, chVar, lastAcceptingVar, posVar, loopStart, loopEnd);
    }

    // Default: dead state, return lastAccepting
    mv.visitLabel(defaultLabel);
    mv.visitVarInsn(ILOAD, lastAcceptingVar);
    mv.visitInsn(IRETURN);
  }

  /** Generate case code for findMatchEnd state transitions. */
  private void generateFindMatchEndCaseCode(
      MethodVisitor mv,
      DFA.DFAState state,
      int stateVar,
      int chVar,
      int lastAcceptingVar,
      int posVar,
      Label loopStart,
      Label loopEnd) {
    // Try each transition
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);

      // Match found - update state
      pushInt(mv, target.id);
      mv.visitVarInsn(ISTORE, stateVar);

      // Check if target state is accepting
      boolean isAccepting = false;
      for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
        if (acceptState.id == target.id) {
          isAccepting = true;
          break;
        }
      }

      if (isAccepting) {
        // Update lastAccepting = pos (after increment)
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ISTORE, lastAcceptingVar);
      }

      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(nextCheck);
    }

    // No transition matched - dead state, return lastAccepting
    mv.visitVarInsn(ILOAD, lastAcceptingVar);
    mv.visitInsn(IRETURN);
  }

  /**
   * Generates findMatchFrom() method using single-pass DFA (O(n) not O(n²)). Finds the match start
   * with findFrom(), then uses greedy DFA to find longest match end.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult findMatchFrom(String input, int start) {
   *     // Find match start position
   *     int matchStart = findFrom(input, start);
   *     if (matchStart < 0) return null;
   *
   *     // Use greedy DFA to find longest match end
   *     int matchEnd = findMatchEnd(input, matchStart);
   *     if (matchEnd < 0) return null;
   *
   *     // Extract groups by matching substring
   *     String matchedSubstring = input.substring(matchStart, matchEnd);
   *     MatchResult groupResult = match(matchedSubstring);
   *
   *     // Adjust group positions by adding matchStart offset
   *     return adjustedMatchResult(input, groupResult, matchStart);
   * }
   * }</pre>
   */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    // First generate the helper method
    generateFindMatchEndMethod(cw);

    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=start
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // int matchStart = findFrom(input, start);
    int matchStartVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) return null;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(found);

    // int matchEnd = findMatchEnd(input, matchStart);
    int matchEndVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchEnd",
        "(Ljava/lang/CharSequence;I)I",
        false);
    mv.visitVarInsn(ISTORE, matchEndVar);

    // if (matchEnd < 0) return null; (no match found)
    Label hasMatch = new Label();
    mv.visitVarInsn(ILOAD, matchEndVar);
    mv.visitJumpInsn(IFGE, hasMatch);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(hasMatch);

    // String substring = input.substring(matchStart, matchEnd);
    int substringVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitVarInsn(ILOAD, matchEndVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
    mv.visitVarInsn(ASTORE, substringVar);

    // MatchResult subResult = match(substring);
    int subResultVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, substringVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, subResultVar);

    // if (subResult == null) return null;
    Label hasSubResult = new Label();
    mv.visitVarInsn(ALOAD, subResultVar);
    mv.visitJumpInsn(IFNONNULL, hasSubResult);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(hasSubResult);

    // Create starts[] array
    int startsVar = allocator.allocate();
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, startsVar);

    // starts[0] = matchStart;
    mv.visitVarInsn(ALOAD, startsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);

    // For groups 1..n: starts[i] = subResult.start(i) == -1 ? -1 : subResult.start(i) + matchStart
    for (int i = 1; i <= groupCount; i++) {
      mv.visitVarInsn(ALOAD, startsVar);
      pushInt(mv, i);

      // Get subResult.start(i)
      mv.visitVarInsn(ALOAD, subResultVar);
      pushInt(mv, i);
      mv.visitMethodInsn(
          INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "start", "(I)I", true);

      // Duplicate to test and use
      mv.visitInsn(DUP);
      Label notMinusOne = new Label();
      Label storeStart = new Label();
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPNE, notMinusOne);
      // It's -1, keep -1
      mv.visitInsn(POP); // Remove the duplicate
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(GOTO, storeStart);

      mv.visitLabel(notMinusOne);
      // Add matchStart offset
      mv.visitVarInsn(ILOAD, matchStartVar);
      mv.visitInsn(IADD);

      mv.visitLabel(storeStart);
      mv.visitInsn(IASTORE);
    }

    // Create ends[] array
    int endsVar = allocator.allocate();
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, endsVar);

    // ends[0] = matchEnd;
    mv.visitVarInsn(ALOAD, endsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchEndVar);
    mv.visitInsn(IASTORE);

    // For groups 1..n: ends[i] = subResult.end(i) == -1 ? -1 : subResult.end(i) + matchStart
    for (int i = 1; i <= groupCount; i++) {
      mv.visitVarInsn(ALOAD, endsVar);
      pushInt(mv, i);

      // Get subResult.end(i)
      mv.visitVarInsn(ALOAD, subResultVar);
      pushInt(mv, i);
      mv.visitMethodInsn(
          INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "end", "(I)I", true);

      // Duplicate to test and use
      mv.visitInsn(DUP);
      Label notMinusOneEnd = new Label();
      Label storeEnd = new Label();
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPNE, notMinusOneEnd);
      // It's -1, keep -1
      mv.visitInsn(POP); // Remove the duplicate
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(GOTO, storeEnd);

      mv.visitLabel(notMinusOneEnd);
      // Add matchStart offset
      mv.visitVarInsn(ILOAD, matchStartVar);
      mv.visitInsn(IADD);

      mv.visitLabel(storeEnd);
      mv.visitInsn(IASTORE);
    }

    // return new MatchResultImpl(input, starts, ends, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input
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
   * Generates findBoundsFrom() method - allocation-free alternative to findMatchFrom(). Stores
   * match boundaries in the provided int[] array instead of allocating MatchResult.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean findBoundsFrom(String input, int start, int[] bounds) {
   *     // Find match start position
   *     int matchStart = findFrom(input, start);
   *     if (matchStart < 0) return false;
   *
   *     // Use greedy DFA to find longest match end
   *     int matchEnd = findMatchEnd(input, matchStart);
   *     if (matchEnd < 0) return false;
   *
   *     // Store bounds (zero allocation)
   *     bounds[0] = matchStart;
   *     bounds[1] = matchEnd;
   *     return true;
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * Optimized for use in replaceAll() operations where MatchResult allocation would be expensive.
   * The bounds array is reused across multiple calls.
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=start, 3=bounds
    LocalVarAllocator allocator = new LocalVarAllocator(4);

    // int matchStart = findFrom(input, start);
    int matchStartVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) return false;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(found);

    // int matchEnd = findMatchEnd(input, matchStart);
    int matchEndVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findMatchEnd",
        "(Ljava/lang/CharSequence;I)I",
        false);
    mv.visitVarInsn(ISTORE, matchEndVar);

    // if (matchEnd < 0) return false; (no match found)
    Label hasMatch = new Label();
    mv.visitVarInsn(ILOAD, matchEndVar);
    mv.visitJumpInsn(IFGE, hasMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(hasMatch);

    // bounds[0] = matchStart;
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);

    // bounds[1] = matchEnd;
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, matchEndVar);
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate match() method with inline group tracking during DFA execution. */
  private void generateMatchWithGroupTracking(MethodVisitor mv, String className) {
    // Create allocator: slots 0=this, 1=input
    LocalVarAllocator allocator = new LocalVarAllocator(2);

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int state = startState.id;
    int stateVar = allocator.allocate();
    pushInt(mv, dfa.getStartState().id);
    mv.visitVarInsn(ISTORE, stateVar);

    // int pos = 0;
    int posVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // char ch;
    int chVar = allocator.allocate();

    // int[] groupStarts = new int[groupCount + 1];
    int groupStartsVar = allocator.allocate();
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupStartsVar);

    // int[] groupEnds = new int[groupCount + 1];
    int groupEndsVar = allocator.allocate();
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, groupEndsVar);

    // Initialize group 0 (full match)
    // groupStarts[0] = 0;
    mv.visitVarInsn(ALOAD, groupStartsVar);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);

    // Initialize inner groups to -1
    for (int i = 1; i <= groupCount; i++) {
      // groupStarts[i] = -1;
      mv.visitVarInsn(ALOAD, groupStartsVar);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);

      // groupEnds[i] = -1;
      mv.visitVarInsn(ALOAD, groupEndsVar);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // Process group actions for start state
    generateGroupActionsForState(mv, dfa.getStartState(), posVar, groupStartsVar, groupEndsVar);

    // Main loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos++);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);
    mv.visitIincInsn(posVar, 1);

    // Switch statement with group tracking
    generateStateSwitchWithGroupTracking(
        mv, stateVar, chVar, posVar, groupStartsVar, groupEndsVar, loopStart);

    // End of input - check accept state
    mv.visitLabel(loopEnd);

    // if (!acceptStates.contains(state)) return null;
    Label isAccept = new Label();
    generateAcceptCheck(mv, stateVar, isAccept);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(isAccept);

    // groupEnds[0] = input.length();
    mv.visitVarInsn(ALOAD, groupEndsVar);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);

    // return new MatchResultImpl(input, groupStarts, groupEnds, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1);
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
  }

  /** Generate switch statement with group tracking. */
  private void generateStateSwitchWithGroupTracking(
      MethodVisitor mv,
      int stateVar,
      int chVar,
      int posVar,
      int groupStartsVar,
      int groupEndsVar,
      Label loopStart) {
    Label defaultLabel = new Label();
    Label[] caseLabels = new Label[dfa.getAllStates().size()];
    int[] caseKeys = new int[dfa.getAllStates().size()];

    for (int i = 0; i < dfa.getAllStates().size(); i++) {
      caseLabels[i] = new Label();
      caseKeys[i] = i;
    }

    mv.visitVarInsn(ILOAD, stateVar);
    // OPTIMIZATION: Use tableswitch for O(1) state lookup
    mv.visitTableSwitchInsn(0, dfa.getAllStates().size() - 1, defaultLabel, caseLabels);

    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(caseLabels[state.id]);
      generateStateCaseCodeWithGroupTracking(
          mv,
          state,
          stateVar,
          chVar,
          posVar,
          groupStartsVar,
          groupEndsVar,
          loopStart,
          defaultLabel);
    }

    // Default: no match
    mv.visitLabel(defaultLabel);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
  }

  /** Generate case code for a state with group tracking. */
  private void generateStateCaseCodeWithGroupTracking(
      MethodVisitor mv,
      DFA.DFAState state,
      int stateVar,
      int chVar,
      int posVar,
      int groupStartsVar,
      int groupEndsVar,
      Label loopStart,
      Label rejectLabel) {
    // Handle character transitions
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);

      // Apply group actions for target state
      generateGroupActionsForState(mv, target, posVar, groupStartsVar, groupEndsVar);

      // Update state
      pushInt(mv, target.id);
      mv.visitVarInsn(ISTORE, stateVar);
      mv.visitJumpInsn(GOTO, loopStart);

      mv.visitLabel(nextCheck);
    }

    // No match
    mv.visitJumpInsn(GOTO, rejectLabel);
  }

  /** Check if the DFA has any group actions. */
  private boolean hasGroupActions() {
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (!state.groupActions.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Generate bytecode to apply group actions for a specific state.
   *
   * @param mv Method visitor
   * @param state DFA state with group actions
   * @param posVar Variable holding current position
   * @param groupStartsVar Variable holding groupStarts array
   * @param groupEndsVar Variable holding groupEnds array
   */
  private void generateGroupActionsForState(
      MethodVisitor mv, DFA.DFAState state, int posVar, int groupStartsVar, int groupEndsVar) {
    for (DFA.GroupAction action : state.groupActions) {
      if (action.type == DFA.GroupAction.ActionType.ENTER) {
        // groupStarts[groupId] = pos;
        mv.visitVarInsn(ALOAD, groupStartsVar);
        pushInt(mv, action.groupId);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitInsn(IASTORE);
      } else {
        // groupEnds[groupId] = pos;
        mv.visitVarInsn(ALOAD, groupEndsVar);
        pushInt(mv, action.groupId);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitInsn(IASTORE);
      }
    }
  }

  /** Generate accept check that jumps to label if accepting. */
  private void generateAcceptCheck(MethodVisitor mv, int stateVar, Label acceptLabel) {
    for (DFA.DFAState acceptState : dfa.getAcceptStates()) {
      mv.visitVarInsn(ILOAD, stateVar);
      pushInt(mv, acceptState.id);
      mv.visitJumpInsn(IF_ICMPEQ, acceptLabel);
    }
  }
}
