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
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Input-side NUL audit: NUL bytes in the INPUT must never be treated as string terminators by any
 * strategy (SWAR bulk scans, StringView byte/char modes, DFA/PikeVM/chain engines, bounded
 * regions). The pattern side is pinned separately (NulPatternTruncationTest); this test pins the
 * input side differentially against java.util.regex over a battery designed to cross the SWAR
 * 8-byte boundaries, force both String coders (latin-1 vs UTF-16), and cover anchors, word
 * boundaries, captures and the hex-digit fast path.
 *
 * <p>Audit basis (2026-09-16): 2,152 differential checks, zero divergences — matches(), find() with
 * spans and groups, findMatchFrom(), findAll(), and matchesBounded over String, StringBuilder,
 * StringBuffer and CharBuffer with all region combinations.
 */
class InputSideNulAuditTest {

  static String[] patterns() {
    return new String[] {
      // SWAR-eligible: literals, single byte, ranges, negated, hex-digit fast path
      "abc",
      "a",
      "(?:foo|bar)",
      "[a-z]+",
      "[^a]",
      "[a-cx-z]+",
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
      "[0-9a-fA-F]+",
      "ab[0-9]cd",
      "\\x00",
      "[\\x00]?",
      "[^\\x00]+",
      // anchors and boundaries
      "^a",
      "a$",
      "^a$",
      "\\ba\\b",
      "^",
      "$",
      // dot, quantifiers, captures
      "a.c",
      ".*",
      ".+",
      "(a)(b)",
      "(.*)",
      "(a+)-b",
    };
  }

  static String[] inputs() {
    return new String[] {
      "\u0000",
      "a\u0000",
      "\u0000a",
      "a\u0000b",
      "ab\u0000",
      "abc\u0000def",
      "a\u0000\u0000b",
      "\u0000foo\u0000bar\u0000",
      // crossing SWAR 8-byte boundaries
      "abcdefgh\u0000ijklmno",
      "abcdefg\u0000hijklmno",
      "12345678\u00009abcdef",
      // UTF-16 coder (non-latin-1 chars force the non-SWAR byte/char paths)
      "a\u0000\u00e9",
      "\u0000\u03a3abc",
      "\u4e2d\u0000\u6587",
      "abc\u0000\u4e2d\u6587\u0000",
    };
  }

  @ParameterizedTest(name = "{0} on {1}")
  @MethodSource({"patternsAndInputs"})
  void inputSideNulDifferential(String pattern, String input) {
    Pattern jp = Pattern.compile(pattern);
    ReggieMatcher rm = Reggie.compile(pattern);

    // matches()
    assertEquals(jp.matcher(input).matches(), rm.matches(input), "matches for " + pattern);

    // find() + span + groups
    java.util.regex.Matcher jm = jp.matcher(input);
    boolean jf = jm.find();
    MatchResult rr = rm.findMatch(input);
    assertEquals(jf, rr != null, "find presence for " + pattern);
    if (jf) {
      assertEquals(jm.start(), rr.start(), "find start for " + pattern);
      assertEquals(jm.end(), rr.end(), "find end for " + pattern);
      for (int g = 1; g <= jm.groupCount(); g++) {
        assertEquals(jm.group(g), rr.group(g), "group " + g + " for " + pattern);
      }
    }

    // find from position 1 (scan across a leading NUL)
    if (input.length() > 1) {
      java.util.regex.Matcher jm2 = jp.matcher(input);
      boolean jf2 = jm2.find(1);
      MatchResult rr2 = rm.findMatchFrom(input, 1);
      assertEquals(jf2, rr2 != null, "findMatchFrom presence for " + pattern);
      if (jf2) {
        assertEquals(jm2.start(), rr2.start(), "findMatchFrom start for " + pattern);
        assertEquals(jm2.end(), rr2.end(), "findMatchFrom end for " + pattern);
      }
    }

    // findAll spans
    java.util.regex.Matcher jm3 = jp.matcher(input);
    java.util.List<MatchResult> rall = rm.findAll(input);
    java.util.List<int[]> jspans = new java.util.ArrayList<>();
    java.util.List<int[]> rspans = new java.util.ArrayList<>();
    while (jm3.find()) jspans.add(new int[] {jm3.start(), jm3.end()});
    for (MatchResult m : rall) rspans.add(new int[] {m.start(), m.end()});
    assertEquals(jspans.size(), rspans.size(), "findAll count for " + pattern);
    for (int k = 0; k < jspans.size(); k++) {
      assertEquals(jspans.get(k)[0], rspans.get(k)[0], "findAll[" + k + "].start for " + pattern);
      assertEquals(jspans.get(k)[1], rspans.get(k)[1], "findAll[" + k + "].end for " + pattern);
    }
  }

  static java.util.List<org.junit.jupiter.params.provider.Arguments> patternsAndInputs() {
    java.util.List<org.junit.jupiter.params.provider.Arguments> out = new java.util.ArrayList<>();
    for (String p : patterns())
      for (String i : inputs()) out.add(org.junit.jupiter.params.provider.Arguments.of(p, i));
    return out;
  }

  /** The bounded-region API over CharSequence implementations (a different StringView mode). */
  @org.junit.jupiter.api.Test
  void boundedRegionsOverCharSequences() {
    CharSequence[] ins = {
      "a\u0000b",
      "\u0000ab\u0000",
      "\u0000abc",
      new StringBuilder("x\u0000abc\u0000y"),
      new StringBuffer("ab\u0000"),
      java.nio.CharBuffer.wrap("a\u0000bc\u0000"),
    };
    String[] pats = {"a", "abc", "[^\\x00]+", "a.c", "\\x00", ".*", "\\w+"};
    for (CharSequence in : ins) {
      for (String p : pats) {
        Pattern jp = Pattern.compile(p);
        ReggieMatcher rm = Reggie.compile(p);
        for (int s = 0; s <= in.length(); s++) {
          for (int e = s; e <= in.length(); e++) {
            assertEquals(
                jp.matcher(in).region(s, e).matches(),
                rm.matchesBounded(in, s, e),
                p + " on [" + s + "," + e + ")");
          }
        }
      }
    }
  }
}
