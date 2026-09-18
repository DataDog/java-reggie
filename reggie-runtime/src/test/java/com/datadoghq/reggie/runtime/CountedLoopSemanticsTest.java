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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Counted-loop lowering (hyp-counted-loop-lowering): bounded quantifiers x{n,m} whose unrolled tail
 * would exceed the builder budget lower to one body copy plus a counter marker, executed by the
 * counter-aware backtracking matcher. Small quantifiers keep the exact unrolled representation and
 * their existing strategies. These tests pin:
 *
 * <ol>
 *   <li>semantics parity with java.util.regex on both paths (min/max enforcement, greedy and lazy
 *       priority, captures, backrefs combined with counted loops, nested loops);
 *   <li>routing (forced shapes land on BackrefBacktrackMatcher);
 *   <li>the worst-case bound: adversarially ambiguous shapes fail fast with
 *       MatchBudgetExceededException instead of unbounded backtracking (the same inputs hang
 *       java.util.regex);
 *   <li>the motivating regression stays fixed: the real semver {0,256} pattern (567,550 unrolled
 *       states / 2.3s compile / 25-400x-JDK matching before) compiles in milliseconds with exact
 *       semantics, including rejecting >256-iteration inputs.
 * </ol>
 */
class CountedLoopSemanticsTest {

  /**
   * Full parity: matches(), find(), and findMatch() spans + every group span vs java.util.regex.
   */
  private static void assertParity(String pattern, String... inputs) {
    com.datadoghq.reggie.ReggieMatcher rm = Reggie.compile(pattern);
    Pattern jp = Pattern.compile(pattern);
    for (String input : inputs) {
      assertEquals(
          jp.matcher(input).matches(),
          rm.matches(input),
          "matches() /" + pattern + "/ on len=" + input.length());
      Matcher jf = jp.matcher(input);
      boolean jdkFind = jf.find();
      assertEquals(jdkFind, rm.find(input), "find() /" + pattern + "/ on len=" + input.length());
      if (jdkFind) {
        MatchResult rr = ((ReggieMatcher) rm).findMatch(input);
        assertEquals(jf.start(), rr.start(), "find start /" + pattern + "/");
        assertEquals(jf.end(), rr.end(), "find end /" + pattern + "/");
        for (int g = 1; g <= jf.groupCount(); g++) {
          assertEquals(jf.start(g), rr.start(g), "group " + g + " start /" + pattern + "/");
          assertEquals(jf.end(g), rr.end(g), "group " + g + " end /" + pattern + "/");
        }
      }
    }
  }

  @Test
  void smallQuantifiersKeepUnrolledSemantics() {
    // Tails far under the 5k-state budget: exact unrolled representation, existing strategies.
    assertParity("a{2,5}", "a", "aa", "aaaa", "aaaaa", "aaaaaa");
    assertParity(
        "b\\d{0,256}e",
        "be",
        "b123e",
        "b" + "7".repeat(256) + "e",
        "b" + "7".repeat(257) + "e",
        "bxe");
    assertParity("(ab){0,3}", "", "ab", "abab", "ababab", "abababab");
  }

  @Test
  @Timeout(60)
  void forcedCountedGreedyParity() {
    // Body ~7 states x 2000 tail > 5000 budget -> counted loop. Greedy alternation priority
    // (first-alternative wins on ties) and last-iteration capture spans must match JDK exactly.
    assertEquals(
        "BackrefBacktrackMatcher",
        Reggie.compile("((?:ab|abc)){0,2000}").getClass().getSimpleName());
    assertParity(
        "((?:ab|abc)){0,2000}",
        "",
        "ab",
        "abc",
        "ababc",
        "ababcab",
        "ab".repeat(2000),
        "ab".repeat(2001), // > max: must REJECT
        "abx");
  }

  @Test
  @Timeout(60)
  void forcedCountedLazyParity() {
    // Lazy bounded quantifier: stop branch preferred; first find() must be the shortest.
    assertEquals(
        "BackrefBacktrackMatcher", Reggie.compile("(?:(ab)){1,2000}?").getClass().getSimpleName());
    assertParity(
        "(?:(ab)){1,2000}?", "ab", "abab", "ababab", "ab".repeat(2000), "ab".repeat(2001), "a");
  }

  @Test
  @Timeout(60)
  void minAndMaxEnforcement() {
    // min=100 unrolled copies + counted tail (2900 x ~7 > budget).
    assertEquals(
        "BackrefBacktrackMatcher",
        Reggie.compile("(?:abcde){100,3000}").getClass().getSimpleName());
    assertParity(
        "(?:abcde){100,3000}",
        "abcde".repeat(99), // below min: REJECT
        "abcde".repeat(100),
        "abcde".repeat(3000),
        "abcde".repeat(3001)); // above max: REJECT
  }

  @Test
  @Timeout(60)
  void nestedCountedLoops() {
    // Inner (?:yy){0,3000} (~3 states x 3000 > budget) and outer body containing it (x budget)
    // both lower: two markers in one NFA, two counter slots on one frame.
    assertEquals(
        "BackrefBacktrackMatcher",
        Reggie.compile("(?:x(?:yy){0,3000}){0,500}").getClass().getSimpleName());
    assertParity(
        "(?:x(?:yy){0,3000}){0,500}",
        "",
        "x",
        "xyy",
        "xyyyy",
        "xyyxyy",
        "x" + "y".repeat(6000), // inner max boundary: ACCEPT (3000 iterations)
        "x" + "y".repeat(6002), // inner max exceeded: REJECT
        "xyyx");
  }

  @Test
  @Timeout(60)
  void countedLoopWithBackreference() {
    // Counted marker + backref states in one DFS: memo key carries both ref spans and counters.
    assertEquals(
        "BackrefBacktrackMatcher",
        Reggie.compile("(?:(ab)\\1){0,2000}").getClass().getSimpleName());
    assertParity(
        "(?:(ab)\\1){0,2000}",
        "",
        "abab",
        "ab".repeat(4),
        "ab".repeat(4000), // 2000 iterations: ACCEPT
        "ab".repeat(4002), // 2001 iterations: REJECT
        "ababx");
  }

  @Test
  @Timeout(120)
  void realSemverPatternParity() {
    // The motivating regression (find-bounded-quantifier-regression): the real logs-backend
    // semver pattern. 567,550 unrolled states / 2,257ms compile before the fix.
    String semver =
        "^(?<major>0|[1-9]\\d{0,256})\\.(?<minor>0|[1-9]\\d{0,256})\\.(?<patch>0|[1-9]\\d{0,256})"
            + "(?:-(?<prerelease>(?:0|[1-9]\\d{0,256}|\\d{0,256}[a-zA-Z-][0-9a-zA-Z-]{0,256})"
            + "(?:\\.(?:0|[1-9]\\d{0,256}|\\d{0,256}[a-zA-Z-][0-9a-zA-Z-]{0,256})){0,256}))?"
            + "(?:\\+(?<buildmetadata>[0-9a-zA-Z-]{0,40}(?:\\.[0-9a-zA-Z-]{0,40}){0,256}))?$";
    long t0 = System.nanoTime();
    com.datadoghq.reggie.ReggieMatcher m = Reggie.compile(semver);
    long compileMs = (System.nanoTime() - t0) / 1_000_000L;
    assertTrue(compileMs < 500, "semver compile must be milliseconds, took " + compileMs + "ms");
    // Parity, including the counter-enforced rejections JDK also makes.
    assertParity(
        semver,
        "1.2.3",
        "1.2.3-alpha.1+build.42",
        "0.0.4-beta+exp.sha.5114f85",
        "123456.789012.345678-rc.1+metadata.999",
        "1.2.3-" + "a.".repeat(150) + "x+build",
        "1.2.3-" + "a.".repeat(300) + "x+build", // >256 prerelease iterations: REJECT
        "1234567890123456789012345678901234567890.2.3", // >256 digits: REJECT
        "1.2",
        "x.y.z",
        "");
  }

  @Test
  @Timeout(60)
  void adversarialShapeFailsFastInsteadOfHanging() {
    // (?:a|aa){0,3000}b against a^2999 requires exploring exponentially many decompositions
    // before failing — java.util.regex burns unbounded time on exactly this shape. The step
    // budget must convert it into a fast, bounded exception.
    com.datadoghq.reggie.ReggieMatcher m = Reggie.compile("(?:a|aa){0,3000}b");
    assertEquals("BackrefBacktrackMatcher", m.getClass().getSimpleName());
    assertThrows(MatchBudgetExceededException.class, () -> m.matches("a".repeat(2999)));
  }
}
