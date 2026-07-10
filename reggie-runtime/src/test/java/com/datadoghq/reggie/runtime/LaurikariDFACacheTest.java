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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadoghq.reggie.codegen.automaton.NFA;
import com.datadoghq.reggie.codegen.automaton.ThompsonBuilder;
import com.datadoghq.reggie.codegen.parsing.RegexParser;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LaurikariDFACache}, the Phase-0 Laurikari-style localization spike that
 * recovers the exact leftmost start of the first completable match in a single left-to-right scan
 * by tracking a per-candidate "age" register through DFA-state interning (see {@link
 * LaurikariNfaStep} and {@link LaurikariStateSetKey} for what "age" means and why it is used
 * instead of an absolute start position).
 *
 * <p>{@link LaurikariDFACache}, {@link LaurikariNfaStep}, {@link LaurikariStepResult}, and {@link
 * LaurikariStateSetKey} are all package-private with no public entry point, but since this test
 * lives in the same package it can use them directly (unlike {@code RejectDfaFactoryTest}, which
 * needs reflection to reach {@code RejectDfaFactory}'s own package-private {@code build} method).
 *
 * <p>This test builds its own {@link LaurikariNfaStep} driver (closure + consuming-transition +
 * merge, over the raw {@link NFA} built by {@link ThompsonBuilder}) rather than depend on any
 * production wiring point, since Phase 0 does not have one yet: see the class doc of {@link
 * LaurikariNfaStep} for the exact algorithm this driver implements. This is legitimate test-only
 * duplication of the production algorithm — the real matcher-construction wiring is a later phase's
 * concern.
 */
class LaurikariDFACacheTest {

  // --- Test-only LaurikariNfaStep driver -------------------------------------------------------
  //
  // Mirrors RejectDfaFactory's closure()/transitionTargets() shape (including its "cross every
  // anchor state as epsilon" over-approximation — anchor eligibility is a later phase's concern,
  // not Phase 0's), but threads a per-state age register through closure and merge instead of
  // doing an unconditional attribution-erasing union.

  private static NFA nfa(String pattern, int groupCount) throws Exception {
    return new ThompsonBuilder().build(new RegexParser().parse(pattern), groupCount);
  }

  private static NFA.NFAState[] statesById(NFA nfa) {
    NFA.NFAState[] arr = new NFA.NFAState[nfa.getStates().size()];
    for (NFA.NFAState s : nfa.getStates()) {
      arr[s.id] = s;
    }
    return arr;
  }

  /**
   * Epsilon-closure of {@code (seedStates[i], seedAges[i])}, crossing every anchor state as epsilon
   * exactly like {@code RejectDfaFactory.closure()} (same sound-over-approximation reasoning: Phase
   * 0 is a localization spike, anchor eligibility is deferred). When two paths reach the same state
   * with different ages, the larger age wins (older candidate = more leftmost), matching {@link
   * LaurikariNfaStep}'s tie-break rule. Ages only ever increase while propagating (epsilon consumes
   * no character), so a monotone worklist relaxation (rather than a single visited-once DFS) is
   * used to let a later, larger-age arrival re-open already-visited states.
   *
   * @return {@code {states, ages}}, both sorted ascending by state id (parallel arrays)
   */
  private static int[][] closureWithAges(
      NFA.NFAState[] statesById, int stateCount, int[] seedStates, int[] seedAges) {
    int[] ages = new int[stateCount];
    Arrays.fill(ages, -1);
    Deque<Integer> worklist = new ArrayDeque<>();
    for (int i = 0; i < seedStates.length; i++) {
      int id = seedStates[i];
      if (seedAges[i] > ages[id]) {
        ages[id] = seedAges[i];
        worklist.push(id);
      }
    }
    while (!worklist.isEmpty()) {
      int id = worklist.pop();
      int age = ages[id];
      for (NFA.NFAState e : statesById[id].getEpsilonTransitions()) {
        if (age > ages[e.id]) {
          ages[e.id] = age;
          worklist.push(e.id);
        }
      }
    }
    return denseToSeed(ages, stateCount);
  }

  /** Converts a dense per-id age array ({@code -1} = absent) to sorted parallel (states, ages). */
  private static int[][] denseToSeed(int[] denseAges, int stateCount) {
    int count = 0;
    for (int v : denseAges) {
      if (v >= 0) count++;
    }
    int[] states = new int[count];
    int[] ages = new int[count];
    int oi = 0;
    for (int id = 0; id < stateCount; id++) {
      if (denseAges[id] >= 0) {
        states[oi] = id;
        ages[oi] = denseAges[id];
        oi++;
      }
    }
    return new int[][] {states, ages};
  }

  /**
   * Builds the {@link LaurikariNfaStep} implementing Phase 0's step function: (1) consuming
   * transitions with age+1, (2) closure of the consuming targets, (3) a separate fresh
   * self-anchoring closure({@code startId}) at age 0, and (4) a larger-age-wins merge of (2) and
   * (3) — see {@link LaurikariNfaStep}'s class javadoc for the full rationale.
   */
  private static LaurikariNfaStep laurikariStep(NFA nfa) {
    NFA.NFAState[] statesById = statesById(nfa);
    int stateCount = statesById.length;
    int startId = nfa.getStartState().id;
    return (curStates, curRegs, c) -> {
      int[] rawAges = new int[stateCount];
      Arrays.fill(rawAges, -1);
      for (int i = 0; i < curStates.length; i++) {
        NFA.NFAState s = statesById[curStates[i]];
        int age = curRegs[i][0];
        for (NFA.Transition t : s.getTransitions()) {
          if (t.chars.contains((char) c)) {
            int cand = age + 1;
            if (cand > rawAges[t.target.id]) rawAges[t.target.id] = cand;
          }
        }
      }
      int[][] consumedSeed = denseToSeed(rawAges, stateCount);
      int[][] consumedClosed =
          closureWithAges(statesById, stateCount, consumedSeed[0], consumedSeed[1]);
      int[][] freshClosed =
          closureWithAges(statesById, stateCount, new int[] {startId}, new int[] {0});

      int[] merged = new int[stateCount];
      Arrays.fill(merged, -1);
      for (int i = 0; i < consumedClosed[0].length; i++) {
        merged[consumedClosed[0][i]] = consumedClosed[1][i];
      }
      for (int i = 0; i < freshClosed[0].length; i++) {
        int id = freshClosed[0][i];
        int age = freshClosed[1][i];
        if (age > merged[id]) merged[id] = age;
      }
      int[][] out = denseToSeed(merged, stateCount);
      int[] outStates = out[0];
      int[] outAges = out[1];
      int[][] outRegs = new int[outStates.length][];
      for (int i = 0; i < outStates.length; i++) {
        outRegs[i] = new int[] {outAges[i]};
      }
      return new LaurikariStepResult(outStates, outRegs);
    };
  }

  /** {@link LaurikariDFACache} + its driving {@link LaurikariNfaStep}, built for one pattern. */
  private static final class Built {
    final LaurikariDFACache cache;
    final LaurikariNfaStep step;

    Built(LaurikariDFACache cache, LaurikariNfaStep step) {
      this.cache = cache;
      this.step = step;
    }
  }

  private static Built build(String pattern, int groupCount) throws Exception {
    NFA nfa = nfa(pattern, groupCount);
    NFA.NFAState[] statesById = statesById(nfa);
    int stateCount = statesById.length;
    int startId = nfa.getStartState().id;

    // Seed state set for LaurikariDFACache's state 0: plain closure(startId) at age 0 — the
    // cache's own constructor re-derives an all-zero age array for it, matching how
    // LazyDFACache's constructor seeds nfaStateSets[0] from startStateSet.
    int[][] startClosure =
        closureWithAges(statesById, stateCount, new int[] {startId}, new int[] {0});

    int[] acceptIds = new int[nfa.getAcceptStates().size()];
    int ai = 0;
    for (NFA.NFAState s : nfa.getAcceptStates()) {
      acceptIds[ai++] = s.id;
    }

    int[] startStates = startClosure[0];
    int[] startAges = startClosure[1];
    int[][] startRegs = new int[startStates.length][];
    for (int i = 0; i < startStates.length; i++) {
      startRegs[i] = new int[] {startAges[i]};
    }

    LaurikariDFACache cache = new LaurikariDFACache(startStates, startRegs, acceptIds);
    return new Built(cache, laurikariStep(nfa));
  }

  // --- (a) Basic sanity: exact leftmost start for simple literal / multi-leading-char patterns --

  @Test
  void simpleLiteral_findsExactStart_orMinusOneWhenAbsent() throws Exception {
    Built b = build("abc", 0);

    // "abc" appears starting at index 3.
    assertEquals(3, b.cache.findLeftmostStart("xxxabcxxx", 0, b.step));
    // Not present at all.
    assertEquals(-1, b.cache.findLeftmostStart("xxxxxxxxx", 0, b.step));
    // Overlaps the literal's own characters but never the full sequence.
    assertEquals(-1, b.cache.findLeftmostStart("cabxbca", 0, b.step));
  }

  @Test
  void alternation_withMultipleLeadingChars_findsExactStart_orMinusOneWhenNeitherBranchMatches()
      throws Exception {
    // cat|dog: two disjoint leading characters, exercising the merge across more than one live
    // NFA position at once (single-literal "abc" above only ever tracks one lineage).
    Built b = build("cat|dog", 0);

    assertEquals(3, b.cache.findLeftmostStart("xxxcatxxx", 0, b.step));
    assertEquals(3, b.cache.findLeftmostStart("xxxdogxxx", 0, b.step));
    assertEquals(-1, b.cache.findLeftmostStart("xxxxxxxxx", 0, b.step));
    // "cad" shares a prefix with "cat" and a suffix with "dog" but is neither.
    assertEquals(-1, b.cache.findLeftmostStart("xxxcadxxx", 0, b.step));
  }

  // --- (b) Adversarial unbounded-width case: localization across content wider than any fixed --
  // --- window could handle, exercising the FALLBACK/frozen path once the cache hits DEFAULT_CAP --

  @Test
  void quotedString_unboundedWidthBody_findsExactOpeningQuote() throws Exception {
    // '(?:''|[^'])*' : a SQL-style quoted string (simplified standalone version of SQL_ANSI's
    // quoted-string branch) whose body is unbounded width. Once the opening quote at index 3 is
    // consumed, the single surviving candidate's age grows by 1 on every subsequent 'a' — so
    // after enough characters this genuinely defeats the "same (subset, age-vector) pair recurs"
    // boundedness LaurikariNfaStep's class javadoc describes for typical bounded-loop patterns:
    // here the interned state space *does* grow with input length, DEFAULT_CAP (4096) is hit
    // partway through the run of 'a's, and LaurikariDFACache.findLeftmostStart must fall through
    // to nfaFallbackFindLeftmostStart to finish the scan. This test's whole point is to prove that
    // fallback path is exactly as correct as the cached path, not merely that the cached path
    // works for short inputs.
    Built b = build("'(?:''|[^'])*'", 0);

    String input = "xxx'" + "a".repeat(5000) + "'yyy";
    assertEquals(3, b.cache.findLeftmostStart(input, 0, b.step));
  }

  // --- (c) Genuine merge-tie case ------------------------------------------------------------

  @Test
  void repetition_selfAnchoringRestart_neverOverridesOlderSurvivingCandidate() throws Exception {
    // a+b built by ThompsonBuilder (traced and verified, not assumed) has exactly these states:
    //   0 --'a'--> 1 --eps--> {0, 2}
    //   2 --'b'--> 3 --eps--> 4 (accept)
    // i.e. state 1's epsilon fans out to BOTH state 0 (loop back for another 'a') and state 2
    // (proceed to try 'b'), and state 0 is *also* the pattern's start state, so it is always
    // re-seeded at age 0 by step 3's fresh self-anchoring closure(startId).
    //
    // Scanning "aaab" from position 0:
    //   pos0 'a': dfa1 = {0,1,2}@{1,1,1}   (closure of consuming-target 1@1 reaches 0@1 and 2@1)
    //   pos1 'a': dfa2 = {0,1,2}@{2,2,2}
    //   pos2 'a': dfa3 = {0,1,2}@{3,3,3}
    //   pos3 'b': dfa4 = {0,3,4}@{0,4,4}  -- accepting (state 4), winning age 4 -> start = 0
    //
    // At every one of those first three steps, state 0 is reached by TWO different-aged
    // candidates in the same LaurikariNfaStep.apply call: the loop-back copy carried through
    // closure of the consuming step (age k, k = pos+1: the original candidate that started
    // consuming 'a' at position 0 and has survived k steps) AND the fresh self-anchoring
    // re-injection of closure(startId) at age 0 (a brand-new candidate about to attempt starting
    // its own match at the next position). This is the genuine collision: same target NFA state
    // (id 0), reached via two different paths with two different ages in a single merge. The
    // "keep the larger age" rule (k > 0 for k >= 1) means the fresh restart never overwrites the
    // older, more-leftmost lineage's age at state 0 — which is exactly what preserves the correct
    // start-position bookkeeping all the way to the eventual accept at position 3, where the
    // winning age (4) recovers start = (3+1)-4 = 0, not some later restart's (wrong) start.
    //
    // Fresher restarts injected at positions 1, 2, or 3 are genuinely dead ends for reaching the
    // 'b'-consuming state 2: they would need to consume an 'a' first (state0 --'a'--> state1), but
    // by the time any of them could reach state1 and loop to state2, the input's only 'b' (at
    // position 3) has already been consumed by the original, older lineage — so state 2/3/4 are
    // never subject to the same double-arrival collision state 0 sees; only state 0 is contested,
    // and only because it is simultaneously the loop-back epsilon target and the start state.
    Built b = build("a+b", 0);

    assertEquals(0, b.cache.findLeftmostStart("aaab", 0, b.step));
  }

  // --- (d) Genuine combinatorial stress: many candidates of different ages colliding under -----
  // --- branchy input, as distinct from (b)'s single-lineage monotonic-age growth. Measured -----
  // --- growth (via a throwaway sweep, not asserted here): stateCount ~= 2*cycleUnits - 1, i.e. ---
  // --- LINEAR in input length, reaching DEFAULT_CAP (4096) at ~2050 cycle units -- confirming ---
  // --- that Phase 0's age-based cache does NOT generally bound state growth by NFA structure ---
  // --- alone; it only stays small on inputs like SQL_ANSI/LONG's benign filler because that ----
  // --- filler keeps almost nothing alive. This test documents the real failure mode rather than -
  // --- pretending it doesn't happen: FALLBACK is expected and exercised, and the assertion is ---
  // --- that the fallback path still localizes correctly despite it, not that the cap is avoided.

  @Test
  void denseAlternation_manyOverlappingCandidateAges_hitsCapButFallbackStaysCorrect()
      throws Exception {
    // Six disjoint 3-char branches over a 2-letter alphabet, looped, requiring a trailing 'z' to
    // accept. Every position in an a/b run is a fresh self-anchoring restart (step 3 of
    // LaurikariNfaStep's merge), so scanning a long a/b string keeps many candidates that started
    // at different offsets simultaneously alive at different ages as they race through the shared
    // alternation -- unlike quotedString_unboundedWidthBody's test above, where only ONE lineage
    // ever survives past the opening quote, here many *different* (subset, age-vector) combinations
    // are live across the scan, and the growth is driven by that diversity, not one candidate's
    // age.
    Built b = build("(?:aab|aba|baa|abb|bab|bba)*z", 0);

    // 3000 cycle units (well past the ~2050-unit point where the sweep above hits DEFAULT_CAP),
    // then a single trailing 'z' so the only way to reach it is through
    // nfaFallbackFindLeftmostStart
    // once the cache has frozen.
    StringBuilder sb = new StringBuilder();
    String[] cycle = {"a", "b", "ab", "ba", "aab", "bba"};
    for (int i = 0; i < 3000; i++) {
      sb.append(cycle[i % cycle.length]);
    }
    sb.append('z');
    String denseInput = sb.toString();

    java.util.regex.Matcher oracle =
        java.util.regex.Pattern.compile("(?:aab|aba|baa|abb|bab|bba)*z").matcher(denseInput);
    assertTrue(oracle.find(), "JDK oracle must find a match (the trailing 'z' alone always does)");
    int expectedStart = oracle.start();

    assertEquals(expectedStart, b.cache.findLeftmostStart(denseInput, 0, b.step));

    int stateCount = b.cache.stateCount();
    assertEquals(
        LaurikariDFACache.DEFAULT_CAP,
        stateCount,
        "expected this dense branchy input to hit DEFAULT_CAP (confirming real, input-length-driven"
            + " growth, not a fixed NFA-structure bound) -- if this fails because stateCount is now"
            + " LOWER, that's a genuine improvement worth re-deriving this test's sweep for; if"
            + " it's HIGHER, the cache silently grew past its own advertised cap.");
  }
}
