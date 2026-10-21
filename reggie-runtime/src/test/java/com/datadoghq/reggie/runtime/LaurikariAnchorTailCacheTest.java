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
package com.datadoghq.reggie.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@link LaurikariDFACache} memoizes anchor-dependent transitions under a lookahead class that
 * encodes the distance to {@code regionEnd} near the end of the input. One matcher per pattern is
 * shared by every input, so a transition cached for one input's tail is replayed on all the others;
 * every result must still agree with {@link PikeVMMatcher} and the JDK.
 */
class LaurikariAnchorTailCacheTest {

  private static final String[] PATTERNS = {
    "\\d+$",
    "\\d+\\Z",
    "\\d+\\z",
    "(?m)\\d+$",
    "\\w+\\b",
    "a\\b",
    "(\\d+)$",
    "(\\d)\\d*\\Z",
    "[0-9a]{2,5}[0-9]{1,3}\\Z",
  };

  /** Characters that select each lookahead class, including every line terminator. */
  private static final char[] ALPHABET = {'1', 'a', ' ', '.', '\n', '\r', '\u0085', '\u2028'};

  private static List<String> inputs() {
    List<String> tails = new ArrayList<>();
    tails.add("");
    for (int len = 1; len <= 4; len++) {
      int n = (int) Math.pow(ALPHABET.length, len);
      for (int k = 0; k < n; k++) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0, v = k; j < len; j++, v /= ALPHABET.length) {
          sb.append(ALPHABET[v % ALPHABET.length]);
        }
        tails.add(sb.toString());
      }
    }
    List<String> inputs = new ArrayList<>();
    for (String prefix : new String[] {"", "1", "a1", "x 12"}) {
      for (String tail : tails) {
        inputs.add(prefix + tail);
      }
    }
    Collections.shuffle(inputs, new Random(42));
    return inputs;
  }

  @Test
  void sharedMatcherAgreesWithOraclesOnEveryTail() throws Exception {
    List<String> inputs = inputs();
    List<String> wrong = new ArrayList<>();
    for (String pattern : PATTERNS) {
      int groupCount = Pattern.compile(pattern).matcher("").groupCount();
      NFA nfa = new ThompsonBuilder().build(new RegexParser().parse(pattern), groupCount);
      LaurikariDfaMatcher laurikari = new LaurikariDfaMatcher(nfa, pattern, groupCount);
      PikeVMMatcher oracle = new PikeVMMatcher(nfa, pattern);
      Pattern jdk = Pattern.compile(pattern);
      for (String in : inputs) {
        String ctx = pattern + " on \"" + escape(in) + "\"";
        Matcher m = jdk.matcher(in);
        if (laurikari.matches(in) != m.matches()) wrong.add(ctx + ": matches");
        m.reset();
        String expected = m.find() ? spans(m, groupCount) : "none";
        String actual = spans(laurikari.findMatch(in), groupCount);
        if (!expected.equals(actual)) wrong.add(ctx + ": find " + actual + " vs JDK " + expected);
        for (int start = 1; start <= in.length(); start++) {
          if (laurikari.findFrom(in, start) != oracle.findFrom(in, start)) {
            wrong.add(ctx + ": findFrom(" + start + ")");
          }
        }
        if (wrong.size() > 20) break;
      }
      assertEquals(0, laurikari.fallbackCount(), pattern);
    }
    assertEquals(List.of(), wrong);
  }

  private static String spans(Matcher m, int groupCount) {
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= groupCount; g++) {
      sb.append('[').append(m.start(g)).append(',').append(m.end(g)).append(']');
    }
    return sb.toString();
  }

  private static String spans(MatchResult r, int groupCount) {
    if (r == null) return "none";
    StringBuilder sb = new StringBuilder();
    for (int g = 0; g <= groupCount; g++) {
      sb.append('[').append(r.start(g)).append(',').append(r.end(g)).append(']');
    }
    return sb.toString();
  }

  private static String escape(String s) {
    return s.replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\u0085", "\\u0085")
        .replace("\u2028", "\\u2028");
  }
}
