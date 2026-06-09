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

/**
 * Regression tests for SPECIALIZED_MULTI_GROUP_GREEDY with star (*) quantified groups.
 *
 * <p>Bug: A {@code *} group (minMatches=0) greedily consumes chars that a following bounded group
 * needs, with no inter-group backtracking. {@code ^([^a])([^\b])([^c]*)([^d]{3,4})} on {@code
 * baNOTccd}: {@code [^c]*} greedily takes "NOT", leaving "ccd" for {@code [^d]{3,4}} which needs ≥3
 * non-d chars — only "cc" (2) remain. Fix: route such patterns to RECURSIVE_DESCENT.
 */
class MultiGroupGreedyStarBacktrackTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  /** The root PCRE failure: [^c]* over-consumes, [^d]{3,4} needs backtrack from [^c]*. */
  @Test
  void starBeforeBoundedGroup_matchesViaPCREBacktracking() {
    MatchResult r = Reggie.compile("^([^a])([^\\b])([^c]*)([^d]{3,4})").findMatch("baNOTccd");
    assertNotNull(r, "^([^a])([^\\b])([^c]*)([^d]{3,4}) must match 'baNOTccd'");
    assertEquals("b", r.group(1));
    assertEquals("a", r.group(2));
    // [^c]* must yield to allow [^d]{3,4} to match "Tcc" (3 non-d chars)
    assertEquals("NO", r.group(3));
    assertEquals("Tcc", r.group(4));
  }

  /** Simpler case: ([^x]*)([^y]{2}) — * group must yield to bounded group. */
  @Test
  void starBeforeBoundedGroup_simple() {
    MatchResult r = Reggie.compile("([^x]*)([^y]{2})").findMatch("abcd");
    assertNotNull(r, "([^x]*)([^y]{2}) must match 'abcd'");
    assertNotNull(r.group(2));
    assertEquals(2, r.group(2).length(), "group(2) must capture exactly 2 chars");
  }

  /** Pattern where * is the last group — no backtracking needed, charset stops at separator. */
  @Test
  void starAsLastGroup_noBacktrackNeeded() {
    // [a-z]+ stops at '-' naturally (not in charset), [^y]* takes the rest
    MatchResult r = Reggie.compile("([a-z]+)-([^y]*)").findMatch("abc-def");
    assertNotNull(r, "([a-z]+)-([^y]*) must match 'abc-def'");
    assertEquals("abc", r.group(1));
    assertEquals("def", r.group(2));
  }
}
