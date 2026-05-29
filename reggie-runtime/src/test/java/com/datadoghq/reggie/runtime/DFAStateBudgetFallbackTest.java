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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DFAStateBudgetFallbackTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void largeAlternationQuantifierDoesNotFailCompilation() {
    ReggieMatcher matcher = Reggie.compile("(?:a|b|c|d|e|f){100}");

    assertTrue(matcher.matches("a".repeat(100)));
    assertTrue(matcher.matches("abcdef".repeat(16) + "abcd"));
    assertFalse(matcher.matches("a".repeat(99)));
    assertFalse(matcher.matches("a".repeat(101)));
  }
}
