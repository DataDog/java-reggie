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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Comprehensive Reggie-vs-JDK throughput benchmark covering all supported matching strategies.
 *
 * <p>Each strategy is represented by a canonical pattern taken from {@code
 * StrategyCorrectnessMetaTest}. The input used for every pair of benchmarks is the "embedded match"
 * input (index 1 in each Spec list), which exercises the full {@code find()} scan path rather than
 * a trivial anchored match.
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
 *   <li>DFA_TABLE: {@code (?:abc){100}}
 *   <li>OPTIMIZED_NFA: {@code (?<x>a)?b}
 *   <li>LAZY_DFA: {@code (?:a+b+|b+a+){75}}
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

  // ── STATELESS_LOOP ────────────────────────────────────────────────────────────
  // Pattern: \w+   Embedded-match input: "  abc  "
  private static final String STATELESS_LOOP_PAT = "\\w+";
  private static final String STATELESS_LOOP_IN = "  abc  ";

  private ReggieMatcher reggieStatelessLoop;
  private Pattern jdkStatelessLoop;

  // ── SPECIALIZED_GREEDY_CHARCLASS ──────────────────────────────────────────────
  // Pattern: (\d+)   Embedded-match input: "abc123def"
  private static final String GREEDY_CHARCLASS_PAT = "(\\d+)";
  private static final String GREEDY_CHARCLASS_IN = "abc123def";

  private ReggieMatcher reggieGreedyCharclass;
  private Pattern jdkGreedyCharclass;

  // ── SPECIALIZED_MULTI_GROUP_GREEDY ────────────────────────────────────────────
  // Pattern: ([a-z]+)@([a-z]+)\.com   Embedded-match input: "x foo@bar.com y"
  private static final String MULTI_GROUP_PAT = "([a-z]+)@([a-z]+)\\.com";
  private static final String MULTI_GROUP_IN = "x foo@bar.com y";

  private ReggieMatcher reggieMultiGroup;
  private Pattern jdkMultiGroup;

  // ── SPECIALIZED_CONCAT_GREEDY_GROUP ──────────────────────────────────────────
  // Pattern: a(b*)   Embedded-match input: "xabbby"
  private static final String CONCAT_GREEDY_PAT = "a(b*)";
  private static final String CONCAT_GREEDY_IN = "xabbby";

  private ReggieMatcher reggieConcatGreedy;
  private Pattern jdkConcatGreedy;

  // ── SPECIALIZED_FIXED_SEQUENCE ────────────────────────────────────────────────
  // Pattern: \d{3}-\d{3}-\d{4}   Embedded-match input: "call 123-456-7890 now"
  private static final String FIXED_SEQ_PAT = "\\d{3}-\\d{3}-\\d{4}";
  private static final String FIXED_SEQ_IN = "call 123-456-7890 now";

  private ReggieMatcher reggieFixedSeq;
  private Pattern jdkFixedSeq;

  // ── SPECIALIZED_BOUNDED_QUANTIFIERS ──────────────────────────────────────────
  // Pattern: \d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}   Embedded-match input: "ip=192.168.0.1!"
  private static final String BOUNDED_QUANT_PAT = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}";
  private static final String BOUNDED_QUANT_IN = "ip=192.168.0.1!";

  private ReggieMatcher reggieBoundedQuant;
  private Pattern jdkBoundedQuant;

  // ── DFA_UNROLLED ──────────────────────────────────────────────────────────────
  // Pattern: [ab]c   Embedded-match input: "x bc y"
  private static final String DFA_UNROLLED_PAT = "[ab]c";
  private static final String DFA_UNROLLED_IN = "x bc y";

  private ReggieMatcher reggieDfaUnrolled;
  private Pattern jdkDfaUnrolled;

  // ── DFA_UNROLLED_WITH_GROUPS ──────────────────────────────────────────────────
  // Pattern: (fo|foo)   Embedded-match input: "xfooy"
  private static final String DFA_UNROLLED_GROUPS_PAT = "(fo|foo)";
  private static final String DFA_UNROLLED_GROUPS_IN = "xfooy";

  private ReggieMatcher reggieDfaUnrolledGroups;
  private Pattern jdkDfaUnrolledGroups;

  // ── DFA_SWITCH ────────────────────────────────────────────────────────────────
  // Pattern: a.*b.*c.*d.*e.*f   Embedded-match input: "x a1b2c3d4e5f y"
  private static final String DFA_SWITCH_PAT = "a.*b.*c.*d.*e.*f";
  private static final String DFA_SWITCH_IN = "x a1b2c3d4e5f y";

  private ReggieMatcher reggieDfaSwitch;
  private Pattern jdkDfaSwitch;

  // ── DFA_SWITCH_WITH_GROUPS ────────────────────────────────────────────────────
  // Pattern: (a|b|c|d|e|f|g)(h|i|j|k|l|m)(n|o|p|q|r)(s|t|u|v)
  // Embedded-match input: "x bios y"
  private static final String DFA_SWITCH_GROUPS_PAT =
      "(a|b|c|d|e|f|g)(h|i|j|k|l|m)(n|o|p|q|r)(s|t|u|v)";
  private static final String DFA_SWITCH_GROUPS_IN = "x bios y";

  private ReggieMatcher reggieDfaSwitchGroups;
  private Pattern jdkDfaSwitchGroups;

  // ── DFA_TABLE ─────────────────────────────────────────────────────────────────
  // Pattern: (?:abc){100}   Embedded-match input: "x" + "abc".repeat(100) + "y"
  private static final String DFA_TABLE_PAT = "(?:abc){100}";
  private static final String DFA_TABLE_IN = "x" + "abc".repeat(100) + "y";

  private ReggieMatcher reggieDfaTable;
  private Pattern jdkDfaTable;

  // ── OPTIMIZED_NFA ─────────────────────────────────────────────────────────────
  // Pattern: (?<x>a)?b   Embedded-match input: "xaby"
  private static final String OPTIMIZED_NFA_PAT = "(?<x>a)?b";
  private static final String OPTIMIZED_NFA_IN = "xaby";

  private ReggieMatcher reggieOptimizedNfa;
  private Pattern jdkOptimizedNfa;

  // ── LAZY_DFA ──────────────────────────────────────────────────────────────────
  // Pattern: (?:a+b+|b+a+){75}   Embedded-match input: "x" + "ab".repeat(75) + "y"
  private static final String LAZY_DFA_PAT = "(?:a+b+|b+a+){75}";
  private static final String LAZY_DFA_IN = "x" + "ab".repeat(75) + "y";

  private ReggieMatcher reggieLazyDfa;
  private Pattern jdkLazyDfa;

  // ── PIKEVM_CAPTURE ────────────────────────────────────────────────────────────
  // Pattern: (a)?b   Embedded-match input: "xaby"
  // Note: routes to OPTIMIZED_NFA at runtime (capture-ambiguous interception).
  private static final String PIKEVM_CAPTURE_PAT = "(a)?b";
  private static final String PIKEVM_CAPTURE_IN = "xaby";

  private ReggieMatcher reggiePikevmCapture;
  private Pattern jdkPikevmCapture;

  // ── ONEPASS_NFA ───────────────────────────────────────────────────────────────
  // Pattern: (foo)(bar)   Embedded-match input: "x foobar y"
  private static final String ONEPASS_NFA_PAT = "(foo)(bar)";
  private static final String ONEPASS_NFA_IN = "x foobar y";

  private ReggieMatcher reggieOnepassNfa;
  private Pattern jdkOnepassNfa;

  @Setup(Level.Trial)
  public void setup() {
    reggieStatelessLoop = Reggie.compile(STATELESS_LOOP_PAT);
    jdkStatelessLoop = Pattern.compile(STATELESS_LOOP_PAT);

    reggieGreedyCharclass = Reggie.compile(GREEDY_CHARCLASS_PAT);
    jdkGreedyCharclass = Pattern.compile(GREEDY_CHARCLASS_PAT);

    reggieMultiGroup = Reggie.compile(MULTI_GROUP_PAT);
    jdkMultiGroup = Pattern.compile(MULTI_GROUP_PAT);

    reggieConcatGreedy = Reggie.compile(CONCAT_GREEDY_PAT);
    jdkConcatGreedy = Pattern.compile(CONCAT_GREEDY_PAT);

    reggieFixedSeq = Reggie.compile(FIXED_SEQ_PAT);
    jdkFixedSeq = Pattern.compile(FIXED_SEQ_PAT);

    reggieBoundedQuant = Reggie.compile(BOUNDED_QUANT_PAT);
    jdkBoundedQuant = Pattern.compile(BOUNDED_QUANT_PAT);

    reggieDfaUnrolled = Reggie.compile(DFA_UNROLLED_PAT);
    jdkDfaUnrolled = Pattern.compile(DFA_UNROLLED_PAT);

    reggieDfaUnrolledGroups = Reggie.compile(DFA_UNROLLED_GROUPS_PAT);
    jdkDfaUnrolledGroups = Pattern.compile(DFA_UNROLLED_GROUPS_PAT);

    reggieDfaSwitch = Reggie.compile(DFA_SWITCH_PAT);
    jdkDfaSwitch = Pattern.compile(DFA_SWITCH_PAT);

    reggieDfaSwitchGroups = Reggie.compile(DFA_SWITCH_GROUPS_PAT);
    jdkDfaSwitchGroups = Pattern.compile(DFA_SWITCH_GROUPS_PAT);

    reggieDfaTable = Reggie.compile(DFA_TABLE_PAT);
    jdkDfaTable = Pattern.compile(DFA_TABLE_PAT);

    reggieOptimizedNfa = Reggie.compile(OPTIMIZED_NFA_PAT);
    jdkOptimizedNfa = Pattern.compile(OPTIMIZED_NFA_PAT);

    reggieLazyDfa = Reggie.compile(LAZY_DFA_PAT);
    jdkLazyDfa = Pattern.compile(LAZY_DFA_PAT);

    reggiePikevmCapture = Reggie.compile(PIKEVM_CAPTURE_PAT);
    jdkPikevmCapture = Pattern.compile(PIKEVM_CAPTURE_PAT);

    reggieOnepassNfa = Reggie.compile(ONEPASS_NFA_PAT);
    jdkOnepassNfa = Pattern.compile(ONEPASS_NFA_PAT);
  }

  // ── STATELESS_LOOP benchmarks ────────────────────────────────────────────────

  @Benchmark
  public boolean statelessLoop_reggie() {
    return reggieStatelessLoop.find(STATELESS_LOOP_IN);
  }

  @Benchmark
  public boolean statelessLoop_jdk() {
    return jdkStatelessLoop.matcher(STATELESS_LOOP_IN).find();
  }

  // ── SPECIALIZED_GREEDY_CHARCLASS benchmarks ──────────────────────────────────

  @Benchmark
  public boolean specializedGreedyCharclass_reggie() {
    return reggieGreedyCharclass.find(GREEDY_CHARCLASS_IN);
  }

  @Benchmark
  public boolean specializedGreedyCharclass_jdk() {
    return jdkGreedyCharclass.matcher(GREEDY_CHARCLASS_IN).find();
  }

  // ── SPECIALIZED_MULTI_GROUP_GREEDY benchmarks ────────────────────────────────

  @Benchmark
  public boolean specializedMultiGroupGreedy_reggie() {
    return reggieMultiGroup.find(MULTI_GROUP_IN);
  }

  @Benchmark
  public boolean specializedMultiGroupGreedy_jdk() {
    return jdkMultiGroup.matcher(MULTI_GROUP_IN).find();
  }

  // ── SPECIALIZED_CONCAT_GREEDY_GROUP benchmarks ───────────────────────────────

  @Benchmark
  public boolean specializedConcatGreedyGroup_reggie() {
    return reggieConcatGreedy.find(CONCAT_GREEDY_IN);
  }

  @Benchmark
  public boolean specializedConcatGreedyGroup_jdk() {
    return jdkConcatGreedy.matcher(CONCAT_GREEDY_IN).find();
  }

  // ── SPECIALIZED_FIXED_SEQUENCE benchmarks ────────────────────────────────────

  @Benchmark
  public boolean specializedFixedSequence_reggie() {
    return reggieFixedSeq.find(FIXED_SEQ_IN);
  }

  @Benchmark
  public boolean specializedFixedSequence_jdk() {
    return jdkFixedSeq.matcher(FIXED_SEQ_IN).find();
  }

  // ── SPECIALIZED_BOUNDED_QUANTIFIERS benchmarks ───────────────────────────────

  @Benchmark
  public boolean specializedBoundedQuantifiers_reggie() {
    return reggieBoundedQuant.find(BOUNDED_QUANT_IN);
  }

  @Benchmark
  public boolean specializedBoundedQuantifiers_jdk() {
    return jdkBoundedQuant.matcher(BOUNDED_QUANT_IN).find();
  }

  // ── DFA_UNROLLED benchmarks ──────────────────────────────────────────────────

  @Benchmark
  public boolean dfaUnrolled_reggie() {
    return reggieDfaUnrolled.find(DFA_UNROLLED_IN);
  }

  @Benchmark
  public boolean dfaUnrolled_jdk() {
    return jdkDfaUnrolled.matcher(DFA_UNROLLED_IN).find();
  }

  // ── DFA_UNROLLED_WITH_GROUPS benchmarks ──────────────────────────────────────

  @Benchmark
  public boolean dfaUnrolledWithGroups_reggie() {
    return reggieDfaUnrolledGroups.find(DFA_UNROLLED_GROUPS_IN);
  }

  @Benchmark
  public boolean dfaUnrolledWithGroups_jdk() {
    return jdkDfaUnrolledGroups.matcher(DFA_UNROLLED_GROUPS_IN).find();
  }

  // ── DFA_SWITCH benchmarks ────────────────────────────────────────────────────

  @Benchmark
  public boolean dfaSwitch_reggie() {
    return reggieDfaSwitch.find(DFA_SWITCH_IN);
  }

  @Benchmark
  public boolean dfaSwitch_jdk() {
    return jdkDfaSwitch.matcher(DFA_SWITCH_IN).find();
  }

  // ── DFA_SWITCH_WITH_GROUPS benchmarks ────────────────────────────────────────

  @Benchmark
  public boolean dfaSwitchWithGroups_reggie() {
    return reggieDfaSwitchGroups.find(DFA_SWITCH_GROUPS_IN);
  }

  @Benchmark
  public boolean dfaSwitchWithGroups_jdk() {
    return jdkDfaSwitchGroups.matcher(DFA_SWITCH_GROUPS_IN).find();
  }

  // ── DFA_TABLE benchmarks ─────────────────────────────────────────────────────

  @Benchmark
  public boolean dfaTable_reggie() {
    return reggieDfaTable.find(DFA_TABLE_IN);
  }

  @Benchmark
  public boolean dfaTable_jdk() {
    return jdkDfaTable.matcher(DFA_TABLE_IN).find();
  }

  // ── OPTIMIZED_NFA benchmarks ─────────────────────────────────────────────────

  @Benchmark
  public boolean optimizedNfa_reggie() {
    return reggieOptimizedNfa.find(OPTIMIZED_NFA_IN);
  }

  @Benchmark
  public boolean optimizedNfa_jdk() {
    return jdkOptimizedNfa.matcher(OPTIMIZED_NFA_IN).find();
  }

  // ── LAZY_DFA benchmarks ──────────────────────────────────────────────────────

  @Benchmark
  public boolean lazyDfa_reggie() {
    return reggieLazyDfa.find(LAZY_DFA_IN);
  }

  @Benchmark
  public boolean lazyDfa_jdk() {
    return jdkLazyDfa.matcher(LAZY_DFA_IN).find();
  }

  // ── PIKEVM_CAPTURE benchmarks ────────────────────────────────────────────────

  @Benchmark
  public boolean pikevmCapture_reggie() {
    return reggiePikevmCapture.find(PIKEVM_CAPTURE_IN);
  }

  @Benchmark
  public boolean pikevmCapture_jdk() {
    return jdkPikevmCapture.matcher(PIKEVM_CAPTURE_IN).find();
  }

  // ── ONEPASS_NFA benchmarks ───────────────────────────────────────────────────

  @Benchmark
  public boolean onepassNfa_reggie() {
    return reggieOnepassNfa.find(ONEPASS_NFA_IN);
  }

  @Benchmark
  public boolean onepassNfa_jdk() {
    return jdkOnepassNfa.matcher(ONEPASS_NFA_IN).find();
  }
}
