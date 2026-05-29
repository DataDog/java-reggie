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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.CapturePolicy;
import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.analysis.LinearTemplatePlan;
import com.datadoghq.reggie.codegen.analysis.PatternCategorizer;
import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinearTemplateMatcherTest {

  @Test
  void matchesLinearTemplateAndExtractsCaptureBoundaries() throws Exception {
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
  void runtimeCompilerRoutesNamedOnlyLinearTemplates() {
    ReggieMatcher matcher =
        Reggie.compile(
            "host=(?<host>\\S+) status=(?<status>[+-]?\\d+)",
            ReggieOptions.builder().capturePolicy(CapturePolicy.NAMED_ONLY).build());

    MatchResult result = matcher.match("host=api.example.com status=200");

    assertEquals("api.example.com", result.group("host"));
    assertEquals("200", result.group("status"));
  }

  private static ReggieMatcher matcherFor(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    Map<String, Integer> names = parser.getGroupNameMap();
    LinearTemplatePlan plan =
        LinearTemplatePlan.from(PatternCategorizer.categorize(ast)).orElseThrow();
    int groupCount = names.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return new LinearTemplateMatcher(pattern, plan, groupCount, names);
  }
}
