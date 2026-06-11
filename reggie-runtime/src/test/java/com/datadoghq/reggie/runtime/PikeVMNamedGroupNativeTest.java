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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests that capture-ambiguous patterns with named groups and/or anchors are routed to
 * PIKEVM_CAPTURE (not JDK fallback) and produce correct named-group spans.
 *
 * <p>Before the fix, {@code PatternAnalyzer} routed the {@code captureAmbiguous+hasNamedGroups} and
 * {@code captureAmbiguous+hasAnchorInNfa} sub-cases to OPTIMIZED_NFA with {@code
 * captureAmbiguous=true}, which triggered {@link JavaRegexFallbackMatcher}. After the fix both
 * sub-cases route to PIKEVM_CAPTURE, and {@code RuntimeCompiler} wraps the result in {@link
 * NameEnrichingMatcher} when named groups are present.
 *
 * <p>Note: patterns with overlapping first-alternative (e.g. {@code a|ab}) where PikeVM currently
 * picks longest-match instead of first-match are tested only for correct routing, not for span
 * agreement — those are tracked as a separate pre-existing semantics issue (A4/A5).
 */
public class PikeVMNamedGroupNativeTest {

  // ---------------------------------------------------------------------------
  // Named-group patterns — routing-only (capture-ambiguous due to overlapping
  // alternatives; PikeVM span semantics diverge from JDK on these, but routing
  // is the focus here — span agreement is in A4/A5 scope)
  // ---------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(?<x>a|ab)\\w",
        "(?<first>a)(?<second>b|bc)",
        "(?<word>\\w+)",
      })
  void captureAmbiguousNamed_usesNativePath(String pat) throws Exception {
    ReggieMatcher m = Reggie.compile(pat);
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat + " but got: " + m.getClass().getSimpleName());
  }

  // ---------------------------------------------------------------------------
  // Named-group access via group(String name) for non-overlapping patterns
  // (these produce correct spans in PikeVM)
  // ---------------------------------------------------------------------------

  static Stream<Arguments> namedGroupByName() {
    return Stream.of(
        // Simple named group, not capture-ambiguous — exercises the NameEnrichingMatcher path
        Arguments.of("(?<word>\\w+)", "hello", "word", "hello"),
        Arguments.of("(?<word>\\w+)", "x", "word", "x"),
        // Two named groups, no ambiguity
        Arguments.of("(?<first>\\w+)-(?<second>\\w+)", "foo-bar", "first", "foo"),
        Arguments.of("(?<first>\\w+)-(?<second>\\w+)", "foo-bar", "second", "bar"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1} name={2}")
  @MethodSource("namedGroupByName")
  void namedGroupByName_matchAgreesWithJdk(String pat, String in, String name, String expected)
      throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);

    assertFalse(reggie instanceof JavaRegexFallbackMatcher, "Expected native matcher for: " + pat);

    // match()
    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    if (jdkM) {
      assertNotNull(rm, "match() should not be null for: " + pat + " in=" + in);
      assertEquals(jm.group(name), rm.group(name), "match() group(" + name + ") pat=" + pat);
    } else {
      assertNull(rm, "match() should be null for: " + pat + " in=" + in);
    }

    // findMatch()
    Matcher jmf = jdk.matcher(in);
    boolean jdkF = jmf.find();
    MatchResult rfm = reggie.findMatch(in);
    if (jdkF) {
      assertNotNull(rfm, "findMatch() should not be null for: " + pat + " in=" + in);
      assertEquals(jmf.group(name), rfm.group(name), "findMatch() group(" + name + ") pat=" + pat);
    } else {
      assertNull(rfm, "findMatch() should be null for: " + pat + " in=" + in);
    }
  }

  // ---------------------------------------------------------------------------
  // Capture-ambiguous patterns with anchors — routing and span agreement
  // (patterns chosen so PikeVM greedy-first semantics agree with JDK)
  // ---------------------------------------------------------------------------

  static Stream<Arguments> captureAmbiguousWithAnchor() {
    return Stream.of(
        // Named group + end-anchor: both sub-cases exercised at once
        Arguments.of("(?<g>a|ab)\\w$", "abz"),
        Arguments.of("(?<g>a|ab)\\w$", "az"),
        // End-anchor in a capture-ambiguous pattern (no named groups)
        Arguments.of("(a|ab)\\w$", "abz"),
        Arguments.of("(a|ab)\\w$", "az"),
        // Start-anchor: ^(a|ab)\w — overlapping alt, span diverges from JDK on "abz"
        // tested for routing only (not span)
        Arguments.of("^(a|ab)\\w", "az"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("captureAmbiguousWithAnchor")
  void captureAmbiguousWithAnchor_usesNativePath(String pat, String in) throws Exception {
    ReggieMatcher reggie = Reggie.compile(pat);
    assertFalse(
        reggie instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat + " but got: " + reggie.getClass().getSimpleName());
  }

  // ---------------------------------------------------------------------------
  // Span agreement for anchor patterns where PikeVM agrees with JDK
  // ---------------------------------------------------------------------------

  static Stream<Arguments> captureAmbiguousWithAnchorSpans() {
    return Stream.of(
        // End-anchor forces unique match so first-alt semantics don't diverge
        Arguments.of("(a|ab)\\w$", "abz"),
        Arguments.of("(a|ab)\\w$", "az"),
        // Named group + end-anchor
        Arguments.of("(?<g>a|ab)\\w$", "az"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("captureAmbiguousWithAnchorSpans")
  void captureAmbiguousWithAnchor_spansAgreeWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;

    assertFalse(reggie instanceof JavaRegexFallbackMatcher, "Expected native matcher for: " + pat);

    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);

    Matcher jm = jdk.matcher(in);
    boolean jdkM = jm.matches();
    MatchResult rm = reggie.match(in);
    assertEquals(jdkM, rm != null, "match() null check " + ctx);
    if (jdkM) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rm.start(g), "match() g" + g + " start " + ctx);
        assertEquals(jm.end(g), rm.end(g), "match() g" + g + " end " + ctx);
      }
    }

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

  // ---------------------------------------------------------------------------
  // Routing assertions: all A7 patterns must not fall back to JDK
  // ---------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "(?<x>a|ab)\\w",
        "(?<first>a)(?<second>b|bc)",
        "(?<word>\\w+)",
        "(?<first>\\w+)-(?<second>\\w+)",
        "^(a|ab)\\w",
        "(a|ab)\\w$",
        "(?<g>a|ab)\\w$",
      })
  void captureAmbiguousA7_usesNativePath(String pat) throws Exception {
    ReggieMatcher m = Reggie.compile(pat);
    assertFalse(
        m instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat + " but got: " + m.getClass().getSimpleName());
  }
}
