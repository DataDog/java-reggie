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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * C5.2 characterization probe: determines whether the C2 priority-correct tagged DFA, executed with
 * a scanning find() and a priority-cut accept rule, reproduces JDK group spans for the
 * alternation-boundary class. This is the Phase-D gate.
 *
 * <p>Outcome A: C2 DFA + priority-cut accept reproduces JDK spans for all probed patterns. Phase D
 * may retire the PatternAnalyzer line-765 decline by implementing this accept-cut as the DFA
 * execution strategy for alternation-boundary patterns.
 */
class TaggedDfaFindSpanProbeTest {

  /** Returns int[4] = [matchStart, matchEnd, group1Start, group1End] via java.util.regex. */
  private int[] jdkFind(String pattern, String input) {
    Matcher m = Pattern.compile(pattern).matcher(input);
    if (!m.find()) {
      return null;
    }
    return new int[] {
      m.start(), m.end(), m.start(1) < 0 ? -1 : m.start(1), m.end(1) < 0 ? -1 : m.end(1)
    };
  }

  /**
   * Scans like find(): for start = 0..input.length(), tries the tagged DFA from that start
   * position. Priority-cut accept rule: when a transition leads to an accepting state, commit
   * immediately without consuming further input.
   *
   * @return int[4] = [matchStart, matchEnd, group1Start, group1End], or null if no match.
   */
  private int[] simulateTaggedDfaFind(DFA dfa, NFA nfa, String input) {
    int groupCount = nfa.getGroupCount();
    int tagLen = 2 * (groupCount + 1);

    for (int start = 0; start <= input.length(); start++) {
      int[] tags = new int[tagLen];
      Arrays.fill(tags, -1);

      DFA.DFAState state = dfa.getStartState();

      for (DFA.GroupAction a : state.groupActions) {
        int idx = (a.type == DFA.GroupAction.ActionType.ENTER) ? 2 * a.groupId : 2 * a.groupId + 1;
        tags[idx] = start;
      }

      boolean failed = false;
      int end = start;
      for (int i = start; i < input.length(); i++) {
        char c = input.charAt(i);
        DFA.DFATransition trans = null;
        for (Map.Entry<CharSet, DFA.DFATransition> e : state.transitions.entrySet()) {
          if (e.getKey().contains(c)) {
            trans = e.getValue();
            break;
          }
        }
        if (trans == null) {
          failed = true;
          break;
        }
        for (DFA.TagOperation op : trans.tagOps) {
          tags[op.tagId] = (op.type == DFA.TagOperation.ActionType.START) ? i : i + 1;
        }
        state = trans.target;
        end = i + 1;
        if (state.accepting) {
          return new int[] {start, end, tags[2], tags[3]};
        }
      }

      if (!failed && state.accepting) {
        return new int[] {start, end, tags[2], tags[3]};
      }
    }
    return null;
  }

  @Test
  void foOrFoo_inputFoo() throws Exception {
    int[] jdk = jdkFind("(fo|foo)", "foo");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(fo|foo)");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] probe = simulateTaggedDfaFind(dfa, nfa, "foo");
    assertNotNull(probe);
    assertArrayEquals(jdk, probe);
  }

  @Test
  void foOrFoo_inputXfooy() throws Exception {
    int[] jdk = jdkFind("(fo|foo)", "xfooy");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(fo|foo)");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] probe = simulateTaggedDfaFind(dfa, nfa, "xfooy");
    assertNotNull(probe);
    assertArrayEquals(jdk, probe);
  }

  @Test
  void aOrAb_inputAb() throws Exception {
    int[] jdk = jdkFind("(a|ab)", "ab");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(a|ab)");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] probe = simulateTaggedDfaFind(dfa, nfa, "ab");
    assertNotNull(probe);
    assertArrayEquals(jdk, probe);
  }

  @Test
  void aOrAb_inputXaby() throws Exception {
    int[] jdk = jdkFind("(a|ab)", "xaby");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(a|ab)");
    NFA nfa = new ThompsonBuilder().build(ast, 1);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] probe = simulateTaggedDfaFind(dfa, nfa, "xaby");
    assertNotNull(probe);
    assertArrayEquals(jdk, probe);
  }

  @Test
  void aOrAb_cOrEmpty_inputAbc() throws Exception {
    int[] jdk = jdkFind("(a|ab)(c|)", "abc");
    assertNotNull(jdk);

    RegexNode ast = new RegexParser().parse("(a|ab)(c|)");
    NFA nfa = new ThompsonBuilder().build(ast, 2);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);

    int[] probe = simulateTaggedDfaFind(dfa, nfa, "abc");
    assertNotNull(probe);
    assertArrayEquals(jdk, probe);
  }
}
