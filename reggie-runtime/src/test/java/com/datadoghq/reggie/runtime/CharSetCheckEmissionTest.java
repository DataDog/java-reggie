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

import com.datadoghq.reggie.Reggie;
import com.datadoghq.reggie.UnsupportedPatternException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Exhaustive BMP check of the multi-range char-set test emitted by {@code
 * BytecodeUtil.emitCharSetCheck}. {@link #agreesWithJdkOnEveryBmpChar} covers many classes through
 * DFA_UNROLLED and DETERMINISTIC_CHAIN_BYTECODE; {@link #everyRouteAgreesWithJdkOnEveryBmpChar}
 * covers {@code [a-cx-z]} and {@code [^a-cx-z]} through the other generators that emit the check.
 */
public class CharSetCheckEmissionTest {

  /**
   * Classes with 2 to 8 ranges, including ranges touching U+0000 and U+FFFF, and classes whose
   * printable-ASCII range starts at U+0000 ({@code [\x00-\x7E...]}), ends at U+FFFF ({@code [^\n]})
   * or touches neither ({@code .}).
   */
  private static final String[] CLASSES = {
    ".",
    "[^\\n]",
    "[^\\n\\r]",
    "\\w",
    "[a-cx-z]",
    "[\\x00-\\x05a\\uFFF0-\\uFFFF]",
    "[0-9A-F_a-f\\u00C0-\\u00FF\\u2028]",
    "[^a-cg-ik-mq-sx-z]",
    "[\\x01\\x03\\x05\\x07\\x09\\x0B\\x0D\\x0F]",
    "[\\x00-\\x7E\\u0100\\u2028-\\u2029]"
  };

  /**
   * {pattern, expected route, input template}. Each {@code #} in the template is replaced by the
   * BMP char under test.
   */
  private static final String[][] ROUTED = {
    {"([^a-cx-z]+)", "SPECIALIZED_GREEDY_CHARCLASS", "#"},
    {"([a-cx-z]+)", "SPECIALIZED_GREEDY_CHARCLASS", "#"},
    {"[a-cx-z]{1,3}-[a-cx-z]{1,3}", "SPECIALIZED_BOUNDED_QUANTIFIERS", "#-#"},
    {"[^a-cx-z]{2}-[^a-cx-z]{2}", "SPECIALIZED_FIXED_SEQUENCE", "##-##"},
    {"[a-cx-z]{2}-[a-cx-z]{2}", "SPECIALIZED_FIXED_SEQUENCE", "##-##"},
    {"x([0-9a-f]*)z", "SPECIALIZED_CONCAT_GREEDY_GROUP", "x#z"},
    {"(?:[^a-cx-z]|ab|cd|ef|gh|ij|kl|mn|op|qr)+z", "DFA_SWITCH", "#z"},
    {"(?:[a-cx-z]|ab|cd|ef|gh|ij|kl|mn|op|qr)+z", "DFA_SWITCH", "#z"},
    {"[^a-cx-z]{1,3}\\.[^a-cx-z]{1,3}\\.[^a-cx-z]{1,3}", "DFA_SWITCH", "#.#.#"},
    {"([^a-cx-z]+)?x", "BITSTATE_CAPTURE", "#x"},
    {"[a-cx-z]??x", "BITSTATE_CAPTURE", "#x"},
    {"[a-cx-z]+?x", "DETERMINISTIC_CHAIN_BYTECODE", "#x"},
    {"(?<=x)[^a-cx-z]+", "DFA_UNROLLED_WITH_ASSERTIONS", "x#"},
    {"(?=[^a-cx-z]+@).*@[^a-cx-z]+", "DFA_UNROLLED_WITH_ASSERTIONS", "#@#"},
    {"(?:([^a-cx-z])x)+", "DFA_UNROLLED_WITH_GROUPS", "#x"},
    {"([a-cx-z])\\1", "LINEAR_BACKREFERENCE", "##"},
    {"([^a-cx-z])([^a-cx-z])", "ONEPASS_NFA", "##"},
    {"([a-cx-z]+):\\1", "PINNED_BACKREFERENCE", "#:#"},
    {"([^a-cx-z]+)-([^a-cx-z]+)", "RECURSIVE_DESCENT", "#-#"},
    {"foo([a-cx-z]+)baz", "RECURSIVE_DESCENT", "foo#baz"},
    {"([a-cx-z]+)-([a-cx-z]+)", "SPECIALIZED_MULTI_GROUP_GREEDY", "#-#"},
    {"foo([^a-cx-z]+)baz", "SPECIALIZED_MULTI_GROUP_GREEDY", "foo#baz"},
    {"([^a-cx-z])+", "SPECIALIZED_QUANTIFIED_GROUP", "#"},
    {"[^a-cx-z]+$", "SPECIALIZED_SUFFIX_SEQUENCE", "#"},
    {"[^a-cx-z]{2,4}", "STATELESS_LOOP", "##"},
    {"([a-cx-z]+)=([a-cx-z]+);\\2", "OPTIMIZED_NFA_WITH_BACKREFS", "#=#;#"},
    {"([^a-cx-z])\\1", "LINEAR_BACKREFERENCE", "##"},
    {"([^a-cx-z]+):\\1", "VARIABLE_CAPTURE_BACKREF", "#:#"},
    {"([a-cx-z]*)x\\1", "VARIABLE_CAPTURE_BACKREF", "#x#"},
    {"([a-cx-z]*)[^a-cx-z]+\\1", "VARIABLE_CAPTURE_BACKREF", "a#a"},
    {"([a-cx-z]*)[^a-cx-z]\\1", "VARIABLE_CAPTURE_BACKREF", "a#a"},
    {"x([^a-cx-z]*)z", "SPECIALIZED_CONCAT_GREEDY_GROUP", "x#z"},
    {"([^a-cx-z])\\1{8,}", "FIXED_REPETITION_BACKREF", "#########"},
    {"([^a-cx-z]+)+([a-c]+)+", "BITSTATE_CAPTURE", "#a"},
    {"([^a-cx-z])+([a-c])+", "SPECIALIZED_QUANTIFIED_GROUP", "#a"},
    {"(([^a-cx-z])+)?", "BITSTATE_CAPTURE", "#"},
    {"([^\\n\\r\\u0085\\u2028\\u2029]+)x", "GREEDY_SUFFIX", "#x"},
    {"[a-cx-z](.*)x", "GREEDY_SUFFIX", "#x"},
    {"(.*)([^a-cx-z]+)", "GREEDY_SUFFIX", "#"}
  };

  @Test
  void everyRouteAgreesWithJdkOnEveryBmpChar() throws Exception {
    List<String> mismatches = new ArrayList<>();
    for (String[] c : ROUTED) {
      String pattern = c[0];
      String actual = StrategyCorrectnessMetaTest.routeOf(pattern).name();
      if (!actual.equals(c[1])) {
        mismatches.add(pattern + " -> " + actual + " (expected " + c[1] + ")");
        continue;
      }
      ReggieMatcher r;
      try {
        r = Reggie.compile(pattern);
      } catch (UnsupportedPatternException e) {
        mismatches.add(pattern + " refused: " + e.getMessage());
        continue;
      }
      Pattern j = Pattern.compile(pattern);
      for (int ch = 0; ch <= 0xFFFF; ch++) {
        String in = c[2].replace('#', (char) ch);
        Matcher jm = j.matcher(in);
        boolean jFound = jm.find();
        MatchResult rm = r.findMatch(in);
        if (r.matches(in) != j.matcher(in).matches()
            || r.find(in) != jFound
            || (rm != null) != jFound
            || (jFound && (rm.start() != jm.start() || rm.end() != jm.end()))) {
          mismatches.add(pattern + " on U+" + Integer.toHexString(ch));
          break;
        }
      }
    }
    assertEquals(List.of(), mismatches);
  }

  @Test
  void agreesWithJdkOnEveryBmpChar() throws Exception {
    List<String> mismatches = new ArrayList<>();
    for (String cls : CLASSES) {
      check(cls, "DFA_UNROLLED", mismatches);
      check("^(" + cls + "*)$", "DETERMINISTIC_CHAIN_BYTECODE", mismatches);
    }
    assertEquals(List.of(), mismatches);
  }

  private static void check(String pattern, String route, List<String> mismatches)
      throws Exception {
    String actual = StrategyCorrectnessMetaTest.routeOf(pattern).name();
    if (!actual.equals(route)) {
      mismatches.add(pattern + " -> " + actual + " (expected " + route + ")");
      return;
    }
    ReggieMatcher r = Reggie.compile(pattern);
    Pattern j = Pattern.compile(pattern);
    for (int c = 0; c <= 0xFFFF; c++) {
      String in = String.valueOf((char) c);
      if (r.matches(in) != j.matcher(in).matches()) {
        mismatches.add(pattern + " on U+" + Integer.toHexString(c));
        return;
      }
    }
  }
}
