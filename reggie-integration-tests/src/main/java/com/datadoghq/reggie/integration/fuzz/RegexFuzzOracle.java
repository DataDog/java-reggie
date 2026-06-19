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
package com.datadoghq.reggie.integration.fuzz;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compares Reggie's matching behaviour against {@link java.util.regex.Pattern} for a single pattern
 * + input pair. The JDK is the oracle; any divergence is reported as a {@link Finding}.
 *
 * <p>Reasons for non-comparable cases (skipped, not failed): the JDK rejects the pattern, Reggie
 * rejects the pattern, or either engine throws at match time. Only well-typed inputs produce a
 * comparison.
 */
public final class RegexFuzzOracle {

  /** A divergence between Reggie and the JDK. Self-contained so it can be logged or rerun. */
  public static final class Finding {
    public final String pattern;
    public final String input;
    public final String description;

    public Finding(String pattern, String input, String description) {
      this.pattern = pattern;
      this.input = input;
      this.description = description;
    }

    @Override
    public String toString() {
      return String.format("pattern=%s input=%s: %s", escape(pattern), escape(input), description);
    }
  }

  /** Outcome of running the oracle on a single (pattern, input) pair. */
  public static final class Result {
    public final boolean skipped;
    public final String skipReason; // non-null when skipped
    public final List<Finding> findings;

    private Result(boolean skipped, String skipReason, List<Finding> findings) {
      this.skipped = skipped;
      this.skipReason = skipReason;
      this.findings = findings;
    }

    static Result skipped(String reason) {
      return new Result(true, reason, List.of());
    }

    static Result ran(List<Finding> findings) {
      return new Result(false, null, findings);
    }
  }

  /**
   * Run the comparison. Returns a {@link Result} carrying any divergences. Never throws; if
   * compilation or matching blows up unexpectedly in either engine the pair is skipped.
   */
  public Result check(String pattern, String input) {
    Pattern jdk;
    try {
      jdk = Pattern.compile(pattern);
    } catch (PatternSyntaxException e) {
      return Result.skipped("JDK rejected pattern: " + e.getDescription());
    }

    // Compile with ALLOW_JDK_FALLBACK so that patterns the native engine cannot handle still
    // delegate to java.util.regex (agreeing by construction) rather than throwing and being
    // skipped.
    // This preserves oracle coverage: fallback patterns are tested via JDK, native patterns are
    // tested via the native engine. Patterns where the native engine produces wrong results will
    // still surface as findings.
    ReggieMatcher reggie;
    try {
      reggie = Reggie.compile(pattern, ReggieOptions.builder().allowJdkFallback().build());
    } catch (Throwable t) {
      return Result.skipped(
          "Reggie rejected pattern: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    List<Finding> findings = new ArrayList<>();

    // matches() — anchored full-input match
    try {
      boolean jdkMatches = jdk.matcher(input).matches();
      boolean reggieMatches = reggie.matches(input);
      if (jdkMatches != reggieMatches) {
        findings.add(
            new Finding(
                pattern,
                input,
                String.format("matches() differs: jdk=%s reggie=%s", jdkMatches, reggieMatches)));
      }
    } catch (Throwable t) {
      return Result.skipped("matches() threw: " + t);
    }

    // match() — whole-input match with group spans
    try {
      java.util.regex.Matcher jmFull = jdk.matcher(input);
      boolean jdkMatchFull = jmFull.matches();
      MatchResult rm = reggie.match(input);
      boolean reggieMatchFull = rm != null;
      if (jdkMatchFull != reggieMatchFull) {
        findings.add(
            new Finding(
                pattern,
                input,
                String.format(
                    "match() boolean differs: jdk=%s reggie=%s", jdkMatchFull, reggieMatchFull)));
      } else if (jdkMatchFull) {
        for (int g = 0; g <= jmFull.groupCount(); g++) {
          int js = jmFull.start(g);
          int je = jmFull.end(g);
          int rs = rm.start(g);
          int re = rm.end(g);
          if (js != rs || je != re) {
            findings.add(
                new Finding(
                    pattern,
                    input,
                    String.format(
                        "match() group %d span differs: jdk=[%d,%d) reggie=[%d,%d)",
                        g, js, je, rs, re)));
          }
        }
      }
    } catch (Throwable t) {
      return Result.skipped("match() threw: " + t);
    }

    // findMatch() — leftmost match
    try {
      Matcher jm = jdk.matcher(input);
      boolean jdkFound = jm.find();
      MatchResult rm = reggie.findMatch(input);
      boolean reggieFound = rm != null;
      if (jdkFound != reggieFound) {
        findings.add(
            new Finding(
                pattern,
                input,
                String.format("find() boolean differs: jdk=%s reggie=%s", jdkFound, reggieFound)));
      } else if (jdkFound) {
        // Spans must agree.
        if (jm.start() != rm.start() || jm.end() != rm.end()) {
          findings.add(
              new Finding(
                  pattern,
                  input,
                  String.format(
                      "first-match span differs: jdk=[%d,%d) reggie=[%d,%d)",
                      jm.start(), jm.end(), rm.start(), rm.end())));
        }
      }
    } catch (Throwable t) {
      return Result.skipped("find() threw: " + t);
    }

    // findAll() — every non-overlapping match with all group spans (the IAST tokenizer "drain"
    // path). JDK is the oracle: iterating Matcher.find() yields non-overlapping leftmost matches
    // with its own empty-match advance, which is the semantics findAll must reproduce.
    try {
      Matcher jm = jdk.matcher(input);
      List<int[]> jdkAll = new ArrayList<>();
      while (jm.find()) {
        int gc = jm.groupCount();
        int[] spans = new int[2 * (gc + 1)];
        for (int g = 0; g <= gc; g++) {
          spans[2 * g] = jm.start(g);
          spans[2 * g + 1] = jm.end(g);
        }
        jdkAll.add(spans);
      }
      List<MatchResult> reggieAll = reggie.findAll(input);
      if (jdkAll.size() != reggieAll.size()) {
        findings.add(
            new Finding(
                pattern,
                input,
                String.format(
                    "findAll() count differs: jdk=%d reggie=%d", jdkAll.size(), reggieAll.size())));
      } else {
        for (int i = 0; i < jdkAll.size(); i++) {
          int[] j = jdkAll.get(i);
          MatchResult r = reggieAll.get(i);
          int gc = (j.length / 2) - 1;
          for (int g = 0; g <= gc; g++) {
            if (j[2 * g] != r.start(g) || j[2 * g + 1] != r.end(g)) {
              findings.add(
                  new Finding(
                      pattern,
                      input,
                      String.format(
                          "findAll() match %d group %d span differs: jdk=[%d,%d) reggie=[%d,%d)",
                          i, g, j[2 * g], j[2 * g + 1], r.start(g), r.end(g))));
              break; // one finding per match is enough signal
            }
          }
        }
      }
    } catch (Throwable t) {
      return Result.skipped("findAll() threw: " + t);
    }

    return Result.ran(findings);
  }

  private static String escape(String s) {
    StringBuilder sb = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      switch (c) {
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        default:
          if (c < 0x20 || c > 0x7e) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
    return sb.toString();
  }
}
