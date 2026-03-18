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
package com.datadoghq.reggie.processor.automaton;

import static org.junit.jupiter.api.Assertions.*;

import com.datadoghq.reggie.codegen.automaton.*;
import org.junit.jupiter.api.Test;

class CharSetTest {

  @Test
  void testSingleChar() {
    CharSet cs = CharSet.of('a');
    assertTrue(cs.contains('a'));
    assertFalse(cs.contains('b'));
    assertTrue(cs.isSingleChar());
    assertEquals('a', cs.getSingleChar());
  }

  @Test
  void testRange() {
    CharSet cs = CharSet.range('a', 'z');
    assertTrue(cs.contains('a'));
    assertTrue(cs.contains('m'));
    assertTrue(cs.contains('z'));
    assertFalse(cs.contains('A'));
    assertFalse(cs.contains('0'));
    assertTrue(cs.isSimpleRange());
    assertEquals('a', cs.rangeStart());
    assertEquals('z', cs.rangeEnd());
  }

  @Test
  void testUnion() {
    CharSet lower = CharSet.range('a', 'z');
    CharSet upper = CharSet.range('A', 'Z');
    CharSet alpha = lower.union(upper);

    assertTrue(alpha.contains('a'));
    assertTrue(alpha.contains('z'));
    assertTrue(alpha.contains('A'));
    assertTrue(alpha.contains('Z'));
    assertFalse(alpha.contains('0'));
  }

  @Test
  void testIntersection() {
    CharSet az = CharSet.range('a', 'z');
    CharSet em = CharSet.range('e', 'm');
    CharSet result = az.intersection(em);

    assertFalse(result.contains('a'));
    assertFalse(result.contains('d'));
    assertTrue(result.contains('e'));
    assertTrue(result.contains('m'));
    assertFalse(result.contains('n'));
    assertFalse(result.contains('z'));
  }

  @Test
  void testComplement() {
    CharSet digit = CharSet.DIGIT;
    CharSet notDigit = digit.complement();

    assertFalse(notDigit.contains('0'));
    assertFalse(notDigit.contains('5'));
    assertFalse(notDigit.contains('9'));
    assertTrue(notDigit.contains('a'));
    assertTrue(notDigit.contains('Z'));
    assertTrue(notDigit.contains(' '));
  }

  @Test
  void testMinus() {
    CharSet az = CharSet.range('a', 'z');
    CharSet em = CharSet.range('e', 'm');
    CharSet result = az.minus(em);

    assertTrue(result.contains('a'));
    assertTrue(result.contains('d'));
    assertFalse(result.contains('e'));
    assertFalse(result.contains('m'));
    assertTrue(result.contains('n'));
    assertTrue(result.contains('z'));
  }

  @Test
  void testPredefinedSets() {
    assertTrue(CharSet.DIGIT.contains('0'));
    assertTrue(CharSet.DIGIT.contains('9'));
    assertFalse(CharSet.DIGIT.contains('a'));

    assertTrue(CharSet.WORD.contains('a'));
    assertTrue(CharSet.WORD.contains('Z'));
    assertTrue(CharSet.WORD.contains('_'));
    assertTrue(CharSet.WORD.contains('5'));
    assertFalse(CharSet.WORD.contains(' '));

    assertTrue(CharSet.WHITESPACE.contains(' '));
    assertTrue(CharSet.WHITESPACE.contains('\t'));
    assertTrue(CharSet.WHITESPACE.contains('\n'));
    assertFalse(CharSet.WHITESPACE.contains('a'));
  }

  @Test
  void testEmpty() {
    CharSet empty = CharSet.empty();
    assertTrue(empty.isEmpty());
    assertFalse(empty.contains('a'));
  }

  @Test
  void testMergeOverlappingRanges() {
    CharSet.Range r1 = new CharSet.Range('a', 'e');
    CharSet.Range r2 = new CharSet.Range('c', 'g');
    CharSet cs = CharSet.fromRanges(java.util.List.of(r1, r2));

    // Should merge to single range a-g
    assertTrue(cs.isSimpleRange());
    assertTrue(cs.contains('a'));
    assertTrue(cs.contains('c'));
    assertTrue(cs.contains('g'));
    assertFalse(cs.contains('h'));
  }

  @Test
  void testDisjointRanges() {
    CharSet.Range r1 = new CharSet.Range('a', 'c');
    CharSet.Range r2 = new CharSet.Range('x', 'z');
    CharSet cs = CharSet.fromRanges(java.util.List.of(r1, r2));

    assertFalse(cs.isSimpleRange());
    assertTrue(cs.contains('a'));
    assertTrue(cs.contains('c'));
    assertFalse(cs.contains('m'));
    assertTrue(cs.contains('x'));
    assertTrue(cs.contains('z'));
  }
}
