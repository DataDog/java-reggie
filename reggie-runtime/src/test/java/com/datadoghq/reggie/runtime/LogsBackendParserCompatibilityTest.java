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

class LogsBackendParserCompatibilityTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void atomicGroupActsLikeNonCapturingGroupForLinearEngine() {
    ReggieMatcher matcher = Reggie.compile("(?>\\d+)-(?>[a-z]+)");

    assertTrue(matcher.matches("123-abc"));
    assertFalse(matcher.matches("123-ABC"));
    assertFalse(matcher.matches("abc-123"));
  }

  @Test
  void quotedLiteralEscapesDateSeparatorsFromPatternQuote() {
    ReggieMatcher matcher = Reggie.compile("\\d{2}\\Q/\\E(?:Jan|Feb)\\Q/\\E\\d{4}\\Q:\\E\\d{2}");

    assertTrue(matcher.matches("12/Jan/2026:09"));
    assertTrue(matcher.matches("07/Feb/2026:23"));
    assertFalse(matcher.matches("12XJan/2026:09"));
    assertFalse(matcher.matches("12/Mar/2026:09"));
  }

  @Test
  void quotedLiteralTreatsRegexMetacharactersAsLiterals() {
    ReggieMatcher matcher = Reggie.compile("prefix\\Q.*+?[]{}()|^$\\Esuffix");

    assertTrue(matcher.matches("prefix.*+?[]{}()|^$suffix"));
    assertFalse(matcher.matches("prefixAAAAAsuffix"));
  }

  @Test
  void quotedLiteralCanRunToEndOfPattern() {
    ReggieMatcher matcher = Reggie.compile("foo\\Q.bar");

    assertTrue(matcher.matches("foo.bar"));
    assertFalse(matcher.matches("fooXbar"));
  }
}
