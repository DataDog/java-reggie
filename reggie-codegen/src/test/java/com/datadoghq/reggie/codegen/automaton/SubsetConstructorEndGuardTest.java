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

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * An END/STRING_END-guarded path that shares a charset with an unguarded path: the guard can only
 * be kept exact when the charset contains no line terminator (the guarded path cannot consume it).
 */
class SubsetConstructorEndGuardTest {

  private static boolean diluted(String pattern, boolean withAssertions) throws Exception {
    NFA nfa = new ThompsonBuilder().build(new RegexParser().parse(pattern), 0);
    SubsetConstructor sc = new SubsetConstructor();
    DFA dfa = withAssertions ? sc.buildDFAWithAssertions(nfa) : sc.buildDFA(nfa);
    return dfa.isAnchorConditionDiluted();
  }

  @Test
  void endGuardedPathCannotConsumeNonLineTerminator() throws Exception {
    assertEquals(false, diluted("(?:[^a]|\\Z)ba", false));
    assertEquals(false, diluted("(?:[^a]|$)ba", false));
    assertEquals(false, diluted("(?!a)(?:[^a]|\\Z)ba{1,105}", true));
  }

  @Test
  void endGuardedPathSharingLineTerminatorIsDiluted() throws Exception {
    assertEquals(true, diluted("(?:[^a]|\\Z)\\n", false));
    assertEquals(true, diluted("(?!a)(?:[^a]|\\Z)\\n\\n?", true));
  }
}
