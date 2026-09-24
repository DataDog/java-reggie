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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * JDK-vs-Reggie FIND-span parity (first match: span + every capture group) over the real
 * logs-backend corpus against the harvested real log lines — the find()-side sibling of {@link
 * RealInputParityTest}'s full-match battery.
 *
 * <p>This gate exists because the full-match battery cannot see find()-only failures: its first run
 * caught a pre-existing divergence where strategies with no word-boundary modeling accepted across
 * boundaries ({@code \bname:(\S+)} matched "name:..." inside "@peer.hostname:127.0.0.1" under
 * SPECIALIZED_MULTI_GROUP_GREEDY, and {@code \b(.*)end} failed to match at all under
 * RECURSIVE_DESCENT — both fixed by PatternAnalyzer's word-boundary declines).
 */
public class RealFindParityTest {

  private static List<String> readResource(String path) throws IOException {
    try (var in = RealFindParityTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing test resource: " + path);
      }
      List<String> out = new ArrayList<>();
      for (String line :
          new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
        if (!line.isBlank() && !line.startsWith("#")) {
          out.add(line);
        }
      }
      return out;
    }
  }

  private static String unescape(String s) {
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char n = s.charAt(++i);
        switch (n) {
          case 't' -> b.append('\t');
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case '\\' -> b.append('\\');
          default -> {
            b.append('\\');
            b.append(n);
          }
        }
      } else {
        b.append(c);
      }
    }
    return b.toString();
  }

  @Test
  void findSpanAndGroupParityOverRealCorpusAndRealInputs() throws IOException {
    List<String> inputs = readResource("/corpus/real-inputs.txt");
    List<String> patterns = new ArrayList<>();
    for (String line : readResource("/corpus/logs-backend-patterns.tsv")) {
      int tab = line.indexOf('\t');
      patterns.add(unescape(tab > 0 ? line.substring(0, tab) : line));
    }
    assertTrue(patterns.size() > 400, "corpus resource missing");

    long pairs = 0;
    long finds = 0;
    List<String> divergences = new ArrayList<>();
    for (String pattern : patterns) {
      java.util.regex.Pattern jdk;
      try {
        jdk = java.util.regex.Pattern.compile(pattern);
      } catch (Exception e) {
        continue;
      }
      ReggieMatcher reggie;
      try {
        // Drop-in supplier contract: allowJdkFallback, so native refusals delegate to
        // java.util.regex instead of throwing (the battery measures parity, not coverage).
        reggie =
            (ReggieMatcher)
                Reggie.compile(
                    pattern,
                    com.datadoghq.reggie.ReggieOptions.builder().allowJdkFallback().build());
      } catch (Exception e) {
        divergences.add("REFUSED " + pattern + " :: " + e.getMessage());
        continue;
      }
      for (String input : inputs) {
        pairs++;
        java.util.regex.Matcher jm = jdk.matcher(input);
        String j = jm.find() ? spanOf(jm) : "-";
        MatchResult r = reggie.findMatch(input);
        String rs = r == null ? "-" : spanOf(r);
        if (!j.equals(rs)) {
          divergences.add("FIND /" + pattern + "/ on [" + input + "] jdk=" + j + " reggie=" + rs);
          continue;
        }
        if (!rs.equals("-")) {
          finds++;
        }
      }
    }
    assertEquals(
        List.of(), divergences, "find divergences (pairs=" + pairs + " finds=" + finds + ")");
  }

  private static String spanOf(java.util.regex.Matcher m) {
    StringBuilder b = new StringBuilder("[" + m.start() + "," + m.end() + ")");
    for (int g = 1; g <= m.groupCount(); g++) {
      b.append("[").append(m.group(g)).append("]");
    }
    return b.toString();
  }

  private static String spanOf(MatchResult m) {
    StringBuilder b = new StringBuilder("[" + m.start() + "," + m.end() + ")");
    for (int g = 1; g <= m.groupCount(); g++) {
      b.append("[").append(m.group(g)).append("]");
    }
    return b.toString();
  }

  // ---- misplaced start-anchor regression (fuzz seed 131071, found by the caret probe) ----

  @Test
  void consumingAlternationBeforeStartAnchorDoesNotFireAnchorMidInput() {
    // (?:c|a) consumed one char, then ^ fired at position 1 under DFA_UNROLLED: the subset
    // construction erased the [START] acceptance condition when the parallel .\z\z branch
    // merged in (no dilution flag). PatternAnalyzer.scanForMisplacedStartAnchor now treats a
    // consuming alternation as consumption, declining the DFA fast path.
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("(?:c|a)^|.\\z\\z");
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("(?:c|a)^|.\\z\\z");
    for (String in : new String[] {"ax", "cab", "xy", "1\n0\n_cbcc_"}) {
      java.util.regex.Matcher jm = jdk.matcher(in);
      String expected = jm.find() ? "[" + jm.start() + "," + jm.end() + ")" : "-";
      MatchResult r = m.findMatch(in);
      assertEquals(
          expected, r == null ? "-" : "[" + r.start() + "," + r.end() + ")", "on [" + in + "]");
    }
    assertEquals(1, m.findAll("1\n0\n_cbcc_").size());
  }

  // ---- word-boundary regressions (found by the battery above) ----

  @Test
  void wordBoundaryNotAcceptedAcrossWordChars() {
    // SPECIALIZED_MULTI_GROUP_GREEDY had no  modeling: "name:" inside "hostname:" was accepted.
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("\\bname:(\\S+)");
    assertFalse(m.find("@peer.hostname:127.0.0.1"));
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile("\\bname:(\\S+)");
    assertFalse(jdk.matcher("@peer.hostname:127.0.0.1").find());
    assertTrue(m.find("a name:value"));
    MatchResult r = m.findMatch("a name:value");
    assertTrue(r != null && r.start() == 2 && r.end() == 12, "found=" + r);
    assertEquals("value", r == null ? null : r.group(1));
  }

  @Test
  void wordBoundaryAcceptedAtInputStart() {
    // RECURSIVE_DESCENT mishandled : (.*)end on "appendend" returned no-match.
    ReggieMatcher m = (ReggieMatcher) Reggie.compile("\\b(.*)end");
    MatchResult r = m.findMatch("appendend");
    assertTrue(r != null && r.start() == 0 && r.end() == 9, "found=" + r);
    assertEquals("append", r == null ? null : r.group(1));
  }
}
