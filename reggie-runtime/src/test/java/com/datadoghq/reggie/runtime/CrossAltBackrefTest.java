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
 * Regression tests for cross-alternative backreference handling in OPTIMIZED_NFA_WITH_BACKREFS.
 *
 * <p>Bug: In {@code (a)|\1}, enterGroup(1) fires during epsilon closure before branch-0 tries to
 * match 'a'. If branch-0 fails, exitGroup(1) never runs, leaving groupEnds[1]=-1. Branch-1's
 * backrefCheck then computes groupLen = -1 - pos &lt; 0, which caused String.regionMatches to
 * spuriously return true (negative-length regionMatches skips all checks and returns true).
 *
 * <p>Partial fix (Wave 6): generateBackreferenceCheck now rejects groupLen &lt; 0 as a hard
 * failure. The FallbackPatternDetector still routes OPTIMIZED_NFA_WITH_BACKREFS cross-alt patterns
 * to JDK because more complex cross-alt patterns (group fully captured in branch-0, branch-1 uses
 * it) still require per-state group arrays (issue #38 Cat B equivalent). The tests below verify
 * correctness via JDK delegation and the negative-groupLen guard defensive fix.
 */
class CrossAltBackrefTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  /**
   * {@code (a)|\1} currently routes to JDK fallback due to the cross-alt backref guard. Once
   * per-state group arrays are implemented in NFABytecodeGenerator the guard can be removed.
   */
  @Test
  void crossAltBackref_isFallback() {
    assertTrue(
        Reggie.compile("(a)|\\1") instanceof JavaRegexFallbackMatcher,
        "(a)|\\1 must fall back to JavaRegexFallbackMatcher until per-state group arrays are added");
  }

  /**
   * {@code (a)|\1} on input "b": branch-0 fails ('a' != 'b'), branch-1 backref must also fail
   * because group 1 was never fully captured. No match expected.
   */
  @Test
  void crossAltBackref_noSpuriousMatch_onBranchZeroFailure() {
    MatchResult r = Reggie.compile("(a)|\\1").findMatch("b");
    assertNull(r, "(a)|\\1 must return null on 'b' — no spurious zero-length match");
  }

  /** {@code (a)|\1} on input "a": branch-0 matches, group 1 captures "a". */
  @Test
  void crossAltBackref_correctMatchOnBranchZeroSuccess() {
    MatchResult r = Reggie.compile("(a)|\\1").findMatch("a");
    assertNotNull(r, "(a)|\\1 must match 'a' via branch-0");
    assertEquals("a", r.group(1), "group 1 must capture 'a'");
  }

  /**
   * {@code (a)|\1} on input "ba": no match at position 0 (branch-0 fails, backref must fail too),
   * but branch-0 matches 'a' at position 1.
   */
  @Test
  void crossAltBackref_noSpuriousMatchBeforeActual() {
    MatchResult r = Reggie.compile("(a)|\\1").findMatch("ba");
    assertNotNull(r, "(a)|\\1 must find a match in 'ba'");
    assertEquals(1, r.start(), "match must start at position 1, not 0");
    assertEquals("a", r.group(1), "group 1 must capture 'a'");
  }

  /**
   * {@code (a)|(b)\1} on input "ba": at pos 0, branch-1 enters group 2 (captures 'b') then checks
   * \1, but group 1 was only entered (not exited) so groupLen=-1 — must not match. At pos 1,
   * branch-0 matches 'a'. Matches JDK behavior: find() at start=1, group(1)="a".
   */
  @Test
  void crossAltBackref_multipleAltsWithLiteralBeforeBackref() {
    MatchResult r = Reggie.compile("(a)|(b)\\1").findMatch("ba");
    assertNotNull(r, "(a)|(b)\\1 must find 'a' at position 1 in 'ba'");
    assertEquals(1, r.start(), "match must start at position 1 (branch-0 on 'a'), not 0");
    assertEquals("a", r.group(1), "group 1 must capture 'a'");
    assertNull(r.group(2), "group 2 must be null (branch-1 did not match)");
  }
}
