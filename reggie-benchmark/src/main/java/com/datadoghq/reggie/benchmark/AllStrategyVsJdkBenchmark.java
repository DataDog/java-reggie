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
import com.datadoghq.reggie.ReggieMatcher;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Comprehensive Reggie-vs-JDK throughput benchmark covering all supported matching strategies.
 *
 * <p>Each strategy is represented by a canonical pattern taken from {@code
 * StrategyCorrectnessMetaTest}. Every pattern/strategy pair is measured at three input scales via
 * {@code @Param("scale")} — SHORT (the original single-shot input this benchmark used to report on
 * its own), MEDIUM, and LONG — instead of a single point estimate. A single tiny input conflates
 * "how good is this strategy" with "how much does fixed per-call overhead dominate this one
 * specific input" (e.g. {@code (a)?b} on {@code "xaby"} is the smallest possible trigger for {@code
 * BITSTATE_CAPTURE}, which maximizes the visibility of its bounded-backtracking bookkeeping
 * relative to actual work done). Reporting the ratio at three scales instead lets a reader tell a
 * fixed-cost artifact (ratio climbs toward/past 1x as input grows) apart from a genuine scaling
 * problem (ratio stays flat or worsens).
 *
 * <p>Two scaling strategies are used, chosen per-pattern to match what actually varies:
 *
 * <ul>
 *   <li><b>Matched-region growth</b> — for patterns whose match itself can be arbitrarily long
 *       ({@code \w+}, {@code (\d+)}, {@code a(b*)}, {@code a.*b.*c.*d.*e.*f}, {@code (a)?b}-shaped
 *       patterns, {@code (?:abc){N}}, {@code (?:a+b+|b+a+){N}}): MEDIUM/LONG grow the matched span
 *       itself (or, for the two optional-group cases, the run of near-miss characters that force
 *       repeated failed attempts before the eventual match).
 *   <li><b>Scan-prefix growth</b> — for fixed-width matches ({@code \d{3}-\d{3}-\d{4}}, the IPv4
 *       pattern, {@code [ab]c}, {@code (fo|foo)}, the DFA_SWITCH_WITH_GROUPS single-char
 *       alternation chain, {@code (foo)(bar)}): MEDIUM/LONG prepend non-matching filler before the
 *       match, stressing {@code find()}'s scan/prefilter cost rather than the match itself (which
 *       cannot grow — its width is fixed by the pattern).
 * </ul>
 *
 * <p>Patterns are compiled once per trial in {@link #setup()} and never inside a {@code @Benchmark}
 * method, so compilation cost is excluded from measurements.
 *
 * <p>Strategies covered:
 *
 * <ul>
 *   <li>STATELESS_LOOP: {@code \w+}
 *   <li>SPECIALIZED_GREEDY_CHARCLASS: {@code (\d+)}
 *   <li>SPECIALIZED_MULTI_GROUP_GREEDY: {@code ([a-z]+)@([a-z]+)\.com}
 *   <li>SPECIALIZED_CONCAT_GREEDY_GROUP: {@code a(b*)}
 *   <li>SPECIALIZED_FIXED_SEQUENCE: {@code \d{3}-\d{3}-\d{4}}
 *   <li>SPECIALIZED_BOUNDED_QUANTIFIERS: {@code \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}}
 *   <li>DFA_UNROLLED: {@code [ab]c}
 *   <li>DFA_UNROLLED_WITH_GROUPS: {@code (fo|foo)}
 *   <li>DFA_SWITCH: {@code a.*b.*c.*d.*e.*f}
 *   <li>DFA_SWITCH_WITH_GROUPS: {@code (a|b|c|d|e|f|g)(h|i|j|k|l|m)(n|o|p|q|r)(s|t|u|v)}
 *   <li>DFA_TABLE: {@code (?:abc){N}}
 *   <li>OPTIMIZED_NFA: {@code (?<x>a)?b}
 *   <li>LAZY_DFA: {@code (?:a+b+|b+a+){N}}
 *   <li>PIKEVM_CAPTURE: {@code (a)?b}
 *   <li>ONEPASS_NFA: {@code (foo)(bar)}
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class AllStrategyVsJdkBenchmark {

  @Param({"SHORT", "MEDIUM", "LONG"})
  public String scale;

  private static String pick(String scale, String shortV, String mediumV, String longV) {
    switch (scale) {
      case "SHORT":
        return shortV;
      case "MEDIUM":
        return mediumV;
      default:
        return longV;
    }
  }

  private static int pick(String scale, int shortV, int mediumV, int longV) {
    switch (scale) {
      case "SHORT":
        return shortV;
      case "MEDIUM":
        return mediumV;
      default:
        return longV;
    }
  }

  // ── STATELESS_LOOP ────────────────────────────────────────────────────────────
  // Pattern: \w+   Matched-region growth.
  private static final String STATELESS_LOOP_PAT = "\\w+";

  private String statelessLoopIn;
  private ReggieMatcher reggieStatelessLoop;
  private Pattern jdkStatelessLoop;

  // ── SPECIALIZED_GREEDY_CHARCLASS ──────────────────────────────────────────────
  // Pattern: (\d+)   Matched-region growth.
  private static final String GREEDY_CHARCLASS_PAT = "(\\d+)";

  private String greedyCharclassIn;
  private ReggieMatcher reggieGreedyCharclass;
  private Pattern jdkGreedyCharclass;

  // ── SPECIALIZED_MULTI_GROUP_GREEDY ────────────────────────────────────────────
  // Pattern: ([a-z]+)@([a-z]+)\.com   Matched-region growth.
  private static final String MULTI_GROUP_PAT = "([a-z]+)@([a-z]+)\\.com";

  private String multiGroupIn;
  private ReggieMatcher reggieMultiGroup;
  private Pattern jdkMultiGroup;

  // ── SPECIALIZED_CONCAT_GREEDY_GROUP ──────────────────────────────────────────
  // Pattern: a(b*)   Matched-region growth.
  private static final String CONCAT_GREEDY_PAT = "a(b*)";

  private String concatGreedyIn;
  private ReggieMatcher reggieConcatGreedy;
  private Pattern jdkConcatGreedy;

  // ── SPECIALIZED_FIXED_SEQUENCE ────────────────────────────────────────────────
  // Pattern: \d{3}-\d{3}-\d{4}   Scan-prefix growth (fixed-width match).
  private static final String FIXED_SEQ_PAT = "\\d{3}-\\d{3}-\\d{4}";

  private String fixedSeqIn;
  private ReggieMatcher reggieFixedSeq;
  private Pattern jdkFixedSeq;

  // ── SPECIALIZED_BOUNDED_QUANTIFIERS ──────────────────────────────────────────
  // Pattern: \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}   Scan-prefix growth (fixed-width match).
  private static final String BOUNDED_QUANT_PAT = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}";

  private String boundedQuantIn;
  private ReggieMatcher reggieBoundedQuant;
  private Pattern jdkBoundedQuant;

  // ── DFA_UNROLLED ──────────────────────────────────────────────────────────────
  // Pattern: [ab]c   Scan-prefix growth (fixed-width match).
  private static final String DFA_UNROLLED_PAT = "[ab]c";

  private String dfaUnrolledIn;
  private ReggieMatcher reggieDfaUnrolled;
  private Pattern jdkDfaUnrolled;

  // ── DFA_UNROLLED_WITH_GROUPS ──────────────────────────────────────────────────
  // Pattern: (fo|foo)   Scan-prefix growth (fixed-width match).
  private static final String DFA_UNROLLED_GROUPS_PAT = "(fo|foo)";

  private String dfaUnrolledGroupsIn;
  private ReggieMatcher reggieDfaUnrolledGroups;
  private Pattern jdkDfaUnrolledGroups;

  // ── DFA_SWITCH ────────────────────────────────────────────────────────────────
  // Pattern: a.*b.*c.*d.*e.*f   Matched-region growth (widens each .* gap).
  private static final String DFA_SWITCH_PAT = "a.*b.*c.*d.*e.*f";

  private String dfaSwitchIn;
  private ReggieMatcher reggieDfaSwitch;
  private Pattern jdkDfaSwitch;

  // ── DFA_SWITCH_WITH_GROUPS ────────────────────────────────────────────────────
  // Pattern: (a|b|c|d|e|f|g)(h|i|j|k|l|m)(n|o|p|q|r)(s|t|u|v)
  // Scan-prefix growth (fixed-width match).
  private static final String DFA_SWITCH_GROUPS_PAT =
      "(a|b|c|d|e|f|g)(h|i|j|k|l|m)(n|o|p|q|r)(s|t|u|v)";

  private String dfaSwitchGroupsIn;
  private ReggieMatcher reggieDfaSwitchGroups;
  private Pattern jdkDfaSwitchGroups;

  // ── DFA_TABLE ─────────────────────────────────────────────────────────────────
  // Pattern: (?:abc){N}   Matched-region growth (grows N, the repetition bound itself).
  private String dfaTablePat;
  private String dfaTableIn;
  private ReggieMatcher reggieDfaTable;
  private Pattern jdkDfaTable;

  // ── OPTIMIZED_NFA ─────────────────────────────────────────────────────────────
  // Pattern: (?<x>a)?b   Matched-region growth (grows the near-miss 'a' run before the match,
  // forcing repeated failed optional-group attempts).
  private static final String OPTIMIZED_NFA_PAT = "(?<x>a)?b";

  private String optimizedNfaIn;
  private ReggieMatcher reggieOptimizedNfa;
  private Pattern jdkOptimizedNfa;

  // ── LAZY_DFA ──────────────────────────────────────────────────────────────────
  // Pattern: (?:a+b+|b+a+){N}   Matched-region growth (grows N, the repetition bound itself).
  private String lazyDfaPat;
  private String lazyDfaIn;
  private ReggieMatcher reggieLazyDfa;
  private Pattern jdkLazyDfa;

  // ── PIKEVM_CAPTURE ────────────────────────────────────────────────────────────
  // Pattern: (a)?b   Matched-region growth, same rationale as OPTIMIZED_NFA above.
  // Note: routes to OPTIMIZED_NFA at runtime (capture-ambiguous interception).
  private static final String PIKEVM_CAPTURE_PAT = "(a)?b";

  private String pikevmCaptureIn;
  private ReggieMatcher reggiePikevmCapture;
  private Pattern jdkPikevmCapture;

  // ── ONEPASS_NFA ───────────────────────────────────────────────────────────────
  // Pattern: (foo)(bar)   Scan-prefix growth (fixed-width match).
  private static final String ONEPASS_NFA_PAT = "(foo)(bar)";

  private String onepassNfaIn;
  private ReggieMatcher reggieOnepassNfa;
  private Pattern jdkOnepassNfa;

  @Setup(Level.Trial)
  public void setup() {
    statelessLoopIn =
        pick(
            scale, "  abc  ", "  " + "word_".repeat(20) + "  ", "  " + "word_".repeat(2000) + "  ");
    reggieStatelessLoop = Reggie.compile(STATELESS_LOOP_PAT);
    jdkStatelessLoop = Pattern.compile(STATELESS_LOOP_PAT);

    greedyCharclassIn =
        pick(
            scale,
            "abc123def",
            "abc" + "0123456789".repeat(20) + "def",
            "abc" + "0123456789".repeat(2000) + "def");
    reggieGreedyCharclass = Reggie.compile(GREEDY_CHARCLASS_PAT);
    jdkGreedyCharclass = Pattern.compile(GREEDY_CHARCLASS_PAT);

    multiGroupIn =
        pick(
            scale,
            "x foo@bar.com y",
            "x " + "a".repeat(30) + "@" + "b".repeat(30) + ".com y",
            "x " + "a".repeat(1000) + "@" + "b".repeat(1000) + ".com y");
    reggieMultiGroup = Reggie.compile(MULTI_GROUP_PAT);
    jdkMultiGroup = Pattern.compile(MULTI_GROUP_PAT);

    concatGreedyIn =
        pick(scale, "xabbby", "xa" + "b".repeat(50) + "y", "xa" + "b".repeat(2000) + "y");
    reggieConcatGreedy = Reggie.compile(CONCAT_GREEDY_PAT);
    jdkConcatGreedy = Pattern.compile(CONCAT_GREEDY_PAT);

    fixedSeqIn =
        pick(
            scale,
            "call 123-456-7890 now",
            "z".repeat(200) + " 123-456-7890 now",
            "z".repeat(5000) + " 123-456-7890 now");
    reggieFixedSeq = Reggie.compile(FIXED_SEQ_PAT);
    jdkFixedSeq = Pattern.compile(FIXED_SEQ_PAT);

    boundedQuantIn =
        pick(
            scale,
            "ip=192.168.0.1!",
            "z".repeat(200) + "192.168.0.1!",
            "z".repeat(5000) + "192.168.0.1!");
    reggieBoundedQuant = Reggie.compile(BOUNDED_QUANT_PAT);
    jdkBoundedQuant = Pattern.compile(BOUNDED_QUANT_PAT);

    dfaUnrolledIn = pick(scale, "x bc y", "z".repeat(200) + " bc y", "z".repeat(5000) + " bc y");
    reggieDfaUnrolled = Reggie.compile(DFA_UNROLLED_PAT);
    jdkDfaUnrolled = Pattern.compile(DFA_UNROLLED_PAT);

    dfaUnrolledGroupsIn = pick(scale, "xfooy", "z".repeat(200) + "fooy", "z".repeat(5000) + "fooy");
    reggieDfaUnrolledGroups = Reggie.compile(DFA_UNROLLED_GROUPS_PAT);
    jdkDfaUnrolledGroups = Pattern.compile(DFA_UNROLLED_GROUPS_PAT);

    int switchGap = pick(scale, 1, 20, 500);
    dfaSwitchIn =
        "x a"
            + "1".repeat(switchGap)
            + "b"
            + "1".repeat(switchGap)
            + "c"
            + "1".repeat(switchGap)
            + "d"
            + "1".repeat(switchGap)
            + "e"
            + "1".repeat(switchGap)
            + "f y";
    reggieDfaSwitch = Reggie.compile(DFA_SWITCH_PAT);
    jdkDfaSwitch = Pattern.compile(DFA_SWITCH_PAT);

    dfaSwitchGroupsIn =
        pick(scale, "x bios y", "z".repeat(200) + " bios y", "z".repeat(5000) + " bios y");
    reggieDfaSwitchGroups = Reggie.compile(DFA_SWITCH_GROUPS_PAT);
    jdkDfaSwitchGroups = Pattern.compile(DFA_SWITCH_GROUPS_PAT);

    int dfaTableReps = pick(scale, 100, 200, 300);
    dfaTablePat = "(?:abc){" + dfaTableReps + "}";
    dfaTableIn = "x" + "abc".repeat(dfaTableReps) + "y";
    reggieDfaTable = Reggie.compile(dfaTablePat);
    jdkDfaTable = Pattern.compile(dfaTablePat);

    optimizedNfaIn = pick(scale, "xaby", "a".repeat(200) + "b", "a".repeat(5000) + "b");
    reggieOptimizedNfa = Reggie.compile(OPTIMIZED_NFA_PAT);
    jdkOptimizedNfa = Pattern.compile(OPTIMIZED_NFA_PAT);

    // Fixed at 75 reps: the unrolled LAZY_DFA codegen is already near the JVM's 64KB
    // per-method bytecode limit at this rep count, so this strategy scales via scan-prefix
    // noise instead of growing the repetition bound.
    lazyDfaPat = "(?:a+b+|b+a+){75}";
    lazyDfaIn =
        pick(
            scale,
            "x" + "ab".repeat(75) + "y",
            "z".repeat(200) + "x" + "ab".repeat(75) + "y",
            "z".repeat(5000) + "x" + "ab".repeat(75) + "y");
    reggieLazyDfa = Reggie.compile(lazyDfaPat);
    jdkLazyDfa = Pattern.compile(lazyDfaPat);

    pikevmCaptureIn = pick(scale, "xaby", "a".repeat(200) + "b", "a".repeat(5000) + "b");
    reggiePikevmCapture = Reggie.compile(PIKEVM_CAPTURE_PAT);
    jdkPikevmCapture = Pattern.compile(PIKEVM_CAPTURE_PAT);

    onepassNfaIn =
        pick(scale, "x foobar y", "z".repeat(200) + " foobar y", "z".repeat(5000) + " foobar y");
    reggieOnepassNfa = Reggie.compile(ONEPASS_NFA_PAT);
    jdkOnepassNfa = Pattern.compile(ONEPASS_NFA_PAT);
  }

  // ── STATELESS_LOOP benchmarks ────────────────────────────────────────────────

  @Benchmark
  public boolean statelessLoop_reggie() {
    return reggieStatelessLoop.find(statelessLoopIn);
  }

  @Benchmark
  public boolean statelessLoop_jdk() {
    return jdkStatelessLoop.matcher(statelessLoopIn).find();
  }

  // ── SPECIALIZED_GREEDY_CHARCLASS benchmarks ──────────────────────────────────

  @Benchmark
  public boolean specializedGreedyCharclass_reggie() {
    return reggieGreedyCharclass.find(greedyCharclassIn);
  }

  @Benchmark
  public boolean specializedGreedyCharclass_jdk() {
    return jdkGreedyCharclass.matcher(greedyCharclassIn).find();
  }

  // ── SPECIALIZED_MULTI_GROUP_GREEDY benchmarks ────────────────────────────────

  @Benchmark
  public boolean specializedMultiGroupGreedy_reggie() {
    return reggieMultiGroup.find(multiGroupIn);
  }

  @Benchmark
  public boolean specializedMultiGroupGreedy_jdk() {
    return jdkMultiGroup.matcher(multiGroupIn).find();
  }

  // ── SPECIALIZED_CONCAT_GREEDY_GROUP benchmarks ───────────────────────────────

  @Benchmark
  public boolean specializedConcatGreedyGroup_reggie() {
    return reggieConcatGreedy.find(concatGreedyIn);
  }

  @Benchmark
  public boolean specializedConcatGreedyGroup_jdk() {
    return jdkConcatGreedy.matcher(concatGreedyIn).find();
  }

  // ── SPECIALIZED_FIXED_SEQUENCE benchmarks ────────────────────────────────────

  @Benchmark
  public boolean specializedFixedSequence_reggie() {
    return reggieFixedSeq.find(fixedSeqIn);
  }

  @Benchmark
  public boolean specializedFixedSequence_jdk() {
    return jdkFixedSeq.matcher(fixedSeqIn).find();
  }

  // ── SPECIALIZED_BOUNDED_QUANTIFIERS benchmarks ───────────────────────────────

  @Benchmark
  public boolean specializedBoundedQuantifiers_reggie() {
    return reggieBoundedQuant.find(boundedQuantIn);
  }

  @Benchmark
  public boolean specializedBoundedQuantifiers_jdk() {
    return jdkBoundedQuant.matcher(boundedQuantIn).find();
  }

  // ── DFA_UNROLLED benchmarks ──────────────────────────────────────────────────

  @Benchmark
  public boolean dfaUnrolled_reggie() {
    return reggieDfaUnrolled.find(dfaUnrolledIn);
  }

  @Benchmark
  public boolean dfaUnrolled_jdk() {
    return jdkDfaUnrolled.matcher(dfaUnrolledIn).find();
  }

  // ── DFA_UNROLLED_WITH_GROUPS benchmarks ──────────────────────────────────────

  @Benchmark
  public boolean dfaUnrolledWithGroups_reggie() {
    return reggieDfaUnrolledGroups.find(dfaUnrolledGroupsIn);
  }

  @Benchmark
  public boolean dfaUnrolledWithGroups_jdk() {
    return jdkDfaUnrolledGroups.matcher(dfaUnrolledGroupsIn).find();
  }

  // ── DFA_SWITCH benchmarks ────────────────────────────────────────────────────

  @Benchmark
  public boolean dfaSwitch_reggie() {
    return reggieDfaSwitch.find(dfaSwitchIn);
  }

  @Benchmark
  public boolean dfaSwitch_jdk() {
    return jdkDfaSwitch.matcher(dfaSwitchIn).find();
  }

  // ── DFA_SWITCH_WITH_GROUPS benchmarks ────────────────────────────────────────

  @Benchmark
  public boolean dfaSwitchWithGroups_reggie() {
    return reggieDfaSwitchGroups.find(dfaSwitchGroupsIn);
  }

  @Benchmark
  public boolean dfaSwitchWithGroups_jdk() {
    return jdkDfaSwitchGroups.matcher(dfaSwitchGroupsIn).find();
  }

  // ── DFA_TABLE benchmarks ─────────────────────────────────────────────────────

  @Benchmark
  public boolean dfaTable_reggie() {
    return reggieDfaTable.find(dfaTableIn);
  }

  @Benchmark
  public boolean dfaTable_jdk() {
    return jdkDfaTable.matcher(dfaTableIn).find();
  }

  // ── OPTIMIZED_NFA benchmarks ─────────────────────────────────────────────────

  @Benchmark
  public boolean optimizedNfa_reggie() {
    return reggieOptimizedNfa.find(optimizedNfaIn);
  }

  @Benchmark
  public boolean optimizedNfa_jdk() {
    return jdkOptimizedNfa.matcher(optimizedNfaIn).find();
  }

  // ── LAZY_DFA benchmarks ──────────────────────────────────────────────────────

  @Benchmark
  public boolean lazyDfa_reggie() {
    return reggieLazyDfa.find(lazyDfaIn);
  }

  @Benchmark
  public boolean lazyDfa_jdk() {
    return jdkLazyDfa.matcher(lazyDfaIn).find();
  }

  // ── PIKEVM_CAPTURE benchmarks ────────────────────────────────────────────────

  @Benchmark
  public boolean pikevmCapture_reggie() {
    return reggiePikevmCapture.find(pikevmCaptureIn);
  }

  @Benchmark
  public boolean pikevmCapture_jdk() {
    return jdkPikevmCapture.matcher(pikevmCaptureIn).find();
  }

  // ── ONEPASS_NFA benchmarks ───────────────────────────────────────────────────

  @Benchmark
  public boolean onepassNfa_reggie() {
    return reggieOnepassNfa.find(onepassNfaIn);
  }

  @Benchmark
  public boolean onepassNfa_jdk() {
    return jdkOnepassNfa.matcher(onepassNfaIn).find();
  }
}
