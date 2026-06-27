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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.UnsupportedPatternException;
import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

public class PikeVmCaptureRegressionTest {

  private static void assertRoute(String pattern, PatternAnalyzer.MatchingStrategy expected)
      throws Exception {
    assertEquals(
        expected,
        StrategyCorrectnessMetaTest.routeOf(pattern),
        "routing changed for /" + pattern + "/ — fix would not be exercised");
  }

  private static void assertAgrees(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    boolean jdkMatches = jdk.matcher(input).matches();
    assertEquals(
        jdkMatches,
        reggie.matches(input),
        "matches() mismatch for /" + pattern + "/ on \"" + input + "\"");
    Matcher jm = jdk.matcher(input);
    boolean jdkFind = jm.find();
    assertEquals(
        jdkFind, reggie.find(input), "find() mismatch for /" + pattern + "/ on \"" + input + "\"");
    MatchResult r = reggie.findMatch(input);
    if (jdkFind) {
      assertEquals(
          List.of(jm.start(), jm.end()),
          r == null ? null : List.of(r.start(), r.end()),
          "findMatch span mismatch for /" + pattern + "/ on \"" + input + "\"");
    } else {
      assertEquals(null, r, "findMatch should be null for /" + pattern + "/ on \"" + input + "\"");
    }
  }

  private static void assertGroupsAgree(String pattern, String input) {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher reggie = Reggie.compile(pattern);
    Matcher jm = jdk.matcher(input);
    boolean jdkMatches = jm.matches();
    MatchResult rm = reggie.match(input);
    assertEquals(
        jdkMatches, rm != null, "match() boolean for /" + pattern + "/ on \"" + input + "\"");
    if (jdkMatches) {
      for (int g = 0; g <= jm.groupCount(); g++) {
        assertEquals(
            List.of(jm.start(g), jm.end(g)),
            List.of(rm.start(g), rm.end(g)),
            "group " + g + " span for /" + pattern + "/ on \"" + input + "\"");
      }
    }
  }

  @Test
  void anchorInRepeatedGroup() throws Exception {
    assertRoute("1|(0|^a?){3}", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    assertAgrees("1|(0|^a?){3}", "a");
  }

  @Test
  void trailingEmptyIterationGroup() throws Exception {
    assertRoute("^(?:)a|(.*[_]*)+", PatternAnalyzer.MatchingStrategy.PIKEVM_CAPTURE);
    assertGroupsAgree("^(?:)a|(.*[_]*)+", "-");
    assertGroupsAgree("^(?:)a|(.*[_]*)+", "0");
    assertGroupsAgree("^(?:)a|(.*[_]*)+", "1");
  }

  // ---- B16 PIKEVM_CAPTURE bypass regression ----

  @Test
  void b16NullableContent_pikeVmCapture_throwsWithoutFallback() {
    // ((x*){0,}|a)(c|bcd): nullable group content (x*) under nullable outer quantifier ({0,})
    // triggers B16. Must throw UnsupportedPatternException, not silently route to PikeVM.
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("((x*){0,}|a)(c|bcd)"));
  }

  @Test
  void b16NullableContent_pikeVmCapture_agreesWithJdkWhenFallbackAllowed() {
    String pat = "((x*){0,}|a)(c|bcd)";
    ReggieOptions opts = ReggieOptions.builder().allowJdkFallback().build();
    ReggieMatcher m = RuntimeCompiler.compile(pat, opts);
    Pattern jdk = Pattern.compile(pat);
    for (String input : new String[] {"xbc", "ac", "abcd", "", "bcd", "xc"}) {
      Matcher jm = jdk.matcher(input);
      boolean jdkF = jm.find();
      assertEquals(jdkF, m.find(input), "find() for \"" + input + "\"");
    }
  }

  // ---- Controls ----

  @Test
  void control_anchorLoop_terminates() throws Exception {
    // Anchor-loop patterns are caught by B16 or B3 guards and must throw cleanly rather than
    // hang. (^)* triggers B16 (nullable capturing group under nullable quantifier);
    // (?:^)* triggers B3 (any anchor inside a quantifier).
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("(^)*a"));
    assertThrows(UnsupportedPatternException.class, () -> Reggie.compile("(?:^)*a"));
    // (^)*a with fallback allowed: verify compilation terminates and agrees with JDK.
    Pattern jdk = Pattern.compile("(^)*a");
    ReggieMatcher reggie = Reggie.compileAllowingFallback("(^)*a");
    assertEquals(jdk.matcher("a").find(), reggie.find("a"));
  }

  @Test
  void control_anchorAtStart() throws Exception {
    assertAgrees("^a", "a");
    assertAgrees("^a", "ba");
  }
}
