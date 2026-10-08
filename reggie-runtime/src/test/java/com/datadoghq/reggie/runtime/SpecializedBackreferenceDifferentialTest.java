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
import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The hardcoded backreference shapes (HTML tag, repeated word, attribute match, greedy-any) and
 * their near variants, whose character classes differ from the hardcoded ones. Each seed input and
 * every single-character substitution, insertion and deletion of it is checked against JDK,
 * including group spans.
 */
public class SpecializedBackreferenceDifferentialTest {

  private static final String[] PATTERNS = {
    "<(\\w+)>.*</\\1>",
    "<([a-z]+)>.*</\\1>",
    "<([^>]+)>.*</\\1>",
    "<(\\w+)>[\\s\\S]*</\\1>",
    "<(\\w+)>(?s:.*)</\\1>",
    "<(\\w+)>[^<]*</\\1>",
    "\\b(\\w+)\\s+\\1\\b",
    "\\b([a-z]+)\\s+\\1\\b",
    "\\b([^ ]+)\\s+\\1\\b",
    "\\b(\\w+)[^\\w]+\\1\\b",
    "\\b(\\w+) +\\1\\b",
    "\"([^\"]+)\"\\s*=\\s*\"\\1\"",
    "\"([^\"=]+)\"\\s*=\\s*\"\\1\"",
    "\"([a-z]+)\"\\s*=\\s*\"\\1\"",
    "\"([^\"]+)\" *= *\"\\1\"",
    "\"([^\"]+)\"[^=]*=\\s*\"\\1\"",
    "(.*)\\d+\\1",
    "(?s)(.*)\\d+\\1",
    "(?s)(.*)[^a]+\\1",
    "(?s)(.+)[a-z]+\\1"
  };

  private static final String[] SEEDS = {
    "<a>x</a>",
    "<ab>y</ab>",
    "<a>\n</a>",
    "<a><b></a>",
    "<a>b</a>c</a>",
    "a a",
    "ab ab",
    "a\u001Ca",
    "x a  a y",
    "a-a",
    "\"k\"=\"k\"",
    "\"k\" = \"k\"",
    "\"k\u001C\"=\"k\u001C\"",
    "\"a=b\"=\"a=b\"",
    "a1a",
    "ab12ab",
    "a\n1a\n",
    "a\nb1a\nb"
  };

  private static final char[] ALPHABET = {
    'a', 'b', '1', '<', '>', '/', '"', '=', ' ', '\n', '\u001C', '\u2028', '-', '_'
  };

  @Test
  void agreesWithJdk() throws Exception {
    Set<String> inputs = new LinkedHashSet<>();
    inputs.add("");
    for (String seed : SEEDS) {
      inputs.add(seed);
      for (int i = 0; i <= seed.length(); i++) {
        if (i < seed.length()) inputs.add(seed.substring(0, i) + seed.substring(i + 1));
        for (char ch : ALPHABET) {
          inputs.add(seed.substring(0, i) + ch + seed.substring(i));
          if (i < seed.length()) inputs.add(seed.substring(0, i) + ch + seed.substring(i + 1));
        }
      }
    }
    List<String> mismatches = new ArrayList<>();
    for (String pattern : PATTERNS) {
      String route = StrategyCorrectnessMetaTest.routeOf(pattern).name();
      ReggieMatcher r;
      try {
        r = Reggie.compile(pattern);
      } catch (UnsupportedPatternException e) {
        continue; // refused: no wrong answers to check
      }
      // A generated matcher that loops forever must fail the test, not hang it.
      String[] current = new String[1];
      String[] result = new String[1];
      Thread worker =
          new Thread(
              () -> {
                Pattern j = Pattern.compile(pattern);
                int bad = 0;
                String first = null;
                for (String in : inputs) {
                  current[0] = in;
                  Matcher jm = j.matcher(in);
                  boolean jFound = jm.find();
                  MatchResult rm = r.findMatch(in);
                  if (r.matches(in) != j.matcher(in).matches()
                      || r.find(in) != jFound
                      || (rm != null) != jFound
                      || (jFound && !spans(jm).equals(spans(rm)))) {
                    if (first == null) first = in;
                    bad++;
                  }
                }
                if (bad > 0) result[0] = bad + " bad, first \"" + escape(first) + "\"";
              });
      worker.setDaemon(true);
      worker.start();
      worker.join(20_000);
      if (worker.isAlive()) {
        mismatches.add(pattern + " [" + route + "] hangs on \"" + escape(current[0]) + "\"");
      } else if (result[0] != null) {
        mismatches.add(pattern + " [" + route + "] " + result[0]);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < 0x20 || c > 0x7E) sb.append(String.format("\\u%04X", (int) c));
      else sb.append(c);
    }
    return sb.toString();
  }

  private static List<Integer> spans(java.util.regex.MatchResult m) {
    List<Integer> s = new ArrayList<>();
    for (int g = 0; g <= m.groupCount(); g++) {
      s.add(m.start(g));
      s.add(m.end(g));
    }
    return s;
  }

  private static List<Integer> spans(MatchResult m) {
    List<Integer> s = new ArrayList<>();
    for (int g = 0; g <= m.groupCount(); g++) {
      s.add(m.start(g));
      s.add(m.end(g));
    }
    return s;
  }
}
