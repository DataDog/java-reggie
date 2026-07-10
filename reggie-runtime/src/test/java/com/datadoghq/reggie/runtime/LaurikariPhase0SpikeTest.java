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

import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Phase-0 exit-criteria evidence (impl plan Task 0.3,
 * doc/2026-07-10-tdfa-capture-engine-impl-plan.md §1): builds a {@link LaurikariDFACache} over the
 * real {@code SQL_ANSI} tokenizer pattern (mirrored from {@code IastRegexpBenchmark.SQL_ANSI}) and
 * runs it over that benchmark's LONG-scale scan-prefix inputs — the exact shape that exceeds {@code
 * BitStateMatcher.BUDGET_CELLS} and today gets punted all the way to {@code PikeVMMatcher}'s full
 * thread simulation.
 *
 * <p>This is a throwaway measurement harness, not a correctness/regression suite: it exists purely
 * to produce the three numbers Task 0.3 asks for (exact localization, materialized state count,
 * rough wall-clock signal vs. the JDK oracle) for the Phase 0 exit-criteria report. The
 * closure/step-function driver duplicates {@code LaurikariDFACacheTest}'s test-only driver verbatim
 * (same legitimate test-only duplication rationale documented there) rather than sharing code
 * across test classes, since both are disposable Phase-0 scaffolding.
 */
class LaurikariPhase0SpikeTest {

  // --- SQL_ANSI, mirrored from IastRegexpBenchmark.java:101-104 --------------------------------
  private static final String SQL_ANSI =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  // --- LONG-scale sqlMatch/sqlNoMatch, mirrored from IastRegexpBenchmark.java:241-260 (the third
  // --- `pick(scale, ...)` argument, since IastRegexpBenchmark.pick() returns longV by default for
  // --- any scale other than SHORT/MEDIUM, i.e. LONG). "col_x = col_y AND ".repeat(2000) is benign
  // --- scan-prefix filler (no digits/quotes/comment markers) that BitStateMatcher's bounded budget
  // --- cannot get through without falling all the way to PikeVMMatcher.
  private static final String SQL_MATCH_LONG =
      "SELECT * FROM users WHERE "
          + "col_x = col_y AND ".repeat(2000)
          + "id = 42 AND name = 'Alice' AND balance = 1234.56";
  private static final String SQL_NO_MATCH_LONG =
      "SELECT id, name, email FROM users WHERE "
          + "col_x = col_y AND ".repeat(2000)
          + "id = id ORDER BY id";

  // --- Test-only LaurikariNfaStep driver, duplicated verbatim from LaurikariDFACacheTest ---------

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    return new ThompsonBuilder().build(new RegexParser().parse(pattern), groupCount);
  }

  private static NFA.NFAState[] statesById(NFA nfa) {
    NFA.NFAState[] arr = new NFA.NFAState[nfa.getStates().size()];
    for (NFA.NFAState s : nfa.getStates()) {
      arr[s.id] = s;
    }
    return arr;
  }

  private static int[][] closureWithAges(
      NFA.NFAState[] statesById, int stateCount, int[] seedStates, int[] seedAges) {
    int[] ages = new int[stateCount];
    Arrays.fill(ages, -1);
    Deque<Integer> worklist = new ArrayDeque<>();
    for (int i = 0; i < seedStates.length; i++) {
      int id = seedStates[i];
      if (seedAges[i] > ages[id]) {
        ages[id] = seedAges[i];
        worklist.push(id);
      }
    }
    while (!worklist.isEmpty()) {
      int id = worklist.pop();
      int age = ages[id];
      for (NFA.NFAState e : statesById[id].getEpsilonTransitions()) {
        if (age > ages[e.id]) {
          ages[e.id] = age;
          worklist.push(e.id);
        }
      }
    }
    return denseToSeed(ages, stateCount);
  }

  private static int[][] denseToSeed(int[] denseAges, int stateCount) {
    int count = 0;
    for (int v : denseAges) {
      if (v >= 0) count++;
    }
    int[] states = new int[count];
    int[] ages = new int[count];
    int oi = 0;
    for (int id = 0; id < stateCount; id++) {
      if (denseAges[id] >= 0) {
        states[oi] = id;
        ages[oi] = denseAges[id];
        oi++;
      }
    }
    return new int[][] {states, ages};
  }

  private static LaurikariNfaStep laurikariStep(NFA nfa) {
    NFA.NFAState[] statesById = statesById(nfa);
    int stateCount = statesById.length;
    int startId = nfa.getStartState().id;
    return (curStates, curRegs, c) -> {
      int[] rawAges = new int[stateCount];
      Arrays.fill(rawAges, -1);
      for (int i = 0; i < curStates.length; i++) {
        NFA.NFAState s = statesById[curStates[i]];
        int age = curRegs[i][0];
        for (NFA.Transition t : s.getTransitions()) {
          if (t.chars.contains((char) c)) {
            int cand = age + 1;
            if (cand > rawAges[t.target.id]) rawAges[t.target.id] = cand;
          }
        }
      }
      int[][] consumedSeed = denseToSeed(rawAges, stateCount);
      int[][] consumedClosed =
          closureWithAges(statesById, stateCount, consumedSeed[0], consumedSeed[1]);
      int[][] freshClosed =
          closureWithAges(statesById, stateCount, new int[] {startId}, new int[] {0});

      int[] merged = new int[stateCount];
      Arrays.fill(merged, -1);
      for (int i = 0; i < consumedClosed[0].length; i++) {
        merged[consumedClosed[0][i]] = consumedClosed[1][i];
      }
      for (int i = 0; i < freshClosed[0].length; i++) {
        int id = freshClosed[0][i];
        int age = freshClosed[1][i];
        if (age > merged[id]) merged[id] = age;
      }
      int[][] out = denseToSeed(merged, stateCount);
      int[] outStates = out[0];
      int[] outAges = out[1];
      int[][] outRegs = new int[outStates.length][];
      for (int i = 0; i < outStates.length; i++) {
        outRegs[i] = new int[] {outAges[i]};
      }
      return new LaurikariStepResult(outStates, outRegs);
    };
  }

  private static final class Built {
    final LaurikariDFACache cache;
    final LaurikariNfaStep step;

    Built(LaurikariDFACache cache, LaurikariNfaStep step) {
      this.cache = cache;
      this.step = step;
    }
  }

  private static Built build(String pattern, int groupCount) throws Exception {
    NFA nfa = nfa(pattern, groupCount);
    NFA.NFAState[] statesById = statesById(nfa);
    int stateCount = statesById.length;
    int startId = nfa.getStartState().id;

    int[][] startClosure =
        closureWithAges(statesById, stateCount, new int[] {startId}, new int[] {0});

    int[] acceptIds = new int[nfa.getAcceptStates().size()];
    int ai = 0;
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      acceptIds[ai++] = s.id;
    }

    int[] startStates = startClosure[0];
    int[] startAges = startClosure[1];
    int[][] startRegs = new int[startStates.length][];
    for (int i = 0; i < startStates.length; i++) {
      startRegs[i] = new int[] {startAges[i]};
    }

    LaurikariDFACache cache = new LaurikariDFACache(startStates, startRegs, acceptIds);
    return new Built(cache, laurikariStep(nfa));
  }

  // --- Timing harness: NOT JMH-rigorous, explicitly a throwaway directional signal per the plan --

  private static final int WARMUP_ITERS = 5;
  private static final int MEASURED_ITERS = 5;

  private static long medianNanos(long[] samples) {
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    return sorted[sorted.length / 2];
  }

  @Test
  void sqlAnsiLongScale_exactLocalization_stateCount_andRoughTiming() throws Exception {
    // SQL_ANSI has no capturing groups (every parenthesis in it is (?i)/(?m)/(?:...)), so
    // groupCount = 0, same convention LaurikariDFACacheTest uses for its own group-less patterns.
    Built built = build(SQL_ANSI, 0);

    // --- (3) Correctness on sqlMatch, oracle-derived (java.util.regex.Pattern is the oracle; the
    // --- expected start is computed here, not hardcoded) -----------------------------------------
    Pattern oracle = Pattern.compile(SQL_ANSI);
    Matcher oracleMatcher = oracle.matcher(SQL_MATCH_LONG);
    assertTrue(oracleMatcher.find(), "JDK oracle must find a match in SQL_MATCH_LONG");
    int expectedStart = oracleMatcher.start();

    int laurikariStart = built.cache.findLeftmostStart(SQL_MATCH_LONG, 0, built.step);
    boolean exactLocalization = laurikariStart == expectedStart;
    assertEquals(
        expectedStart,
        laurikariStart,
        "LaurikariDFACache localized start must exactly match the JDK oracle's match start");

    // --- (4) sqlNoMatch must return -1
    // -------------------------------------------------------------
    Matcher noMatchOracle = Pattern.compile(SQL_ANSI).matcher(SQL_NO_MATCH_LONG);
    assertTrue(
        !noMatchOracle.find(), "JDK oracle must NOT find a match in SQL_NO_MATCH_LONG (sanity)");
    int laurikariNoMatch = built.cache.findLeftmostStart(SQL_NO_MATCH_LONG, 0, built.step);
    assertEquals(-1, laurikariNoMatch, "LaurikariDFACache must report -1 on SQL_NO_MATCH_LONG");

    // --- (b) total distinct DFA states materialized (package-private test-only accessor already
    // --- present on LaurikariDFACache: stateCount())
    // ----------------------------------------------
    int stateCount = built.cache.stateCount();

    // --- (c) rough wall-clock: LaurikariDFACache.findLeftmostStart vs Pattern.matcher().find(),
    // --- same input (SQL_MATCH_LONG), 5 warmup + 5 measured iterations each, report the median of
    // --- the measured set. Both sides reuse an already-"warm" object (the cache and the compiled
    // --- Pattern respectively) across iterations, matching how each would actually be used
    // --- repeatedly in production (one-time build cost amortized away) -- this is explicitly NOT
    // --- JMH-quality, just a directional signal per the plan's own framing.
    Pattern timingPattern = Pattern.compile(SQL_ANSI);

    for (int i = 0; i < WARMUP_ITERS; i++) {
      built.cache.findLeftmostStart(SQL_MATCH_LONG, 0, built.step);
      timingPattern.matcher(SQL_MATCH_LONG).find();
    }

    long[] laurikariNanos = new long[MEASURED_ITERS];
    for (int i = 0; i < MEASURED_ITERS; i++) {
      long t0 = System.nanoTime();
      built.cache.findLeftmostStart(SQL_MATCH_LONG, 0, built.step);
      laurikariNanos[i] = System.nanoTime() - t0;
    }

    long[] jdkNanos = new long[MEASURED_ITERS];
    for (int i = 0; i < MEASURED_ITERS; i++) {
      long t0 = System.nanoTime();
      timingPattern.matcher(SQL_MATCH_LONG).find();
      jdkNanos[i] = System.nanoTime() - t0;
    }

    long laurikariMedianNanos = medianNanos(laurikariNanos);
    long jdkMedianNanos = medianNanos(jdkNanos);

    System.out.println("=== LaurikariPhase0SpikeTest / Phase 0 Task 0.3 measurements ===");
    System.out.println("(a) exact localization vs JDK oracle: " + exactLocalization);
    System.out.println(
        "    expectedStart(JDK oracle)=" + expectedStart + " laurikariStart=" + laurikariStart);
    System.out.println("(b) distinct DFA states materialized: " + stateCount);
    System.out.println(
        "(c) LaurikariDFACache.findLeftmostStart median nanos ("
            + MEASURED_ITERS
            + " measured, "
            + WARMUP_ITERS
            + " warmup): "
            + laurikariMedianNanos
            + " ns, all samples="
            + Arrays.toString(laurikariNanos));
    System.out.println(
        "    Pattern.matcher(...).find() median nanos ("
            + MEASURED_ITERS
            + " measured, "
            + WARMUP_ITERS
            + " warmup): "
            + jdkMedianNanos
            + " ns, all samples="
            + Arrays.toString(jdkNanos));
    System.out.println(
        "    ratio (JDK / Laurikari): "
            + (jdkMedianNanos == 0 ? "n/a" : (double) jdkMedianNanos / laurikariMedianNanos));
    System.out.println("==================================================================");
  }
}
