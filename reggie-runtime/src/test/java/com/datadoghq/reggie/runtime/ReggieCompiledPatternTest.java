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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReggieCompiledPatternTest {

  @AfterEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void compilesNativelyWithoutTouchingGeneralCompilerCaches() {
    int patternCacheSize = RuntimeCompiler.cacheSize();
    int structuralCacheSize = RuntimeCompiler.structuralCacheSize();

    ReggieCompilationResult result =
        ReggieCompiledPattern.tryCompile(
            new ReggieCompileRequest("(?<value>\\S+)", ReggieCompileFlag.NONE));

    assertTrue(result.isAdmitted());
    assertEquals(patternCacheSize, RuntimeCompiler.cacheSize());
    assertEquals(structuralCacheSize, RuntimeCompiler.structuralCacheSize());
    ReggieMatchState state = result.pattern().newState();
    assertTrue(state.matches(new GuardedCharSequence("value"), 0, 5));
    assertEquals(0, state.start("value"));
    assertEquals(5, state.end("value"));
  }

  @Test
  void returnsNativeRejectionsWithoutFallbackOrCacheMutation() {
    int patternCacheSize = RuntimeCompiler.cacheSize();
    int structuralCacheSize = RuntimeCompiler.structuralCacheSize();

    ReggieCompilationResult result =
        ReggieCompiledPattern.tryCompile(
            new ReggieCompileRequest("(?<value>[a-z]+)", ReggieCompileFlag.NONE));

    assertFalse(result.isAdmitted());
    assertEquals(ReggieCompilationRejection.PLAN_UNAVAILABLE, result.rejection());
    assertThrows(IllegalStateException.class, result::pattern);
    assertEquals(patternCacheSize, RuntimeCompiler.cacheSize());
    assertEquals(structuralCacheSize, RuntimeCompiler.structuralCacheSize());
  }

  @Test
  void acceptsOnlyTheNativeProfileFlagValues() {
    assertThrows(
        NullPointerException.class, () -> new ReggieCompileRequest(null, ReggieCompileFlag.NONE));
    assertThrows(NullPointerException.class, () -> new ReggieCompileRequest("pattern", null));

    ReggieMatchState state =
        ReggieCompiledPattern.tryCompile(
                new ReggieCompileRequest(
                    ".* \\[(?<logger>\\b\\w+\\b)\\] .*", ReggieCompileFlag.DOTALL))
            .pattern()
            .newState();
    assertTrue(state.matches(new GuardedCharSequence("before\n [nginx] after"), 0, 21));
    assertEquals(9, state.start("logger"));
    assertEquals(14, state.end("logger"));
  }

  @Test
  void exposesAbsoluteSpansWithoutMaterializingTheInput() {
    ReggieCompiledPattern pattern = admitted("host=(?<host>\\S+) status=(?<status>\\d+)").pattern();
    ReggieMatchState state = pattern.newState();
    CharSequence input = new GuardedCharSequence("xx host=api status=200 yy");

    assertTrue(state.matches(input, 3, input.length() - 3));
    assertEquals(3, state.start());
    assertEquals(input.length() - 3, state.end());
    assertEquals(8, state.start("host"));
    assertEquals(11, state.end("host"));
    assertEquals(19, state.start("status"));
    assertEquals(22, state.end("status"));
  }

  @Test
  void usesTheNamedCaptureAfterAnUnnamedProjectedGroup() {
    ReggieMatchState state = admitted("(x)(?<value>\\S+)").pattern().newState();

    assertTrue(state.matches(new GuardedCharSequence("xvalue"), 0, 6));
    assertEquals(1, state.start("value"));
    assertEquals(6, state.end("value"));
  }

  @Test
  void clearsSpansBeforeFailedAndInvalidMatches() {
    ReggieMatchState state = admitted("(?<value>\\S+)").pattern().newState();
    assertTrue(state.matches(new GuardedCharSequence("value"), 0, 5));

    assertFalse(state.matches(new GuardedCharSequence(""), 0, 0));
    assertThrows(IllegalStateException.class, state::start);
    assertThrows(IllegalStateException.class, () -> state.start("value"));

    assertThrows(
        IndexOutOfBoundsException.class,
        () -> state.matches(new GuardedCharSequence("value"), -1, 5));
    assertThrows(IllegalStateException.class, state::end);
    assertThrows(NullPointerException.class, () -> state.matches(null, 0, 0));
    assertThrows(IllegalStateException.class, () -> state.end("value"));
  }

  @Test
  void rejectsUnknownNamesAndReportsUnmatchedNamedGroups() {
    ReggieMatchState state =
        admitted("\"(?<method>\\b\\w+\\b) (?<target>\\S+)(?: HTTP/(?<version>\\d+\\.\\d+)|)\"")
            .pattern()
            .newState();

    assertTrue(state.matches(new GuardedCharSequence("\"GET /health\""), 0, 13));
    assertEquals(-1, state.start("version"));
    assertEquals(-1, state.end("version"));
    assertThrows(IllegalArgumentException.class, () -> state.start("missing"));
  }

  @Test
  void statesCanMatchIndependentlyInParallel() throws Exception {
    ReggieCompiledPattern pattern = admitted("(?<value>\\S+)").pattern();
    ExecutorService executor = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Void>> calls = new ArrayList<>();
      for (int thread = 0; thread < 4; thread++) {
        int id = thread;
        calls.add(
            () -> {
              ReggieMatchState state = pattern.newState();
              for (int iteration = 0; iteration < 100; iteration++) {
                String value = "value-" + id + "-" + iteration;
                assertTrue(state.matches(new GuardedCharSequence(value), 0, value.length()));
                assertEquals(0, state.start("value"));
                assertEquals(value.length(), state.end("value"));
              }
              return null;
            });
      }
      for (Future<Void> result : executor.invokeAll(calls)) {
        result.get();
      }
    } finally {
      executor.shutdownNow();
    }
  }

  private static ReggieCompilationResult admitted(String source) {
    ReggieCompilationResult result =
        ReggieCompiledPattern.tryCompile(new ReggieCompileRequest(source, ReggieCompileFlag.NONE));
    assertTrue(result.isAdmitted(), () -> "rejection: " + result.rejection());
    return result;
  }

  private static final class GuardedCharSequence implements CharSequence {
    private final String value;

    private GuardedCharSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new AssertionError("matching must not materialize a subsequence");
    }

    @Override
    public String toString() {
      throw new AssertionError("matching must not materialize the input");
    }
  }
}
