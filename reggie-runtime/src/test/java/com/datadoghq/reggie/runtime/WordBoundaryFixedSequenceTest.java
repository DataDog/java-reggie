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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bare \b/\B + literal patterns (e.g. \bfoo, foo\b, \bfoo\b) route to SPECIALIZED_FIXED_SEQUENCE
 * instead of the slower PIKEVM_CAPTURE fallback - see PatternAnalyzer.detectFixedSequence's
 * leading/trailing boundary stripping and FixedSequenceBytecodeGenerator's isBoundary check.
 */
class WordBoundaryFixedSequenceTest {

  @BeforeEach
  void clearCache() {
    RuntimeCompiler.clearCache();
  }

  @Test
  void routesToSpecializedFixedSequence() throws Exception {
    for (String regex :
        new String[] {"\\bfoo", "foo\\b", "\\bfoo\\b", "\\Bfoo", "foo\\B", "\\Bfoo\\B"}) {
      assertEquals(
          PatternAnalyzer.MatchingStrategy.SPECIALIZED_FIXED_SEQUENCE,
          StrategyCorrectnessMetaTest.routeOf(regex),
          () -> "/" + regex + "/ should route to SPECIALIZED_FIXED_SEQUENCE");
    }
  }

  @Test
  void leadingWordBoundary() {
    assertFindFamilyMatchesJdk("\\bfoo", "foo");
    assertFindFamilyMatchesJdk("\\bfoo", "xfoo");
    assertFindFamilyMatchesJdk("\\bfoo", " foo");
    assertFindFamilyMatchesJdk("\\bfoo", "foobar");
    assertFindFamilyMatchesJdk("\\bfoo", "");
    assertFindFamilyMatchesJdk("\\bfoo", "fo");
  }

  @Test
  void trailingWordBoundary() {
    assertFindFamilyMatchesJdk("foo\\b", "foo");
    assertFindFamilyMatchesJdk("foo\\b", "foox");
    assertFindFamilyMatchesJdk("foo\\b", "foo ");
    assertFindFamilyMatchesJdk("foo\\b", "xfoo");
    assertFindFamilyMatchesJdk("foo\\b", "");
  }

  @Test
  void bothWordBoundaries() {
    assertFindFamilyMatchesJdk("\\bfoo\\b", "foo");
    assertFindFamilyMatchesJdk("\\bfoo\\b", "foo bar");
    assertFindFamilyMatchesJdk("\\bfoo\\b", "xfoo");
    assertFindFamilyMatchesJdk("\\bfoo\\b", "foox");
    assertFindFamilyMatchesJdk("\\bfoo\\b", "xfoox");
    assertFindFamilyMatchesJdk("\\bfoo\\b", "");
  }

  @Test
  void nonWordBoundaryVariants() {
    assertFindFamilyMatchesJdk("\\Bfoo", "foo");
    assertFindFamilyMatchesJdk("\\Bfoo", "xfoo");
    assertFindFamilyMatchesJdk("foo\\B", "foo");
    assertFindFamilyMatchesJdk("foo\\B", "foox");
    assertFindFamilyMatchesJdk("\\Bfoo\\B", "foo");
    assertFindFamilyMatchesJdk("\\Bfoo\\B", "xfoox");
    assertFindFamilyMatchesJdk("\\Bfoo\\B", "xfoo");
    assertFindFamilyMatchesJdk("\\Bfoo\\B", "foox");
  }

  private static void assertFindFamilyMatchesJdk(String regex, String input) {
    Pattern jdk = Pattern.compile(regex);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatched = jm.find();

    ReggieMatcher m = RuntimeCompiler.compile(regex);
    MatchResult mr = m.findMatch(input);

    if (!jdkMatched) {
      assertNull(
          mr, () -> "Reggie found a match for /" + regex + "/ on \"" + input + "\", JDK did not");
      return;
    }
    assertTrue(
        mr != null && mr.start() == jm.start() && mr.end() == jm.end(),
        () ->
            "Reggie/JDK diverge for /"
                + regex
                + "/ on \""
                + input
                + "\": reggie="
                + mr
                + " jdk=["
                + jm.start()
                + ","
                + jm.end()
                + ")");

    // matches() must agree with JDK's matches() (whole-string match), independent of find().
    boolean jdkWholeMatch = jdk.matcher(input).matches();
    assertEquals(
        jdkWholeMatch,
        m.matches(input),
        () -> "matches() diverges from JDK for /" + regex + "/ on \"" + input + "\"");
  }
}
