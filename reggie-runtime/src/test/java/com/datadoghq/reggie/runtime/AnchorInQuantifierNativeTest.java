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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Route-or-keep investigation for B2/B3/B4 anchor-in-quantifier predicates.
 *
 * <p>B2 ({@code hasAnchorInQuantifierInCapturingGroup}): KEEP-PERMANENT — PikeVM mis-positions
 * zero-width matches: {@code (${0,3})} on {@code "abc"} lands at [3,3] instead of JDK's [0,0], and
 * {@code (^{0,2}ab)} on {@code "xab"} returns false instead of true. Root cause: PikeVM's
 * leftmost-longest NFA traversal collapses the zero-width anchor to end-of-input rather than the
 * first viable zero-width position when the anchor is optional inside a capturing group.
 *
 * <p>B3 ({@code hasAnchorInQuantifier}): KEEP-PERMANENT (conservative) — the two tested patterns
 * ({@code (\b)+}, {@code (?:\Z)+}) pass under PikeVM for all sample inputs. However, the predicate
 * covers exotic combinations (e.g. {@code \A{2}}, {@code (?:^){3}}) not yet tested. Retain the
 * guard pending a broader fuzz sweep; predicate can be removed once confirmed.
 *
 * <p>B4 ({@code hasEndAnchorBeforeNonNewlineConsumer}): KEEP-PERMANENT (conservative) — both tested
 * patterns ({@code \Z[^c]}, {@code $[^\n]}) pass under PikeVM (unconditionally false, as in JDK).
 * Retain as a conservative guard pending fuzz confirmation.
 *
 * <p>The {@code _currentlyFallsBackToJdk} tests assert that these patterns still route to JDK
 * fallback (i.e., {@code assertFalse(... instanceof JavaRegexFallbackMatcher)} fails). They will
 * become green only if the corresponding predicate in {@code FallbackPatternDetector} is removed
 * after a correctness proof.
 */
public class AnchorInQuantifierNativeTest {

  // ---------------------------------------------------------------------------
  // B2: anchor inside a quantifier inside a capturing group
  // e.g. (${0,3}), (^{0,2}ab)
  // ---------------------------------------------------------------------------

  static Stream<Arguments> b2Patterns() {
    return Stream.of(
        // Dollar anchor quantified inside a capturing group; JDK treats $ as zero-width so
        // (${0,3}) always matches the empty string at the end of input.
        Arguments.of("(${0,3})", ""),
        Arguments.of("(${0,3})", "a"),
        Arguments.of("(${0,3})", "abc"),
        // Start anchor quantified inside a capturing group; the group span covers the
        // non-anchor part that follows.
        Arguments.of("(^{0,2}ab)", "ab"),
        Arguments.of("(^{0,2}ab)", "abc"),
        Arguments.of("(^{0,2}ab)", "xab"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b2Patterns")
  void b2_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    // Cross-check findMatch() group spans against JDK.
    Matcher jmf = jdk.matcher(in);
    boolean jdkF = jmf.find();
    MatchResult rfm = reggie.findMatch(in);
    assertEquals(jdkF, rfm != null, "findMatch() null check " + ctx);
    if (jdkF) {
      for (int g = 0; g <= jmf.groupCount(); g++) {
        assertEquals(jmf.start(g), rfm.start(g), "findMatch() g" + g + " start " + ctx);
        assertEquals(jmf.end(g), rfm.end(g), "findMatch() g" + g + " end " + ctx);
      }
    }
  }

  /** Verifies that B2 patterns currently fall back to JDK (predicates are still active). */
  @ParameterizedTest
  @ValueSource(strings = {"(${0,3})", "(^{0,2}ab)"})
  void b2_currentlyFallsBackToJdk(String pat) throws Exception {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "B2 ROUTE decision: expected native matcher for: " + pat);
  }

  // ---------------------------------------------------------------------------
  // B3: anchor inside any quantifier (not necessarily in a capturing group)
  // e.g. (\b)+, (?:\Z)+
  // ---------------------------------------------------------------------------

  static Stream<Arguments> b3Patterns() {
    return Stream.of(
        // Word-boundary anchor under + quantifier; zero-width epsilon cycle in NFA.
        Arguments.of("(\\b)+", "a"),
        Arguments.of("(\\b)+", "abc"),
        Arguments.of("(\\b)+", "a b"),
        // Non-capturing group with \Z under + quantifier.
        Arguments.of("(?:\\Z)+", ""),
        Arguments.of("(?:\\Z)+", "a"),
        Arguments.of("(?:\\Z)+", "abc"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b3Patterns")
  void b3_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  /** Verifies that B3 patterns currently fall back to JDK (predicates are still active). */
  @ParameterizedTest
  @ValueSource(strings = {"(\\b)+", "(?:\\Z)+"})
  void b3_currentlyFallsBackToJdk(String pat) throws Exception {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "B3 ROUTE decision: expected native matcher for: " + pat);
  }

  // ---------------------------------------------------------------------------
  // B4: end anchor ($, \Z) immediately before a non-newline consumer
  // e.g. \Z[^c], $[^\n]
  // Both patterns always return false in JDK (the end anchor precludes any
  // further character consumption that is not the terminal \n).
  // ---------------------------------------------------------------------------

  static Stream<Arguments> b4Patterns() {
    return Stream.of(
        Arguments.of("\\Z[^c]", ""),
        Arguments.of("\\Z[^c]", "a"),
        Arguments.of("\\Z[^c]", "abc"),
        // $[^\n] — in Java regex, $ followed by a non-\n consumer is always false in
        // non-MULTILINE mode, so these inputs all return false for both find() and matches().
        Arguments.of("$[^\\n]", ""),
        Arguments.of("$[^\\n]", "a"),
        Arguments.of("$[^\\n]", "abc"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("b4Patterns")
  void b4_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  /** Verifies that B4 patterns currently fall back to JDK (predicates are still active). */
  @ParameterizedTest
  @ValueSource(strings = {"\\Z[^c]", "$[^\\n]"})
  void b4_currentlyFallsBackToJdk(String pat) throws Exception {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "B4 ROUTE decision: expected native matcher for: " + pat);
  }

  private static String repr(String s) {
    if (s.isEmpty()) return "(empty)";
    return "\"" + s.replace("\n", "\\n") + "\"";
  }
}
