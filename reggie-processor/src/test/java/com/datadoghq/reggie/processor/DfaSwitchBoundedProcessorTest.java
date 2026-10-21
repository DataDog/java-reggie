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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * JDK-differential check of {@code matchesBounded}/{@code matchBounded} on annotation-processor
 * generated DFA_SWITCH* matchers on the whole input, prefix, suffix and inner regions (JDK {@code
 * region(start, end).matches()} with default anchoring and opaque bounds).
 */
class DfaSwitchBoundedProcessorTest {

  private static final String[] PATTERNS = {
    // DFA_SWITCH
    "x{1,25}y",
    "^[0-9a-f]{40}$",
    // DFA_SWITCH_WITH_GROUPS
    "(x{1,25})\\.(y{1,3})",
    // acceptance anchors on the accepting state
    "(x{1,25}\\n)^b?",
    "(x{1,25}\\n)\\Ab?",
    // END/STRING_END after a \r that lies before the region
    "\\Z[a\\n]{1,30}",
    "$[a\\n]{1,30}",
    "(?ms)^$.[a\\n]{1,30}",
    // DFA_SWITCH_WITH_ASSERTIONS: variable-width lookahead on the start state and on a later
    // state, fixed-width lookaround
    "(?=a{1,3}b)[ab]{1,25}c",
    "c(?=a{1,3}b)[ab]{1,25}",
    "(?<=a)x{1,25}",
    "(?<!a)x{1,25}",
    "(?=a)[ab]{1,25}",
    "(?!b)[ab]{1,25}"
  };

  private static final String[] INPUTS = {
    "",
    "xy",
    "xxxy",
    "xx",
    "x\nb",
    "xx\nb",
    "abc",
    "aabbc",
    "ac",
    "x",
    "xxx",
    "ax",
    "axx",
    "ab",
    "ba",
    "cab",
    "cbb",
    "xx.yy",
    "x\n\n",
    "a\r\n",
    "0123456789abcdef0123456789abcdef01234567"
  };

  @Test
  void boundedAgreesWithJdk() throws Exception {
    List<String> mismatches = new ArrayList<>();
    for (int i = 0; i < PATTERNS.length; i++) {
      ReggieMatcherBytecodeGenerator gen =
          new ReggieMatcherBytecodeGenerator("test.generated", "DsB" + i, PATTERNS[i]);
      byte[] bytecode = gen.generate();
      assertTrue(
          gen.resolvedStrategy().name().startsWith("DFA_SWITCH"),
          PATTERNS[i] + " routes to " + gen.resolvedStrategy());
      ReggieMatcher r =
          (ReggieMatcher)
              new TestClassLoader()
                  .defineClass("test.generated.DsB" + i, bytecode)
                  .getDeclaredConstructor()
                  .newInstance();
      Pattern j = Pattern.compile(PATTERNS[i]);
      for (String in : INPUTS) {
        // whole input, its prefix region [0, len-1), its suffix regions [k, len), then the same
        // text as the inner region [1, len-1) of "a" + in + "a"
        check(r, j, in, in, 0, in.length(), PATTERNS[i], mismatches);
        if (!in.isEmpty()) {
          check(r, j, in, in, 0, in.length() - 1, PATTERNS[i], mismatches);
        }
        for (int k = 1; k <= in.length(); k++) {
          check(r, j, in, in, k, in.length(), PATTERNS[i], mismatches);
        }
        String padded = "a" + in + "a";
        check(r, j, padded, in, 1, padded.length() - 1, PATTERNS[i], mismatches);
      }
    }
    assertEquals(List.of(), mismatches);
  }

  private static void check(
      ReggieMatcher r,
      Pattern j,
      String input,
      String shown,
      int start,
      int end,
      String pattern,
      List<String> mismatches) {
    Matcher m = j.matcher(input).region(start, end);
    String expected = m.matches() ? spans(m) : "-";
    String actual;
    try {
      MatchResult mr = r.matchBounded(input, start, end);
      actual = (mr == null ? "-" : spans(mr)) + "/" + r.matchesBounded(input, start, end);
    } catch (Throwable t) {
      actual = t.toString();
    }
    if (!actual.equals(expected + "/" + !expected.equals("-"))) {
      mismatches.add(
          "/"
              + pattern
              + "/ on \""
              + shown.replace("\n", "\\n")
              + "\" region ["
              + start
              + ","
              + end
              + "): jdk="
              + expected
              + " reggie="
              + actual);
    }
  }

  private static String spans(java.util.regex.MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append('[').append(m.start(g)).append(',').append(m.end(g)).append(')');
    }
    return sb.toString();
  }

  private static String spans(MatchResult m) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= m.groupCount(); g++) {
      sb.append('[').append(m.start(g)).append(',').append(m.end(g)).append(')');
    }
    return sb.toString();
  }

  /** Custom ClassLoader for loading generated bytecode in tests. */
  private static class TestClassLoader extends ClassLoader {
    Class<?> defineClass(String name, byte[] bytecode) {
      return defineClass(name, bytecode, 0, bytecode.length);
    }
  }
}
