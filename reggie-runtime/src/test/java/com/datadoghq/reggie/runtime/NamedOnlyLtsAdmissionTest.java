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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieFlags;
import com.datadoghq.reggie.ReggieOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NamedOnlyLtsAdmissionTest {

  @AfterEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void admitsOnlyNativeLtsWithoutTouchingLegacyCaches() {
    int patternCacheSize = RuntimeCompiler.cacheSize();
    int structuralCacheSize = RuntimeCompiler.structuralCacheSize();

    RuntimeCompiler.NamedOnlyLtsCompilation compilation =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence(
            ".* \\[(?<logger>\\b\\w+\\b)\\] .*", ReggieFlags.DOTALL);

    assertNotNull(compilation.matcher());
    assertNull(compilation.rejection());
    assertEquals(".* \\[(?<logger>\\b\\w+\\b)\\] .*", compilation.matcher().pattern());
    assertEquals("nginx", compilation.matcher().match("before\n [nginx] after").group("logger"));
    assertEquals(patternCacheSize, RuntimeCompiler.cacheSize());
    assertEquals(structuralCacheSize, RuntimeCompiler.structuralCacheSize());
  }

  @Test
  void returnsStructuredRejectionsBeforeLegacyCompilation() {
    int patternCacheSize = RuntimeCompiler.cacheSize();
    int structuralCacheSize = RuntimeCompiler.structuralCacheSize();
    assertRejected(
        "(?s)(?<value>\\S+)", 0, RuntimeCompiler.NamedOnlyLtsRejection.SOURCE_INLINE_MODIFIER);
    assertRejected(
        "(?s:(?<value>\\S+))", 0, RuntimeCompiler.NamedOnlyLtsRejection.SOURCE_INLINE_MODIFIER);
    assertRejected(
        "(?<value>\\S+)",
        ReggieFlags.CASE_INSENSITIVE,
        RuntimeCompiler.NamedOnlyLtsRejection.UNSUPPORTED_FLAGS);
    assertRejected(
        "(?<value>\\S+)",
        java.util.regex.Pattern.DOTALL,
        RuntimeCompiler.NamedOnlyLtsRejection.UNSUPPORTED_FLAGS);
    assertRejected(
        ".* \\[(?<logger>\\b\\w+\\b)\\] .*",
        0,
        RuntimeCompiler.NamedOnlyLtsRejection.PROFILE_INELIGIBLE);
    assertRejected("(?<value>[a-z]+)", 0, RuntimeCompiler.NamedOnlyLtsRejection.PLAN_UNAVAILABLE);
    assertRejected(
        "(?<outer>(?:-|(?<inner>[+-]?\\d+)))",
        0,
        RuntimeCompiler.NamedOnlyLtsRejection.MISSING_NAMED_CAPTURE);
    assertRejected("(?<value>", 0, RuntimeCompiler.NamedOnlyLtsRejection.PARSE_FAILURE);
    assertEquals(patternCacheSize, RuntimeCompiler.cacheSize());
    assertEquals(structuralCacheSize, RuntimeCompiler.structuralCacheSize());
  }

  @Test
  void repeatedAdmissionsReturnIndependentMatchers() {
    RuntimeCompiler.NamedOnlyLtsCompilation first =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence("(?<value>\\S+)", 0);
    RuntimeCompiler.NamedOnlyLtsCompilation second =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence("(?<value>\\S+)", 0);

    assertNotNull(first.matcher());
    assertNotNull(second.matcher());
    assertNotSame(first.matcher(), second.matcher());
    assertEquals("first", first.matcher().match("first").group("value"));
    assertEquals("second", second.matcher().match("second").group("value"));
  }

  @Test
  void preservesOriginalNamedIndexWhileProjectingAnUnnamedCapture() {
    String pattern = "(x)(?<value>\\S+)";
    RuntimeCompiler.NamedOnlyLtsCompilation direct =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence(pattern, 0);
    ReggieMatcher legacy = Reggie.compile(pattern, ReggieOptions.builder().namedOnly().build());

    MatchResult directResult = direct.matcher().match("xvalue");
    MatchResult legacyResult = legacy.match("xvalue");
    assertEquals(2, directResult.groupCount());
    assertNull(directResult.group(1));
    assertEquals("value", directResult.group(2));
    assertEquals(directResult.group("value"), legacyResult.group("value"));
    assertEquals(directResult.start(2), legacyResult.start(2));
    assertEquals(directResult.end(2), legacyResult.end(2));
  }

  @Test
  void admittedMatchersRemainIndependentUnderConcurrentUse() throws Exception {
    LinearTokenSequenceMatcher shared =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence("(?<value>\\S+)", 0).matcher();
    ExecutorService executor = Executors.newFixedThreadPool(4);
    CountDownLatch done = new CountDownLatch(4);
    for (int thread = 0; thread < 4; thread++) {
      int id = thread;
      executor.execute(
          () -> {
            LinearTokenSequenceMatcher independent =
                RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence("(?<value>\\S+)", 0)
                    .matcher();
            for (int iteration = 0; iteration < 100; iteration++) {
              assertEquals("shared", shared.match("shared").group("value"));
              assertEquals("value" + id, independent.match("value" + id).group("value"));
            }
            done.countDown();
          });
    }
    assertEquals(true, done.await(10, TimeUnit.SECONDS));
    executor.shutdownNow();
  }

  private static void assertRejected(
      String source, int flags, RuntimeCompiler.NamedOnlyLtsRejection expected) {
    RuntimeCompiler.NamedOnlyLtsCompilation compilation =
        RuntimeCompiler.tryCompileNamedOnlyLinearTokenSequence(source, flags);
    assertNull(compilation.matcher());
    assertEquals(expected, compilation.rejection());
  }
}
