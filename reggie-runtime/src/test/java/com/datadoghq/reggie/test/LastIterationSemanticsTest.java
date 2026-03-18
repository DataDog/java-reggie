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
package com.datadoghq.reggie.test;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import org.junit.jupiter.api.Test;

public class LastIterationSemanticsTest {
  @Test
  public void testGreedyBacktrackingGroupCapture() {
    // Pattern: a([bc]*)(c+d)  on input 'abcd'
    // Expected: group 1='b', group 2='cd'
    // The greedy ([bc]*) initially matches 'bcd', then backtracks to 'b'
    // to allow (c+d) to match 'cd'
    ReggieMatcher m = Reggie.compile("a([bc]*)(c+d)");
    MatchResult result = m.match("abcd");

    System.out.println("Pattern: a([bc]*)(c+d) on 'abcd'");
    assertNotNull(result, "Should match");
    assertEquals("abcd", result.group(0), "Full match");
    System.out.println("  group 0: '" + result.group(0) + "'");
    System.out.println("  group 1: '" + result.group(1) + "' (expected 'b')");
    System.out.println("  group 2: '" + result.group(2) + "' (expected 'cd')");

    assertEquals("b", result.group(1), "Group 1 should capture 'b' after backtracking");
    assertEquals("cd", result.group(2), "Group 2 should capture 'cd'");
  }

  @Test
  public void testQuantifiedGroupLastIteration() {
    // Pattern: ([a-z]{3}\s+){2}  should capture last iteration
    // On input "Mon Sep ", group 1 should be 'Sep ' (last iteration), not 'Sep ' (but wait, both
    // are the same?)
    // Let me try a clearer example
    ReggieMatcher m = Reggie.compile("(\\w+\\s+){2}");
    MatchResult result = m.match("Mon Sep ");

    System.out.println("\nPattern: (\\w+\\s+){2} on 'Mon Sep '");
    if (result != null) {
      System.out.println("  group 0: '" + result.group(0) + "'");
      System.out.println("  group 1: '" + result.group(1) + "' (expected 'Sep ')");
      // POSIX semantics: quantified groups capture the LAST iteration
      assertEquals("Sep ", result.group(1), "Group 1 should capture last iteration");
    }
  }
}
