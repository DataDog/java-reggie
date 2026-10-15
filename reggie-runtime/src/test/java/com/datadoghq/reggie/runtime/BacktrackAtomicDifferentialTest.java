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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Atomic groups and possessive quantifiers on {@link BackrefBacktrackMatcher}, compared with
 * java.util.regex over every input of up to five characters from {a, b}: matches, find spans, all
 * group spans, matchInto and findMatchInto from every start.
 */
class BacktrackAtomicDifferentialTest {

  private static final String[] BODIES = {
    "a",
    "ab",
    "a|b",
    "a|ab",
    "ab|a",
    "a*",
    "a*?",
    "a+",
    "(a)",
    "(a|ab)",
    "(ab|a)",
    "(a)|b",
    "a*b?",
    "(a*)b?",
    "(?:a|b)*",
    "(a)\\1",
    "",
    "(a?)",
    "a{1,2}",
    "(?>a|ab)",
    "(?>(a|ab))b?",
  };

  private static final String[] WRAPPERS = {
    "(?>%s)",
    "(?:%s)*+",
    "(?:%s)++",
    "(?:%s)?+",
    "(?:%s){1,2}+",
    "(%s)*+",
    "(%s)++",
    "(?>%s)*",
    "(?>%s)+?",
    "(?>(?>%s)b|a)",
  };

  // Backreferences read only groups outside the atomic construct: java.util.regex keeps captures of
  // an abandoned atomic or loop path, which changes what a later backreference matches
  // ((?>(?>(a))b|a)(b)?\1? matches "aa" in the JDK; PCRE leaves group 1 unset and does not).
  private static final String[] CONTEXTS = {
    "%s", "%sb", "%sa", "%sab", "^%s$", "(a)?%s\\1", "(a|b)%s\\1", "(?:%s)+b",
  };

  @Test
  void atomicAndPossessiveAgreeWithJdk() {
    List<String> inputs = inputs(5);
    int checked = 0;
    for (String body : BODIES) {
      for (String wrapper : WRAPPERS) {
        for (String context : CONTEXTS) {
          String pattern = String.format(context, String.format(wrapper, body));
          Pattern jdk;
          try {
            jdk = Pattern.compile(pattern);
          } catch (RuntimeException e) {
            continue;
          }
          ReggieMatcher m = RuntimeCompiler.compileBackrefBacktrack(pattern, "");
          for (String input : inputs) {
            assertAgrees(pattern, jdk, m, input);
          }
          checked++;
        }
      }
    }
    assertTrue(checked > 1500, "patterns checked: " + checked);
  }

  @Test
  void handPickedAtomicPatternsAgreeWithJdk() {
    String[] patterns = {
      "(?>a+)a",
      "a++a",
      "(?>a|ab)c",
      "(?>ab|a)c",
      "(?>(a)|b)*c",
      "(?>(a+))\\1",
      "(a+)(?>\\1)b",
      "(?>a*)*b",
      "(?>a*?)b",
      "(?>(?>a+)b|a)+c",
      "(?>a(?>b|bc)|abcd)d",
      "x*+(?>(a)|ab)c",
      "(?>a{2,3}?)a",
      "(?>(a)(b)?)+\\2",
      "((?>a|ab))+c",
      "(?>a|ab)(?>c|bc)d",
      "(?>\\w+)\\b",
    };
    List<String> inputs = inputs(5);
    for (String pattern : patterns) {
      Pattern jdk = Pattern.compile(pattern);
      ReggieMatcher m = RuntimeCompiler.compileBackrefBacktrack(pattern, "");
      for (String input : inputs) {
        assertAgrees(pattern, jdk, m, input);
      }
      for (String input : new String[] {"abcd", "abcdd", "abbcd", "aab", "abac", "xabc", "aaaa"}) {
        assertAgrees(pattern, jdk, m, input);
      }
    }
  }

  private static void assertAgrees(String pattern, Pattern jdk, ReggieMatcher m, String input) {
    String where = "/" + pattern + "/ on \"" + input + "\"";
    ReggieMatcher pike = pikeVm(pattern);
    Matcher j = jdk.matcher(input);
    int groups = j.groupCount() + 1;
    int[] starts = new int[groups];
    int[] ends = new int[groups];
    int[] pikeStarts = new int[groups];
    int[] pikeEnds = new int[groups];

    boolean jm = j.matches();
    assertEquals(jm, m.matches(input), "matches " + where);
    assertEquals(jm, m.matchInto(input, starts, ends), "matchInto " + where);
    if (jm) {
      boolean pm = pike != null && pike.matchInto(input, pikeStarts, pikeEnds);
      assertGroups(where + " matchInto", j, starts, ends, pm ? pikeStarts : null, pikeEnds);
    }

    for (int from = 0; from <= input.length(); from++) {
      boolean jf = j.find(from);
      String at = where + " from " + from;
      Arrays.fill(starts, -2);
      assertEquals(jf, m.findMatchInto(input, from, starts, ends), "findMatchInto " + at);
      if (jf) {
        boolean pf = pike != null && pike.findMatchInto(input, from, pikeStarts, pikeEnds);
        assertGroups(at, j, starts, ends, pf ? pikeStarts : null, pikeEnds);
      }
      if (from == 0) {
        assertEquals(jf, m.find(input), "find " + at);
        MatchResult r = m.findMatch(input);
        assertEquals(jf, r != null, "findMatch " + at);
        if (jf) {
          for (int g = 0; g < groups; g++) {
            assertEquals(starts[g], r.start(g), "findMatch group " + g + " start " + at);
            assertEquals(ends[g], r.end(g), "findMatch group " + g + " end " + at);
          }
        }
      }
    }
  }

  /**
   * Group spans against the JDK's. java.util.regex keeps a group span written by a path it later
   * abandoned ({@code (?:(a))*a} on "a" reports group 1 = 0..1, {@code (?>(a))*a} on "aa" 1..2;
   * PCRE reports unset and 0..1), so a span differing from the JDK's passes when PikeVM reports the
   * same span. PikeVM does not run backreferences: for those patterns only group 0 is compared (the
   * referenced spans still decide every match outcome), and the same bodies and wrappers without
   * backreferences check the inner groups.
   */
  private static void assertGroups(
      String where, Matcher j, int[] starts, int[] ends, int[] pikeStarts, int[] pikeEnds) {
    for (int g = 0; g < starts.length; g++) {
      if (starts[g] == j.start(g) && ends[g] == j.end(g)) {
        continue;
      }
      boolean jdkLeftover =
          g > 0 && (pikeStarts == null || (pikeStarts[g] == starts[g] && pikeEnds[g] == ends[g]));
      assertTrue(
          jdkLeftover,
          "group "
              + g
              + " "
              + where
              + ": "
              + starts[g]
              + ".."
              + ends[g]
              + ", JDK "
              + j.start(g)
              + ".."
              + j.end(g));
    }
  }

  private static ReggieMatcher pikeVm(String pattern) {
    try {
      return RuntimeCompiler.compilePikeVm(pattern, "");
    } catch (UnsupportedPatternException e) {
      return null;
    }
  }

  private static List<String> inputs(int maxLen) {
    List<String> out = new ArrayList<>();
    out.add("");
    for (int len = 1; len <= maxLen; len++) {
      for (int bits = 0; bits < (1 << len); bits++) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
          sb.append((bits >> i & 1) == 0 ? 'a' : 'b');
        }
        out.add(sb.toString());
      }
    }
    return out;
  }
}
