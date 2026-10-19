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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for a greedy {@code .*}/{@code .+} capturing group that must give
 * characters back to its suffix: compares matches()/match() and find()/findMatch() group spans.
 */
public class GreedySuffixGiveBackDifferentialTest {

  private static final String[] PATTERNS = {
    "(.+)(\\d+)",
    "(.*)(\\d+)",
    "(.+)(\\d*)",
    "(.*)(\\d*)",
    "(.+)(\\d+)$",
    "(.*)(\\d+)$",
    "(.+)\\b(\\d+)",
    "(.*)\\b(\\d+)",
    "(.+)\\b(\\d+)$",
    "(.*)\\b(\\d+)$",
    "x(.+)(\\d+)",
    "x(.*)(\\d+)",
    "(?s)(.+)(\\d+)",
    "(?s)(.*)(\\d+)$",
    "(.+)([a-z]+)",
    "(.*)\\d",
    "(.+)\\d",
    "(.*)ab",
    "(.*)ab$",
    "x(.*)ab"
  };

  private static final String[] INPUTS = {
    "", "1", "12", "123", "a1", "ab12", "a", "abc", "1a", "12a", "a1b2", "\n5", "a\n12", "12\n",
    "a\n", "1\n2", "x1", "x12", "xx12", "ax12", "x\n12", " 12 34", "a 1", "ab", "xab", "aabab",
    "ab\n", "a\nab", "x\nab", "ab1", "aab", "12ab34"
  };

  @Test
  void agreesWithJdk() {
    List<String> mismatches = new ArrayList<>();
    for (String p : PATTERNS) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : INPUTS) {
        Matcher m = j.matcher(in);
        String jdkMatch = m.matches() ? spans(m) : "-";
        MatchResult mr = r.match(in);
        String reggieMatch = mr == null ? "-" : spans(mr);
        boolean reggieMatches = r.matches(in);
        m = j.matcher(in);
        String jdkFind = m.find() ? spans(m) : "-";
        MatchResult fr = r.findMatch(in);
        String reggieFind = fr == null ? "-" : spans(fr);
        boolean reggieFound = r.find(in);
        if (!jdkMatch.equals(reggieMatch)
            || reggieMatches != !jdkMatch.equals("-")
            || !jdkFind.equals(reggieFind)
            || reggieFound != !jdkFind.equals("-")) {
          mismatches.add(
              String.format(
                  "/%s/ on \"%s\": match jdk=%s reggie=%s(%s) find jdk=%s reggie=%s(%s)",
                  p,
                  in.replace("\n", "\\n"),
                  jdkMatch,
                  reggieMatch,
                  reggieMatches,
                  jdkFind,
                  reggieFind,
                  reggieFound));
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static String spans(java.util.regex.MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append('[').append(m.start(g)).append(',').append(m.end(g)).append(')');
    }
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append('[').append(m.start(g)).append(',').append(m.end(g)).append(')');
    }
    return sb.toString();
  }
}
