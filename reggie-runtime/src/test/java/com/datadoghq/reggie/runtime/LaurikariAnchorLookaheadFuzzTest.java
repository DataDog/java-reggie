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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Differential-fuzz verification for the uncommitted "lookahead-class fan-out" fix in {@link
 * LaurikariDFACache} (doc/2026-07-14-laurikari-anchor-caching-fix-design.md), which restores {@code
 * find()}-mode caching for the 5 anchor types {@code LaurikariEligibility} admits ({@code END},
 * {@code STRING_END}, {@code STRING_END_ABSOLUTE}, {@code END_MULTILINE}, {@code WORD_BOUNDARY}).
 *
 * <p>Motivation: a JMH re-run after the fix landed showed the Laurikari {@code find()} path on the
 * three SQL-tokenizer benchmark patterns running far faster than its pre-Phase-2 baseline (~15-16x
 * RE2J vs. the historical ~0.83-0.86x) — implausible enough to warrant checking whether the fan-out
 * is silently skipping real work rather than genuinely caching it. This test asserts exact
 * agreement between {@link LaurikariDfaMatcher} and the {@link PikeVMMatcher} oracle for:
 *
 * <ul>
 *   <li>the exact SQL_ANSI/SQL_MYSQL/SQL_POSTGRESQL patterns from {@code IastRegexpBenchmark}, at
 *       both the benchmark's LONG-scale match and no-match inputs;
 *   <li>hand-crafted adversarial shapes covering {@code \b\d+}, {@code (?m)...--.*$}, boundary
 *       positions within {@code ANCHOR_LOOKAHEAD_LIVE_MARGIN} (2 chars) of the region end, and
 *       {@code \r}/{@code \n}/{@code \r\n} combinations near end-of-input and {@code (?m)$}.
 * </ul>
 */
class LaurikariAnchorLookaheadFuzzTest {

  // --- exact benchmark pattern strings, copied verbatim from IastRegexpBenchmark --------------

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

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder().build(ast, groupCount);
  }

  private static LaurikariDfaMatcher laurikari(String pattern, int groupCount) throws Exception {
    return new LaurikariDfaMatcher(nfa(pattern, groupCount), pattern, groupCount);
  }

  private static PikeVMMatcher pikeVm(String pattern, int groupCount) throws Exception {
    return new PikeVMMatcher(nfa(pattern, groupCount), pattern);
  }

  private static void assertMatchEquals(
      MatchResult expected, MatchResult actual, int groupCount, String context) {
    if (expected == null) {
      assertNull(actual, "expected no match for " + context);
      return;
    }
    assertNotNull(actual, "expected a match for " + context);
    for (int g = 0; g <= groupCount; g++) {
      assertEquals(
          expected.start(g), actual.start(g), "group " + g + " start diverged for " + context);
      assertEquals(expected.end(g), actual.end(g), "group " + g + " end diverged for " + context);
    }
  }

  /** Full find-family differential check, plus a same-input findFrom at every offset. */
  private static void assertFindFamilyMatchesOracle(
      LaurikariDfaMatcher laurikari, PikeVMMatcher oracle, int groupCount, String input) {
    String ctx = "input=[" + preview(input) + "] (len=" + input.length() + ")";
    assertEquals(oracle.find(input), laurikari.find(input), "find() diverged for " + ctx);
    assertEquals(
        oracle.findFrom(input, 0), laurikari.findFrom(input, 0), "findFrom(0) diverged for " + ctx);
    assertMatchEquals(oracle.findMatch(input), laurikari.findMatch(input), groupCount, ctx);
    assertMatchEquals(
        oracle.findMatchFrom(input, 0), laurikari.findMatchFrom(input, 0), groupCount, ctx);

    // Exercise findFrom at every start offset -- covers the reinject/reinjectAfterNl seeding and
    // the ANCHOR_LOOKAHEAD_LIVE_MARGIN boundary from many different regionEnd distances.
    for (int start = 0; start <= input.length(); start++) {
      assertEquals(
          oracle.findFrom(input, start),
          laurikari.findFrom(input, start),
          "findFrom(" + start + ") diverged for " + ctx);
    }
  }

  private static String preview(String s) {
    String p = s.length() > 60 ? s.substring(0, 60) + "...(" + s.length() + " chars)" : s;
    return p.replace("\n", "\\n").replace("\r", "\\r");
  }

  // ==============================================================================================
  // Eligibility re-check: confirm the SQL patterns actually route through Laurikari, not a
  // silent fallback that coincidentally looks fast.
  // ==============================================================================================

  @Test
  void sqlPatterns_areLaurikariEligible() throws Exception {
    for (String pattern : new String[] {SQL_ANSI, SQL_MYSQL, SQL_POSTGRESQL}) {
      NFA nfa = nfa(pattern, 0);
      assertTrue(
          LaurikariEligibility.isEligible(nfa, false),
          "expected " + pattern + " to be Laurikari-eligible");
    }
  }

  // ==============================================================================================
  // Exact benchmark patterns/inputs at LONG scale (mirrors IastRegexpBenchmark.setup(), scale
  // = LONG: "col_x = col_y AND ".repeat(2000) prefix, per that class).
  // ==============================================================================================

  @Test
  void sqlAnsi_longScaleMatchAndNoMatch_agreeWithOracle() throws Exception {
    int groupCount = 0;
    LaurikariDfaMatcher laurikari = laurikari(SQL_ANSI, groupCount);
    PikeVMMatcher oracle = pikeVm(SQL_ANSI, groupCount);

    String sqlMatch =
        "SELECT * FROM users WHERE "
            + "col_x = col_y AND ".repeat(2000)
            + "id = 42 AND name = 'Alice' AND balance = 1234.56";
    String sqlNoMatch =
        "SELECT id, name, email FROM users WHERE "
            + "col_x = col_y AND ".repeat(2000)
            + "id = id ORDER BY id";

    assertTrue(laurikari.find(sqlMatch), "SQL_ANSI LONG-scale match input must find() true");
    assertTrue(oracle.find(sqlMatch), "oracle sanity check: SQL_ANSI match input must find() true");
    assertFindFamilyMatchesOracleFast(laurikari, oracle, groupCount, sqlMatch);

    assertFindFamilyMatchesOracleFast(laurikari, oracle, groupCount, sqlNoMatch);
    assertEquals(
        oracle.find(sqlNoMatch), laurikari.find(sqlNoMatch), "SQL_ANSI no-match find() diverged");

    assertEquals(0, laurikari.fallbackCount(), "no PikeVM fallback expected for SQL_ANSI");
  }

  @Test
  void sqlMysql_longScaleMatchAndNoMatch_agreeWithOracle() throws Exception {
    int groupCount = 0;
    LaurikariDfaMatcher laurikari = laurikari(SQL_MYSQL, groupCount);
    PikeVMMatcher oracle = pikeVm(SQL_MYSQL, groupCount);

    String mysqlMatch =
        "SELECT id, `name` FROM users WHERE "
            + "col_x = col_y AND ".repeat(2000)
            + "id = 1 AND email = 'user@example.com' AND active = 1";
    String mysqlNoMatch =
        "SELECT id, name FROM users WHERE "
            + "col_x = col_y AND ".repeat(2000)
            + "active AND enabled";

    assertTrue(laurikari.find(mysqlMatch), "SQL_MYSQL LONG-scale match input must find() true");
    assertFindFamilyMatchesOracleFast(laurikari, oracle, groupCount, mysqlMatch);

    assertFindFamilyMatchesOracleFast(laurikari, oracle, groupCount, mysqlNoMatch);
    assertEquals(
        oracle.find(mysqlNoMatch),
        laurikari.find(mysqlNoMatch),
        "SQL_MYSQL no-match find() diverged");

    assertEquals(0, laurikari.fallbackCount(), "no PikeVM fallback expected for SQL_MYSQL");
  }

  @Test
  void sqlPostgresql_longScaleMatchAndNoMatch_agreeWithOracle() throws Exception {
    int groupCount = 0;
    LaurikariDfaMatcher laurikari = laurikari(SQL_POSTGRESQL, groupCount);
    PikeVMMatcher oracle = pikeVm(SQL_POSTGRESQL, groupCount);

    String postgresqlMatch =
        "SELECT * FROM docs WHERE "
            + "col_x = col_y AND ".repeat(2000)
            + "body = $$hello world$$ AND revision = 3";
    String postgresqlNoMatch =
        "SELECT id, title FROM docs WHERE " + "col_x = col_y AND ".repeat(2000) + "id = id";

    assertTrue(
        laurikari.find(postgresqlMatch), "SQL_POSTGRESQL LONG-scale match input must find() true");
    assertFindFamilyMatchesOracleFast(laurikari, oracle, groupCount, postgresqlMatch);

    assertFindFamilyMatchesOracleFast(laurikari, oracle, groupCount, postgresqlNoMatch);
    assertEquals(
        oracle.find(postgresqlNoMatch),
        laurikari.find(postgresqlNoMatch),
        "SQL_POSTGRESQL no-match find() diverged");

    assertEquals(0, laurikari.fallbackCount(), "no PikeVM fallback expected for SQL_POSTGRESQL");
  }

  /**
   * Same intent as {@link #assertFindFamilyMatchesOracle} but skips the O(n) per-offset findFrom
   * sweep (too slow at LONG scale, ~38K chars) -- just the four find-family entry points at offset
   * 0, which is exactly what the JMH benchmark calls.
   */
  private static void assertFindFamilyMatchesOracleFast(
      LaurikariDfaMatcher laurikari, PikeVMMatcher oracle, int groupCount, String input) {
    String ctx = "input len=" + input.length();
    assertEquals(oracle.find(input), laurikari.find(input), "find() diverged for " + ctx);
    assertEquals(
        oracle.findFrom(input, 0), laurikari.findFrom(input, 0), "findFrom(0) diverged for " + ctx);
    assertMatchEquals(oracle.findMatch(input), laurikari.findMatch(input), groupCount, ctx);
    assertMatchEquals(
        oracle.findMatchFrom(input, 0), laurikari.findMatchFrom(input, 0), groupCount, ctx);
  }

  // ==============================================================================================
  // Adversarial hand-crafted shapes: \b\d+ at various positions
  // ==============================================================================================

  @Test
  void wordBoundaryDigits_variousPositions() throws Exception {
    // Each input gets a FRESH matcher pair to isolate this test's target -- correctness of the
    // single-call anchor fan-out -- from any cross-input cache-reuse effects.
    String pattern = "\\b\\d+";
    int groupCount = 0;

    String[] inputs = {
      "123",
      "abc123",
      "123abc",
      "abc 123 def",
      "abc123def", // \b never fires mid-word -> no match
      "1",
      "",
      "a1",
      "1a",
      " 1",
      "1 ",
      "999999999999999999999999999999",
      "x".repeat(50) + "42",
      "42" + "x".repeat(50),
    };
    for (String input : inputs) {
      LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
      PikeVMMatcher oracle = pikeVm(pattern, groupCount);
      assertFindFamilyMatchesOracle(laurikari, oracle, groupCount, input);
      assertEquals(0, laurikari.fallbackCount());
    }
  }

  // ==============================================================================================
  // Adversarial hand-crafted shapes: (?m)...--.*$ and $ at end of each line
  // ==============================================================================================

  @Test
  void multilineCommentToEndOfLine_variousLineBreaks() throws Exception {
    // Fresh matcher pair per input -- see comment on wordBoundaryDigits_variousPositions.
    String pattern = "(?m)--.*$";
    int groupCount = 0;

    String[] inputs = {
      "-- comment",
      "-- comment\n",
      "-- comment\r\n",
      "-- comment\r",
      "line1\n-- comment\nline3",
      "line1\r\n-- comment\r\nline3",
      "line1\r-- comment\rline3",
      "no comment here",
      "--",
      "--\n",
      "a\n--\nb\n--\nc",
      "--" + "x".repeat(100),
      "--" + "x".repeat(100) + "\n",
      "--" + "x".repeat(100) + "\r\n",
    };
    for (String input : inputs) {
      LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
      PikeVMMatcher oracle = pikeVm(pattern, groupCount);
      assertFindFamilyMatchesOracle(laurikari, oracle, groupCount, input);
      assertEquals(0, laurikari.fallbackCount());
    }
  }

  // ==============================================================================================
  // Boundary cases within ANCHOR_LOOKAHEAD_LIVE_MARGIN (2 chars) of regionEnd / string end
  // ==============================================================================================

  @Test
  void endAnchor_boundaryPositionsNearRegionEnd() throws Exception {
    // Fresh matcher pair per input -- see comment on wordBoundaryDigits_variousPositions.
    String pattern = "\\d+$";
    int groupCount = 0;

    // empty / length-1 / length-2 inputs, plus digits landing exactly at regionEnd, regionEnd-1,
    // regionEnd-2.
    String[] inputs = {
      "", "1", "12", "123", "a", "a1", "a12", "a123", "1a", // digit not at true end -> no match
      "12a", "a1a1",
    };
    for (String input : inputs) {
      LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
      PikeVMMatcher oracle = pikeVm(pattern, groupCount);
      assertFindFamilyMatchesOracle(laurikari, oracle, groupCount, input);
      assertEquals(0, laurikari.fallbackCount());
    }
  }

  @Test
  void wordBoundary_boundaryPositionsNearRegionEnd() throws Exception {
    // Fresh matcher pair per input -- see comment on wordBoundaryDigits_variousPositions.
    String pattern = "\\w+\\b";
    int groupCount = 0;

    String[] inputs = {"", "a", "ab", "abc", "a ", "ab ", "a.", "ab.", " a", " ab"};
    for (String input : inputs) {
      LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
      PikeVMMatcher oracle = pikeVm(pattern, groupCount);
      assertFindFamilyMatchesOracle(laurikari, oracle, groupCount, input);
      assertEquals(0, laurikari.fallbackCount());
    }
  }

  // ==============================================================================================
  // \r, \n, \r\n line-terminator combinations near end-of-input and near (?m)$
  // ==============================================================================================

  @Test
  void lineTerminatorCombinations_endMultiline() throws Exception {
    // Fresh matcher pair per input -- see comment on wordBoundaryDigits_variousPositions.
    String pattern = "(?m)\\d+$";
    int groupCount = 0;

    String[] inputs = {
      "1\n",
      "1\r\n",
      "1\r",
      "1\n2",
      "1\r\n2",
      "1\r2",
      "12\n",
      "12\r\n",
      "12\r",
      "1\n\n",
      "1\r\n\r\n",
      "a1\nb2\nc3",
      "a1\r\nb2\r\nc3",
      "a1\rb2\rc3",
      "1 ", // LINE SEPARATOR
      "1 ", // PARAGRAPH SEPARATOR
      "1", // NEL
    };
    for (String input : inputs) {
      LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
      PikeVMMatcher oracle = pikeVm(pattern, groupCount);
      assertFindFamilyMatchesOracle(laurikari, oracle, groupCount, input);
      assertEquals(0, laurikari.fallbackCount());
    }
  }

  @Test
  void lineTerminatorCombinations_stringEnd() throws Exception {
    // STRING_END (\Z semantics as used internally) vs STRING_END_ABSOLUTE (\z) both live in the
    // same 5-anchor-type scope; drive them via $ without (?m) (STRING_END: allows a single
    // trailing line terminator) and via \z-equivalent END anchor through end-of-input digit runs.
    // Fresh matcher pair per input -- see comment on wordBoundaryDigits_variousPositions.
    String pattern = "\\d+$"; // no (?m): STRING_END, matches before a final line terminator too
    int groupCount = 0;

    String[] inputs = {
      "1", "1\n", "1\r\n", "1\r", "12\n", "12\r\n", "1\n2", "1\n2\n", "a1\n", "a1\r\n", "a12\n",
      "1\n\n", // digits not immediately before the final terminator -> no match under $ semantics
    };
    for (String input : inputs) {
      LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
      PikeVMMatcher oracle = pikeVm(pattern, groupCount);
      assertFindFamilyMatchesOracle(laurikari, oracle, groupCount, input);
      assertEquals(0, laurikari.fallbackCount());
    }
  }

  // ==============================================================================================
  // Proxy check from the design doc's test plan item 1: anchorSensitiveCount() should stay a
  // small minority of findCache's interned states for the SQL patterns (the fan-out fixes the
  // caching defeat; it must not also be masking a correctness bug by e.g. never marking states
  // anchor-sensitive at all, which would make the "genuine speedup" story impossible to
  // distinguish from "the classification silently stopped applying").
  // ==============================================================================================

  @Test
  void sqlAnsi_anchorSensitiveStatesPresent_afterExercisingFindCache() throws Exception {
    int groupCount = 0;
    LaurikariDfaMatcher laurikari = laurikari(SQL_ANSI, groupCount);

    // Exercise findCache with representative inputs touching every anchor-bearing branch.
    String[] inputs = {
      "SELECT 42 FROM t",
      "-- comment\n",
      "'quoted'",
      "0x1F",
      "SELECT * FROM users WHERE " + "col_x = col_y AND ".repeat(50) + "id = 42",
    };
    for (String input : inputs) {
      laurikari.find(input);
    }

    int anchorSensitiveCount = 0;
    int stateCount = laurikari.findCache.stateCount();
    for (int i = 0; i < stateCount; i++) {
      if (laurikari.findCache.anchorSensitive[i]) anchorSensitiveCount++;
    }
    assertTrue(
        anchorSensitiveCount > 0,
        "expected at least one anchor-sensitive state for SQL_ANSI's \\b\\d+ branch (found 0 of "
            + stateCount
            + " states) -- if this is 0, the classification isn't firing at all, which would make"
            + " any 'speedup' from this fix impossible (there would be nothing left to cache"
            + " smarter)");
    assertTrue(
        anchorSensitiveCount < stateCount,
        "expected anchor-sensitive states to be a minority of findCache's state space, not all of"
            + " it (that would indicate the fan-out isn't narrowing anything)");
  }

  // ==============================================================================================
  // FIXED (issue #108): position-scoped cache pollution in LaurikariDFACache's findCache.
  //
  // Root cause: LaurikariDFACache.lookupOrCompute's caching gate used to check only whether the
  // SOURCE state of a transition was anchorSensitive. It did not check whether the transition's
  // DESTINATION subset newly touches an anchor-bearing state one step later via a single
  // consuming transition + epsilon-closure. So a transition out of a state that was NOT flagged
  // anchorSensitive could, on its first evaluation, resolve through a live checkAnchor(...) call
  // that is specific to that call's (pos, regionEnd) -- and the caching gate unconditionally
  // memoized that position-specific outcome, keyed only on (state, c).
  //
  // This was more severe than "reuse the matcher across two find() calls": LaurikariDfaMatcher's
  // self-anchoring find() already sweeps every start position within ONE call, reinjecting start
  // threads into the SAME findCache instance at each position. So a single find() call over a
  // multi-character input could self-pollute: an anchor outcome computed live at an early
  // position got cached under (state, c) and then wrongly reused at a later position with a
  // different pos/regionEnd, even though no second find() call or matcher reuse was involved.
  //
  // Fix: lookupOrCompute's caching gate additionally checks anchorSensitive[id] (the destination
  // state), not just anchorSensitive[state] (the source) -- see LaurikariDFACache.lookupOrCompute
  // and the anchorSensitive field javadoc for the correctness argument (addClosure adds an
  // anchor-bearing NFA state to a closure unconditionally the moment it becomes epsilon-reachable,
  // so this is a purely structural, position-independent check).
  // ==============================================================================================

  @Test
  void anchorCachePollutionWithinSingleFindCall() throws Exception {
    String pattern = "\\d+$";
    int groupCount = 0;
    LaurikariDfaMatcher laurikari = laurikari(pattern, groupCount);
    PikeVMMatcher oracle = pikeVm(pattern, groupCount);

    // This is a single find() call on a brand-new matcher -- not a cross-call scenario. The
    // oracle correctly matches the trailing digit "1" at index 3.
    assertTrue(oracle.find("a1a1"), "sanity: oracle must find \\d+$ in \"a1a1\" (trailing '1')");

    assertTrue(
        laurikari.find("a1a1"),
        "single find() call must find the trailing digit match in \"a1a1\" (issue #108"
            + " regression)");
  }
}
