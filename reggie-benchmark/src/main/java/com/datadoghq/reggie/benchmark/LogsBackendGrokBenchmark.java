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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Grok-like access-log parsing benchmark for logs-backend adoption work.
 *
 * <p>The pattern intentionally includes constructs used by grok-generated regexes:
 *
 * <ul>
 *   <li>atomic groups, e.g. {@code (?>...)}
 *   <li>{@code \Q...\E} quoted separators from {@code Pattern.quote()}
 *   <li>capture extraction for multiple fields
 * </ul>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
public class LogsBackendGrokBenchmark {

  private static final String MONTHS = "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)";

  private static final String ACCESS_LOG_PATTERN =
      "^"
          + "((?>\\d{1,3}\\.){3}\\d{1,3})"
          + " ([^ ]+)"
          + " ([^ ]+)"
          + " \\Q[\\E"
          + "(\\d{2}\\Q/\\E"
          + MONTHS
          + "\\Q/\\E\\d{4}\\Q:\\E\\d{2}\\Q:\\E\\d{2}\\Q:\\E\\d{2} [+-]\\d{4})"
          + "\\Q]\\E"
          + " \\\"(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS) ([^\\\"]*) (HTTP/\\d\\.\\d)\\\""
          + " (\\d{3})"
          + " (\\d+|-)"
          + " \\\"([^\\\"]*)\\\""
          + " \\\"([^\\\"]*)\\\""
          + "$";

  private static final String[] INPUTS = {
    "127.0.0.1 - frank [10/Oct/2000:13:55:36 -0700] \"GET /apache_pb.gif HTTP/1.0\" 200 2326 \"http://www.example.com/start.html\" \"Mozilla/4.08 [en] (Win98; I ;Nav)\"",
    "192.168.1.10 - jane [07/Feb/2026:09:14:03 +0000] \"POST /api/v1/orders?id=123 HTTP/1.1\" 201 842 \"-\" \"curl/8.0.1\"",
    "10.0.44.12 - svc [25/Dec/2025:23:59:59 +0100] \"DELETE /resource/abc-def HTTP/2.0\" 204 - \"https://example.org/ref\" \"logs-backend-benchmark\"",
    "not an access log line"
  };

  private Pattern jdkPattern;
  private ReggieMatcher reggieMatcher;
  private int[] starts;
  private int[] ends;

  @Setup
  public void setup() {
    RuntimeCompiler.clearCache();
    jdkPattern = Pattern.compile(ACCESS_LOG_PATTERN);
    reggieMatcher = RuntimeCompiler.compile(ACCESS_LOG_PATTERN);
    starts = new int[12];
    ends = new int[12];
  }

  @Benchmark
  public int jdkParseAndExtract() {
    int total = 0;
    for (String input : INPUTS) {
      Matcher matcher = jdkPattern.matcher(input);
      if (matcher.matches()) {
        for (int group = 1; group <= 11; group++) {
          total += matcher.group(group).length();
        }
      }
    }
    return total;
  }

  @Benchmark
  public int jdkParseBoundsOnly() {
    int total = 0;
    for (String input : INPUTS) {
      Matcher matcher = jdkPattern.matcher(input);
      if (matcher.matches()) {
        for (int group = 1; group <= 11; group++) {
          total += matcher.start(group) + matcher.end(group);
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchResultParseAndExtract() {
    int total = 0;
    for (String input : INPUTS) {
      MatchResult match = reggieMatcher.match(input);
      if (match != null) {
        for (int group = 1; group <= 11; group++) {
          total += match.group(group).length();
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchResultBoundsOnly() {
    int total = 0;
    for (String input : INPUTS) {
      MatchResult match = reggieMatcher.match(input);
      if (match != null) {
        for (int group = 1; group <= 11; group++) {
          total += match.start(group) + match.end(group);
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchIntoParseAndExtract() {
    int total = 0;
    for (String input : INPUTS) {
      if (reggieMatcher.matchInto(input, starts, ends)) {
        for (int group = 1; group <= 11; group++) {
          total += input.substring(starts[group], ends[group]).length();
        }
      }
    }
    return total;
  }

  @Benchmark
  public int reggieMatchIntoBoundsOnly() {
    int total = 0;
    for (String input : INPUTS) {
      if (reggieMatcher.matchInto(input, starts, ends)) {
        for (int group = 1; group <= 11; group++) {
          total += starts[group] + ends[group];
        }
      }
    }
    return total;
  }
}
