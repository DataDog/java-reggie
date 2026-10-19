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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Engine-level (routing bypassed) JDK-differential check for multiline {@code ^}/{@code $},
 * non-multiline {@code $} and non-dotall {@code .} on input containing each java.util.regex line
 * terminator. Covers the runtime engines that evaluate these anchors themselves: PikeVM (thread
 * simulation and its lazy DFAs), the Laurikari tagged DFA (when eligible) and the backreference
 * backtracker.
 */
public class MultilineEngineDifferentialTest {

  private static final String[] PATTERNS = {
    "(?m)^(.*)$",
    "(?m)^(a*)",
    "(?m)^a*",
    "(?m)^\\n",
    "(?m)^(\\s)",
    "(?m)^([ab]+)",
    "(?m)^$",
    "(?m)(^a|b)",
    "(?m)(\\w)$",
    "(?m)^(\\w+)$",
    "(?m)^(?:a|b)x?",
    "(?m)(a.)$",
    "(?m)^(\\r?\\n)",
    "(?m)$(\\s)"
  };

  private static final String[] BACKREF_PATTERNS = {
    "(?m)^(\\w)\\1", "(?m)(\\w)\\1$", "(?m)^(.)\\1*$", "(?m)^(a*)\\1$", "(\\w)\\1$", "(a|b)\\1$"
  };

  private interface Factory {
    ReggieMatcher create(NFA nfa, String pattern, int groupCount);
  }

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    return new ThompsonBuilder().build(new RegexParser().parse(pattern), groupCount);
  }

  private static List<String> mismatches(String[] patterns, String engine, Factory factory)
      throws Exception {
    List<String> mismatches = new ArrayList<>();
    for (String p : patterns) {
      Pattern j = Pattern.compile(p);
      int groupCount = j.matcher("").groupCount();
      ReggieMatcher r = factory.create(nfa(p, groupCount), p, groupCount);
      if (r == null) {
        continue;
      }
      for (String in : DotLineTerminatorTest.inputs()) {
        String diff = DotLineTerminatorTest.diff(r, j, in);
        if (diff != null) {
          mismatches.add(
              engine + " /" + p + "/ on \"" + DotLineTerminatorTest.escape(in) + "\": " + diff);
        }
      }
    }
    return mismatches;
  }

  @Test
  void pikeVmAgreesWithJdk() throws Exception {
    assertEquals(List.of(), mismatches(PATTERNS, "pikevm", (n, p, g) -> new PikeVMMatcher(n, p)));
  }

  @Test
  void laurikariAgreesWithJdk() throws Exception {
    assertEquals(
        List.of(),
        mismatches(
            PATTERNS,
            "laurikari",
            (n, p, g) ->
                LaurikariEligibility.isEligible(n, false)
                    ? new LaurikariDfaMatcher(n, p, g)
                    : null));
  }

  @Test
  void backrefBacktrackAgreesWithJdk() throws Exception {
    assertEquals(
        List.of(),
        mismatches(BACKREF_PATTERNS, "backref", (n, p, g) -> new BackrefBacktrackMatcher(n, p)));
  }
}
