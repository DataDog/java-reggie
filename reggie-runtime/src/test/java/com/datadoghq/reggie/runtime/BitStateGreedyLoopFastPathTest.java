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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the greedy single-char-class loop fast path in {@code BitStateMatcher.search} (see {@code
 * greedyLoopShapeAt}): the fast-consume block must replicate, byte-for-byte in observable behavior,
 * the generic per-char job-stack path it replaces. The equivalence claims it leans on:
 *
 * <ul>
 *   <li>greedy give-back order — exit jobs pop longest-run-first, so backtracking explores the
 *       longest consumption before shorter ones (Perl greedy semantics);
 *   <li>visited short-circuit — a (stateId, pos) cell already expanded by another path stops the
 *       fast-consume replication exactly where the generic chain would have stopped;
 *   <li>captures written by states around the loop are unaffected (the loop states themselves are
 *       group-free by detection).
 * </ul>
 *
 * <p>All parity assertions run against {@code java.util.regex} as the oracle, over both the IAST
 * loss-pattern shapes that motivated the fast path (URL authority, LDAP literal, SQL literal — long
 * greedy char-class runs on the match path) and adversarial give-back shapes.
 */
class BitStateGreedyLoopFastPathTest {

  private static final String URL_AUTHORITY =
      "^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)";
  private static final String LDAP = "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)";
  private static final String SQL_ANSI =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";
  private static final String MULTIPLE_STARS = "(a*b*c*d*e*)";

  private static BitStateMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    // lazyAware=true mirrors RuntimeCompiler's lazy-NFA construction (result.lazyNfa →
    // ThompsonBuilder(true)); for all-greedy patterns both builders emit identical NFAs, and
    // lazy quantifiers get their exit-first priority only under lazyAware=true.
    NFA nfa = new ThompsonBuilder(true).build(ast, countGroups(pattern));
    return new BitStateMatcher(nfa, pattern);
  }

  private static java.util.regex.Matcher jdk(String pattern, String input) {
    return java.util.regex.Pattern.compile(pattern).matcher(input);
  }

  /** One (pattern, input) parity case: find() result + all group spans must match the JDK. */
  record FindCase(String pattern, String input) {}

  static List<FindCase> findCases() {
    List<FindCase> cases = new ArrayList<>();
    // URL authority — the motivating shape: [^@]+ over a long credential run, ^-anchored branch
    // plus an unanchored query branch (multiple loop shapes in one pattern).
    cases.add(new FindCase(URL_AUTHORITY, "https://admin:s3cr3t@internal.corp/api/v1/health"));
    cases.add(
        new FindCase(
            URL_AUTHORITY,
            "https://" + "a".repeat(20) + ":" + "b".repeat(20) + "@internal.corp/api/v1/health"));
    cases.add(
        new FindCase(
            URL_AUTHORITY,
            "https://" + "a".repeat(2000) + ":" + "b".repeat(2000) + "@internal.corp"));
    cases.add(new FindCase(URL_AUTHORITY, "https://api.example.com/search?q=hello&pw=hunter2"));
    cases.add(new FindCase(URL_AUTHORITY, "nothing to see here"));
    // LDAP literal — [^)]+ over a long value, with a lazy prefix the fast path must not touch.
    for (String in :
        new String[] {
          "(uid=jsmith)",
          "(uid=" + "jsmith".repeat(10) + ")",
          "(uid=" + "jsmith".repeat(500) + ")",
          "cn~= nobody",
          "no parens at all"
        }) {
      cases.add(new FindCase(LDAP, in));
    }
    // SQL literal — alternation of several loop shapes plus anchored comment branches.
    for (String in :
        new String[] {
          "SELECT * FROM t WHERE x = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaa'",
          "SELECT 0x1f, b'0101 FROM t",
          "INSERT INTO t VALUES ('')",
          "SELECT a FROM t -- trailing comment",
          "SELECT a FROM t /* block comment */",
          "plain select without literals"
        }) {
      cases.add(new FindCase(SQL_ANSI, in));
    }
    // Star chains — five back-to-back fast-pathable loops, forcing give-back between them.
    for (String in : new String[] {"aabbccddeeff", "abcde", "", "e", "eeeedddcccbba", "xyzzy"}) {
      cases.add(new FindCase(MULTIPLE_STARS, in));
    }
    // Give-back shapes: the exit must be explored longest-first.
    cases.add(new FindCase("a+a", "aaaa"));
    cases.add(new FindCase("[^x]+x", "yyyyx"));
    cases.add(new FindCase("(a*)(a)", "aaa")); // group1="aa", group2="a" pins give-back order
    cases.add(new FindCase("(a+)(a*)", "aaa"));
    cases.add(new FindCase("a+x?a+", "aaxaa"));
    // Loop re-entry via another path mid-run (visited short-circuit across paths):
    // (a|b)+ can re-enter the loop body per branch; aa+ revisits the same class loop.
    cases.add(new FindCase("(?:a|ab)+b", "abab"));
    cases.add(new FindCase("aa+b", "aab"));
    cases.add(new FindCase("a+a*b", "aab"));
    // Empty / single-char runs and run that hits span end.
    cases.add(new FindCase("\\d+", "abc123def456"));
    cases.add(new FindCase("[0-9]+", "no digits"));
    return cases;
  }

  @ParameterizedTest(name = "{index}: {1}")
  @MethodSource("findCases")
  void findAndCaptureParityWithJdk(FindCase c) throws Exception {
    BitStateMatcher m = build(c.pattern());
    java.util.regex.Matcher oracle = jdk(c.pattern(), c.input());

    MatchResult actual = m.findMatch(c.input());
    if (!oracle.find()) {
      assertNull(actual, "JDK says no match, reggie found one: " + actual);
      return;
    }
    assertNotNull(actual, "JDK found a match, reggie found none");
    assertEquals(oracle.start(), actual.start(0), "group 0 start");
    assertEquals(oracle.end(), actual.end(0), "group 0 end");
    for (int g = 1; g <= oracle.groupCount(); g++) {
      assertEquals(oracle.start(g), actual.start(g), "group " + g + " start");
      assertEquals(oracle.end(g), actual.end(g), "group " + g + " end");
    }
  }

  @Test
  void greedyGiveBackOrderPinnedByCaptures() throws Exception {
    // (a*)(a) on "aaa": greedy a* takes "aaa" first, must give back one char for (a) —
    // group(1)="aa", group(2)="a". If exit jobs popped shortest-first, group(1) would be "".
    BitStateMatcher m = build("(a*)(a)");
    MatchResult r = m.findMatch("aaa");
    assertNotNull(r);
    assertEquals(0, r.start(1));
    assertEquals(2, r.end(1));
    assertEquals(2, r.start(2));
    assertEquals(3, r.end(2));
  }

  @Test
  void matchesParityWithJdkForLoopPatterns() throws Exception {
    String[] pats = {"a+a", "(a*)(a)", "[0-9]+", MULTIPLE_STARS, "[^x]+x"};
    String[] ins = {"aaaa", "aaa", "a", "", "01234", "aabbccddeeff", "yyyyx", "yyx"};
    for (String p : pats) {
      BitStateMatcher m = build(p);
      for (String in : ins) {
        boolean expected = jdk(p, in).matches();
        assertEquals(
            expected, m.matches(in), "matches() divergence for <" + p + "> on <" + in + ">");
      }
    }
  }

  @Test
  void lazyLoopsStayOnGenericPath() throws Exception {
    // .*? is lazy: exit-first priority. The fast path must not fire (detection requires
    // loop-back-first eps order), and laziness itself must be preserved against the JDK.
    String p = "(<\\w+>).*?(</\\w+>)";
    BitStateMatcher m = build(p);
    String in = "<a>x<b>y</b>z</a>";
    java.util.regex.Matcher oracle = jdk(p, in);
    assertTrue(oracle.find());
    MatchResult r = m.findMatch(in);
    assertNotNull(r);
    assertEquals(oracle.start(), r.start(0));
    assertEquals(oracle.end(), r.end(0));
  }

  @Test
  void longRunsRemainWithinLinearBudget() throws Exception {
    // A no-match input where the fast path consumes a 10k-char run and then must fail:
    // verifies the visited-marking loop doesn't blow the budget accounting (all within
    // stateCount x span + 1 cells; 10k stays under BUDGET_CELLS, unlike the PikeVM-delegating
    // oversized case) and stays fast (no stack explosion).
    BitStateMatcher m = build("[a-z]+@");
    String in = "z".repeat(10_000);
    assertNull(m.findMatch(in));
    assertFalse(m.matches(in));
    assertEquals(0L, m.fallbackCount());
  }

  // Verbatim copy of BitStateMatcherTest's countGroups (kept in sync manually; both files build
  // NFAs directly from RegexParser output and need the same capturing-group count).
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
