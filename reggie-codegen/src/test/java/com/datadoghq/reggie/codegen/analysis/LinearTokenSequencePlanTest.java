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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinearTokenSequencePlanTest {

  @Test
  void buildsPlanWithOriginalCaptureNumbers() throws Exception {
    LinearTokenSequencePlan plan = planFor("(?<host>\\S+) (?<status>[+-]?\\d+)");

    assertEquals(2, plan.groupCount());
    assertEquals(
        List.of(
            LinearTokenSequencePlan.OpKind.CAPTURE_NON_SPACE,
            LinearTokenSequencePlan.OpKind.LITERAL,
            LinearTokenSequencePlan.OpKind.CAPTURE_SIGNED_INTEGER),
        plan.ops().stream().map(LinearTokenSequencePlan.Op::kind).toList());
    assertEquals(1, plan.ops().get(0).groupNumber());
    assertEquals(2, plan.ops().get(2).groupNumber());
  }

  @Test
  void foldsQuotedDelimiterCaptureIntoSinglePlanOp() throws Exception {
    LinearTokenSequencePlan plan = planFor("prefix=\"(?<value>[^\"]*)\" suffix");

    assertEquals(
        List.of(
            LinearTokenSequencePlan.OpKind.LITERAL,
            LinearTokenSequencePlan.OpKind.CAPTURE_QUOTED_UNTIL_DELIMITER,
            LinearTokenSequencePlan.OpKind.LITERAL),
        plan.ops().stream().map(LinearTokenSequencePlan.Op::kind).toList());
    assertEquals("prefix=", plan.ops().get(0).literal());
    assertEquals(1, plan.ops().get(1).groupNumber());
    assertEquals('"', plan.ops().get(1).delimiter());
    assertEquals(" suffix", plan.ops().get(2).literal());
  }

  @Test
  void failsClosedForGeneralRegexCategories() throws Exception {
    PatternCategorization categorization = categorize("(?<word>\\w+)\\s+\\1");

    assertTrue(LinearTokenSequencePlan.from(categorization).isEmpty());
  }

  @Test
  void coverageIncludesCapturesNestedInOptionalSequences() throws Exception {
    LinearTokenSequencePlan plan = planFor("(?:|(?<value>\\S+))");

    assertEquals(0, plan.groupCount());
    assertTrue(plan.coversCaptureIndexes(List.of(1)));
    assertFalse(plan.coversCaptureIndexes(List.of(2)));
  }

  @Test
  void coverageRequiresEveryNamedCaptureNotOnlyTheLargestGroupNumber() throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse("(?<outer>(?:-|(?<inner>[+-]?\\d+)))");
    LinearTokenSequencePlan plan =
        LinearTokenSequencePlan.from(PatternCategorizer.categorize(ast)).orElseThrow();

    assertTrue(plan.coversCaptureIndexes(List.of(2)));
    assertFalse(plan.coversCaptureIndexes(parser.getGroupNameMap().values()));
  }

  @Test
  void coverageRejectsNonPositiveGroupIndexes() throws Exception {
    LinearTokenSequencePlan plan = planFor("(?<value>\\S+)");

    assertThrows(IllegalArgumentException.class, () -> plan.coversCaptureIndexes(List.of(0)));
  }

  private static LinearTokenSequencePlan planFor(String pattern) throws Exception {
    return LinearTokenSequencePlan.from(categorize(pattern)).orElseThrow();
  }

  private static PatternCategorization categorize(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return PatternCategorizer.categorize(ast);
  }
}
