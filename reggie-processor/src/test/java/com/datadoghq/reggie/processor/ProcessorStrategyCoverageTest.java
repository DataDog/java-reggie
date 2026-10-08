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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Strategies that {@code RuntimeCompiler} generates but the annotation processor's strategy switch
 * did not handle (build failed with "Unknown strategy"). Each pattern must route to its strategy,
 * compile to a {@link ReggieMatcher}, and agree with {@code java.util.regex}.
 */
class ProcessorStrategyCoverageTest {

  static Stream<Arguments> patterns() {
    return Stream.of(
        Arguments.of(".*a.{9}", MatchingStrategy.BITPARALLEL_GLUSHKOV),
        Arguments.of("(?:[a-z][0-9]){1000}", MatchingStrategy.COUNTING_GLUSHKOV),
        Arguments.of("a(b*)", MatchingStrategy.SPECIALIZED_CONCAT_GREEDY_GROUP),
        Arguments.of("x(y*)z", MatchingStrategy.SPECIALIZED_CONCAT_GREEDY_GROUP),
        Arguments.of("(a)+", MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP),
        Arguments.of("(\\d){1,}", MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP),
        Arguments.of("([ab]|[cd]){1,}", MatchingStrategy.SPECIALIZED_QUANTIFIED_GROUP),
        Arguments.of("(.*)end", MatchingStrategy.GREEDY_SUFFIX),
        Arguments.of("(.+)\\b(\\d+)$", MatchingStrategy.GREEDY_SUFFIX),
        Arguments.of("z(.*)(\\d{2,3})", MatchingStrategy.GREEDY_SUFFIX));
  }

  private static final List<String> INPUTS =
      List.of(
          "",
          "a",
          "abbb",
          "xyyz",
          "xz",
          "aaa",
          "123",
          "abcd",
          "the end",
          "endend",
          "abc123",
          "za123456789zz",
          "a 12\n",
          "z1234",
          "a0".repeat(1000),
          "a0".repeat(999));

  @ParameterizedTest
  @MethodSource("patterns")
  void generatesAndMatchesJdk(String pattern, MatchingStrategy expected) throws Exception {
    String className = "S" + Integer.toHexString(pattern.hashCode());
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test.generated", className, pattern);
    assertEquals(ReggieMatcherBytecodeGenerator.Realization.NATIVE, gen.resolveRealization(false));
    assertEquals(expected, gen.resolvedStrategy());
    byte[] bytecode = gen.generate();
    ReggieMatcher matcher =
        (ReggieMatcher)
            new TestClassLoader()
                .defineClass("test.generated." + className, bytecode)
                .getDeclaredConstructor()
                .newInstance();

    Pattern jdk = Pattern.compile(pattern);
    for (String input : INPUTS) {
      String ctx = "/" + pattern + "/ on \"" + abbreviate(input) + "\"";
      assertEquals(jdk.matcher(input).matches(), matcher.matches(input), "matches " + ctx);
      assertEquals(jdk.matcher(input).find(), matcher.find(input), "find " + ctx);
      Matcher m = jdk.matcher(input);
      assertSpans(m, m.matches(), matcher.match(input), "match " + ctx);
      Matcher f = jdk.matcher(input);
      assertSpans(f, f.find(), matcher.findMatch(input), "findMatch " + ctx);
    }
  }

  private static void assertSpans(Matcher m, boolean found, MatchResult r, String ctx) {
    if (!found) {
      assertNull(r, ctx);
      return;
    }
    assertNotNull(r, ctx);
    for (int g = 0; g <= m.groupCount(); g++) {
      assertEquals(m.start(g), r.start(g), "start(" + g + ") " + ctx);
      assertEquals(m.end(g), r.end(g), "end(" + g + ") " + ctx);
    }
  }

  private static String abbreviate(String s) {
    return s.length() > 20 ? s.substring(0, 20) + "...(" + s.length() + ")" : s;
  }

  private static class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return super.defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
