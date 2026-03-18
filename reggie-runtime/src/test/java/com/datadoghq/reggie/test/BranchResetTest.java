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
package com.datadoghq.reggie.test;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import org.junit.jupiter.api.Test;

public class BranchResetTest {
  @Test
  public void testBranchResetWithBackref() {
    // Pattern: (?|(abc)|(xyz))\1  with input "abcabc"
    // Branch reset means groups 1 maps to both branches
    // Backref \1 should match whatever group 1 captured
    ReggieMatcher m = Reggie.compile("(?|(abc)|(xyz))\\1");
    MatchResult result = m.findMatch("abcabc");

    assertNotNull(result, "Should match");
    assertEquals("abcabc", result.group(0), "Full match");
    assertEquals("abc", result.group(1), "Group 1 should capture 'abc'");
  }
}
