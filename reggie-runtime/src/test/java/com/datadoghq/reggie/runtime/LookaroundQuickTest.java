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

public class LookaroundQuickTest {
  @Test
  void lookbehindPlusLookahead_issue31() {
    var m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertTrue(m.find("[value]"), "#31 sandwich should find");
    assertFalse(m.find("value"), "#31 no brackets should not find");
  }

  @Test
  void lookaheadInQuantifiedGroup_issue28() {
    var m = Reggie.compile("(?:(?=\\d)\\d)+");
    assertTrue(m.find("123"), "#28 should find digits");
    assertFalse(m.find("abc"), "#28 should not find letters");
  }
}
