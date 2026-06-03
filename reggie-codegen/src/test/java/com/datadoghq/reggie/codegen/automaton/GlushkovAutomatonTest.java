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
package com.datadoghq.reggie.codegen.automaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.ast.RegexNode;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/** Unit tests for the Glushkov (epsilon-free, position-based) automaton construction. */
public class GlushkovAutomatonTest {

  private static GlushkovAutomaton build(String pattern) {
    try {
      RegexNode ast = new RegexParser().parse(pattern);
      NFA nfa = new ThompsonBuilder().build(ast, 0);
      return GlushkovAutomaton.from(nfa);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Index of the (single) position whose char class contains {@code ch}; -1 if none. */
  private static int posOf(GlushkovAutomaton g, char ch) {
    int found = -1;
    for (int i = 0; i < g.positionCount; i++) {
      if (g.positionCharSets[i].contains(ch)) {
        assertEquals(-1, found, "expected a unique position matching '" + ch + "'");
        found = i;
      }
    }
    return found;
  }

  @Test
  void concat_ab() {
    GlushkovAutomaton g = build("ab");
    assertNotNull(g);
    assertEquals(2, g.positionCount);
    assertFalse(g.nullable);

    int a = posOf(g, 'a');
    int b = posOf(g, 'b');
    assertTrue(a >= 0 && b >= 0);

    // initial = {a}; accept = {b}; follow(a) = {b}; follow(b) = {}
    assertEquals(1L << a, g.initial);
    assertEquals(1L << b, g.accept);
    assertEquals(1L << b, g.follow[a]);
    assertEquals(0L, g.follow[b]);
    // reverse follow is the transpose: followReverse(b) = {a}
    assertEquals(1L << a, g.followReverse[b]);
    assertEquals(0L, g.followReverse[a]);
  }

  @Test
  void alternation_aOrB() {
    GlushkovAutomaton g = build("a|b");
    assertNotNull(g);
    assertEquals(2, g.positionCount);
    assertFalse(g.nullable);

    int a = posOf(g, 'a');
    int b = posOf(g, 'b');
    // both are initial and accepting; neither has a follower
    assertEquals((1L << a) | (1L << b), g.initial);
    assertEquals((1L << a) | (1L << b), g.accept);
    assertEquals(0L, g.follow[a]);
    assertEquals(0L, g.follow[b]);
  }

  @Test
  void star_isNullable_andSelfLoops() {
    GlushkovAutomaton g = build("a*");
    assertNotNull(g);
    assertEquals(1, g.positionCount);
    assertTrue(g.nullable);
    int a = posOf(g, 'a');
    assertEquals(1L << a, g.initial);
    assertEquals(1L << a, g.accept);
    assertEquals(1L << a, g.follow[a]); // a can follow a
  }

  @Test
  void plus_notNullable_butSelfLoops() {
    GlushkovAutomaton g = build("a+");
    assertNotNull(g);
    assertEquals(1, g.positionCount);
    assertFalse(g.nullable);
    int a = posOf(g, 'a');
    assertEquals(1L << a, g.follow[a]);
  }

  @Test
  void charClassEntryMasks() {
    GlushkovAutomaton g = build("[a-c]d");
    assertNotNull(g);
    int p0 = posOf(g, 'b'); // the [a-c] position
    int p1 = posOf(g, 'd');
    assertTrue(p0 >= 0 && p1 >= 0);
    // The entry mask for a char selects exactly the positions whose class contains it.
    assertEquals(1L << p0, entryMaskFor(g, 'a'));
    assertEquals(1L << p0, entryMaskFor(g, 'c'));
    assertEquals(1L << p1, entryMaskFor(g, 'd'));
    assertEquals(0L, entryMaskFor(g, 'z')); // no position matches 'z'
  }

  /** Mirrors the runtime char->class->entry-mask lookup, for testing the encoded tables. */
  private static long entryMaskFor(GlushkovAutomaton g, char ch) {
    int cls;
    if (ch < 128) {
      cls = g.asciiClasses[ch];
    } else {
      cls = 0;
      for (int i = 0; i < g.rangeStarts.length; i++) {
        if (ch >= g.rangeStarts[i] && ch <= g.rangeEnds[i]) {
          cls = g.rangeClasses[i];
          break;
        }
      }
    }
    return g.entry[cls];
  }

  @Test
  void ineligible_capturingGroup_returnsNull() {
    assertNull(build("(a)b"));
  }

  @Test
  void ineligible_anchor_returnsNull() {
    assertNull(build("^ab"));
  }

  @Test
  void positionBudget_63ok_64rejected() {
    assertNotNull(build("a{63}"));
    assertNull(build("a{64}"));
  }
}
