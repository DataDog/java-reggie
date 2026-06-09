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

import com.datadoghq.reggie.Reggie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression tests for the group-start recording bug in OPTIMIZED_NFA_WITH_LOOKAROUND.
 *
 * <p>Root cause: the epsilon closure called after pos++ writes posVar (post-advance) for enterGroup
 * states reached via quantifier loop-back. For the last iteration this records posVar=len (end of
 * string), overwriting the correct last-iteration start. Fix: pass usePosixLastMatch=true for
 * OPTIMIZED_NFA_WITH_LOOKAROUND patterns with groups in repeating quantifiers, enabling
 * per-configuration group tracking.
 *
 * <p>All patterns here route to OPTIMIZED_NFA_WITH_LOOKAROUND (verified via debugPattern). No JDK
 * fallback is triggered by FallbackPatternDetector for any of them.
 */
public class NfaLookaroundGroupSpanTest {

  static Stream<Arguments> groupInQuantifier() {
    return Stream.of(
        // Negative lookahead (complex → OPTIMIZED_NFA_WITH_LOOKAROUND), group in +
        Arguments.of("(?!.*[A-Z])(a)+", "aaa"),
        Arguments.of("(?!.*[A-Z])(a)+", "bbb"), // no match
        Arguments.of("(?!.*[A-Z])(a)+", "a"), // single iteration
        Arguments.of("(?!.*[A-Z])(\\w)+", "hello"),
        Arguments.of("(?!.*[A-Z])(\\w)+", "Hello"), // no match (has uppercase)
        // Multiple groups with negative lookahead
        Arguments.of("(?!.*[A-Z])([a-z])+([0-9])+", "abc123"),
        Arguments.of("(?!.*[A-Z])([a-z])+([0-9])+", "abc"), // no match (no digit group)
        // Zero-or-more quantifier — same epsilon-closure loop-back path
        Arguments.of("(?!.*[A-Z])(a)*", "aaa"),
        Arguments.of("(?!.*[A-Z])(a)*", "") // zero iterations
        );
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupInQuantifier")
  void groupSpan_agreesWithJdk_match(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;

    Matcher jm = jdk.matcher(in);
    boolean jdkMatch = jm.matches();
    MatchResult rm = reg.match(in);

    assertEquals(jdkMatch, rm != null, "match() null check " + ctx);
    if (jdkMatch) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rm.start(g), "match() g" + g + " start " + ctx);
        assertEquals(jm.end(g), rm.end(g), "match() g" + g + " end " + ctx);
      }
    }
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("groupInQuantifier")
  void groupSpan_agreesWithJdk_findMatch(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reg = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;

    Matcher jm = jdk.matcher(in);
    boolean jdkFound = jm.find();
    MatchResult rfm = reg.findMatch(in);

    assertEquals(jdkFound, rfm != null, "findMatch() null check " + ctx);
    if (jdkFound) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(jm.start(g), rfm.start(g), "findMatch() g" + g + " start " + ctx);
        assertEquals(jm.end(g), rfm.end(g), "findMatch() g" + g + " end " + ctx);
      }
    }
  }
}
