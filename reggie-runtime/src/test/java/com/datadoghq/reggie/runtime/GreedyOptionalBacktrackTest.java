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

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.Test;

/**
 * Oracle tests for capturing groups with a trailing optional greedy quantifier followed by an
 * overlapping element. The group must give back the optional match when the continuation requires
 * it.
 */
public class GreedyOptionalBacktrackTest {

  @Test
  public void testDecimalGroupOptionalDigit() {
    // (\.\d\d[1-9]?)\d+ on "1.235":
    // Group wants to match ".23" and let \d+ take "5"
    // because [1-9]? greedily takes "5" but then \d+ has nothing left
    ReggieMatcher m = Reggie.compile("(\\.\\d\\d[1-9]?)\\d+");
    MatchResult mr = m.findMatch("1.235");

    assertNotNull(mr, "Should find match");
    assertEquals(".235", mr.group(0), "Full match should be '.235'");
    assertEquals(1, mr.groupCount(), "Should have 1 capturing group");
    assertEquals(".23", mr.group(1), "Group 1 must backtrack: [1-9]? gives back '5' to \\d+");
  }
}
