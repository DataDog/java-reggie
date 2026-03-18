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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates fully unrolled DFA bytecode for patterns with &lt;50 states. Each state is a label with
 * inline transition checks - no loops, no BitSet. This is the fastest strategy but produces larger
 * bytecode.
 *
 * <h3>Pattern Types</h3>
 *
 * Handles any pattern that compiles to &lt;50 DFA states, including:
 *
 * <ul>
 *   <li>Literals: {@code abc}, {@code hello}
 *   <li>Character classes: {@code [a-z]+}, {@code \d{3}}
 *   <li>Simple alternations: {@code foo|bar}
 *   <li>Assertions: {@code (?=suffix)}, {@code (?&lt;=prefix)}
 * </ul>
 *
 * <h3>Generated Algorithm (matches)</h3>
 *
 * <pre>{@code
 * // For pattern "a[bc]+d" on input "abcd":
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int pos = 0;
 *
 *     // STATE_0: (start state)
 *     STATE_0:
 *     if (pos >= input.length()) return false;  // Not accepting
 *     char ch = input.charAt(pos++);
 *     if (ch == 'a') goto STATE_1;
 *     return false;  // No transition matched
 *
 *     // STATE_1:
 *     STATE_1:
 *     if (pos >= input.length()) return false;  // Not accepting
 *     ch = input.charAt(pos++);
 *     if (ch == 'b' || ch == 'c') goto STATE_1;  // Loop on [bc]
 *     if (ch == 'd') goto STATE_2;
 *     return false;
 *
 *     // STATE_2: (accepting)
 *     STATE_2:
 *     if (pos >= input.length()) return true;  // Accepting, end of input
 *     return false;  // Extra characters after match
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li><b>No state variable</b>: Control flow uses direct GOTO between labels
 *   <li><b>Inline char checks</b>: Single char comparison or range checks, no method calls
 *   <li><b>First char skip</b>: In findFrom(), skip positions where first char can't match
 *   <li><b>Anchor pruning</b>: For ^/\A patterns, only try position 0
 *   <li><b>Assertion inlining</b>: Lookahead/lookbehind checks are fully inlined
 * </ul>
 *
 * <h3>Group Tracking Modes</h3>
 *
 * <ul>
 *   <li><b>No groups</b>: Simple boolean matching
 *   <li><b>State-based groups</b>: Track groups via DFA state groupActions
 *   <li><b>Tagged DFA</b>: Tag array records group boundaries during transitions
 * </ul>
 */
public class DFAUnrolledBytecodeGenerator {

  private final DFA dfa;
  private final int groupCount;
  private final boolean useTaggedDFA;
  private final NFA nfa; // Needed for anchor information
  private final boolean hasMultilineStart;
  private final boolean hasMultilineEnd;
  private final boolean hasStartAnchor;
  private final boolean requiresStartAnchor; // True only if ALL paths need start anchor
  private final boolean hasEndAnchor;
  private final boolean hasStringStartAnchor;
  private final boolean hasStringEndAnchor;
  private final boolean hasStringEndAbsoluteAnchor;
  private final boolean
      skipEagerAssertionGroupCapture; // True when assertion groups conflict with regular groups

  public DFAUnrolledBytecodeGenerator(DFA dfa) {
    this(dfa, 0, false, null);
  }

  public DFAUnrolledBytecodeGenerator(DFA dfa, int groupCount) {
    this(dfa, groupCount, false, null);
  }

  public DFAUnrolledBytecodeGenerator(DFA dfa, int groupCount, boolean useTaggedDFA) {
    this(dfa, groupCount, useTaggedDFA, null);
  }

  public DFAUnrolledBytecodeGenerator(DFA dfa, int groupCount, boolean useTaggedDFA, NFA nfa) {
    this.dfa = dfa;
    this.groupCount = groupCount;
    this.useTaggedDFA = useTaggedDFA;
    this.nfa = nfa;
    this.hasMultilineStart = (nfa != null) && nfa.hasMultilineStartAnchor();
    this.hasMultilineEnd = (nfa != null) && nfa.hasMultilineEndAnchor();
    this.hasStartAnchor = (nfa != null) && nfa.hasStartAnchor();
    this.requiresStartAnchor = (nfa != null) && nfa.requiresStartAnchor();
    this.hasEndAnchor = (nfa != null) && nfa.hasEndAnchor();
    this.hasStringStartAnchor = (nfa != null) && nfa.hasStringStartAnchor();
    this.hasStringEndAnchor = (nfa != null) && nfa.hasStringEndAnchor();
    this.hasStringEndAbsoluteAnchor = (nfa != null) && nfa.hasStringEndAbsoluteAnchor();
    this.skipEagerAssertionGroupCapture = computeSkipEagerAssertionGroupCapture();
  }

  /**
   * Determines if we should skip eager assertion group capture. This returns true when: 1. There
   * are assertions with capturing groups, AND 2. There are also OTHER groups (in regular
   * groupActions) in the DFA
   *
   * <p>The second condition indicates that the pattern has capturing groups outside the assertion,
   * which typically means alternation with groups in other branches. In such cases, eager assertion
   * group capture would incorrectly preserve group captures from assertions in alternatives that
   * ultimately don't match.
   *
   * <p>For example, in {@code (?=(a))ab|(a)c} on "ac": - Group 1 is in the lookahead - Group 2 is
   * in the second alternative - The lookahead passes and would capture group 1 - But then 'ab'
   * fails and '(a)c' matches - Per PCRE semantics, group 1 should be empty since the lookahead's
   * alternative failed
   *
   * <p>However, for {@code (?=(b))b|c} on "b": - Only group 1 (in lookahead), no other groups -
   * When lookahead passes and 'b' matches, group 1 should be captured - This is safe because there
   * are no conflicting group captures in other alternatives
   */
  private boolean computeSkipEagerAssertionGroupCapture() {
    // Check if there are assertion groups
    boolean hasAssertionGroups = false;
    for (DFA.DFAState state : dfa.getAllStates()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        if (assertion.hasGroups()) {
          hasAssertionGroups = true;
          break;
        }
      }
      if (hasAssertionGroups) break;
    }

    if (!hasAssertionGroups) {
      return false;
    }

    // Check if there are regular group actions (groups outside assertions)
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (!state.groupActions.isEmpty()) {
        // There are groups outside assertions = potential conflict
        return true;
      }
    }

    return false;
  }

  /**
   * Generates matches() method: fully unrolled DFA state machine. Zero allocations, direct jumps
   * between states using GOTO.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean matches(String input) {
   *     if (input == null) return false;
   *     int pos = 0;
   *
   *     // Each DFA state becomes a label with inline code:
   *     STATE_N:
   *         // 1. Check assertions (if any)
   *         if (!checkAssertions(pos)) return false;
   *
   *         // 2. Check end of input
   *         if (pos >= input.length()) {
   *             return state.isAccepting();
   *         }
   *
   *         // 3. Get next character
   *         char ch = input.charAt(pos++);
   *
   *         // 4. Check transitions (inline char comparisons)
   *         if (ch == 'a') goto STATE_X;
   *         if (ch >= 'b' && ch <= 'z') goto STATE_Y;
   *         // ... more transitions ...
   *
   *         // 5. No transition matched
   *         return false;
   * }
   * }</pre>
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

    // int pos = 0;
    int posVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Create labels for all states
    Map<DFA.DFAState, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state, new Label());
    }

    // Generate code for start state
    mv.visitLabel(stateLabels.get(dfa.getStartState()));
    generateStateCode(mv, dfa.getStartState(), stateLabels, posVar, allocator);

    // Generate code for all other states
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state == dfa.getStartState()) continue;
      mv.visitLabel(stateLabels.get(state));
      generateStateCode(mv, state, stateLabels, posVar, allocator);
    }

    mv.visitMaxs(0, 0); // Computed automatically
    mv.visitEnd();
  }

  /**
   * Generate code for a single DFA state. Structure: 1. Check if at end of input 2. Get next
   * character 3. Check transitions and jump to target state 4. If no transition matches, return
   * false
   */
  private void generateStateCode(
      MethodVisitor mv,
      DFA.DFAState state,
      Map<DFA.DFAState, Label> stateLabels,
      int posVar,
      LocalVarAllocator allocator) {
    Label endOfInput = new Label();
    Label assertionFailed = new Label();

    // PROTOTYPE: Generate assertion checks first (before consuming character)
    if (!state.assertionChecks.isEmpty()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        generateAssertionCheck(mv, assertion, posVar, assertionFailed, allocator);
      }
    }

    // if (pos >= input.length()) goto endOfInput
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, endOfInput);

    // Special check for \Z (STRING_END): if accepting and pos == length-1 and charAt(pos) == '\n',
    // accept
    if (state.accepting && hasStringEndAnchor) {
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
    }

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate transition checks (NO BitSet, direct char comparisons)
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);

      // Match found - jump to target state
      mv.visitJumpInsn(GOTO, stateLabels.get(target));

      // No match - try next transition
      mv.visitLabel(nextCheck);
    }

    // No transition matched - reject
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // Handle end of input
    mv.visitLabel(endOfInput);
    if (state.accepting) {
      mv.visitInsn(ICONST_1);
    } else {
      mv.visitInsn(ICONST_0);
    }
    mv.visitInsn(IRETURN);

    // Assertion failed label (if assertions were present)
    if (!state.assertionChecks.isEmpty()) {
      mv.visitLabel(assertionFailed);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
    }
  }

  /**
   * PROTOTYPE: Generate inline bytecode for assertion check. Hard-codes the literal check as
   * suggested by user.
   *
   * <p>For lookahead: peek at current position For lookbehind: peek backward (not implemented yet)
   */
  private void generateAssertionCheck(
      MethodVisitor mv,
      AssertionCheck assertion,
      int posVar,
      Label assertionFailed,
      LocalVarAllocator allocator) {
    if (assertion.isLookahead()) {
      // Lookahead: peek at current position + offset
      if (assertion.isLiteral) {
        // Literal assertion (e.g., "abc")
        String literal = assertion.literal;

        if (assertion.isPositive()) {
          // Positive lookahead: Check all chars match
          // Optimization: Single bounds check for entire assertion
          // if (pos + offset + length > input.length()) fail
          mv.visitVarInsn(ILOAD, posVar);
          pushInt(mv, assertion.offset + literal.length());
          mv.visitInsn(IADD);
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
          mv.visitJumpInsn(IF_ICMPGT, assertionFailed);

          // Allocate temp var for checkPos
          int checkPosVar = allocator.allocate();

          for (int i = 0; i < literal.length(); i++) {
            char expectedChar = literal.charAt(i);
            int peekOffset = assertion.offset + i;

            // Cache offset calculation
            // int checkPos = pos + peekOffset;
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, peekOffset);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, checkPosVar);

            // Character check: if (input.charAt(checkPos) != expected) fail
            mv.visitVarInsn(ALOAD, 1); // input
            mv.visitVarInsn(ILOAD, checkPosVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, (int) expectedChar);
            mv.visitJumpInsn(IF_ICMPNE, assertionFailed);
          }
        } else {
          // Negative lookahead: Check if pattern is NOT present
          // If ANY char doesn't match, assertion succeeds (skip to end)
          // If ALL chars match, assertion fails
          Label assertionPassed = new Label();

          // Allocate temp var for checkPos
          int checkPosVar = allocator.allocate();

          for (int i = 0; i < literal.length(); i++) {
            char expectedChar = literal.charAt(i);
            int peekOffset = assertion.offset + i;

            // Cache offset calculation
            // int checkPos = pos + peekOffset;
            mv.visitVarInsn(ILOAD, posVar);
            pushInt(mv, peekOffset);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ISTORE, checkPosVar);

            // Bounds check: if (checkPos >= input.length())
            // Pattern not present (out of bounds), so negative assertion succeeds
            mv.visitVarInsn(ILOAD, checkPosVar);
            mv.visitVarInsn(ALOAD, 1); // input
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
            mv.visitJumpInsn(IF_ICMPGE, assertionPassed);

            // Character check: if (input.charAt(checkPos) != expected)
            // Pattern doesn't match, so negative assertion succeeds
            mv.visitVarInsn(ALOAD, 1); // input
            mv.visitVarInsn(ILOAD, checkPosVar);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
            pushInt(mv, (int) expectedChar);
            mv.visitJumpInsn(IF_ICMPNE, assertionPassed);
          }

          // All chars matched - pattern IS present, negative assertion fails
          mv.visitJumpInsn(GOTO, assertionFailed);

          // Pattern not present - negative assertion succeeds
          mv.visitLabel(assertionPassed);
        }
      } else {
        // Character class sequence assertion (e.g., [A-Z][0-9])
        // Optimization: Single bounds check for entire sequence
        // if (pos + offset + length > input.length()) fail
        mv.visitVarInsn(ILOAD, posVar);
        pushInt(mv, assertion.offset + assertion.charSets.size());
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 1); // input
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitJumpInsn(IF_ICMPGT, assertionFailed);

        // Allocate temp vars
        int checkPosVar = allocator.allocate();
        int chVar = allocator.allocate();

        for (int i = 0; i < assertion.charSets.size(); i++) {
          CharSet charSet = assertion.charSets.get(i);
          int peekOffset = assertion.offset + i;

          // Cache offset calculation
          // int checkPos = pos + peekOffset;
          mv.visitVarInsn(ILOAD, posVar);
          pushInt(mv, peekOffset);
          mv.visitInsn(IADD);
          mv.visitVarInsn(ISTORE, checkPosVar);

          // Character check: if (!charSet.contains(input.charAt(checkPos))) fail
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, checkPosVar);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, chVar);

          // Check if character matches charset
          Label matches = new Label();
          generateCharSetCheck(mv, charSet, chVar, matches);
          mv.visitJumpInsn(GOTO, assertionFailed); // Doesn't match
          mv.visitLabel(matches); // Matches - continue
        }
      }
    } else if (assertion.isLookbehind()) {
      // Lookbehind: check backward from current position
      int width = assertion.width;
      // Allocate temp var for checkPos
      int checkPosVar = allocator.allocate();

      // Calculate checkPos = pos - width
      mv.visitVarInsn(ILOAD, posVar); // Load pos
      pushInt(mv, width); // Load width
      mv.visitInsn(ISUB); // Calculate: pos - width
      mv.visitVarInsn(ISTORE, checkPosVar); // Store in temp var

      // Bounds check: if (checkPos < 0)
      mv.visitVarInsn(ILOAD, checkPosVar); // Load checkPos
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
        // Literal assertion: use String.regionMatches() for efficient comparison
        mv.visitVarInsn(ALOAD, 1); // Load input string
        mv.visitVarInsn(ILOAD, checkPosVar); // Load checkPos (toffset)
        mv.visitLdcInsn(assertion.literal); // Load literal string (other)
        mv.visitInsn(ICONST_0); // ooffset = 0
        pushInt(mv, assertion.literal.length()); // len
        mv.visitMethodInsn(
            INVOKEVIRTUAL, "java/lang/String", "regionMatches", "(ILjava/lang/String;II)Z", false);

        // Stack now has boolean result (1 = match, 0 = no match)
        if (assertion.isPositive()) {
          // Positive lookbehind: fail if NOT matched (result == 0)
          mv.visitJumpInsn(IFEQ, assertionFailed);
        } else {
          // Negative lookbehind: succeed if NOT matched (result == 0)
          mv.visitJumpInsn(IFEQ, assertionPassed);
          // If matched (result == 1), fall through to fail
          mv.visitJumpInsn(GOTO, assertionFailed);
          mv.visitLabel(assertionPassed);
        }
      } else {
        // CharSet sequence assertion: check each character class
        Label mismatch = new Label();
        int chVar = allocator.allocate(); // Temporary variable for character

        for (int i = 0; i < assertion.charSets.size(); i++) {
          // Load: char ch = input.charAt(checkPos + i);
          mv.visitVarInsn(ALOAD, 1); // input
          mv.visitVarInsn(ILOAD, checkPosVar); // checkPos
          if (i > 0) {
            pushInt(mv, i); // offset
            mv.visitInsn(IADD); // checkPos + i
          }
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          mv.visitVarInsn(ISTORE, chVar); // Store in chVar

          // Check if character matches the charset
          generateCharSetCheck(mv, assertion.charSets.get(i), chVar, mismatch);
        }

        // All characters matched
        if (assertion.isPositive()) {
          // Positive: success (continue)
        } else {
          // Negative: all matched means pattern found, so fail
          mv.visitJumpInsn(GOTO, assertionFailed);
        }
        mv.visitJumpInsn(GOTO, assertionPassed);

        // Mismatch label
        mv.visitLabel(mismatch);
        if (assertion.isPositive()) {
          // Positive: mismatch means fail
          mv.visitJumpInsn(GOTO, assertionFailed);
        } else {
          // Negative: mismatch means pattern not found, so succeed
        }
        mv.visitLabel(assertionPassed);
      }
      // Positive assertion continues here (already passed)
    } else {
      throw new IllegalStateException("Unknown assertion type: " + assertion.type);
    }
  }

  /**
   * Generate inline character checks (NO method calls to CharSet). Leaves execution at noMatch
   * label if character doesn't match.
   */
  private void generateCharSetCheck(MethodVisitor mv, CharSet chars, int chVar, Label noMatch) {
    if (chars.isSingleChar()) {
      // ch == 'x'
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) chars.getSingleChar());
      mv.visitJumpInsn(IF_ICMPNE, noMatch);
    } else if (chars.isSimpleRange()) {
      // ch >= start && ch <= end
      CharSet.Range range = chars.getSimpleRange();
      Label rangeCheckFail = new Label();

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
      // if (ch in any range) continue, else goto noMatch
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

  /**
   * Generates find() method: delegates to findFrom(input, 0).
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
   * Generates findFrom() method: search for match starting at given position. Uses first char skip
   * optimization + unrolled matchesAtStart for best performance.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * int findFrom(String input, int start) {
   *     if (input == null || start < 0 || start > input.length()) return -1;
   *     int len = input.length();
   *
   *     for (int tryPos = start; tryPos < len; tryPos++) {
   *         // ANCHOR OPTIMIZATION: For ^ or \A patterns, only try position 0
   *         if (hasStartAnchor && tryPos != 0) return -1;
   *
   *         // MULTILINE ^: Only try position 0 or after '\n'
   *         if (hasMultilineStart && tryPos != 0 && input.charAt(tryPos-1) != '\n') {
   *             continue;
   *         }
   *
   *         // FIRST CHAR SKIP: Skip positions where first char can't match
   *         char ch = input.charAt(tryPos);
   *         if (!validFirstChars.contains(ch)) continue;
   *
   *         // Try matching from this position using unrolled DFA
   *         if (matchesAtStart(input, tryPos)) {
   *             return tryPos;  // Found match
   *         }
   *     }
   *
   *     // Handle empty match at end for patterns like "a*"
   *     if (startState.isAccepting() && tryPos == len) return len;
   *
   *     return -1;  // No match found
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * Inline switch-based single-pass was tested but is ~24% slower due to lookupswitch overhead vs
   * JIT-optimized unrolled state machine.
   */
  public void generateFindFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

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
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 3); // len

    // int tryPos = start;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, 4); // tryPos

    // OPTIMIZATION: Compute valid first characters from DFA start state
    CharSet validFirstChars = computeValidFirstChars();

    Label outerLoopStart = new Label();
    Label outerLoopEnd = new Label();

    mv.visitLabel(outerLoopStart);
    // if (tryPos >= len) goto outerLoopEnd (need at least 1 char to test)
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitJumpInsn(IF_ICMPGE, outerLoopEnd);

    // ANCHOR OPTIMIZATION: Skip positions that can't match due to anchors
    // Use requiresStartAnchor (not hasStartAnchor) to handle alternations like (^foo|bar)
    // where one branch has anchor but pattern can still match at any position via other branch
    if (requiresStartAnchor || hasStringStartAnchor) {
      // Non-multiline ^ or \A: Only try position 0
      // if (tryPos != 0) return -1;
      Label validPosition = new Label();
      mv.visitVarInsn(ILOAD, 4);
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
      mv.visitVarInsn(ILOAD, 4);
      mv.visitJumpInsn(IFEQ, validPosition);

      // if (input.charAt(tryPos-1) == '\n') goto validPosition;
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 4);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(ISUB);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPEQ, validPosition);

      // Not a valid anchor position - skip to next
      mv.visitLabel(nextPosition);
      mv.visitIincInsn(4, 1); // tryPos++
      mv.visitJumpInsn(GOTO, outerLoopStart);

      mv.visitLabel(validPosition);
    }

    // OPTIMIZATION: First char skip - if char at tryPos cannot start a match, skip
    if (validFirstChars != null && !dfa.getStartState().accepting) {
      Label canStartMatch = new Label();

      // char ch = input.charAt(tryPos);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 4);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, 5); // ch in var 5

      // Check if ch can start a match - jump to canStartMatch if yes
      generateFirstCharFilterCheck(mv, validFirstChars, 5, canStartMatch);

      // Not a valid first char - skip to next position
      mv.visitIincInsn(4, 1); // tryPos++
      mv.visitJumpInsn(GOTO, outerLoopStart);

      mv.visitLabel(canStartMatch);
    }

    // Try DFA matching from tryPos using unrolled state machine
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesAtStart",
        "(Ljava/lang/String;I)Z",
        false);

    Label noMatchHere = new Label();
    mv.visitJumpInsn(IFEQ, noMatchHere);

    // Match found - return tryPos
    mv.visitVarInsn(ILOAD, 4);
    mv.visitInsn(IRETURN);

    mv.visitLabel(noMatchHere);
    mv.visitIincInsn(4, 1); // tryPos++
    mv.visitJumpInsn(GOTO, outerLoopStart);

    mv.visitLabel(outerLoopEnd);

    // Handle tryPos == len case (empty match at end if start state is accepting)
    if (dfa.getStartState().accepting) {
      mv.visitVarInsn(ILOAD, 4);
      mv.visitVarInsn(ILOAD, 3);
      Label notAtEnd = new Label();
      mv.visitJumpInsn(IF_ICMPNE, notAtEnd);
      mv.visitVarInsn(ILOAD, 4);
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
   * Generates helper method that checks if DFA matches and accepts at some point. This is used by
   * findFrom() to detect partial matches. Takes starting position to avoid substring allocations.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * // Similar to matches() but returns true when ANY accepting state is reached
   * boolean matchesAtStart(String input, int startPos) {
   *     if (input == null) return false;
   *     int pos = startPos;
   *
   *     // Check if start state is accepting (empty match)
   *     if (startState.isAccepting()) {
   *         if (!startState.assertions.isEmpty()) {
   *             if (!checkAssertions(pos)) goto continueMatching;
   *         }
   *         return true;  // Empty match valid
   *     }
   *
   *     // Unrolled DFA matching - returns true on first accepting state
   *     STATE_N:
   *         if (!state.assertions.isEmpty()) {
   *             if (!checkAssertions(pos)) return false;
   *         }
   *         if (state.isAccepting() && state != startState) {
   *             if (hasEndAnchor) {
   *                 // Must check anchor condition before accepting
   *                 if (!checkEndAnchor(pos)) goto continueMatching;
   *             }
   *             return true;  // Found match
   *         }
   *         // ... continue with transitions ...
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * Unlike matches(), this method accepts partial input matches. For example, pattern "foo" would
   * match at start of "foobar" (returns true after consuming "foo").
   */
  public void generateMatchesAtStartMethod(ClassWriter cw) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, "matchesAtStart", "(Ljava/lang/String;I)Z", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // Similar to matches() but accepts on any accept state, not just at end
    // This allows finding patterns that don't consume entire remaining input

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int pos = startPos; (parameter 2)
    int posVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // Check if start state is accepting BUT also check assertions first!
    if (dfa.getStartState().accepting) {
      // Must check assertions before accepting empty match
      if (!dfa.getStartState().assertionChecks.isEmpty()) {
        Label assertionFailed = new Label();
        for (AssertionCheck assertion : dfa.getStartState().assertionChecks) {
          generateAssertionCheck(mv, assertion, posVar, assertionFailed, allocator);
        }
        // Assertions passed - empty match is valid
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);

        // Assertions failed - continue to try non-empty match
        mv.visitLabel(assertionFailed);
      } else {
        // No assertions - empty match is valid (patterns like a*)
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
      }
    }

    // Create labels for all states
    Map<DFA.DFAState, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state, new Label());
    }

    // Generate code similar to matches() but accept on any accept state
    mv.visitLabel(stateLabels.get(dfa.getStartState()));
    generateMatchAtStartStateCode(mv, dfa.getStartState(), stateLabels, posVar, allocator);

    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state == dfa.getStartState()) continue;
      mv.visitLabel(stateLabels.get(state));
      generateMatchAtStartStateCode(mv, state, stateLabels, posVar, allocator);
    }

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Similar to generateStateCode but returns true on any accept state. */
  private void generateMatchAtStartStateCode(
      MethodVisitor mv,
      DFA.DFAState state,
      Map<DFA.DFAState, Label> stateLabels,
      int posVar,
      LocalVarAllocator allocator) {
    Label endOfInput = new Label();
    Label assertionFailed = new Label();

    // CRITICAL: Check assertions FIRST, before accepting!
    // Bug was here: previously returned true immediately for accepting states,
    // skipping assertion checks. Pattern a(?=bc) would match 'a' without checking (?=bc).
    if (!state.assertionChecks.isEmpty()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        generateAssertionCheck(mv, assertion, posVar, assertionFailed, allocator);
      }
    }

    // After assertions pass, check if this is an accepting state
    // If so, return true (we've matched successfully)
    // BUT: For patterns with end anchors, we must also check anchor conditions
    if (state.accepting && state != dfa.getStartState()) {
      if (hasEndAnchor || hasStringEndAnchor || hasStringEndAbsoluteAnchor || hasMultilineEnd) {
        // End anchor present - must check position before accepting
        Label continueMatching = new Label();

        // Allocate temp vars for anchor check
        int savedPosVar = allocator.allocate();
        int lenVar = allocator.allocate();

        // Get current position and length for anchor check
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ISTORE, savedPosVar); // Save pos temporarily

        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitVarInsn(ISTORE, lenVar); // Save length temporarily

        if (hasStringEndAbsoluteAnchor) {
          // \z: Accept only if pos == length (absolute end)
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitJumpInsn(IF_ICMPNE, continueMatching);

          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
        } else if (hasStringEndAnchor) {
          // \Z: Accept if pos == length OR (pos == length-1 AND charAt(pos) == '\n')
          Label accept = new Label();

          // if (pos == length) accept
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitJumpInsn(IF_ICMPEQ, accept);

          // if (pos == length-1 && charAt(pos) == '\n') accept
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(ISUB);
          mv.visitJumpInsn(IF_ICMPNE, continueMatching); // pos != length-1

          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, '\n');
          mv.visitJumpInsn(IF_ICMPNE, continueMatching); // Not '\n'

          mv.visitLabel(accept);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
        } else if (hasEndAnchor) {
          // Non-multiline $: Accept only if pos == length
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitJumpInsn(IF_ICMPNE, continueMatching);

          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
        } else { // hasMultilineEnd
          // Multiline $: Accept if pos == length OR charAt(pos) == '\n'
          Label accept = new Label();

          // if (pos == length) accept
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitJumpInsn(IF_ICMPEQ, accept);

          // if (pos < length && charAt(pos) == '\n') accept
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitVarInsn(ILOAD, lenVar);
          mv.visitJumpInsn(IF_ICMPGE, continueMatching); // pos >= length

          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, savedPosVar);
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
          pushInt(mv, '\n');
          mv.visitJumpInsn(IF_ICMPNE, continueMatching);

          mv.visitLabel(accept);
          mv.visitInsn(ICONST_1);
          mv.visitInsn(IRETURN);
        }

        // Anchor condition not met, continue matching
        mv.visitLabel(continueMatching);
      } else {
        // No end anchor - accept immediately
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IRETURN);
      }
    }

    // if (pos >= input.length()) goto endOfInput
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, endOfInput);

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Check transitions
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);
      mv.visitJumpInsn(GOTO, stateLabels.get(target));
      mv.visitLabel(nextCheck);
    }

    // No transition - reject
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // End of input - check if accepting
    mv.visitLabel(endOfInput);
    if (state.accepting) {
      mv.visitInsn(ICONST_1);
    } else {
      mv.visitInsn(ICONST_0);
    }
    mv.visitInsn(IRETURN);

    // Assertion failed - return false
    if (!state.assertionChecks.isEmpty()) {
      mv.visitLabel(assertionFailed);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
    }
  }

  /**
   * Generates match() method that returns MatchResult with group information. Uses inline group
   * tracking if DFA has group actions, otherwise simple path.
   *
   * <h3>Generated Algorithm (with groups)</h3>
   *
   * <pre>{@code
   * MatchResult match(String input) {
   *     // For Tagged DFA patterns, delegate to findMatch
   *     if (useTaggedDFA && groupCount > 0) {
   *         return findMatch(input);
   *     }
   *
   *     // For patterns with group actions, track inline
   *     if (groupCount > 0 && hasGroupActions()) {
   *         return matchWithGroupTracking(input);
   *     }
   *
   *     // Simple path: no groups or no group actions
   *     if (!matches(input)) return null;
   *
   *     // Create MatchResult with group 0 = entire match
   *     int[] starts = new int[groupCount + 1];
   *     int[] ends = new int[groupCount + 1];
   *     starts[0] = 0;
   *     ends[0] = input.length();
   *     for (int i = 1; i <= groupCount; i++) {
   *         starts[i] = -1;  // Unmatched group
   *         ends[i] = -1;
   *     }
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

    // If using Tagged DFA with groups, delegate to findMatch which uses tag-based tracking
    // This is necessary for optional groups where state-based group actions are incorrect
    if (useTaggedDFA && groupCount > 0) {
      // return findMatch(input);
      mv.visitVarInsn(ALOAD, 0); // this
      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitMethodInsn(
          INVOKEVIRTUAL,
          className.replace('.', '/'),
          "findMatch",
          "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
          false);
      mv.visitInsn(ARETURN);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
      return;
    }

    // Check for groups - if we have group actions or assertion groups, track them inline
    if (groupCount > 0 && (hasGroupActions() || hasAssertionGroups())) {
      // Create allocator: slots 0=this, 1=input
      LocalVarAllocator allocator = new LocalVarAllocator(2);
      generateMatchWithGroupTracking(mv, className, allocator);
      mv.visitMaxs(0, 0);
      mv.visitEnd();
      return;
    }

    // No groups or no group actions/assertion groups - use simple path

    // if (!matches(input)) return null;
    Label matchSuccess = new Label();
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitJumpInsn(IFNE, matchSuccess);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchSuccess);

    // new MatchResultImpl(input, new int[]{0, ...}, new int[]{input.length(), ...}, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // input

    // starts array - new int[groupCount + 1]
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);
    // Fill rest with -1
    for (int i = 1; i <= groupCount; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // ends array - new int[groupCount + 1]
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitInsn(IASTORE);
    // Fill rest with -1
    for (int i = 1; i <= groupCount; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // groupCount
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
   * Generates findMatch() method. Delegates to findMatchFrom(input, 0).
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
   * Generate findMatchFrom() method with Tagged DFA for group tracking. Uses tag array to record
   * group positions during DFA execution.
   */
  private void generateFindMatchFromMethodTagged(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Local variables:
    // 0: this
    // 1: input (String)
    // 2: start (int)
    // 3: matchStart (int)
    // 4: pos (int) - current position
    // 5: ch (int) - current character
    // 6: tags (int[]) - tag array
    // 7: longestPos (int) - longest accepting position (-1 = no match)
    // 8: savedTags (int[]) - snapshot of tags at longest match
    final int inputVar = 1;
    final int startVar = 2;
    final int matchStartVar = 3;
    final int posVar = 4;
    final int chVar = 5;
    final int tagsVar = 6;
    final int longestPosVar = 7;
    final int savedTagsVar = 8;

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, startVar);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, matchStartVar);

    // if (matchStart < 0) return null;
    Label foundStart = new Label();
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitJumpInsn(IFGE, foundStart);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(foundStart);

    // Allocate tag array: int[] tags = new int[2 * (groupCount + 1)];
    int tagCount = 2 * (groupCount + 1);
    pushInt(mv, tagCount);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, tagsVar);

    // Initialize all tags to -1
    for (int i = 0; i < tagCount; i++) {
      mv.visitVarInsn(ALOAD, tagsVar);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // Set group 0 start tag: tags[0] = matchStart;
    mv.visitVarInsn(ALOAD, tagsVar);
    pushInt(mv, 0);
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitInsn(IASTORE);

    // Process zero-width group actions at start state
    // This handles empty alternatives like (a|)b where group 1 is entered/exited at position 0
    DFA.DFAState startState = dfa.getStartState();
    for (DFA.GroupAction action : startState.groupActions) {
      // S: [] -> [A:tags, I:tagId, I:matchStart]
      mv.visitVarInsn(ALOAD, tagsVar);
      if (action.type == DFA.GroupAction.ActionType.ENTER) {
        // tags[2*groupId] = matchStart
        pushInt(mv, 2 * action.groupId);
      } else {
        // tags[2*groupId + 1] = matchStart
        pushInt(mv, 2 * action.groupId + 1);
      }
      mv.visitVarInsn(ILOAD, matchStartVar);
      // S: [A:tags, I:tagId, I:matchStart] -> []
      mv.visitInsn(IASTORE);
    }

    // int pos = matchStart;
    mv.visitVarInsn(ILOAD, matchStartVar);
    mv.visitVarInsn(ISTORE, posVar);

    // int longestPos = -1; (no match yet)
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, longestPosVar);

    // int[] savedTags = null;
    mv.visitInsn(ACONST_NULL);
    mv.visitVarInsn(ASTORE, savedTagsVar);

    // Generate inline DFA matching with tag updates
    generateTaggedDFAMatching(mv, inputVar, posVar, chVar, tagsVar, longestPosVar, savedTagsVar);

    // After DFA matching completes:
    // if (longestPos < 0) return null; (no match found)
    Label hasMatch = new Label();
    mv.visitVarInsn(ILOAD, longestPosVar);
    mv.visitJumpInsn(IFGE, hasMatch);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(hasMatch);

    // Set group 0 end tag: savedTags[1] = longestPos;
    mv.visitVarInsn(ALOAD, savedTagsVar);
    pushInt(mv, 1);
    mv.visitVarInsn(ILOAD, longestPosVar);
    mv.visitInsn(IASTORE);

    // Extract group positions from savedTags
    // int[] groupStarts = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    int groupStartsVar = 9;
    mv.visitVarInsn(ASTORE, groupStartsVar);

    // int[] groupEnds = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    int groupEndsVar = 10;
    mv.visitVarInsn(ASTORE, groupEndsVar);

    // Extract groups from tags: groupStarts[i] = savedTags[2*i], groupEnds[i] = savedTags[2*i+1]
    for (int i = 0; i <= groupCount; i++) {
      // groupStarts[i] = savedTags[2*i];
      mv.visitVarInsn(ALOAD, groupStartsVar);
      pushInt(mv, i);
      mv.visitVarInsn(ALOAD, savedTagsVar);
      pushInt(mv, 2 * i);
      mv.visitInsn(IALOAD);
      mv.visitInsn(IASTORE);

      // groupEnds[i] = savedTags[2*i+1];
      mv.visitVarInsn(ALOAD, groupEndsVar);
      pushInt(mv, i);
      mv.visitVarInsn(ALOAD, savedTagsVar);
      pushInt(mv, 2 * i + 1);
      mv.visitInsn(IALOAD);
      mv.visitInsn(IASTORE);
    }

    // return new MatchResultImpl(input, groupStarts, groupEnds, groupCount);
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, inputVar);
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

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate inline DFA matching with tag updates. Generates unrolled state machine where each
   * state emits tag operations.
   */
  private void generateTaggedDFAMatching(
      MethodVisitor mv,
      int inputVar,
      int posVar,
      int chVar,
      int tagsVar,
      int longestPosVar,
      int savedTagsVar) {
    // Create labels for all states
    Map<DFA.DFAState, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state, new Label());
    }

    Label exitLabel = new Label();

    // Allocate a temp variable for saving position before increment (for START tags)
    int preIncrementPosVar = savedTagsVar + 1;

    // Jump to start state
    mv.visitJumpInsn(GOTO, stateLabels.get(dfa.getStartState()));

    // Generate code for each state
    for (DFA.DFAState state : dfa.getAllStates()) {
      mv.visitLabel(stateLabels.get(state));

      // If this is an accepting state, save current position and tags
      if (state.accepting) {
        // longestPos = pos;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ISTORE, longestPosVar);

        // savedTags = tags.clone();
        mv.visitVarInsn(ALOAD, tagsVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
        mv.visitTypeInsn(CHECKCAST, "[I");
        mv.visitVarInsn(ASTORE, savedTagsVar);
      }

      // Check if we've reached end of input
      Label notAtEnd = new Label();
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
      mv.visitJumpInsn(IF_ICMPLT, notAtEnd);
      // At end of input - exit matching
      mv.visitJumpInsn(GOTO, exitLabel);
      mv.visitLabel(notAtEnd);

      // Read character: int ch = input.charAt(pos);
      mv.visitVarInsn(ALOAD, inputVar);
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      mv.visitVarInsn(ISTORE, chVar);

      // Save position BEFORE increment (for START tags): preIncrementPos = pos
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, preIncrementPosVar);

      // pos++
      mv.visitIincInsn(posVar, 1);

      // Generate transition checks with tag updates
      for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
        CharSet chars = entry.getKey();
        DFA.DFATransition transition = entry.getValue();

        Label nextCheck = new Label();
        generateCharSetCheck(mv, chars, chVar, nextCheck);

        // Character matches - update tags
        // Now both branches use consistent stack operations: ALOAD, pushInt, ILOAD, IASTORE
        for (DFA.TagOperation tagOp : transition.tagOps) {
          mv.visitVarInsn(ALOAD, tagsVar);
          pushInt(mv, tagOp.tagId);

          if (tagOp.type == DFA.TagOperation.ActionType.START) {
            // For START tags, use position BEFORE consuming character
            mv.visitVarInsn(ILOAD, preIncrementPosVar);
          } else {
            // For END tags, use position AFTER consuming character
            mv.visitVarInsn(ILOAD, posVar);
          }

          mv.visitInsn(IASTORE);
        }

        // Jump to target state
        mv.visitJumpInsn(GOTO, stateLabels.get(transition.target));

        mv.visitLabel(nextCheck);
      }

      // No transition matched - dead state, exit
      mv.visitJumpInsn(GOTO, exitLabel);
    }

    mv.visitLabel(exitLabel);
  }

  /**
   * Generates findMatchFrom() method. Uses greedy DFA matching to find the longest match and
   * extracts group information.
   *
   * <h3>Generated Algorithm (simple path)</h3>
   *
   * <pre>{@code
   * MatchResult findMatchFrom(String input, int start) {
   *     // Find match start position
   *     int matchStart = findFrom(input, start);
   *     if (matchStart < 0) return null;
   *
   *     // Use greedy DFA matching to find longest match end
   *     int longestEnd = findLongestMatchEnd(input, matchStart);
   *     if (longestEnd < 0) return null;
   *
   *     // Extract groups by matching the substring
   *     String matchedSubstring = input.substring(matchStart, longestEnd);
   *     MatchResult groupResult = match(matchedSubstring);
   *
   *     // Adjust group positions by adding matchStart offset
   *     int[] starts = new int[groupCount + 1];
   *     int[] ends = new int[groupCount + 1];
   *     for (int i = 0; i <= groupCount; i++) {
   *         int s = groupResult.start(i);
   *         int e = groupResult.end(i);
   *         starts[i] = (s == -1) ? -1 : s + matchStart;
   *         ends[i] = (e == -1) ? -1 : e + matchStart;
   *     }
   *
   *     return new MatchResultImpl(input, starts, ends, groupCount);
   * }
   * }</pre>
   *
   * <h3>Tagged DFA Path</h3>
   *
   * When using Tagged DFA for group tracking, tags are recorded during DFA execution:
   *
   * <pre>{@code
   * // Initialize tag array: int[] tags = new int[2 * (groupCount + 1)];
   * // tags[2*i] = group i start, tags[2*i+1] = group i end
   *
   * // During DFA execution, transitions record tags:
   * for (TagOperation tagOp : transition.tagOps) {
   *     if (tagOp.type == START) {
   *         tags[tagOp.tagId] = pos;  // Position before consuming char
   *     } else {
   *         tags[tagOp.tagId] = pos;  // Position after consuming char
   *     }
   * }
   * }</pre>
   */
  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    if (useTaggedDFA && groupCount > 0) {
      generateFindMatchFromMethodTagged(cw, className);
      return;
    }

    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "findMatchFrom",
            "(Ljava/lang/String;I)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
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

    // OPTIMIZATION: Use greedy DFA matching instead of trying all lengths (eliminates O(N²) loop)
    // Call a helper method that runs DFA greedily and returns longest match end
    // int longestEnd = findLongestMatchEnd(input, matchStart);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 3); // matchStart
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "findLongestMatchEnd",
        "(Ljava/lang/String;I)I",
        false);
    mv.visitVarInsn(ISTORE, 4); // longestEnd in var 4

    // if (longestEnd < 0) return null; (no match found)
    Label hasMatch = new Label();
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IFGE, hasMatch);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(hasMatch);

    // Extract groups by calling match() on the matched substring
    // String matchedSubstring = input.substring(matchStart, longestEnd);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 3); // matchStart
    mv.visitVarInsn(ILOAD, 4); // longestEnd
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
    mv.visitVarInsn(ASTORE, 6); // matchedSubstring

    // MatchResult groupResult = match(matchedSubstring);
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 6); // matchedSubstring
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "match",
        "(Ljava/lang/String;)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, 7); // groupResult

    // Adjust group positions by adding matchStart offset
    // int[] starts = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, 8); // starts array

    // int[] ends = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, 9); // ends array

    // Copy and adjust group positions
    for (int i = 0; i <= groupCount; i++) {
      // starts[i] = groupResult.start(i) + matchStart;
      mv.visitVarInsn(ALOAD, 8);
      pushInt(mv, i);
      mv.visitVarInsn(ALOAD, 7); // groupResult
      pushInt(mv, i);
      mv.visitMethodInsn(
          INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "start", "(I)I", true);
      // Only add offset if not -1
      Label skipStartAdjust = new Label();
      Label doneStartAdjust = new Label();
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, skipStartAdjust);
      mv.visitVarInsn(ILOAD, 3); // matchStart
      mv.visitInsn(IADD);
      mv.visitJumpInsn(GOTO, doneStartAdjust);
      mv.visitLabel(skipStartAdjust);
      // Stack already has -1 from DUP, no need to push again
      mv.visitLabel(doneStartAdjust);
      mv.visitInsn(IASTORE);

      // ends[i] = groupResult.end(i) + matchStart;
      mv.visitVarInsn(ALOAD, 9);
      pushInt(mv, i);
      mv.visitVarInsn(ALOAD, 7); // groupResult
      pushInt(mv, i);
      mv.visitMethodInsn(
          INVOKEINTERFACE, "com/datadoghq/reggie/runtime/MatchResult", "end", "(I)I", true);
      // Only add offset if not -1
      Label skipEndAdjust = new Label();
      Label doneEndAdjust = new Label();
      mv.visitInsn(DUP);
      mv.visitInsn(ICONST_M1);
      mv.visitJumpInsn(IF_ICMPEQ, skipEndAdjust);
      mv.visitVarInsn(ILOAD, 3); // matchStart
      mv.visitInsn(IADD);
      mv.visitJumpInsn(GOTO, doneEndAdjust);
      mv.visitLabel(skipEndAdjust);
      // Stack already has -1 from DUP, no need to push again
      mv.visitLabel(doneEndAdjust);
      mv.visitInsn(IASTORE);
    }

    // Create MatchResultImpl with adjusted positions
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // original input
    mv.visitVarInsn(ALOAD, 8); // starts array
    mv.visitVarInsn(ALOAD, 9); // ends array
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
   * Generates findLongestMatchEnd() - helper for greedy DFA matching. Runs unrolled DFA from
   * startPos and returns the longest position where an accepting state was reached. Returns -1 if
   * no accepting state was ever reached.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * int findLongestMatchEnd(String input, int startPos) {
   *     int longestMatchEnd = -1;
   *     int pos = startPos;
   *
   *     // Unrolled DFA - each state tracks accepting positions
   *     STATE_N:
   *         // Record position if accepting
   *         if (state.isAccepting()) {
   *             longestMatchEnd = pos;
   *         }
   *
   *         // Check assertions
   *         if (!checkAssertions(pos)) return longestMatchEnd;
   *
   *         // Check end of input
   *         if (pos >= input.length()) return longestMatchEnd;
   *
   *         // Get next character and transition
   *         char ch = input.charAt(pos++);
   *         if (ch == 'a') goto STATE_X;
   *         if (ch >= 'b' && ch <= 'z') goto STATE_Y;
   *         // ... more transitions ...
   *
   *         // No transition matched - dead state
   *         return longestMatchEnd;
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * This enables O(N) greedy matching by tracking the furthest accepting state position during DFA
   * simulation. Used by findMatchFrom() and findBoundsFrom().
   */
  public void generateFindLongestMatchEndMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PRIVATE, "findLongestMatchEnd", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Create allocator: slots 0=this, 1=input, 2=startPos
    LocalVarAllocator allocator = new LocalVarAllocator(3);

    // int longestMatchEnd = -1; (no match found yet)
    int longestMatchEndVar = allocator.allocate();
    mv.visitInsn(ICONST_M1);
    mv.visitVarInsn(ISTORE, longestMatchEndVar);

    // int pos = startPos;
    int posVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, posVar);

    // Create labels for all states
    Map<DFA.DFAState, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state, new Label());
    }

    // Generate code for start state
    mv.visitLabel(stateLabels.get(dfa.getStartState()));
    generateGreedyStateCode(
        mv, dfa.getStartState(), stateLabels, posVar, longestMatchEndVar, allocator);

    // Generate code for all other states
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state == dfa.getStartState()) continue;
      mv.visitLabel(stateLabels.get(state));
      generateGreedyStateCode(mv, state, stateLabels, posVar, longestMatchEndVar, allocator);
    }

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate code for a single DFA state in greedy mode. Similar to generateStateCode() but tracks
   * longest accepting position instead of returning boolean.
   */
  private void generateGreedyStateCode(
      MethodVisitor mv,
      DFA.DFAState state,
      Map<DFA.DFAState, Label> stateLabels,
      int posVar,
      int longestMatchEndVar,
      LocalVarAllocator allocator) {
    Label endOfInput = new Label();
    Label assertionFailed = new Label();

    // If this is an accepting state, record current position
    if (state.accepting) {
      // longestMatchEnd = pos;
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, longestMatchEndVar);
    }

    // Generate assertion checks first (before consuming character)
    if (!state.assertionChecks.isEmpty()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        generateAssertionCheck(mv, assertion, posVar, assertionFailed, allocator);
      }
    }

    // if (pos >= input.length()) goto endOfInput
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, endOfInput);

    // Special check for \Z (STRING_END): if accepting and pos == length-1 and charAt(pos) == '\n',
    // record and return
    if (state.accepting && hasStringEndAnchor) {
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

      // Both conditions met - record position and return
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, longestMatchEndVar);
      mv.visitVarInsn(ILOAD, longestMatchEndVar);
      mv.visitInsn(IRETURN);

      mv.visitLabel(notStringEnd);
    }

    // char ch = input.charAt(pos);
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate transition checks
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, chVar, nextCheck);

      // Match found - jump to target state
      mv.visitJumpInsn(GOTO, stateLabels.get(target));

      // No match - try next transition
      mv.visitLabel(nextCheck);
    }

    // No transition matched - dead state, return longestMatchEnd
    mv.visitVarInsn(ILOAD, longestMatchEndVar);
    mv.visitInsn(IRETURN);

    // Handle end of input - return longestMatchEnd
    mv.visitLabel(endOfInput);
    mv.visitVarInsn(ILOAD, longestMatchEndVar);
    mv.visitInsn(IRETURN);

    // Handle assertion failure
    mv.visitLabel(assertionFailed);
    mv.visitVarInsn(ILOAD, longestMatchEndVar);
    mv.visitInsn(IRETURN);
  }

  /**
   * Generates matchesBounded() method: fully unrolled DFA on bounded region. Matches against
   * input.subSequence(start, end) without creating a substring.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean matchesBounded(CharSequence input, int start, int end) {
   *     if (input == null) return false;
   *     int pos = start;
   *
   *     // Unrolled DFA - uses 'end' instead of input.length()
   *     STATE_N:
   *         // Check assertions (bounded version)
   *         if (!checkBoundedAssertions(pos, end)) return false;
   *
   *         // Check end of region
   *         if (pos >= end) return state.isAccepting();
   *
   *         // Get character via CharSequence.charAt (interface call)
   *         char ch = input.charAt(pos++);
   *
   *         // Check transitions
   *         if (ch == 'a') goto STATE_X;
   *         // ... more transitions ...
   *
   *         return false;  // No transition matched
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * Uses CharSequence interface for flexibility, but incurs interface call overhead
   * (INVOKEINTERFACE vs INVOKEVIRTUAL for String).
   */
  public void generateMatchesBoundedMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesBounded", "(Ljava/lang/CharSequence;II)Z", null, null);
    mv.visitCode();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int pos = start;
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ISTORE, 4); // pos in var 4

    // Create labels for all states
    Map<DFA.DFAState, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state, new Label());
    }

    // Generate code for start state
    mv.visitLabel(stateLabels.get(dfa.getStartState()));
    generateBoundedStateCode(mv, dfa.getStartState(), stateLabels, 4, 3);

    // Generate code for all other states
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state == dfa.getStartState()) continue;
      mv.visitLabel(stateLabels.get(state));
      generateBoundedStateCode(mv, state, stateLabels, 4, 3);
    }

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates matchBounded() method - returns MatchResult for bounded region matching. Matches
   * against input.subSequence(start, end) and returns MatchResult with group 0 bounds.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult matchBounded(CharSequence input, int start, int end) {
   *     // Use matchesBounded for the actual matching
   *     if (!matchesBounded(input, start, end)) return null;
   *
   *     // Create MatchResult with group 0 = matched region
   *     int[] starts = new int[groupCount + 1];
   *     int[] ends = new int[groupCount + 1];
   *     starts[0] = start;
   *     ends[0] = end;
   *     for (int i = 1; i <= groupCount; i++) {
   *         starts[i] = -1;  // Unmatched groups
   *         ends[i] = -1;
   *     }
   *
   *     return new MatchResultImpl(input.toString(), starts, ends, groupCount);
   * }
   * }</pre>
   *
   * <h3>Notes</h3>
   *
   * Currently does not track individual group boundaries - only group 0 is set. For full group
   * tracking in bounded matches, Tagged DFA would need adaptation.
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

    // If using Tagged DFA with groups, we'd ideally track group boundaries during matching
    // For now, fall back to simple path: check if matches, then create result

    // if (!matchesBounded(input, start, end)) return null;
    Label matchSuccess = new Label();
    mv.visitVarInsn(ALOAD, 0); // this
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesBounded",
        "(Ljava/lang/CharSequence;II)Z",
        false);
    mv.visitJumpInsn(IFNE, matchSuccess);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(matchSuccess);

    // new MatchResultImpl(input.toString(), new int[]{start, ...}, new int[]{end, ...}, groupCount)
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
    mv.visitInsn(DUP);

    // Convert CharSequence to String
    mv.visitVarInsn(ALOAD, 1); // input
    mv.visitMethodInsn(
        INVOKEINTERFACE, "java/lang/CharSequence", "toString", "()Ljava/lang/String;", true);

    // starts array - new int[groupCount + 1]
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 2); // start
    mv.visitInsn(IASTORE);
    // Fill rest with -1
    for (int i = 1; i <= groupCount; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // ends array - new int[groupCount + 1]
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitInsn(DUP);
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 3); // end
    mv.visitInsn(IASTORE);
    // Fill rest with -1
    for (int i = 1; i <= groupCount; i++) {
      mv.visitInsn(DUP);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // groupCount
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
   * Generate code for a single DFA state in bounded matching mode. Uses end parameter (var 3)
   * instead of input.length()
   */
  private void generateBoundedStateCode(
      MethodVisitor mv,
      DFA.DFAState state,
      Map<DFA.DFAState, Label> stateLabels,
      int posVar,
      int endVar) {
    Label endOfRegion = new Label();
    Label assertionFailed = new Label();

    // Generate assertion checks first (before consuming character)
    if (!state.assertionChecks.isEmpty()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        generateBoundedAssertionCheck(mv, assertion, posVar, endVar, assertionFailed);
      }
    }

    // if (pos >= end) goto endOfRegion
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, endVar);
    mv.visitJumpInsn(IF_ICMPGE, endOfRegion);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);
    mv.visitVarInsn(ISTORE, 5); // ch in var 5

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate transition checks
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, 5, nextCheck);

      // Match found - jump to target state
      mv.visitJumpInsn(GOTO, stateLabels.get(target));

      // No match - try next transition
      mv.visitLabel(nextCheck);
    }

    // No transition matched - reject
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // Handle end of region
    mv.visitLabel(endOfRegion);
    if (state.accepting) {
      mv.visitInsn(ICONST_1);
    } else {
      mv.visitInsn(ICONST_0);
    }
    mv.visitInsn(IRETURN);

    // Assertion failed label (if assertions were present)
    if (!state.assertionChecks.isEmpty()) {
      mv.visitLabel(assertionFailed);
      mv.visitInsn(ICONST_0);
      mv.visitInsn(IRETURN);
    }
  }

  /**
   * Generate assertion check for bounded matching. Similar to generateAssertionCheck but respects
   * end boundary.
   */
  private void generateBoundedAssertionCheck(
      MethodVisitor mv, AssertionCheck assertion, int posVar, int endVar, Label assertionFailed) {
    if (assertion.isLookahead()) {
      if (assertion.isLiteral) {
        String literal = assertion.literal;

        if (assertion.isPositive()) {
          // Positive lookahead: Check all chars match
          for (int i = 0; i < literal.length(); i++) {
            int peekOffset = i;

            // Bounds check: if (pos + offset >= end) goto assertionFailed
            mv.visitVarInsn(ILOAD, posVar);
            if (peekOffset > 0) {
              pushInt(mv, peekOffset);
              mv.visitInsn(IADD);
            }
            mv.visitVarInsn(ILOAD, endVar);
            mv.visitJumpInsn(IF_ICMPGE, assertionFailed);

            // char ch = input.charAt(pos + offset);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            if (peekOffset > 0) {
              pushInt(mv, peekOffset);
              mv.visitInsn(IADD);
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);

            // if (ch != literal.charAt(i)) goto assertionFailed
            pushInt(mv, (int) literal.charAt(i));
            mv.visitJumpInsn(IF_ICMPNE, assertionFailed);
          }
        } else {
          // Negative lookahead: pass if any char doesn't match
          Label assertionPassed = new Label();

          for (int i = 0; i < literal.length(); i++) {
            int peekOffset = i;

            // Bounds check: if (pos + offset >= end) goto assertionPassed
            mv.visitVarInsn(ILOAD, posVar);
            if (peekOffset > 0) {
              pushInt(mv, peekOffset);
              mv.visitInsn(IADD);
            }
            mv.visitVarInsn(ILOAD, endVar);
            mv.visitJumpInsn(IF_ICMPGE, assertionPassed);

            // char ch = input.charAt(pos + offset);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, posVar);
            if (peekOffset > 0) {
              pushInt(mv, peekOffset);
              mv.visitInsn(IADD);
            }
            mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "charAt", "(I)C", true);

            // if (ch != literal.charAt(i)) goto assertionPassed
            pushInt(mv, (int) literal.charAt(i));
            mv.visitJumpInsn(IF_ICMPNE, assertionPassed);
          }

          // All chars matched - negative assertion fails
          mv.visitJumpInsn(GOTO, assertionFailed);
          mv.visitLabel(assertionPassed);
        }
      }
    }
    // Lookbehind and other assertion types omitted for simplicity
  }

  /**
   * Generates findBoundsFrom() method - allocation-free alternative to findMatchFrom(). Uses a
   * single greedy DFA scan instead of trying all possible lengths. Stores match boundaries in the
   * provided int[] array instead of allocating MatchResult.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean findBoundsFrom(String input, int start, int[] bounds) {
   *     // Find match start position
   *     int matchStart = findFrom(input, start);
   *     if (matchStart < 0) return false;
   *
   *     // Greedy DFA scan: track last accepting position
   *     int pos = matchStart;
   *     int lastAcceptingPos = matchStart;
   *     int len = input.length();
   *
   *     // Unrolled DFA states
   *     STATE_N:
   *         // Record position if accepting (with skip optimization)
   *         if (state.isAccepting() && !skippableStates.contains(state)) {
   *             lastAcceptingPos = pos;
   *         }
   *
   *         // ... transitions and dead state handling ...
   *
   *     SCAN_COMPLETE:
   *     if (lastAcceptingPos == matchStart) return false;  // No match
   *
   *     // Store bounds (zero allocation)
   *     bounds[0] = matchStart;
   *     bounds[1] = lastAcceptingPos;
   *     return true;
   * }
   * }</pre>
   *
   * <h3>Skippable State Optimization</h3>
   *
   * For patterns like {@code a{30,}}, accepting states after the minimum (30) can skip the
   * lastAcceptingPos update if all their transitions lead to other accepting states. This
   * eliminates redundant ISTORE operations in monotonic acceptance regions.
   */
  public void generateFindBoundsFromMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "findBoundsFrom", "(Ljava/lang/String;I[I)Z", null, null);
    mv.visitCode();

    // int matchStart = findFrom(input, start);
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitVarInsn(ISTORE, 4); // matchStart

    // if (matchStart < 0) return false;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitLabel(found);

    // Greedy scan: run DFA forward, tracking last accepting position
    // int pos = matchStart;
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ISTORE, 5); // pos

    // int lastAcceptingPos = matchStart; (no match yet)
    mv.visitVarInsn(ILOAD, 4);
    mv.visitVarInsn(ISTORE, 6); // lastAcceptingPos

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 7); // len

    // Create labels for all states
    Map<DFA.DFAState, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state, new Label());
    }

    // Compute which accepting states can skip lastAcceptingPos updates (Phase 4 optimization)
    Set<DFA.DFAState> skippableStates = computeSkippableAcceptingStates();

    Label scanComplete = new Label();

    // Generate code for start state
    mv.visitLabel(stateLabels.get(dfa.getStartState()));
    generateGreedyStateCode(
        mv, dfa.getStartState(), stateLabels, 5, 7, 6, scanComplete, skippableStates);

    // Generate code for all other states
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state == dfa.getStartState()) continue;
      mv.visitLabel(stateLabels.get(state));
      generateGreedyStateCode(mv, state, stateLabels, 5, 7, 6, scanComplete, skippableStates);
    }

    mv.visitLabel(scanComplete);

    // if (lastAcceptingPos == matchStart) return false; (no match found)
    Label hasMatch = new Label();
    mv.visitVarInsn(ILOAD, 6);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IF_ICMPNE, hasMatch);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(hasMatch);

    // bounds[0] = matchStart;
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ILOAD, 4); // matchStart
    mv.visitInsn(IASTORE);

    // bounds[1] = longestEnd;
    mv.visitVarInsn(ALOAD, 3); // bounds array
    mv.visitInsn(ICONST_1);
    mv.visitVarInsn(ILOAD, 6); // longestEnd
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Compute which accepting states can skip the lastAcceptingPos update optimization.
   *
   * <p>An accepting state can skip the update if: 1. ALL its outgoing transitions lead to other
   * accepting states, AND 2. ALL incoming transitions come from other accepting states (not first
   * accepting state)
   *
   * <p>This ensures the first accepting state reached always updates lastAcceptingPos, but
   * subsequent states in a monotonic acceptance region can skip redundant updates.
   *
   * <p>Example: In a{30,}, states 31+ can skip, but state 30 (first accepting) must update.
   */
  private Set<DFA.DFAState> computeSkippableAcceptingStates() {
    Set<DFA.DFAState> skippable = new HashSet<>();

    // First, compute incoming edges for all states
    Map<DFA.DFAState, Set<DFA.DFAState>> incomingEdges = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      incomingEdges.putIfAbsent(state, new HashSet<>());
      for (DFA.DFATransition transition : state.transitions.values()) {
        incomingEdges.putIfAbsent(transition.target, new HashSet<>());
        incomingEdges.get(transition.target).add(state);
      }
    }

    for (DFA.DFAState state : dfa.getAllStates()) {
      if (!state.accepting) {
        continue; // Only consider accepting states
      }

      if (state.transitions.isEmpty()) {
        continue; // Terminal state - must update (last accepting position)
      }

      // Check if ALL outgoing transitions lead to accepting states
      boolean allOutgoingAccepting = true;
      for (DFA.DFATransition transition : state.transitions.values()) {
        if (!transition.target.accepting) {
          allOutgoingAccepting = false;
          break;
        }
      }

      if (!allOutgoingAccepting) {
        continue; // Has transitions to non-accepting states
      }

      // Check if ALL incoming transitions come from accepting states
      boolean allIncomingAccepting = true;
      Set<DFA.DFAState> incoming = incomingEdges.get(state);
      if (incoming != null) {
        for (DFA.DFAState source : incoming) {
          if (!source.accepting) {
            allIncomingAccepting = false;
            break;
          }
        }
      }

      // Only skip if both conditions met (not the first accepting state)
      if (allOutgoingAccepting && allIncomingAccepting) {
        skippable.add(state);
      }
    }

    return skippable;
  }

  /**
   * Generate code for a single DFA state in greedy scan mode. Updates lastAcceptingPos when in an
   * accepting state. Stops scanning when no transition matches or input ends.
   *
   * @param mv Method visitor
   * @param state Current DFA state
   * @param stateLabels Labels for all states
   * @param posVar Local variable holding current position
   * @param lenVar Local variable holding input length
   * @param lastAcceptingVar Local variable tracking last accepting position
   * @param scanComplete Label to jump to when scan is done
   * @param skippableStates Set of accepting states that can skip lastAcceptingPos update
   */
  private void generateGreedyStateCode(
      MethodVisitor mv,
      DFA.DFAState state,
      Map<DFA.DFAState, Label> stateLabels,
      int posVar,
      int lenVar,
      int lastAcceptingVar,
      Label scanComplete,
      Set<DFA.DFAState> skippableStates) {
    // If this is an accepting state, update lastAcceptingPos = pos (unless skippable)
    if (state.accepting && !skippableStates.contains(state)) {
      mv.visitVarInsn(ILOAD, posVar);
      mv.visitVarInsn(ISTORE, lastAcceptingVar);
    }

    // Handle assertions if present
    Label assertionFailed = new Label();
    if (!state.assertionChecks.isEmpty()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        generateGreedyAssertionCheck(mv, assertion, posVar, lenVar, assertionFailed);
      }
    }

    // if (pos >= len) goto scanComplete (end of input)
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ILOAD, lenVar);
    mv.visitJumpInsn(IF_ICMPGE, scanComplete);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, 8); // ch in var 8

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Generate transition checks
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;

      Label nextCheck = new Label();
      generateCharSetCheck(mv, chars, 8, nextCheck);

      // Match found - jump to target state
      mv.visitJumpInsn(GOTO, stateLabels.get(target));

      // No match - try next transition
      mv.visitLabel(nextCheck);
    }

    // No transition matched - stop scanning
    mv.visitJumpInsn(GOTO, scanComplete);

    // Assertion failed label (if assertions were present)
    if (!state.assertionChecks.isEmpty()) {
      mv.visitLabel(assertionFailed);
      mv.visitJumpInsn(GOTO, scanComplete);
    }
  }

  /**
   * Generate assertion check for greedy scan mode. Similar to generateBoundedAssertionCheck but
   * jumps to assertionFailed instead of returning false.
   */
  private void generateGreedyAssertionCheck(
      MethodVisitor mv, AssertionCheck assertion, int posVar, int lenVar, Label assertionFailed) {
    if (assertion.isLookahead()) {
      if (assertion.isLiteral) {
        String literal = assertion.literal;
        boolean positive = assertion.isPositive();

        Label assertionPassed = positive ? new Label() : assertionFailed;

        // Check bounds for entire literal
        for (int i = 0; i < literal.length(); i++) {
          int peekOffset = i;

          // Check if pos + peekOffset < len
          mv.visitVarInsn(ILOAD, posVar);
          if (peekOffset > 0) {
            pushInt(mv, peekOffset);
            mv.visitInsn(IADD);
          }
          mv.visitVarInsn(ILOAD, lenVar);
          if (positive) {
            mv.visitJumpInsn(IF_ICMPGE, assertionFailed);
          } else {
            mv.visitJumpInsn(IF_ICMPGE, assertionPassed);
          }

          // char ch = input.charAt(pos + peekOffset)
          mv.visitVarInsn(ALOAD, 1);
          mv.visitVarInsn(ILOAD, posVar);
          if (peekOffset > 0) {
            pushInt(mv, peekOffset);
            mv.visitInsn(IADD);
          }
          mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);

          // if (ch != literal.charAt(i)) goto assertionPassed/Failed
          pushInt(mv, (int) literal.charAt(i));
          if (positive) {
            mv.visitJumpInsn(IF_ICMPNE, assertionFailed);
          } else {
            mv.visitJumpInsn(IF_ICMPNE, assertionPassed);
          }
        }

        if (!positive) {
          // All chars matched - negative assertion fails
          mv.visitJumpInsn(GOTO, assertionFailed);
          mv.visitLabel(assertionPassed);
        }
      }
    }
    // Lookbehind and other assertion types omitted for simplicity
  }

  /**
   * Generate match() with inline group tracking - fully unrolled version. Structure: int[]
   * groupStarts = new int[groupCount + 1]; int[] groupEnds = new int[groupCount + 1]; Initialize
   * arrays to -1, groupStarts[0] = 0 int pos = 0; Generate unrolled state machine with group
   * actions before character consumption return new MatchResultImpl(input, groupStarts, groupEnds,
   * groupCount);
   */
  private void generateMatchWithGroupTracking(
      MethodVisitor mv, String className, LocalVarAllocator allocator) {
    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int pos = 0;
    int posVar = allocator.allocate();
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

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

    // Process group actions for start state (before any character consumption)
    // At the start, both pre-increment and post-increment positions are 0
    generateGroupActionsForState(
        mv, dfa.getStartState(), posVar, posVar, groupStartsVar, groupEndsVar);

    // Create labels for all states
    Map<DFA.DFAState, Label> stateLabels = new HashMap<>();
    for (DFA.DFAState state : dfa.getAllStates()) {
      stateLabels.put(state, new Label());
    }

    // Generate code for start state
    mv.visitLabel(stateLabels.get(dfa.getStartState()));
    generateStateCodeWithGroupTracking(
        mv, dfa.getStartState(), stateLabels, posVar, groupStartsVar, groupEndsVar, allocator);

    // Generate code for all other states
    for (DFA.DFAState state : dfa.getAllStates()) {
      if (state == dfa.getStartState()) continue;
      mv.visitLabel(stateLabels.get(state));
      generateStateCodeWithGroupTracking(
          mv, state, stateLabels, posVar, groupStartsVar, groupEndsVar, allocator);
    }
  }

  /**
   * Generate code for a single DFA state with group tracking. Processes group actions BEFORE
   * consuming the character.
   */
  private void generateStateCodeWithGroupTracking(
      MethodVisitor mv,
      DFA.DFAState state,
      Map<DFA.DFAState, Label> stateLabels,
      int posVar,
      int groupStartsVar,
      int groupEndsVar,
      LocalVarAllocator allocator) {
    Label endOfInput = new Label();
    Label assertionFailed = new Label();

    // Check assertions first (before consuming character)
    // Use group-aware assertion check to capture groups inside assertions
    if (!state.assertionChecks.isEmpty()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        generateAssertionCheckWithGroups(
            mv, assertion, posVar, assertionFailed, groupStartsVar, groupEndsVar, allocator);
      }
    }

    // Check if at end of input
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, endOfInput);

    // Get next character
    int chVar = allocator.allocate();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // Save position before incrementing (for ENTER group actions)
    int preIncrementPosVar = allocator.allocate();
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ISTORE, preIncrementPosVar);

    // Increment position
    mv.visitIincInsn(posVar, 1);

    // Check transitions and jump to target states
    for (Map.Entry<CharSet, DFA.DFATransition> entry : state.transitions.entrySet()) {
      CharSet chars = entry.getKey();
      DFA.DFAState target = entry.getValue().target;
      Label nextCheck = new Label();

      // Generate group actions for target state BEFORE jumping to it
      // This ensures group actions are processed at the right position
      generateGroupActionsForState(
          mv, target, preIncrementPosVar, posVar, groupStartsVar, groupEndsVar);

      generateCharSetCheck(mv, chars, chVar, nextCheck);
      mv.visitJumpInsn(GOTO, stateLabels.get(target));
      mv.visitLabel(nextCheck);
    }

    // No transition - return null (reject)
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    // End of input - check if accepting
    mv.visitLabel(endOfInput);
    if (state.accepting) {
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
    } else {
      // Not accepting - return null
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
    }

    // Assertion failed - return null
    if (!state.assertionChecks.isEmpty()) {
      mv.visitLabel(assertionFailed);
      mv.visitInsn(ACONST_NULL);
      mv.visitInsn(ARETURN);
    }
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

  /** Check if the DFA has any assertions with capturing groups inside them. */
  private boolean hasAssertionGroups() {
    for (DFA.DFAState state : dfa.getAllStates()) {
      for (AssertionCheck assertion : state.assertionChecks) {
        if (assertion.hasGroups()) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Generate bytecode to apply group actions for a specific state.
   *
   * @param mv Method visitor
   * @param state DFA state with group actions
   * @param preIncrementPosVar Variable holding position BEFORE consuming character (unused, kept
   *     for compatibility)
   * @param postIncrementPosVar Variable holding position AFTER consuming character (used for both
   *     ENTER and EXIT)
   * @param groupStartsVar Variable holding groupStarts array
   * @param groupEndsVar Variable holding groupEnds array
   */
  private void generateGroupActionsForState(
      MethodVisitor mv,
      DFA.DFAState state,
      int preIncrementPosVar,
      int postIncrementPosVar,
      int groupStartsVar,
      int groupEndsVar) {
    for (DFA.GroupAction action : state.groupActions) {
      if (action.type == DFA.GroupAction.ActionType.ENTER) {
        // groupStarts[groupId] = postIncrementPos (position AFTER consuming character);
        // We enter the group AFTER consuming the character that caused the transition
        mv.visitVarInsn(ALOAD, groupStartsVar);
        pushInt(mv, action.groupId);
        mv.visitVarInsn(ILOAD, postIncrementPosVar);
        mv.visitInsn(IASTORE);
      } else {
        // groupEnds[groupId] = postIncrementPos (position AFTER consuming character);
        mv.visitVarInsn(ALOAD, groupEndsVar);
        pushInt(mv, action.groupId);
        mv.visitVarInsn(ILOAD, postIncrementPosVar);
        mv.visitInsn(IASTORE);
      }
    }
  }

  /**
   * Generate bytecode to capture groups inside an assertion. Called after assertion check passes to
   * record group boundaries.
   *
   * <p>For lookahead (?=(a)) at position pos: groupStarts[groupNum] = pos + startOffset
   * groupEnds[groupNum] = pos + startOffset + length
   *
   * @param mv Method visitor
   * @param assertion The assertion check containing group info
   * @param posVar Variable holding current position
   * @param groupStartsVar Variable holding groupStarts array
   * @param groupEndsVar Variable holding groupEnds array
   */
  private void generateAssertionGroupCapture(
      MethodVisitor mv,
      AssertionCheck assertion,
      int posVar,
      int groupStartsVar,
      int groupEndsVar) {
    if (!assertion.hasGroups()) {
      return;
    }

    for (AssertionCheck.GroupCapture group : assertion.groups) {
      // S: [] -> [A:int[]]
      mv.visitVarInsn(ALOAD, groupStartsVar);
      // S: [A:int[]] -> [A:int[], I]
      pushInt(mv, group.groupNumber);

      // Calculate start position: pos + assertion.offset + group.startOffset
      // S: [A:int[], I] -> [A:int[], I, I]
      mv.visitVarInsn(ILOAD, posVar);
      if (assertion.offset + group.startOffset != 0) {
        // S: [A:int[], I, I] -> [A:int[], I, I, I]
        pushInt(mv, assertion.offset + group.startOffset);
        // S: [A:int[], I, I, I] -> [A:int[], I, I]
        mv.visitInsn(IADD);
      }
      // S: [A:int[], I, I] -> []
      mv.visitInsn(IASTORE);

      // S: [] -> [A:int[]]
      mv.visitVarInsn(ALOAD, groupEndsVar);
      // S: [A:int[]] -> [A:int[], I]
      pushInt(mv, group.groupNumber);

      // Calculate end position: pos + assertion.offset + group.startOffset + group.length
      // S: [A:int[], I] -> [A:int[], I, I]
      mv.visitVarInsn(ILOAD, posVar);
      int totalOffset = assertion.offset + group.startOffset + group.length;
      if (totalOffset != 0) {
        // S: [A:int[], I, I] -> [A:int[], I, I, I]
        pushInt(mv, totalOffset);
        // S: [A:int[], I, I, I] -> [A:int[], I, I]
        mv.visitInsn(IADD);
      }
      // S: [A:int[], I, I] -> []
      mv.visitInsn(IASTORE);
    }
  }

  /**
   * Generate assertion check with optional group capture. For positive assertions with groups,
   * captures group boundaries after check passes.
   *
   * @param mv Method visitor
   * @param assertion The assertion to check
   * @param posVar Variable holding current position
   * @param assertionFailed Label to jump to on failure
   * @param groupStartsVar Variable holding groupStarts array (-1 to skip capture)
   * @param groupEndsVar Variable holding groupEnds array (-1 to skip capture)
   * @param allocator Local variable allocator
   */
  private void generateAssertionCheckWithGroups(
      MethodVisitor mv,
      AssertionCheck assertion,
      int posVar,
      Label assertionFailed,
      int groupStartsVar,
      int groupEndsVar,
      LocalVarAllocator allocator) {
    // Generate the assertion check
    generateAssertionCheck(mv, assertion, posVar, assertionFailed, allocator);

    // If assertion passed and has groups, capture them (unless we're in an alternation
    // where the same group could be captured by a different branch)
    if (assertion.isPositive()
        && assertion.hasGroups()
        && groupStartsVar >= 0
        && groupEndsVar >= 0
        && !skipEagerAssertionGroupCapture) {
      generateAssertionGroupCapture(mv, assertion, posVar, groupStartsVar, groupEndsVar);
    }
  }
}
