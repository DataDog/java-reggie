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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;
import com.datadoghq.reggie.runtime.BackrefBacktrackMatcher;
import com.datadoghq.reggie.runtime.HybridMatcher;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.PooledMatcher;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated {@code $Impl} caches one matcher per method and shares it across threads.
 * Quantified capturing groups ({@code usePosixLastMatch}) are realized as a DFA/NFA hybrid: two
 * generated classes wired into a {@link HybridMatcher} whose NFA half is a {@link PooledMatcher}.
 * Generated matchers that write instance state while matching are cached as a {@link
 * PooledMatcher}; lookaround patterns share one stateless {@link BackrefBacktrackMatcher}.
 */
class HybridRealizationProcessorTest {

  private static final String PROVIDER =
      "package gen;\n"
          + "import com.datadoghq.reggie.annotations.RegexPattern;\n"
          + "import com.datadoghq.reggie.runtime.ReggieMatcher;\n"
          + "public abstract class HP {\n"
          + "  @RegexPattern(\"(?<pair>.c)+\")\n"
          + "  public abstract ReggieMatcher pairs();\n"
          + "  @RegexPattern(\"x(?<=x)(b+)\")\n"
          + "  public abstract ReggieMatcher lookbehind();\n"
          + "  @RegexPattern(\"(?=.*a)(?=.*b)(x|xy)\")\n"
          + "  public abstract ReggieMatcher alternatives();\n"
          + "  @RegexPattern(\"([a-z]+)-\\\\1+\")\n"
          + "  public abstract ReggieMatcher recursive();\n"
          + "}\n";

  @Test
  void posixPatternResolvesToHybrid() throws Exception {
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test.generated", "H", "(.c)+");
    assertEquals(ReggieMatcherBytecodeGenerator.Realization.HYBRID, gen.resolveRealization(false));
    assertThrows(UnsupportedOperationException.class, gen::generate);
  }

  @Test
  void generatedImplSharesThreadSafeHybrid(@TempDir Path out) throws Exception {
    assertTrue(compile(out), "HYBRID @RegexPattern should compile");
    assertTrue(Files.exists(out.resolve("gen/HP_PairsMatcher$Dfa.class")));
    assertTrue(Files.exists(out.resolve("gen/HP_PairsMatcher$Nfa.class")));
    assertFalse(Files.exists(out.resolve("gen/HP_PairsMatcher.class")));

    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
      Object impl = loader.loadClass("gen.HP$Impl").getDeclaredConstructor().newInstance();
      ReggieMatcher m = (ReggieMatcher) impl.getClass().getMethod("pairs").invoke(impl);
      assertInstanceOf(HybridMatcher.class, m);
      assertSame(m, impl.getClass().getMethod("pairs").invoke(impl), "matcher is cached");

      MatchResult r = m.match("acbc");
      assertNotNull(r);
      assertEquals(2, r.start(1));
      assertEquals(4, r.end(1));
      assertEquals("bc", r.group("pair"));
      assertTrue(m.matches("acbc"));
      assertFalse(m.matches("acb"));

      // Concurrent capture calls on the shared matcher must not corrupt each other's spans.
      // matchInto runs on the NFA half's per-instance capture arrays.
      assertConcurrentSpans(
          m,
          new String[] {"acbc", "xcxcxc", "zc", "acbcdcec"},
          new int[] {2, 4, 0, 6},
          new int[] {4, 6, 2, 8},
          true);
    }
  }

  @Test
  void statefulNativeMatchersArePooled(@TempDir Path out) throws Exception {
    assertTrue(compile(out), "@RegexPattern should compile");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
      Object impl = loader.loadClass("gen.HP$Impl").getDeclaredConstructor().newInstance();

      // RECURSIVE_DESCENT: matchInto runs on ReggieMatcher's recursiveGroups scratch array.
      ReggieMatcher m = pooled(impl, "recursive", "([a-z]+)-\\1+");
      assertConcurrentSpans(
          m,
          new String[] {"ab-ab", "abcd-abcdabcd", "x-x", "abcdefg-abcdefg"},
          new int[] {0, 0, 0, 0},
          new int[] {2, 4, 1, 7},
          true);
    }
  }

  @Test
  void lookaroundMatchersShareTheBacktracker(@TempDir Path out) throws Exception {
    assertTrue(compile(out), "@RegexPattern should compile");
    try (URLClassLoader loader =
        new URLClassLoader(new URL[] {out.toUri().toURL()}, getClass().getClassLoader())) {
      Object impl = loader.loadClass("gen.HP$Impl").getDeclaredConstructor().newInstance();

      ReggieMatcher m = backtrack(impl, "lookbehind", "x(?<=x)(b+)");
      assertConcurrentSpans(
          m,
          new String[] {"xb", "xbbbbbbb", "xbbb", "xbbbbbbbbbbbbbbb"},
          new int[] {1, 1, 1, 1},
          new int[] {2, 8, 4, 16},
          true);

      // Leftmost-first alternation: the first alternative x wins over the longer xy.
      m = backtrack(impl, "alternatives", "(?=.*a)(?=.*b)(x|xy)");
      assertConcurrentSpans(
          m,
          new String[] {"xy ab", "x ba", "-xyab"},
          new int[] {0, 0, 1},
          new int[] {1, 1, 2},
          false);
    }
  }

  /**
   * Asserts that {@code method} of {@code impl} returns a cached, shared {@link
   * BackrefBacktrackMatcher}.
   */
  private static ReggieMatcher backtrack(Object impl, String method, String pattern)
      throws Exception {
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test.generated", "S", pattern);
    assertEquals(
        ReggieMatcherBytecodeGenerator.Realization.DELEGATE_BACKTRACK,
        gen.resolveRealization(false));
    ReggieMatcher m = (ReggieMatcher) impl.getClass().getMethod(method).invoke(impl);
    assertInstanceOf(BackrefBacktrackMatcher.class, m);
    assertSame(m, impl.getClass().getMethod(method).invoke(impl), "matcher is cached");
    return m;
  }

  /** Asserts that {@code method} of {@code impl} returns a cached {@link PooledMatcher}. */
  private static ReggieMatcher pooled(Object impl, String method, String pattern) throws Exception {
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test.generated", "S", pattern);
    assertEquals(ReggieMatcherBytecodeGenerator.Realization.NATIVE, gen.resolveRealization(false));
    MatchingStrategy strategy = gen.resolvedStrategy();
    assertTrue(strategy.hasMutableMatchState(), strategy.toString());
    ReggieMatcher m = (ReggieMatcher) impl.getClass().getMethod(method).invoke(impl);
    assertInstanceOf(PooledMatcher.class, m);
    assertSame(m, impl.getClass().getMethod(method).invoke(impl), "matcher is cached");
    return m;
  }

  /**
   * Runs findMatch, matchInto ({@code whole}) or findMatchInto, and findAll on {@code m} from 8
   * threads; group 1 of the first match in {@code inputs[k]} must span {@code [starts[k],
   * ends[k])}.
   */
  private static void assertConcurrentSpans(
      ReggieMatcher m, String[] inputs, int[] starts, int[] ends, boolean whole) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        int offset = t;
        tasks.add(
            () -> {
              int[] gs = new int[2];
              int[] ge = new int[2];
              for (int i = 0; i < 20_000; i++) {
                int k = (i + offset) % inputs.length;
                MatchResult res = m.findMatch(inputs[k]);
                assertEquals(starts[k], res.start(1), inputs[k]);
                assertEquals(ends[k], res.end(1), inputs[k]);
                assertTrue(
                    whole ? m.matchInto(inputs[k], gs, ge) : m.findMatchInto(inputs[k], gs, ge),
                    inputs[k]);
                assertEquals(starts[k], gs[1], inputs[k]);
                assertEquals(ends[k], ge[1], inputs[k]);
                MatchResult all = m.findAll(inputs[k]).get(0);
                assertEquals(starts[k], all.start(1), inputs[k]);
                assertEquals(ends[k], all.end(1), inputs[k]);
              }
              return null;
            });
      }
      for (Future<Void> f : pool.invokeAll(tasks)) {
        f.get();
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static boolean compile(Path out) throws Exception {
    JavaFileObject source =
        new SimpleJavaFileObject(URI.create("string:///gen/HP.java"), JavaFileObject.Kind.SOURCE) {
          @Override
          public CharSequence getCharContent(boolean ignore) {
            return PROVIDER;
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
