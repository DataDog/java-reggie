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
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check for recursive-descent patterns that run on the backtracker with a
 * captureless DFA half: the DFA answers matches()/find()/findFrom(), the backtracker the spans.
 * Patterns with a construct the DFA does not express keep the plain backtracker.
 */
public class BacktrackDfaHalfTest {

  private static final String WITH_DFA_HALF = "BACKREF_BACKTRACK with DFA half";
  private static final String PLAIN = "BACKREF_BACKTRACK (";

  /** Pattern and the routing-note prefix it must produce. */
  private static final String[][] CASES = {
    {"^(\\S+):(\\d+).*$", WITH_DFA_HALF},
    {"(.*) \\((.*)\\)", WITH_DFA_HALF},
    {"^(?<kind>\\S+)(:\\s*(?<message>.*))?$", WITH_DFA_HALF},
    {"^(.*)-([a-zA-Z0-9]+)-[a-zA-Z0-9]+$", WITH_DFA_HALF},
    {"^([1-9]\\d*)\\.(\\d+)\\.(\\d+).*$", WITH_DFA_HALF},
    {"a([bc]*)(c+d)", WITH_DFA_HALF},
    {"(?i)a([bc]*)(c+d)", WITH_DFA_HALF},
    {"(?m)^(.*)-([a-z]+)$", WITH_DFA_HALF},
    {"(.*)-(?=[a-z])([a-z]+)", PLAIN},
    {"(.*)-([a-z]+)\\1", PLAIN}
  };

  private static final String[] INPUTS = {
    "",
    "-",
    "a-b-c",
    "abccd",
    "ABCCD",
    "xabcbcdy abd acd",
    "host:8080 rest",
    "host:x",
    ":1",
    "foo (bar) (baz)",
    "foo (bar",
    "kind: message text",
    "kind",
    "kind:",
    "name-abc-def",
    "name-abc-",
    "1.2.3",
    "10.20.30-pre",
    "0.1.2",
    "a-b\nc-d\r\ne-f",
    "x-ab-ab",
    "x-ab-",
    "é-中-😀"
  };

  private static String[] patterns() {
    String[] patterns = new String[CASES.length];
    for (int i = 0; i < CASES.length; i++) {
      patterns[i] = CASES[i][0];
    }
    return patterns;
  }

  @Test
  void agreesWithJdk() {
    assertEquals(List.of(), DotLineTerminatorTest.mismatches(patterns(), Arrays.asList(INPUTS)));
  }

  @Test
  void findMatchIntoAndBoundsAgreeWithJdk() {
    List<String> mismatches = new ArrayList<>();
    int[] bounds = new int[2];
    for (String p : patterns()) {
      ReggieMatcher r = Reggie.compile(p);
      Pattern j = Pattern.compile(p);
      for (String in : INPUTS) {
        for (int start = 0; start <= in.length(); start++) {
          Matcher m = j.matcher(in);
          boolean found = m.find(start);
          int[] starts = new int[m.groupCount() + 1];
          int[] ends = new int[m.groupCount() + 1];
          String jdk = found ? spans(m) : "-";
          String into = r.findMatchInto(in, start, starts, ends) ? spans(starts, ends) : "-";
          String jdkBounds = found ? m.start() + "-" + m.end() : "-";
          String reggieBounds =
              r.findBoundsFrom(in, start, bounds) ? bounds[0] + "-" + bounds[1] : "-";
          if (!jdk.equals(into) || !jdkBounds.equals(reggieBounds)) {
            mismatches.add(
                "/"
                    + p
                    + "/ from "
                    + start
                    + " on \""
                    + DotLineTerminatorTest.escape(in)
                    + "\": jdk="
                    + jdk
                    + " into="
                    + into
                    + " bounds="
                    + reggieBounds);
            break;
          }
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void routesAsIntended() {
    List<String> wrong = new ArrayList<>();
    for (String[] c : CASES) {
      String routing = RuntimeCompiler.describeRouting(c[0]).routing;
      if (!routing.startsWith(c[1])) {
        wrong.add(c[0] + " -> " + routing + " (expected " + c[1] + "...)");
      }
    }
    assertEquals(List.of(), wrong);
  }

  private static String spans(Matcher m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append(m.start(g)).append(',').append(m.end(g)).append(' ');
    }
    return sb.toString();
  }

  private static String spans(int[] starts, int[] ends) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g < starts.length; g++) {
      sb.append(starts[g]).append(',').append(ends[g]).append(' ');
    }
    return sb.toString();
  }
}
