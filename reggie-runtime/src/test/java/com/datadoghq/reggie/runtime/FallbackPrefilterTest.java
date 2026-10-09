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
import static org.junit.jupiter.api.Assertions.assertSame;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieFlags;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The JDK fallback is returned without a prefilter: the prefilter literal comes from reggie's own
 * parse, which may misread a construct the JDK accepts.
 */
class FallbackPrefilterTest {

  // Goes to the JDK fallback; reggie's parse reads \v as the letter 'v' (B1), so a prefilter built
  // from that parse requires a 'v' the input never contains.
  private static final String PATTERN = "(?:(a)|b)+\\1\\v";
  private static final String INPUT = "aa\u000B";

  private static final ReggieOptions FALLBACK = ReggieOptions.builder().allowJdkFallback().build();

  @Test
  void fallbackFindEqualsJdk() {
    ReggieMatcher m = Reggie.compileAllowingFallback(PATTERN);
    Matcher j = Pattern.compile(PATTERN).matcher(INPUT);
    boolean jdkFound = j.find();
    MatchResult r = m.findMatchFrom(INPUT, 0);
    assertEquals(jdkFound, r != null);
    assertNotNull(r);
    assertEquals(j.start(), r.start());
    assertEquals(j.end(), r.end());
  }

  @Test
  void fallbackIsNotWrapped() {
    assertSame(JavaRegexFallbackMatcher.class, Reggie.compileAllowingFallback(PATTERN).getClass());
    assertSame(
        JavaRegexFallbackMatcher.class,
        Reggie.compile(PATTERN, ReggieFlags.DOTALL, FALLBACK).getClass());
    assertSame(
        JavaRegexFallbackMatcher.class,
        Reggie.cached("FallbackPrefilterTest", PATTERN, FALLBACK).getClass());
  }
}
