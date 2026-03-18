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
package com.datadoghq.reggie.benchmark.suites;

import com.datadoghq.reggie.benchmark.corpus.CorpusManager;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmark for scanning large corpus files. Tests regex performance on realistic large-scale
 * text data.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 2)
@Measurement(iterations = 2, time = 2)
@Fork(1)
public class CorpusScanBenchmark {

  @Param({"GUTENBERG_SMALL", "ACCESS_LOG"})
  private CorpusManager.Corpus corpus;

  @Param({"email", "ipv4", "word"})
  private String patternType;

  private List<String> corpusLines;
  private Pattern jdkPattern;
  private ReggieMatcher reggieMatcher;

  @Setup
  public void setup() throws IOException {
    // Load corpus data
    CorpusManager manager = new CorpusManager();
    corpusLines = manager.streamLines(corpus, 10000).collect(Collectors.toList());

    // Select pattern based on type
    String pattern =
        switch (patternType) {
          case "email" -> "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
          case "ipv4" -> "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}";
          case "word" -> "\\btest\\b";
          default -> "\\w+";
        };

    jdkPattern = Pattern.compile(pattern);
    reggieMatcher = RuntimeCompiler.compile(pattern);
  }

  @Benchmark
  public long jdkScan() {
    return corpusLines.stream().filter(line -> jdkPattern.matcher(line).find()).count();
  }

  @Benchmark
  public long reggieScan() {
    return corpusLines.stream().filter(line -> reggieMatcher.find(line)).count();
  }
}
