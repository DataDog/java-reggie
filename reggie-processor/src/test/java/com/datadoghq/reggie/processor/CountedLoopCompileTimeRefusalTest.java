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
package com.datadoghq.reggie.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Review regression: the annotation-processor pipeline must refuse counted-loop-lowered patterns.
 * The lowering runs in this build too, but only the runtime pipeline executes the markers — the
 * compile-time generators saw them as dead states (a{0,6000} crashed with "Unknown strategy:
 * COUNTING_GLUSHKOV"; (?=b)a{0,6000} generated a matcher that rejected valid matches).
 */
class CountedLoopCompileTimeRefusalTest {

  private static final String MONSTER = "a{0,6000}";

  @Test
  void strictRealizationThrows() throws Exception {
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test", "CountedLoopRefusal", MONSTER);
    UnsupportedOperationException e =
        assertThrows(UnsupportedOperationException.class, () -> gen.resolveRealization(false));
    assertTrue(e.getMessage().contains("runtime-only"), e.getMessage());
  }

  @Test
  void fallbackRealizationDelegates() throws Exception {
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test", "CountedLoopRefusal2", MONSTER);
    assertEquals(
        ReggieMatcherBytecodeGenerator.Realization.DELEGATE_FALLBACK, gen.resolveRealization(true));
  }

  @Test
  void generateThrows() {
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test", "CountedLoopRefusal3", MONSTER);
    assertThrows(UnsupportedOperationException.class, gen::generate);
  }
}
