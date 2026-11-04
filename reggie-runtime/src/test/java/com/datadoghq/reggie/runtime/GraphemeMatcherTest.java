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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.ReggieFlags;
import com.datadoghq.reggie.ReggieOption;
import com.datadoghq.reggie.ReggieOptions;
import com.datadoghq.reggie.UnsupportedPatternException;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphemeMatcherTest {
  @BeforeEach
  void requireNativeProfile() {
    Assumptions.assumeTrue(
        GraphemeData.supported(Runtime.version().feature(), Runtime.version().pre().isPresent()));
  }

  private static final Pattern JDK = Pattern.compile("\\X");
  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "X",
          "e\u0301",
          "日本",
          "\r\n",
          "\t\u0301",
          "\u007f\u0301",
          "\r\n\u0301",
          "\u0600a",
          "\u0600\uD83D\uDC69\u200D\uD83D\uDCBB",
          "\uD83D\uDC69\u200D\uD83D\uDCBB",
          "\uD83D\uDC4D\uD83C\uDFFD",
          "\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDE9",
          "\u1100\u1161\u11a8",
          "\u0915\u094d\u0915",
          "\ud800",
          "\udc00",
          "a\ud800b\udc00",
          "\u0378\u0301",
          "\uD83D\uDC69\u0903\u200D\uD83D\uDCBB");

  @Test
  void nativeStandaloneAndExternalFlags() {
    var matcher = Reggie.compile("\\X");
    assertFalse(matcher.isJdkFallback());
    assertSame(matcher, Reggie.compile("\\X"));
    var cached = Reggie.cached("grapheme-test", "\\X");
    assertFalse(cached.isJdkFallback());
    assertSame(cached, Reggie.cached("grapheme-test", "\\X"));
    for (int flags :
        new int[] {
          0,
          ReggieFlags.CASE_INSENSITIVE,
          ReggieFlags.MULTILINE,
          ReggieFlags.DOTALL,
          ReggieFlags.UNICODE_CHARACTER_CLASS,
          ReggieFlags.CASE_INSENSITIVE | ReggieFlags.UNICODE_CHARACTER_CLASS
        }) {
      var flagged = Reggie.compile("\\X", flags);
      assertFalse(flagged.isJdkFallback());
      assertEquals("\\X", flagged.pattern());
      for (String input : INPUTS) verify(flagged, input);
    }
    var options = ReggieOptions.builder().enable(ReggieOption.CAPTURE_NAMED_ONLY).build();
    assertFalse(Reggie.compile("\\X", options).isJdkFallback());
    assertTrue(Reggie.compile("\\X", ReggieFlags.LITERAL).matches("\\X"));
    assertThrows(IllegalArgumentException.class, () -> Reggie.compile("\\X", 1 << 30));
    assertTrue(RuntimeCompiler.describeRouting("\\X").routing.contains("GRAPHEME"));
  }

  @Test
  void everyOffsetAndRegion() {
    var matcher = Reggie.compile("\\X");
    for (String input : INPUTS) verify(matcher, input);
  }

  @Test
  void compositionsKeepTheirRefusal() {
    for (String source : List.of("a\\X", "(\\X)", "(?:\\X)", "\\X+", "(?i)\\X")) {
      assertThrows(UnsupportedPatternException.class, () -> Reggie.compile(source), source);
      assertTrue(Reggie.compileAllowingFallback(source).isJdkFallback(), source);
    }
    assertTrue(Reggie.compile("\\\\X").matches("\\X"));
    assertThrows(java.util.regex.PatternSyntaxException.class, () -> Reggie.compile("[\\X]"));
  }

  @Test
  void richOperationsNeverBuildJdkDelegate() throws Exception {
    var matcher = Reggie.compile("\\X");
    String input = "a\u0301\r\n\uD83D\uDC69\u200D\uD83D\uDCBBz";
    assertEquals(
        JDK.matcher(input).results().map(java.util.regex.MatchResult::group).toList(),
        matcher.findAll(input).stream().map(MatchResult::group).toList());
    assertEquals(JDK.matcher(input).replaceAll("<$0>"), matcher.replaceAll(input, "<$0>"));
    assertEquals(JDK.matcher(input).replaceFirst("!"), matcher.replaceFirst(input, "!"));
    assertEquals(
        JDK.matcher(input).replaceAll(m -> "<" + m.group() + ">"),
        matcher.replaceAll(input, m -> "<" + m.group() + ">"));
    for (int limit : new int[] {-1, 0, 1, 2, 20}) {
      assertArrayEquals(JDK.split(input, limit), matcher.split(input, limit));
    }
    var cursor = matcher.cursor(input);
    var jdk = JDK.matcher(input);
    while (jdk.find()) {
      var next = cursor.findNext();
      assertNotNull(next);
      assertEquals(jdk.group(), next.group());
    }
    assertNull(cursor.findNext());
    verify(matcher, input);
    var field = ReggieMatcher.class.getDeclaredField("jdkRich");
    field.setAccessible(true);
    assertNull(field.get(matcher));
  }

  @Test
  void argumentAndOutputContracts() {
    var matcher = Reggie.compile("\\X");
    int[] starts = {91}, ends = {92}, bounds = {93, 94};
    assertEquals(0, matcher.findFrom("a", -1));
    assertFalse(matcher.findBoundsFrom("", 0, bounds));
    assertArrayEquals(new int[] {93, 94}, bounds);
    assertFalse(matcher.matchInto("", starts, ends));
    assertFalse(matcher.findMatchInto("", starts, ends));
    assertArrayEquals(new int[] {91}, starts);
    assertArrayEquals(new int[] {92}, ends);
    assertFalse(matcher.matchInto("", new int[0], new int[0]));
    assertThrows(
        IndexOutOfBoundsException.class, () -> matcher.matchInto("a", new int[0], new int[0]));
    assertThrows(IndexOutOfBoundsException.class, () -> matcher.matchInto("a", starts, new int[0]));
    assertArrayEquals(new int[] {91}, starts);
    assertThrows(NullPointerException.class, () -> matcher.matchInto(null, starts, ends));
    assertThrows(NullPointerException.class, () -> matcher.matchInto("", null, ends));
    assertThrows(NullPointerException.class, () -> matcher.findMatchInto("", starts, null));
    assertThrows(IndexOutOfBoundsException.class, () -> matcher.matchesBounded("a", -1, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> matcher.matchBounded("a", 1, 0));
    assertFalse(matcher.matches(null));
    assertFalse(matcher.find(null));
    assertEquals(-1, matcher.findFrom(null, 0));
    assertFalse(matcher.findBoundsFrom(null, 0, bounds));
    assertArrayEquals(new int[] {93, 94}, bounds);
    assertNull(matcher.match(null));
    assertNull(matcher.findMatch(null));
  }

  @Test
  void contextualFuzzAndLongClusters() {
    var matcher = Reggie.compile("\\X");
    int[] alphabet = {
      0, 9, 10, 13, 65, 127, 0x300, 0x378, 0x600, 0x903, 0x915, 0x94d, 0x1100, 0x1161, 0x11a8,
      0x200c, 0x200d, 0xd800, 0xdc00, 0x1f469, 0x1f4bb, 0x1f1e6, 0x1f1e7, 0x1f3fb
    };
    Random random = new Random(0x4752415048454d45L);
    for (int trial = 0; trial < 2000; trial++) {
      StringBuilder input = new StringBuilder();
      for (int n = random.nextInt(8); n > 0; n--)
        input.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
      verify(matcher, input.toString());
    }
    String marks = "a" + "\u0301".repeat(1_000_000);
    assertTrue(matcher.matches(marks));
    int[] bounds = new int[2];
    assertTrue(matcher.findBoundsFrom(marks, 0, bounds));
    assertArrayEquals(new int[] {0, marks.length()}, bounds);
  }

  @Test
  void cachedMatcherIsConcurrent() throws Exception {
    var matcher = Reggie.compile("\\X");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var tasks =
          java.util.stream.IntStream.range(0, 16)
              .<java.util.concurrent.Callable<Void>>mapToObj(
                  i ->
                      () -> {
                        for (String input : INPUTS) verify(matcher, input);
                        return null;
                      })
              .toList();
      for (var future : executor.invokeAll(tasks)) future.get();
    }
  }

  private static void verify(ReggieMatcher matcher, String input) {
    boolean wholeMatch = JDK.matcher(input).matches();
    assertEquals(wholeMatch, matcher.matches(input), input);
    int[] matchStarts = {91}, matchEnds = {92};
    assertEquals(wholeMatch, matcher.matchInto(input, matchStarts, matchEnds), input);
    assertEquals(wholeMatch ? 0 : 91, matchStarts[0], input);
    assertEquals(wholeMatch ? input.length() : 92, matchEnds[0], input);
    compare(
        wholeMatch ? JDK.matcher(input).results().findFirst().orElseThrow() : null,
        matcher.match(input));
    assertEquals(JDK.matcher(input).find(), matcher.find(input));
    for (int from = 0; from <= input.length() + 1; from++) {
      var j = JDK.matcher(input);
      boolean found = from <= input.length() && j.find(from);
      assertEquals(found ? j.start() : -1, matcher.findFrom(input, from), input);
      compare(found ? j.toMatchResult() : null, matcher.findMatchFrom(input, from));
      int[] bounds = {91, 92}, starts = {93}, ends = {94};
      assertEquals(found, matcher.findBoundsFrom(input, from, bounds), input);
      assertEquals(found, matcher.findMatchInto(input, from, starts, ends), input);
      assertArrayEquals(found ? new int[] {j.start(), j.end()} : new int[] {91, 92}, bounds);
      assertEquals(found ? j.start() : 93, starts[0]);
      assertEquals(found ? j.end() : 94, ends[0]);
    }
    for (CharSequence sequence :
        List.of(input, new StringBuilder(input), new StringBuffer(input), CharBuffer.wrap(input))) {
      for (int start = 0; start <= input.length(); start++) {
        for (int end = start; end <= input.length(); end++) {
          var j = JDK.matcher(sequence).region(start, end);
          boolean matches = j.matches();
          assertEquals(
              matches,
              matcher.matchesBounded(sequence, start, end),
              () ->
                  "bounded "
                      + sequence
                      + " "
                      + Arrays.toString(sequence.toString().chars().toArray()));
          compare(matches ? j.toMatchResult() : null, matcher.matchBounded(sequence, start, end));
        }
      }
    }
  }

  private static void compare(java.util.regex.MatchResult expected, MatchResult actual) {
    if (expected == null) {
      assertNull(actual);
      return;
    }
    assertNotNull(actual);
    assertEquals(0, actual.groupCount());
    assertEquals(expected.start(), actual.start());
    assertEquals(expected.end(), actual.end());
    assertEquals(expected.group(), actual.group());
  }
}
