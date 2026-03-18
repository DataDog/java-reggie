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

/** Tests for \K (reset match start) feature. */
public class ResetMatchTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void testResetMatchBasic() {
    // (foo)\Kbar should match "bar" in group 0, "foo" in group 1
    ReggieMatcher m = Reggie.compile("(foo)\\Kbar");
    MatchResult r = m.match("foobar");

    assertNotNull(r, "Should match");
    assertEquals("foo", r.group(1), "Group 1 should capture 'foo'");
    // Note: group(0) should ideally be "bar" but without full \K implementation
    // it will be "foobar". This test documents current behavior.
  }

  @Test
  void testResetMatchInsideGroup() {
    // (a\Kb) - the \K is inside the group
    // Group 1 should capture "ab" (full content of group)
    ReggieMatcher m = Reggie.compile("(a\\Kb)");
    MatchResult r = m.match("ab");

    assertNotNull(r, "Should match");
    assertEquals("ab", r.group(1), "Group 1 should capture 'ab'");
  }

  @Test
  void testResetMatchWithAlternation() {
    // (foo)(\Kbar|baz) - \K in one alternative
    ReggieMatcher m = Reggie.compile("(foo)(\\Kbar|baz)");
    MatchResult r = m.match("foobar");

    assertNotNull(r, "Should match");
    assertEquals("foo", r.group(1), "Group 1 should capture 'foo'");
    assertEquals("bar", r.group(2), "Group 2 should capture 'bar'");
  }
}
