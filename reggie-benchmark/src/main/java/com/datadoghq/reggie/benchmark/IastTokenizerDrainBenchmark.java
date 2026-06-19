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

import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import com.datadoghq.reggie.runtime.RuntimeCompiler;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.openjdk.jmh.annotations.*;

/**
 * Representative IAST tokenizer benchmark mirroring dd-trace-java's SensitiveTokenizerBenchmark:
 * MALFORMED payloads (512/1024/2048 bytes) that are FULLY DRAINED (find ALL matches across the
 * payload, advancing past each). This exercises JDK's catastrophic backtracking, which the
 * tiny-input single-find() {@link IastRegexpBenchmark} hides.
 *
 * <p>Compares Reggie vs RE2J vs JDK. Each drain body is wrapped in a try/catch returning -1 on any
 * Throwable (JDK may stack-overflow / blow up on pathological scenarios — same as dd-trace).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IastTokenizerDrainBenchmark {

  // --- Pattern strings (verbatim from IastRegexpBenchmark) ---
  private static final String COMMAND = "(?s)(?m)^(?:\\s*(?:sudo|doas)\\s+)?\\b\\S+\\b\\s*(.*)";

  private static final String URL_JDK =
      "^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)";
  private static final String URL_RE2J =
      "^(?:[^:]+:)?//(?P<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?P<QUERY>[^?#&]+)";

  private static final String SQL_ANSI =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  private static final String SQL_MYSQL =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|\"(?:\\\\\"|[^\"])*\"|'(?:\\\\'|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  // LDAP tokenizer: lazy literal extraction. JDK/Reggie use (?<name>); re2j uses (?P<name>).
  private static final String LDAP_JDK = "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)";
  private static final String LDAP_RE2J = "\\(.*?(?:~=|=|<=|>=)(?P<LITERAL>[^)]+)\\)";

  public enum Scenario {
    LDAP_UNCLOSED_FILTER,
    LDAP_NESTED_OPEN_EQ,
    SQL_ANSI_UNTERMINATED,
    SQL_MYSQL_UNTERMINATED,
    URL_QUERY,
    URL_QUESTION_RUN,
    URL_AUTHORITY,
    COMMAND_SINGLE_TOKEN,
    COMMAND_BLANK_LINES
  }

  @Param({"512", "1024", "2048"})
  int size;

  @Param({
    "LDAP_UNCLOSED_FILTER",
    "LDAP_NESTED_OPEN_EQ",
    "SQL_ANSI_UNTERMINATED",
    "SQL_MYSQL_UNTERMINATED",
    "URL_QUERY",
    "URL_QUESTION_RUN",
    "URL_AUTHORITY",
    "COMMAND_SINGLE_TOKEN",
    "COMMAND_BLANK_LINES"
  })
  Scenario scenario;

  private String payload;
  private Pattern jdkPat;
  private com.google.re2j.Pattern re2jPat;
  private ReggieMatcher reggieMatcher;

  private static String repeat(char c, int count) {
    return String.valueOf(c).repeat(Math.max(0, count));
  }

  private static String buildPayload(Scenario s, int n) {
    switch (s) {
      case LDAP_UNCLOSED_FILTER:
        return "(" + repeat('=', n - 1);
      case LDAP_NESTED_OPEN_EQ:
        return "(=".repeat((n + 1) / 2).substring(0, n);
      case SQL_ANSI_UNTERMINATED:
        return "'" + repeat('a', n - 1);
      case SQL_MYSQL_UNTERMINATED:
        return "\"" + repeat('a', n - 1);
      case URL_QUERY:
        return "http://h/p?" + repeat('a', n - 11);
      case URL_QUESTION_RUN:
        return repeat('?', n);
      case URL_AUTHORITY:
        return "//" + repeat('a', n - 2);
      case COMMAND_SINGLE_TOKEN:
        return "cmd " + repeat('a', n - 4);
      case COMMAND_BLANK_LINES:
        return repeat('\n', n);
      default:
        throw new IllegalArgumentException(s.name());
    }
  }

  private static String jdkPatternFor(Scenario s) {
    switch (s) {
      case LDAP_UNCLOSED_FILTER:
      case LDAP_NESTED_OPEN_EQ:
        return LDAP_JDK;
      case SQL_ANSI_UNTERMINATED:
        return SQL_ANSI;
      case SQL_MYSQL_UNTERMINATED:
        return SQL_MYSQL;
      case URL_QUERY:
      case URL_QUESTION_RUN:
      case URL_AUTHORITY:
        return URL_JDK;
      case COMMAND_SINGLE_TOKEN:
      case COMMAND_BLANK_LINES:
        return COMMAND;
      default:
        throw new IllegalArgumentException(s.name());
    }
  }

  private static String re2jPatternFor(Scenario s) {
    switch (s) {
      case LDAP_UNCLOSED_FILTER:
      case LDAP_NESTED_OPEN_EQ:
        return LDAP_RE2J;
      case URL_QUERY:
      case URL_QUESTION_RUN:
      case URL_AUTHORITY:
        return URL_RE2J;
      default:
        return jdkPatternFor(s);
    }
  }

  // LDAP is MULTILINE-compiled per the task; the others carry inline flags already.
  private static boolean isLdap(Scenario s) {
    return s == Scenario.LDAP_UNCLOSED_FILTER || s == Scenario.LDAP_NESTED_OPEN_EQ;
  }

  private static boolean printed = false;

  @Setup
  public void setup() {
    payload = buildPayload(scenario, size);

    String jp = jdkPatternFor(scenario);
    String rp = re2jPatternFor(scenario);
    if (isLdap(scenario)) {
      jdkPat = Pattern.compile(jp, Pattern.MULTILINE);
      re2jPat = com.google.re2j.Pattern.compile(rp, com.google.re2j.Pattern.MULTILINE);
      reggieMatcher =
          RuntimeCompiler.compile("(?m)" + jp, ReggieOptions.builder().allowJdkFallback().build());
    } else {
      jdkPat = Pattern.compile(jp);
      re2jPat = com.google.re2j.Pattern.compile(rp);
      reggieMatcher = RuntimeCompiler.compile(jp);
    }

    synchronized (IastTokenizerDrainBenchmark.class) {
      if (!printed) {
        printed = true;
        System.out.println("=== Reggie matcher class per pattern ===");
        report("COMMAND", COMMAND);
        report("URL", URL_JDK);
        report("SQL_ANSI", SQL_ANSI);
        report("SQL_MYSQL", SQL_MYSQL);
        report("LDAP", "(?m)" + LDAP_JDK);
        System.out.println("=========================================");
      }
    }
  }

  private static void report(String name, String pattern) {
    try {
      String cls = RuntimeCompiler.compile(pattern).getClass().getSimpleName();
      System.out.println("  " + name + " -> " + cls);
    } catch (Throwable t) {
      System.out.println("  " + name + " -> COMPILE_ERROR: " + t);
    }
  }

  @Benchmark
  public long jdkDrain() {
    try {
      java.util.regex.Matcher m = jdkPat.matcher(payload);
      long c = 0;
      while (m.find()) {
        c++;
      }
      return c;
    } catch (Throwable t) {
      return -1;
    }
  }

  @Benchmark
  public long re2jDrain() {
    try {
      com.google.re2j.Matcher m = re2jPat.matcher(payload);
      long c = 0;
      while (m.find()) {
        c++;
      }
      return c;
    } catch (Throwable t) {
      return -1;
    }
  }

  @Benchmark
  public long reggieDrain() {
    try {
      int len = payload.length();
      int pos = 0;
      long c = 0;
      while (pos <= len) {
        MatchResult r = reggieMatcher.findMatchFrom(payload, pos);
        if (r == null) {
          break;
        }
        c++;
        pos = r.end() > r.start() ? r.end() : r.end() + 1;
      }
      return c;
    } catch (Throwable t) {
      return -1;
    }
  }
}
