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
 * JMH benchmark for patterns from dd-trace-java PR #11649, which migrated IAST evidence-redaction
 * and the query obfuscator from JDK Pattern to RE2J for linear-time matching.
 *
 * <p>All patterns use find() semantics, matching how the tokenizers and obfuscator scan inputs.
 *
 * <p>Previously excluded (lazy quantifiers unsupported by Reggie): lazy quantifiers are now
 * supported via PIKEVM_CAPTURE (ThompsonBuilder lazyAware=true). The LDAP tokenizer pattern {@code
 * \(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\)} has been re-enabled.
 *
 * <p>Still excluded: SQL Oracle tokenizer ({@code q'<.*?>'} and similar q-quoted literal variants)
 * — these require additional work to enable in the benchmark.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IastRegexpBenchmark {

  // --- Pattern strings ---

  // CommandRegexpTokenizer: extracts the argument list of a shell command.
  // Original flags: MULTILINE | DOTALL — expressed as inline (?s)(?m) for Reggie.
  private static final String COMMAND = "(?s)(?m)^(?:\\s*(?:sudo|doas)\\s+)?\\b\\S+\\b\\s*(.*)";

  // UrlRegexpTokenizer: matches credentials in the authority component, or sensitive query params.
  // Named groups: JDK/Reggie use (?<name>), re2j uses (?P<name>).
  private static final String URL_JDK =
      "^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)";
  private static final String URL_RE2J =
      "^(?:[^:]+:)?//(?P<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?P<QUERY>[^?#&]+)";

  // SqlRegexpTokenizer — ANSI dialect: numeric literals, string literals, line/block comments.
  // Original flags: CASE_INSENSITIVE | MULTILINE — expressed as inline (?i)(?m).
  private static final String SQL_ANSI =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  // SqlRegexpTokenizer — MySQL dialect: adds double-quoted and backslash-escaped string literals.
  private static final String SQL_MYSQL =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|\"(?:\\\\\"|[^\"])*\"|'(?:\\\\'|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  // SqlRegexpTokenizer — PostgreSQL dialect: ANSI plus dollar-quoted literal openers ($tag$).
  private static final String SQL_POSTGRESQL =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|\\$(?:[a-zA-Z_]\\w*)?\\$|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

  // LdapRegexpTokenizer: matches LDAP filter attribute-value pairs with a lazy quantifier.
  // Previously excluded because lazy quantifiers were unsupported; now routed to PIKEVM_CAPTURE.
  private static final String LDAP_JDK = "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)";

  // QueryObfuscator: redacts credentials, tokens, and API keys in HTTP query strings.
  // Already has (?i) inline.
  private static final String QUERY_OBFUSCATOR =
      "(?i)(?:(?:\"|%22)?)(?:(?:old[-_]?|new[-_]?)?p(?:ass)?w(?:or)?d(?:1|2)?"
          + "|pass(?:[-_]?phrase)?|secret"
          + "|(?:api[-_]?|private[-_]?|public[-_]?|access[-_]?|secret[-_]?|app(?:lication)?[-_]?)key(?:[-_]?id)?"
          + "|token|consumer[-_]?(?:id|key|secret)|sign(?:ed|ature)?|auth(?:entication|orization)?)"
          + "(?:(?:\\s|%20)*(?:=|%3D)[^&]+"
          + "|(?:\"|%22)(?:\\s|%20)*(?::|%3A)(?:\\s|%20)*(?:\"|%22)(?:%2[^2]|%[^2]|[^\"%])+(?:\"|%22))"
          + "|(?:bearer(?:\\s|%20)+[a-z0-9._\\-]+"
          + "|token(?::|%3A)[a-z0-9]{13}"
          + "|gh[opsu]_[0-9a-zA-Z]{36}"
          + "|ey[I-L](?:[\\w=-]|%3D)+\\.ey[I-L](?:[\\w=-]|%3D)+(?:\\.(?:[\\w.+/=-]|%3D|%2F|%2B)+)?"
          + "|-{5}BEGIN(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY-{5}[^\\-]+-{5}END(?:[a-z\\s]|%20)+PRIVATE(?:\\s|%20)KEY(?:-{5})?(?:\\n|%0A)?"
          + "|(?:ssh-(?:rsa|dss)|ecdsa-[a-z0-9]+-[a-z0-9]+)(?:\\s|%20|%09)+(?:[a-z0-9/.+]|%2F|%5C|%2B){100,}(?:=|%3D)*(?:(?:\\s|%20|%09)+[a-z0-9._-]+)?)";

  // --- Reggie matchers ---
  private ReggieMatcher reggieCommand;
  private ReggieMatcher reggieUrl;
  private ReggieMatcher reggieSqlAnsi;
  private ReggieMatcher reggieSqlMysql;
  private ReggieMatcher reggieSqlPostgresql;
  private ReggieMatcher reggieQueryObfuscator;
  private ReggieMatcher reggieLdap;

  // --- JDK patterns ---
  private Pattern jdkCommand;
  private Pattern jdkUrl;
  private Pattern jdkSqlAnsi;
  private Pattern jdkSqlMysql;
  private Pattern jdkSqlPostgresql;
  private Pattern jdkQueryObfuscator;
  private Pattern jdkLdap;

  // --- RE2J patterns ---
  private com.google.re2j.Pattern re2jCommand;
  private com.google.re2j.Pattern re2jUrl;
  private com.google.re2j.Pattern re2jSqlAnsi;
  private com.google.re2j.Pattern re2jSqlMysql;
  private com.google.re2j.Pattern re2jSqlPostgresql;
  private com.google.re2j.Pattern re2jQueryObfuscator;
  private com.google.re2j.Pattern re2jLdap;

  // --- Test inputs ---

  // Command: a sudo invocation with arguments (find() always matches — tests match-path cost)
  private static final String COMMAND_INPUT = "sudo apt-get install -y curl --verbose";

  // URL: authority credentials match vs query-param match vs no match
  private static final String URL_AUTH_MATCH = "https://admin:s3cr3t@internal.corp/api/v1/health";
  private static final String URL_QUERY_MATCH =
      "https://api.example.com/search?q=hello&password=hunter2&page=1";
  private static final String URL_NO_MATCH = "https://api.example.com/health";

  // SQL ANSI: query with literals to redact vs schema-only query with no literals
  private static final String SQL_MATCH =
      "SELECT * FROM users WHERE id = 42 AND name = 'Alice' AND balance = 1234.56";
  private static final String SQL_NO_MATCH = "SELECT id, name, email FROM users ORDER BY id";

  // SQL MySQL: MySQL-flavored query with both quote styles
  private static final String MYSQL_MATCH =
      "SELECT id, `name` FROM users WHERE id = 1 AND email = 'user@example.com' AND active = 1";
  private static final String MYSQL_NO_MATCH = "SELECT id, name FROM users LIMIT 10";

  // SQL PostgreSQL: query with dollar-quoted literal
  private static final String POSTGRESQL_MATCH =
      "SELECT * FROM docs WHERE body = $$hello world$$ AND revision = 3";
  private static final String POSTGRESQL_NO_MATCH = "SELECT id, title FROM docs ORDER BY id";

  // QueryObfuscator: HTTP query string with API key vs benign params
  private static final String QOBF_MATCH = "api_key=abc123def456&user=alice&action=view";
  private static final String QOBF_NO_MATCH = "user=alice&action=view&page=1&sort=asc";

  // LDAP tokenizer: attribute-value pair (match) vs plain text (no match)
  private static final String LDAP_MATCH = "(uid=jsmith)";
  private static final String LDAP_NO_MATCH = "uid jsmith";

  @Setup
  public void setup() {
    reggieCommand = RuntimeCompiler.compile(COMMAND);
    jdkCommand = Pattern.compile(COMMAND);
    re2jCommand = com.google.re2j.Pattern.compile(COMMAND);

    reggieUrl = RuntimeCompiler.compile(URL_JDK);
    jdkUrl = Pattern.compile(URL_JDK);
    re2jUrl = com.google.re2j.Pattern.compile(URL_RE2J);

    reggieSqlAnsi = RuntimeCompiler.compile(SQL_ANSI);
    jdkSqlAnsi = Pattern.compile(SQL_ANSI);
    re2jSqlAnsi = com.google.re2j.Pattern.compile(SQL_ANSI);

    reggieSqlMysql = RuntimeCompiler.compile(SQL_MYSQL);
    jdkSqlMysql = Pattern.compile(SQL_MYSQL);
    re2jSqlMysql = com.google.re2j.Pattern.compile(SQL_MYSQL);

    reggieSqlPostgresql = RuntimeCompiler.compile(SQL_POSTGRESQL);
    jdkSqlPostgresql = Pattern.compile(SQL_POSTGRESQL);
    re2jSqlPostgresql = com.google.re2j.Pattern.compile(SQL_POSTGRESQL);

    reggieQueryObfuscator = RuntimeCompiler.compile(QUERY_OBFUSCATOR);
    jdkQueryObfuscator = Pattern.compile(QUERY_OBFUSCATOR);
    re2jQueryObfuscator = com.google.re2j.Pattern.compile(QUERY_OBFUSCATOR);

    reggieLdap = RuntimeCompiler.compile(LDAP_JDK);
    jdkLdap = Pattern.compile(LDAP_JDK);
    re2jLdap = com.google.re2j.Pattern.compile(LDAP_JDK);
  }

  // ===== Command =====

  @Benchmark
  public boolean reggieCommandFind() {
    return reggieCommand.find(COMMAND_INPUT);
  }

  @Benchmark
  public boolean jdkCommandFind() {
    return jdkCommand.matcher(COMMAND_INPUT).find();
  }

  @Benchmark
  public boolean re2jCommandFind() {
    return re2jCommand.matcher(COMMAND_INPUT).find();
  }

  // ----- Command capture (span extraction) -----

  @Benchmark
  public long reggieCommandCapture() {
    MatchResult r = reggieCommand.findMatch(COMMAND_INPUT);
    if (r == null) {
      return -1L;
    }
    return (long) r.start(1) + r.end(1);
  }

  @Benchmark
  public long jdkCommandCapture() {
    java.util.regex.Matcher m = jdkCommand.matcher(COMMAND_INPUT);
    if (!m.find()) {
      return -1L;
    }
    return (long) m.start(1) + m.end(1);
  }

  @Benchmark
  public long re2jCommandCapture() {
    com.google.re2j.Matcher m = re2jCommand.matcher(COMMAND_INPUT);
    if (!m.find()) {
      return -1L;
    }
    return (long) m.start(1) + m.end(1);
  }

  // ===== URL =====

  @Benchmark
  public boolean reggieUrlAuthFind() {
    return reggieUrl.find(URL_AUTH_MATCH);
  }

  @Benchmark
  public boolean jdkUrlAuthFind() {
    return jdkUrl.matcher(URL_AUTH_MATCH).find();
  }

  @Benchmark
  public boolean re2jUrlAuthFind() {
    return re2jUrl.matcher(URL_AUTH_MATCH).find();
  }

  @Benchmark
  public boolean reggieUrlQueryFind() {
    return reggieUrl.find(URL_QUERY_MATCH);
  }

  @Benchmark
  public boolean jdkUrlQueryFind() {
    return jdkUrl.matcher(URL_QUERY_MATCH).find();
  }

  @Benchmark
  public boolean re2jUrlQueryFind() {
    return re2jUrl.matcher(URL_QUERY_MATCH).find();
  }

  // ----- URL capture (span extraction) -----
  // Group 1 = AUTHORITY (auth branch); groups 2 and 3 = param-name and QUERY (query branch).
  // Sum all participating group offsets; -1 (non-participating) is skipped.

  private static long sumGroupOffsets(MatchResult r, int maxGroup) {
    long sum = 0;
    for (int g = 1; g <= maxGroup; g++) {
      int s = r.start(g);
      if (s >= 0) {
        sum += s + r.end(g);
      }
    }
    return sum;
  }

  private static long sumGroupOffsets(java.util.regex.MatchResult r, int maxGroup) {
    long sum = 0;
    for (int g = 1; g <= maxGroup; g++) {
      int s = r.start(g);
      if (s >= 0) {
        sum += s + r.end(g);
      }
    }
    return sum;
  }

  private static long sumGroupOffsets(com.google.re2j.Matcher m, int maxGroup) {
    long sum = 0;
    for (int g = 1; g <= maxGroup; g++) {
      int s = m.start(g);
      if (s >= 0) {
        sum += s + m.end(g);
      }
    }
    return sum;
  }

  @Benchmark
  public long reggieUrlAuthCapture() {
    MatchResult r = reggieUrl.findMatch(URL_AUTH_MATCH);
    if (r == null) {
      return -1L;
    }
    return sumGroupOffsets(r, 3);
  }

  @Benchmark
  public long jdkUrlAuthCapture() {
    java.util.regex.Matcher m = jdkUrl.matcher(URL_AUTH_MATCH);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public long reggieUrlQueryCapture() {
    MatchResult r = reggieUrl.findMatch(URL_QUERY_MATCH);
    if (r == null) {
      return -1L;
    }
    return sumGroupOffsets(r, 3);
  }

  @Benchmark
  public long jdkUrlQueryCapture() {
    java.util.regex.Matcher m = jdkUrl.matcher(URL_QUERY_MATCH);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public long re2jUrlAuthCapture() {
    com.google.re2j.Matcher m = re2jUrl.matcher(URL_AUTH_MATCH);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public long re2jUrlQueryCapture() {
    com.google.re2j.Matcher m = re2jUrl.matcher(URL_QUERY_MATCH);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public boolean reggieUrlNoMatch() {
    return reggieUrl.find(URL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkUrlNoMatch() {
    return jdkUrl.matcher(URL_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jUrlNoMatch() {
    return re2jUrl.matcher(URL_NO_MATCH).find();
  }

  // ===== SQL ANSI =====

  @Benchmark
  public boolean reggieSqlAnsiFind() {
    return reggieSqlAnsi.find(SQL_MATCH);
  }

  @Benchmark
  public boolean jdkSqlAnsiFind() {
    return jdkSqlAnsi.matcher(SQL_MATCH).find();
  }

  @Benchmark
  public boolean re2jSqlAnsiFind() {
    return re2jSqlAnsi.matcher(SQL_MATCH).find();
  }

  @Benchmark
  public boolean reggieSqlAnsiNoMatch() {
    return reggieSqlAnsi.find(SQL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkSqlAnsiNoMatch() {
    return jdkSqlAnsi.matcher(SQL_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jSqlAnsiNoMatch() {
    return re2jSqlAnsi.matcher(SQL_NO_MATCH).find();
  }

  // ===== SQL MySQL =====

  @Benchmark
  public boolean reggieSqlMysqlFind() {
    return reggieSqlMysql.find(MYSQL_MATCH);
  }

  @Benchmark
  public boolean jdkSqlMysqlFind() {
    return jdkSqlMysql.matcher(MYSQL_MATCH).find();
  }

  @Benchmark
  public boolean re2jSqlMysqlFind() {
    return re2jSqlMysql.matcher(MYSQL_MATCH).find();
  }

  @Benchmark
  public boolean reggieSqlMysqlNoMatch() {
    return reggieSqlMysql.find(MYSQL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkSqlMysqlNoMatch() {
    return jdkSqlMysql.matcher(MYSQL_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jSqlMysqlNoMatch() {
    return re2jSqlMysql.matcher(MYSQL_NO_MATCH).find();
  }

  // ===== SQL PostgreSQL =====

  @Benchmark
  public boolean reggieSqlPostgresqlFind() {
    return reggieSqlPostgresql.find(POSTGRESQL_MATCH);
  }

  @Benchmark
  public boolean jdkSqlPostgresqlFind() {
    return jdkSqlPostgresql.matcher(POSTGRESQL_MATCH).find();
  }

  @Benchmark
  public boolean re2jSqlPostgresqlFind() {
    return re2jSqlPostgresql.matcher(POSTGRESQL_MATCH).find();
  }

  @Benchmark
  public boolean reggieSqlPostgresqlNoMatch() {
    return reggieSqlPostgresql.find(POSTGRESQL_NO_MATCH);
  }

  @Benchmark
  public boolean jdkSqlPostgresqlNoMatch() {
    return jdkSqlPostgresql.matcher(POSTGRESQL_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jSqlPostgresqlNoMatch() {
    return re2jSqlPostgresql.matcher(POSTGRESQL_NO_MATCH).find();
  }

  // ===== Query Obfuscator =====

  @Benchmark
  public boolean reggieQueryObfuscatorFind() {
    return reggieQueryObfuscator.find(QOBF_MATCH);
  }

  @Benchmark
  public boolean jdkQueryObfuscatorFind() {
    return jdkQueryObfuscator.matcher(QOBF_MATCH).find();
  }

  @Benchmark
  public boolean re2jQueryObfuscatorFind() {
    return re2jQueryObfuscator.matcher(QOBF_MATCH).find();
  }

  @Benchmark
  public boolean reggieQueryObfuscatorNoMatch() {
    return reggieQueryObfuscator.find(QOBF_NO_MATCH);
  }

  @Benchmark
  public boolean jdkQueryObfuscatorNoMatch() {
    return jdkQueryObfuscator.matcher(QOBF_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jQueryObfuscatorNoMatch() {
    return re2jQueryObfuscator.matcher(QOBF_NO_MATCH).find();
  }

  // ===== LDAP tokenizer =====

  @Benchmark
  public boolean reggieLdapFind() {
    return reggieLdap.find(LDAP_MATCH);
  }

  @Benchmark
  public boolean jdkLdapFind() {
    return jdkLdap.matcher(LDAP_MATCH).find();
  }

  @Benchmark
  public boolean re2jLdapFind() {
    return re2jLdap.matcher(LDAP_MATCH).find();
  }

  @Benchmark
  public boolean reggieLdapNoMatch() {
    return reggieLdap.find(LDAP_NO_MATCH);
  }

  @Benchmark
  public boolean jdkLdapNoMatch() {
    return jdkLdap.matcher(LDAP_NO_MATCH).find();
  }

  @Benchmark
  public boolean re2jLdapNoMatch() {
    return re2jLdap.matcher(LDAP_NO_MATCH).find();
  }
}
