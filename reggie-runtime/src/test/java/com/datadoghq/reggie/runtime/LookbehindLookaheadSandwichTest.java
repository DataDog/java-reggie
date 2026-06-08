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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for issue #31: combined lookbehind + lookahead ("sandwich") patterns.
 *
 * <p>These patterns are of the form {@code (?<=prefix)content(?=suffix)} and should produce correct
 * results using the native Reggie engine without JDK fallback.
 */
class LookbehindLookaheadSandwichTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── Basic sandwich: (?<=x)a(?=b) ─────────────────────────────────────────

  @Test
  void sandwichLookaroundFind() {
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(?<=x)a(?=b)");
    ReggieMatcher reg = Reggie.compile("(?<=x)a(?=b)");
    assertEquals(jdk.matcher("xab").find(), reg.find("xab"), "(?<=x)a(?=b) on 'xab'");
    assertEquals(jdk.matcher("ab").find(), reg.find("ab"), "(?<=x)a(?=b) on 'ab' (no lookbehind)");
    assertEquals(jdk.matcher("xa").find(), reg.find("xa"), "(?<=x)a(?=b) on 'xa' (no lookahead)");
    assertEquals(
        jdk.matcher("xac").find(), reg.find("xac"), "(?<=x)a(?=b) on 'xac' (wrong lookahead)");
  }

  @Test
  void sandwichBracketPattern() {
    // (?<=\[)[^\]]+(?=\]) — content between square brackets
    String pat = "(?<=\\[)[^\\]]+(?=\\])";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("[value]").find(), reg.find("[value]"), pat + " on '[value]'");
    assertEquals(jdk.matcher("value").find(), reg.find("value"), pat + " on 'value'");
    assertEquals(jdk.matcher("[value").find(), reg.find("[value"), pat + " on '[value' (no close)");
    assertEquals(jdk.matcher("value]").find(), reg.find("value]"), pat + " on 'value]' (no open)");
  }

  @Test
  void sandwichNegativeLookbehindPositiveLookahead() {
    // (?<!\d)\w+(?=\s) — word not preceded by digit, followed by space
    String pat = "(?<!\\d)\\w+(?=\\s)";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(
        jdk.matcher("hello world").find(), reg.find("hello world"), pat + " on 'hello world'");
    assertEquals(jdk.matcher("3abc def").find(), reg.find("3abc def"), pat + " on '3abc def'");
  }

  @Test
  void sandwichPositiveLookbehindNegativeLookahead() {
    // (?<=\d)[a-z]{1,4}(?!\d) — letters after digit, not before digit
    String pat = "(?<=\\d)[a-z]{1,4}(?!\\d)";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("3abc").find(), reg.find("3abc"), pat + " on '3abc'");
    assertEquals(jdk.matcher("3abc4").find(), reg.find("3abc4"), pat + " on '3abc4'");
  }

  @Test
  void sandwichNegativeBoth() {
    // (?<!\d)\w+(?!\d) — word not preceded or followed by digit
    String pat = "(?<!\\d)\\w+(?!\\d)";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("hello").find(), reg.find("hello"), pat + " on 'hello'");
    assertEquals(jdk.matcher("abc").find(), reg.find("abc"), pat + " on 'abc'");
  }

  // ── Verify native engine is used (not JavaRegexFallbackMatcher) ───────────

  @Test
  void sandwichUsesNativeEngine() {
    ReggieMatcher m = Reggie.compile("(?<=\\[)[^\\]]+(?=\\])");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "(?<=\\[)[^\\]]+(?=\\]) must use native Reggie engine, not java.util.regex fallback");
  }

  @Test
  void simpleSandwichUsesNativeEngine() {
    ReggieMatcher m = Reggie.compile("(?<=x)a(?=b)");
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "(?<=x)a(?=b) must use native Reggie engine, not java.util.regex fallback");
  }
}
