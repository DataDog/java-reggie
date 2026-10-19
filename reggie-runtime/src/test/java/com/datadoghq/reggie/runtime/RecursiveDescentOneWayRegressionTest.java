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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Recursive-descent generator bugs found while designing the one-way route. */
class RecursiveDescentOneWayRegressionTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  /** A failed match leaves undersized arrays untouched; a match with them throws. */
  @Test
  void matchIntoChecksArrayLengthsOnlyOnAMatch() {
    ReggieMatcher m = RuntimeCompiler.compile("(?|(a)|b)c");
    assertFalse(m.matchInto("zz", new int[1], new int[1]));
    assertFalse(m.matchInto("ab", new int[1], new int[1]));
    assertThrows(IndexOutOfBoundsException.class, () -> m.matchInto("ac", new int[1], new int[1]));
  }

  /** The region is a substring: anchors and the end refer to it, as on the backtracker. */
  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "^(?|(a)|b);xa;1;2;true",
        "(?m)^(?|(a)|b);xa;1;2;true",
        "(?|(a)|b)c;xac;1;3;true",
        "(?|(a)|b)c;acx;0;2;true",
        "(a)(?1);aa;1;2;false",
        "(a)?(?(1)b|c);ab;1;1;false",
      })
  void matchesBoundedUsesTheRegion(
      String pattern, String input, int start, int end, boolean expected) {
    assertEquals(expected, RuntimeCompiler.compile(pattern).matchesBounded(input, start, end));
    assertEquals(
        expected,
        RuntimeCompiler.compileBackrefBacktrack(pattern, "").matchesBounded(input, start, end));
  }

  /** matchesBounded and matchBounded on a padded input agree with matches and match. */
  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "(\\()?blah(?(1)\\));(blah)",
        "(\\()?blah(?(1)\\));blah",
        "(a)?(?(1)b|c);ab",
        "(a)?(?(1)b|c);c",
        "(a+)?(?(1)b+|c+);aabb",
        "(a+)?(?(1)b+|c+);cc",
        "(?|(abc)|(xyz));xyz",
        "(?|(a)(b)(c)|(x)(y)(z));xyz",
        "(?|(a+)|(b+));bbb",
        "(?|(a)(b)(c)|(x));abc",
      })
  void boundedMethodsOnBenchmarkPatterns(String pattern, String input) {
    ReggieMatcher m = RuntimeCompiler.compile(pattern);
    String padded = "#" + input + "#";
    int end = padded.length() - 1;
    assertTrue(m.matches(input));
    assertTrue(m.matchesBounded(padded, 1, end));
    MatchResult expected = m.match(input);
    MatchResult actual = m.matchBounded(padded, 1, end);
    assertNotNull(actual);
    assertEquals(expected.groupCount(), actual.groupCount());
    for (int g = 0; g <= expected.groupCount(); g++) {
      assertEquals(expected.group(g), actual.group(g), pattern + " group " + g);
    }
    assertFalse(m.matchesBounded(padded, 0, end));
    assertNull(m.matchBounded(padded, 1, end + 1));
  }

  /** Patterns whose structural hashes collide still get their own classes, in either order. */
  @Test
  void epsilonAndNulAlternativesDoNotShareAClass() {
    for (boolean epsilonFirst : new boolean[] {true, false}) {
      RuntimeCompiler.clearCache();
      ReggieMatcher epsilon;
      ReggieMatcher nul;
      if (epsilonFirst) {
        epsilon = RuntimeCompiler.compile("(?|(a)|)b");
        nul = RuntimeCompiler.compile("(?|(a)|\\0)b");
      } else {
        nul = RuntimeCompiler.compile("(?|(a)|\\0)b");
        epsilon = RuntimeCompiler.compile("(?|(a)|)b");
      }
      assertTrue(epsilon.matches("b"));
      assertFalse(epsilon.matches("\0b"));
      assertFalse(nul.matches("b"));
      assertTrue(nul.matches("\0b"));
    }
  }

  /** No iteration cap on a one-way greedy loop: the input length bounds it. */
  @Test
  void longGreedyRunIsNotCut() {
    ReggieMatcher m = RuntimeCompiler.compile("(?|(a)|(b))x*y");
    String input = "a" + "x".repeat(10001) + "y";
    assertTrue(m.matches(input));
    MatchResult r = m.findMatch(input);
    assertNotNull(r);
    assertEquals(0, r.start());
    assertEquals(10003, r.end());
  }

  /** The find prefilter accounts for a nullable start. */
  @Test
  void findWithNullableStart() {
    assertSpan(RuntimeCompiler.compile("(?|(a)|)").findMatch(""), 0, 0);
    assertSpan(RuntimeCompiler.compile("(?|(a)|)b").findMatch("b"), 0, 1);
    assertSpan(RuntimeCompiler.compile("(?:)(a)(?1)").findMatch("xaa"), 1, 3);
  }

  /** Review round 1: constructs the generator gets wrong; the pattern runs on the backtracker. */
  @Test
  void roundOneCounterexamples() {
    assertSpan(RuntimeCompiler.compile("(?:y(?:(x?)a)*)(?(1)b|c)").match("yc"), 0, 2);
    MatchResult branchResetInLoop = RuntimeCompiler.compile("(?:(?|(a)|b))+").match("ab");
    assertNotNull(branchResetInLoop);
    assertEquals(0, branchResetInLoop.start(1));
    assertEquals(1, branchResetInLoop.end(1));
    assertNull(RuntimeCompiler.compile("(a\\1)(?(1)|)").match("a"));
    assertSpan(RuntimeCompiler.compile("(?i)(a)\\1(?(1)|)").match("aA"), 0, 2);
    assertSpan(RuntimeCompiler.compile("(?<n>a)(?&n)").match("aa"), 0, 2);
  }

  /**
   * Oversized one-way concats and alternations are split into helper methods and do not exceed the
   * method or class size limit, so they compile natively.
   */
  @Test
  void oversizedOneWayPatternStaysOnRecursiveDescent() {
    String calls = "(a|b)" + "(?1)".repeat(3000);
    ReggieMatcher m = RuntimeCompiler.compile(calls);
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("ab".repeat(1500) + "a"));
    assertFalse(m.matches("ab".repeat(1500)));

    String literals = "(?|(a)|b)" + "c".repeat(4000);
    m = RuntimeCompiler.compile(literals);
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertSpan(m.findMatch("xb" + "c".repeat(4000)), 1, 4002);

    StringBuilder alternatives = new StringBuilder("(?|");
    for (int i = 0; i < 2000; i++) {
      alternatives.append(i == 0 ? "(" : "|(").append((char) (0x100 + i)).append(')');
    }
    m = RuntimeCompiler.compile(alternatives.append(")z").toString());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    MatchResult r = m.findMatch("x" + (char) (0x100 + 1999) + "z");
    assertSpan(r, 1, 3);
    assertEquals(1, r.start(1));
    assertEquals(2, r.end(1));
    assertFalse(m.matches((char) (0x100 + 2000) + "z"));

    StringBuilder plain = new StringBuilder("(a)(?1)(?:");
    for (int i = 0; i < 2000; i++) {
      plain.append(i == 0 ? "" : "|").append((char) (0x100 + i)).append('q');
    }
    m = RuntimeCompiler.compile(plain.append(")z").toString());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("aa" + (char) (0x100 + 1500) + "qz"));
    assertFalse(m.matches("aa" + (char) (0x100 + 1500) + "z"));
  }

  /**
   * Over the JIT limit with JDK fallback allowed, a pattern the JDK cannot parse (the conditional)
   * runs on the backtracker. The char class with 500 ranges is one method over the limit.
   */
  @Test
  void overJitLimitOneWayPatternWithFallbackRunsOnBacktracker() {
    ReggieMatcher m = Reggie.compileAllowingFallback("(a)?(?(1)b|c)" + scatteredClass(500) + "d");
    assertEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    MatchResult r = m.findMatch("xab" + (char) 0x102 + "d");
    assertSpan(r, 1, 5);
    assertEquals(1, r.start(1));
    assertEquals(2, r.end(1));
    assertTrue(m.matches("c" + (char) 0x100 + "d"));
    assertFalse(m.matches("c" + (char) 0x101 + "d"));
    assertFalse(m.matches("b" + (char) 0x100 + "d"));

    m = Reggie.compileAllowingFallback("(?|(a)|b)" + "c".repeat(500));
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertSpan(m.findMatch("xb" + "c".repeat(500)), 1, 502);
  }

  /**
   * Give-back concats call the long runs of children around their backtracking points through range
   * helpers and stay on recursive descent.
   */
  @Test
  void oversizedGiveBackConcatStaysOnRecursiveDescent() {
    String d = "d".repeat(500);
    ReggieMatcher m = RuntimeCompiler.compile("(?|(a)|b)c?" + "d".repeat(4000));
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("ac" + "d".repeat(4000)));
    assertTrue(m.matches("b" + "d".repeat(4000)));
    assertFalse(m.matches("bc" + "d".repeat(3999)));

    m = Reggie.compileAllowingFallback("(a)?(?(1)b|c)" + d);
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    MatchResult r = m.findMatch("xab" + d);
    assertSpan(r, 1, 3 + d.length());
    assertEquals(1, r.start(1));
    assertEquals(2, r.end(1));
    assertTrue(m.matches("c" + d));
    assertFalse(m.matches("b" + d));

    // Before the backtracking point
    assertGiveBackOnRecursiveDescent(
        "(?|(a)|b)" + d + "c?x", "a" + d + "cx", "a" + d + "x", "b" + d);
    // Before a nested backtracking point
    assertGiveBackOnRecursiveDescent(
        "(?|(a)|b)c?" + d + "e?x", "bc" + d + "ex", "b" + d + "x", "bc" + d + "e");
    // After the last nested backtracking point
    assertGiveBackOnRecursiveDescent(
        "(?|(a)|b)c?e?" + d, "bce" + d, "b" + d, "bce" + d.substring(1));
    // Between nested backtracking points
    assertGiveBackOnRecursiveDescent(
        "(?|(a)|b)c?e?" + d + "f?x", "bce" + d + "fx", "b" + d + "x", "b" + d);
    // After a group with an optional tail
    assertGiveBackOnRecursiveDescent("(?|(a)|b)(ab?)" + d, "aab" + d, "ba" + d, "bab" + d + "d");
    // Many 60-child runs, which exceed the method size limit when inlined
    String x = "x".repeat(60);
    assertGiveBackOnRecursiveDescent(
        "(?|(a)|b)" + ("c?" + x).repeat(10) + "y",
        "a" + ("c" + x).repeat(10) + "y",
        "b" + x.repeat(10) + "y",
        "b" + x.repeat(10));
    m = RuntimeCompiler.compile("(?|(a)|b)(ab?)" + d);
    r = m.match("ba" + d);
    assertEquals(-1, r.start(1));
    assertEquals(1, r.start(2));
    assertEquals(2, r.end(2));
  }

  /** Many nested backtracking points in one concat stay on recursive descent. */
  @Test
  void manyNestedBacktrackingPointsStayOnRecursiveDescent() {
    String pattern = "(?|(a)|b)" + "c?x".repeat(100) + "(e?)y";
    ReggieMatcher m = Reggie.compileAllowingFallback(pattern);
    assertFalse(m.isJdkFallback());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    String mixed = "cx".repeat(50) + "x".repeat(50);
    assertTrue(m.matches("a" + mixed + "ey"));
    assertTrue(m.matches("b" + "x".repeat(100) + "y"));
    assertFalse(m.matches("b" + "x".repeat(99) + "y"));
    assertFalse(m.matches("b" + "cx".repeat(100) + "cy"));
    MatchResult r = m.findMatch("z" + "b" + mixed + "ey");
    assertSpan(r, 1, 3 + mixed.length() + 1);
    assertEquals(2 + mixed.length(), r.start(2));
    assertEquals(3 + mixed.length(), r.end(2));
  }

  /** A branch-reset alternative with many groups stays on recursive descent. */
  @Test
  void branchResetWithManyGroupsStaysOnRecursiveDescent() {
    ReggieMatcher m = Reggie.compileAllowingFallback("(?|" + "(a)".repeat(600) + "|b)z");
    assertFalse(m.isJdkFallback());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    MatchResult r = m.findMatch("x" + "a".repeat(600) + "z");
    assertSpan(r, 1, 602);
    assertEquals(1, r.start(1));
    assertEquals(600, r.start(600));
    assertEquals(601, r.end(600));
    r = m.findMatch("a".repeat(599) + "bz");
    assertSpan(r, 599, 601);
    assertEquals(-1, r.start(1));
    assertEquals(-1, r.start(600));
    assertFalse(m.matches("a".repeat(599) + "z"));
  }

  private static void assertGiveBackOnRecursiveDescent(
      String pattern, String match1, String match2, String nonMatch) {
    ReggieMatcher m = Reggie.compileAllowingFallback(pattern);
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m), pattern);
    assertTrue(m.matches(match1), match1);
    assertTrue(m.matches(match2), match2);
    assertFalse(m.matches(nonMatch), nonMatch);
    assertSpan(m.findMatch("x" + match1), 1, match1.length() + 1);
  }

  /** A char class of {@code n} single chars, 0x100 + 2i: n ranges. */
  private static String scatteredClass(int n) {
    StringBuilder cls = new StringBuilder("[");
    for (int i = 0; i < n; i++) {
      cls.append((char) (0x100 + 2 * i));
    }
    return cls.append(']').toString();
  }

  /**
   * Literals and small char classes are checked inline in concats and alternations: long literal
   * runs and wide single-char alternations need no method per char and stay on recursive descent,
   * with no method over the JIT limit.
   */
  @Test
  void inlineLeavesKeepLargePatternsOnRecursiveDescent() {
    ReggieMatcher m = Reggie.compileAllowingFallback("(?|(a)|b)" + "c".repeat(40000));
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("a" + "c".repeat(40000)));
    assertFalse(m.matches("a" + "c".repeat(39999)));
    assertFalse(m.matches("a" + "c".repeat(39999) + "d"));

    m = Reggie.compileAllowingFallback("(?|(a)|b)" + "[a-cx-z]".repeat(4000));
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("b" + "ax".repeat(2000)));
    assertFalse(m.matches("b" + "ax".repeat(1999) + "am"));

    m = Reggie.compileAllowingFallback("(?|(a)|b)" + "[^q]".repeat(4000));
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("b" + "z".repeat(4000)));
    assertFalse(m.matches("b" + "z".repeat(3999) + "q"));

    StringBuilder chars = new StringBuilder("(?|(a)|b)(?:");
    for (int i = 0; i < 20000; i++) {
      chars.append(i == 0 ? "" : "|").append((char) (0x1000 + i));
    }
    m = Reggie.compileAllowingFallback(chars.append(")z").toString());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("a" + (char) (0x1000 + 19999) + "z"));
    assertFalse(m.matches("a" + (char) (0x1000 + 20000) + "z"));
    MatchResult r = m.findMatch("xa" + (char) 0x1000 + "z");
    assertSpan(r, 1, 4);
    assertEquals(1, r.start(1));
  }

  /**
   * Range helpers nest, so no method of a very wide alternation, concat or branch reset is huge.
   */
  @Test
  void rangeHelperTreeKeepsWidePatternsOnRecursiveDescent() {
    StringBuilder alts = new StringBuilder("(?|(a)|b)(?:");
    for (int i = 0; i < 40000; i++) {
      alts.append(i == 0 ? "" : "|").append((char) (0x1000 + i));
    }
    ReggieMatcher m = Reggie.compileAllowingFallback(alts.append(")z").toString());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("a" + (char) (0x1000 + 39999) + "z"));
    assertTrue(m.matches("b" + (char) 0x1000 + "z"));
    assertFalse(m.matches("a" + (char) (0x1000 + 40000) + "z"));

    m = Reggie.compileAllowingFallback("(?|(a)|b)" + "c".repeat(60000));
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("a" + "c".repeat(60000)));
    assertFalse(m.matches("a" + "c".repeat(59999) + "d"));

    StringBuilder reset = new StringBuilder("(?|(a)|b)(?|");
    StringBuilder groupChars = new StringBuilder();
    for (int j = 0; j < 30; j++) {
      char c = (char) (0x200 + j);
      reset.append('(').append(c).append(')');
      groupChars.append(c);
    }
    for (int i = 0; i < 4000; i++) {
      reset
          .append("|[")
          .append((char) (0x3000 + 2 * i))
          .append((char) (0x6000 + 2 * i))
          .append(']');
    }
    m = Reggie.compileAllowingFallback(reset.append(")z").toString());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("a" + (char) (0x6000 + 2 * 3999) + "z"));
    assertFalse(m.matches("a" + (char) (0x6000 + 2 * 3999 + 1) + "z"));
    MatchResult r = m.findMatch("xb" + groupChars + "z");
    assertSpan(r, 1, 33);
    assertEquals(2, r.start(2));
    assertEquals(32, r.end(31));
  }

  /** One method per call overflows the constant pool: the class is too large, not one method. */
  @Test
  void oversizedOneWayClassRunsOnBacktracker() {
    ReggieMatcher m = RuntimeCompiler.compile("(a|b)" + "(?1)".repeat(25000));
    assertEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("ab".repeat(12500) + "a"));
    assertFalse(m.matches("ab".repeat(12500)));
  }

  /** Over 64 call targets the backtracker refuses an oversized class: strict compile refuses it. */
  @Test
  void oversizedOneWayClassTheBacktrackerRefusesIsUnsupported() {
    StringBuilder pattern = new StringBuilder("(?|(a)|b)");
    for (int i = 0; i < 70; i++) {
      pattern.append('(').append((char) (0x100 + i)).append(')');
    }
    for (int k = 0; k < 360; k++) {
      for (int i = 2; i <= 71; i++) {
        pattern.append("(?").append(i).append(')');
      }
    }
    String oversized = pattern.toString();
    assertThrows(UnsupportedPatternException.class, () -> RuntimeCompiler.compile(oversized));
  }

  /**
   * Not one-way and unparseable by the JDK: with an atomic group the pattern runs on the
   * backtracker at any size, also where generated code would exceed the JIT or 64 KB limits.
   */
  @Test
  void atomicPatternTheJdkCannotParseRunsOnTheBacktracker() {
    StringBuilder cls = new StringBuilder("[");
    for (int i = 0; i < 4000; i++) {
      cls.append((char) (0x100 + 2 * i));
    }
    String wide = cls.append(']').toString();
    for (String pattern :
        new String[] {
          "(?|(a)|b)(?>x)c?c" + wide.substring(0, 1001) + "]z", "(?|(a)|b)(?>x)c?c" + wide + "z"
        }) {
      ReggieMatcher m = Reggie.compileAllowingFallback(pattern);
      assertFalse(m.isJdkFallback());
      assertEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
      assertTrue(m.matches("axc\u0102z"));
      assertTrue(m.matches("bxcc\u0100z"));
      assertFalse(m.matches("bxc\u0101z"));
    }
  }

  /**
   * Not one-way and refused by the backtracker (an atomic group inside a lookaround, more than 64
   * unbounded loops): the recursive-descent generator would not retry the choice points, so the
   * pattern is refused, or runs on the JDK when the JDK parses it and fallback is allowed.
   */
  @Test
  void choicePointPatternTheBacktrackerRefusesIsNotGenerated() {
    String noJdk = "(?|(a)|b)(?=(?>x))(x|xy)c";
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile(noJdk));
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compileAllowingFallback(noJdk));

    String jdk = "(a|ab)\\1+c" + "(?:d*e)".repeat(65);
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile(jdk));
    RuntimeCompiler.RoutingInfo info = RuntimeCompiler.describeRouting(jdk);
    assertTrue(
        info.routing.startsWith("JAVA_FALLBACK: recursive-descent pattern with choice points"),
        info.routing);
    assertTrue(info.matcher.isJdkFallback());
    assertTrue(info.matcher.matches("ababc" + "e".repeat(65)));
  }

  /**
   * Each group with a trailing optional part is tried whole, then without it; the code for the rest
   * of the concat is generated once, not once per path, so its size is linear in the groups.
   */
  @Test
  void groupsWithTrailingOptionalPartsGenerateLinearCode() {
    ReggieMatcher m = Reggie.compileAllowingFallback("(?|(a)|b)" + "(ab?)".repeat(20) + "z");
    assertFalse(m.isJdkFallback());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches("b" + "a".repeat(20) + "z"));
    assertTrue(m.matches("a" + "ab".repeat(20) + "z"));
    assertTrue(m.matches("b" + "ab".repeat(10) + "a".repeat(10) + "z"));
    assertFalse(m.matches("b" + "a".repeat(19) + "z"));
    assertFalse(m.matches("b" + "abb".repeat(20) + "z"));
    MatchResult r = m.match("b" + "a".repeat(10) + "ab".repeat(10) + "z");
    assertNotNull(r);
    assertEquals(10, r.start(11));
    assertEquals(11, r.end(11));
    assertEquals(11, r.start(12));
    assertEquals(13, r.end(12));
  }

  /**
   * matches() and match() try a wide root alternation through range helpers, and match(),
   * matchInto() and findMatch() copy thousands of groups with a loop.
   */
  @Test
  void wideRootAlternationStaysOnRecursiveDescent() {
    StringBuilder pattern = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      pattern.append(i == 0 ? "" : "|").append((char) (0x100 + i)).append("(?|(a)|b)");
    }
    ReggieMatcher m = Reggie.compileAllowingFallback(pattern.toString());
    assertFalse(m.isJdkFallback());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    assertTrue(m.matches((char) (0x100 + 2999) + "a"));
    assertTrue(m.matches((char) 0x100 + "b"));
    assertFalse(m.matches((char) (0x100 + 3000) + "a"));
    assertFalse(m.matches((char) 0x100 + "ax"));
    MatchResult r = m.match((char) (0x100 + 1500) + "a");
    assertSpan(r, 0, 2);
    assertEquals(-1, r.start(1));
    assertEquals(1, r.start(1501));
    assertEquals(2, r.end(1501));
    r = m.match((char) (0x100 + 2999) + "b");
    assertSpan(r, 0, 2);
    assertEquals(-1, r.start(3000));
    assertNull(m.match("b"));
    int[] starts = new int[3001];
    int[] ends = new int[3001];
    assertTrue(m.matchInto((char) (0x100 + 2999) + "a", starts, ends));
    assertEquals(1, starts[3000]);
    assertEquals(2, ends[3000]);
    assertEquals(-1, starts[2999]);
    r = m.findMatch("x" + (char) (0x100 + 7) + "a");
    assertSpan(r, 1, 3);
    assertEquals(2, r.start(8));
    assertEquals(3, r.end(8));
  }

  @Test
  void scatteredFirstCharsStayOnRecursiveDescent() {
    StringBuilder pattern = new StringBuilder();
    for (int i = 0; i < 600; i++) {
      pattern.append(i == 0 ? "" : "|").append((char) (0x100 + 2 * i)).append("(?|(a)|b)");
    }
    ReggieMatcher m = Reggie.compileAllowingFallback(pattern.toString());
    assertFalse(m.isJdkFallback());
    assertNotEquals(BackrefBacktrackMatcher.class, EngineRouting.engineClass(m));
    MatchResult r = m.findMatch("x" + (char) 0x101 + "a" + (char) (0x100 + 2 * 599) + "a");
    assertSpan(r, 3, 5);
    assertEquals(4, r.start(600));
    assertEquals(5, r.end(600));
    assertNull(m.findMatch("x" + (char) 0x101 + "a" + (char) (0x100 + 2 * 600) + "a"));
  }

  private static void assertSpan(MatchResult r, int start, int end) {
    assertNotNull(r);
    assertEquals(start, r.start());
    assertEquals(end, r.end());
  }
}
