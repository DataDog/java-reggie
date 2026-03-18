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

import com.datadoghq.reggie.integration.testdata.CommonPatternsLoader;
import com.datadoghq.reggie.integration.testdata.TestCase;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmark for common regex patterns. Tests performance across email, URL, IP address, phone
 * number, and other common patterns.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class CommonPatternsBenchmark {

  @Param({"email_simple", "url_http", "ipv4", "phone_us", "uuid", "date_iso8601"})
  private String patternName;

  @Param({"match", "no_match"})
  private String scenario;

  private Pattern jdkPattern;
  private ReggieMatcher reggieMatcher;
  private String testInput;

  @Setup
  public void setup() throws Exception {
    // Load common patterns from test suite
    CommonPatternsLoader loader = new CommonPatternsLoader();
    List<TestCase> allTests = loader.load();

    // Find test case matching pattern name and scenario
    Optional<TestCase> testCase =
        allTests.stream()
            .filter(tc -> tc.source().equals("common"))
            .filter(tc -> tc.pattern().contains(patternName) || matchesPatternName(tc))
            .filter(tc -> tc.shouldMatch() == scenario.equals("match"))
            .findFirst();

    String pattern;
    if (testCase.isPresent()) {
      TestCase tc = testCase.get();
      testInput = tc.input();
      pattern = tc.pattern();
    } else {
      // Fallback to simple pattern if not found
      testInput = "test@example.com";
      pattern = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}";
    }

    jdkPattern = Pattern.compile(pattern);
    reggieMatcher = RuntimeCompiler.compile(pattern);
  }

  private boolean matchesPatternName(TestCase tc) {
    // Simple heuristic to match test cases to pattern names
    String input = tc.input().toLowerCase();
    return switch (patternName) {
      case "email_simple" -> input.contains("@");
      case "url_http" -> input.startsWith("http");
      case "ipv4" -> input.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
      case "phone_us" -> input.matches("\\d{3}-\\d{3}-\\d{4}");
      case "uuid" -> input.contains("-") && input.length() > 30;
      case "date_iso8601" -> input.matches("\\d{4}-\\d{2}-\\d{2}");
      default -> false;
    };
  }

  @Benchmark
  public boolean jdkMatch() {
    return jdkPattern.matcher(testInput).find();
  }

  @Benchmark
  public boolean reggieMatch() {
    return reggieMatcher.find(testInput);
  }
}
