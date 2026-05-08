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
import org.openjdk.jmh.annotations.*;

/**
 * JMH benchmarks for named-group extraction: group(String), start(String), end(String). Compares
 * Reggie's named-group API against JDK and RE2J, and measures overhead of name lookup vs index
 * lookup on the same pattern.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class NamedGroupExtractionBenchmark {

  // Named phone pattern — equivalent named variant of GroupExtractionBenchmark's phone pattern
  private static final String NAMED_PHONE_PATTERN =
      "(?<area>\\d{3})-(?<exchange>\\d{3})-(?<subscriber>\\d{4})";
  private static final String PHONE_INPUT = "123-456-7890";

  // Named host:port pattern — common log/config parsing use case
  private static final String HOST_PORT_PATTERN = "(?<host>[\\w.]+):(?<port>\\d+)";
  private static final String HOST_PORT_INPUT = "example.com:8080";

  // Named log-line pattern with three fields
  private static final String LOG_PATTERN =
      "(?<level>\\w+)\\s+(?<logger>[\\w.]+)\\s+-\\s+(?<message>.+)";
  private static final String LOG_INPUT = "INFO  com.example.App - Application started";

  // JDK patterns
  private Pattern jdkPhone;
  private Pattern jdkHostPort;
  private Pattern jdkLog;

  // RE2J patterns
  private com.google.re2j.Pattern re2jPhone;
  private com.google.re2j.Pattern re2jHostPort;
  private com.google.re2j.Pattern re2jLog;

  // Reggie matchers
  private ReggieMatcher reggiePhone;
  private ReggieMatcher reggieHostPort;
  private ReggieMatcher reggieLog;

  @Setup
  public void setup() {
    jdkPhone = Pattern.compile(NAMED_PHONE_PATTERN);
    jdkHostPort = Pattern.compile(HOST_PORT_PATTERN);
    jdkLog = Pattern.compile(LOG_PATTERN);

    re2jPhone = com.google.re2j.Pattern.compile(NAMED_PHONE_PATTERN);
    re2jHostPort = com.google.re2j.Pattern.compile(HOST_PORT_PATTERN);
    re2jLog = com.google.re2j.Pattern.compile(LOG_PATTERN);

    reggiePhone = RuntimeCompiler.compile(NAMED_PHONE_PATTERN);
    reggieHostPort = RuntimeCompiler.compile(HOST_PORT_PATTERN);
    reggieLog = RuntimeCompiler.compile(LOG_PATTERN);
  }

  // ===== Phone: group(String name) =====

  @Benchmark
  public String[] reggiePhoneByName() {
    MatchResult r = reggiePhone.match(PHONE_INPUT);
    if (r == null) return null;
    return new String[] {r.group("area"), r.group("exchange"), r.group("subscriber")};
  }

  @Benchmark
  public String[] reggiePhoneByIndex() {
    MatchResult r = reggiePhone.match(PHONE_INPUT);
    if (r == null) return null;
    return new String[] {r.group(1), r.group(2), r.group(3)};
  }

  @Benchmark
  public String[] jdkPhoneByName() {
    Matcher m = jdkPhone.matcher(PHONE_INPUT);
    if (!m.matches()) return null;
    return new String[] {m.group("area"), m.group("exchange"), m.group("subscriber")};
  }

  @Benchmark
  public String[] re2jPhoneByName() {
    com.google.re2j.Matcher m = re2jPhone.matcher(PHONE_INPUT);
    if (!m.matches()) return null;
    return new String[] {m.group("area"), m.group("exchange"), m.group("subscriber")};
  }

  // ===== host:port: group(String name) =====

  @Benchmark
  public String[] reggieHostPortByName() {
    MatchResult r = reggieHostPort.match(HOST_PORT_INPUT);
    if (r == null) return null;
    return new String[] {r.group("host"), r.group("port")};
  }

  @Benchmark
  public String[] reggieHostPortByIndex() {
    MatchResult r = reggieHostPort.match(HOST_PORT_INPUT);
    if (r == null) return null;
    return new String[] {r.group(1), r.group(2)};
  }

  @Benchmark
  public String[] jdkHostPortByName() {
    Matcher m = jdkHostPort.matcher(HOST_PORT_INPUT);
    if (!m.matches()) return null;
    return new String[] {m.group("host"), m.group("port")};
  }

  @Benchmark
  public String[] re2jHostPortByName() {
    com.google.re2j.Matcher m = re2jHostPort.matcher(HOST_PORT_INPUT);
    if (!m.matches()) return null;
    return new String[] {m.group("host"), m.group("port")};
  }

  // ===== Log line: start(String)/end(String) for span extraction =====

  @Benchmark
  public int[] reggieLogSpansByName() {
    MatchResult r = reggieLog.match(LOG_INPUT);
    if (r == null) return null;
    return new int[] {
      r.start("level"), r.end("level"),
      r.start("logger"), r.end("logger"),
      r.start("message"), r.end("message")
    };
  }

  @Benchmark
  public int[] reggieLogSpansByIndex() {
    MatchResult r = reggieLog.match(LOG_INPUT);
    if (r == null) return null;
    return new int[] {
      r.start(1), r.end(1),
      r.start(2), r.end(2),
      r.start(3), r.end(3)
    };
  }

  @Benchmark
  public int[] jdkLogSpansByName() {
    Matcher m = jdkLog.matcher(LOG_INPUT);
    if (!m.matches()) return null;
    return new int[] {
      m.start("level"), m.end("level"),
      m.start("logger"), m.end("logger"),
      m.start("message"), m.end("message")
    };
  }

  // ===== findMatch with named groups =====

  @Benchmark
  public String[] reggieHostPortFindByName() {
    MatchResult r = reggieHostPort.findMatch("connect to example.com:8080 for service");
    if (r == null) return null;
    return new String[] {r.group("host"), r.group("port")};
  }

  @Benchmark
  public String[] jdkHostPortFindByName() {
    Matcher m = jdkHostPort.matcher("connect to example.com:8080 for service");
    if (!m.find()) return null;
    return new String[] {m.group("host"), m.group("port")};
  }
}
