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
import com.datadoghq.reggie.ReggieOptions;
import org.junit.jupiter.api.Test;

class CapturePolicyTest {

  @Test
  void namedOnlyPreservesNamedGroupIndexesAndDropsInternalCaptures() {
    ReggieMatcher matcher =
        Reggie.compile(
            "(?<first>(a|b)+)-(?<second>(c))", ReggieOptions.builder().namedOnly().build());

    MatchResult result = matcher.match("abba-c");
    assertNotNull(result);
    assertEquals(4, result.groupCount());
    assertEquals("abba", result.group(1));
    assertNull(result.group(2));
    assertEquals("c", result.group(3));
    assertNull(result.group(4));
  }

  @Test
  void namedOnlyMatchIntoUsesOriginalNamedGroupIndexes() {
    ReggieMatcher matcher =
        Reggie.compile(
            "(?<first>(a|b)+)-(?<second>(c))", ReggieOptions.builder().namedOnly().build());

    int[] starts = new int[5];
    int[] ends = new int[5];
    assertTrue(matcher.matchInto("abba-c", starts, ends));
    assertEquals(0, starts[1]);
    assertEquals(4, ends[1]);
    assertEquals(-1, starts[2]);
    assertEquals(-1, ends[2]);
    assertEquals(5, starts[3]);
    assertEquals(6, ends[3]);
    assertEquals(-1, starts[4]);
    assertEquals(-1, ends[4]);
  }
}
