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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Probe: does §3.4 transition-index tag-op replay diverge on loop patterns? */
public class RefuteProbeTest {
  static PrintWriter OUT;

  // Mirror of SubsetConstructorPriorityTest.simulateTaggedDfa = §3.4 replay.
  private int[] simulateTaggedDfa(DFA dfa, NFA nfa, String input) {
    int groupCount = nfa.getGroupCount();
    int[] tags = new int[2 * (groupCount + 1)];
    Arrays.fill(tags, -1);
    DFA.DFAState state = dfa.getStartState();
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
      if (trans == null) return null;
      for (DFA.TagOperation op : trans.tagOps) {
        tags[op.tagId] = (op.type == DFA.TagOperation.ActionType.START) ? i : i + 1;
      }
      state = trans.target;
    }
    return state.accepting ? tags : null;
  }

  private void probe(String pat, int groups, String input) throws Exception {
    RegexNode ast = new RegexParser().parse(pat);
    NFA nfa = new ThompsonBuilder().build(ast, groups);
    DFA dfa = new SubsetConstructor().buildDFA(nfa, true);
    int[] tags = simulateTaggedDfa(dfa, nfa, input);

    Matcher m = Pattern.compile(pat).matcher(input);
    boolean jdkMatch = m.matches();
    StringBuilder jdk = new StringBuilder();
    if (jdkMatch) {
      for (int g = 0; g <= groups; g++)
        jdk.append(" g" + g + "=[" + m.start(g) + "," + m.end(g) + ")");
    }
    StringBuilder tdfa = new StringBuilder();
    if (tags != null) {
      for (int g = 0; g <= groups; g++)
        tdfa.append(" g" + g + "=[" + tags[2 * g] + "," + tags[2 * g + 1] + ")");
    }
    OUT.println("PAT " + pat + " IN '" + input + "'");
    OUT.println("  JDK  match=" + jdkMatch + jdk);
    OUT.println("  TDFA accept=" + (tags != null) + tdfa);
    OUT.println();
  }

  @Test
  public void probeLoops() throws Exception {
    OUT = new PrintWriter("/tmp/refute-probe.txt");
    probe("(a+)(a*)", 2, "aaa");
    probe("(a*)(a*)", 2, "aaa");
    probe("(a)*", 1, "aaa");
    probe("(a|b)*", 1, "abab");
    probe("(a*)*", 1, "aaa");
    probe("((a)b)*", 2, "abab");
    // FINDING WITNESS: g1.start must be 0 (first entry), carried forward
    // through later identical state-set visits. Does last-iteration-wins
    // wrongly rebind g1.start to a later pos?
    probe("(a+)(a*)b", 2, "aaab");
    probe("(a+)b", 1, "aaab");
    probe("(a+)(b*)c", 2, "aaac");
    probe("(a+)(a+)b", 2, "aaaab");
    OUT.close();
    // carried-forward witness: g1 start set early, re-touched late
    probe("(a)b\\1", 1, "aba"); // backref - may not build, ignore failures
  }
}
