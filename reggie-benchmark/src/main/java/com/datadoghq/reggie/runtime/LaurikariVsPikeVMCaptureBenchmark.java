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

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.concurrent.TimeUnit;
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
 * Direct-construction throughput comparison of {@link LaurikariDfaMatcher} against {@link
 * PikeVMMatcher} over the exact five patterns whose capture correctness was verified against the
 * PikeVMMatcher oracle in {@code LaurikariDfaMatcherTest}'s Phase 0.5 adversarial set: two
 * independent greedy groups, a loop-body group, nested nullable groups, shared-prefix alternation,
 * and quantified alternation.
 *
 * <p>Package placement note: this class lives in {@code com.datadoghq.reggie.runtime} (matching the
 * package it benchmarks) even though it is physically under {@code reggie-benchmark}'s source tree,
 * to reach {@link LaurikariDfaMatcher}, which is package-private with no production entry point yet
 * (not wired into {@code PatternAnalyzer}/{@code RuntimeCompiler}). The project has no {@code
 * module-info.java} anywhere, so this split package is plain classpath-mode Java, not a module
 * violation — same approach already used by {@code LaurikariPhase0Benchmark}.
 *
 * <p>Both matchers are constructed once per trial in {@link #setup()} over the same {@link NFA}
 * instance per pattern (mirroring {@code LaurikariDfaMatcherTest}'s {@code laurikari(...)}/{@code
 * pikeVm(...)} helpers), so NFA-build cost is excluded from the measured {@code match}/{@code
 * findMatch} calls themselves — except that each matcher's DFA cache is lazily populated on first
 * use, so the very first measured call per state pays one-time subset-construction cost; this is
 * intentional (mirrors real steady-state reuse) and warmup iterations absorb it.
 *
 * <p>Two scaling strategies, chosen per-pattern to match what actually varies, mirroring {@code
 * AllStrategyVsJdkBenchmark}'s convention:
 *
 * <ul>
 *   <li><b>Matched-region growth</b> (patterns 1-3: {@code (a+)(b+)}, {@code (ab)+}, {@code
 *       ((a)|())*} — all have an unbounded loop/quantifier): LONG grows the matched span itself
 *       (~1000 chars), for both {@code match()} and {@code findMatch()}, so the DFA's per-character
 *       advantage (no capture-array copy) actually gets exercised over a long run.
 *   <li><b>Scan-prefix growth for find, fixed width for match</b> (patterns 4-5: {@code
 *       (foobar)|(foobaz)}, {@code (a|b){2,4}(c)} — both are fixed-width once matched, so an
 *       anchored {@code match()} call cannot grow its own input without breaking the anchor).
 *       {@code match()} uses the same fixed-width input at both scales (SHORT == LONG is
 *       deliberate, not a bug); {@code findMatch()} instead prepends non-matching filler before the
 *       match at LONG, stressing the self-anchoring scan cost rather than the match width (which
 *       cannot grow).
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LaurikariVsPikeVMCaptureBenchmark {

  @Param({"SHORT", "LONG"})
  public String scale;

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return new ThompsonBuilder().build(ast, groupCount);
  }

  private static String pick(String scale, String shortV, String longV) {
    return "SHORT".equals(scale) ? shortV : longV;
  }

  // ── Pattern 1: (a+)(b+) — two independent greedy groups (matched-region growth) ──
  private static final String TWO_GROUPS_PAT = "(a+)(b+)";
  private static final int TWO_GROUPS_GC = 2;

  private String twoGroupsIn;
  private LaurikariDfaMatcher twoGroupsLaurikari;
  private PikeVMMatcher twoGroupsPikeVm;

  // ── Pattern 2: (ab)+ — loop-body group (matched-region growth) ───────────────────
  private static final String LOOP_BODY_PAT = "(ab)+";
  private static final int LOOP_BODY_GC = 1;

  private String loopBodyIn;
  private LaurikariDfaMatcher loopBodyLaurikari;
  private PikeVMMatcher loopBodyPikeVm;

  // ── Pattern 3: ((a)|())* — nested nullable groups (matched-region growth) ────────
  private static final String NESTED_NULLABLE_PAT = "((a)|())*";
  private static final int NESTED_NULLABLE_GC = 3;

  private String nestedNullableIn;
  private LaurikariDfaMatcher nestedNullableLaurikari;
  private PikeVMMatcher nestedNullablePikeVm;

  // ── Pattern 4: (foobar)|(foobaz) — shared-prefix alternation (fixed width; scan-
  // prefix growth for find only) ────────────────────────────────────────────────────
  private static final String SHARED_PREFIX_PAT = "(foobar)|(foobaz)";
  private static final int SHARED_PREFIX_GC = 2;
  private static final String SHARED_PREFIX_MATCH_IN = "foobaz";

  private String sharedPrefixFindIn;
  private LaurikariDfaMatcher sharedPrefixLaurikari;
  private PikeVMMatcher sharedPrefixPikeVm;

  // ── Pattern 5: (a|b){2,4}(c) — quantified alternation (fixed width; scan-prefix
  // growth for find only) ────────────────────────────────────────────────────────────
  private static final String QUANTIFIED_ALT_PAT = "(a|b){2,4}(c)";
  private static final int QUANTIFIED_ALT_GC = 2;
  private static final String QUANTIFIED_ALT_MATCH_IN = "abbac";

  private String quantifiedAltFindIn;
  private LaurikariDfaMatcher quantifiedAltLaurikari;
  private PikeVMMatcher quantifiedAltPikeVm;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    twoGroupsIn = pick(scale, "aaabbb", "a".repeat(500) + "b".repeat(500));
    twoGroupsLaurikari =
        new LaurikariDfaMatcher(nfa(TWO_GROUPS_PAT, TWO_GROUPS_GC), TWO_GROUPS_PAT, TWO_GROUPS_GC);
    twoGroupsPikeVm = new PikeVMMatcher(nfa(TWO_GROUPS_PAT, TWO_GROUPS_GC), TWO_GROUPS_PAT);

    loopBodyIn = pick(scale, "ababab", "ab".repeat(500));
    loopBodyLaurikari =
        new LaurikariDfaMatcher(nfa(LOOP_BODY_PAT, LOOP_BODY_GC), LOOP_BODY_PAT, LOOP_BODY_GC);
    loopBodyPikeVm = new PikeVMMatcher(nfa(LOOP_BODY_PAT, LOOP_BODY_GC), LOOP_BODY_PAT);

    nestedNullableIn = pick(scale, "aaaaaaaaaa", "a".repeat(1000));
    nestedNullableLaurikari =
        new LaurikariDfaMatcher(
            nfa(NESTED_NULLABLE_PAT, NESTED_NULLABLE_GC), NESTED_NULLABLE_PAT, NESTED_NULLABLE_GC);
    nestedNullablePikeVm =
        new PikeVMMatcher(nfa(NESTED_NULLABLE_PAT, NESTED_NULLABLE_GC), NESTED_NULLABLE_PAT);

    sharedPrefixFindIn = pick(scale, "xfoobazy", "z".repeat(1000) + "foobaz");
    sharedPrefixLaurikari =
        new LaurikariDfaMatcher(
            nfa(SHARED_PREFIX_PAT, SHARED_PREFIX_GC), SHARED_PREFIX_PAT, SHARED_PREFIX_GC);
    sharedPrefixPikeVm =
        new PikeVMMatcher(nfa(SHARED_PREFIX_PAT, SHARED_PREFIX_GC), SHARED_PREFIX_PAT);

    quantifiedAltFindIn = pick(scale, "xabbacy", "z".repeat(1000) + "abbac");
    quantifiedAltLaurikari =
        new LaurikariDfaMatcher(
            nfa(QUANTIFIED_ALT_PAT, QUANTIFIED_ALT_GC), QUANTIFIED_ALT_PAT, QUANTIFIED_ALT_GC);
    quantifiedAltPikeVm =
        new PikeVMMatcher(nfa(QUANTIFIED_ALT_PAT, QUANTIFIED_ALT_GC), QUANTIFIED_ALT_PAT);
  }

  // ── (a+)(b+) benchmarks ────────────────────────────────────────────────────────

  @Benchmark
  public MatchResult twoGroups_match_laurikari() {
    return twoGroupsLaurikari.match(twoGroupsIn);
  }

  @Benchmark
  public MatchResult twoGroups_match_pikevm() {
    return twoGroupsPikeVm.match(twoGroupsIn);
  }

  @Benchmark
  public MatchResult twoGroups_findMatch_laurikari() {
    return twoGroupsLaurikari.findMatch(twoGroupsIn);
  }

  @Benchmark
  public MatchResult twoGroups_findMatch_pikevm() {
    return twoGroupsPikeVm.findMatch(twoGroupsIn);
  }

  // ── (ab)+ benchmarks ────────────────────────────────────────────────────────────

  @Benchmark
  public MatchResult loopBody_match_laurikari() {
    return loopBodyLaurikari.match(loopBodyIn);
  }

  @Benchmark
  public MatchResult loopBody_match_pikevm() {
    return loopBodyPikeVm.match(loopBodyIn);
  }

  @Benchmark
  public MatchResult loopBody_findMatch_laurikari() {
    return loopBodyLaurikari.findMatch(loopBodyIn);
  }

  @Benchmark
  public MatchResult loopBody_findMatch_pikevm() {
    return loopBodyPikeVm.findMatch(loopBodyIn);
  }

  // ── ((a)|())* benchmarks ─────────────────────────────────────────────────────────

  @Benchmark
  public MatchResult nestedNullable_match_laurikari() {
    return nestedNullableLaurikari.match(nestedNullableIn);
  }

  @Benchmark
  public MatchResult nestedNullable_match_pikevm() {
    return nestedNullablePikeVm.match(nestedNullableIn);
  }

  @Benchmark
  public MatchResult nestedNullable_findMatch_laurikari() {
    return nestedNullableLaurikari.findMatch(nestedNullableIn);
  }

  @Benchmark
  public MatchResult nestedNullable_findMatch_pikevm() {
    return nestedNullablePikeVm.findMatch(nestedNullableIn);
  }

  // ── (foobar)|(foobaz) benchmarks ─────────────────────────────────────────────────

  @Benchmark
  public MatchResult sharedPrefix_match_laurikari() {
    return sharedPrefixLaurikari.match(SHARED_PREFIX_MATCH_IN);
  }

  @Benchmark
  public MatchResult sharedPrefix_match_pikevm() {
    return sharedPrefixPikeVm.match(SHARED_PREFIX_MATCH_IN);
  }

  @Benchmark
  public MatchResult sharedPrefix_findMatch_laurikari() {
    return sharedPrefixLaurikari.findMatch(sharedPrefixFindIn);
  }

  @Benchmark
  public MatchResult sharedPrefix_findMatch_pikevm() {
    return sharedPrefixPikeVm.findMatch(sharedPrefixFindIn);
  }

  // ── (a|b){2,4}(c) benchmarks ─────────────────────────────────────────────────────

  @Benchmark
  public MatchResult quantifiedAlt_match_laurikari() {
    return quantifiedAltLaurikari.match(QUANTIFIED_ALT_MATCH_IN);
  }

  @Benchmark
  public MatchResult quantifiedAlt_match_pikevm() {
    return quantifiedAltPikeVm.match(QUANTIFIED_ALT_MATCH_IN);
  }

  @Benchmark
  public MatchResult quantifiedAlt_findMatch_laurikari() {
    return quantifiedAltLaurikari.findMatch(quantifiedAltFindIn);
  }

  @Benchmark
  public MatchResult quantifiedAlt_findMatch_pikevm() {
    return quantifiedAltPikeVm.findMatch(quantifiedAltFindIn);
  }
}
