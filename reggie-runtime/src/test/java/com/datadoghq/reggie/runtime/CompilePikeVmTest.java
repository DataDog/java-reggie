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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompilePikeVmTest {
  // PIKEVM_CAPTURE pattern: capture-ambiguous greedy wildcard
  private static final String P = "(<\\w+>).*(</\\w+>)";
  private static final String IN = "<a>text</a>";

  @Test
  void nameMapRoundTrips() {
    Map<String, Integer> m = new LinkedHashMap<>();
    m.put("open", 1);
    m.put("close", 2);
    assertEquals(m, RuntimeCompiler.decodeNameMap(RuntimeCompiler.encodeNameMap(m)));
    assertEquals(Map.of(), RuntimeCompiler.decodeNameMap(RuntimeCompiler.encodeNameMap(Map.of())));
    assertEquals(Map.of(), RuntimeCompiler.decodeNameMap(""));
  }

  @Test
  void compilePikeVmMatchesRuntimePath() {
    // PIKEVM pattern with no named groups — encode empty name map
    String encoded = RuntimeCompiler.encodeNameMap(Map.of());
    ReggieMatcher staged = RuntimeCompiler.compilePikeVm(P, encoded);
    // compile with allowJdkFallback so we can compare; P goes PIKEVM_CAPTURE natively anyway
    ReggieMatcher runtime = Reggie.compile(P, ReggieOptions.builder().allowJdkFallback().build());

    assertEquals(runtime.find(IN), staged.find(IN));
    MatchResult sr = staged.findMatch(IN);
    MatchResult rr = runtime.findMatch(IN);
    assertEquals(rr != null, sr != null);
    if (rr != null) {
      assertEquals(rr.start(), sr.start());
      assertEquals(rr.end(), sr.end());
    }
    assertFalse(staged instanceof JavaRegexFallbackMatcher);
  }

  // --- Tests for Fix 2: compilePikeVm() must honour full needsFallback() guard ---

  @Test
  void compilePikeVm_quantifiedAnchorGroup_throws() {
    // ($){2} triggers B3 (hasAnchorInQuantifier) — compilePikeVm must reject it
    assertThrows(
        UnsupportedPatternException.class, () -> RuntimeCompiler.compilePikeVm("($){2}", ""));
  }

  @Test
  void compilePikeVm_optionalAnchorGroup_throws() {
    // (^)? triggers B3 (hasAnchorInQuantifier)
    assertThrows(
        UnsupportedPatternException.class, () -> RuntimeCompiler.compilePikeVm("(^)?", ""));
  }

  @Test
  void compilePikeVm_nonQuantifiedAnchorGroup_succeeds() {
    // ($) is safe for PikeVM: hasAnchorInQuantifier = false
    String encoded = RuntimeCompiler.encodeNameMap(Map.of());
    assertNotNull(RuntimeCompiler.compilePikeVm("($)", encoded));
  }

  @Test
  void compileAllowingFallbackWorks() {
    // A native pattern compiles cleanly
    ReggieMatcher m = Reggie.compileAllowingFallback("\\d{3}-\\d{3}-\\d{4}");
    assertNotNull(m);
    assertFalse(m instanceof JavaRegexFallbackMatcher);
    // A JDK-fallback pattern also succeeds (delegates to JDK)
    ReggieMatcher fb = Reggie.compileAllowingFallback("([a-z]{3}).*\\1");
    assertNotNull(fb);
  }
}
