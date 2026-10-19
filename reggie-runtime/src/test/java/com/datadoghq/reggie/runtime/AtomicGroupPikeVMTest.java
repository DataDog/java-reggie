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

import com.datadoghq.reggie.Reggie;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * PikeVM-level backtracking-prevention semantics for atomic groups and possessive quantifiers.
 *
 * <p>Atomic groups ({@code (?>X)}) and possessive quantifiers ({@code X++}, {@code X*+}, etc.)
 * prevent backtracking into the matched portion. These patterns are routed to the PikeVM engine,
 * which enforces the commit semantics via atomic-group tracking.
 */
public class AtomicGroupPikeVMTest {

  // -------------------------------------------------------------------------
  // Atomic group backtracking prevention
  // -------------------------------------------------------------------------

  @Test
  void testAtomicGroupPreventsBacktrack_starThenLiteral() {
    // (?>a*)a: atomic group greedily consumes all 'a's; cannot backtrack to let trailing 'a' match.
    assertFalse(Reggie.compile("(?>a*)a").matches("a"));
    assertFalse(Reggie.compile("(?>a*)a").matches("aa"));
    assertFalse(Reggie.compile("(?>a*)a").matches(""));
  }

  @Test
  void testAtomicGroupPreventsBacktrack_plusThenLiteral() {
    // (?>a+)a: atomic group greedily consumes all 'a's; trailing 'a' has nothing to match.
    assertFalse(Reggie.compile("(?>a+)a").matches("a"));
    assertFalse(Reggie.compile("(?>a+)a").matches("aa"));
  }

  @Test
  void testAtomicGroupAllowsMatchWhenSuffixNotConsumed() {
    // (?>a*)b: atomic group commits on 'a's; 'b' can still follow.
    assertTrue(Reggie.compile("(?>a*)b").matches("b"));
    assertTrue(Reggie.compile("(?>a*)b").matches("ab"));
    assertTrue(Reggie.compile("(?>a*)b").matches("aaab"));
  }

  @Test
  void testAtomicGroupContrastWithNonAtomic() {
    // Non-atomic (a*)a can backtrack and succeed; atomic (?>a*)a cannot.
    assertTrue(Reggie.compile("(a*)a").matches("a"));
    assertFalse(Reggie.compile("(?>a*)a").matches("a"));
  }

  // -------------------------------------------------------------------------
  // Possessive quantifier backtracking prevention
  // -------------------------------------------------------------------------

  @Test
  void testPossessivePlusPreventsBacktrack() {
    // a++a: possessive '+' grabs all 'a's; trailing 'a' cannot match.
    assertFalse(Reggie.compile("a++a").matches("aa"));
    assertFalse(Reggie.compile("a++a").matches("aaa"));
  }

  @Test
  void testPossessiveStarPreventsBacktrack() {
    // a*+a: possessive '*' grabs all 'a's; trailing 'a' cannot match.
    assertFalse(Reggie.compile("a*+a").matches("a"));
    assertFalse(Reggie.compile("a*+a").matches("aa"));
  }

  @Test
  void testPossessiveQuestionPreventsBacktrack() {
    // a?+a: possessive '?' grabs the one 'a'; trailing 'a' has nothing to match.
    assertFalse(Reggie.compile("a?+a").matches("a"));
  }

  @Test
  void testPossessiveAllowsMatchWhenSuffixNotConsumed() {
    // a*+b: possessive commits on 'a's; 'b' can still follow.
    assertTrue(Reggie.compile("a*+b").matches("b"));
    assertTrue(Reggie.compile("a*+b").matches("ab"));
    assertTrue(Reggie.compile("a*+b").matches("aaab"));
  }

  /**
   * An atomic group commits to its body's first match in priority order, not its longest one
   * ({@code (?>a|ab)c} cannot match "abc"). A possessive group quantifier also makes each iteration
   * atomic, as JDK does ({@code (?:a+){2,3}+b} cannot match "aab").
   */
  @Test
  void atomicCommitAgreesWithJdk() {
    List<String> inputs = TrailingLookaheadDifferentialTest.inputs(new char[] {'a', 'b', 'c'}, 5);
    List<String> failures = new ArrayList<>();
    for (String p :
        new String[] {
          "(?>a|ab)c",
          "((?>a|ab))c",
          "x?(?>a|ab)c",
          "(?>ab|a)c",
          "(?>a|ab)+c",
          "(?>(?:a|ab)+)c",
          "(?:a|ab)++c",
          "(?:a|ab)?+c",
          "(?:a?b?)++b",
          "(?>(?:a?b?)+)b",
          "(?>a(?>b|bc)|abc)c",
          "(?>(a|ab)(c|bcd))(d*)",
          "(?:a+){2,3}+b",
          "(?:(a){1,2}){2,3}+b",
          "(a|ab){2}+c",
          "(?>a*)(?>b|bc)c"
        }) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : inputs) {
        Matcher m = jdk.matcher(in);
        String exp = m.find() ? spans(m) : "null";
        String got = spans(r.findMatch(in));
        if (!exp.equals(got))
          failures.add("findMatch /" + p + "/ " + in + " exp " + exp + " got " + got);
        m = jdk.matcher(in);
        exp = m.matches() ? spans(m) : "null";
        got = spans(r.match(in));
        if (!exp.equals(got))
          failures.add("match /" + p + "/ " + in + " exp " + exp + " got " + got);
      }
    }
    assertEquals(List.of(), failures);
  }

  /**
   * A group repeat inside an atomic body finishes against the body's own accept, so an enclosing
   * repeat does not pin its group to an earlier atomic invocation ({@code (?:(?>(b)*c)a)+} on
   * "bcabca": JDK group 1 = [3,4)).
   */
  @Test
  void atomicBodyGroupRepeatIsNotPinnedByEnclosingRepeat() {
    List<String> failures = new ArrayList<>();
    for (String p : new String[] {"(?:(?>(b)*c)a)+"}) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : new String[] {"bcabca", "bbcabca", "bcabbca", "bcabcabbbca", "xbcabca"}) {
        Matcher m = jdk.matcher(in);
        String exp = m.find() ? spans(m) : "null";
        String got = spans(r.findMatch(in));
        if (!exp.equals(got))
          failures.add("findMatch /" + p + "/ " + in + " exp " + exp + " got " + got);
        m = jdk.matcher(in);
        exp = m.matches() ? spans(m) : "null";
        got = spans(r.match(in));
        if (!exp.equals(got))
          failures.add("match /" + p + "/ " + in + " exp " + exp + " got " + got);
      }
    }
    assertEquals(List.of(), failures);
  }

  /**
   * A nullable capture repeated inside an atomic group keeps the capture of an abandoned atomic
   * attempt in JDK ({@code (?>(a?)*)*a} on "a": group 1 = [1,1)); whatever engine runs it must
   * agree.
   */
  @Test
  void nullableCaptureInAtomicRepeatAgreesWithJdk() {
    List<String> failures = new ArrayList<>();
    for (String p : new String[] {"(?>(a?)*)*a", "(?>(a?)*)b|a"}) {
      ReggieMatcher r = Reggie.compileAllowingFallback(p);
      Pattern jdk = Pattern.compile(p);
      for (String in : new String[] {"a", "aa", "ab", "b", ""}) {
        Matcher m = jdk.matcher(in);
        String exp = m.find() ? spans(m) : "null";
        String got = spans(r.findMatch(in));
        if (!exp.equals(got))
          failures.add("findMatch /" + p + "/ " + in + " exp " + exp + " got " + got);
        m = jdk.matcher(in);
        exp = m.matches() ? spans(m) : "null";
        got = spans(r.match(in));
        if (!exp.equals(got))
          failures.add("match /" + p + "/ " + in + " exp " + exp + " got " + got);
      }
    }
    assertEquals(List.of(), failures);
  }

  private static String spans(Matcher m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append("..").append(m.end(g)).append(' ');
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    if (m == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++)
      sb.append(m.start(g)).append("..").append(m.end(g)).append(' ');
    return sb.toString();
  }
}
