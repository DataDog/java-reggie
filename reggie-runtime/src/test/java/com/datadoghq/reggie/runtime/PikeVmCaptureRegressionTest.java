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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.UnsupportedPatternException;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class PikeVmCaptureRegressionTest {

  private static void assertRoute(String pattern, PatternAnalyzer.MatchingStrategy expected)
      throws Exception {
    PatternAnalyzer.MatchingStrategy actual = StrategyCorrectnessMetaTest.routeOf(pattern);
    // BITSTATE_CAPTURE is a post-hoc substitution for PIKEVM_CAPTURE (see
    // PatternAnalyzer.isBitStateEligible) over the same leftmost-greedy, native group-extraction
    // semantics — accept either so these guards still exercise the intended fix.
    PatternAnalyzer.MatchingStrategy normalizedExpected =
        expected == PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE
                && actual == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE
            ? actual
            : expected;
    assertEquals(
        normalizedExpected,
        actual,
        "routing changed for /" + pattern + "/ — fix would not be exercised");
  }

  private static void assertAgrees(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    boolean jdkMatches = jdk.matcher(input).matches();
    assertEquals(
        jdkMatches,
        reggie.matches(input),
        "matches() mismatch for /" + pattern + "/ on \"" + input + "\"");
    Matcher jm = jdk.matcher(input);
    boolean jdkFind = jm.find();
    assertEquals(
        jdkFind, reggie.find(input), "find() mismatch for /" + pattern + "/ on \"" + input + "\"");
    MatchResult r = reggie.findMatch(input);
    if (jdkFind) {
      assertEquals(
          List.of(jm.start(), jm.end()),
          r == null ? null : List.of(r.start(), r.end()),
          "findMatch span mismatch for /" + pattern + "/ on \"" + input + "\"");
    } else {
      assertEquals(null, r, "findMatch should be null for /" + pattern + "/ on \"" + input + "\"");
    }
  }

  private static void assertGroupsAgree(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatches = jm.matches();
    MatchResult rm = reggie.match(input);
    assertEquals(
        jdkMatches, rm != null, "match() boolean for /" + pattern + "/ on \"" + input + "\"");
    if (jdkMatches) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(
            List.of(jm.start(g), jm.end(g)),
            List.of(rm.start(g), rm.end(g)),
            "group " + g + " span for /" + pattern + "/ on \"" + input + "\"");
      }
    }
  }

  @Test
  void anchorInRepeatedGroup() throws Exception {
    // PatternAnalyzer still routes to PIKEVM_CAPTURE, but FallbackPatternDetector B3 guard
    // (anchor-in-quantifier, now active for all strategies) forces JDK fallback. Result agrees.
    assertRoute("1|(0|^a?){3}", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    ReggieMatcher m = Reggie.compile("1|(0|^a?){3}", opts);
    assertTrue(
        m instanceof JavaRegexFallbackMatcher,
        "B3 anchor-in-quantifier guard applies to PIKEVM_CAPTURE — expected JDK fallback");
    Pattern jdk = Pattern.compile("1|(0|^a?){3}");
    assertEquals(jdk.matcher("a").matches(), m.matches("a"), "matches() agrees with JDK");
    assertEquals(jdk.matcher("a").find(), m.find("a"), "find() agrees with JDK");
  }

  @Test
  void trailingEmptyIterationGroup() throws Exception {
    assertRoute("^(?:)a|(.*[_]*)+", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    assertGroupsAgree("^(?:)a|(.*[_]*)+", "-");
    assertGroupsAgree("^(?:)a|(.*[_]*)+", "0");
    assertGroupsAgree("^(?:)a|(.*[_]*)+", "1");
  }

  // ---- B16 PIKEVM_CAPTURE bypass regression ----

  @Test
  void b16NullableContent_pikeVmCapture_throwsWithoutFallback() {
    // ((x*){0,}|a)(c|bcd): nullable group content (x*) under nullable outer quantifier ({0,})
    // triggers B16. Must throw UnsupportedPatternException, not silently route to PikeVM.
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("((x*){0,}|a)(c|bcd)"));
  }

  @Test
  void b16NullableContent_pikeVmCapture_agreesWithJdkWhenFallbackAllowed() {
    String pat = "((x*){0,}|a)(c|bcd)";
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    ReggieMatcher m = RuntimeCompiler.compile(pat, opts);
    Pattern jdk = Pattern.compile(pat);
    for (String input : new String[] {"xbc", "ac", "abcd", "", "bcd", "xc"}) {
      Matcher jm = jdk.matcher(input);
      boolean jdkF = jm.find();
      assertEquals(jdkF, m.find(input), "find() for \"" + input + "\"");
    }
  }

  // ---- Controls ----

  @Test
  void control_anchorLoop_terminates() throws Exception {
    // Anchor-loop patterns are caught by B16 or B3 guards and must throw cleanly rather than
    // hang. (^)* triggers B16 (nullable capturing group under nullable quantifier);
    // (?:^)* triggers B3 (any anchor inside a quantifier).
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("(^)*a"));
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("(?:^)*a"));
    // (^)*a with fallback allowed: verify compilation terminates and agrees with JDK.
    Pattern jdk = Pattern.compile("(^)*a");
    ReggieMatcher reggie = Reggie.compileAllowingFallback("(^)*a");
    assertEquals(jdk.matcher("a").find(), reggie.find("a"));
  }

  @Test
  void control_anchorAtStart() throws Exception {
    assertAgrees("^a", "a");
    assertAgrees("^a", "ba");
  }

  // ---- Lazy quantifier PIKEVM_CAPTURE correctness ----

  @Test
  void lazyShortestMatch_star() throws Exception {
    // a.*?b must stop at the first 'b', not consume the whole string
    assertRoute("a.*?b", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    String pattern = "a.*?b";
    String input = "axxbxxb";
    Pattern jdk = Pattern.compile(pattern);
    Matcher jm = jdk.matcher(input);
    assertTrue(jm.find(), "JDK must find a match");
    ReggieMatcher reggie = Reggie.compile(pattern);
    MatchResult r = reggie.findMatch(input);
    assertNotEquals(null, r, "Reggie must find a match in \"" + input + "\"");
    assertEquals(
        List.of(jm.start(), jm.end()),
        List.of(r.start(), r.end()),
        "lazy a.*?b on \"" + input + "\" must stop at first b");
  }

  @Test
  void lazyShortestMatch_plus() throws Exception {
    // <.+?> must stop at the first '>'
    assertRoute("<.+?>", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    String pattern = "<.+?>";
    String input = "<a><b>";
    Pattern jdk = Pattern.compile(pattern);
    Matcher jm = jdk.matcher(input);
    assertTrue(jm.find(), "JDK must find a match");
    ReggieMatcher reggie = Reggie.compile(pattern);
    MatchResult r = reggie.findMatch(input);
    assertNotEquals(null, r, "Reggie must find a match in \"" + input + "\"");
    assertEquals(
        List.of(jm.start(), jm.end()),
        List.of(r.start(), r.end()),
        "lazy <.+?> on \"" + input + "\" must stop at first >");
  }

  @Test
  void lazyCountedQuantifier() throws Exception {
    // a{2,5}? on "aaaaa" — find() must return a match of length 2 (minimum)
    assertRoute("a{2,5}?", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    String pattern = "a{2,5}?";
    Pattern jdk = Pattern.compile(pattern);

    assertTrue(Reggie.compile(pattern).matches("aa"), "a{2,5}? matches \"aa\"");
    assertTrue(Reggie.compile(pattern).matches("aaa"), "a{2,5}? matches \"aaa\"");

    String input = "aaaaa";
    Matcher jm = jdk.matcher(input);
    assertTrue(jm.find());
    ReggieMatcher reggie = Reggie.compile(pattern);
    MatchResult r = reggie.findMatch(input);
    assertNotEquals(null, r, "Reggie must find a match");
    assertEquals(
        List.of(jm.start(), jm.end()),
        List.of(r.start(), r.end()),
        "a{2,5}? on \"aaaaa\" must match minimum (2 chars)");
  }

  @Test
  void lazyGreedyOverlapGiveBack() throws Exception {
    assertRoute("(a*?)(\\d+)", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    assertGroupsAgree("(a*?)(\\d+)", "aaa123");
    assertGroupsAgree("(a+?)(a+)", "aaa");
  }

  @Test
  void lazyShortestMatch_star_onSelf() throws Exception {
    // a*?a on "baaa": lazy star must prefer the shortest match (at position 1, matching one 'a'),
    // not consume all 'a's greedily.  This would fail if ThompsonBuilder emits greedy epsilon
    // order.
    assertRoute("a*?a", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    String pattern = "a*?a";
    String input = "baaa";
    Pattern jdk = Pattern.compile(pattern);
    Matcher jm = jdk.matcher(input);
    assertTrue(jm.find(), "JDK must find a match");
    ReggieMatcher reggie = Reggie.compile(pattern);
    MatchResult r = reggie.findMatch(input);
    assertNotEquals(null, r, "Reggie must find a match in \"" + input + "\"");
    assertEquals(
        List.of(jm.start(), jm.end()),
        List.of(r.start(), r.end()),
        "lazy a*?a on \"" + input + "\" must match shortest (1 char) against JDK");
  }

  @Test
  void lazyGroupSpan_minimalFirstGroup() throws Exception {
    // (\\w*?)(\\w+) on "abc": lazy first group must be minimal (empty), second captures all.
    // This would fail if ThompsonBuilder emits greedy epsilon order for the lazy quantifier.
    assertRoute("(\\w*?)(\\w+)", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    assertGroupsAgree("(\\w*?)(\\w+)", "abc");
  }

  @Test
  void lazyAlternationInteraction() throws Exception {
    assertGroupsAgree("(a*?|b)", "b");
    assertGroupsAgree("(a+?|b)", "b");
    assertGroupsAgree("(a*?|a+)", "aaa");
    // find() variant: verify lazy star does not over-consume in alternation with find() semantics.
    // On "bbb", (a*?|b) lazy must prefer the zero-width 'a*?' at positions where both branches
    // could match; result should agree with JDK.
    assertAgrees("(a*?|b)", "bbb");
  }

  @Test
  void lazyZeroWidthFindAdvancement() throws Exception {
    // a*? on "aaa": repeated find() must produce zero-width matches at every position,
    // matching JDK Matcher.find() positions [0,0], [1,1], [2,2], [3,3], then no match.
    assertRoute("a*?", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    String pattern = "a*?";
    String input = "aaa";
    Pattern jdk = Pattern.compile(pattern);
    Matcher jm = jdk.matcher(input);
    ReggieMatcher reggie = Reggie.compile(pattern);

    int pos = 0;
    while (jm.find()) {
      MatchResult r = reggie.findMatchFrom(input, pos);
      assertNotEquals(null, r, "Reggie must find match at pos " + pos);
      assertEquals(
          List.of(jm.start(), jm.end()),
          List.of(r.start(), r.end()),
          "a*? find() position mismatch at step starting from pos=" + pos);
      // Advance past zero-width match to avoid infinite loop, same as JDK semantics
      pos = r.end() == pos ? pos + 1 : r.end();
    }
    // After all JDK matches exhausted, Reggie must also find no further match
    MatchResult noMore = reggie.findMatchFrom(input, pos);
    assertEquals(
        null, noMore, "Reggie must not find match after all JDK matches exhausted at pos=" + pos);

    // a*?b on "aaab": non-zero-width lazy star must advance through input and match [0,4].
    // This exercises a different code path from pure zero-width matching above.
    assertRoute("a*?b", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    String pattern2 = "a*?b";
    String input2 = "aaab";
    Pattern jdk2 = Pattern.compile(pattern2);
    Matcher jm2 = jdk2.matcher(input2);
    assertTrue(jm2.find(), "JDK must find a match for a*?b on \"" + input2 + "\"");
    ReggieMatcher reggie2 = Reggie.compile(pattern2);
    MatchResult r2 = reggie2.findMatch(input2);
    assertNotEquals(null, r2, "Reggie must find a match for a*?b on \"" + input2 + "\"");
    assertEquals(
        List.of(jm2.start(), jm2.end()),
        List.of(r2.start(), r2.end()),
        "a*?b on \"" + input2 + "\" must match [0,4] (cross-checked against JDK)");
  }

  @Test
  void nullableBodyLazyMin1() throws Exception {
    // (a?)+? on "aaa": nullable capturing group under lazy repeatable quantifier. B16 guard routes
    // this to JDK fallback (same as greedy equivalent), so fallback must be allowed.
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    Pattern jdk = Pattern.compile("(a?)+?");
    ReggieMatcher reggie = Reggie.compile("(a?)+?", opts);
    String input = "aaa";
    java.util.regex.Matcher jm = jdk.matcher(input);
    boolean jdkMatches = jm.matches();
    MatchResult rm = reggie.match(input);
    assertEquals(jdkMatches, rm != null, "match() boolean for /(a?)+?/ on \"" + input + "\"");
    if (jdkMatches) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(
            List.of(jm.start(g), jm.end(g)),
            List.of(rm.start(g), rm.end(g)),
            "group " + g + " span for /(a?)+?/ on \"" + input + "\"");
      }
    }
  }

  @Test
  void lazyNonCapturingGroupRepetition() throws Exception {
    // (?:ab)*?c on "ababc": lazy non-capturing group repetition must stop at minimum iterations.
    assertRoute("(?:ab)*?c", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    assertAgrees("(?:ab)*?c", "ababc");
    assertGroupsAgree("(?:ab)*?c", "ababc");
  }

  @Test
  void ldapPattern() throws Exception {
    // Lazy quantifier LDAP-style pattern with named group
    assertRoute(
        "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)",
        PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    String pattern = "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)";
    String input = "(uid=jsmith)";
    Pattern jdk = Pattern.compile(pattern);
    Matcher jm = jdk.matcher(input);
    assertTrue(jm.find(), "JDK must find match in LDAP input");
    assertEquals("jsmith", jm.group("LITERAL"), "JDK LITERAL group");

    ReggieMatcher reggie = Reggie.compile(pattern);
    MatchResult r = reggie.findMatch(input);
    assertNotEquals(null, r, "Reggie must find match in \"" + input + "\"");
    assertEquals("jsmith", r.group("LITERAL"), "Reggie LITERAL group must equal JDK");
  }
}
