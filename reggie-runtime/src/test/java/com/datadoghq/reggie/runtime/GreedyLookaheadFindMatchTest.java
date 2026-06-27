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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Regression tests for {@code findMatch()} / {@code findBoundsFrom()} on {@code
 * DFA_UNROLLED_WITH_ASSERTIONS} patterns with a trailing lookahead.
 *
 * <p>Root cause: {@code findLongestMatchEnd()} in {@code DFAUnrolledBytecodeGenerator} must
 * evaluate the lookahead at each accepting position to decide whether to record {@code
 * longestMatchEnd}, but must NOT abort to dead-end when the lookahead fails — it must continue to
 * consume more characters so a later position can satisfy the assertion.
 *
 * <p>Example failure before fix: {@code a+(?=a)} on {@code "aa"} — {@code find()} correctly returns
 * {@code true}, but {@code findMatch().group(0)} returned {@code null} because the deferred
 * lookahead check only fired after all transitions were exhausted, missing the valid position at
 * pos=1 where the lookahead holds.
 */
class GreedyLookaheadFindMatchTest {

  @ParameterizedTest(name = "findMatch agrees with JDK: pat={0} in={1}")
  @CsvSource({
    // Positive lookahead: earlier accepting position satisfies lookahead
    "a+(?=a), aa",
    // Positive lookahead: only the longer prefix satisfies lookahead
    "a+(?=b), aab",
    "a+(?=b), ab",
    // Negative lookahead: all consumed positions should fail
    "a+(?!b), aac",
  })
  void findMatchAgreesWithJdk(String pat, String in) {
    Pattern jdk = Pattern.compile(pat);
    Matcher jm = jdk.matcher(in);
    boolean jdkFind = jm.find();
    String jdkGroup = jdkFind ? jm.group(0) : null;

    var rm = Reggie.compile(pat);
    boolean rFind = rm.find(in);
    MatchResult rMatch = rm.findMatch(in);
    String rGroup = rMatch != null ? rMatch.group(0) : null;

    assertEquals(jdkFind, rFind, "find() must agree: pat=" + pat + " in=" + in);
    assertEquals(jdkGroup, rGroup, "findMatch().group(0) must agree: pat=" + pat + " in=" + in);
  }
}
