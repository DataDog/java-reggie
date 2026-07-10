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

import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
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
 * Real-JMH counterpart to {@code LaurikariPhase0SpikeTest}'s ad-hoc {@code nanoTime} measurement,
 * closing the Phase 0 exit-criterion-(c) gap flagged by adversarial verification (impl plan Task
 * 0.3): {@code findLeftmostStart} measured under the same JMH harness (warmup/fork/measurement
 * discipline) as {@code IastRegexpBenchmark}'s {@code reggieSqlAnsiFind}/{@code
 * reggieSqlMysqlFind}/{@code reggieSqlPostgresqlFind} (the "current full-{@code
 * PikeVMMatcher}-punt" baseline this design targets, since at LONG scale those calls already exceed
 * {@code BitStateMatcher.BUDGET_CELLS} and fall through to {@code PikeVMMatcher}'s thread
 * simulation), against the exact same LONG-scale inputs (mirrored verbatim from {@code
 * IastRegexpBenchmark.java}'s {@code setup()}).
 *
 * <p>Package placement note: this class lives in {@code com.datadoghq.reggie.runtime} (matching the
 * package it benchmarks) even though it is physically under {@code reggie-benchmark}'s source tree,
 * to reach {@link LaurikariDFACache}/{@link LaurikariNfaStep}/{@link LaurikariStepResult}, which
 * are package-private with no production entry point yet (Phase 0 has none — see those classes'
 * javadoc). The project has no {@code module-info.java} anywhere, so this split package is plain
 * classpath-mode Java, not a module violation.
 *
 * <p>The step-function driver (closure + consuming-transition + merge) duplicates {@code
 * LaurikariDFACacheTest}'s and {@code LaurikariPhase0SpikeTest}'s test-only driver verbatim, for
 * the same legitimate reason those two duplicate each other: Phase 0 has no shared production
 * wiring point, and this is disposable spike-measurement scaffolding, not a place to introduce a
 * shared abstraction ahead of Phase 1's actual generalization.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class LaurikariPhase0Benchmark {

  // --- SQL_ANSI/MYSQL/POSTGRESQL, mirrored verbatim from IastRegexpBenchmark.java:101-116 -------
  private static final String SQL_ANSI =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";
  private static final String SQL_MYSQL =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|\"(?:\\\\\"|[^\"])*\"|'(?:\\\\'|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";
  private static final String SQL_POSTGRESQL =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|\\$(?:[a-zA-Z_]\\w*)?\\$|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  // --- LONG-scale sqlMatch/mysqlMatch/postgresqlMatch, mirrored verbatim from
  // --- IastRegexpBenchmark.java's setup() (the third pick(scale, ...) argument = LONG).
  // -----------
  private static final String SQL_MATCH_LONG =
      "SELECT * FROM users WHERE "
          + "col_x = col_y AND ".repeat(2000)
          + "id = 42 AND name = 'Alice' AND balance = 1234.56";
  private static final String MYSQL_MATCH_LONG =
      "SELECT id, `name` FROM users WHERE "
          + "col_x = col_y AND ".repeat(2000)
          + "id = 1 AND email = 'user@example.com' AND active = 1";
  private static final String POSTGRESQL_MATCH_LONG =
      "SELECT * FROM docs WHERE "
          + "col_x = col_y AND ".repeat(2000)
          + "body = $$hello world$$ AND revision = 3";

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

  private static Built build(String pattern) throws Exception {
    NFA nfa = nfa(pattern, 0);
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

  private Built sqlAnsi;
  private Built sqlMysql;
  private Built sqlPostgresql;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    sqlAnsi = build(SQL_ANSI);
    sqlMysql = build(SQL_MYSQL);
    sqlPostgresql = build(SQL_POSTGRESQL);
  }

  @Benchmark
  public int laurikariSqlAnsiFindLong() {
    return sqlAnsi.cache.findLeftmostStart(SQL_MATCH_LONG, 0, sqlAnsi.step);
  }

  @Benchmark
  public int laurikariSqlMysqlFindLong() {
    return sqlMysql.cache.findLeftmostStart(MYSQL_MATCH_LONG, 0, sqlMysql.step);
  }

  @Benchmark
  public int laurikariSqlPostgresqlFindLong() {
    return sqlPostgresql.cache.findLeftmostStart(POSTGRESQL_MATCH_LONG, 0, sqlPostgresql.step);
  }
}
