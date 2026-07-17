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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ReggieNativeCapabilityTest {
  private static final Set<ReggieNativeCapability> EXPECTED =
      Set.of(
          ReggieNativeCapability.NATIVE_ONLY,
          ReggieNativeCapability.LINEAR_TIME,
          ReggieNativeCapability.INTERRUPTIBLE_CHAR_SEQUENCE);

  @Test
  void directAndCachedPatternsReportTheExactImmutableProfile() {
    ReggieCompilationResult direct =
        ReggieCompiledPattern.tryCompile(
            new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE));
    assertTrue(direct.isAdmitted());
    assertEquals(EXPECTED, direct.pattern().capabilities());
    assertSame(direct.pattern().capabilities(), direct.pattern().capabilities());
    assertThrows(
        UnsupportedOperationException.class,
        () -> direct.pattern().capabilities().add(ReggieNativeCapability.NATIVE_ONLY));

    ReggieCompiledPatternCompiler compiler = new ReggieCompiledPatternCompiler(1);
    ReggieCompiledPattern cached =
        compiler
            .tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE))
            .pattern();
    assertEquals(EXPECTED, cached.capabilities());
    assertSame(
        cached,
        compiler
            .tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE))
            .pattern());
  }

  @Test
  void rejectionCannotExposeCapabilities() {
    ReggieCompilationResult rejected =
        ReggieCompiledPattern.tryCompile(
            new ReggieCompileRequest("(?<value>[a-z]+)", ReggieCompileFlag.NONE));
    assertThrows(IllegalStateException.class, rejected::pattern);
  }

  @Test
  void capabilitiesSurviveEvictionAndSeparateCompilerInstances() {
    ReggieCompiledPatternCompiler first = new ReggieCompiledPatternCompiler(1);
    ReggieCompiledPatternCompiler second = new ReggieCompiledPatternCompiler(1);
    ReggieCompiledPattern original =
        first
            .tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE))
            .pattern();
    assertTrue(
        first
            .tryCompile(new ReggieCompileRequest("(?<value>\\d+)", ReggieCompileFlag.NONE))
            .isAdmitted());
    ReggieCompiledPattern recompiled =
        first
            .tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE))
            .pattern();
    ReggieCompiledPattern independent =
        second
            .tryCompile(new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE))
            .pattern();
    assertEquals(EXPECTED, original.capabilities());
    assertEquals(EXPECTED, recompiled.capabilities());
    assertEquals(EXPECTED, independent.capabilities());
    assertNotSame(original, recompiled);
  }
}
