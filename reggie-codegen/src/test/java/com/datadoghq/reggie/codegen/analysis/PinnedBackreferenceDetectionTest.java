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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/** Unit tests for {@code PatternAnalyzer#detectPinnedBackreference}, invoked via reflection. */
class PinnedBackreferenceDetectionTest {

  private PinnedBackreferenceInfo detect(String pattern) throws Exception {
    RegexParser parser = new RegexParser();
    RegexNode ast = parser.parse(pattern);
    ThompsonBuilder builder = new ThompsonBuilder();
    PatternAnalyzer analyzer;
    try {
      NFA nfa = builder.build(ast, countGroups(pattern));
      analyzer = new PatternAnalyzer(ast, nfa);
    } catch (UnsupportedOperationException e) {
      analyzer = new PatternAnalyzer(ast, null);
    }

    Method method =
        PatternAnalyzer.class.getDeclaredMethod("detectPinnedBackreference", RegexNode.class);
    method.setAccessible(true);
    return (PinnedBackreferenceInfo) method.invoke(analyzer, ast);
  }

  private int countGroups(String pattern) {
    int count = 0;
    boolean inEscape = false;
    for (int i = 0; i < pattern.length(); i++) {
      char ch = pattern.charAt(i);
      if (inEscape) {
        inEscape = false;
        continue;
      }
      if (ch == '\\') {
        inEscape = true;
      } else if (ch == '(' && i + 1 < pattern.length()) {
        if (i + 2 < pattern.length()
            && pattern.charAt(i + 1) == '?'
            && pattern.charAt(i + 2) == ':') {
          continue;
        }
        count++;
      }
    }
    return count;
  }

  @Test
  void rejectsHtmlTagCloseShapeWithMultiNodeSeparator() throws Exception {
    // The separator between the group's close and the backreference (">", ".*", "</") spans
    // three AST nodes; the single-pass codegen can only soundly scan a homogeneous one-node
    // separator (e.g. \s+), so this correctly stays on SPECIALIZED_BACKREFERENCE instead.
    assertNull(detect("<(\\w+)>.*</\\1>"));
  }

  @Test
  void detectsRepeatedWordShape() throws Exception {
    assertNotNull(detect("(\\w+)\\s+\\1"));
  }

  @Test
  void rejectsPatternWithAnchorsOutsideGroupAndBackrefSpan() throws Exception {
    // The generated matcher only scans the group, separator, and backreference echo - it has
    // no code path for the \b anchors here, so the whole pattern must be rejected rather than
    // silently ignoring them.
    assertNull(detect("\\b(\\w+)\\s+\\1\\b"));
  }

  @Test
  void rejectsOverlappingGroupAndSuffixCharsets() throws Exception {
    assertNull(detect("([bc]*)(c+d)"));
  }

  @Test
  void rejectsAmbiguousQuotedStringWithEscape() throws Exception {
    assertNull(detect("([\"'])(?:\\\\\\1|.)*?\\1"));
  }

  @Test
  void rejectsPatternWithPrefixEvenWhenBackrefIsLast() throws Exception {
    // Exercises the groupIndex/backrefIndex span check with only one side violated: a prefix
    // before the group, but the backreference is still the last child. Both conditions must
    // independently be able to trigger the rejection, not just their conjunction.
    assertNull(detect("x(\\w+)\\s+\\1"));
  }

  @Test
  void rejectsZeroMinLengthSeparator() throws Exception {
    // A separator quantifier with min == 0 (e.g. \s*) can match empty, making the group's
    // closing boundary ambiguous - the scan can't tell where the group ends and the separator
    // begins, so this must be rejected rather than accepted with a bogus zero-length separator.
    assertNull(detect("(\\w+)\\s*\\1"));
  }

  @Test
  void detectsSeparatorWrappedInNonCapturingGroup() throws Exception {
    // (?:\s+) wraps the separator's quantifier without changing what's matched, so it must be
    // unwrapped to read the real bounds instead of being treated as a single fixed occurrence.
    PinnedBackreferenceInfo info = detect("(\\w+)(?:\\s+)\\1");
    assertNotNull(info);
  }

  @Test
  void rejectsLazySeparatorQuantifier() throws Exception {
    // The codegen's separator scan always consumes the longest available run; a lazy quantifier
    // (\s+?) has the opposite semantics, so it must be rejected rather than approximated.
    assertNull(detect("(\\w+)\\s+?\\1"));
  }

  @Test
  void rejectsNestedCapturingGroupInSeparator() throws Exception {
    // A capturing group nested inside the separator (even wrapped in a non-capturing group)
    // would be silently unaccounted for by totalGroupCount(), so it must be rejected.
    assertNull(detect("(\\w+)(?:(x)+)\\1"));
  }

  @Test
  void rejectsNestedCapturingGroupInPinnedGroupBody() throws Exception {
    // A capturing group nested inside the pinned group's quantified body would likewise be
    // silently unaccounted for by totalGroupCount(), so it must be rejected.
    assertNull(detect("((a)+)\\s+\\1"));
  }
}
