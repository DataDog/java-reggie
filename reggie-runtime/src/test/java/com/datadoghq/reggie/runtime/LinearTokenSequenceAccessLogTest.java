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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LinearTokenSequenceAccessLogTest {
  private static final ReggieOptions NAMED_ONLY = ReggieOptions.builder().namedOnly().build();

  private static final String COMBINED_ACCESS_LOG_PATTERN =
      "(?s)(?<grok0>[0-9A-Fa-f:.]+) (?<grok1>\\S+) (?<grok2>\\S+) "
          + "\\[(?<grok3>[^\\]]+)\\]\\s+\"(?<grok4>\\b\\w+\\b) (?<grok5>\\S+) HTTP/(?<grok6>\\d+\\.\\d+)\" "
          + "(?<grok7>[+-]?\\d+) (?<grok8>[+-]?\\d+) "
          + "\"(?<grok9>\\S+)\" \"(?<grok10>[^\\\"]*)\" \"(?<grok11>[^\\\"]*)\" \"(?<grok12>[^\\\"]*)\" "
          + "(?<grok13>[+-]?\\d+(?:\\.\\d+)?) (?<grok14>[+-]?\\d+(?:\\.\\d+)?).* "
          + "\\[(?<grok15>\\b\\w+\\b)\\] .*";

  @Test
  void matchesCombinedAccessLogWithDelimiterAwareCaptures() {
    ReggieMatcher matcher = Reggie.compile(COMBINED_ACCESS_LOG_PATTERN, NAMED_ONLY);
    String input =
        "10.202.82.195 - - [15/Mar/2019:19:45:35 -0700]  \"POST /config?x=y HTTP/1.1\" "
            + "200 17888 \"https://example.com/index.html\" \"Mozilla/5.0 Test\" \"-\" "
            + "\"tracking-id\" 0.024 0.024 . [nginx_access]  [not_the_logger]";

    int[] starts = new int[17];
    int[] ends = new int[17];
    assertTrue(matcher.matchInto(input, starts, ends));

    assertGroup(input, starts, ends, 1, "10.202.82.195");
    assertGroup(input, starts, ends, 4, "15/Mar/2019:19:45:35 -0700");
    assertGroup(input, starts, ends, 5, "POST");
    assertGroup(input, starts, ends, 6, "/config?x=y");
    assertGroup(input, starts, ends, 7, "1.1");
    assertGroup(input, starts, ends, 8, "200");
    assertGroup(input, starts, ends, 9, "17888");
    assertGroup(input, starts, ends, 10, "https://example.com/index.html");
    assertGroup(input, starts, ends, 11, "Mozilla/5.0 Test");
    assertGroup(input, starts, ends, 12, "-");
    assertGroup(input, starts, ends, 13, "tracking-id");
    assertGroup(input, starts, ends, 14, "0.024");
    assertGroup(input, starts, ends, 15, "0.024");
    assertGroup(input, starts, ends, 16, "nginx_access");
  }

  @Test
  void routesRealExpandedCommonAccessLogPatternThroughLinearTokenSequenceMatcher()
      throws Exception {
    ReggieMatcher matcher = Reggie.compile(testResource("logs-grok-pattern-1.regex"), NAMED_ONLY);
    String input =
        "10.202.82.195 - - [15/Mar/2019:19:45:35 -0700]  \"POST /config?x=y HTTP/1.1\" "
            + "200 17888";

    MatchResult result = matcher.match(input);

    assertNotNull(result);
    assertEquals("10.202.82.195", result.group("grok0"));
    assertEquals("POST", result.group("grok4"));
    assertEquals("/config?x=y", result.group("grok5"));
    assertEquals("1.1", result.group("grok6"));
    assertEquals("200", result.group("grok7"));
    assertEquals("17888", result.group("grok8"));
    assertDelegateType(matcher, LinearTokenSequenceMatcher.class);
  }

  @Test
  void routesRealExpandedCombinedAccessLogPatternThroughLinearTokenSequenceMatcher()
      throws Exception {
    ReggieMatcher matcher = Reggie.compile(testResource("logs-grok-pattern-2.regex"), NAMED_ONLY);
    String input =
        "10.202.82.195 - - [15/Mar/2019:19:45:35 -0700]  \"POST /config?x=y HTTP/1.1\" "
            + "200 17888 \"https://example.com/index.html\" \"Mozilla/5.0 Test\" \"-\" "
            + "\"tracking-id\" 0.024 0.024 . [nginx_access]  [not_the_logger]";

    MatchResult result = matcher.match(input);

    assertNotNull(result);
    assertEquals("10.202.82.195", result.group("grok0"));
    assertEquals("POST", result.group("grok4"));
    assertEquals("/config?x=y", result.group("grok5"));
    assertEquals("1.1", result.group("grok6"));
    assertEquals("https://example.com/index.html", result.group("grok9"));
    assertEquals("Mozilla/5.0 Test", result.group("grok10"));
    assertEquals("tracking-id", result.group("grok12"));
    assertEquals("0.024", result.group("grok13"));
    assertEquals("0.024", result.group("grok14"));
    assertEquals("nginx_access", result.group("grok15"));
    assertDelegateType(matcher, LinearTokenSequenceMatcher.class);
  }

  @Test
  void realExpandedCommonPatternHasJdkEquivalentNamedCaptureBoundaries() throws Exception {
    String pattern = testResource("logs-grok-pattern-1.regex");
    assertNamedCaptureBoundariesEquivalent(
        pattern,
        commonMessage("10.202.82.195", "POST ", "/config?x=y", " HTTP/1.1", "17888"),
        commonMessage("2001:db8::1", "", "/health", " HTTP/2.0", "-"),
        commonMessage("api-1.example.com", "GET ", "/without-version", "", "42"));
  }

  @Test
  void realExpandedCombinedPatternHasJdkEquivalentNamedCaptureBoundaries() throws Exception {
    String pattern = testResource("logs-grok-pattern-2.regex");
    assertNamedCaptureBoundariesEquivalent(
        pattern,
        combinedMessage(
            "10.202.82.195",
            "POST ",
            "/config?x=y",
            " HTTP/1.1",
            "17888",
            "https://example.com/index.html",
            "Mozilla/5.0 Test",
            "-",
            "tracking-id",
            "0.024",
            "0.024",
            "[decoy] . [nginx_access]  [not-the-logger]"),
        combinedMessage(
            "2001:db8::1",
            "",
            "/health",
            " HTTP/2.0",
            "-",
            "-",
            "",
            "",
            "",
            ".5",
            "0.",
            "[ignored] . [nginx_access]  [not_the_logger]"),
        combinedMessage(
            "api-1.example.com",
            "GET ",
            "/without-version",
            "",
            "42",
            "http://embedded.example/launcher.html",
            "Agent/1.0",
            "field1",
            "field2",
            "+12.5",
            "-0.25",
            "[not_the_logger] . [nginx_access]  [host-with-dash]"));
  }

  @Test
  void realExpandedPatternsUseOnlyTheBoundedCharSequenceRegion() throws Exception {
    assertBoundedFixtureUsesOnlyItsRegion(
        testResource("logs-grok-pattern-1.regex"),
        commonMessage("10.202.82.195", "POST ", "/config?x=y", " HTTP/1.1", "17888"),
        commonMessage("10.202.82.195", "POST ", "/config?x=y", " HTTP/1.1", "not-a-number"));
    assertBoundedFixtureUsesOnlyItsRegion(
        testResource("logs-grok-pattern-2.regex"),
        combinedMessage(
            "10.202.82.195",
            "POST ",
            "/config?x=y",
            " HTTP/1.1",
            "17888",
            "https://example.com/index.html",
            "Mozilla/5.0 Test",
            "-",
            "tracking-id",
            "0.024",
            "0.024",
            "[decoy] . [nginx_access]  [not-the-logger]"),
        combinedMessage(
            "10.202.82.195",
            "POST ",
            "/config?x=y",
            " HTTP/1.1",
            "not-a-number",
            "https://example.com/index.html",
            "Mozilla/5.0 Test",
            "-",
            "tracking-id",
            "0.024",
            "0.024",
            "[decoy] . [nginx_access]  [not-the-logger]"));
  }

  @Test
  void leavesCallerArraysUnchangedOnNoMatch() {
    ReggieMatcher matcher = Reggie.compile(COMBINED_ACCESS_LOG_PATTERN, NAMED_ONLY);
    int[] starts = new int[17];
    int[] ends = new int[17];
    starts[1] = 123;
    ends[1] = 456;

    assertFalse(matcher.matchInto("not an access log", starts, ends));

    assertEquals(123, starts[1]);
    assertEquals(456, ends[1]);
  }

  private static void assertGroup(String input, int[] starts, int[] ends, int group, String value) {
    assertEquals(value, input.substring(starts[group], ends[group]));
  }

  private static void assertNamedCaptureBoundariesEquivalent(String pattern, String... inputs)
      throws Exception {
    RegexParser parser = new RegexParser();
    parser.parse(pattern);
    Map<String, Integer> nameToIndex = parser.getGroupNameMap();
    Pattern jdkPattern = Pattern.compile(pattern);
    ReggieMatcher reggieMatcher = Reggie.compile(pattern, NAMED_ONLY);
    assertDelegateTypeUnchecked(reggieMatcher, LinearTokenSequenceMatcher.class);

    for (String input : inputs) {
      Matcher jdkMatcher = jdkPattern.matcher(input);
      boolean jdkMatches = jdkMatcher.matches();
      int[] starts = new int[jdkMatcher.groupCount() + 1];
      int[] ends = new int[jdkMatcher.groupCount() + 1];
      Arrays.fill(starts, 777);
      Arrays.fill(ends, 888);
      boolean reggieMatches = reggieMatcher.matchInto(input, starts, ends);

      assertEquals(jdkMatches, reggieMatches, input);
      if (!jdkMatches) {
        assertTrue(Arrays.stream(starts).allMatch(value -> value == 777), input);
        assertTrue(Arrays.stream(ends).allMatch(value -> value == 888), input);
        continue;
      }

      assertEquals(jdkMatcher.start(), starts[0], input);
      assertEquals(jdkMatcher.end(), ends[0], input);
      for (Map.Entry<String, Integer> entry : nameToIndex.entrySet()) {
        int group = entry.getValue();
        assertEquals(jdkMatcher.start(group), starts[group], entry.getKey() + " start: " + input);
        assertEquals(jdkMatcher.end(group), ends[group], entry.getKey() + " end: " + input);
        if (starts[group] >= 0) {
          assertEquals(
              jdkMatcher.group(group),
              input.substring(starts[group], ends[group]),
              entry.getKey() + " value: " + input);
        }
      }
    }
  }

  private static void assertBoundedFixtureUsesOnlyItsRegion(
      String pattern, String matchingInput, String failingInput) throws Exception {
    ReggieMatcher matcher = Reggie.compile(pattern, NAMED_ONLY);
    assertDelegateType(matcher, LinearTokenSequenceMatcher.class);
    LinearTokenSequenceMatcher ltsMatcher = (LinearTokenSequenceMatcher) matcher;
    int groupCount = Pattern.compile(pattern).matcher("").groupCount();

    assertBoundedResult(
        ltsMatcher, groupCount, "before " + matchingInput + " after ", matchingInput, true);
    assertBoundedResult(
        ltsMatcher, groupCount, "before " + failingInput + " after ", failingInput, false);
  }

  private static void assertBoundedResult(
      LinearTokenSequenceMatcher matcher,
      int groupCount,
      String source,
      String region,
      boolean expectedMatch) {
    int start = source.indexOf(region);
    int end = start + region.length();
    RangeGuardCharSequence input = new RangeGuardCharSequence(source, start, end);
    int[] starts = new int[groupCount + 1];
    int[] ends = new int[groupCount + 1];
    Arrays.fill(starts, 777);
    Arrays.fill(ends, 888);

    assertEquals(expectedMatch, matcher.matchIntoBounded(input, start, end, starts, ends));
    if (expectedMatch) {
      assertEquals(start, starts[0]);
      assertEquals(end, ends[0]);
    } else {
      assertTrue(Arrays.stream(starts).allMatch(value -> value == 777));
      assertTrue(Arrays.stream(ends).allMatch(value -> value == 888));
    }
  }

  private static String commonMessage(
      String client,
      String methodWithSpace,
      String target,
      String versionWithPrefix,
      String bytes) {
    return client
        + " - - [15/Mar/2019:19:45:35 -0700]  \""
        + methodWithSpace
        + target
        + versionWithPrefix
        + "\" 200 "
        + bytes;
  }

  private static String combinedMessage(
      String client,
      String methodWithSpace,
      String target,
      String versionWithPrefix,
      String bytes,
      String referer,
      String userAgent,
      String trackingId,
      String upstreamTrackingId,
      String duration,
      String upstreamDuration,
      String tail) {
    return commonMessage(client, methodWithSpace, target, versionWithPrefix, bytes)
        + " \""
        + referer
        + "\" \""
        + userAgent
        + "\" \""
        + trackingId
        + "\" \""
        + upstreamTrackingId
        + "\" "
        + duration
        + " "
        + upstreamDuration
        + " "
        + tail;
  }

  private static String testResource(String name) throws IOException {
    String path = "/com/datadoghq/reggie/runtime/" + name;
    try (InputStream stream = LinearTokenSequenceAccessLogTest.class.getResourceAsStream(path)) {
      assertNotNull(stream, path);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }

  private static void assertDelegateType(ReggieMatcher matcher, Class<?> expectedType)
      throws Exception {
    if (matcher.getClass() == expectedType) {
      return;
    }
    Field delegate = matcher.getClass().getDeclaredField("delegate");
    delegate.setAccessible(true);
    assertEquals(expectedType, delegate.get(matcher).getClass());
  }

  private static void assertDelegateTypeUnchecked(ReggieMatcher matcher, Class<?> expectedType) {
    try {
      assertDelegateType(matcher, expectedType);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
