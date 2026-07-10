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
package com.datadoghq.reggie.codegen.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@link PatternAnalyzer.MatchingStrategy} routed for the IAST hot-path patterns (mirrored
 * from {@code IastRegexpBenchmark}), so an accidental reroute — e.g. a pattern silently losing the
 * {@code PikeVMMatcher} SIMD fast-reject by being rerouted to {@code BitStateMatcher} — fails a
 * fast unit test instead of only showing up as a JMH regression.
 */
class IastPatternRoutingTest {

  private static final String COMMAND = "(?s)(?m)^(?:\\s*(?:sudo|doas)\\s+)?\\b\\S+\\b\\s*(.*)";

  private static final String URL =
      "^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)";

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

  private static final String LDAP = "\\(.*?(?:~=|=|<=|>=)(?<LITERAL>[^)]+)\\)";

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

  private PatternAnalyzer.MatchingStrategyResult analyze(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, 0);
    return new PatternAnalyzer(ast, nfa).analyzeAndRecommend();
  }

  @Test
  void commandRoutesToBitstateBytecode() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_BYTECODE,
        analyze(COMMAND).strategy,
        "COMMAND pattern must keep its prefix-guarded-scan fast path (PR #99)");
  }

  @Test
  void urlRoutesToBitstateCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE,
        analyze(URL).strategy,
        "URL pattern routing changed — verify BitStateMatcher still carries the "
            + "singleFirstCharAscii fast-reject before updating this pin");
  }

  @Test
  void sqlAnsiRoutesToBitstateCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE,
        analyze(SQL_ANSI).strategy,
        "SQL_ANSI pattern routing changed — verify BitStateMatcher still carries the "
            + "singleFirstCharAscii fast-reject before updating this pin");
  }

  @Test
  void sqlMysqlRoutesToBitstateCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE,
        analyze(SQL_MYSQL).strategy,
        "SQL_MYSQL pattern routing changed — verify BitStateMatcher still carries the "
            + "singleFirstCharAscii fast-reject before updating this pin");
  }

  @Test
  void sqlPostgresqlRoutesToBitstateCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE,
        analyze(SQL_POSTGRESQL).strategy,
        "SQL_POSTGRESQL pattern routing changed — this pattern regressed 15x->0.15x on no-match"
            + " once routed off PikeVMMatcher's SIMD fast-reject; verify BitStateMatcher still"
            + " carries the singleFirstCharAscii fast-reject before updating this pin");
  }

  @Test
  void ldapRoutesToBitstateCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE,
        analyze(LDAP).strategy,
        "LDAP pattern routing changed — this pattern regressed 15x->0.15x on no-match once"
            + " routed off PikeVMMatcher's SIMD fast-reject; verify BitStateMatcher still carries"
            + " the singleFirstCharAscii fast-reject before updating this pin");
  }

  @Test
  void queryObfuscatorRoutesToBitstateCapture() throws Exception {
    assertEquals(
        PatternAnalyzer.MatchingStrategy.BITSTATE_CAPTURE,
        analyze(QUERY_OBFUSCATOR).strategy,
        "QUERY_OBFUSCATOR pattern routing changed — verify BitStateMatcher still carries the "
            + "singleFirstCharAscii fast-reject before updating this pin");
  }
}
