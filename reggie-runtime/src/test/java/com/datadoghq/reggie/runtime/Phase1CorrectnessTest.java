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

/** Test to verify Phase 1 indexOf optimization correctness. */
public class Phase1CorrectnessTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  public void testLookaheadNoBoyerMoore() {
    String pattern = "(?=\\w+@).*@\\w+\\.\\w+";

    // Positive cases
    ReggieMatcher matcher = Reggie.compile(pattern);
    assertTrue(matcher.find("user@domain.org"), "Should match user@domain.org");
    assertTrue(matcher.find("test@example.com"), "Should match test@example.com");
    assertTrue(matcher.find("abc@def.net"), "Should match abc@def.net");

    // Negative cases
    assertFalse(
        matcher.find("@domain.org"), "Should NOT match @domain.org (no word chars before @)");
    assertFalse(matcher.find("user@domain"), "Should NOT match user@domain (no TLD)");
    assertFalse(matcher.find("userdomain.org"), "Should NOT match userdomain.org (no @)");
  }

  @Test
  public void testLookaheadWithPlusQuantifier() {
    String pattern = "(?=\\w+@).*@example\\.com";

    ReggieMatcher matcher = Reggie.compile(pattern);
    assertTrue(matcher.find("user@example.com"), "Should match user@example.com");
    assertFalse(matcher.find("@example.com"), "Should NOT match @example.com");
  }
}
