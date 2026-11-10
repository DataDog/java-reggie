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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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

  /**
   * Strategies intentionally without a {@code case} label in the processor's strategy switch;
   * {@code resolveRealization} routes them to a delegate realization before dispatch (mirrors
   * {@code scripts/check-dual-path.py} EXEMPT budget).
   */
  private static final java.util.Set<String> PROCESSOR_EXEMPT =
      java.util.Set.of("PIKEVM_CAPTURE", "BITSTATE_CAPTURE");

  /**
   * Dual-path completeness gate (AGENTS.md Critical Rule 2): every {@link MatchingStrategy}
   * constant must have a statement-form {@code case} label in BOTH {@code RuntimeCompiler} (runtime
   * path) and {@code ReggieMatcherBytecodeGenerator} (@RegexPattern compile-time path) — minus the
   * processor EXEMPT budget. The historical fault class: a strategy wired into RuntimeCompiler
   * first (LAZY_DFA, OPTIMIZED_NFA_WITH_BACKREFS) while the processor path lagged until runtime
   * failure ("Unknown strategy", missing bounded methods, IllegalAccessError in user packages).
   * Mirrors scripts/check-dual-path.py so the fault class fails plain {@code ./gradlew build} on
   * every PR, not only agent PRs behind the agent-gates workflow.
   */
  @Test
  void processorDispatchCoversRuntimeStrategies() throws IOException {
    // Gradle runs tests with the module directory as working directory; source layout is
    // ../reggie-codegen, ../reggie-runtime, and this module's src/main.
    Path codegen = Path.of("..", "reggie-codegen");
    Path runtime = Path.of("..", "reggie-runtime");
    assumeTrue(
        Files.isDirectory(codegen), "source checkout not present: " + codegen.toAbsolutePath());
    assumeTrue(
        Files.isDirectory(runtime), "source checkout not present: " + runtime.toAbsolutePath());

    String enumSrc =
        stripComments(
            Files.readString(
                codegen.resolve(
                    "src/main/java/com/datadoghq/reggie/codegen/analysis/PatternAnalyzer.java")));
    String runtimeSrc =
        stripComments(
            Files.readString(
                runtime.resolve(
                    "src/main/java/com/datadoghq/reggie/runtime/RuntimeCompiler.java")));
    String processorSrc =
        stripComments(
            Files.readString(
                Path.of(
                    "src/main/java/com/datadoghq/reggie/processor/ReggieMatcherBytecodeGenerator.java")));

    java.util.Set<String> constants = enumConstants(enumSrc);
    assertTrue(!constants.isEmpty(), "no MatchingStrategy constants parsed");
    java.util.Set<String> runtimeCases = caseLabels(runtimeSrc);
    java.util.Set<String> processorCases = caseLabels(processorSrc);

    var missing = new java.util.ArrayList<String>();
    for (String c : constants) {
      if (!runtimeCases.contains(c)) {
        missing.add(c + ": no `case " + c + "` in RuntimeCompiler.java");
      } else if (!processorCases.contains(c) && !PROCESSOR_EXEMPT.contains(c)) {
        missing.add(c + ": no `case " + c + "` in ReggieMatcherBytecodeGenerator.java");
      }
    }
    assertTrue(
        missing.isEmpty(),
        "Dual-path rule (AGENTS.md Critical Rule 2) violated — update BOTH compilation paths:\n    "
            + String.join("\n    ", missing));
  }

  private static String stripComments(String src) {
    String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
    return noBlock.replaceAll("//[^\n]*", "");
  }

  private static java.util.Set<String> enumConstants(String src) {
    java.util.regex.Matcher m = Pattern.compile("enum\\s+MatchingStrategy\\s*\\{").matcher(src);
    assertTrue(m.find(), "MatchingStrategy enum not found");
    String body = src.substring(m.end());
    int end = Integer.min(body.indexOf(';'), body.indexOf('}'));
    var out = new LinkedHashSet<String>();
    for (String part : body.substring(0, end).split(",")) {
      String c = part.strip();
      if (!c.isEmpty()) {
        out.add(c);
      }
    }
    return out;
  }

  /**
   * Statement-form labels only ({@code case X:} / {@code case X, Y:}); arrow-form switch
   * expressions are auxiliary lookups, not strategy dispatch.
   */
  private static java.util.Set<String> caseLabels(String src) {
    var out = new LinkedHashSet<String>();
    java.util.regex.Matcher m =
        Pattern.compile("\\bcase\\s+([A-Z][A-Z0-9_]*(?:\\s*,\\s*[A-Z][A-Z0-9_]*)*)\\s*:")
            .matcher(src);
    while (m.find()) {
      for (String label : m.group(1).split(",")) {
        out.add(label.strip());
      }
    }
    return out;
  }
}
