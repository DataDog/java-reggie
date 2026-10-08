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

import com.datadoghq.reggie.codegen.analysis.PatternAnalyzer.MatchingStrategy;
import com.datadoghq.reggie.runtime.MatchResult;
import com.datadoghq.reggie.runtime.ReggieMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * GREEDY_SUFFIX through the annotation processor's generator: a subset of the shape grid against
 * JDK on every input up to length 4, for every API, every {@code from} and every bounded region.
 */
class GreedySuffixProcessorTest {

  private static final char[] ALPHABET = {'a', 'x', '1', ' ', '\n'};

  @Test
  void agreesWithJdk() throws Exception {
    List<String> inputs = new ArrayList<>();
    inputs.add("");
    for (int i = 0; i < inputs.size(); i++) {
      String s = inputs.get(i);
      if (s.length() < 4) {
        for (char c : ALPHABET) inputs.add(s + c);
      }
    }

    List<String> failures = new ArrayList<>();
    int id = 0;
    for (String g : new String[] {"", "(?s)"})
      for (String p : new String[] {"", "x"})
        for (String m : new String[] {"*", "+"})
          for (String wb : new String[] {"", "\\b"})
            for (String t : new String[] {"ab", "\\d", "(\\d+)", "(\\d{2,3})"})
              for (String end : new String[] {"", "$", "\\Z", "\\z"}) {
                String pattern = g + p + "(." + m + ")" + wb + t + end;
                String f = check(pattern, "G" + id++, inputs);
                if (f != null) failures.add("/" + pattern + "/ " + f);
              }
    assertEquals(List.of(), failures);
  }

  private static String check(String pattern, String className, List<String> inputs)
      throws Exception {
    ReggieMatcherBytecodeGenerator gen =
        new ReggieMatcherBytecodeGenerator("test.generated", className, pattern);
    if (gen.resolveRealization(false) != ReggieMatcherBytecodeGenerator.Realization.NATIVE
        || gen.resolvedStrategy() != MatchingStrategy.GREEDY_SUFFIX) {
      return "routes to " + gen.resolvedStrategy();
    }
    byte[] bytecode = gen.generate();
    ReggieMatcher r =
        (ReggieMatcher)
            new TestClassLoader()
                .defineClass("test.generated." + className, bytecode)
                .getDeclaredConstructor()
                .newInstance();
    Pattern jdk = Pattern.compile(pattern);
    for (String in : inputs) {
      String ctx = " on \"" + in.replace("\n", "\\n") + "\"";
      if (jdk.matcher(in).matches() != r.matches(in)) return "matches" + ctx;
      if (jdk.matcher(in).find() != r.find(in)) return "find" + ctx;
      Matcher m = jdk.matcher(in);
      String exp = m.matches() ? spans(m) : "null";
      if (!exp.equals(spans(r.match(in)))) return "match" + ctx;
      for (int s = 0; s <= in.length(); s++) {
        m = jdk.matcher(in);
        exp = m.find(s) ? spans(m) : "null";
        if (!exp.equals(spans(r.findMatchFrom(in, s)))) return "findMatchFrom(" + s + ")" + ctx;
        for (int e = s; e <= in.length(); e++) {
          m = jdk.matcher(in).region(s, e);
          exp = m.matches() ? spans(m) : "null";
          if (!exp.equals(spans(r.matchBounded(in, s, e)))) {
            return "matchBounded(" + s + "," + e + ")" + ctx;
          }
        }
      }
    }
    return null;
  }

  private static String spans(Matcher m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) sb.append(m.start(g)).append(',').append(m.end(g));
    return sb.toString();
  }

  private static String spans(MatchResult r) {
    if (r == null) return "null";
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= r.groupCount(); g++) sb.append(r.start(g)).append(',').append(r.end(g));
    return sb.toString();
  }

  private static class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return super.defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
