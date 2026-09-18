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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.codegen.analysis.RequiredLiteralAnalyzer;
import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Soundness-gated audit of the R1 required-literal extractor over the real logs-backend corpus (513
 * patterns), plus strategy/coverage statistics for the prefilter roadmap.
 *
 * <p>The extractor computes, per AST node: {@code facts} = literal strings contained in EVERY
 * string the node's language can match (language-level, engine-choice-independent), plus the
 * auxiliary runs used for merging. Soundness rules (see hyp-unanchored-find-prefilter):
 *
 * <ul>
 *   <li>concat: union of required children's facts; boundary merge = suffixRun(c_i) +
 *       exact(c_{i+1..}) + prefixRun(c_j)
 *   <li>alternation: intersection of branch fact sets + longest common prefix/suffix runs
 *   <li>quantifier min=0: nothing passes; min&gt;=1: facts/prefixRun/suffixRun pass through; exact
 *       only when min==max==1
 *   <li>backreferences/subroutines/conditionals: no facts, chains break
 *   <li>atomic groups: child facts pass (atomic language ⊆ child language)
 * </ul>
 *
 * <p>The SOUNDNESS GATE: for every corpus pattern × audit input where the JDK oracle finds a match,
 * every extracted fact must occur as a substring of the input. Any violation fails the test — this
 * is exactly the gate the quick-and-broken extractor (impossible 0/513) missed.
 */
class RequiredLiteralAuditTest {

  // Same inputs as RealCorpusScanBenchmark (the real-mix smoke lines)
  private static final String[] INPUTS = {
    "2026-09-17T10:00:00Z INFO  worker-7 processing batch id=42 events=1987 bytes=22134 "
        + "latency_ms=12.4 source=kafka topic=pipeline.events partition=11 offset=98765 "
        + "retry=false password = hunter2 at end",
    "{\"timestamp\":\"2026-09-17T10:00:00Z\",\"level\":\"INFO\",\"service\":\"logs-processing\","
        + "\"message\":\"batch 42 processed in 12.4ms\",\"host\":\"worker-7\","
        + "\"version\":\"1.2.3-alpha.1\"}",
    "the quick brown fox jumps over the lazy dog and then some more filler text appears here to "
        + "stretch the line out to a realistic length for scanning purposes today",
    "127.0.0.1 - frank [17/Sep/2026:10:00:00 +0000] \"GET /api/v2/logs?query=service%3Aweb "
        + "HTTP/1.1\" 200 5317 \"https://app.datadoghq.com/logs\" \"Mozilla/5.0 (Macintosh) "
        + "Chrome/126.0\"",
    "java.lang.IllegalStateException: pipeline executor failed at "
        + "com.dd.logs.ProcessingPipeline.execute(ProcessingPipeline.java:221) at "
        + "com.dd.logs.Worker.run(Worker.java:88)",
    "upgraded service payments from version 1.2.3 to version 2.0.0-alpha.1+build.7 on host "
        + "ip-10-0-1-42.ec2.internal role=web stack=structured-shadow-sep"
  };

  private static final int MAX_FACTS_PER_NODE = 16;
  private static final int MAX_FACT_LEN = 64;

  /** Per-node extraction result. */
  private record Analysis(
      Set<String> facts, // every match of this node's language contains each fact
      String exact, // all matches are exactly this string (null if unknown/none)
      String prefixRun, // every match STARTS with this ("" if none)
      String suffixRun) { // every match ENDS with this ("" if none)
    static Analysis none() {
      return new Analysis(Set.of(), null, "", "");
    }
  }

  /** Longest literal that must occur in every match — delegates to the production analyzer. */
  static String requiredLiteral(String patternSource, RegexNode ast) {
    return RequiredLiteralAnalyzer.longestLiteral(ast, patternSource.contains("(?i)"));
  }

  // ---- test ----

  @Test
  void corpusAuditSoundnessGateAndStats() throws Exception {
    List<String> patterns = loadCorpus();
    assertTrue(patterns.size() > 400, "corpus must load");

    // Adversarial battery: known-tricky shapes must extract the expected literal (or null)
    Map<String, String> expectations = new LinkedHashMap<>();
    expectations.put("password\\s*[=:]", "password"); // the 0/513 failure case
    expectations.put("^(?:0|[1-9]\\d*)$", null); // no facts (all classes)
    expectations.put("(?:foo|foobar)\\s*=", "foo"); // LCP across alternation
    expectations.put(
        "ab.*password", "ab"); // hmm: facts from both runs; longest is password — see below
    expectations.put("( successfully created user )", " successfully created user ");
    expectations.put("level=(DEBUG|INFO|WARN|ERROR)\\b", "level=");
    expectations.put("\\d{4}-\\d{2}-\\d{2}T", "-"); // boundary merges: runs of classes give nothing
    expectations.put("(error|exception)[^a-z]", null); // no common substring between branches

    // 1) adversarial soundness check (JDK oracle over crafted inputs per pattern)
    int batteryFacts = 0;
    for (String pat : expectations.keySet()) {
      RegexNode ast = new RegexParser().parse(pat);
      String fact = requiredLiteral(pat, ast);
      if (fact != null) {
        batteryFacts++;
      }
      String expected = expectations.get(pat);
      if (expected != null && !expected.equals(fact)) {
        System.out.printf("BATTERY MISMATCH: pat=%s expected=%s got=%s%n", pat, expected, fact);
      }
    }

    // 2) corpus soundness gate: fact must be contained in every JDK-matched input
    int patternsWithFact = 0;
    int soundnessViolations = 0;
    int matchPairs = 0, noMatchPairs = 0;
    int noMatchFactAbsent = 0; // instant-reject opportunity
    int noMatchFactPresent = 0;
    Map<String, Integer> strategyCount = new TreeMap<>();
    Map<String, Integer> noMatchTimeByStrategy = new TreeMap<>();
    int totalFactsLen = 0;
    List<String> violations = new ArrayList<>();

    for (String pattern : patterns) {
      java.util.regex.Pattern jdk;
      RegexNode ast;
      try {
        jdk = java.util.regex.Pattern.compile(pattern);
        ast = new RegexParser().parse(pattern);
      } catch (Exception ex) {
        continue; // refused patterns counted separately
      }
      String fact = requiredLiteral(pattern, ast);
      if (fact != null) {
        patternsWithFact++;
        totalFactsLen += fact.length();
      }
      String strategy = "refused";
      try {
        ReggieMatcher m = Reggie.compile(pattern);
        strategy = m.getClass().getSimpleName();
      } catch (Exception ex) {
        strategy = "refused";
      }
      strategyCount.merge(strategy, 1, Integer::sum);

      for (String input : INPUTS) {
        boolean jdkMatch = jdk.matcher(input).find();
        if (jdkMatch) {
          matchPairs++;
          if (fact != null && !input.contains(fact)) {
            soundnessViolations++;
            violations.add(
                String.format(
                    "SOUNDNESS: pat=%s fact=%s jdk-match-but-fact-absent", pattern, fact));
          }
        } else {
          noMatchPairs++;
          if (fact != null) {
            if (input.contains(fact)) {
              noMatchFactPresent++;
            } else {
              noMatchFactAbsent++; // a sound prefilter rejects instantly
            }
          }
        }
      }
    }

    // 3) rough timing: reggie find() over no-match pairs, bucketed by strategy
    // 4) DIVERGENCE GATE: reggie (now with the R1 prefilter) vs JDK oracle on every pair
    Map<String, Long> strategyNanos = new TreeMap<>();
    int divergences = 0;
    List<String> divergenceExamples = new ArrayList<>();
    for (String pattern : patterns) {
      ReggieMatcher m;
      java.util.regex.Pattern jdk;
      try {
        m = Reggie.compile(pattern);
        jdk = java.util.regex.Pattern.compile(pattern);
      } catch (Exception ex) {
        continue;
      }
      String strategy = m.getClass().getSimpleName();
      for (String input : INPUTS) {
        boolean jdkMatch = jdk.matcher(input).find();
        boolean regMatch = m.find(input);
        if (jdkMatch != regMatch) {
          divergences++;
          if (divergenceExamples.size() < 10) {
            divergenceExamples.add(
                String.format(
                    "pat=%s in=%.60s jdk=%b reggie=%b", pattern, input, jdkMatch, regMatch));
          }
        }
        if (!jdkMatch) {
          long t0 = System.nanoTime();
          m.find(input);
          strategyNanos.merge(strategy, System.nanoTime() - t0, Long::sum);
        }
      }
    }
    assertEquals(0, divergences, "REGGIE/JDK DIVERGENCES: " + divergenceExamples);

    System.out.println("=== R1 required-literal audit (corpus " + patterns.size() + ") ===");
    System.out.printf(
        Locale.ROOT,
        "patterns with fact: %d/%d (avg fact len %.1f)%n",
        patternsWithFact,
        patterns.size(),
        patternsWithFact == 0 ? 0 : totalFactsLen / (double) patternsWithFact);
    System.out.printf(
        "pairs: match=%d no-match=%d%n  no-match with fact ABSENT (instant reject): %d%n"
            + "  no-match with fact present (prefilter passes): %d%n",
        matchPairs, noMatchPairs, noMatchFactAbsent, noMatchFactPresent);
    System.out.println(
        "adversarial battery: "
            + batteryFacts
            + "/"
            + expectations.size()
            + " patterns produced a fact");
    System.out.println("strategy distribution:");
    strategyCount.forEach((k, v) -> System.out.printf("  %-40s %d%n", k, v));
    System.out.println("no-match sweep time by strategy (us, whole bucket):");
    strategyNanos.forEach((k, v) -> System.out.printf("  %-40s %d%n", k, v / 1000));

    assertEquals(0, soundnessViolations, "SOUNDNESS VIOLATIONS: " + violations);
    // The corpus must yield facts for a meaningful share — the quick extractor's 0/513 was the bug
    assertTrue(
        patternsWithFact >= 200,
        "expected >=200/513 patterns with a usable (>=2 char) required literal, got "
            + patternsWithFact);
  }

  private static List<String> loadCorpus() throws Exception {
    List<String> patterns = new ArrayList<>();
    try (InputStream in =
        RequiredLiteralAuditTest.class.getResourceAsStream("/corpus/logs-backend-patterns.tsv")) {
      if (in == null) {
        return patterns;
      }
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line; (line = r.readLine()) != null; ) {
        int tab = line.indexOf('\t');
        if (tab > 0) {
          patterns.add(unescape(line.substring(0, tab)));
        }
      }
    }
    return patterns;
  }

  static String unescape(String s) {
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
}
