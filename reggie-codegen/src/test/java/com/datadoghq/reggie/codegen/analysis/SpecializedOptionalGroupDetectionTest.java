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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@code PatternAnalyzer#detectSpecializedOptionalGroup}, invoked via reflection.
 */
class SpecializedOptionalGroupDetectionTest {

  private SpecializedOptionalGroupInfo detect(String pattern) throws Exception {
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
        PatternAnalyzer.class.getDeclaredMethod("detectSpecializedOptionalGroup", RegexNode.class);
    method.setAccessible(true);
    return (SpecializedOptionalGroupInfo) method.invoke(analyzer, ast);
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
  void detectsBasicShape() throws Exception {
    SpecializedOptionalGroupInfo info = detect("(a)?b");
    assertNotNull(info);
    assertEquals(1, info.groupNumber);
    assertEquals('a', info.groupChar);
    assertEquals('b', info.suffixChar);
    assertFalse(info.hasStartAnchor);
    assertFalse(info.hasEndAnchor);
  }

  @Test
  void toStringIncludesKeyFields() throws Exception {
    SpecializedOptionalGroupInfo info = detect("^(a)?b$");
    String s = info.toString();
    assertTrue(s.contains("groupNumber=1"), s);
    assertTrue(s.contains("groupChar='a'"), s);
    assertTrue(s.contains("suffixChar='b'"), s);
    assertTrue(s.contains("hasStartAnchor=true"), s);
    assertTrue(s.contains("hasEndAnchor=true"), s);
  }

  @Test
  void detectsStartAnchor() throws Exception {
    SpecializedOptionalGroupInfo info = detect("^(a)?b");
    assertNotNull(info);
    assertTrue(info.hasStartAnchor);
    assertFalse(info.hasEndAnchor);
  }

  @Test
  void detectsEndAnchor() throws Exception {
    SpecializedOptionalGroupInfo info = detect("(a)?b$");
    assertNotNull(info);
    assertFalse(info.hasStartAnchor);
    assertTrue(info.hasEndAnchor);
  }

  @Test
  void detectsBothAnchors() throws Exception {
    SpecializedOptionalGroupInfo info = detect("^(a)?b$");
    assertNotNull(info);
    assertTrue(info.hasStartAnchor);
    assertTrue(info.hasEndAnchor);
  }

  @Test
  void detectsStringStartAndAbsoluteEndAnchors() throws Exception {
    SpecializedOptionalGroupInfo info = detect("\\A(a)?b\\z");
    assertNotNull(info);
    assertTrue(info.hasStartAnchor);
    assertTrue(info.hasEndAnchor);
  }

  @Test
  void detectsNamedGroup() throws Exception {
    SpecializedOptionalGroupInfo info = detect("(?<x>a)?b");
    assertNotNull(info);
    assertEquals(1, info.groupNumber);
    assertEquals('a', info.groupChar);
    assertEquals('b', info.suffixChar);
  }

  @Test
  void rejectsPrefix() throws Exception {
    // No-prefix enforcement (v1 scope): any AST content before the optional group other than a
    // start anchor is out of scope.
    assertNull(detect("x(a)?b"));
  }

  @Test
  void rejectsMultipleOptionalGroups() throws Exception {
    assertNull(detect("(a)?(b)?c"));
  }

  @Test
  void rejectsLiteralStringGroupContent() throws Exception {
    assertNull(detect("(ab)?c"));
  }

  @Test
  void rejectsCharClassGroupContent() throws Exception {
    assertNull(detect("([a-z])?b"));
  }

  @Test
  void rejectsNonOptionalQuantifier() throws Exception {
    assertNull(detect("(a)+b"));
    assertNull(detect("(a)*b"));
    assertNull(detect("(a){0,2}b"));
  }

  @Test
  void rejectsNonCapturingGroup() throws Exception {
    assertNull(detect("(?:a)?b"));
  }

  @Test
  void rejectsBackreference() throws Exception {
    assertNull(detect("(a)?\\1"));
  }

  @Test
  void rejectsMissingSuffix() throws Exception {
    assertNull(detect("(a)?"));
  }

  @Test
  void rejectsSuffixString() throws Exception {
    assertNull(detect("(a)?bc"));
  }

  @Test
  void rejectsCaseInsensitiveLetterLiteral() throws Exception {
    // Rejected for two independent reasons, both visible in RegexParser: the global (?i) itself
    // parses into an epsilon LiteralNode('\0') inserted as a ConcatNode child (breaking the
    // detector's fixed child-position shape), and every letter literal downstream of it (both
    // the group's 'a' and the suffix 'b') parses into a CharClassNode rather than a LiteralNode
    // (breaking the detector's LiteralNode-only content checks) - see the detector's javadoc.
    assertNull(detect("(?i)(a)?b"));
  }

  @Test
  void rejectsStringEndNotAbsolute() throws Exception {
    // \Z (STRING_END: end of string OR before a final line terminator) is deliberately not
    // accepted as an end anchor here — only $ (END) and \z (STRING_END_ABSOLUTE) are, since \Z's
    // "before a final newline" semantics differ from a hard end-of-string check. The trailing
    // AnchorNode is then left unconsumed, so the detector's final idx==n check rejects the whole
    // pattern (confirmed against JDK: "(a)?b\Z" matches "ab" but not "ab\n" — a hard end check
    // would wrongly match neither the same way).
    assertNull(detect("(a)?b\\Z"));
  }
}
