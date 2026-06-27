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

public class SandwichLookaroundTest {

  @Test
  void lookbehind_lookahead_find() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertTrue(m.find("[value]"), "find should return true for '[value]'");
  }

  @Test
  void lookbehind_lookahead_findMatch() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertNotNull(m.findMatch("[hello]"), "findMatch should return non-null");
    assertEquals("hello", m.findMatch("[hello]").group(0));
  }

  @Test
  void lookbehind_lookahead_multipleInString() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertNotNull(m.findMatch("[one][two]"), "should find first match");
    assertEquals("one", m.findMatch("[one][two]").group(0));
  }

  @Test
  void lookahead_only_find_still_works() {
    ReggieMatcher m = Reggie.compile("foo(?=bar)");
    assertTrue(m.find("foobar"), "lookahead-only find must still work");
    assertFalse(m.find("foobaz"), "lookahead-only find must reject non-match");
  }

  @Test
  void sandwichLookaroundWithInnerCapture() {
    // Sandwich pattern: lookbehind + capturing group + lookahead.
    // The capturing group is OUTSIDE the assertions; tagged-DFA must recover group 1.
    ReggieMatcher m = Reggie.compile("(?<=\\[)([a-z]+)(?=\\])");
    MatchResult result = m.findMatch("[abc]");

    assertNotNull(result, "findMatch must return non-null for '[abc]'");
    assertEquals("abc", result.group(0), "group(0) must be 'abc'");
    assertEquals("abc", result.group(1), "group(1) must be 'abc', not null/-1");
  }

  @Test
  void sandwichLookaroundWithInnerCaptureMatchSemantics() {
    // match() requires the entire input to match. The pattern (?<=\[)([a-z]+)(?=\]) only
    // matches the inner letters — the brackets are not consumed — so match() always returns null.
    ReggieMatcher m = Reggie.compile("(?<=\\[)([a-z]+)(?=\\])");
    assertNull(
        m.match("[abc]"), "match('[abc]') must return null — brackets not consumed by pattern");
    assertNull(
        m.match("abc"),
        "match('abc') must return null — lookbehind/lookahead fail without brackets");
  }

  @Test
  void sandwichLookaroundWithInnerCaptureRerouted() {
    // Pattern with a wider character class inside the capturing group.
    // Tests that inner group capture is correct regardless of which DFA_UNROLLED path handles it.
    ReggieMatcher m = Reggie.compile("(?<=\\[)([a-zA-Z0-9_]+)(?=\\])");
    MatchResult result = m.findMatch("[Hello_World123]");

    assertNotNull(result, "findMatch must return non-null");
    assertEquals("Hello_World123", result.group(0), "group(0) must be full match");
    assertEquals(
        "Hello_World123", result.group(1), "group(1) must equal group(0) for this pattern");

    // Also verify null for non-matching input.
    assertNull(m.findMatch("Hello_World123"), "no brackets → no match");
  }
}
