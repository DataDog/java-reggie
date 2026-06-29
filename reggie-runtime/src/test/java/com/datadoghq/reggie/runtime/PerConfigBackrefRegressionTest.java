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
import com.datadoghq.reggie.ReggieOptions;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the OPTIMIZED_NFA_WITH_BACKREFS per-config worklist redesign.
 *
 * <p>The three TARGET patterns are over-matches under the legacy lockstep simulation and must agree
 * with the JDK after the per-config capture work (Phase 2) lands. The REGRESSION patterns already
 * pass and must keep passing through every phase.
 */
public class PerConfigBackrefRegressionTest {

  private static final ReggieOptions WITH_FALLBACK =
      ReggieOptions.builder().allowJdkFallback().build();

  // (pattern, input) pairs that diverge today; must agree after Phase 2. Inputs are the exact
  // minimal repros recorded in doc/temp/prod-readiness/fuzz-inventory.md section B:
  //   P1 group-span divergence (g1 [0,0)->[0,1), g2 [0,2)->[1,2)); P2/P3 over-match (JDK no-match).
  private static final String[][] TARGETS = {
    {"(c{0}|1)(\\1_{3}|.{1}[0-c])", "1_"},
    {"(0{1,}|b{2}){2}(?:[a]|-{1})*(\\1|c)", "000a"},
    {"(0{1}|b{2}){2,}(?:[c]|-{1})(\\1.|.c)", "00cb"},
  };

  // Patterns that work natively today; guard against regressions in the new skeleton.
  private static final String[][] REGRESSION = {
    {"(a{2})\\1", "aaaa"},
    {"<(\\w+)>.*</\\1>", "<b>x</b>"},
    {"(\\w+)\\s+\\1", "hi hi"},
    {"(ab)\\1", "abab"},
    {"(a)(b)\\2\\1", "abba"},
    {"(.)\\1", "zz"},
  };

  @Test
  void targetsAgreeWithJdk_acrossInputs() {
    for (String[] pi : TARGETS) {
      assertAgrees(pi[0], pi[1]);
    }
  }

  @Test
  void regressionPatternsStayCorrect() {
    for (String[] pi : REGRESSION) {
      assertAgrees(pi[0], pi[1]);
    }
  }

  /** CRLF pair at end: per-config anchor must accept curPos==len-2 when charAt is '\r\n'. */
  @Test
  void crlfEndAnchorInPerConfigBackref_agreesWithJdk() {
    assertAgrees("((?:a|b))\\1$", "aa\r\n");
    assertAgrees("((?:a|b))\\1$", "aa");
    assertAgrees("((?:a|b))\\1$", "ab\r\n");
    assertAgrees("((?:a|b))\\1\\Z", "bb\r\n");
  }

  /**
   * matches() boolean, find() boolean, and the full first-match span <em>including every group
   * span</em> must agree with java.util.regex. Group spans are essential: the P1 target diverges
   * only in group spans (matches()/find() agree), so a span-blind check would miss it.
   */
  private static void assertAgrees(String pattern, String input) {
    var reggie = Reggie.compile(pattern, WITH_FALLBACK);

    assertEquals(
        Pattern.compile(pattern).matcher(input).matches(),
        reggie.matches(input),
        "matches() /" + pattern + "/ on \"" + input + "\"");

    Matcher jf = Pattern.compile(pattern).matcher(input);
    boolean jdkFind = jf.find();
    assertEquals(jdkFind, reggie.find(input), "find() /" + pattern + "/ on \"" + input + "\"");
    if (jdkFind) {
      var rm = reggie.findMatch(input);
      assertEquals(jf.start(), rm.start(), "find start /" + pattern + "/ on \"" + input + "\"");
      assertEquals(jf.end(), rm.end(), "find end /" + pattern + "/ on \"" + input + "\"");
      for (int g = 1; g <= jf.groupCount(); g++) {
        assertEquals(
            jf.start(g),
            rm.start(g),
            "group " + g + " start /" + pattern + "/ on \"" + input + "\"");
        assertEquals(
            jf.end(g), rm.end(g), "group " + g + " end /" + pattern + "/ on \"" + input + "\"");
      }
    }
  }
}
