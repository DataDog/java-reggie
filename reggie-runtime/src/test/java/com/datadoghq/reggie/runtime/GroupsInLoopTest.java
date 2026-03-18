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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class GroupsInLoopTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testGroupInLoop() {
    String pattern = "(a+|b)+";
    String input = "ab";

    System.out.println("\n=== Testing " + pattern + " on '" + input + "' ===");

    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    System.out.println("Matcher class: " + matcher.getClass().getName());

    MatchResult result = matcher.findMatch(input);

    if (result != null) {
      System.out.println("Match found!");
      for (int g = 0; g <= result.groupCount(); g++) {
        System.out.println(
            "  group("
                + g
                + "): '"
                + result.group(g)
                + "' ["
                + result.start(g)
                + "-"
                + result.end(g)
                + "]");
      }
      System.out.println();
      System.out.println("Expected: group(1) should be 'b' [1-2] (last match in loop)");
      System.out.println(
          "Actual:   group(1) is '"
              + result.group(1)
              + "' ["
              + result.start(1)
              + "-"
              + result.end(1)
              + "]");
    } else {
      System.out.println("No match");
    }
  }
}
