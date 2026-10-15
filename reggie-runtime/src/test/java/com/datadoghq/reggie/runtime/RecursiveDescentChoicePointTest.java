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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Patterns with subroutine calls, conditionals or branch reset retry every choice point when the
 * rest of the pattern fails: a later alternative, an earlier quantifier iteration, and a quantifier
 * inside a conditional branch. Expected values follow PCRE2 backtracking order.
 */
class RecursiveDescentChoicePointTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "(q)x(?:ab|a)b(?1);qxabq;true",
        "(q)x(?:a|ab)(?:bc|b)c(?1);qxabcq;true",
        "(q)x(?:ab|a)+b(?1);qxababq;true",
        "(q)x(?:ab?)+b(?1);qxababq;true",
        "(q)x(?:a{1,2}){2}a(?1);qxaaaq;true",
        "(q)x(?:aa|a){2}ab(?1);qxaaaabq;true",
        "(?|(a)|(ab));ab;true",
        "(?|(a)|(ab))(?1);aba;true",
        "(?|(a)|(b))(?:cd|c)d;acd;true",
        "(?|(a)|(b))(?:cd|c)+d;acdcd;true",
        "(?|(a)|(b))x(?:cd|c)+d;axcdcd;true",
        "(a)?(?(1)(?:bc|b)c|d);abc;true",
        "(a)?(?(1)(?:bc|b)+c|d);abcbc;true",
        "(a+)?(?(1)b+|c+)b;aabb;true",
        "^(a|b(?1)c)+$;abbacc;true",
        "^(a|b(?1)c)+$;abacc;false",
        "^(a(?1)?b|c)d$;acbd;true",
        "(q)x(?:ab|a)b\\1+;qxabq;true",
        "(a|ab)\\1+c;ababc;true",
        "(a|b\\1)(?1);aba;true",
        "(a|b\\1)(?1);ab;false",
        "(x{0,6000}y)(?1);xxyy;true",
        "(?<n>a|b)(?&n);ab;true",
        "(?:(a)){0}(?1);a;true",
        "(?:(a)){0}(?1);b;false",
        "^((.)(?1)\\2|.?)$;abcba;true",
      })
  void matchesRetriesChoicePoints(String pattern, String input, boolean expected) {
    assertEquals(
        expected, RuntimeCompiler.compile(pattern).matches(input), pattern + " on " + input);
  }

  @Test
  void groupsSetInsideACallRevertOnReturn() {
    MatchResult r = RuntimeCompiler.compile("(a|b)(?1)").match("ab");
    assertNotNull(r);
    assertEquals("a", r.group(1));
    r = RuntimeCompiler.compile("(a(?1)?b)").match("aabb");
    assertNotNull(r);
    assertEquals("aabb", r.group(1));
  }

  @Test
  void groupsOfACallStayTheCallers() {
    MatchResult r = RuntimeCompiler.compile("(x{0,6000}y)(?1)").match("xxyy");
    assertNotNull(r);
    assertEquals("xxy", r.group(1));
    r = RuntimeCompiler.compile("(?<n>a|b)(?&n)").match("ba");
    assertNotNull(r);
    assertEquals("b", r.group("n"));
  }

  /** A {@code \K} that runs inside a call moves the start of the match (PCRE2 10.47). */
  @Test
  void keepOutInsideACallMovesMatchStart() {
    MatchResult r = RuntimeCompiler.compile("(a\\Kb)(?1)c").findMatch("ababc");
    assertNotNull(r);
    assertEquals("3..5", r.start() + ".." + r.end());
    assertEquals("ab", r.group(1));
  }

  /**
   * A call of a target already running at the same position in the call chain fails that path;
   * PCRE2 reports an error ("nested recursion at the same subject position") instead. The top level
   * is not a call: in {@code (?:(?R)a|b)} the first {@code (?R)} at 0 runs, its own {@code (?R)}
   * fails, and it returns after {@code b}.
   */
  @Test
  void callRunningAtTheSamePositionFailsThePath() {
    ReggieMatcher m = RuntimeCompiler.compile("^(?:a|(?R)b)$");
    assertTimeoutPreemptively(
        Duration.ofSeconds(10), () -> assertFalse(m.matches("ab"), "left recursion"));
    MatchResult r = RuntimeCompiler.compile("(?:(?R)a|b)").findMatch("baa");
    assertNotNull(r);
    assertEquals("0..2", r.start() + ".." + r.end());
    assertFalse(
        RuntimeCompiler.compile("(?:(?1)!|(?2)$|#((?2)x|y)((?1)z|w))").matches("wxz"),
        "mutual recursion at one position");
  }

  /** Calls, conditions and branch-reset arms do not count as groups. */
  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {"(a)(?1);aa;1", "(?<n>a)(?&n);aa;1", "(a)(?(1)b|c);ab;1", "(?|(a)|(b))(c);bc;2"})
  void groupCount(String pattern, String input, int groups) {
    MatchResult r = RuntimeCompiler.compile(pattern).match(input);
    assertNotNull(r, pattern);
    assertEquals(groups, r.groupCount(), pattern);
  }

  /** A counted loop in a called group, and a loop repeating the counted loop (PCRE2 10.47). */
  @Test
  void countedLoopInsideACall() {
    String input = "x".repeat(4000) + "y" + "x".repeat(2500) + "y";
    MatchResult r = RuntimeCompiler.compile("(x{0,6000}y)(?1)").match(input);
    assertNotNull(r);
    assertEquals(4001, r.end(1));
    assertTrue(RuntimeCompiler.compile("(?:x{0,6000}y)+").matches(input));
  }

  /** RECURSIVE_DESCENT-strategy patterns java.util.regex parses: lazy and quantified backrefs. */
  @ParameterizedTest
  @CsvSource({
    "(a|ab)\\1+c",
    "(a+?)\\1+b",
    "(ab|a)\\1+?b",
    "x(a+?)\\1+",
    "(a|ab)\\1*b",
    "(a?)\\1+b"
  })
  void equalsJdk(String pattern) {
    String[] inputs = {
      "", "a", "ab", "aab", "aaab", "aaaab", "ababc", "ababb", "abababb", "xaa", "xaaaa", "aabab",
      "xab",
    };
    ReggieMatcher reggie = RuntimeCompiler.compile(pattern);
    Pattern jdk = Pattern.compile(pattern);
    for (String input : inputs) {
      String row = pattern + " on " + input;
      Matcher j = jdk.matcher(input);
      assertEquals(j.matches(), reggie.matches(input), "matches " + row);
      j = jdk.matcher(input);
      MatchResult r = reggie.findMatch(input);
      if (j.find()) {
        assertNotNull(r, row);
        assertEquals(j.start() + ".." + j.end(), r.start() + ".." + r.end(), row);
        for (int g = 1; g <= j.groupCount(); g++) {
          assertEquals(j.start(g) + ".." + j.end(g), r.start(g) + ".." + r.end(g), g + " " + row);
        }
      } else {
        assertEquals(null, r, row);
      }
    }
  }

  /** Call results are reused per (target, position): no exponential retry of the call body. */
  @Test
  void ambiguousRecursionFailsInPolynomialTime() {
    ReggieMatcher m = RuntimeCompiler.compile("^(a(?1)?|aa(?1)?)$");
    String input = "a".repeat(35) + "b";
    assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertFalse(m.matches(input)));
  }

  /**
   * A RECURSIVE_DESCENT-strategy pattern with a choice point the generator would have to retry runs
   * on the backtracker; a one-way pattern stays on the generated matcher.
   */
  @Test
  void routing() {
    for (String pattern :
        new String[] {"(a|ab)(?1)", "(a)?(?(1)(?:bc|b)c|d)", "(?|(a)|(ab))c", "b((?1))*"}) {
      assertEquals(
          BackrefBacktrackMatcher.class,
          EngineRouting.engineClass(RuntimeCompiler.compile(pattern)),
          pattern);
    }
    for (String pattern : new String[] {"(a|b)(?1)", "(a)?(?(1)b|c)", "(?|(a)|(b))c"}) {
      assertFalse(
          EngineRouting.unwrap(RuntimeCompiler.compile(pattern)) instanceof BackrefBacktrackMatcher,
          pattern);
    }
    // Round 13: the one-way check rejects a self-reaching call before following it
    assertNotNull(RuntimeCompiler.compile("((?1))?+c"));
    // Round 11: the conditional turns the merged call context into a constraint.
    ReggieMatcher conditional = RuntimeCompiler.compile("(?1)c((?:(a)|b?)(?(2)y))");
    assertEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(conditional));
    MatchResult found = conditional.findMatch("ca");
    assertNotNull(found);
    assertEquals(0, found.start());
    assertEquals(1, found.end());
    // Group 1 is never built ((?:...){0}): the generated matcher runs the call.
    ReggieMatcher unbuilt = RuntimeCompiler.compile("(?:(a)){0}(?1)");
    assertFalse(EngineRouting.unwrap(unbuilt) instanceof BackrefBacktrackMatcher);
    assertTrue(unbuilt.matches("a"));
  }

  /**
   * {@code (?|(X)|(Y))} with one group per arm numbers both groups 1, as {@code (X|Y)} does; {@code
   * (a)?(?(1)X|Y)} tries {@code aX} before {@code Y}, as {@code (?:(a)X|Y)} does.
   */
  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "(?|(a)|(ab))(?:bc|b)c;(a|ab)(?:bc|b)c",
        "(?|(ab)|(a))b+c?;(ab|a)b+c?",
        "x(?|(a+)|(b))+a;x(a+|b)+a",
        "(a)?(?(1)(?:bc|b)+|c+)c;(?:(a)(?:bc|b)+|c+)c",
        "(a)?(?(1)b*|bc)c;(?:(a)b*|bc)c",
      })
  void equalsJdkRewrite(String pattern, String jdkPattern) {
    String[] inputs = {
      "", "a", "ab", "abc", "abbc", "abcbc", "abcc", "bc", "bcc", "cc", "c", "xaa", "xaba", "xabab",
      "xba", "abb", "aabcc",
    };
    ReggieMatcher reggie = RuntimeCompiler.compile(pattern);
    Pattern jdk = Pattern.compile(jdkPattern);
    for (String input : inputs) {
      String row = pattern + " on " + input;
      Matcher j = jdk.matcher(input);
      assertEquals(j.matches(), reggie.matches(input), "matches " + row);
      j = jdk.matcher(input);
      MatchResult r = reggie.findMatch(input);
      if (j.find()) {
        assertNotNull(r, row);
        assertEquals(j.start() + ".." + j.end(), r.start() + ".." + r.end(), row);
        assertEquals(String.valueOf(j.group(1)), String.valueOf(r.group(1)), "group 1 " + row);
      } else {
        assertEquals(null, r, row);
      }
    }
  }
}
