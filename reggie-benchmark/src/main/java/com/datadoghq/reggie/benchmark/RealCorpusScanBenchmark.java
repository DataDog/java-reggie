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
package com.datadoghq.reggie.benchmark;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.benchmark.engines.RustRegexEngine;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Real-corpus scan benchmark: the 528 pattern literals actually used by logs-backend (513 from the
 * readiness survey, plus 15 synthetic give-back/anchor-in-branch/lookaround shapes guarding the
 * generated-NFA findFrom fix; committed as {@code corpus/logs-backend-patterns.tsv}) swept against
 * representative log lines. The jdk/reggie/rust lanes sweep their common served set; a fourth
 * engine lane (re2j) sweeps its own served subset of the corpus — RE2's syntax subset refuses
 * backrefs/lookarounds, so its served pattern/input counts are printed at setup and its per-pair
 * cost must be read against those counts, not the common-set totals.
 *
 * <p>This is the benchmark that reproduces the 2026-09-17 real-mix smoke test as a permanent lane:
 * per-pair timing is split into MATCH and NOMATCH sweeps, because the two workload shapes
 * (grok-style parsing vs rule-filter rejection) behave very differently — the no-match scan is
 * where the literal-prefilter gap lives, and it does not show up in match-only benchmarks.
 *
 * <p>Each benchmark method performs one full sweep of its pair list (a pair = pattern × input); the
 * reported time is per sweep, so engine comparisons are apples-to-apples over identical pairs.
 * Patterns are compiled in ALL engines before entering the sweeps (per-engine refusal counts are
 * printed at setup), so coverage differences are visible, not silently excluded.
 *
 * <p>The rust lane requires {@code ./gradlew :reggie-benchmark:buildRustEngine}; its methods fail
 * fast with a descriptive error when the native library is absent.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class RealCorpusScanBenchmark {

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

  // per common pattern: compiled engines + matched input indices
  private Pattern[] jdk;
  private ReggieMatcher[] reggie;
  private RustRegexEngine[] rust;
  private String[][] inputsForPattern; // [patternIdx][inputIdx] -> input
  private int[][] matchSweep; // (patternIdx, inputIdx) pairs, matched
  private int[][] noMatchSweep; // (patternIdx, inputIdx) pairs, unmatched

  // re2j sweeps over its own served subset of the corpus (RE2 syntax is a subset: backrefs,
  // lookarounds etc. are refused). The jdk/reggie/rust lanes above keep their common set, so
  // historical baselines stay comparable; the re2j lanes are read per-pair using the served
  // pattern/input counts printed at setup.
  private com.google.re2j.Pattern[] re2j;
  private String[][] re2jInputsForPattern;
  private int[][] re2jMatchSweep;
  private int[][] re2jNoMatchSweep;
  private int re2jServedMatchPairs;
  private int re2jServedNoMatchPairs;

  @Setup
  public void setup() throws Exception {
    List<String> patterns = new ArrayList<>();
    try (InputStream in =
        RealCorpusScanBenchmark.class.getResourceAsStream("/corpus/logs-backend-patterns.tsv")) {
      if (in == null) {
        throw new IllegalStateException("corpus/logs-backend-patterns.tsv missing from resources");
      }
      BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      for (String line; (line = r.readLine()) != null; ) {
        int tab = line.indexOf('\t');
        if (tab > 0) {
          patterns.add(unescape(line.substring(0, tab)));
        }
      }
    }

    List<Pattern> jdkList = new ArrayList<>();
    List<ReggieMatcher> reggieList = new ArrayList<>();
    List<RustRegexEngine> rustList = new ArrayList<>();
    List<String[]> inputList = new ArrayList<>();
    List<boolean[]> boolList = new ArrayList<>();
    List<int[]> matchSweepList = new ArrayList<>();
    List<int[]> noMatchSweepList = new ArrayList<>();
    int jdkRefused = 0, reggieRefused = 0, rustRefused = 0;

    for (String pattern : patterns) {
      Pattern jp;
      try {
        jp = Pattern.compile(pattern);
      } catch (Exception e) {
        jdkRefused++;
        continue;
      }
      ReggieMatcher rp;
      try {
        rp = (ReggieMatcher) Reggie.compile(pattern);
      } catch (Exception e) {
        reggieRefused++;
        continue;
      }
      RustRegexEngine up;
      try {
        up = RustRegexEngine.compile(pattern);
      } catch (RustRegexEngine.UnavailableException e) {
        throw e; // no rust library: fail the setup with the build hint
      } catch (RustRegexEngine.PatternUnsupportedException e) {
        rustRefused++;
        continue;
      }

      String[] ins = new String[INPUTS.length];
      boolean[] bools = new boolean[INPUTS.length];
      int[] matchPairs = new int[INPUTS.length];
      int[] noMatchPairs = new int[INPUTS.length];
      int nMatch = 0, nNoMatch = 0;
      for (int i = 0; i < INPUTS.length; i++) {
        ins[i] = INPUTS[i];
        bools[i] = jp.matcher(INPUTS[i]).find();
        if (bools[i]) {
          matchPairs[nMatch++] = i;
        } else {
          noMatchPairs[nNoMatch++] = i;
        }
      }
      int idx = jdkList.size();
      jdkList.add(jp);
      reggieList.add(rp);
      rustList.add(up);
      inputList.add(ins);
      boolList.add(bools);
      matchSweepList.add(java.util.Arrays.copyOf(matchPairs, nMatch));
      noMatchSweepList.add(java.util.Arrays.copyOf(noMatchPairs, nNoMatch));
    }

    jdk = jdkList.toArray(new Pattern[0]);
    reggie = reggieList.toArray(new ReggieMatcher[0]);
    rust = rustList.toArray(new RustRegexEngine[0]);
    inputsForPattern = inputList.toArray(new String[0][]);
    matchSweep = matchSweepList.toArray(new int[0][]);
    noMatchSweep = noMatchSweepList.toArray(new int[0][]);

    System.out.printf(
        "corpus: %d patterns loaded, %d common across all engines "
            + "(refusals: jdk=%d reggie=%d rust=%d)%n",
        patterns.size(), jdk.length, jdkRefused, reggieRefused, rustRefused);

    // re2j lane: own served subset of the full corpus (independent of the common set),
    // classified by the JDK oracle so its sweeps measure the same matched/no-match shapes.
    List<com.google.re2j.Pattern> re2jList = new ArrayList<>();
    List<String[]> re2jInputList = new ArrayList<>();
    List<int[]> re2jMatchList = new ArrayList<>();
    List<int[]> re2jNoMatchList = new ArrayList<>();
    int re2jRefused = 0;
    for (String pattern : patterns) {
      Pattern jp;
      try {
        jp = Pattern.compile(pattern);
      } catch (Exception e) {
        continue; // not classifiable by the oracle
      }
      com.google.re2j.Pattern rp;
      try {
        rp = com.google.re2j.Pattern.compile(pattern);
      } catch (Exception e) {
        re2jRefused++;
        continue;
      }
      int[] matchPairs = new int[INPUTS.length];
      int[] noMatchPairs = new int[INPUTS.length];
      int nMatch = 0, nNoMatch = 0;
      for (int i = 0; i < INPUTS.length; i++) {
        if (jp.matcher(INPUTS[i]).find()) {
          matchPairs[nMatch++] = i;
        } else {
          noMatchPairs[nNoMatch++] = i;
        }
      }
      re2jServedMatchPairs += nMatch;
      re2jServedNoMatchPairs += nNoMatch;
      re2jList.add(rp);
      re2jInputList.add(INPUTS);
      re2jMatchList.add(java.util.Arrays.copyOf(matchPairs, nMatch));
      re2jNoMatchList.add(java.util.Arrays.copyOf(noMatchPairs, nNoMatch));
    }
    re2j = re2jList.toArray(new com.google.re2j.Pattern[0]);
    re2jInputsForPattern = re2jInputList.toArray(new String[0][]);
    re2jMatchSweep = re2jMatchList.toArray(new int[0][]);
    re2jNoMatchSweep = re2jNoMatchList.toArray(new int[0][]);
    System.out.printf(
        "re2j lane: %d/%d patterns served (refused %d) — %d matched pairs, %d no-match pairs%n",
        re2j.length, patterns.size(), re2jRefused, re2jServedMatchPairs, re2jServedNoMatchPairs);
  }

  @TearDown
  public void tearDown() {
    if (rust != null) {
      for (RustRegexEngine e : rust) {
        if (e != null) {
          e.close();
        }
      }
    }
  }

  // ===== matched sweeps (grok-parsing shape: the pattern fires) =====

  @Benchmark
  public boolean jdkSweepMatched() {
    boolean acc = false;
    for (int p = 0; p < jdk.length; p++) {
      int[] inputsIdx = matchSweep[p];
      for (int i : inputsIdx) {
        acc ^= jdk[p].matcher(inputsForPattern[p][i]).find();
      }
    }
    return acc;
  }

  @Benchmark
  public boolean reggieSweepMatched() {
    boolean acc = false;
    for (int p = 0; p < reggie.length; p++) {
      int[] inputsIdx = matchSweep[p];
      for (int i : inputsIdx) {
        acc ^= reggie[p].find(inputsForPattern[p][i]);
      }
    }
    return acc;
  }

  @Benchmark
  public boolean rustSweepMatched() {
    boolean acc = false;
    for (int p = 0; p < rust.length; p++) {
      int[] inputsIdx = matchSweep[p];
      for (int i : inputsIdx) {
        acc ^= rust[p].isMatch(inputsForPattern[p][i]);
      }
    }
    return acc;
  }

  // ===== no-match sweeps (rule-filtering shape: reject the input) =====

  @Benchmark
  public boolean jdkSweepNoMatch() {
    boolean acc = false;
    for (int p = 0; p < jdk.length; p++) {
      int[] inputsIdx = noMatchSweep[p];
      for (int i : inputsIdx) {
        acc ^= jdk[p].matcher(inputsForPattern[p][i]).find();
      }
    }
    return acc;
  }

  @Benchmark
  public boolean reggieSweepNoMatch() {
    boolean acc = false;
    for (int p = 0; p < reggie.length; p++) {
      int[] inputsIdx = noMatchSweep[p];
      for (int i : inputsIdx) {
        acc ^= reggie[p].find(inputsForPattern[p][i]);
      }
    }
    return acc;
  }

  @Benchmark
  public boolean rustSweepNoMatch() {
    boolean acc = false;
    for (int p = 0; p < rust.length; p++) {
      int[] inputsIdx = noMatchSweep[p];
      for (int i : inputsIdx) {
        acc ^= rust[p].isMatch(inputsForPattern[p][i]);
      }
    }
    return acc;
  }

  // ===== re2j sweeps (own served subset; per-pair cost = us/op over the printed pair counts) =====

  @Benchmark
  public boolean re2jSweepMatched() {
    boolean acc = false;
    for (int p = 0; p < re2j.length; p++) {
      int[] inputsIdx = re2jMatchSweep[p];
      for (int i : inputsIdx) {
        acc ^= re2j[p].matcher(re2jInputsForPattern[p][i]).find();
      }
    }
    return acc;
  }

  @Benchmark
  public boolean re2jSweepNoMatch() {
    boolean acc = false;
    for (int p = 0; p < re2j.length; p++) {
      int[] inputsIdx = re2jNoMatchSweep[p];
      for (int i : inputsIdx) {
        acc ^= re2j[p].matcher(re2jInputsForPattern[p][i]).find();
      }
    }
    return acc;
  }

  /** Undoes the TSV escaping used when the corpus was exported (\t, \n, \r, \\). */
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
