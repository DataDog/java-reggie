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

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for DETERMINISTIC_CHAIN_BYTECODE greedy loops followed by {@code \b}: the
 * loop's run can end where {@code \b} fails while an earlier boundary passes, when the loop class
 * has both word and non-word chars or the loop can stop before its first iteration.
 */
public class ChainWordBoundaryGiveBackTest {

  /** Pattern and the strategy it routes to, so each case keeps covering the intended engine. */
  private static final String[][] CASES = {
    {"a.*\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(?s)a.*\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(?s)a[^x]*\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"a.+\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(?s)a[^x]+\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"a\\s*\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"a\\w*\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"-\\w*\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"a\\W*\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"(a.*)\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"a(?:b.*)?\\b", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"a.*\\b-", "DETERMINISTIC_CHAIN_BYTECODE"},
    {"x(?:a|b )*\\b", "DETERMINISTIC_CHAIN_BYTECODE"}
  };

  private static List<String> inputs() {
    List<String> inputs = new ArrayList<>(DotLineTerminatorTest.inputs());
    for (String s :
        new String[] {
          "ab ", "ab  ", "a b", "a b ", "ab b", "a\n", "a ", "a  -", "ab -", "ab- ", "a-b -",
          "b ab ", "xb ", "xb b ", "xab a ", "xa b", "x"
        }) {
      inputs.add(s);
    }
    return inputs;
  }

  private static String[] patterns() {
    String[] patterns = new String[CASES.length];
    for (int i = 0; i < CASES.length; i++) {
      patterns[i] = CASES[i][0];
    }
    return patterns;
  }

  @Test
  void agreesWithJdk() {
    assertEquals(List.of(), DotLineTerminatorTest.mismatches(patterns(), inputs()));
  }

  @Test
  void routesAsIntended() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String[] c : CASES) {
      String route = StrategyCorrectnessMetaTest.routeOf(c[0]).name();
      if (!route.equals(c[1])) {
        wrong.add(c[0] + " -> " + route + " (expected " + c[1] + ")");
      }
    }
    assertEquals(List.of(), wrong);
  }
}
