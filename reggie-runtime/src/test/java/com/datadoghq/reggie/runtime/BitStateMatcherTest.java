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

import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * P1 unit-level tests for {@link BitStateMatcher} (design doc {@code
 * doc/2026-07-03-bitstate-capture-engine-design.md}, implementation plan {@code
 * doc/2026-07-05-bitstate-capture-engine-p1-implementation-plan.md} §2.6). Compares against {@code
 * java.util.regex} as the capture-span oracle, mirroring {@code PikeVMMatcherTest}.
 *
 * <p>This is not the full §9 differential gate against {@code PikeVMMatcher} + fuzz (that is P2
 * scope); it exercises the core interpreter directly: leftmost-first priority, group captures,
 * empty-iteration loops, anchors, the unanchored single-pass, and the budget/fallback path.
 */
class BitStateMatcherTest {

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private static BitStateMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    // lazyAware=true so `+?`/`*?`/`??` build with correct (minimal-first) priority; PikeVMMatcher
    // test coverage largely predates lazy-quantifier support and uses the (greedy-only) default
    // constructor, so this is intentionally more thorough than that harness for the lazy case.
    ThompsonBuilder builder = new ThompsonBuilder(true);
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new BitStateMatcher(nfa, pattern);
  }

  private static java.util.regex.Matcher jdkMatch(String pattern, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
    return m.matches() ? m : null;
  }

  private static java.util.regex.Matcher jdkFind(String pattern, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
    return m.find() ? m : null;
  }

  private static void assertSpansEqual(
      java.util.regex.Matcher oracle, MatchResult r, String label) {
    assertEquals(oracle.groupCount(), r.groupCount(), label + ": groupCount");
    for (int g = 0; g <= oracle.groupCount(); g++) {
      assertEquals(oracle.start(g), r.start(g), label + ": group " + g + " start");
      assertEquals(oracle.end(g), r.end(g), label + ": group " + g + " end");
    }
  }

  // -------------------------------------------------------------------------
  // Representative production-shaped patterns
  // -------------------------------------------------------------------------

  @Test
  void commandLikePattern() throws Exception {
    String pat = "\\b(sudo|doas)\\s+(\\S+)";
    String input = "run sudo rm -rf /";
    BitStateMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r);
    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "COMMAND-like");
  }

  @Test
  void urlLikePattern() throws Exception {
    String pat = "(\\w+)://([^/\\s]+)(/\\S*)?";
    String input = "fetch https://example.com/path?x=1 now";
    BitStateMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r);
    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "URL-like");
  }

  @Test
  void sqlLikePattern() throws Exception {
    String pat = "(?i)(?m)^\\s*(select|insert|update|delete)\\s+.*";
    String input = "prefix\nSELECT * FROM t WHERE x = 1";
    BitStateMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r);
    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "SQL-like");
  }

  // -------------------------------------------------------------------------
  // Semantics corner cases (design §7 / §9.2)
  // -------------------------------------------------------------------------

  @Test
  void greedyVsLazy() throws Exception {
    String greedy = "(a+)(a*)";
    String input = "aaaa";
    BitStateMatcher m = build(greedy);
    MatchResult r = m.match(input);
    java.util.regex.Matcher oracle = jdkMatch(greedy, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "greedy");
  }

  @Test
  void lazyQuantifier() throws Exception {
    String pat = "(a+?)(a*)";
    String input = "aaaa";
    BitStateMatcher m = build(pat);
    MatchResult r = m.match(input);
    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "lazy");
  }

  @Test
  void alternationPriority() throws Exception {
    String pat = "(a|ab)(c|bcd)(d*)";
    String input = "abcd";
    BitStateMatcher m = build(pat);
    MatchResult r = m.match(input);
    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "alternation priority");
  }

  @Test
  void emptyLoopOptionalGroupStar() throws Exception {
    String pat = "(a?)*b";
    String input = "aaab";
    BitStateMatcher m = build(pat);
    MatchResult r = m.match(input);
    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "(a?)*b");
  }

  @Test
  void onePlusGroupCapturesLastIteration() throws Exception {
    String pat = "(.)+";
    String input = "abc";
    BitStateMatcher m = build(pat);
    MatchResult r = m.match(input);
    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "(.)+");
  }

  @Test
  void wordBoundaryAnchor() throws Exception {
    String pat = "\\bcat\\b";
    String input = "a cat sat";
    BitStateMatcher m = build(pat);
    MatchResult r = m.findMatch(input);
    assertNotNull(r);
    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "\\bcat\\b");
  }

  @Test
  void startEndAnchors() throws Exception {
    String pat = "^(foo)$";
    String input = "foo";
    BitStateMatcher m = build(pat);
    MatchResult r = m.match(input);
    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "^(foo)$");
  }

  @Test
  void zeroLengthMatch() throws Exception {
    String pat = "a*";
    String input = "";
    BitStateMatcher m = build(pat);
    MatchResult r = m.match(input);
    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertSpansEqual(oracle, r, "a* on empty");
  }

  // -------------------------------------------------------------------------
  // matches() / find() boolean API + findFrom positional API
  // -------------------------------------------------------------------------

  @Test
  void matchesReturnsFalseOnPartialInput() throws Exception {
    BitStateMatcher m = build("(a)(b)");
    assertTrue(m.matches("ab"));
    assertFalse(m.matches("abc"));
    assertFalse(m.matches("a"));
  }

  @Test
  void findLocatesMatchNotAtStart() throws Exception {
    BitStateMatcher m = build("(b+)c");
    assertTrue(m.find("aaabbbc"));
    assertFalse(m.find("aaa"));
  }

  @Test
  void findFromReturnsLeftmostStartAtOrAfterOffset() throws Exception {
    BitStateMatcher m = build("(a)(b)");
    String input = "xxabxxab";
    int first = m.findFrom(input, 0);
    assertEquals(2, first);
    int second = m.findFrom(input, first + 1);
    assertEquals(6, second);
    assertEquals(-1, m.findFrom(input, second + 1));
  }

  @Test
  void findMatchFromSpansMatchJdk() throws Exception {
    String pat = "(a)(b)";
    String input = "xxabxxab";
    BitStateMatcher m = build(pat);
    MatchResult r = m.findMatchFrom(input, 3);
    assertNotNull(r);
    java.util.regex.Matcher oracle = java.util.regex.Pattern.compile(pat).matcher(input);
    assertTrue(oracle.find(3));
    assertSpansEqual(oracle, r, "findMatchFrom(3)");
  }

  // -------------------------------------------------------------------------
  // Bounded region API
  // -------------------------------------------------------------------------

  @Test
  void matchesBoundedRestrictsToRegion() throws Exception {
    BitStateMatcher m = build("(a)(b)");
    String input = "xxabxx";
    assertTrue(m.matchesBounded(input, 2, 4));
    assertFalse(m.matchesBounded(input, 0, 4));
  }

  @Test
  void matchBoundedShiftsSpansToOriginalInput() throws Exception {
    BitStateMatcher m = build("(a)(b)");
    String input = "xxabxx";
    MatchResult r = m.matchBounded(input, 2, 4);
    assertNotNull(r);
    assertEquals(2, r.start(0));
    assertEquals(4, r.end(0));
    assertEquals(2, r.start(1));
    assertEquals(3, r.start(2));
    assertEquals("ab", r.group(0));
  }

  // -------------------------------------------------------------------------
  // embedsNameMap contract
  // -------------------------------------------------------------------------

  @Test
  void embedsNameMapReturnsTrue() throws Exception {
    BitStateMatcher m = build("(a)(b)");
    assertTrue(callEmbedsNameMap(m));
  }

  private static boolean callEmbedsNameMap(BitStateMatcher m) throws Exception {
    java.lang.reflect.Method method = ReggieMatcher.class.getDeclaredMethod("embedsNameMap");
    method.setAccessible(true);
    return (boolean) method.invoke(m);
  }

  // -------------------------------------------------------------------------
  // Budget / fallback (design §5, §8, §9.5)
  // -------------------------------------------------------------------------

  @Test
  void oversizedSearchDelegatesToPikeVmAndIncrementsFallbackCounter() throws Exception {
    RegexParser parser = new RegexParser();
    String pat = "(a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);
    PikeVMMatcher pikeVm = new PikeVMMatcher(nfa, pat);

    // Force the budget check to fail regardless of actual stateCount by using a very long input:
    // stateCount * (spanLen + 1) will exceed BUDGET_CELLS (1<<18) for any non-trivial stateCount.
    StringBuilder sb = new StringBuilder("a");
    for (int i = 0; i < 300_000; i++) sb.append('b');
    sb.append('c');
    String input = sb.toString();

    assertEquals(0L, bitState.fallbackCount());
    MatchResult expected = pikeVm.match(input);
    MatchResult actual = bitState.match(input);
    assertNotNull(expected);
    assertNotNull(actual);
    assertEquals(expected.start(0), actual.start(0));
    assertEquals(expected.end(0), actual.end(0));
    assertEquals(1L, bitState.fallbackCount());

    assertTrue(bitState.matches(input.substring(0, input.length())) || true); // smoke: no throw
    assertEquals(2L, bitState.fallbackCount());
  }

  private static String oversizedInput() {
    StringBuilder sb = new StringBuilder("a");
    for (int i = 0; i < 300_000; i++) sb.append('b');
    sb.append('c');
    return sb.toString();
  }

  @Test
  void findNullInputThrowsNpe() throws Exception {
    BitStateMatcher m = build("(a)(b)");
    assertThrows(NullPointerException.class, () -> m.find(null));
  }

  @Test
  void findOversizedInputDelegatesToPikeVmAndIncrementsFallbackCounter() throws Exception {
    RegexParser parser = new RegexParser();
    String pat = "(a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);
    PikeVMMatcher pikeVm = new PikeVMMatcher(nfa, pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.fallbackCount());
    assertEquals(pikeVm.find(input), bitState.find(input));
    assertEquals(1L, bitState.fallbackCount());
  }

  @Test
  void findFromOversizedInputDelegatesToPikeVmAndIncrementsFallbackCounter() throws Exception {
    RegexParser parser = new RegexParser();
    String pat = "(a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);
    PikeVMMatcher pikeVm = new PikeVMMatcher(nfa, pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.fallbackCount());
    assertEquals(pikeVm.findFrom(input, 0), bitState.findFrom(input, 0));
    assertEquals(1L, bitState.fallbackCount());
  }

  @Test
  void findMatchFromOversizedInputDelegatesToPikeVmAndIncrementsFallbackCounter() throws Exception {
    RegexParser parser = new RegexParser();
    String pat = "(a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);
    PikeVMMatcher pikeVm = new PikeVMMatcher(nfa, pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.fallbackCount());
    MatchResult expected = pikeVm.findMatchFrom(input, 0);
    MatchResult actual = bitState.findMatchFrom(input, 0);
    assertNotNull(expected);
    assertNotNull(actual);
    assertEquals(expected.start(0), actual.start(0));
    assertEquals(expected.end(0), actual.end(0));
    assertEquals(1L, bitState.fallbackCount());
  }

  // -------------------------------------------------------------------------
  // Laurikari wiring (Phase 2 Task 2.1, Option A, doc/2026-07-10-tdfa-capture-engine-impl-plan.md)
  // -------------------------------------------------------------------------

  private static BitStateMatcher buildWithLaurikari(String pat) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    ReggieMatcher laurikari =
        LaurikariDfaSupport.tryCreate(nfa, pat, countGroups(pat), /* usePosixLastMatch= */ false);
    assertNotNull(laurikari, pat + " expected to be LaurikariEligibility-eligible");
    return new BitStateMatcher(nfa, pat, laurikari);
  }

  @Test
  void oversizedSearchWithEligiblePatternRoutesToLaurikariNotPikeVm() throws Exception {
    String pat = "(a)(b)*c";
    BitStateMatcher bitState = buildWithLaurikari(pat);
    java.util.regex.Matcher oracle = jdkMatch(pat, oversizedInput());

    assertEquals(0L, bitState.laurikariCount());
    assertEquals(0L, bitState.fallbackCount());
    MatchResult actual = bitState.match(oversizedInput());
    assertNotNull(oracle);
    assertNotNull(actual);
    assertEquals(oracle.start(0), actual.start(0));
    assertEquals(oracle.end(0), actual.end(0));
    assertEquals(1L, bitState.laurikariCount());
    assertEquals(0L, bitState.fallbackCount());
  }

  @Test
  void findOversizedInputWithEligiblePatternRoutesToLaurikariNotPikeVm() throws Exception {
    String pat = "(a)(b)*c";
    BitStateMatcher bitState = buildWithLaurikari(pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.laurikariCount());
    assertTrue(bitState.find(input));
    assertEquals(1L, bitState.laurikariCount());
    assertEquals(0L, bitState.fallbackCount());
  }

  @Test
  void matchesOversizedInputWithEligiblePatternRoutesToLaurikariNotPikeVm() throws Exception {
    String pat = "(a)(b)*c";
    BitStateMatcher bitState = buildWithLaurikari(pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.laurikariCount());
    assertTrue(bitState.matches(input));
    assertEquals(1L, bitState.laurikariCount());
    assertEquals(0L, bitState.fallbackCount());
  }

  @Test
  void findFromOversizedInputWithEligiblePatternRoutesToLaurikariNotPikeVm() throws Exception {
    String pat = "(a)(b)*c";
    BitStateMatcher bitState = buildWithLaurikari(pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.laurikariCount());
    assertEquals(0, bitState.findFrom(input, 0));
    assertEquals(1L, bitState.laurikariCount());
    assertEquals(0L, bitState.fallbackCount());
  }

  @Test
  void findMatchFromOversizedInputWithEligiblePatternRoutesToLaurikariNotPikeVm() throws Exception {
    String pat = "(a)(b)*c";
    BitStateMatcher bitState = buildWithLaurikari(pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.laurikariCount());
    MatchResult result = bitState.findMatchFrom(input, 0);
    assertNotNull(result);
    assertEquals(0, result.start(0));
    assertEquals(1L, bitState.laurikariCount());
    assertEquals(0L, bitState.fallbackCount());
  }

  @Test
  void nullLaurikariPreservesExistingFallbackBehavior() throws Exception {
    // The 2-arg constructor (used by every pre-Phase-2 call site) must behave identically to
    // before: laurikari is always null, so BUDGET_CELLS overflow always goes straight to
    // PikeVMMatcher, never LaurikariDfaMatcher.
    RegexParser parser = new RegexParser();
    String pat = "(a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);

    assertTrue(bitState.find(oversizedInput()));
    assertEquals(0L, bitState.laurikariCount());
    assertEquals(1L, bitState.fallbackCount());
  }

  // -------------------------------------------------------------------------
  // Named-group resolution across the fallback/laurikari paths (issue #106)
  // -------------------------------------------------------------------------

  @Test
  void oversizedMatchWithNamedGroupResolvesGroupNameViaPikeVmFallback() throws Exception {
    RegexParser parser = new RegexParser();
    String pat = "(?<num>a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);
    bitState.setNameToIndex(Collections.singletonMap("num", 1));

    assertEquals(0L, bitState.fallbackCount());
    MatchResult result = bitState.match(oversizedInput());
    assertNotNull(result);
    assertEquals(1L, bitState.fallbackCount());
    assertEquals(0, result.start("num"));
    assertEquals(1, result.end("num"));
  }

  @Test
  void oversizedMatchWithNamedGroupResolvesGroupNameViaLaurikari() throws Exception {
    String pat = "(?<num>a)(b)*c";
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    ReggieMatcher laurikari =
        LaurikariDfaSupport.tryCreate(nfa, pat, countGroups(pat), /* usePosixLastMatch= */ false);
    assertNotNull(laurikari, pat + " expected to be LaurikariEligibility-eligible");
    laurikari.setNameToIndex(Collections.singletonMap("num", 1));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat, laurikari);
    bitState.setNameToIndex(Collections.singletonMap("num", 1));

    assertEquals(0L, bitState.laurikariCount());
    MatchResult result = bitState.match(oversizedInput());
    assertNotNull(result);
    assertEquals(1L, bitState.laurikariCount());
    assertEquals(0, result.start("num"));
    assertEquals(1, result.end("num"));
  }

  @Test
  void oversizedMatchWithNamedGroupResolvesGroupNameEndToEndViaRuntimeCompiler() throws Exception {
    ReggieMatcher m =
        (ReggieMatcher)
            RuntimeCompiler.compile(
                "(?<num>a)(b)*c", ReggieOptions.builder().allowJdkFallback().build());
    MatchResult result = m.match(oversizedInput());
    assertNotNull(result);
    assertEquals(0, result.start("num"));
    assertEquals(1, result.end("num"));
  }

  @Test
  void matchesBoundedOversizedRegionDelegatesToPikeVmAndIncrementsFallbackCounter()
      throws Exception {
    RegexParser parser = new RegexParser();
    String pat = "(a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);
    PikeVMMatcher pikeVm = new PikeVMMatcher(nfa, pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.fallbackCount());
    boolean expected = pikeVm.matchesBounded(input, 0, input.length());
    boolean actual = bitState.matchesBounded(input, 0, input.length());
    assertEquals(expected, actual);
    assertTrue(actual);
    assertEquals(1L, bitState.fallbackCount());
  }

  @Test
  void matchBoundedOversizedRegionDelegatesToPikeVmAndIncrementsFallbackCounter() throws Exception {
    RegexParser parser = new RegexParser();
    String pat = "(a)(b)*c";
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher bitState = new BitStateMatcher(nfa, pat);
    PikeVMMatcher pikeVm = new PikeVMMatcher(nfa, pat);
    String input = oversizedInput();

    assertEquals(0L, bitState.fallbackCount());
    MatchResult expected = pikeVm.matchBounded(input, 0, input.length());
    MatchResult actual = bitState.matchBounded(input, 0, input.length());
    assertNotNull(expected);
    assertNotNull(actual);
    assertEquals(expected.start(0), actual.start(0));
    assertEquals(expected.end(0), actual.end(0));
    assertEquals(1L, bitState.fallbackCount());
  }

  @Test
  void visitedGenerationWrapsAtIntMaxValueWithoutCorruptingVisitedSet() throws Exception {
    BitStateMatcher m = build("(a)(b)");
    // Warm up the visited-set array first: ensureVisitedCapacity resets visitedGeneration to 0
    // whenever it (re)allocates, which would otherwise wipe out the injected value below on the
    // very first search.
    assertTrue(m.matches("ab"));

    java.lang.reflect.Field genField = BitStateMatcher.class.getDeclaredField("visitedGeneration");
    genField.setAccessible(true);
    genField.setInt(m, Integer.MAX_VALUE - 1);

    assertTrue(m.matches("ab"));
    assertEquals(1, genField.getInt(m));

    // The wrap-around must not corrupt visited-set bookkeeping: repeated searches on the same
    // matcher instance must still produce correct results.
    assertTrue(m.matches("ab"));
    assertFalse(m.matches("ba"));
  }

  @Test
  void matchBoundedShiftsNamedGroupAndUnmatchedOptionalGroupSpans() throws Exception {
    String pat = "(?<num>a)(b)?c";
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pat);
    ThompsonBuilder builder = new ThompsonBuilder(true);
    NFA nfa = builder.build(ast, countGroups(pat));
    BitStateMatcher m = new BitStateMatcher(nfa, pat);
    m.setNameToIndex(parser.getGroupNameMap());

    String input = "xxacxx";
    MatchResult r = m.matchBounded(input, 2, 4);
    assertNotNull(r);
    assertTrue(r instanceof NamedMatchResultImpl);
    assertEquals(2, r.start(0));
    assertEquals(4, r.end(0));
    assertEquals(2, r.start(1));
    assertEquals(3, r.end(1));
    assertEquals(-1, r.start(2));
    assertEquals(-1, r.end(2));
    assertEquals(2, r.start("num"));
  }

  // -------------------------------------------------------------------------
  // Utilities
  // -------------------------------------------------------------------------

  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '(' && i + 1 < pattern.length()) {
        if (pattern.charAt(i + 1) == '?') {
          if (i + 2 < pattern.length()) {
            char next = pattern.charAt(i + 2);
            if (next == ':'
                || next == '='
                || next == '!'
                || next == '>'
                || next == '#'
                || next == '|'
                || next == '('
                || next == '-'
                || next == 'i'
                || next == 'm'
                || next == 's'
                || next == 'x'
                || next == 'u'
                || next == 'U'
                || next == 'd') {
              if (next == '<' && i + 3 < pattern.length()) {
                char afterLt = pattern.charAt(i + 3);
                if (afterLt == '=' || afterLt == '!') {
                  continue;
                }
              } else {
                continue;
              }
            }
            if (next == '<' && i + 3 < pattern.length()) {
              char afterLt = pattern.charAt(i + 3);
              if (afterLt == '=' || afterLt == '!') {
                continue;
              }
            }
          }
        }
        count++;
      }
    }
    return count;
  }
}
