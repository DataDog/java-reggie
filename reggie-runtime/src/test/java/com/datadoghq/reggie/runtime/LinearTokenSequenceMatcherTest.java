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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.analysis.LinearTokenSequencePlan;
import com.datadoghq.reggie.codegen.analysis.PatternCategorizer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinearTokenSequenceMatcherTest {

  @Test
  void matchesLinearTokenSequenceAndExtractsCaptureBoundaries() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");
    String input = "host=api.example.com status=200";

    int[] starts = new int[3];
    int[] ends = new int[3];
    assertTrue(matcher.matchInto(input, starts, ends));

    assertEquals("api.example.com", input.substring(starts[1], ends[1]));
    assertEquals("200", input.substring(starts[2], ends[2]));
  }

  @Test
  void handlesQuotedDelimiterCaptures() throws Exception {
    ReggieMatcher matcher = matcherFor("referer=\"(?<referer>[^\"]*)\"");
    String input = "referer=\"https://example.com/index.html\"";

    MatchResult result = matcher.match(input);

    assertEquals("https://example.com/index.html", result.group("referer"));
  }

  @Test
  void preservesCallerArraysOnNoMatch() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");
    int[] starts = new int[] {7, 7, 7};
    int[] ends = new int[] {9, 9, 9};

    assertFalse(matcher.matchInto("host=api.example.com status=not-a-number", starts, ends));

    assertTrue(Arrays.equals(new int[] {7, 7, 7}, starts));
    assertTrue(Arrays.equals(new int[] {9, 9, 9}, ends));
  }

  @Test
  void validatesCallerArrays() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");

    assertThrows(
        IndexOutOfBoundsException.class,
        () -> matcher.matchInto("host=a status=1", new int[2], new int[3]));
  }

  @Test
  void runtimeCompilerRoutesNamedOnlyLinearTokenSequences() throws Exception {
    ReggieMatcher matcher =
        Reggie.compile(
            "host=(?<host>\\S+) status=(?<status>[+-]?\\d+)",
            ReggieOptions.builder().namedOnly().build());

    MatchResult result = matcher.match("host=api.example.com status=200");

    assertEquals("api.example.com", result.group("host"));
    assertEquals("200", result.group("status"));
    assertDelegateType(matcher, LinearTokenSequenceMatcher.class);
  }

  @Test
  void namedOnlyProjectionRunsBeforeLinearTokenRouting() throws Exception {
    ReggieMatcher matcher = Reggie.compile("(?<host>\\S+) (\\d+)", NAMED_ONLY_OPTIONS);
    int[] starts = new int[] {777, 777, 777};
    int[] ends = new int[] {888, 888, 888};

    assertTrue(matcher.matchInto("api.example.com 200", starts, ends));

    assertEquals("api.example.com", "api.example.com 200".substring(starts[1], ends[1]));
    assertEquals(-1, starts[2]);
    assertEquals(-1, ends[2]);
    assertDelegateType(matcher, LinearTokenSequenceMatcher.class);
  }

  @Test
  void capturedDashAlternativeRecordsNamedGroupSpan() throws Exception {
    ReggieMatcher matcher = Reggie.compile("(?<bytes>(?:-|[+-]?\\d+))", NAMED_ONLY_OPTIONS);

    MatchResult dash = matcher.match("-");
    MatchResult digits = matcher.match("42");

    assertEquals("-", dash.group("bytes"));
    assertEquals("42", digits.group("bytes"));
    assertDelegateType(matcher, LinearTokenSequenceMatcher.class);
  }

  @Test
  void ambiguousOptionalSequencesUseGeneralRegexRoute() {
    ReggieMatcher matcher = Reggie.compile("(?:a|)a", NAMED_ONLY_OPTIONS);

    assertTrue(matcher.matches("a"));
    assertNotEquals(LinearTokenSequenceMatcher.class, matcher.getClass());
  }

  @Test
  void runtimeCompilerRoutesCombinedAccessLogTemplateWithNonGrokNames() throws Exception {
    String pattern =
        "(?s)(?<client>(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[A-Za-z0-9.-]+) "
            + "(?<ident>\\S+) (?<auth>\\S+) "
            + "\\[(?<timestamp>[\\d]{2}/(?:[jJ][aA][nN]|[mM][aA][rR])/[\\d]{4,19}:[\\d]{2}:[\\d]{2}:[\\d]{2} [+-]\\d\\d:?\\d\\d)\\]\\s+"
            + "\"(?>(?<method>\\b\\w+\\b) |)(?<target>\\S+)(?> HTTP\\/(?<version>\\d+\\.\\d+)|)\" "
            + "(?<status>[+-]?\\d+) (?>(?<bytes>[+-]?\\d+)|-) "
            + "\"(?<referer>\\S+)\" \"(?<agent>[^\\\"]*)\" \"(?<field1>[^\\\"]*)\" \"(?<field2>[^\\\"]*)\" "
            + "(?<duration>[+-]?(?>\\d+(?:\\.(?:\\d*)?)?|\\.\\d+)) "
            + "(?<upstream>[+-]?(?>\\d+(?:\\.(?:\\d*)?)?|\\.\\d+)).* "
            + "\\[(?<logger>\\b\\w+\\b)\\] .*";
    ReggieMatcher matcher = Reggie.compile(pattern, NAMED_ONLY_OPTIONS);
    String input =
        "10.202.82.195 - - [15/Mar/2019:19:45:35 -0700]  \"POST /config?x=y HTTP/1.1\" "
            + "200 17888 \"https://example.com/index.html\" \"Mozilla/5.0 Test\" \"-\" "
            + "\"tracking-id\" 0.024 0.024 . [nginx_access]  [not_the_logger]";

    MatchResult result = matcher.match(input);

    assertEquals("10.202.82.195", result.group("client"));
    assertEquals("POST", result.group("method"));
    assertEquals("/config?x=y", result.group("target"));
    assertEquals("1.1", result.group("version"));
    assertEquals("https://example.com/index.html", result.group("referer"));
    assertEquals("Mozilla/5.0 Test", result.group("agent"));
    assertEquals("nginx_access", result.group("logger"));
    assertDelegateType(matcher, LinearTokenSequenceMatcher.class);
  }

  private static final ReggieOptions NAMED_ONLY_OPTIONS =
      ReggieOptions.builder().namedOnly().build();

  private static void assertDelegateType(ReggieMatcher matcher, Class<?> expectedType)
      throws Exception {
    if (matcher.getClass() == expectedType) {
      return;
    }
    Field delegate = matcher.getClass().getDeclaredField("delegate");
    delegate.setAccessible(true);
    assertEquals(expectedType, delegate.get(matcher).getClass());
  }

  private static ReggieMatcher matcherFor(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    Map<String, Integer> names = parser.getGroupNameMap();
    LinearTokenSequencePlan plan =
        LinearTokenSequencePlan.from(PatternCategorizer.categorize(ast)).orElseThrow();
    int groupCount = names.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return new LinearTokenSequenceMatcher(pattern, plan, groupCount, names);
  }
}
