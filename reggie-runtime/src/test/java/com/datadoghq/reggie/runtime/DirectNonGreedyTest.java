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
 * Direct test for non-greedy quantifiers - no cache clearing between tests. This matches how
 * CaptureGroupValidator runs tests.
 */
public class DirectNonGreedyTest {

  @Test
  void testDirectPCREPatterns() {
    // These patterns from pcre-capturing-groups.txt
    // Testing without cache clearing between patterns

    // Test 1: a(?:b|c|d){5,6}?(.) on acdbcdbe - expected group 1 = b
    {
      ReggieMatcher m = Reggie.compile("a(?:b|c|d){5,6}?(.)");
      MatchResult r = m.findMatch("acdbcdbe");
      assertNotNull(r, "Pattern should find match");
      System.out.println("Pattern 1: a(?:b|c|d){5,6}?(.)");
      System.out.println("  Input: acdbcdbe");
      System.out.println("  Full match: '" + r.group() + "'");
      System.out.println("  Group 1: '" + r.group(1) + "'");
      assertEquals("b", r.group(1), "Expected 'b', got '" + r.group(1) + "'");
    }

    // Test 2: a(?:b|c|d){5,7}?(.) on acdbcdbe - expected group 1 = b
    {
      ReggieMatcher m = Reggie.compile("a(?:b|c|d){5,7}?(.)");
      MatchResult r = m.findMatch("acdbcdbe");
      assertNotNull(r, "Pattern should find match");
      System.out.println("\nPattern 2: a(?:b|c|d){5,7}?(.)");
      System.out.println("  Input: acdbcdbe");
      System.out.println("  Full match: '" + r.group() + "'");
      System.out.println("  Group 1: '" + r.group(1) + "'");
      assertEquals("b", r.group(1), "Expected 'b', got '" + r.group(1) + "'");
    }

    // Test 3: a(?:b|c|d){4,5}?(.) on acdbcdbe - expected group 1 = d
    {
      ReggieMatcher m = Reggie.compile("a(?:b|c|d){4,5}?(.)");
      MatchResult r = m.findMatch("acdbcdbe");
      assertNotNull(r, "Pattern should find match");
      System.out.println("\nPattern 3: a(?:b|c|d){4,5}?(.)");
      System.out.println("  Input: acdbcdbe");
      System.out.println("  Full match: '" + r.group() + "'");
      System.out.println("  Group 1: '" + r.group(1) + "'");
      assertEquals("d", r.group(1), "Expected 'd', got '" + r.group(1) + "'");
    }
  }
}
