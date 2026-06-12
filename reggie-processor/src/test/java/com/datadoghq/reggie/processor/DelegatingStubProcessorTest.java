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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DelegatingStubProcessorTest {

  private static JavaFileObject src(String fqcn, String code) {
    return new SimpleJavaFileObject(
        URI.create("string:///" + fqcn.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignore) {
        return code;
      }
    };
  }

  private boolean compile(Path out, JavaFileObject source) throws Exception {
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

  @Test
  void pikevmPatternCompilesWithoutFlag(@TempDir Path out) throws Exception {
    // (<\w+>).*(</ \w+>) is PIKEVM_CAPTURE — should compile with no options
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class PVM {\n"
            + "  @RegexPattern(\"(<\\\\w+>).*(</\\\\w+>)\")\n"
            + "  public abstract ReggieMatcher tags();\n"
            + "}\n";
    assertTrue(compile(out, src("gen.PVM", code)), "PIKEVM @RegexPattern should compile");
  }

  @Test
  void fallbackPatternFailsWithoutFlag(@TempDir Path out) throws Exception {
    // (a)\1|b has a bypass path through the NFA (the "b" branch skips group 1),
    // so the processor detects captureAmbiguous=true and requires ALLOW_JDK_FALLBACK.
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class FB {\n"
            + "  @RegexPattern(\"(a)\\\\1|b\")\n"
            + "  public abstract ReggieMatcher backref();\n"
            + "}\n";
    assertFalse(compile(out, src("gen.FB", code)), "fallback pattern must fail without flag");
  }

  @Test
  void fallbackPatternCompilesWithFlag(@TempDir Path out) throws Exception {
    // Same pattern with ALLOW_JDK_FALLBACK — should produce a delegating stub.
    String code =
        "package gen;\n"
            + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
            + "import com.datadoghq.reggie.ReggieOption;\n"
            + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
            + "public abstract class FBOK {\n"
            + "  @RegexPattern(value = \"(a)\\\\1|b\","
            + " options = ReggieOption.ALLOW_JDK_FALLBACK)\n"
            + "  public abstract ReggieMatcher backref();\n"
            + "}\n";
    assertTrue(compile(out, src("gen.FBOK", code)), "fallback pattern should compile with flag");
  }
}
