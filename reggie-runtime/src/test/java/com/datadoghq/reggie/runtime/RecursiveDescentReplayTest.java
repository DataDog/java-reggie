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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Recursive-descent backtracking over a quantified child does not re-run the child's greedy
 * iterations: a quantified subroutine call is matched once per recursion level, not twice, so
 * a<sup>n</sup>b<sup>n</sup> stays linear in n instead of 2<sup>n</sup>.
 */
class RecursiveDescentReplayTest {

  @ParameterizedTest
  @ValueSource(strings = {"a(?R)?b", "(a(?1)?b)", "a(?R)*b"})
  void nestedSubroutineIsLinear(String pattern) {
    ReggieMatcher m = RuntimeCompiler.compile(pattern);
    int n = 90;
    String balanced = "a".repeat(n) + "b".repeat(n);
    String unbalanced = "a".repeat(n) + "b".repeat(n - 1);
    assertTimeoutPreemptively(
        Duration.ofSeconds(10),
        () -> {
          assertTrue(m.matches(balanced), pattern);
          assertFalse(m.matches(unbalanced), pattern);
        });
  }

  @Test
  void subroutineGroupSpan() {
    MatchResult r = RuntimeCompiler.compile("(a(?1)?b)").match("aaabbb");
    assertNotNull(r);
    assertEquals("aaabbb", r.group(1));
  }

  @Test
  void optionalCapturingBodyWithSubroutine() {
    ReggieMatcher m = RuntimeCompiler.compile("(a|b)(c(?1))?d");
    MatchResult r = m.match("acbd");
    assertNotNull(r);
    assertEquals("a", r.group(1));
    assertEquals("cb", r.group(2));
    r = m.match("ad");
    assertNotNull(r);
    assertNull(r.group(2));
    assertFalse(m.matches("acd"));
  }

  @Test
  void starCapturingBodyWithSubroutine() {
    String pattern = "(a|b)(c(?1))*d";
    ReggieMatcher m = RuntimeCompiler.compile(pattern);
    MatchResult r = m.match("acacbd");
    assertNotNull(r, pattern);
    assertEquals("a", r.group(1), pattern);
    assertEquals("cb", r.group(2), pattern);
    r = m.match("ad");
    assertNotNull(r, pattern);
    assertNull(r.group(2), pattern);
    assertFalse(m.matches("acacd"), pattern);
  }

  @Test
  void lastIterationShrinkStartsAtQuantifierStart() {
    // k == 1 with a failing suffix retries the only iteration with a shorter end bound; the
    // retry must start where the quantifier started (2), not at 0.
    assertTrue(RuntimeCompiler.compile("(q)x(?:ab|a)+b(?1)").matches("qxabq"));
  }
}
