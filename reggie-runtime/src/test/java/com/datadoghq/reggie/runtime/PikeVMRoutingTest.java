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
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import org.junit.jupiter.api.Test;

class PikeVMRoutingTest {

  @Test
  void captureAmbiguousRoutes_toPikeVMCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(a)?b"),
        "(a)?b must route to PIKEVM_CAPTURE");
  }

  @Test
  void captureAmbiguousRoutes_dotOptionalB() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE,
        StrategyCorrectnessMetaTest.routeOf("(.)?b"),
        "(.)?b must route to PIKEVM_CAPTURE");
  }

  @Test
  void pikeVMCaptureMatcher_matchesCorrectly() {
    ReggieMatcher m = Reggie.compile("(a)?b");
    assertTrue(m.matches("ab"), "should match 'ab'");
    assertTrue(m.matches("b"), "should match 'b'");
    assertFalse(m.matches("a"), "should not match 'a'");
    assertTrue(m.find("xaby"), "should find in 'xaby'");
    assertTrue(m.find("xby"), "should find in 'xby'");
  }
}
