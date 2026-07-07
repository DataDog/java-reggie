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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * TDD tests for {@link PikeVMMatcher}. Each test compares group spans against {@code
 * java.util.regex} as the oracle.
 */
class PikeVMMatcherTest {

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  /** Build a PikeVMMatcher for the given pattern. */
  private static PikeVMMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    NFA nfa = builder.build(ast, countGroups(pattern));
    return new PikeVMMatcher(nfa, pattern);
  }

  /** JDK oracle: whole-input match, returns null if no match. */
  private static java.util.regex.Matcher jdkMatch(String pattern, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
    return m.matches() ? m : null;
  }

  /** JDK oracle: find anywhere in input, returns null if no match. */
  private static java.util.regex.Matcher jdkFind(String pattern, String input) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(input);
    return m.find() ? m : null;
  }

  // -------------------------------------------------------------------------
  // Repro tests — spans must match JDK exactly
  // -------------------------------------------------------------------------

  /** {@code (.)?b} on {@code "b"}: group 1 is optional and unmatched → start/end must be -1. */
  @Test
  void optionalGroupNotMatched() throws Exception {
    String pat = "(.)?b";
    String input = "b";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.match(input);
    assertNotNull(r, "should match");

    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
    assertEquals(-1, r.start(1), "unmatched group start must be -1");
    assertEquals(-1, r.end(1), "unmatched group end must be -1");
  }

  /**
   * {@code (0)?\Z} on {@code ""}: group 1 optional, input is empty, \Z matches end. Group 1 must be
   * [-1,-1].
   */
  @Test
  void optionalGroupWithStringEndAnchor() throws Exception {
    String pat = "(0)?\\Z";
    String input = "";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.match(input);
    assertNotNull(r, "should match");

    java.util.regex.Matcher oracle = jdkMatch(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
    assertEquals(-1, r.start(1));
    assertEquals(-1, r.end(1));
  }

  /**
   * {@code (fo|foo)} on {@code "foo"}: first alternative "fo" wins (Perl leftmost-greedy). JDK
   * agrees: group 1 = [0,2].
   */
  @Test
  void firstAltWins_foOrFoo() throws Exception {
    String pat = "(fo|foo)";
    String input = "foo";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
  }

  /** {@code (a|ab)} on {@code "ab"}: first alternative "a" wins. JDK: group 1 = [0,1]. */
  @Test
  void firstAltWins_aOrAb() throws Exception {
    String pat = "(a|ab)";
    String input = "ab";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
  }

  /**
   * {@code (a|ab)(c|)} on {@code "abc"}: Perl semantics; JDK gives group 1 = [0,1], group 2 =
   * [1,2].
   */
  @Test
  void twoGroupAlternation() throws Exception {
    String pat = "(a|ab)(c|)";
    String input = "abc";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
    assertEquals(oracle.start(2), r.start(2), "group 2 start");
    assertEquals(oracle.end(2), r.end(2), "group 2 end");
  }

  /** {@code a{1}()|.} on {@code "a"}: tests that group 1 span matches JDK. */
  @Test
  void boundedQuantifierGroupOrDot() throws Exception {
    String pat = "a{1}()|.";
    String input = "a";
    PikeVMMatcher m = build(pat);

    MatchResult r = m.findMatch(input);
    assertNotNull(r, "should find");

    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(1), r.start(1), "group 1 start");
    assertEquals(oracle.end(1), r.end(1), "group 1 end");
  }

  // -------------------------------------------------------------------------
  // Basic sanity
  // -------------------------------------------------------------------------

  @Test
  void simpleMatchesTrue() throws Exception {
    assertTrue(build("abc").matches("abc"));
  }

  @Test
  void simpleMatchesFalse() throws Exception {
    assertFalse(build("abc").matches("abx"));
  }

  @Test
  void simpleFindTrue() throws Exception {
    assertTrue(build("bc").find("abcd"));
  }

  @Test
  void simpleFindFrom() throws Exception {
    assertEquals(2, build("c").findFrom("abcd", 0));
  }

  @Test
  void wholeMatchGroup() throws Exception {
    MatchResult r = build("(a)(b)").match("ab");
    assertNotNull(r);
    assertEquals("ab", r.group(0));
    assertEquals("a", r.group(1));
    assertEquals("b", r.group(2));
  }

  // -------------------------------------------------------------------------
  // Multiline ((?m)^) reinject-closure coverage
  // -------------------------------------------------------------------------

  /** {@code (?m)^foo} must match at the start of the second line, not just at input start. */
  @Test
  void multilineFindMatchesAfterNewline() throws Exception {
    String pat = "(?m)^foo";
    String input = "xxx\nfoo";
    PikeVMMatcher m = build(pat);
    assertTrue(m.find(input));
    assertEquals(jdkFind(pat, input) != null, m.find(input));
  }

  /** No line start (pos 0 or after '\n') carries a valid first char: find() must fast-reject. */
  @Test
  void multilineFindRejectsWhenNoLineStartCandidate() throws Exception {
    String pat = "(?m)^[a-z]+";
    String input = "123\n456";
    PikeVMMatcher m = build(pat);
    assertFalse(m.find(input));
    assertNull(jdkFind(pat, input));
  }

  /** A line start after an embedded '\n' (not pos 0) carries a valid first char. */
  @Test
  void multilineFindMatchesLineStartAfterNewline() throws Exception {
    String pat = "(?m)^[a-z]+";
    String input = "123\nabc";
    PikeVMMatcher m = build(pat);
    assertTrue(m.find(input));
    java.util.regex.Matcher oracle = jdkFind(pat, input);
    assertNotNull(oracle);
    assertEquals(oracle.start(), m.findFrom(input, 0));
  }

  /** pos 0 itself carries a valid first char — no need to scan for '\n'. */
  @Test
  void multilineFindMatchesAtPositionZero() throws Exception {
    String pat = "(?m)^[a-z]+";
    String input = "abc123";
    PikeVMMatcher m = build(pat);
    assertTrue(m.find(input));
  }

  /** Empty input has no line-start candidate at all. */
  @Test
  void multilineFindRejectsEmptyInput() throws Exception {
    PikeVMMatcher m = build("(?m)^[a-z]+");
    assertFalse(m.find(""));
  }

  /** matches() on a multiline-eligible pattern exercises the strict matchesStep lambda. */
  @Test
  void multilineMatchesWholeInput() throws Exception {
    String pat = "(?m)^foo";
    PikeVMMatcher m = build(pat);
    assertTrue(m.matches("foo"));
    assertFalse(m.matches("xxx"));
  }

  /**
   * {@code a^b}: the {@code ^} anchor is reachable via a real consuming transition (after {@code
   * a}), not just epsilon-only from the NFA start — so the pos-0-only find()/matches() DFA model
   * cannot be sound and findDfaEligible() must decline it. The pattern can never match (no input
   * position satisfies {@code ^} after consuming a character), but PikeVMMatcher must still build
   * and answer correctly via the general thread simulation.
   */
  @Test
  void anchorReachableAfterConsumeDisqualifiesFindDfa() throws Exception {
    PikeVMMatcher m = build("a^b");
    assertFalse(m.matches("ab"));
    assertFalse(m.find("ab"));
  }

  // -------------------------------------------------------------------------
  // Single required first char (SIMD fast-reject) coverage
  // -------------------------------------------------------------------------

  @Test
  void singleFirstCharFastRejectsWhenAbsent() throws Exception {
    PikeVMMatcher m = build("cat");
    assertFalse(m.find("no feline here"));
  }

  @Test
  void singleFirstCharProceedsWhenPresent() throws Exception {
    PikeVMMatcher m = build("cat");
    assertTrue(m.find("a cat sat"));
  }

  // -------------------------------------------------------------------------
  // Boolean find() fast path for positional-anchor (non-leading) patterns
  // -------------------------------------------------------------------------

  /** {@code \bfoo\b} is not findDfaEligible (word boundary) but qualifies for useBoolFind. */
  @Test
  void wordBoundaryBoolFindLocatesMatch() throws Exception {
    PikeVMMatcher m = build("\\bfoo\\b");
    assertTrue(m.find("xx foo yy"));
  }

  @Test
  void wordBoundaryBoolFindNoMatch() throws Exception {
    PikeVMMatcher m = build("\\bfoo\\b");
    assertFalse(m.find("xxxxxxxx"));
  }

  /** matches() still runs the full thread simulation, exercising nlist anchor traversal. */
  @Test
  void wordBoundaryMatchesWholeInput() throws Exception {
    PikeVMMatcher m = build("\\bfoo\\b");
    assertTrue(m.matches("foo"));
  }

  /**
   * {@code findFrom} (position API) always runs the general thread simulation, independent of the
   * boolean-find fast path; on a word-boundary pattern it exercises the over-approximating reject
   * DFA's {@code findEnd} scan-limit computation.
   */
  @Test
  void wordBoundaryFindFromUsesRejectDfaScanLimit() throws Exception {
    PikeVMMatcher m = build("\\bfoo\\b");
    assertEquals(3, m.findFrom("xx foo yy", 0));
  }

  // -------------------------------------------------------------------------
  // Backreference fallback: neither findDfa nor useBoolFind eligible
  // -------------------------------------------------------------------------

  /**
   * {@code (a)\1} has a backreference, so it is ineligible for both the findDfa and useBoolFind
   * fast paths; find() falls back to the general thread simulation with capture recording
   * suppressed ({@code skipCaptures}).
   */
  @Test
  void backrefFindUsesSkipCapturesFallback() throws Exception {
    PikeVMMatcher m = build("(a)\\1");
    assertTrue(m.find("xaayy"));
    assertFalse(m.find("xyz"));
  }

  /** Zero-length match at the seed position exercises the pos==seed branch in findPosFrom. */
  @Test
  void findFromZeroLengthMatchAtSeedPosition() throws Exception {
    PikeVMMatcher m = build("a*");
    assertEquals(0, m.findFrom("bbb", 0));
  }

  // -------------------------------------------------------------------------
  // Greedy dotall-sink fast-forward
  // -------------------------------------------------------------------------

  /** {@code (?s)(.*)} qualifies as a dotall sink: group 1's end extends straight to regionEnd. */
  @Test
  void dotallStarIsSinkAndExtendsGroupToEnd() throws Exception {
    String pat = "(?s)(.*)";
    String input = "abc\ndef";
    PikeVMMatcher m = build(pat);
    MatchResult r = m.findMatch(input);
    assertNotNull(r);
    assertEquals(input, r.group(1));
  }

  /** Plain {@code .*} (non-dotall) is disqualified as a sink: it must stop before '\n'. */
  @Test
  void nonDotallStarIsNotSinkAcrossNewline() throws Exception {
    PikeVMMatcher m = build(".*");
    MatchResult r = m.findMatch("ab\ncd");
    assertNotNull(r);
    assertEquals("ab", r.group(0));
  }

  /**
   * {@code (?s).*$} has a trailing {@code $} anchor reachable via epsilon from the loop's exit
   * path, disqualifying the sink table (anchors are position-dependent and cannot be skipped).
   */
  @Test
  void dotallStarWithTrailingAnchorIsNotSink() throws Exception {
    PikeVMMatcher m = build("(?s).*$");
    assertTrue(m.matches("abc\ndef"));
  }

  // -------------------------------------------------------------------------
  // Misc API contract coverage
  // -------------------------------------------------------------------------

  @Test
  void embedsNameMapReturnsTrue() throws Exception {
    assertTrue(build("(a)").embedsNameMap());
  }

  /** matchBounded() with a non-zero start shifts a named-group result via shiftResult(). */
  @Test
  void matchBoundedShiftsNamedGroupResult() throws Exception {
    PikeVMMatcher m = build("(?<foo>a)b");
    m.setNameToIndex(java.util.Map.of("foo", 1));
    MatchResult r = m.matchBounded("xxab", 2, 4);
    assertNotNull(r);
    assertEquals("a", r.group("foo"));
    assertEquals(2, r.start(1));
    assertEquals(3, r.end(1));
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
