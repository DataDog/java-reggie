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
 * Regression tests for patterns where {@code dfa.isAnchorConditionDiluted()} fires in the capturing
 * TDFA path. Dilution occurs when both alternation branches share the same leading character but
 * one branch has a start-anchor guard (e.g. {@code ^x|x(y)}: both start with {@code x}, but only
 * {@code ^x} requires position 0). Before the fix these routed to the JDK fallback; after the fix
 * they route to PIKEVM_CAPTURE, which evaluates {@code ^}/\{@code \A} correctly against the
 * search-region origin since commit 0acfc66.
 *
 * <p>Patterns whose branches start with different characters do not produce dilution in the DFA
 * (each branch occupies a distinct DFA state), and are unaffected by this fix.
 *
 * <p>Patterns with optional quantifiers ({@code ?}, {@code *}, {@code {0,n}}) retain the JDK
 * fallback because PikeVM greedy semantics diverge from JDK for those shapes.
 */
public class AnchorDilutedNativeTest {

  /**
   * Capturing alternation patterns where dilution fires in the capturing TDFA path and PikeVM
   * handles correctly: both branches share the same leading character, no optional quantifiers.
   */
  @ParameterizedTest
  @ValueSource(strings = {"^x|x(y)", "\\Ax|x(y)", "^1|1(-.)", "^a|a(b)", "x(y)|^x"})
  void capturingAnchorDiluted_usesNativePath(String pat) throws Exception {
    assertFalse(
        Reggie.compile(pat) instanceof JavaRegexFallbackMatcher,
        "Expected native matcher for: " + pat);
  }

  static Stream<Arguments> capturingAnchorDiluted() {
    return Stream.of(
        // Anchor branch first, both branches share leading character 'x'
        Arguments.of("^x|x(y)", "x"),
        Arguments.of("^x|x(y)", "xy"),
        Arguments.of("^x|x(y)", "axy"),
        // \A anchor variant
        Arguments.of("\\Ax|x(y)", "x"),
        Arguments.of("\\Ax|x(y)", "xy"),
        Arguments.of("\\Ax|x(y)", "axy"),
        // Both branches share '1'
        Arguments.of("^1|1(-.)", "1"),
        Arguments.of("^1|1(-.)", "1-a"),
        Arguments.of("^1|1(-.)", "x1-b"),
        // Shared 'a', capturing group in anchor branch
        Arguments.of("^a|a(b)", "a"),
        Arguments.of("^a|a(b)", "ab"),
        Arguments.of("^a|a(b)", "xab"),
        // Capturing branch first
        Arguments.of("x(y)|^x", "x"),
        Arguments.of("x(y)|^x", "xy"),
        Arguments.of("x(y)|^x", "axy"));
  }

  @ParameterizedTest(name = "[{index}] pat={0} in={1}")
  @MethodSource("capturingAnchorDiluted")
  void capturingAnchorDiluted_agreesWithJdk(String pat, String in) throws Exception {
    Pattern jdk = Pattern.compile(pat);
    ReggieMatcher reggie = Reggie.compile(pat);
    String ctx = "pat=" + pat + " in=" + in;
    assertEquals(jdk.matcher(in).matches(), reggie.matches(in), "matches() " + ctx);
    Matcher jdkM = jdk.matcher(in);
    boolean jdkFind = jdkM.find();
    var reggieResult = reggie.findMatch(in);
    assertEquals(jdkFind, reggieResult != null, "find() " + ctx);
    if (jdkFind) {
      for (int g = 0; g <= jdkM.groupCount(); g++) {
        assertEquals(jdkM.start(g), reggieResult.start(g), "start(g=" + g + ") " + ctx);
        assertEquals(jdkM.end(g), reggieResult.end(g), "end(g=" + g + ") " + ctx);
      }
    }
  }
}
