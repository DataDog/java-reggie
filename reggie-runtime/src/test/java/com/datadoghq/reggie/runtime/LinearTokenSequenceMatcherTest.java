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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  void quotedDelimiterCaptureFailsWhenClosingDelimiterIsMissing() throws Exception {
    ReggieMatcher matcher = matcherFor("referer=\"(?<referer>[^\"]*)\"");

    assertNull(matcher.match("referer=\"https://example.com/index.html"));
  }

  @Test
  void matchReturnsNullWhenSequenceDoesNotMatch() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");

    assertNull(matcher.match("host=api.example.com status=not-a-number"));
  }

  @Test
  void matchReturnsPlainResultForUnnamedGroups() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(\\S+) status=([+-]?\\d+)", 2);

    MatchResult result = matcher.match("host=api.example.com status=200");

    assertNotNull(result);
    assertEquals("host=api.example.com status=200", result.group());
    assertEquals("api.example.com", result.group(1));
    assertEquals("200", result.group(2));
  }

  @Test
  void findMatchLocatesNamedGroupsPastLeadingNoise() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");

    MatchResult result = matcher.findMatch("noise noise host=api.example.com status=200");

    assertNotNull(result);
    assertEquals("api.example.com", result.group("host"));
    assertEquals("200", result.group("status"));
  }

  @Test
  void findMatchFromLocatesUnnamedGroupsAfterOffset() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(\\S+) status=([+-]?\\d+)", 2);
    String input = "host=first status=1 host=api.example.com status=200";
    int offset = input.indexOf("host=", 1);

    MatchResult result = matcher.findMatchFrom(input, offset);

    assertNotNull(result);
    assertEquals("api.example.com", result.group(1));
    assertEquals("200", result.group(2));
  }

  @Test
  void findMatchReturnsNullWhenNoOccurrenceExists() throws Exception {
    ReggieMatcher matcher = matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");

    assertNull(matcher.findMatch("no host or status here"));
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
  void namedOnlyRoutingRejectsPlansMissingAnObservableNamedCapture() throws Exception {
    String pattern = "(?<outer>(?:-|(?<inner>[+-]?\\d+)))";
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    LinearTokenSequencePlan plan =
        LinearTokenSequencePlan.from(PatternCategorizer.categorize(ast)).orElseThrow();
    assertFalse(plan.coversCaptureIndexes(parser.getGroupNameMap().values()));

    ReggieMatcher matcher = Reggie.compile(pattern, NAMED_ONLY_OPTIONS);
    assertNotLinearTokenSequenceDelegate(matcher);

    Pattern jdkPattern = Pattern.compile(pattern);
    for (String input : new String[] {"-", "42"}) {
      Matcher jdk = jdkPattern.matcher(input);
      assertTrue(jdk.matches(), input);

      MatchResult actual = matcher.match(input);
      assertNotNull(actual, input);
      for (int group : parser.getGroupNameMap().values()) {
        assertEquals(jdk.start(group), actual.start(group), "start group " + group + ": " + input);
        assertEquals(jdk.end(group), actual.end(group), "end group " + group + ": " + input);
        assertEquals(jdk.group(group), actual.group(group), "value group " + group + ": " + input);
      }
    }
  }

  @Test
  void boundedExecutionUsesOnlyCharAtAndReturnsAbsoluteSpans() throws Exception {
    LinearTokenSequenceMatcher matcher =
        (LinearTokenSequenceMatcher) matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");
    String source = "prefix host=api.example status=200 suffix";
    int start = source.indexOf("host=");
    int end = source.indexOf(" suffix");
    RangeGuardCharSequence input = new RangeGuardCharSequence(source, start, end);
    int[] starts = new int[3];
    int[] ends = new int[3];

    assertTrue(matcher.matchIntoBounded(input, start, end, starts, ends));

    assertEquals(start, starts[0]);
    assertEquals(end, ends[0]);
    assertEquals("api.example", source.substring(starts[1], ends[1]));
    assertEquals("200", source.substring(starts[2], ends[2]));
  }

  @Test
  void boundedExecutionRejectsInvalidArgumentsAndKeepsArraysAtomicOnFailure() throws Exception {
    LinearTokenSequenceMatcher matcher =
        (LinearTokenSequenceMatcher) matcherFor("host=(?<host>\\S+) status=(?<status>[+-]?\\d+)");
    int[] starts = new int[] {7, 7, 7};
    int[] ends = new int[] {9, 9, 9};
    CharSequence input = new ConversionFailingCharSequence("host=api status=not-a-number");

    assertFalse(matcher.matchIntoBounded(input, 0, input.length(), starts, ends));
    assertTrue(Arrays.equals(new int[] {7, 7, 7}, starts));
    assertTrue(Arrays.equals(new int[] {9, 9, 9}, ends));
    assertThrows(
        NullPointerException.class,
        () -> matcher.matchIntoBounded(null, 0, 0, new int[3], new int[3]));
    assertThrows(
        NullPointerException.class,
        () -> matcher.matchIntoBounded(input, 0, input.length(), null, new int[3]));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> matcher.matchIntoBounded(input, -1, input.length(), new int[3], new int[3]));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> matcher.matchIntoBounded(input, 0, input.length(), new int[2], new int[3]));
  }

  @Test
  void boundedExecutionCoversQuotedAndOptionalHttpVersionPathsWithoutConversion() throws Exception {
    LinearTokenSequenceMatcher quoted =
        (LinearTokenSequenceMatcher) matcherFor("referer=\"(?<referer>[^\"]*)\"");
    String quotedSource = "referer=\"https://example.com/index.html\"";
    CharSequence quotedInput = new ConversionFailingCharSequence(quotedSource);
    assertTrue(quoted.matchesBounded(quotedInput, 0, quotedInput.length()));

    LinearTokenSequenceMatcher request =
        (LinearTokenSequenceMatcher)
            matcherFor(
                "\"(?<method>\\b\\w+\\b) (?<target>\\S+)(?: HTTP/(?<version>\\d+\\.\\d+)|)\"");
    String requestSource = "\"GET /health HTTP/2.0\"";
    CharSequence requestInput = new ConversionFailingCharSequence(requestSource);
    int[] starts = new int[4];
    int[] ends = new int[4];
    assertTrue(request.matchIntoBounded(requestInput, 0, requestInput.length(), starts, ends));
    assertEquals("GET", requestSource.substring(starts[1], ends[1]));
    assertEquals("/health", requestSource.substring(starts[2], ends[2]));
    assertEquals("2.0", requestSource.substring(starts[3], ends[3]));
  }

  @Test
  void finalSkipAnyConsumesTheTailAndPropagatesCharAtFailures() throws Exception {
    LinearTokenSequenceMatcher matcher =
        (LinearTokenSequenceMatcher) matcherFor("(?<prefix>\\S+) .*");
    String source = "token remaining";
    CountingCharSequence counted = new CountingCharSequence(source);

    assertTrue(matcher.matchesBounded(counted, 0, counted.length()));
    assertTrue(counted.charAtCalls >= source.length());
    assertThrows(
        CharAtFailure.class,
        () ->
            matcher.matchesBounded(
                new FailingAtCharSequence(source, source.indexOf('r')), 0, source.length()));
  }

  @Test
  void matchBoundedMaterializesOnlyAfterSuccessAndUsesAbsoluteSpans() throws Exception {
    LinearTokenSequenceMatcher matcher =
        (LinearTokenSequenceMatcher) matcherFor("(?<host>\\S+)(?: (?<status>\\d+)|)", 2);
    CharSequence failing = new ConversionFailingCharSequence("host not-a-number");
    assertNull(matcher.matchBounded(failing, 0, failing.length()));

    String source = "xxapiyy";
    MatchResult result = matcher.matchBounded(source, 2, 5);
    assertNotNull(result);
    assertEquals(2, result.start());
    assertEquals(5, result.end());
    assertEquals("api", result.group("host"));
    assertEquals(-1, result.start("status"));
    assertNull(result.group("status"));
  }

  @Test
  void matchBoundedMaterializesOnlyTheRequestedRegionOnSuccess() throws Exception {
    LinearTokenSequenceMatcher matcher =
        (LinearTokenSequenceMatcher) matcherFor("(?<host>\\S+)(?: (?<status>\\d+)|)", 2);
    String source = "xxapiyy trailing garbage that must never be read or materialized";
    int end = 5;
    CharSequence input = new BoundedRegionCharSequence(source, end);

    MatchResult result = matcher.matchBounded(input, 2, end);

    assertNotNull(result);
    assertEquals(2, result.start());
    assertEquals(end, result.end());
    assertEquals("api", result.group("host"));
  }

  /**
   * CharSequence test double that only permits charAt within {@code [0, end)} and only permits
   * subSequence/toString for exactly {@code [0, end)} — used to prove matchBounded materializes
   * just the requested region rather than the entire backing sequence.
   */
  private static final class BoundedRegionCharSequence implements CharSequence {
    private final String value;
    private final int end;

    BoundedRegionCharSequence(String value, int end) {
      this.value = value;
      this.end = end;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      if (index < 0 || index >= end) {
        throw new AssertionError("read outside bounded region: " + index);
      }
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int subEnd) {
      if (start != 0 || subEnd != end) {
        throw new AssertionError(
            "subSequence outside bounded region: [" + start + ", " + subEnd + ")");
      }
      return value.substring(start, subEnd);
    }

    @Override
    public String toString() {
      throw new AssertionError("toString must not be called directly; use subSequence(0, end)");
    }
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

  @Test
  void bracketedWordAfterSkipUsesLastEligibleBracketedWord() throws Exception {
    ReggieMatcher matcher = matcherFor(".* \\[(?<logger>\\b\\w+\\b)\\] .*");

    MatchResult result = matcher.match("[ignored] [[first] [last] trailing");

    assertNotNull(result);
    assertEquals("last", result.group("logger"));
  }

  @Test
  void bracketedWordAfterSkipIgnoresMalformedPrefixes() throws Exception {
    ReggieMatcher matcher = matcherFor(".* \\[(?<logger>\\b\\w+\\b)\\] .*");

    MatchResult result = matcher.match("[bad-value] [valid] trailing");

    assertNotNull(result);
    assertEquals("valid", result.group("logger"));
  }

  @Test
  void bracketedWordAfterSkipHandlesManyUnclosedBracketsInOnePass() throws Exception {
    ReggieMatcher matcher = matcherFor(".* \\[(?<logger>\\b\\w+\\b)\\] .*");
    assertNull(matcher.match("[".repeat(20_000)));

    String input = "[".repeat(20_000) + "word] ";

    MatchResult result = matcher.match(input);

    assertNotNull(result);
    assertEquals("word", result.group("logger"));
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

  private static void assertNotLinearTokenSequenceDelegate(ReggieMatcher matcher) throws Exception {
    if (matcher.getClass() == LinearTokenSequenceMatcher.class) {
      throw new AssertionError("matcher unexpectedly used LinearTokenSequenceMatcher");
    }
    try {
      Field delegate = matcher.getClass().getDeclaredField("delegate");
      delegate.setAccessible(true);
      assertNotEquals(LinearTokenSequenceMatcher.class, delegate.get(matcher).getClass());
    } catch (NoSuchFieldException ignored) {
      // A non-wrapper matcher cannot be an LTS matcher because the direct type was checked above.
    }
  }

  private static ReggieMatcher matcherFor(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    Map<String, Integer> names = parser.getGroupNameMap();
    int groupCount = names.values().stream().mapToInt(Integer::intValue).max().orElse(0);
    return matcherFor(pattern, groupCount);
  }

  /**
   * Like {@link #matcherFor(String)}, but with an explicit total capturing-group count. Needed when
   * the pattern has unnamed capturing groups: {@code parser.getGroupNameMap()} only reports named
   * groups, so the max-named-index used by {@link #matcherFor(String)} would undercount.
   */
  private static ReggieMatcher matcherFor(String pattern, int groupCount) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    Map<String, Integer> names = parser.getGroupNameMap();
    LinearTokenSequencePlan plan =
        LinearTokenSequencePlan.from(PatternCategorizer.categorize(ast)).orElseThrow();
    return new LinearTokenSequenceMatcher(pattern, plan, groupCount, names);
  }

  private static class ConversionFailingCharSequence implements CharSequence {
    final String value;

    ConversionFailingCharSequence(String value) {
      this.value = value;
    }

    @Override
    public int length() {
      return value.length();
    }

    @Override
    public char charAt(int index) {
      return value.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
      throw new AssertionError("subSequence must not be called while matching");
    }

    @Override
    public String toString() {
      throw new AssertionError("toString must not be called while matching");
    }
  }

  private static final class CountingCharSequence extends ConversionFailingCharSequence {
    int charAtCalls;

    CountingCharSequence(String value) {
      super(value);
    }

    @Override
    public char charAt(int index) {
      charAtCalls++;
      return super.charAt(index);
    }
  }

  private static final class FailingAtCharSequence extends ConversionFailingCharSequence {
    private final int failingIndex;

    FailingAtCharSequence(String value, int failingIndex) {
      super(value);
      this.failingIndex = failingIndex;
    }

    @Override
    public char charAt(int index) {
      if (index == failingIndex) {
        throw new CharAtFailure();
      }
      return super.charAt(index);
    }
  }

  private static final class CharAtFailure extends RuntimeException {}
}
