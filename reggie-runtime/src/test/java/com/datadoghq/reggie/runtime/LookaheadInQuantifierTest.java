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
 * Tests for issue #28: lookahead inside a quantified group.
 *
 * <p>Correctness is verified by comparing Reggie's output against JDK {@link
 * java.util.regex.Pattern}. Currently these patterns fall back to JDK because the native NFA engine
 * handles assertions inside loop iterations incorrectly (e.g. {@code find("1")} returns false for
 * {@code (?:(?=\d)\d)+}). The fallback is removed and the {@link
 * #lookaheadInQuantifierUsesFallback()} test must be flipped once NFABytecodeGenerator is fixed.
 */
class LookaheadInQuantifierTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  // ── #28: correctness via JDK fallback ────────────────────────────────────

  @Test
  void lookaheadInsideRepeatingGroupPlus() {
    // (?:(?=\d)\d)+ — group with lookahead repeated one or more times
    String pat = "(?:(?=\\d)\\d)+";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("123").find(), reg.find("123"), pat + " on '123'");
    assertEquals(jdk.matcher("abc").find(), reg.find("abc"), pat + " on 'abc'");
    assertEquals(jdk.matcher("1").find(), reg.find("1"), pat + " on '1'");
  }

  @Test
  void lookaheadInsideCapturingGroupRepeated() {
    // (a(?=b)){2} — capturing group with lookahead repeated exactly 2 times
    String pat = "(a(?=b)){2}";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("abab").find(), reg.find("abab"), pat + " find on 'abab'");
    assertEquals(jdk.matcher("ab").find(), reg.find("ab"), pat + " find on 'ab'");
    assertEquals(jdk.matcher("aa").find(), reg.find("aa"), pat + " find on 'aa'");
  }

  @Test
  void repeatingGroupEndsWithLookahead() {
    // (?:a(?=b)){2}b — non-capturing group with trailing lookahead, repeated 2 times
    String pat = "(?:a(?=b)){2}b";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("abab").find(), reg.find("abab"), pat + " on 'abab'");
    assertEquals(jdk.matcher("ab").find(), reg.find("ab"), pat + " on 'ab'");
  }

  @Test
  void bareRepeatedLookahead() {
    // (?=a){3} — bare lookahead repeated 3 times (zero-width, should match at 'a')
    String pat = "(?=a){3}";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("a").find(), reg.find("a"), pat + " on 'a'");
    assertEquals(jdk.matcher("b").find(), reg.find("b"), pat + " on 'b'");
  }

  @Test
  void lookaheadInOptionalGroup() {
    // (?:(?=a)a)? — optional group containing a lookahead
    String pat = "(?:(?=a)a)?";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("a").find(), reg.find("a"), pat + " on 'a'");
    assertEquals(jdk.matcher("b").find(), reg.find("b"), pat + " on 'b'");
  }

  @Test
  void negativeLookaheadInsideRepeatingGroup() {
    // (?:(?!\d)\w)+ — non-digit word characters (negative lookahead in quantified group)
    String pat = "(?:(?!\\d)\\w)+";
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    assertEquals(jdk.matcher("abc").find(), reg.find("abc"), pat + " on 'abc'");
    assertEquals(jdk.matcher("123").find(), reg.find("123"), pat + " on '123'");
    assertEquals(jdk.matcher("abc123").find(), reg.find("abc123"), pat + " on 'abc123'");
  }

  @Test
  void lookaheadInQuantifierUsesFallback() {
    // Issue #28: NFA engine still mis-handles assertions across loop iterations.
    ReggieMatcher m = Reggie.compile("(?:(?=\\d)\\d)+");
    assertTrue(m instanceof JavaRegexFallbackMatcher, "(?:(?=\\d)\\d)+ must use JDK fallback");
  }
}
