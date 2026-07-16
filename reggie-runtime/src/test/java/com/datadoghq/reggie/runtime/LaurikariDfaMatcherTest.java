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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Impl-plan Task 1.5 (doc/2026-07-10-tdfa-capture-engine-impl-plan.md §3): direct-construction
 * correctness tests for {@link LaurikariDfaMatcher} against a {@link PikeVMMatcher} oracle over the
 * same NFA — models {@code PikeVMMatcherTest}'s direct-construction pattern (no {@code
 * Reggie.compile()}).
 */
class LaurikariDfaMatcherTest {

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder().build(ast, groupCount);
  }

  private static NFA lazyAwareNfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder(true).build(ast, groupCount);
  }

  private static LaurikariDfaMatcher laurikari(String pattern, int groupCount) throws Exception {
    return new LaurikariDfaMatcher(nfa(pattern, groupCount), pattern, groupCount);
  }

  private static LaurikariDfaMatcher laurikariLazyAware(String pattern, int groupCount)
      throws Exception {
    return new LaurikariDfaMatcher(lazyAwareNfa(pattern, groupCount), pattern, groupCount);
  }

  private static PikeVMMatcher pikeVm(String pattern, int groupCount) throws Exception {
    return new PikeVMMatcher(nfa(pattern, groupCount), pattern);
  }

  private static void assertMatchEquals(
      MatchResult expected, MatchResult actual, int groupCount, String input) {
    if (expected == null) {
      assertNull(actual, "expected no match for " + input);
      return;
    }
    assertNotNull(actual, "expected a match for " + input);
    for (int g = 0; g <= groupCount; g++) {
      assertEquals(
          expected.start(g), actual.start(g), "group " + g + " start diverged for " + input);
      assertEquals(expected.end(g), actual.end(g), "group " + g + " end diverged for " + input);
    }
  }

  /**
   * Runs the full matches/match/find/findFrom/findMatch/findMatchFrom surface against the oracle.
   */
  private static void assertAllApisMatchOracle(
      LaurikariDfaMatcher laurikari, PikeVMMatcher oracle, int groupCount, String input) {
    assertEquals(
        oracle.matches(input), laurikari.matches(input), "matches() diverged for " + input);
    assertMatchEquals(oracle.match(input), laurikari.match(input), groupCount, input);

    assertEquals(oracle.find(input), laurikari.find(input), "find() diverged for " + input);
    assertEquals(
        oracle.findFrom(input, 0),
        laurikari.findFrom(input, 0),
        "findFrom(0) diverged for " + input);
    assertMatchEquals(oracle.findMatch(input), laurikari.findMatch(input), groupCount, input);
    assertMatchEquals(
        oracle.findMatchFrom(input, 0), laurikari.findMatchFrom(input, 0), groupCount, input);
  }

  // --- Phase 0.5 adversarial set -------------------------------------------------------------

  @Test
  void twoIndependentGroups_matchesOracle() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"ab", "aaabbb", "aaaaaaaabb", "a", "xab", "abx"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount(), "no fallback expected for small patterns/inputs");
  }

  @Test
  void loopBodyGroup_matchesOracle() throws Exception {
    String pattern = "(ab)+";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"ab", "abab", "ababab", "x", "ababx"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void nestedNullableGroups_matchesOracle() throws Exception {
    String pattern = "((a)|())*";
    int groupCount = 3;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"", "a", "aa", "aaa"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- shared-prefix alternation with distinct capture groups per branch ---------------------

  @Test
  void sharedPrefixAlternation_matchesOracle() throws Exception {
    String pattern = "(foobar)|(foobaz)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"foobar", "foobaz", "fooba", "xfoobar"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- unrolled-quantifier / multi-anchor style pattern ---------------------------------------

  @Test
  void quantifiedAlternation_matchesOracle() throws Exception {
    String pattern = "(a|b){2,4}(c)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"aac", "abbac", "bbbbc", "ac", "aaaac", "xabc"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- no-match and zero-length-match cases, anchored and find-family -------------------------

  @Test
  void noMatch_bothAnchoredAndFind() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "xyz";
    assertFalse(laurikari.matches(input));
    assertNull(laurikari.match(input));
    assertFalse(laurikari.find(input));
    assertEquals(-1, laurikari.findFrom(input, 0));
    assertNull(laurikari.findMatch(input));
    assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void zeroLengthMatch_anchoredAndFind() throws Exception {
    String pattern = "(a*)";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"", "b", "a", "aaa"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- findFrom at a non-zero offset, and full capture-span pinning ---------------------------

  @Test
  void findFromNonZeroOffset_matchesOracleCaptures() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "xxxaabbxxxaaabbb";
    for (int start : new int[] {0, 1, 3, 8, 11, input.length()}) {
      assertEquals(
          oracle.findFrom(input, start),
          laurikari.findFrom(input, start),
          "findFrom(" + start + ") diverged");
      MatchResult expected = oracle.findMatchFrom(input, start);
      MatchResult actual = laurikari.findMatchFrom(input, start);
      assertMatchEquals(expected, actual, groupCount, input + "@" + start);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- anchor-boundary cases: regionEnd/scan-bound separation for start anchors --------------
  // (design doc §"Task 1.5": the same bug class already fixed once in BitStateMatcher.search --
  // confusing where a scan is allowed to *start* re-checking an anchor with where the scan's
  // *content window* begins/ends. LaurikariDfaMatcher's analog is startDfaStateFor/
  // reinjectDfaState/reinjectAfterNlDfaState: ^ must only ever fire at absolute position 0, and
  // (?m)^ only at absolute 0 or immediately after a '\n', regardless of what offset findFrom is
  // called with -- pinned here across multiple offsets against the PikeVMMatcher oracle.

  @Test
  void leadingStartAnchor_findFromOffsets_matchesOracle() throws Exception {
    String pattern = "^(a+)";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "aaa\naaa";
    for (int start : new int[] {0, 1, 3, 4, 6, input.length()}) {
      assertEquals(
          oracle.findFrom(input, start),
          laurikari.findFrom(input, start),
          "findFrom(" + start + ") diverged");
      MatchResult expected = oracle.findMatchFrom(input, start);
      MatchResult actual = laurikari.findMatchFrom(input, start);
      assertMatchEquals(expected, actual, groupCount, input + "@" + start);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void multilineStartAnchor_findFromAcrossNewlines_matchesOracle() throws Exception {
    String pattern = "(?m)^(a+)";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    // '\n' at indices 2 and 6 -- (?m)^ is live at absolute 0 and immediately after each of those,
    // i.e. positions 0, 3, 7; every other offset must have the anchor blocked.
    String input = "xa\naaa\nxx";
    for (int start : new int[] {0, 1, 2, 3, 4, 6, 7, 8, input.length()}) {
      assertEquals(
          oracle.findFrom(input, start),
          laurikari.findFrom(input, start),
          "findFrom(" + start + ") diverged");
      MatchResult expected = oracle.findMatchFrom(input, start);
      MatchResult actual = laurikari.findMatchFrom(input, start);
      assertMatchEquals(expected, actual, groupCount, input + "@" + start);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void captureSpans_pinnedAgainstOracleExactly() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "aaabbb";
    MatchResult expected = oracle.match(input);
    MatchResult actual = laurikari.match(input);
    int[] expectedSpans = new int[2 * (groupCount + 1)];
    int[] actualSpans = new int[2 * (groupCount + 1)];
    for (int g = 0; g <= groupCount; g++) {
      expectedSpans[2 * g] = expected.start(g);
      expectedSpans[2 * g + 1] = expected.end(g);
      actualSpans[2 * g] = actual.start(g);
      actualSpans[2 * g + 1] = actual.end(g);
    }
    assertArrayEquals(expectedSpans, actualSpans);
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void fallbackCountStaysZero_acrossAllSmallPatterns() throws Exception {
    assertTrue(laurikari("(a+)(b+)", 2).fallbackCount() == 0);
  }

  // --- lazy-quantifier regressions surfaced by LaurikariAlgorithmicFuzzTest ------------------
  // Reproduces residual JDK-oracle findings directly against LaurikariDfaMatcher (built with
  // ThompsonBuilder(true), same as the fuzz harness) to determine whether they are a genuine
  // engine bug or an artifact of the fuzz test's own NFA construction.

  @Test
  void lazyPlusQuantifier_matchesJdk() throws Exception {
    String pattern = ".+?";
    LaurikariDfaMatcher laurikari = laurikariLazyAware(pattern, 0);
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);

    for (String input : new String[] {"0a011a-", "_111-bc", "10a", "bb0-"}) {
      assertEquals(
          jdk.matcher(input).matches(),
          laurikari.matches(input),
          "matches() diverged for " + input);
    }
  }

  // --- FALLBACK-sentinel find() regression (Phase 2 discovery) -------------------------------
  // runFind's DFA-cache-overflow branch used to restart PikeVMMatcher from the frozen scan
  // position instead of the scan's original start, silently losing any already-consumed prefix
  // of a still-in-progress match (see LaurikariDfaMatcher.runFind's FALLBACK branch). Only
  // observable once findCache's 4096-state cap is exceeded, which requires a long, high-variety
  // input -- Phase 1's fuzz coverage never drove find()-family calls this long.

  @Test
  void findSurvivesDfaCacheOverflowMidMatch() throws Exception {
    String pattern = "(a)(b)*c";
    LaurikariDfaMatcher m = laurikari(pattern, 2);
    PikeVMMatcher oracle = pikeVm(pattern, 2);

    StringBuilder sb = new StringBuilder("a");
    for (int i = 0; i < 10_000; i++) sb.append('b');
    sb.append('c');
    String input = sb.toString();

    assertTrue(oracle.find(input), "oracle sanity check");
    assertTrue(m.find(input), "find() must not lose the match's prefix on cache overflow");
    assertTrue(m.fallbackCount() > 0, "this input is expected to overflow findCache's state cap");
    assertMatchEquals(oracle.findMatch(input), m.findMatch(input), 2, input);
  }

  // --- TDFA Phase 2 end-anchor/\b extension: the 5 new anchor types --------------------------
  // Direct-construction differential coverage against the PikeVMMatcher oracle, mirroring the
  // existing anchor-boundary section above but for END/STRING_END/STRING_END_ABSOLUTE/
  // END_MULTILINE/WORD_BOUNDARY instead of the START-family.

  @Test
  void wordBoundary_leadingAndTrailing_matchesOracle() throws Exception {
    String pattern = "\\b(\\w+)\\b";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"foo", " foo ", "foo bar", "123", "", " ", "a1_2"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void endAnchor_trailingWithLineTerminators_matchesOracle() throws Exception {
    String pattern = "(a+)$";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input :
        new String[] {
          "aaa", "aaa\n", "aaa\r", "aaa\r\n", "aaa\n\n", "aaab", "", "aaa\u2028", "aaa\u0085"
        }) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void stringEndAnchor_matchesOracle() throws Exception {
    String pattern = "(a+)\\Z";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"aaa", "aaa\n", "aaa\r\n", "aaab", ""}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void stringEndAbsoluteAnchor_matchesOracle() throws Exception {
    String pattern = "(a+)\\z";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"aaa", "aaa\n", "aaa\r\n", "aaab", ""}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void endMultilineAnchor_matchesOracleAcrossLines() throws Exception {
    String pattern = "(?m)(a+)$";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"aaa", "aaa\nbbb", "xxx\naaa\nyyy", "aaa\r\naaa", ""}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void endAnchorInsideNonTailBranch_matchesOracle() throws Exception {
    // Adversarial shape from the TDFA Phase 2 plan: the end anchor sits inside one alternation
    // branch, with more pattern content (the 'c') following. No structural "leading/trailing-
    // only" restriction is needed here -- checkAnchor is evaluated live per-occurrence.
    String pattern = "(a$|b)c";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"bc", "ac", "bcx", ""}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void wordBoundary_findFromNonZeroOffset_matchesOracle() throws Exception {
    // Cache-soundness regression (design doc's top-named risk): drives the SAME
    // LaurikariDfaMatcher instance/caches across multiple findFrom offsets on the same input, so
    // the same (subset, char) transition recurs at different absolute positions -- if \b's
    // outcome were ever memoized instead of recomputed live, this would surface the divergence.
    String pattern = "\\b(\\w+)\\b";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "foo bar baz";
    for (int start = 0; start <= input.length(); start++) {
      assertEquals(
          oracle.findFrom(input, start),
          laurikari.findFrom(input, start),
          "findFrom(" + start + ") diverged");
      assertMatchEquals(
          oracle.findMatchFrom(input, start),
          laurikari.findMatchFrom(input, start),
          groupCount,
          input + "@" + start);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void multilineStartAnchorCombinedWithWordBoundary_findAcrossNewlines_matchesOracle()
      throws Exception {
    // Combines a START_MULTILINE reinject ((?m)^) with a WORD_BOUNDARY (hasNewAnchor): the
    // find()-family reinject path must recompute the after-'\n' seed closure live via
    // reinjectAfterNlClosure(input, pos, regionEnd) at each '\n' crossing, not the precomputed
    // reinjectAfterNlStates field (which predates the input string and can't evaluate \b).
    String pattern = "(?m)^\\w+\\b";
    int groupCount = 0;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "xx\nfoo bar\nx\nbaz9 qux";
    for (int start = 0; start <= input.length(); start++) {
      assertEquals(
          oracle.findFrom(input, start),
          laurikari.findFrom(input, start),
          "findFrom(" + start + ") diverged");
      assertMatchEquals(
          oracle.findMatchFrom(input, start),
          laurikari.findMatchFrom(input, start),
          groupCount,
          input + "@" + start);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  @Test
  void endAnchor_sameSubsetRecursAtDifferentPositions_matchesOracle() throws Exception {
    // Direct regression for the cache-soundness risk named in the design doc: a single instance
    // is matched() against several inputs, some where the trailing 'a' run sits at true
    // end-of-string and some where it doesn't -- if the DFA cache ever memoized an anchor-
    // sensitive transition, a later call with a different regionEnd would read the wrong answer.
    String pattern = "(a+)$";
    int groupCount = 1;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    for (String input : new String[] {"aaa", "aaab", "aaa", "aa", "aaab", "aaa"}) {
      assertAllApisMatchOracle(laurikari, oracle, groupCount, input);
    }
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- SQL tokenizer regression (IastRegexpBenchmark) -----------------------------------------
  // The three SQL dialect patterns whose \b\d+/(?m)...--.*$ anchors were the original motivation
  // for this eligibility extension -- confirms they're now eligible and route through
  // LaurikariDfaMatcher with zero fallbacks.

  private static final String SQL_ANSI =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  private static final String SQL_MYSQL =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|\"(?:\\\\\"|[^\"])*\"|'(?:\\\\'|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  private static final String SQL_POSTGRESQL =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|\\$(?:[a-zA-Z_]\\w*)?\\$|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  private static final String SQL_INPUT =
      "SELECT id, name FROM users -- fetch all users\nWHERE age > 0x1F AND salary = 1234.56\n"
          + "/* block comment\n spanning lines */\n"
          + "AND note = 'it''s fine' OR flag = b'101'";

  private static void assertSqlRoutesThroughLaurikari(String pattern) throws Exception {
    NFA nfa = nfa(pattern, 0);
    assertTrue(
        LaurikariEligibility.isEligible(nfa, false),
        pattern + " must now be Laurikari-eligible (was rejected pre-Phase-2-extension)");

    LaurikariDfaMatcher laurikari = laurikari(pattern, 0);
    PikeVMMatcher oracle = pikeVm(pattern, 0);

    assertAllApisMatchOracle(laurikari, oracle, 0, SQL_INPUT);
    assertEquals(0, laurikari.fallbackCount(), pattern + " must not fall back to PikeVMMatcher");
  }

  @Test
  void sqlAnsiTokenizer_routesThroughLaurikari() throws Exception {
    assertSqlRoutesThroughLaurikari(SQL_ANSI);
  }

  @Test
  void sqlMysqlTokenizer_routesThroughLaurikari() throws Exception {
    assertSqlRoutesThroughLaurikari(SQL_MYSQL);
  }

  @Test
  void sqlPostgresqlTokenizer_routesThroughLaurikari() throws Exception {
    assertSqlRoutesThroughLaurikari(SQL_POSTGRESQL);
  }

  // --- embedsNameMap: RuntimeCompiler consults this to decide whether to wrap the matcher in a
  // --- NameEnrichingMatcher for named groups -- exercised directly here since this test suite
  // --- constructs LaurikariDfaMatcher without going through RuntimeCompiler/Reggie.compile().

  @Test
  void embedsNameMap_returnsTrue() throws Exception {
    LaurikariDfaMatcher m = laurikari("(a)(b)", 2);
    assertTrue(m.embedsNameMap());
  }

  // --- findFrom(start > input.length()): runFind's own out-of-range guard, distinct from
  // --- PikeVMMatcher's identical clamp (see class javadoc's comparison to BitStateMatcher.search).

  @Test
  void findFromStartBeyondInputLength_returnsNoMatch() throws Exception {
    String pattern = "(a+)(b+)";
    int groupCount = 2;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    String input = "aabb";
    int start = input.length() + 5;
    assertEquals(oracle.findFrom(input, start), laurikari.findFrom(input, start));
    assertNull(laurikari.findMatchFrom(input, start));
    assertEquals(0, laurikari.fallbackCount());
  }

  // --- hasNewAnchor cache-overflow FALLBACK at the *seed* (as opposed to
  // --- findSurvivesDfaCacheOverflowMidMatch's mid-scan FALLBACK): runAnchored/runFind's
  // --- hasNewAnchor branches recompute their seed live via anchoredCache.intern/findCache.intern
  // --- on every call (see LaurikariDfaMatcher's class javadoc on why -- the
  // constructor-precomputed
  // --- seeds are just placeholders for hasNewAnchor patterns). Once a prior call has already
  // frozen
  // --- the cache, a *later* call whose live seed key was never interned before must itself return
  // --- FALLBACK, delegating the whole call to PikeVMMatcher -- exercised here for both the
  // anchored
  // --- (matches()/match()) and self-anchoring (find()/findFrom()) drivers, and for both a
  // --- fallback-still-matches and a fallback-finds-nothing outcome.

  @Test
  void hasNewAnchor_seedFallbackAfterAnchoredCacheOverflow() throws Exception {
    String pattern = "\\b(a)(b)*c";
    int groupCount = 2;
    LaurikariDfaMatcher m = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    StringBuilder sb = new StringBuilder("a");
    for (int i = 0; i < 10_000; i++) sb.append('b');
    sb.append('c');
    String overflowInput = sb.toString();
    // Word-initial: satisfies the leading \b, so this call's live initial-closure seed is the
    // "anchor satisfied" variant -- drives anchoredCache past DEFAULT_CAP via (b)*'s per-position
    // register growth, exactly like findSurvivesDfaCacheOverflowMidMatch but for matches().
    assertEquals(oracle.matches(overflowInput), m.matches(overflowInput));
    assertTrue(m.fallbackCount() > 0, "overflowInput must overflow anchoredCache mid-scan");

    // Non-word-initial: \b fails at position 0, so this call's live seed is the anchor-*blocked*
    // variant -- a key anchoredCache never interned before it froze -- forcing intern() to return
    // FALLBACK directly from the seed check (runAnchored's hasNewAnchor branch), not mid-scan.
    String blockedInput = " " + overflowInput;
    assertNull(oracle.match(blockedInput), "oracle sanity check: leading \\b must fail here");
    assertEquals(oracle.matches(blockedInput), m.matches(blockedInput));
    assertNull(m.match(blockedInput));
  }

  @Test
  void hasNewAnchor_seedFallbackAfterFindCacheOverflow() throws Exception {
    String pattern = "\\b(a)(b)*c";
    int groupCount = 2;
    LaurikariDfaMatcher m = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    StringBuilder sb = new StringBuilder("a");
    for (int i = 0; i < 10_000; i++) sb.append('b');
    sb.append('c');
    String overflowInput = sb.toString();
    assertTrue(oracle.find(overflowInput), "oracle sanity check");
    assertTrue(m.find(overflowInput), "find() must not lose the match's prefix on cache overflow");
    assertTrue(m.fallbackCount() > 0, "overflowInput must overflow findCache mid-scan");

    // A short, unrelated input scanned from a non-zero offset: findCache is already frozen, and
    // this call's live reinject-seed key (startDfaStateFor's hasNewAnchor branch) was never
    // interned before -- forcing FALLBACK directly at the seed, before any per-character stepping.
    // No 'c' anywhere after the offset, so the delegated PikeVMMatcher scan also finds nothing.
    String shortInput = "xx bbbbb";
    int start = 3;
    assertNull(oracle.findMatchFrom(shortInput, start), "oracle sanity check: no 'c' after offset");
    assertEquals(oracle.findFrom(shortInput, start), m.findFrom(shortInput, start));
    assertNull(m.findMatchFrom(shortInput, start));
  }

  // --- runFind's FALLBACK-mid-scan ternary's "best != null" branch: unlike
  // --- findSurvivesDfaCacheOverflowMidMatch (whose pattern can't accept before the mandatory
  // --- trailing 'c', so `best` is always still null when FALLBACK hits), this pattern's trailing
  // --- 'c' is optional, so every prefix "a", "ab", "abb", ... is already a valid (shorter) match
  // --
  // --- `best` is non-null well before the cache overflows deep into the 'b' run. Per runFind's own
  // --- javadoc (LaurikariDfaMatcher.java:279-283), a mid-scan FALLBACK with a non-null `best`
  // returns
  // --- that already-recorded (possibly non-maximal) match as-is rather than delegating to the
  // --- fallback engine to keep extending -- a deliberate whole-call-delegation-or-nothing
  // trade-off,
  // --- not a bug -- so this test checks self-consistency against the pattern, not equality with
  // the
  // --- oracle's true greedy (longest) match.

  @Test
  void findSurvivesDfaCacheOverflow_withAlreadyRecordedBest() throws Exception {
    String pattern = "\\b(a)(b)*c?";
    int groupCount = 2;
    LaurikariDfaMatcher m = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    StringBuilder sb = new StringBuilder("a");
    for (int i = 0; i < 10_000; i++) sb.append('b');
    String input = sb.toString();

    assertTrue(oracle.find(input), "oracle sanity check");
    assertTrue(m.find(input), "find() must keep the already-recorded best match on cache overflow");
    assertTrue(m.fallbackCount() > 0, "input must overflow findCache mid-scan");

    MatchResult oracleResult = oracle.findMatch(input);
    MatchResult actual = m.findMatch(input);
    assertNotNull(actual, "the already-recorded best match must survive the overflow");
    assertEquals(0, actual.start(0), "group 0 start diverged");
    assertEquals(0, actual.start(1), "group 1 start diverged");
    assertEquals(1, actual.end(1), "group 1 ('a') must end right after position 0");
    assertTrue(
        actual.end(0) >= 1 && actual.end(0) <= oracleResult.end(0),
        "the recorded best is truncated by the overflow, so its span must be a proper prefix of "
            + "the oracle's true greedy match: expected 1 <= "
            + actual.end(0)
            + " <= "
            + oracleResult.end(0));
    assertTrue(
        java.util.regex.Pattern.compile(pattern)
            .matcher(input.substring(0, actual.end(0)))
            .matches(),
        "the recorded best span itself must be a valid, self-consistent match of the pattern");
  }
}
