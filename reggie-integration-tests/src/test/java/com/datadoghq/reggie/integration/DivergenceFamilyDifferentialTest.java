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
package com.datadoghq.reggie.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pinned differential cases for the native-divergence fault families that the fuzz grammar and the
 * per-strategy meta-test representatives did not produce: the gates passed while native results
 * were wrong, and each divergence below had to be caught in PR review instead.
 *
 * <p>Families (each was a real silent wrong answer on an ACCEPTED pattern, contrary to the
 * correctness guarantee):
 *
 * <ol>
 *   <li>greedy unbounded prefix overlapping a backref capture group (a*(a+)\1)
 *   <li>trailing lookahead after a greedy loop (a+(?=a))
 *   <li>trailing lookbehind checked on the final accepting state of a DFA_SWITCH_WITH_ASSERTIONS
 *       pattern ([a-z]{10}[0-9]{10}(?&lt;=abc))
 *   <li>lookbehind combined with capturing groups ((?&lt;=(a))b(c), \d+(?&lt;=\d{3}))
 *   <li>END/STRING_END anchors against the full JDK line-terminator set — CRLF, NEL (U+0085), LS
 *       (U+2028), PS (U+2029) — for \Z's final-terminator tolerance and \z's hard end (abc\z,
 *       abc\Z, ab+\Z)
 *   <li>per-config backref worklist pressure: bounded-alternation backref patterns whose NFA
 *       simulation can exceed the 256-config worklist cap (PER_CONFIG_CAP) — the engine must
 *       delegate on overflow, never silently drop configurations
 * </ol>
 *
 * <p>Every row compiles native ({@link Reggie#compile} throws instead of falling back) and must
 * agree with {@code java.util.regex} across matches/find/match/findMatch/findFrom, including group
 * spans. Expected routings are documented per row but deliberately NOT asserted: routing changes
 * are legitimate; silent divergence from the JDK is not.
 */
public class DivergenceFamilyDifferentialTest {

  /** A pinned family: pattern plus the inputs that exercise the fault shape. */
  record Family(String family, String pattern, List<String> inputs) {}

  static List<Family> families() {
    return List.of(
        // Family 1: greedy unbounded prefix overlapping the backref capture group.
        // Routing: BACKREF_BACKTRACK. The fault: the prefix consumed the backref group's
        // content without backtracking into it.
        new Family(
            "greedy-prefix-overlapping-backref",
            "a*(a+)\\1",
            List.of("", "aa", "aaa", "aaaa", "xaaya", "aaab", "aabaa")),
        // Family 2: trailing lookahead after a greedy loop.
        // Routing: DFA_UNROLLED_WITH_ASSERTIONS. The fault: deferred lookahead evaluation on
        // the final accepting state.
        new Family(
            "trailing-lookahead-after-greedy-loop",
            "a+(?=a)",
            List.of("", "a", "aa", "aaa", "ab", "aab")),
        // Family 3: trailing lookbehind on the final accepting state (DFA_SWITCH_WITH_ASSERTIONS:
        // 20-300 states with fixed-width assertions).
        new Family(
            "trailing-lookbehind-final-state",
            "[a-z]{10}[0-9]{10}(?<=abc)",
            List.of(
                "abcdefghij0123456789abc",
                "abcdefghij0123456789xyz",
                "abcdefghij0123456789",
                "abc",
                "abcdefghij0123456789abcabc")),
        // Family 4: lookbehind combined with capturing groups (spans must agree, not just
        // booleans).
        new Family(
            "lookbehind-with-captures",
            "(?<=(a))b(c)",
            List.of("", "abc", "xabc", "aabc", "bc", "ab")),
        new Family(
            "lookbehind-with-captures-bounded",
            "\\d+(?<=\\d{3})",
            List.of("", "123", "1234", "x1234y", "12", "12345")),
        // Family 5: END/STRING_END anchors vs the full JDK line-terminator set. \Z tolerates one
        // final terminator; \z does not. The fault: the per-config END anchor missed the CRLF pair.
        new Family(
            "string-end-anchor",
            "abc\\z",
            List.of(
                "abc", "abc\n", "abc\r\n", "abc\r", "abc\u0085", "abc\u2028", "abc\u2029", "xabc")),
        new Family(
            "end-anchor-terminator-tolerant",
            "abc\\Z",
            List.of(
                "abc",
                "abc\n",
                "abc\r\n",
                "abc\r",
                "abc\u0085",
                "abc\u2028",
                "abc\u2029",
                "abc\r\n\r\n",
                "abc\nx")),
        new Family(
            "greedy-loop-end-anchor",
            "ab+\\Z",
            List.of("ab", "abbb", "abbb\n", "abbb\r\n", "abbb\u2028", "abbc", "ab\nb")),
        // Family 6: per-config backref NFA under worklist pressure — bounded alternations with a
        // backref, long enough to fork many simultaneous configurations. Overflow must delegate
        // to the general engine, never drop configs (a dropped config is a silent false negative).
        new Family(
            "per-config-worklist-pressure",
            "(?:ab|a|b){12}(z)\\1",
            List.of(
                "ababababababababababababzz",
                "aaaaaaaaaaaaz z",
                "ababababababzz",
                "bbbbbbbbbbbbzz",
                "ab".repeat(30) + "zz",
                "a".repeat(40) + "zz")));
  }

  @ParameterizedTest(name = "{0}: /{1}/")
  @MethodSource("families")
  void agreesWithJdk(Family family) {
    // Native-only: Reggie.compile throws UnsupportedPatternException instead of silently
    // delegating to java.util.regex, so a successful compile proves the native path answered.
    ReggieMatcher reggie = Reggie.compile(family.pattern());
    Pattern jdk = Pattern.compile(family.pattern());
    for (String input : family.inputs()) {
      String ctx = family.family() + " /" + family.pattern() + "/ on " + abbreviate(input);

      // 1. matches() + whole-input match() span
      boolean jdkMatches = jdk.matcher(input).matches();
      assertEquals(jdkMatches, reggie.matches(input), "matches " + ctx);
      MatchResult whole = reggie.match(input);
      if (jdkMatches) {
        assertNotNull(whole, "match " + ctx);
        assertEquals(0, whole.start(), "match.start " + ctx);
        assertEquals(input.length(), whole.end(), "match.end " + ctx);
      } else {
        assertEquals(null, whole, "match " + ctx);
      }

      // 2. per-start sweep: for every start offset, JDK find(s) spans and success must agree
      // with findMatchFrom(input, s) — this covers find()/findFrom() semantics exhaustively for
      // the pinned inputs without relying on iterator state.
      for (int s = 0; s <= input.length(); s++) {
        Matcher js2 = jdk.matcher(input);
        boolean jdkFind = js2.find(s);
        MatchResult fm = reggie.findMatchFrom(input, s);
        String sctx = ctx + " from=" + s;
        if (jdkFind) {
          assertNotNull(fm, "findMatchFrom " + sctx);
          assertEquals(js2.start(), fm.start(), "find.start " + sctx);
          assertEquals(js2.end(), fm.end(), "find.end " + sctx);
          assertEquals(js2.groupCount(), fm.groupCount(), "groupCount " + sctx);
          for (int g = 0; g <= js2.groupCount(); g++) {
            assertEquals(js2.start(g), fm.start(g), "start(" + g + ") " + sctx);
            assertEquals(js2.end(g), fm.end(g), "end(" + g + ") " + sctx);
          }
        } else {
          assertEquals(null, fm, "findMatchFrom " + sctx);
        }
      }

      // 3. findFrom(in, 0) agrees with JDK find start (or -1)
      Matcher js = jdk.matcher(input);
      int jdkFindStart = js.find() ? js.start() : -1;
      assertEquals(jdkFindStart, reggie.findFrom(input, 0), "findFrom(0) " + ctx);
    }
  }

  private static String abbreviate(String s) {
    String flat = s.replaceAll("\n", "\\n").replaceAll("\r", "\\r");
    return "\""
        + (flat.length() > 40 ? flat.substring(0, 40) + "...(" + s.length() + ")" : flat)
        + "\"";
  }
}
