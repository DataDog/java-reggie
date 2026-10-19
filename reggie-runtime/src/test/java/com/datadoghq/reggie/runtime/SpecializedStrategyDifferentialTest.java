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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.datadoghq.reggie.Reggie;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for the specialized greedy strategies on short inputs around the loop
 * bounds: empty and zero-width loops, a run shorter than the minimum (alone or before a longer
 * run), a run followed by a char outside the loop class, and Latin-1 and UTF-16 inputs. Runs of
 * eight or more chars reach the SWAR word loop; Latin-1 inputs include chars above 0x7F.
 */
public class SpecializedStrategyDifferentialTest {

  private static final String[] PATTERNS = {
    "([^a]+)",
    "([ab]{2,})",
    "([ab]{2})",
    "[^a]{1,2}a",
    "(?:[^a]{1,2})",
    "c([^a]?)",
    "a([^a]{0})",
    "(a)b([ab]{0})",
    "()([ab]{0})",
    "(?:b)(c{1,2})",
    "(?:x)(\\d+)",
    "(?:[^a])([^a]+)",
    "([ab]{2})(?:)",
    "(?:)([ab]{2})",
    "c(?:c)([^a]{2})",
    "(?:b)([ab]{0})",
    "(\\d{1,3})\\.(\\d{1,3})",
    "a(\\d{2,3})",
    "(\\d{2,3})b",
    "()(a)",
    "([ab]+)b",
    "(\\d+)(\\d)",
    "([a-z]+)([a-z]{2})",
    "([ab]+)()c",
    "([ab]+)(c(?:)d)",
    "(a)b([ab]*)",
    "(a)b([ab]?)",
    "(a)b([ab]{0,2})",
    "([^\u00ff]+)"
  };

  /** Pattern and the strategy it routes to, so the cases keep covering the specialized engines. */
  private static final String[][] ROUTES = {
    {"([^a]+)", "SPECIALIZED_GREEDY_CHARCLASS"},
    {"([ab]{2,})", "SPECIALIZED_GREEDY_CHARCLASS"},
    {"[^a]{1,2}a", "SPECIALIZED_BOUNDED_QUANTIFIERS"},
    {"(\\d{1,3})\\.(\\d{1,3})", "SPECIALIZED_MULTI_GROUP_GREEDY"},
    {"(a)b([ab]{0,2})", "SPECIALIZED_MULTI_GROUP_GREEDY"},
    {"([ab]{2})(?:)", "SPECIALIZED_CONCAT_GREEDY_GROUP"},
    {"c(?:c)([^a]{2})", "SPECIALIZED_CONCAT_GREEDY_GROUP"}
  };

  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "b",
          "c",
          "5",
          "x5",
          "aa",
          "ab",
          "ba",
          "bb",
          "cb",
          "cbb",
          "ccbb",
          "bc",
          "bcc",
          "acab",
          "cacbb",
          "abcab",
          "\u4e2d",
          "a\u4e2d\u4e2da",
          "\u4e2da\u00e9\u00e9",
          "1.2",
          "123.456",
          "1234.5",
          "1.2345",
          "a12",
          "a1234",
          "12b",
          "1234b",
          "abb",
          "123",
          "abc",
          "abcd",
          "aaaaaaaaaaaab",
          "aaaaaaaaaaaaaaaa",
          "aaaaaaaa\u00e9",
          "\u00ff\u00ff\u00ff\u00ff\u00ff\u00ff\u00ff\u00ff\u00ffx",
          "1234567890.1234567890");

  @Test
  void agreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : PATTERNS) {
      try {
        mismatches.addAll(DotLineTerminatorTest.mismatches(new String[] {p}, INPUTS));
      } catch (RuntimeException e) {
        mismatches.add("/" + p + "/ threw " + e);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void boundedAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : INPUTS) {
        for (int start = 0; start <= in.length(); start++) {
          Matcher m = j.matcher(in).region(start, in.length());
          boolean jdk = m.matches();
          String reggie;
          try {
            MatchResult res = r.matchBounded(in, start, in.length());
            reggie = r.matchesBounded(in, start, in.length()) + " " + spans(res);
          } catch (RuntimeException e) {
            reggie = e.toString();
          }
          String expected = jdk + " " + (jdk ? spans(m) : "-");
          if (!expected.equals(reggie)) {
            mismatches.add(
                "/"
                    + p
                    + "/ bounded("
                    + start
                    + ") on \""
                    + DotLineTerminatorTest.escape(in)
                    + "\": jdk="
                    + expected
                    + " reggie="
                    + reggie);
            break;
          }
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static String spans(Matcher m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append(m.start(g)).append('-').append(m.end(g)).append(' ');
    }
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    if (m == null) {
      return "-";
    }
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append(m.start(g)).append('-').append(m.end(g)).append(' ');
    }
    return sb.toString();
  }

  @Test
  void routesAsIntended() throws Exception {
    List<String> wrong = new ArrayList<>();
    for (String[] c : ROUTES) {
      String route = StrategyCorrectnessMetaTest.routeOf(c[0]).name();
      if (!route.equals(c[1])) {
        wrong.add(c[0] + " -> " + route + " (expected " + c[1] + ")");
      }
    }
    assertEquals(List.of(), wrong);
  }

  @Test
  void findBoundsAgreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    int[] bounds = new int[2];
    for (String p : PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : INPUTS) {
        for (int start = 0; start <= in.length(); start++) {
          Matcher m = j.matcher(in);
          String jdk = m.find(start) ? m.start() + "-" + m.end() : "-";
          String reggie;
          try {
            reggie = r.findBoundsFrom(in, start, bounds) ? bounds[0] + "-" + bounds[1] : "-";
          } catch (RuntimeException e) {
            reggie = e.toString();
          }
          if (!jdk.equals(reggie)) {
            mismatches.add(
                "/"
                    + p
                    + "/ findBoundsFrom("
                    + start
                    + ") on \""
                    + DotLineTerminatorTest.escape(in)
                    + "\": jdk="
                    + jdk
                    + " reggie="
                    + reggie);
            break;
          }
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void nestedOptionalsCompileQuickly() {
    String[] patterns = {
      "(?:a(?:b".repeat(6) + "c" + ")?)?".repeat(6), "(?:(?:x".repeat(6) + "y" + ")+z)*".repeat(6)
    };
    for (String p : patterns) {
      assertTimeoutPreemptively(Duration.ofSeconds(10), () -> Reggie.compile(p));
    }
  }
}
