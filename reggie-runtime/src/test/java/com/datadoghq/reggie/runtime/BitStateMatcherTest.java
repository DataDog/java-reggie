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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Unit-level tests for {@link BitStateMatcher} and its routing (P1 scope: unit assertions, not the
 * full §9 differential-gate harness against {@code PikeVMMatcher}/{@code java.util.regex}, which is
 * tracked as a separate P2 task per the P1 implementation plan §2.6).
 */
class BitStateMatcherTest {

  // Duplicated from PikeVMMatcherTest.countGroups (no public group-count accessor on RegexParser).
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

  private static BitStateMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new BitStateMatcher(nfa, pattern);
  }

  private static PatternAnalyzer.MatchingStrategy routedStrategy(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new PatternAnalyzer(ast, nfa).analyzeAndRecommend().strategy;
  }

  // -------------------------------------------------------------------------
  // Production-pattern shapes (COMMAND/URL/SQL-like) × representative inputs
  // -------------------------------------------------------------------------

  @Test
  void commandLikePattern() throws Exception {
    BitStateMatcher m = build("^(\\w+)\\s+(--?\\w[\\w-]*)(?:\\s+(\\S+))?$");
    assertTrue(m.matches("deploy --env prod"));
    MatchResult r = m.match("deploy --env prod");
    assertNotNull(r);
    assertEquals("deploy", r.group(1));
    assertEquals("--env", r.group(2));
    assertEquals("prod", r.group(3));
    assertFalse(m.matches("###"));
  }

  @Test
  void urlLikePattern() throws Exception {
    BitStateMatcher m = build("(https?)://([a-zA-Z0-9.-]+)(/[a-zA-Z0-9/_-]*)?");
    MatchResult r = m.findMatch("visit https://example.com/path/to before continuing");
    assertNotNull(r);
    assertEquals("https", r.group(1));
    assertEquals("example.com", r.group(2));
    assertEquals("/path/to", r.group(3));
    assertTrue(m.find("http://a.com"));
    assertFalse(m.find("ftp://a.com"));
  }

  @Test
  void sqlLikePattern() throws Exception {
    BitStateMatcher m = build("(?i)SELECT\\s+(\\*|[\\w,\\s]+)\\s+FROM\\s+(\\w+)");
    MatchResult r = m.findMatch("select id, name from users where id=1");
    assertNotNull(r);
    assertEquals("id, name", r.group(1));
    assertEquals("users", r.group(2));
  }

  @Test
  void findFromAndFindAreConsistentWithMatch() throws Exception {
    BitStateMatcher m = build("[a-z]+@[a-z]+\\.com");
    String input = "contact foo@bar.com or baz@qux.com";
    assertTrue(m.find(input));
    int pos = m.findFrom(input, 0);
    assertTrue(pos >= 0);
    MatchResult r = m.findMatch(input);
    assertNotNull(r);
    assertEquals(pos, r.start());
  }

  @Test
  void namedGroupsPropagateThroughSetNameToIndex() throws Exception {
    BitStateMatcher m = build("(?<year>\\d{4})-(?<month>\\d{2})");
    m.setNameToIndex(
        java.util.Map.of(
            "year", 1,
            "month", 2));
    MatchResult r = m.match("2026-07");
    assertNotNull(r);
    assertEquals("2026", r.group("year"));
    assertEquals("07", r.group("month"));
  }

  @Test
  void matchesBoundedAndMatchBoundedDelegateCorrectly() throws Exception {
    BitStateMatcher m = build("(\\d+)-(\\d+)");
    String input = "xx12-34yy";
    assertTrue(m.matchesBounded(input, 2, 7));
    MatchResult r = m.matchBounded(input, 2, 7);
    assertNotNull(r);
    assertEquals(2, r.start());
    assertEquals(7, r.end());
    assertEquals("12", r.group(1));
    assertEquals("34", r.group(2));
  }

  @Test
  void noMatchReturnsNull() throws Exception {
    BitStateMatcher m = build("^abc$");
    assertNull(m.match("xyz"));
    assertNull(m.findMatch("xyz"));
    assertFalse(m.matches("xyz"));
    assertFalse(m.find("xyz"));
  }

  // -------------------------------------------------------------------------
  // Eligibility exclusions (P1 implementation plan §2.4/§2.6): these shapes must NOT route to
  // BITSTATE_CAPTURE, regardless of whether BitStateMatcher would get them right.
  // -------------------------------------------------------------------------

  @Test
  void backreferencesAreExcluded() throws Exception {
    PatternAnalyzer.MatchingStrategy s = routedStrategy("(\\w+)\\s+\\1");
    assertFalse(s == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, "got " + s);
  }

  @Test
  void lookaroundIsExcluded() throws Exception {
    PatternAnalyzer.MatchingStrategy s = routedStrategy("(?=\\w+@).*@example\\.com");
    assertFalse(s == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, "got " + s);
  }

  @Test
  void atomicGroupsAreExcluded() throws Exception {
    PatternAnalyzer.MatchingStrategy s = routedStrategy("(?>a+)b??");
    assertFalse(s == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE);
  }

  @Test
  void possessiveQuantifiersAreExcluded() throws Exception {
    PatternAnalyzer.MatchingStrategy s = routedStrategy("a++b??");
    assertFalse(s == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE);
  }

  /**
   * {@code (^a?){3}}: anchored-nullable-repeated-body shape must be excluded (design §7.5/§9.3).
   */
  @Test
  void anchoredNullableRepeatedGroupIsExcluded() throws Exception {
    PatternAnalyzer.MatchingStrategy s = routedStrategy("(^a?){3}");
    assertFalse(s == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, "got " + s);
  }

  /** {@code (?m)(^x?)+}: same shape under multiline mode. */
  @Test
  void multilineAnchoredNullableRepeatedGroupIsExcluded() throws Exception {
    PatternAnalyzer.MatchingStrategy s = routedStrategy("(?m)(^x?)+");
    assertFalse(s == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, "got " + s);
  }

  /** {@code \A{3}a}: bare start anchor repeated by an outer quantifier. */
  @Test
  void repeatedStringStartAnchorIsExcluded() throws Exception {
    PatternAnalyzer.MatchingStrategy s = routedStrategy("\\A{3}a");
    assertFalse(s == PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE, "got " + s);
  }

  // -------------------------------------------------------------------------
  // Budget/fallback (P1 implementation plan §2.6 / design §9.5): once stateCount * (span + 1)
  // exceeds BUDGET_CELLS, BitStateMatcher must delegate the whole call to PikeVMMatcher and record
  // the delegation via fallbackCount() — no silent truncation.
  // -------------------------------------------------------------------------

  @Test
  void oversizedInputFallsBackToPikeVmAndCountsIt() throws Exception {
    String pattern = "(a+)(b+)(c+)";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 100_000; i++) sb.append('a');
    for (int i = 0; i < 100_000; i++) sb.append('b');
    for (int i = 0; i < 100_000; i++) sb.append('c');
    String input = sb.toString();

    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, countGroups(pattern));

    BitStateMatcher bitState = new BitStateMatcher(nfa, pattern);
    PikeVMMatcher pikeVm = new PikeVMMatcher(nfa, pattern);

    MatchResult expected = pikeVm.match(input);
    MatchResult actual = bitState.match(input);
    assertNotNull(expected);
    assertNotNull(actual);
    assertEquals(expected.start(), actual.start());
    assertEquals(expected.end(), actual.end());
    for (int g = 1; g <= 3; g++) {
      assertEquals(expected.start(g), actual.start(g), "group " + g + " start");
      assertEquals(expected.end(g), actual.end(g), "group " + g + " end");
    }
    assertEquals(1, bitState.fallbackCount(), "expected exactly one budget-exceeded delegation");
  }
}
