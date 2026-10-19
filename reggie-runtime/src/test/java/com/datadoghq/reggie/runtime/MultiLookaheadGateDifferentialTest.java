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

import static com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy.DFA_UNROLLED_WITH_ASSERTIONS;
import static com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy.OPTIMIZED_NFA_WITH_LOOKAROUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Groupless patterns with two or more variable-width lookaheads: routed to the assertion DFA's
 * sub-DFA gate unless the construction refuses them, and checked against JDK on every input up to
 * length 5 across all APIs.
 */
public class MultiLookaheadGateDifferentialTest {

  static final String[] PATTERNS = {
    "(?=.*a)(?=.*b).{3,}",
    "(?s)(?=.*a)(?=.*b).{3,}",
    "(?=.*a)(?=.*b)x",
    "(?=.*a)(?=.*b)(?:x|xy)",
    "(?=.*a)(?=.*b).*",
    "(?=.*a)(?!.*c).+",
    "(?=.*a)(?=.*b)(?=.*c).",
    "(?=[ab]*a)(?=\\w*b)\\w{2,}",
    "x(?=.*a)(?=.*b).",
    "(?m)^(?=.*a)(?=.*b).*$",
    "(?=.*a)(?=.*b).{3,}|c"
  };

  /**
   * Patterns the sub-DFA gate must refuse (priority conflict, partial multiline ^, alternation).
   */
  static final List<String> GATE_REFUSED =
      List.of("(?=.*a)(?=.*b)(?:x|xy)", "(?m)^(?=.*a)(?=.*b).*$", "(?=.*a)(?=.*b).{3,}|c");

  @Test
  void routes() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String p : PATTERNS) {
      MatchingStrategy expected =
          GATE_REFUSED.contains(p) ? OPTIMIZED_NFA_WITH_LOOKAROUND : DFA_UNROLLED_WITH_ASSERTIONS;
      MatchingStrategy s = StrategyCorrectnessMetaTest.routeOf(p);
      if (s != expected) wrong.add(p + " -> " + s);
    }
    assertEquals(List.of(), wrong);
  }

  @Test
  void agreesWithJdk() {
    List<String> inputs =
        TrailingLookaheadDifferentialTest.inputs(new char[] {'a', 'b', 'c', 'x', '\n'}, 5);
    List<String> failures = new ArrayList<>();
    for (String p : PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : inputs) {
        String f = TrailingLookaheadDifferentialTest.divergence(r, jdk, in);
        if (f != null) {
          failures.add("/" + p + "/ on \"" + in.replace("\n", "\\n") + "\": " + f);
          break;
        }
      }
    }
    assertEquals(List.of(), failures);
  }
}
