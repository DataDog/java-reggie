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
package com.datadoghq.reggie.processor.parsing;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.ast.*;
import com.datadoghq.reggie.codegen.parsing.*;
import org.junit.jupiter.api.Test;

class RegexParserTest {

  private final RegexParser parser = new RegexParser();

  @Test
  void testLiteral() throws Exception {
    RegexNode node = parser.parse("a");
    assertTrue(node instanceof LiteralNode);
    assertEquals('a', ((LiteralNode) node).ch);
  }

  @Test
  void testConcatenation() throws Exception {
    RegexNode node = parser.parse("abc");
    assertTrue(node instanceof ConcatNode);
    ConcatNode concat = (ConcatNode) node;
    assertEquals(3, concat.children.size());
  }

  @Test
  void testAlternation() throws Exception {
    RegexNode node = parser.parse("a|b|c");
    assertTrue(node instanceof AlternationNode);
    AlternationNode alt = (AlternationNode) node;
    assertEquals(3, alt.alternatives.size());
  }

  @Test
  void testQuantifiers() throws Exception {
    // *
    RegexNode star = parser.parse("a*");
    assertTrue(star instanceof QuantifierNode);
    QuantifierNode qStar = (QuantifierNode) star;
    assertEquals(0, qStar.min);
    assertEquals(-1, qStar.max);

    // +
    RegexNode plus = parser.parse("a+");
    assertTrue(plus instanceof QuantifierNode);
    QuantifierNode qPlus = (QuantifierNode) plus;
    assertEquals(1, qPlus.min);
    assertEquals(-1, qPlus.max);

    // ?
    RegexNode question = parser.parse("a?");
    assertTrue(question instanceof QuantifierNode);
    QuantifierNode qQuestion = (QuantifierNode) question;
    assertEquals(0, qQuestion.min);
    assertEquals(1, qQuestion.max);

    // {3}
    RegexNode exact = parser.parse("a{3}");
    assertTrue(exact instanceof QuantifierNode);
    QuantifierNode qExact = (QuantifierNode) exact;
    assertEquals(3, qExact.min);
    assertEquals(3, qExact.max);

    // {2,5}
    RegexNode range = parser.parse("a{2,5}");
    assertTrue(range instanceof QuantifierNode);
    QuantifierNode qRange = (QuantifierNode) range;
    assertEquals(2, qRange.min);
    assertEquals(5, qRange.max);

    // {3,}
    RegexNode openRange = parser.parse("a{3,}");
    assertTrue(openRange instanceof QuantifierNode);
    QuantifierNode qOpen = (QuantifierNode) openRange;
    assertEquals(3, qOpen.min);
    assertEquals(-1, qOpen.max);
  }

  @Test
  void testCharacterClass() throws Exception {
    RegexNode node = parser.parse("[a-z]");
    assertTrue(node instanceof CharClassNode);
    CharClassNode cc = (CharClassNode) node;
    assertFalse(cc.negated);
    assertTrue(cc.chars.contains('a'));
    assertTrue(cc.chars.contains('z'));
    assertFalse(cc.chars.contains('A'));
  }

  @Test
  void testNegatedCharClass() throws Exception {
    RegexNode node = parser.parse("[^0-9]");
    assertTrue(node instanceof CharClassNode);
    CharClassNode cc = (CharClassNode) node;
    assertTrue(cc.negated);
  }

  @Test
  void testEscapeSequences() throws Exception {
    // \d
    RegexNode digit = parser.parse("\\d");
    assertTrue(digit instanceof CharClassNode);

    // \w
    RegexNode word = parser.parse("\\w");
    assertTrue(word instanceof CharClassNode);

    // \s
    RegexNode space = parser.parse("\\s");
    assertTrue(space instanceof CharClassNode);

    // Escaped literal
    RegexNode dot = parser.parse("\\.");
    assertTrue(dot instanceof LiteralNode);
    assertEquals('.', ((LiteralNode) dot).ch);
  }

  @Test
  void testGroups() throws Exception {
    RegexNode node = parser.parse("(abc)");
    assertTrue(node instanceof GroupNode);
    GroupNode group = (GroupNode) node;
    assertTrue(group.capturing);
    assertEquals(1, group.groupNumber);
  }

  @Test
  void testNonCapturingGroup() throws Exception {
    RegexNode node = parser.parse("(?:abc)");
    assertTrue(node instanceof GroupNode);
    GroupNode group = (GroupNode) node;
    assertFalse(group.capturing);
  }

  @Test
  void testAnchors() throws Exception {
    // ^
    RegexNode start = parser.parse("^");
    assertTrue(start instanceof AnchorNode);
    assertEquals(AnchorNode.Type.START, ((AnchorNode) start).type);

    // $
    RegexNode end = parser.parse("$");
    assertTrue(end instanceof AnchorNode);
    assertEquals(AnchorNode.Type.END, ((AnchorNode) end).type);

    // \b
    RegexNode boundary = parser.parse("\\b");
    assertTrue(boundary instanceof AnchorNode);
    assertEquals(AnchorNode.Type.WORD_BOUNDARY, ((AnchorNode) boundary).type);
  }

  @Test
  void testBackreference() throws Exception {
    RegexNode node = parser.parse("(a)\\1");
    assertTrue(node instanceof ConcatNode);
    ConcatNode concat = (ConcatNode) node;
    assertEquals(2, concat.children.size());
    assertTrue(concat.children.get(1) instanceof BackreferenceNode);
    assertEquals(1, ((BackreferenceNode) concat.children.get(1)).groupNumber);
  }

  @Test
  void testComplexPattern() throws Exception {
    // Phone pattern
    RegexNode phone = parser.parse("\\d{3}-\\d{3}-\\d{4}");
    assertNotNull(phone);

    // Email-like pattern
    RegexNode email = parser.parse("[a-z]+@[a-z]+\\.[a-z]+");
    assertNotNull(email);

    // Mixed pattern
    RegexNode mixed = parser.parse("^(https?://)?[a-z]+(\\.[a-z]+)*$");
    assertNotNull(mixed);
  }

  @Test
  void testInvalidPattern() {
    assertThrows(Exception.class, () -> parser.parse("(unclosed"));
    assertThrows(Exception.class, () -> parser.parse("*invalid"));
  }

  @Test
  void testDot() throws Exception {
    RegexNode dot = parser.parse(".");
    assertTrue(dot instanceof CharClassNode);
    CharClassNode cc = (CharClassNode) dot;
    assertFalse(cc.negated);
    // . should match any character
    assertTrue(cc.chars.contains('a'));
    assertTrue(cc.chars.contains('0'));
    assertTrue(cc.chars.contains(' '));
  }
}
