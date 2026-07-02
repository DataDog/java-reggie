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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.automaton.GlushkovAutomaton;
import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CountingGlushkovRuntime#matchesBounded} (and the {@code matches} wrapper)
 * using real {@link GlushkovAutomaton} instances built from simple body patterns. The loop-back
 * edges (accept → initial) are added manually so that the runtime can count full iterations of the
 * body across an input.
 */
public class CountingGlushkovRuntimeTest {

  /**
   * Build a Glushkov automaton for {@code bodyPattern}, add loop-back edges (each accept position
   * fans back to {@code initial}), and return a {@link CG} wrapper ready to call {@code
   * matchesBounded}.
   */
  private static CG cg(String bodyPattern, int counterMin, int counterMax) {
    try {
      NFA nfa = new ThompsonBuilder().build(new RegexParser().parse(bodyPattern), 0);
      GlushkovAutomaton g = GlushkovAutomaton.from(nfa);
      assertNotNull(g, "body must be Glushkov-eligible: " + bodyPattern);

      // Build follow-with-loopback: accept positions fan back to g.initial.
      long[] followLB = new long[g.follow.length];
      for (int p = 0; p < g.follow.length; p++) {
        followLB[p] = g.follow[p];
        if ((g.accept & (1L << p)) != 0L) {
          followLB[p] |= g.initial;
        }
      }
      return new CG(g, followLB, counterMin, counterMax);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** Thin adapter that calls {@link CountingGlushkovRuntime#matchesBounded} with body params. */
  private record CG(GlushkovAutomaton g, long[] followLB, int counterMin, int counterMax) {
    boolean matches(String input) {
      return CountingGlushkovRuntime.matchesBounded(
          input,
          0,
          input.length(),
          g.initial,
          g.accept,
          g.nullable,
          counterMin,
          counterMax,
          followLB,
          g.asciiClasses,
          g.rangeStarts,
          g.rangeEnds,
          g.rangeClasses,
          g.entry);
    }
  }

  // -------------------------------------------------------------------------
  // (?:ab){3}
  // -------------------------------------------------------------------------

  @Test
  void ab_exact3_matches_ababab() {
    CG cg = cg("ab", 3, 3);
    assertTrue(cg.matches("ababab"), "(?:ab){3} must match \"ababab\"");
  }

  @Test
  void ab_exact3_rejects_abab() {
    CG cg = cg("ab", 3, 3);
    assertFalse(cg.matches("abab"), "(?:ab){3} must not match \"abab\" (only 2 iterations)");
  }

  @Test
  void ab_exact3_rejects_abababab() {
    CG cg = cg("ab", 3, 3);
    assertFalse(cg.matches("abababab"), "(?:ab){3} must not match 4 repetitions");
  }

  // -------------------------------------------------------------------------
  // (?:ab){2,4}
  // -------------------------------------------------------------------------

  @Test
  void ab_2to4_matches_abab() {
    CG cg = cg("ab", 2, 4);
    assertTrue(cg.matches("abab"), "(?:ab){2,4} must match 2 repetitions");
  }

  @Test
  void ab_2to4_matches_ababab() {
    CG cg = cg("ab", 2, 4);
    assertTrue(cg.matches("ababab"), "(?:ab){2,4} must match 3 repetitions");
  }

  @Test
  void ab_2to4_matches_abababab() {
    CG cg = cg("ab", 2, 4);
    assertTrue(cg.matches("abababab"), "(?:ab){2,4} must match 4 repetitions");
  }

  @Test
  void ab_2to4_rejects_ab() {
    CG cg = cg("ab", 2, 4);
    assertFalse(cg.matches("ab"), "(?:ab){2,4} must not match 1 repetition");
  }

  @Test
  void ab_2to4_rejects_abababababab() {
    CG cg = cg("ab", 2, 4);
    assertFalse(cg.matches("abababababab"), "(?:ab){2,4} must not match 6 repetitions");
  }

  // -------------------------------------------------------------------------
  // \d{11}  (eleven-digit body repeated once is simply 11 digits)
  // -------------------------------------------------------------------------

  @Test
  void digit_exact11_matches_11digits() {
    // Body is a single \d, repeated 11 times by the counter.
    CG cg = cg("\\d", 11, 11);
    assertTrue(cg.matches("12345678901"), "\\d{11} must match 11 digits");
  }

  @Test
  void digit_exact11_rejects_10digits() {
    CG cg = cg("\\d", 11, 11);
    assertFalse(cg.matches("1234567890"), "\\d{11} must not match 10 digits");
  }

  @Test
  void digit_exact11_rejects_12digits() {
    CG cg = cg("\\d", 11, 11);
    assertFalse(cg.matches("123456789012"), "\\d{11} must not match 12 digits");
  }

  // -------------------------------------------------------------------------
  // edge cases
  // -------------------------------------------------------------------------

  @Test
  void ab_1to1_matches_ab() {
    CG cg = cg("ab", 1, 1);
    assertTrue(cg.matches("ab"), "(?:ab){1} must match \"ab\"");
  }

  @Test
  void ab_1to1_rejects_abab() {
    CG cg = cg("ab", 1, 1);
    assertFalse(cg.matches("abab"), "(?:ab){1} must not match \"abab\"");
  }

  @Test
  void ab_1to1_rejects_empty() {
    CG cg = cg("ab", 1, 1);
    assertFalse(cg.matches(""), "(?:ab){1} must not match empty string");
  }
}
