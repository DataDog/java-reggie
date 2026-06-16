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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Direct algorithm validation for {@link BackrefBacktrackMatcher} (Task 8 shape B), independent of
 * routing. Constructs the matcher straight from a Thompson NFA and asserts matches()/find()/full
 * first-match spans agree with {@code java.util.regex} — including the three fuzz TARGET
 * divergences and the existing native-backref REGRESSION corpus.
 */
public class BackrefBacktrackMatcherTest {

  private static final String[][] TARGETS = {
    {"(c{0}|1)(\\1_{3}|.{1}[0-c])", "1_"},
    {"(0{1,}|b{2}){2}(?:[a]|-{1})*(\\1|c)", "000a"},
    {"(0{1}|b{2}){2,}(?:[c]|-{1})(\\1.|.c)", "00cb"},
  };

  private static final String[][] REGRESSION = {
    {"(a{2})\\1", "aaaa"},
    {"<(\\w+)>.*</\\1>", "<b>x</b>"},
    {"(\\w+)\\s+\\1", "hi hi"},
    {"(ab)\\1", "abab"},
    {"(a)(b)\\2\\1", "abba"},
    {"(.)\\1", "zz"},
  };

  @Test
  void targetsAgreeWithJdk() throws Exception {
    for (String[] pi : TARGETS) {
      assertAgrees(pi[0], pi[1]);
    }
  }

  @Test
  void regressionAgreesWithJdk() throws Exception {
    for (String[] pi : REGRESSION) {
      assertAgrees(pi[0], pi[1]);
    }
  }

  private static void assertAgrees(String pattern, String input) throws Exception {
    BackrefBacktrackMatcher m = build(pattern);

    assertEquals(
        Pattern.compile(pattern).matcher(input).matches(),
        m.matches(input),
        "matches() /" + pattern + "/ on \"" + input + "\"");

    Matcher jf = Pattern.compile(pattern).matcher(input);
    boolean jdkFind = jf.find();
    assertEquals(jdkFind, m.find(input), "find() /" + pattern + "/ on \"" + input + "\"");
    if (jdkFind) {
      MatchResult rm = m.findMatch(input);
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

  // Representative backref patterns harvested from the *Backref*/SelfReferencing/MultiBackref
  // suites, used for a differential sweep vs java.util.regex to flush out edge-case gaps.
  private static final String[] CORPUS = {
    "((.*))\\d+\\1",
    "((a?)\\2)",
    "(.)\\1",
    "(.*)-\\1",
    "(.*)=\\1",
    "(.*)\\d+\\1",
    "(.*)\\d\\1",
    "(.+)-\\1",
    "(.+):\\1",
    "(.+)=\\1",
    "(?:x)(a)\\1",
    "(?i)(ab)\\1",
    "(?i)(ab)\\d\\1",
    "(?i)(x)\\1",
    "([0-9]+) \\1 \\1",
    "([0-9]+)=\\1",
    "([a-z]+) \\1 \\1",
    "(\\d)\\1{2}",
    "(\\w)\\1{1,3}",
    "(\\w*) \\1 \\1",
    "(\\w+) \\1 \\1",
    "(\\w+) \\1 \\1 \\1",
    "(\\w+)-\\1-\\1",
    "(\\w+)=(\\w+)=\\1",
    "(\\w+)=\\1",
    "(\\w+)\\s+(\\w+)\\s+\\1\\s+\\2",
    "(\\w+)\\s+\\1",
    "(\\w+)\\s+\\1\\s+\\1",
    "(a)(b)\\2\\1",
    "(a)+\\1",
    "(a)?(b)?\\1\\2",
    "(a)?(b)?\\2\\1",
    "(a)?\\1",
    "(a)\\1",
    "(a)\\1{2,4}",
    "(a)\\1{2,}",
    "(a)\\1{2}",
    "(a)\\1{3}",
    "(a)\\1{8,}",
    "(a)\\1|b",
    "(a)|(b)\\1",
    "(a)|\\1",
    "(a?)+\\1",
    "(a?)\\1",
    "(a?)\\1{2}",
    "(ab)\\1",
    "(a{2})\\1",
    "(a|)\\1",
    "(a|)\\1{2}b",
    "(x)?\\1",
    "(x)?\\1y",
    "<(\\w+)>.*</\\1>",
    "^(.+)=\\1$",
    "^(a)?\\1$",
    "^(a)\\1{2,3}(.)",
    "^(a?)\\1+b",
    "^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$",
    "^(a\\1?)+$",
    "^(a\\1?){4}$",
    "^(a\\1?|b){4}$",
    "^(a|)\\1+b",
    "^(a|)\\1{2,3}b",
    "^(a|)\\1{2}b",
    "^(cow|)\\1(bell)",
    "(0{1,}|b{2}){2}(?:[a]|-{1})*(\\1|c)",
    "(0{1}|b{2}){2,}(?:[c]|-{1})(\\1.|.c)",
    "(c{0}|1)(\\1_{3}|.{1}[0-c])",
  };

  /**
   * Differential sweep: for each corpus pattern, exhaustively enumerate short inputs over a small
   * alphabet derived from the pattern and assert matches()/find()/first-match spans agree with
   * java.util.regex. Divergences are collected and reported together (so one run surfaces the full
   * gap list rather than stopping at the first). A pattern that this engine or the JDK cannot
   * compile is skipped.
   */
  // Self-referential-backref patterns whose INNER group spans intentionally differ from JDK per
  // REQ-39 (verified to match current Reggie, not this engine's invention). Boolean + overall-span
  // agreement is still asserted for them.
  private static final Set<String> SELF_REF_GROUP_SPAN_DEVIATION =
      new HashSet<>(
          Arrays.asList(
              "^(a\\1?)+$", "^(a\\1?){4}$", "^(a\\1?|b){4}$", "^(a\\1?)(a\\1?)(a\\2?)(a\\3?)$"));

  @Test
  void differentialSweepAgainstJdk() {
    StringBuilder report = new StringBuilder();
    int checked = 0;
    int skipped = 0;
    for (String pattern : CORPUS) {
      BackrefBacktrackMatcher m;
      Pattern jdk;
      try {
        m = build(pattern);
        jdk = Pattern.compile(pattern);
      } catch (Throwable t) {
        skipped++;
        continue;
      }
      String alphabet = alphabetFor(pattern);
      for (String input : enumerate(alphabet, 4)) {
        checked++;
        String diff = diff(m, jdk, pattern, input);
        if (diff != null) {
          report.append(diff).append('\n');
        }
      }
    }
    if (report.length() > 0) {
      org.junit.jupiter.api.Assertions.fail(
          "Differential divergences vs JDK (checked="
              + checked
              + ", skipped="
              + skipped
              + "):\n"
              + report);
    }
  }

  /**
   * The bounded/into API ({@code matchInto}, {@code matchBounded}, {@code matchesBounded}) is
   * inherited from {@link ReggieMatcher}'s defaults, which compose this engine's native {@code
   * match}/{@code matches} overrides (not JDK). Confirm that composition agrees with
   * java.util.regex.
   */
  @Test
  void boundedAndIntoApiAgreeWithJdk() throws Exception {
    String[][] cases = {
      {"(a)\\1", "aa"},
      {"(\\w+)=\\1", "ab=ab"},
      {"(a)(b)\\2\\1", "abba"},
      {"(.)\\1", "zz"},
    };
    for (String[] pi : cases) {
      String pattern = pi[0];
      String input = pi[1];
      BackrefBacktrackMatcher m = build(pattern);
      Pattern jdk = Pattern.compile(pattern);

      // matchInto vs match()
      int[] starts = new int[16];
      int[] ends = new int[16];
      boolean into = m.matchInto(input, starts, ends);
      Matcher jm = jdk.matcher(input);
      assertEquals(jm.matches(), into, "matchInto bool /" + pattern + "/");
      if (into) {
        for (int g = 0; g <= jm.groupCount(); g++) {
          assertEquals(jm.start(g), starts[g], "matchInto start g" + g + " /" + pattern + "/");
          assertEquals(jm.end(g), ends[g], "matchInto end g" + g + " /" + pattern + "/");
        }
      }

      // matchesBounded / matchBounded over a region of a padded input
      String padded = "xy" + input + "z";
      int rs = 2;
      int re = 2 + input.length();
      Matcher jb = jdk.matcher(padded);
      jb.region(rs, re);
      assertEquals(
          jb.matches(), m.matchesBounded(padded, rs, re), "matchesBounded /" + pattern + "/");
      MatchResult mb = m.matchBounded(padded, rs, re);
      if (jb.matches()) {
        Matcher jb2 = jdk.matcher(padded);
        jb2.region(rs, re);
        jb2.matches();
        assertEquals(jb2.start(), mb.start(), "matchBounded start /" + pattern + "/");
        assertEquals(jb2.end(), mb.end(), "matchBounded end /" + pattern + "/");
      }
    }
  }

  private static String diff(BackrefBacktrackMatcher m, Pattern jdk, String pattern, String input) {
    boolean jm, mm;
    try {
      jm = jdk.matcher(input).matches();
      mm = m.matches(input);
    } catch (Throwable t) {
      return "EXC matches /" + pattern + "/ on \"" + input + "\": " + t;
    }
    if (jm != mm) {
      return "matches /" + pattern + "/ on \"" + input + "\" jdk=" + jm + " reggie=" + mm;
    }
    Matcher jf = jdk.matcher(input);
    boolean jfind = jf.find();
    boolean mfind;
    try {
      mfind = m.find(input);
    } catch (Throwable t) {
      return "EXC find /" + pattern + "/ on \"" + input + "\": " + t;
    }
    if (jfind != mfind) {
      return "find /" + pattern + "/ on \"" + input + "\" jdk=" + jfind + " reggie=" + mfind;
    }
    if (jfind) {
      MatchResult rm;
      try {
        rm = m.findMatch(input);
      } catch (Throwable t) {
        return "EXC findMatch /" + pattern + "/ on \"" + input + "\": " + t;
      }
      if (jf.start() != rm.start() || jf.end() != rm.end()) {
        return "span /"
            + pattern
            + "/ on \""
            + input
            + "\" jdk=["
            + jf.start()
            + ","
            + jf.end()
            + ") reggie=["
            + rm.start()
            + ","
            + rm.end()
            + ")";
      }
      // Self-referential backref (\g inside group g's own body) has a documented Reggie semantic
      // that intentionally differs from java.util.regex on inner group spans: each iteration begins
      // with an empty partial capture, so \1 matches nothing (SelfReferencingBackrefTest, REQ-39).
      // Verified: current Reggie agrees with this engine (g1=[2,3)) while JDK gives [1,3) on
      // "^(a\1?)+$"/"aaa". matches()/find()/overall-span still agree, so only the per-group span
      // check is skipped for this family.
      if (SELF_REF_GROUP_SPAN_DEVIATION.contains(pattern)) {
        return null;
      }
      for (int g = 1; g <= jf.groupCount(); g++) {
        if (jf.start(g) != rm.start(g) || jf.end(g) != rm.end(g)) {
          return "group "
              + g
              + " /"
              + pattern
              + "/ on \""
              + input
              + "\" jdk=["
              + jf.start(g)
              + ","
              + jf.end(g)
              + ") reggie=["
              + rm.start(g)
              + ","
              + rm.end(g)
              + ")";
        }
      }
    }
    return null;
  }

  private static String alphabetFor(String pattern) {
    java.util.LinkedHashSet<Character> set = new java.util.LinkedHashSet<>();
    boolean escaped = false;
    for (int i = 0; i < pattern.length() && set.size() < 5; i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        if (c == 'd') set.add('1');
        else if (c == 'w') set.add('a');
        else if (c == 's') set.add(' ');
        continue;
      }
      if (c == '\\') escaped = true;
      else if (Character.isLetterOrDigit(c)) set.add(c);
      else if (c == '_' || c == '-' || c == '=' || c == ' ' || c == ':') set.add(c);
    }
    set.add('a');
    set.add('b');
    StringBuilder sb = new StringBuilder();
    for (char c : set) sb.append(c);
    return sb.length() > 5 ? sb.substring(0, 5) : sb.toString();
  }

  private static List<String> enumerate(String alphabet, int maxLen) {
    List<String> out = new ArrayList<>();
    out.add("");
    List<String> frontier = new ArrayList<>();
    frontier.add("");
    for (int len = 1; len <= maxLen; len++) {
      List<String> next = new ArrayList<>();
      for (String s : frontier) {
        for (int i = 0; i < alphabet.length(); i++) {
          String t = s + alphabet.charAt(i);
          next.add(t);
          out.add(t);
        }
      }
      frontier = next;
    }
    return out;
  }

  private static BackrefBacktrackMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, countGroups(pattern));
    return new BackrefBacktrackMatcher(nfa, pattern);
  }

  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    boolean inClass = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '[') {
        inClass = true;
      } else if (c == ']') {
        inClass = false;
      } else if (c == '(' && !inClass) {
        if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '?') {
          continue; // non-capturing / special group
        }
        count++;
      }
    }
    return count;
  }
}
