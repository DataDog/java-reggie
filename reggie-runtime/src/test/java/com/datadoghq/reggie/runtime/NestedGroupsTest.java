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

public class NestedGroupsTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testSimpleNestedGroups() {
    String pattern = "((x))";
    String input = "x";

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
    } else {
      System.out.println("No match");
    }
  }

  @Test
  public void testDeeplyNestedGroups() {
    String pattern = "((((((((((((((((((((x))))))))))))))))))))";
    String input = "x";

    System.out.println("\n=== Testing deeply nested groups on '" + input + "' ===");

    ReggieMatcher matcher = RuntimeCompiler.compile(pattern);
    System.out.println("Matcher class: " + matcher.getClass().getName());

    MatchResult result = matcher.findMatch(input);

    if (result != null) {
      System.out.println("Match found!");
      System.out.println("Group count: " + result.groupCount());
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
      System.out.println("\nExpected: All groups should be 'x' [0-1]");
    } else {
      System.out.println("No match");
    }
  }
}
