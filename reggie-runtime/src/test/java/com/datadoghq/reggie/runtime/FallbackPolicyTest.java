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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.UnsupportedPatternException;
import org.junit.jupiter.api.Test;

class FallbackPolicyTest {
  // captureAmbiguous: the "b" alternative bypasses group 1, so the NFA has a thread that reaches
  // accept without entering group 1. Per-state group arrays are required for correct spans
  // (issue A6); until then RuntimeCompiler routes this pattern to JavaRegexFallbackMatcher.
  private static final String FALLBACK_PATTERN = "(a)\\1|b";

  @Test
  void throwsByDefault() {
    UnsupportedPatternException ex =
        assertThrows(
            UnsupportedPatternException.class,
            () -> Reggie.compile(FALLBACK_PATTERN, ReggieOptions.DEFAULT));
    assertFalse(ex.getMessage().isEmpty());
  }

  @Test
  void delegatesWhenFallbackEnabled() {
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    ReggieMatcher m = Reggie.compile(FALLBACK_PATTERN, opts);
    assertTrue(m instanceof JavaRegexFallbackMatcher);
    // behaviorally correct: matches JDK
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(FALLBACK_PATTERN);
    String in = "abcdxyz";
    org.junit.jupiter.api.Assertions.assertEquals(jdk.matcher(in).find(), m.find(in));
  }

  @Test
  void nativePatternUnaffected() {
    ReggieMatcher m = Reggie.compile("\\d{3}-\\d{3}-\\d{4}", ReggieOptions.DEFAULT);
    assertFalse(m instanceof JavaRegexFallbackMatcher);
  }
}
