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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Acceptance tests for the unbounded-quantifier prefix loop fixes described in the spec
 * 2026-06-19-in-the-unbounded-quantifier-prefix-loop.
 *
 * <p>Group A: non-atomic multi-character prefix repetition — each attempted repetition of the
 * greedy loop must be atomic so partial matches do not advance the group-start variable.
 *
 * <p>Group B: nullable unbounded prefix quantifier — patterns whose child can match the empty
 * string must not spin; they must terminate and agree with java.util.regex.
 *
 * <p>Group C: routing comment accuracy for {@code \Z} pure-anchor alternation (already covered by
 * AnchorAlternationPikeVMTest; confirmatory checks added here).
 *
 * <p>Group D: routing comment accuracy for simple quantified capturing-group alternation (already
 * covered by QuantifiedGroupAltPriorityTest; confirmatory checks added here).
 *
 * <p>Group E: no regression on previously-supported single-char or multi-char non-nullable
 * prefixes.
 */
class UnboundedQuantifierPrefixLoopTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  /** Timeout applied to every group-B assertion to catch infinite-loop regressions. */
  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  // ---------------------------------------------------------------------------
  // Group A — Non-atomic multi-character child prefix repetition
  // ---------------------------------------------------------------------------

  static Stream<Arguments> groupAPatterns() {
    return Stream.of(
        // (?:ab)* prefix: partial 'a' match must not skip a valid start
        Arguments.of("(?:ab)*(c+)\\1", "abc"),
        Arguments.of("(?:ab)*(c+)\\1", "ababcc"),
        Arguments.of("(?:ab)*(c+)\\1", "abacc"),
        // (?:ab)* prefix: 'a' from 'ab' could be skipped without atomicity
        Arguments.of("(?:ab)*(a+)\\1", "abaa"),
        Arguments.of("(?:ab)*(a+)\\1", "abaaaa"),
        Arguments.of("(?:ab)*(a+)\\1", "aaaa"),
        // (?:xy)* prefix: valid match requires stopping at un-advanced position
        Arguments.of("(?:xy)*(y+)\\1", "xyyy"),
        Arguments.of("(?:xy)*(y+)\\1", "yy"),
        Arguments.of("(?:xy)*(y+)\\1", "xyy"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupAPatterns")
  void groupA_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  // ---------------------------------------------------------------------------
  // Group B — Nullable unbounded prefix (no infinite loop / correctness)
  // ---------------------------------------------------------------------------

  static Stream<Arguments> groupBPatterns() {
    return Stream.of(
        // (?:a*)* — nullable child inside *
        Arguments.of("(?:a*)*(b+)\\1", "bb"),
        Arguments.of("(?:a*)*(b+)\\1", "abb"),
        Arguments.of("(?:a*)*(b+)\\1", "aabb"),
        Arguments.of("(?:a*)*(b+)\\1", "bbbb"),
        Arguments.of("(?:a*)*(b+)\\1", ""),
        Arguments.of("(?:a*)*(b+)\\1", "b"),
        // (?:a?)* — nullable child (optional single char) inside *
        Arguments.of("(?:a?)*(b+)\\1", "bb"),
        Arguments.of("(?:a?)*(b+)\\1", "aaabb"),
        // (?:a*)+ — nullable child inside +
        Arguments.of("(?:a*)+(b+)\\1", "bb"),
        Arguments.of("(?:a*)+(b+)\\1", "abb"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupBPatterns")
  void groupB_terminatesAndAgreesWithJdk(String pat, String in) {
    assertTimeoutPreemptively(
        TIMEOUT,
        () -> assertAgrees(pat, in),
        "timed out (possible infinite loop) for pat=" + pat + " in=" + repr(in));
  }

  // ---------------------------------------------------------------------------
  // Group C — \Z pure-anchor alternation routes to native (not JDK fallback)
  // ---------------------------------------------------------------------------

  static Stream<Arguments> groupCPatterns() {
    return Stream.of(
        Arguments.of("\\Z|abc", ""),
        Arguments.of("\\Z|abc", "abc"),
        Arguments.of("\\Z|abc", "xyz"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupCPatterns")
  void groupC_routesToNative(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "\\Z pure-anchor alternation should route to native matcher: " + pat);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupCPatterns")
  void groupC_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  // ---------------------------------------------------------------------------
  // Group D — Simple quantified capturing-group alternation routes to native
  // ---------------------------------------------------------------------------

  static Stream<Arguments> groupDPatterns() {
    return Stream.of(
        Arguments.of("(a|b)+x", "ax"),
        Arguments.of("(a|b)+x", "bx"),
        Arguments.of("(a|b)+x", "abx"),
        Arguments.of("(a|b)+x", "x"),
        Arguments.of("(a|b)+x", "bbx"),
        Arguments.of("(a|b)+x", "aaax"),
        Arguments.of("(a|b)*x", "x"),
        Arguments.of("(a|b)*x", "ax"),
        Arguments.of("(a|b)*x", "abx"),
        Arguments.of("(a|b){2,3}x", "aax"),
        Arguments.of("(a|b){2,3}x", "abx"),
        Arguments.of("(a|b){2,3}x", "ababx"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupDPatterns")
  void groupD_routesToNative(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "simple quantified capturing-group alternation should route to native matcher: " + pat);
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupDPatterns")
  void groupD_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  // ---------------------------------------------------------------------------
  // Group E — No regression on previously-supported non-nullable prefixes
  // ---------------------------------------------------------------------------

  static Stream<Arguments> groupEPatterns() {
    return Stream.of(
        // single-char quantifier prefix
        Arguments.of("a*(b+)\\1", "bb"),
        Arguments.of("a*(b+)\\1", "abb"),
        Arguments.of("a*(b+)\\1", "aabb"),
        Arguments.of("a*(b+)\\1", "b"),
        Arguments.of("a*(b+)\\1", ""),
        // char-class quantifier prefix
        Arguments.of("[ab]*(c+)\\1", "cc"),
        Arguments.of("[ab]*(c+)\\1", "acc"),
        Arguments.of("[ab]*(c+)\\1", "abcc"),
        Arguments.of("[ab]*(c+)\\1", "cd"),
        // multi-char non-nullable prefix (+ quantifier)
        Arguments.of("(?:ab)+(c+)\\1", "abcc"),
        Arguments.of("(?:ab)+(c+)\\1", "ababcc"),
        Arguments.of("(?:ab)+(c+)\\1", "cc"),
        Arguments.of("(?:ab)+(c+)\\1", "c"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupEPatterns")
  void groupE_agreesWithJdk(String pat, String in) {
    assertAgrees(pat, in);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void assertAgrees(String pat, String in) {
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
    }
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
