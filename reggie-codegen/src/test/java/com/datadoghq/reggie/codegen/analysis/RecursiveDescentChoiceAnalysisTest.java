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
package com.datadoghq.reggie.codegen.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.ConcatNode;
import com.datadoghq.reggie.codegen.ast.GroupNode;
import com.datadoghq.reggie.codegen.ast.LiteralNode;
import com.datadoghq.reggie.codegen.ast.SubroutineNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RecursiveDescentChoiceAnalysisTest {

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        // Choice points the RD generator would have to retry
        "(q)x(?:ab|a)b(?1);false",
        "(q)x(?:a|ab)(?:bc|b)c(?1);false",
        "(q)x(?:ab|a)+b(?1);false",
        "(q)x(?:ab?)+b(?1);false",
        "(q)x(?:a{1,2}){2}a(?1);false",
        "(q)x(?:aa|a){2}ab(?1);false",
        "(?|(a)|(ab));false",
        "(?|(a)|(ab))(?1);false",
        "(?|(a)|(b))(?:cd|c)d;false",
        "(?|(a)|(b))(?:cd|c)+d;false",
        "(?|(a)|(b))x(?:cd|c)+d;false",
        "(a)?(?(1)(?:bc|b)c|d);false",
        "(a)?(?(1)(?:bc|b)+c|d);false",
        "(a+)?(?(1)b+|c+)b;false",
        // Call cycles and root calls
        "^(a|b(?1)c)+$;false",
        "^(a(?1)?b|c)d$;false",
        "(a+)(?1);false",
        "a(?R)?b;false",
        "(a(?1)?b);false",
        // Backreferences
        "(q)x(?:ab|a)b\\1+;false",
        "(a|ab)\\1+c;false",
        "(a|b\\1)(?1);false",
        "^((.)(?1)\\2|.?)$;false",
        "^(a\\1?){4}$;false",
        // Conditionals
        "(\\()?blah(?(1)\\));true",
        "(a)?(?(1)b|c);true",
        "(a+)?(?(1)b+|c+);true",
        "(x)?(?(1)y);false",
        "(a)?(b)?(?(1)x)(?(2)y);false",
        // Branch reset
        "(?|(abc)|(xyz));true",
        "(?|(a)(b)(c)|(x)(y)(z));true",
        "(?|(a+)|(b+));true",
        "(?|(a)(b)(c)|(x));true",
        // Subroutines
        "(x{0,6000}y)(?1);true",
        "(?<n>a|b)(?&n);true",
        "(?:(a)){0}(?1);true",
        // Round 13: a call that reaches itself is rejected before C2 follows it
        "b((?1))*;false",
        "((?1))?+c;false",
        // Matches mode vs find mode
        "(?:|a);false",
        "(?:a|)a;false",
        "(?:a|)c;true",
        // Review round 1
        "(?:y(?:(x?)a)*)(?(1)b|c);false",
        "(?:(?|(a)|b))+;false",
        "(a\\1)(?(1)|);false",
        "(?i)(a)\\1(?(1)|);false",
        // Review round 11
        "(?1)c((?:(a)|b?)(?(2)y));false",
        "(?m)(?|(z)|w)?(?:x(?:\\n|b?)$)+;false",
        // Review round 12
        "(?:(a)|b?)(?(1)y);false",
        "(?|(a)|b)(?:(\\z)|);false",
        // Review round 13
        "(?|(a)|(?<n>b))(?&n);true",
        "(?|(a)|b)(?:(x\\z)|x);false",
      })
  void isOneWay(String pattern, boolean expected) throws Exception {
    assertEquals(
        expected,
        RecursiveDescentChoiceAnalysis.isOneWay(new RegexParser().parse(pattern)),
        pattern);
  }

  /** A call to a group missing from the AST is rejected; the parser refuses such patterns. */
  @Test
  void callToMissingGroupIsNotOneWay() {
    GroupNode group = new GroupNode(new LiteralNode('a'), 1, true);
    assertFalse(
        RecursiveDescentChoiceAnalysis.isOneWay(
            new ConcatNode(List.of(group, new SubroutineNode(2)))));
    assertFalse(
        RecursiveDescentChoiceAnalysis.isOneWay(
            new ConcatNode(List.of(group, new SubroutineNode("zz")))));
  }

  /** Each target calls the previous one twice: the analysis stays polynomial in the call depth. */
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void doublingCallChainIsFast() throws Exception {
    StringBuilder pattern = new StringBuilder("(a)");
    for (int k = 2; k <= 40; k++) {
      pattern.append("((?").append(k - 1).append(")(?").append(k - 1).append("))");
    }
    assertTrue(
        RecursiveDescentChoiceAnalysis.isOneWay(new RegexParser().parse(pattern.toString())));
  }

  /**
   * Each level adds a distinct optional char after the calls: the follows of the innermost target
   * differ per path, and the analysis still stays polynomial in the call depth.
   */
  @Test
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  void distinctFollowCallChainIsFast() throws Exception {
    StringBuilder pattern = new StringBuilder("(a)");
    for (int k = 2; k <= 30; k++) {
      char q = (char) (0x100 + 3 * k);
      char r = (char) (q + 1);
      char x = (char) (q + 2);
      pattern.append('(').append(q).append("(?").append(k - 1).append(")|");
      pattern.append(r).append("(?").append(k - 1).append(')').append(x).append("?)");
    }
    pattern.append('z');
    assertTrue(
        RecursiveDescentChoiceAnalysis.isOneWay(new RegexParser().parse(pattern.toString())));
  }

  /**
   * A wide alternation in group 1 under a call chain: each level's in-place position precedes the
   * call that feeds its context, so the context fixpoint takes one walk per level, and each walk
   * checks the alternation. The check is linear in the number of options.
   */
  @Test
  // Wide wall-clock budget: CI runners under parallel load measure 3-4x local latency; a real
  // algorithmic blowup here would take minutes, so 20s still catches the regression.
  @Timeout(value = 20, unit = TimeUnit.SECONDS)
  void wideAlternationUnderCallChainIsFast() throws Exception {
    StringBuilder pattern = new StringBuilder("((?:");
    for (int i = 0; i < 2000; i++) {
      pattern.append(i == 0 ? "" : "|").append((char) (0x1000 + i)).append("xy");
    }
    pattern.append("))");
    for (int k = 2; k <= 60; k++) {
      pattern.append("(q").append((char) (0x100 + k)).append("(?").append(k - 1).append("))");
    }
    pattern.append("(?60)z");
    assertTrue(
        RecursiveDescentChoiceAnalysis.isOneWay(new RegexParser().parse(pattern.toString())));
  }
}
