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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
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

/**
 * Drives the real {@link RegexPatternProcessor} via the JDK compiler and asserts the compile-time
 * "uses java.util.regex under the hood" diagnostics: a RICH_API_HYBRID {@code @RegexPattern} emits
 * a warning, and a NATIVE one does not.
 */
class HybridDiagnosticProcessorTest {

  private static final class Source extends SimpleJavaFileObject {
    private final String code;

    Source(String fqn, String code) {
      super(URI.create("string:///" + fqn.replace('.', '/') + ".java"), Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return code;
    }
  }

  private DiagnosticCollector<JavaFileObject> compile(String fqn, String code, Path out)
      throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null)) {
      Files.createDirectories(out);
      fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(out));
      // Run the real annotation processor (it lives on the test classpath via the project dep).
      JavaCompiler.CompilationTask task =
          compiler.getTask(
              null,
              fm,
              diagnostics,
              List.of("-processor", RegexPatternProcessor.class.getName()),
              null,
              List.of(new Source(fqn, code)));
      task.call();
    }
    return diagnostics;
  }

  private boolean hasWarningContaining(
      DiagnosticCollector<JavaFileObject> diagnostics, String needle) {
    return diagnostics.getDiagnostics().stream()
        .filter(
            d ->
                d.getKind() == Diagnostic.Kind.WARNING
                    || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING)
        .anyMatch(d -> d.getMessage(Locale.ROOT).contains(needle));
  }

  @Test
  void hybridPatternEmitsCompileTimeWarning(@TempDir Path out) throws IOException {
    // Literal alternation -> SPECIALIZED_LITERAL_ALTERNATION -> RICH_API_HYBRID.
    String code =
        "package t;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class HybridPatterns {\n"
            + "  @RegexPattern(\"alpha|bravo|charlie|delta|echo\")\n"
            + "  public abstract ReggieMatcher words();\n"
            + "}\n";
    DiagnosticCollector<JavaFileObject> diagnostics = compile("t.HybridPatterns", code, out);
    assertTrue(
        hasWarningContaining(diagnostics, "HYBRID matcher"),
        () -> "expected hybrid warning; got " + diagnostics.getDiagnostics());
  }

  @Test
  void nativePatternEmitsNoHybridWarning(@TempDir Path out) throws IOException {
    // Phone -> SPECIALIZED_FIXED_SEQUENCE -> NATIVE.
    String code =
        "package t;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class NativePatterns {\n"
            + "  @RegexPattern(\"\\\\d{3}-\\\\d{3}-\\\\d{4}\")\n"
            + "  public abstract ReggieMatcher phone();\n"
            + "}\n";
    DiagnosticCollector<JavaFileObject> diagnostics = compile("t.NativePatterns", code, out);
    assertTrue(
        !hasWarningContaining(diagnostics, "HYBRID matcher")
            && !hasWarningContaining(diagnostics, "delegates the whole matcher"),
        () -> "unexpected jdk-dependency warning; got " + diagnostics.getDiagnostics());
  }
}
