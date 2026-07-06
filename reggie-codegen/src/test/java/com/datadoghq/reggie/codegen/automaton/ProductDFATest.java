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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hand-built DFAs, manually-computed expected product transitions. Verifies {@link ProductDFA}
 * against the design in doc/2026-07-06-fused-multi-lookahead-generalization-impl-plan.md Task 3.1.
 */
class ProductDFATest {

  // DFA A: a0 --[a-m]--> a1(accept, sink)
  private static DFA singleRangeDfa(char start, char end) {
    DFA.DFAState s0 = new DFA.DFAState(0, Collections.emptySet(), false);
    DFA.DFAState s1 = new DFA.DFAState(1, Collections.emptySet(), true);
    s0.addTransition(CharSet.range(start, end), s1);
    return new DFA(s0, Collections.singleton(s1), java.util.List.of(s0, s1));
  }

  @Test
  void twoComponentProductPartitionsAlphabetCorrectly() {
    // A matches 'a'-'m', B matches 'h'-'z'. Overlap 'h'-'m' both accept; 'a'-'g' only A; 'n'-'z'
    // only B; outside 'a'-'z' both dead (no transition materialized).
    DFA a = singleRangeDfa('a', 'm');
    DFA b = singleRangeDfa('h', 'z');

    ProductDFA product = ProductDFA.build(new DFA[] {a, b});

    // start + 3 reachable sinks (both-dead outside a-z is never materialized)
    assertEquals(4, product.getStateCount());

    ProductDFA.ProductState start = product.getStartState();
    assertEquals(3, start.transitions.size());

    ProductDFA.ProductState both = transitionFor(start, 'h');
    assertTrue(both.componentAccepting[0]);
    assertTrue(both.componentAccepting[1]);
    assertFalse(both.isComponentDead(0));
    assertFalse(both.isComponentDead(1));

    ProductDFA.ProductState onlyA = transitionFor(start, 'a');
    assertTrue(onlyA.componentAccepting[0]);
    assertFalse(onlyA.componentAccepting[1]);
    assertTrue(onlyA.isComponentDead(1));

    ProductDFA.ProductState onlyB = transitionFor(start, 'n');
    assertFalse(onlyB.componentAccepting[0]);
    assertTrue(onlyB.componentAccepting[1]);
    assertTrue(onlyB.isComponentDead(0));

    // A character both components reject has no materialized transition (implicit dead, matching
    // generateDFATransitionSwitch's existing "no transition matched -> dead" semantics).
    assertEquals(null, transitionForOrNull(start, '5'));
  }

  private static ProductDFA.ProductState transitionFor(ProductDFA.ProductState from, char ch) {
    ProductDFA.ProductState result = transitionForOrNull(from, ch);
    assertTrue(result != null, "expected a transition for '" + ch + "'");
    return result;
  }

  private static ProductDFA.ProductState transitionForOrNull(
      ProductDFA.ProductState from, char ch) {
    for (Map.Entry<CharSet, ProductDFA.ProductState> entry : from.transitions.entrySet()) {
      if (entry.getKey().contains(ch)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
