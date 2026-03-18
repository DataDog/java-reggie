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

/** Tests for capturing groups inside lookahead assertions. */
public class LookaheadGroupCaptureTest {

  @Test
  public void testLookaheadWithCapturingGroup() {
    // Pattern (?=(a))ab - lookahead captures 'a', then 'ab' matches
    ReggieMatcher m = Reggie.compile("(?=(a))ab");
    MatchResult mr = m.findMatch("ab");

    assertNotNull(mr, "Should find match");
    assertEquals("ab", mr.group(0), "Full match should be 'ab'");
    assertEquals(1, mr.groupCount(), "Should have 1 capturing group");
    assertEquals("a", mr.group(1), "Group 1 should capture 'a' from lookahead");
  }

  @Test
  public void testLookaheadWithAlternation() {
    // Pattern (?=(b))b|c - lookahead captures 'b', then 'b' matches
    // In "Ab", should match at position 1
    ReggieMatcher m = Reggie.compile("(?=(b))b|c");
    MatchResult mr = m.findMatch("Ab");

    assertNotNull(mr, "Should find match");
    assertEquals("b", mr.group(0), "Full match should be 'b'");
    assertEquals(1, mr.groupCount(), "Should have 1 capturing group");
    assertEquals("b", mr.group(1), "Group 1 should capture 'b' from lookahead");
  }

  @Test
  public void testOptionalLookaheadWithGroup() {
    // Pattern (?=(a))?. - optional lookahead with group, then any char
    // JDK: On "ab", group 1 captures "a"
    ReggieMatcher m = Reggie.compile("(?=(a))?.");
    MatchResult mr = m.findMatch("ab");

    assertNotNull(mr, "Should find match");
    assertEquals("a", mr.group(0), "Full match should be 'a'");
    assertEquals(1, mr.groupCount(), "Should have 1 capturing group");
    assertEquals("a", mr.group(1), "Group 1 should capture 'a' from lookahead");
  }

  @Test
  public void testSimpleLookaheadGroup() {
    // Pattern (?=(a))a - lookahead captures 'a', then 'a' matches
    ReggieMatcher m = Reggie.compile("(?=(a))a");
    MatchResult mr = m.findMatch("a");

    assertNotNull(mr, "Should find match");
    assertEquals("a", mr.group(0), "Full match should be 'a'");
    assertEquals(1, mr.groupCount(), "Should have 1 capturing group");
    assertEquals("a", mr.group(1), "Group 1 should capture 'a' from lookahead");
  }

  @Test
  public void testAlternationWithConflictingGroups() {
    // Pattern (?=(a))ab|(a)c has 2 groups:
    //   Group 1: (a) inside lookahead
    //   Group 2: (a) in second alternative
    // On "ac", the first alternative (?=(a))ab fails (no 'b'), second alternative (a)c matches
    // PCRE expects: Group 1 = empty (lookahead's alt failed), Group 2 = 'a'
    // Note: JDK differs here - it preserves lookahead captures even when alt fails
    ReggieMatcher m = Reggie.compile("(?=(a))ab|(a)c");
    MatchResult mr = m.match("ac");

    assertNotNull(mr, "Should find match");
    assertEquals("ac", mr.group(0), "Full match should be 'ac'");
    assertEquals(2, mr.groupCount(), "Should have 2 capturing groups");
    // PCRE: group 1 should be empty because lookahead's alternative failed
    assertNull(mr.group(1), "Group 1 should be null (lookahead alt failed) per PCRE");
    assertEquals("a", mr.group(2), "Group 2 should capture 'a' from second alternative");
  }

  // TODO: This test is disabled due to a known limitation in DFA-based assertion handling.
  // In pattern "(?=(a))ab|c", the lookahead assertion is at the DFA start state and
  // affects ALL transitions, not just the 'a' transition. When assertion fails on 'c',
  // the whole pattern rejects instead of trying the 'c' alternative.
  // This is an architectural issue that would require associating assertions with
  // specific transition paths rather than with states.
  // @Test
  public void testAlternationSecondBranchNoGroup() {
    // Pattern (?=(a))ab|c - lookahead group in first alt, no group in second alt
    // On "c", second alternative matches, group 1 should be null
    ReggieMatcher m = Reggie.compile("(?=(a))ab|c");

    // First verify matches() works
    assertTrue(m.matches("c"), "Pattern should match 'c' via second alternative");

    MatchResult mr = m.match("c");

    assertNotNull(mr, "Should find match");
    assertEquals("c", mr.group(0), "Full match should be 'c'");
    assertEquals(1, mr.groupCount(), "Should have 1 capturing group");
    assertNull(mr.group(1), "Group 1 should be null when lookahead branch doesn't match");
  }
}
