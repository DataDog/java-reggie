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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class PatternCategorizerTest {

  @Test
  void categorizesLinearDelimitedLogTemplateWithoutGrokNames() throws Exception {
    String pattern =
        "(?<client>(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[A-Za-z0-9.-]+) "
            + "(?<ident>\\S+) "
            + "(?<auth>\\S+) "
            + "\\[(?<timestamp>[^\\]]+)\\]\\s+"
            + "\"(?<method>\\b\\w+\\b) (?<target>\\S+) HTTP/(?<version>\\d+\\.\\d+)\" "
            + "(?<status>[+-]?\\d+) "
            + "(?<bytes>[+-]?\\d+) "
            + "\"(?<referer>[^\"]*)\" "
            + "(?<duration>[+-]?\\d+(?:\\.\\d+)?)"
            + ".* \\[(?<logger>\\b\\w+\\b)\\] .*";

    PatternCategorization categorization = categorize(pattern);

    assertEquals(PatternCategorization.Category.LINEAR_TEMPLATE, categorization.category());
    assertTrue(categorization.notes().stream().noneMatch(note -> note.contains("grok")));

    List<PatternAtom.Kind> capturedKinds =
        categorization.atoms().stream()
            .filter(PatternAtom::isCaptured)
            .map(PatternAtom::kind)
            .toList();
    assertEquals(
        List.of(
            PatternAtom.Kind.COMPLEX_ALTERNATION,
            PatternAtom.Kind.NON_SPACE_PLUS,
            PatternAtom.Kind.NON_SPACE_PLUS,
            PatternAtom.Kind.UNTIL_DELIMITER,
            PatternAtom.Kind.WORD,
            PatternAtom.Kind.NON_SPACE_PLUS,
            PatternAtom.Kind.DECIMAL_NUMBER,
            PatternAtom.Kind.SIGNED_INTEGER,
            PatternAtom.Kind.SIGNED_INTEGER,
            PatternAtom.Kind.UNTIL_DELIMITER,
            PatternAtom.Kind.SIGNED_DECIMAL_NUMBER,
            PatternAtom.Kind.WORD),
        capturedKinds);

    assertTrue(
        categorization.atoms().stream()
            .anyMatch(
                atom ->
                    atom.kind() == PatternAtom.Kind.UNTIL_DELIMITER
                        && "timestamp".equals(atom.groupName())
                        && atom.delimiter() == ']'));
    assertTrue(
        categorization.atoms().stream()
            .anyMatch(
                atom ->
                    atom.kind() == PatternAtom.Kind.UNTIL_DELIMITER
                        && "referer".equals(atom.groupName())
                        && atom.delimiter() == '"'));
  }

  @Test
  void rejectsBacktrackingDependentShapes() throws Exception {
    PatternCategorization categorization = categorize("(?<word>\\w+)\\s+\\1");

    assertEquals(PatternCategorization.Category.GENERAL_REGEX, categorization.category());
    assertTrue(categorization.notes().stream().anyMatch(note -> note.contains("backreference")));
  }

  private static PatternCategorization categorize(String pattern) throws Exception {
    RegexNode ast = new RegexParser().parse(pattern);
    return PatternCategorizer.categorize(ast);
  }
}
