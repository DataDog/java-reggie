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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Regression tests for FallbackPatternDetector conditions eliminated by routing fixes. */
public class FallbackDetectorBugFixTest {

  /**
   * Patterns with a capturing group inside a quantified section. After the routing fix these are
   * handled natively (PIKEVM_CAPTURE for plain patterns, OPTIMIZED_NFA_WITH_LOOKAROUND for
   * assertion patterns) instead of falling back to JDK.
   */
  static Stream<Arguments> capturingGroupInQuantifiedSection() {
    return Stream.of(
        // Lookbehind + group inside quantifier → DFA_UNROLLED_WITH_ASSERTIONS → now
        // OPTIMIZED_NFA_WITH_LOOKAROUND
        Arguments.of("(?<=a)(x)+", "axx"),
        Arguments.of("(?<=a)(x)+", "bxx"),
        Arguments.of("(?<=a)(\\w)+", "ahello"),
        // Lookbehind with larger DFA (>= 20 states, DFA_SWITCH_WITH_ASSERTIONS range) + group
        // inside quantifier → now OPTIMIZED_NFA_WITH_LOOKAROUND (intercepted before DFA selection)
        Arguments.of("(?<=[a-z0-9A-Z]{20})(\\w)+", "abcdefghijklmnopqrstuvwxyz"),
        Arguments.of("(?<=[a-z0-9A-Z]{20})(\\w)+", "abcdefghijklmnopqrstu"),
        Arguments.of("(?<=[a-z0-9A-Z]{20})(\\w)+", "abcdefghijklmnopqrst1"),
        // Non-assertion group inside quantifier → now PIKEVM_CAPTURE
        Arguments.of("(a)+b", "aaab"),
        Arguments.of("(a)+b", "b"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("capturingGroupInQuantifiedSection")
  void capturingGroupInQuantifiedSection_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);

    String ctx = "pat=" + pat + " in=" + in;

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    // match() group spans
    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    assertEquals(jdkM, rm != null, "match() null check " + ctx);
    if (jdkM) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rm.start(g), "match() g" + g + " start " + ctx);
        assertEquals(jm.end(g), rm.end(g), "match() g" + g + " end " + ctx);
      }
    }

    // findMatch() group spans
    Matcher jmf = jdk.matcher(in);
    boolean jdkF = jmf.find();
    MatchResult rfm = reggie.findMatch(in);
    assertEquals(jdkF, rfm != null, "findMatch() null check " + ctx);
    if (jdkF) {
      for (int g = 0; g <= jmf.groupCount(); g++) {
        assertEquals(jmf.start(g), rfm.start(g), "findMatch() g" + g + " start " + ctx);
        assertEquals(jmf.end(g), rfm.end(g), "findMatch() g" + g + " end " + ctx);
      }
    }
  }

  static Stream<Arguments> variableCaptureBackrefBoundedGroup() {
    return Stream.of(
        Arguments.of("(-{0,3}):\\1", "---:---"),
        Arguments.of("(-{0,3}):\\1", "----:----"),
        Arguments.of("(\\w{1,4})=\\1", "abc=abc"),
        Arguments.of("(\\w{1,4})=\\1", "abcde=abcde"),
        Arguments.of("(\\w{1,4})=\\1", " abc=abc"),
        Arguments.of("(\\w{1,4})=\\1", " abcde=abcde"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("variableCaptureBackrefBoundedGroup")
  void variableCaptureBackrefBoundedGroup_matchesAgreesWithJdk(String pat, String in)
      throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  static Stream<Arguments> variableCaptureBackrefNullableGroup() {
    return Stream.of(
        Arguments.of("(a*)=\\1", "abc=abc"),
        Arguments.of("(a*)=\\1", "="),
        Arguments.of("(a*)=\\1", "a=a"),
        Arguments.of("(-*):\\1", "---:---"),
        Arguments.of("(b*)\\1", "bb"),
        Arguments.of("(b*)\\1", ""),
        // outer + has min=1 but content a? is nullable → triggers isGroupContentNullable path
        Arguments.of("(a?)+\\1", "aa"),
        Arguments.of("(a?)+\\1", ""));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("variableCaptureBackrefNullableGroup")
  void variableCaptureBackrefNullableGroup_matchesAgreesWithJdk(String pat, String in)
      throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  static Stream<Arguments> nestedQuantifiedGroupsWithAlt() {
    return Stream.of(
        Arguments.of("((a|b)+)*", "abab"),
        Arguments.of("((a|b)+)*", "ccc"),
        Arguments.of("((a|bc)+)*", "abcabc"),
        Arguments.of("((a|bc)+)*x", "abcx"),
        Arguments.of("((a|b)*)+", "aab"),
        Arguments.of("((a|b)+)*", ""),
        Arguments.of("(((a|b)c)+)*", "acbc")); // alternation inside ConcatNode level
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("nestedQuantifiedGroupsWithAlt")
  void nestedQuantifiedGroupsWithAlt_matchesAgreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  static Stream<Arguments> prefixOverlapAlternation() {
    return Stream.of(
        Arguments.of("fo|foo", "foo"),
        Arguments.of("fo|foo", "fo"),
        Arguments.of("a|ab", "ab"),
        Arguments.of("a|ab", "a"),
        Arguments.of("cat|catch", "catch"),
        Arguments.of("cat|catch", "cat"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("prefixOverlapAlternation")
  void prefixOverlapAlternation_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    // Verify the first-alternative (JDK-compatible) match span, not longest-match.
    // fo|foo on "foo": JDK finds "fo" at [0,2], not "foo" at [0,3].
    Matcher jmf = jdk.matcher(in);
    boolean jdkF = jmf.find();
    MatchResult rfm = reggie.findMatch(in);
    assertEquals(jdkF, rfm != null, "findMatch() null check " + ctx);
    if (jdkF) {
      assertEquals(jmf.start(0), rfm.start(0), "findMatch() start " + ctx);
      assertEquals(jmf.end(0), rfm.end(0), "findMatch() end " + ctx);
    }
  }

  static Stream<Arguments> variableCaptureBackrefPrefix() {
    return Stream.of(
        Arguments.of("c(.*)\\1", "cabc abc"),
        Arguments.of("c(.*)\\1", "c"),
        Arguments.of("ab(.+):\\1", "abfoo:foo"),
        Arguments.of("ab(.+):\\1", "foo:foo"),
        Arguments.of("ab(.+):\\1", "abxyz:abc"),
        Arguments.of("c(.+):\\1", " cfoo:foo"),
        Arguments.of("c(.+):\\1", " cxy:xz"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("variableCaptureBackrefPrefix")
  void variableCaptureBackrefPrefix_matchesAgreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }
}
