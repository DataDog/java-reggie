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
 *
 * <p>Every match/no-match input is measured at three input scales via {@code @Param("scale")} —
 * SHORT (the original single-shot inputs this benchmark used to report on its own), MEDIUM, and
 * LONG — instead of a single point estimate. A single short input (12-90 chars) conflates "how good
 * is this engine on this pattern" with "how much does fixed per-call/per-step overhead dominate
 * this one specific tiny input (e.g. BITSTATE_CAPTURE's per-step visited-bitmap/job-stack
 * bookkeeping is a fixed cost per DFS step, so it looms disproportionately large on a 12-char LDAP
 * input regardless of how the engine scales). Reporting the ratio at three scales instead lets a
 * reader tell a fixed-cost artifact (ratio improves as input grows) apart from a genuine scaling
 * problem (ratio stays flat or worsens).
 *
 * <p>Two scaling strategies are used, chosen per-pattern to match what actually varies:
 *
 * <ul>
 *   <li><b>Matched-region growth</b> — for patterns whose match/capture itself can be arbitrarily
 *       long (COMMAND's trailing {@code (.*)}, URL's AUTHORITY credentials, LDAP's LITERAL value):
 *       MEDIUM/LONG grow the matched/captured span itself.
 *   <li><b>Scan-prefix growth</b> — for patterns whose match is anchored to a fixed-width literal
 *       found by scanning (URL's query-param branch, all three SQL dialects, QueryObfuscator,
 *       LDAP's no-match case): MEDIUM/LONG prepend benign filler text (containing none of the
 *       pattern's own trigger characters) before the actual match/non-match content, stressing
 *       find()'s scan/prefilter cost rather than the match itself.
 * </ul>
 *
 * <p>Scan-prefix filler for the SQL dialects and QueryObfuscator is capped well below {@code
 * BitStateMatcher}'s fallback budget ({@code stateCount * (input.length() + 1) <= 1 << 18}): those
 * patterns are pinned to {@code BITSTATE_CAPTURE} and have large NFAs (roughly 100 states for the
 * SQL dialects, ~3000 for QueryObfuscator), so filler long enough to blow the budget would make
 * {@code reggieSqlAnsi*}/{@code reggieSqlMysql*}/{@code reggieSqlPostgresql*}/{@code
 * reggieQueryObfuscator*} silently delegate to the PikeVM fallback and report its throughput
 * instead of BitState's.
 *
 * <p>Patterns are compiled once per trial in {@link #setup()} and never inside a {@code @Benchmark}
 * method, so compilation cost is excluded from measurements.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class IastRegexpBenchmark {

  /** Input scale for a benchmark trial; see the class doc for what each scale stresses. */
  public enum Scale {
    SHORT,
    MEDIUM,
    LONG
  }

  @Param({"SHORT", "MEDIUM", "LONG"})
  public Scale scale;

  private static String pick(Scale scale, String shortV, String mediumV, String longV) {
    switch (scale) {
      case SHORT:
        return shortV;
      case MEDIUM:
        return mediumV;
      case LONG:
        return longV;
      default:
        throw new IllegalArgumentException("Unhandled scale: " + scale);
    }
  }

  // --- Pattern strings ---

  // CommandRegexpTokenizer: extracts the argument list of a shell command.
  // Original flags: MULTILINE | DOTALL — expressed as inline (?s)(?m) for Reggie.
  private static final String COMMAND = "(?s)(?m)^(?:\\s*(?:sudo|doas)\\s+)?\\b\\S+\\b\\s*(.*)";

  // Bare word-boundary anchor, isolated from any surrounding tokenizer pattern — measures \b's
  // own cost rather than a larger pattern's. Scan-prefix growth: filler text (no "foo" substring)
  // is prepended before the matching "foo", stressing find()'s scan cost.
  private static final String BARE_WORD_BOUNDARY = "\\bfoo";

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
  private ReggieMatcher reggieBareWordBoundary;
  private ReggieMatcher reggieUrl;
  private ReggieMatcher reggieSqlAnsi;
  private ReggieMatcher reggieSqlMysql;
  private ReggieMatcher reggieSqlPostgresql;
  private ReggieMatcher reggieQueryObfuscator;
  private ReggieMatcher reggieLdap;

  // --- JDK patterns ---
  private Pattern jdkCommand;
  private Pattern jdkBareWordBoundary;
  private Pattern jdkUrl;
  private Pattern jdkSqlAnsi;
  private Pattern jdkSqlMysql;
  private Pattern jdkSqlPostgresql;
  private Pattern jdkQueryObfuscator;
  private Pattern jdkLdap;

  // --- RE2J patterns ---
  private com.google.re2j.Pattern re2jCommand;
  private com.google.re2j.Pattern re2jBareWordBoundary;
  private com.google.re2j.Pattern re2jUrl;
  private com.google.re2j.Pattern re2jSqlAnsi;
  private com.google.re2j.Pattern re2jSqlMysql;
  private com.google.re2j.Pattern re2jSqlPostgresql;
  private com.google.re2j.Pattern re2jQueryObfuscator;
  private com.google.re2j.Pattern re2jLdap;

  // --- Test inputs (built per-scale in setup(); see class doc for the growth strategy chosen
  // per pattern) ---

  // Command: a sudo invocation with arguments (find() always matches — tests match-path cost).
  // Matched-region growth: the trailing (.*) capture widens.
  private String commandInput;

  // Bare word-boundary: scan-prefix growth — filler (no "foo" substring) grows before the match.
  private String bareWordBoundaryInput;

  // URL: authority credentials match vs query-param match vs no match.
  // AUTHORITY branch is matched-region growth (^-anchored, so scan-prefix filler isn't possible
  // without breaking the anchor); QUERY branch and no-match are scan-prefix growth.
  private String urlAuthMatch;
  private String urlQueryMatch;
  private String urlNoMatch;

  // SQL (all three dialects): scan-prefix growth — benign filler (no digits/quotes/comment
  // markers) prepended before the literal that triggers the match, or appended to the
  // schema-only no-match query.
  private String sqlMatch;
  private String sqlNoMatch;
  private String mysqlMatch;
  private String mysqlNoMatch;
  private String postgresqlMatch;
  private String postgresqlNoMatch;

  // QueryObfuscator: scan-prefix growth — benign query params (none of the sensitive-key
  // keywords) prepended/appended.
  private String qobfMatch;
  private String qobfNoMatch;

  // LDAP tokenizer: LITERAL value length is matched-region growth; no-match is scan-prefix
  // growth (repeated benign text with none of the pattern's comparison operators or parens).
  private String ldapMatch;
  private String ldapNoMatch;

  @Setup(Level.Trial)
  public void setup() {
    commandInput =
        pick(
            scale,
            "sudo apt-get install -y curl --verbose",
            "sudo apt-get install -y " + "curl ".repeat(20) + "--verbose",
            "sudo apt-get install -y " + "curl ".repeat(2000) + "--verbose");
    reggieCommand = RuntimeCompiler.compile(COMMAND);
    jdkCommand = Pattern.compile(COMMAND);
    re2jCommand = com.google.re2j.Pattern.compile(COMMAND);

    bareWordBoundaryInput =
        pick(
            scale,
            "lorem ipsum dolor sit amet foo",
            "lorem ipsum dolor sit amet ".repeat(20) + "foo",
            "lorem ipsum dolor sit amet ".repeat(2000) + "foo");
    reggieBareWordBoundary = RuntimeCompiler.compile(BARE_WORD_BOUNDARY);
    jdkBareWordBoundary = Pattern.compile(BARE_WORD_BOUNDARY);
    re2jBareWordBoundary = com.google.re2j.Pattern.compile(BARE_WORD_BOUNDARY);

    urlAuthMatch =
        pick(
            scale,
            "https://admin:s3cr3t@internal.corp/api/v1/health",
            "https://" + "a".repeat(20) + ":" + "b".repeat(20) + "@internal.corp/api/v1/health",
            "https://"
                + "a".repeat(2000)
                + ":"
                + "b".repeat(2000)
                + "@internal.corp/api/v1/health");
    urlQueryMatch =
        pick(
            scale,
            "https://api.example.com/search?q=hello&password=hunter2&page=1",
            "https://api.example.com/"
                + "seg/".repeat(20)
                + "search?q=hello&password=hunter2&page=1",
            "https://api.example.com/"
                + "seg/".repeat(2000)
                + "search?q=hello&password=hunter2&page=1");
    urlNoMatch =
        pick(
            scale,
            "https://api.example.com/health",
            "https://api.example.com/" + "seg/".repeat(20) + "health",
            "https://api.example.com/" + "seg/".repeat(2000) + "health");
    reggieUrl = RuntimeCompiler.compile(URL_JDK);
    jdkUrl = Pattern.compile(URL_JDK);
    re2jUrl = com.google.re2j.Pattern.compile(URL_RE2J);

    sqlMatch =
        pick(
            scale,
            "SELECT * FROM users WHERE id = 42 AND name = 'Alice' AND balance = 1234.56",
            "SELECT * FROM users WHERE "
                + "col_x = col_y AND ".repeat(20)
                + "id = 42 AND name = 'Alice' AND balance = 1234.56",
            "SELECT * FROM users WHERE "
                + "col_x = col_y AND ".repeat(100)
                + "id = 42 AND name = 'Alice' AND balance = 1234.56");
    sqlNoMatch =
        pick(
            scale,
            "SELECT id, name, email FROM users ORDER BY id",
            "SELECT id, name, email FROM users WHERE "
                + "col_x = col_y AND ".repeat(20)
                + "id = id ORDER BY id",
            "SELECT id, name, email FROM users WHERE "
                + "col_x = col_y AND ".repeat(100)
                + "id = id ORDER BY id");
    reggieSqlAnsi = RuntimeCompiler.compile(SQL_ANSI);
    jdkSqlAnsi = Pattern.compile(SQL_ANSI);
    re2jSqlAnsi = com.google.re2j.Pattern.compile(SQL_ANSI);

    mysqlMatch =
        pick(
            scale,
            "SELECT id, `name` FROM users WHERE id = 1 AND email = 'user@example.com' AND active = 1",
            "SELECT id, `name` FROM users WHERE "
                + "col_x = col_y AND ".repeat(20)
                + "id = 1 AND email = 'user@example.com' AND active = 1",
            "SELECT id, `name` FROM users WHERE "
                + "col_x = col_y AND ".repeat(100)
                + "id = 1 AND email = 'user@example.com' AND active = 1");
    mysqlNoMatch =
        pick(
            scale,
            "SELECT id, name FROM users WHERE active AND enabled",
            "SELECT id, name FROM users WHERE "
                + "col_x = col_y AND ".repeat(20)
                + "active AND enabled",
            "SELECT id, name FROM users WHERE "
                + "col_x = col_y AND ".repeat(100)
                + "active AND enabled");
    reggieSqlMysql = RuntimeCompiler.compile(SQL_MYSQL);
    jdkSqlMysql = Pattern.compile(SQL_MYSQL);
    re2jSqlMysql = com.google.re2j.Pattern.compile(SQL_MYSQL);

    postgresqlMatch =
        pick(
            scale,
            "SELECT * FROM docs WHERE body = $$hello world$$ AND revision = 3",
            "SELECT * FROM docs WHERE "
                + "col_x = col_y AND ".repeat(20)
                + "body = $$hello world$$ AND revision = 3",
            "SELECT * FROM docs WHERE "
                + "col_x = col_y AND ".repeat(100)
                + "body = $$hello world$$ AND revision = 3");
    postgresqlNoMatch =
        pick(
            scale,
            "SELECT id, title FROM docs ORDER BY id",
            "SELECT id, title FROM docs WHERE " + "col_x = col_y AND ".repeat(20) + "id = id",
            "SELECT id, title FROM docs WHERE " + "col_x = col_y AND ".repeat(100) + "id = id");
    reggieSqlPostgresql = RuntimeCompiler.compile(SQL_POSTGRESQL);
    jdkSqlPostgresql = Pattern.compile(SQL_POSTGRESQL);
    re2jSqlPostgresql = com.google.re2j.Pattern.compile(SQL_POSTGRESQL);

    qobfMatch =
        pick(
            scale,
            "api_key=abc123def456&user=alice&action=view",
            "region=us&locale=en&".repeat(1) + "api_key=abc123def456&user=alice&action=view",
            "region=us&locale=en&".repeat(2) + "api_key=abc123def456&user=alice&action=view");
    qobfNoMatch =
        pick(
            scale,
            "user=alice&action=view&page=1&sort=asc",
            "user=alice&action=view&page=1&sort=asc&" + "region=us&locale=en&".repeat(1),
            "user=alice&action=view&page=1&sort=asc&" + "region=us&locale=en&".repeat(2));
    reggieQueryObfuscator = RuntimeCompiler.compile(QUERY_OBFUSCATOR);
    jdkQueryObfuscator = Pattern.compile(QUERY_OBFUSCATOR);
    re2jQueryObfuscator = com.google.re2j.Pattern.compile(QUERY_OBFUSCATOR);

    ldapMatch =
        pick(
            scale,
            "(uid=jsmith)",
            "(uid=" + "jsmith".repeat(10) + ")",
            "(uid=" + "jsmith".repeat(500) + ")");
    ldapNoMatch = pick(scale, "uid jsmith", "uid jsmith ".repeat(20), "uid jsmith ".repeat(2000));
    reggieLdap = RuntimeCompiler.compile(LDAP_JDK);
    jdkLdap = Pattern.compile(LDAP_JDK);
    re2jLdap = com.google.re2j.Pattern.compile(LDAP_JDK);
  }

  // ===== Command =====

  @Benchmark
  public boolean reggieCommandFind() {
    return reggieCommand.find(commandInput);
  }

  @Benchmark
  public boolean jdkCommandFind() {
    return jdkCommand.matcher(commandInput).find();
  }

  @Benchmark
  public boolean re2jCommandFind() {
    return re2jCommand.matcher(commandInput).find();
  }

  // ----- Command capture (span extraction) -----

  @Benchmark
  public long reggieCommandCapture() {
    MatchResult r = reggieCommand.findMatch(commandInput);
    if (r == null) {
      return -1L;
    }
    return (long) r.start(1) + r.end(1);
  }

  @Benchmark
  public long jdkCommandCapture() {
    java.util.regex.Matcher m = jdkCommand.matcher(commandInput);
    if (!m.find()) {
      return -1L;
    }
    return (long) m.start(1) + m.end(1);
  }

  @Benchmark
  public long re2jCommandCapture() {
    com.google.re2j.Matcher m = re2jCommand.matcher(commandInput);
    if (!m.find()) {
      return -1L;
    }
    return (long) m.start(1) + m.end(1);
  }

  // ===== Bare word boundary =====

  @Benchmark
  public boolean reggieBareWordBoundaryFind() {
    return reggieBareWordBoundary.find(bareWordBoundaryInput);
  }

  @Benchmark
  public boolean jdkBareWordBoundaryFind() {
    return jdkBareWordBoundary.matcher(bareWordBoundaryInput).find();
  }

  @Benchmark
  public boolean re2jBareWordBoundaryFind() {
    return re2jBareWordBoundary.matcher(bareWordBoundaryInput).find();
  }

  // ===== URL =====

  @Benchmark
  public boolean reggieUrlAuthFind() {
    return reggieUrl.find(urlAuthMatch);
  }

  @Benchmark
  public boolean jdkUrlAuthFind() {
    return jdkUrl.matcher(urlAuthMatch).find();
  }

  @Benchmark
  public boolean re2jUrlAuthFind() {
    return re2jUrl.matcher(urlAuthMatch).find();
  }

  @Benchmark
  public boolean reggieUrlQueryFind() {
    return reggieUrl.find(urlQueryMatch);
  }

  @Benchmark
  public boolean jdkUrlQueryFind() {
    return jdkUrl.matcher(urlQueryMatch).find();
  }

  @Benchmark
  public boolean re2jUrlQueryFind() {
    return re2jUrl.matcher(urlQueryMatch).find();
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
    MatchResult r = reggieUrl.findMatch(urlAuthMatch);
    if (r == null) {
      return -1L;
    }
    return sumGroupOffsets(r, 3);
  }

  @Benchmark
  public long jdkUrlAuthCapture() {
    java.util.regex.Matcher m = jdkUrl.matcher(urlAuthMatch);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public long reggieUrlQueryCapture() {
    MatchResult r = reggieUrl.findMatch(urlQueryMatch);
    if (r == null) {
      return -1L;
    }
    return sumGroupOffsets(r, 3);
  }

  @Benchmark
  public long jdkUrlQueryCapture() {
    java.util.regex.Matcher m = jdkUrl.matcher(urlQueryMatch);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public long re2jUrlAuthCapture() {
    com.google.re2j.Matcher m = re2jUrl.matcher(urlAuthMatch);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public long re2jUrlQueryCapture() {
    com.google.re2j.Matcher m = re2jUrl.matcher(urlQueryMatch);
    if (!m.find()) {
      return -1L;
    }
    return sumGroupOffsets(m, 3);
  }

  @Benchmark
  public boolean reggieUrlNoMatch() {
    return reggieUrl.find(urlNoMatch);
  }

  @Benchmark
  public boolean jdkUrlNoMatch() {
    return jdkUrl.matcher(urlNoMatch).find();
  }

  @Benchmark
  public boolean re2jUrlNoMatch() {
    return re2jUrl.matcher(urlNoMatch).find();
  }

  // ===== SQL ANSI =====

  @Benchmark
  public boolean reggieSqlAnsiFind() {
    return reggieSqlAnsi.find(sqlMatch);
  }

  @Benchmark
  public boolean jdkSqlAnsiFind() {
    return jdkSqlAnsi.matcher(sqlMatch).find();
  }

  @Benchmark
  public boolean re2jSqlAnsiFind() {
    return re2jSqlAnsi.matcher(sqlMatch).find();
  }

  @Benchmark
  public boolean reggieSqlAnsiNoMatch() {
    return reggieSqlAnsi.find(sqlNoMatch);
  }

  @Benchmark
  public boolean jdkSqlAnsiNoMatch() {
    return jdkSqlAnsi.matcher(sqlNoMatch).find();
  }

  @Benchmark
  public boolean re2jSqlAnsiNoMatch() {
    return re2jSqlAnsi.matcher(sqlNoMatch).find();
  }

  // ===== SQL MySQL =====

  @Benchmark
  public boolean reggieSqlMysqlFind() {
    return reggieSqlMysql.find(mysqlMatch);
  }

  @Benchmark
  public boolean jdkSqlMysqlFind() {
    return jdkSqlMysql.matcher(mysqlMatch).find();
  }

  @Benchmark
  public boolean re2jSqlMysqlFind() {
    return re2jSqlMysql.matcher(mysqlMatch).find();
  }

  @Benchmark
  public boolean reggieSqlMysqlNoMatch() {
    return reggieSqlMysql.find(mysqlNoMatch);
  }

  @Benchmark
  public boolean jdkSqlMysqlNoMatch() {
    return jdkSqlMysql.matcher(mysqlNoMatch).find();
  }

  @Benchmark
  public boolean re2jSqlMysqlNoMatch() {
    return re2jSqlMysql.matcher(mysqlNoMatch).find();
  }

  // ===== SQL PostgreSQL =====

  @Benchmark
  public boolean reggieSqlPostgresqlFind() {
    return reggieSqlPostgresql.find(postgresqlMatch);
  }

  @Benchmark
  public boolean jdkSqlPostgresqlFind() {
    return jdkSqlPostgresql.matcher(postgresqlMatch).find();
  }

  @Benchmark
  public boolean re2jSqlPostgresqlFind() {
    return re2jSqlPostgresql.matcher(postgresqlMatch).find();
  }

  @Benchmark
  public boolean reggieSqlPostgresqlNoMatch() {
    return reggieSqlPostgresql.find(postgresqlNoMatch);
  }

  @Benchmark
  public boolean jdkSqlPostgresqlNoMatch() {
    return jdkSqlPostgresql.matcher(postgresqlNoMatch).find();
  }

  @Benchmark
  public boolean re2jSqlPostgresqlNoMatch() {
    return re2jSqlPostgresql.matcher(postgresqlNoMatch).find();
  }

  // ===== Query Obfuscator =====

  @Benchmark
  public boolean reggieQueryObfuscatorFind() {
    return reggieQueryObfuscator.find(qobfMatch);
  }

  @Benchmark
  public boolean jdkQueryObfuscatorFind() {
    return jdkQueryObfuscator.matcher(qobfMatch).find();
  }

  @Benchmark
  public boolean re2jQueryObfuscatorFind() {
    return re2jQueryObfuscator.matcher(qobfMatch).find();
  }

  @Benchmark
  public boolean reggieQueryObfuscatorNoMatch() {
    return reggieQueryObfuscator.find(qobfNoMatch);
  }

  @Benchmark
  public boolean jdkQueryObfuscatorNoMatch() {
    return jdkQueryObfuscator.matcher(qobfNoMatch).find();
  }

  @Benchmark
  public boolean re2jQueryObfuscatorNoMatch() {
    return re2jQueryObfuscator.matcher(qobfNoMatch).find();
  }

  // ===== LDAP tokenizer =====

  @Benchmark
  public boolean reggieLdapFind() {
    return reggieLdap.find(ldapMatch);
  }

  @Benchmark
  public boolean jdkLdapFind() {
    return jdkLdap.matcher(ldapMatch).find();
  }

  @Benchmark
  public boolean re2jLdapFind() {
    return re2jLdap.matcher(ldapMatch).find();
  }

  @Benchmark
  public boolean reggieLdapNoMatch() {
    return reggieLdap.find(ldapNoMatch);
  }

  @Benchmark
  public boolean jdkLdapNoMatch() {
    return jdkLdap.matcher(ldapNoMatch).find();
  }

  @Benchmark
  public boolean re2jLdapNoMatch() {
    return re2jLdap.matcher(ldapNoMatch).find();
  }
}
