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

import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Benchmarks for four previously uncovered bytecode generation strategies.
 *
 * <ul>
 *   <li><b>FIXED_REPETITION_BACKREF</b>: {@code (a)\1{8,}} — capture group, then verify N+
 *       repetitions of the same content.
 *   <li><b>OPTIONAL_GROUP_BACKREF</b>: {@code (a)?\1} — backreference to an optional group; matches
 *       when the group captured a value and the backreference repeats it.
 *   <li><b>GREEDY_BACKTRACK</b>: {@code (.*)bar} — greedy {@code .*} must give back characters to
 *       allow the literal suffix to match.
 *   <li><b>ONEPASS_NFA</b>: {@code ^(a)(b)(c)$} — unambiguous single-path execution; group tracking
 *       without state copying.
 * </ul>
 *
 * <p>Each strategy has {@code matches()} and {@code find()} variants with a JDK baseline.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class StrategyBenchmark {

  // ── FIXED_REPETITION_BACKREF ─────────────────────────────────────────────────
  // Pattern: (a)\1{8,}  — capture 'a', verify 8 or more additional 'a's follow.
  // Strategy confirmed: FIXED_REPETITION_BACKREF
  private static final String FIXED_REP_PATTERN = "(a)\\1{8,}";
  private static final String FIXED_REP_MATCH = "aaaaaaaaaaaaaaaaaaaaa"; // 21 a's → matches
  private static final String FIXED_REP_NO_MATCH = "aaaaaaaab"; // ends in 'b' → no match

  private ReggieMatcher reggieFixedRep;
  private Pattern jdkFixedRep;

  // ── OPTIONAL_GROUP_BACKREF ───────────────────────────────────────────────────
  // Pattern: (a)?\1  — optional capturing group + backreference to it.
  // Matches "aa" (group captures 'a', \1 repeats it). No match if group didn't capture.
  // Strategy confirmed: OPTIONAL_GROUP_BACKREF
  private static final String OPT_GROUP_PATTERN = "(a)?\\1";
  private static final String OPT_GROUP_MATCH = "aa";
  private static final String OPT_GROUP_NO_MATCH = "ab";

  private ReggieMatcher reggieOptGroup;
  private Pattern jdkOptGroup;

  // ── GREEDY_BACKTRACK ─────────────────────────────────────────────────────────
  // Pattern: (.*)bar  — greedy .* must give back chars for "bar" suffix to match.
  // Strategy confirmed: GREEDY_BACKTRACK
  private static final String GREEDY_BT_PATTERN = "(.*)bar";
  private static final String GREEDY_BT_MATCH = "foobar";
  private static final String GREEDY_BT_NO_MATCH = "foocar";
  private static final String GREEDY_BT_FIND_INPUT = "hello foobar world";

  private ReggieMatcher reggieGreedyBt;
  private Pattern jdkGreedyBt;

  // ── ONEPASS_NFA ──────────────────────────────────────────────────────────────
  // Pattern: ^(a)(b)(c)$  — three disjoint capturing groups, no ambiguity.
  // Strategy confirmed: ONEPASS_NFA
  private static final String ONEPASS_PATTERN = "^(a)(b)(c)$";
  private static final String ONEPASS_MATCH = "abc";
  private static final String ONEPASS_NO_MATCH = "xyz";
  private static final String ONEPASS_FIND_INPUT = "xyzabcdef";

  private ReggieMatcher reggieOnepass;
  private Pattern jdkOnepass;

  // ── ONEPASS_NFA findMatch/findBoundsFrom (long haystack) ────────────────────
  // Exercises the group-capturing find path fixed in this branch: matchFrom's end position is
  // now reused directly instead of being re-derived via an O(remaining input) matchesInRange
  // trial loop. Match sits near the end of a long haystack so any leftover rescan dominates.
  private static final String ONEPASS_LONG_PATTERN = "(abc)";
  private static final String ONEPASS_LONG_INPUT;

  static {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 20_000; i++) {
      sb.append('x');
    }
    sb.append("abc");
    ONEPASS_LONG_INPUT = sb.toString();
  }

  private ReggieMatcher reggieOnepassLong;
  private Pattern jdkOnepassLong;

  @Setup(Level.Trial)
  public void setup() {
    reggieFixedRep = RuntimeCompiler.compile(FIXED_REP_PATTERN);
    jdkFixedRep = Pattern.compile(FIXED_REP_PATTERN);

    reggieOptGroup = RuntimeCompiler.compile(OPT_GROUP_PATTERN);
    jdkOptGroup = Pattern.compile(OPT_GROUP_PATTERN);

    reggieGreedyBt = RuntimeCompiler.compile(GREEDY_BT_PATTERN);
    jdkGreedyBt = Pattern.compile(GREEDY_BT_PATTERN);

    reggieOnepass = RuntimeCompiler.compile(ONEPASS_PATTERN);
    jdkOnepass = Pattern.compile(ONEPASS_PATTERN);

    reggieOnepassLong = RuntimeCompiler.compile(ONEPASS_LONG_PATTERN);
    jdkOnepassLong = Pattern.compile(ONEPASS_LONG_PATTERN);
  }

  // ── FIXED_REPETITION_BACKREF benchmarks ─────────────────────────────────────

  @Benchmark
  public boolean reggieFixedRepMatch() {
    return reggieFixedRep.matches(FIXED_REP_MATCH);
  }

  @Benchmark
  public boolean jdkFixedRepMatch() {
    return jdkFixedRep.matcher(FIXED_REP_MATCH).matches();
  }

  @Benchmark
  public boolean reggieFixedRepNoMatch() {
    return reggieFixedRep.matches(FIXED_REP_NO_MATCH);
  }

  @Benchmark
  public boolean jdkFixedRepNoMatch() {
    return jdkFixedRep.matcher(FIXED_REP_NO_MATCH).matches();
  }

  @Benchmark
  public boolean reggieFixedRepFind() {
    return reggieFixedRep.find(FIXED_REP_MATCH);
  }

  @Benchmark
  public boolean jdkFixedRepFind() {
    return jdkFixedRep.matcher(FIXED_REP_MATCH).find();
  }

  // ── OPTIONAL_GROUP_BACKREF benchmarks ───────────────────────────────────────

  @Benchmark
  public boolean reggieOptGroupMatch() {
    return reggieOptGroup.matches(OPT_GROUP_MATCH);
  }

  @Benchmark
  public boolean jdkOptGroupMatch() {
    return jdkOptGroup.matcher(OPT_GROUP_MATCH).matches();
  }

  @Benchmark
  public boolean reggieOptGroupNoMatch() {
    return reggieOptGroup.matches(OPT_GROUP_NO_MATCH);
  }

  @Benchmark
  public boolean jdkOptGroupNoMatch() {
    return jdkOptGroup.matcher(OPT_GROUP_NO_MATCH).matches();
  }

  @Benchmark
  public boolean reggieOptGroupFind() {
    return reggieOptGroup.find(OPT_GROUP_MATCH);
  }

  @Benchmark
  public boolean jdkOptGroupFind() {
    return jdkOptGroup.matcher(OPT_GROUP_MATCH).find();
  }

  // ── GREEDY_BACKTRACK benchmarks ──────────────────────────────────────────────

  @Benchmark
  public boolean reggieGreedyBtMatch() {
    return reggieGreedyBt.matches(GREEDY_BT_MATCH);
  }

  @Benchmark
  public boolean jdkGreedyBtMatch() {
    return jdkGreedyBt.matcher(GREEDY_BT_MATCH).matches();
  }

  @Benchmark
  public boolean reggieGreedyBtNoMatch() {
    return reggieGreedyBt.matches(GREEDY_BT_NO_MATCH);
  }

  @Benchmark
  public boolean jdkGreedyBtNoMatch() {
    return jdkGreedyBt.matcher(GREEDY_BT_NO_MATCH).matches();
  }

  @Benchmark
  public boolean reggieGreedyBtFind() {
    return reggieGreedyBt.find(GREEDY_BT_FIND_INPUT);
  }

  @Benchmark
  public boolean jdkGreedyBtFind() {
    return jdkGreedyBt.matcher(GREEDY_BT_FIND_INPUT).find();
  }

  // ── ONEPASS_NFA benchmarks ───────────────────────────────────────────────────

  @Benchmark
  public boolean reggieOnepassMatch() {
    return reggieOnepass.matches(ONEPASS_MATCH);
  }

  @Benchmark
  public boolean jdkOnepassMatch() {
    return jdkOnepass.matcher(ONEPASS_MATCH).matches();
  }

  @Benchmark
  public boolean reggieOnepassNoMatch() {
    return reggieOnepass.matches(ONEPASS_NO_MATCH);
  }

  @Benchmark
  public boolean jdkOnepassNoMatch() {
    return jdkOnepass.matcher(ONEPASS_NO_MATCH).matches();
  }

  @Benchmark
  public boolean reggieOnepassFind() {
    return reggieOnepass.find(ONEPASS_FIND_INPUT);
  }

  @Benchmark
  public boolean jdkOnepassFind() {
    return jdkOnepass.matcher(ONEPASS_FIND_INPUT).find();
  }

  // ── ONEPASS_NFA findMatch/findBoundsFrom benchmarks (long haystack) ─────────

  @Benchmark
  public MatchResult reggieOnepassFindMatchLong() {
    return reggieOnepassLong.findMatch(ONEPASS_LONG_INPUT);
  }

  @Benchmark
  public java.util.regex.MatchResult jdkOnepassFindMatchLong() {
    java.util.regex.Matcher m = jdkOnepassLong.matcher(ONEPASS_LONG_INPUT);
    m.find();
    return m.toMatchResult();
  }

  @Benchmark
  public boolean reggieOnepassFindBoundsFromLong() {
    int[] bounds = new int[2];
    return reggieOnepassLong.findBoundsFrom(ONEPASS_LONG_INPUT, 0, bounds);
  }
}
