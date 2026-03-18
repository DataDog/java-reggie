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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NFAOptimizationTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testNFAFindMatchPerformance() {
    // Pattern with lookahead (forces NFA/hybrid strategy)
    ReggieMatcher m = Reggie.compile("(?=.*b)(a+)b");

    long start = System.nanoTime();
    MatchResult result = m.findMatch("aaaab");
    long end = System.nanoTime();

    assertNotNull(result, "Should find match");
    assertEquals("aaaab", result.group(0));
    assertEquals("aaaa", result.group(1));

    double timeMs = (end - start) / 1_000_000.0;
    System.out.println("NFA findMatch time: " + timeMs + "ms");
    assertTrue(timeMs < 100, "Should complete in less than 100ms, took: " + timeMs + "ms");
  }

  @Test
  void testNFAFindMatchWithLongInput() {
    // Pattern with lookahead
    ReggieMatcher m = Reggie.compile("(?=.*b)(a+)b");

    // Create input with match at the end
    String input = "x".repeat(1000) + "aaaab";

    long start = System.nanoTime();
    MatchResult result = m.findMatch(input);
    long end = System.nanoTime();

    assertNotNull(result, "Should find match");
    assertEquals("aaaab", result.group(0));

    double timeMs = (end - start) / 1_000_000.0;
    System.out.println("NFA findMatch with long input time: " + timeMs + "ms");
    assertTrue(timeMs < 500, "Should complete in less than 500ms, took: " + timeMs + "ms");
  }
}
