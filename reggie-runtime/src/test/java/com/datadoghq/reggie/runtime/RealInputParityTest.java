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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieFlags;
import com.datadoghq.reggie.ReggieMatcher;
import com.datadoghq.reggie.ReggieOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/**
 * JDK-vs-Reggie parity over the real logs-backend pattern corpus against real log lines harvested
 * from the logs-backend grok test fixtures — the local stand-in for the shadow-seam mismatch metric
 * (regex.matcher.shadow.count status:mismatch in logs-backend).
 *
 * <p>The comparison replicates the drop-in supplier contract: patterns compile with DOTALL (the
 * grok RegexPatternSupplier contract) and {@code allowJdkFallback} (zero functional refusals), and
 * every full match must agree on matches() AND every capture group. This battery caught three
 * divergences the synthetic six-line corpus never saw (fixed with it):
 *
 * <ul>
 *   <li>nullable-tail group spans on the tagged DFA — {@code ^(\w+:(?://)?)[^#]+} reported group 1
 *       as "http:" instead of "http://" (now routed to PIKEVM_CAPTURE)
 *   <li>VARIABLE_CAPTURE_BACKREF accepted trailing suffix nodes the generator never emitted and
 *       skipped the end check under a trailing $ — x(\d+)y\1z / x(\d+)y\1$ on "x123y123z"
 *   <li>parse-time refusals (variable-width lookbehind) escaped allowJdkFallback and threw instead
 *       of delegating to java.util.regex
 * </ul>
 */
public class RealInputParityTest {

  private static final boolean RUN_FULL_BATTERY =
      Boolean.parseBoolean(System.getProperty("reggie.test.fullRealInputParity", "true"));

  private static List<String> readResource(String path) throws IOException {
    try (InputStream in = RealInputParityTest.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing test resource: " + path);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      List<String> out = new ArrayList<>();
      for (String line : content.split("\n", -1)) {
        if (!line.isBlank() && !line.startsWith("#")) {
          out.add(line);
        }
      }
      return out;
    }
  }

  /** Same TSV unescape as RealCorpusScanBenchmark (export escaping: \t \n \r \\). */
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
  void fullMatchAndGroupParityOverRealCorpusAndRealInputs() throws IOException {
    List<String> inputs = readResource("/corpus/real-inputs.txt");
    if (!RUN_FULL_BATTERY) {
      inputs = inputs.subList(0, Math.min(64, inputs.size()));
    }
    List<String> patterns = new ArrayList<>();
    for (String line : readResource("/corpus/logs-backend-patterns.tsv")) {
      int tab = line.indexOf('\t');
      patterns.add(unescape(tab > 0 ? line.substring(0, tab) : line));
    }
    assertTrue(patterns.size() > 400, "corpus resource missing");

    int pairs = 0;
    int matches = 0;
    List<String> divergences = new ArrayList<>();
    for (String pattern : patterns) {
      java.util.regex.Pattern jdk;
      try {
        jdk = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.DOTALL);
      } catch (Exception e) {
        continue; // jdk-syntax-failed: not a reggie contract case
      }
      ReggieMatcher reggie;
      try {
        reggie =
            Reggie.compile(
                pattern, ReggieFlags.DOTALL, ReggieOptions.builder().allowJdkFallback().build());
      } catch (Exception e) {
        divergences.add("REFUSED " + pattern + " :: " + e.getMessage());
        continue;
      }
      for (String input : inputs) {
        pairs++;
        java.util.regex.Matcher jm = jdk.matcher(input);
        boolean j = jm.matches();
        boolean r = reggie.matches(input);
        if (j != r) {
          divergences.add("BOOL /" + pattern + "/ on [" + input + "] jdk=" + j + " reggie=" + r);
          continue;
        }
        if (!j) {
          continue;
        }
        matches++;
        com.datadoghq.reggie.runtime.MatchResult result =
            ((com.datadoghq.reggie.runtime.ReggieMatcher) reggie).match(input);
        if (result == null) {
          divergences.add("NO-RESULT /" + pattern + "/ on [" + input + "]");
          continue;
        }
        if (result.groupCount() != jm.groupCount()) {
          divergences.add(
              "GROUPCOUNT /"
                  + pattern
                  + "/ jdk="
                  + jm.groupCount()
                  + " reggie="
                  + result.groupCount());
          continue;
        }
        for (int g = 1; g <= jm.groupCount(); g++) {
          if (!Objects.equals(jm.group(g), result.group(g))) {
            divergences.add(
                "GROUP"
                    + g
                    + " /"
                    + pattern
                    + "/ on ["
                    + input
                    + "] jdk=["
                    + jm.group(g)
                    + "] reggie=["
                    + result.group(g)
                    + "]");
            break;
          }
        }
      }
    }
    // Zero-divergence contract under the drop-in supplier semantics.
    assertEquals(
        List.of(),
        divergences,
        "real-input parity divergences (pairs=" + pairs + " matches=" + matches + ")");
  }

  // ---- nullable-tail group span (tagged DFA divergence caught by this battery) ----

  @Test
  void nullableTailGroupSpan() {
    String pattern = "^(\\w+:(?://)?)[^#]+";
    String input = "http://10.21.120.145:6400";
    java.util.regex.Matcher jdk = java.util.regex.Pattern.compile(pattern).matcher(input);
    assertTrue(jdk.matches());
    assertEquals("http://", jdk.group(1));
    ReggieMatcher reggie = Reggie.compile(pattern);
    com.datadoghq.reggie.runtime.MatchResult result =
        ((com.datadoghq.reggie.runtime.ReggieMatcher) reggie).match(input);
    assertEquals("http://", result.group(1), "group 1 must span the matched optional tail");
    assertEquals(
        "https://",
        ((com.datadoghq.reggie.runtime.ReggieMatcher) reggie).match("https://ipinfo.io/").group(1));
  }

  // ---- VARIABLE_CAPTURE_BACKREF end/suffix semantics ----

  @Test
  void backrefWithTrailingElement() {
    assertTrue(Reggie.compile("x(\\d+)y\\1z").matches("x123y123z"));
    assertTrue(
        Reggie.compile(
                "Minified React error #(\\d+); visit https://reactjs\\.org/docs"
                    + "/error-decoder\\.html\\?invariant=\\1 for the full message or use the"
                    + " non-minified dev environment for full errors and additional helpful"
                    + " warnings\\.")
            .matches(
                "Minified React error #2834; visit https://reactjs.org/docs"
                    + "/error-decoder.html?invariant=2834 for the full message or use the"
                    + " non-minified dev environment for full errors and additional helpful"
                    + " warnings."));
  }

  @Test
  void backrefEndAnchorRequiresFullConsumption() {
    // matches() is full-region: a trailing $ does NOT accept an unconsumed tail/terminator.
    assertTrue(Reggie.compile("x(\\d+)y\\1$").matches("x123y123"));
    assertFalse(Reggie.compile("x(\\d+)y\\1$").matches("x123y123z"));
    assertFalse(Reggie.compile("x(\\d+)y\\1$").matches("x123y123\n"));
  }

  private static void assertFalse(boolean v) {
    assertTrue(!v);
  }

  // ---- parse-time refusals honor ALLOW_JDK_FALLBACK ----

  @Test
  void variableWidthLookbehindFallbackContract() {
    // Variable-width lookbehind is unsupported natively: refused without the option, delegated
    // to java.util.regex with it (zero functional refusals).
    String pattern = "(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])";
    assertThrows(Exception.class, () -> Reggie.compile(pattern, ReggieOptions.DEFAULT));
    ReggieMatcher fallback =
        Reggie.compile(pattern, ReggieOptions.builder().allowJdkFallback().build());
    java.util.regex.Pattern jdk = java.util.regex.Pattern.compile(pattern);
    for (String input : new String[] {"fooBar", "FOO", "a", ""}) {
      assertEquals(jdk.matcher(input).find(), fallback.find(input), "on [" + input + "]");
    }
  }
}
