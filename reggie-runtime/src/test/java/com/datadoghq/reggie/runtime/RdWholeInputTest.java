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

import org.junit.jupiter.api.Test;

/**
 * The recursive-descent generator's choice-point code (compiled through {@link
 * RuntimeCompiler#compileRecursiveDescentForTest}) must give back inside the root concat until the
 * whole input is consumed (matches, match, matchInto), and findMatch must recover the groups with
 * the same end of input as the search. The oracle is the backtracker.
 */
class RdWholeInputTest {

  private static final String[][] CASES = {
    {"(a)?(?(1)|aa)", "aa"},
    {"(a)?(?(1)|aa)", "a"},
    {"(a)?(?(1)$|a)", "ab"},
    {"(a)?(?(1)$|a)", "a"},
    {"(a)?(?(1)b|a)", "ab"},
    {"(a)?(?(1)b|aa)", "aa"},
  };

  @Test
  void wholeInputAndReparseMatchBacktracker() throws Exception {
    for (String[] c : CASES) {
      String pattern = c[0];
      ReggieMatcher m = RuntimeCompiler.compileRecursiveDescentForTest(pattern);
      ReggieMatcher oracle = RuntimeCompiler.compileBackrefBacktrack(c[0], null);
      String in = c[1];
      String id = pattern + " on " + in;

      assertEquals(oracle.matches(in), m.matches(in), id);
      MatchResult om = oracle.match(in);
      MatchResult rm = m.match(in);
      assertEquals(om != null, rm != null, id);
      if (om != null) {
        assertGroups(om, rm, id);
      }
      int[] starts = new int[8];
      int[] ends = new int[8];
      assertEquals(om != null, m.matchInto(in, starts, ends), id);
      if (om != null) {
        for (int g = 0; g <= om.groupCount(); g++) {
          assertEquals(om.start(g), starts[g], id + " matchInto start(" + g + ")");
          assertEquals(om.end(g), ends[g], id + " matchInto end(" + g + ")");
        }
      }
      MatchResult of = oracle.findMatch(in);
      MatchResult rf = m.findMatch(in);
      assertEquals(of != null, rf != null, id);
      if (of != null) {
        assertGroups(of, rf, id);
      }
    }
  }

  private static void assertGroups(MatchResult expected, MatchResult actual, String id) {
    assertEquals(expected.groupCount(), actual.groupCount(), id);
    for (int g = 0; g <= expected.groupCount(); g++) {
      assertEquals(expected.start(g), actual.start(g), id + " start(" + g + ")");
      assertEquals(expected.end(g), actual.end(g), id + " end(" + g + ")");
    }
  }
}
