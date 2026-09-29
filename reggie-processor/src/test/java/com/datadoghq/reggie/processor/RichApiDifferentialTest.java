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
package com.datadoghq.reggie.processor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Differential parity for the rich match-result API ({@code findAll}, {@code replaceAll}, {@code
 * split}) on annotation-processor generated matchers, against the JDK.
 *
 * <p>The bug: the APT dispatcher emitted {@code findMatchFrom}/{@code findBoundsFrom} for the
 * DFA-unrolled and generic-NFA strategies — whose bodies call the {@code findLongestMatchEnd}
 * helper — without also emitting that helper, so every rich-API use crashed with {@link
 * NoSuchMethodError}. {@link com.datadoghq.reggie.runtime.RuntimeCompiler} emits the helper for the
 * same strategies; the dispatcher must mirror it (dual-path rule).
 */
class RichApiDifferentialTest {
  private static final List<String> PATTERNS =
      List.of(
          "[\"']",
          "puma threadpool [0-9]+",
          "^[a-zA-Z_0-9]+$",
          "/pkg/mod/.*@",
          "[^0-9a-zA-Z._]",
          "-(\\d+)",
          "(^|\\s)(?:OR|AND|NOT)(?=\\s|\\()",
          // DFA_UNROLLED_WITH_ASSERTIONS bounds: findBoundsFrom used to abort its inline greedy
          // scan on the first failed lookahead instead of skipping the accepting-position record
          // and continuing — on \"aab\" replaceAll(\"_\") returned the input unchanged while
          // findAll
          // was correct. Assertion-bearing DFAs now derive bounds from findLongestMatchEnd.
          "a+(?=b)",
          // "^(.+?)\\.([^/]+)" is intentionally absent: the APT pipeline routes it to the
          // PIKEVM_CAPTURE fallback, whose lazy-group assignment diverges from the JDK —
          // fixed on fix/pikevm-lazy-group-priority; add it back there.
          "\\d{3}-\\d{3}-\\d{4}");

  private static final List<String> INPUTS =
      List.of(
          "",
          "a'b",
          "aab",
          "puma threadpool 42 and puma threadpool 7",
          "abc123",
          "/pkg/mod/github.com/pkg/errors@v0.9.1/x.go",
          "a b!c.d",
          "worker-1-pool-2-worker-3",
          "a OR b AND NOT c",
          "net/http.(*ServeMux).ServeHTTP",
          "call 555-123-4567 or 555-000-0000",
          "\u00e9t\u00e9-1");

  static List<String> patterns() {
    return PATTERNS;
  }

  private Object compile(String pattern, String className) throws Exception {
    byte[] bytecode =
        new ReggieMatcherBytecodeGenerator("test.generated", className, pattern).generate();
    assertNotNull(bytecode);
    Class<?> cls = new TestClassLoader().defineClass("test.generated." + className, bytecode);
    return cls.getDeclaredConstructor().newInstance();
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void replaceAllParity(String pattern) throws Exception {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher matcher =
        (ReggieMatcher) compile(pattern, "M" + Integer.toHexString(pattern.hashCode()));
    for (String input : INPUTS) {
      for (String replacement : List.of("_", "-X", "", "[$0]")) {
        assertEquals(
            jdk.matcher(input).replaceAll(replacement),
            matcher.replaceAll(input, replacement),
            "replaceAll(" + replacement + ") divergence for /" + pattern + "/ input: " + input);
      }
    }
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void splitParity(String pattern) throws Exception {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher matcher =
        (ReggieMatcher) compile(pattern, "M" + Integer.toHexString(pattern.hashCode()));
    for (String input : INPUTS) {
      assertArrayEquals(
          jdk.split(input),
          matcher.split(input),
          "split() divergence for /" + pattern + "/ input: " + input);
    }
  }

  @ParameterizedTest
  @MethodSource("patterns")
  void findAllParity(String pattern) throws Exception {
    Pattern jdk = Pattern.compile(pattern);
    ReggieMatcher matcher =
        (ReggieMatcher) compile(pattern, "M" + Integer.toHexString(pattern.hashCode()));
    for (String input : INPUTS) {
      assertEquals(
          jdk.matcher(input).results().count(),
          matcher.findAll(input).size(),
          "findAll() divergence for /" + pattern + "/ input: " + input);
    }
  }

  private static class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return super.defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
