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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JDK-differential check that a {@code @RegexPattern} with {@code ALLOW_JDK_FALLBACK}, served by
 * the JDK fallback, is not narrowed by a prefilter built from reggie's own parse.
 */
class FallbackPrefilterProcessorTest {

  private static final String PATTERN = "(?:(a)|b)+\\1\\v";
  private static final String INPUT = "aa\u000B";

  @Test
  void fallbackFindEqualsJdk(@TempDir Path out) throws Exception {
    assertTrue(compile(out), "@RegexPattern provider should compile");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
      Object impl = loader.loadClass("gen.FP$Impl").getDeclaredConstructor().newInstance();
      ReggieMatcher r = (ReggieMatcher) impl.getClass().getMethod("m").invoke(impl);
      Matcher j = Pattern.compile(PATTERN).matcher(INPUT);
      String jdk = j.find() ? "[" + j.start() + "," + j.end() + ")" : "-";
      MatchResult mr = r.findMatchFrom(INPUT, 0);
      String reggie = mr == null ? "-" : "[" + mr.start() + "," + mr.end() + ")";
      assertEquals(jdk, reggie);
    }
  }

  private static boolean compile(Path out) throws Exception {
    String literal = PATTERN.replace("\\", "\\\\");
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.ReggieOption;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class FP {\n"
            + "  @RegexPattern(value = \""
            + literal
            + "\", options = ReggieOption.ALLOW_JDK_FALLBACK)\n"
            + "  public abstract ReggieMatcher m();\n"
            + "}\n";
    JavaFileObject source =
        new SimpleJavaFileObject(URI.create("string:///gen/FP.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignore) {
            return code;
          }
        };
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    var fm = javac.getStandardFileManager(null, null, null);
    fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(out.toFile()));
    StringWriter sw = new StringWriter();
    boolean ok =
        javac
            .getTask(
                sw,
                fm,
                null,
                Arrays.asList("-classpath", System.getProperty("java.class.path")),
                null,
                List.of(source))
            .call();
    fm.close();
    if (!ok) System.out.println(sw);
    return ok;
  }
}
