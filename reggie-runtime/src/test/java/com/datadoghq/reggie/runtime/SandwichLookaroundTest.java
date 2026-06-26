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
}
