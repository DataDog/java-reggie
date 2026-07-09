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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RejectDfaFactory}, the shared over-approximating "reject DFA" builder used
 * by {@link BitStateMatcher} and {@link PikeVMMatcher} as a general (multi-leading-char)
 * fast-reject filter. Invoked via reflection since the class and its {@code build}/{@code Bundle}
 * members are package-private with no public entry point of their own.
 *
 * <p>Covers the three rejection guards ({@code build} returning {@code null}) and the actual
 * filtering behavior of a successfully-built {@link RejectDfaFactory.Bundle} — soundness (a real
 * match must never be rejected) and usefulness (a genuine no-match must be rejected), including
 * across the anchor-crossing-as-epsilon over-approximation the class doc describes.
 */
class RejectDfaFactoryTest {

  private static final Class<?> FACTORY_CLS;
  private static final Method BUILD;

  static {
    try {
      FACTORY_CLS = Class.forName("com.datadoghq.reggie.runtime.RejectDfaFactory");
      BUILD = FACTORY_CLS.getDeclaredMethod("build", NFA.class);
      BUILD.setAccessible(true);
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    return new ThompsonBuilder().build(new RegexParser().parse(pattern), groupCount);
  }

  private static Object build(String pattern, int groupCount) throws Exception {
    return BUILD.invoke(null, nfa(pattern, groupCount));
  }

  /** Unwraps a non-null {@code Bundle} into its {@code dfa}/{@code step} fields via reflection. */
  private static Object[] unwrap(Object bundle) throws Exception {
    Field dfaField = bundle.getClass().getDeclaredField("dfa");
    Field stepField = bundle.getClass().getDeclaredField("step");
    dfaField.setAccessible(true);
    stepField.setAccessible(true);
    return new Object[] {dfaField.get(bundle), stepField.get(bundle)};
  }

  private static int findFrom(Object bundle, String input, int start) throws Exception {
    Object[] parts = unwrap(bundle);
    LazyDFACache dfa = (LazyDFACache) parts[0];
    NfaStep step = (NfaStep) parts[1];
    return dfa.findFrom(input, start, step);
  }

  // --- Rejection guards (build() returns null) -----------------------------------------------

  @Test
  void returnsNull_whenNfaHasLookaheadAssertion() throws Exception {
    // a(?=b)c: the lookahead assertion state's over-approximation (crossing it as an
    // unconditional epsilon) would be unsound — build() must decline entirely.
    assertNull(build("a(?=b)c", 0));
  }

  @Test
  void returnsNull_whenNfaHasBackreference() throws Exception {
    // (a)\1: the backref-check state can't be soundly over-approximated as free epsilon either.
    assertNull(build("(a)\\1", 1));
  }

  @Test
  void returnsNull_whenOverApproximationMatchesEmpty() throws Exception {
    // a*: the body is nullable (matches "" directly, independent of any anchor-crossing), so the
    // filter would accept at every position and be useless as a filter.
    assertNull(build("a*", 0));
  }

  // --- Successful builds: soundness and usefulness -------------------------------------------

  @Test
  void simpleLiteral_rejectsInputWithoutTheLiteral_acceptsInputWithIt() throws Exception {
    Object bundle = build("abc", 0);
    assertNotNull(bundle);

    // Sound: "abc" genuinely appears (soundness requires never rejecting a real match).
    assertTrue(findFrom(bundle, "xxxabcxxx", 0) >= 0);
    // Useful: none of the pattern's required characters appear at all.
    assertEquals(-1, findFrom(bundle, "xxxxxxxxx", 0));
    // Useful: some overlap with the literal's characters, but never the full sequence.
    assertEquals(-1, findFrom(bundle, "cabxbca", 0));
  }

  @Test
  void alternation_withMultipleLeadingChars_rejectsInputMatchingNeitherBranch() throws Exception {
    // cat|dog: two disjoint leading characters, exercising transitionTargets()/union() across
    // more than one live NFA position at once (single-literal "abc" above only ever tracks one).
    Object bundle = build("cat|dog", 0);
    assertNotNull(bundle);

    assertTrue(findFrom(bundle, "xxxcatxxx", 0) >= 0);
    assertTrue(findFrom(bundle, "xxxdogxxx", 0) >= 0);
    assertEquals(-1, findFrom(bundle, "xxxxxxxxx", 0));
    // "cad" shares a prefix with "cat" and a suffix with "dog" but is neither.
    assertEquals(-1, findFrom(bundle, "xxxcadxxx", 0));
  }

  @Test
  void startAnchoredPattern_stillFiltersMidStringAsUnanchoredSlidingFilter() throws Exception {
    // ^abc: build() does not reject patterns for having anchors (only for assertions/backrefs/
    // nullability) — the class doc's "crosses every anchor as epsilon" is exactly what makes this
    // safe to use as a sliding filter regardless of the real pattern's anchoring. The real anchor
    // constraint is enforced elsewhere (BitStateMatcher/PikeVMMatcher's own search); this filter
    // only needs to never reject when the literal content is present, anywhere.
    Object bundle = build("^abc", 0);
    assertNotNull(bundle);

    assertTrue(findFrom(bundle, "xxxabcxxx", 0) >= 0, "must not reject — 'abc' is present");
    assertEquals(-1, findFrom(bundle, "xxxxxxxxx", 0));
  }

  @Test
  void repetition_withSelfLoopingState_deduplicatesConvergingTransitions() throws Exception {
    // a+b: the self-anchoring closure() re-admits startAll on every step, so the active NFA
    // position set can contain both a fresh restart copy and a mid-loop copy of the 'a'-consuming
    // state at once; both transition to the same target on 'a', exercising transitionTargets()'s
    // and closure()'s own seen/inSet dedup guards (not exercised by the single-active-position
    // patterns above).
    Object bundle = build("a+b", 0);
    assertNotNull(bundle);
    assertTrue(findFrom(bundle, "xxxaaabxxx", 0) >= 0);
    assertEquals(-1, findFrom(bundle, "xxxxxxxxxx", 0));
  }

  @Test
  void findFrom_respectsStartOffset_rejectsWhenLiteralOnlyAppearsBeforeStart() throws Exception {
    Object bundle = build("abc", 0);
    assertNotNull(bundle);

    String input = "abcxxxxxxx"; // "abc" only at [0,3)
    assertTrue(findFrom(bundle, input, 0) >= 0, "literal present from position 0");
    assertEquals(
        -1, findFrom(bundle, input, 3), "starting the scan after the only occurrence must reject");
  }
}
