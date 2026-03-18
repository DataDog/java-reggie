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

import com.datadoghq.reggie.codegen.automaton.CharSet;
import com.datadoghq.reggie.codegen.automaton.NFA;
import java.util.HashMap;
import java.util.Map;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

/**
 * Generates bytecode for OnePass (unambiguous) patterns. OnePass patterns have a single
 * deterministic path through the NFA, allowing for simple state machine implementation without
 * BitSet overhead.
 *
 * <h3>Pattern Types</h3>
 *
 * Handles patterns that are "unambiguous" - at each position, there's at most one possible next
 * state. Examples: {@code abc}, {@code [a-z]+}, {@code \d{3}-\d{4}}.
 *
 * <h3>Generated Algorithm</h3>
 *
 * <pre>{@code
 * // For pattern [a-z]+\d+
 * boolean matches(String input) {
 *     if (input == null) return false;
 *     int state = startStateId;
 *     int pos = 0;
 *
 *     while (true) {
 *         // Process epsilon chain (group markers, anchors)
 *         state = processEpsilonChain(state, pos);
 *
 *         // Check end of input
 *         if (pos >= input.length()) break;
 *
 *         // Get next character
 *         char ch = input.charAt(pos++);
 *
 *         // Single-path transition (switch on state)
 *         switch (state) {
 *             case 0:
 *                 if (ch >= 'a' && ch <= 'z') { state = 1; continue; }
 *                 return false;
 *             case 1:
 *                 if (ch >= 'a' && ch <= 'z') { state = 1; continue; }
 *                 if (ch >= '0' && ch <= '9') { state = 2; continue; }
 *                 return false;
 *             // ...
 *         }
 *     }
 *
 *     // Final epsilon chain and acceptance check
 *     state = processEpsilonChain(state, pos);
 *     return isAccepting(state);
 * }
 * }</pre>
 *
 * <h3>Key Optimizations</h3>
 *
 * <ul>
 *   <li><b>Single int state</b>: No BitSet - just one state variable
 *   <li><b>Direct group tracking</b>: Arrays without state copying
 *   <li><b>Switch transitions</b>: O(1) state lookup
 *   <li><b>Inline epsilon chains</b>: Group markers processed inline
 * </ul>
 */
public class OnePassBytecodeGenerator {

  private final NFA nfa;
  private final int groupCount;
  private final boolean hasMultilineStart;
  private final boolean hasStartAnchor;
  private final boolean hasStringStartAnchor;
  private final boolean hasEndAnchor;
  private final boolean hasMultilineEnd;
  private final boolean hasStringEndAnchor;
  private final boolean hasStringEndAbsoluteAnchor;

  public OnePassBytecodeGenerator(NFA nfa) {
    this.nfa = nfa;
    this.groupCount = nfa.getGroupCount();
    this.hasMultilineStart = nfa.hasMultilineStartAnchor();
    this.hasStartAnchor = nfa.hasStartAnchor();
    this.hasStringStartAnchor = nfa.hasStringStartAnchor();
    this.hasEndAnchor = nfa.hasEndAnchor();
    this.hasMultilineEnd = nfa.hasMultilineEndAnchor();
    this.hasStringEndAnchor = nfa.hasStringEndAnchor();
    this.hasStringEndAbsoluteAnchor = nfa.hasStringEndAbsoluteAnchor();
  }

  /**
   * Generates matches() method using simple single-state state machine. Processes epsilon chains
   * inline between character transitions.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * boolean matches(String input) {
   *     if (input == null) return false;
   *     int state = startStateId;
   *     int pos = 0;
   *
   *     while (pos < input.length()) {
   *         state = processEpsilonChain(state, pos);
   *         char ch = input.charAt(pos++);
   *         state = transition(state, ch);  // Single-path
   *         if (state == DEAD) return false;
   *     }
   *
   *     state = processEpsilonChain(state, pos);
   *     return isAccepting(state);
   * }
   * }</pre>
   */
  public void generateMatchesMethod(ClassWriter cw, String className) {
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "matches", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();

    // Slot 0 = this, slot 1 = input
    int inputVar = 1;
    LocalVarAllocator allocator = new LocalVarAllocator(2);
    int stateVar = allocator.allocate();
    int posVar = allocator.allocate();
    int chVar = allocator.allocate();

    // if (input == null) return false;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(notNull);

    // int state = startState.id;
    pushInt(mv, nfa.getStartState().id);
    mv.visitVarInsn(ISTORE, stateVar);

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, posVar);

    // Main loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Process epsilon chain (group markers, anchors)
    generateEpsilonChainProcessing(mv, stateVar, posVar, inputVar, null, null);

    // Check end of input
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, inputVar);
    mv.visitVarInsn(ILOAD, posVar);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, chVar);

    // pos++;
    mv.visitIincInsn(posVar, 1);

    // Process character transition (updates state)
    Label noTransition = new Label();
    generateCharacterTransitions(mv, stateVar, chVar, loopStart, noTransition);

    // No transition found - fail
    mv.visitLabel(noTransition);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    // End of input - process final epsilon chain and check acceptance
    mv.visitLabel(loopEnd);
    generateEpsilonChainProcessing(mv, stateVar, posVar, inputVar, null, null);

    // Check if in accept state
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      Label notThis = new Label();
      mv.visitVarInsn(ILOAD, stateVar);
      pushInt(mv, acceptState.id);
      mv.visitJumpInsn(IF_ICMPNE, notThis);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(IRETURN);
      mv.visitLabel(notThis);
    }

    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generates match() method that returns MatchResult with group information. Tracks group
   * boundaries during single-path NFA simulation.
   *
   * <h3>Generated Algorithm</h3>
   *
   * <pre>{@code
   * MatchResult match(String input) {
   *     if (input == null) return null;
   *     int state = startStateId;
   *     int pos = 0;
   *     int[] starts = new int[groupCount + 1];  // All -1 initially
   *     int[] ends = new int[groupCount + 1];
   *
   *     while (pos < input.length()) {
   *         // Epsilon chain updates group boundaries
   *         state = processEpsilonChain(state, pos, starts, ends);
   *         char ch = input.charAt(pos++);
   *         state = transition(state, ch);
   *         if (state == DEAD) return null;
   *     }
   *
   *     state = processEpsilonChain(state, pos, starts, ends);
   *     if (!isAccepting(state)) return null;
   *
   *     starts[0] = 0;
   *     ends[0] = input.length();
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

    // if (input == null) return null;
    Label notNull = new Label();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitJumpInsn(IFNONNULL, notNull);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);
    mv.visitLabel(notNull);

    // int[] groupStarts = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, 2);

    // int[] groupEnds = new int[groupCount + 1];
    pushInt(mv, groupCount + 1);
    mv.visitIntInsn(NEWARRAY, T_INT);
    mv.visitVarInsn(ASTORE, 3);

    // Initialize arrays to -1
    for (int i = 0; i <= groupCount; i++) {
      mv.visitVarInsn(ALOAD, 2);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);

      mv.visitVarInsn(ALOAD, 3);
      pushInt(mv, i);
      mv.visitInsn(ICONST_M1);
      mv.visitInsn(IASTORE);
    }

    // groupStarts[0] = 0;
    mv.visitVarInsn(ALOAD, 2);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IASTORE);

    // int state = startState.id;
    pushInt(mv, nfa.getStartState().id);
    mv.visitVarInsn(ISTORE, 4); // state in var 4

    // int pos = 0;
    mv.visitInsn(ICONST_0);
    mv.visitVarInsn(ISTORE, 5); // pos in var 5

    // Main loop
    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // Process epsilon chain with group tracking
    generateEpsilonChainProcessing(mv, 4, 5, 1, 2, 3);

    // Check end of input
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitJumpInsn(IF_ICMPGE, loopEnd);

    // char ch = input.charAt(pos);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
    mv.visitVarInsn(ISTORE, 6); // ch in var 6

    // pos++;
    mv.visitIincInsn(5, 1);

    // Process character transition
    Label noTransition = new Label();
    generateCharacterTransitions(mv, 4, 6, loopStart, noTransition);

    // No transition - fail
    mv.visitLabel(noTransition);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    // End of input - process final epsilon chain
    mv.visitLabel(loopEnd);
    generateEpsilonChainProcessing(mv, 4, 5, 1, 2, 3);

    // Check if in accept state
    Label notAccepting = new Label();
    for (NFA.NFAState acceptState : nfa.getAcceptStates()) {
      Label notThis = new Label();
      mv.visitVarInsn(ILOAD, 4);
      pushInt(mv, acceptState.id);
      mv.visitJumpInsn(IF_ICMPNE, notThis);

      // Set groupEnds[0] = pos;
      mv.visitVarInsn(ALOAD, 3);
      mv.visitInsn(ICONST_0);
      mv.visitVarInsn(ILOAD, 5);
      mv.visitInsn(IASTORE);

      // Return new MatchResultImpl(input, groupStarts, groupEnds, groupCount)
      mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/MatchResultImpl");
      mv.visitInsn(DUP);
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ALOAD, 2);
      mv.visitVarInsn(ALOAD, 3);
      pushInt(mv, groupCount);
      mv.visitMethodInsn(
          INVOKESPECIAL,
          "com/datadoghq/reggie/runtime/MatchResultImpl",
          "<init>",
          "(Ljava/lang/String;[I[II)V",
          false);
      mv.visitInsn(ARETURN);

      mv.visitLabel(notThis);
    }

    mv.visitLabel(notAccepting);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Process epsilon chain: follow epsilon transitions and track groups.
   *
   * @param stateVar variable holding current state ID
   * @param posVar variable holding current position
   * @param inputVar variable holding input string
   * @param groupStartsVar variable holding groupStarts array (null if not tracking)
   * @param groupEndsVar variable holding groupEnds array (null if not tracking)
   */
  private void generateEpsilonChainProcessing(
      MethodVisitor mv,
      int stateVar,
      int posVar,
      int inputVar,
      Integer groupStartsVar,
      Integer groupEndsVar) {
    Label epsilonLoopStart = new Label();
    Label epsilonLoopEnd = new Label();

    mv.visitLabel(epsilonLoopStart);

    // Generate switch on state for epsilon transitions
    Map<Integer, Label> stateLabelMap = new HashMap<>();
    for (NFA.NFAState state : nfa.getStates()) {
      if (!state.getEpsilonTransitions().isEmpty()
          || state.enterGroup != null
          || state.exitGroup != null
          || state.anchor != null) {
        stateLabelMap.put(state.id, new Label());
      }
    }

    if (stateLabelMap.isEmpty()) {
      mv.visitJumpInsn(GOTO, epsilonLoopEnd);
    } else {
      int[] keys = stateLabelMap.keySet().stream().mapToInt(i -> i).sorted().toArray();
      Label[] labels = new Label[keys.length];
      for (int i = 0; i < keys.length; i++) {
        labels[i] = stateLabelMap.get(keys[i]);
      }

      mv.visitVarInsn(ILOAD, stateVar);
      mv.visitLookupSwitchInsn(epsilonLoopEnd, keys, labels);

      for (int stateId : keys) {
        mv.visitLabel(stateLabelMap.get(stateId));
        NFA.NFAState state = nfa.getStates().get(stateId);

        // Track group entry (POSIX last-match: always update)
        if (groupStartsVar != null && state.enterGroup != null) {
          // groupStarts[enterGroup] = pos;
          mv.visitVarInsn(ALOAD, groupStartsVar);
          pushInt(mv, state.enterGroup);
          mv.visitVarInsn(ILOAD, posVar);
          mv.visitInsn(IASTORE);
        }

        // Track group exit
        if (groupEndsVar != null && state.exitGroup != null) {
          // groupEnds[exitGroup] = pos;
          mv.visitVarInsn(ALOAD, groupEndsVar);
          pushInt(mv, state.exitGroup);
          mv.visitVarInsn(ILOAD, posVar);
          mv.visitInsn(IASTORE);
        }

        // Check anchors (failures should return false/null)
        if (state.anchor != null) {
          generateAnchorCheck(mv, state.anchor, posVar, inputVar, groupStartsVar == null);
        }

        // Follow epsilon transition (OnePass guarantees at most one)
        if (!state.getEpsilonTransitions().isEmpty()) {
          NFA.NFAState target = state.getEpsilonTransitions().get(0);
          pushInt(mv, target.id);
          mv.visitVarInsn(ISTORE, stateVar);
          mv.visitJumpInsn(GOTO, epsilonLoopStart);
        }

        mv.visitJumpInsn(GOTO, epsilonLoopEnd);
      }
    }

    mv.visitLabel(epsilonLoopEnd);
  }

  /**
   * Generate anchor check bytecode.
   *
   * @param mv method visitor
   * @param anchorType type of anchor to check
   * @param posVar variable holding current position
   * @param inputVar variable holding input string
   * @param returnBoolean true if failure should return false (matches method), false for null
   *     (match method)
   */
  private void generateAnchorCheck(
      MethodVisitor mv,
      NFA.AnchorType anchorType,
      int posVar,
      int inputVar,
      boolean returnBoolean) {
    Label passLabel = new Label();

    switch (anchorType) {
      case START:
        // if (pos == 0) pass; else fail;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitJumpInsn(IFEQ, passLabel);
        // Anchor failed - return false/null
        if (returnBoolean) {
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
        } else {
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
        }
        mv.visitLabel(passLabel);
        break;

      case END:
        // if (pos == input.length()) pass; else fail;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitJumpInsn(IF_ICMPEQ, passLabel);
        // Anchor failed - return false/null
        if (returnBoolean) {
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
        } else {
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
        }
        mv.visitLabel(passLabel);
        break;

      case START_MULTILINE:
        // if (pos == 0 || input.charAt(pos-1) == '\n') pass; else fail;
        // if (pos == 0) goto pass;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitJumpInsn(IFEQ, passLabel);

        // if (input.charAt(pos-1) == '\n') goto pass;
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        pushInt(mv, '\n');
        mv.visitJumpInsn(IF_ICMPEQ, passLabel);

        // Anchor failed - return false/null
        if (returnBoolean) {
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
        } else {
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
        }
        mv.visitLabel(passLabel);
        break;

      case END_MULTILINE:
        // if (pos == input.length() || input.charAt(pos) == '\n') pass; else fail;
        // if (pos == input.length()) goto pass;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitJumpInsn(IF_ICMPEQ, passLabel);

        // if (input.charAt(pos) == '\n') goto pass;
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        pushInt(mv, '\n');
        mv.visitJumpInsn(IF_ICMPEQ, passLabel);

        // Anchor failed - return false/null
        if (returnBoolean) {
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
        } else {
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
        }
        mv.visitLabel(passLabel);
        break;

      case WORD_BOUNDARY:
        // TODO: Implement word boundary check if needed
        break;

      case STRING_START:
        // \A - start of string (same as ^ but not affected by multiline)
        // if (pos == 0) pass; else fail;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitJumpInsn(IFEQ, passLabel);
        // Anchor failed - return false/null
        if (returnBoolean) {
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
        } else {
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
        }
        mv.visitLabel(passLabel);
        break;

      case STRING_END:
        // \Z - end of string or before final newline
        // if (pos == length || (pos == length-1 && charAt(pos) == '\n')) pass; else fail;
        // if (pos == length) goto pass;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitJumpInsn(IF_ICMPEQ, passLabel);

        // if (pos == length-1 && charAt(pos) == '\n') goto pass;
        Label checkNewline = new Label();
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitJumpInsn(IF_ICMPNE, checkNewline);

        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
        pushInt(mv, '\n');
        mv.visitJumpInsn(IF_ICMPEQ, passLabel);

        mv.visitLabel(checkNewline);
        // Anchor failed - return false/null
        if (returnBoolean) {
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
        } else {
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
        }
        mv.visitLabel(passLabel);
        break;

      case STRING_END_ABSOLUTE:
        // \z - absolute end of string
        // if (pos == length) pass; else fail;
        mv.visitVarInsn(ILOAD, posVar);
        mv.visitVarInsn(ALOAD, inputVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        mv.visitJumpInsn(IF_ICMPEQ, passLabel);
        // Anchor failed - return false/null
        if (returnBoolean) {
          mv.visitInsn(ICONST_0);
          mv.visitInsn(IRETURN);
        } else {
          mv.visitInsn(ACONST_NULL);
          mv.visitInsn(ARETURN);
        }
        mv.visitLabel(passLabel);
        break;

      case RESET_MATCH:
        // \K - reset match start (always succeeds)
        // TODO: Update groupStarts[0] to current position for proper \K semantics
        // For now, just pass through - \K patterns will match but report wrong bounds
        break;
    }
  }

  /** Generate character transition switch. */
  private void generateCharacterTransitions(
      MethodVisitor mv, int stateVar, int chVar, Label loopStart, Label noMatch) {
    // Generate switch on state
    int[] keys = new int[nfa.getStates().size()];
    Label[] labels = new Label[nfa.getStates().size()];
    for (int i = 0; i < nfa.getStates().size(); i++) {
      keys[i] = i;
      labels[i] = new Label();
    }

    mv.visitVarInsn(ILOAD, stateVar);
    mv.visitLookupSwitchInsn(noMatch, keys, labels);

    for (NFA.NFAState state : nfa.getStates()) {
      mv.visitLabel(labels[state.id]);

      if (state.getTransitions().isEmpty()) {
        mv.visitJumpInsn(GOTO, noMatch);
        continue;
      }

      // Generate character checks
      for (NFA.Transition trans : state.getTransitions()) {
        Label nextCheck = new Label();
        generateCharSetCheck(mv, trans.chars, chVar, nextCheck);

        // Match - update state and continue loop
        pushInt(mv, trans.target.id);
        mv.visitVarInsn(ISTORE, stateVar);
        mv.visitJumpInsn(GOTO, loopStart);

        mv.visitLabel(nextCheck);
      }

      mv.visitJumpInsn(GOTO, noMatch);
    }
  }

  /** Generate inline character set check. */
  private void generateCharSetCheck(MethodVisitor mv, CharSet chars, int chVar, Label noMatch) {
    if (chars.isSingleChar()) {
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) chars.getSingleChar());
      mv.visitJumpInsn(IF_ICMPNE, noMatch);
    } else if (chars.isSimpleRange()) {
      CharSet.Range range = chars.getSimpleRange();
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) range.start);
      mv.visitJumpInsn(IF_ICMPLT, noMatch);
      mv.visitVarInsn(ILOAD, chVar);
      pushInt(mv, (int) range.end);
      mv.visitJumpInsn(IF_ICMPGT, noMatch);
    } else {
      Label match = new Label();
      for (CharSet.Range range : chars.getRanges()) {
        Label nextRange = new Label();
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.start);
        mv.visitJumpInsn(IF_ICMPLT, nextRange);
        mv.visitVarInsn(ILOAD, chVar);
        pushInt(mv, (int) range.end);
        mv.visitJumpInsn(IF_ICMPLE, match);
        mv.visitLabel(nextRange);
      }
      mv.visitJumpInsn(GOTO, noMatch);
      mv.visitLabel(match);
    }
  }

  // Delegate remaining methods to simpler implementations
  public void generateFindMethod(ClassWriter cw, String className) {
    // Simple delegation to findFrom
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "find", "(Ljava/lang/String;)Z", null, null);
    mv.visitCode();
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitInsn(ICONST_0);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "findFrom", "(Ljava/lang/String;I)I", false);
    mv.visitInsn(ICONST_M1);
    Label returnTrue = new Label();
    mv.visitJumpInsn(IF_ICMPGT, returnTrue);
    mv.visitInsn(ICONST_0);
    mv.visitInsn(IRETURN);
    mv.visitLabel(returnTrue);
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);
    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  public void generateFindFromMethod(ClassWriter cw, String className) {
    // Scan through input trying to match at each position
    MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "findFrom", "(Ljava/lang/String;I)I", null, null);
    mv.visitCode();

    // Validation checks
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

    // int tryPos = start;
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ISTORE, 3);

    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 4);

    Label scanLoop = new Label();
    Label scanEnd = new Label();

    mv.visitLabel(scanLoop);

    // Check if tryPos <= len
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IF_ICMPGT, scanEnd);

    // ANCHOR OPTIMIZATION: Skip positions that can't match due to anchors
    if (hasStartAnchor || hasStringStartAnchor) {
      // Non-multiline ^ or \A: Only try position 0
      // if (tryPos != 0) return -1;
      Label validPosition = new Label();
      mv.visitVarInsn(ILOAD, 3);
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
      mv.visitVarInsn(ILOAD, 3);
      mv.visitJumpInsn(IFEQ, validPosition);

      // if (input.charAt(tryPos-1) == '\n') goto validPosition;
      mv.visitVarInsn(ALOAD, 1);
      mv.visitVarInsn(ILOAD, 3);
      mv.visitInsn(ICONST_1);
      mv.visitInsn(ISUB);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPEQ, validPosition);

      // Not a valid anchor position - skip to next
      mv.visitLabel(nextPosition);
      mv.visitIincInsn(3, 1); // tryPos++
      mv.visitJumpInsn(GOTO, scanLoop);

      mv.visitLabel(validPosition);
    }

    // Try different match lengths at tryPos, starting from zero-length match
    // int matchEnd = tryPos;
    mv.visitVarInsn(ILOAD, 3);
    // S: [tryPos] -> []
    mv.visitVarInsn(ISTORE, 5); // matchEnd in var 5

    Label lengthLoop = new Label();
    Label lengthLoopEnd = new Label();

    mv.visitLabel(lengthLoop);

    // while (matchEnd <= len)
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IF_ICMPGT, lengthLoopEnd);

    // if (matchesInRange(input, tryPos, matchEnd)) return tryPos;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 3); // tryPos
    mv.visitVarInsn(ILOAD, 5); // matchEnd
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesInRange",
        "(Ljava/lang/String;II)Z",
        false);

    Label noMatchThisLength = new Label();
    mv.visitJumpInsn(IFEQ, noMatchThisLength);

    // END ANCHOR VALIDATION: Check if matchEnd is at valid end position
    if (hasStringEndAbsoluteAnchor) {
      // \z: Only valid if matchEnd == len (most strict - absolute end only)
      // if (matchEnd != len) goto noMatchThisLength;
      mv.visitVarInsn(ILOAD, 5); // matchEnd
      mv.visitVarInsn(ILOAD, 4); // len
      mv.visitJumpInsn(IF_ICMPNE, noMatchThisLength);
    } else if (hasEndAnchor) {
      // Non-multiline $: Only valid if matchEnd == len
      // if (matchEnd != len) goto noMatchThisLength;
      mv.visitVarInsn(ILOAD, 5); // matchEnd
      mv.visitVarInsn(ILOAD, 4); // len
      mv.visitJumpInsn(IF_ICMPNE, noMatchThisLength);
    } else if (hasStringEndAnchor) {
      // \Z: Valid if matchEnd == len OR (matchEnd == len-1 && input.charAt(len-1) == '\n')
      // This matches at end or before final newline
      Label validEndPosition = new Label();

      // if (matchEnd == len) goto validEndPosition;
      mv.visitVarInsn(ILOAD, 5); // matchEnd
      mv.visitVarInsn(ILOAD, 4); // len
      mv.visitJumpInsn(IF_ICMPEQ, validEndPosition);

      // if (matchEnd == len-1 && input.charAt(len-1) == '\n') goto validEndPosition;
      mv.visitVarInsn(ILOAD, 5); // matchEnd
      mv.visitVarInsn(ILOAD, 4); // len
      mv.visitInsn(ICONST_1);
      mv.visitInsn(ISUB);
      mv.visitJumpInsn(IF_ICMPNE, noMatchThisLength); // if matchEnd != len-1, not valid

      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 4); // len
      mv.visitInsn(ICONST_1);
      mv.visitInsn(ISUB);
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPNE, noMatchThisLength);

      mv.visitLabel(validEndPosition);
    } else if (hasMultilineEnd) {
      // Multiline $: Valid if matchEnd == len OR input.charAt(matchEnd) == '\n'
      Label validEndPosition = new Label();

      // if (matchEnd == len) goto validEndPosition;
      mv.visitVarInsn(ILOAD, 5); // matchEnd
      mv.visitVarInsn(ILOAD, 4); // len
      mv.visitJumpInsn(IF_ICMPEQ, validEndPosition);

      // if (matchEnd < len && input.charAt(matchEnd) == '\n') goto validEndPosition;
      // First check matchEnd < len to avoid bounds exception
      mv.visitVarInsn(ILOAD, 5); // matchEnd
      mv.visitVarInsn(ILOAD, 4); // len
      mv.visitJumpInsn(IF_ICMPGE, noMatchThisLength); // If matchEnd >= len, not valid

      mv.visitVarInsn(ALOAD, 1); // input
      mv.visitVarInsn(ILOAD, 5); // matchEnd
      mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false);
      pushInt(mv, '\n');
      mv.visitJumpInsn(IF_ICMPNE, noMatchThisLength);

      mv.visitLabel(validEndPosition);
    }

    // Match found - return tryPos
    mv.visitVarInsn(ILOAD, 3);
    mv.visitInsn(IRETURN);

    mv.visitLabel(noMatchThisLength);
    // matchEnd++
    mv.visitIincInsn(5, 1);
    mv.visitJumpInsn(GOTO, lengthLoop);

    mv.visitLabel(lengthLoopEnd);

    // No match at this position, try next
    mv.visitIincInsn(3, 1); // tryPos++
    mv.visitJumpInsn(GOTO, scanLoop);

    mv.visitLabel(scanEnd);

    // No match found
    mv.visitInsn(ICONST_M1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

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

  public void generateFindMatchFromMethod(ClassWriter cw, String className) {
    // Use findFrom to locate match start, then find longest match using matchBounded
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
    mv.visitVarInsn(ISTORE, 3);

    // if (matchStart < 0) return null;
    Label found = new Label();
    mv.visitVarInsn(ILOAD, 3);
    mv.visitJumpInsn(IFGE, found);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(found);

    // Find longest match by trying all lengths WITHOUT substring allocation
    // Start from zero-length to support empty groups like ()
    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 4);

    // int matchEnd = matchStart (start with zero-length);
    // S: [] -> [matchStart]
    mv.visitVarInsn(ILOAD, 3);
    // S: [matchStart] -> []
    mv.visitVarInsn(ISTORE, 5);

    // int longestEnd = -1 (use -1 to indicate no match found);
    // S: [] -> [-1]
    mv.visitInsn(ICONST_M1);
    // S: [-1] -> []
    mv.visitVarInsn(ISTORE, 6);

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // while (matchEnd <= len)
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ILOAD, 4);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // Use matchesInRange() instead of substring allocation
    // if (matchesInRange(input, matchStart, matchEnd)) longestEnd = matchEnd;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesInRange",
        "(Ljava/lang/String;II)Z",
        false);

    Label noMatch = new Label();
    mv.visitJumpInsn(IFEQ, noMatch);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitVarInsn(ISTORE, 6);
    mv.visitLabel(noMatch);

    // matchEnd++
    mv.visitIincInsn(5, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // if (longestEnd < 0) return null;  // No match found (including zero-length)
    Label hasMatch = new Label();
    // S: [] -> [longestEnd]
    mv.visitVarInsn(ILOAD, 6);
    // S: [longestEnd] -> []
    mv.visitJumpInsn(IFGE, hasMatch);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(hasMatch);

    // Call matchInRange() for groups - single substring allocation at end
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitVarInsn(ILOAD, 6);
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchInRange",
        "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
        false);
    mv.visitVarInsn(ASTORE, 7);

    // if (result == null) return null;
    Label hasResult = new Label();
    mv.visitVarInsn(ALOAD, 7);
    mv.visitJumpInsn(IFNONNULL, hasResult);
    mv.visitInsn(ACONST_NULL);
    mv.visitInsn(ARETURN);

    mv.visitLabel(hasResult);

    // Adjust group positions by matchStart offset
    mv.visitTypeInsn(NEW, "com/datadoghq/reggie/runtime/HybridMatcher$OffsetMatchResult");
    mv.visitInsn(DUP);
    mv.visitVarInsn(ALOAD, 1); // original input
    mv.visitVarInsn(ALOAD, 7); // delegate result
    mv.visitVarInsn(ILOAD, 3); // offset
    mv.visitMethodInsn(
        INVOKESPECIAL,
        "com/datadoghq/reggie/runtime/HybridMatcher$OffsetMatchResult",
        "<init>",
        "(Ljava/lang/String;Lcom/datadoghq/reggie/runtime/MatchResult;I)V",
        false);
    mv.visitInsn(ARETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /**
   * Generate matchesInRange() helper method - matches substring without allocation. Matches
   * input[startPos..endPos) without creating substring.
   */
  public void generateMatchesInRangeMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(ACC_PUBLIC, "matchesInRange", "(Ljava/lang/String;II)Z", null, null);
    mv.visitCode();

    // Extract substring and delegate to matches()
    // This is still one allocation, but only when we find a candidate match
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
    mv.visitVarInsn(ASTORE, 4);

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 4);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, className.replace('.', '/'), "matches", "(Ljava/lang/String;)Z", false);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }

  /** Generate matchInRange() helper method - returns MatchResult for range. */
  public void generateMatchInRangeMethod(ClassWriter cw, String className) {
    MethodVisitor mv =
        cw.visitMethod(
            ACC_PUBLIC,
            "matchInRange",
            "(Ljava/lang/String;II)Lcom/datadoghq/reggie/runtime/MatchResult;",
            null,
            null);
    mv.visitCode();

    // Extract substring once and delegate to match()
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
    mv.visitVarInsn(ILOAD, 3);
    mv.visitMethodInsn(
        INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false);
    mv.visitVarInsn(ASTORE, 4);

    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 4);
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

  /**
   * Generate findBoundsFrom() method - allocation-free boundary detection. Returns match boundaries
   * in the provided int[] array instead of allocating MatchResult. Note: This implementation still
   * has O(N²) complexity for greedy quantifiers, but avoids MatchResult allocation for use in
   * replaceAll operations.
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
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 2);
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

    // Find longest match by trying all lengths WITHOUT substring allocation
    // Start from zero-length to support empty groups like ()
    // int len = input.length();
    mv.visitVarInsn(ALOAD, 1);
    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
    mv.visitVarInsn(ISTORE, 5); // len in var 5

    // int matchEnd = matchStart (start with zero-length);
    // S: [] -> [matchStart]
    mv.visitVarInsn(ILOAD, 4);
    // S: [matchStart] -> []
    mv.visitVarInsn(ISTORE, 6); // matchEnd in var 6

    // int longestEnd = -1 (use -1 to indicate no match found);
    // S: [] -> [-1]
    mv.visitInsn(ICONST_M1);
    // S: [-1] -> []
    mv.visitVarInsn(ISTORE, 7); // longestEnd in var 7

    Label loopStart = new Label();
    Label loopEnd = new Label();

    mv.visitLabel(loopStart);

    // while (matchEnd <= len)
    mv.visitVarInsn(ILOAD, 6);
    mv.visitVarInsn(ILOAD, 5);
    mv.visitJumpInsn(IF_ICMPGT, loopEnd);

    // Use matchesInRange() instead of substring allocation
    // if (matchesInRange(input, matchStart, matchEnd)) longestEnd = matchEnd;
    mv.visitVarInsn(ALOAD, 0);
    mv.visitVarInsn(ALOAD, 1);
    mv.visitVarInsn(ILOAD, 4); // matchStart
    mv.visitVarInsn(ILOAD, 6); // matchEnd
    mv.visitMethodInsn(
        INVOKEVIRTUAL,
        className.replace('.', '/'),
        "matchesInRange",
        "(Ljava/lang/String;II)Z",
        false);

    Label noMatch = new Label();
    mv.visitJumpInsn(IFEQ, noMatch);
    mv.visitVarInsn(ILOAD, 6);
    mv.visitVarInsn(ISTORE, 7); // longestEnd = matchEnd
    mv.visitLabel(noMatch);

    // matchEnd++
    mv.visitIincInsn(6, 1);
    mv.visitJumpInsn(GOTO, loopStart);

    mv.visitLabel(loopEnd);

    // if (longestEnd < 0) return false;  // No match found (including zero-length)
    Label hasMatch = new Label();
    // S: [] -> [longestEnd]
    mv.visitVarInsn(ILOAD, 7);
    // S: [longestEnd] -> []
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
    mv.visitVarInsn(ILOAD, 7);
    mv.visitInsn(IASTORE);

    // return true;
    mv.visitInsn(ICONST_1);
    mv.visitInsn(IRETURN);

    mv.visitMaxs(0, 0);
    mv.visitEnd();
  }
}
