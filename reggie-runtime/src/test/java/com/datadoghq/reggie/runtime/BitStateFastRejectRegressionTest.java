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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the {@code BitStateMatcher} fast-reject filters (single-char {@code indexOf} prefilter and
 * the general reject-DFA, both mirrored from {@code PikeVMMatcher}) for the IAST hot-path patterns
 * (mirrored from {@code IastRegexpBenchmark} / {@code IastPatternRoutingTest}).
 *
 * <p>{@code IastPatternRoutingTest} (reggie-codegen) pins the {@code MatchingStrategy} these
 * patterns route to, but routing alone does not guarantee the filter survives inside {@code
 * BitStateMatcher} once it's there — that's exactly how the SQL_ANSI/SQL_POSTGRESQL/LDAP/
 * QUERY_OBFUSCATOR/URL no-match cases regressed 15x->0.15x against JDK/RE2J while routing stayed
 * unchanged. This test fails fast, at the unit level, if that filter is ever dropped again.
 *
 * <p>{@code SQL_MYSQL} is intentionally excluded: {@code IastRegexpBenchmark.MYSQL_NO_MATCH} is a
 * mislabeled benchmark constant that actually contains a genuine match (see tracked follow-up
 * issue), so there is no confirmed-correct no-match input to pin here without duplicating that bug.
 */
class BitStateFastRejectRegressionTest {

  private static final String URL =
      "^(?:[^:]+:)?//(?<AUTHORITY>[^@]+)@|[?#&]([^=&;]+)=(?<QUERY>[^?#&]+)";

  private static final String SQL_ANSI =
      "(?i)(?m)[-+]?(?:x'[0-9a-f]+'|0x[0-9a-f]+|b'[0-9a-f]+'|0b[0-9a-f]+"
          + "|\\d*\\.\\d+(?:E[-+]?\\d+[fd]?)?|\\b\\d+(?:E[-+]?\\d+[fd]?)?)"
          + "|'(?:''|[^'])*'|--.*$|/\\*[\\s\\S]*\\*/";

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

  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> patterns() {
    return java.util.stream.Stream.of(
        org.junit.jupiter.params.provider.Arguments.of(
            "URL", URL, "this string has neither a scheme separator nor a query parameter"),
        org.junit.jupiter.params.provider.Arguments.of(
            "SQL_ANSI",
            SQL_ANSI,
            "SELECT id, name FROM users WHERE active"), // no literals/numbers/comments
        org.junit.jupiter.params.provider.Arguments.of(
            "SQL_POSTGRESQL", SQL_POSTGRESQL, "SELECT id, name FROM users WHERE active"),
        org.junit.jupiter.params.provider.Arguments.of(
            "LDAP", LDAP, "no parentheses or comparison operators here at all"),
        org.junit.jupiter.params.provider.Arguments.of(
            "QUERY_OBFUSCATOR",
            QUERY_OBFUSCATOR,
            "GET /home?greeting=hello&locale=en-US HTTP/1.1"));
  }

  private static BitStateMatcher build(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    NFA nfa = new ThompsonBuilder().build(ast, countGroups(pattern));
    return new BitStateMatcher(nfa, pattern);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("patterns")
  void carriesFastRejectAndRejectsNoMatchInput(String name, String pattern, String noMatchInput)
      throws Exception {
    BitStateMatcher matcher = build(pattern);

    assertTrue(
        matcher.hasFastReject(),
        name
            + ": BitStateMatcher lost its fast-reject filter (neither singleFirstCharAscii nor"
            + " rejectDfa is wired) — this is the exact regression that tanked SQL/URL/LDAP"
            + " no-match performance 15x->0.15x against JDK/RE2J");

    // Sanity: confirm the JDK oracle agrees this input is a genuine no-match before trusting our
    // own rejection of it.
    assertFalse(
        java.util.regex.Pattern.compile(pattern).matcher(noMatchInput).find(),
        name + ": test fixture bug — noMatchInput actually matches per java.util.regex");

    assertFalse(matcher.find(noMatchInput), name + ": BitStateMatcher.find() false positive");
  }

  // Verbatim copy of BitStateMatcherTest's countGroups (kept in sync manually; both files build
  // NFAs directly from RegexParser output and need the same capturing-group count, since there is
  // no shared parser-side "how many capturing groups" query to call instead).
  private static int countGroups(String pattern) {
    int count = 0;
    boolean escaped = false;
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (c == '\\') {
        escaped = true;
      } else if (c == '(' && i + 1 < pattern.length()) {
        if (pattern.charAt(i + 1) == '?') {
          if (i + 2 < pattern.length()) {
            char next = pattern.charAt(i + 2);
            if (next == ':'
                || next == '='
                || next == '!'
                || next == '>'
                || next == '#'
                || next == '|'
                || next == '('
                || next == '-'
                || next == 'i'
                || next == 'm'
                || next == 's'
                || next == 'x'
                || next == 'u'
                || next == 'U'
                || next == 'd') {
              if (next == '<' && i + 3 < pattern.length()) {
                char afterLt = pattern.charAt(i + 3);
                if (afterLt == '=' || afterLt == '!') {
                  continue;
                }
              } else {
                continue;
              }
            }
            if (next == '<' && i + 3 < pattern.length()) {
              char afterLt = pattern.charAt(i + 3);
              if (afterLt == '=' || afterLt == '!') {
                continue;
              }
            }
          }
        }
        count++;
      }
    }
    return count;
  }
}
