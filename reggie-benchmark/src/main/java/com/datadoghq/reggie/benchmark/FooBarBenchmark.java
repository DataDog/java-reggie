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

import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Benchmark for (foo)(bar) pattern — the ONEPASS_NFA regression case.
 *
 * <p>Before the fix, (foo)(bar) routed to ONEPASS_NFA which uses O(n^2) findFrom with substring
 * allocation. After the fix it routes to SPECIALIZED_FIXED_SEQUENCE with O(n) linear scan and no
 * allocation.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(3)
public class FooBarBenchmark {

  private static final String PATTERN = "(foo)(bar)";
  private static final String INPUT_FIND = "x foobar y";
  private static final String INPUT_MATCH = "foobar";
  private static final String INPUT_NO_MATCH = "foobaz";

  private ReggieMatcher reggie;
  private Pattern jdk;

  @Setup(Level.Trial)
  public void setup() {
    reggie = RuntimeCompiler.compile(PATTERN);
    jdk = Pattern.compile(PATTERN);
  }

  @Benchmark
  public boolean reggieFind() {
    return reggie.find(INPUT_FIND);
  }

  @Benchmark
  public boolean jdkFind() {
    return jdk.matcher(INPUT_FIND).find();
  }

  @Benchmark
  public boolean reggieMatch() {
    return reggie.matches(INPUT_MATCH);
  }

  @Benchmark
  public boolean jdkMatch() {
    return jdk.matcher(INPUT_MATCH).matches();
  }

  @Benchmark
  public boolean reggieNoMatch() {
    return reggie.find(INPUT_NO_MATCH);
  }

  @Benchmark
  public boolean jdkNoMatch() {
    return jdk.matcher(INPUT_NO_MATCH).find();
  }
}
