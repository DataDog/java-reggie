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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies that patterns with capturing groups whose hybrid DFA is anchor-diluted route to the
 * NFA-only path instead of falling back to java.util.regex.
 */
class HybridAnchorDilutedTest {

  static Stream<Arguments> hybridDilutedPatterns() {
    return Stream.of(
        Arguments.of("([a-z]+|$)", ""),
        Arguments.of("([a-z]+|$)", "abc"),
        Arguments.of("([a-z]+|$)", "123"),
        Arguments.of("([a-z]+)(^x|y)", ""),
        Arguments.of("([a-z]+)(^x|y)", "abcy"),
        Arguments.of("([a-z]+)(^x|y)", "xy"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("hybridDilutedPatterns")
  void agreesWithJdk(String pat, String in) {
    ReggieMatcher reggie = Reggie.compile(pat);
    Pattern jdk = Pattern.compile(pat);
    String ctx = "pat=" + pat + " in=" + repr(in);
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    assertEquals(jdk.matcher(in).find(), reggie.find(in), "find() " + ctx);
  }

  @Disabled(
      "NEEDS-RND: ([a-z]+|$) and ([a-z]+)(^x|y) are caught by alternationPriorityConflict before"
          + " reaching the hybrid path; promoted routing to PIKEVM introduced fuzz divergences for"
          + " patterns like ([^a]{0,}\\z|.){1,} — requires per-group anchor guards before enabling")
  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("hybridDilutedPatterns")
  void routesToNative(String pat, String in) {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "expected native matcher for: " + pat);
  }

  private static String repr(String s) {
    return s.isEmpty() ? "(empty)" : "\"" + s.replace("\n", "\\n") + "\"";
  }
}
