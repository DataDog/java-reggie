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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Characterization tests for nullable/optional alternation patterns routed to PIKEVM_CAPTURE.
 * Asserts that PikeVM stays native (no fallback to JavaRegexFallbackMatcher) and agrees with JDK on
 * find() start/end positions.
 */
public class PikeVMNullableAlternationTest {

  static Stream<Arguments> nullableAlternationPatterns() {
    return Stream.of(
        // optional quantifier in first alternation branch
        Arguments.of("a{0,3}|b", ""),
        Arguments.of("a{0,3}|b", "a"),
        Arguments.of("a{0,3}|b", "b"),
        Arguments.of("a{0,3}|b", "aaa"),
        Arguments.of("a{0,3}|b", "c"),
        // empty trailing branch (nullable)
        Arguments.of("a|", ""),
        Arguments.of("a|", "a"),
        Arguments.of("a|", "b"),
        // non-nullable branch with optional suffix
        Arguments.of("c.{0,3}|b", ""),
        Arguments.of("c.{0,3}|b", "b"),
        Arguments.of("c.{0,3}|b", "c"),
        Arguments.of("c.{0,3}|b", "ccc"),
        Arguments.of("c.{0,3}|b", "cab"),
        // leading end-anchor branch
        Arguments.of("a|$", ""),
        Arguments.of("a|$", "a"),
        Arguments.of("a|$", "b"),
        // nullable quantifier in second branch
        Arguments.of("x|y{0,2}", ""),
        Arguments.of("x|y{0,2}", "a"),
        Arguments.of("x|y{0,2}", "xy"),
        Arguments.of("x|y{0,2}", "x"),
        // nested group in alternation
        Arguments.of("(ab|a)|c", ""),
        Arguments.of("(ab|a)|c", "a"),
        Arguments.of("(ab|a)|c", "b"),
        Arguments.of("(ab|a)|c", "ab"),
        Arguments.of("(ab|a)|c", "aaa"),
        Arguments.of("(ab|a)|c", "c"),
        Arguments.of("(ab|a)|c", "cab"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("nullableAlternationPatterns")
  void nullableAlternation_usesNativeAndAgreesWithJdk(String pat, String in) throws Exception {
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat + " but got: " + reggie.getClass().getSimpleName());

    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;

    boolean jdkFind = jdk.matcher(in).find();
    boolean reggieFind = reggie.find(in);
    assertEquals(jdkFind, reggieFind, "find() " + ctx);

    if (jdkFind) {
      Matcher jm = jdk.matcher(in);
      jm.find();
      MatchResult rm = reggie.findMatch(in);
      assertEquals(jm.start(), rm.start(0), "findMatch() start " + ctx);
      assertEquals(jm.end(), rm.end(0), "findMatch() end " + ctx);
    }
  }
}
