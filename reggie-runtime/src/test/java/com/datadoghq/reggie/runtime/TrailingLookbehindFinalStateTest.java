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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Regression tests for trailing lookbehind on the final accepting state in {@code
 * DFA_SWITCH_WITH_ASSERTIONS} patterns.
 *
 * <p>Root cause: in {@code DFASwitchBytecodeGenerator}, the final accepting state is reached as the
 * TARGET of the last character transition — its per-state case code in {@code
 * generateStateCaseCode} is never run for the acceptance position. The post-loop {@code
 * generateAcceptCheckWithAssertions} and the inner-loop inline acceptance check must therefore
 * evaluate ALL assertions (lookbehind and lookahead), not just lookahead.
 *
 * <p>Before the fix, lookbehind was omitted from both acceptance-check paths on the premise that it
 * was "already checked as a transition guard" — but for the FINAL state that reasoning is wrong.
 */
class TrailingLookbehindFinalStateTest {

  // 51-char literal prefix → 52 DFA states → DFA_SWITCH_WITH_ASSERTIONS (threshold: 50 states)
  private static final String PREFIX52 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXY";

  static Stream<Arguments> switchSizedMatchesCases() {
    return Stream.of(
        // Final char is 'Y'; (?<=Y) must pass → match
        Arguments.of(PREFIX52 + "(?<=Y)", PREFIX52, true),
        // Final char is 'Y'; (?<=X) must fail → no match
        Arguments.of(PREFIX52 + "(?<=X)", PREFIX52, false),
        // Final char is 'Y'; (?<!Y) must fail → no match
        Arguments.of(PREFIX52 + "(?<!Y)", PREFIX52, false),
        // Final char is 'Y'; (?<!X) must pass → match
        Arguments.of(PREFIX52 + "(?<!X)", PREFIX52, true));
  }

  @ParameterizedTest(name = "DFA_SWITCH matches: expected={2}")
  @MethodSource("switchSizedMatchesCases")
  void switchSizedMatchesAgreesWithJdk(String pat, String in, boolean expected) {
    assertEquals(expected, Pattern.compile(pat).matcher(in).matches(), "JDK sanity");
    assertEquals(expected, Reggie.compile(pat).matches(in), "matches() pat=" + pat);
  }

  @ParameterizedTest(name = "DFA_SWITCH find: expected={2}")
  @MethodSource("switchSizedMatchesCases")
  void switchSizedFindAgreesWithJdk(String pat, String in, boolean expected) {
    assertEquals(expected, Pattern.compile(pat).matcher(in).find(), "JDK sanity");
    assertEquals(expected, Reggie.compile(pat).find(in), "find() pat=" + pat);
  }

  @ParameterizedTest(name = "DFA_UNROLLED trailing lookbehind: pat={0} in={1}")
  @CsvSource({
    "a+b(?<!a), ab",
    "ab(?<=b), ab",
    "ab(?<=a), ab",
    "a+b(?<!b), ab",
  })
  void unrolledMatchesAgreesWithJdk(String pat, String in) {
    boolean expected = Pattern.compile(pat).matcher(in).matches();
    assertEquals(expected, Reggie.compile(pat).matches(in), "matches() pat=" + pat + " in=" + in);
  }
}
