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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.analysis.RecursiveDescentChoiceAnalysis;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * One-way patterns run on the recursive-descent generator; the backtracker is the oracle. Every
 * input over the alphabet up to length 6 must give the same matches, find bounds and group spans.
 */
class RecursiveDescentOneWayDifferentialTest {

  private static final int MAX_LENGTH = 6;

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "(\\()?blah(?(1)\\));;(blah)",
        "(a)?(?(1)b|c);;abcx",
        "(a+)?(?(1)b+|c+);;abcx",
        "(?|(abc)|(xyz));;abcxyz",
        "(?|(a)(b)(c)|(x)(y)(z));;abcxyz",
        "(?|(a+)|(b+));;abx",
        "(?|(a)(b)(c)|(x));;abcx",
        "(x{0,3}y)(?1);;xyz",
        "(?<n>a|b)(?&n);;abx",
        "(?|(a)|(?<n>b))(?&n);;abx",
        "(?:a|)c;;acx",
        "(?|(a)|)b;;abx",
        "(?:)(a)(?1);;ax",
        "(a|b)(?1);;abx",
        // Atomic and possessive: the oracle unwraps the group and makes the quantifier greedy
        "(?>(?|(a)|b))c;(?:(?|(a)|b))c;abcx",
        "(?|(a)|b)x++y;(?|(a)|b)x+y;abxy",
        // Calls to a group with no built instance: the oracle turns each {0} into a never-entered
        // (?:(?!)BODY)?
        "(?:(a)){0}(?1);(?:(?!)(a))?(?1);ax",
        "(?:((x)(?(2)b|c))){0}(?1);(?:(?!)((x)(?(2)b|c)))?(?1);xbc",
        "(?i:(a)){0}(?1);(?:(?!)(?i:(a)))?(?1);aAx",
        "(?:(a|b)){0}c(?1)+;(?:(?!)(a|b))?c(?1)+;abcx",
        "(?:(?:(a)){0}){0}(?1);(?:(?!)(?:(?!)(a))?)?(?1);ax",
        "(?:(a(?2))){0}(?:(b)){0}(?1);(?:(?!)(a(?2)))?(?:(?!)(b))?(?1);abx",
      })
  void agreesWithBacktracker(String pattern, String oracle, String alphabet) throws Exception {
    assertTrue(RecursiveDescentChoiceAnalysis.isOneWay(new RegexParser().parse(pattern)), pattern);
    ReggieMatcher rd = RuntimeCompiler.compile(pattern);
    assertFalse(EngineRouting.unwrap(rd) instanceof BackrefBacktrackMatcher, pattern);
    ReggieMatcher bt =
        RuntimeCompiler.compileBackrefBacktrack(oracle != null ? oracle : pattern, "");
    char[] buf = new char[MAX_LENGTH];
    for (int len = 0; len <= MAX_LENGTH; len++) {
      int[] digits = new int[len];
      while (true) {
        for (int i = 0; i < len; i++) {
          buf[i] = alphabet.charAt(digits[i]);
        }
        String input = new String(buf, 0, len);
        String where = pattern + " on \"" + input + "\"";
        assertEquals(bt.matches(input), rd.matches(input), where + " matches");
        assertEquals(bt.find(input), rd.find(input), where + " find");
        assertSameResult(bt.match(input), rd.match(input), where + " match");
        assertSameResult(bt.findMatch(input), rd.findMatch(input), where + " findMatch");
        int i = len - 1;
        while (i >= 0 && ++digits[i] == alphabet.length()) {
          digits[i--] = 0;
        }
        if (i < 0) {
          break;
        }
      }
    }
  }

  private static void assertSameResult(MatchResult expected, MatchResult actual, String where) {
    assertEquals(expected == null, actual == null, where);
    if (expected == null) {
      return;
    }
    assertEquals(expected.groupCount(), actual.groupCount(), where + " groupCount");
    for (int g = 0; g <= expected.groupCount(); g++) {
      assertEquals(expected.start(g), actual.start(g), where + " start(" + g + ")");
      assertEquals(expected.end(g), actual.end(g), where + " end(" + g + ")");
    }
  }
}
