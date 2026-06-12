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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression coverage for B12: quantifier nodes in the prefix before a capturing backref group.
 * After the fix these patterns route natively via VARIABLE_CAPTURE_BACKREF.
 *
 * <p>Patterns require variable-length group content (e.g. {@code (b+)}, {@code ([0-9]+)}) so they
 * are detected as VARIABLE_CAPTURE_BACKREF; the prefix quantifier (e.g. {@code a*}, {@code x{3}})
 * is what previously caused the fallback.
 */
class B12QuantifierPrefixTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  static Stream<Arguments> quantifierPrefixPatterns() {
    return Stream.of(
        Arguments.of("a*(b+)\\1", "bb"),
        Arguments.of("a*(b+)\\1", "abb"),
        Arguments.of("a*(b+)\\1", "aabb"),
        Arguments.of("a*(b+)\\1", "aac"),
        Arguments.of("a+(b+)\\1", "abb"),
        Arguments.of("a+(b+)\\1", "aabb"),
        Arguments.of("a+(b+)\\1", "bb"),
        Arguments.of("[0-9]*([a-z]+)\\1", "aa"),
        Arguments.of("[0-9]*([a-z]+)\\1", "1aa"),
        Arguments.of("[0-9]*([a-z]+)\\1", "123aa"),
        Arguments.of("[0-9]*([a-z]+)\\1", "ab"),
        Arguments.of("x{3}(a+)\\1", "xxxaa"),
        Arguments.of("x{3}(a+)\\1", "xxaa"),
        Arguments.of("x{3}(a+)\\1", "xxxxaa"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("quantifierPrefixPatterns")
  void agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat, WITH_FALLBACK);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
    Matcher jm = jdk.matcher(in);
    boolean jFound = jm.find();
    MatchResult rf = reggie.findMatch(in);
    assertEquals(jFound, rf != null, "findMatch() null " + ctx);
    if (jFound && rf != null) {
      assertEquals(jm.start(), rf.start(), "findMatch() start " + ctx);
      assertEquals(jm.end(), rf.end(), "findMatch() end " + ctx);
      if (jm.groupCount() >= 1 && jm.start(1) != -1 && rf.start(1) != -1) {
        assertEquals(jm.start(1), rf.start(1), "g1 start " + ctx);
        assertEquals(jm.end(1), rf.end(1), "g1 end " + ctx);
      }
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("quantifierPrefixPatterns")
  void routesToNative(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
