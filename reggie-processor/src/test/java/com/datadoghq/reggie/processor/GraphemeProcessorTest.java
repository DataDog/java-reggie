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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.PooledMatcher;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GraphemeProcessorTest {

  @Test
  void providerCompiledOnJava21UsesTheExecutingJdkProfile() {
    GraphemePatterns provider = Reggie.patterns(GraphemePatterns.class);
    if (Runtime.version().pre().isPresent() || Runtime.version().feature() > 27) {
      assertThrows(UnsupportedPatternException.class, provider::strict);
      assertThrows(UnsupportedPatternException.class, provider::strict);
    } else {
      assertSame(Reggie.compile("\\X"), provider.strict());
    }

    ReggieMatcher matcher = provider.allowingFallback();
    assertSame(Reggie.compileAllowingFallback("\\X"), matcher);
    Pattern jdk = Pattern.compile("\\X");
    for (String input : List.of("\u0915\u094D\u0915", "\uD803\uDEFA\u0301")) {
      var expected = jdk.matcher(input).results().toList();
      List<MatchResult> actual = matcher.findAll(input);
      assertEquals(expected.size(), actual.size());
      for (int i = 0; i < expected.size(); i++) {
        assertEquals(expected.get(i).start(), actual.get(i).start());
        assertEquals(expected.get(i).end(), actual.get(i).end());
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void accessorSelectsAndCachesTheExecutingJdkMatcher(boolean allowFallback, @TempDir Path out)
      throws Exception {
    DiagnosticCollector<JavaFileObject> diagnostics = compile("\\X", allowFallback, out);
    assertNoErrors(diagnostics);
    assertTrue(
        diagnostics.getDiagnostics().stream()
            .anyMatch(
                d ->
                    d.getKind() == Diagnostic.Kind.NOTE
                        && d.getMessage(Locale.ROOT).contains("executing JDK")));
    assertFalse(Files.exists(out.resolve("gen/GP_MatcherMatcher.class")));

    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
      Object provider = loader.loadClass("gen.GP$Impl").getDeclaredConstructor().newInstance();
      var accessor = provider.getClass().getMethod("matcher");
      var cached = provider.getClass().getDeclaredField("matcher");
      cached.setAccessible(true);
      assertNull(cached.get(provider));

      boolean supported =
          Runtime.version().feature() >= 21
              && Runtime.version().feature() <= 27
              && Runtime.version().pre().isEmpty();
      if (!supported && !allowFallback) {
        for (int attempt = 0; attempt < 2; attempt++) {
          InvocationTargetException failure =
              assertThrows(InvocationTargetException.class, () -> accessor.invoke(provider));
          assertInstanceOf(UnsupportedPatternException.class, failure.getCause());
          assertNull(cached.get(provider));
        }
        return;
      }

      ReggieMatcher matcher = (ReggieMatcher) accessor.invoke(provider);
      assertSame(matcher, accessor.invoke(provider));
      assertSame(
          allowFallback ? Reggie.compileAllowingFallback("\\X") : Reggie.compile("\\X"), matcher);
      assertFalse(matcher instanceof PooledMatcher);
      assertEquals(!supported, matcher.isJdkFallback());

      Pattern jdk = Pattern.compile("\\X");
      for (String input :
          List.of(
              "",
              "a",
              "e\u0301",
              "\r\n",
              "\u0915\u094D\u0915",
              "\uD83D\uDC69\u200D\uD83D\uDCBB",
              "ab")) {
        Matcher expected = jdk.matcher(input);
        assertEquals(expected.matches(), matcher.matches(input));
        assertMatch(expected.matches(), expected, matcher.match(input));
        assertEquals(expected.find(0), matcher.find(input));
        assertMatch(expected.find(0), expected, matcher.findMatch(input));

        for (int start = -1; start <= input.length() + 1; start++) {
          boolean found = start <= input.length() && expected.find(Math.max(0, start));
          assertEquals(found ? expected.start() : -1, matcher.findFrom(input, start));
          assertMatch(found, expected, matcher.findMatchFrom(input, start));
          int[] bounds = {-2, -2};
          assertEquals(found, matcher.findBoundsFrom(input, start, bounds));
          assertArrayEquals(
              found ? new int[] {expected.start(), expected.end()} : new int[] {-2, -2}, bounds);

          int[] starts = {-2};
          int[] ends = {-2};
          assertEquals(found, matcher.findMatchInto(input, start, starts, ends));
          assertEquals(found ? expected.start() : -2, starts[0]);
          assertEquals(found ? expected.end() : -2, ends[0]);
        }

        StringBuilder regionInput = new StringBuilder("x").append(input).append("y");
        Matcher region = jdk.matcher(regionInput).region(1, input.length() + 1);
        assertEquals(region.matches(), matcher.matchesBounded(regionInput, 1, input.length() + 1));
        assertMatch(
            region.matches(), region, matcher.matchBounded(regionInput, 1, input.length() + 1));
      }

      var jdkRich = ReggieMatcher.class.getDeclaredField("jdkRich");
      jdkRich.setAccessible(true);
      assertNull(jdkRich.get(matcher), "generated accessor must retain the native rich API");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"a\\X", "(\\X)", "(?:\\X)", "\\X+", "(?i)\\X"})
  void compositionsStillRequireFallback(String pattern, @TempDir Path out) throws Exception {
    DiagnosticCollector<JavaFileObject> diagnostics = compile(pattern, false, out);
    assertTrue(
        diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));

    Path allowed = Files.createDirectory(out.resolve("allowed"));
    assertNoErrors(compile(pattern, true, allowed));
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {allowed.toUri().toURL()}, getClass().getClassLoader())) {
      Object provider = loader.loadClass("gen.GP$Impl").getDeclaredConstructor().newInstance();
      ReggieMatcher matcher =
          (ReggieMatcher) provider.getClass().getMethod("matcher").invoke(provider);
      Pattern jdk = Pattern.compile(pattern);
      for (String input : List.of("", "a", "ae\u0301", "e\u0301")) {
        Matcher expected = jdk.matcher(input);
        assertMatch(expected.matches(), expected, matcher.match(input));
      }
    }
  }

  private static void assertMatch(boolean matched, Matcher expected, MatchResult actual) {
    if (!matched) {
      assertNull(actual);
      return;
    }
    assertNotNull(actual);
    assertEquals(expected.groupCount(), actual.groupCount());
    for (int group = 0; group <= expected.groupCount(); group++) {
      assertEquals(expected.start(group), actual.start(group));
      assertEquals(expected.end(group), actual.end(group));
      assertEquals(expected.group(group), actual.group(group));
    }
  }

  private static void assertNoErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
    assertTrue(
        diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
        () -> diagnostics.getDiagnostics().toString());
  }

  private static DiagnosticCollector<JavaFileObject> compile(
      String pattern, boolean allowFallback, Path out) throws Exception {
    String code =
        """
        package gen;
        import com.datadoghq.reggie.ReggieOption;
        import com.datadoghq.reggie.annotations.RegexPattern;
        import com.datadoghq.reggie.runtime.ReggieMatcher;
        public abstract class GP {
          @RegexPattern(value = "%s"%s)
          public abstract ReggieMatcher matcher();
        }
        """
            .formatted(
                pattern.replace("\\", "\\\\"),
                allowFallback ? ", options = ReggieOption.ALLOW_JDK_FALLBACK" : "");
    JavaFileObject source =
        new SimpleJavaFileObject(URI.create("string:///gen/GP.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
          }
        };
    var javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager files = javac.getStandardFileManager(diagnostics, null, null)) {
      files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
      javac
          .getTask(
              null,
              files,
              diagnostics,
              List.of(
                  "-classpath",
                  System.getProperty("java.class.path"),
                  "-processor",
                  RegexPatternProcessor.class.getName()),
              null,
              List.of(source))
          .call();
    }
    return diagnostics;
  }
}
