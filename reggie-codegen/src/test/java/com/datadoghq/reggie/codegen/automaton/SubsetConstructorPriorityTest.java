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
package com.datadoghq.reggie.codegen.automaton;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for correct priority-based tag-operation selection in SubsetConstructor.buildDFA(nfa,
 * true). The current implementation uses the lowest NFA state ID as a tiebreak when multiple NFA
 * threads compete for a group's END position. This is structurally wrong: the winning thread must
 * be the one with the highest epsilon-priority (leftmost-first / greedy), not the lowest state ID.
 *
 * <p>Tests 1 and 2 are RED under the current implementation. Tests 3 and 4 are GREEN (sanity checks
 * for unambiguous cases that must remain correct after any fix).
 */
class SubsetConstructorPriorityTest {

  /**
   * Simulates the tagged-DFA path used by DFA_*_WITH_GROUPS (useTaggedDFA=true). Returns the tags[]
   * array if the DFA accepts {@code input}, or null if it rejects.
   *
   * <p>Semantics (mirror of DFAUnrolledBytecodeGenerator tagged path):
   *
   * <ol>
   *   <li>Start-state GroupActions apply at matchStart=0: tags[2*g]=0 / tags[2*g+1]=0.
   *   <li>Per-transition TagOperations: START type uses pre-increment position (i), END uses
   *       post-increment position (i+1).
   * </ol>
   *
   * @return int[] where tags[2*g]=group-g-start, tags[2*g+1]=group-g-end; -1 means not captured.
   */
  private int[] simulateTaggedDfa(DFA dfa, NFA nfa, String input) {
    int groupCount = nfa.getGroupCount();
    int[] tags = new int[2 * (groupCount + 1)];
    Arrays.fill(tags, -1);

    DFA.DFAState state = dfa.getStartState();

    // Step 1: start-state GroupActions (matchStart=0)
    for (DFA.GroupAction a : state.groupActions) {
      int idx = (a.type == DFA.GroupAction.ActionType.ENTER) ? 2 * a.groupId : 2 * a.groupId + 1;
      tags[idx] = 0;
    }

    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      DFA.DFATransition trans = null;
      for (Map.Entry<CharSet, DFA.DFATransition> e : state.transitions.entrySet()) {
        if (e.getKey().contains(c)) {
          trans = e.getValue();
          break;
        }
      }
      if (trans == null) {
        return null; // dead — no transition for this character
      }

      // Step 2: TagOperations on this transition
      for (DFA.TagOperation op : trans.tagOps) {
        tags[op.tagId] = (op.type == DFA.TagOperation.ActionType.START) ? i : i + 1;
      }
      state = trans.target;
    }

    return state.accepting ? tags : null;
  }

  /**
   * Test 1 — bypass thread wins: group 1 end must be -1 (not captured).
   *
   * <p>Pattern {@code (.)?b} on input {@code "b"}: the dot matches 'b' (try-match path), but then a
   * second 'b' would be needed and is absent — so the try-match thread dies. The bypass thread
   * (which skipped the optional group) matches 'b' directly. Group 1 was never entered on the
   * winning path, so its end tag must remain -1.
   *
   * <p>Current bug: computeTagOperations emits an END tag for group 1 on the 'b' transition
   * (because ge1 is reachable from sd_x in the target DFA state). This is the wrong thread winning.
   */
  @Test
  void bypassThreadWins_groupEndIsUnset() throws Exception {
    RegexNode ast = new RegexParser().parse("(.)?b");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] tags = simulateTaggedDfa(dfa, nfa, "b");
    assertNotNull(tags, "DFA must accept 'b'");
    // Group 1 end must be -1 (not captured). Currently wrong (= 1).
    assertEquals(-1, tags[3], "bypass thread wins: group 1 end must be -1");
  }

  /**
   * Test 2 — left-branch exit must not override the winning thread's exit.
   *
   * <p>Pattern {@code (aa|a)a} on input {@code "aa"}: the left branch 'aa' would set group 1 end at
   * position 2, but the right branch 'a' wins (Perl left-first greedy semantics: right branch exits
   * at position 1, then consumes the trailing 'a'). Group 1 must be "a" = [0,1).
   *
   * <p>Current bug: the DFA accepting state contains g1_exit_aa (lower NFA state ID). The lowest-ID
   * tiebreak picks it, emitting END=2 and overwriting the correct END=1.
   */
  @Test
  void leftBranchExitMustNotOverrideWinningThreadExit() throws Exception {
    RegexNode ast = new RegexParser().parse("(aa|a)a");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] tags = simulateTaggedDfa(dfa, nfa, "aa");
    assertNotNull(tags, "DFA must accept 'aa'");
    assertEquals(0, tags[2], "group 1 start must be 0");
    // Group 1 end must be 1 (right branch 'a' wins). Currently wrong (= 2).
    assertEquals(
        1, tags[3], "group 1 end must be 1 (right branch 'a' wins): currently wrong (= 2)");
  }

  /**
   * Test 3 — sanity: unambiguous group must produce the correct span.
   *
   * <p>Pattern {@code (a)b} on input {@code "ab"}: group 1 = "a" = [0,1). No priority ambiguity.
   * This must continue to pass after any fix.
   */
  @Test
  void unambiguousGroup_correctSpan() throws Exception {
    RegexNode ast = new RegexParser().parse("(a)b");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] tags = simulateTaggedDfa(dfa, nfa, "ab");
    assertNotNull(tags, "DFA must accept 'ab'");
    assertEquals(0, tags[2], "group 1 start must be 0");
    assertEquals(1, tags[3], "group 1 end must be 1");
  }

  /**
   * Test 4 — try-match thread wins when the optional char is available (greedy {@code ?}).
   *
   * <p>Pattern {@code (.)?b} on input {@code "xb"}: dot matches 'x' (try-match wins because 'x' is
   * consumed and then 'b' follows). Group 1 = "x" = [0,1). This must continue to pass after any
   * fix.
   */
  @Test
  void tryMatchThreadWins_groupSpanIsSet() throws Exception {
    RegexNode ast = new RegexParser().parse("(.)?b");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] tags = simulateTaggedDfa(dfa, nfa, "xb");
    assertNotNull(tags, "DFA must accept 'xb'");
    assertEquals(0, tags[2], "group 1 start must be 0");
    assertEquals(1, tags[3], "group 1 end must be 1");
  }
}
