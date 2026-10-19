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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A pattern the parser rejects fails only its own {@code @RegexPattern} method, not the whole
 * annotated class; a pattern the parser refuses as unsupported delegates to the JDK when {@code
 * ALLOW_JDK_FALLBACK} is set.
 */
class ParseErrorProcessorTest {

  @Test
  void parseErrorFailsOnlyItsMethod(@TempDir Path out) throws Exception {
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class PE {\n"
            + "  @RegexPattern(\"(abc\")\n"
            + "  public abstract ReggieMatcher bad();\n"
            + "  @RegexPattern(\"abc\")\n"
            + "  public abstract ReggieMatcher good();\n"
            + "}\n";
    List<Diagnostic<? extends JavaFileObject>> errors = errors(compile("gen.PE", code, out));
    assertEquals(1, errors.size(), () -> "expected one error; got " + errors);
    assertEquals(6, errors.get(0).getLineNumber(), () -> "error not on bad(): " + errors);
    assertFalse(errors.get(0).getMessage(Locale.ROOT).contains("Failed to process class"));
  }

  @Test
  void reversedClassRangeFailsOnlyItsMethod(@TempDir Path out) throws Exception {
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class RR {\n"
            + "  @RegexPattern(\"[z-a]\")\n"
            + "  public abstract ReggieMatcher bad();\n"
            + "  @RegexPattern(\"abc\")\n"
            + "  public abstract ReggieMatcher good();\n"
            + "}\n";
    List<Diagnostic<? extends JavaFileObject>> errors = errors(compile("gen.RR", code, out));
    assertEquals(1, errors.size(), () -> "expected one error; got " + errors);
    assertEquals(6, errors.get(0).getLineNumber(), () -> "error not on bad(): " + errors);
    assertTrue(errors.get(0).getMessage(Locale.ROOT).contains("Illegal character range"));
  }

  @Test
  void unsupportedPatternWithoutFallbackFailsOnlyItsMethod(@TempDir Path out) throws Exception {
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class UP {\n"
            + "  @RegexPattern(\"(?iu)k\")\n"
            + "  public abstract ReggieMatcher bad();\n"
            + "  @RegexPattern(\"abc\")\n"
            + "  public abstract ReggieMatcher good();\n"
            + "}\n";
    List<Diagnostic<? extends JavaFileObject>> errors = errors(compile("gen.UP", code, out));
    assertEquals(1, errors.size(), () -> "expected one error; got " + errors);
    assertEquals(6, errors.get(0).getLineNumber(), () -> "error not on bad(): " + errors);
    assertFalse(errors.get(0).getMessage(Locale.ROOT).contains("Failed to process class"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"(?iu)k", "a\\X"})
  void unsupportedPatternWithFallbackEqualsJdk(String pattern, @TempDir Path out) throws Exception {
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.ReggieOption;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class UF {\n"
            + "  @RegexPattern(value = \""
            + pattern.replace("\\", "\\\\")
            + "\", options = ReggieOption.ALLOW_JDK_FALLBACK)\n"
            + "  public abstract ReggieMatcher m();\n"
            + "}\n";
    DiagnosticCollector<JavaFileObject> diagnostics = compile("gen.UF", code, out);
    assertTrue(errors(diagnostics).isEmpty(), () -> "unexpected errors: " + errors(diagnostics));
    Pattern jdk = Pattern.compile(pattern);
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
      Object impl = loader.loadClass("gen.UF$Impl").getDeclaredConstructor().newInstance();
      ReggieMatcher r = (ReggieMatcher) impl.getClass().getMethod("m").invoke(impl);
      for (String input : new String[] {"k", "K", "\u212A", "x", "ab", "ae\u0301", "a"}) {
        assertEquals(jdk.matcher(input).matches(), r.matches(input), input);
        assertEquals(jdk.matcher(input).find(), r.find(input), input);
      }
    }
  }

  private static List<Diagnostic<? extends JavaFileObject>> errors(
      DiagnosticCollector<JavaFileObject> diagnostics) {
    return diagnostics.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
        .collect(Collectors.toList());
  }

  private static DiagnosticCollector<JavaFileObject> compile(String fqn, String code, Path out)
      throws Exception {
    JavaFileObject source =
        new SimpleJavaFileObject(
            URI.create("string:///" + fqn.replace('.', '/') + ".java"),
            JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignore) {
            return code;
          }
        };
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, null)) {
      fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
      javac
          .getTask(
              null,
              fm,
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
