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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential parity for lazy-quantifier capture-group spans on the PikeVM capture route ({@link
 * RuntimeCompiler#compilePikeVm}, the fallback the annotation processor emits for patterns its
 * native strategies refuse).
 *
 * <p>The bug: compilePikeVm built its NFA with a non-lazy-aware {@code ThompsonBuilder}, so lazy
 * quantifiers ({@code +?}, {@code *?}, {@code ??}) were compiled with greedy priority and the
 * extracted group spans took the longest split instead of the lazy (shortest) one — e.g. for {@code
 * ^(.+?)\.([^/]+)} on {@code net/http.(*ServeMux).ServeHTTP} the JDK yields group1 = {@code
 * net/http} but the PikeVM yielded {@code net/http.(*ServeMux)}. Boolean matches()/find() are
 * priority-independent and were never affected.
 */
class PikeVMLazyGroupParityTest {
  static List<String> patterns() {
    return List.of(
        "^(.+?)\\.([^/]+)",
        "^(a+?)b",
        "(.+?)-(.+?)-",
        "^(\\w+?)/(\\w+?)$",
        "a(.*?)b",
        "^(.+?)([^/]+)");
  }

  static List<String> inputs() {
    return List.of(
        "net/http.(*ServeMux).ServeHTTP", "aaab", "a-b-c-d", "foo/bar", "xaaxbbx", "pkg/file.go");
  }

  private static String groups(MatchResult result) {
    if (result == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i <= result.groupCount(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(result.group(i));
    }
    return sb.append(']').toString();
  }

  private static String jdkGroups(Matcher matcher) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i <= matcher.groupCount(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(matcher.group(i));
    }
    return sb.append(']').toString();
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void matchGroupParityOnPikeVmRoute(String pattern) {
    Pattern jdk = Pattern.compile(pattern);
    for (String input : inputs()) {
      Matcher jm = jdk.matcher(input);
      ReggieMatcher reggie = RuntimeCompiler.compilePikeVm(pattern, "");
      MatchResult result = reggie.match(input);
      if (!jm.matches()) {
        assertEquals(null, result, "unexpected match for /" + pattern + "/ on \"" + input + "\"");
        continue;
      }
      assertEquals(
          jdkGroups(jm),
          groups(result),
          "match() group divergence for /" + pattern + "/ on \"" + input + "\"");
    }
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void findGroupParityOnPikeVmRoute(String pattern) {
    Pattern jdk = Pattern.compile(pattern);
    for (String input : inputs()) {
      Matcher jm = jdk.matcher(input);
      List<String> expected = new ArrayList<>();
      while (jm.find()) {
        expected.add(spans(jm));
      }
      ReggieMatcher reggie = RuntimeCompiler.compilePikeVm(pattern, "");
      int from = 0;
      List<String> actual = new ArrayList<>();
      while (true) {
        MatchResult result = reggie.findMatchFrom(input, from);
        if (result == null) {
          break;
        }
        actual.add(spans(result));
        from = result.end() == result.start() ? result.end() + 1 : result.end();
        if (from > input.length()) {
          break;
        }
      }
      assertEquals(
          expected, actual, "find() group divergence for /" + pattern + "/ on \"" + input + "\"");
    }
  }

  /** Every group's half-open span {@code [start,end)} — spans, not just group strings. */
  private static String spans(MatchResult result) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= result.groupCount(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('[').append(result.start(i)).append(',').append(result.end(i)).append(')');
    }
    return sb.toString();
  }

  private static String spans(Matcher matcher) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i <= matcher.groupCount(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append('[').append(matcher.start(i)).append(',').append(matcher.end(i)).append(')');
    }
    return sb.toString();
  }
}
